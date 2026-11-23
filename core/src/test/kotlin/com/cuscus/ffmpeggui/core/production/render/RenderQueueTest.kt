package com.cuscus.ffmpeggui.core.production.render

import com.cuscus.ffmpeggui.core.production.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.nio.file.Files

class RenderQueueTest {
    private class QueueAdapter(
        private val gate: CompletableDeferred<Unit>? = null,
    ) : EngineAdapter {
        override val id = EngineId("queue-test")
        val calls = AtomicInteger(0)

        override fun capabilities() = EngineCapabilities(setOf(LayerKind.VIDEO), setOf("h264"))
        override suspend fun validate(request: EngineRenderRequest) = emptyList<RenderIssue>()
        override suspend fun render(
            request: EngineRenderRequest,
            onProgress: (EngineProgress) -> Unit,
        ): EngineRenderResult {
            calls.incrementAndGet()
            onProgress(EngineProgress(0.5f, "Halfway"))
            gate?.await()
            return EngineRenderResult(true, request.output.outputUri)
        }
    }

    private fun plan(id: String) = RenderPlan(
        RenderJobId(id),
        EngineId("queue-test"),
        ProductionProject(
            id = ProjectId("p1"), name = "Queue project", profile = ProductionProfile.DOCUMENTARY,
            settings = ProductionSettings(1280, 720, 30),
            assets = listOf(ProductionAsset(AssetId("a1"), AssetKind.VIDEO, "asset://clip", "clip")),
            scenes = listOf(ProductionScene(
                SceneId("s1"), "Scene", 1000,
                listOf(ProductionLayer(LayerId("l1"), LayerKind.VIDEO, 0, 1000, AssetId("a1")))
            ))
        ),
        listOf(SceneId("s1")),
        RenderOutputSpec(1280, 720, 30, 1, "mp4", "h264", outputUri = "output://$id.mp4"),
    )

    @Test fun queueExecutesJobsSequentiallyAndPublishesSuccess() { runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val adapter = QueueAdapter()
            val queue = RenderQueue(scope, RenderOrchestrator(EngineRegistry(listOf(adapter))))
            queue.enqueue(plan("job-1"))
            queue.enqueue(plan("job-2"))

            val completed = queue.jobs.first { jobs ->
                jobs.size == 2 && jobs.all { it.status == RenderJobStatus.SUCCEEDED }
            }

            assertEquals(2, adapter.calls.get())
            assertEquals(listOf("job-1", "job-2"), completed.map { it.jobId.value })
            assertTrue(completed.all { it.outputUri != null })
        } finally {
            scope.cancel()
        }
    } }

    @Test fun queueRejectsDuplicateJobIds() { runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val queue = RenderQueue(scope, RenderOrchestrator(EngineRegistry(listOf(QueueAdapter()))))
            queue.enqueue(plan("same"))
            try {
                queue.enqueue(plan("same"))
                fail("Duplicate job ID should be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        } finally {
            scope.cancel()
        }
    } }

    @Test fun queuedJobCanBeCancelledBeforeExecution() { runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val gate = CompletableDeferred<Unit>()
            val adapter = QueueAdapter(gate)
            val queue = RenderQueue(scope, RenderOrchestrator(EngineRegistry(listOf(adapter))))
            queue.enqueue(plan("active"))
            queue.jobs.first { it.firstOrNull()?.status == RenderJobStatus.RUNNING }
            queue.enqueue(plan("waiting"))
            assertTrue(queue.cancel(RenderJobId("waiting")))

            val waiting = queue.find(RenderJobId("waiting"))
            assertEquals(RenderJobStatus.CANCELLED, waiting?.status)
            gate.complete(Unit)
        } finally {
            scope.cancel()
        }
    } }

    @Test fun runningJobCancellationIsObservable() { runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val gate = CompletableDeferred<Unit>()
            val queue = RenderQueue(scope, RenderOrchestrator(EngineRegistry(listOf(QueueAdapter(gate)))))
            queue.enqueue(plan("cancel-running"))
            queue.jobs.first { it.firstOrNull()?.status == RenderJobStatus.RUNNING }
            assertTrue(queue.cancel(RenderJobId("cancel-running")))
            val cancelled = queue.jobs.first { jobs -> jobs.firstOrNull()?.status == RenderJobStatus.CANCELLED }
            assertEquals(RenderJobStatus.CANCELLED, cancelled.single().status)
        } finally {
            scope.cancel()
        }
    } }

    @Test fun unknownAndTerminalJobsCannotBeCancelled() { runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val queue = RenderQueue(scope, RenderOrchestrator(EngineRegistry(listOf(QueueAdapter()))))
            assertFalse(queue.cancel(RenderJobId("missing")))
            queue.enqueue(plan("done"))
            queue.jobs.first { it.firstOrNull()?.status == RenderJobStatus.SUCCEEDED }
            assertFalse(queue.cancel(RenderJobId("done")))
        } finally {
            scope.cancel()
        }
    } }
    @Test fun queuePersistsEnqueuedAndCompletedJobs() { runBlocking {
        val directory = Files.createTempDirectory("fikrado-queue-persistence").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = JsonFileRenderJobStore(directory)
            val queue = RenderQueue(scope, RenderOrchestrator(EngineRegistry(listOf(QueueAdapter()))), store)
            queue.enqueue(plan("persisted"))
            queue.jobs.first { it.singleOrNull()?.status == RenderJobStatus.SUCCEEDED }

            val restored = store.loadAll().single()
            assertEquals(RenderJobStatus.SUCCEEDED, restored.snapshot.status)
            assertEquals("output://persisted.mp4", restored.snapshot.outputUri)
            assertEquals("persisted", restored.plan.jobId.value)
        } finally {
            scope.cancel()
            directory.deleteRecursively()
        }
    } }

    @Test fun restoreRequeuesJobsInterruptedWhileRunning() { runBlocking {
        val directory = Files.createTempDirectory("fikrado-queue-recovery").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = JsonFileRenderJobStore(directory)
            val interrupted = plan("interrupted")
            store.save(StoredRenderJob(
                interrupted,
                RenderJobSnapshot(interrupted.jobId, RenderJobStatus.RUNNING, interrupted.engineId, EngineProgress(0.6f))
            ))
            val queue = RenderQueue(scope, RenderOrchestrator(EngineRegistry(listOf(QueueAdapter()))), store)
            queue.restore()

            val completed = queue.jobs.first { jobs ->
                jobs.singleOrNull()?.status == RenderJobStatus.SUCCEEDED
            }
            assertEquals(RenderJobStatus.SUCCEEDED, completed.single().status)
            assertEquals(RenderJobStatus.SUCCEEDED, store.loadAll().single().snapshot.status)
        } finally {
            scope.cancel()
            directory.deleteRecursively()
        }
    } }

}
