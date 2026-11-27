package com.veritas.localresearch.verification

import com.veritas.localresearch.domain.RawClaimDto
import com.veritas.localresearch.i18n.tr
import com.veritas.localresearch.domain.TextChunk
import com.veritas.localresearch.domain.TrustLevel
import com.veritas.localresearch.domain.UnverifiedNumberSpan
import com.veritas.localresearch.domain.VerifiedClaim
import com.veritas.localresearch.llm.OnnxEmbedder

/**
 * Zeilenumbruch mitten im Satz, wie ihn PDFs und schmale Spalten erzeugen: die Zeile davor ist lang (keine
 * Ueberschrift) und endet nicht mit einem Satzzeichen, die naechste Zeile ist nicht leer.
 */
internal fun isSoftBreak(text: String, nl: Int, minLine: Int = softLineMin(text)): Boolean {
    if (nl <= 0 || nl >= text.length - 1 || text[nl] != '\n' || text[nl + 1] == '\n') return false
    val lineStart = text.lastIndexOf('\n', nl - 1) + 1
    val prev = text.substring(lineStart, nl).trimEnd()
    if (prev.isEmpty() || prev.last() in ".!?:;" || text.substring(nl + 1).isBlank()) return false
    if (prev.length >= minLine) return true
    // Kuerzere Zeile, aber sichtbar mitten im Satz: endet auf Artikel, Praeposition oder Bindewort ("Formen des"),
    // auf Komma oder Trennstrich, oder die naechste Zeile beginnt klein
    if (prev.length < 25) return false
    val lastWord = prev.substringAfterLast(' ').lowercase()
    val next = text.getOrNull(nl + 1)
    return prev.last() == ',' || prev.last() == '-' || lastWord in CONTINUATION_WORDS || (next != null && next.isLowerCase())
}

private val CONTINUATION_WORDS = setOf(
    "der", "die", "das", "des", "dem", "den", "ein", "eine", "einer", "eines", "einem", "einen", "und", "oder", "sowie",
    "von", "vom", "zu", "zum", "zur", "mit", "für", "auf", "an", "am", "in", "im", "bei", "aus", "nach", "über", "unter",
    "durch", "gegen", "ohne", "um", "als", "wie", "dass", "ob", "nicht", "auch", "sich", "the", "of", "and", "to", "a", "an", "in", "for", "with"
)

/**
 * Mindestlaenge einer Zeile, die mitten im Satz umbricht: sie fuellt die Spalte fast ganz (70 % der laengsten Zeile,
 * hoechstens bis 110 Zeichen gerechnet), mindestens 50 Zeichen. Kuerzere Zeilen ohne Satzzeichen sind Ueberschriften.
 */
internal fun softLineMin(text: String): Int {
    var maxLen = 0
    var start = 0
    while (start <= text.length) {
        val nl = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        maxLen = maxOf(maxLen, nl - start)
        start = nl + 1
    }
    return maxOf(50, (minOf(maxLen, 110) * 0.7f).toInt())
}

/** Ersetzt weiche Zeilenumbrueche durch Leerzeichen; Silbentrennung ("Arbeits-\ngruppe") wird zusammengezogen. */
internal fun joinSoftBreaks(text: String): String {
    val sb = StringBuilder(text.length)
    val minLine = softLineMin(text)
    for (i in text.indices) {
        val c = text[i]
        if (c == '\n' && isSoftBreak(text, i, minLine)) {
            val next = text.getOrNull(i + 1)
            if (sb.endsWith("-") && sb.length >= 2 && sb[sb.length - 2].isLetter() && next != null && next.isLowerCase()) sb.setLength(sb.length - 1)
            else sb.append(' ')
        } else sb.append(c)
    }
    return sb.toString()
}

/**
 * Text bricht mitten im Satz ab: die letzte Zeile ist lang (keine Ueberschrift, kein Listenpunkt) und endet
 * ohne Satzzeichen. So endet ein Abschnitt, der an einer Wortgrenze statt am Satzende getrennt wurde.
 */
internal fun endsMidSentence(text: String): Boolean {
    val t = text.trimEnd()
    if (t.isEmpty()) return false
    val lastLine = t.substring(t.lastIndexOf('\n') + 1)
    return lastLine.length >= 50 && t.last() !in ".!?:;\"“”»«)]"
}

/** Levenshtein-Abstand von hoechstens 1 (einfuegen, loeschen oder ersetzen eines Zeichens). */
internal fun editDistanceAtMostOne(a: String, b: String): Boolean {
    if (kotlin.math.abs(a.length - b.length) > 1) return false
    var i = 0
    var j = 0
    var edits = 0
    while (i < a.length && j < b.length) {
        if (a[i] == b[j]) { i++; j++; continue }
        if (++edits > 1) return false
        when {
            a.length > b.length -> i++
            a.length < b.length -> j++
            else -> { i++; j++ }
        }
    }
    return edits + (a.length - i) + (b.length - j) <= 1
}

class DeterministicVerifier {

    data class ClaimVerificationResult(
        val verifiedClaims: List<VerifiedClaim>,
        val rejectedCount: Int,
        /** Grund je verworfener Aussage, fuer das Protokoll. */
        val rejections: List<String> = emptyList()
    )

    private data class PrecomputedChunk(
        val chunk: TextChunk,
        val keyTokens: Set<String>,
        val normalizedNumbers: Set<String>
    )

