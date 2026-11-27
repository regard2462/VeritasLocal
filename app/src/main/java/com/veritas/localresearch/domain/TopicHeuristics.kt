package com.veritas.localresearch.domain

/** Hauptthema einer deutschen Frage, fuer den Themencheck der Quellen. */
object TopicHeuristics {

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** Fragewoerter und Arbeitsauftraege am Satzanfang. */
    private val QUESTION_WORDS = setOf(
        "warum", "wieso", "weshalb", "welche", "welcher", "welches", "welchen", "welchem", "inwiefern", "inwieweit",
        "worin", "wodurch", "wozu", "wofür", "woran", "worauf", "wovon", "erkläre", "erklären", "beschreibe",
        "vergleiche", "analysiere", "untersuche", "nenne", "recherchiere", "stelle", "fasse", "bitte", "gibt", "sowie"
    )

    /** Allgemeine Substantive, die in Fragen zu jedem Thema stehen und das Thema nicht benennen. */
    private val GENERIC_NOUNS = setOf(
        "einfluss", "einflüsse", "einflussfaktoren", "faktoren", "barrieren", "hürden", "hindernisse", "rolle",
        "bedeutung", "auswirkungen", "auswirkung", "wirkung", "folgen", "ursachen", "gründe", "vorteile", "nachteile",
        "chancen", "risiken", "probleme", "herausforderungen", "entwicklung", "entwicklungen", "stand", "überblick",
        "unterschied", "unterschiede", "zusammenhang", "beispiel", "beispiele", "möglichkeiten", "methoden", "ansätze",
        "aspekte", "kriterien", "voraussetzungen", "bedingungen", "erfolgsfaktoren", "zukunft", "geschichte",
        "definition", "begriff", "praxis", "umsetzung", "situation", "lage", "status", "trends", "studien", "forschung",
        "zusammenfassung", "analyse", "vergleich", "deutschland", "europa"
    )

    /** Adjektivendungen: am Satzanfang gross geschrieben, aber kein Substantiv ("Rechtliche Hürden"). */
    private val ADJECTIVE_END = Regex("(?:lich|isch|ell|ig|iv|bar|sam|haft|al|är)(?:e|en|er|es|em)?$")

    /**
     * Das erste gross geschriebene Substantiv, das kein Fragewort, kein Allgemeinbegriff und kein Adjektiv ist,
     * dazu die Woerter der Frage mit gleichem Wortanfang ("Solarstrom", "Solarspeicher"). Leer, wenn
     * kein solches Substantiv da ist: dann prueft der Themencheck nichts, statt zu raten.
     */
    fun topicFamily(question: String): List<String> {
        val all = question.split(NON_WORD).filter { it.isNotEmpty() }
        // Nur das erste Wort ist wegen des Satzanfangs gross geschrieben, ohne Substantiv sein zu muessen
        val nouns = all.filterIndexed { i, w ->
            val l = w.lowercase()
            w.length > 4 && w.first().isUpperCase() && l !in QUESTION_WORDS && l !in GENERIC_NOUNS &&
                !(i == 0 && ADJECTIVE_END.containsMatchIn(l))
        }.map { it.lowercase() }
        val primary = nouns.firstOrNull() ?: return emptyList()
        return nouns.filter { it == primary || (primary.length >= 8 && it.length >= 8 && it.take(6) == primary.take(6)) }.distinct()
    }

    /** Wie oft die Themenwoerter in [text] (klein geschrieben) vorkommen; Beugungen und Komposita zaehlen mit. */
    fun mentions(family: List<String>, text: String): Int = family.sumOf { f ->
        val stem = if (f.length >= 8) f.dropLast(2) else f
        var n = 0
        var i = text.indexOf(stem)
        while (i >= 0) { n++; i = text.indexOf(stem, i + stem.length) }
        n
    }
}
