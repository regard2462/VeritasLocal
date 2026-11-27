package com.veritas.localresearch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicHeuristicsTest {

    private fun family(q: String) = TopicHeuristics.topicFamily(q)

    @Test
    fun questionShapes() {
        assertEquals(listOf("solarstrom", "solarspeicher"),
            family("Solarstrom und Solarspeicher in deutschen Haushalten sowie rechtliche technische und kulturelle Hürden und Einflussfaktoren"))
        assertEquals(listOf("solarstrom"), family("Warum scheitert Solarstrom in Mietshäusern?"))
        assertEquals(listOf("koffein"), family("Welchen Einfluss hat Koffein auf den Schlaf?"))
        assertEquals(listOf("solarspeichers"), family("Rechtliche Hürden des Solarspeichers"))
        assertEquals(listOf("solarstrom"), family("Hürden beim Solarstrom deutscher Haushalte"))
        assertEquals(listOf("photosynthese"), family("Erkläre Photosynthese in der Biologie"))
        // Ohne klares Thema prueft der Filter nichts
        assertTrue(family("Welche Vorteile und Nachteile gibt es?").isEmpty())
    }

    @Test
    fun mentionsCountsInflections() {
        val f = listOf("solarspeicher")
        assertEquals(2, TopicHeuristics.mentions(f, "des solarspeichers im haus und solarspeicher-förderung"))
        assertEquals(0, TopicHeuristics.mentions(f, "das solarium und die forschung"))
    }
}
