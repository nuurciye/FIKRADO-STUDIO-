package com.cuscus.ffmpeggui.production

import com.cuscus.ffmpeggui.core.engine.CommandMode
import com.cuscus.ffmpeggui.core.engine.ConversionJob
import com.cuscus.ffmpeggui.core.engine.MediaSources
import com.cuscus.ffmpeggui.core.engine.NamedSource
import com.cuscus.ffmpeggui.core.engine.MediaProbe
import com.cuscus.ffmpeggui.core.model.CanvasFit
import com.cuscus.ffmpeggui.core.model.CanvasRatio
import com.cuscus.ffmpeggui.core.model.ConversionRequest
import com.cuscus.ffmpeggui.core.model.EncodingSettings
import com.cuscus.ffmpeggui.core.model.Framerate
import com.cuscus.ffmpeggui.core.model.OutputFormat
import com.cuscus.ffmpeggui.core.model.Resolution
import com.cuscus.ffmpeggui.core.model.Segment
import com.cuscus.ffmpeggui.core.model.SourceInfo
import com.cuscus.ffmpeggui.core.production.model.AssetKind
import com.cuscus.ffmpeggui.core.production.model.LayerKind
import com.cuscus.ffmpeggui.core.production.render.EngineRenderRequest
import com.cuscus.ffmpeggui.core.production.render.FfmpegJobPreparation
import com.cuscus.ffmpeggui.core.production.render.FfmpegProductionJobFactory
import com.cuscus.ffmpeggui.core.production.render.RenderIssue

/**
 * Conservative Android bridge for the first real production render.
 *
 * It intentionally accepts one full-frame video layer beginning at t=0.
 * Unsupported placement/timeline semantics are rejected rather than silently
 * discarded. URI staging remains the responsibility of ConversionEngine.
 */
class AndroidFfmpegProductionJobFactory(
    private val mediaProbe: MediaProbe,
) : FfmpegProductionJobFactory {

    override suspend fun prepare(request: EngineRenderRequest): FfmpegJobPreparation {
        fun issue(code: String, path: String, message: String) =
            FfmpegJobPreparation(issues = listOf(RenderIssue(code, path, message)))

        if (request.sceneIds.size != 1) return issue(
            "single_scene_required", "sceneIds", "The FFmpeg MVP requires exactly one selected scene."
        )
        val scene = request.project.scenes.firstOrNull { it.id == request.sceneIds.single() }
            ?: return issue("unknown_scene", "sceneIds[0]", "The selected scene does not exist.")
        if (scene.layers.size != 1) return issue(
            "single_video_layer_required", "scenes[${scene.id.value}].layers",
            "The first FFmpeg production render requires exactly one video layer."
        )
        val layer = scene.layers.single()
        if (layer.kind != LayerKind.VIDEO) return issue(
            "video_layer_required", "layers[${layer.id.value}].kind", "Only VIDEO layers are supported."
        )
        if (layer.startMs != 0L || layer.zIndex != 0 || layer.opacity != 1f ||
            layer.transform != com.cuscus.ffmpeggui.core.production.model.LayerTransform()
        ) return issue(
            "unsupported_layer_transform", "layers[${layer.id.value}]",
            "The first render supports a full-frame layer at time zero with default transform and opacity."
        )
        val asset = request.project.assets.firstOrNull { it.id == layer.assetId }
            ?: return issue("missing_video_asset", "layers[${layer.id.value}].assetId", "The video layer asset is missing.")
        if (asset.kind != AssetKind.VIDEO) return issue(
            "asset_not_video", "assets[${asset.id.value}].kind", "The selected layer must reference a VIDEO asset."
        )
        if (asset.uri.isBlank()) return issue("empty_asset_uri", "assets[${asset.id.value}].uri", "The video asset URI is empty.")
        val info = try { mediaProbe.describe(asset.uri) } catch (_: Exception) { null }
            ?: return issue("source_unreadable", "assets[${asset.id.value}].uri", "Android could not read or probe this video URI.")
        if (!info.isVideo || info.width <= 0 || info.height <= 0) return issue(
            "invalid_video_source", "assets[${asset.id.value}]", "The selected asset is not a readable video."
        )
        val duration = minOf(layer.durationMs, scene.durationMs, asset.durationMs ?: Long.MAX_VALUE, info.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
        if (duration <= 0L) return issue("unknown_video_duration", "layers[${layer.id.value}].durationMs", "A positive video duration is required.")
        val resolution = when (request.output.height) {
            240 -> Resolution.P240
            480 -> Resolution.P480
            720 -> Resolution.P720
            1080 -> Resolution.P1080
            info.height -> Resolution.ORIGINAL
            else -> return issue("unsupported_output_height", "output.height", "Supported output heights are 240, 480, 720, 1080, or the source height.")
        }
        val frameRate = when {
            request.output.frameRateDenominator <= 0 -> null
            request.output.frameRateNumerator.toDouble() / request.output.frameRateDenominator == 10.0 -> Framerate.FPS10
            request.output.frameRateNumerator.toDouble() / request.output.frameRateDenominator == 15.0 -> Framerate.FPS15
            request.output.frameRateNumerator.toDouble() / request.output.frameRateDenominator == 24.0 -> Framerate.FPS24
            request.output.frameRateNumerator.toDouble() / request.output.frameRateDenominator == 30.0 -> Framerate.FPS30
            else -> null
        } ?: return issue("unsupported_frame_rate", "output.frameRate", "The FFmpeg MVP supports 10, 15, 24, or 30 fps.")
        val ratio = when {
            request.output.width == request.output.height -> CanvasRatio.SQUARE
            request.output.width.toLong() * 9 == request.output.height.toLong() * 16 -> CanvasRatio.LANDSCAPE_16_9
            request.output.width.toLong() * 16 == request.output.height.toLong() * 9 -> CanvasRatio.PORTRAIT_9_16
            request.output.width.toLong() * 3 == request.output.height.toLong() * 4 -> CanvasRatio.LANDSCAPE_4_3
            request.output.width.toLong() * 4 == request.output.height.toLong() * 3 -> CanvasRatio.LANDSCAPE_4_3
            request.output.width == info.width && request.output.height == info.height -> CanvasRatio.FIT
            else -> return issue("unsupported_output_ratio", "output.width/height", "The requested aspect ratio is not supported by the first FFmpeg mapper.")
        }
        if (request.output.container.lowercase() !in setOf("mp4", "video/mp4")) return issue(
            "unsupported_container", "output.container", "The first production mapper exports MP4 only."
        )
        val fileName = asset.displayName.ifBlank { request.project.name.ifBlank { "production" } }
        val conversion = ConversionRequest(
            format = OutputFormat.MP4,
            inputFileName = fileName,
            sources = listOf(SourceInfo(
                durationMs = info.durationMs, width = info.width, height = info.height,
                hasAudio = info.hasAudio, isStill = false, hdr = info.hdr, frameRate = info.frameRate,
                hasAlpha = info.hasAlpha,
            )),
            segments = listOf(Segment(source = 0, startMs = 0L, endMs = duration)),
            encoding = EncodingSettings(resolution = resolution, framerate = frameRate),
            canvas = ratio,
            canvasFit = CanvasFit.FIT,
        )
        return FfmpegJobPreparation(job = ConversionJob(
            request = conversion,
            sources = MediaSources(inputs = listOf(NamedSource(asset.uri, fileName))),
            mode = CommandMode.Generated,
        ))
    }
}
