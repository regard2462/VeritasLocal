package com.veritas.localresearch.domain

/**
 * Erkennt die Sprache eines Textes ueber haeufige Funktionswoerter. Reicht, um Suche, Quellen und Antwort
 * in der Sprache der Frage zu halten. Liefert null, wenn der Text zu kurz oder nicht eindeutig ist.
 */
object LanguageDetector {

    data class Language(val code: String, val name: String, val ddgRegion: String, val bingCountry: String)

    val GERMAN = Language("de", "Deutsch", "de-de", "DE")
    val ENGLISH = Language("en", "Englisch", "us-en", "US")
    private val FRENCH = Language("fr", "Französisch", "fr-fr", "FR")
    private val SPANISH = Language("es", "Spanisch", "es-es", "ES")
    private val ITALIAN = Language("it", "Italienisch", "it-it", "IT")
    private val DUTCH = Language("nl", "Niederländisch", "nl-nl", "NL")

    private val STOPWORDS: Map<Language, Set<String>> = mapOf(
        GERMAN to setOf("der", "die", "das", "und", "ist", "nicht", "ein", "eine", "mit", "von", "zu", "den", "dem", "des",
            "auf", "für", "wie", "wird", "werden", "sind", "auch", "sich", "im", "in", "bei", "oder", "welche", "was",
            "wer", "wann", "warum", "wo", "über", "nach", "aus", "einer", "eines", "durch", "zum", "zur", "kann", "es"),
        ENGLISH to setOf("the", "and", "is", "are", "of", "to", "in", "for", "with", "on", "what", "how", "which", "when",
            "why", "who", "where", "does", "do", "this", "that", "be", "by", "from", "an", "a", "it", "as", "was",
            "were", "or", "can", "their", "has", "have", "about"),
        FRENCH to setOf("le", "la", "les", "et", "est", "des", "du", "un", "une", "pour", "dans", "avec", "que", "qui",
            "sur", "pas", "sont", "comment", "quel", "quelle", "au", "aux", "ce", "il", "elle"),
        SPANISH to setOf("el", "la", "los", "las", "y", "es", "de", "del", "un", "una", "para", "con", "que", "por",
            "en", "como", "cuál", "qué", "son", "se", "al", "lo"),
        ITALIAN to setOf("il", "lo", "la", "gli", "le", "e", "è", "di", "del", "della", "un", "una", "per", "con", "che",
            "come", "sono", "nel", "nella", "non", "si", "al"),
        DUTCH to setOf("de", "het", "een", "en", "is", "van", "voor", "met", "dat", "niet", "zijn", "hoe", "wat", "welke",
            "op", "ook", "worden", "wordt", "bij", "naar")
    )

    private val WORD = Regex("[\\p{L}]+")

    fun detect(text: String): Language? {
        val words = WORD.findAll(text.take(4000).lowercase()).map { it.value }.toList()
        if (words.size < 3) return null
        val scores = STOPWORDS.mapValues { (_, set) -> words.count { it in set } }
        // Umlaute und ß sprechen deutlich fuer Deutsch, auch in kurzen Fragen
        val umlautBonus = words.count { w -> w.any { it in "äöüß" } }
        val withBonus = scores.mapValues { (lang, s) -> if (lang == GERMAN) s + umlautBonus else s }
        val sorted = withBonus.entries.sortedByDescending { it.value }
        val best = sorted[0]
        val second = sorted.getOrNull(1)?.value ?: 0
        if (best.value < 2) return null
        // Nur bei klarem Vorsprung entscheiden
        return if (best.value >= second * 1.5 + 1) best.key else null
    }

    /** true, wenn der Text erkennbar in einer anderen Sprache als [lang] geschrieben ist. */
    fun isOtherLanguage(text: String, lang: Language): Boolean {
        val d = detect(text) ?: return false
        return d.code != lang.code
    }
}
