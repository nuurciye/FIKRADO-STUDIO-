package com.cuscus.ffmpeggui.core.production.render

import com.cuscus.ffmpeggui.core.engine.ConversionJob
import com.cuscus.ffmpeggui.core.engine.ConversionOutcome
import com.cuscus.ffmpeggui.core.engine.ConversionEvent
import com.cuscus.ffmpeggui.core.engine.ConversionEngine
import kotlinx.coroutines.CancellationException

/**
 * Explicit boundary between a production project and the existing FFmpeg
 * conversion pipeline. The host supplies the mapper because resolving durable
 * asset URIs and Android content permissions belongs to the platform layer.
 */
data class FfmpegJobPreparation(
    val job: ConversionJob? = null,
    val issues: List<RenderIssue> = emptyList(),
) {
    init {
        require((job != null) xor issues.isNotEmpty()) {
            "Preparation must contain exactly one of a job or validation issues."
        }
    }
}

fun interface FfmpegProductionJobFactory {
    suspend fun prepare(request: EngineRenderRequest): FfmpegJobPreparation
}

/**
 * Adapts the mature ConversionEngine to the multi-engine production contract.
 *
 * This adapter intentionally advertises only VIDEO layers. It does not claim
 * support for timelines, overlays, audio-only tracks, 3D, or composition layers
 * until the platform mapper can translate those semantics without data loss.
 */
class FfmpegProductionAdapter(
    private val engine: ConversionEngine,
    private val jobFactory: FfmpegProductionJobFactory,
) : EngineAdapter {
    override val id: EngineId = ENGINE_ID

    override fun capabilities(): EngineCapabilities = EngineCapabilities(
        supportedLayerKinds = setOf(com.cuscus.ffmpeggui.core.production.model.LayerKind.VIDEO),
        supportedCodecs = setOf("h264", "aac"),
        supportsAlpha = false,
        supportsAudio = true,
        supportsCancellation = true,
    )

    override suspend fun validate(request: EngineRenderRequest): List<RenderIssue> {
        if (request.sceneIds.size != 1) {
            return listOf(RenderIssue(
                "ffmpeg_mvp_single_scene_only",
                "sceneIds",
                "The initial FFmpeg production adapter supports exactly one scene per render."
            ))
        }
        val scene = request.project.scenes.firstOrNull { it.id == request.sceneIds.single() }
            ?: return listOf(RenderIssue("unknown_scene", "sceneIds[0]", "Selected scene does not exist."))
        if (scene.layers.isEmpty()) {
            return listOf(RenderIssue("empty_scene", "scenes[${scene.id.value}].layers", "The selected scene has no layers."))
        }
        val unsupported = scene.layers.filter {
            it.kind != com.cuscus.ffmpeggui.core.production.model.LayerKind.VIDEO
        }
        if (unsupported.isNotEmpty()) {
            return unsupported.map { layer ->
                RenderIssue(
                    "unsupported_layer_kind",
                    "scenes[${scene.id.value}].layers[${layer.id.value}]",
                    "FFmpeg MVP cannot yet translate ${layer.kind} layers."
                )
            }
        }
        return jobFactory.prepare(request).issues
    }

    override suspend fun render(
        request: EngineRenderRequest,
        onProgress: (EngineProgress) -> Unit,
    ): EngineRenderResult {
        val prepared = jobFactory.prepare(request)
        if (prepared.issues.isNotEmpty()) {
            return EngineRenderResult(
                success = false,
                errorCode = prepared.issues.first().code,
                errorMessage = prepared.issues.joinToString("; ") { it.message },
            )
        }
        val job = requireNotNull(prepared.job) { "FFmpeg job factory returned no job." }
        return when (val outcome = engine.run(job) { event ->
            when (event) {
                is ConversionEvent.Progress -> onProgress(EngineProgress(event.fraction, "FFmpeg encoding"))
                is ConversionEvent.StageChanged -> onProgress(EngineProgress(null, event.stage.name.lowercase()))
                else -> Unit
            }
        }) {
            is ConversionOutcome.Success -> EngineRenderResult(
                success = true,
                outputUri = outcome.outputPath,
            )
            is ConversionOutcome.Failure -> EngineRenderResult(
                success = false,
                errorCode = outcome.reason.name.lowercase(),
                errorMessage = outcome.detail.ifBlank { "FFmpeg conversion failed: ${outcome.reason}." },
            )
            ConversionOutcome.Cancelled -> throw CancellationException("FFmpeg render was cancelled.")
        }
    }

    companion object {
        val ENGINE_ID = EngineId("ffmpeg")
    }
}
