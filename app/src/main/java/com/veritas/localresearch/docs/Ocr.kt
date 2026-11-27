package com.veritas.localresearch.docs

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Texterkennung (Tesseract) fuer PDF-Seiten ohne Textebene und fuer Bilder. Die Umsetzung haengt von der
 * Plattform ab (Android: Tesseract4Android mit PdfRenderer, Desktop: Tess4J mit PDFBox) und wird beim Start gesetzt.
 */
interface OcrEngine {
    /** Text der Seiten [pages] (0-basiert, null = alle), hoechstens [maxPages]. Null, wenn Tesseract nicht startet. */
    suspend fun pdfPages(bytes: ByteArray, tessdataParent: File, langs: String, pages: List<Int>?, maxPages: Int,
                         onPage: (Int, Int) -> Unit): Map<Int, String>?

    /** Text eines Bildes (JPG, PNG, WebP, BMP); null, wenn das Bild nicht lesbar ist. */
    suspend fun image(bytes: ByteArray, tessdataParent: File, langs: String): String?
}

object Ocr {
    @Volatile var engine: OcrEngine? = null

    const val LANGS = "deu+eng"
    const val MAX_PAGES = 2000

    /** Schnelle Tesseract-Sprachdaten (Apache-2.0), zusammen etwa 6 MB. */
    private val FILES = listOf(
        "deu.traineddata" to "https://github.com/tesseract-ocr/tessdata_fast/raw/main/deu.traineddata",
        "eng.traineddata" to "https://github.com/tesseract-ocr/tessdata_fast/raw/main/eng.traineddata"
    )

    /** Ordner, der den Unterordner tessdata enthaelt. */
    fun dataParent(context: Context): File = File(context.filesDir, "ocr")

    /** Sprachdaten beim ersten Bedarf laden. Liefert den Ordner oder einen Fehler mit Grund. */
    suspend fun ensureData(context: Context, onStatus: (String) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(dataParent(context), "tessdata").apply { mkdirs() }
            val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true).build()
            for ((name, url) in FILES) {
                val f = File(dir, name)
                if (f.isFile && f.length() > 100_000) continue
                onStatus("Lade Sprachdaten für die Texterkennung ($name)…")
                val req = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("Sprachdaten nicht ladbar (HTTP ${resp.code})")
                    val tmp = File(dir, "$name.part")
                    resp.body!!.byteStream().use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                    if (tmp.length() < 100_000) { tmp.delete(); error("Sprachdaten unvollständig") }
                    if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
                }
            }
            dataParent(context)
        }
    }
}
