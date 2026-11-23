package com.cuscus.ffmpeggui.core.production.timeline

import com.cuscus.ffmpeggui.core.production.model.AssetKind
import com.cuscus.ffmpeggui.core.production.model.LayerKind
import com.cuscus.ffmpeggui.core.production.model.ProductionAsset
import com.cuscus.ffmpeggui.core.production.model.ProductionLayer
import com.cuscus.ffmpeggui.core.production.model.ProductionScene
import com.cuscus.ffmpeggui.core.timeline.SoundSpan

/**
 * Read-only projection of AUDIO layers onto independent timeline lanes.
 *
 * ProductionLayer.startMs is the output-clock position. The source in/out
 * points are stored in the same properties used by ProductionSceneTimeline.
 * zIndex is the authored lane hint; overlapping clips are packed into
 * additional rows without changing their timing.
 */
data class ProductionAudioClip(
    val layerId: String,
    val assetId: String,
    val span: SoundSpan,
    val lane: Int,
)

data class ProductionAudioTimelineResult(
    val clips: List<ProductionAudioClip>,
    val issues: List<ProductionAudioTimelineIssue> = emptyList(),
) {
    val isValid: Boolean get() = issues.isEmpty()
}

data class ProductionAudioTimelineIssue(
    val layerId: String,
    val code: String,
    val message: String,
)

/**
 * Converts audio layers into validated, overlap-safe lanes. It never mutates
 * the scene and never silently drops an invalid audio layer.
 */
object ProductionAudioTimeline {
    fun project(scene: ProductionScene, assets: List<ProductionAsset>): ProductionAudioTimelineResult {
        val audioLayers = scene.layers.filter { it.kind == LayerKind.AUDIO }
            .sortedWith(compareBy<ProductionLayer>({ it.zIndex }, { it.startMs }, { it.id.value }))
        val assetsById = assets.associateBy { it.id }
        val issues = mutableListOf<ProductionAudioTimelineIssue>()
        val pending = mutableListOf<Pair<ProductionLayer, SoundSpan>>()

        audioLayers.forEach { layer ->
            val assetId = layer.assetId
            val asset = assetId?.let(assetsById::get)
            if (asset == null) {
                issues += ProductionAudioTimelineIssue(layer.id.value, "missing_audio_asset", "Audio layer must reference an existing asset.")
                return@forEach
            }
            if (asset.kind != AssetKind.AUDIO) {
                issues += ProductionAudioTimelineIssue(layer.id.value, "asset_not_audio", "Audio layer must reference an AUDIO asset.")
                return@forEach
            }
            if (asset.durationMs == null || asset.durationMs <= 0L) {
                issues += ProductionAudioTimelineIssue(layer.id.value, "unknown_audio_duration", "Audio asset duration must be known and positive.")
                return@forEach
            }
            val sourceIn = layer.properties[ProductionSceneTimeline.SOURCE_IN_KEY]?.toLongOrNull() ?: 0L
            val sourceOut = layer.properties[ProductionSceneTimeline.SOURCE_OUT_KEY]?.toLongOrNull()
                ?: (sourceIn + layer.durationMs)
            if (layer.startMs < 0L || layer.durationMs <= 0L || sourceIn < 0L ||
                sourceOut <= sourceIn || sourceOut > asset.durationMs ||
                sourceOut - sourceIn != layer.durationMs
            ) {
                issues += ProductionAudioTimelineIssue(
                    layer.id.value,
                    "invalid_audio_range",
                    "Audio timeline position, duration, and source in/out range must be valid and consistent.",
                )
                return@forEach
            }
            val speed = layer.properties["speed"]?.toFloatOrNull() ?: 1f
            val repeats = layer.properties["repeats"]?.toIntOrNull() ?: 1
            if (!speed.isFinite() || speed !in SoundSpan.MIN_SPEED..SoundSpan.MAX_SPEED ||
                repeats !in 1..SoundSpan.MAX_REPEATS
            ) {
                issues += ProductionAudioTimelineIssue(layer.id.value, "invalid_audio_playback", "Audio speed or repeat count is outside supported bounds.")
                return@forEach
            }
            pending += layer to SoundSpan(
                startMs = layer.startMs,
                trimStartMs = sourceIn,
                trimEndMs = sourceOut,
                fileDurationMs = asset.durationMs,
                speed = speed,
                repeats = repeats,
            )
        }

        if (issues.isNotEmpty()) return ProductionAudioTimelineResult(emptyList(), issues)

        // Pack clips independently inside each authored zIndex lane.
        val clips = mutableListOf<ProductionAudioClip>()
        var laneOffset = 0
        pending.groupBy { it.first.zIndex }.toSortedMap().forEach { (_, entries) ->
            val spans = entries.map { it.second }
            val rows = SoundSpan.rows(spans)
            entries.forEachIndexed { index, (layer, span) ->
                clips += ProductionAudioClip(
                    layerId = layer.id.value,
                    assetId = requireNotNull(layer.assetId).value,
                    span = span,
                    lane = laneOffset + rows[index],
                )
            }
            laneOffset += (rows.maxOrNull() ?: -1) + 1
        }
        return ProductionAudioTimelineResult(clips.sortedWith(compareBy({ it.lane }, { it.span.startMs }, { it.layerId })))
    }
}
