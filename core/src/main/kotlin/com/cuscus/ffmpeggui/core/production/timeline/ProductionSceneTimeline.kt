package com.cuscus.ffmpeggui.core.production.timeline

import com.cuscus.ffmpeggui.core.production.model.AssetId
import com.cuscus.ffmpeggui.core.production.model.LayerId
import com.cuscus.ffmpeggui.core.production.model.ProductionAsset
import com.cuscus.ffmpeggui.core.production.model.ProductionScene
import com.cuscus.ffmpeggui.core.timeline.Clip
import com.cuscus.ffmpeggui.core.timeline.Timeline

/**
 * Adapts the production scene model to the existing, tested immutable timeline
 * editing engine. Source in/out points are stored as string properties so the
 * production JSON schema remains backward compatible.
 */
object ProductionSceneTimeline {
    const val SOURCE_IN_KEY = "sourceStartMs"
    const val SOURCE_OUT_KEY = "sourceEndMs"

    fun toTimeline(scene: ProductionScene, assets: List<ProductionAsset>): Timeline =
        Timeline(scene.layers.map { layer ->
            val assetId = layer.assetId?.value ?: "generated:${layer.id.value}"
            val sourceDuration = assets.firstOrNull { it.id.value == assetId }?.durationMs
            val sourceStart = layer.properties[SOURCE_IN_KEY]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            val sourceEnd = layer.properties[SOURCE_OUT_KEY]?.toLongOrNull()
                ?: sourceDuration?.takeIf { it > sourceStart }
                ?: (sourceStart + layer.durationMs).coerceAtLeast(sourceStart)
            Clip(
                id = layer.id.value,
                sourceId = assetId,
                startMs = sourceStart,
                endMs = sourceEnd,
            )
        })

    fun apply(scene: ProductionScene, assets: List<ProductionAsset>, timeline: Timeline): ProductionScene {
        val originalById = scene.layers.associateBy { it.id.value }
        val originalByAsset = scene.layers.groupBy { it.assetId?.value }
        val assetById = assets.associateBy { it.id.value }
        val layers = timeline.clips.mapIndexed { index, clip ->
            val sourceTemplates = originalByAsset[clip.sourceId].orEmpty()
            val matchingSourceRange = sourceTemplates.firstOrNull { candidate ->
                val candidateIn = candidate.properties[SOURCE_IN_KEY]?.toLongOrNull() ?: 0L
                val candidateOut = candidate.properties[SOURCE_OUT_KEY]?.toLongOrNull()
                    ?: assetById[clip.sourceId]?.durationMs
                    ?: (candidateIn + candidate.durationMs)
                candidateIn == clip.startMs && candidateOut == clip.endMs
            }
            val template = originalById[clip.id]
                ?: matchingSourceRange
                ?: sourceTemplates.firstOrNull()
                ?: error("No production layer template for clip ${clip.id}")
            val asset = assetById[clip.sourceId]
            val sourceEnd = clip.endMs ?: asset?.durationMs ?: (clip.startMs + clip.lengthMs)
            template.copy(
                id = LayerId(clip.id),
                startMs = timeline.startOf(index),
                durationMs = clip.lengthMs,
                properties = template.properties + mapOf(
                    SOURCE_IN_KEY to clip.startMs.toString(),
                    SOURCE_OUT_KEY to sourceEnd.toString(),
                ),
            )
        }
        return scene.copy(layers = layers, durationMs = timeline.durationMs)
    }

    fun split(scene: ProductionScene, assets: List<ProductionAsset>, atMs: Long, newLayerId: String): ProductionScene {
        val timeline = toTimeline(scene, assets)
        return apply(scene, assets, timeline.splitAt(atMs, newLayerId))
    }

    fun move(scene: ProductionScene, assets: List<ProductionAsset>, layerId: String, offset: Int): ProductionScene {
        val timeline = toTimeline(scene, assets)
        return apply(scene, assets, timeline.move(layerId, offset))
    }

    fun duplicate(scene: ProductionScene, assets: List<ProductionAsset>, layerId: String, newLayerId: String): ProductionScene {
        val timeline = toTimeline(scene, assets)
        return apply(scene, assets, timeline.duplicate(layerId, newLayerId))
    }

    fun remove(scene: ProductionScene, assets: List<ProductionAsset>, layerId: String): ProductionScene {
        val timeline = toTimeline(scene, assets)
        return apply(scene, assets, timeline.remove(layerId))
    }

    fun trimEnd(scene: ProductionScene, assets: List<ProductionAsset>, layerId: String, newEndMs: Long): ProductionScene {
        val timeline = toTimeline(scene, assets)
        val clip = timeline.clips.firstOrNull { it.id == layerId } ?: return scene
        val sourceDuration = assets.firstOrNull { it.id.value == clip.sourceId }?.durationMs ?: 0L
        return apply(scene, assets, timeline.trim(layerId, clip.startMs, newEndMs, sourceDuration))
    }
}
