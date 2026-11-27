package com.veritas.localresearch.docs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.sourceforge.tess4j.Tesseract
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.coroutines.coroutineContext

/**
 * Desktop: Seiten mit PDFBox als Graustufenbild (200 dpi), Erkennung mit Tesseract ueber Tess4J. Unter Windows
 * bringt Tess4J die Bibliotheken mit, unter Linux wird libtesseract des Systems genutzt (Paket tesseract-ocr).
 */
class DesktopOcr : OcrEngine {

    private fun tesseract(tessdataParent: File, langs: String) = Tesseract().apply {
        setDatapath(File(tessdataParent, "tessdata").absolutePath)
        setLanguage(langs)
        // Seiten werden mit 200 dpi gerendert; Fotos ohne Angabe bekommen denselben Wert
        setVariable("user_defined_dpi", "200")
    }

    private inline fun <T> native(block: () -> T): T = try {
        block()
    } catch (e: UnsatisfiedLinkError) {
        error("Texterkennung nicht verfügbar: Tesseract fehlt (unter Linux das Paket tesseract-ocr installieren).")
    }

    override suspend fun pdfPages(bytes: ByteArray, tessdataParent: File, langs: String, pages: List<Int>?, maxPages: Int,
                                  onPage: (Int, Int) -> Unit): Map<Int, String>? = withContext(Dispatchers.Default) {
        PDDocument.load(bytes).use { doc ->
            val renderer = PDFRenderer(doc)
            val todo = (pages ?: (0 until doc.numberOfPages).toList()).filter { it < doc.numberOfPages }.take(maxPages)
            val tess = native { tesseract(tessdataParent, langs) }
            val out = HashMap<Int, String>()
            todo.forEachIndexed { k, i ->
                coroutineContext.ensureActive()
                onPage(k + 1, todo.size)
                val img = renderer.renderImageWithDPI(i, 200f, ImageType.GRAY)
                out[i] = native { tess.doOCR(img) }.orEmpty()
            }
            out
        }
    }

    override suspend fun image(bytes: ByteArray, tessdataParent: File, langs: String): String? = withContext(Dispatchers.Default) {
        val img = ImageIO.read(ByteArrayInputStream(bytes)) ?: return@withContext null
        native { tesseract(tessdataParent, langs).doOCR(img) }
    }
}
