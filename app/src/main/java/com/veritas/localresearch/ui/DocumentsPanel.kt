package com.veritas.localresearch.ui

import androidx.compose.foundation.layout.Arrangement
import com.veritas.localresearch.i18n.tr
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.veritas.localresearch.docs.DocumentParser
import com.veritas.localresearch.docs.LocalDoc

/** Eigene Dokumente: hinzufuegen, fuer die Frage an- oder abwaehlen, entfernen. */
@Composable
fun DocumentsPanel(viewModel: ResearchViewModel, importStatus: String?, enabled: Boolean) {
    val docs by viewModel.docStore.docs.collectAsState()
    var confirmRemove by remember { mutableStateOf<LocalDoc?>(null) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.importDocument(uri) }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr("my_docs"), style = MaterialTheme.typography.titleSmall)
            if (docs.isEmpty() && importStatus == null) {
                Text(
                    tr("docs_empty"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            docs.forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = d.selected, onCheckedChange = { viewModel.selectDocument(d.id, it) }, enabled = enabled)
                    Column(Modifier.weight(1f)) {
                        Text(d.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            buildString {
                                append(d.kind)
                                if (d.pages > 0) append(" • " + if (d.pages == 1) tr("pages_one") else tr("pages_n", d.pages))
                                if (d.ocr) append(" • " + tr("ocr_tag"))
                                append(" • ${formatChars(d.chars)}")
                                if (d.fileName != d.title) append(" • ${d.fileName}")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { confirmRemove = d }, enabled = enabled) {
                        Icon(Icons.Default.Delete, contentDescription = tr("remove"))
                    }
                }
                DocumentGuide(d, enabled = enabled && importStatus == null, viewModel = viewModel)
            }
            if (importStatus != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(importStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::stopResearch) { Text(tr("cancel")) }
                }
            }
            OutlinedButton(
                onClick = { picker.launch(DocumentParser.MIME_TYPES) },
                enabled = enabled && importStatus == null
            ) {
                Icon(Icons.Default.NoteAdd, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(tr("add_doc"))
            }
        }
    }

    confirmRemove?.let { d ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(tr("remove_doc_q")) },
            text = { Text(tr("remove_doc_text", d.title)) },
            confirmButton = { TextButton(onClick = { viewModel.removeDocument(d.id); confirmRemove = null }) { Text(tr("remove")) } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(tr("cancel")) } }
        )
    }
}

/** Kurzfassung (aufklappbar) und Fragevorschlaege eines Dokuments; ohne Kurzfassung ein Knopf zum Erstellen. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DocumentGuide(d: LocalDoc, enabled: Boolean, viewModel: ResearchViewModel) {
    var open by remember(d.id) { mutableStateOf(false) }
    Column(Modifier.padding(start = 48.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (d.summary.isBlank()) {
            TextButton(onClick = { viewModel.createGuide(d.id) }, enabled = enabled) { Text(tr("make_guide")) }
            return@Column
        }
        TextButton(onClick = { open = !open }) { Text(if (open) tr("hide_guide") else tr("show_guide")) }
        if (open) {
            Text(d.summary, style = MaterialTheme.typography.bodySmall)
            if (d.questions.isNotEmpty()) {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    d.questions.forEach { q ->
                        androidx.compose.material3.SuggestionChip(
                            onClick = { viewModel.onQueryChange(q) },
                            enabled = enabled,
                            label = { Text(q) }
                        )
                    }
                }
            }
        }
    }
}

private fun formatChars(n: Int): String = when {
    n >= 1_000_000 -> tr("chars_m", String.format(com.veritas.localresearch.i18n.I18n.locale, "%.1f", n / 1_000_000.0))
    n >= 1_000 -> tr("chars_k", n / 1000)
    else -> tr("chars", n)
}
