package com.veritas.localresearch.docs

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

/**
 * Text aus eigenen Dokumenten (ausser PDF, das liest [com.veritas.localresearch.scraping.PdfTextExtractor]).
 * Absaetze werden durch Leerzeilen getrennt, Ueberschriften stehen als eigene Zeile, damit Abschnitte an
 * Satz- und Absatzgrenzen geschnitten werden.
 */
object DocumentParser {

    data class Parsed(
        val text: String,
        val title: String = "",
        val authors: List<String> = emptyList(),
        val date: String = "",
        /** Zeichenposition, an der jede Seite beginnt (nur PDF). */
        val pageStarts: List<Int> = emptyList()
    )

    enum class Kind(val label: String) { PDF("PDF"), DOCX("Word"), ODT("ODT"), EPUB("EPUB"), HTML("HTML"), TEXT("Text"), IMAGE("Bild") }

    /** MIME-Typen fuer den Dateidialog. */
    val MIME_TYPES = arrayOf(
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.oasis.opendocument.text",
        "application/epub+zip",
        "text/html", "text/plain", "text/markdown", "text/x-markdown", "text/csv",
        "image/jpeg", "image/png", "image/webp", "image/bmp"
    )

    fun kindOf(name: String, bytes: ByteArray): Kind? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext == "pdf" || bytes.startsWith("%PDF") -> Kind.PDF
            ext == "docx" -> Kind.DOCX
            ext == "odt" -> Kind.ODT
            ext == "epub" -> Kind.EPUB
            ext in setOf("html", "htm", "xhtml") -> Kind.HTML
            ext in setOf("txt", "md", "markdown", "csv", "text", "rst", "org", "tex") -> Kind.TEXT
            ext in setOf("jpg", "jpeg", "png", "webp", "bmp") || isImage(bytes) -> Kind.IMAGE
            bytes.startsWith("PK") -> zipKind(bytes)
            looksLikeText(bytes) -> if (decode(bytes).trimStart().startsWith("<")) Kind.HTML else Kind.TEXT
            else -> null
        }
    }

    /** Alles ausser PDF. Liefert null, wenn kein Text gefunden wurde. */
    fun parse(kind: Kind, bytes: ByteArray): Parsed? = runCatching {
        when (kind) {
            Kind.DOCX -> docx(bytes)
            Kind.ODT -> odt(bytes)
            Kind.EPUB -> epub(bytes)
            Kind.HTML -> html(decode(bytes))
            Kind.TEXT -> Parsed(plain(decode(bytes)))
            Kind.PDF, Kind.IMAGE -> null
        }
    }.getOrNull()?.takeIf { it.text.isNotBlank() }

    // --- Formate ---

    private fun docx(bytes: ByteArray): Parsed {
        val files = unzip(bytes) { it == "word/document.xml" || it == "docProps/core.xml" }
        val xml = files["word/document.xml"]?.let { String(it, Charsets.UTF_8) } ?: return Parsed("")
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val paras = doc.select("w|body w|p").map { p ->
            buildString {
                p.select("w|t, w|tab, w|br").forEach { e ->
                    when (e.tagName()) {
                        "w:t" -> append(e.wholeText())
                        "w:tab" -> append(' ')
                        else -> append('\n')
                    }
                }
            }.trim()
        }
        val core = files["docProps/core.xml"]?.let { Jsoup.parse(String(it, Charsets.UTF_8), "", Parser.xmlParser()) }
        return Parsed(
            text = joinParagraphs(paras),
            title = core?.selectFirst("dc|title")?.text().orEmpty(),
            authors = core?.selectFirst("dc|creator")?.text()?.let(::splitAuthors).orEmpty(),
            date = core?.selectFirst("dcterms|created")?.text()?.take(4)?.takeIf { it.all(Char::isDigit) }.orEmpty()
        )
    }

    private fun odt(bytes: ByteArray): Parsed {
        val files = unzip(bytes) { it == "content.xml" || it == "meta.xml" }
        val xml = files["content.xml"]?.let { String(it, Charsets.UTF_8) } ?: return Parsed("")
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        // Tabulatoren und Leerzeichen-Elemente als Leerzeichen, Zeilenumbrueche als Umbruch
        doc.select("text|tab, text|s").forEach { it.replaceWith(org.jsoup.nodes.TextNode(" ")) }
        doc.select("text|line-break").forEach { it.replaceWith(org.jsoup.nodes.TextNode("\n")) }
        val paras = doc.select("office|text text|h, office|text text|p").filter { it.parents().none { p -> p.tagName() == "text:p" } }
            .map { it.wholeText().trim() }
        val meta = files["meta.xml"]?.let { Jsoup.parse(String(it, Charsets.UTF_8), "", Parser.xmlParser()) }
        return Parsed(
            text = joinParagraphs(paras),
            title = meta?.selectFirst("dc|title")?.text().orEmpty(),
            authors = (meta?.selectFirst("meta|initial-creator") ?: meta?.selectFirst("dc|creator"))?.text()?.let(::splitAuthors).orEmpty(),
            date = meta?.selectFirst("meta|creation-date")?.text()?.take(4)?.takeIf { it.all(Char::isDigit) }.orEmpty()
        )
    }

    private fun epub(bytes: ByteArray): Parsed {
        val files = unzip(bytes) { true }
        val container = files["META-INF/container.xml"]?.let { Jsoup.parse(String(it, Charsets.UTF_8), "", Parser.xmlParser()) }
        val opfPath = container?.selectFirst("rootfile")?.attr("full-path").orEmpty()
        val opf = files[opfPath]?.let { Jsoup.parse(String(it, Charsets.UTF_8), "", Parser.xmlParser()) }
        val base = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        // Reihenfolge des Buches laut spine; ohne OPF alle HTML-Dateien nach Namen
        val order = if (opf != null) {
            val hrefs = opf.select("manifest > item").associate { it.attr("id") to it.attr("href") }
            opf.select("spine > itemref").mapNotNull { hrefs[it.attr("idref")] }.map { normalize(base + decodeUrl(it)) }
        } else files.keys.filter { it.endsWith(".xhtml") || it.endsWith(".html") || it.endsWith(".htm") }.sorted()
        val paras = order.flatMap { path -> files[path]?.let { htmlParagraphs(Jsoup.parse(decode(it))) }.orEmpty() }
        return Parsed(
            text = joinParagraphs(paras),
            title = opf?.selectFirst("dc|title")?.text().orEmpty(),
            authors = opf?.select("dc|creator")?.map { it.text().trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            date = opf?.selectFirst("dc|date")?.text()?.take(4)?.takeIf { it.all(Char::isDigit) }.orEmpty()
        )
    }

    private fun html(source: String): Parsed {
        val doc = Jsoup.parse(source)
        val author = doc.selectFirst("meta[name=author]")?.attr("content").orEmpty()
        return Parsed(
            text = joinParagraphs(htmlParagraphs(doc)),
            title = doc.title().trim(),
            authors = splitAuthors(author)
        )
    }

    private fun htmlParagraphs(doc: Document): List<String> {
        doc.select("script, style, nav, noscript, svg").remove()
        val blocks = doc.select("h1, h2, h3, h4, h5, h6, p, li, blockquote, pre, td, dd, dt, figcaption")
            .filter { el -> el.parents().none { it.tagName() in BLOCK_PARENTS } }
            .map { it.wholeText().replace(WS, " ").trim() }
            .filter { it.isNotEmpty() }
        return blocks.ifEmpty { doc.body()?.wholeText()?.split(Regex("\\n\\s*\\n"))?.map { it.replace(WS, " ").trim() }.orEmpty() }
    }

    /** Markdown-Zeichen am Zeilenanfang und Hervorhebungen weg, Zeilen eines Absatzes bleiben zusammen. */
    fun plain(text: String): String {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines().map { line ->
            line.replace(MD_HEADING, "").replace(MD_BULLET, "").replace(MD_EMPH, "$2").trimEnd()
        }
        return lines.joinToString("\n").replace(MANY_BREAKS, "\n\n").trim()
    }

    // --- Hilfen ---

    private fun joinParagraphs(paras: List<String>): String =
        paras.map { it.replace(HSPACE, " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")

    private fun splitAuthors(s: String): List<String> =
        s.split(';', '/', '|', '&').map { it.trim() }.filter { it.length in 3..80 }

    private fun unzip(bytes: ByteArray, want: (String) -> Boolean): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (!e.isDirectory && want(e.name)) {
                    val data = zin.readBytes()
                    total += data.size
                    // Schutz vor Zip-Bomben: hoechstens 300 MB entpackt
                    if (total > 300L * 1024 * 1024) break
                    out[e.name] = data
                }
            }
        }
        return out
    }

    private fun zipKind(bytes: ByteArray): Kind? {
        val names = HashSet<String>()
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { z -> while (true) { names.add((z.nextEntry ?: break).name) } }
        }
        return when {
            "word/document.xml" in names -> Kind.DOCX
            "META-INF/container.xml" in names -> Kind.EPUB
            "content.xml" in names -> Kind.ODT
            else -> null
        }
    }

    private fun normalize(path: String): String {
        val parts = ArrayList<String>()
        for (p in path.split('/')) when (p) { "", "." -> {}; ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1); else -> parts.add(p) }
        return parts.joinToString("/")
    }

    private fun decodeUrl(s: String): String = runCatching { java.net.URLDecoder.decode(s.substringBefore('#'), "UTF-8") }.getOrDefault(s)

    private fun ByteArray.startsWith(prefix: String): Boolean =
        size >= prefix.length && prefix.indices.all { this[it] == prefix[it].code.toByte() }

    private fun isImage(b: ByteArray): Boolean =
        (b.size > 4 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()) ||
            (b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()) ||
            (b.size > 12 && b.startsWith("RIFF") && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP")

    private fun looksLikeText(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, 4096)
        if (n == 0) return false
        val zeros = (0 until n).count { bytes[it] == 0.toByte() }
        return zeros == 0
    }

    /** UTF-8 (mit oder ohne BOM), sonst Windows-1252. */
    fun decode(bytes: ByteArray): String {
        val b = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            bytes.copyOfRange(3, bytes.size) else bytes
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString()
        } catch (_: CharacterCodingException) {
            String(b, Charset.forName("windows-1252"))
        }
    }

    private val BLOCK_PARENTS = setOf("p", "li", "blockquote", "pre", "td", "dd", "dt", "figcaption", "h1", "h2", "h3", "h4", "h5", "h6")
    private val WS = Regex("\\s+")
    private val HSPACE = Regex("[ \\t\\u00A0]+")
    private val MANY_BREAKS = Regex("\\n{3,}")
    private val MD_HEADING = Regex("^\\s{0,3}#{1,6}\\s+")
    private val MD_BULLET = Regex("^\\s*(?:[-*+]|\\d+[.)])\\s+")
    private val MD_EMPH = Regex("(\\*\\*|__|\\*|_|`)(\\S(?:.*?\\S)?)\\1")
}
