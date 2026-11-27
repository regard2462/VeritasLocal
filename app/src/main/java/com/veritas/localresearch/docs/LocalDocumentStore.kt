package com.veritas.localresearch.docs

import android.content.Context
import com.veritas.localresearch.i18n.tr
import com.veritas.localresearch.domain.ScrapedDocument
import com.veritas.localresearch.domain.SourceMeta
import com.veritas.localresearch.domain.SourceType
import com.veritas.localresearch.domain.TextChunk
import com.veritas.localresearch.rag.TextChunker
import com.veritas.localresearch.scraping.PdfTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Lesezeichen eines PDFs: Seite (1-basiert), Ebene (0 = oberste), Titel. */
@Serializable
data class OutlineEntry(val page: Int, val level: Int, val title: String)

/** Ein vom Nutzer geladenes Dokument. Der Text liegt daneben in <id>.txt. */
@Serializable
data class LocalDoc(
    val id: String,
    val fileName: String,
    val title: String,
    val kind: String,
    val chars: Int,
    val pages: Int = 0,
    val pageStarts: List<Int> = emptyList(),
    val authors: List<String> = emptyList(),
    val date: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    val selected: Boolean = true,
    /** Zitatgepruefte Kurzfassung (leer, solange keine erstellt wurde). */
    val summary: String = "",
    val questions: List<String> = emptyList(),
    /** Text ganz oder teilweise per Texterkennung gelesen. */
    val ocr: Boolean = false,
    /** Lesezeichen (Inhaltsverzeichnis) des PDFs, leer wenn keines da ist. */
    val outline: List<OutlineEntry> = emptyList()
)

/**
 * Eigene Dokumente im App-Ordner (documents/): Liste, Text und gespeicherte Vektoren je Dokument. Vektoren gelten
 * nur, solange Embedding-Modell und Abschnitte gleich bleiben (Kennung und Pruefsumme im Kopf der Datei).
 */
class LocalDocumentStore(private val context: Context) {

    private val dir = File(context.filesDir, "documents").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val pdf by lazy { PdfTextExtractor(context) }

    private val _docs = MutableStateFlow(load())
    val docs: StateFlow<List<LocalDoc>> = _docs.asStateFlow()

    private fun load(): List<LocalDoc> = runCatching {
        json.decodeFromString<List<LocalDoc>>(indexFile.readText())
    }.getOrDefault(emptyList()).filter { File(dir, "${it.id}.txt").isFile }

    @Synchronized
    private fun save(list: List<LocalDoc>) {
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(json.encodeToString(list))
        if (!tmp.renameTo(indexFile)) { indexFile.delete(); tmp.renameTo(indexFile) }
        _docs.value = list
    }

    fun text(id: String): String = runCatching { File(dir, "$id.txt").readText() }.getOrDefault("")

