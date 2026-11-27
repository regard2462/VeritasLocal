package com.veritas.localresearch.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.veritas.localresearch.domain.PipelineProgress
import com.veritas.localresearch.domain.ResearchReport
import com.veritas.localresearch.domain.UnverifiedNumberSpan
import com.veritas.localresearch.domain.VerifiedClaim
import com.veritas.localresearch.llm.LlamaEngine
import com.veritas.localresearch.llm.ModelManager
import com.veritas.localresearch.llm.ModelPreset
import com.veritas.localresearch.llm.OnnxEmbedder
import com.veritas.localresearch.pipeline.DeepResearchEngine
import com.veritas.localresearch.rag.Fts5ChunkStore
import com.veritas.localresearch.scraping.SearchEngine
import com.veritas.localresearch.settings.SettingsStore
import com.veritas.localresearch.rag.HybridRetriever
import com.veritas.localresearch.rag.TextChunker
import com.veritas.localresearch.scraping.DirectFactApis
import com.veritas.localresearch.scraping.HiddenWebScraper
import com.veritas.localresearch.scraping.RobotsTxtChecker
import com.veritas.localresearch.verification.DeterministicVerifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

data class ResearchUiState(
    val queryInput: String = "",
    val maxRounds: Int = 2,
    val loadedLlmName: String? = null,
    val isEmbeddingLoaded: Boolean = false,
    val loadedEmbeddingId: String? = null,
    val rerankerLoaded: Boolean = false,
    val isModelLoading: Boolean = false,
    val downloadProgress: Map<String, Float> = emptyMap(),
    val deviceRamGb: Int = 8,
    val localGgufFiles: List<File> = emptyList(),
    val progress: PipelineProgress = PipelineProgress(),
    val streamingDraft: String = "",
    val report: ResearchReport? = null,
    val selectedClaimForSheet: VerifiedClaim? = null,
    val selectedWarningNumber: UnverifiedNumberSpan? = null,
    val showModelManager: Boolean = false,
    val showSettings: Boolean = false,
    val errorMessage: String? = null
)

class ResearchViewModel(app: Application) : AndroidViewModel(app) {

    val modelManager = ModelManager(app)
    val settingsStore = SettingsStore(app)
    private val sharedHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val llamaEngine = LlamaEngine(app.applicationInfo.nativeLibraryDir)
    private val onnxEmbedder = OnnxEmbedder()
    private val reranker = com.veritas.localresearch.llm.CrossEncoderReranker()
    private val robotsChecker = RobotsTxtChecker(sharedHttpClient)
    private val webScraper = HiddenWebScraper(app, robotsChecker, settingsStore, sharedHttpClient)
    private val factApis = DirectFactApis(sharedHttpClient)
    private val ftsStore = Fts5ChunkStore()
    private val chunker = TextChunker()
    private val retriever = HybridRetriever(ftsStore, onnxEmbedder)
    private val verifier = DeterministicVerifier()
    private val researchEngine = DeepResearchEngine(
        llamaEngine, webScraper, factApis, chunker, retriever, verifier, settingsStore, reranker
    )

    private val _uiState = MutableStateFlow(
        ResearchUiState(
            deviceRamGb = modelManager.getDeviceTotalRamGb(),
            maxRounds = settingsStore.current.defaultDepth,
            localGgufFiles = modelManager.listLocalGgufFiles()
        )
    )
    val uiState: StateFlow<ResearchUiState> = _uiState.asStateFlow()

    private var activeResearchJob: Job? = null

    init {
        autoLoadAvailableModels()
    }

    private fun autoLoadAvailableModels() {
        viewModelScope.launch {
            // Gewaehltes Embedding-Modell, sonst irgendein installiertes
            modelManager.presets.firstOrNull { it.isReranker && modelManager.isPresetInstalled(it) }?.let { rp ->
                val ok = reranker.load(modelManager.getFile(rp), modelManager.getVocabFile(rp))
                _uiState.update { it.copy(rerankerLoaded = ok) }
            }
            val embeddings = modelManager.presets.filter { it.isEmbedding }
            val embPreset = embeddings.firstOrNull { it.id == settingsStore.current.embeddingPresetId && modelManager.isPresetInstalled(it) }
                ?: embeddings.firstOrNull { modelManager.isPresetInstalled(it) }
            if (embPreset != null) {
                val ok = onnxEmbedder.load(modelManager.getFile(embPreset), modelManager.getVocabFile(embPreset))
                _uiState.update { it.copy(isEmbeddingLoaded = ok, loadedEmbeddingId = if (ok) embPreset.id else null) }
            }

            val firstGguf = modelManager.listLocalGgufFiles().firstOrNull()
            if (firstGguf != null) {
                loadLlmFile(firstGguf)
            } else {
                _uiState.update { it.copy(showModelManager = true) }
            }
        }
    }

