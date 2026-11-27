package com.veritas.localresearch.rag

import com.veritas.localresearch.domain.TextChunk
import java.text.Normalizer
import kotlin.math.ln

/**
 * Keyword index with BM25 ranking, kept in memory.
 * The system SQLite on many devices ships without the FTS5 module, so the ranking runs in Kotlin.
 * A web research session holds a few hundred chunks; a book of 3000 pages about 70,000, still a scan of a few milliseconds per term.
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
            val sectionTokens = tokenize(c.section)
            val bodyTokens = tokenize(c.rawText)
            val totalTokens = titleTokens.size * 2 + sectionTokens.size + bodyTokens.size
            if (totalTokens == 0) continue

            val tf = HashMap<String, Int>()
            for (t in titleTokens) tf[t] = (tf[t] ?: 0) + 2
            // Kapitel und Ueberschrift zaehlen einfach: "Preispolitik" findet auch Absaetze darunter
            for (t in sectionTokens) tf[t] = (tf[t] ?: 0) + 1
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

        // Deutsche Komposita: "kundigungsfrist" findet auch "grundkundigungsfrist" (das Grundwort steht hinten), schwaecher gewichtet
        val groups = terms.map { t ->
            val longer = if (t.length < 6) emptyList() else docFreq.keys.asSequence()
                .filter { it.length >= t.length + 3 && it.endsWith(t) }
                .sortedByDescending { docFreq[it] ?: 0 }
                .take(MAX_COMPOUNDS)
                .map { it to COMPOUND_WEIGHT }
                .toList()
            listOf(t to 1.0) + longer
        }
        val idfOf = HashMap<String, Double>()
        for (g in groups) for ((t, _) in g) {
            val df = (docFreq[t] ?: 0).toDouble()
            idfOf[t] = ln(1.0 + (n - df + 0.5) / (df + 0.5))
        }

        val scored = ArrayList<Pair<Int, Double>>()
        for (doc in docs) {
            var score = 0.0
            for (g in groups) {
                // Je Suchwort zaehlt nur die beste Form (Wort selbst oder ein Kompositum damit)
                var best = 0.0
                for ((term, w) in g) {
                    val f = doc.termFreq[term] ?: continue
                    val s = w * idfOf.getValue(term) * (f * (k1 + 1.0)) / (f + k1 * (1.0 - b + b * doc.length / avgLen))
                    if (s > best) best = s
                }
                score += best
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
        return TOKEN_REGEX.findAll(folded).flatMap { m ->
            val w = m.value
            // Chinesisch, Japanisch und Thai schreiben ohne Leerzeichen: dort zaehlen Zeichenpaare als Begriffe
            if (w.length > 2 && w.any(::unspaced)) w.windowed(2).asSequence() else sequenceOf(stem(w))
        }.filter { it.length >= 2 }.toList()
    }

    private fun unspaced(c: Char): Boolean = when (runCatching { Character.UnicodeScript.of(c.code) }.getOrNull()) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.THAI -> true
        else -> false
    }

    /** Einfache Grundform: haeufige Endungen ab ("okonomischen" und "okonomische" -> "okonomisch"), Rest mind. 4 Zeichen. */
    private fun stem(w: String): String {
        if (w.length < 6 || w.any(Char::isDigit)) return w
        // Zweimal, damit "speichers" und "speicher" dieselbe Form ergeben
        var s = w
        repeat(2) {
            val suf = SUFFIXES.firstOrNull { s.length - it.length >= 5 && s.endsWith(it) } ?: return s
            s = s.dropLast(suf.length)
        }
        return s
    }

    private companion object {
        val DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
        val TOKEN_REGEX = Regex("[\\p{L}\\p{N}]+")
        const val MAX_COMPOUNDS = 12
        const val COMPOUND_WEIGHT = 0.7
        val SUFFIXES = listOf("ern", "en", "em", "er", "es", "e", "n", "s")
        val STOP_WORDS = setOf(
            "der", "die", "das", "und", "oder", "ein", "eine", "ist", "sind", "war",
            "wie", "was", "wer", "wo", "wann", "warum", "von", "mit", "fur", "auf",
            "the", "and", "for", "with", "what", "how", "why", "who", "are"
        )
    }
}
