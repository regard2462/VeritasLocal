package com.veritas.localresearch.ui

import android.content.Context
import com.veritas.localresearch.i18n.tr
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.veritas.localresearch.llm.ModelManager
import com.veritas.localresearch.llm.ModelPreset
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerSheet(
    uiState: ResearchUiState,
    modelManager: ModelManager,
    onDownloadPreset: (ModelPreset) -> Unit,
    onLoadGguf: (File) -> Unit,
    onLoadEmbedding: (ModelPreset) -> Unit,
    onImportLocalGguf: (Uri, String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val resolvedName = resolveDisplayName(context, uri)
                ?: "imported_${System.currentTimeMillis()}.gguf"
            onImportLocalGguf(uri, resolvedName)
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
            Text(tr("models_title"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                text = tr("ram_info", uiState.deviceRamGb, if (uiState.deviceRamGb >= 8) "Qwen3 4B (Q4_K_M)" else "Qwen3 1.7B (Q4_K_M)"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            modelManager.presets.forEach { preset ->
                val file = modelManager.getFile(preset)
                val installed = modelManager.isPresetInstalled(preset)
                val dlProgress = uiState.downloadProgress[preset.id]
                val isActive = when {
                    preset.isReranker -> uiState.rerankerLoaded
                    preset.isEmbedding -> uiState.loadedEmbeddingId == preset.id
                    else -> uiState.loadedLlmName == preset.fileName
                }

                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(preset.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(tr("size_info", preset.approxSizeMb, preset.minRamGb), style = MaterialTheme.typography.bodySmall)

                        if (dlProgress != null) {
                            LinearProgressIndicator(progress = { dlProgress }, modifier = Modifier.fillMaxWidth())
                            Text(tr("downloading", (dlProgress * 100).toInt()), style = MaterialTheme.typography.labelSmall)
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (!installed) {
                                    Button(onClick = { onDownloadPreset(preset) }) {
                                        Icon(Icons.Default.Download, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text(tr("download"))
                                    }
                                } else if (isActive) {
                                    AssistChip(
                                        onClick = {},
                                        leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = VerifiedGreen) },
                                        label = { Text(tr("active_loaded")) }
                                    )
                                } else if (preset.isEmbedding || preset.isReranker) {
                                    Button(onClick = { onLoadEmbedding(preset) }) {
                                        Text(if (preset.isReranker) tr("load_reranker") else tr("load_embeddings"))
                                    }
                                } else {
                                    Button(
                                        onClick = { onLoadGguf(file) },
                                        enabled = !uiState.isModelLoading
                                    ) {
                                        Text(tr("load_model"))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isModelLoading
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(tr("import_gguf"))
            }

            if (uiState.localGgufFiles.isNotEmpty()) {
                Text(tr("local_gguf"), style = MaterialTheme.typography.labelLarge)
                uiState.localGgufFiles.forEach { file ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(file.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = { onLoadGguf(file) },
                            enabled = !uiState.isModelLoading
                        ) {
                            Text(if (uiState.loadedLlmName == file.name) tr("active") else tr("load"))
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun resolveDisplayName(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
