package com.cuscus.ffmpeggui.core.production.render

import com.cuscus.ffmpeggui.core.engine.ConversionJob
import com.cuscus.ffmpeggui.core.engine.ConversionOutcome
import com.cuscus.ffmpeggui.core.engine.ConversionEvent
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

fun interface FfmpegProductionOutputPublisher {
    suspend fun publish(localOutputPath: String, destinationUri: String): String
}

fun interface FfmpegProductionRunner {
    suspend fun run(
        job: ConversionJob,
        onEvent: (ConversionEvent) -> Unit,
    ): ConversionOutcome
}

/**
 * Adapts the mature ConversionEngine to the multi-engine production contract.
 *
 * This adapter currently supports sequential VIDEO layers plus independent
 * AUDIO layers. It does not claim support for overlays, audio-only tracks, 3D,
 * or composition layers until the platform mapper can preserve those semantics.
 */
class FfmpegProductionAdapter(
    private val runner: FfmpegProductionRunner,
    private val jobFactory: FfmpegProductionJobFactory,
    private val outputPublisher: FfmpegProductionOutputPublisher = FfmpegProductionOutputPublisher { localPath, _ -> localPath },
) : EngineAdapter {
    override val id: EngineId = ENGINE_ID

    override fun capabilities(): EngineCapabilities = EngineCapabilities(
        supportedLayerKinds = setOf(com.cuscus.ffmpeggui.core.production.model.LayerKind.VIDEO, com.cuscus.ffmpeggui.core.production.model.LayerKind.AUDIO),
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
            it.kind != com.cuscus.ffmpeggui.core.production.model.LayerKind.VIDEO && it.kind != com.cuscus.ffmpeggui.core.production.model.LayerKind.AUDIO
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
        // Mapping may probe media and allocate/stage temporary resources.
        // Keep validation side-effect free; prepare exactly once in render().
        return emptyList()
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
        return when (val outcome = runner.run(job) { event ->
            when (event) {
                is ConversionEvent.Progress -> onProgress(EngineProgress(event.fraction, "FFmpeg encoding"))
                is ConversionEvent.StageChanged -> onProgress(EngineProgress(null, event.stage.name.lowercase()))
                else -> Unit
            }
        }) {
            is ConversionOutcome.Success -> try {
                val publishedUri = outputPublisher.publish(outcome.outputPath, request.output.outputUri)
                EngineRenderResult(success = true, outputUri = publishedUri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EngineRenderResult(
                    success = false,
                    errorCode = "output_publish_failed",
                    errorMessage = e.message?.takeIf { it.isNotBlank() }
                        ?: "The rendered file could not be copied to the selected destination.",
                )
            }
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
