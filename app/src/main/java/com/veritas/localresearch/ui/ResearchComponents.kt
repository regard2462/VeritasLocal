package com.veritas.localresearch.ui

import androidx.compose.foundation.background
import com.veritas.localresearch.i18n.tr
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import com.veritas.localresearch.domain.LiveEvent
import com.veritas.localresearch.domain.LiveKind
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veritas.localresearch.domain.PipelineProgress
import com.veritas.localresearch.domain.ResearchReport
import com.veritas.localresearch.domain.TrustLevel
import com.veritas.localresearch.domain.UnverifiedNumberSpan
import com.veritas.localresearch.domain.VerifiedClaim

private val CITATION_TAG_REGEX = Regex("\\[(\\d+)\\]")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PipelineProgressCard(
    progress: PipelineProgress,
    liveFlow: kotlinx.coroutines.flow.StateFlow<List<LiveEvent>>? = null
) {
    var showLive by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (progress.isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = VerifiedGreen)
                }
                Text(
                    text = tr("round_of", progress.stageTitle, progress.currentRound, progress.maxRounds),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (progress.detail.isNotBlank()) {
                Text(progress.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val n = progress.scrapedDomains.size
                AssistChip(onClick = {}, label = { Text(if (n == 1) tr("sources_one") else tr("sources_n", n)) })
                AssistChip(onClick = {}, label = { Text(tr("sections_n", progress.chunkCount)) })
                if (progress.verifiedClaimsCount > 0 || progress.rejectedClaimsCount > 0) {
                    AssistChip(
                        onClick = {},
                        label = { Text(tr("checked_rejected", progress.verifiedClaimsCount, progress.rejectedClaimsCount)) }
                    )
                }
            }
            if (liveFlow != null) {
                TextButton(onClick = { showLive = !showLive }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text(if (showLive) tr("live_hide") else tr("live_show"))
                }
                // Nur aufgeklappt wird die Liste gelesen und gezeichnet
                if (showLive) LiveList(liveFlow)
            }
        }
    }
}

