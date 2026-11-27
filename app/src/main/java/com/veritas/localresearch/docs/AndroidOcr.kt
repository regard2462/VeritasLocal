package com.veritas.localresearch.docs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** Android: Seiten mit dem PdfRenderer des Systems als Bild (etwa 200 dpi), Erkennung mit Tesseract4Android. */
class AndroidOcr(private val context: Context) : OcrEngine {

    override suspend fun pdfPages(bytes: ByteArray, tessdataParent: File, langs: String, pages: List<Int>?, maxPages: Int,
                                  onPage: (Int, Int) -> Unit): Map<Int, String>? = withContext(Dispatchers.Default) {
        val tmp = File(context.cacheDir, "ocr-${System.nanoTime()}.pdf")
        tmp.writeBytes(bytes)
        try {
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val todo = (pages ?: (0 until renderer.pageCount).toList()).filter { it < renderer.pageCount }.take(maxPages)
                    val tess = TessBaseAPI()
                    if (!tess.init(tessdataParent.absolutePath, langs)) { tess.recycle(); return@withContext null }
                    tess.setVariable("user_defined_dpi", "200")
                    val out = HashMap<Int, String>()
                    try {
                        todo.forEachIndexed { k, i ->
                            coroutineContext.ensureActive()
                            onPage(k + 1, todo.size)
                            renderer.openPage(i).use { page ->
                                val scale = (200f / 72f).coerceAtMost(2600f / maxOf(page.width, page.height))
                                val bmp = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1),
                                    (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                tess.setImage(bmp)
                                out[i] = tess.utF8Text.orEmpty()
                                tess.clear()
                                bmp.recycle()
                            }
                        }
                    } finally {
                        tess.recycle()
                    }
                    out
                }
            }
        } finally {
            tmp.delete()
        }
    }

    override suspend fun image(bytes: ByteArray, tessdataParent: File, langs: String): String? = withContext(Dispatchers.Default) {
        // Grosse Fotos verkleinern: Tesseract braucht keine 12 Megapixel
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return@withContext null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2600) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@withContext null
        val tess = TessBaseAPI()
        try {
            if (!tess.init(tessdataParent.absolutePath, langs)) return@withContext null
            tess.setVariable("user_defined_dpi", "200")
            tess.setImage(bmp)
            tess.utF8Text.orEmpty()
        } finally {
            tess.recycle()
            bmp.recycle()
        }
    }
}
