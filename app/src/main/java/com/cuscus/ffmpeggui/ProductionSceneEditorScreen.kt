package com.cuscus.ffmpeggui

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.times
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import com.cuscus.ffmpeggui.core.production.model.LayerKind
import com.cuscus.ffmpeggui.core.production.model.ProductionProject
import com.cuscus.ffmpeggui.core.production.timeline.ProductionLayerTimeline
import java.util.UUID

private val EditorBackground = Color(0xFF090E18)
private val EditorPanel = Color(0xFF141D2C)
private val EditorPanelRaised = Color(0xFF1B283A)
private val EditorGold = Color(0xFFD7AD62)
private val EditorCream = Color(0xFFF4EBDD)
private val EditorMuted = Color(0xFF9BA9BA)
private val TrackVideo = Color(0xFF4C83C3)
private val TrackAudio = Color(0xFF36A58C)
private val TrackImage = Color(0xFFB28BD5)

@Composable
fun ProductionSceneEditorScreen(
    project: ProductionProject,
    onProjectChanged: (ProductionProject) -> Unit,
    onAddMedia: () -> Unit,
    onBack: () -> Unit,
) {
    val scene = project.scenes.firstOrNull()
    var selectedLayerId by remember(project.id.value) { mutableStateOf(scene?.layers?.firstOrNull()?.id?.value) }
    LaunchedEffect(scene?.layers?.map { it.id.value }) {
        if (scene?.layers?.none { it.id.value == selectedLayerId } == true) {
            selectedLayerId = scene.layers.firstOrNull()?.id?.value
        }
    }
    val selectedLayer = scene?.layers?.firstOrNull { it.id.value == selectedLayerId }
    val selectedAsset = selectedLayer?.assetId?.let { id -> project.assets.firstOrNull { it.id == id } }

    fun updateScene(updated: com.cuscus.ffmpeggui.core.production.model.ProductionScene) {
        onProjectChanged(
            project.copy(
                scenes = project.scenes.map { if (it.id == updated.id) updated else it },
                metadata = project.metadata.copy(modifiedAtEpochMs = System.currentTimeMillis()),
            ),
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().background(EditorBackground).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("‹  WORKSPACE", color = EditorMuted) }
            Text("FIKRADO / EDITOR", color = EditorGold, style = MaterialTheme.typography.labelLarge)
        }
        Text(project.name, color = EditorCream, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${project.settings.width} × ${project.settings.height}", color = EditorMuted, style = MaterialTheme.typography.labelSmall)
            Text("${project.settings.frameRateNumerator} FPS", color = EditorMuted, style = MaterialTheme.typography.labelSmall)
            Text(project.profile.name.replace('_', ' '), color = EditorGold, style = MaterialTheme.typography.labelSmall)
        }

        // Actual source-video monitor: uses the selected VIDEO asset URI from the project.
        Card(
            modifier = Modifier.fillMaxWidth().height(210.dp).border(1.dp, Color(0xFF344154), RoundedCornerShape(18.dp)),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black),
        ) {
            val context = LocalContext.current
            if (selectedLayer?.kind == LayerKind.VIDEO && selectedAsset != null) {
                keyVideoPreview(selectedAsset.uri)
            } else {
                Column(
                    Modifier.fillMaxSize().padding(18.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        when (selectedLayer?.kind) {
                            LayerKind.IMAGE -> "IMAGE LAYER SELECTED"
                            LayerKind.AUDIO -> "AUDIO LAYER SELECTED"
                            LayerKind.TEXT -> "TEXT LAYER SELECTED"
                            else -> "PREVIEW MONITOR"
                        },
                        color = EditorGold,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        selectedAsset?.displayName ?: "Import media to begin editing",
                        color = EditorCream,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("Select a video clip to play its source preview", color = EditorMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("MULTI-TRACK TIMELINE", color = EditorGold, style = MaterialTheme.typography.labelLarge)
                Text(
                    "${scene?.layers?.size ?: 0} clips  ·  ${((scene?.durationMs ?: 0L) / 1000.0)}s",
                    color = EditorMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(onClick = onAddMedia) { Text("＋ Import") }
        }

        if (scene == null || scene.layers.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = EditorPanel)) {
                Text("Import video, audio or images to build your first timeline.", color = EditorMuted, modifier = Modifier.padding(18.dp))
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val trackOrder = listOf(LayerKind.VIDEO, LayerKind.IMAGE, LayerKind.TEXT, LayerKind.SHAPE, LayerKind.THREE_D, LayerKind.COMPOSITION, LayerKind.AUDIO)
                trackOrder.forEach { kind ->
                    val layers = scene.layers.filter { it.kind == kind }.sortedBy { it.startMs }
                    if (layers.isNotEmpty()) {
                        item(key = "track-${kind.name}") {
                            Text(
                                "${kind.name.replace('_', ' ')} TRACK  ·  ${layers.size}",
                                color = trackColor(kind),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(top = 5.dp, bottom = 2.dp),
                            )
                        }
                        items(layers, key = { it.id.value }) { layer ->
                            val asset = layer.assetId?.let { id -> project.assets.firstOrNull { it.id == id } }
                            val isSelected = layer.id.value == selectedLayerId
                            TrackClipRow(
                                name = asset?.displayName ?: "${kind.name} layer",
                                kind = kind,
                                startMs = layer.startMs,
                                durationMs = layer.durationMs,
                                sceneDurationMs = scene.durationMs,
                                selected = isSelected,
                                onClick = { selectedLayerId = layer.id.value },
                            )
                        }
                    }
                }
            }
        }

        if (scene != null && selectedLayer != null) {
            Card(
                modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF344154), RoundedCornerShape(18.dp)),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = EditorPanel),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("CLIP CONTROLS", color = EditorGold, style = MaterialTheme.typography.labelLarge)
                    Text(selectedAsset?.displayName ?: selectedLayer.kind.name, color = EditorCream, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Start ${selectedLayer.startMs} ms  ·  Duration ${selectedLayer.durationMs} ms  ·  Track ${selectedLayer.zIndex}",
                        color = EditorMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        OutlinedButton(onClick = {
                            updateScene(ProductionLayerTimeline.move(scene, selectedLayer.id.value, -500L))
                        }, modifier = Modifier.weight(1f)) { Text("← 0.5s") }
                        OutlinedButton(onClick = {
                            updateScene(ProductionLayerTimeline.move(scene, selectedLayer.id.value, 500L))
                        }, modifier = Modifier.weight(1f)) { Text("+0.5s →") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        OutlinedButton(onClick = {
                            updateScene(ProductionLayerTimeline.trimEnd(scene, project.assets, selectedLayer.id.value, -500L))
                        }, modifier = Modifier.weight(1f)) { Text("Trim −0.5s") }
                        OutlinedButton(onClick = {
                            updateScene(ProductionLayerTimeline.trimEnd(scene, project.assets, selectedLayer.id.value, 500L))
                        }, modifier = Modifier.weight(1f)) { Text("Extend +0.5s") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        OutlinedButton(onClick = {
                            val splitAt = selectedLayer.startMs + selectedLayer.durationMs / 2L
                            val updated = ProductionLayerTimeline.split(scene, selectedLayer.id.value, splitAt, UUID.randomUUID().toString())
                            if (updated != scene) {
                                updateScene(updated)
                                selectedLayerId = updated.layers.firstOrNull { it.startMs == splitAt && it.id.value != selectedLayer.id.value }?.id?.value
                            }
                        }, modifier = Modifier.weight(1f)) { Text("Split") }
                        OutlinedButton(onClick = {
                            val newId = UUID.randomUUID().toString()
                            updateScene(ProductionLayerTimeline.duplicate(scene, selectedLayer.id.value, newId))
                            selectedLayerId = newId
                        }, modifier = Modifier.weight(1f)) { Text("Duplicate") }
                        TextButton(onClick = {
                            val updated = ProductionLayerTimeline.remove(scene, selectedLayer.id.value)
                            updateScene(updated)
                            selectedLayerId = updated.layers.firstOrNull()?.id?.value
                        }) { Text("Delete", color = Color(0xFFE58C8C)) }
                    }
                }
            }
        }
        Text(
            "Preview plays the selected video source. Multi-track timing and clip edits are saved in the project. Final compositing/export for image, text and overlapping video layers is still being integrated.",
            color = EditorMuted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun keyVideoPreview(uri: String) {
    val context = LocalContext.current
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            VideoView(context).apply {
                val controller = MediaController(context)
                controller.setAnchorView(this)
                setMediaController(controller)
                setVideoURI(Uri.parse(uri))
                setOnPreparedListener { player ->
                    player.isLooping = true
                    start()
                }
            }
        },
        update = { view ->
            if (view.tag != uri) {
                view.tag = uri
                view.setVideoURI(Uri.parse(uri))
                view.setOnPreparedListener { player ->
                    player.isLooping = true
                    view.start()
                }
            }
        },
    )
}

