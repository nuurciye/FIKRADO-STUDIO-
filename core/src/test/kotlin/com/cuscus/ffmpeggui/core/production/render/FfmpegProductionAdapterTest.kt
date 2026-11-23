package com.cuscus.ffmpeggui.core.production.render

import com.cuscus.ffmpeggui.core.production.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FfmpegProductionAdapterTest {
    private fun request(layerKind: LayerKind = LayerKind.VIDEO): EngineRenderRequest {
        val sceneId = SceneId("scene-1")
        val project = ProductionProject(
            id = ProjectId("project-1"),
            name = "Documentary",
            profile = ProductionProfile.DOCUMENTARY,
            settings = ProductionSettings(1920, 1080, 30),
            assets = listOf(ProductionAsset(AssetId("asset-1"), AssetKind.VIDEO, "content://clip", "clip.mp4")),
            scenes = listOf(ProductionScene(
                sceneId, "Scene 1", 5000,
                listOf(ProductionLayer(LayerId("layer-1"), layerKind, 0, 5000, AssetId("asset-1")))
            )),
        )
        return EngineRenderRequest(
            RenderJobId("job-1"),
            project,
            listOf(sceneId),
            RenderOutputSpec(1920, 1080, 30, 1, "mp4", "h264", outputUri = "file:///output.mp4"),
        )
    }

    @Test fun advertisesSupportedVideoAndAudioLayerCapabilities() {
        val adapter = FfmpegProductionAdapter(
            runner = FfmpegProductionRunner { _, _ -> error("Runner must not be called.") },
            jobFactory = FfmpegProductionJobFactory { FfmpegJobPreparation(issues = listOf(
                RenderIssue("mapper_not_configured", "assets", "No host mapper is configured.")
            )) },
        )

        assertEquals(setOf(LayerKind.VIDEO, LayerKind.AUDIO), adapter.capabilities().supportedLayerKinds)
        assertTrue(adapter.capabilities().supportsAudio)
        assertTrue(adapter.capabilities().supportsCancellation)
    }

    @Test fun rejectsUnsupportedLayerWithoutCallingHostMapper() = runBlocking {
        var mapperCalls = 0
        val adapter = FfmpegProductionAdapter(
            runner = FfmpegProductionRunner { _, _ -> error("Runner must not be called.") },
            jobFactory = FfmpegProductionJobFactory {
                mapperCalls++
                FfmpegJobPreparation(issues = listOf(
                    RenderIssue("mapper_not_configured", "assets", "No host mapper is configured.")
                ))
            },
        )

        val issues = adapter.validate(request(LayerKind.TEXT))

        assertEquals("unsupported_layer_kind", issues.single().code)
        assertEquals(0, mapperCalls)
    }

    @Test fun validationDoesNotPrepareOrProbeTheHostJob() = runBlocking {
        var mapperCalls = 0
        val adapter = FfmpegProductionAdapter(
            runner = FfmpegProductionRunner { _, _ -> error("Runner must not be called.") },
            jobFactory = FfmpegProductionJobFactory {
                mapperCalls++
                FfmpegJobPreparation(issues = listOf(
                    RenderIssue("mapper_not_configured", "assets", "No host mapper is configured.")
                ))
            },
        )

        val issues = adapter.validate(request())

        assertTrue(issues.isEmpty())
        assertEquals("Validation must not probe or prepare media.", 0, mapperCalls)
    }

    @Test fun returnsStructuredFailureWhenHostCannotMapAssets() = runBlocking {
        var runnerCalls = 0
        val issue = RenderIssue("asset_uri_unavailable", "assets[0].uri", "Asset cannot be opened.")
        val adapter = FfmpegProductionAdapter(
            runner = FfmpegProductionRunner { _, _ ->
                runnerCalls++
                error("Runner must not be called.")
            },
            jobFactory = FfmpegProductionJobFactory { FfmpegJobPreparation(issues = listOf(issue)) },
        )

        val result = adapter.render(request(), onProgress = {})

        assertFalse(result.success)
        assertEquals("asset_uri_unavailable", result.errorCode)
        assertEquals("Asset cannot be opened.", result.errorMessage)
        assertEquals(0, runnerCalls)
    }

    @Test fun rejectsMultiSceneMvpRenderExplicitly() = runBlocking {
        val original = request()
        val second = ProductionScene(SceneId("scene-2"), "Scene 2", 1000, emptyList())
        val multiScene = original.copy(
            project = original.project.copy(scenes = original.project.scenes + second),
            sceneIds = listOf(SceneId("scene-1"), SceneId("scene-2")),
        )
        val adapter = FfmpegProductionAdapter(
            runner = FfmpegProductionRunner { _, _ -> error("Runner must not be called.") },
            jobFactory = FfmpegProductionJobFactory { error("Mapper must not be called.") },
        )

        val issues = adapter.validate(multiScene)

        assertEquals("ffmpeg_mvp_single_scene_only", issues.single().code)
    }
}
