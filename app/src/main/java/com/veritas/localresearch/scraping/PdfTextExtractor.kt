package com.veritas.localresearch.scraping

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
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

    companion object {
        const val MAX_PAGES = 40
        const val MAX_CHARS = 40_000
        const val MAX_BYTES = 15L * 1024 * 1024

        private val HYPHEN_BREAK = Regex("(\\p{Ll})-\\n(\\p{Ll})")
        private val SINGLE_BREAK = Regex("(?<!\\n)\\n(?!\\n)")
        private val SPACES = Regex("[ \\t\\u00A0]+")
        private val MANY_BREAKS = Regex("\\n{3,}")
        private val PAGE_NUMBER_LINE = Regex("(?m)^\\s*(Seite\\s+)?\\d{1,4}(\\s*(von|/)\\s*\\d{1,4})?\\s*$")

        /** Silbentrennung am Zeilenende zusammenfuegen, Zeilen eines Absatzes verbinden, Seitenzahlen entfernen. */
        fun clean(raw: String): String = raw
            .replace("\r\n", "\n")
            .replace(PAGE_NUMBER_LINE, "")
            .replace(HYPHEN_BREAK, "$1$2")
            .replace(SINGLE_BREAK, " ")
            .replace(SPACES, " ")
            .replace(MANY_BREAKS, "\n\n")
            .trim()
    }
}