@Composable
private fun TrackClipRow(
    name: String,
    kind: LayerKind,
    startMs: Long,
    durationMs: Long,
    sceneDurationMs: Long,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val color = trackColor(kind)
    val total = sceneDurationMs.coerceAtLeast(1L).toFloat()
    val startFraction = (startMs / total).coerceIn(0f, 1f)
    val durationFraction = (durationMs / total).coerceIn(0.02f, 1f - startFraction)
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(0xFF25344A) else EditorPanel)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) EditorGold else Color(0xFF2B394C), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, color = EditorCream, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${startMs / 1000.0}s · ${durationMs / 1000.0}s", color = EditorMuted, style = MaterialTheme.typography.labelSmall)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(15.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFF0A111D))) {
            val clipWidth = maxWidth * durationFraction
            Box(
                Modifier.offset(x = maxWidth * startFraction)
                    .width(clipWidth)
                    .height(15.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(color),
            )
        }
    }
}

private fun trackColor(kind: LayerKind): Color = when (kind) {
    LayerKind.VIDEO -> TrackVideo
    LayerKind.AUDIO -> TrackAudio
    LayerKind.IMAGE -> TrackImage
    LayerKind.TEXT -> Color(0xFFD59C55)
    LayerKind.SHAPE -> Color(0xFFB76D91)
    LayerKind.THREE_D -> Color(0xFF7B91C9)
    LayerKind.COMPOSITION -> Color(0xFF8A9B78)
}
