package com.veritas.localresearch.export

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Schreibt die Arbeit als PDF (A4) mit PdfBox: Inhaltsverzeichnis mit Seitenzahlen und Sprunglinks, Lesezeichen
 * je Abschnitt, Verweise als Links ins Literaturverzeichnis, anklickbare Webadressen und Seitenzahlen in der Fusszeile.
 * Schrift: Noto Serif des Systems (eingebettet), sonst Helvetica.
 */
class PdfWriter(private val context: Context) {

    private enum class Face { REGULAR, BOLD, ITALIC }
    private data class Style(val face: Face, val size: Float, val color: Int = 0x000000, val uri: String? = null, val anchor: String? = null)
    private data class Word(val text: String, val style: Style)
    private data class Pending(val page: PDPage, val rect: PDRectangle, val anchor: String)

    private val pageW = PDRectangle.A4.width
    private val pageH = PDRectangle.A4.height
    private val left = 70f
    private val right = 70f
    private val top = 72f
    private val bottom = 70f
    private val width get() = pageW - left - right

    private lateinit var pdf: PDDocument
    private lateinit var fonts: Map<Face, PDFont>
    private var page: PDPage? = null
    private var cs: PDPageContentStream? = null
    private var y = 0f
    private val anchors = HashMap<String, Pair<PDPage, Float>>()
    private val pending = ArrayList<Pending>()

    fun write(doc: ExportDocument): ByteArray {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument().use { d ->
            pdf = d
            fonts = loadFonts(d)
            d.documentInformation = PDDocumentInformation().apply { title = doc.title; creator = "VeritasLocal" }

            // Titelseite mit Inhaltsverzeichnis; Seitenzahlen werden nachgetragen, wenn sie feststehen
            newPage()
            paragraph(words(doc.title, Style(Face.BOLD, 19f)), lineGap = 1.25f, after = 6f)
            paragraph(words(doc.subtitle, Style(Face.ITALIC, 9.5f, 0x595959)), after = 22f)
            paragraph(words(doc.tocTitle, Style(Face.BOLD, 14f)), after = 8f)
            val tocLines = ArrayList<Triple<PDPage, Float, String>>() // Seite, Grundlinie, Sprungziel
            for ((text, anchor) in doc.tocEntries) {
                // Lange Eintraege umbrechen statt abschneiden; Seitenzahl steht auf der letzten Zeile
                val style = Style(Face.REGULAR, 11f)
                val lines = ArrayList<List<Word>>()
                var cur = ArrayList<Word>(); var curW = 0f
                for (w in words(text, style)) {
                    val ww = widthOf(w)
                    if (curW + ww > width - 40f && cur.isNotEmpty()) { lines.add(cur); cur = ArrayList(); curW = 0f }
                    cur.add(w); curW += ww
                }
                if (cur.isNotEmpty()) lines.add(cur)
                ensureSpace(16f * lines.size)
                var baseline = 0f
                for (l in lines) {
                    baseline = pageH - y - 11f
                    drawLine(l, baseline)
                    y += 16f
                }
                tocLines.add(Triple(page!!, baseline, anchor))
            }

            // Abschnitte
            newPage()
            val outline = PDDocumentOutline()
            for (b in doc.blocks) when (b) {
                is Block.Heading -> {
                    ensureSpace(60f)
                    y += 10f
                    anchors[b.anchor] = page!! to (pageH - y + 4f)
                    outline.addLast(PDOutlineItem().apply { title = b.text; destination = dest(page!!, pageH - y + 4f) })
                    paragraph(words(b.text, Style(Face.BOLD, 14f)), after = 6f)
                }
                is Block.Paragraph -> {
                    val ws = b.runs.flatMap { r ->
                        when (r) {
                            is Run.Text -> words(r.text, Style(Face.REGULAR, 11f))
                            is Run.Cite -> words(r.label, Style(Face.REGULAR, 11f, 0x0563C1, anchor = r.anchors.first()))
                        }
                    }
                    paragraph(ws, after = 8f)
                }
            }

            // Literaturverzeichnis
            ensureSpace(60f)
            y += 10f
            anchors[doc.bibAnchor] = page!! to (pageH - y + 4f)
            outline.addLast(PDOutlineItem().apply { title = doc.bibTitle; destination = dest(page!!, pageH - y + 4f) })
            paragraph(words(doc.bibTitle, Style(Face.BOLD, 14f)), after = 6f)
            for ((entry, segs) in doc.bibliography) {
                ensureSpace(30f)
                anchors[entry.anchor] = page!! to (pageH - y + 4f)
                val ws = segs.flatMap { s ->
                    words(s.text, when {
                        s.link != null -> Style(Face.REGULAR, 10f, 0x0563C1, uri = s.link)
                        s.italic -> Style(Face.ITALIC, 10f)
                        else -> Style(Face.REGULAR, 10f)
                    })
                }
                paragraph(ws, after = 6f, hanging = 22f)
            }
            cs?.close(); cs = null

            // Seitenzahlen im Inhaltsverzeichnis nachtragen
            val pages = d.pages.toList()
            val tocPageSet = tocLines.map { it.first }.toSet()
            for (p in tocPageSet) {
                PDPageContentStream(d, p, PDPageContentStream.AppendMode.APPEND, true, true).use { s ->
                    for ((tp, baseline, anchor) in tocLines.filter { it.first == p }) {
                        val target = anchors[anchor] ?: continue
                        val no = (pages.indexOf(target.first) + 1).toString()
                        val f = fonts.getValue(Face.REGULAR)
                        val w = f.getStringWidth(no) / 1000f * 11f
                        s.beginText(); s.setFont(f, 11f); s.newLineAtOffset(pageW - right - w, baseline); s.showText(no); s.endText()
                        addLink(tp, PDRectangle(left, baseline - 3f, width, 14f), goTo = target)
                    }
                }
            }
            // Fusszeile mit Seitenzahl
            pages.forEachIndexed { i, p ->
                PDPageContentStream(d, p, PDPageContentStream.AppendMode.APPEND, true, true).use { s ->
                    val text = "${i + 1} / ${pages.size}"
                    val f = fonts.getValue(Face.REGULAR)
                    val w = f.getStringWidth(text) / 1000f * 9f
                    s.setNonStrokingColor(0.45f, 0.45f, 0.45f)
                    s.beginText(); s.setFont(f, 9f); s.newLineAtOffset((pageW - w) / 2f, 36f); s.showText(text); s.endText()
                }
            }
            // Interne Sprunglinks, jetzt wo alle Ziele feststehen
            for (pl in pending) anchors[pl.anchor]?.let { addLink(pl.page, pl.rect, goTo = it) }

            d.documentCatalog.documentOutline = outline
            val bos = ByteArrayOutputStream()
            d.save(bos)
            return bos.toByteArray()
        }
    }