    fun verifyClaims(
        rawClaims: List<RawClaimDto>,
        chunkMap: Map<Int, TextChunk>,
        allIndexedChunks: List<TextChunk>,
        sameTopicThreshold: Float = 0.74f
    ): ClaimVerificationResult {
        val verified = mutableListOf<VerifiedClaim>()
        var rejected = 0
        val reasons = ArrayList<String>()
        val seenQuotes = HashSet<String>()
        val seenClaims = HashSet<String>()

        // Einmal vorab pro Chunk berechnen statt in einer verschachtelten Schleife
        val precomputedPool = allIndexedChunks.map { c ->
            PrecomputedChunk(
                chunk = c,
                keyTokens = tokenizeWords(c.rawText).filter { it.length >= 4 }.toSet(),
                normalizedNumbers = extractNormalizedNumberTokens(c.rawText)
            )
        }

        for (raw in rawClaims) {
            val cleanClaim = raw.claim.trim()
            val cleanQuote = raw.exactQuote.trim()
            val named = chunkMap[raw.chunkId]

            if (cleanClaim.isBlank() || cleanQuote.length < 12) {
                reasons.add("leer: $cleanClaim")
                rejected++
                continue
            }

            // Duplikate vermeiden
            val dedupKey = normalizeText(cleanClaim).take(60)
            if (!seenClaims.add(dedupKey)) continue

            // 1. Deterministischer Zitat-Check im genannten Chunk. Kleine Modelle vertauschen oft die Chunk-Nummer:
            //    steht das Zitat dort nicht, zaehlt es, wenn es woertlich in einem anderen geladenen Chunk steht.
            var chunk = named
            var match = named?.let { findQuoteInChunk(cleanQuote, it.rawText) } ?: QuoteMatch(0f, null)
            if (match.score < 0.72f) {
                val qTokens = tokenizeWords(cleanQuote).filter { it.length >= 4 }.toSet()
                val candidates = (chunkMap.values + allIndexedChunks).distinctBy { it.chunkId }
                    .filter { c -> qTokens.isEmpty() || qTokens.count { it in c.rawText.lowercase() } >= qTokens.size * 0.7 }
                for (c in candidates) {
                    val m = findQuoteInChunk(cleanQuote, c.rawText)
                    if (m.score > match.score) { match = m; chunk = c }
                }
            }
            if (chunk == null || match.score < 0.72f) {
                reasons.add("Zitat ${"%.2f".format(match.score)}: $cleanQuote")
                rejected++
                continue
            }

            // 1b. Die Aussage selbst muss aus der Quelle stammen: ihre Inhaltswoerter stehen ueberwiegend im Chunk
            //     und teils im Zitat. Faengt Umdeutungen, Uebersetzungen und aus der Frage eingeschmuggelte Begriffe ab.
            if (!claimSupportedBy(cleanClaim, cleanQuote, chunk)) {
                reasons.add("Aussage nicht im Chunk: $cleanClaim | $cleanQuote")
                rejected++
                continue
            }

            // Werbung und Hinweise der Seite auf sich selbst ("in unserem Artikel") sind keine Fakten
            if (SELF_PROMO_REGEX.containsMatchIn(cleanQuote) || SELF_PROMO_REGEX.containsMatchIn(cleanClaim) ||
                READER_ADDRESS_REGEX.containsMatchIn(cleanQuote)) {
                reasons.add("Eigenwerbung: $cleanQuote")
                rejected++
                continue
            }

            // Zitat auf den ganzen Satz erweitern, damit keine Bruchstuecke ("… erkannt wurden") weitergegeben werden.
            // Ist die Aussage nur das Bruchstueck selbst, wird sie ebenfalls zum ganzen Satz.
            val expanded = expandToSentence(chunk.rawText, match.range)
            var (sentence, sentenceRange) = expanded ?: (cleanQuote to match.range)
            // Zitat ueber mehrere Zeilen (Ueberschrift, Aufzaehlung, Satz): nur die Zeile, auf der die Aussage beruht
            if ('\n' in sentence) {
                val claimStems = contentStems("$cleanClaim").toSet()
                val lines = sentence.lines().map { it.trim() }.filter { it.isNotEmpty() }
                val best = lines.maxByOrNull { l -> contentStems(l).count { it in claimStems } * 1000 + l.length }
                if (best != null) {
                    val at = chunk.rawText.indexOf(best.take(40), (sentenceRange?.first ?: 0).coerceAtLeast(0))
                    sentence = best
                    if (at >= 0) sentenceRange = at until (at + best.length).coerceAtMost(chunk.rawText.length)
                }
            }
            if (LITERATURE_REGEX.containsMatchIn(sentence)) {
                reasons.add("Literaturangabe: $sentence")
                rejected++
                continue
            }
            // Satz beginnt mit "Es/Sie/Dieser …": der Bezug steht im Satz davor. Das Zitat bekommt diesen Satz mit, und was
            // die Aussage fuer das Fuerwort einsetzt ("Solarspeicher"), muss dort auch stehen.
            if (sentence.substringBefore(' ').lowercase().trimEnd(',') in OPENING_PRONOUNS) {
                val prev = sentenceRange?.let { previousSentence(chunk.rawText, it.first) }
                // Ganze Woerter statt 5-Zeichen-Staemmen: "Solarspeicher" und "Solarstrom" sind verschieden
                fun words(t: String) = tokenizeWords(t).filter { it.length >= 4 && it !in STOPWORDS && it.any(Char::isLetter) }
                val own = sentence.lowercase()
                val added = words(cleanClaim).distinct().filter { w -> !own.contains(w.take(maxOf(5, w.length - 2))) }
                val prevLower = prev?.first?.lowercase().orEmpty()
                if (prev == null || (added.isNotEmpty() && added.count { w -> prevLower.contains(w.take(maxOf(5, w.length - 2))) } * 2 < added.size)) {
                    reasons.add("Bezug unklar: $cleanClaim | $sentence")
                    rejected++
                    continue
                }
                sentence = prev.first + " " + sentence
                sentenceRange = prev.second.first..(sentenceRange?.last ?: prev.second.last)
            }
            val claimText = if (normalizeText(sentence).contains(normalizeText(cleanClaim))) sentence else cleanClaim
            // Satz endet dort, wo der Abschnitt mitten im Satz abgeschnitten wurde
            if (runsIntoCut(chunk.rawText, match.range)) {
                reasons.add("Abgeschnitten: $claimText")
                rejected++
                continue
            }
            // Bruchstuecke: Satz beginnt klein oder mitten in einer Klammer ("Gruppe oder eine Beratung) von …")
            // Ueberschriften ("Technische und rechtliche Aspekte der Solarspeicher"): kurz und ohne Satzzeichen am Ende
            val sentEnd = sentence.trimEnd().lastOrNull()
            if ((expanded != null && sentEnd != null && sentEnd !in ".!?\"“”»«)" && sentence.split(' ').size < 12) ||
                BOILERPLATE_START.containsMatchIn(sentence.trimStart())) {
                reasons.add("Überschrift oder Seitenbaustein: $sentence")
                rejected++
                continue
            }
            if (claimText.first().isLowerCase() || sentence.first().isLowerCase() || OPEN_PAREN_FRAGMENT.containsMatchIn(claimText)) {
                reasons.add("Bruchstück: $claimText")
                rejected++
                continue
            }
            if (!seenQuotes.add(normalizeText(sentence).take(120))) continue

            // 2. Cross-Source-Pruefung
            val corroborating = findCorroboratingDomains(claimText, chunk, precomputedPool, sameTopicThreshold)
            val trust = if (corroborating.isNotEmpty()) TrustLevel.MULTI_SOURCE else TrustLevel.SINGLE_SOURCE

            verified.add(
                VerifiedClaim(
                    citationIndex = verified.size + 1,
                    claim = claimText,
                    exactQuote = sentence,
                    chunk = chunk,
                    quoteMatchScore = match.score,
                    quoteHighlightRange = sentenceRange,
                    trustLevel = trust,
                    corroboratingDomains = corroborating
                )
            )
        }

        // Cross-Source bestaetigte Aussagen nach vorne sortieren & Zitatnummern neu vergeben
        val sorted = verified
            .sortedWith(
                compareByDescending<VerifiedClaim> { it.trustLevel == TrustLevel.MULTI_SOURCE }
                    .thenByDescending { it.quoteMatchScore }
            )
            .mapIndexed { idx, vc -> vc.copy(citationIndex = idx + 1) }

        return ClaimVerificationResult(sorted, rejected, reasons)
    }

    /**
     * Extrahiert Zahlen, Jahreszahlen, Prozentwerte, Waehrungen und Daten aus der generierten Antwort
     * und prueft deterministisch, ob sie in den belegten Quell-Chunks vorkommen.
     */
    fun detectUnverifiedNumbers(
        answerText: String,
        verifiedClaims: List<VerifiedClaim>
    ): List<UnverifiedNumberSpan> {
        if (answerText.isBlank()) return emptyList()

        // Alle belegten Quelltexte (inkl. Chunk-Text & Zitate) sammeln
        val sourceCorpus = verifiedClaims.joinToString("\n") {
            "${it.chunk.title}\n${it.chunk.rawText}"
        }
        val sourceNormalizedNumbers = extractNormalizedNumberTokens(sourceCorpus)

        // Zitat-Marker wie [1], [2] duerfen NICHT als unbelegte Zahlen gewertet werden
        val citationBracketRanges = CITATION_BRACKET_REGEX
            .findAll(answerText)
            .map { it.range }
            .toList()

        val flagged = mutableListOf<UnverifiedNumberSpan>()
        for (match in NUMBER_TOKEN_REGEX.findAll(answerText)) {
            val range = match.range
            if (citationBracketRanges.any { it.first <= range.first && it.last >= range.last }) {
                continue
            }

            // Aufzaehlungsziffern am Zeilenanfang (auch eingerueckt oder nach "- "/"* ") ignorieren
            val lineStart = answerText.lastIndexOf('\n', (range.first - 1).coerceAtLeast(0))
                .let { if (it < 0) 0 else it + 1 }
            val prefixOnLine = answerText.substring(lineStart, range.first)
            val followedByDotOrParen = range.last + 1 < answerText.length &&
                (answerText[range.last + 1] == '.' || answerText[range.last + 1] == ')')
            val isListBullet = followedByDotOrParen && LIST_PREFIX_REGEX.matches(prefixOnLine)
            if (isListBullet) continue

            val rawToken = match.value.trim()
            val candidateForms = normalizeSingleNumberForms(rawToken)

            // Kleine strukturelle Zaehlwoerter (0-3 ohne Einheit) sind oft sprachlich ("zwei Gruende"),
            // aber Jahreszahlen, Prozente, Waehrungen und Zahlen >= 4 werden streng geprueft.
            val numericVal = candidateForms.firstNotNullOfOrNull { it.toDoubleOrNull() }
            val hasUnit = rawToken.any { it == '%' || it == '€' || it == '$' || it == '£' || it.isLetter() }
            if (!hasUnit && numericVal != null && numericVal in 0.0..3.0 && !rawToken.contains('.') && !rawToken.contains(',')) {
                continue
            }

            val grounded = candidateForms.any { form -> form in sourceNormalizedNumbers }
            if (!grounded) {
                flagged.add(
                    UnverifiedNumberSpan(
                        text = rawToken,
                        range = range,
                        reason = tr("warn_reason", rawToken)
                    )
                )
            }
        }
        return flagged
    }