    /** Liest die Datei, legt Text und Eintrag an. Fehlermeldung als Text, wenn nichts lesbar ist. */
    suspend fun import(fileName: String, bytes: ByteArray, onStatus: (String) -> Unit = {}): Result<LocalDoc> = withContext(Dispatchers.Default) {
        runCatching {
            require(bytes.size <= MAX_BYTES) { tr("file_too_big_mb", MAX_BYTES / 1024 / 1024) }
            val kind = DocumentParser.kindOf(fileName, bytes) ?: error(tr("fmt_unsupported"))
            var usedOcr = false
            var outline = emptyList<OutlineEntry>()
            val parsed = if (kind == DocumentParser.Kind.IMAGE) {
                val engine = Ocr.engine ?: error(tr("ocr_unavailable"))
                val parent = Ocr.ensureData(context, onStatus).getOrThrow()
                onStatus(tr("ocr_running"))
                val text = engine.image(bytes, parent, Ocr.LANGS) ?: error(tr("img_unreadable"))
                usedOcr = true
                DocumentParser.Parsed(PdfTextExtractor.cleanOcr(text))
            } else if (kind == DocumentParser.Kind.PDF) {
                val p = pdf.extractPages(bytes)
                val pageTexts = p?.pages?.toMutableList() ?: mutableListOf()
                // Seiten ohne Textebene (Scans) per Texterkennung lesen
                val scanned = pageTexts.indices.filter { i -> pageTexts[i].count(Char::isLetter) < 25 }
                val engine = Ocr.engine
                if (engine != null && (p == null || scanned.isNotEmpty())) {
                    val parent = Ocr.ensureData(context, onStatus).getOrElse { e ->
                        if (pageTexts.sumOf { it.count(Char::isLetter) } < 40) throw e else null
                    }
                    if (parent != null) {
                        val res = engine.pdfPages(bytes, parent, Ocr.LANGS, if (p == null) null else scanned, Ocr.MAX_PAGES) { k, n ->
                            onStatus(tr("ocr_page", k, n))
                        } ?: error(tr("ocr_start_failed"))
                        if (p == null) {
                            val last = res.keys.maxOrNull() ?: -1
                            for (i in 0..last) pageTexts.add("")
                        }
                        res.forEach { (i, t) ->
                            val cleaned = PdfTextExtractor.cleanOcr(t)
                            if (cleaned.count(Char::isLetter) >= 25) { pageTexts[i] = cleaned; usedOcr = true }
                        }
                    }
                }
                if (p == null && !usedOcr) error(tr("pdf_unreadable"))
                val starts = ArrayList<Int>()
                val sb = StringBuilder()
                for (page in pageTexts) {
                    if (sb.isNotEmpty() && page.isNotBlank()) sb.append("\n\n")
                    starts.add(sb.length)
                    sb.append(page)
                }
                outline = p?.outline.orEmpty().map { OutlineEntry(it.page + 1, it.level, it.title) }
                DocumentParser.Parsed(sb.toString(), p?.title.orEmpty(), p?.authors.orEmpty(), p?.date.orEmpty(), starts)
            } else DocumentParser.parse(kind, bytes) ?: error(tr("no_text"))
            val text = parsed.text
            if (text.count(Char::isLetter) < 40) {
                error(if (kind == DocumentParser.Kind.PDF || kind == DocumentParser.Kind.IMAGE) tr("no_text_ocr")
                    else tr("no_text"))
            }
            require(text.length <= MAX_CHARS) { tr("text_too_long", MAX_CHARS / 1_000_000) }
            val id = UUID.randomUUID().toString().take(12)
            File(dir, "$id.txt").writeText(text)
            val title = parsed.title.takeIf { it.length in 3..200 && !it.equals("untitled", true) }
                ?: fileName.substringBeforeLast('.').replace('_', ' ').trim()
            val doc = LocalDoc(
                id = id, fileName = fileName, title = title, kind = kind.label, chars = text.length,
                pages = parsed.pageStarts.size, pageStarts = parsed.pageStarts,
                authors = parsed.authors, date = parsed.date, ocr = usedOcr, outline = outline
            )
            save(_docs.value + doc)
            doc
        }
    }

    fun remove(id: String) {
        File(dir, "$id.txt").delete()
        File(dir, "$id.vec").delete()
        save(_docs.value.filterNot { it.id == id })
    }

    fun setGuide(id: String, summary: String, questions: List<String>) =
        save(_docs.value.map { if (it.id == id) it.copy(summary = summary, questions = questions) else it })

    fun setSelected(id: String, selected: Boolean) =
        save(_docs.value.map { if (it.id == id) it.copy(selected = selected) else it })

    fun toScraped(doc: LocalDoc, text: String = text(doc.id)): ScrapedDocument = ScrapedDocument(
        url = "local:${doc.id}",
        domain = doc.fileName,
        title = doc.title,
        cleanText = text,
        sourceType = SourceType.LOCAL,
        meta = SourceMeta(authors = doc.authors, date = doc.date, siteName = doc.fileName, isPdf = doc.kind == "PDF")
    )

    /**
     * Suchabschnitte und Absaetze: gesucht wird in kleinen Abschnitten (genauer Treffer), das Modell bekommt den
     * ganzen Absatz drumherum (genug Zusammenhang fuer Aussage und Zitat). Beide mit Seitenangaben.
     */
    class ChunkTree(val children: List<TextChunk>, val parentOf: Map<Int, TextChunk>)

