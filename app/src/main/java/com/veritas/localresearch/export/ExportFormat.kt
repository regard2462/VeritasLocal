package com.veritas.localresearch.export

import android.content.Context
import com.veritas.localresearch.domain.ResearchReport

enum class ExportFormat(val label: String, val mime: String, val extension: String) {
    PDF("PDF", "application/pdf", "pdf"),
    DOCX("DOCX", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
    ODT("ODT", "application/vnd.oasis.opendocument.text", "odt");

    fun render(context: Context, report: ResearchReport, style: CitationStyle): ByteArray {
        val doc = ExportDocument.from(report, style)
        return when (this) {
            PDF -> PdfWriter(context).write(doc)
            DOCX -> DocxWriter.write(doc)
            ODT -> OdtWriter.write(doc)
        }
    }

    companion object {
        /** Dateiname aus der Frage: Buchstaben, Ziffern und Bindestriche, hoechstens 60 Zeichen. */
        fun fileName(question: String, format: ExportFormat): String {
            val base = question.trim().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(60).trimEnd('-')
            return "${base.ifBlank { "Recherche" }}.${format.extension}"
        }
    }
}
