package com.cuscus.ffmpeggui.core.production.render

import com.cuscus.ffmpeggui.core.production.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProductionRenderSubmissionTest {
    private class VideoAdapter : EngineAdapter {
        override val id = EngineId("test-video")
        override fun capabilities() = EngineCapabilities(
            supportedLayerKinds = setOf(LayerKind.VIDEO),
            supportedCodecs = setOf("h264"),
        )
        override suspend fun validate(request: EngineRenderRequest) = emptyList<RenderIssue>()
        override suspend fun render(
            request: EngineRenderRequest,
            onProgress: (EngineProgress) -> Unit,
        ) = EngineRenderResult(true, request.output.outputUri)
    }

    private fun project(name: String = "Production") = ProductionProject(
        id = ProjectId("project-1"),
        name = name,
        profile = ProductionProfile.DOCUMENTARY,
        settings = ProductionSettings(1280, 720, 30),
        assets = listOf(ProductionAsset(AssetId("asset-1"), AssetKind.VIDEO, "asset://clip", "clip.mp4")),
        scenes = listOf(ProductionScene(
            SceneId("scene-1"), "Scene 1", 5_000,
            listOf(ProductionLayer(LayerId("layer-1"), LayerKind.VIDEO, 0, 5_000, AssetId("asset-1"))),
        )),
    )

    private fun output() = RenderOutputSpec(
        1280, 720, 30, 1, "mp4", "h264", outputUri = "content://exports/final.mp4",
    )

    @Test fun compatibleRequestIsPlannedAndAcceptedByQueue() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val queue = RenderQueue(scope, RenderOrchestrator(EngineRegistry(listOf(VideoAdapter()))))
            val submission = ProductionRenderSubmission(RenderPlanner(EngineRegistry(listOf(VideoAdapter()))), queue)

            val result = submission.submit(
                RenderJobId("job-1"), project(), listOf(SceneId("scene-1")), output(),
            )

            assertTrue(result.planning.issues.toString(), result.planning.isSuccess)
            assertTrue(result.accepted)
            assertEquals(RenderJobStatus.QUEUED, result.queuedJob?.status)
            assertEquals(EngineId("test-video"), result.queuedJob?.engineId)
        } finally {
            scope.cancel()
        }
    }

    @Test fun invalidProjectIsRejectedBeforeItCanEnterQueue() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val adapter = VideoAdapter()
            val queue = RenderQueue(scope, RenderOrchestrator(EngineRegistry(listOf(adapter))))
            val submission = ProductionRenderSubmission(RenderPlanner(EngineRegistry(listOf(adapter))), queue)

            val result = submission.submit(
                RenderJobId("job-invalid"), project(name = " "), listOf(SceneId("scene-1")), output(),
            )

            assertFalse(result.accepted)
            assertNull(result.queuedJob)
            assertTrue(result.planning.issues.any { it.code == "project_blank_name" })
            assertTrue(queue.jobs.value.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test fun unavailableEngineIsRejectedWithoutQueueMutation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val queue = RenderQueue(scope, RenderOrchestrator(EngineRegistry()))
            val submission = ProductionRenderSubmission(RenderPlanner(EngineRegistry()), queue)

            val result = submission.submit(
                RenderJobId("job-no-engine"), project(), listOf(SceneId("scene-1")), output(),
                preferredEngineId = EngineId("blender"),
            )

            assertFalse(result.accepted)
            assertEquals("engine_unavailable", result.planning.issues.single().code)
            assertTrue(queue.jobs.value.isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