    fun chunkTree(doc: LocalDoc, chunker: TextChunker): ChunkTree {
        val text = text(doc.id)
        val scraped = toScraped(doc, text)
        // Kapitel aus den Lesezeichen des PDFs, sonst aus den Ueberschriften im Text
        val heads = if (doc.outline.isNotEmpty() && doc.pageStarts.isNotEmpty()) emptyList() else headings(text)
        val parents = annotate(chunker.chunkDocument(scraped, PARENT_SIZE, 0), text, doc.pageStarts, heads, doc.outline)
        val parentOf = HashMap<Int, TextChunk>()
        val children = parents.flatMap { p ->
            val kids = chunker.chunkDocument(scraped.copy(cleanText = p.rawText), CHILD_SIZE, CHILD_OVERLAP)
            kids.forEach { parentOf[it.chunkId] = p }
            kids
        }
        return ChunkTree(annotate(children, text, doc.pageStarts, heads, doc.outline), parentOf)
    }

    /** Gespeicherte Vektoren, wenn Modellkennung und Abschnitte passen, sonst null. */
    fun loadVectors(id: String, tag: String, chunks: List<TextChunk>): List<FloatArray>? = runCatching {
        val f = File(dir, "$id.vec")
        if (!f.isFile || tag.isEmpty()) return null
        DataInputStream(f.inputStream().buffered()).use { inp ->
            if (inp.readUTF() != tag || inp.readUTF() != digest(chunks)) return null
            val n = inp.readInt()
            val dim = inp.readInt()
            if (n != chunks.size) return null
            List(n) { FloatArray(dim) { inp.readFloat() } }
        }
    }.getOrNull()

    fun saveVectors(id: String, tag: String, chunks: List<TextChunk>, vectors: List<FloatArray?>) {
        if (tag.isEmpty() || vectors.size != chunks.size || vectors.any { it == null }) return
        runCatching {
            val f = File(dir, "$id.vec")
            DataOutputStream(f.outputStream().buffered()).use { out ->
                out.writeUTF(tag)
                out.writeUTF(digest(chunks))
                out.writeInt(vectors.size)
                out.writeInt(vectors[0]!!.size)
                vectors.forEach { v -> v!!.forEach(out::writeFloat) }
            }
        }
    }

    private fun digest(chunks: List<TextChunk>): String {
        val md = MessageDigest.getInstance("SHA-256")
        chunks.forEach { md.update(it.section.toByteArray()); md.update(1); md.update(it.rawText.toByteArray()); md.update(0) }
        return md.digest().joinToString("") { "%02x".format(it) }.take(32)
    }

