package com.veritas.localresearch.scraping

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Liest den Text aus PDFs (Studien, Fachberichte, Diskussionspapiere), mit PdfBox-Android.
 * Begrenzt auf die ersten [MAX_PAGES] Seiten und [MAX_CHARS] Zeichen, damit grosse Dokumente den Speicher nicht sprengen.
 */
class PdfTextExtractor(private val context: Context) {

    private val initialized = AtomicBoolean(false)

    private fun ensureInit() {
        if (initialized.compareAndSet(false, true)) PDFBoxResourceLoader.init(context.applicationContext)
    }

    /** Text und Dokumentinfo (Titel, Autoren, Erstelljahr) einer PDF-Datei. */
    data class PdfContent(val text: String, val title: String, val authors: List<String>, val date: String)

    suspend fun extract(bytes: ByteArray): PdfContent? = withContext(Dispatchers.Default) {
        runCatching {
            ensureInit()
            // Puffer im Hauptspeicher bis 32 MB, darueber in Zwischendateien
            PDDocument.load(bytes, "", null, null, MemoryUsageSetting.setupMixed(32L * 1024 * 1024)).use { doc ->
                val stripper = PDFTextStripper().apply {
                    sortByPosition = true
                    startPage = 1
                    endPage = minOf(doc.numberOfPages, MAX_PAGES)
                    paragraphEnd = "\n\n"
                }
                val raw = stripper.getText(doc)
                val info = doc.documentInformation
                val title = info?.title.orEmpty().trim()
                // Autor-Feld oft "A; B" oder "A, B und C"; Programmnamen (Word, LaTeX) sind keine Autoren
                val authors = info?.author.orEmpty().split(';', '/', '|')
                    .map { it.trim() }
                    .filter { it.length in 3..80 && !it.contains("microsoft", true) && !it.contains("user", true) }
                val year = runCatching { info?.creationDate?.get(java.util.Calendar.YEAR) }.getOrNull()
                PdfContent(clean(raw).take(MAX_CHARS), title, authors, year?.takeIf { it in 1900..2100 }?.toString().orEmpty())
            }
        }.getOrNull()?.takeIf { it.text.length >= 300 }
    }

    /** Eigenes Dokument: Text je Seite (bereinigt), dazu Dokumentinfo. Leere Liste, wenn kein Text lesbar ist (Scan). */
    /** Lesezeichen: Seite (0-basiert), Ebene (0 = oberste), Titel. */
    data class OutlineItem(val page: Int, val level: Int, val title: String)

    data class PdfPages(
        val pages: List<String>,
        val title: String,
        val authors: List<String>,
        val date: String,
        val outline: List<OutlineItem> = emptyList()
    )

    /** Lesezeichen des PDFs in Reihenfolge, bis Ebene 4 und hoechstens 5000 Eintraege. */
    private fun outlineOf(doc: PDDocument): List<OutlineItem> = runCatching {
        val root = doc.documentCatalog?.documentOutline ?: return emptyList()
        val out = ArrayList<OutlineItem>()
        fun walk(node: PDOutlineNode, level: Int) {
            var child = node.firstChild
            var guard = 0
            while (child != null && out.size < 5000 && guard++ < 5000) {
                val title = child.title.orEmpty().replace(Regex("\\s+"), " ").trim()
                val page = runCatching { child.findDestinationPage(doc)?.let { doc.pages.indexOf(it) } }.getOrNull() ?: -1
                if (title.isNotEmpty() && page >= 0) out.add(OutlineItem(page, level, title.take(120)))
                if (level < 3) walk(child, level + 1)
                child = child.nextSibling
            }
        }
        walk(root, 0)
        out
    }.getOrDefault(emptyList())

    suspend fun extractPages(bytes: ByteArray, maxPages: Int = Int.MAX_VALUE): PdfPages? = withContext(Dispatchers.Default) {
        runCatching {
            ensureInit()
            PDDocument.load(bytes, "", null, null, MemoryUsageSetting.setupMixed(64L * 1024 * 1024)).use { doc ->
                val stripper = PDFTextStripper().apply {
                    sortByPosition = true
                    startPage = 1
                    endPage = minOf(doc.numberOfPages, maxPages)
                    paragraphEnd = "\n\n"
                    pageEnd = "\u000C"
                }
                val pages = stripper.getText(doc).split('\u000C').map { clean(markHeadings(joinSpacedLines(it))) }
                    .let { if (it.isNotEmpty() && it.last().isBlank()) it.dropLast(1) else it }
                val info = doc.documentInformation
                val authors = info?.author.orEmpty().split(';', '/', '|')
                    .map { it.trim() }
                    .filter { it.length in 3..80 && !it.contains("microsoft", true) && !it.contains("user", true) }
                val year = runCatching { info?.creationDate?.get(java.util.Calendar.YEAR) }.getOrNull()
                PdfPages(pages, info?.title.orEmpty().trim(), authors, year?.takeIf { it in 1900..2100 }?.toString().orEmpty(), outlineOf(doc))
            }
        }.getOrNull()
    }

