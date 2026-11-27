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
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

class OnnxEmbedder {

    private val lock = ReentrantLock()
    private val closed = AtomicBoolean(false)

    @Volatile
    private var env: OrtEnvironment? = null

    @Volatile
    private var session: OrtSession? = null

    @Volatile
    private var tokenizer: EmbeddingTokenizer? = null

    @Volatile
    private var usesE5Prefixes = false

    /** Kosinus-Schwelle fuer "gleiches Thema"; e5 liefert insgesamt hoehere Aehnlichkeiten als MiniLM. */
    @Volatile
    var sameTopicThreshold: Float = 0.74f
        private set

    val isReady: Boolean get() = !closed.get() && session != null && tokenizer != null

    suspend fun load(modelFile: File, vocabFile: File): Boolean = withContext(Dispatchers.IO) {
        if (closed.get() || !modelFile.exists() || !vocabFile.exists()) return@withContext false
        lock.withLock {
            if (closed.get()) return@withLock false
            closeInternalLocked()
            if (!modelFile.exists() || !vocabFile.exists()) return@withLock false
            try {
                val e = OrtEnvironment.getEnvironment()
                runCatching { e.setTelemetry(false) }
                val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
                val createdSession = OrtSession.SessionOptions().use { opts ->
                    opts.setIntraOpNumThreads(threads)
                    opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    e.createSession(modelFile.absolutePath, opts)
                }
                env = e
                session = createdSession
                // tokenizer.json = SentencePiece-Unigram (XLM-R, mehrsprachig), vocab.txt = WordPiece (BERT)
                val multilingual = vocabFile.name.endsWith(".json")
                tokenizer = if (multilingual) UnigramTokenizer(vocabFile) else WordPieceTokenizer(vocabFile)
                usesE5Prefixes = multilingual && modelFile.name.contains("e5", ignoreCase = true)
                sameTopicThreshold = if (usesE5Prefixes) 0.88f else 0.74f
                true
            } catch (_: Exception) {
                closeInternalLocked()
                false
            }
        }
    }

    suspend fun embed(text: String, isQuery: Boolean = true): FloatArray? =
        embedBatch(listOf(text), batchSize = 1, isQuery = isQuery).firstOrNull()

    suspend fun embedBatch(texts: List<String>, batchSize: Int = 16, isQuery: Boolean = false): List<FloatArray?> = withContext(Dispatchers.Default) {
        if (texts.isEmpty() || !isReady) return@withContext List(texts.size) { null }
        val results = arrayOfNulls<FloatArray>(texts.size)

        // Tokenisierung ausserhalb des Session-Locks fuer geringe Blockierzeit
        val tokSnapshot = tokenizer ?: return@withContext List(texts.size) { null }
        // e5 erwartet "query: " vor Suchfragen und "passage: " vor Quelltexten
        val prefix = if (!usesE5Prefixes) "" else if (isQuery) "query: " else "passage: "
        val encodedAll = texts.map { tokSnapshot.tokenize(prefix + it, maxLength = 256) }

        val safeBatch = batchSize.coerceAtLeast(1)
        var offset = 0
        while (offset < encodedAll.size) {
            coroutineContext.ensureActive()
            val end = (offset + safeBatch).coerceAtMost(encodedAll.size)
            val slice = encodedAll.subList(offset, end)
            val batchVectors = runBatchLocked(slice)
            for (i in batchVectors.indices) {
                results[offset + i] = batchVectors[i]
            }
            offset = end
        }
        results.toList()
    }

    private fun runBatchLocked(batch: List<TokenizedInput>): List<FloatArray?> {
        lock.withLock {
            if (closed.get()) return List(batch.size) { null }
            val s = session ?: return List(batch.size) { null }
            val e = env ?: return List(batch.size) { null }

            val bSize = batch.size
            val maxLen = batch.maxOf { it.inputIds.size }.coerceAtLeast(1)
            val total = bSize * maxLen

            val flatIds = LongArray(total)
            val flatMask = LongArray(total)
            val flatTypes = LongArray(total)

            for (b in 0 until bSize) {
                val item = batch[b]
                val base = b * maxLen
                System.arraycopy(item.inputIds, 0, flatIds, base, item.inputIds.size)
                System.arraycopy(item.attentionMask, 0, flatMask, base, item.attentionMask.size)
                System.arraycopy(item.tokenTypeIds, 0, flatTypes, base, item.tokenTypeIds.size)
            }

            val shape = longArrayOf(bSize.toLong(), maxLen.toLong())
            val inputIdsTensor = OnnxTensor.createTensor(e, LongBuffer.wrap(flatIds), shape)
            val maskTensor = OnnxTensor.createTensor(e, LongBuffer.wrap(flatMask), shape)
            val typeTensor = OnnxTensor.createTensor(e, LongBuffer.wrap(flatTypes), shape)

            return try {
                val inputs = buildMap<String, OnnxTensor> {
                    put("input_ids", inputIdsTensor)
                    put("attention_mask", maskTensor)
                    if ("token_type_ids" in s.inputNames) {
                        put("token_type_ids", typeTensor)
                    }
                }
                s.run(inputs).use { ortRes ->
                    when (val raw = ortRes[0].value) {
                        is Array<*> -> {
                            val first = raw.firstOrNull()
                            when (first) {
                                is Array<*> -> {
                                    @Suppress("UNCHECKED_CAST")
                                    val tensor3d = raw as Array<Array<FloatArray>>
                                    List(bSize) { b ->
                                        meanPoolAndNormalize(tensor3d[b], batch[b].attentionMask)
                                    }
                                }
                                is FloatArray -> {
                                    @Suppress("UNCHECKED_CAST")
                                    val tensor2d = raw as Array<FloatArray>
                                    List(bSize) { b -> l2Normalize(tensor2d[b].copyOf()) }
                                }
                                else -> List(bSize) { null }
                            }
                        }
                        else -> List(bSize) { null }
                    }
                }
            } catch (_: Exception) {
                List(bSize) { null }
            } finally {
                inputIdsTensor.close()
                maskTensor.close()
                typeTensor.close()
            }
        }
    }

    private fun meanPoolAndNormalize(tokens: Array<FloatArray>, mask: LongArray): FloatArray {
        val dim = tokens.firstOrNull()?.size ?: return FloatArray(0)
        val pooled = FloatArray(dim)
        var validTokens = 0f

        val limit = minOf(tokens.size, mask.size)
        for (i in 0 until limit) {
            if (mask[i] == 1L) {
                validTokens += 1f
                val vec = tokens[i]
                for (d in 0 until dim) pooled[d] += vec[d]
            }
        }
        if (validTokens > 0f) {
            for (d in 0 until dim) pooled[d] /= validTokens
        }
        return l2Normalize(pooled)
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        var normSq = 0f
        for (v in vec) normSq += v * v
        val norm = sqrt(normSq).coerceAtLeast(1e-9f)
        for (d in vec.indices) vec[d] /= norm
        return vec
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        thread(name = "onnx-cleanup", isDaemon = false) {
            lock.withLock {
                closeInternalLocked()
            }
        }
    }

    private fun closeInternalLocked() {
        runCatching { session?.close() }
        session = null
        tokenizer = null
    }

    companion object {
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size || a.isEmpty()) return 0f
            var dot = 0f
            for (i in a.indices) dot += a[i] * b[i]
            return dot // Bereits L2-normalisiert
        }
    }
}
