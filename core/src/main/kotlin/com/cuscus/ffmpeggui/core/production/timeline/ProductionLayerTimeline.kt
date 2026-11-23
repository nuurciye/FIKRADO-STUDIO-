package com.cuscus.ffmpeggui.core.production.timeline

import com.cuscus.ffmpeggui.core.production.model.LayerId
import com.cuscus.ffmpeggui.core.production.model.ProductionAsset
import com.cuscus.ffmpeggui.core.production.model.ProductionLayer
import com.cuscus.ffmpeggui.core.production.model.ProductionScene

/**
 * Timeline operations for independent production tracks.
 *
 * Unlike the legacy sequential clip adapter, these operations preserve each
 * layer's authored timeline position and allow layers to overlap.
 */
object ProductionLayerTimeline {
    private const val MIN_CLIP_DURATION_MS = 100L

    fun move(scene: ProductionScene, layerId: String, deltaMs: Long): ProductionScene {
        if (deltaMs == 0L) return scene
        val target = scene.layers.firstOrNull { it.id.value == layerId } ?: return scene
        val moved = target.copy(startMs = (target.startMs + deltaMs).coerceAtLeast(0L))
        return scene.copy(layers = scene.layers.map { if (it.id == target.id) moved else it })
    }

    fun trimEnd(
        scene: ProductionScene,
        assets: List<ProductionAsset>,
        layerId: String,
        deltaMs: Long,
    ): ProductionScene {
        val target = scene.layers.firstOrNull { it.id.value == layerId } ?: return scene
        val sourceIn = target.properties[ProductionSceneTimeline.SOURCE_IN_KEY]?.toLongOrNull() ?: 0L
        val sourceOut = target.properties[ProductionSceneTimeline.SOURCE_OUT_KEY]?.toLongOrNull()
            ?: (sourceIn + target.durationMs)
        val assetDuration = target.assetId?.let { id -> assets.firstOrNull { it.id == id }?.durationMs }
        val nextOut = (sourceOut + deltaMs).coerceAtLeast(sourceIn + MIN_CLIP_DURATION_MS)
            .let { value -> assetDuration?.let { value.coerceAtMost(it) } ?: value }
        if (nextOut <= sourceIn) return scene
        val updated = target.copy(
            durationMs = nextOut - sourceIn,
            properties = target.properties + mapOf(
                ProductionSceneTimeline.SOURCE_IN_KEY to sourceIn.toString(),
                ProductionSceneTimeline.SOURCE_OUT_KEY to nextOut.toString(),
            ),
        )
        return scene.copy(
            layers = scene.layers.map { if (it.id == target.id) updated else it },
            durationMs = maxOf(scene.durationMs, updated.startMs + updated.durationMs),
        )
    }

    fun split(
        scene: ProductionScene,
        layerId: String,
        atTimelineMs: Long,
        newLayerId: String,
    ): ProductionScene {
        val target = scene.layers.firstOrNull { it.id.value == layerId } ?: return scene
        val offset = atTimelineMs - target.startMs
        if (offset < MIN_CLIP_DURATION_MS || target.durationMs - offset < MIN_CLIP_DURATION_MS) return scene
        val sourceIn = target.properties[ProductionSceneTimeline.SOURCE_IN_KEY]?.toLongOrNull() ?: 0L
        val sourceOut = target.properties[ProductionSceneTimeline.SOURCE_OUT_KEY]?.toLongOrNull()
            ?: (sourceIn + target.durationMs)
        val splitSource = sourceIn + offset
        val left = target.copy(
            durationMs = offset,
            properties = target.properties + mapOf(
                ProductionSceneTimeline.SOURCE_IN_KEY to sourceIn.toString(),
                ProductionSceneTimeline.SOURCE_OUT_KEY to splitSource.toString(),
            ),
        )
        val right = target.copy(
            id = LayerId(newLayerId),
            startMs = atTimelineMs,
            durationMs = target.durationMs - offset,
            properties = target.properties + mapOf(
                ProductionSceneTimeline.SOURCE_IN_KEY to splitSource.toString(),
                ProductionSceneTimeline.SOURCE_OUT_KEY to sourceOut.toString(),
            ),
        )
        return scene.copy(layers = scene.layers.flatMap { if (it.id == target.id) listOf(left, right) else listOf(it) })
    }

    fun duplicate(scene: ProductionScene, layerId: String, newLayerId: String): ProductionScene {
        val target = scene.layers.firstOrNull { it.id.value == layerId } ?: return scene
        val duplicate = target.copy(
            id = LayerId(newLayerId),
            startMs = target.startMs + target.durationMs,
        )
        val layers = scene.layers + duplicate
        return scene.copy(
            layers = layers,
            durationMs = maxOf(scene.durationMs, duplicate.startMs + duplicate.durationMs),
        )
    }

    fun remove(scene: ProductionScene, layerId: String): ProductionScene {
        val remaining = scene.layers.filterNot { it.id.value == layerId }
        val duration = remaining.maxOfOrNull { it.startMs + it.durationMs } ?: 0L
        return scene.copy(layers = remaining, durationMs = duration)
    }
}
