package com.cuscus.ffmpeggui

import android.content.ContentResolver
import android.media.MediaMetadataRetriever
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cuscus.ffmpeggui.core.production.model.ProductionProject
import com.cuscus.ffmpeggui.core.production.model.ProductionScene

@Composable
fun ProductionSceneEditorScreen(
    project: ProductionProject,
    onAddMedia: () -> Unit,
    onBack: () -> Unit,
) {
    val scene = project.scenes.firstOrNull()
    Column(
        modifier = Modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("All productions") }
            Text("SCENE EDITOR", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
        Text(project.name, style = MaterialTheme.typography.headlineMedium)
        Text(
            "${project.settings.width} × ${project.settings.height}  •  ${project.settings.frameRateNumerator}/${project.settings.frameRateDenominator} FPS  •  ${project.profile.name.replace('_', ' ')}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(scene?.name ?: "Scene 1", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (scene == null) "Your timeline is empty. Import video, audio or images to start building the first scene."
                    else "${scene.layers.size} clips  •  ${scene.durationMs / 1000.0}s timeline  •  ${project.assets.size} media assets",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onAddMedia, modifier = Modifier.fillMaxWidth()) {
                    Text("＋  Import media")
                }
            }
        }
        Text("Timeline", style = MaterialTheme.typography.titleLarge)
        if (scene == null || scene.layers.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text("No clips yet", modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(scene.layers, key = { it.id.value }) { layer ->
                    val asset = project.assets.firstOrNull { it.id == layer.assetId }
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(asset?.displayName ?: "Missing media", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${layer.kind.name}  •  ${layer.startMs / 1000.0}s → ${(layer.startMs + layer.durationMs) / 1000.0}s",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text("MVP note: clips are currently arranged sequentially. Trimming, transitions, overlays and multi-track editing are planned for later milestones.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
