package com.cuscus.ffmpeggui.core.production.render

import com.cuscus.ffmpeggui.core.production.model.LayerKind
import com.cuscus.ffmpeggui.core.production.model.ProductionProject
import com.cuscus.ffmpeggui.core.production.model.SceneId

@JvmInline value class EngineId(val value: String) {
    init { require(value.isNotBlank()) { "Engine ID must not be blank." } }
}

@JvmInline value class RenderJobId(val value: String) {
    init { require(value.isNotBlank()) { "Render job ID must not be blank." } }
}

data class EngineCapabilities(
    val supportedLayerKinds: Set<LayerKind>,
    val supportedCodecs: Set<String> = emptySet(),
    val supportsAlpha: Boolean = false,
    val supportsAudio: Boolean = false,
    val supportsCancellation: Boolean = true,
)

data class EngineRenderRequest(
    val jobId: RenderJobId,
    val project: ProductionProject,
    val sceneIds: List<SceneId>,
    val output: RenderOutputSpec,
    val workingDirectoryUri: String? = null,
)

data class RenderOutputSpec(
    val width: Int,
    val height: Int,
    val frameRateNumerator: Int,
    val frameRateDenominator: Int,
    val container: String,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val outputUri: String,
)

data class RenderIssue(
    val code: String,
    val path: String,
    val message: String,
)

data class EngineProgress(
    val fraction: Float?,
    val message: String? = null,
) {
    init {
        require(fraction == null || (fraction.isFinite() && fraction in 0f..1f)) {
            "Progress fraction must be null or finite within 0..1."
        }
    }
}

data class EngineRenderResult(
    val success: Boolean,
    val outputUri: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
) {
    init {
        require(success || (!errorCode.isNullOrBlank() || !errorMessage.isNullOrBlank())) {
            "A failed render result must include an error code or message."
        }
        require(!success || !outputUri.isNullOrBlank()) {
            "A successful render result must include an output URI."
        }
    }
}

/**
 * One execution backend. Implementations belong to platform/worker modules;
 * this contract deliberately contains no Android, FFmpeg or network types.
 */
interface EngineAdapter {
    val id: EngineId

    fun capabilities(): EngineCapabilities

    suspend fun validate(request: EngineRenderRequest): List<RenderIssue>

    suspend fun render(
        request: EngineRenderRequest,
        onProgress: (EngineProgress) -> Unit,
    ): EngineRenderResult
}