    companion object {
        const val MAX_BYTES = 300L * 1024 * 1024
        const val MAX_CHARS = 20_000_000
        const val PARENT_SIZE = 900
        const val CHILD_SIZE = 280
        const val CHILD_OVERLAP = 60

        /** Seite von Anfang und Ende jedes Abschnitts anhand der Seitenanfaenge im Gesamttext. */
        fun assignPages(chunks: List<TextChunk>, text: String, pageStarts: List<Int>): List<TextChunk> =
            annotate(chunks, text, pageStarts, emptyList())

        /** Seiten (bei PDFs) und Kapitel samt Ueberschrift fuer jeden Abschnitt, nach seiner Stelle im Gesamttext. */
        fun annotate(
            chunks: List<TextChunk>,
            text: String,
            pageStarts: List<Int>,
            heads: List<Heading>,
            outline: List<OutlineEntry> = emptyList()
        ): List<TextChunk> {
            if (pageStarts.isEmpty() && heads.isEmpty()) return chunks
            val useOutline = outline.isNotEmpty() && pageStarts.isNotEmpty()
            var from = 0
            return chunks.map { c ->
                val pos = text.indexOf(c.rawText, from).takeIf { it >= 0 } ?: text.indexOf(c.rawText)
                if (pos < 0) return@map c
                from = pos + 1
                val withPages = if (pageStarts.isEmpty()) c
                    else c.copy(page = pageAt(pageStarts, pos), pageEnd = pageAt(pageStarts, pos + c.rawText.length - 1))
                withPages.copy(section = if (useOutline) outlineAt(outline, withPages.page) else sectionAt(heads, pos))
            }
        }

        /** Ueberschrift im Gesamttext: Stelle, Text und ob sie ein Kapitel eroeffnet ("3 Marketing", "Kapitel 3"). */
        class Heading(val pos: Int, val text: String, val chapter: Boolean)

        /**
         * Ueberschriften: eigene kurze Absaetze ohne Satzzeichen am Ende, die gross oder mit einer Nummer beginnen.
         * Kapitel sind Ueberschriften mit einstelliger Gliederungsnummer oder "Kapitel", "Teil", "Chapter", "Part".
         */
        fun headings(text: String): List<Heading> {
            val out = ArrayList<Heading>()
            var start = 0
            while (start < text.length) {
                var end = text.indexOf("\n\n", start)
                if (end < 0) end = text.length
                val t = text.substring(start, end).trim()
                if (t.length in 3..90 && '\n' !in t && t.last() !in ".,;:!?" && HEAD_START.containsMatchIn(t) &&
                    t.count(Char::isLetter) * 2 > t.length && t.count { it == ' ' } <= 12 && !NO_HEADING.containsMatchIn(t)
                ) out.add(Heading(start, t, CHAPTER.containsMatchIn(t)))
                start = end + 2
            }
            // "3 Marketing" ist nur ein Kapitel, wenn das Dokument auch "3.1 …" hat (sonst Aufzaehlung, Achsenbeschriftung oder "7. Auflage")
            val numbered = out.mapNotNull { SUB_NUMBER.find(it.text)?.groupValues?.get(1) }.toSet()
            return out.map { h ->
                val num = NUMBERED_CHAPTER.find(h.text)?.groupValues?.get(1)
                if (h.chapter && num != null && num !in numbered) Heading(h.pos, h.text, false) else h
            }
        }

        /** Kapitel und tiefstes Lesezeichen fuer Seite [page] (1-basiert): "3 Marketing › 3.2 Preise". */
        fun outlineAt(outline: List<OutlineEntry>, page: Int): String {
            val at = outline.indexOfLast { it.page <= page }
            if (at < 0) return ""
            val path = ArrayList<String>()
            var level = outline[at].level
            path.add(outline[at].title)
            for (k in at - 1 downTo 0) {
                if (outline[k].level < level) { path.add(0, outline[k].title); level = outline[k].level }
                if (level == 0) break
            }
            return if (path.size <= 2) path.joinToString(" › ") else "${path[path.size - 2]} › ${path.last()}"
        }

        /** "Kapitel › Ueberschrift" an Stelle [pos]; nur die Ueberschrift, wenn sie selbst das Kapitel ist. */
        fun sectionAt(heads: List<Heading>, pos: Int): String {
            var lo = 0
            var hi = heads.size - 1
            var at = -1
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                if (heads[mid].pos <= pos) { at = mid; lo = mid + 1 } else hi = mid - 1
            }
            if (at < 0) return ""
            val head = heads[at]
            if (head.chapter) return head.text
            val chapter = (at - 1 downTo 0).firstOrNull { heads[it].chapter }?.let { heads[it].text }
            return if (chapter == null) head.text else "$chapter › ${head.text}"
        }

        private val HEAD_START = Regex("^(?:\\d+(?:\\.\\d+)*\\.?\\s+|[IVX]+\\.\\s+|[A-Z]\\.\\s+)?\\p{Lu}")
        /** Bildunterschriften, Tabellenkoepfe, Quellen und Impressumszeilen sind keine Ueberschriften. */
        private val NO_HEADING = Regex("(?i)^(?:abb\\.|abbildung|tab\\.|tabelle|fig\\.|figure|table|quelle|source|info-box|anm\\.)|" +
            "\\b(?:aufl\\.|auflage|edition|verlag|springer|isbn|doi)\\b|\\(\\d{4}\\)")
        private val SUB_NUMBER = Regex("^(\\d{1,2})\\.\\d")
        private val NUMBERED_CHAPTER = Regex("^(\\d{1,2})\\.?\\s")
        private val CHAPTER = Regex("^(?:\\d{1,2}\\.?\\s+\\p{Lu}|(?:Kapitel|Teil|Chapter|Part|Lektion|Unit)\\s+\\w+)")

        fun pageAt(pageStarts: List<Int>, pos: Int): Int {
            var lo = 0
            var hi = pageStarts.size - 1
            var ans = 0
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                if (pageStarts[mid] <= pos) { ans = mid; lo = mid + 1 } else hi = mid - 1
            }
            return ans + 1
        }
    }
}
