package com.veritas.localresearch.llm

import kotlinx.coroutines.Dispatchers
import com.veritas.localresearch.i18n.tr
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

interface TokenCallback {
    fun onToken(token: String)
}

/** QWEN_CHATML: ChatML mit abschaltbarem Denkblock (Qwen3, Qwen3.5). CHATML: ChatML ohne Denkblock (LFM2, andere). */
enum class ModelFamily { QWEN_CHATML, CHATML, GEMMA }

/**
 * Laedt veritas_llama. Android findet die Bibliotheken selbst. Auf dem Desktop liegen sie in [dir]; dort werden
 * ggml-base, ggml und llama vorher geladen, weil Windows abhaengige DLLs sonst nicht im selben Ordner sucht.
 */
private fun loadNativeLibraries(dir: String) {
    val isAndroid = System.getProperty("java.vendor").orEmpty().contains("Android", ignoreCase = true)
    if (isAndroid || dir.isBlank()) {
        System.loadLibrary("veritas_llama")
        return
    }
    val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    fun file(name: String) = if (windows) File(dir, "$name.dll") else File(dir, "lib$name.so")
    if (windows) {
        // C++-Laufzeit von Visual C++: liegt der Java-Laufzeit bei; vorher laden, damit llama.cpp sie findet
        val javaBin = File(System.getProperty("java.home").orEmpty(), "bin")
        for (crt in listOf("vcruntime140", "vcruntime140_1", "msvcp140")) {
            val f = File(javaBin, "$crt.dll").takeIf { it.exists() } ?: File(dir, "$crt.dll")
            if (f.exists()) runCatching { System.load(f.absolutePath) }
        }
    }
    for (name in listOf("ggml-base", "ggml", "llama", "veritas_llama")) {
        val f = file(name)
        if (f.exists()) System.load(f.absolutePath)
        else if (name == "veritas_llama") System.loadLibrary(name)
    }
}

class LlamaEngine(private val nativeLibDir: String) {

    // Bibliothek und ggml-CPU-Varianten erst beim ersten Laden initialisieren (IO-Thread), nicht im UI-Thread.
    @Volatile private var backendReady = false
    private fun ensureBackend() {
        if (backendReady) return
        synchronized(this) {
            if (backendReady) return
            loadNativeLibraries(nativeLibDir)
            nativeInitBackend(nativeLibDir)
            backendReady = true
        }
    }

    // Serialisiert alle nativen llama_context-Aufrufe (load, generate, free), um Use-After-Free zu verhindern.
    private val nativeLock = ReentrantLock()
    // Schuetzt nur das Lesen/Setzen von `handle` fuer lockfreies cancelGeneration().
    private val handleLock = Any()
    private val closed = AtomicBoolean(false)

    @Volatile
    private var handle: Long = 0L

    @Volatile
    var loadedModelName: String? = null
        private set

    @Volatile
    private var modelFamily: ModelFamily = ModelFamily.QWEN_CHATML

    val isLoaded: Boolean get() = handle != 0L && !closed.get()

    /** Modell kennt einen Denkmodus (Vorlage mit think-Tags): der wird fuer jede Anfrage abgeschaltet. */
    @Volatile
    private var thinks = false

    /** Grafikkarte nutzen, wenn ein Vulkan-Geraet da ist (nur Desktop; Android rechnet auf dem Prozessor). */
    @Volatile
    var useGpu: Boolean = true

    /** Grafikkarte, auf der das geladene Modell rechnet; leer = Prozessor. */
    @Volatile
    var gpuInUse: String = ""
        private set

    /** Name der verfuegbaren Grafikkarte (Vulkan), leer wenn keine da ist. */
    fun gpuName(): String { ensureBackend(); return runCatching { nativeGpuName() }.getOrDefault("") }

    suspend fun loadModel(file: File, nCtx: Int = 4096): Boolean = withContext(Dispatchers.IO) {
        if (closed.get() || !file.exists()) return@withContext false
        ensureBackend()
        cancelGeneration()
        nativeLock.withLock {
            if (closed.get()) return@withLock false
            freeHandleLocked()
            if (!file.exists()) return@withLock false

            val cores = Runtime.getRuntime().availableProcessors()
            // Auf big.LITTLE-SoCs bremsen E-Cores bei Token-Generierung oft aus -> max. 4 Decode-Threads,
            // waehrend Prompt-Prefill von mehr Threads profitiert.
            val decodeThreads = (cores / 2).coerceIn(2, 4)
            val batchThreads = (cores - 2).coerceIn(decodeThreads, 6)

            // Erst auf der Grafikkarte, bei Fehler (zu wenig Grafikspeicher) auf dem Prozessor
            val gpu = if (useGpu) runCatching { nativeGpuName() }.getOrDefault("") else ""
            var newHandle = if (gpu.isNotEmpty()) nativeLoadModel(file.absolutePath, nCtx, decodeThreads, batchThreads, 999) else 0L
            gpuInUse = if (newHandle != 0L) gpu else ""
            if (newHandle == 0L) newHandle = nativeLoadModel(file.absolutePath, nCtx, decodeThreads, batchThreads, 0)
            if (newHandle != 0L) {
                val arch = nativeGetModelArch(newHandle)
                synchronized(handleLock) { handle = newHandle }
                loadedModelName = file.name
                modelFamily = when {
                    arch.contains("gemma", ignoreCase = true) || file.name.contains("gemma", ignoreCase = true) -> ModelFamily.GEMMA
                    arch.startsWith("qwen", ignoreCase = true) || arch.isBlank() -> ModelFamily.QWEN_CHATML
                    else -> ModelFamily.CHATML
                }
                thinks = runCatching { nativeGetMeta(newHandle, "tokenizer.chat_template") }.getOrDefault("").contains("<think>")
                true
            } else {
                false
            }
        }
    }