    private fun loadFonts(d: PDDocument): Map<Face, PDFont> {
        // Schriftordner von Android, Windows und Linux; die erste vorhandene Datei gewinnt
        val dirs = listOf("/system/fonts", "C:/Windows/Fonts", "/usr/share/fonts/truetype/noto", "/usr/share/fonts/noto",
            "/usr/share/fonts/truetype/dejavu", "/usr/share/fonts/dejavu", "/usr/share/fonts/TTF",
            "/usr/share/fonts/truetype/liberation", "/usr/share/fonts/liberation")
        fun load(vararg names: String): PDFont? = names.firstNotNullOfOrNull { n ->
            dirs.firstNotNullOfOrNull { dir ->
                val f = File(dir, n)
                if (f.exists()) runCatching { PDType0Font.load(d, f) }.getOrNull() else null
            }
        }
        val regular = load("NotoSerif-Regular.ttf", "georgia.ttf", "DejaVuSerif.ttf", "LiberationSerif-Regular.ttf",
            "RobotoStatic-Regular.ttf", "DroidSans.ttf") ?: PDType1Font.HELVETICA
        return mapOf(
            Face.REGULAR to regular,
            Face.BOLD to (load("NotoSerif-Bold.ttf", "georgiab.ttf", "DejaVuSerif-Bold.ttf", "LiberationSerif-Bold.ttf", "DroidSans-Bold.ttf")
                ?: if (regular is PDType1Font) PDType1Font.HELVETICA_BOLD else regular),
            Face.ITALIC to (load("NotoSerif-Italic.ttf", "georgiai.ttf", "DejaVuSerif-Italic.ttf", "LiberationSerif-Italic.ttf")
                ?: if (regular is PDType1Font) PDType1Font.HELVETICA_OBLIQUE else regular)
        )
    }