    fun onQueryChange(text: String) {
        _uiState.update { it.copy(queryInput = text) }
    }

    fun onMaxRoundsChange(rounds: Int) {
        _uiState.update { it.copy(maxRounds = rounds.coerceIn(1, 3)) }
    }

    fun toggleSettings(show: Boolean) {
        _uiState.update { it.copy(showSettings = show) }
    }

    fun updateSettings(transform: (com.veritas.localresearch.settings.AppSettings) -> com.veritas.localresearch.settings.AppSettings) =
        settingsStore.update(transform)

    fun engineCooldownUntil(engine: SearchEngine): Long = webScraper.engineCooldownUntil(engine)
    fun clearEngineCooldown(engine: SearchEngine) = webScraper.clearEngineCooldown(engine)

    fun toggleModelManager(show: Boolean) {
        _uiState.update {
            it.copy(
                showModelManager = show,
                localGgufFiles = modelManager.listLocalGgufFiles()
            )
        }
    }

    fun openCitationSheet(claim: VerifiedClaim?) {
        _uiState.update { it.copy(selectedClaimForSheet = claim) }
    }

    fun openNumberWarning(span: UnverifiedNumberSpan?) {
        _uiState.update { it.copy(selectedWarningNumber = span) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun downloadPreset(preset: ModelPreset) {
        // Doppelte parallele Downloads derselben Datei verhindern
        if (preset.id in _uiState.value.downloadProgress) return
        viewModelScope.launch {
            _uiState.update { it.copy(downloadProgress = it.downloadProgress + (preset.id to 0.01f), errorMessage = null) }
            try {
                val result = modelManager.downloadPreset(preset) { pct ->
                    _uiState.update { it.copy(downloadProgress = it.downloadProgress + (preset.id to pct)) }
                }
                _uiState.update {
                    it.copy(
                        downloadProgress = it.downloadProgress - preset.id,
                        localGgufFiles = modelManager.listLocalGgufFiles()
                    )
                }
                result.onSuccess { file ->
                    if (preset.isEmbedding || preset.isReranker) {
                        loadEmbeddingPreset(preset)
                    } else {
                        loadLlmFile(file)
                    }
                }.onFailure { err ->
                    _uiState.update { it.copy(errorMessage = "Download fehlgeschlagen: ${err.message}") }
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(downloadProgress = it.downloadProgress - preset.id) }
                throw e
            }
        }
    }

    fun loadEmbeddingPreset(preset: ModelPreset) {
        if (preset.isReranker) {
            viewModelScope.launch {
                val ok = reranker.load(modelManager.getFile(preset), modelManager.getVocabFile(preset))
                _uiState.update { it.copy(rerankerLoaded = ok, errorMessage = if (!ok) "Reranker konnte nicht geladen werden." else null) }
            }
            return
        }
        viewModelScope.launch {
            val ok = onnxEmbedder.load(modelManager.getFile(preset), modelManager.getVocabFile(preset))
            if (ok) settingsStore.update { it.copy(embeddingPresetId = preset.id) }
            _uiState.update {
                it.copy(
                    isEmbeddingLoaded = ok,
                    loadedEmbeddingId = if (ok) preset.id else null,
                    errorMessage = if (!ok) "Embedding-Modell konnte nicht geladen werden." else null
                )
            }
        }
    }

    fun importLocalGguf(uri: Uri, name: String) {
        viewModelScope.launch {
            val res = modelManager.importLocalGguf(uri, name)
            res.onSuccess { file ->
                _uiState.update { it.copy(localGgufFiles = modelManager.listLocalGgufFiles()) }
                loadLlmFile(file)
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = "Import fehlgeschlagen: ${err.message}") }
            }
        }
    }

    fun loadLlmFile(file: File) {
        viewModelScope.launch {
            // Laufende Recherche vor Modellwechsel sauber stoppen
            val prev = activeResearchJob
            if (prev?.isActive == true) {
                llamaEngine.cancelGeneration()
                prev.cancelAndJoin()
            }
            _uiState.update {
                it.copy(
                    isModelLoading = true,
                    errorMessage = null,
                    progress = it.progress.copy(isRunning = false)
                )
            }
            val ok = llamaEngine.loadModel(file, nCtx = 4096)
            _uiState.update {
                it.copy(
                    isModelLoading = false,
                    loadedLlmName = llamaEngine.loadedModelName,
                    errorMessage = if (!ok) "GGUF-Modell konnte nicht geladen werden (RAM prüfen)." else null
                )
            }
        }
    }