    companion object {
        const val MAX_PAGES = 40
        const val MAX_CHARS = 40_000
        const val MAX_BYTES = 15L * 1024 * 1024

        private val HYPHEN_BREAK = Regex("(\\p{Ll})-\\n(\\p{Ll})")
        private val SINGLE_BREAK = Regex("(?<!\\n)\\n(?!\\n)")
        /** Fussnotenziffer am Wortende ("zuzustimmen6", "geregelt3."), nicht bei Formeln wie "CO2". */
        private val FOOTNOTE_MARK = Regex("(?<=\\p{Ll}{3})\\d{1,2}(?=[\\s.,;:)]|$)")
        private val SPACES = Regex("[ \\t\\u00A0]+")
        private val MANY_BREAKS = Regex("\\n{3,}")
        private val PAGE_NUMBER_LINE = Regex("(?m)^\\s*(Seite\\s+)?\\d{1,4}(\\s*(von|/)\\s*\\d{1,4})?\\s*$")

        /**
         * Kurze Zeilen ohne Satzzeichen am Ende, die gross oder mit einer Abschnittsnummer beginnen, sind Ueberschriften:
         * sie bekommen Leerzeilen davor und danach, damit [clean] sie nicht mit dem Absatz verbindet.
         */
        fun markHeadings(raw: String): String {
            val lines = raw.replace("\r\n", "\n").split('\n')
            val lengths = lines.map { it.trim().length }.filter { it >= 20 }.sorted()
            if (lengths.size < 4) return raw
            val typical = lengths[lengths.size * 3 / 4]
            val out = StringBuilder()
            for ((i, line) in lines.withIndex()) {
                val t = line.trim()
                val next = lines.getOrNull(i + 1)?.trim().orEmpty()
                val prev = lines.getOrNull(i - 1)?.trim().orEmpty()
                // Ueberschrift nur nach Absatzende, Leerzeile oder einer weiteren kurzen Zeile, sonst Satz mitten im Absatz
                val afterBreak = prev.isEmpty() || prev.last() in ".!?:\"“”)" || prev.length < typical * 0.7
                // Geht die naechste Zeile klein weiter, ist die kurze Zeile nur ein umbrochener Satz
                // Endet die Zeile auf ein Fuellwort ("dem", "bis") oder geht mit einer Zahl weiter, laeuft der Satz weiter
                val openEnd = t.substringAfterLast(' ').lowercase() in OPEN_END_WORDS
                val heading = t.length in 3..80 && t.length < typical * 0.7 && afterBreak && !openEnd && next.isNotEmpty() &&
                    !next.first().isLowerCase() && !next.first().isDigit() &&
                    t.last() !in ".,;:!?-–" && HEADING_START.containsMatchIn(t) && t.count(Char::isLetter) * 2 > t.length
                if (heading) out.append("\n\n").append(t).append("\n\n") else out.append(line).append('\n')
            }
            return out.toString()
        }

        /**
         * Weiter Zeilenabstand: PDFBox gibt dann nach fast jeder Zeile eine Leerzeile aus, jede Zeile waere ein Absatz.
         * Steht nach den meisten Zeilen eine Leerzeile, werden sie wieder zu Zeilen; Ueberschriften findet [markHeadings].
         */
        fun joinSpacedLines(raw: String): String {
            val lines = raw.replace("\r\n", "\n").split('\n')
            var filled = 0
            var spaced = 0
            for (i in lines.indices) {
                if (lines[i].isBlank()) continue
                filled++
                if (i + 1 < lines.size && lines[i + 1].isBlank()) spaced++
            }
            return if (filled >= 6 && spaced * 10 >= filled * 6) raw.replace(OCR_BLANK_LINES, "\n") else raw
        }

        /** Texterkennung trennt oft jede Zeile mit einer Leerzeile: erst wieder zu Zeilen, dann wie PDF-Text bereinigen. */
        fun cleanOcr(raw: String): String = clean(markHeadings(raw.replace(OCR_BLANK_LINES, "\n")))

        private val OCR_BLANK_LINES = Regex("\\n[ \\t]*(?:\\n[ \\t]*)+")

        private val OPEN_END_WORDS = setOf(
            "der", "die", "das", "dem", "den", "des", "ein", "eine", "einen", "einem", "einer", "eines", "und", "oder",
            "sowie", "nach", "mit", "von", "vom", "zu", "zum", "zur", "für", "bis", "bei", "beim", "aus", "auf", "in", "im",
            "an", "am", "um", "über", "unter", "vor", "als", "wie", "etwa", "noch", "nicht", "dass", "weil", "wenn", "ist",
            "sind", "hat", "haben", "wird", "werden", "kann", "the", "a", "an", "of", "and", "or", "to", "for", "with", "on",
            "by", "at", "from", "is", "are", "that", "than"
        )

        private val HEADING_START =Regex("^(?:\\d+(?:\\.\\d+)*\\.?\\s+)?\\p{Lu}")

        /** Silbentrennung am Zeilenende zusammenfuegen, Zeilen eines Absatzes verbinden, Seitenzahlen entfernen. */
        fun clean(raw: String): String = raw
            .replace("\r\n", "\n")
            .replace(PAGE_NUMBER_LINE, "")
            .replace(HYPHEN_BREAK, "$1$2")
            .replace(SINGLE_BREAK, " ")
            .replace(SPACES, " ")
            .replace(FOOTNOTE_MARK, "")
            .replace(MANY_BREAKS, "\n\n")
            .trim()
    }
}