    /**
     * Normalisiert kombinierte Zitate wie [1, 2] zu [1] [2] und bereinigt erfundene Zitatnummern
     * (z. B. [9], wenn es nur 4 gepruefte Claims gibt).
     */
    fun sanitizeCitations(answerText: String, maxCitationIndex: Int): String {
        // Zuerst Mehrfach-Zitate wie [1, 2] oder [1; 3] in einzelne klickbare [1] [2] aufteilen
        val expanded = MULTI_CITATION_REGEX.replace(answerText) { m ->
            val validIndices = DIGIT_GROUP_REGEX.findAll(m.groupValues[1])
                .mapNotNull { it.value.toIntOrNull() }
                .filter { it in 1..maxCitationIndex }
                .distinct()
                .toList()
            validIndices.joinToString(" ") { "[$it]" }
        }
        val cleaned = SINGLE_CITATION_REGEX.replace(expanded) { m ->
            val idx = m.groupValues[1].toIntOrNull() ?: 0
            if (idx in 1..maxCitationIndex) m.value else ""
        }
        // Doppelte Leerzeichen & Leerzeichen vor Satzzeichen nach dem Entfernen ungueltiger Zitate bereinigen
        return cleaned
            .replace(DOUBLE_SPACE_REGEX, " ")
            .replace(SPACE_BEFORE_PUNCT_REGEX, "$1")
            .trim()
    }

    private fun claimSupportedBy(claim: String, quote: String, chunk: TextChunk): Boolean {
        val claimStems = contentStems(claim)
        // Ein bis zwei Stichwoerter ("Einflussfaktoren") sind keine Aussage
        if (claimStems.size < 3 || quote.split(' ').size < 5) return false
        // Fragen ("Sie möchten wissen, …?") behaupten nichts
        if (quote.trimEnd().endsWith("?") || claim.trimEnd().endsWith("?")) return false
        val chunkStems = contentStems(chunk.title + " " + chunk.rawText).toSet()
        val quoteStems = contentStems(quote).toSet()
        val inChunk = claimStems.count { it in chunkStems }.toFloat() / claimStems.size
        val inQuote = claimStems.count { it in quoteStems }
        return inChunk >= 0.6f && inQuote >= 2
    }

    /**
     * Haelt die Antwort an den geprueften Fakten fest: Satz fuer Satz muss der Wortlaut ueberwiegend aus dem
     * zitierten Fakt und seiner Quelle stammen. Saetze ohne Nummer bekommen die passende, wenn eine klar passt,
     * sonst fliegen sie raus. Abgeschriebene Zitate und Kennzeichnungen wie "[1 QUELLE]" werden entfernt.
     * Bleibt nichts uebrig, stehen die Fakten selbst als Antwort da.
     */
    /**
     * Fakten aus [claims], deren Inhalt in [text] noch nicht steht: ein Satz der Antwort deckt einen Fakt ab, wenn er
     * mindestens 70 % seiner Inhaltswoerter enthaelt. Inhaltlich gleiche Fakten kommen nur einmal.
     */
    fun notYetCovered(text: String, claims: List<VerifiedClaim>): List<VerifiedClaim> {
        val sentences = text.lines().filter { !it.startsWith("#") }
            .flatMap { SENTENCE_SPLIT_REGEX.split(it) }
            .map { contentStems(SINGLE_CITATION_REGEX.replace(it, " ")).toSet() }
            .filter { it.isNotEmpty() }
            .toMutableList()
        val out = ArrayList<VerifiedClaim>()
        for (vc in claims) {
            val st = contentStems(vc.claim).toSet()
            if (st.isEmpty()) continue
            if (sentences.any { s -> st.count { it in s }.toFloat() / st.size >= 0.7f }) continue
            out.add(vc)
            sentences.add(st)
        }
        return out
    }

