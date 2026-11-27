package com.veritas.localresearch.export

import com.veritas.localresearch.domain.ResearchReport
import com.veritas.localresearch.domain.SourceMeta
import com.veritas.localresearch.domain.SourceType
import com.veritas.localresearch.domain.TextChunk
import com.veritas.localresearch.domain.TrustLevel
import com.veritas.localresearch.domain.VerifiedClaim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class ExportTest {

    private val created = LocalDate.of(2026, 11, 27).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun claim(i: Int, url: String, title: String, meta: SourceMeta, type: SourceType = SourceType.WEB, text: String = "Text") =
        VerifiedClaim(i, text, text, TextChunk(i, url, url.substringAfter("//").substringBefore('/'), title, text, type, meta = meta),
            1f, null, TrustLevel.SINGLE_SOURCE, emptyList())

    private val claims = listOf(
        claim(1, "https://www.lwa.example.org/dm", "Datenmanagement", SourceMeta(siteName = "Landeswetteramt")),
        claim(2, "https://www.example.org/dt-betrieb/", "Datentransfer und Datensicherung im Betrieb",
            SourceMeta(authors = listOf("Anna Beispiel", "Max Muster"), date = "2025-06-02", siteName = "Datenverband Süd")),
        claim(3, "https://de.wikipedia.org/wiki/Datenmanagement", "Wikipedia: Datenmanagement",
            SourceMeta(siteName = "Wikipedia", publisher = "Wikimedia Foundation"), SourceType.WIKIPEDIA)
    )

    private val answer = "Stand\nStrukturiertes Datenmanagement hilft, Daten zu sichern [1]. Datensicherung gilt als wichtige Aufgabe [2] [3].\n\nHürden & <Faktoren>\nPersonalwechsel gefährdet Daten [2]."

    private val report = ResearchReport(
        question = "Wie ist der Stand von Datenmanagement in deutschen Unternehmen?",
        synthesizedAnswer = answer,
        verifiedClaims = claims,
        rejectedClaimCount = 0,
        unverifiedNumbers = emptyList(),
        roundsExecuted = 1,
        totalSourcesScraped = 3,
        totalChunksIndexed = 3,
        usedChunks = emptyList(),
        headingRanges = listOf(0 until 5, answer.indexOf("Hürden") until answer.indexOf("Hürden") + "Hürden & <Faktoren>".length),
        createdAt = created,
        modelName = "Qwen3-0.6B"
    )

    @Test
    fun citationStyles() {
        val e = Citations.entries(claims, created)
        val de = CiteLang(true)
        assertEquals("(Landeswetteramt, o. J.)", Citations.inText(listOf(e[0]), CitationStyle.APA7, de))
        assertEquals("(Beispiel & Muster, 2025)", Citations.inText(listOf(e[1]), CitationStyle.APA7, de))
        assertEquals("(Beispiel und Muster 2025; Wikipedia o. J.)", Citations.inText(listOf(e[1], e[2]), CitationStyle.HARVARD, de))
        assertEquals("[2], [3]", Citations.inText(listOf(e[1], e[2]), CitationStyle.IEEE, de))
        assertEquals("[2, 3]", Citations.inText(listOf(e[1], e[2]), CitationStyle.DIN_ISO_690, de))
        val apa = Citations.reference(e[1], CitationStyle.APA7, de).joinToString("") { it.text }
        assertEquals("Beispiel, A., & Muster, M. (2025, 2. Juni). Datentransfer und Datensicherung im Betrieb. Datenverband Süd. https://www.example.org/dt-betrieb/", apa)
        val wiki = Citations.reference(e[2], CitationStyle.APA7, de).joinToString("") { it.text }
        assertEquals("Wikipedia. (o. J.). Datenmanagement. Abgerufen am 27. November 2026, von https://de.wikipedia.org/wiki/Datenmanagement", wiki)
        val din = Citations.reference(e[0], CitationStyle.DIN_ISO_690, de).joinToString("") { it.text }
        assertEquals("[1] LANDESWETTERAMT: Datenmanagement [online]. o. J. [Zugriff am: 27.11.2026]. Verfügbar unter: https://www.lwa.example.org/dm", din)
        val ieee = Citations.reference(e[1], CitationStyle.IEEE, de).joinToString("") { it.text }
        assertTrue(ieee, ieee.startsWith("[2] A. Beispiel und M. Muster, “Datentransfer"))
        // Ueber die Suche gefundene mobile Wikipedia-Seite
        val mwiki = Citations.entries(listOf(claim(4, "https://de.m.wikipedia.org/wiki/Solarenergie", "Solarenergie – Wikipedia", SourceMeta())), created)[0]
        assertEquals("Wikipedia. (o. J.). Solarenergie. Abgerufen am 27. November 2026, von https://de.m.wikipedia.org/wiki/Solarenergie",
            Citations.reference(mwiki, CitationStyle.APA7, de).joinToString("") { it.text })
        // Autor-Jahr-Stile alphabetisch
        assertEquals(listOf(2, 1, 3), Citations.ordered(e, CitationStyle.APA7, de).map { it.number })
    }

    @Test
    fun writesAllFormats() {
        val outDir = System.getenv("VL_EXPORT_OUT")?.let { File(it) } ?: File("build/export-test")
        outDir.mkdirs()
        for (style in listOf(CitationStyle.APA7, CitationStyle.IEEE)) {
            val doc = ExportDocument.from(report, style)
            assertEquals(listOf("Stand", "Hürden & <Faktoren>", "Literaturverzeichnis"), doc.tocEntries.map { it.first })
            val docx = DocxWriter.write(doc)
            val odt = OdtWriter.write(doc)
            assertTrue(docx.size > 1000 && odt.size > 1000)
            File(outDir, "test-${style.name}.docx").writeBytes(docx)
            File(outDir, "test-${style.name}.odt").writeBytes(odt)
        }
        val apaDoc = ExportDocument.from(report, CitationStyle.APA7)
        val para = apaDoc.blocks.filterIsInstance<Block.Paragraph>().first()
        val cites = para.runs.filterIsInstance<Run.Cite>().map { it.label }
        assertEquals(listOf("(Landeswetteramt, o. J.)", "(Beispiel & Muster, 2025; Wikipedia, o. J.)"), cites)
    }
}
