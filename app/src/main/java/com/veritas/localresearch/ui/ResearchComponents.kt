package com.veritas.localresearch.ui

import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
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
fun PipelineProgressCard(progress: PipelineProgress) {
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
                    text = "${progress.stageTitle} (Runde ${progress.currentRound}/${progress.maxRounds})",
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
                AssistChip(onClick = {}, label = { Text(if (n == 1) "1 Quelle" else "$n Quellen") })
                AssistChip(onClick = {}, label = { Text("${progress.chunkCount} Abschnitte") })
                if (progress.verifiedClaimsCount > 0 || progress.rejectedClaimsCount > 0) {
                    AssistChip(
                        onClick = {},
                        label = { Text("${progress.verifiedClaimsCount} geprüft / ${progress.rejectedClaimsCount} verworfen") }
                    )
                }
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
                    text = "${report.verifiedClaims.size} zitatgeprüfte Fakten ($multiSourceCount mehrfach bestätigt) • " +
                        "${report.rejectedClaimCount} verworfen • ${report.unverifiedNumbers.size} gelbe Warnungen",
                    style = MaterialTheme.typography.labelMedium
                )
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
                Text("Antwort mit Quellen kopieren")
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
                        text = "Gelb markierte Zahlen wurden in keiner geladenen Quelle gefunden. Tippe auf eine Zahl für Details.",
                        style = MaterialTheme.typography.bodySmall,
                        color = WarningYellowText
                    )
                }
            }

            // Quellen-Uebersicht (Click-to-Passage)
            if (report.verifiedClaims.isNotEmpty()) {
                HorizontalDivider()
                Text("Belegte Quellen (Antippen für exakte Textpassage):", style = MaterialTheme.typography.labelLarge)
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
                                        append(" • bestätigt durch +${vc.corroboratingDomains.size} weitere Domain(s)")
                                    } else {
                                        append(" • Einzelquelle")
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
                                "Cross-Source (+${claim.corroboratingDomains.size})"
                            else "Einzelquelle"
                        )
                    }
                )
                AssistChip(onClick = {}, label = { Text("Zitat-Match ${(claim.quoteMatchScore * 100).toInt()}%") })
            }

            Text("Extrahierte Aussage:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(claim.claim, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)

            Text("Exakte Original-Passage (Chunk #${claim.chunk.chunkId}):", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)

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
                Text("Original-Website im Browser öffnen")
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** Antwort als reiner Text ohne Rauten, darunter die Quellen mit Nummer, Titel und Adresse. */
private fun plainCopy(report: ResearchReport): String = buildString {
    append(report.synthesizedAnswer)
    if (report.verifiedClaims.isNotEmpty()) {
        append("\n\nQuellen:\n")
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
    c.pageEnd > c.page -> "S. ${c.page}–${c.pageEnd}"
    else -> "S. ${c.page}"
}

