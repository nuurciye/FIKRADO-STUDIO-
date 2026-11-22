package com.cuscus.ffmpeggui.core.production.render

import com.cuscus.ffmpeggui.core.production.model.ProductionProject
import com.cuscus.ffmpeggui.core.production.model.SceneId
import com.cuscus.ffmpeggui.core.production.validation.ProjectValidator

data class RenderPlan(
    val jobId: RenderJobId,
    val engineId: EngineId,
    val project: ProductionProject,
    val sceneIds: List<SceneId>,
    val output: RenderOutputSpec,
)

data class RenderPlanResult(
    val plan: RenderPlan? = null,
    val issues: List<RenderIssue> = emptyList(),
) {
    val isSuccess: Boolean get() = plan != null && issues.isEmpty()
}

/**
 * Creates a deterministic single-engine plan. It never drops unsupported
 * layers and never pretends an unregistered backend is available.
 *
 * If preferredEngineId is supplied, only that engine is considered.
 * Otherwise the first compatible registered adapter wins in registration order.
 */
class RenderPlanner(
    private val registry: EngineRegistry,
    private val projectValidator: ProjectValidator = ProjectValidator(),
) {
    fun plan(
        jobId: RenderJobId,
        project: ProductionProject,
        sceneIds: List<SceneId>,
        output: RenderOutputSpec,
        preferredEngineId: EngineId? = null,
    ): RenderPlanResult {
        val issues = mutableListOf<RenderIssue>()

        projectValidator.validate(project).issues.forEach {
            issues += RenderIssue("project_${it.code}", it.path, it.message)
        }

        if (sceneIds.isEmpty()) {
            issues += RenderIssue("empty_scene_selection", "sceneIds", "At least one scene must be selected.")
        }
        if (sceneIds.distinct().size != sceneIds.size) {
            issues += RenderIssue("duplicate_scene_selection", "sceneIds", "Scene selection contains duplicate IDs.")
        }

        val scenesById = project.scenes.associateBy { it.id }
        sceneIds.forEachIndexed { index, sceneId ->
            if (sceneId !in scenesById) {
                issues += RenderIssue("unknown_scene", "sceneIds[$index]", "Selected scene does not exist.")
            }
        }

        if (output.width <= 0) issues += RenderIssue("invalid_output_width", "output.width", "Output width must be positive.")
        if (output.height <= 0) issues += RenderIssue("invalid_output_height", "output.height", "Output height must be positive.")
        if (output.frameRateNumerator <= 0 || output.frameRateDenominator <= 0) {
            issues += RenderIssue("invalid_output_frame_rate", "output.frameRate", "Output frame-rate terms must be positive.")
        }
        if (output.container.isBlank()) issues += RenderIssue("missing_output_container", "output.container", "Output container is required.")
        if (output.outputUri.isBlank()) issues += RenderIssue("missing_output_uri", "output.outputUri", "Output URI is required.")
        if (issues.isNotEmpty()) return RenderPlanResult(issues = issues)

        val selectedLayers = sceneIds.flatMap { scenesById.getValue(it).layers }
        val requiredKinds = selectedLayers.map { it.kind }.toSet()
        val needsAudio = selectedLayers.any { it.kind == com.cuscus.ffmpeggui.core.production.model.LayerKind.AUDIO } ||
            !output.audioCodec.isNullOrBlank()

        val candidates = if (preferredEngineId != null) {
            listOfNotNull(registry.find(preferredEngineId))
        } else {
            registry.all()
        }

        if (preferredEngineId != null && candidates.isEmpty()) {
            return RenderPlanResult(issues = listOf(
                RenderIssue("engine_unavailable", "engineId", "Requested engine '${preferredEngineId.value}' is not registered.")
            ))
        }

        val compatible = candidates.firstOrNull { adapter ->
            val capabilities = adapter.capabilities()
            val supportsLayers = requiredKinds.all { it in capabilities.supportedLayerKinds }
            val codec = output.videoCodec
            val supportsVideoCodec = codec.isNullOrBlank() ||
                capabilities.supportedCodecs.any { it.equals(codec, ignoreCase = true) }
            val audioCodec = output.audioCodec
            val supportsAudioCodec = audioCodec.isNullOrBlank() ||
                capabilities.supportedCodecs.any { it.equals(audioCodec, ignoreCase = true) }
            supportsLayers && supportsVideoCodec && supportsAudioCodec && (!needsAudio || capabilities.supportsAudio)
        }

        if (compatible == null) {
            val ids = candidates.joinToString { it.id.value }.ifBlank { "none registered" }
            val reason = buildString {
                append("No available engine supports the selected scene layers and requested output.")
                append(" Required layers: ")
                append(requiredKinds.joinToString { it.name }.ifBlank { "none" })
                append("; video codec: ")
                append(output.videoCodec ?: "unspecified")
                append("; audio required: ")
                append(needsAudio)
                append("; candidates: ")
                append(ids)
            }
            return RenderPlanResult(issues = listOf(RenderIssue("no_compatible_engine", "engineId", reason)))
        }

        return RenderPlanResult(
            plan = RenderPlan(jobId, compatible.id, project, sceneIds.toList(), output)
        )
    }
}
