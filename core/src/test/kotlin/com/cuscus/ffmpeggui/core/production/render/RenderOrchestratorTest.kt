package com.cuscus.ffmpeggui.core.production.render

import com.cuscus.ffmpeggui.core.production.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RenderOrchestratorTest {
    private class FakeAdapter(
        override val id: EngineId = EngineId("fake"),
        private val validation: List<RenderIssue> = emptyList(),
        private val result: EngineRenderResult = EngineRenderResult(true, "output://render.mp4"),
        private val failure: Exception? = null,
    ) : EngineAdapter {
        var validateCalls = 0
        var renderCalls = 0

        override fun capabilities() = EngineCapabilities(setOf(LayerKind.VIDEO), setOf("h264"))

        override suspend fun validate(request: EngineRenderRequest): List<RenderIssue> {
            validateCalls++
            return validation
        }

        override suspend fun render(
            request: EngineRenderRequest,
            onProgress: (EngineProgress) -> Unit,
        ): EngineRenderResult {
            renderCalls++
            failure?.let { throw it }
            onProgress(EngineProgress(0.25f, "Rendering"))
            onProgress(EngineProgress(1f, "Finished"))
            return result
        }
    }

    private fun plan(engineId: EngineId = EngineId("fake")): RenderPlan {
        val project = ProductionProject(
            id = ProjectId("p1"), name = "Project", profile = ProductionProfile.DOCUMENTARY,
            settings = ProductionSettings(1280, 720, 30),
            assets = listOf(ProductionAsset(AssetId("a1"), AssetKind.VIDEO, "asset://clip", "clip")),
            scenes = listOf(ProductionScene(
                SceneId("s1"), "Scene", 1000,
                listOf(ProductionLayer(LayerId("l1"), LayerKind.VIDEO, 0, 1000, AssetId("a1")))
            ))
        )
        return RenderPlan(
            RenderJobId("job-1"), engineId, project, listOf(SceneId("s1")),
            RenderOutputSpec(1280, 720, 30, 1, "mp4", "h264", outputUri = "output://render.mp4")
        )
    }

    @Test fun executesSelectedAdapterAndForwardsProgress() = runBlocking {
        val adapter = FakeAdapter()
        val orchestrator = RenderOrchestrator(EngineRegistry(listOf(adapter)))
        val progress = mutableListOf<EngineProgress>()

        val result = orchestrator.execute(plan()) { progress += it }

        assertTrue(result.issues.toString(), result.isSuccess)
        assertEquals(adapter.id, result.engineId)
        assertEquals(1, adapter.validateCalls)
        assertEquals(1, adapter.renderCalls)
        assertEquals(listOf(0.25f, 1f), progress.map { it.fraction })
    }

    @Test fun validationIssuesPreventRendering() = runBlocking {
        val issue = RenderIssue("unsupported_feature", "scenes[0]", "Unsupported feature")
        val adapter = FakeAdapter(validation = listOf(issue))
        val result = RenderOrchestrator(EngineRegistry(listOf(adapter))).execute(plan())

        assertFalse(result.isSuccess)
        assertEquals(listOf(issue), result.issues)
        assertEquals(1, adapter.validateCalls)
        assertEquals(0, adapter.renderCalls)
    }

    @Test fun missingPlannedEngineReturnsStructuredIssue() = runBlocking {
        val result = RenderOrchestrator(EngineRegistry()).execute(plan(EngineId("missing")))

        assertFalse(result.isSuccess)
        assertEquals("engine_unavailable", result.issues.single().code)
        assertNull(result.renderResult)
    }

    @Test fun adapterFailureBecomesStructuredExecutionIssue() = runBlocking {
        val adapter = FakeAdapter(failure = IllegalStateException("Worker disconnected"))
        val result = RenderOrchestrator(EngineRegistry(listOf(adapter))).execute(plan())

        assertFalse(result.isSuccess)
        assertEquals("engine_execution_failed", result.issues.single().code)
        assertEquals("Worker disconnected", result.issues.single().message)
    }

    @Test fun failedEngineResultIsNotReportedAsSuccess() = runBlocking {
        val adapter = FakeAdapter(
            result = EngineRenderResult(false, errorCode = "render_failed", errorMessage = "Encoding failed")
        )
        val result = RenderOrchestrator(EngineRegistry(listOf(adapter))).execute(plan())

        assertFalse(result.isSuccess)
        assertEquals("render_failed", result.renderResult?.errorCode)
    }

    @Test fun cancellationPropagatesInsteadOfBecomingFailure() = runBlocking {
        val adapter = FakeAdapter(failure = CancellationException("Cancelled by user"))
        try {
            RenderOrchestrator(EngineRegistry(listOf(adapter))).execute(plan())
            fail("Cancellation must propagate")
        } catch (cancelled: CancellationException) {
            assertEquals("Cancelled by user", cancelled.message)
        }
    }
}
