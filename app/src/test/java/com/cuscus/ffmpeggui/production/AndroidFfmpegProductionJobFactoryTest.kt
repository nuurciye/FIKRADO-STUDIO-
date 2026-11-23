package com.cuscus.ffmpeggui.production

import com.cuscus.ffmpeggui.core.engine.MediaProbe
import com.cuscus.ffmpeggui.core.engine.ConversionOutcome
import com.cuscus.ffmpeggui.core.model.MediaInfo
import com.cuscus.ffmpeggui.core.production.model.*
import com.cuscus.ffmpeggui.core.production.render.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class AndroidFfmpegProductionJobFactoryTest {
    private val sceneId = SceneId("scene-1")
    private val assetId = AssetId("asset-1")

    private fun request(
        layer: ProductionLayer = ProductionLayer(LayerId("layer-1"), LayerKind.VIDEO, 0, 5_000, assetId),
        asset: ProductionAsset = ProductionAsset(assetId, AssetKind.VIDEO, "content://media/video/1", "clip.mp4", durationMs = 5_000),
        output: RenderOutputSpec = RenderOutputSpec(1920, 1080, 30, 1, "mp4", "h264", outputUri = "content://output/1"),
    ) = EngineRenderRequest(
        jobId = RenderJobId("job-1"),
        project = ProductionProject(
            id = ProjectId("project-1"),
            name = "Documentary",
            profile = ProductionProfile.DOCUMENTARY,
            settings = ProductionSettings(1920, 1080, 30),
            assets = listOf(asset),
            scenes = listOf(ProductionScene(sceneId, "Scene 1", 5_000, listOf(layer))),
        ),
        sceneIds = listOf(sceneId),
        output = output,
    )

    private fun factory(info: MediaInfo? = MediaInfo.of(
        durationMs = 5_000, isVideo = true, width = 1920, height = 1080,
        fileSizeBytes = 1_000_000, hasAudio = true, frameRate = 30f,
    )) = AndroidFfmpegProductionJobFactory(object : MediaProbe {
        override fun describe(sourceUri: String): MediaInfo? = info
        override fun durationMs(sourceUri: String): Long = info?.durationMs ?: 0L
        override fun displayName(sourceUri: String): String? = "clip.mp4"
    })

    @Test fun mapsValidSingleVideoSceneToConversionJob() = runBlocking {
        val prepared = factory().prepare(request())

        assertTrue(prepared.issues.toString(), prepared.issues.isEmpty())
        val job = requireNotNull(prepared.job)
        assertEquals("content://media/video/1", job.sources.inputs.single().uri)
        assertEquals(5_000L, job.request.segments.single().endMs)
    }

    @Test fun rejectsMultipleScenesBeforeProbing() = runBlocking {
        var probes = 0
        val probe = object : MediaProbe {
            override fun describe(sourceUri: String): MediaInfo? { probes++; return null }
            override fun durationMs(sourceUri: String) = 0L
            override fun displayName(sourceUri: String): String? = null
        }
        val multi = request().copy(sceneIds = listOf(sceneId, SceneId("scene-2")))
        val result = AndroidFfmpegProductionJobFactory(probe).prepare(multi)

        assertEquals("single_scene_required", result.issues.single().code)
        assertEquals(0, probes)
    }

    @Test fun rejectsNonVideoLayer() = runBlocking {
        val result = factory().prepare(request(
            layer = ProductionLayer(LayerId("layer-1"), LayerKind.TEXT, 0, 5_000, assetId),
        ))
        assertEquals("video_layer_required", result.issues.single().code)
    }

    @Test fun rejectsNonVideoAsset() = runBlocking {
        val result = factory().prepare(request(
            asset = ProductionAsset(assetId, AssetKind.IMAGE, "content://media/image/1", "still.png"),
        ))
        assertEquals("asset_not_video", result.issues.single().code)
    }

    @Test fun rejectsUnreadableSource() = runBlocking {
        val result = factory(info = null).prepare(request())
        assertEquals("source_unreadable", result.issues.single().code)
    }

    @Test fun rejectsUnsupportedFrameRate() = runBlocking {
        val result = factory().prepare(request(output = RenderOutputSpec(
            1920, 1080, 25, 1, "mp4", "h264", outputUri = "content://output/1",
        )))
        assertEquals("unsupported_frame_rate", result.issues.single().code)
    }

    @Test fun rejectsUnsupportedContainer() = runBlocking {
        val result = factory().prepare(request(output = RenderOutputSpec(
            1920, 1080, 30, 1, "webm", "vp9", outputUri = "content://output/1",
        )))
        assertEquals("unsupported_container", result.issues.single().code)
    }

    @Test fun rejectsUnsupportedVideoCodec() = runBlocking {
        val result = factory().prepare(request(output = RenderOutputSpec(
            1920, 1080, 30, 1, "mp4", "vp9", outputUri = "content://output/1",
        )))
        assertEquals("unsupported_video_codec", result.issues.single().code)
    }

    @Test fun rejectsUnsupportedAudioCodec() = runBlocking {
        val result = factory().prepare(request(output = RenderOutputSpec(
            1920, 1080, 30, 1, "mp4", "h264", "opus", "content://output/1",
        )))
        assertEquals("unsupported_audio_codec", result.issues.single().code)
    }
    @Test fun adapterPublishesCompletedFileToTheRequestedContentUri() = runBlocking {
        var publishedSource: String? = null
        var publishedDestination: String? = null
        val adapter = FfmpegProductionAdapter(
            runner = FfmpegProductionRunner { _, _ ->
                ConversionOutcome.Success("/private/exports/render.mp4")
            },
            jobFactory = factory(),
            outputPublisher = FfmpegProductionOutputPublisher { source, destination ->
                publishedSource = source
                publishedDestination = destination
                destination
            },
        )

        val result = adapter.render(request(), onProgress = {})

        assertTrue(result.errorMessage.orEmpty(), result.success)
        assertEquals("/private/exports/render.mp4", publishedSource)
        assertEquals("content://output/1", publishedDestination)
        assertEquals("content://output/1", result.outputUri)
    }

    @Test fun outputPublishingFailureIsReportedAsRenderFailure() = runBlocking {
        val adapter = FfmpegProductionAdapter(
            runner = FfmpegProductionRunner { _, _ ->
                ConversionOutcome.Success("/private/exports/render.mp4")
            },
            jobFactory = factory(),
            outputPublisher = FfmpegProductionOutputPublisher { _, _ ->
                throw java.io.IOException("Destination is read-only.")
            },
        )

        val result = adapter.render(request(), onProgress = {})

        assertFalse(result.success)
        assertEquals("output_publish_failed", result.errorCode)
        assertEquals("Destination is read-only.", result.errorMessage)
    }

}