    fun groundAnswer(
        answer: String,
        claims: List<VerifiedClaim>,
        extraVocab: String = "",
        onDrop: (String) -> Unit = {},
        minShare: Float = 0f
    ): String {
        if (claims.isEmpty()) return answer
        val refs = claims.associate { vc ->
            vc.citationIndex to contentStems("${vc.claim} ${vc.exactQuote} ${vc.chunk.title} ${vc.chunk.rawText}").toSet()
        }
        fun ratio(stems: List<String>, ref: Set<String>) = stems.count { it in ref }.toFloat() / stems.size
        // Verunglueckte Umformulierungen kleiner Modelle: ein Quellwort mit angehaengtem Unsinn ("Solarspeicherung"
        // aus "Solarspeicher") und dazu ein weiteres langes Wort, das in keiner Quelle steht. Gewoehnliche Woerter
        // wie "zusammenfassend" allein loesen nichts aus, so bleiben fluessige Saetze groesserer Modelle stehen.
        val allWords = (claims.flatMap { tokenizeWords("${it.claim} ${it.chunk.title} ${it.chunk.rawText}") } + tokenizeWords(extraVocab))
            .filter { it.length >= 4 }.toSet()
        val vocab = allWords.filter { it.length >= 6 }
        val byPrefix = vocab.groupBy { it.take(4) }
        // Quellwort mit angehaengtem Unsinn: "Solarspeichert", "Solarspeicherung". Beugungs- und Ableitungsendungen
        // ("Anlagen", "technische") zaehlen nicht.
        fun oddSuffix(w: String): Boolean = byPrefix[w.take(4)].orEmpty().any { v ->
            v.length >= 8 && w.length > v.length && w.startsWith(v) && (w.length - v.length) in 1..5 &&
                w.substring(v.length) !in WORD_ENDINGS
        }
        // Gebeugte Form eines Quellwortes ("Solarspeichers" zu "Solarspeicher") gilt als bekannt
        fun knownForm(w: String): Boolean = w in allWords ||
            WORD_ENDINGS.any { e -> w.length > e.length + 3 && w.endsWith(e) && w.dropLast(e.length) in allWords } ||
            byPrefix[w.take(4)].orEmpty().any { v -> v.startsWith(w) && v.length - w.length <= 3 }
        // Neu zusammengesetztes Wort aus Quellwoertern, das so in keiner Quelle steht ("Solarnetzbetreiberanlagen")
        fun inventedCompound(w: String): Boolean = w.length >= 12 && vocab.any { v -> v.length >= 6 && w.contains(v) }
        // Tippfehler-Abwandlung eines langen Quellwortes: ein Buchstabe mehr, weniger oder anders ("Solarsspeicher")
        // Kuerzere Woerter (7 bis 9 Buchstaben) nur bei einem vertauschten Buchstaben gleicher Laenge ("Speichar")
        fun oneEditFromSource(w: String): Boolean = w.length >= 7 && byPrefix[w.take(4)].orEmpty().any { v ->
            v != w && if (w.length >= 10) v.length >= 10 && kotlin.math.abs(v.length - w.length) <= 1 && editDistanceAtMostOne(v, w)
            else v.length == w.length && editDistanceAtMostOne(v, w)
        }
        fun garbled(s: String): Boolean {
            val unknown = tokenizeWords(s).filter { it.length >= 7 && it.all(Char::isLetter) && !knownForm(it) }.distinct()
            if (unknown.any(::oddSuffix) || unknown.any(::oneEditFromSource) || unknown.count(::inventedCompound) >= 2) return true
            // Dieselbe Folge von vier Woertern zweimal in einem Satz: das Modell hat sich wiederholt
            val words = tokenizeWords(SINGLE_CITATION_REGEX.replace(s, " "))
            return words.windowed(4).groupingBy { it }.eachCount().values.any { it >= 2 }
        }

        // Woertlich abgeschriebenes Satzende, das in der Quelle mitten im Satz beginnt ("Team und ermöglicht den
        // Zugang …" aus "… einem festen Team und ermöglicht …"). Auch mit vorangestelltem Artikel ("Die Risiko… dient, ist …").
        val sources = claims.map { joinSoftBreaks(it.chunk.rawText) }.distinct()
        // null: kommt in keiner Quelle vor; true: nur mitten in Quellsaetzen; false: mindestens einmal als Satzanfang
        fun onlyMidSentence(fragment: String): Boolean? {
            var found = false
            for (src in sources) {
                var idx = src.indexOf(fragment)
                while (idx >= 0) {
                    found = true
                    var p = idx - 1
                    while (p >= 0 && src[p] == ' ') p--
                    val mid = p >= 0 && (src[p].isLetterOrDigit() || src[p] == ',') && run {
                        var w = p
                        while (w >= 0 && src[w].isLetter()) w--
                        src.substring(w + 1, p + 1).lowercase() !in SENTENCE_CONNECTORS
                    }
                    if (!mid) return false
                    idx = src.indexOf(fragment, idx + 1)
                }
            }
            return if (found) true else null
        }
        fun quoteFragment(s: String): Boolean {
            val bare = SINGLE_CITATION_REGEX.replace(s, " ").replace(DOUBLE_SPACE_REGEX, " ").trim().trimEnd('.', '!', '?', ' ')
            val words = bare.split(' ')
            if (words.size < 5) return false
            onlyMidSentence(bare)?.let { return it }
            // Vorangestellter Artikel vor einem Satzende aus der Quelle ("Die Netzstabilität … sichert, ist …")
            return words.first().lowercase() in LEADING_ARTICLES && words.size >= 6 &&
                onlyMidSentence(words.drop(1).joinToString(" ")) == true
        }

        // Wortlaut-Pruefung: weicht ein Satz zu weit vom Wortlaut der zitierten Quelle ab (Anteil gemeinsamer Wortpaare
        // unter minShare), steht dort der gepruefte Quellsatz selbst mit seiner Nummer. Kleine Modelle bekommen eine
        // strengere Grenze als grosse.
        val byCitation = claims.associateBy { it.citationIndex }
        fun pairs(text: String): Set<String> =
            tokenizeWords(SINGLE_CITATION_REGEX.replace(text, " ")).zipWithNext { a, b -> "$a $b" }.toSet()
        fun quoteOf(vc: VerifiedClaim): String =
            joinSoftBreaks(vc.exactQuote).replace('\n', ' ').replace(DOUBLE_SPACE_REGEX, " ").trim().trimEnd('.', '!', '?', ' ') +
                " [${vc.citationIndex}]."
        fun bestCited(sentence: String, cites: List<Int>): VerifiedClaim? {
            val own = pairs(sentence)
            return cites.mapNotNull { byCitation[it] }.maxByOrNull { vc -> own.count { it in pairs(vc.exactQuote) } }
        }
        fun keepOrQuote(sentence: String, cites: List<Int>): String {
            if (minShare <= 0f) return sentence
            val own = pairs(sentence)
            if (own.isEmpty()) return sentence
            val best = bestCited(sentence, cites) ?: return sentence
            val share = own.count { it in pairs(best.exactQuote) }.toFloat() / own.size
            if (share >= minShare) return sentence
            onDrop("Wortlaut statt Umformulierung: $sentence")
            return quoteOf(best)
        }
        // Satz am Anfang der Antwort oder eines Abschnitts, der mit "Sie/Es/Diese …" auf etwas verweist, das davor
        // nicht steht: durch den zitierten Quellsatz ersetzen, oder weglassen, wenn auch dieser so beginnt
        fun startsWithPronoun(s: String): Boolean =
            s.trimStart('*', ' ', '"', '„').substringBefore(' ').lowercase().trimEnd(',', ':') in OPENING_PRONOUNS
        fun fixDangling(sentence: String, cites: List<Int>): String? {
            if (!startsWithPronoun(sentence)) return sentence
            val best = bestCited(sentence, cites)
            if (best == null || startsWithPronoun(joinSoftBreaks(best.exactQuote))) {
                onDrop("Bezug fehlt: $sentence")
                return null
            }
            onDrop("Bezug fehlt, Wortlaut statt Umformulierung: $sentence")
            return quoteOf(best)
        }

        val cleaned = answer
            // Abgeschriebener Eingabekopf samt Titel bis zur naechsten Quellennummer
            .replace(PROMPT_LEAK_SEGMENT_REGEX, " ")
            .replace(HEADING_INLINE_REGEX, "$1\n## ")
            .replace(BADGE_REGEX, "")
            .replace(LABELED_QUOTE_REGEX, "")
            .replace(INLINE_QUOTE_REGEX) { m ->
                val inner = m.groupValues[1]
                val found = claims.any { vc -> normalizeText(vc.chunk.rawText).contains(normalizeText(inner)) }
                if (found) m.value else ""
            }

        val out = StringBuilder()
        val seenSentences = ArrayList<Pair<Set<String>, String>>()
        val superseded = LinkedHashSet<String>()
        var kept = 0
        var sectionStart = true
        val cleanedLines = cleaned.lines()
        for ((li, line) in cleanedLines.withIndex()) {
            val t = line.trim()
            if (t.isEmpty()) { out.append('\n'); continue }
            // Ueberschrift ohne "##" (groessere Modelle): kurze Zeile ohne Satzzeichen und Nummer, danach folgt Text
            val plainHeading = !t.startsWith("#") && t.split(' ').size <= 6 && t.last() !in ".!?:;,]" &&
                t.first().isUpperCase() && !SINGLE_CITATION_REGEX.containsMatchIn(t) &&
                cleanedLines.drop(li + 1).any { it.isNotBlank() }
            if (plainHeading) {
                out.append("## ").append(t.trimEnd('*', ' ').trimStart('*', ' ')).append('\n')
                sectionStart = true
                continue
            }
            if (t.startsWith("#")) {
                // Ueberschrift: einheitlich "## ", ohne Quellennummern
                val title = SINGLE_CITATION_REGEX.replace(t.trimStart('#'), "").replace(DOUBLE_SPACE_REGEX, " ").trim()
                if (title.isNotEmpty()) out.append("## ").append(title).append('\n')
                sectionStart = true
                continue
            }
            val keptSentences = SENTENCE_SPLIT_REGEX.split(t).mapNotNull { raw ->
                val s = raw.trim()
                val cites = SINGLE_CITATION_REGEX.findAll(s).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it in refs }.toList()
                val stems = contentStems(SINGLE_CITATION_REGEX.replace(s, " "))
                if (stems.isEmpty()) return@mapNotNull null
                if (PROMPT_LEAK_REGEX.containsMatchIn(s) || READER_ADDRESS_REGEX.containsMatchIn(s)) {
                    onDrop("Eingabekopf oder Anrede: $s"); return@mapNotNull null
                }
                if (garbled(s)) {
                    // Strenge Pruefung: der zitierte Quellsatz statt des verunglueckten Satzes
                    val q = if (minShare > 0f && cites.isNotEmpty()) bestCited(s, cites) else null
                    if (q == null || startsWithPronoun(joinSoftBreaks(q.exactQuote))) { onDrop("verunglückt: $s"); return@mapNotNull null }
                    onDrop("verunglückt, Wortlaut statt Umformulierung: $s")
                    return@mapNotNull quoteOf(q)
                }
                if (quoteFragment(s)) { onDrop("Bruchstück: $s"); return@mapNotNull null }
                if (cites.isNotEmpty()) {
                    val ref = cites.flatMap { refs[it].orEmpty() }.toSet()
                    if (ratio(stems, ref) >= 0.5f) return@mapNotNull keepOrQuote(moveLeadingCitations(s), cites)
                }
                // Ohne (passende) Nummer: den Fakt suchen, auf den sich der Satz klar stuetzt
                val best = refs.maxByOrNull { ratio(stems, it.value) } ?: return@mapNotNull null
                if (ratio(stems, best.value) < 0.6f) { onDrop("ohne Beleg: $s"); return@mapNotNull null }
                val bare = SINGLE_CITATION_REGEX.replace(s, "").replace(DOUBLE_SPACE_REGEX, " ").trim()
                val end = bare.lastOrNull()
                keepOrQuote(if (end == '.' || end == '!' || end == '?') bare.dropLast(1).trimEnd() + " [${best.key}]" + end
                else "$bare [${best.key}]", listOf(best.key))
            }.mapNotNull { raw ->
                // Aufzaehlungszeichen aus der Quelle ("• Mehr Forschung …", "*Viele Geraete …") gehoeren nicht in den Satz
                val s = raw.replace(LEADING_BULLET_REGEX, "").ifEmpty { return@mapNotNull null }
                val cites = SINGLE_CITATION_REGEX.findAll(s).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it in refs }.toList()
                // "…, die zwar oft in Handbüchern nachzulesen sind." ohne das "aber": der Quellsatz mit beiden Teilen
                val whole = if (HALF_CONCESSION_REGEX.containsMatchIn(s) && !CONCESSION_COUNTER_REGEX.containsMatchIn(s)) {
                    val q = bestCited(s, cites)
                    if (q != null && CONCESSION_COUNTER_REGEX.containsMatchIn(q.exactQuote)) {
                        onDrop("halber Satz, Wortlaut statt Umformulierung: $s"); quoteOf(q)
                    } else { onDrop("halber Satz: $s"); return@mapNotNull null }
                } else s
                if (!sectionStart) return@mapNotNull whole
                fixDangling(whole, cites)?.also { sectionStart = false }
            }.filter { sentence ->
                // Inhaltlich gleiche Saetze nur einmal
                val plain = SINGLE_CITATION_REGEX.replace(sentence, " ")
                val st = contentStems(plain).toSet()
                val nums = FINDING_NUMBER_REGEX.findAll(plain).map { it.value }.toList()
                // Derselbe Befund anders formuliert: mindestens drei gleiche Zahlen (mit Wiederholung, "8 kWp ... 8 kWh")
                // und deutlich gleicher Wortschatz
                fun sameFinding(prev: Set<String>, text: String): Boolean {
                    if (nums.size < 3) return false
                    val other = FINDING_NUMBER_REGEX.findAll(SINGLE_CITATION_REGEX.replace(text, " ")).map { it.value }
                        .groupingBy { it }.eachCount()
                    val common = nums.groupingBy { it }.eachCount().entries.sumOf { (n, c) -> minOf(c, other[n] ?: 0) }
                    if (common < 3) return false
                    val inter = prev.intersect(st).size.toFloat()
                    return inter / (prev.size + st.size - inter).coerceAtLeast(1f) >= 0.3f
                }
                // Fast gleich, oder ganz in einem frueheren Satz enthalten, oder derselbe Befund ohne Neues
                val dup = seenSentences.any { (prev, text) ->
                    val inter = prev.intersect(st).size.toFloat()
                    inter / (prev.size + st.size - inter).coerceAtLeast(1f) >= 0.75f ||
                        (st.size >= 3 && inter / st.size >= 0.85f) ||
                        (st.size <= prev.size && sameFinding(prev, text))
                }
                if (dup) { onDrop("doppelt: $sentence"); return@filter false }
                // Ein frueherer Satz steckt ganz in diesem vollstaendigeren (oder sagt dasselbe knapper): der fruehere entfaellt
                seenSentences.filter { (prev, text) ->
                    prev.size >= 3 && text !in superseded &&
                        (prev.count { it in st }.toFloat() / prev.size >= 0.85f || (st.size > prev.size && sameFinding(prev, text)))
                }.forEach { (_, text) -> superseded.add(text); onDrop("doppelt (vollständiger später): $text") }
                seenSentences.add(st to sentence)
                true
            }
            if (keptSentences.isNotEmpty()) {
                kept += keptSentences.size
                out.append(keptSentences.joinToString(" ")).append('\n')
            }
        }
        if (kept == 0) return claims.joinToString("\n") { "${it.claim} [${it.citationIndex}]" }

        // Ueberschriften ohne Inhalt entfernen, Leerzeilen zusammenfassen
        var body = out.toString()
        for (s in superseded) {
            body = body.replaceFirst("$s ", "").let { if (it.length == body.length) it.replaceFirst(s, "") else it }
        }
        if (body.isBlank()) return claims.joinToString("\n") { "${it.claim} [${it.citationIndex}]" }
        val lines = body.lines().map { it.trimEnd() }
        val result = ArrayList<String>()
        for ((i, l) in lines.withIndex()) {
            if (l.startsWith("## ")) {
                val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() }
                if (next == null || next.startsWith("## ")) continue
            }
            if (l.isBlank() && (result.isEmpty() || result.last().isBlank())) continue
            result.add(l)
        }
        return result.joinToString("\n").trim()
    }

    /**
     * Satz fuer den Anhang "Weitere Befunde": Wortlaut der Quelle (strenge Pruefung) oder die gepruefte Aussage.
     * null, wenn er ohne Satz davor keinen Bezug hat oder nur ein halber Satz ist.
     */
    fun appendixSentence(vc: VerifiedClaim, verbatim: Boolean): String? {
        val text = (if (verbatim) joinSoftBreaks(vc.exactQuote) else vc.claim)
            .replace('\n', ' ').replace(DOUBLE_SPACE_REGEX, " ").trim().trimEnd('.', '!', '?', ' ')
        if (text.isEmpty()) return null
        if (text.trimStart('"', '„').substringBefore(' ').lowercase().trimEnd(',') in OPENING_PRONOUNS) return null
        if (HALF_CONCESSION_REGEX.containsMatchIn(text) && !CONCESSION_COUNTER_REGEX.containsMatchIn(text)) return null
        if (READER_ADDRESS_REGEX.containsMatchIn(text) || LITERATURE_REGEX.containsMatchIn("$text.")) return null
        return "$text [${vc.citationIndex}]."
    }

    /**
     * Saetze fuer den Anhang in der Reihenfolge von [missing]. Ein Eintrag entfaellt, sobald einer seiner Saetze (auch der
     * mitgenommene Satz davor) inhaltlich schon in [body] oder in einem frueheren Eintrag steht.
     */
    fun appendixSentences(body: String, missing: List<VerifiedClaim>, verbatim: Boolean): List<String> {
        fun stems(s: String) = contentStems(SINGLE_CITATION_REGEX.replace(s, " ")).toSet()
        val seen = SENTENCE_SPLIT_REGEX.split(body.replace('\n', ' ')).map(::stems).filter { it.size >= 3 }.toMutableList()
        val out = ArrayList<String>()
        for (vc in missing) {
            val s = appendixSentence(vc, verbatim) ?: continue
            val parts = SENTENCE_SPLIT_REGEX.split(s).map(::stems).filter { it.isNotEmpty() }
            val repeated = parts.any { p -> seen.any { prev -> p.count { it in prev }.toFloat() / p.size >= 0.85f } }
            if (repeated) continue
            out.add(s)
            seen.addAll(parts.filter { it.size >= 3 })
        }
        return out
    }

    /**
     * Behaelt nur Fakten, die die Antwort zitiert, und nummeriert sie in der Reihenfolge ihres ersten Auftretens
     * neu. Zitiert die Antwort gar nichts, bleibt alles wie es ist.
     */
    fun keepCitedOnly(answer: String, claims: List<VerifiedClaim>): Pair<String, List<VerifiedClaim>> {
        val byIndex = claims.associateBy { it.citationIndex }
        val order = SINGLE_CITATION_REGEX.findAll(answer).mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in byIndex }.distinct().toList()
        if (order.isEmpty()) return answer to claims
        // Eine Nummer je Quelle (Adresse): mehrere Fakten derselben Seite teilen sich die Nummer
        val sourceNo = LinkedHashMap<String, Int>()
        for (old in order) sourceNo.getOrPut(byIndex.getValue(old).chunk.url) { sourceNo.size + 1 }
        val remap = order.associateWith { old -> sourceNo.getValue(byIndex.getValue(old).chunk.url) }
        val text = SINGLE_CITATION_REGEX.replace(answer) { m ->
            remap[m.groupValues[1].toIntOrNull()]?.let { "[$it]" } ?: m.value
        }.replace(REPEATED_CITE_REGEX, "$1")
        return text to order.map { old -> byIndex.getValue(old).copy(citationIndex = remap.getValue(old)) }
    }

    /** Ganzer Satz um [range] herum (hoechstens 450 Zeichen), oder null, wenn kein sauberer Satz zu finden ist. */
    private fun expandToSentence(text: String, range: IntRange?): Pair<String, IntRange>? {
        if (range == null || range.first < 0 || range.last >= text.length) return null
        var start = range.first
        while (start > 0) {
            val c = text[start - 1]
            if ((c == '\n' && !isSoftBreak(text, start - 1)) ||
                ((c == '.' || c == '!' || c == '?') && start < text.length && text[start].isWhitespace()) &&
                !isAbbreviationEnd(text, start - 1)) break
            start--
        }
        var end = range.last
        while (end < text.length - 1) {
            val c = text[end]
            if (c == '\n' && !isSoftBreak(text, end)) { end--; break }
            if ((c == '.' || c == '!' || c == '?') && text[end + 1].isWhitespace() && !isAbbreviationEnd(text, end)) break
            end++
        }
        while (start <= end && text[start].isWhitespace()) start++
        if (end - start + 1 > 450 || end < start) return null
        // Satz laeuft bis ans Ende des Abschnitts und hoert dort ohne Satzzeichen auf: abgeschnitten
        if (end >= text.length - 1 && endsMidSentence(joinSoftBreaks(text.substring(start, end + 1)))) return null
        return joinSoftBreaks(text.substring(start, end + 1)).trim() to (start..end)
    }

    /** Der Satz vor Position [start] im selben Absatz, null am Abschnitts- oder Absatzanfang. */
    private fun previousSentence(text: String, start: Int): Pair<String, IntRange>? {
        var p = start - 1
        while (p >= 0 && text[p] == ' ') p--
        if (p < 0 || (text[p] == '\n' && !isSoftBreak(text, p))) return null
        while (p >= 0 && text[p].isWhitespace()) p--
        if (p < 0) return null
        val prev = expandToSentence(text, p..p) ?: return null
        if (prev.first.length > 300 || prev.first.first().isLowerCase()) return null
        return prev
    }

    /** Satz um [range] laeuft bis ans Ende des Abschnitts, und der Abschnitt hoert dort mitten im Satz auf. */
    private fun runsIntoCut(text: String, range: IntRange?): Boolean {
        if (range == null || range.last >= text.length || !endsMidSentence(joinSoftBreaks(text))) return false
        var end = range.last.coerceAtLeast(0)
        while (end < text.length - 1) {
            val c = text[end]
            if (c == '\n' && !isSoftBreak(text, end)) return false
            if ((c == '.' || c == '!' || c == '?') && text[end + 1].isWhitespace() && !isAbbreviationEnd(text, end)) return false
            end++
        }
        return true
    }

    /** Punkt hinter "z. B", "bzw", "Nr", einzelnen Buchstaben oder Ziffern beendet keinen Satz. */
    private fun isAbbreviationEnd(text: String, dotIdx: Int): Boolean {
        if (text[dotIdx] != '.') return false
        var i = dotIdx - 1
        while (i >= 0 && text[i].isLetterOrDigit()) i--
        val word = text.substring(i + 1, dotIdx)
        return word.length <= 1 || word.all(Char::isDigit) || word.lowercase() in ABBREVIATIONS
    }

    /** "[1] Satz." wird zu "Satz [1]." */
    private fun moveLeadingCitations(s: String): String {
        val m = LEADING_CITES_REGEX.matchEntire(s) ?: return s
        val cites = m.groupValues[1].trim().replace(DOUBLE_SPACE_REGEX, " ")
        val body = m.groupValues[2].trim()
        val end = body.lastOrNull()
        return if (end == '.' || end == '!' || end == '?') body.dropLast(1).trimEnd() + " " + cites + end else "$body $cites"
    }

    private fun contentStems(s: String): List<String> =
        tokenizeWords(s).filter { it.length >= 4 && it !in STOPWORDS && it.any(Char::isLetter) }.map { it.take(5) }

    private data class QuoteMatch(val score: Float, val range: IntRange?)

    private fun findQuoteInChunk(quote: String, chunkText: String): QuoteMatch {
        // 1. Exakter Case-Insensitive-Match
        val directIdx = chunkText.indexOf(quote, ignoreCase = true)
        if (directIdx >= 0) {
            return QuoteMatch(1.0f, directIdx until (directIdx + quote.length).coerceAtMost(chunkText.length))
        }

        // 2. Normalisierter Sliding-Window-Token-Match
        val quoteTokens = tokenizeWords(quote)
        if (quoteTokens.isEmpty()) return QuoteMatch(0f, null)

        val wordMatches = WORD_REGEX.findAll(chunkText).toList()
        if (wordMatches.isEmpty()) return QuoteMatch(0f, null)

        val windowSize = quoteTokens.size.coerceAtMost(wordMatches.size)
        val quoteSet = quoteTokens.toSet()

        var bestScore = 0f
        var bestRange: IntRange? = null

        for (i in 0..(wordMatches.size - windowSize)) {
            val endIdx = (i + windowSize - 1).coerceAtMost(wordMatches.lastIndex)
            val windowTokens = (i..endIdx).map { wordMatches[it].value.lowercase() }
            val windowSet = windowTokens.toSet()

            val intersection = quoteSet.intersect(windowSet).size
            val recall = intersection.toFloat() / quoteSet.size
            // Reihenfolge-Bonus
            val orderedBonus = if (windowTokens.firstOrNull() == quoteTokens.firstOrNull()) 0.05f else 0f
            val score = (recall + orderedBonus).coerceAtMost(0.99f)

            if (score > bestScore) {
                bestScore = score
                bestRange = wordMatches[i].range.first..wordMatches[endIdx].range.last
            }
        }
        return QuoteMatch(bestScore, bestRange)
    }

    private fun findCorroboratingDomains(
        claim: String,
        primaryChunk: TextChunk,
        pool: List<PrecomputedChunk>,
        sameTopicThreshold: Float
    ): List<String> {
        val claimTokens = tokenizeWords(claim).filter { it.length >= 4 }.toSet()
        val claimNumbers = extractNormalizedNumberTokens(claim)
        val domains = LinkedHashSet<String>()

        for (other in pool) {
            if (other.chunk.domain == primaryChunk.domain) continue

            val primaryVec = primaryChunk.embedding
            val otherVec = other.chunk.embedding
            val vecMatch = if (primaryVec != null && otherVec != null) {
                OnnxEmbedder.cosineSimilarity(primaryVec, otherVec) >= sameTopicThreshold
            } else {
                false
            }

            // Ergaenzender lexikalischer & Zahlen-Check
            val overlap = if (claimTokens.isNotEmpty()) {
                claimTokens.intersect(other.keyTokens).size.toFloat() / claimTokens.size
            } else 0f

            val numbersCompatible = claimNumbers.isEmpty() ||
                claimNumbers.any { it in other.normalizedNumbers }

            if ((vecMatch || overlap >= 0.45f) && numbersCompatible) {
                domains.add(other.chunk.domain)
            }
        }
        return domains.toList()
    }

    private fun extractNormalizedNumberTokens(text: String): Set<String> {
        val set = HashSet<String>()
        for (m in RAW_NUMBER_REGEX.findAll(text)) {
            set.addAll(normalizeSingleNumberForms(m.value))
        }
        return set
    }

    private fun normalizeSingleNumberForms(token: String): Set<String> {
        val digitsOnly = token.replace(NON_NUM_PUNCT_REGEX, "").trim('.', ',')
        if (digitsOnly.isBlank()) return emptySet()
        val forms = HashSet<String>()
        forms.add(digitsOnly)

        // Gemischt deutsch "1.200,50" -> "1200.50" & "1200.5"
        if (GERMAN_MIXED_NUM_REGEX.matches(digitsOnly)) {
            val canonical = digitsOnly.replace(".", "").replace(",", ".")
            addCanonicalForms(forms, canonical)
        }
        // Gemischt englisch "1,200.50" -> "1200.50" & "1200.5"
        if (ENGLISH_MIXED_NUM_REGEX.matches(digitsOnly)) {
            val canonical = digitsOnly.replace(",", "")
            addCanonicalForms(forms, canonical)
        }
        // Deutsches Tausender-Format "1.200" oder "1.200.000" -> "1200"
        if (GERMAN_THOUSANDS_REGEX.matches(digitsOnly)) {
            forms.add(digitsOnly.replace(".", ""))
        }
        // Englisches Tausender-Format "1,200" oder "1,200,000" -> "1200"
        if (ENGLISH_THOUSANDS_REGEX.matches(digitsOnly)) {
            forms.add(digitsOnly.replace(",", ""))
        }
        // Einfache Dezimalzahl ("3,50" <-> "3.50" <-> "3.5")
        if (SIMPLE_DECIMAL_REGEX.matches(digitsOnly)) {
            val canonical = digitsOnly.replace(',', '.')
            addCanonicalForms(forms, canonical)
        }
        return forms
    }

    private fun addCanonicalForms(forms: MutableSet<String>, canonicalDot: String) {
        forms.add(canonicalDot)
        forms.add(canonicalDot.replace('.', ','))
        if ('.' in canonicalDot) {
            val trimmed = canonicalDot.trimEnd('0').trimEnd('.')
            if (trimmed.isNotEmpty()) {
                forms.add(trimmed)
                forms.add(trimmed.replace('.', ','))
            }
        }
    }

    private fun normalizeText(s: String): String =
        s.lowercase().replace(NON_ALNUM_SPACE_REGEX, " ").replace(WHITESPACE_REGEX, " ").trim()

    private fun tokenizeWords(s: String): List<String> =
        WORD_REGEX.findAll(s.lowercase()).map { it.value }.toList()

    internal companion object {
        val CITATION_BRACKET_REGEX = Regex("\\[\\d+\\]")
        val SINGLE_CITATION_REGEX = Regex("\\[(\\d+)\\]")
        val MULTI_CITATION_REGEX = Regex("\\[(\\d+(?:\\s*[,;]\\s*\\d+)+)\\]")
        val DIGIT_GROUP_REGEX = Regex("\\d+")
        val DOUBLE_SPACE_REGEX = Regex("[ \\t]{2,}")
        val SPACE_BEFORE_PUNCT_REGEX = Regex(" +([.,;:!?])")
        // Matcht beliebige Zahlenlaengen (auch >= 10000), Daten, Prozente und Waehrungen
        val NUMBER_TOKEN_REGEX = Regex(
            """(?<!\w)(?:[€$£]\s?)?\d+(?:[.,]\d+)*(?:\s?(?:%|Prozent|€|EUR|USD|\$|Mio\.?|Mrd\.?|Millionen|Milliarden|km|kg|GB|MB|nm|GHz))?(?!\w)"""
        )
        val LIST_PREFIX_REGEX = Regex("^[\\s*•\\-–—]*$")
        val RAW_NUMBER_REGEX = Regex("\\d+(?:[.,]\\d+)*")
        val NON_NUM_PUNCT_REGEX = Regex("[^0-9.,]")
        val GERMAN_MIXED_NUM_REGEX = Regex("^\\d{1,3}(\\.\\d{3})+,\\d+$")
        val ENGLISH_MIXED_NUM_REGEX = Regex("^\\d{1,3}(,\\d{3})+\\.\\d+$")
        val GERMAN_THOUSANDS_REGEX = Regex("^\\d{1,3}(\\.\\d{3})+$")
        val ENGLISH_THOUSANDS_REGEX = Regex("^\\d{1,3}(,\\d{3})+$")
        val SIMPLE_DECIMAL_REGEX = Regex("^\\d+[.,]\\d+$")
        val NON_ALNUM_SPACE_REGEX = Regex("[^\\p{L}\\p{N}\\s]")
        val WHITESPACE_REGEX = Regex("\\s+")
        val WORD_REGEX = Regex("[\\p{L}\\p{N}]+")
        val ABBREVIATIONS = setOf("bzw", "ca", "vgl", "nr", "usw", "etc", "inkl", "ggf", "evtl", "bspw", "sog", "zb", "dr", "prof", "abs", "art", "bzgl", "ua", "mio", "mrd", "jh", "st",
            "abb", "abschn", "tab", "kap", "bd", "hrsg", "aufl", "anm", "fig", "ff", "ebd", "zit", "jg", "vol", "pp", "ch", "sect", "eq", "resp")
        val SELF_PROMO_REGEX = Regex(
            "\\b(?:unser(?:e[mnrs]?)?|in diesem|dieser)\\s+(?:Artikel|Blog|Beitrag|Blogbeitrag|Ratgeber|Leitfaden|Whitepaper|Newsletter|Team|Angebot|Produkt|Software|Tool|Webinar|E-Book|Kunden)\\b|" +
                "\\b(?:our|this)\\s+(?:article|blog|post|guide|team|product|software|tool|webinar|newsletter)\\b",
            RegexOption.IGNORE_CASE
        )
        /** Anrede der Leser ("So bleibt Ihr Betrieb …") oder Leistungsangebot ("Unser Ansatz …"): Werbung, kein Fakt. */
        val READER_ADDRESS_REGEX = Regex(
            "(?<=[a-zäöüß,] )(?:Ihr(?:e[mnrs]?)?|Ihnen|Sie)\\b|" +
                "(?<![\\p{L}])(?:du|dir|dich|dein(?:e[mnrs]?)?|Du|Dein(?:e[mnrs]?)?)(?![\\p{L}])|" +
                "\\bUnser(?:e[mnrs]?)?\\s+(?:Ansatz|Angebot|Leistung(?:en)?|Beratung|Service|Seminar(?:e)?|Workshop(?:s)?|Unternehmen|Expert(?:en|innen|ise)|Erfahrung|Kurs(?:e)?|Lösung(?:en)?|Team)\\b"
        )
        /** Kopfzeilen der Abschnitte aus der Eingabe des Modells ("CHUNK_ID=12 | QUELLE=example.org | TITEL=…") */
        val PROMPT_LEAK_REGEX = Regex("(?i)\\b(?:CHUNK_ID|QUELLE|TITEL|SOURCE|TITLE)\\s*=")
        val PROMPT_LEAK_SEGMENT_REGEX = Regex("(?i)\\bCHUNK_ID\\s*=[^\\n]*?(?:\\[\\d+]\\.?|$)", RegexOption.MULTILINE)
        /** Literaturangabe statt Aussage ("Beispielverlag, S. 17 ff.") */
        val LITERATURE_REGEX = Regex("\\bS\\.\\s?\\d+(?:\\s?ff?\\.?|\\s?[-–]\\s?\\d+)?\\s*\\.?\\s*$|\\bISBN\\b|\\((?:Hrsg|Hg)\\.\\)|\\bHrsg\\.\\s|(?:^|\\s)In:\\s|https?://|\\bwww\\.\\w|\\b(?:Abgerufen am|Zugriff am|Zugegriffen am|Retrieved|Accessed)\\b|^\\(\\d{4}[a-z]?[,)]|\\(\\d{4}[a-z]?(?:,\\s[^)]{1,25})?\\)\\.\\s+\\p{Lu}|^\\p{Lu}[\\p{L}'-]+,\\s(?:\\p{Lu}\\.\\s?)+(?:[,&]|u\\.|und|\\(\\d{4})")
        /** "zwar" ohne Gegenstueck im selben Satz: die Aussage ist nur halb */
        val HALF_CONCESSION_REGEX = Regex("(?i)\\b(?:zwar|einerseits)\\b")
        val CONCESSION_COUNTER_REGEX = Regex("(?i)\\b(?:aber|doch|jedoch|allerdings|andererseits|dennoch|trotzdem)\\b")
        val HEADING_INLINE_REGEX = Regex("([^\\n])[ \\t]*#{2,}[ \\t]+")
        val OPEN_PAREN_FRAGMENT = Regex("^[^(]*\\)")
        /** Beugungs- und Ableitungsendungen, die an ein Quellwort angehaengt kein Unsinn sind. */
        val WORD_ENDINGS = setOf(
            "e", "n", "s", "r", "m", "en", "er", "es", "em", "ern", "ens", "nen", "ell", "elle", "ellen", "eller", "elles",
            "isch", "ische", "ischen", "ischer", "lich", "liche", "lichen", "licher", "ung", "ungen", "ig", "ige", "igen",
            "in", "innen", "te", "ten", "et", "ete", "eten", "st", "ste", "sten", "al", "ale", "alen", "iv", "ive", "iven"
        )
        /** Woerter, nach denen ein Satz in der Quelle eigenstaendig weitergehen kann ("Denn Qualität zählt …"). */
        val SENTENCE_CONNECTORS = setOf("denn", "doch", "aber", "auch", "zudem", "daher", "deshalb", "außerdem", "so", "und",
            "jedoch", "allerdings", "dabei", "somit", "also", "but", "and", "so", "however", "also")
        val OPENING_PRONOUNS = setOf("sie", "er", "es", "diese", "dies", "dieser", "dieses", "they", "it", "this", "these")
        val LEADING_ARTICLES = setOf("die", "der", "das", "den", "dem", "des", "ein", "eine", "einer", "diese", "dieser", "dieses", "the", "a", "an")
        /** Seitenbausteine statt Aussagen: Impressum, Copyright, Datenschutz und aehnliches am Satzanfang. */
        val BOILERPLATE_START = Regex("^(?:Impressum|Copyright|©|Datenschutz|Kontakt|Bildnachweis|Herausgeber(?:in)?|Redaktion|Haftungsausschluss|Inhaltsverzeichnis)\\b", RegexOption.IGNORE_CASE)
        val REPEATED_CITE_REGEX =Regex("(\\[\\d+])(?:\\s*\\1)+")
        val LEADING_CITES_REGEX = Regex("^((?:\\[\\d+]\\s*)+)(\\S.*)$")
        val BADGE_REGEX = Regex("\\[(?:\\d+ QUELLEN?|MEHRERE QUELLEN|EINZELQUELLE)\\]", RegexOption.IGNORE_CASE)
        val LABELED_QUOTE_REGEX = Regex("\\(?\\s*(?:Zitat|Beleg|Quelle|Quote)\\s*:?\\s*[„\"“][^“”\"]*[“”\"]\\s*\\)?", RegexOption.IGNORE_CASE)
        val INLINE_QUOTE_REGEX = Regex("[„\"“]([^“”\"]{25,})[“”\"]")
        /** Aufzaehlungszeichen am Satzanfang: *, •, ·, ▪, ►, ‣, oder ein Strich mit Leerzeichen danach. */
        val LEADING_BULLET_REGEX = Regex("^(?:[*•·▪►‣◦]+\\s*|[-–—]\\s+)+")

        /** Zahlen eines Satzes (35, 1.6, 2024) fuer den Vergleich gleicher Befunde. */
        val FINDING_NUMBER_REGEX = Regex("\\d+(?:[.,]\\d+)?")

        // Satzenden auch ohne Grossbuchstaben (Arabisch, Hebraeisch, Hindi, Thai, CJK) und ohne Leerzeichen (。！？)
        val SENTENCE_SPLIT_REGEX = Regex("(?<=\\p{L}{2}[.!?]|[\\])\\d][.!?])\\s+(?=[\\p{Lu}\\[(„\"])|(?<=[。！？])\\s*(?=\\S)|" +
            "(?<=[.!?؟।])\\s+(?=[\\u0600-\\u06FF\\u0590-\\u05FF\\u0900-\\u097F\\u0E00-\\u0E7F\\u3400-\\u9FFF\\u3040-\\u30FF\\uAC00-\\uD7AF])")
        // Fuellwoerter ab 4 Buchstaben (Deutsch, Englisch), die nichts ueber den Inhalt sagen
        val STOPWORDS = setOf(
            "dass", "eine", "einer", "einem", "einen", "eines", "sind", "wird", "werden", "wurde", "wurden", "auch", "sich",
            "oder", "aber", "nicht", "noch", "dies", "diese", "dieser", "dieses", "diesem", "diesen", "durch", "über",
            "unter", "nach", "beim", "sowie", "kann", "können", "muss", "müssen", "soll", "sollen", "haben", "hat",
            "hatte", "sein", "seine", "ihre", "ihren", "ihrem", "ihrer", "deren", "dessen", "welche", "welcher", "welches",
            "mehr", "sehr", "dabei", "damit", "davon", "dazu", "daher", "jedoch", "sowohl", "zwischen", "allem", "alle",
            "allen", "aller", "alles", "zudem", "etwa", "bzw", "wobei", "wenn", "weil", "ohne", "gegen", "bereits",
            "oft", "häufig", "insbesondere", "beispielsweise", "zeigt", "zeigen", "gibt", "geben", "wichtig", "wichtige",
            "that", "this", "with", "from", "have", "been", "were", "their", "there", "which", "about", "into", "also",
            "such", "than", "then", "they", "these", "those", "will", "would", "could", "should", "other", "more",
            "most", "some", "only", "each", "very", "when", "where", "while", "what"
        )
    }
}
