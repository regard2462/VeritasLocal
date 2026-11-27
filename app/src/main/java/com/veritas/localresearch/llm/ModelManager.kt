package com.veritas.localresearch.llm

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class ModelPreset(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val approxSizeMb: Int,
    val minRamGb: Int,
    val isEmbedding: Boolean = false,
    val extraVocabUrl: String? = null,
    /** Dateiname des Tokenizers (vocab.txt fuer WordPiece, tokenizer.json fuer SentencePiece). */
    val vocabFileName: String = "minilm_vocab.txt",
    val isReranker: Boolean = false
)

class ModelManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }

    val presets: List<ModelPreset> = listOf(
        ModelPreset(
            id = "qwen3-0.6b",
            displayName = "Qwen3 0.6B (Q4_K_M) • Sehr schnell, weniger genau, ab 3 GB RAM",
            fileName = "Qwen3-0.6B-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf",
            approxSizeMb = 400,
            minRamGb = 3
        ),
        ModelPreset(
            id = "qwen3-1.7b",
            displayName = "Qwen3 1.7B (Q4_K_M) • Empfohlen ab 6 GB RAM",
            fileName = "Qwen3-1.7B-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
            approxSizeMb = 1110,
            minRamGb = 6
        ),
        ModelPreset(
            id = "qwen3-4b",
            displayName = "Qwen3 4B (Q4_K_M) • Hohe Präzision ab 8 GB RAM",
            fileName = "Qwen3-4B-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf",
            approxSizeMb = 2500,
            minRamGb = 8
        ),
        ModelPreset(
            id = "e5-small-onnx",
            displayName = "multilingual-e5-small (ONNX Quantized) • Mehrsprachige Embeddings, empfohlen",
            fileName = "multilingual_e5_small_qint8.onnx",
            downloadUrl = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/onnx/model_quantized.onnx",
            approxSizeMb = 135,
            minRamGb = 4,
            isEmbedding = true,
            extraVocabUrl = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/tokenizer.json",
            vocabFileName = "e5_tokenizer.json"
        ),
        ModelPreset(
            id = "mmarco-reranker-onnx",
            displayName = "mmarco-mMiniLMv2-L12 (ONNX int8) • Reranker, mehrsprachig",
            fileName = "mmarco_reranker_qint8.onnx",
            downloadUrl = "https://huggingface.co/cross-encoder/mmarco-mMiniLMv2-L12-H384-v1/resolve/main/onnx/model_qint8_arm64.onnx",
            approxSizeMb = 136,
            minRamGb = 4,
            extraVocabUrl = "https://huggingface.co/cross-encoder/mmarco-mMiniLMv2-L12-H384-v1/resolve/main/tokenizer.json",
            vocabFileName = "mmarco_tokenizer.json",
            isReranker = true
        ),
        ModelPreset(
            id = "minilm-onnx",
            displayName = "all-MiniLM-L6-v2 (ONNX Quantized) • Nur Englisch, klein",
            fileName = "minilm_l6_v2_qint8.onnx",
            downloadUrl = "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/onnx/model_quantized.onnx",
            approxSizeMb = 23,
            minRamGb = 4,
            isEmbedding = true,
            extraVocabUrl = "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/vocab.txt"
        )
    )

    fun getDeviceTotalRamGb(): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return ((info.totalMem / (1024.0 * 1024.0 * 1024.0)) + 0.5).toInt()
    }

    fun getFile(preset: ModelPreset): File = File(modelsDir, preset.fileName)
    fun getVocabFile(preset: ModelPreset): File = File(modelsDir, preset.vocabFileName)

    fun isPresetInstalled(preset: ModelPreset): Boolean {
        val mainOk = getFile(preset).let { it.exists() && it.length() > 0L }
        if (!mainOk) return false
        return if (preset.extraVocabUrl != null) {
            getVocabFile(preset).let { it.exists() && it.length() > 0L }
        } else {
            true
        }
    }

    fun listLocalGgufFiles(): List<File> =
        modelsDir.listFiles { f -> f.isFile && f.extension.equals("gguf", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

    suspend fun downloadPreset(
        preset: ModelPreset,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            // Vocab zuerst laden, damit niemals ein .onnx ohne zugehoeriges vocab.txt als installiert gilt.
            if (preset.extraVocabUrl != null) {
                downloadUrlToFile(preset.extraVocabUrl, getVocabFile(preset)) {}
            }
            val target = getFile(preset)
            downloadUrlToFile(preset.downloadUrl, target, onProgress)
            Result.success(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun importLocalGguf(uri: Uri, targetName: String): Result<File> = withContext(Dispatchers.IO) {
        val sanitizedBase = File(targetName).name
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .ifBlank { "imported_${System.currentTimeMillis()}.gguf" }
        val safeName = if (sanitizedBase.endsWith(".gguf", ignoreCase = true)) {
            sanitizedBase
        } else {
            "$sanitizedBase.gguf"
        }
        val dest = File(modelsDir, safeName)
        val tmp = File(modelsDir, "$safeName.part")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n == -1) break
                        out.write(buffer, 0, n)
                    }
                }
            } ?: error("Datei konnte nicht gelesen werden")
            moveFileAtomically(tmp, dest)
            Result.success(dest)
        } catch (e: CancellationException) {
            tmp.delete()
            throw e
        } catch (e: Exception) {
            tmp.delete()
            Result.failure(e)
        }
    }

    private suspend fun downloadUrlToFile(url: String, target: File, onProgress: (Float) -> Unit) {
        val tmp = File(target.parentFile, "${target.name}.part")
        val req = Request.Builder().url(url).build()
        val call = client.newCall(req)
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code} beim Download von $url")
                val body = resp.body ?: error("Leerer Response-Body")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    FileOutputStream(tmp).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var readTotal = 0L
                        var lastReported = 0f
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n == -1) break
                            out.write(buffer, 0, n)
                            readTotal += n
                            if (total > 0) {
                                val pct = (readTotal.toFloat() / total).coerceIn(0f, 1f)
                                if (pct - lastReported >= 0.01f || pct >= 1f) {
                                    lastReported = pct
                                    onProgress(pct)
                                }
                            }
                        }
                        if (total > 0 && readTotal != total) error("Download unvollständig ($readTotal von $total Bytes)")
                    }
                }
            }
            // Abgebrochene Verbindung: lieber Fehler als ein halbes Modell, das spaeter nicht laedt
            if (tmp.length() == 0L) error("Leere Datei von $url")
            moveFileAtomically(tmp, target)
            onProgress(1f)
        } catch (e: CancellationException) {
            call.cancel()
            tmp.delete()
            throw e
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    private fun moveFileAtomically(tmp: File, target: File) {
        if (target.exists() && !target.delete()) {
            error("Konnte bestehende Datei ${target.name} nicht ersetzen")
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}
