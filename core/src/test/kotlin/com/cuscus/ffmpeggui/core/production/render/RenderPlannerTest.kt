package com.cuscus.ffmpeggui.core.production.render

import com.cuscus.ffmpeggui.core.production.model.*
import org.junit.Assert.*
import org.junit.Test

class RenderPlannerTest {
    private class Adapter(
        override val id: EngineId,
        private val supported: Set<LayerKind>,
        private val codecs: Set<String> = emptySet(),
        private val audio: Boolean = false,
    ) : EngineAdapter {
        override fun capabilities() = EngineCapabilities(supported, codecs, supportsAudio = audio)
        override suspend fun validate(request: EngineRenderRequest) = emptyList<RenderIssue>()
        override suspend fun render(
            request: EngineRenderRequest,
            onProgress: (EngineProgress) -> Unit,
        ) = EngineRenderResult(true, request.output.outputUri)
    }

    private fun project(layerKind: LayerKind = LayerKind.VIDEO) = ProductionProject(
        id = ProjectId("project-1"),
        name = "Test project",
        profile = ProductionProfile.DOCUMENTARY,
        settings = ProductionSettings(1920, 1080, 30),
        assets = if (layerKind in setOf(LayerKind.VIDEO, LayerKind.IMAGE, LayerKind.AUDIO, LayerKind.THREE_D)) {
            listOf(ProductionAsset(AssetId("asset-1"), when (layerKind) {
                LayerKind.VIDEO -> AssetKind.VIDEO
                LayerKind.IMAGE -> AssetKind.IMAGE
                LayerKind.AUDIO -> AssetKind.AUDIO
                else -> AssetKind.MODEL_3D
            }, "asset://one", "asset"))
        } else emptyList(),
        scenes = listOf(ProductionScene(
            SceneId("scene-1"), "Scene", 5000,
            listOf(ProductionLayer(
                LayerId("layer-1"), layerKind, 0, 5000,
                if (layerKind in setOf(LayerKind.VIDEO, LayerKind.IMAGE, LayerKind.AUDIO, LayerKind.THREE_D)) AssetId("asset-1") else null
            ))
        ))
    )

    private fun output(
        videoCodec: String? = "h264",
        audioCodec: String? = null,
    ) = RenderOutputSpec(1920, 1080, 30, 1, "mp4", videoCodec, audioCodec, "output://final.mp4")

    private fun planner(vararg adapters: EngineAdapter) = RenderPlanner(EngineRegistry(adapters.toList()))

    @Test fun choosesFirstCompatibleAdapterInRegistrationOrder() {
        val first = Adapter(EngineId("ffmpeg"), setOf(LayerKind.VIDEO), setOf("h264"))
        val second = Adapter(EngineId("remotion"), setOf(LayerKind.VIDEO), setOf("h264"))
        val result = planner(first, second).plan(
            RenderJobId("job-1"), project(), listOf(SceneId("scene-1")), output()
        )

        assertTrue(result.issues.toString(), result.isSuccess)
        assertEquals(first.id, result.plan?.engineId)
    }

    @Test fun preferredEngineIsHonoredWhenCompatible() {
        val ffmpeg = Adapter(EngineId("ffmpeg"), setOf(LayerKind.VIDEO), setOf("h264"))
        val remotion = Adapter(EngineId("remotion"), setOf(LayerKind.VIDEO), setOf("h264"))
        val result = planner(ffmpeg, remotion).plan(
            RenderJobId("job-1"), project(), listOf(SceneId("scene-1")), output(), remotion.id
        )

        assertEquals(remotion.id, result.plan?.engineId)
    }

    @Test fun rejectsUnregisteredPreferredEngine() {
        val result = planner().plan(
            RenderJobId("job-1"), project(), listOf(SceneId("scene-1")), output(), EngineId("blender")
        )
        assertFalse(result.isSuccess)
        assertEquals("engine_unavailable", result.issues.single().code)
    }

    @Test fun rejectsUnsupportedLayerInsteadOfDroppingIt() {
        val adapter = Adapter(EngineId("ffmpeg"), setOf(LayerKind.VIDEO), setOf("h264"))
        val result = planner(adapter).plan(
            RenderJobId("job-1"), project(LayerKind.THREE_D), listOf(SceneId("scene-1")), output()
        )

        assertFalse(result.isSuccess)
        assertEquals("no_compatible_engine", result.issues.single().code)
    }

    @Test fun checksCodecAndAudioCapabilities() {
        val noAudio = Adapter(EngineId("ffmpeg"), setOf(LayerKind.VIDEO, LayerKind.AUDIO), setOf("h264", "aac"))
        val audioProject = project(LayerKind.AUDIO)
        val rejected = planner(noAudio).plan(
            RenderJobId("job-1"), audioProject, listOf(SceneId("scene-1")), output("h264", "aac")
        )
        assertEquals("no_compatible_engine", rejected.issues.single().code)

        val withAudio = Adapter(EngineId("ffmpeg-audio"), setOf(LayerKind.AUDIO), setOf("h264", "aac"), audio = true)
        val accepted = planner(withAudio).plan(
            RenderJobId("job-1"), audioProject, listOf(SceneId("scene-1")), output("h264", "aac")
        )
        assertTrue(accepted.issues.toString(), accepted.isSuccess)
    }

    @Test fun rejectsUnknownOrDuplicateSceneSelections() {
        val adapter = Adapter(EngineId("ffmpeg"), setOf(LayerKind.VIDEO), setOf("h264"))
        val unknown = planner(adapter).plan(
            RenderJobId("job-1"), project(), listOf(SceneId("missing")), output()
        )
        assertTrue(unknown.issues.any { it.code == "unknown_scene" })

        val duplicate = planner(adapter).plan(
            RenderJobId("job-1"), project(), listOf(SceneId("scene-1"), SceneId("scene-1")), output()
        )
        assertTrue(duplicate.issues.any { it.code == "duplicate_scene_selection" })
    }

    @Test fun rejectsInvalidOutputSpecification() {
        val bad = output().copy(width = 0, frameRateDenominator = 0, outputUri = " ")
        val result = planner().plan(
            RenderJobId("job-1"), project(), listOf(SceneId("scene-1")), bad
        )
        assertFalse(result.isSuccess)
        assertTrue(result.issues.any { it.code == "invalid_output_width" })
        assertTrue(result.issues.any { it.code == "invalid_output_frame_rate" })
        assertTrue(result.issues.any { it.code == "missing_output_uri" })
    }

    @Test fun rejectsProjectsThatFailDomainValidation() {
        val invalid = project().copy(name = " ")
        val result = planner(
            Adapter(EngineId("ffmpeg"), setOf(LayerKind.VIDEO), setOf("h264"))
        ).plan(RenderJobId("job-1"), invalid, listOf(SceneId("scene-1")), output())

        assertFalse(result.isSuccess)
        assertTrue(result.issues.any { it.code == "project_blank_name" })
    }
}
