package com.veritas.localresearch.export

import com.veritas.localresearch.export.DocxWriter.esc
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Schreibt eine ODT-Datei (OpenDocument 1.3): echtes Inhaltsverzeichnis (text:table-of-content, in LibreOffice
 * ueber "Verzeichnis aktualisieren" mit Seitenzahlen), Ueberschriften mit Gliederungsebene, Verweise als Links auf
 * Textmarken im Literaturverzeichnis und Webadressen als Hyperlinks.
 */
object OdtWriter {

    private const val NS = "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" " +
        "xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\" " +
        "xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" " +
        "xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\" " +
        "xmlns:xlink=\"http://www.w3.org/1999/xlink\" " +
        "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
        "xmlns:meta=\"urn:oasis:names:tc:opendocument:xmlns:meta:1.0\" office:version=\"1.3\""

    fun write(doc: ExportDocument): ByteArray {
        val body = StringBuilder()
        fun span(text: String, italic: Boolean = false) =
            if (italic) "<text:span text:style-name=\"Kursiv\">${esc(text)}</text:span>" else esc(text)
        fun link(href: String, inner: String, style: String = "Internet_20_link") =
            "<text:a xlink:type=\"simple\" xlink:href=\"${esc(href)}\" text:style-name=\"$style\" text:visited-style-name=\"$style\">$inner</text:a>"

        body.append("<text:p text:style-name=\"Title\">${esc(doc.title)}</text:p>")
        body.append("<text:p text:style-name=\"Subtitle\">${esc(doc.subtitle)}</text:p>")

        // Inhaltsverzeichnis mit Vorlage (Link, Text, Tabulator, Seitenzahl) und vorgefuelltem Inhalt
        body.append("<text:table-of-content text:style-name=\"Sect1\" text:protected=\"true\" text:name=\"Inhaltsverzeichnis1\">")
        body.append("<text:table-of-content-source text:outline-level=\"1\" text:use-index-marks=\"false\">")
        body.append("<text:index-title-template text:style-name=\"Contents_20_Heading\">${esc(doc.tocTitle)}</text:index-title-template>")
        body.append("<text:table-of-content-entry-template text:outline-level=\"1\" text:style-name=\"Contents_20_1\">")
        body.append("<text:index-entry-link-start text:style-name=\"Index_20_Link\"/><text:index-entry-chapter/><text:index-entry-text/>")
        body.append("<text:index-entry-tab-stop style:type=\"right\" style:leader-char=\".\"/><text:index-entry-page-number/><text:index-entry-link-end/>")
        body.append("</text:table-of-content-entry-template></text:table-of-content-source><text:index-body>")
        body.append("<text:index-title text:style-name=\"Sect1\" text:name=\"Inhaltsverzeichnis1_Head\"><text:p text:style-name=\"Contents_20_Heading\">${esc(doc.tocTitle)}</text:p></text:index-title>")
        for ((text, anchor) in doc.tocEntries) {
            body.append("<text:p text:style-name=\"Contents_20_1\">${link("#$anchor", esc(text), "Index_20_Link")}</text:p>")
        }
        body.append("</text:index-body></text:table-of-content>")

        for (b in doc.blocks) when (b) {
            is Block.Heading -> body.append(
                "<text:h text:style-name=\"Heading_20_1\" text:outline-level=\"1\"><text:bookmark text:name=\"${b.anchor}\"/>${esc(b.text)}</text:h>"
            )
            is Block.Paragraph -> {
                body.append("<text:p text:style-name=\"Text_20_body\">")
                for (r in b.runs) when (r) {
                    is Run.Text -> body.append(esc(r.text))
                    is Run.Cite -> body.append(link("#${r.anchors.first()}", esc(r.label), "Zitat"))
                }
                body.append("</text:p>")
            }
        }

        body.append("<text:h text:style-name=\"Heading_20_1\" text:outline-level=\"1\"><text:bookmark text:name=\"${doc.bibAnchor}\"/>${esc(doc.bibTitle)}</text:h>")
        for ((entry, segs) in doc.bibliography) {
            body.append("<text:p text:style-name=\"Bibliography_20_1\"><text:bookmark text:name=\"${entry.anchor}\"/>")
            for (s in segs) body.append(if (s.link != null) link(s.link, esc(s.text)) else span(s.text, s.italic))
            body.append("</text:p>")
        }

        val content = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content $NS><office:automatic-styles><style:style style:name="Sect1" style:family="section"/></office:automatic-styles><office:body><office:text>$body</office:text></office:body></office:document-content>"""

        val meta = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-meta $NS><office:meta><dc:title>${esc(doc.title)}</dc:title><meta:generator>VeritasLocal</meta:generator></office:meta></office:document-meta>"""

        val manifest = """<?xml version="1.0" encoding="UTF-8"?>
<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.3"><manifest:file-entry manifest:full-path="/" manifest:media-type="application/vnd.oasis.opendocument.text"/><manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/><manifest:file-entry manifest:full-path="styles.xml" manifest:media-type="text/xml"/><manifest:file-entry manifest:full-path="meta.xml" manifest:media-type="text/xml"/></manifest:manifest>"""

        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            // "mimetype" muss als erster Eintrag unkomprimiert stehen
            val mt = "application/vnd.oasis.opendocument.text".toByteArray(Charsets.US_ASCII)
            z.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mt.size.toLong(); compressedSize = mt.size.toLong()
                crc = CRC32().apply { update(mt) }.value
            })
            z.write(mt); z.closeEntry()
            for ((name, text) in listOf("content.xml" to content, "styles.xml" to styles(), "meta.xml" to meta, "META-INF/manifest.xml" to manifest)) {
                z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun styles() = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-styles $NS><office:styles>
<style:default-style style:family="paragraph"><style:paragraph-properties fo:margin-bottom="0.2cm" fo:line-height="130%"/><style:text-properties style:font-name="Liberation Serif" fo:font-family="'Liberation Serif', Georgia, serif" fo:font-size="11.5pt" fo:language="de" fo:country="DE"/></style:default-style>
<style:style style:name="Standard" style:family="paragraph" style:class="text"/>
<style:style style:name="Text_20_body" style:display-name="Text body" style:family="paragraph" style:parent-style-name="Standard" style:class="text"><style:paragraph-properties fo:text-align="justify" fo:margin-bottom="0.25cm"/></style:style>
<style:style style:name="Title" style:family="paragraph" style:parent-style-name="Standard" style:class="chapter"><style:paragraph-properties fo:margin-bottom="0.2cm"/><style:text-properties fo:font-size="20pt" fo:font-weight="bold"/></style:style>
<style:style style:name="Subtitle" style:family="paragraph" style:parent-style-name="Standard" style:class="chapter"><style:paragraph-properties fo:margin-bottom="0.8cm"/><style:text-properties fo:font-size="10pt" fo:font-style="italic" fo:color="#595959"/></style:style>
<style:style style:name="Heading_20_1" style:display-name="Heading 1" style:family="paragraph" style:parent-style-name="Standard" style:next-style-name="Text_20_body" style:default-outline-level="1" style:class="text"><style:paragraph-properties fo:margin-top="0.6cm" fo:margin-bottom="0.2cm" fo:keep-with-next="always"/><style:text-properties fo:font-size="15pt" fo:font-weight="bold"/></style:style>
<style:style style:name="Contents_20_Heading" style:display-name="Contents Heading" style:family="paragraph" style:parent-style-name="Standard" style:class="index"><style:paragraph-properties fo:margin-top="0.4cm" fo:margin-bottom="0.2cm"/><style:text-properties fo:font-size="15pt" fo:font-weight="bold"/></style:style>
<style:style style:name="Contents_20_1" style:display-name="Contents 1" style:family="paragraph" style:parent-style-name="Standard" style:class="index"><style:paragraph-properties><style:tab-stops><style:tab-stop style:position="16cm" style:type="right" style:leader-style="dotted" style:leader-text="."/></style:tab-stops></style:paragraph-properties></style:style>
<style:style style:name="Bibliography_20_1" style:display-name="Bibliography 1" style:family="paragraph" style:parent-style-name="Standard" style:class="index"><style:paragraph-properties fo:margin-left="1cm" fo:text-indent="-1cm" fo:margin-bottom="0.2cm"/></style:style>
<style:style style:name="Internet_20_link" style:display-name="Internet link" style:family="text"><style:text-properties fo:color="#0563c1" style:text-underline-style="solid" style:text-underline-width="auto" style:text-underline-color="font-color"/></style:style>
<style:style style:name="Index_20_Link" style:display-name="Index Link" style:family="text"/>
<style:style style:name="Zitat" style:family="text"><style:text-properties fo:color="#0563c1"/></style:style>
<style:style style:name="Kursiv" style:family="text"><style:text-properties fo:font-style="italic"/></style:style>
</office:styles><office:automatic-styles><style:page-layout style:name="pm1"><style:page-layout-properties fo:page-width="21cm" fo:page-height="29.7cm" fo:margin-top="2.5cm" fo:margin-bottom="2cm" fo:margin-left="2.5cm" fo:margin-right="2.5cm"/></style:page-layout></office:automatic-styles>
<office:master-styles><style:master-page style:name="Standard" style:page-layout-name="pm1"/></office:master-styles></office:document-styles>"""
}
