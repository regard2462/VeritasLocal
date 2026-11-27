package com.veritas.localresearch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.veritas.localresearch.scraping.SearchEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(viewModel: ResearchViewModel, onDismiss: () -> Unit) {
    val settings by viewModel.settingsStore.state.collectAsState()
    // Pausen neu lesen, wenn der Nutzer eine aufhebt
    var refresh by remember { mutableIntStateOf(0) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Einstellungen", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            Text("Suchmaschinen", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Reihum gefragt, jede mit eigenem Minutenbudget. Bei Drosselung oder Captcha pausiert sie ab 15 Minuten.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            key(refresh) {
                SearchEngine.entries.forEach { engine ->
                    val enabled = engine.id in settings.enabledEngines
                    val coolUntil = viewModel.engineCooldownUntil(engine)
                    val coolMin = ((coolUntil - System.currentTimeMillis()) / 60_000L).toInt()
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${engine.displayName} • ${engine.perMinute}/min", style = MaterialTheme.typography.bodyMedium)
                            Text(engine.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (coolMin >= 0 && coolUntil > System.currentTimeMillis()) {
                                TextButton(
                                    onClick = { viewModel.clearEngineCooldown(engine); refresh++ },
                                    contentPadding = PaddingValues(0.dp)
                                ) { Text("Pausiert noch ${coolMin + 1} min • Pause aufheben") }
                            }
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { on ->
                                viewModel.updateSettings {
                                    it.copy(enabledEngines = if (on) it.enabledEngines + engine.id else it.enabledEngines - engine.id)
                                }
                            }
                        )
                    }
                }
            }
            if (SearchEngine.SEARXNG.id in settings.enabledEngines) {
                OutlinedTextField(
                    value = settings.searxngUrl,
                    onValueChange = { v -> viewModel.updateSettings { it.copy(searxngUrl = v.trim()) } },
                    label = { Text("Adresse der SearXNG-Instanz") },
                    placeholder = { Text("https://searx.example.org") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            HorizontalDivider()
            Text("Direkte Quellen", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            SettingSwitch("Wikipedia", settings.useWikipedia) { on -> viewModel.updateSettings { it.copy(useWikipedia = on) } }
            SettingSwitch("arXiv (Fachaufsätze, nur englische Fragen)", settings.useArxiv) { on -> viewModel.updateSettings { it.copy(useArxiv = on) } }

            HorizontalDivider()
            Text("Recherche", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("Standardtiefe", style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1 to "Schnell", 2 to "Standard", 3 to "Tief").forEach { (d, label) ->
                    FilterChip(
                        selected = settings.defaultDepth == d,
                        onClick = { viewModel.updateSettings { it.copy(defaultDepth = d) }; viewModel.onMaxRoundsChange(d) },
                        label = { Text(label, maxLines = 1) }
                    )
                }
            }
            if (viewModel.gpuName.isNotEmpty()) {
                SettingSwitch("Grafikkarte nutzen (${viewModel.gpuName})", settings.useGpu) { on -> viewModel.setUseGpu(on) }
            }
            SettingSwitch("Reranker nutzen (wenn geladen)", settings.useReranker) { on ->
                viewModel.updateSettings { it.copy(useReranker = on) }
            }
            SettingSwitch("Dauer je Stufe unter der Antwort zeigen", settings.showTimings) { on ->
                viewModel.updateSettings { it.copy(showTimings = on) }
            }

            HorizontalDivider()
            Text("Export (PDF, DOCX, ODT)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("Zitierstil", style = MaterialTheme.typography.bodyMedium)
            com.veritas.localresearch.export.CitationStyle.entries.forEach { style ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.updateSettings { it.copy(citationStyle = style) } },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = settings.citationStyle == style,
                        onClick = { viewModel.updateSettings { it.copy(citationStyle = style) } }
                    )
                    Text(style.label, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
