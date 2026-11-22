package com.cuscus.ffmpeggui.core.production.validation

import com.cuscus.ffmpeggui.core.production.model.*

data class ValidationIssue(
    val code: String,
    val path: String,
    val message: String,
)

data class ValidationResult(val issues: List<ValidationIssue>) {
    val isValid: Boolean get() = issues.isEmpty()
}

class ProjectValidator(
    private val maxWidth: Int = 16_384,
    private val maxHeight: Int = 16_384,
    private val maxScenes: Int = 10_000,
    private val maxLayersPerScene: Int = 10_000,
    private val maxAssets: Int = 100_000,
) {
    fun validate(project: ProductionProject): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        fun issue(code: String, path: String, message: String) {
            issues += ValidationIssue(code, path, message)
        }

        if (project.schemaVersion != ProductionProject.CURRENT_SCHEMA_VERSION)
            issue("unsupported_schema", "schemaVersion", "Unsupported project schema version.")
        if (project.id.value.isBlank()) issue("blank_id", "id", "Project ID must not be blank.")
        if (project.name.isBlank()) issue("blank_name", "name", "Project name must not be blank.")

        val s = project.settings
        if (s.width !in 1..maxWidth) issue("invalid_width", "settings.width", "Width must be between 1 and $maxWidth.")
        if (s.height !in 1..maxHeight) issue("invalid_height", "settings.height", "Height must be between 1 and $maxHeight.")
        if (s.frameRateNumerator <= 0 || s.frameRateDenominator <= 0)
            issue("invalid_frame_rate", "settings.frameRate", "Frame-rate numerator and denominator must be positive.")

        if (project.scenes.size > maxScenes) issue("too_many_scenes", "scenes", "Scene count exceeds the configured limit.")
        if (project.assets.size > maxAssets) issue("too_many_assets", "assets", "Asset count exceeds the configured limit.")

        val assetIds = project.assets.map { it.id.value }
        if (assetIds.size != assetIds.toSet().size) issue("duplicate_asset_id", "assets", "Asset IDs must be unique.")
        val sceneIds = project.scenes.map { it.id.value }
        if (sceneIds.size != sceneIds.toSet().size) issue("duplicate_scene_id", "scenes", "Scene IDs must be unique.")

        project.assets.forEachIndexed { i, asset ->
            val p = "assets[$i]"
            if (asset.id.value.isBlank()) issue("blank_id", "$p.id", "Asset ID must not be blank.")
            if (asset.uri.isBlank()) issue("blank_asset_uri", "$p.uri", "Asset reference must not be blank.")
            if (asset.displayName.isBlank()) issue("blank_asset_name", "$p.displayName", "Asset display name must not be blank.")
            if (asset.durationMs != null && asset.durationMs < 0)
                issue("negative_asset_duration", "$p.durationMs", "Asset duration cannot be negative.")
        }

        val knownAssets = assetIds.toSet()
        project.scenes.forEachIndexed { si, scene ->
            val sp = "scenes[$si]"
            if (scene.id.value.isBlank()) issue("blank_id", "$sp.id", "Scene ID must not be blank.")
            if (scene.name.isBlank()) issue("blank_scene_name", "$sp.name", "Scene name must not be blank.")
            if (scene.durationMs < 0) issue("negative_duration", "$sp.durationMs", "Scene duration cannot be negative.")
            if (scene.layers.size > maxLayersPerScene) issue("too_many_layers", "$sp.layers", "Layer count exceeds the configured limit.")

            val layerIds = scene.layers.map { it.id.value }
            if (layerIds.size != layerIds.toSet().size) issue("duplicate_layer_id", "$sp.layers", "Layer IDs must be unique within a scene.")

            scene.layers.forEachIndexed { li, layer ->
                val lp = "$sp.layers[$li]"
                if (layer.id.value.isBlank()) issue("blank_id", "$lp.id", "Layer ID must not be blank.")
                if (layer.startMs < 0) issue("negative_start", "$lp.startMs", "Layer start cannot be negative.")
                if (layer.durationMs < 0) issue("negative_duration", "$lp.durationMs", "Layer duration cannot be negative.")
                if (layer.startMs > scene.durationMs || layer.durationMs > scene.durationMs - layer.startMs)
                    issue("layer_outside_scene", lp, "Layer interval must fit within its scene.")
                if (layer.assetId != null && layer.assetId.value !in knownAssets)
                    issue("missing_asset", "$lp.assetId", "Layer references an asset that does not exist.")
                val t = layer.transform
                if (listOf(t.x, t.y, t.scaleX, t.scaleY, t.rotationDegrees, layer.opacity).any { !it.isFinite() })
                    issue("non_finite_transform", "$lp.transform", "Transform and opacity values must be finite.")
                if (layer.opacity !in 0f..1f)
                    issue("invalid_opacity", "$lp.opacity", "Opacity must be between 0 and 1.")
                if (layer.kind in setOf(LayerKind.VIDEO, LayerKind.IMAGE, LayerKind.AUDIO, LayerKind.THREE_D) && layer.assetId == null)
                    issue("missing_layer_asset", "$lp.assetId", "This layer kind requires an asset reference.")
                if (layer.engineExtensions.keys.any { it.isBlank() })
                    issue("blank_engine_namespace", "$lp.engineExtensions", "Engine extension namespaces must not be blank.")
            }
        }
        return ValidationResult(issues)
    }
}
