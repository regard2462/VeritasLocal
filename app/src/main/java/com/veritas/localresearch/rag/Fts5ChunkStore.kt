package com.veritas.localresearch.rag

import com.veritas.localresearch.domain.TextChunk
import java.text.Normalizer
import kotlin.math.ln

/**
 * Keyword index with BM25 ranking, kept in memory.
 * The system SQLite on many devices ships without the FTS5 module, so the ranking runs in Kotlin.
 * A research session holds a few hundred chunks, a linear scan takes well under a millisecond.
 */
class Fts5ChunkStore {

    private class Doc(val chunkId: Int, val termFreq: Map<String, Int>, val length: Int)

    private val docs = ArrayList<Doc>()
    private val docFreq = HashMap<String, Int>()
    private var totalLength = 0L

    fun clearSession() {
        docs.clear()
        docFreq.clear()
        totalLength = 0L
    }

    fun insertChunks(chunks: List<TextChunk>) {
        for (c in chunks) {
            // The title counts twice, so title hits rank above plain body hits
            val titleTokens = tokenize(c.title)
            val bodyTokens = tokenize(c.rawText)
            val totalTokens = titleTokens.size * 2 + bodyTokens.size
            if (totalTokens == 0) continue

            val tf = HashMap<String, Int>()
            for (t in titleTokens) tf[t] = (tf[t] ?: 0) + 2
            for (t in bodyTokens) tf[t] = (tf[t] ?: 0) + 1
            for (t in tf.keys) docFreq[t] = (docFreq[t] ?: 0) + 1
            docs.add(Doc(c.chunkId, tf, totalTokens))
            totalLength += totalTokens
        }
    }

    /** Chunk ids sorted by BM25, best hit first. */
    fun searchBm25(query: String, limit: Int = 30): List<Int> {
        if (docs.isEmpty()) return emptyList()
        val terms = queryTerms(query)
        if (terms.isEmpty()) return emptyList()

        val n = docs.size.toDouble()
        val avgLen = (totalLength.toDouble() / docs.size).coerceAtLeast(1.0)
        val k1 = 1.2
        val b = 0.75

        val scored = ArrayList<Pair<Int, Double>>()
        for (doc in docs) {
            var score = 0.0
            for (term in terms) {
                val f = doc.termFreq[term] ?: continue
                val df = (docFreq[term] ?: 0).toDouble()
                val idf = ln(1.0 + (n - df + 0.5) / (df + 0.5))
                score += idf * (f * (k1 + 1.0)) / (f + k1 * (1.0 - b + b * doc.length / avgLen))
            }
            if (score > 0.0) scored.add(doc.chunkId to score)
        }
        return scored.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    fun close() = clearSession()

    private fun queryTerms(raw: String): List<String> =
        tokenize(raw).filter { it.length >= 2 && it !in STOP_WORDS }.distinct().take(12)

    private fun tokenize(text: String): List<String> {
        val lowered = text.lowercase().replace("ß", "ss")
        val folded = Normalizer.normalize(lowered, Normalizer.Form.NFD)
            .replace(DIACRITICS_REGEX, "")
        return TOKEN_REGEX.findAll(folded).map { it.value }.filter { it.length >= 2 }.toList()
    }

    private companion object {
        val DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
        val TOKEN_REGEX = Regex("[\\p{L}\\p{N}]+")
        val STOP_WORDS = setOf(
            "der", "die", "das", "und", "oder", "ein", "eine", "ist", "sind", "war",
            "wie", "was", "wer", "wo", "wann", "warum", "von", "mit", "fur", "auf",
            "the", "and", "for", "with", "what", "how", "why", "who", "are"
        )
    }
}