    fun startResearch() {
        val question = _uiState.value.queryInput.trim()
        if (question.isBlank()) return
        if (!llamaEngine.isLoaded) {
            _uiState.update {
                it.copy(showModelManager = true, errorMessage = "Bitte zuerst ein lokales GGUF-Modell laden.")
            }
            return
        }

        val previousJob = activeResearchJob
        llamaEngine.cancelGeneration()

        activeResearchJob = viewModelScope.launch {
            // Sicherstellen, dass der vorherige Lauf beendet ist, bevor chunker/retriever geleert werden
            previousJob?.cancelAndJoin()

            _uiState.update {
                it.copy(
                    report = null,
                    streamingDraft = "",
                    errorMessage = null,
                    progress = PipelineProgress(
                        currentRound = 1,
                        maxRounds = it.maxRounds,
                        isRunning = true,
                        stageTitle = "Starte lokale Recherche…"
                    )
                )
            }

            val streamLock = Any()
            val streamBuffer = StringBuilder()
            var lastUiPushMs = 0L

            try {
                // Jsoup, Chunking, BM25 und Verifikation laufen abseits des UI-Threads
                val report = withContext(Dispatchers.Default) { researchEngine.runResearch(
                    question = question,
                    maxRounds = _uiState.value.maxRounds,
                    onProgress = { p -> _uiState.update { it.copy(progress = p) } },
                    onStreamToken = { tok ->
                        val now = System.currentTimeMillis()
                        val snapshot: String? = synchronized(streamLock) {
                            streamBuffer.append(tok)
                            if (now - lastUiPushMs >= 55L || tok.contains('\n')) {
                                lastUiPushMs = now
                                streamBuffer.toString()
                            } else {
                                null
                            }
                        }
                        if (snapshot != null) {
                            _uiState.update { it.copy(streamingDraft = snapshot) }
                        }
                    }
                ) }
                _uiState.update { it.copy(report = report, streamingDraft = "") }
            } catch (e: CancellationException) {
                _uiState.update {
                    it.copy(
                        streamingDraft = "",
                        progress = it.progress.copy(isRunning = false, stageTitle = "Gestoppt")
                    )
                }
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        streamingDraft = "",
                        progress = it.progress.copy(isRunning = false, stageTitle = "Abgebrochen"),
                        errorMessage = "Fehler in der Pipeline: ${e.message}"
                    )
                }
            }
        }
    }

    /** Debug-Testlauf: Modell laden, Frage stellen, Bericht als JSON nach files/autorun/<tag>.json schreiben. */
    fun autorun(question: String, modelFileName: String, depth: Int, tag: String) {
        viewModelScope.launch {
            val dir = File(getApplication<Application>().filesDir, "autorun").apply { mkdirs() }
            val out = File(dir, "$tag.json")
            out.delete()
            // Warten, bis das Embedding-Modell vom Start fertig geladen ist
            for (i in 0 until 120) {
                val embOk = _uiState.value.isEmbeddingLoaded || modelManager.presets.none { it.isEmbedding && modelManager.isPresetInstalled(it) }
                val rrOk = _uiState.value.rerankerLoaded || modelManager.presets.none { it.isReranker && modelManager.isPresetInstalled(it) }
                if (embOk && rrOk) break
                delay(500)
            }
            // Laufendes Autoload des ersten Modells abwarten, statt parallel ein zweites zu starten
            delay(300)
            for (i in 0 until 600) { if (!_uiState.value.isModelLoading) break; delay(500) }
            if (modelFileName.isNotBlank()) {
                val f = File(modelManager.modelsDir, modelFileName)
                if (llamaEngine.loadedModelName != f.name) {
                    _uiState.update { it.copy(isModelLoading = true) }
                    val ok = llamaEngine.loadModel(f, nCtx = 4096)
                    _uiState.update { it.copy(isModelLoading = false, loadedLlmName = llamaEngine.loadedModelName) }
                    if (!ok) { out.writeText("{\"error\":\"model load failed\"}"); return@launch }
                }
            }
            _uiState.update { it.copy(queryInput = question, maxRounds = depth.coerceIn(1, 3)) }
            startResearch()
            activeResearchJob?.join()
            val r = _uiState.value.report
            val json = kotlinx.serialization.json.buildJsonObject {
                put("question", kotlinx.serialization.json.JsonPrimitive(question))
                put("model", kotlinx.serialization.json.JsonPrimitive(llamaEngine.loadedModelName.orEmpty()))
                put("embedding", kotlinx.serialization.json.JsonPrimitive(_uiState.value.loadedEmbeddingId.orEmpty()))
                put("reranker", kotlinx.serialization.json.JsonPrimitive(_uiState.value.rerankerLoaded))
                put("depth", kotlinx.serialization.json.JsonPrimitive(depth))
                put("error", kotlinx.serialization.json.JsonPrimitive(_uiState.value.errorMessage.orEmpty()))
                if (r != null) {
                    put("answer", kotlinx.serialization.json.JsonPrimitive(r.synthesizedAnswer))
                    put("totalMs", kotlinx.serialization.json.JsonPrimitive(r.totalMs))
                    put("timings", kotlinx.serialization.json.JsonObject(r.stageTimings.associate { it.first to kotlinx.serialization.json.JsonPrimitive(it.second) }))
                    put("rejected", kotlinx.serialization.json.JsonPrimitive(r.rejectedClaimCount))
                    put("sources", kotlinx.serialization.json.JsonPrimitive(r.totalSourcesScraped))
                    put("chunks", kotlinx.serialization.json.JsonPrimitive(r.totalChunksIndexed))
                    put("unverifiedNumbers", kotlinx.serialization.json.JsonArray(r.unverifiedNumbers.map { kotlinx.serialization.json.JsonPrimitive(it.text) }))
                    put("claims", kotlinx.serialization.json.JsonArray(r.verifiedClaims.map { c ->
                        kotlinx.serialization.json.buildJsonObject {
                            put("i", kotlinx.serialization.json.JsonPrimitive(c.citationIndex))
                            put("claim", kotlinx.serialization.json.JsonPrimitive(c.claim))
                            put("quote", kotlinx.serialization.json.JsonPrimitive(c.exactQuote))
                            put("url", kotlinx.serialization.json.JsonPrimitive(c.chunk.url))
                            put("trust", kotlinx.serialization.json.JsonPrimitive(c.trustLevel.name))
                            put("title", kotlinx.serialization.json.JsonPrimitive(c.chunk.title))
                            put("meta", kotlinx.serialization.json.JsonPrimitive(c.chunk.meta.toString()))
                        }
                    }))
                    put("usedDomains", kotlinx.serialization.json.JsonArray(r.usedChunks.map { it.chunk.domain }.distinct().map { kotlinx.serialization.json.JsonPrimitive(it) }))
                }
            }
            // Testlauf: alle Exportformate im eingestellten Zitierstil daneben ablegen
            if (r != null) {
                val style = settingsStore.current.citationStyle
                for (f in com.veritas.localresearch.export.ExportFormat.entries) {
                    runCatching {
                        val bytes = withContext(Dispatchers.Default) { f.render(getApplication(), r, style) }
                        File(dir, "$tag.${f.extension}").writeBytes(bytes)
                    }.onFailure { android.util.Log.e("VeritasExport", "${f.name}: $it", it) }
                }
            }
            out.writeText(json.toString())
        }
    }

    /** Schreibt den aktuellen Bericht im eingestellten Zitierstil in die vom Nutzer gewaehlte Datei. */
    fun exportReport(uri: Uri, format: com.veritas.localresearch.export.ExportFormat) {
        val report = _uiState.value.report ?: return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val result = runCatching {
                val bytes = withContext(Dispatchers.Default) { format.render(app, report, settingsStore.current.citationStyle) }
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Datei nicht beschreibbar")
                }
            }
            result.onSuccess {
                android.widget.Toast.makeText(app, "${format.label} gespeichert", android.widget.Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                android.util.Log.e("VeritasExport", "Export ${format.name}", e)
                _uiState.update { it.copy(errorMessage = "Export fehlgeschlagen: ${e.message}") }
            }
        }
    }

    fun stopResearch() {
        llamaEngine.cancelGeneration()
        activeResearchJob?.cancel()
        _uiState.update {
            it.copy(
                streamingDraft = "",
                progress = it.progress.copy(isRunning = false, stageTitle = "Gestoppt")
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        llamaEngine.cancelGeneration()
        activeResearchJob?.cancel()
        llamaEngine.close()
        onnxEmbedder.close()
        reranker.close()
        webScraper.destroy()
        ftsStore.close()
    }
}
