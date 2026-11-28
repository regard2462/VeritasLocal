package com.veritas.localresearch.docs

import com.veritas.localresearch.domain.ScrapedDocument
import com.veritas.localresearch.domain.SourceType
import com.veritas.localresearch.domain.TextChunk
import com.veritas.localresearch.pipeline.DeepResearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentParserTest {

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            files.forEach { (name, text) ->
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun docxParagraphsAndMeta() {
        val bytes = zip(
            "word/document.xml" to """<?xml version="1.0"?><w:document xmlns:w="w"><w:body>
                <w:p><w:r><w:t>Solarspeicher</w:t></w:r></w:p>
                <w:p><w:r><w:t xml:space="preserve">Ein Speicher nimmt </w:t></w:r><w:r><w:t>Überschüsse &amp; Reste auf.</w:t></w:r></w:p>
                </w:body></w:document>""",
            "docProps/core.xml" to """<cp:coreProperties xmlns:cp="c" xmlns:dc="d" xmlns:dcterms="t">
                <dc:title>Speicherbericht</dc:title><dc:creator>Erika Beispiel</dc:creator><dcterms:created>2024-03-01T00:00:00Z</dcterms:created>
                </cp:coreProperties>"""
        )
        assertEquals(DocumentParser.Kind.DOCX, DocumentParser.kindOf("bericht.docx", bytes))
        assertEquals(DocumentParser.Kind.DOCX, DocumentParser.kindOf("ohne-endung", bytes))
        val p = DocumentParser.parse(DocumentParser.Kind.DOCX, bytes)!!
        assertEquals("Solarspeicher\n\nEin Speicher nimmt Überschüsse & Reste auf.", p.text)
        assertEquals("Speicherbericht", p.title)
        assertEquals(listOf("Erika Beispiel"), p.authors)
        assertEquals("2024", p.date)
    }

    @Test
    fun odtHeadingsAndParagraphs() {
        val bytes = zip(
            "content.xml" to """<office:document-content xmlns:office="o" xmlns:text="t"><office:body><office:text>
                <text:h>Einleitung</text:h><text:p>Erster<text:s/>Absatz<text:tab/>mit Lücke.</text:p><text:p>Zweiter Absatz.</text:p>
                </office:text></office:body></office:document-content>"""
        )
        val p = DocumentParser.parse(DocumentParser.kindOf("a.odt", bytes)!!, bytes)!!
        assertEquals("Einleitung\n\nErster Absatz mit Lücke.\n\nZweiter Absatz.", p.text)
    }

    @Test
    fun epubFollowsSpineOrder() {
        val bytes = zip(
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
            "OEBPS/content.opf" to """<package xmlns:dc="dc"><metadata><dc:title>Beispielbuch</dc:title><dc:creator>Max Muster</dc:creator></metadata>
                <manifest><item id="b" href="kap2.xhtml"/><item id="a" href="text/kap1.xhtml"/></manifest>
                <spine><itemref idref="a"/><itemref idref="b"/></spine></package>""",
            "OEBPS/kap2.xhtml" to "<html><body><h1>Zwei</h1><p>Zweites Kapitel.</p></body></html>",
            "OEBPS/text/kap1.xhtml" to "<html><body><h1>Eins</h1><p>Erstes <b>Kapitel</b>.</p><script>x()</script></body></html>"
        )
        assertEquals(DocumentParser.Kind.EPUB, DocumentParser.kindOf("buch.epub", bytes))
        val p = DocumentParser.parse(DocumentParser.Kind.EPUB, bytes)!!
        assertEquals("Eins\n\nErstes Kapitel.\n\nZwei\n\nZweites Kapitel.", p.text)
        assertEquals("Beispielbuch", p.title)
        assertEquals(listOf("Max Muster"), p.authors)
    }

    @Test
    fun htmlAndMarkdownAndLatin1() {
        val html = "<html><head><title>Seite</title><meta name=author content='A. Autor'></head><body><nav>Menü</nav>" +
            "<h2>Titel</h2><ul><li>Punkt <p>eins</p></li></ul><p>Text.</p></body></html>"
        val p = DocumentParser.parse(DocumentParser.Kind.HTML, html.toByteArray())!!
        assertEquals("Titel\n\nPunkt eins\n\nText.", p.text)
        assertEquals("Seite", p.title)

        val md = "# Überschrift\n\n- **fett** und `code`\n1. Schritt\n"
        assertEquals("Überschrift\n\nfett und code\nSchritt", DocumentParser.parse(DocumentParser.Kind.TEXT, md.toByteArray())!!.text)

        val latin = "Größe".toByteArray(Charsets.ISO_8859_1)
        assertEquals("Größe", DocumentParser.decode(latin))
        assertEquals(DocumentParser.Kind.TEXT, DocumentParser.kindOf("notiz", "Hallo Welt".toByteArray()))
        assertEquals(DocumentParser.Kind.IMAGE, DocumentParser.kindOf("scan", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)))
        assertNull(DocumentParser.kindOf("daten.bin", byteArrayOf(0x12, 0x00, 0x00, 0x01)))
    }

    @Test
    fun chunkPagesFromPageStarts() {
        val text = "Seite eins Text.\n\nSeite zwei Text.\n\nSeite drei."
        val starts = listOf(0, text.indexOf("Seite zwei"), text.indexOf("Seite drei"))
        fun chunk(id: Int, t: String) = TextChunk(id, "local:x", "x.pdf", "X", t, SourceType.LOCAL)
        val out = LocalDocumentStore.assignPages(
            listOf(chunk(1, "Seite eins Text."), chunk(2, "Text.\n\nSeite zwei Text.\n\nSeite drei.")), text, starts
        )
        assertEquals(1, out[0].page)
        assertEquals(1, out[0].pageEnd)
        assertEquals(1, out[1].page)
        assertEquals(3, out[1].pageEnd)
        assertEquals(2, LocalDocumentStore.pageAt(starts, starts[1]))
    }

    @Test
    fun pdfHeadingsStaySeparate() {
        val raw = "Einflussfaktoren\nEin Heimspeicher nimmt tagsüber Solarstrom auf, der im Haus nicht\ngebraucht " +
            "wird, und gibt ihn abends wieder ab, wenn die Sonne nicht mehr\nscheint.\n2.1 Offene Punkte\nDie " +
            "Größe des Speichers richtet sich nach dem Jahresverbrauch und\nder Leistung der Anlage auf dem Dach.\n"
        val text = com.veritas.localresearch.scraping.PdfTextExtractor.clean(com.veritas.localresearch.scraping.PdfTextExtractor.markHeadings(raw))
        assertTrue(text, text.startsWith("Einflussfaktoren\n\nEin Heimspeicher"))
        assertTrue(text, text.contains("nicht mehr scheint.\n\n2.1 Offene Punkte\n\nDie Größe"))
    }

    @Test
    fun wrappedLineBeforeNumberIsNoHeading() {
        val raw = "Ein Heimspeicher hält meist zehn bis fünfzehn Jahre und verliert dabei langsam an Kapazität.\n" +
            "Nach dieser Zeit hat der Speicher in der Regel noch etwa 70 bis\n80 Prozent seiner ursprünglichen " +
            "Kapazität und kann weiter genutzt werden.\nDie Hersteller geben dafür meist eine Garantie über zehn Jahre.\n"
        val text = com.veritas.localresearch.scraping.PdfTextExtractor.clean(com.veritas.localresearch.scraping.PdfTextExtractor.markHeadings(raw))
        assertTrue(text, text.contains("etwa 70 bis 80 Prozent"))
    }

    @Test
    fun wideLineSpacingIsJoined() {
        val raw = "Funktionsweise\n\nEin Heimspeicher nimmt tagsüber den Solarstrom auf, der im Haus gerade nicht\n\n" +
            "verbraucht wird. Abends gibt er diesen Strom wieder ab, sodass weniger Strom aus dem\n\n" +
            "Netz bezogen werden muss. Nach dieser Zeit hat der Speicher in der Regel noch etwa 70 bis\n\n" +
            "80 Prozent seiner ursprünglichen Kapazität.\n\nKosten\n\nEin Speicher mit zehn Kilowattstunden " +
            "kostet einschließlich Installation\n\nmehrere Tausend Euro, je nach Hersteller und Region.\n"
        val p = com.veritas.localresearch.scraping.PdfTextExtractor
        val text = p.clean(p.markHeadings(p.joinSpacedLines(raw)))
        assertTrue(text, text.contains("etwa 70 bis 80 Prozent"))
        assertTrue(text, text.contains("nicht verbraucht wird"))
        assertTrue(text, text.contains("Kapazität.\n\nKosten\n\nEin Speicher"))
    }

    @Test
    fun ocrLinesAreJoined() {
        val ocr = "Stand\n\nEin Heimspeicher nimmt tagsüber Solarstrom auf, der im Haus nicht\n\ngebraucht wird, und " +
            "gibt ihn abends wieder ab.\n\nDie Größe des Speichers richtet sich nach dem\n\n" +
            "Jahresverbrauch und der Leistung der Anlage auf dem Dach.\n"
        val text = com.veritas.localresearch.scraping.PdfTextExtractor.cleanOcr(ocr)
        assertTrue(text, text.contains("im Haus nicht gebraucht wird"))
        assertTrue(text, text.contains("nach dem Jahresverbrauch"))
    }

    @Test
    fun chunksKnowTheirChapter() {
        val text = "Grundlagen der Solartechnik\n\n1 Einleitung\n\nSolarzellen wandeln Licht direkt in Strom um. " +
            "Das gilt für alle gängigen Bauarten.\n\n2 Speicher\n\nEin Speicher nimmt Strom auf und gibt ihn später ab.\n\n" +
            "2.1 Lebensdauer\n\nDie Zellen halten meist zehn bis fünfzehn Jahre und verlieren dabei Kapazität.\n\n" +
            "Kapitel 3 Kosten\n\nDie Preise sind in den letzten Jahren deutlich gesunken."
        val heads = LocalDocumentStore.headings(text)
        assertEquals(listOf("Grundlagen der Solartechnik", "1 Einleitung", "2 Speicher", "2.1 Lebensdauer", "Kapitel 3 Kosten"), heads.map { it.text })
        assertEquals("2 Speicher › 2.1 Lebensdauer", LocalDocumentStore.sectionAt(heads, text.indexOf("Die Zellen")))
        assertEquals("2 Speicher", LocalDocumentStore.sectionAt(heads, text.indexOf("Ein Speicher")))
        assertEquals("Kapitel 3 Kosten", LocalDocumentStore.sectionAt(heads, text.indexOf("Die Preise")))
        assertEquals("", LocalDocumentStore.sectionAt(heads, 0).let { if (it == "Grundlagen der Solartechnik") "" else it })
        val chunk = TextChunk(1, "local:x", "x.pdf", "Buch", "Die Zellen halten meist zehn bis fünfzehn Jahre", SourceType.LOCAL)
        val out = LocalDocumentStore.annotate(listOf(chunk), text, emptyList(), heads)
        assertEquals("Buch › 2 Speicher › 2.1 Lebensdauer", out[0].titleWithSection)
    }

    @Test
    fun keywordSearchFindsCompoundsAndStems() {
        val store = com.veritas.localresearch.rag.Fts5ChunkStore()
        fun c(id: Int, t: String) = TextChunk(id, "local:x", "x.pdf", "Buch", t, SourceType.LOCAL)
        store.insertChunks(listOf(
            c(1, "Die Grundgebühr des Speichers beträgt vier Euro im Monat."),
            c(2, "Die Wartung erfolgt einmal im Jahr durch eine Fachkraft."),
            c(3, "Speicher kosten heute weniger als früher.")
        ))
        assertEquals(1, store.searchBm25("Welche Gebühren fallen an?").firstOrNull())
        assertEquals(3, store.searchBm25("Was kosten Speicher?").firstOrNull())
    }

    @Test
    fun sectionTextStopsAtNextHeading() {
        assertEquals("Er lohnt sich meist nicht [1].", DeepResearchEngine.cutSectionText("## Kosten\nEr lohnt sich meist nicht [1].## Speicher und L [1]\nWeiter."))
        assertEquals("Satz eins [1].\nSatz zwei [2].", DeepResearchEngine.cutSectionText("Satz eins [1].\nSatz zwei [2]."))
    }

    @Test
    fun footnoteMarksAreRemoved() {
        val text = com.veritas.localresearch.scraping.PdfTextExtractor.clean("Das ist nicht geregelt3, aber üblich7. Der CO2-Preis steigt.")
        assertEquals("Das ist nicht geregelt, aber üblich. Der CO2-Preis steigt.", text)
    }

    @Test
    fun captionsAndLooseNumbersAreNoChapters() {
        val text = "1 Einleitung\n\nSolarzellen wandeln Licht direkt in Strom um.\n\nAbb. 1.4 Aufbau einer Zelle\n\n" +
            "Die Zelle besteht aus zwei Schichten.\n\n9 Nachfragekurve\n\nDie Kurve fällt mit steigendem Preis.\n\n7. Auflage\n\nText."
        val heads = LocalDocumentStore.headings(text)
        assertFalse(heads.any { it.text.startsWith("Abb.") || it.text.contains("Auflage") })
        assertFalse(heads.any { it.chapter })
    }

    @Test
    fun outlineGivesChapterAndSection() {
        val outline = listOf(
            OutlineEntry(1, 0, "1 Grundlagen"), OutlineEntry(3, 1, "1.1 Begriffe"), OutlineEntry(7, 1, "1.2 Speicher"),
            OutlineEntry(9, 2, "1.2.1 Lebensdauer"), OutlineEntry(12, 0, "2 Kosten")
        )
        assertEquals("1 Grundlagen › 1.1 Begriffe", LocalDocumentStore.outlineAt(outline, 5))
        assertEquals("1.2 Speicher › 1.2.1 Lebensdauer", LocalDocumentStore.outlineAt(outline, 10))
        assertEquals("2 Kosten", LocalDocumentStore.outlineAt(outline, 12))
        assertEquals("", LocalDocumentStore.outlineAt(outline.drop(0).filter { it.page > 1 }, 1))
    }

    @Test
    fun referenceEntriesAreNoFacts() {
        val lit = com.veritas.localresearch.verification.DeterministicVerifier.LITERATURE_REGEX
        assertTrue(lit.containsMatchIn("Muster, S. (2025). Solarstrom im Eigenheim."))
        assertTrue(lit.containsMatchIn("(2025, 11. März). Solarstrom im Eigenheim."))
        assertTrue(lit.containsMatchIn("Erika Muster. (2025, 11. März). Solarstrom im Eigenheim."))
        assertFalse(lit.containsMatchIn("Der Bericht erschien 2025. Er nennt drei Gründe."))
        assertTrue(lit.containsMatchIn("Solarstrom im Eigenheim. Abgerufen am 2. Mai 2026, von https://example.org/solar"))
        assertFalse(lit.containsMatchIn("Muster sagt, dass Speicher sich lohnen."))
    }

    @Test
    fun overviewQuestions() {
        assertTrue(DeepResearchEngine.isOverviewQuestion("Fasse das Dokument zusammen"))
        assertTrue(DeepResearchEngine.isOverviewQuestion("Worum geht es in dem Bericht?"))
        assertTrue(DeepResearchEngine.isOverviewQuestion("What is this paper about?"))
        assertTrue(DeepResearchEngine.isOverviewQuestion("Was sind die Kernaussagen?"))
        assertFalse(DeepResearchEngine.isOverviewQuestion("Wie viel Strom speichert ein Solarspeicher?"))
    }

    @Test
    fun siteBoilerplateIsNoFact() {
        assertTrue(DeepResearchEngine.isBoilerplate("Your use of Example Wiki is subject to our terms of service, available here."))
        assertTrue(DeepResearchEngine.isBoilerplate("Example Wiki is a publicly accessible and editable website for gathering information."))
        assertTrue(DeepResearchEngine.isBoilerplate("Diese Seite enthält Hinweise zum Datenschutz."))
        assertTrue(DeepResearchEngine.isBoilerplate("The service is provided as is, without any warranty of any kind."))
        assertTrue(DeepResearchEngine.isBoilerplate("Articles on this site have not necessarily been reviewed by the publisher."))
        assertTrue(DeepResearchEngine.isBoilerplate("Report problems directly to the site moderators."))
        assertTrue(DeepResearchEngine.isBoilerplate("Users may buy virtual items for real money."))
        assertTrue(DeepResearchEngine.isLegalPage(ScrapedDocument(url = "https://example.org/terms-of-use", title = "Terms", cleanText = "x", domain = "example.org", sourceType = SourceType.WEB)))
        assertFalse(DeepResearchEngine.isLegalPage(ScrapedDocument(url = "https://example.org/guide/heat-pumps", title = "Heat pumps", cleanText = "x", domain = "example.org", sourceType = SourceType.WEB)))
        assertFalse(DeepResearchEngine.isBoilerplate("A home battery of about 8 kilowatt hours fits a household that uses 4,000 kilowatt hours per year."))
        assertFalse(DeepResearchEngine.isBoilerplate("Wärmepumpen erreichen bei -20 Grad noch 40 bis 55 Prozent ihrer Nennleistung."))
    }
}
