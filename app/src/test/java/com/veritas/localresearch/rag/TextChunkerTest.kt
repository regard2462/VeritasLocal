package com.veritas.localresearch.rag

import com.veritas.localresearch.domain.ScrapedDocument
import com.veritas.localresearch.domain.SourceType
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {

    @Test
    fun softPdfLineBreakIsNoChunkBoundary() {
        // Satz mit PDF-Zeilenumbruechen, der ueber die Mitte des 500-Zeichen-Fensters hinausreicht
        val filler = "Datenmanagement in Unternehmen ist ein Thema mit vielen Facetten und langer Geschichte. ".repeat(3)
        val longSentence = "Dieses Modell macht deutlich, welche Einflussfaktoren auf den Datentransfer einwirken: der\n" +
            "organisationale Kontext, wozu auch die Firmenkultur gehört, die Qualität der Beziehung zwischen\n" +
            "der datengebenden Person im Unternehmen (Quellkontext) und der datennehmender\nPerson sowie die Merkmale der Daten selbst und weitere Faktoren."
        val text = filler + longSentence + " Danach folgt ein weiterer Absatz mit genug Text für einen zweiten Abschnitt hier."
        val doc = ScrapedDocument(url = "https://example.org/a.pdf", domain = "example.org", title = "T", cleanText = text, sourceType = SourceType.WEB)
        val chunks = TextChunker().chunkDocument(doc)
        for (c in chunks) {
            val t = c.rawText.trimEnd()
            assertTrue("Abschnitt endet mitten im Satz: …${t.takeLast(60)}", !t.endsWith("datennehmender") && !t.endsWith(" der"))
        }
    }
}
