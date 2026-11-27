package com.veritas.localresearch.llm

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.coroutineContext

/**
 * Cross-Encoder-Reranker (cross-encoder/mmarco-mMiniLMv2-L12-H384-v1, mehrsprachig inkl. Deutsch):
 * bewertet Frage und Abschnitt gemeinsam statt getrennt wie das Embedding und sortiert die Kandidaten
 * der hybriden Suche neu. Paarformat von XLM-R: <s> Frage </s></s> Abschnitt </s>, Ausgabe ein Logit je Paar.
 */
class CrossEncoderReranker {

    private val lock = ReentrantLock()
    private val closed = AtomicBoolean(false)
    @Volatile private var session: OrtSession? = null
    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var tokenizer: UnigramTokenizer? = null

    val isReady: Boolean get() = !closed.get() && session != null && tokenizer != null

    suspend fun load(modelFile: File, tokenizerJson: File): Boolean = withContext(Dispatchers.IO) {
        if (!modelFile.exists() || !tokenizerJson.exists()) return@withContext false
        lock.withLock {
            if (closed.get()) return@withLock false
            unloadLocked()
            try {
                val e = OrtEnvironment.getEnvironment()
                runCatching { e.setTelemetry(false) }
                val s = OrtSession.SessionOptions().use { opts ->
                    opts.setIntraOpNumThreads((Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4))
                    opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    e.createSession(modelFile.absolutePath, opts)
                }
                env = e
                session = s
                tokenizer = UnigramTokenizer(tokenizerJson)
                true
            } catch (_: Throwable) {
                unloadLocked()
                false
            }
        }
    }

    /** Logits je Text (hoeher = relevanter), in der Reihenfolge von [passages]; null, wenn nicht geladen. */
    suspend fun score(query: String, passages: List<String>, batchSize: Int = 8): FloatArray? =
        withContext(Dispatchers.Default) {
            val tok = tokenizer ?: return@withContext null
            if (!isReady) return@withContext null
            val q = tok.encode(clean(query)).take(MAX_QUERY_TOKENS)
            val pairs = passages.map { p ->
                val d = tok.encode(clean(p)).take(MAX_LEN - q.size - 4)
                LongArray(q.size + d.size + 4).also { ids ->
                    var i = 0
                    ids[i++] = BOS
                    for (t in q) ids[i++] = t.toLong()
                    ids[i++] = EOS; ids[i++] = EOS
                    for (t in d) ids[i++] = t.toLong()
                    ids[i] = EOS
                }
            }
            val out = FloatArray(passages.size)
            var off = 0
            while (off < pairs.size) {
                coroutineContext.ensureActive()
                val end = minOf(pairs.size, off + batchSize)
                val r = runBatch(pairs.subList(off, end)) ?: return@withContext null
                r.copyInto(out, off)
                off = end
            }
            out
        }

    private fun runBatch(batch: List<LongArray>): FloatArray? = lock.withLock {
        val s = session ?: return null
        val e = env ?: return null
        val maxLen = batch.maxOf { it.size }
        val ids = LongArray(batch.size * maxLen) { PAD }
        val mask = LongArray(batch.size * maxLen)
        for ((b, seq) in batch.withIndex()) {
            System.arraycopy(seq, 0, ids, b * maxLen, seq.size)
            for (i in seq.indices) mask[b * maxLen + i] = 1L
        }
        val shape = longArrayOf(batch.size.toLong(), maxLen.toLong())
        val tIds = OnnxTensor.createTensor(e, LongBuffer.wrap(ids), shape)
        val tMask = OnnxTensor.createTensor(e, LongBuffer.wrap(mask), shape)
        val tTypes = OnnxTensor.createTensor(e, LongBuffer.wrap(LongArray(ids.size)), shape)
        try {
            val inputs = buildMap {
                put("input_ids", tIds)
                put("attention_mask", tMask)
                if ("token_type_ids" in s.inputNames) put("token_type_ids", tTypes)
            }
            s.run(inputs).use { res ->
                @Suppress("UNCHECKED_CAST")
                val logits = res[0].value as Array<FloatArray>
                FloatArray(batch.size) { logits[it][0] }
            }
        } catch (_: Throwable) {
            null
        } finally {
            tIds.close(); tMask.close(); tTypes.close()
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        lock.withLock { unloadLocked() }
    }

    private fun unloadLocked() {
        runCatching { session?.close() }
        session = null
        tokenizer = null
    }

    private companion object {
        const val BOS = 0L
        const val PAD = 1L
        const val EOS = 2L
        const val MAX_LEN = 320
        const val MAX_QUERY_TOKENS = 48
        val SPACES = Regex("\\s+")
        fun clean(s: String) = s.replace(SPACES, " ").trim()
    }
}