/** Live-Liste: neueste Eintraege oben, Seiten und Fakten oeffnen per Tippen die Quelle. */
@Composable
private fun LiveList(liveFlow: kotlinx.coroutines.flow.StateFlow<List<LiveEvent>>) {
    val events by liveFlow.collectAsState()
    val uriHandler = LocalUriHandler.current
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(events.size) { i ->
            val e = events[events.size - 1 - i]
            val (icon, tint) = when (e.kind) {
                LiveKind.SEARCH -> Icons.Default.Search to MaterialTheme.colorScheme.primary
                LiveKind.OPEN -> Icons.Default.Public to MaterialTheme.colorScheme.primary
                LiveKind.LINK -> Icons.Default.Link to MaterialTheme.colorScheme.primary
                LiveKind.FAIL -> Icons.Default.ErrorOutline to MaterialTheme.colorScheme.onSurfaceVariant
                LiveKind.FACT -> Icons.Default.CheckCircle to VerifiedGreen
                LiveKind.REJECT -> Icons.Default.Close to MaterialTheme.colorScheme.error
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (e.url.isNotBlank()) Modifier.clickable { runCatching { uriHandler.openUri(e.url) } } else Modifier),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                Text(e.text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun VerifiedAnswerCard(
    report: ResearchReport,
    onCitationClick: (VerifiedClaim) -> Unit,
    onNumberWarningClick: (UnverifiedNumberSpan) -> Unit
) {
    val multiSourceCount = remember(report) {
        report.verifiedClaims.count { it.trustLevel == TrustLevel.MULTI_SOURCE }
    }

    // Interaktiver Antworttext mit klickbaren [1]-Zitaten & gelben Zahlen-Warnungen (gecacht pro Report)
    val annotated = remember(report, onCitationClick, onNumberWarningClick) {
        val validIndices = report.verifiedClaims.map { it.citationIndex }.toSet()
        buildAnnotatedString {
            val text = report.synthesizedAnswer
            append(text)

            // 1. Gelbe Markierung fuer unbelegte Zahlen
            report.unverifiedNumbers.forEachIndexed { idx, warn ->
                if (warn.range.first >= 0 && warn.range.last < text.length) {
                    addStyle(
                        style = SpanStyle(
                            background = WarningYellowBg,
                            color = WarningYellowText,
                            fontWeight = FontWeight.Bold
                        ),
                        start = warn.range.first,
                        end = warn.range.last + 1
                    )
                    addLink(
                        LinkAnnotation.Clickable("num$idx") { onNumberWarningClick(warn) },
                        warn.range.first,
                        warn.range.last + 1
                    )
                }
            }

            // 2. Zwischenueberschriften fett
            for (r in report.headingRanges) {
                if (r.first >= 0 && r.last < text.length) {
                    addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp), r.first, r.last + 1)
                }
            }

            // 3. Klickbare Inline-Zitate [1], [2], ...
            for (m in CITATION_TAG_REGEX.findAll(text)) {
                val citNum = m.groupValues[1].toIntOrNull() ?: continue
                if (citNum in validIndices) {
                    addStyle(
                        style = SpanStyle(
                            color = CitationBlue,
                            fontWeight = FontWeight.ExtraBold,
                            background = CitationBlue.copy(alpha = 0.12f)
                        ),
                        start = m.range.first,
                        end = m.range.last + 1
                    )
                    report.verifiedClaims.firstOrNull { it.citationIndex == citNum }?.let { vc ->
                        addLink(
                            LinkAnnotation.Clickable("cit$citNum") { onCitationClick(vc) },
                            m.range.first,
                            m.range.last + 1
                        )
                    }
                }
            }
        }
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Verifikations-Statusleiste
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Verified, contentDescription = null, tint = VerifiedGreen, modifier = Modifier.size(18.dp))
                Text(
                    text = tr("status_bar", report.verifiedClaims.size, multiSourceCount, report.rejectedClaimCount, report.unverifiedNumbers.size),
                    style = MaterialTheme.typography.labelMedium
                )
            }
            // Selbst festgelegte Wortzahl deutlich verfehlt: sagen, woran es lag
            if (report.wordTarget > 0) {
                val words = report.synthesizedAnswer.split(' ', '\n').count { w -> w.any { it.isLetterOrDigit() } }
                if (words < report.wordTarget * 8 / 10) {
                    Text(
                        text = tr("len_missed", words, report.wordTarget),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Antwort markier- und kopierbar; Zitate und gelbe Zahlen bleiben antippbar
            SelectionContainer {
                Text(
                    text = annotated,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 24.sp
                    )
                )
            }
            val clipboard = LocalClipboardManager.current
            TextButton(onClick = { clipboard.setText(AnnotatedString(plainCopy(report))) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("copy_answer"))
            }

            // Warnbanner, falls unbelegte Zahlen gefunden wurden
            if (report.unverifiedNumbers.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(WarningYellowBg.copy(alpha = 0.55f))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = WarningYellowText)
                    Text(
                        text = tr("yellow_banner"),
                        style = MaterialTheme.typography.bodySmall,
                        color = WarningYellowText
                    )
                }
            }

            // Quellen-Uebersicht (Click-to-Passage)
            if (report.verifiedClaims.isNotEmpty()) {
                HorizontalDivider()
                Text(tr("cited_sources"), style = MaterialTheme.typography.labelLarge)
                report.verifiedClaims.distinctBy { it.citationIndex }.forEach { vc ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onCitationClick(vc) }
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "[${vc.citationIndex}]",
                            fontWeight = FontWeight.Bold,
                            color = CitationBlue
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(vc.chunk.title, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = buildString {
                                    append(vc.chunk.domain)
                                    if (vc.trustLevel == TrustLevel.MULTI_SOURCE) {
                                        append(" • " + tr("confirmed_by", vc.corroboratingDomains.size))
                                    } else {
                                        append(" • " + tr("single_source"))
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ClickToPassageSheet(
    claim: VerifiedClaim,
    onDismiss: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    val highlightedChunk = remember(claim) {
        buildAnnotatedString {
            val raw = claim.chunk.rawText
            val range = claim.quoteHighlightRange
            if (range != null && range.first >= 0 && range.last < raw.length && range.first <= range.last) {
                append(raw.substring(0, range.first))
                withStyle(SpanStyle(background = WarningYellowBg, color = WarningYellowText, fontWeight = FontWeight.SemiBold)) {
                    append(raw.substring(range.first, range.last + 1))
                }
                append(raw.substring(range.last + 1))
            } else {
                append(raw)
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("[${claim.citationIndex}]", style = MaterialTheme.typography.titleLarge, color = CitationBlue, fontWeight = FontWeight.Bold)
                Text(claim.chunk.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AssistChip(onClick = {}, label = { Text(claim.chunk.domain) })
                pageLabel(claim.chunk)?.let { p -> AssistChip(onClick = {}, label = { Text(p) }) }
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            if (claim.trustLevel == TrustLevel.MULTI_SOURCE)
                                tr("cross_source", claim.corroboratingDomains.size)
                            else tr("single_source")
                        )
                    }
                )
                AssistChip(onClick = {}, label = { Text(tr("quote_match", (claim.quoteMatchScore * 100).toInt())) })
            }

            Text(tr("extracted_claim"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(claim.claim, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)

            Text(tr("orig_passage", claim.chunk.chunkId), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)

            Surface(
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = highlightedChunk,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(14.dp),
                    lineHeight = 22.sp
                )
            }

            if (claim.chunk.sourceType != com.veritas.localresearch.domain.SourceType.LOCAL) Button(
                onClick = {
                    runCatching {
                        uriHandler.openUri(claim.chunk.url)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(tr("open_website"))
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** Antwort als reiner Text ohne Rauten, darunter die Quellen mit Nummer, Titel und Adresse. */
private fun plainCopy(report: ResearchReport): String = buildString {
    append(report.synthesizedAnswer)
    if (report.verifiedClaims.isNotEmpty()) {
        append("\n\n" + tr("sources_label") + "\n")
        report.verifiedClaims.distinctBy { it.citationIndex }.forEach { vc ->
            val where = if (vc.chunk.sourceType == com.veritas.localresearch.domain.SourceType.LOCAL)
                listOfNotNull(vc.chunk.domain, pageLabel(vc.chunk)).joinToString(", ") else vc.chunk.url
            append("[${vc.citationIndex}] ${vc.chunk.title}, $where\n")
        }
    }
}.trim()

/** "S. 4" bzw. "S. 4–5" fuer Abschnitte eigener PDF-Dokumente, sonst null. */
fun pageLabel(c: com.veritas.localresearch.domain.TextChunk): String? = when {
    c.page <= 0 -> null
    c.pageEnd > c.page -> tr("pages_range", c.page, c.pageEnd)
    else -> tr("page", c.page)
}


/** Hinweis: eine oder mehrere Suchmaschinen wollen einen Menschen sehen; Antippen oeffnet den Test. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CaptchaHint(engines: List<com.veritas.localresearch.scraping.SearchEngine>, onSolve: (com.veritas.localresearch.scraping.SearchEngine) -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                tr("captcha_hint", engines.joinToString(", ") { it.displayName }),
                style = MaterialTheme.typography.bodyMedium
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                engines.forEach { e ->
                    TextButton(onClick = { onSolve(e) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text(if (engines.size == 1) tr("captcha_solve") else "${tr("captcha_solve")}: ${e.displayName}")
                    }
                }
            }
        }
    }
}
