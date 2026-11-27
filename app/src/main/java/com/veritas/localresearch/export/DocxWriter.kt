package com.veritas.localresearch.export

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Schreibt eine DOCX-Datei (Office Open XML, ECMA-376) ohne Fremdbibliothek: Titel, Inhaltsverzeichnis als
 * TOC-Feld mit vorgefuellten, verlinkten Eintraegen (Word fragt beim Oeffnen, ob es die Seitenzahlen aktualisieren
 * soll), Ueberschriften mit Textmarken, Verweise als Links ins Literaturverzeichnis und Webadressen als Hyperlinks.
 */
object DocxWriter {

    fun write(doc: ExportDocument): ByteArray {
        val rels = ArrayList<Pair<String, String>>() // rId -> URL
        fun urlRel(url: String): String = rels.firstOrNull { it.second == url }?.first
            ?: "rIdL${rels.size + 1}".also { rels.add(it to url) }

        var bookmarkId = 0
        val body = StringBuilder()

        fun run(text: String, italic: Boolean = false, style: String? = null): String {
            val rpr = buildString {
                if (style != null) append("<w:rStyle w:val=\"$style\"/>")
                if (italic) append("<w:i/>")
            }
            return "<w:r>${if (rpr.isNotEmpty()) "<w:rPr>$rpr</w:rPr>" else ""}<w:t xml:space=\"preserve\">${esc(text)}</w:t></w:r>"
        }

        fun bookmark(name: String, inner: String): String {
            val id = ++bookmarkId
            return "<w:bookmarkStart w:id=\"$id\" w:name=\"$name\"/>$inner<w:bookmarkEnd w:id=\"$id\"/>"
        }

        // Titelblock
        body.append("<w:p><w:pPr><w:pStyle w:val=\"Title\"/></w:pPr>${run(doc.title)}</w:p>")
        body.append("<w:p><w:pPr><w:pStyle w:val=\"Subtitle\"/></w:pPr>${run(doc.subtitle)}</w:p>")

        // Inhaltsverzeichnis: Feld TOC mit Links (\h), vorgefuellt mit den Eintraegen
        body.append("<w:p><w:pPr><w:pStyle w:val=\"TOCHeading\"/></w:pPr>${run(doc.tocTitle)}</w:p>")
        val entries = doc.tocEntries
        entries.forEachIndexed { i, (text, anchor) ->
            body.append("<w:p><w:pPr><w:pStyle w:val=\"TOC1\"/><w:tabs><w:tab w:val=\"right\" w:leader=\"dot\" w:pos=\"9060\"/></w:tabs></w:pPr>")
            if (i == 0) {
                body.append("<w:r><w:fldChar w:fldCharType=\"begin\" w:dirty=\"true\"/></w:r>")
                body.append("<w:r><w:instrText xml:space=\"preserve\"> TOC \\o \"1-1\" \\h \\z \\u </w:instrText></w:r>")
                body.append("<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>")
            }
            body.append("<w:hyperlink w:anchor=\"$anchor\" w:history=\"1\">${run(text)}</w:hyperlink>")
            if (i == entries.lastIndex) body.append("<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>")
            body.append("</w:p>")
        }

        // Abschnitte
        for (b in doc.blocks) when (b) {
            is Block.Heading -> body.append("<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr>${bookmark(b.anchor, run(b.text))}</w:p>")
            is Block.Paragraph -> {
                body.append("<w:p><w:pPr><w:pStyle w:val=\"BodyText\"/></w:pPr>")
                for (r in b.runs) when (r) {
                    is Run.Text -> body.append(run(r.text))
                    // Verweis springt zum (ersten) Eintrag im Literaturverzeichnis
                    is Run.Cite -> body.append("<w:hyperlink w:anchor=\"${r.anchors.first()}\" w:history=\"1\">${run(r.label, style = "CitationLink")}</w:hyperlink>")
                }
                body.append("</w:p>")
            }
        }

        // Literaturverzeichnis mit Textmarken je Quelle und anklickbaren Adressen
        body.append("<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr>${bookmark(doc.bibAnchor, run(doc.bibTitle))}</w:p>")
        for ((entry, segs) in doc.bibliography) {
            val inner = segs.joinToString("") { s ->
                if (s.link != null) "<w:hyperlink r:id=\"${urlRel(s.link)}\" w:history=\"1\">${run(s.text, style = "Hyperlink")}</w:hyperlink>"
                else run(s.text, italic = s.italic)
            }
            body.append("<w:p><w:pPr><w:pStyle w:val=\"Bibliography\"/></w:pPr>${bookmark(entry.anchor, inner)}</w:p>")
        }

        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1417" w:right="1417" w:bottom="1134" w:left="1417" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>"""

        val docRels = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
            append("""<Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
            append("""<Relationship Id="rIdSettings" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/>""")
            for ((id, url) in rels) {
                append("""<Relationship Id="$id" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="${esc(url)}" TargetMode="External"/>""")
            }
            append("</Relationships>")
        }

        val now = Instant.now().toString().substringBefore('.') + "Z"
        val files = linkedMapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>""",
            "docProps/core.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><dc:title>${esc(doc.title)}</dc:title><dc:creator>VeritasLocal</dc:creator><dcterms:created xsi:type="dcterms:W3CDTF">$now</dcterms:created></cp:coreProperties>""",
            "word/_rels/document.xml.rels" to docRels,
            "word/document.xml" to document,
            "word/settings.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:updateFields w:val="true"/><w:defaultTabStop w:val="708"/></w:settings>""",
            "word/styles.xml" to STYLES
        )
        return zip(files)
    }

