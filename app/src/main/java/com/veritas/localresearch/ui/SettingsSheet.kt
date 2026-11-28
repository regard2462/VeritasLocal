package com.veritas.localresearch.ui

import androidx.compose.foundation.clickable
import com.veritas.localresearch.i18n.tr
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
            Text(tr("settings"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            LanguagePicker(settings.uiLanguage) { code -> viewModel.updateSettings { it.copy(uiLanguage = code) } }

            HorizontalDivider()
            Text(tr("engines"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                tr("engines_info"),
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
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    TextButton(
                                        onClick = { viewModel.clearEngineCooldown(engine); refresh++ },
                                        contentPadding = PaddingValues(0.dp)
                                    ) { Text(tr("paused", coolMin + 1)) }
                                    if (viewModel.canSolveCaptcha && engine.isHtml) {
                                        TextButton(
                                            onClick = { viewModel.openCaptcha(engine); onDismiss() },
                                            contentPadding = PaddingValues(0.dp)
                                        ) { Text(tr("captcha_solve")) }
                                    }
                                }
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
                    label = { Text(tr("searxng_url")) },
                    placeholder = { Text("https://searx.example.org") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            HorizontalDivider()
            Text(tr("direct_sources"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            SettingSwitch("Wikipedia", settings.useWikipedia) { on -> viewModel.updateSettings { it.copy(useWikipedia = on) } }
            SettingSwitch(tr("arxiv"), settings.useArxiv) { on -> viewModel.updateSettings { it.copy(useArxiv = on) } }

            HorizontalDivider()
            Text(tr("research_hdr"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(tr("default_depth"), style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1 to tr("depth_fast"), 2 to tr("depth_std"), 3 to tr("depth_deep")).forEach { (d, label) ->
                    FilterChip(
                        selected = settings.defaultDepth == d,
                        onClick = { viewModel.updateSettings { it.copy(defaultDepth = d) }; viewModel.onMaxRoundsChange(d) },
                        label = { Text(label, maxLines = 1) }
                    )
                }
            }
            if (viewModel.gpuName.isNotEmpty()) {
                SettingSwitch(tr("use_gpu", viewModel.gpuName), settings.useGpu) { on -> viewModel.setUseGpu(on) }
            }
            SettingSwitch(tr("use_reranker"), settings.useReranker) { on ->
                viewModel.updateSettings { it.copy(useReranker = on) }
            }
            SettingSwitch(tr("show_timings"), settings.showTimings) { on ->
                viewModel.updateSettings { it.copy(showTimings = on) }
            }

            HorizontalDivider()
            Text(tr("export_hdr"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(tr("cite_style"), style = MaterialTheme.typography.bodyMedium)
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

/** Sprache der Oberflaeche; [selected] leer = Systemsprache. */
@Composable
private fun LanguagePicker(selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val langs = com.veritas.localresearch.i18n.I18n.LANGUAGES
    val systemName = langs.first { it.code == com.veritas.localresearch.i18n.I18n.systemCode() }.nativeName
    val currentName = langs.firstOrNull { it.code == selected }?.nativeName ?: tr("lang_system", systemName)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("language"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }) { Text(currentName) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text(tr("lang_system", systemName)) }, onClick = { open = false; onSelect("") })
                langs.forEach { l ->
                    DropdownMenuItem(text = { Text(l.nativeName) }, onClick = { open = false; onSelect(l.code) })
                }
            }
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