    fun formatChatPrompt(systemPrompt: String, userPrompt: String, disableThinking: Boolean = true): String {
        return when (modelFamily) {
            ModelFamily.QWEN_CHATML -> buildString {
                append("<|im_start|>system\n")
                append(systemPrompt)
                append("<|im_end|>\n<|im_start|>user\n")
                append(userPrompt)
                // Bei Qwen3 unterdrueckt /no_think lange Reasoning-Schleifen bei schnellen JSON-Schritten
                if (disableThinking) append(" /no_think")
                append("<|im_end|>\n<|im_start|>assistant\n")
                if (disableThinking) append("<think>\n\n</think>\n")
            }
            // Andere Modelle: Chatformat aus der GGUF-Datei, sonst ChatML
            ModelFamily.CHATML -> handle.takeIf { it != 0L }?.let { h ->
                runCatching { nativeApplyTemplate(h, systemPrompt, userPrompt) }.getOrNull()?.takeIf { it.isNotBlank() }
            }?.let { t ->
                // Denkmodus aus: leerer Denkblock, wie ihn die Vorlagen bei enable_thinking=false setzen
                when {
                    !disableThinking || !thinks -> t
                    t.trimEnd().endsWith("<think>") -> t.trimEnd() + "\n</think>\n\n"
                    else -> t + "<think>\n\n</think>\n\n"
                }
            } ?: buildString {
                append("<|im_start|>system\n")
                append(systemPrompt)
                append("<|im_end|>\n<|im_start|>user\n")
                append(userPrompt)
                append("<|im_end|>\n<|im_start|>assistant\n")
            }
            ModelFamily.GEMMA -> buildString {
                append("<start_of_turn>user\n")
                append(systemPrompt)
                append("\n\n")
                append(userPrompt)
                append("<end_of_turn>\n<start_of_turn>model\n")
            }
        }
    }

    suspend fun generate(
        systemPrompt: String,
        userPrompt: String,
        gbnfGrammar: String? = null,
        temperature: Float = 0.05f,
        maxTokens: Int = 768,
        onToken: ((String) -> Unit)? = null
    ): String = withContext(Dispatchers.Default) {
        nativeLock.withLock {
            val h = handle
            if (closed.get() || h == 0L) error(tr("no_model"))
            val prompt = formatChatPrompt(systemPrompt, userPrompt, disableThinking = true)
            val callback = onToken?.let { fn ->
                object : TokenCallback {
                    override fun onToken(token: String) = fn(token)
                }
            }
            nativeGenerate(h, prompt, gbnfGrammar, temperature, maxTokens, callback).trim()
        }
    }

    fun cancelGeneration() {
        synchronized(handleLock) {
            val h = handle
            if (h != 0L) nativeCancel(h)
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancelGeneration()
        // Freigabe asynchron nach Abschluss laufender nativer Calls (verhindert SIGSEGV & Main-Thread-ANR).
        thread(name = "llama-cleanup", isDaemon = false) {
            nativeLock.withLock {
                freeHandleLocked()
            }
        }
    }

    private fun freeHandleLocked() {
        val h = synchronized(handleLock) {
            val cur = handle
            handle = 0L
            cur
        }
        loadedModelName = null
        if (h != 0L) nativeFree(h)
    }

    private external fun nativeInitBackend(libDir: String)
    private external fun nativeLoadModel(path: String, nCtx: Int, nThreads: Int, nThreadsBatch: Int, nGpuLayers: Int): Long
    private external fun nativeGpuName(): String
    private external fun nativeGetMeta(handle: Long, key: String): String
    private external fun nativeGetModelArch(handle: Long): String
    private external fun nativeGenerate(
        handle: Long,
        prompt: String,
        grammar: String?,
        temperature: Float,
        maxTokens: Int,
        callback: TokenCallback?
    ): String
    private external fun nativeApplyTemplate(handle: Long, systemPrompt: String, userPrompt: String): String
    private external fun nativeCancel(handle: Long)
    private external fun nativeFree(handle: Long)
}
