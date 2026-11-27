package com.veritas.localresearch.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** LOCAL: vom Nutzer geladenes Dokument; url ist "local:<id>", domain der Dateiname. */
enum class SourceType { WEB, WIKIPEDIA, ARXIV, LOCAL }

data class SearchResultItem(
    val title: String,
    val url: String,
    val snippet: String,
    val engine: String
)

/**
 * Angaben fuer das Literaturverzeichnis, soweit die Quelle sie preisgibt (Meta-Tags, PDF-Dokumentinfo, API).
 * [date] ist ISO (JJJJ, JJJJ-MM oder JJJJ-MM-TT) oder leer.
 */
data class SourceMeta(
    val authors: List<String> = emptyList(),
    val date: String = "",
    val siteName: String = "",
    val publisher: String = "",
    val isPdf: Boolean = false
)

data class ScrapedDocument(
    val url: String,
    val domain: String,
    val title: String,
    val cleanText: String,
    val sourceType: SourceType,
    val meta: SourceMeta = SourceMeta()
)

data class TextChunk(
    val chunkId: Int,
    val url: String,
    val domain: String,
    val title: String,
    val rawText: String,
    val sourceType: SourceType,
    val embedding: FloatArray? = null,
    val meta: SourceMeta = SourceMeta(),
    /** Eigene PDF-Dokumente: erste und letzte Seite des Abschnitts (1-basiert), sonst 0. */
    val page: Int = 0,
    val pageEnd: Int = 0,
    /** Eigene Dokumente: Kapitel und Ueberschrift, unter der der Abschnitt steht ("3 Marketing › 3.2 Preise"). */
    val section: String = ""
) {
    /** Titel samt Kapitel fuer Suche, Reranker und Modell. */
    val titleWithSection: String get() = if (section.isEmpty()) title else "$title › $section"
}

data class RankedChunk(
    val chunk: TextChunk,
    val bm25Rank: Int?,
    val semanticScore: Float,
    val crossSourceDomains: Int,
    val finalScore: Float
)

// --- GBNF-erzwungene JSON-Strukturen ---

@Serializable
data class QueryPlanDto(
    val queries: List<String> = emptyList(),
    @SerialName("en_query") val englishQuery: String = "",
    @SerialName("use_wikipedia") val useWikipedia: Boolean = true,
    @SerialName("use_arxiv") val useArxiv: Boolean = false
)

@Serializable
data class GapCheckDto(
    val sufficient: Boolean = true,
    @SerialName("missing_info") val missingInfo: String = "",
    @SerialName("follow_up_queries") val followUpQueries: List<String> = emptyList()
)

@Serializable
data class RawClaimDto(
    val claim: String = "",
    @SerialName("exact_quote") val exactQuote: String = "",
    @SerialName("chunk_id") val chunkId: Int = -1
)

@Serializable
data class ClaimExtractionDto(
    val claims: List<RawClaimDto> = emptyList()
)

// --- Verifizierte Daten fuer die UI ---

enum class TrustLevel {
    MULTI_SOURCE,   // Von >= 2 unabhaengigen Domains gestuetzt + Zitat geprueft
    SINGLE_SOURCE,  // Exaktes Zitat geprueft, aber nur 1 Domain
    REJECTED        // Zitat nicht im Chunk gefunden -> verworfen
}

data class VerifiedClaim(
    val citationIndex: Int, // 1-basiert fuer [1], [2], ...
    val claim: String,
    val exactQuote: String,
    val chunk: TextChunk,
    val quoteMatchScore: Float,
    val quoteHighlightRange: IntRange?, // Exakte Position im Chunk fuer Click-to-Passage
    val trustLevel: TrustLevel,
    val corroboratingDomains: List<String>
)

data class UnverifiedNumberSpan(
    val text: String,
    val range: IntRange,
    val reason: String
)

data class ResearchReport(
    val question: String,
    val synthesizedAnswer: String,
    val verifiedClaims: List<VerifiedClaim>,
    val rejectedClaimCount: Int,
    val unverifiedNumbers: List<UnverifiedNumberSpan>,
    val roundsExecuted: Int,
    val totalSourcesScraped: Int,
    val totalChunksIndexed: Int,
    val usedChunks: List<RankedChunk>,
    /** Dauer je Stufe in ms, in der Reihenfolge des Ablaufs. */
    val stageTimings: List<Pair<String, Long>> = emptyList(),
    val totalMs: Long = 0L,
    val modelName: String = "",
    /** Zeichenbereiche der Zwischenueberschriften in [synthesizedAnswer] (ohne Rauten im Text). */
    val headingRanges: List<IntRange> = emptyList(),
    /** Zeitpunkt der Recherche, zugleich Abrufdatum der Quellen. */
    val createdAt: Long = System.currentTimeMillis()
)

data class PipelineProgress(
    val currentRound: Int = 1,
    val maxRounds: Int = 3,
    val stageTitle: String = "Bereit",
    val detail: String = "",
    val scrapedDomains: List<String> = emptyList(),
    val chunkCount: Int = 0,
    val verifiedClaimsCount: Int = 0,
    val rejectedClaimsCount: Int = 0,
    val isRunning: Boolean = false
)