    private fun zip(files: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, content) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    internal fun esc(s: String): String = buildString(s.length) {
        for (c in s) when {
            c == '&' -> append("&amp;")
            c == '<' -> append("&lt;")
            c == '>' -> append("&gt;")
            c == '"' -> append("&quot;")
            // In XML 1.0 verbotene Steuerzeichen weglassen
            c < ' ' && c != '\t' && c != '\n' && c != '\r' -> {}
            else -> append(c)
        }
    }

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Calibri" w:cs="Calibri"/><w:sz w:val="23"/><w:szCs w:val="23"/><w:lang w:val="de-DE"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="300" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>
<w:style w:type="paragraph" w:styleId="BodyText"><w:name w:val="Body Text"/><w:basedOn w:val="Normal"/><w:pPr><w:jc w:val="both"/></w:pPr><w:qFormat/></w:style>
<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:next w:val="Subtitle"/><w:pPr><w:spacing w:after="120"/></w:pPr><w:rPr><w:b/><w:sz w:val="40"/><w:szCs w:val="40"/></w:rPr><w:qFormat/></w:style>
<w:style w:type="paragraph" w:styleId="Subtitle"><w:name w:val="Subtitle"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="360"/></w:pPr><w:rPr><w:i/><w:color w:val="595959"/><w:sz w:val="20"/></w:rPr><w:qFormat/></w:style>
<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="BodyText"/><w:pPr><w:keepNext/><w:spacing w:before="360" w:after="120"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="30"/><w:szCs w:val="30"/></w:rPr><w:qFormat/></w:style>
<w:style w:type="paragraph" w:styleId="TOCHeading"><w:name w:val="TOC Heading"/><w:basedOn w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="240" w:after="120"/></w:pPr><w:rPr><w:b/><w:sz w:val="30"/></w:rPr><w:qFormat/></w:style>
<w:style w:type="paragraph" w:styleId="TOC1"><w:name w:val="toc 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:pPr><w:spacing w:after="60"/></w:pPr></w:style>
<w:style w:type="paragraph" w:styleId="Bibliography"><w:name w:val="Bibliography"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="120"/><w:ind w:left="567" w:hanging="567"/></w:pPr></w:style>
<w:style w:type="character" w:styleId="Hyperlink"><w:name w:val="Hyperlink"/><w:rPr><w:color w:val="0563C1"/><w:u w:val="single"/></w:rPr></w:style>
<w:style w:type="character" w:styleId="CitationLink"><w:name w:val="Citation Link"/><w:rPr><w:color w:val="0563C1"/></w:rPr></w:style>
</w:styles>"""
}
