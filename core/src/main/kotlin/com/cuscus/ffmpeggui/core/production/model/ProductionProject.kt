package com.cuscus.ffmpeggui.core.production.model

@JvmInline value class ProjectId(val value: String)
@JvmInline value class SceneId(val value: String)
@JvmInline value class LayerId(val value: String)
@JvmInline value class AssetId(val value: String)

enum class ProductionProfile {
    DOCUMENTARY, ADVERTISING, SOCIAL_MEDIA, THREE_D, CORPORATE,
    EDUCATION, REAL_ESTATE, E_COMMERCE, GAMING, EVENTS, NEWS, CUSTOM
}

enum class AssetKind { VIDEO, IMAGE, AUDIO, FONT, MODEL_3D, TEXTURE, VECTOR, OTHER }
enum class LayerKind { VIDEO, IMAGE, AUDIO, TEXT, SHAPE, THREE_D, COMPOSITION }

data class ProductionSettings(
    val width: Int,
    val height: Int,
    val frameRateNumerator: Int,
    val frameRateDenominator: Int = 1,
    val backgroundColorArgb: Long? = null,
)

data class ProjectMetadata(
    val createdAtEpochMs: Long? = null,
    val modifiedAtEpochMs: Long? = null,
    val description: String? = null,
)

data class ProductionAsset(
    val id: AssetId,
    val kind: AssetKind,
    /** Opaque durable reference; not necessarily a filesystem path. */
    val uri: String,
    val displayName: String,
    val mimeType: String? = null,
    val durationMs: Long? = null,
    val checksumSha256: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class LayerTransform(
    val x: Float = 0f,
    val y: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotationDegrees: Float = 0f,
)

data class ProductionLayer(
    val id: LayerId,
    val kind: LayerKind,
    val startMs: Long,
    val durationMs: Long,
    val assetId: AssetId? = null,
    val transform: LayerTransform = LayerTransform(),
    val opacity: Float = 1f,
    val zIndex: Int = 0,
    val properties: Map<String, String> = emptyMap(),
    /** Extension values are namespaced by engine ID; they are data, never executable code. */
    val engineExtensions: Map<String, Map<String, String>> = emptyMap(),
)

data class ProductionScene(
    val id: SceneId,
    val name: String,
    val durationMs: Long,
    val layers: List<ProductionLayer>,
    val backgroundColorArgb: Long? = null,
)

data class ProductionProject(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: ProjectId,
    val name: String,
    val profile: ProductionProfile,
    val settings: ProductionSettings,
    val scenes: List<ProductionScene>,
    val assets: List<ProductionAsset>,
    val metadata: ProjectMetadata = ProjectMetadata(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
