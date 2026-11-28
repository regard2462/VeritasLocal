package com.veritas.localresearch.ui

import android.app.Application
import com.veritas.localresearch.i18n.tr
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
    /** Grafikkarte, auf der das Modell rechnet; leer = Prozessor. */
    val gpuInUse: String = "",
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
    val errorMessage: String? = null,
    /** Laufender Import eines eigenen Dokuments: Text fuer die Anzeige, sonst null. */
    val importStatus: String? = null
)

class ResearchViewModel(app: Application) : AndroidViewModel(app) {

    val modelManager = ModelManager(app)
    val settingsStore = SettingsStore(app)
    val docStore = com.veritas.localresearch.docs.LocalDocumentStore(app)
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

    /** Live-Liste der laufenden Recherche (Suchen, geoeffnete Seiten, Fakten). */
    val live: StateFlow<List<com.veritas.localresearch.domain.LiveEvent>> = researchEngine.live

    private var activeResearchJob: Job? = null
    /** Laufender Import oder Ueberblick, damit Stopp (auch aus der Benachrichtigung) ihn abbrechen kann. */
    private var importJob: Job? = null

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

    /** Verfuegbare Grafikkarte (Vulkan), leer auf Android oder ohne passende Karte. */
    val gpuName: String by lazy { llamaEngine.gpuName() }

    /** Grafikkarte an oder aus; das geladene Modell wird dafuer neu geladen. */
    fun setUseGpu(on: Boolean) {
        settingsStore.update { it.copy(useGpu = on) }
        val name = llamaEngine.loadedModelName ?: return
        loadLlmFile(java.io.File(modelManager.modelsDir, name))
    }

    fun engineCooldownUntil(engine: SearchEngine): Long = webScraper.engineCooldownUntil(engine)
    fun clearEngineCooldown(engine: SearchEngine) = webScraper.clearEngineCooldown(engine)

