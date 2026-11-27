package com.veritas.localresearch.ui

import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
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
                                append(state.loadedLlmName ?: "Kein LLM geladen")
                                append(" • ")
                                append(if (state.isEmbeddingLoaded) "Hybrid (BM25+ONNX)" else "BM25-Modus")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleModelManager(true) }) {
                        Icon(Icons.Default.Memory, contentDescription = "Modelle verwalten")
                    }
                    IconButton(onClick = { viewModel.toggleSettings(true) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Einstellungen")
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
                Text("Lade lokales GGUF-Modell in den RAM…", style = MaterialTheme.typography.bodySmall)
            }

            state.errorMessage?.let { err ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(err, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                        TextButton(onClick = viewModel::clearError) { Text("OK") }
                    }
                }
            }

            // Eingabefeld & Recherche-Tiefe (1-3 Runden)
            OutlinedTextField(
                value = state.queryInput,
                onValueChange = viewModel::onQueryChange,
                label = { Text("Forschungsfrage stellen (100 % lokal geprüft)…") },
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
                Text("Tiefe:", style = MaterialTheme.typography.labelMedium)
                listOf(1 to "Schnell", 2 to "Standard", 3 to "Tief").forEach { (rounds, label) ->
                    FilterChip(
                        selected = state.maxRounds == rounds,
                        onClick = { viewModel.onMaxRoundsChange(rounds) },
                        enabled = !state.progress.isRunning,
                        label = { Text(label, maxLines = 1) }
                    )
                }
            }

            // Laenge und Ausfuehrlichkeit der Antwort (gespeichert in den Einstellungen)
            val answerLength = viewModel.settingsStore.state.collectAsState().value.answerLength
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Länge:", style = MaterialTheme.typography.labelMedium)
                com.veritas.localresearch.settings.AnswerLength.entries.forEach { len ->
                    FilterChip(
                        selected = answerLength == len,
                        onClick = { viewModel.updateSettings { it.copy(answerLength = len) } },
                        enabled = !state.progress.isRunning,
                        label = { Text(len.label, maxLines = 1) }
                    )
                }
            }

            if (state.progress.isRunning) {
                FilledTonalButton(onClick = viewModel::stopResearch, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Stop")
                }
            } else {
                Button(
                    onClick = viewModel::startResearch,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.queryInput.isNotBlank() && !state.isModelLoading
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Forschen")
                }
            }

            // Live-Fortschritt der 5-Stufen-Pipeline
            if (state.progress.isRunning || state.report != null) {
                PipelineProgressCard(progress = state.progress)
            }

            // Streaming-Entwurf waehrend Stufe 4b (Synthese)
            if (state.progress.isRunning && state.streamingDraft.isNotBlank()) {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Synthese läuft (Zahlen-Check folgt)…", style = MaterialTheme.typography.labelMedium)
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
                    Text("Export:", style = MaterialTheme.typography.labelMedium)
                    launchers.forEach { (f, launcher) ->
                        OutlinedButton(
                            onClick = { launcher.launch(com.veritas.localresearch.export.ExportFormat.fileName(report.question, f)) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) { Text(f.label, maxLines = 1) }
                    }
                }
                Text(
                    "Zitierstil: ${citeStyle.label} (änderbar in den Einstellungen)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val showTimings = viewModel.settingsStore.state.collectAsState().value.showTimings
                if (showTimings && report.totalMs > 0) {
                    Text(
                        text = buildString {
                            append("Dauer ${formatSeconds(report.totalMs)}")
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
            title = { Text("Unbelegte Zahl erkannt") },
            text = { Text("${warn.reason}\n\nPrüfe diesen Wert unbedingt manuell in den verlinkten Originalquellen.") },
            confirmButton = {
                TextButton(onClick = { viewModel.openNumberWarning(null) }) { Text("Verstanden") }
            }
        )
    }

    if (state.showSettings) {
        SettingsSheet(viewModel = viewModel, onDismiss = { viewModel.toggleSettings(false) })
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
    if (ms < 10_000) String.format(java.util.Locale.GERMANY, "%.1f s", ms / 1000.0)
    else if (ms < 120_000) "${ms / 1000} s"
    else "${ms / 60_000} min ${(ms / 1000) % 60} s"