    /** Text in Woerter mit anhaengendem Leerzeichen zerlegen. */
    private fun words(text: String, style: Style): List<Word> =
        Regex("\\S+\\s*|\\s+").findAll(text).map { Word(it.value.replace(Regex("\\s+"), " "), style) }.toList()

    /** Zeichen, die die Schrift nicht kennt, durch "?" ersetzen. */
    private fun safe(f: PDFont, s: String): String = runCatching { f.encode(s); s }.getOrElse {
        s.map { c -> if (runCatching { f.encode(c.toString()) }.isSuccess) c else '?' }.joinToString("")
    }

    private fun widthOf(w: Word): Float {
        val f = fonts.getValue(w.style.face)
        return f.getStringWidth(safe(f, w.text)) / 1000f * w.style.size
    }

    private fun newPage() {
        cs?.close()
        val p = PDPage(PDRectangle.A4)
        pdf.addPage(p)
        page = p
        cs = PDPageContentStream(pdf, p)
        y = top
    }

    private fun ensureSpace(h: Float) {
        if (y + h > pageH - bottom) newPage()
    }

    /** Absatz mit Zeilenumbruch; [hanging]: Folgezeilen eingerueckt (Literaturverzeichnis). */
    private fun paragraph(ws: List<Word>, lineGap: Float = 1.4f, after: Float = 0f, hanging: Float = 0f) {
        var line = ArrayList<Word>()
        var lineW = 0f
        var first = true
        fun flush() {
            if (line.isEmpty()) return
            val size = line.maxOf { it.style.size }
            val lh = size * lineGap
            ensureSpace(lh)
            val baseline = pageH - y - size
            drawLine(line, baseline, indent = if (first) 0f else hanging)
            y += lh
            line = ArrayList(); lineW = 0f; first = false
        }
        for (w in ws) {
            val avail = width - (if (first) 0f else hanging)
            val ww = widthOf(w)
            if (lineW + ww > avail && line.isNotEmpty()) {
                // Leerzeichen am Zeilenende zaehlt nicht
                flush()
            }
            if (ww > avail) {
                // Ueberlange Woerter (lange URLs) zeichenweise umbrechen
                var rest = w.text
                while (rest.isNotEmpty()) {
                    var n = rest.length
                    while (n > 1 && widthOf(Word(rest.take(n), w.style)) > (width - (if (first) 0f else hanging)) - lineW) n--
                    line.add(Word(rest.take(n), w.style)); lineW += widthOf(Word(rest.take(n), w.style))
                    rest = rest.drop(n)
                    if (rest.isNotEmpty()) flush()
                }
                continue
            }
            line.add(w); lineW += ww
        }
        flush()
        y += after
    }

    private fun drawLine(ws: List<Word>, baseline: Float, indent: Float = 0f) {
        val s = cs ?: return
        var x = left + indent
        for (w in ws) {
            val f = fonts.getValue(w.style.face)
            val text = safe(f, w.text)
            val ww = f.getStringWidth(text) / 1000f * w.style.size
            val c = w.style.color
            s.setNonStrokingColor(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)
            s.beginText(); s.setFont(f, w.style.size); s.newLineAtOffset(x, baseline); s.showText(text); s.endText()
            val rect = PDRectangle(x, baseline - 3f, ww, w.style.size + 3f)
            w.style.uri?.let { addLink(page!!, rect, uri = it) }
            w.style.anchor?.let { pending.add(Pending(page!!, rect, it)) }
            x += ww
        }
    }

    private fun dest(p: PDPage, top: Float) = PDPageXYZDestination().apply { page = p; this.top = top.toInt(); left = 0 }

    private fun addLink(p: PDPage, rect: PDRectangle, uri: String? = null, goTo: Pair<PDPage, Float>? = null) {
        val link = PDAnnotationLink().apply {
            this.rectangle = rect
            borderStyle = PDBorderStyleDictionary().apply { width = 0f }
            action = if (uri != null) PDActionURI().apply { this.uri = uri }
            else PDActionGoTo().apply { destination = dest(goTo!!.first, goTo.second) }
        }
        p.annotations.add(link)
    }
}