    // Captcha selbst loesen (nur mit eingebautem Browser)
    val challengedEngines: StateFlow<Set<SearchEngine>> = webScraper.challenged
    val canSolveCaptcha: Boolean get() = webScraper.canSolveCaptcha
    val browserUserAgent: String get() = webScraper.browserUserAgent
    fun captchaUrl(engine: SearchEngine): String = webScraper.captchaUrl(engine)
    fun captchaSolved(engine: SearchEngine) = webScraper.captchaSolved(engine)
    private val _captchaFor = MutableStateFlow<SearchEngine?>(null)
    /** Suchmaschine, deren Test gerade im sichtbaren Browserfenster offen ist. */
    val captchaFor: StateFlow<SearchEngine?> = _captchaFor
    fun openCaptcha(engine: SearchEngine?) { _captchaFor.value = engine }

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
                    _uiState.update { it.copy(errorMessage = tr("err_download", err.message)) }
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
                _uiState.update { it.copy(rerankerLoaded = ok, errorMessage = if (!ok) tr("err_reranker") else null) }
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
                    errorMessage = if (!ok) tr("err_embedding") else null
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
                _uiState.update { it.copy(errorMessage = tr("err_import", err.message)) }
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
            llamaEngine.useGpu = settingsStore.current.useGpu
            val ok = llamaEngine.loadModel(file, nCtx = 4096)
            _uiState.update {
                it.copy(
                    isModelLoading = false,
                    loadedLlmName = llamaEngine.loadedModelName,
                    gpuInUse = llamaEngine.gpuInUse,
                    errorMessage = if (!ok) tr("err_gguf") else null
                )
            }
        }
    }

    fun startResearch() {
        val question = _uiState.value.queryInput.trim()
        if (question.isBlank() || _uiState.value.importStatus != null) return
        val mode = settingsStore.current.sourceMode
        val docs = if (mode == com.veritas.localresearch.settings.SourceMode.WEB) emptyList() else docStore.docs.value.filter { it.selected }
        if (mode == com.veritas.localresearch.settings.SourceMode.DOCS && docs.isEmpty()) {
            _uiState.update { it.copy(errorMessage = tr("need_doc")) }
            return
        }
        if (!llamaEngine.isLoaded) {
            _uiState.update {
                it.copy(showModelManager = true, errorMessage = tr("need_model"))
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
                        stageTitle = tr("starting")
                    )
                )
            }

            val streamLock = Any()
            val streamBuffer = StringBuilder()
            var lastUiPushMs = 0L
            var lastNotifyMs = 0L
            val shortQ = question.take(60)
            BackgroundWork.hooks?.started(tr("bg_research", shortQ))

            try {
                // Jsoup, Chunking, BM25 und Verifikation laufen abseits des UI-Threads
                val report = withContext(Dispatchers.Default) { researchEngine.runResearch(
                    question = question,
                    maxRounds = _uiState.value.maxRounds,
                    onProgress = { p ->
                        _uiState.update { it.copy(progress = p) }
                        val now = System.currentTimeMillis()
                        if (p.isRunning && now - lastNotifyMs >= 1500L) {
                            lastNotifyMs = now
                            BackgroundWork.hooks?.progress(tr("bg_research", shortQ), p.stageTitle)
                        }
                    },
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
                    },
                    docs = docs,
                    docStore = docStore,
                    useWeb = mode != com.veritas.localresearch.settings.SourceMode.DOCS
                ) }
                _uiState.update { it.copy(report = report, streamingDraft = "") }
                BackgroundWork.hooks?.finished(tr("answer_ready"), shortQ)
            } catch (e: CancellationException) {
                BackgroundWork.hooks?.finished("", "")
                _uiState.update {
                    it.copy(
                        streamingDraft = "",
                        progress = it.progress.copy(isRunning = false, stageTitle = tr("stopped"))
                    )
                }
                throw e
            } catch (e: Exception) {
                BackgroundWork.hooks?.finished(tr("bg_aborted"), e.message.orEmpty().take(200))
                _uiState.update {
                    it.copy(
                        streamingDraft = "",
                        progress = it.progress.copy(isRunning = false, stageTitle = tr("aborted")),
                        errorMessage = tr("err_pipeline", e.message)
                    )
                }
            }
        }
    }

    /**
     * Eigenes Dokument laden: Text lesen, Abschnitte bilden und Vektoren vorab berechnen, damit die erste Frage
     * nicht warten muss. Laeuft wie eine Recherche im Vordergrunddienst weiter, wenn die App geschlossen wird.
     */
    fun importDocument(uri: Uri) {
        if (_uiState.value.importStatus != null || _uiState.value.progress.isRunning) return
        val app = getApplication<Application>()
        importJob = viewModelScope.launch {
            val name = runCatching {
                app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && i >= 0) c.getString(i) else null
                }
            }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: tr("document")
            importBytes(name) {
                withContext(Dispatchers.IO) {
                    val max = com.veritas.localresearch.docs.LocalDocumentStore.MAX_BYTES
                    val size = runCatching {
                        app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                            val i = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                            if (c.moveToFirst() && i >= 0 && !c.isNull(i)) c.getLong(i) else null
                        }
                    }.getOrNull()
                    if (size != null && size > max) error(tr("file_too_big_mb", max / 1024 / 1024))
                    app.contentResolver.openInputStream(uri)?.use { inp ->
                        if (size != null && size > 0) {
                            // Bekannte Groesse: direkt in ein Feld passender Laenge lesen (keine zweite Kopie im Speicher)
                            val arr = ByteArray(size.toInt())
                            var off = 0
                            while (off < arr.size) {
                                val n = inp.read(arr, off, arr.size - off)
                                if (n < 0) break
                                off += n
                            }
                            if (off == arr.size) arr else arr.copyOf(off)
                        } else {
                            val out = java.io.ByteArrayOutputStream()
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                if (out.size() > max) error(tr("file_too_big"))
                            }
                            out.toByteArray()
                        }
                    } ?: error(tr("file_unreadable"))
                }
            }
        }
    }

    private suspend fun importBytes(name: String, read: suspend () -> ByteArray): Boolean {
        _uiState.update { it.copy(importStatus = tr("reading", name), errorMessage = null) }
        BackgroundWork.hooks?.started(tr("bg_doc", name.take(60)))
        try {
            val doc = docStore.import(name, read()) { s ->
                _uiState.update { it.copy(importStatus = s) }
                BackgroundWork.hooks?.progress(tr("bg_doc", name.take(60)), s)
            }.getOrElse { e ->
                android.util.Log.w("VeritasImport", "$name", e)
                _uiState.update { it.copy(errorMessage = "$name: ${e.message}") }
                BackgroundWork.hooks?.finished("", "")
                return false
            }
            if (onnxEmbedder.isReady) {
                val chunks = docStore.chunkTree(doc, TextChunker()).children
                _uiState.update { it.copy(importStatus = tr("preparing", doc.title, chunks.size)) }
                BackgroundWork.hooks?.progress(tr("bg_doc", name.take(60)), tr("bg_indexing", chunks.size))
                val vectors = withContext(Dispatchers.Default) {
                    retriever.embedChunks(chunks) { k, n ->
                        if (k % 256 < 16 || k == n) {
                            _uiState.update { it.copy(importStatus = tr("indexing", doc.title, k, n)) }
                            BackgroundWork.hooks?.progress(tr("bg_doc", name.take(60)), tr("bg_indexed", k, n))
                        }
                    }
                }
                docStore.saveVectors(doc.id, retriever.embedderTag, chunks, vectors)
            }
            if (llamaEngine.isLoaded) buildGuide(doc, name)
            BackgroundWork.hooks?.finished("", "")
            return true
        } catch (e: CancellationException) {
            BackgroundWork.hooks?.finished("", "")
            throw e
        } catch (e: OutOfMemoryError) {
            android.util.Log.w("VeritasImport", name, e)
            _uiState.update { it.copy(errorMessage = tr("oom", name)) }
            BackgroundWork.hooks?.finished("", "")
            return false
        } catch (e: Exception) {
            android.util.Log.w("VeritasImport", name, e)
            _uiState.update { it.copy(errorMessage = "$name: ${e.message}") }
            BackgroundWork.hooks?.finished("", "")
            return false
        } finally {
            _uiState.update { it.copy(importStatus = null) }
        }
    }

    /** Kurzfassung und Fragevorschlaege; Fortschritt in der Importzeile und in der Benachrichtigung. */
    private suspend fun buildGuide(doc: com.veritas.localresearch.docs.LocalDoc, name: String) {
        _uiState.update { it.copy(importStatus = tr("guide_making", doc.title)) }
        val (summary, questions) = withContext(Dispatchers.Default) {
            researchEngine.sourceGuide(doc, docStore) { p ->
                _uiState.update { it.copy(importStatus = tr("guide_progress", doc.title, p.stageTitle)) }
                BackgroundWork.hooks?.progress(tr("bg_doc", name.take(60)), tr("bg_guide", p.stageTitle))
            }
        }
        docStore.setGuide(doc.id, summary, questions)
    }

    /** Ueberblick fuer ein Dokument nachtraeglich erstellen (z. B. wenn beim Import kein Modell geladen war). */
    fun createGuide(id: String) {
        val doc = docStore.docs.value.firstOrNull { it.id == id } ?: return
        if (_uiState.value.importStatus != null || _uiState.value.progress.isRunning) return
        if (!llamaEngine.isLoaded) {
            _uiState.update { it.copy(showModelManager = true, errorMessage = tr("need_model")) }
            return
        }
        importJob = viewModelScope.launch {
            BackgroundWork.hooks?.started(tr("bg_doc", doc.title.take(60)))
            try {
                buildGuide(doc, doc.title)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = tr("err_guide", e.message)) }
            } finally {
                BackgroundWork.hooks?.finished("", "")
                _uiState.update { it.copy(importStatus = null) }
            }
        }
    }

    fun removeDocument(id: String) = docStore.remove(id)
    fun selectDocument(id: String, selected: Boolean) = docStore.setSelected(id, selected)

    /** Debug-Testlauf: Modell laden, Frage stellen, Bericht als JSON nach files/autorun/<tag>.json schreiben. */
    fun autorun(question: String, modelFileName: String, depth: Int, tag: String, docPath: String? = null, sources: String? = null) {
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
                    llamaEngine.useGpu = settingsStore.current.useGpu
                    val ok = llamaEngine.loadModel(f, nCtx = 4096)
                    _uiState.update { it.copy(isModelLoading = false, loadedLlmName = llamaEngine.loadedModelName, gpuInUse = llamaEngine.gpuInUse) }
                    if (!ok) { out.writeText("{\"error\":\"model load failed\"}"); return@launch }
                }
            }
            sources?.let { n -> com.veritas.localresearch.settings.SourceMode.entries.firstOrNull { it.name == n } }
                ?.let { m -> settingsStore.update { it.copy(sourceMode = m) } }
            // Testdokument einmalig laden, danach nur dieses auswaehlen
            if (!docPath.isNullOrBlank()) {
                val f = File(docPath)
                if (docStore.docs.value.none { it.fileName == f.name }) importBytes(f.name) { f.readBytes() }
                docStore.docs.value.forEach { d -> docStore.setSelected(d.id, d.fileName == f.name) }
            }
            _uiState.update { it.copy(queryInput = question, maxRounds = depth.coerceIn(1, 3)) }
            startResearch()
            activeResearchJob?.join()
            val r = _uiState.value.report
            val json = kotlinx.serialization.json.buildJsonObject {
                put("question", kotlinx.serialization.json.JsonPrimitive(question))
                put("model", kotlinx.serialization.json.JsonPrimitive(llamaEngine.loadedModelName.orEmpty()))
                put("gpu", kotlinx.serialization.json.JsonPrimitive(llamaEngine.gpuInUse))
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
                            put("page", kotlinx.serialization.json.JsonPrimitive(c.chunk.page))
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
                android.widget.Toast.makeText(app, tr("saved", format.label), android.widget.Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                android.util.Log.e("VeritasExport", "Export ${format.name}", e)
                _uiState.update { it.copy(errorMessage = tr("err_export", e.message)) }
            }
        }
    }

    fun stopResearch() {
        llamaEngine.cancelGeneration()
        activeResearchJob?.cancel()
        importJob?.cancel()
        _uiState.update {
            it.copy(
                streamingDraft = "",
                progress = it.progress.copy(isRunning = false, stageTitle = tr("stopped"))
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
