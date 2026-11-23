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
import com.cuscus.ffmpeggui.core.model.PlacedSound
import com.cuscus.ffmpeggui.core.model.Resolution
import com.cuscus.ffmpeggui.core.model.Segment
import com.cuscus.ffmpeggui.core.model.SourceInfo
import com.cuscus.ffmpeggui.core.production.model.AssetKind
import com.cuscus.ffmpeggui.core.production.model.LayerKind
import com.cuscus.ffmpeggui.core.production.model.LayerTransform
import com.cuscus.ffmpeggui.core.production.timeline.ProductionSceneTimeline
import com.cuscus.ffmpeggui.core.production.timeline.ProductionAudioTimeline
import com.cuscus.ffmpeggui.core.production.render.EngineRenderRequest
import com.cuscus.ffmpeggui.core.production.render.FfmpegJobPreparation
import com.cuscus.ffmpeggui.core.production.render.FfmpegProductionJobFactory
import com.cuscus.ffmpeggui.core.production.render.RenderIssue

/**
 * Conservative Android bridge from a production scene to the existing FFmpeg pipeline.
 *
 * The first mapper supports a single scene made of sequential, full-frame video
 * clips. Gaps, overlaps, transforms, overlays and non-video layers are rejected
 * instead of being silently flattened or discarded. URI staging remains the
 * responsibility of ConversionEngine.
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
        if (scene.layers.isEmpty()) return issue(
            "empty_scene", "scenes[${scene.id.value}].layers", "The selected scene has no clips."
        )
        if (scene.durationMs <= 0L) return issue(
            "invalid_scene_duration", "scenes[${scene.id.value}].durationMs", "Scene duration must be positive."
        )

        val orderedLayers = scene.layers.filter { it.kind == LayerKind.VIDEO }
            .sortedWith(compareBy({ it.startMs }, { it.zIndex }))
        if (orderedLayers.isEmpty()) return issue("video_layer_required", "scenes[${scene.id.value}].layers", "At least one VIDEO layer is required for an MP4 render.")
        val sources = mutableListOf<NamedSource>()
        val sourceInfo = mutableListOf<SourceInfo>()
        val segments = mutableListOf<Segment>()
        var timelineCursor = 0L

        orderedLayers.forEachIndexed { index, layer ->
            val path = "scenes[${scene.id.value}].layers[${layer.id.value}]"
            if (layer.kind != LayerKind.VIDEO) return issue(
                "video_layer_required", "$path.kind", "Only VIDEO clips are supported in this sequential multi-clip MVP."
            )
            if (layer.startMs != timelineCursor) return issue(
                if (layer.startMs < timelineCursor) "overlapping_clips" else "timeline_gap",
                "$path.startMs",
                "Sequential clips must start at $timelineCursor ms; found ${layer.startMs} ms."
            )
            if (layer.durationMs <= 0L) return issue(
                "invalid_clip_duration", "$path.durationMs", "Clip duration must be positive."
            )
            if (layer.zIndex != 0 || layer.opacity != 1f || layer.transform != LayerTransform()) return issue(
                "unsupported_layer_transform", path,
                "Sequential clips must use the default full-frame transform, opacity 1, and z-index 0."
            )
            if (layer.startMs + layer.durationMs > scene.durationMs) return issue(
                "clip_exceeds_scene", "$path.durationMs", "Clip extends beyond the selected scene duration."
            )

            val asset = request.project.assets.firstOrNull { it.id == layer.assetId }
                ?: return issue("missing_video_asset", "$path.assetId", "The video clip asset is missing.")
            if (asset.kind != AssetKind.VIDEO) return issue(
                "asset_not_video", "assets[${asset.id.value}].kind", "The selected clip must reference a VIDEO asset."
            )
            if (asset.uri.isBlank()) return issue(
                "empty_asset_uri", "assets[${asset.id.value}].uri", "The video asset URI is empty."
            )
            val info = try { mediaProbe.describe(asset.uri) } catch (_: Exception) { null }
                ?: return issue(
                    "source_unreadable", "assets[${asset.id.value}].uri",
                    "Android could not read or probe this video URI."
                )
            if (!info.isVideo || info.width <= 0 || info.height <= 0) return issue(
                "invalid_video_source", "assets[${asset.id.value}]",
                "The selected asset is not a readable video."
            )
            val availableDuration = minOf(
                asset.durationMs ?: Long.MAX_VALUE,
                info.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE,
            )
            val sourceStartMs = layer.properties[ProductionSceneTimeline.SOURCE_IN_KEY]?.toLongOrNull() ?: 0L
            val sourceEndMs = layer.properties[ProductionSceneTimeline.SOURCE_OUT_KEY]?.toLongOrNull()
                ?: (sourceStartMs + layer.durationMs)
            if (sourceStartMs < 0L || sourceEndMs <= sourceStartMs) return issue(
                "invalid_source_range", "$path.properties",
                "Source trim range must have a non-negative start and an end after the start."
            )
            if (sourceEndMs > availableDuration) return issue(
                "clip_exceeds_source", "$path.properties.${ProductionSceneTimeline.SOURCE_OUT_KEY}",
                "Clip source range exceeds the readable source duration."
            )
            if (sourceEndMs - sourceStartMs != layer.durationMs) return issue(
                "source_range_duration_mismatch", "$path.durationMs",
                "Clip duration must equal its source out-point minus its source in-point."
            )

            val displayName = asset.displayName.ifBlank {
                request.project.name.ifBlank { "clip-${index + 1}.mp4" }
            }
            // Reuse one ConversionRequest source slot for repeated references to the
            // same asset. Timeline segments still retain their own order and identity.
            val sourceIndex = sources.indexOfFirst { it.uri == asset.uri }.let { existing ->
                if (existing >= 0) existing else {
                    sources += NamedSource(asset.uri, displayName)
                    sourceInfo += SourceInfo(
                        durationMs = info.durationMs,
                        width = info.width,
                        height = info.height,
                        hasAudio = info.hasAudio,
                        isStill = false,
                        hdr = info.hdr,
                        frameRate = info.frameRate,
                        hasAlpha = info.hasAlpha,
                    )
                    sources.lastIndex
                }
            }
            segments += Segment(
                source = sourceIndex,
                startMs = sourceStartMs,
                endMs = sourceEndMs,
                clipId = layer.id.value,
            )
            timelineCursor += layer.durationMs
        }

        if (timelineCursor != scene.durationMs) return issue(
            "scene_duration_mismatch", "scenes[${scene.id.value}].durationMs",
            "Sequential clips cover $timelineCursor ms, but the scene declares ${scene.durationMs} ms."
        )

        val audioProjection = ProductionAudioTimeline.project(scene, request.project.assets)
        if (!audioProjection.isValid) {
            val first = audioProjection.issues.first()
            return issue(first.code, "scenes[${scene.id.value}].layers[${first.layerId}]", first.message)
        }
        val sounds = mutableListOf<NamedSource>()
        val placedSounds = audioProjection.clips.map { clip ->
            val asset = request.project.assets.first { it.id.value == clip.assetId }
            if (asset.uri.isBlank()) return issue("empty_audio_uri", "assets[${asset.id.value}].uri", "The audio asset URI is empty.")
            val info = try { mediaProbe.describe(asset.uri) } catch (_: Exception) { null }
                ?: return issue("audio_source_unreadable", "assets[${asset.id.value}].uri", "Android could not read or probe this audio URI.")
            if (!info.hasAudio) return issue("invalid_audio_source", "assets[${asset.id.value}]", "The selected asset has no readable audio stream.")
            sounds += NamedSource(asset.uri, asset.displayName.ifBlank { "audio-${sounds.size + 1}.mp3" })
            PlacedSound(startMs = clip.span.startMs, trimStartMs = clip.span.trimStartMs, trimEndMs = clip.span.trimEndMs, speed = clip.span.rate, repeats = clip.span.loops)
        }
        val firstInfo = sourceInfo.first()
        val resolution = when (request.output.height) {
            240 -> Resolution.P240
            480 -> Resolution.P480
            720 -> Resolution.P720
            1080 -> Resolution.P1080
            firstInfo.height -> Resolution.ORIGINAL
            else -> return issue(
                "unsupported_output_height", "output.height",
                "Supported output heights are 240, 480, 720, 1080, or the first source height."
            )
        }
        val frameRate = when {
            request.output.frameRateDenominator <= 0 -> null
            request.output.frameRateNumerator.toDouble() / request.output.frameRateDenominator == 10.0 -> Framerate.FPS10
            request.output.frameRateNumerator.toDouble() / request.output.frameRateDenominator == 15.0 -> Framerate.FPS15
            request.output.frameRateNumerator.toDouble() / request.output.frameRateDenominator == 24.0 -> Framerate.FPS24
            request.output.frameRateNumerator.toDouble() / request.output.frameRateDenominator == 30.0 -> Framerate.FPS30
            else -> null
        } ?: return issue(
            "unsupported_frame_rate", "output.frameRate",
            "The FFmpeg MVP supports 10, 15, 24, or 30 fps."
        )
        if (request.output.width <= 0 || request.output.height <= 0) return issue(
            "invalid_output_dimensions", "output.width/height", "Output dimensions must be positive."
        )
        val ratio = when {
            request.output.width == request.output.height -> CanvasRatio.SQUARE
            request.output.width.toLong() * 9 == request.output.height.toLong() * 16 -> CanvasRatio.LANDSCAPE_16_9
            request.output.width.toLong() * 16 == request.output.height.toLong() * 9 -> CanvasRatio.PORTRAIT_9_16
            request.output.width.toLong() * 3 == request.output.height.toLong() * 4 -> CanvasRatio.LANDSCAPE_4_3
            request.output.width == firstInfo.width && request.output.height == firstInfo.height -> CanvasRatio.FIT
            else -> return issue(
                "unsupported_output_ratio", "output.width/height",
                "The requested aspect ratio is not supported by the first FFmpeg mapper."
            )
        }
        if (request.output.container.lowercase() !in setOf("mp4", "video/mp4")) return issue(
            "unsupported_container", "output.container", "The first production mapper exports MP4 only."
        )
        if (request.output.videoCodec?.lowercase()?.let { it != "h264" } == true) return issue(
            "unsupported_video_codec", "output.videoCodec", "The first production mapper supports H.264 video only."
        )
        if (request.output.audioCodec?.lowercase()?.let { it != "aac" } == true) return issue(
            "unsupported_audio_codec", "output.audioCodec", "The first production mapper supports AAC audio only."
        )

        val conversion = ConversionRequest(
            format = OutputFormat.MP4,
            inputFileName = sources.first().name,
            sources = sourceInfo,
            segments = segments,
            encoding = EncodingSettings(resolution = resolution, framerate = frameRate),
            canvas = ratio,
            canvasFit = CanvasFit.FIT,
            sounds = placedSounds,
        )
        return FfmpegJobPreparation(job = ConversionJob(
            request = conversion,
            sources = MediaSources(inputs = sources, sounds = sounds),
            mode = CommandMode.Generated,
        ))
    }
}
