package com.veritas.localresearch.verification

import com.veritas.localresearch.domain.LanguageDetector
import com.veritas.localresearch.pipeline.wordingThreshold
import com.veritas.localresearch.domain.RawClaimDto
import com.veritas.localresearch.domain.SourceType
import com.veritas.localresearch.domain.TextChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundingTest {

    private val paper = TextChunk(
        1, "https://arxiv.org/abs/0000.00000", "arxiv.org", "Data sharing outside the lab",
        "Here, engineers are those with a technical background employed in other sectors. We also distinguish between " +
            "direct data sharing (the report includes the engineer's own measurements), indirect data sharing " +
            "(technical authors talk about their measurements with business partners) and reverse data sharing.",
        SourceType.ARXIV
    )
    private val firm = TextChunk(
        2, "https://www.lwa.example.org/dm", "lwa.example.org", "Datenmanagement",
        "Daten zählen zu den wertvollsten Ressourcen in Unternehmen. Strukturiertes Datenmanagement hilft, die Daten " +
            "zu sichern, weiterzugeben und wirksam zu nutzen. Auch bei Personalwechsel bleiben wichtige Daten erhalten.",
        SourceType.WEB
    )
    private val office = TextChunk(
        3, "https://www.lwa.example.org/wer", "lwa.example.org", "Wer sind wir",
        "Das Landeswetteramt mit Hauptsitz in Hafenstadt wurde 1960 als selbstständige Landeseinrichtung durch das " +
            "Umweltministerium errichtet.",
        SourceType.WEB
    )
    private val chunks = listOf(paper, firm, office).associateBy { it.chunkId }
    private val v = DeterministicVerifier()

    private fun verify(vararg raw: RawClaimDto) = v.verifyClaims(raw.toList(), chunks, chunks.values.toList())

    private val dataClaim = RawClaimDto("Strukturiertes Datenmanagement hilft, die Daten zu sichern.",
        "Strukturiertes Datenmanagement hilft, die Daten zu sichern, weiterzugeben und wirksam zu nutzen", 2)

    @Test
    fun germanClaimOnEnglishQuoteIsRejected() {
        val r = verify(RawClaimDto(
            "In deutschen Unternehmen wird Datenmanagement oft durch interdisziplinäre Prozesse gesteuert.",
            "engineers are those with a technical background employed in other sectors", 1
        ))
        assertEquals(0, r.verifiedClaims.size)
        assertEquals(1, r.rejectedCount)
    }

    @Test
    fun pdfLineBreakDoesNotCutSentence() {
        val pdf = TextChunk(
            4, "https://www.example.org/dt.pdf", "example.org", "Datentransfer",
            "Oft wird eine Stelle (zum Beispiel eine Arbeitsgruppe oder eine\nexterne Beratung) von der Leitung des Unternehmens beauftragt, den\n" +
                "Datentransfer im Unternehmen zu fördern und zu etablieren.\nNächster Absatz beginnt hier mit anderem Inhalt.",
            SourceType.WEB
        )
        val all = chunks + (4 to pdf)
        val r = v.verifyClaims(listOf(RawClaimDto("Eine externe Beratung wird beauftragt, den Datentransfer zu fördern.",
            "externe Beratung) von der Leitung des Unternehmens beauftragt, den Datentransfer im Unternehmen zu fördern", 4)), all, all.values.toList())
        assertEquals(1, r.verifiedClaims.size)
        val q = r.verifiedClaims[0].exactQuote
        assertTrue(q, q.startsWith("Oft wird eine Stelle (zum Beispiel"))
        assertTrue(q, q.endsWith("zu etablieren."))
        assertFalse(q.contains('\n'))
    }

    @Test
    fun sentenceCutAtChunkEndIsRejected() {
        val cut = TextChunk(
            5, "https://www.example.org/dt.pdf", "example.org", "Datentransfer",
            "Das Modell zeigt Einflussfaktoren. Dieses Modell macht deutlich, welche Einflussfaktoren auf den Datentransfer\n" +
                "einwirken: die Qualität der Beziehung zwischen datengebender Person und datennehmender",
            SourceType.WEB
        )
        val all = chunks + (5 to cut)
        val r = v.verifyClaims(listOf(RawClaimDto("Das Modell nennt Einflussfaktoren auf den Datentransfer.",
            "welche Einflussfaktoren auf den Datentransfer einwirken", 5)), all, all.values.toList())
        assertEquals(0, r.verifiedClaims.size)
        assertTrue(endsMidSentence("Ein langer Satz ohne Ende, der mitten im Wort aufhört und nicht weitergeht"))
        assertFalse(endsMidSentence("Ein langer Satz mit Ende, der ordentlich aufhört und einen Punkt setzt."))
        assertFalse(endsMidSentence("Überschrift ohne Punkt"))
    }

    @Test
    fun inventedCompoundsAndOddSuffixesAreRemoved() {
        val r = verify(dataClaim)
        val q = "Datenmanagement und Datentransfer in Unternehmen"
        // Erfundene Zusammensetzungen aus Quellwoertern
        val invented = "Die Datenmanagementgesetze und die Einrichtung von Unternehmensdatenstellen sichern Daten [1]."
        assertFalse(v.groundAnswer(invented, r.verifiedClaims, q).contains("Unternehmensdatenstellen"))
        // Quellwort mit angehaengtem Buchstaben
        val odd = "Strukturiertes Datenmanagement ist entscheidend für den Datentransfert und hilft, Daten zu sichern [1]."
        assertFalse(v.groundAnswer(odd, r.verifiedClaims, q).contains("Datentransfert"))
        // Ein Buchstabe zu wenig mitten im Wort
        val typo = "Strukturiertes Datenmanagement unterstützt das Datenmanagment in Unternehmen und hilft, Daten zu sichern [1]."
        assertFalse(v.groundAnswer(typo, r.verifiedClaims, q).contains("Datenmanagment"))
        // Gebeugte Formen der Quellwoerter bleiben
        val ok = "Strukturiertes Datenmanagement des Datenmanagements hilft in Unternehmen, Daten zu sichern [1]."
        assertTrue(v.groundAnswer(ok, r.verifiedClaims, q).contains("Unternehmen"))
    }

    private val tool = TextChunk(
        8, "https://www.example.org/tool", "example.org", "Datenmanagement",
        "Ein Datenmanagement-Tool für Unternehmen muss Daten direkt mit der täglichen Arbeit verknüpfen. Es muss ohne Schulung nutzbar sein.",
        SourceType.WEB
    )

    @Test
    fun plainHeadingsAndQuoteFragments() {
        val src = TextChunk(
            7, "https://www.example.org/dt", "example.org", "Datentransfer",
            "Neue Mitarbeitende starten mit einem festen Team und ermöglicht den Zugang zu bewährten Praktiken anderer Unternehmen. " +
                "Wenn der Transfer von Daten der Risikovorsorge und Zukunftssicherung dient, ist der Weg zum Datenmanagement nicht mehr weit. " +
                "Denn Daten zählen zu den wertvollsten Ressourcen in Firmen und Unternehmen.",
            SourceType.WEB
        )
        val all = chunks + (7 to src)
        val r = v.verifyClaims(listOf(RawClaimDto("Neue Mitarbeitende starten mit einem festen Team.",
            "Neue Mitarbeitende starten mit einem festen Team und ermöglicht den Zugang zu bewährten Praktiken anderer Unternehmen", 7)),
            all, all.values.toList())
        assertEquals(1, r.verifiedClaims.size)
        val answer = "Einflussfaktoren\n" +
            "Team und ermöglicht den Zugang zu bewährten Praktiken anderer Unternehmen [1]. " +
            "Die Risikovorsorge und Zukunftssicherung dient, ist der Weg zum Datenmanagement nicht mehr weit [1]. " +
            "Daten zählen zu den wertvollsten Ressourcen in Firmen und Unternehmen [1]. " +
            "Neue Mitarbeitende starten mit einem festen Team und erhalten Zugang zu bewährten Praktiken [1]."
        val g = v.groundAnswer(answer, r.verifiedClaims, "Datentransfer in Unternehmen")
        assertTrue(g, g.startsWith("## Einflussfaktoren\n"))
        assertFalse(g, g.contains("Team und ermöglicht"))
        assertFalse(g, g.contains("Die Risikovorsorge"))
        // Ganzer Quellsatz mit Artikel am Anfang bleibt
        val whole = v.groundAnswer("Ein Datenmanagement-Tool für Unternehmen muss Daten direkt mit der täglichen Arbeit verknüpfen [1].",
            v.verifyClaims(listOf(RawClaimDto("Ein Datenmanagement-Tool für Unternehmen muss Daten mit der Arbeit verknüpfen.",
                "Ein Datenmanagement-Tool für Unternehmen muss Daten direkt mit der täglichen Arbeit verknüpfen", 8)),
                mapOf(8 to tool), listOf(tool)).verifiedClaims, "Datenmanagement in Unternehmen")
        assertTrue(whole, whole.contains("Ein Datenmanagement-Tool"))
        // Satz, der in der Quelle nach "Denn" beginnt, bleibt; eigene Umformulierung ebenso
        assertTrue(g, g.contains("Daten zählen zu den wertvollsten Ressourcen"))
        assertTrue(g, g.contains("erhalten Zugang"))
    }

    @Test
    fun shortPdfLineEndingInArticleIsJoined() {
        val text = "Unter Datentransfer können Formen des\nAustauschs zwischen Personen verstanden werden, die in einem Unternehmen arbeiten.\n" +
            "Dies ist eine weitere sehr lange Zeile mit viel Text, damit die Spaltenbreite erkennbar wird und mehr."
        assertTrue(joinSoftBreaks(text).contains("Formen des Austauschs"))
        assertFalse(joinSoftBreaks("Technische und kulturelle Aspekte im Datentransfer\nImpressum Text der Seite und noch mehr Text hier.\n" +
            "Der Datentransfer in Unternehmen hängt von der Firmenkultur und von klaren Zuständigkeiten ab, sagt die Studie.")
            .contains("Datentransfer Impressum"))
    }

    @Test
    fun verbatimModeReplacesBrokenParaphrase() {
        val r = verify(dataClaim)
        val broken = "Daten sichern ist strukturiertes Datenmanagement hilft weitergeben [1]."
        val g = v.groundAnswer(broken, r.verifiedClaims, "Datenmanagement", minShare = 0.6f)
        assertTrue(g, g.startsWith("Strukturiertes Datenmanagement hilft, die Daten zu sichern, weiterzugeben und wirksam zu nutzen [1]."))
        // Nah am Wortlaut bleibt der Satz des Modells; ohne Wortlaut-Grenze bleibt alles wie geschrieben
        val close = "Strukturiertes Datenmanagement hilft, die Daten zu sichern und weiterzugeben [1]."
        assertEquals(close, v.groundAnswer(close, r.verifiedClaims, "Datenmanagement", minShare = 0.6f))
        assertTrue(v.groundAnswer(broken, r.verifiedClaims, "Datenmanagement").contains("Daten sichern ist"))
    }

    @Test
    fun danglingPronounAndRepetition() {
        val r = verify(dataClaim)
        // "Sie …" am Abschnittsanfang hat keinen Bezug: dort steht der Quellsatz
        val g = v.groundAnswer("## Nutzen\nSie hilft, die Daten zu sichern und weiterzugeben [1].", r.verifiedClaims, "Datenmanagement")
        assertTrue(g, g.contains("Strukturiertes Datenmanagement hilft, die Daten zu sichern, weiterzugeben und wirksam zu nutzen [1]."))
        assertFalse(g, g.contains("Sie hilft"))
        // Mitten im Abschnitt ist "Sie" ein Verweis auf den Satz davor und bleibt
        val mid = "Strukturiertes Datenmanagement hilft, die Daten zu sichern [1]. Sie hilft auch, die Daten weiterzugeben [1]."
        assertTrue(v.groundAnswer(mid, r.verifiedClaims, "Datenmanagement").contains("Sie hilft auch"))
        // Dieselbe Wortfolge zweimal in einem Satz gilt als verunglueckt
        val rep = "Datenmanagement hilft die Daten zu sichern und hilft die Daten zu sichern [1]. Strukturiertes Datenmanagement hilft, die Daten zu sichern [1]."
        assertFalse(v.groundAnswer(rep, r.verifiedClaims, "Datenmanagement").contains("und hilft die Daten"))
    }

    @Test
    fun quotesCarryContextAndSkipListsAdsAndReferences() {
        val page = TextChunk(
            7, "https://www.example.org/dt", "example.org", "Datentransfer",
            "Datenmanagement ist ein Thema der Firmenleitung und betrifft alle Bereiche eines Unternehmens gleichermaßen und dauerhaft.\n" +
                "Unser Ansatz im Überblick:\nAnalyse des Datenbedarfs\nIdentifikation von Datenlücken und kritischen Prozessen.\n" +
                "Datenmanagement wird in vielen Betrieben eingeführt. Es ist Grundlage für die Leitung und Strategie in Organisationen.\n" +
                "Datentransfer: Das Wichtigste in Kürze\n" +
                "Datentransfer sichert kritische Daten bei Personalwechseln und schafft wirtschaftliche Vorteile durch Einarbeitung.\n" +
                "So bleibt Ihr Betrieb handlungsfähig, heute und in Zukunft, mit einem klaren Plan für alle Beschäftigten.\n" +
                "Hier finden Sie nützliche Handreichungen und Checklisten für den Datentransfer bei Personalwechsel.\n" +
                "Mit einem strukturierten Datentransfer sicherst du die Daten deiner Mitarbeitenden und deines Teams.\n" +
                "Große Unternehmen haben spezifische Anforderungen, die zwar oft in Handbüchern nachzulesen sind. " +
                "Die entscheidenden Daten liegen aber auf den Rechnern der Mitarbeitenden.\n" +
                "Datenpflege im Betrieb. Beispielverlag, S. 17 ff.",
            SourceType.WEB
        )
        val all = chunks + (7 to page)
        fun check(vararg raw: RawClaimDto) = v.verifyClaims(raw.toList(), all, all.values.toList())
        // "Es ist …": der Satz davor kommt ins Zitat; setzt die Aussage etwas ein, das dort nicht steht, fliegt sie raus
        val ok = check(RawClaimDto("Datenmanagement ist Grundlage für die Leitung und Strategie in Organisationen.",
            "Es ist Grundlage für die Leitung und Strategie in Organisationen", 7))
        assertEquals(1, ok.verifiedClaims.size)
        assertTrue(ok.verifiedClaims[0].exactQuote, ok.verifiedClaims[0].exactQuote.startsWith("Datenmanagement wird in vielen Betrieben eingeführt. Es ist"))
        // Tippfehler in einem kurzen Quellwort ("Betrieden"): strenge Pruefung setzt den Quellsatz ein, sonst faellt der Satz weg
        val typo = "Datenmanagement wird in vielen Betrieden eingeführt [1]."
        val fixed = v.groundAnswer(typo, ok.verifiedClaims, "Datenmanagement", minShare = 0.6f)
        assertTrue(fixed, fixed.contains("vielen Betrieben eingeführt. Es ist Grundlage") && !fixed.contains("Betrieden"))
        assertFalse(v.groundAnswer(typo, ok.verifiedClaims, "Datenmanagement").contains("Betrieden"))
        assertEquals(0, check(RawClaimDto("Datentransfer ist Grundlage für die Leitung und Strategie.",
            "Es ist Grundlage für die Leitung und Strategie in Organisationen", 7)).verifiedClaims.size)
        // Ueberschrift vor dem Satz faellt aus dem Zitat
        val line = check(RawClaimDto("Datentransfer sichert kritische Daten bei Personalwechseln.",
            "Datentransfer: Das Wichtigste in Kürze\nDatentransfer sichert kritische Daten bei Personalwechseln", 7))
        assertEquals(1, line.verifiedClaims.size)
        assertTrue(line.verifiedClaims[0].exactQuote, line.verifiedClaims[0].exactQuote.startsWith("Datentransfer sichert"))
        // Leistungsangebot, Leseranrede und Literaturangabe sind keine Fakten
        assertEquals(0, check(RawClaimDto("Der Ansatz beginnt mit der Analyse des Datenbedarfs.",
            "Unser Ansatz im Überblick:\nAnalyse des Datenbedarfs", 7)).verifiedClaims.size)
        assertEquals(0, check(RawClaimDto("Der Betrieb bleibt handlungsfähig, heute und in Zukunft.",
            "So bleibt Ihr Betrieb handlungsfähig, heute und in Zukunft", 7)).verifiedClaims.size)
        assertEquals(0, check(RawClaimDto("Es gibt Handreichungen und Checklisten für den Datentransfer bei Personalwechsel.",
            "Hier finden Sie nützliche Handreichungen und Checklisten für den Datentransfer bei Personalwechsel", 7)).verifiedClaims.size)
        assertEquals(0, check(RawClaimDto("Strukturierter Datentransfer sichert die Daten der Mitarbeitenden.",
            "Mit einem strukturierten Datentransfer sicherst du die Daten deiner Mitarbeitenden", 7)).verifiedClaims.size)
        assertEquals(0, check(RawClaimDto("Datenpflege im Betrieb stammt vom Beispielverlag.",
            "Beispielverlag, S. 17 ff", 7)).verifiedClaims.size)
        // Abgeschriebener Eingabekopf verschwindet, der Satz danach bleibt
        val leak = v.groundAnswer("CHUNK_ID=12 | QUELLE=example.org | TITEL=Datentransfer: 'Empfehlungen der Fachgruppe e. V. – Juni 2025' [1]. " +
            "Strukturiertes Datenmanagement hilft, die Daten zu sichern [1].", verify(dataClaim).verifiedClaims, "Datenmanagement")
        assertFalse(leak, leak.contains("CHUNK_ID") || leak.contains("Juni 2025"))
        assertTrue(leak, leak.contains("Strukturiertes Datenmanagement hilft"))
        // Halber "zwar"-Satz: der Quellsatz mit dem "aber" steht dort
        val both = check(RawClaimDto("Große Unternehmen haben spezifische Anforderungen, die zwar oft in Handbüchern nachzulesen sind.",
            "Große Unternehmen haben spezifische Anforderungen, die zwar oft in Handbüchern nachzulesen sind. Die entscheidenden Daten liegen aber auf den Rechnern der Mitarbeitenden", 7))
        assertEquals(1, both.verifiedClaims.size)
        val g = v.groundAnswer("Große Unternehmen haben spezifische Anforderungen, die zwar oft in Handbüchern nachzulesen sind [1].",
            both.verifiedClaims, "Datenmanagement")
        assertTrue(g, g.contains("aber auf den Rechnern"))
        // Anhang: Satz ohne Bezug oder halber Satz kommt nicht hinein
        val es = both.verifiedClaims[0].copy(exactQuote = "Es stärkt den Austausch zwischen Mitarbeitenden.")
        assertEquals(null, v.appendixSentence(es, verbatim = true))
        assertEquals(null, v.appendixSentence(both.verifiedClaims[0].copy(exactQuote = "Regeln sind zwar oft nachzulesen."), verbatim = true))
    }

    @Test
    fun nearDuplicateSentencesAreMerged() {
        val r = verify(dataClaim)
        // Umformulierung und danach derselbe Inhalt als Quellsatz mit Zusatz: nur der vollstaendigere Satz bleibt
        val answer = "Strukturiertes Datenmanagement hilft, die Daten zu sichern und weiterzugeben [1]. " +
            "Strukturiertes Datenmanagement hilft, die Daten zu sichern, weiterzugeben und wirksam zu nutzen. Auch bei Personalwechsel bleiben wichtige Daten erhalten [1]."
        val g = v.groundAnswer(answer, r.verifiedClaims, "Datenmanagement")
        assertEquals(g, 1, Regex("hilft, die Daten zu sichern").findAll(g).count())
        assertTrue(g, g.contains("Auch bei Personalwechsel bleiben wichtige Daten erhalten [1]."))
        // Anhang: Eintrag, dessen mitgenommener Satz davor schon in der Antwort steht, kommt nicht noch einmal
        val c = r.verifiedClaims[0]
        val repeated = c.copy(citationIndex = 2, exactQuote = "Strukturiertes Datenmanagement hilft, die Daten zu sichern, weiterzugeben und wirksam zu nutzen. Es senkt Kosten im Betrieb.")
        val fresh = c.copy(citationIndex = 3, exactQuote = "Daten zählen zu den wertvollsten Ressourcen in Unternehmen.")
        assertEquals(listOf("Daten zählen zu den wertvollsten Ressourcen in Unternehmen [3]."),
            v.appendixSentences(g, listOf(repeated, fresh, fresh.copy(citationIndex = 4)), verbatim = true))
    }

    @Test
    fun sameFindingInOtherWordsIsMerged() {
        val pump = TextChunk(
            5, "https://www.example.org/wp", "example.org", "Wärmepumpen im Winter",
            "Messungen im Feld aus dem Jahr 2024 zeigen, dass gute Geräte bei -20 Grad noch 40 bis 55 Prozent ihrer Nennleistung erreichen. " +
                "Feldmessungen aus dem Jahr 2024 zeigen, dass gute Geräte bei -20 Grad 40 bis 55 Prozent ihrer Nennleistung erreichen " +
                "und dennoch viel Strom gegenüber einer Heizpatrone sparen.",
            SourceType.WEB
        )
        val all = chunks + (5 to pump)
        val r = v.verifyClaims(listOf(
            RawClaimDto("Gute Geräte erreichen bei -20 Grad noch 40 bis 55 Prozent ihrer Nennleistung.",
                "Messungen im Feld aus dem Jahr 2024 zeigen, dass gute Geräte bei -20 Grad noch 40 bis 55 Prozent ihrer Nennleistung erreichen", 5),
            RawClaimDto("Gute Geräte sparen bei -20 Grad viel Strom gegenüber einer Heizpatrone.",
                "und dennoch viel Strom gegenüber einer Heizpatrone sparen", 5)
        ), all, all.values.toList())
        val answer = "Messungen im Feld aus dem Jahr 2024 zeigen, dass gute Geräte bei -20 Grad noch 40 bis 55 Prozent ihrer Nennleistung erreichen [1]. " +
            "Feldmessungen aus dem Jahr 2024 zeigen, dass gute Geräte bei -20 Grad 40 bis 55 Prozent ihrer Nennleistung erreichen " +
            "und dennoch viel Strom gegenüber einer Heizpatrone sparen [2]."
        val g = v.groundAnswer(answer, r.verifiedClaims, "Wärmepumpen im Winter")
        // Derselbe Befund mit denselben Zahlen steht nur einmal da, und zwar in der vollstaendigeren Fassung
        assertEquals(g, 1, Regex("40 bis 55 Prozent").findAll(g).count())
        assertTrue(g, g.contains("Heizpatrone"))
    }

    @Test
    fun sameFindingWithRepeatedNumberIsMerged() {
        val guide = TextChunk(
            6, "local:guide", "Home Battery Guide", "Home Battery Guide",
            "A battery of about 8 kilowatt hours fits a household that uses 4,000 kilowatt hours per year and has an 8 kilowatt peak system.",
            SourceType.LOCAL
        )
        val all = chunks + (6 to guide)
        val para = "For a household consuming 4,000 kilowatt hours annually with an 8 kilowatt peak solar system, " +
            "a battery capacity of approximately 8 kilowatt hours is recommended"
        val r = v.verifyClaims(listOf(
            RawClaimDto(para, "A battery of about 8 kilowatt hours fits a household that uses 4,000 kilowatt hours per year and has an 8 kilowatt peak system", 6)
        ), all, all.values.toList())
        val answer = "Sizing\n$para [1].\n" +
            "Costs\nA battery of about 8 kilowatt hours fits a household that uses 4,000 kilowatt hours per year and has an 8 kilowatt peak system [1]."
        val g = v.groundAnswer(answer, r.verifiedClaims, "Home battery size")
        assertEquals(g, 1, Regex("4,000").findAll(g).count())
    }

    @Test
    fun bulletsFromSourcesAreRemoved() {
        val r = verify(dataClaim)
        val g = v.groundAnswer("• Strukturiertes Datenmanagement hilft, die Daten zu sichern, weiterzugeben und wirksam zu nutzen [1].",
            r.verifiedClaims, "Datenmanagement")
        assertTrue(g, g.startsWith("Strukturiertes Datenmanagement"))
    }

    @Test
    fun customLengthScalesFactsAndSections() {
        val medium = com.veritas.localresearch.pipeline.LengthSpec.of(com.veritas.localresearch.settings.AnswerLength.MEDIUM)
        assertEquals(0, medium.words)
        assertEquals(5, medium.maxSections)
        val long = com.veritas.localresearch.pipeline.LengthSpec.of(com.veritas.localresearch.settings.AnswerLength.MEDIUM, 1500)
        assertEquals(50, long.factTarget)
        assertTrue(long.detailed && long.outline && long.maxSections >= 8)
        // Ausserhalb des Bereichs wird begrenzt
        assertEquals(3000, com.veritas.localresearch.pipeline.LengthSpec.of(com.veritas.localresearch.settings.AnswerLength.SHORT, 99999).words)
    }

    @Test
    fun wordingThresholdFollowsModelSize() {
        assertEquals(0.6f, wordingThreshold("Qwen3-0.6B-Q4_K_M.gguf"), 0f)
        assertEquals(0.6f, wordingThreshold("Qwen3-1.7B-Q4_K_M.gguf"), 0f)
        assertEquals(0.6f, wordingThreshold("SmolLM2-360M-Instruct-Q8_0.gguf"), 0f)
        assertEquals(0.4f, wordingThreshold("Qwen3-4B-Q4_K_M.gguf"), 0f)
        assertEquals(0.4f, wordingThreshold("gemma-2-9b-it.gguf"), 0f)
        assertEquals(0.5f, wordingThreshold("eigenes-modell.gguf"), 0f)
    }

    @Test
    fun headingsAndImprintAreNoFacts() {
        val pdf = TextChunk(
            6, "https://www.example.org/dt.pdf", "example.org", "Datentransfer",
            "Technische und kulturelle Aspekte im Datentransfer\n" +
                "Impressum „Datentransfer im Betrieb“ ist eine Publikation der Fachgruppe der Gesellschaft für Datenmanagement.\n" +
                "Der Datentransfer in Unternehmen hängt von der Firmenkultur und von klaren Zuständigkeiten ab.",
            SourceType.WEB
        )
        val all = chunks + (6 to pdf)
        val r = v.verifyClaims(listOf(
            RawClaimDto("Technische und kulturelle Aspekte im Datentransfer", "Technische und kulturelle Aspekte im Datentransfer", 6),
            RawClaimDto("Die Publikation stammt von der Fachgruppe der Gesellschaft für Datenmanagement.",
                "ist eine Publikation der Fachgruppe der Gesellschaft für Datenmanagement", 6),
            RawClaimDto("Der Datentransfer in Unternehmen hängt von der Firmenkultur ab.",
                "Der Datentransfer in Unternehmen hängt von der Firmenkultur und von klaren Zuständigkeiten ab", 6)
        ), all, all.values.toList())
        assertEquals(listOf("Der Datentransfer in Unternehmen hängt von der Firmenkultur und von klaren Zuständigkeiten ab."),
            r.verifiedClaims.map { it.exactQuote })
    }

    @Test
    fun garbledParaphraseIsRemoved() {
        val r = verify(dataClaim)
        val answer = "Strukturiertes Datenmanagement hilft, die Daten zu sichern [1]. " +
            "Diese Aspekte sind entscheidend für die Datenstruktur und die Datentransfertung im Datenmanagement [1]."
        val g = v.groundAnswer(answer, r.verifiedClaims, "Datenmanagement und Datentransfer in Unternehmen")
        assertTrue(g, g.contains("hilft, die Daten zu sichern [1]."))
        assertFalse(g, g.contains("Datentransfertung"))
        // Fluessige Umformulierung mit gewoehnlichen langen Woertern bleibt stehen
        val fluent = "Zusammenfassend lässt sich grundsätzlich sagen: Strukturiertes Datenmanagement hilft, Daten zu sichern [1]."
        assertTrue(v.groundAnswer(fluent, r.verifiedClaims, "Datenmanagement").contains("Zusammenfassend"))
    }

    private val officeClaim = RawClaimDto("Das Landeswetteramt wurde 1960 als selbstständige Landeseinrichtung errichtet.",
        "wurde 1960 als selbstständige Landeseinrichtung durch das Umweltministerium errichtet", 3)

    @Test
    fun coveredFactsAreNotAppendedAgain() {
        val r = verify(dataClaim, officeClaim)
        val dm = r.verifiedClaims.first { it.chunk.chunkId == 2 }
        val amt = r.verifiedClaims.first { it.chunk.chunkId == 3 }
        val text = "Daten zählen viel [${dm.citationIndex}]. Strukturiertes Datenmanagement hilft, die Daten zu sichern, " +
            "weiterzugeben und wirksam zu nutzen [${dm.citationIndex}]."
        // Gleicher Fakt unter anderer Nummer (doppelt gefunden) gilt als abgedeckt
        val again = dm.copy(citationIndex = 9)
        assertEquals(listOf(amt.citationIndex), v.notYetCovered(text, listOf(again, amt, amt.copy(citationIndex = 8))).map { it.citationIndex })
    }

    @Test
    fun faithfulClaimsPass() {
        val r = verify(
            officeClaim,
            RawClaimDto("Strukturiertes Datenmanagement hilft Unternehmen, Daten zu sichern und weiterzugeben.",
                "Strukturiertes Datenmanagement hilft, die Daten zu sichern, weiterzugeben und wirksam zu nutzen", 2)
        )
        assertEquals(2, r.verifiedClaims.size)
    }

    @Test
    fun quoteWithWrongChunkIdIsFoundInRightChunk() {
        val r = verify(RawClaimDto("Daten zählen zu den wertvollsten Ressourcen in Unternehmen.",
            "Daten zählen zu den wertvollsten Ressourcen in Unternehmen.", 3))
        assertEquals(1, r.verifiedClaims.size)
        assertEquals("lwa.example.org", r.verifiedClaims[0].chunk.domain)
        assertEquals(2, r.verifiedClaims[0].chunk.chunkId)
    }

    @Test
    fun inventedSentencesAndQuotesAreRemoved() {
        val claims = verify(
            RawClaimDto("Strukturiertes Datenmanagement hilft Unternehmen, Daten zu sichern und weiterzugeben.",
                "Strukturiertes Datenmanagement hilft, die Daten zu sichern, weiterzugeben und wirksam zu nutzen", 2)
        ).verifiedClaims
        val answer = "## Stand\n" +
            "Strukturiertes Datenmanagement hilft Unternehmen, Daten zu sichern und weiterzugeben [1]. " +
            "Auch bei Personalwechsel bleiben wichtige Daten erhalten.\n\n" +
            "## Hürden\n" +
            " [2 QUELLE] Die rechtlichen und kulturellen Hürden variieren je nach Region und politischer Struktur. " +
            "(Zitat: „The cultural and legal barriers in German companies vary depending on the location.“)"
        val out = v.groundAnswer(answer, claims)
        assertTrue(out, out.contains("sichern und weiterzugeben [1]"))
        assertTrue(out, out.contains("Personalwechsel bleiben wichtige Daten erhalten [1]."))
        assertFalse(out, out.contains("Hürden"))
        assertFalse(out, out.contains("QUELLE"))
        assertFalse(out, out.contains("cultural"))
    }

    @Test
    fun uncitedClaimsAreDroppedAndRenumbered() {
        val claims = verify(
            officeClaim,
            RawClaimDto("Strukturiertes Datenmanagement hilft Unternehmen, Daten zu sichern und weiterzugeben.",
                "Strukturiertes Datenmanagement hilft, die Daten zu sichern, weiterzugeben und wirksam zu nutzen", 2)
        ).verifiedClaims
        val second = claims.first { it.chunk.chunkId == 2 }.citationIndex
        val (text, used) = v.keepCitedOnly("Daten werden gesichert [$second].", claims)
        assertEquals("Daten werden gesichert [1].", text)
        assertEquals(1, used.size)
        assertEquals(2, used[0].chunk.chunkId)
        assertEquals(1, used[0].citationIndex)
    }

    @Test
    fun headingsDuplicatesSelfPromoAndFragments() {
        val group = TextChunk(
            4, "https://www.example.org/dt-betrieb/", "example.org", "Fachgruppe Datentransfer",
            "Die Fachgruppe hat in Unternehmen gearbeitet. Dabei zeigte sich, dass Datentransfer, Datensicherung und Mentoring " +
                "als wichtige Aufgaben erkannt wurden. Die digitalen Firmenstände sind in unserem Artikel zur digitalen Firma beschrieben.",
            SourceType.WEB
        )
        val all = (chunks.values + group).associateBy { it.chunkId }
        val r = v.verifyClaims(listOf(
            RawClaimDto("Datentransfer, Datensicherung und Mentoring als wichtige Aufgaben erkannt wurden",
                "Datentransfer, Datensicherung und Mentoring als wichtige Aufgaben erkannt wurden", 4),
            RawClaimDto("Datentransfer, Datensicherung und Mentoring wurden als wichtige Aufgaben erkannt",
                "Datentransfer, Datensicherung und Mentoring als wichtige Aufgaben erkannt wurden", 4),
            RawClaimDto("Die digitalen Firmenstände sind beschrieben worden.",
                "Die digitalen Firmenstände sind in unserem Artikel zur digitalen Firma beschrieben", 4),
            RawClaimDto("Strukturiertes Datenmanagement hilft Unternehmen, Daten zu sichern und weiterzugeben.",
                "Strukturiertes Datenmanagement hilft, die Daten zu sichern, weiterzugeben und wirksam zu nutzen", 2),
            RawClaimDto("Daten zählen zu den wertvollsten Ressourcen in Unternehmen.",
                "Daten zählen zu den wertvollsten Ressourcen in Unternehmen.", 2)
        ), all, all.values.toList())
        // Bruchstueck wird ganzer Satz, Dublette und Eigenwerbung fliegen raus
        assertEquals(3, r.verifiedClaims.size)
        val transfer = r.verifiedClaims.first { it.chunk.chunkId == 4 }
        assertTrue(transfer.exactQuote, transfer.exactQuote.startsWith("Dabei zeigte sich"))
        assertTrue(transfer.claim, transfer.claim.startsWith("Dabei zeigte sich"))

        val idx = r.verifiedClaims.filter { it.chunk.chunkId == 2 }.map { it.citationIndex }
        val answer = "## Stand [${idx[0]}]\nDaten zählen zu den wertvollsten Ressourcen in Unternehmen [${idx[1]}]. " +
            "Strukturiertes Datenmanagement hilft, Daten zu sichern und weiterzugeben [${idx[0]}].## Hürden\n" +
            "Strukturiertes Datenmanagement hilft, Daten zu sichern und weiterzugeben [${idx[0]}]."
        val grounded = v.groundAnswer(answer, r.verifiedClaims)
        val (text, used) = v.keepCitedOnly(grounded, r.verifiedClaims)
        assertEquals("## Stand\nDaten zählen zu den wertvollsten Ressourcen in Unternehmen [1]. " +
            "Strukturiertes Datenmanagement hilft, Daten zu sichern und weiterzugeben [1].", text)
        assertEquals(setOf(1), used.map { it.citationIndex }.toSet())
    }

    private val seatClaim = RawClaimDto("Das Landeswetteramt hat seinen Hauptsitz in Hafenstadt.",
        "Das Landeswetteramt mit Hauptsitz in Hafenstadt wurde 1960", 3)

    @Test
    fun leadingCitationMovesToEnd() {
        val claims = verify(seatClaim).verifiedClaims
        assertEquals("Das Landeswetteramt hat seinen Hauptsitz in Hafenstadt [1].",
            v.groundAnswer("[1] Das Landeswetteramt hat seinen Hauptsitz in Hafenstadt.", claims))
    }

    @Test
    fun abbreviationsDoNotBreakSentences() {
        val claims = verify(seatClaim).verifiedClaims
        val out = v.groundAnswer("Das Landeswetteramt, z. B. Hauptsitz in Hafenstadt, wurde 1960 errichtet [1].", claims)
        assertEquals("Das Landeswetteramt, z. B. Hauptsitz in Hafenstadt, wurde 1960 errichtet [1].", out)
    }

    @Test
    fun languageDetection() {
        assertEquals("de", LanguageDetector.detect("Datenmanagement und Datentransfer in deutschen Unternehmen sowie rechtliche, technische und kulturelle Hürden und Einflussfaktoren")?.code)
        assertEquals("de", LanguageDetector.detect("Wann wurde das Landeswetteramt gegründet und wo hat es seinen Sitz?")?.code)
        assertEquals("en", LanguageDetector.detect("What are the main barriers to data sharing in large companies?")?.code)
        assertTrue(LanguageDetector.isOtherLanguage(paper.rawText, LanguageDetector.GERMAN))
        assertFalse(LanguageDetector.isOtherLanguage(firm.rawText, LanguageDetector.GERMAN))
    }
}
