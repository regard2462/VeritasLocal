package com.veritas.localresearch.pipeline

import com.veritas.localresearch.docs.LocalDoc
import com.veritas.localresearch.docs.LocalDocumentStore
import com.veritas.localresearch.domain.ClaimExtractionDto
import com.veritas.localresearch.domain.GapCheckDto
import com.veritas.localresearch.domain.GbnfGrammars
import com.veritas.localresearch.domain.LanguageDetector
import com.veritas.localresearch.domain.PipelineProgress
import com.veritas.localresearch.domain.QueryPlanDto
import com.veritas.localresearch.domain.RankedChunk
import com.veritas.localresearch.domain.ResearchReport
import com.veritas.localresearch.domain.ScrapedDocument
import com.veritas.localresearch.domain.SourceType
import com.veritas.localresearch.domain.TextChunk
import com.veritas.localresearch.domain.TopicHeuristics
import com.veritas.localresearch.domain.TrustLevel
import com.veritas.localresearch.domain.VerifiedClaim
import com.veritas.localresearch.llm.LlamaEngine
import com.veritas.localresearch.rag.HybridRetriever
import com.veritas.localresearch.rag.TextChunker
import com.veritas.localresearch.scraping.DirectFactApis
import com.veritas.localresearch.scraping.HiddenWebScraper
import com.veritas.localresearch.settings.SettingsStore
import com.veritas.localresearch.settings.AnswerLength
import android.util.Log
import com.veritas.localresearch.i18n.I18n
import com.veritas.localresearch.i18n.tr
import com.veritas.localresearch.verification.DeterministicVerifier
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger

