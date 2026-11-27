package com.veritas.localresearch.export

import com.veritas.localresearch.domain.LanguageDetector
import com.veritas.localresearch.domain.ResearchReport
import java.time.Instant
import java.time.ZoneId

/** Ein Stueck Fliesstext oder ein Verweis auf eine oder mehrere Quellen. */
sealed class Run {
    data class Text(val text: String) : Run()
    /** [label] ist der fertige Verweis im gewaehlten Stil, [anchors] die Sprungmarken der Quellen. */
    data class Cite(val label: String, val anchors: List<String>) : Run()
}

sealed class Block {
    /** Ueberschrift erster Ebene; [anchor] fuer Inhaltsverzeichnis und Lesezeichen. */
    data class Heading(val text: String, val anchor: String) : Block()
    data class Paragraph(val runs: List<Run>) : Block()
}

/**
 * Format-unabhaengige Fassung der Arbeit: Titel, Abschnitte mit Verweisen im gewaehlten Zitierstil und das
 * Literaturverzeichnis. DOCX, ODT und PDF werden daraus geschrieben.
 */
data class ExportDocument(
    val title: String,
    val subtitle: String,
    val tocTitle: String,
    val bibTitle: String,
    val blocks: List<Block>,
    val bibliography: List<Pair<BibEntry, List<Seg>>>,
    val bibAnchor: String = "literatur"
) {
    /** Alle Ueberschriften fuer das Inhaltsverzeichnis, das Literaturverzeichnis zuletzt. */
    val tocEntries: List<Pair<String, String>>
        get() = blocks.filterIsInstance<Block.Heading>().map { it.text to it.anchor } + (bibTitle to bibAnchor)

    companion object {
        private val CITE_GROUP = Regex("\\[(\\d+)](?:\\s*\\[(\\d+)])*")
        private val NUM = Regex("\\d+")

        fun from(report: ResearchReport, style: CitationStyle): ExportDocument {
            val german = LanguageDetector.detect(report.question)?.code?.let { it == "de" } ?: true
            val lang = CiteLang(german)
            val entries = Citations.entries(report.verifiedClaims, report.createdAt)
            val byNo = entries.associateBy { it.number }

            val text = report.synthesizedAnswer
            val headingStarts = report.headingRanges.associateBy { it.first }
            val blocks = ArrayList<Block>()
            var offset = 0
            var headingNo = 0
            for (line in text.split('\n')) {
                val start = offset
                offset += line.length + 1
                val t = line.trim()
                if (t.isEmpty()) continue
                if (headingStarts.containsKey(start)) {
                    headingNo++
                    blocks.add(Block.Heading(t, "abschnitt$headingNo"))
                    continue
                }
                blocks.add(Block.Paragraph(runsFor(t, byNo, style, lang)))
            }
            // Ohne Zwischenueberschriften bekommt der Text eine, damit das Inhaltsverzeichnis nicht leer ist
            if (blocks.none { it is Block.Heading }) blocks.add(0, Block.Heading(if (german) "Ergebnisse" else "Findings", "abschnitt1"))

            val date = Instant.ofEpochMilli(report.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
            val subtitle = if (german)
                "Recherche vom ${lang.longDate(date)} • erstellt mit VeritasLocal${report.modelName.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()} • jede Aussage mit wörtlichem Beleg geprüft"
            else
                "Research of ${lang.longDate(date)} • created with VeritasLocal${report.modelName.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()} • every statement checked against a verbatim quote"

            return ExportDocument(
                title = report.question.trim(),
                subtitle = subtitle,
                tocTitle = if (german) "Inhaltsverzeichnis" else "Contents",
                bibTitle = if (german) "Literaturverzeichnis" else "References",
                blocks = blocks,
                bibliography = Citations.ordered(entries, style, lang).map { it to Citations.reference(it, style, lang) }
            )
        }

        /** Zerlegt einen Absatz in Text und Verweise; "[1] [3]" wird ein gemeinsamer Verweis im gewaehlten Stil. */
        private fun runsFor(paragraph: String, byNo: Map<Int, BibEntry>, style: CitationStyle, lang: CiteLang): List<Run> {
            val runs = ArrayList<Run>()
            var last = 0
            for (m in CITE_GROUP.findAll(paragraph)) {
                val nums = NUM.findAll(m.value).mapNotNull { it.value.toIntOrNull() }.distinct().mapNotNull { byNo[it] }.toList()
                var before = paragraph.substring(last, m.range.first)
                if (nums.isEmpty()) { last = m.range.first; continue }
                // Autor-Jahr-Verweise stehen mit Leerzeichen vor dem Satzzeichen, Nummern ebenso
                if (!before.endsWith(" ") && before.isNotEmpty()) before += " "
                if (before.isNotEmpty()) runs.add(Run.Text(before))
                runs.add(Run.Cite(Citations.inText(nums, style, lang), nums.map { it.anchor }))
                last = m.range.last + 1
            }
            if (last < paragraph.length) runs.add(Run.Text(paragraph.substring(last)))
            // Doppelte Leerzeichen vor Verweisen glätten
            return runs.mapIndexed { i, r ->
                if (r is Run.Text && i + 1 < runs.size && runs[i + 1] is Run.Cite) Run.Text(r.text.replace(Regex(" {2,}$"), " ")) else r
            }
        }
    }
}
