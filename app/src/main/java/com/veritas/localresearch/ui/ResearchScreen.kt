package com.veritas.localresearch.ui

import androidx.compose.foundation.layout.*
import com.veritas.localresearch.i18n.tr
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ResearchScreen(viewModel: ResearchViewModel) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("VeritasLocal", fontWeight = FontWeight.Bold)
                        Text(
                            text = buildString {
                                append(state.loadedLlmName ?: tr("no_llm"))
                                if (state.gpuInUse.isNotEmpty()) append(" • GPU")
                                append(" • ")
                                append(if (state.isEmbeddingLoaded) tr("mode_hybrid") else tr("mode_bm25"))
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleModelManager(true) }) {
                        Icon(Icons.Default.Memory, contentDescription = tr("models"))
                    }
                    IconButton(onClick = { viewModel.toggleSettings(true) }) {
                        Icon(Icons.Default.Settings, contentDescription = tr("settings"))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.isModelLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(tr("loading_model"), style = MaterialTheme.typography.bodySmall)
            }

            state.errorMessage?.let { err ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(err, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                        TextButton(onClick = viewModel::clearError) { Text(tr("ok")) }
                    }
                }
            }

            // Quellen: Websuche, eigene Dokumente oder beides
            val sourceMode = viewModel.settingsStore.state.collectAsState().value.sourceMode
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(tr("sources_label"), style = MaterialTheme.typography.labelMedium)
                com.veritas.localresearch.settings.SourceMode.entries.forEach { m ->
                    FilterChip(
                        selected = sourceMode == m,
                        onClick = { viewModel.updateSettings { it.copy(sourceMode = m) } },
                        enabled = !state.progress.isRunning,
                        label = { Text(m.label, maxLines = 1) }
                    )
                }
            }
            if (sourceMode != com.veritas.localresearch.settings.SourceMode.WEB) {
                DocumentsPanel(viewModel, state.importStatus, enabled = !state.progress.isRunning)
            }

            // Eingabefeld & Recherche-Tiefe (1-3 Runden)
            OutlinedTextField(
                value = state.queryInput,
                onValueChange = viewModel::onQueryChange,
                label = {
                    Text(
                        if (sourceMode == com.veritas.localresearch.settings.SourceMode.DOCS) tr("hint_docs")
                        else tr("hint_web")
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                enabled = !state.progress.isRunning
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(tr("depth_label"), style = MaterialTheme.typography.labelMedium)
                listOf(1 to tr("depth_fast"), 2 to tr("depth_std"), 3 to tr("depth_deep")).forEach { (rounds, label) ->
                    FilterChip(
                        selected = state.maxRounds == rounds,
                        onClick = { viewModel.onMaxRoundsChange(rounds) },
                        enabled = !state.progress.isRunning,
                        label = { Text(label, maxLines = 1) }
                    )
                }
            }

            // Laenge und Ausfuehrlichkeit der Antwort (gespeichert in den Einstellungen)
            val lengthSettings = viewModel.settingsStore.state.collectAsState().value
            val answerLength = lengthSettings.answerLength
            val answerWords = lengthSettings.answerWords
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(tr("length_label"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.CenterVertically))
                com.veritas.localresearch.settings.AnswerLength.entries.forEach { len ->
                    FilterChip(
                        selected = answerWords == 0 && answerLength == len,
                        onClick = { viewModel.updateSettings { it.copy(answerLength = len, answerWords = 0) } },
                        enabled = !state.progress.isRunning,
                        label = { Text(len.label, maxLines = 1) }
                    )
                }
                // Eigene Laenge in Woertern
                FilterChip(
                    selected = answerWords > 0,
                    onClick = { viewModel.updateSettings { it.copy(answerWords = if (it.answerWords > 0) it.answerWords else 1000) } },
                    enabled = !state.progress.isRunning,
                    label = { Text(if (answerWords > 0) tr("len_words", answerWords) else tr("len_custom"), maxLines = 1) }
                )
            }
            if (answerWords > 0) {
                var sliderWords by androidx.compose.runtime.remember(answerWords) { androidx.compose.runtime.mutableFloatStateOf(answerWords.toFloat()) }
                Slider(
                    value = sliderWords,
                    onValueChange = { sliderWords = (it / 100f).roundToInt() * 100f },
                    onValueChangeFinished = { viewModel.updateSettings { it.copy(answerWords = sliderWords.toInt()) } },
                    valueRange = com.veritas.localresearch.pipeline.LengthSpec.MIN_WORDS.toFloat()..com.veritas.localresearch.pipeline.LengthSpec.MAX_WORDS.toFloat(),
                    enabled = !state.progress.isRunning,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    tr("len_hint", sliderWords.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (state.progress.isRunning) {
                FilledTonalButton(onClick = viewModel::stopResearch, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(tr("stop"))
                }
            } else {
                Button(
                    onClick = viewModel::startResearch,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.queryInput.isNotBlank() && !state.isModelLoading && state.importStatus == null
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (sourceMode == com.veritas.localresearch.settings.SourceMode.DOCS) tr("ask") else tr("research"))
                }
            }

            // Live-Fortschritt der 5-Stufen-Pipeline
            if (state.progress.isRunning || state.report != null) {
                PipelineProgressCard(progress = state.progress, liveFlow = viewModel.live)
            }

            // Suchmaschinen, die einen Menschen sehen wollen: der Nutzer kann den Test selbst loesen
            val challenged = viewModel.challengedEngines.collectAsState().value
            if (viewModel.canSolveCaptcha && challenged.isNotEmpty()) {
                CaptchaHint(challenged.sortedBy { it.ordinal }) { viewModel.openCaptcha(it) }
            }

            // Streaming-Entwurf waehrend Stufe 4b (Synthese)
            if (state.progress.isRunning && state.streamingDraft.isNotBlank()) {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(tr("synth_running"), style = MaterialTheme.typography.labelMedium)
                        Text(state.streamingDraft.replace("## ", ""), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            // Fertiger, deterministisch verifizierter Bericht
            state.report?.let { report ->
                VerifiedAnswerCard(
                    report = report,
                    onCitationClick = viewModel::openCitationSheet,
                    onNumberWarningClick = viewModel::openNumberWarning
                )
                // Export als Arbeit mit Inhalts- und Literaturverzeichnis; Speicherort waehlt der Nutzer
                val launchers = com.veritas.localresearch.export.ExportFormat.entries.associateWith { f ->
                    androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.CreateDocument(f.mime)
                    ) { uri -> if (uri != null) viewModel.exportReport(uri, f) }
                }
                val citeStyle = viewModel.settingsStore.state.collectAsState().value.citationStyle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(tr("export_label"), style = MaterialTheme.typography.labelMedium)
                    launchers.forEach { (f, launcher) ->
                        OutlinedButton(
                            onClick = { launcher.launch(com.veritas.localresearch.export.ExportFormat.fileName(report.question, f)) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) { Text(f.label, maxLines = 1) }
                    }
                }
                Text(
                    tr("cite_style_info", citeStyle.label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val showTimings = viewModel.settingsStore.state.collectAsState().value.showTimings
                if (showTimings && report.totalMs > 0) {
                    Text(
                        text = buildString {
                            append(tr("duration", formatSeconds(report.totalMs)))
                            if (report.modelName.isNotBlank()) append(" • ${report.modelName}")
                            report.stageTimings.forEach { (stage, ms) -> append('\n').append("$stage: ${formatSeconds(ms)}") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // Click-to-Passage Sheet
    state.selectedClaimForSheet?.let { claim ->
        ClickToPassageSheet(
            claim = claim,
            onDismiss = { viewModel.openCitationSheet(null) }
        )
    }

    // Warn-Dialog beim Antippen einer gelb markierten Zahl
    state.selectedWarningNumber?.let { warn ->
        AlertDialog(
            onDismissRequest = { viewModel.openNumberWarning(null) },
            title = { Text(tr("warn_title")) },
            text = { Text(tr("warn_text", warn.reason)) },
            confirmButton = {
                TextButton(onClick = { viewModel.openNumberWarning(null) }) { Text(tr("got_it")) }
            }
        )
    }

    if (state.showSettings) {
        SettingsSheet(viewModel = viewModel, onDismiss = { viewModel.toggleSettings(false) })
    }

    viewModel.captchaFor.collectAsState().value?.let { engine ->
        CaptchaDialog(
            engineName = engine.displayName,
            url = viewModel.captchaUrl(engine),
            userAgent = viewModel.browserUserAgent,
            onSolved = { viewModel.captchaSolved(engine); viewModel.openCaptcha(null) },
            onDismiss = { viewModel.openCaptcha(null) }
        )
    }

    // Model-Manager Sheet
    if (state.showModelManager) {
        ModelManagerSheet(
            uiState = state,
            modelManager = viewModel.modelManager,
            onDownloadPreset = viewModel::downloadPreset,
            onLoadGguf = viewModel::loadLlmFile,
            onLoadEmbedding = viewModel::loadEmbeddingPreset,
            onImportLocalGguf = viewModel::importLocalGguf,
            onDismiss = { viewModel.toggleModelManager(false) }
        )
    }
}

private fun formatSeconds(ms: Long): String =
    if (ms < 10_000) String.format(com.veritas.localresearch.i18n.I18n.locale, "%.1f s", ms / 1000.0)
    else if (ms < 120_000) "${ms / 1000} s"
    else "${ms / 60_000} min ${(ms / 1000) % 60} s"