class DeepResearchEngine(
    private val llama: LlamaEngine,
    private val webScraper: HiddenWebScraper,
    private val factApis: DirectFactApis,
    private val chunker: TextChunker,
    private val retriever: HybridRetriever,
    private val verifier: DeterministicVerifier,
    private val settings: SettingsStore,
    private val reranker: com.veritas.localresearch.llm.CrossEncoderReranker? = null
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Sprache der aktuellen Frage, z. B. "Deutsch"; Suchanfragen und Antwort werden darin formuliert. */
    @Volatile private var answerLanguage = "Deutsch"

    private val _live = kotlinx.coroutines.flow.MutableStateFlow<List<com.veritas.localresearch.domain.LiveEvent>>(emptyList())

    /** Live-Liste des laufenden Rechercheschritts (hoechstens 300 Eintraege, neueste zuletzt). */
    val live: kotlinx.coroutines.flow.StateFlow<List<com.veritas.localresearch.domain.LiveEvent>> = _live

    private fun live(kind: com.veritas.localresearch.domain.LiveKind, text: String, url: String = "") {
        val e = com.veritas.localresearch.domain.LiveEvent(kind, text.replace(Regex("\\s+"), " ").trim().take(400), url)
        _live.update { (it + e).takeLast(300) }
    }

    /** Seite geladen oder nicht: Eintrag fuer die Live-Liste. */
    private fun liveDoc(url: String, doc: ScrapedDocument?, viaLink: Boolean = false) {
        val domain = HiddenWebScraper.extractDomain(url)
        if (doc == null) live(com.veritas.localresearch.domain.LiveKind.FAIL, domain, url)
        else live(if (viaLink) com.veritas.localresearch.domain.LiveKind.LINK else com.veritas.localresearch.domain.LiveKind.OPEN,
            "${doc.title} · $domain", url)
    }

    /** Gepruefte und verworfene Aussagen fuer die Live-Liste; Gruende stehen im Protokoll, gezeigt wird die Aussage. */
    private fun liveClaims(verified: List<VerifiedClaim>, rejections: List<String>) {
        verified.forEach { live(com.veritas.localresearch.domain.LiveKind.FACT, "${it.claim} · ${it.chunk.domain}", it.chunk.url) }
        rejections.forEach { r -> live(com.veritas.localresearch.domain.LiveKind.REJECT, r.substringAfter(": ").substringBefore(" | ")) }
    }

    /** Gewuenschte Antwortlaenge des aktuellen Laufs. */
    @Volatile private var length = LengthSpec.of(AnswerLength.MEDIUM)

    /**
     * Budgets je Recherche-Tiefe. Komplexe Fragen brauchen mehr Teilaspekte, Quellen und Fakten;
     * die Extraktion laeuft dafuer in mehreren Durchgaengen, damit jeder Prompt in n_ctx = 4096 passt.
     */
    private data class DepthProfile(
        val queries: Int,
        val pagesFirstRound: Int,
        val pagesLaterRounds: Int,
        val finalChunks: Int,
        val maxPerDomain: Int,
        val chunksPerPass: Int,
        val claimsPerPass: Int,
        val synthesisTokens: Int
    )

    /** Zeitgruppe einer Stufe (Schluessel wie "st_search") fuer die Dauer-Anzeige unter der Antwort. */
    private fun stageKey(stage: String): String = when (stage) {
        "st_plan", "st_plan_paper" -> "tg_plan"
        "st_search" -> "tg_search"
        "st_load" -> "tg_load"
        "st_docs" -> "tg_docs"
        "st_index" -> "tg_index"
        "st_rerank" -> "tg_rerank"
        "st_gap" -> "tg_gap"
        "st_extract", "st_extract_n" -> "tg_extract"
        "st_outline" -> "tg_outline"
        "st_synth", "st_write" -> "tg_write"
        "st_verify", "st_check" -> "tg_check"
        else -> "tg_select"
    }

    private fun profileFor(maxRounds: Int) = when (maxRounds) {
        1 -> DepthProfile(3, 5, 3, 8, 2, 8, 6, 500)
        2 -> DepthProfile(4, 7, 4, 14, 3, 7, 7, 850)
        else -> DepthProfile(5, 9, 5, 21, 3, 7, 7, 1150)
    }

    /** Nur eigene Dokumente (kein Web) im aktuellen Lauf. */
    @Volatile private var docsOnly = false

    /** Uebersichtsfrage zu eigenen Dokumenten ("Worum geht es?", "Fasse zusammen"). */
    @Volatile private var overview = false

    /** Frage und eigene Dokumente in verschiedenen Sprachen: Suchbegriffe der Frage stehen nicht im Text. */
    @Volatile private var crossLang = false

    /**
     * [docs]: eigene Dokumente, die mit durchsucht werden; [useWeb] = false beantwortet die Frage nur aus ihnen.
     */
    suspend fun runResearch(
        question: String,
        maxRounds: Int = 2,
        onProgress: (PipelineProgress) -> Unit,
        onStreamToken: (String) -> Unit,
        docs: List<LocalDoc> = emptyList(),
        docStore: LocalDocumentStore? = null,
        useWeb: Boolean = true,
        /** Laenge fuer diesen Lauf statt der Einstellung (Ueberblick beim Import). */
        lengthOverride: AnswerLength? = null
    ): ResearchReport {
        chunker.resetIds()
        retriever.clear()
        _live.value = emptyList()
        docsOnly = !useWeb && docs.isNotEmpty() && docStore != null
        overview = docsOnly && isOverviewQuestion(question)

        val visitedUrls = LinkedHashSet<String>()
        val scrapedDomains = LinkedHashSet<String>()
        var totalScrapedDocs = 0
        var executedRounds = 0
        var cachedFinalRanking: List<RankedChunk>? = null

        // Zeitmessung je Zeitgruppe (Suche, Seiten laden ...), fuer die Anzeige unter der Antwort
        val startedAt = System.currentTimeMillis()
        val timings = LinkedHashMap<String, Long>()
        var lastKey: String? = null
        var lastTs = startedAt
        fun mark(stage: String?) {
            val now = System.currentTimeMillis()
            lastKey?.let { val k = tr(it); timings[k] = (timings[k] ?: 0L) + (now - lastTs) }
            lastKey = stage?.let { stageKey(it) }
            lastTs = now
        }
        fun finish(r: ResearchReport): ResearchReport {
            mark(null)
            val total = System.currentTimeMillis() - startedAt
            val out = r.copy(stageTimings = timings.toList(), totalMs = total, modelName = llama.loadedModelName.orEmpty())
            Log.i("VeritasTiming", "total=${total}ms " + timings.entries.joinToString { "${it.key}=${it.value}ms" })
            return out
        }

        /** [stage]: Schluessel der Stufe, [args] fuellt die Platzhalter ihres Titels. */
        fun emit(round: Int, stage: String, detail: String, verified: Int = 0, rejected: Int = 0, args: List<Any> = emptyList()) {
            if (lastKey != stageKey(stage)) mark(stage)
            onProgress(
                PipelineProgress(
                    currentRound = round,
                    maxRounds = maxRounds,
                    stageTitle = tr(stage, *args.toTypedArray()),
                    detail = detail,
                    scrapedDomains = scrapedDomains.toList(),
                    chunkCount = retriever.totalChunks(),
                    verifiedClaimsCount = verified,
                    rejectedClaimsCount = rejected,
                    isRunning = true
                )
            )
        }

        val cfg = settings.current.let { c -> if (lengthOverride != null) c.copy(answerLength = lengthOverride) else c }
        val spec = if (lengthOverride != null) LengthSpec.of(lengthOverride) else LengthSpec.of(cfg.answerLength, cfg.answerWords)
        val detailed = spec.detailed
        // Ausfuehrlich: mehr Suchanfragen, Seiten und Abschnitte, damit auch Unterthemen Quellen bekommen
        val profile = profileFor(maxRounds).let { p ->
            if (detailed) p.copy(queries = p.queries + 2, pagesLaterRounds = p.pagesLaterRounds + 3,
                finalChunks = p.finalChunks + 6, maxPerDomain = p.maxPerDomain + 1) else p
        }.let { p ->
            // Selbst festgelegte Wortzahl: so viele Abschnitte und Quellen, dass genug gepruefte Fakten zusammenkommen
            if (spec.words <= 0) p else p.copy(
                queries = p.queries + (spec.words / 800).coerceAtMost(4),
                pagesLaterRounds = p.pagesLaterRounds + (spec.words / 400).coerceAtMost(8),
                finalChunks = maxOf(p.finalChunks, spec.factTarget).coerceAtMost(60),
                maxPerDomain = p.maxPerDomain + spec.words / 1000
            )
        }
        // Ausfuehrlich sucht immer eine zweite Runde zu den Unterthemen
        val searchRounds = if (detailed) maxOf(maxRounds, 2) else maxRounds
        // Suche, Quellen und Antwort bleiben in der Sprache der Frage
        val qLang = LanguageDetector.detect(question)
        answerLanguage = qLang?.name ?: "der Sprache der Frage"
        // Nur eigene Dokumente in anderer Sprache: Aussagen muessen den Wortlaut der Quelle nutzen, also antwortet
        // die App in der Sprache der Dokumente
        crossLang = false
        if (docsOnly && docStore != null) {
            val sample = docs.joinToString(" ") { docStore.text(it.id).take(3000) }
            val docLang = LanguageDetector.detect(sample)
            if (docLang != null && docLang != qLang) { answerLanguage = docLang.name; crossLang = true }
        }
        length = spec
        // Selbst festgelegte Wortzahl mit Websuche: geplante Recherche wie eine Hausarbeit, Kapitel fuer Kapitel
        val planned = spec.words > 0 && useWeb && docs.isEmpty() && lengthOverride == null
        webScraper.maxPageChars = if (planned) 40_000 else 14_000
        pageTopicCache.clear()
        var plannedChapters: List<Pair<String, List<VerifiedClaim>>> = emptyList()
        val synthesisTokens = (profile.synthesisTokens * length.tokenFactor).toInt()
        // Eigene Dokumente duerfen mehr Abschnitte stellen als eine Webseite; ohne Web gibt es keine Grenze je Datei
        val localCap = if (docsOnly) profile.finalChunks * 2 else profile.maxPerDomain + 2
        fun capOf(c: TextChunk) = if (c.sourceType == SourceType.LOCAL) localCap else profile.maxPerDomain

        // === Eigene Dokumente: Abschnitte mit Seitenangaben, Vektoren aus dem Speicher oder neu berechnet ===
        val parentOf = HashMap<Int, TextChunk>()
        if (docs.isNotEmpty() && docStore != null) {
            for ((i, d) in docs.withIndex()) {
                emit(1, "st_docs", d.title, args = listOf(i + 1, docs.size))
                val tree = docStore.chunkTree(d, chunker)
                parentOf.putAll(tree.parentOf)
                val chunks = tree.children
                val tag = retriever.embedderTag
                val vectors = docStore.loadVectors(d.id, tag, chunks)
                    ?: retriever.embedChunks(chunks) { k, n ->
                        if (k % 512 < 16 || k == n) emit(1, "st_docs", tr("d_docs_index", d.title, k, n), args = listOf(i + 1, docs.size))
                    }.also { docStore.saveVectors(d.id, tag, chunks, it) }
                retriever.indexChunks(chunks, vectors)
                scrapedDomains.add(d.fileName)
                totalScrapedDocs++
            }
        }

        val verification: DeterministicVerifier.ClaimVerificationResult
        val topRanked: List<RankedChunk>
        if (planned) {
            val res = plannedResearch(question, qLang, maxRounds, profile, cfg.useWikipedia, cfg.useArxiv, visitedUrls,
                scrapedDomains) { r, st, d, a -> emit(r, st, d, args = a) }
            totalScrapedDocs += res.pages
            executedRounds = res.rounds
            var n = 0
            plannedChapters = res.chapters.map { (t, cs) -> t to cs.map { vc -> vc.copy(citationIndex = ++n) } }
            verification = DeterministicVerifier.ClaimVerificationResult(plannedChapters.flatMap { it.second }, res.rejected)
            topRanked = res.used
        } else {
        // === STUFE 1: Query-Zerlegung via GBNF, parallel dazu schon die Suche mit der Originalfrage ===
        emit(1, "st_plan", if (docsOnly) tr("d_plan_docs") else tr("d_plan_web"))
        val (plan, earlyResults) = if (docsOnly) {
            (if (overview) QueryPlanDto(queries = listOf(question), englishQuery = question) else planQueries(question, profile.queries, detailed, forDocs = true)) to emptyList()
        } else coroutineScope {
            val early = async { runCatching { webScraper.searchWeb(question, maxResults = 5, lang = qLang) }.getOrDefault(emptyList()) }
            val p = planQueries(question, profile.queries, detailed)
            p to early.await()
        }
        var currentQueries = plan.queries
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals(question, ignoreCase = true) }
            .distinct()
            .take(profile.queries)
            .ifEmpty { listOf(question) }
        val englishQuery = plan.englishQuery.trim().ifBlank { currentQueries.first() }
        val aspectQueries = LinkedHashSet(currentQueries)
        // Eigene Dokumente: ein gedachter Lehrbuchabsatz als zusaetzliche Suchanfrage (HyDE). Er findet Stellen, die die
        // Antwort mit anderen Worten als die Frage geben; er dient nur der Suche und wird nie als Fakt verwendet.
        val hydeQuery = if (parentOf.isNotEmpty() && !overview) runCatching { hypotheticalPassage(question) }.getOrNull() else null

        // Ranking je Teilaspekt und abwechselnd zusammenfuehren, damit nicht ein Aspekt alle Plaetze belegt
        suspend fun rankAcrossAspects(): List<RankedChunk> {
            // Kleine Suchabschnitte eigener Dokumente: doppelt so viele, mehrere davon fallen auf denselben Absatz
            val pickLimit = if (parentOf.isEmpty()) profile.finalChunks else profile.finalChunks * 2
            val queries = listOf(question) + listOfNotNull(hydeQuery) + aspectQueries.take(if (detailed) 9 else 5)
            // Eigene Dokumente mit Reranker: breiter Kandidatenkreis je Teilfrage, der Cross-Encoder waehlt daraus
            val rerank = reranker?.isReady == true && cfg.useReranker
            val poolK = if (rerank && parentOf.isNotEmpty()) maxOf(pickLimit, 60) else pickLimit
            var lists = queries.map { q ->
                retriever.retrieveTopChunks(q, topK = poolK, maxPerDomain = profile.maxPerDomain,
                    capFor = { c -> if (poolK > pickLimit && c.sourceType == SourceType.LOCAL) poolK else capOf(c) })
            }
            // Cross-Encoder sortiert jede Liste neu: Frage und Abschnitt werden gemeinsam bewertet
            if (rerank) {
                emit(executedRounds.coerceAtLeast(1), "st_rerank", tr("d_rerank", lists.sumOf { it.size }))
                lists = queries.zip(lists).map { (q, list) ->
                    if (list.size < 2) return@map list
                    val scores = reranker.score(q, list.map { "${it.chunk.titleWithSection}. ${it.chunk.rawText}" }) ?: return@map list
                    val order = list.indices.sortedByDescending { scores[it] }
                    order.map { list[it] }
                }
            }
            val picked = LinkedHashMap<Int, RankedChunk>()
            val perDomain = HashMap<String, Int>()
            val maxLen = lists.maxOfOrNull { it.size } ?: 0
            loop@ for (i in 0 until maxLen) {
                for (list in lists) {
                    if (picked.size >= pickLimit) break@loop
                    val rc = list.getOrNull(i) ?: continue
                    if (rc.chunk.chunkId in picked) continue
                    val d = rc.chunk.domain
                    if ((perDomain[d] ?: 0) >= capOf(rc.chunk)) continue
                    perDomain[d] = (perDomain[d] ?: 0) + 1
                    picked[rc.chunk.chunkId] = rc
                }
            }
            // Selbst festgelegte Wortzahl: reicht die Vielfalt der Websites nicht, fuellen weitere Abschnitte
            // derselben Websites auf (ein Fachwiki kann die einzige gute Quelle sein)
            if (spec.words > 0 && picked.size < pickLimit) {
                fill@ for (i in 0 until maxLen) for (list in lists) {
                    if (picked.size >= pickLimit) break@fill
                    val rc = list.getOrNull(i) ?: continue
                    picked.putIfAbsent(rc.chunk.chunkId, rc)
                }
            }
            // Eigene Dokumente ohne Themenfilter: Abschnitte, die zur eigentlichen Frage weit hinter dem besten liegen,
            // kommen ueber eine abwegige Teilfrage herein und fallen weg (mindestens 4 bleiben)
            if (rerank && parentOf.isNotEmpty() && picked.size > 4) {
                val list = picked.values.toList()
                val s = reranker!!.score(question, list.map { "${it.chunk.titleWithSection}. ${it.chunk.rawText}" })
                if (s != null) {
                    val best = s.maxOrNull() ?: 0f
                    val order = list.indices.sortedByDescending { s[it] }
                    // Nach Bewertung zur Frage sortiert: nur die vorderen Absaetze gehen an das Modell
                    return order.filterIndexed { rank, i -> rank < 4 || s[i] >= best - RELEVANCE_MARGIN }.map { list[it] }
                }
            }
            return picked.values.toList()
        }

        // === ITERATIVE RESEARCH-SCHLEIFE (max. 1-3 Runden) ===
        val subtopicQueries = LinkedHashSet<String>()
        if (detailed) plan.queries.drop(profile.queries).forEach { subtopicQueries.add(it.trim()) }
        if (docsOnly) executedRounds = 1
        for (round in 1..(if (docsOnly) 0 else searchRounds)) {
            executedRounds = round

            // === STUFE 2: Parallele Suche (APIs + Websuche gleichzeitig) ===
            emit(round, "st_search", currentQueries.joinToString(" • "), args = listOf(round))
            currentQueries.forEach { live(com.veritas.localresearch.domain.LiveKind.SEARCH, it) }
            val newDocs = mutableListOf<ScrapedDocument>()
            val candidateUrls = mutableListOf<Pair<String, String>>()

            coroutineScope {
                val apiJobs = if (round == 1) {
                    buildList {
                        // Englische Quellen (Wikipedia EN, arXiv) nur, wenn die Frage englisch ist oder die Sprache offen bleibt
                        val englishOk = qLang == null || qLang.code == "en"
                        if ((plan.useWikipedia || detailed) && cfg.useWikipedia) {
                            add(async { factApis.fetchWikipedia(currentQueries.first(), lang = qLang?.wiki ?: com.veritas.localresearch.i18n.I18n.code.let { if (it == "nb") "no" else it }, limit = 2) })
                            if (qLang == null) add(async { factApis.fetchWikipedia(englishQuery, lang = "en", limit = 1) })
                        }
                        if (englishOk && plan.useArxiv && cfg.useArxiv) {
                            add(async { factApis.fetchArxiv(englishQuery, maxResults = 2) })
                        }
                    }
                } else {
                    emptyList()
                }

                val webSearchJobs = currentQueries.map { q ->
                    async { webScraper.searchWeb(q, maxResults = 4, lang = qLang) }
                }

                apiJobs.awaitAll().flatten().forEach { doc ->
                    if (visitedUrls.add(doc.url)) { newDocs.add(doc); liveDoc(doc.url, doc) }
                }

                // Ergebnisse abwechselnd je Suchanfrage einsammeln, damit jeder Teilaspekt Quellen bekommt
                val perQuery = (if (round == 1) listOf(earlyResults) else emptyList()) + webSearchJobs.awaitAll()
                val webResults = buildList {
                    val maxLen = perQuery.maxOfOrNull { it.size } ?: 0
                    for (i in 0 until maxLen) perQuery.forEach { list -> list.getOrNull(i)?.let { add(it) } }
                }
                for (r in webResults) {
                    if (r.url !in visitedUrls && candidateUrls.none { it.first == r.url }) {
                        candidateUrls.add(r.url to r.title)
                    }
                }
            }

            // Paralleles Scraping (max. 4 gleichzeitig via OkHttp+Jsoup Fast-Path, WebView nur als Fallback)
            val urlsToScrape = candidateUrls
                .filter { visitedUrls.add(it.first) }
                .take(if (round == 1) profile.pagesFirstRound else profile.pagesLaterRounds)

            if (urlsToScrape.isNotEmpty()) {
                val scrapeSemaphore = Semaphore(4)
                val completed = AtomicInteger(0)
                emit(round, "st_load", tr("d_load", urlsToScrape.size), args = listOf(0, urlsToScrape.size))

                val scrapedWebDocs = coroutineScope {
                    urlsToScrape.map { (url, title) ->
                        async {
                            scrapeSemaphore.withPermit {
                                val domain = HiddenWebScraper.extractDomain(url)
                                val doc = webScraper.scrapePage(url, title)
                                liveDoc(url, doc)
                                val done = completed.incrementAndGet()
                                emit(round, "st_load", domain, args = listOf(done, urlsToScrape.size))
                                doc
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
                newDocs.addAll(scrapedWebDocs)
            }

            // === STUFE 3: Chunking & Hybrid-RAG-Indexierung (BM25 + Batched ONNX) ===
            // Seiten in fremder Sprache aussortieren; nur wenn dann fast nichts bleibt, bleiben sie drin
            if (qLang != null && newDocs.isNotEmpty()) {
                val sameLang = newDocs.filterNot { LanguageDetector.isOtherLanguage(it.title + " " + it.cleanText.take(3000), qLang) }
                if (sameLang.size >= 2 || retriever.totalChunks() > 0) {
                    newDocs.clear()
                    newDocs.addAll(sameLang)
                }
            }
            // Unterthemen aus den Abschnittsueberschriften der Wikipedia-Artikel (nach dem Vorbild von STORM, das
            // die Gliederungen verwandter Artikel nutzt); die Marker "== X ==" werden dabei zu normalen Zeilen.
            if (newDocs.any { it.sourceType == SourceType.WIKIPEDIA }) {
                val cleaned = newDocs.map { d ->
                    if (d.sourceType != SourceType.WIKIPEDIA) return@map d
                    val topic = d.title.removePrefix("Wikipedia: ").trim()
                    if (detailed) {
                        WIKI_HEADING.findAll(d.cleanText).map { it.groupValues[1].trim() }
                            .filter { h -> h.length in 4..60 && h.lowercase() !in GENERIC_HEADINGS }
                            .take(8)
                            .forEach { h -> subtopicQueries.add("$topic $h") }
                    }
                    d.copy(cleanText = WIKI_HEADING.replace(d.cleanText) { m -> m.groupValues[1].trim() + "\n" })
                }
                newDocs.clear()
                newDocs.addAll(cleaned)
            }
            if (newDocs.isNotEmpty()) {
                cachedFinalRanking = null
                totalScrapedDocs += newDocs.size
                newDocs.forEach { scrapedDomains.add(it.domain) }
                emit(round, "st_index", tr("d_index", newDocs.size))
                val chunks = newDocs.flatMap { chunker.chunkDocument(it) }
                retriever.indexChunks(chunks)
            } else if (round > 1 && retriever.totalChunks() > 0) {
                // Keine neuen Dokumente in Folgerunde gefunden -> weitere Runden sparen
                break
            }

            // Luecken-Check zwischen Runde 1 und Runde 2/3
            if (round < searchRounds) {
                val previewChunks = rankAcrossAspects()
                if (previewChunks.isNotEmpty()) {
                    cachedFinalRanking = previewChunks
                    emit(round, "st_gap", tr("d_gap"), args = listOf(round))
                    val gap = checkInformationGaps(question, previewChunks.take(6).map { it.chunk.rawText })
                    // Ausfuehrlich: noch nicht gesuchte Unterthemen kommen vor die Luecken-Anfragen des Modells
                    val pendingSubtopics = subtopicQueries.filter { q -> aspectQueries.none { it.equals(q, ignoreCase = true) } }
                    val nextQueries = (pendingSubtopics.take(4) + gap.followUpQueries)
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .filter { q -> currentQueries.none { it.equals(q, ignoreCase = true) } }
                        .distinct()
                        .take(if (detailed) 6 else 3)
                    if ((gap.sufficient && pendingSubtopics.isEmpty()) || nextQueries.isEmpty()) {
                        break
                    }
                    currentQueries = nextQueries
                    aspectQueries.addAll(nextQueries)
                }
            }
        }

        // Frage ohne erkennbare Sprache (kurz, viele Eigennamen): es gilt die Sprache der geladenen Quellen, sonst
        // schreibt das Modell in einer anderen Sprache als die Belege und jeder Satz faellt durch die Pruefung
        if (qLang == null && !docsOnly) sourcesLanguage()?.let { answerLanguage = it.name }

        // === Finale Kontext-Diaet: Top 8 Chunks (max. 2 pro Domain, Cross-Source-gewichtet) ===
        // Ueberblick: Abschnitte gleichmaessig ueber jedes Dokument verteilt statt nach Aehnlichkeit zur Frage
        val overviewPick = if (overview) overviewChunks(profile.finalChunks * 2, question) else null
        val topRankedSmall = if (overviewPick != null) {
            emit(executedRounds, "st_select", tr("d_select"))
            overviewPick
        } else cachedFinalRanking ?: run {
            emit(executedRounds, "st_retrieve", tr("d_retrieve", profile.finalChunks))
            rankAcrossAspects()
        }
        // Eigene Dokumente: statt des kleinen Suchabschnitts geht der ganze Absatz an das Modell (je Absatz einmal)
        topRanked = if (parentOf.isEmpty()) topRankedSmall else topRankedSmall
            .map { rc -> parentOf[rc.chunk.chunkId]?.let { rc.copy(chunk = it) } ?: rc }
            .distinctBy { it.chunk.chunkId }
            .take(maxOf(4, profile.finalChunks * 2 / 3))

        if (topRanked.isEmpty()) {
            onProgress(
                PipelineProgress(
                    currentRound = executedRounds,
                    maxRounds = maxRounds,
                    stageTitle = tr("done"),
                    detail = tr("done_none"),
                    scrapedDomains = scrapedDomains.toList(),
                    chunkCount = retriever.totalChunks(),
                    isRunning = false
                )
            )
            return finish(ResearchReport(
                question = question,
                synthesizedAnswer = if (docsOnly) tr("ans_none_docs") else tr("ans_none_web"),
                verifiedClaims = emptyList(),
                rejectedClaimCount = 0,
                unverifiedNumbers = emptyList(),
                roundsExecuted = executedRounds,
                totalSourcesScraped = totalScrapedDocs,
                totalChunksIndexed = retriever.totalChunks(),
                usedChunks = emptyList()
            ))
        }

        // === STUFE 4a: Quote-First Fakten-Extraktion (striktes GBNF-JSON, Temp 0.0) ===
        emit(executedRounds, "st_extract", tr("d_extract"))
        // Absaetze sind fast doppelt so lang wie Webabschnitte: weniger je Durchgang, damit der Prompt in n_ctx passt
        val perPass = if (parentOf.isEmpty()) profile.chunksPerPass else maxOf(3, profile.chunksPerPass * 5 / 9)
        val passes = topRanked.map { it.chunk }.chunked(perPass)
        val rawClaims = passes.flatMapIndexed { i, group ->
            if (passes.size > 1) emit(executedRounds, "st_extract_n", tr("d_extract_n", group.size), args = listOf(i + 1, passes.size))
            extractClaimsWithQuotes(question, group, (profile.claimsPerPass + length.extraFacts / 2).coerceIn(3, 12)).claims
        }

        // === STUFE 5a: Deterministische Quote- & Cross-Source-Verifikation VOR Synthese ===
        emit(executedRounds, "st_verify", tr("d_verify"))
        val chunkMap = topRanked.associate { it.chunk.chunkId to it.chunk }
        val firstPass = verifier.verifyClaims(
            rawClaims = rawClaims,
            chunkMap = chunkMap,
            allIndexedChunks = retriever.getAllChunks(),
            sameTopicThreshold = retriever.sameTopicThreshold
        )
        firstPass.rejections.forEach { Log.i("VeritasReject", it.take(400)) }
        liveClaims(firstPass.verifiedClaims, firstPass.rejections)
        // Liefert das Modell zu wenig Brauchbares, kommen die am besten passenden Quellsaetze woertlich dazu
        // (vom Reranker gegen die Frage bewertet). Woertliche Saetze koennen nichts erfinden.
        // Hauptthema der Frage: Fakten zaehlen nur aus Quellen, die es behandeln (bei "Solarstrom und Solarspeicher …" muss
        // die Seite Solarstrom oder Solarspeicher nennen; ein Kapitel ueber "rechtliche Hürden" im Allgemeinen nicht)
        // Eigene Dokumente hat der Nutzer selbst gewaehlt: dort entfaellt der Themencheck
        val family = if (!docsOnly && answerLanguage == LanguageDetector.GERMAN.name) TopicHeuristics.topicFamily(question) else emptyList()
        val allChunks = retriever.getAllChunks()
        val docWords = if (family.isEmpty()) emptyMap() else allChunks.groupBy { it.url }
            .mapValues { (_, cs) -> cs.joinToString(" ") { "${it.title} ${it.rawText}" }.lowercase() }
        // Thema im Abschnitt selbst oder im Seitentitel genannt, oder mehrfach auf der Seite (ein Nebensatz reicht nicht)
        fun chunkAboutTopic(c: TextChunk): Boolean {
            if (family.isEmpty()) return true
            if (TopicHeuristics.mentions(family, "${c.title} ${c.rawText}".lowercase()) > 0) return true
            return TopicHeuristics.mentions(family, docWords[c.url].orEmpty()) >= 3
        }
        // Fakt: das Thema steht in der Aussage selbst oder im Seitentitel, oder die Seite behandelt es mehrfach.
        // Ein Abschnitt, der das Thema nur nebenbei nennt (eine Aufzählung "Wind-, Wasser- und Solarstrom" auf einer Seite über Netze), reicht nicht.
        fun aboutTopic(vc: VerifiedClaim): Boolean {
            if (family.isEmpty()) return true
            if (TopicHeuristics.mentions(family, "${vc.claim} ${vc.exactQuote} ${vc.chunk.title}".lowercase()) > 0) return true
            return TopicHeuristics.mentions(family, docWords[vc.chunk.url].orEmpty()) >= 3
        }

        val minFacts = if (length.words > 0) length.factTarget else (3 + maxRounds + length.extraFacts).coerceAtLeast(2)
        val usableFirst = firstPass.verifiedClaims.count(::aboutTopic)
        val combined = if (usableFirst >= minFacts) firstPass else {
            emit(executedRounds, "st_verify", tr("d_verify_add"))
            // Quellsaetze aus den besten Abschnitten zum Hauptthema, danach aus allen weiteren geladenen dazu
            val pool = (topRanked.map { it.chunk } + allChunks).distinctBy { it.chunkId }
            val onTopicPool = pool.filter(::chunkAboutTopic).let { if (it.size >= 3) it else pool }
            val extra = extractiveClaims(question, onTopicPool, minFacts + 2 - usableFirst,
                firstPass.verifiedClaims.map { it.exactQuote })
            if (extra.isEmpty()) firstPass else {
                val merged = verifier.verifyClaims(
                    rawClaims = rawClaims + extra,
                    chunkMap = chunkMap,
                    allIndexedChunks = retriever.getAllChunks(),
                    sameTopicThreshold = retriever.sameTopicThreshold
                )
                merged.copy(rejectedCount = firstPass.rejectedCount)
            }
        }
        // Themenfremdes raus: ein Fakt muss mindestens einen Suchbegriff der Frage enthalten und zum Hauptthema gehoeren
        val wordOk = if (docsOnly) combined.verifiedClaims
            else combined.verifiedClaims.filter { questionWordHits(question, "${it.claim} ${it.exactQuote}") >= 1 }
        val topicOk = wordOk.filter(::aboutTopic)
        (wordOk - topicOk.toSet()).forEach { Log.i("VeritasReject", "Quelle ohne Hauptthema $family: ${it.chunk.url}") }
        // Sicherung: bleibt durch den Themencheck fast nichts, war das Hauptthema falsch erkannt
        val onTopic = if (topicOk.size >= 3 || topicOk.size * 2 >= wordOk.size) topicOk else wordOk
        onTopic.size.let { n -> if (n < combined.verifiedClaims.size) Log.i("VeritasReject", "themenfremd: ${combined.verifiedClaims.size - n}") }
        verification = combined.copy(
            verifiedClaims = onTopic.mapIndexed { i, vc -> vc.copy(citationIndex = i + 1) },
            rejectedCount = combined.rejectedCount + (combined.verifiedClaims.size - onTopic.size)
        )
        }

        if (verification.verifiedClaims.isEmpty()) {
            val fallbackMsg = if (docsOnly) tr("ans_noquote_docs") else tr("ans_noquote_web")
            onStreamToken(fallbackMsg)
            onProgress(
                PipelineProgress(
                    currentRound = executedRounds,
                    maxRounds = maxRounds,
                    stageTitle = tr("done_zero"),
                    detail = tr("done_zero_d", verification.rejectedCount),
                    scrapedDomains = scrapedDomains.toList(),
                    chunkCount = retriever.totalChunks(),
                    verifiedClaimsCount = 0,
                    rejectedClaimsCount = verification.rejectedCount,
                    isRunning = false
                )
            )
            return finish(ResearchReport(
                question = question,
                synthesizedAnswer = fallbackMsg,
                verifiedClaims = emptyList(),
                rejectedClaimCount = verification.rejectedCount,
                unverifiedNumbers = emptyList(),
                roundsExecuted = executedRounds,
                totalSourcesScraped = totalScrapedDocs,
                totalChunksIndexed = retriever.totalChunks(),
                usedChunks = topRanked
            ))
        }

        // === STUFE 4b: Synthese ausschliesslich aus geprueften Fakten ===
        emit(
            executedRounds,
            "st_synth",
            tr("d_synth", verification.verifiedClaims.size),
            verified = verification.verifiedClaims.size,
            rejected = verification.rejectedCount
        )
        // Geplante Recherche: Kapitel fuer Kapitel aus den dort gesammelten Fakten.
        // Sonst ab Standard-Tiefe und genug Fakten: erst Gliederung, dann Abschnitt fuer Abschnitt
        val rawAnswer = (if (planned) writeChapters(
            question, plannedChapters,
            onSection = { i, n ->
                emit(executedRounds, "st_write", tr("d_write"),
                    verified = verification.verifiedClaims.size, rejected = verification.rejectedCount, args = listOf(i, n))
            },
            onStreamToken = onStreamToken
        ) else if (length.outline && verification.verifiedClaims.size >= 4 && (maxRounds >= 2 || length.detailed || length.words > 0)) {
            emit(executedRounds, "st_outline", tr("d_outline", verification.verifiedClaims.size),
                verified = verification.verifiedClaims.size, rejected = verification.rejectedCount)
            outlineAndWrite(
                question, verification.verifiedClaims, synthesisTokens,
                onSection = { i, n ->
                    emit(executedRounds, "st_write", tr("d_write"),
                        verified = verification.verifiedClaims.size, rejected = verification.rejectedCount, args = listOf(i, n))
                },
                onStreamToken = onStreamToken
            )
        } else null) ?: synthesizeAnswer(question, verification.verifiedClaims, synthesisTokens, onStreamToken)

        // === STUFE 5b: Deterministischer Zahlen-, Datums- & Zitat-Post-Check ===
        emit(
            executedRounds,
            "st_check",
            tr("d_check"),
            verified = verification.verifiedClaims.size,
            rejected = verification.rejectedCount
        )
        // Jeder Satz muss sich auf einen geprueften Fakt stuetzen; erfundene Saetze und Zitate fliegen raus
        // Kleine Modelle (bis etwa 2B) zerlegen beim Umformulieren oft Satzbau und Sinn: fuer sie gilt eine strengere
        // Wortlautgrenze, ab 3B duerfen Saetze freier formuliert sein. Unbekannte Groesse liegt dazwischen.
        if (planned) Log.i("VeritasPlan", "Rohtext ${rawAnswer.length} Zeichen, ${rawAnswer.lines().count { it.startsWith("## ") }} Überschriften")
        val minShare = wordingThreshold(llama.loadedModelName.orEmpty())
        val verbatim = minShare >= 0.6f
        val groundedAnswer = verifier.groundAnswer(
            verifier.sanitizeCitations(rawAnswer, verification.verifiedClaims.size),
            verification.verifiedClaims,
            extraVocab = question,
            onDrop = { Log.i("VeritasReject", "Antwortsatz: ${it.take(300)}") },
            minShare = minShare
        ).let { text ->
            // Gepruefte Fakten, die das Modell beim Schreiben ausgelassen hat, gehen nicht verloren. Was inhaltlich
            // schon dasteht, kommt nicht doppelt; bei gegliederten Antworten steht der Rest unter eigener Ueberschrift.
            val cited = Regex("\\[(\\d+)]").findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()
            val missing = verifier.appendixSentences(text,
                verifier.notYetCovered(text, verification.verifiedClaims.filter { it.citationIndex !in cited }), verbatim)
            if (missing.isEmpty() || length.short) text
            else {
                val title = if (answerLanguage == LanguageDetector.GERMAN.name) "Weitere Befunde" else "Further findings"
                val heading = if (text.lines().any { it.startsWith("## ") }) "## $title\n" else ""
                text + "\n\n" + heading + missing.joinToString(" ")
            }
        }
        // Nur Quellen auffuehren, die in der Antwort auch vorkommen; Nummern in Reihenfolge des Auftretens
        val (citedAnswer, usedClaims) = verifier.keepCitedOnly(
            verifier.sanitizeCitations(groundedAnswer, verification.verifiedClaims.size),
            verification.verifiedClaims
        )
        // Ueberschriften ohne "## " im Text, ihre Positionen gehen getrennt an die Anzeige
        val headingRanges = ArrayList<IntRange>()
        val sanitizedAnswer = buildString {
            citedAnswer.lines().forEachIndexed { i, line ->
                if (i > 0) append('\n')
                if (line.startsWith("## ")) {
                    val title = line.removePrefix("## ").trim()
                    headingRanges.add(length until length + title.length)
                    append(title)
                } else append(line)
            }
        }
        val unverifiedNumbers = verifier.detectUnverifiedNumbers(sanitizedAnswer, usedClaims)

        onProgress(
            PipelineProgress(
                currentRound = executedRounds,
                maxRounds = maxRounds,
                stageTitle = tr("done_verified"),
                detail = tr("done_verified_d", usedClaims.size, unverifiedNumbers.size),
                scrapedDomains = scrapedDomains.toList(),
                chunkCount = retriever.totalChunks(),
                verifiedClaimsCount = usedClaims.size,
                rejectedClaimsCount = verification.rejectedCount,
                isRunning = false
            )
        )

        return finish(ResearchReport(
            question = question,
            synthesizedAnswer = sanitizedAnswer,
            verifiedClaims = usedClaims,
            rejectedClaimCount = verification.rejectedCount,
            unverifiedNumbers = unverifiedNumbers,
            roundsExecuted = executedRounds,
            totalSourcesScraped = totalScrapedDocs,
            totalChunksIndexed = retriever.totalChunks(),
            usedChunks = topRanked,
            headingRanges = headingRanges,
            wordTarget = length.words
        ))
    }

    private suspend fun planQueries(question: String, maxQueries: Int, detailed: Boolean = false, forDocs: Boolean = false): QueryPlanDto {
        val sys = if (forDocs) "Du bist ein präziser Such-Planer für Fachtexte. Die Antwort wird in einem Dokument (z. B. Lehrbuch, Skript, Bericht) " +
            "gesucht. Formuliere 2 bis $maxQueries kurze Suchanfragen (queries), mit denen sich die passende Textstelle findet: die Fachbegriffe " +
            "der Frage, ihre gängigen Synonyme und die Unterbegriffe, mit denen ein Lehrbuch das Thema erklärt (z. B. zu Photosynthese: " +
            "Lichtreaktion, Calvin-Zyklus, Chlorophyll). Keine Anfragen zu aktuellem Stand, Studien oder Praxisbeispielen, wenn die Frage nicht " +
            "danach fragt. Formuliere auf $answerLanguage, dazu 1 kurze englische Suchanfrage (en_query). Antworte ausschließlich als JSON."
        else "Du bist ein präziser Such-Planer. Zerlege die Nutzerfrage in ihre Teilaspekte und formuliere für jeden Teilaspekt eine eigene kurze Web-Suchanfrage (queries, 2 bis $maxQueries Stück, keine Wiederholungen, Fachbegriffe verwenden). Bei Fragen nach Stand, Einflüssen, Ursachen oder Vergleichen deckt jede Anfrage einen anderen Aspekt ab (z. B. aktueller Stand, Einflussfaktoren, Studien, Praxisbeispiele, Probleme). Formuliere alle queries auf $answerLanguage, in der Sprache der Frage, nicht übersetzt. Dazu 1 kurze englische Suchanfrage (en_query) für Wikipedia EN / arXiv. Antworte ausschließlich als JSON." +
            (if (detailed) " Die Antwort soll ausführlich wie eine wissenschaftliche Arbeit werden: Hänge an die queries zusätzlich bis zu 4 Anfragen zu Unterthemen und Teilgebieten an, die zum Thema gehören, auch wenn die Frage sie nicht nennt (z. B. zu Solarenergie: Photovoltaik, Stromspeicher, Netzeinspeisung, Förderung)." else "")
        val usr = "Nutzerfrage: $question\nEntscheide außerdem, ob Wikipedia (Fakten/Überblick) und arXiv (Wissenschaft/Forschung) sinnvoll sind."
        val out = llama.generate(
            systemPrompt = sys,
            userPrompt = usr,
            gbnfGrammar = GbnfGrammars.QUERY_PLAN,
            temperature = 0.05f,
            maxTokens = 60 + 30 * maxQueries + (if (detailed) 120 else 0)
        )
        return runCatching { json.decodeFromString<QueryPlanDto>(out) }
            .getOrDefault(QueryPlanDto(queries = listOf(question), englishQuery = question, useWikipedia = true, useArxiv = false))
    }

    /** Zwei bis drei Saetze, wie ein Lehrbuch die Frage beantworten wuerde (nur fuer die Suche). */
    private suspend fun hypotheticalPassage(question: String): String? {
        val sys = "Schreibe auf $answerLanguage zwei bis drei sachliche Sätze, wie ein Lehrbuch oder Handbuch die Frage beantworten " +
            "würde, mit den Fachbegriffen, Paragraphen und Zahlen, die dort typischerweise stehen. Keine Einleitung, keine Rückfrage."
        val out = llama.generate(systemPrompt = sys, userPrompt = question, temperature = 0.05f, maxTokens = 110)
            .replace(Regex("\\s+"), " ").trim()
        return out.takeIf { it.length >= 40 }?.take(600)
    }

    private suspend fun checkInformationGaps(question: String, snippets: List<String>): GapCheckDto {
        val sys = "Du prüfst, ob die bisherigen Textauszüge ausreichen, um die Nutzerfrage präzise zu beantworten. Antworte strikt als JSON."
        val context = snippets.take(6).mapIndexed { i, s -> "[$i] ${s.take(300)}" }.joinToString("\n")
        val usr = "Frage: $question\n\nBisherige Auszüge:\n$context\n\nDeckt das alle Teilaspekte der Frage ab? Nenne fehlende Aspekte als neue Suchanfragen. Setze sufficient=true nur, wenn wirklich jeder Teilaspekt belegt ist."
        val out = llama.generate(
            systemPrompt = sys,
            userPrompt = usr,
            gbnfGrammar = GbnfGrammars.GAP_CHECK,
            temperature = 0.05f,
            maxTokens = 120
        )
        return runCatching { json.decodeFromString<GapCheckDto>(out) }
            .getOrDefault(GapCheckDto(sufficient = true))
    }

    private suspend fun extractClaimsWithQuotes(
        question: String,
        chunks: List<TextChunk>,
        maxClaims: Int
    ): ClaimExtractionDto {
        val sys = """
            Du bist ein strenger Fakten-Extraktor.
            Regeln:
            1. Extrahiere NUR Aussagen, die direkt zur Beantwortung der Frage beitragen.
            2. Jede Aussage MUSS eine kurze, wörtliche, unveränderte Belegstelle (exact_quote, ca. 8–20 Wörter) exakt aus dem zugehörigen chunk_id enthalten.
            3. Erfinde KEINE Zahlen, Daten oder Fakten. Wenn ein Chunk nichts Relevantes enthält, ignoriere ihn.
            3a. Schreibe die Aussage (claim) in der Sprache des Chunks, nicht übersetzt. Sie gibt nur wieder, was im Zitat steht. Füge keine Begriffe aus der Frage hinzu, die im Chunk nicht vorkommen (z. B. ein Land oder eine Organisation, von der der Chunk nicht spricht).
            4. Gib maximal $maxClaims präzise Claims als kompaktes JSON zurück, möglichst aus verschiedenen Chunks und zu verschiedenen Teilaspekten der Frage.
        """.trimIndent() + (if (overview) "\n5. Die Frage verlangt einen Überblick: Extrahiere die zentralen Aussagen der Texte (Thema, Kernthesen, Ergebnisse, Begriffe), keine Nebensächlichkeiten." else "")

        val chunksBlock = chunks.joinToString("\n\n") { c ->
            "CHUNK_ID=${c.chunkId} | QUELLE=${c.domain} | TITEL=${c.titleWithSection}\n${c.rawText}"
        }
        val usr = "Frage: $question\n\nQuellen-Chunks:\n$chunksBlock"

        val out = llama.generate(
            systemPrompt = sys,
            userPrompt = usr,
            gbnfGrammar = GbnfGrammars.CLAIM_EXTRACTION,
            temperature = 0.0f,
            maxTokens = 90 * maxClaims
        )
        return runCatching { json.decodeFromString<ClaimExtractionDto>(out) }
            .getOrDefault(ClaimExtractionDto())
    }

    private suspend fun synthesizeAnswer(
        question: String,
        verifiedClaims: List<VerifiedClaim>,
        maxTokens: Int,
        onStreamToken: (String) -> Unit
    ): String {
        val sys = """
            Du schreibst eine präzise, gut strukturierte Antwort auf $answerLanguage ausschließlich auf Basis der gelieferten GEPRÜFTEN FAKTEN.
            Harte Regeln:
            - Verwende KEIN eigenes Vorwissen und erfinde KEINE neuen Zahlen, Jahreszahlen oder Details.
            - Setze hinter jede Aussage die passende Quellennummer einzeln im Format [1] [2] (nicht [1, 2]).
            - Priorisiere Fakten, die als „mehrfach belegt“ markiert sind.
            - Schreibe keine Zitate ab und keine Kennzeichnungen wie „Quelle“ oder „Beleg“, nur deine Sätze mit Nummern.
            - Nutze ALLE gelieferten Fakten, die zur Frage passen, und lasse keinen Teilaspekt der Frage aus.
            ${if (length.short) "- Antworte knapp in einem Absatz mit ${length.sentences} Sätzen, ohne Überschriften." else "- Gliedere bei mehreren Teilaspekten in Abschnitte mit kurzen Überschriften (z. B. Stand, Einflussfaktoren, Beispiele, offene Punkte)."}
            - Fehlen Belege zu einem Teilaspekt, schreibe das ausdrücklich, statt Wissen zu ergänzen.
            - Bleibe sachlich und vollständig, ohne Füllsätze.
        """.trimIndent()

        val factsBlock = verifiedClaims.joinToString("\n") { vc -> factLine(vc) }
        val usr = "Frage: $question\n\nGEPRÜFTE FAKTEN:\n$factsBlock\n\nFormuliere jetzt die Antwort mit Inline-Zitaten [1] [2]:"

        return llama.generate(
            systemPrompt = sys,
            userPrompt = usr,
            gbnfGrammar = null,
            temperature = 0.1f,
            // Ohne Gliederung schreibt das Modell alles in einem Zug: innerhalb des Kontextfensters bleiben
            maxTokens = maxTokens.coerceAtMost(2500),
            onToken = onStreamToken
        )
    }

    /**
     * Waehlt woertliche Saetze aus den besten Abschnitten als Fakten: Saetze mit 8 bis 45 Woertern, vom
     * Cross-Encoder gegen die Frage bewertet (ohne Reranker nach Wortueberlappung), hoechstens zwei je Domain.
     */
    private suspend fun extractiveClaims(
        question: String,
        chunks: List<TextChunk>,
        want: Int,
        alreadyQuoted: List<String>,
        /** Nur Saetze, die hoechstens so weit hinter dem besten liegen (Reranker-Bewertung); null = keine Grenze. */
        margin: Float? = null,
        /** Geplante Recherche: ein Suchbegriff je Satz genuegt, keine Grenze je Website (der Reranker waehlt). */
        loose: Boolean = false
    ): List<com.veritas.localresearch.domain.RawClaimDto> {
        if (want <= 0) return emptyList()
        // Nur Saetze mit genug Suchbegriffen der Frage
        val qWords = questionWords(question)
        val need = if (overview || crossLang) 0 else if (loose) 1 else if (qWords.size >= 3) 2 else 1
        val candidates = chunks.flatMap { c ->
            val joined = com.veritas.localresearch.verification.joinSoftBreaks(c.rawText)
            val pieces = joined.split(SENTENCE_END).map { it.trim() }
            // Letztes Stueck eines mitten im Satz getrennten Abschnitts ist kein ganzer Satz
            val usable = if (com.veritas.localresearch.verification.endsMidSentence(joined)) pieces.dropLast(1) else pieces
            usable
                .filter { s ->
                    val n = s.split(SPACES).size.let { if (it < 3) s.length / 2 else it }
                    n in 8..45 && s.count(Char::isLetter) > s.length / 2 &&
                        !NOT_A_STATEMENT.containsMatchIn(s) && !OPEN_FRAGMENT.containsMatchIn(s) && !isBoilerplate(s) &&
                        s.first().isLetter() && !s.first().isLowerCase() && !s.endsWith("?") && !s.endsWith("？") &&
                        (qWords.isEmpty() || questionWordHits(question, s) >= need) &&
                        alreadyQuoted.none { q -> q.contains(s) || s.contains(q) }
                }
                .map { c to it }
        }.distinctBy { it.second }.take(if (length.words > 0) 200 else 80)
        if (candidates.isEmpty()) return emptyList()
        val scores: FloatArray = (if (reranker?.isReady == true) reranker.score(question, candidates.map { it.second }) else null)
            ?: run {
                val qWords = question.lowercase().split(NON_WORD).filter { it.length > 3 }.map { it.take(5) }.toSet()
                FloatArray(candidates.size) { i ->
                    candidates[i].second.lowercase().split(NON_WORD).count { it.length > 3 && it.take(5) in qWords }.toFloat()
                }
            }
        val perDomain = HashMap<String, Int>()
        val floor = if (margin != null && reranker?.isReady == true) (scores.maxOrNull() ?: 0f) - margin else Float.NEGATIVE_INFINITY
        return candidates.indices.sortedByDescending { scores[it] }
            .filter { i -> scores[i] >= floor }
            .filter { i ->
                val d = candidates[i].first.domain
                val ok = docsOnly || loose || (perDomain[d] ?: 0) < (if (length.detailed) 3 else 2) + length.words / 1000
                if (ok) perDomain[d] = (perDomain[d] ?: 0) + 1
                ok
            }
            .take(want)
            .map { i ->
                val (c, s) = candidates[i]
                com.veritas.localresearch.domain.RawClaimDto(claim = s, exactQuote = s, chunkId = c.chunkId)
            }
    }

    @kotlinx.serialization.Serializable
    private data class QuestionsDto(val questions: List<String> = emptyList())

    /**
     * Ueberblick beim Import: eine kurze, zitatgepruefte Zusammenfassung (derselbe Weg wie jede Ueberblicksfrage,
     * ohne Quellennummern) und Fragevorschlaege. Die Fragen sind keine Aussagen ueber das Dokument und brauchen
     * daher keine Pruefung.
     */
    suspend fun sourceGuide(doc: LocalDoc, docStore: LocalDocumentStore, onProgress: (PipelineProgress) -> Unit): Pair<String, List<String>> {
        val text = docStore.text(doc.id)
        val lang = LanguageDetector.detect(text.take(4000))
        val german = lang == LanguageDetector.GERMAN || (lang == null && I18n.code == "de")
        val q = if (german) "Worum geht es in dem Dokument?" else "What is this document about?"
        val report = runResearch(q, maxRounds = 1, onProgress = onProgress, onStreamToken = {}, docs = listOf(doc),
            docStore = docStore, useWeb = false, lengthOverride = AnswerLength.SHORT)
        val summary = if (report.verifiedClaims.isEmpty()) "" else report.synthesizedAnswer
            .replace(Regex("\\s*\\[\\d+]"), "").lines().joinToString(" ") { it.trim() }.replace(Regex("\\s{2,}"), " ").trim()
        onProgress(PipelineProgress(stageTitle = tr("guide_q"), detail = doc.title, isRunning = true))
        val sample = overviewChunks(6).orEmpty().joinToString("\n\n") { it.chunk.rawText }.take(3500)
        val language = lang?.name ?: "Deutsch"
        val sys = "Du schlägst Fragen zu einem Dokument vor. Formuliere 4 kurze, konkrete Fragen auf $language, die sich mit dem Text " +
            "beantworten lassen, zu verschiedenen Teilen des Dokuments. Keine Ja-Nein-Fragen. Antworte ausschließlich als JSON."
        val out = llama.generate(sys, "Titel: ${doc.title}\n\nAuszüge:\n$sample", GbnfGrammars.QUESTIONS, temperature = 0.2f, maxTokens = 200)
        val questions = runCatching { json.decodeFromString<QuestionsDto>(out).questions }.getOrDefault(emptyList())
            .map { it.trim() }.filter { it.length in 8..160 && it.endsWith("?") }.distinct().take(4)
        return summary to questions
    }

    /** Je Dokument gleich viele Abschnitte, gleichmaessig vom Anfang bis zum Ende verteilt. */
    /**
     * Ueberblick: Abschnitte gleichmaessig ueber jedes Dokument. Nennt die Frage ein Kapitel ("Kapitel 3") oder ein
     * Thema aus den Ueberschriften, nur aus diesen Kapiteln. Nennt sie ein Thema, das keine Ueberschrift traegt, und
     * ist das Dokument gross, null: dann sucht die normale Auswahl die passenden Stellen.
     */
    private fun overviewChunks(total: Int, question: String = ""): List<RankedChunk>? {
        var byDoc = retriever.getAllChunks().groupBy { it.url }.values.toList()
        if (byDoc.isEmpty()) return emptyList()
        if (question.isNotBlank()) {
            val num = CHAPTER_REF.find(question)?.groupValues?.get(1)
            val topic = questionWords(question).filter { w -> !OVERVIEW.containsMatchIn(w) && w !in OVERVIEW_FILLER }
            fun inScope(c: TextChunk): Boolean {
                val s = c.section.lowercase()
                if (s.isEmpty()) return false
                if (num != null) return s.split(" › ").any { part ->
                    part.startsWith("$num ") || part.startsWith("$num.") || Regex("^(?:kapitel|teil|chapter|part|lektion|unit)\\s+$num\\b").containsMatchIn(part)
                }
                val words = s.split(NON_WORD).filter { it.length > 4 }
                return topic.any { q -> words.any { w -> sameWord(q, w) } }
            }
            if (num != null || topic.isNotEmpty()) {
                val scoped = byDoc.map { cs -> cs.filter(::inScope) }.filter { it.isNotEmpty() }
                if (scoped.isNotEmpty()) byDoc = scoped
                else if (topic.isNotEmpty() && byDoc.sumOf { it.size } > 400) return null
            }
        }
        val per = ((total + byDoc.size - 1) / byDoc.size).coerceAtLeast(2)
        return byDoc.flatMap { cs ->
            if (cs.size <= per) cs
            else (0 until per).map { k -> cs[(k.toLong() * (cs.size - 1) / (per - 1)).toInt()] }.distinctBy { it.chunkId }
        }.map { RankedChunk(it, null, 0f, 0, 0f) }
    }

    private fun questionWords(question: String): Set<String> =
        question.lowercase().split(NON_WORD).filter { it.length > 4 && it !in QUESTION_FILLER }.toSet()

    /**
     * Zahl der verschiedenen Woerter in [text], die zu einem Suchbegriff der Frage passen: gleicher Wortanfang
     * (Beugungen) oder Teil eines Fragewortes bzw. umgekehrt (Komposita: "Speicher" in "Solarspeicher").
     * Leere Frage-Wortliste zaehlt als Treffer.
     */
    private fun questionWordHits(question: String, text: String): Int {
        val qWords = questionWords(question)
        if (qWords.isEmpty()) return Int.MAX_VALUE
        return text.lowercase().split(NON_WORD).filter { it.length > 4 }.distinct().count { w ->
            qWords.any { q -> sameWord(q, w) }
        }
    }

    /**
     * Gleiches Wort bis auf die Endung ("Anlage"/"Anlagen") oder Teil eines Kompositums ("Speicher" in
     * "Solarspeicher"). "Solarium" passt nicht zu "Solarspeicher": der gemeinsame Anfang muss bis auf
     * hoechstens drei Buchstaben das kuerzere Wort sein.
     */
    private fun sameWord(q: String, w: String): Boolean {
        if (q == w) return true
        var p = 0
        while (p < q.length && p < w.length && q[p] == w[p]) p++
        if (p >= maxOf(5, minOf(q.length, w.length) - 3)) return true
        return (w.length >= 6 && q.contains(w)) || (q.length >= 6 && w.contains(q))
    }

    private fun factLine(vc: VerifiedClaim): String {
        val multi = if (vc.trustLevel == TrustLevel.MULTI_SOURCE) " (mehrfach belegt)" else ""
        return "[${vc.citationIndex}] ${vc.claim}$multi. Beleg: „${vc.exactQuote}“"
    }

    @kotlinx.serialization.Serializable
    private data class OutlineSection(val title: String = "", val facts: List<Int> = emptyList())

    @kotlinx.serialization.Serializable
    private data class OutlineDto(val sections: List<OutlineSection> = emptyList())

    /**
     * Schreiben in Schritten (nach dem Vorbild von STORM): erst eine Gliederung, die jeden geprueften Fakt einem
     * Abschnitt zuordnet, dann jeden Abschnitt einzeln nur aus seinen Fakten. So bleibt jeder Prompt kurz, und
     * kein Teilaspekt geht unter. Liefert null, wenn die Gliederung nicht brauchbar ist (dann normale Synthese).
     */
    private suspend fun outlineAndWrite(
        question: String,
        verifiedClaims: List<VerifiedClaim>,
        totalTokens: Int,
        onSection: (Int, Int) -> Unit,
        onStreamToken: (String) -> Unit
    ): String? {
        // Hoechstens 40 Fakten im Prompt (Kontextfenster); die uebrigen ordnet der Wortvergleich unten zu
        val outlineFacts = verifiedClaims.take(40)
        val factsBlock = outlineFacts.joinToString("\n") { "[${it.citationIndex}] ${it.claim}" }
        val detailed = length.detailed
        val maxSections = length.maxSections
        val sys = "Du gliederst eine Antwort. Bilde 2 bis $maxSections Abschnitte mit kurzen, sachlichen Überschriften auf $answerLanguage, " +
            "die zusammen alle Teilaspekte der Frage abdecken. Ordne jeden Fakt über seine Nummer genau einem passenden Abschnitt zu. " +
            (if (detailed) "Gliedere wie eine wissenschaftliche Arbeit: zuerst Begriff und Einordnung, dann je ein Abschnitt pro Unterthema " +
                "(z. B. einzelne Verfahren oder Anwendungsbereiche), dann Einflussfaktoren und Hindernisse, zuletzt offene Fragen. " else "") +
            "Antworte ausschließlich als JSON."
        val usr = "Frage: $question\n\nGEPRÜFTE FAKTEN:\n$factsBlock"
        val out = llama.generate(sys, usr, GbnfGrammars.OUTLINE, temperature = 0.05f, maxTokens = 60 + 25 * outlineFacts.size)
        val outline = runCatching { json.decodeFromString<OutlineDto>(out) }.getOrNull() ?: return null

        val byIndex = verifiedClaims.associateBy { it.citationIndex }
        val used = HashSet<Int>()
        val sections = outline.sections
            .map { s -> s.title.trim().take(80) to s.facts.filter { it in byIndex && used.add(it) } }
            .filter { it.first.isNotBlank() && it.second.isNotEmpty() }
            .take(maxSections)
            .toMutableList()
        if (sections.size < 2) return null
        // Nicht zugeordnete Fakten an den Abschnitt haengen, der die meisten Woerter mit ihnen teilt
        for (vc in verifiedClaims) {
            if (vc.citationIndex in used) continue
            val words = vc.claim.lowercase().split(Regex("\\W+")).filter { it.length > 3 }.toSet()
            val best = sections.indices.maxByOrNull { i ->
                sections[i].second.sumOf { idx -> byIndex[idx]!!.claim.lowercase().split(Regex("\\W+")).count { it in words } }
            } ?: 0
            sections[best] = sections[best].first to (sections[best].second + vc.citationIndex)
        }

        val perSection = (totalTokens / sections.size).coerceIn((160 * length.tokenFactor).toInt(), (400 * length.tokenFactor).toInt())
            .coerceAtMost(1400)
        val titles = sections.joinToString(" | ") { it.first }
        val answer = StringBuilder()
        for ((i, sec) in sections.withIndex()) {
            onSection(i + 1, sections.size)
            val heading = "## ${sec.first}\n"
            answer.append(heading)
            onStreamToken(heading)
            val secFacts = sec.second.mapNotNull { byIndex[it] }.joinToString("\n") { vc -> factLine(vc) }
            val secSys = """
                Du schreibst EINEN Abschnitt einer Antwort auf $answerLanguage, ausschließlich aus den gelieferten GEPRÜFTEN FAKTEN.
                - Schreibe keine Zitate ab und keine Kennzeichnungen wie „Quelle“ oder „Beleg“, nur deine Sätze mit Nummern.
                - Kein eigenes Vorwissen, keine neuen Zahlen oder Jahreszahlen.
                - Hinter jede Aussage die Quellennummer einzeln: [3] [5].
                - Keine Überschrift, keine Einleitung zur Gesamtfrage, keine Wiederholung anderer Abschnitte.
                - ${length.sentences} Sätze, sachlich und ohne Füllsätze.
            """.trimIndent()
            val secUsr = "Frage: $question\nAlle Abschnitte: $titles\nDieser Abschnitt: ${sec.first}\n\nGEPRÜFTE FAKTEN:\n$secFacts\n\nSchreibe jetzt den Abschnitt:"
            val raw = llama.generate(secSys, secUsr, null, temperature = 0.1f, maxTokens = perSection, onToken = onStreamToken).trim()
            answer.append(cutSectionText(raw)).append("\n\n")
            onStreamToken("\n\n")
        }
        return answer.toString().trim()
    }

    // === Geplante Recherche fuer lange Antworten ===

    @kotlinx.serialization.Serializable
    private data class PlanChapterDto(val title: String = "", val queries: List<String> = emptyList())

    @kotlinx.serialization.Serializable
    private data class ResearchPlanDto(
        val chapters: List<PlanChapterDto> = emptyList(),
        @kotlinx.serialization.SerialName("en_query") val englishQuery: String = ""
    )

    private class PlannedResult(
        val chapters: List<Pair<String, List<VerifiedClaim>>>,
        val rejected: Int,
        val used: List<RankedChunk>,
        val pages: Int,
        val rounds: Int
    )

    /** Treffer der Suchbegriffe der Frage je Seite (alle Abschnitte einer Adresse), fuer den Themencheck. */
    private val pageTopicCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun pageTopicHits(question: String, url: String): Int = pageTopicCache.getOrPut(url) {
        val qWords = questionWords(question)
        if (qWords.isEmpty()) return@getOrPut Int.MAX_VALUE
        retriever.getAllChunks().filter { it.url == url }.sumOf { c ->
            "${c.title} ${c.rawText}".lowercase().split(NON_WORD).count { w -> w.length > 4 && qWords.any { q -> sameWord(q, w) } }
        }
    }

    /**
     * Fakt gehoert zum Thema der Frage: ein Suchbegriff der Frage steht in der Aussage, im Zitat oder im Seitentitel,
     * oder die Seite nennt die Suchbegriffe mindestens dreimal (eine Unterseite eines Fachwikis ueber Netzwerkpakete
     * nennt "Paket", aber in der Regel auch das Spiel oder Programm, um das es geht).
     */
    private fun onQuestionTopic(question: String, vc: VerifiedClaim): Boolean =
        questionWordHits(question, "${vc.claim} ${vc.exactQuote} ${vc.chunk.title}") >= 1 || pageTopicHits(question, vc.chunk.url) >= 3

    /** Haeufigste Sprache der geladenen Seiten. */
    private fun sourcesLanguage(): LanguageDetector.Language? =
        retriever.getAllChunks().groupBy { it.url }.values
            .mapNotNull { cs -> LanguageDetector.detect(cs.take(4).joinToString(" ") { it.rawText }) }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    /**
     * Rechercheplan: Kapitel wie in einer Hausarbeit (Einordnung, Teilaspekte, Probleme, offene Fragen), jedes mit
     * eigenen Suchanfragen.
     */
    private suspend fun planChapters(question: String, count: Int): ResearchPlanDto {
        val sys = "Du planst eine wissenschaftliche Hausarbeit, die die Frage vollständig beantwortet. Bilde genau $count Kapitel " +
            "mit kurzen, sachlichen Überschriften auf $answerLanguage: zuerst Begriff, Hintergrund und Einordnung, dann je ein " +
            "Kapitel pro Teilaspekt, Verfahren, Werkzeug oder Teilgebiet, dann Probleme, Grenzen und Risiken, zuletzt aktuelle " +
            "Entwicklungen und offene Fragen. Jedes Kapitel behandelt etwas anderes. Gib je Kapitel 2 kurze Web-Suchanfragen in " +
            "der Sprache der Frage, die genau dieses Kapitel belegen; jede nennt das Hauptthema der Frage und die Fachbegriffe " +
            "des Kapitels. Dazu 1 kurze englische Suchanfrage zur ganzen Frage (en_query). Antworte ausschließlich als JSON."
        val out = llama.generate(sys, "Frage: $question", GbnfGrammars.RESEARCH_PLAN, temperature = 0.05f, maxTokens = 120 + 90 * count)
        val plan = runCatching { json.decodeFromString<ResearchPlanDto>(out) }
        Log.i("VeritasPlan", "Plan (${out.length} Zeichen, ok=${plan.isSuccess}): ${out.take(1500)}")
        return plan.getOrDefault(ResearchPlanDto())
    }

    /** Passt ein Link (Linktext und Pfad) zum Kapitel: Zahl der Kapitelwoerter, die darin vorkommen. */
    private fun linkScore(url: String, anchor: String, chapterWords: Set<String>): Int {
        val path = url.substringAfter("://").substringAfter('/', "")
        val words = "$anchor $path".lowercase().split(NON_WORD).filter { it.length > 3 }.toSet()
        return chapterWords.count { c -> words.any { w -> sameWord(c, w) } }
    }

    /**
     * Geplante Recherche: erst ein Plan mit Kapiteln, dann fuer jedes Kapitel suchen, Seiten und passende Unterseiten
     * derselben Websites lesen, Fakten herausziehen und pruefen, bis das Kapitel seinen Anteil an der Wortzahl hat
     * oder keine neuen Quellen mehr kommen. Die Zahl der Versuche je Kapitel folgt der Tiefe.
     */
    private suspend fun plannedResearch(
        question: String,
        questionLang: LanguageDetector.Language?,
        maxRounds: Int,
        profile: DepthProfile,
        useWikipedia: Boolean,
        useArxiv: Boolean,
        visitedUrls: MutableSet<String>,
        scrapedDomains: MutableSet<String>,
        emit: (Int, String, String, List<Any>) -> Unit
    ): PlannedResult {
        var lang = questionLang
        val count = length.maxSections
        emit(1, "st_plan_paper", tr("d_plan_paper", count), emptyList())
        // Abgeschnittenes oder kaputtes JSON: zweiter Versuch mit weniger Kapiteln
        val plan = planChapters(question, count).let { p ->
            if (p.chapters.size >= 2 || count <= 4) p else planChapters(question, (count / 2).coerceAtLeast(4))
        }
        val mainTerms = questionWords(question).take(3).joinToString(" ")
        fun withTopic(q: String) = if (mainTerms.isBlank() || questionWordHits(question, q) >= 1) q else "$mainTerms $q"
        var chapters = plan.chapters
            .map { c -> c.title.trim().take(80) to c.queries.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(3) }
            .filter { it.first.isNotBlank() }
            .distinctBy { it.first.lowercase() }
            .take(count)
            .map { (t, qs) -> t to qs.ifEmpty { listOf(t) }.map(::withTopic) }
        if (chapters.size < 2) {
            // Plan unbrauchbar: Teilaspekte aus der normalen Zerlegung werden zu Kapiteln
            chapters = planQueries(question, count, detailed = true).queries.map { it.trim() }.filter { it.isNotBlank() }
                .distinct().take(count).map { it to listOf(withTopic(it)) }
                .ifEmpty { listOf(question to listOf(question)) }
        }

        val linkPool = LinkedHashMap<String, String>()
        var pages = 0
        fun collectLinks(docs: List<ScrapedDocument>) {
            for (d in docs) for ((u, t) in d.links) if (u !in visitedUrls && linkPool.size < 3000) linkPool.putIfAbsent(u, t)
        }
        suspend fun indexDocs(docs: List<ScrapedDocument>, round: Int, l: LanguageDetector.Language?) {
            val kept = docs.filterNot { isLegalPage(it) || (l != null && LanguageDetector.isOtherLanguage(it.title + " " + it.cleanText.take(3000), l)) }
            if (kept.isEmpty()) return
            collectLinks(kept)
            kept.forEach { scrapedDomains.add(it.domain) }
            pages += kept.size
            emit(round, "st_index", tr("d_index", kept.size), emptyList())
            retriever.indexChunks(kept.flatMap { d ->
                val clean = if (d.sourceType == SourceType.WIKIPEDIA) d.copy(cleanText = WIKI_HEADING.replace(d.cleanText) { m -> m.groupValues[1].trim() + "\n" }) else d
                chunker.chunkDocument(clean)
            })
            pageTopicCache.clear()
        }
        suspend fun loadAndIndex(urls: List<Pair<String, String>>, round: Int, info: String, viaLinks: Set<String> = emptySet()) {
            if (urls.isEmpty()) return
            val sem = Semaphore(4)
            val done = AtomicInteger(0)
            emit(round, "st_load", "$info • ${tr("d_load", urls.size)}", listOf(0, urls.size))
            val loaded = coroutineScope {
                urls.map { (u, t) ->
                    async {
                        sem.withPermit {
                            webScraper.scrapePage(u, t).also {
                                liveDoc(u, it, u in viaLinks)
                                emit(round, "st_load", HiddenWebScraper.extractDomain(u), listOf(done.incrementAndGet(), urls.size))
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            indexDocs(loaded, round, lang)
        }
        suspend fun search(queries: List<String>, n: Int): List<Pair<String, String>> = coroutineScope {
            queries.forEach { live(com.veritas.localresearch.domain.LiveKind.SEARCH, it) }
            val lists = queries.map { q -> async { runCatching { webScraper.searchWeb(q, maxResults = n, lang = lang) }.getOrDefault(emptyList()) } }.awaitAll()
            buildList {
                val m = lists.maxOfOrNull { it.size } ?: 0
                for (i in 0 until m) lists.forEach { l -> l.getOrNull(i)?.let { add(it.url to it.title) } }
            }.distinctBy { it.first }.filter { it.first !in visitedUrls }
        }

        // Grundlage: Nachschlagewerke und Suche mit der ganzen Frage
        emit(1, "st_search", question, listOf(1))
        val startLang = lang
        val englishOk = startLang == null || startLang.code == "en"
        val base = coroutineScope {
            val jobs = buildList {
                if (useWikipedia) {
                    add(async { factApis.fetchWikipedia(mainTerms.ifBlank { question }, lang = startLang?.wiki ?: I18n.code.let { if (it == "nb") "no" else it }, limit = 2) })
                    if (startLang == null) add(async { factApis.fetchWikipedia(plan.englishQuery.ifBlank { question }, lang = "en", limit = 1) })
                }
                if (englishOk && useArxiv) add(async { factApis.fetchArxiv(plan.englishQuery.ifBlank { question }, maxResults = 2) })
            }
            jobs.awaitAll().flatten()
        }.filter { visitedUrls.add(it.url) }
        base.forEach { liveDoc(it.url, it) }
        indexDocs(base, 1, startLang)
        // Englische Zusatzsuche nur, wenn die Frage englisch ist oder ihre Sprache offen bleibt
        val firstQueries = listOf(question) + (if (englishOk) listOfNotNull(plan.englishQuery.takeIf { it.isNotBlank() && !it.equals(question, true) }) else emptyList())
        loadAndIndex(search(firstQueries, 8).take(profile.pagesFirstRound).filter { visitedUrls.add(it.first) }, 1, question)
        // Sprache der Frage offen (kurze Frage, viele Eigennamen): die der Quellen gilt fuer Suche und Antwort
        if (lang == null) sourcesLanguage()?.let { lang = it; answerLanguage = it.name }

        // Ein geschriebener Fakt ergibt im Mittel etwa 25 Woerter; etwas mehr je Kapitel, weil nicht jedes voll wird
        val factsNeeded = length.words / 24
        val quota = ((factsNeeded + chapters.size - 1) / chapters.size + 1).coerceAtLeast(3)
        val maxTries = maxRounds.coerceIn(1, 3)
        val perPass = profile.chunksPerPass.coerceAtLeast(3)
        val claimsPerPass = (profile.claimsPerPass + 2).coerceIn(6, 10)
        val fastFacts = reranker?.isReady == true && settings.current.useReranker
        // Mit schneller Satzwahl kostet ein weiterer Versuch nur Suche und Laden: zwei mehr je Kapitel
        val tryLimit = if (fastFacts) maxTries + 2 else maxTries
        val found = chapters.map { ArrayList<VerifiedClaim>() }
        val seenQuotes = ArrayList<String>()
        val extracted = HashSet<Int>()
        val used = LinkedHashMap<Int, RankedChunk>()
        var rejected = 0
        fun isNew(vc: VerifiedClaim) = seenQuotes.none { q -> q.contains(vc.exactQuote) || vc.exactQuote.contains(q) }

        for ((ci, chapter) in chapters.withIndex()) {
            val (title, queries) = chapter
            val round = ci + 1
            val info = tr("chapter_n", ci + 1, chapters.size, title)
            val chapterQuery = "$title ${queries.first()}"
            val chapterWords = "$title ${queries.joinToString(" ")}".lowercase().split(NON_WORD).filter { it.length > 3 }.toSet()
            var nextQueries = queries
            var tries = 0
            while (found[ci].size < quota && tries < tryLimit) {
                tries++
                // 1. Neue Seiten: Suchtreffer zum Kapitel und passende Unterseiten schon gelesener Websites
                val hits = if (nextQueries.isEmpty()) emptyList() else {
                    emit(round, "st_search", "$info • ${nextQueries.joinToString(" • ")}", listOf(round))
                    search(nextQueries, 6).take(profile.pagesLaterRounds.coerceIn(3, 6))
                }
                val links = linkPool.entries.asSequence().filter { it.key !in visitedUrls }
                    .map { e -> Triple(e.key, e.value, linkScore(e.key, e.value, chapterWords)) }
                    .filter { it.third >= 1 }.sortedByDescending { it.third }.take(4).map { it.first to it.second }.toList()
                loadAndIndex((hits + links).distinctBy { it.first }.filter { visitedUrls.add(it.first) }, round, info,
                    viaLinks = links.map { it.first }.toSet() - hits.map { it.first }.toSet())

                // 2. Beste, noch nicht gelesene Abschnitte zum Kapitel
                var ranked = retriever.retrieveTopChunks(chapterQuery, topK = 30, maxPerDomain = 30)
                    .filter { it.chunk.chunkId !in extracted }
                if (reranker?.isReady == true && settings.current.useReranker && ranked.size > 1) {
                    emit(round, "st_rerank", info, emptyList())
                    val s = reranker.score(chapterQuery, ranked.map { "${it.chunk.titleWithSection}. ${it.chunk.rawText}" })
                    if (s != null) {
                        // Abschnitte weit hinter dem besten zum Kapitel (eine Spielanleitung beim Kapitel ueber
                        // Netzwerkprotokolle) gehen nicht an das Modell; mindestens zwei bleiben
                        val best = s.maxOrNull() ?: 0f
                        ranked = ranked.indices.sortedByDescending { s[it] }
                            .filterIndexed { rank, i -> rank < 2 || s[i] >= best - CHAPTER_MARGIN }.map { ranked[it] }
                    }
                }
                // Mit Reranker waehlt er die Fakten als woertliche Saetze (Sekunden statt Minuten je Kapitel), dafuer
                // werden mehr Abschnitte gelesen; ohne Reranker zieht das Sprachmodell die Fakten heraus
                val pick = ranked.take(if (fastFacts) 20 else perPass * 2)
                nextQueries = if (tries == 1) listOf(withTopic(title)).filter { q -> queries.none { it.equals(q, ignoreCase = true) } } else emptyList()
                if (pick.isEmpty()) continue
                pick.forEach { extracted.add(it.chunk.chunkId); used[it.chunk.chunkId] = it }

                // 3. Fakten mit woertlichem Beleg, gegen den Abschnitt geprueft
                val chunkMap = pick.associate { it.chunk.chunkId to it.chunk }
                val ver = if (fastFacts) {
                    emit(round, "st_extract", info, emptyList())
                    val sentences = extractiveClaims(chapterQuery, pick.map { it.chunk }, quota - found[ci].size,
                        seenQuotes.toList(), margin = SENTENCE_MARGIN, loose = true)
                    emit(round, "st_verify", info, emptyList())
                    verifier.verifyClaims(sentences, chunkMap, retriever.getAllChunks(), retriever.sameTopicThreshold)
                } else {
                    val passes = pick.map { it.chunk }.chunked(perPass)
                    val raw = passes.flatMapIndexed { pi, g ->
                        emit(round, "st_extract_n", info, listOf(pi + 1, passes.size))
                        extractClaimsWithQuotes("$question\n($title)", g, claimsPerPass).claims
                    }
                    emit(round, "st_verify", info, emptyList())
                    verifier.verifyClaims(raw, chunkMap, retriever.getAllChunks(), retriever.sameTopicThreshold)
                }
                ver.rejections.forEach { Log.i("VeritasReject", it.take(400)) }
                liveClaims(emptyList(), ver.rejections)
                rejected += ver.rejectedCount
                val fresh = ArrayList<VerifiedClaim>()
                fun take(list: List<VerifiedClaim>) = list.forEach { vc ->
                    if (!onQuestionTopic(question, vc)) {
                        rejected++
                        Log.i("VeritasReject", "themenfremd: ${vc.claim.take(200)}")
                        liveClaims(emptyList(), listOf("themenfremd: ${vc.claim}"))
                    } else if (isNew(vc) && fresh.none { it.exactQuote == vc.exactQuote }) {
                        fresh.add(vc); seenQuotes.add(vc.exactQuote)
                        liveClaims(listOf(vc), emptyList())
                    }
                }
                take(ver.verifiedClaims)
                // Liefert das Modell zu wenig, kommen woertliche Saetze aus denselben Abschnitten dazu
                val missing = (quota - found[ci].size - fresh.size).coerceAtMost(pick.size * 2)
                if (missing > 0 && !fastFacts) {
                    val extra = extractiveClaims(chapterQuery, pick.map { it.chunk }, missing, seenQuotes.toList())
                    if (extra.isNotEmpty()) take(verifier.verifyClaims(extra, chunkMap, retriever.getAllChunks(), retriever.sameTopicThreshold).verifiedClaims)
                }
                found[ci].addAll(fresh)
            }
            Log.i("VeritasPlan", "Kapitel ${ci + 1}/${chapters.size} \"$title\": ${found[ci].size} Fakten nach $tries Versuchen")
        }
        return PlannedResult(chapters.mapIndexed { i, c -> c.first to found[i].toList() }, rejected, used.values.toList(), pages, chapters.size)
    }

    /**
     * Schreibt die Kapitel der geplanten Recherche: je Kapitel die Ueberschrift, dann Absaetze aus hoechstens acht
     * Fakten (so bleibt jeder Prompt im Kontextfenster, und jeder Fakt bekommt einen eigenen Satz).
     */
    private suspend fun writeChapters(
        question: String,
        chapters: List<Pair<String, List<VerifiedClaim>>>,
        onSection: (Int, Int) -> Unit,
        onStreamToken: (String) -> Unit
    ): String? {
        val filled = chapters.filter { it.second.isNotEmpty() }
        if (filled.isEmpty()) return null
        val answer = StringBuilder()
        for ((i, chapter) in filled.withIndex()) {
            val (title, facts) = chapter
            onSection(i + 1, filled.size)
            val heading = "## $title\n"
            answer.append(heading)
            onStreamToken(heading)
            val batches = facts.chunked(8)
            for ((bi, batch) in batches.withIndex()) {
                val sys = """
                    Du schreibst einen Absatz eines Kapitels einer Hausarbeit auf $answerLanguage, ausschließlich aus den gelieferten GEPRÜFTEN FAKTEN.
                    - Verwende jeden Fakt, jeweils in einem eigenen Satz mit seiner Quellennummer einzeln dahinter: [3].
                    - Schreibe keine Zitate ab und keine Kennzeichnungen wie „Quelle“ oder „Beleg“.
                    - Kein eigenes Vorwissen, keine neuen Zahlen oder Jahreszahlen.
                    - Keine Überschrift, keine Einleitung zur Gesamtfrage, kein Fazit.
                    - Ordne die Sätze so, dass ein zusammenhängender Absatz entsteht.
                """.trimIndent()
                val part = if (batches.size > 1) " (Absatz ${bi + 1} von ${batches.size})" else ""
                val usr = "Frage: $question\nKapitel: $title$part\n\nGEPRÜFTE FAKTEN:\n${batch.joinToString("\n") { factLine(it) }}\n\nSchreibe jetzt den Absatz:"
                val raw = llama.generate(sys, usr, null, temperature = 0.1f, maxTokens = (90 * batch.size + 60).coerceAtMost(1100),
                    onToken = onStreamToken).trim()
                Log.i("VeritasPlan", "Schreiben \"$title\" ${bi + 1}/${batches.size}: ${batch.size} Fakten, Prompt ${usr.length} Zeichen, " +
                    "Text ${raw.length} Zeichen: ${raw.take(120)}")
                answer.append(cutSectionText(raw)).append("\n\n")
                onStreamToken("\n\n")
            }
        }
        return answer.toString().trim()
    }

    companion object {
        /**
         * Abschnittstext ohne eigene Ueberschrift; beginnt das Modell darin schon den naechsten Abschnitt ("… nicht.## Kosten"),
         * endet der Text davor.
         */
        internal fun cutSectionText(raw: String): String {
            val t = raw.lines().dropWhile { it.isBlank() || it.trimStart().startsWith("#") }.joinToString("\n").trim()
            val k = INLINE_HEADING.find(t)?.range?.first ?: return t
            return t.substring(0, k).trim()
        }

        private val INLINE_HEADING = Regex("#{1,6}\\s")

        private val OVERVIEW = Regex("(?i)zusammenfass|fasse\\b.*\\bzusammen|überblick|ueberblick|worum geht|wovon handel|" +
            "hauptaussage|kernaussage|kernthese|inhaltsangabe|summar|overview|main points|key points|key takeaways|what is (?:it|this|that|the)(?: \\w+)? about")

        /** Fragt nach Inhalt oder Zusammenfassung der Dokumente insgesamt statt nach einem bestimmten Punkt. */
        fun isOverviewQuestion(question: String): Boolean = OVERVIEW.containsMatchIn(question)

        /** Abstand der Reranker-Bewertung zum besten Abschnitt, ab dem ein Abschnitt eigener Dokumente wegfaellt. */
        private const val RELEVANCE_MARGIN = 1.8f

        /** Wie RELEVANCE_MARGIN, fuer die Abschnitte eines Kapitels der geplanten Recherche (Webseiten streuen mehr). */
        private const val CHAPTER_MARGIN = 2.5f

        /** Abstand einzelner Saetze zum besten Satz eines Kapitels (Saetze streuen staerker als Abschnitte). */
        private const val SENTENCE_MARGIN = 3.5f

        /** "Kapitel 3", "Chapter 12", "Abschnitt 4" in der Frage. */
        private val CHAPTER_REF = Regex("(?i)\\b(?:kapitel|teil|abschnitt|chapter|part|section|lektion|unit)\\s+(\\d{1,3})\\b")

        /** Woerter von Ueberblicksfragen, die kein Thema nennen. */
        private val OVERVIEW_FILLER = setOf(
            "dokument", "dokuments", "dokumente", "dokumenten", "buches", "texts", "textes", "kapitel", "kapitels", "abschnitt",
            "abschnitts", "inhalt", "inhalte", "inhalts", "zusammen", "wichtigsten", "wesentlichen", "kurz", "kurze", "bitte",
            "document", "chapter", "section", "about", "content", "contents", "briefly", "please", "paper", "artikel", "article",
            "datei", "lehrbuch", "skript", "studie", "bericht", "worum", "wovon", "handelt", "dreht", "insgesamt"
        )

        private val WIKI_HEADING = Regex("(?m)^\\s*={2,4}\\s*(.+?)\\s*={2,4}\\s*$")
        val GENERIC_HEADINGS = setOf("literatur", "weblinks", "einzelnachweise", "siehe auch", "anmerkungen", "quellen",
            "references", "external links", "see also", "further reading", "notes", "bibliography", "weiterführende literatur")
        val SENTENCE_END = Regex("(?<=\\p{L}{2}[.!?])\\s+(?=\\p{Lu})|(?<=[。！？])\\s*(?=\\S)|" +
            "(?<=[.!?؟।])\\s+(?=[\\u0600-\\u06FF\\u0590-\\u05FF\\u0900-\\u097F\\u0E00-\\u0E7F\\u3400-\\u9FFF\\u3040-\\u30FF\\uAC00-\\uD7AF])|\\n+")
        val SPACES = Regex("\\s+")
        val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
        /** Fussnoten, Literaturangaben, Links: keine Aussagen. */
        val NOT_A_STATEMENT = Regex("\\[\\d+]|\\(\\d{4}[a-z]?\\)|https?://|www\\.|ISBN|\\bS\\. \\d|\\bpp?\\. \\d|\\bVgl\\.|\\bHrsg\\.|\\b(?:Ihre?[mnrs]?|Ihnen)\\b|\\b(?:Klicken|Kontaktieren|Melden Sie|Erfahren Sie)\\b")
        val QUESTION_FILLER = setOf("sowie", "wieder", "welche", "welcher", "welches", "werden", "durch", "einer", "eines",
            "zwischen", "aktuell", "heute", "gibt", "which", "where", "about", "their", "there", "these")
        /** Klammer zu ohne Klammer auf davor: Satz beginnt mitten in einer Klammer. */
        val WS_RUN = Regex("\\s+")
        val OPEN_FRAGMENT = Regex("^[^(]*\\)")

        /** Seitenbausteine: Nutzungsbedingungen, Lizenz- und Datenschutzhinweise, Selbstbeschreibung der Website. */
        val BOILERPLATE = Regex("(?i)terms of (?:service|use)|privacy policy|cookie|all rights reserved|your use of|subject to our|" +
            "this (?:page|website|site|wiki) (?:is|was|contains|provides)|see this page|(?:is|are) a (?:publicly accessible|public|online)[^.]{0,40}(?:wiki|website|collaboration)|" +
            "\\bwiki is\\b|editable website|sign up|subscribe|newsletter|click here|read more|" +
            "\\bas is\\b|without (?:any )?warrant|merchantability|fitness for a particular|limitation of liability|indemnif|you agree|we reserve the right|" +
            "virtual (?:items|currency)|free[- ]trial|your subscription|moderators?\\b|\\bfandom\\b|not necessarily (?:been )?reviewed|" +
            "grant (?:this|a|the) licen[cs]e|necessary rights|cannot guarantee the (?:validity|accuracy)|" +
            "nutzungsbedingungen|datenschutz|alle rechte vorbehalten|diese (?:seite|website) (?:ist|enthält|bietet)|hier klicken|weiterlesen|" +
            "ohne (?:jede )?gewähr|haftungsausschluss")

        /** Rechtstexte als ganze Seite: AGB, Lizenz, Datenschutz, Haftungsausschluss. */
        val LEGAL_PAGE = Regex("(?i)/(?:terms|tos|legal|eula|licen[cs]e|privacy|disclaimer|polic(?:y|ies)|agb|impressum|datenschutz)(?:[-_/.?#]|$)")
        val LEGAL_TITLE = Regex("(?i)terms of (?:service|use)|privacy policy|end[- ]user licen[cs]e|\\beula\\b|disclaimer|nutzungsbedingungen|datenschutzerklärung|\\bagb\\b|impressum")

        fun isLegalPage(d: ScrapedDocument): Boolean =
            d.sourceType != SourceType.WIKIPEDIA && (LEGAL_PAGE.containsMatchIn(d.url) || LEGAL_TITLE.containsMatchIn(d.title))

        /** Ein Satz, der nichts ueber das Thema sagt, sondern ueber die Website, auf der er steht. */
        fun isBoilerplate(sentence: String): Boolean = BOILERPLATE.containsMatchIn(sentence)
    }
}
