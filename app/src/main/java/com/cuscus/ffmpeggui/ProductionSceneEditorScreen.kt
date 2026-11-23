package com.cuscus.ffmpeggui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cuscus.ffmpeggui.core.production.model.ProductionProject
import com.cuscus.ffmpeggui.core.production.timeline.ProductionSceneTimeline

private val EditorBackground = Color(0xFF090E18)
private val EditorPanel = Color(0xFF141D2C)
private val EditorGold = Color(0xFFD7AD62)
private val EditorCream = Color(0xFFF4EBDD)
private val EditorMuted = Color(0xFF9BA9BA)

@Composable
fun ProductionSceneEditorScreen(
    project: ProductionProject,
    onProjectChanged: (ProductionProject) -> Unit,
    onAddMedia: () -> Unit,
    onBack: () -> Unit,
) {
    val scene = project.scenes.firstOrNull()
    fun updateScene(updated: com.cuscus.ffmpeggui.core.production.model.ProductionScene) {
        val currentScene = scene ?: return
        onProjectChanged(
            project.copy(
                scenes = project.scenes.map { if (it.id == currentScene.id) updated else it },
                metadata = project.metadata.copy(modifiedAtEpochMs = System.currentTimeMillis()),
            ),
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().background(EditorBackground).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("‹  WORKSPACE", color = EditorMuted) }
            Text("EDIT / TIMELINE", color = EditorGold, style = MaterialTheme.typography.labelLarge)
        }
        Text(project.name, color = EditorCream, style = MaterialTheme.typography.headlineMedium)
        Text(
            "${project.settings.width} × ${project.settings.height}  •  ${project.settings.frameRateNumerator}/${project.settings.frameRateDenominator} FPS  •  ${project.profile.name.replace('_', ' ')}",
            color = EditorMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Card(modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF344154), androidx.compose.foundation.shape.RoundedCornerShape(20.dp)), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = EditorPanel)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(scene?.name ?: "Scene 1", color = EditorCream, style = MaterialTheme.typography.titleLarge)
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
        Text("TIMELINE  /  LAYERS", color = EditorGold, style = MaterialTheme.typography.titleLarge)
        if (scene == null || scene.layers.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text("No clips yet", modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(scene.layers, key = { it.id.value }) { layer ->
                    val asset = project.assets.firstOrNull { it.id == layer.assetId }
                    val sourceIn = layer.properties[ProductionSceneTimeline.SOURCE_IN_KEY]?.toLongOrNull() ?: 0L
                    val sourceOut = layer.properties[ProductionSceneTimeline.SOURCE_OUT_KEY]?.toLongOrNull()
                        ?: asset?.durationMs ?: (sourceIn + layer.durationMs)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(asset?.displayName ?: "Missing media", color = EditorCream, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${layer.kind.name}  •  Timeline ${layer.startMs / 1000.0}s  •  Source ${sourceIn / 1000.0}–${sourceOut / 1000.0}s",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        val at = layer.startMs + layer.durationMs / 2L
                                        if (layer.durationMs >= 200L) updateScene(
                                            ProductionSceneTimeline.split(scene, project.assets, at, java.util.UUID.randomUUID().toString()),
                                        )
                                    },
                                    enabled = layer.durationMs >= 200L,
                                ) { Text("Split") }
                                OutlinedButton(
                                    onClick = { updateScene(ProductionSceneTimeline.move(scene, project.assets, layer.id.value, -1)) },
                                    enabled = scene.layers.indexOfFirst { it.id == layer.id } > 0,
                                ) { Text("←") }
                                OutlinedButton(
                                    onClick = { updateScene(ProductionSceneTimeline.move(scene, project.assets, layer.id.value, 1)) },
                                    enabled = scene.layers.indexOfFirst { it.id == layer.id } < scene.layers.lastIndex,
                                ) { Text("→") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(
                                    onClick = {
                                        updateScene(ProductionSceneTimeline.trimEnd(scene, project.assets, layer.id.value, sourceOut - 1_000L))
                                    },
                                    enabled = layer.durationMs > 1_100L,
                                ) { Text("Trim −1s") }
                                TextButton(
                                    onClick = {
                                        updateScene(ProductionSceneTimeline.trimEnd(scene, project.assets, layer.id.value, sourceOut + 1_000L))
                                    },
                                    enabled = asset?.durationMs?.let { sourceOut < it } == true,
                                ) { Text("Extend +1s") }
                                TextButton(
                                    onClick = {
                                        updateScene(ProductionSceneTimeline.duplicate(scene, project.assets, layer.id.value, java.util.UUID.randomUUID().toString()))
                                    },
                                ) { Text("Duplicate") }
                                TextButton(
                                    onClick = { updateScene(ProductionSceneTimeline.remove(scene, project.assets, layer.id.value)) },
                                    enabled = scene.layers.size > 1,
                                ) { Text("Remove") }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "CLIP TOOLS  ·  Split, trim, reorder, duplicate and remove. Multi-track compositing and live preview remain in development.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
