package com.cuscus.ffmpeggui.core.production.render

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RenderJobStatus {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED
}

data class RenderJobSnapshot(
    val jobId: RenderJobId,
    val status: RenderJobStatus,
    val engineId: EngineId,
    val progress: EngineProgress? = null,
    val outputUri: String? = null,
    val issues: List<RenderIssue> = emptyList(),
)

/**
 * In-memory render queue with one active worker.
 *
 * The host owns the CoroutineScope and its lifecycle. Queue state is observable
 * but intentionally not persisted; durable recovery belongs to a future store.
 */
class RenderQueue(
    private val scope: CoroutineScope,
    private val orchestrator: RenderOrchestrator,
    private val store: RenderJobStore? = null,
) {
    private val mutex = Mutex()
    private val mutableJobs = MutableStateFlow<List<RenderJobSnapshot>>(emptyList())
    private val workerJobs = mutableMapOf<RenderJobId, Job>()
    private val plans = ArrayDeque<RenderPlan>()
    private var workerRunning = false

    val jobs: StateFlow<List<RenderJobSnapshot>> = mutableJobs.asStateFlow()

    /**
     * Restores persisted jobs before accepting new work. Jobs interrupted while
     * RUNNING are re-queued because the previous worker cannot survive process death.
     */
    suspend fun restore() {
        mutex.withLock {
            require(mutableJobs.value.isEmpty()) { "Queue restore must run before enqueueing jobs." }
            val restored = store?.loadAll().orEmpty()
            restored.forEach { stored ->
                require(restored.count { it.plan.jobId == stored.plan.jobId } == 1) {
                    "Persistent store contains duplicate job ID '${stored.plan.jobId.value}'."
                }
                val recoveredSnapshot = if (stored.snapshot.status == RenderJobStatus.RUNNING) {
                    stored.snapshot.copy(status = RenderJobStatus.QUEUED, progress = null)
                } else stored.snapshot
                mutableJobs.value = mutableJobs.value + recoveredSnapshot
                if (recoveredSnapshot.status == RenderJobStatus.QUEUED) {
                    plans.addLast(stored.plan)
                }
                if (recoveredSnapshot != stored.snapshot) {
                    store?.save(stored.copy(snapshot = recoveredSnapshot))
                }
            }
            if (plans.isNotEmpty()) startWorkerIfNeeded()
        }
    }

    suspend fun enqueue(plan: RenderPlan): RenderJobSnapshot {
        val snapshot = RenderJobSnapshot(
            jobId = plan.jobId,
            status = RenderJobStatus.QUEUED,
            engineId = plan.engineId,
        )
        mutex.withLock {
            require(mutableJobs.value.none { it.jobId == plan.jobId }) {
                "Render job '${plan.jobId.value}' is already known to this queue."
            }
            store?.save(StoredRenderJob(plan, snapshot))
            plans.addLast(plan)
            mutableJobs.value = mutableJobs.value + snapshot
            startWorkerIfNeeded()
        }
        return snapshot
    }

    suspend fun cancel(jobId: RenderJobId): Boolean {
        val worker: Job?
        mutex.withLock {
            val current = mutableJobs.value.firstOrNull { it.jobId == jobId } ?: return false
            when (current.status) {
                RenderJobStatus.QUEUED -> {
                    plans.removeAll { it.jobId == jobId }
                    update(jobId) { it.copy(status = RenderJobStatus.CANCELLED) }
                    persist(jobId)
                    return true
                }
                RenderJobStatus.RUNNING -> worker = workerJobs[jobId]
                RenderJobStatus.SUCCEEDED,
                RenderJobStatus.FAILED,
                RenderJobStatus.CANCELLED -> return false
            }
        }
        worker?.cancel(CancellationException("Render cancelled by request."))
        return true
    }

    suspend fun find(jobId: RenderJobId): RenderJobSnapshot? =
        mutex.withLock { mutableJobs.value.firstOrNull { it.jobId == jobId } }

    private fun startWorkerIfNeeded() {
        if (workerRunning) return
        workerRunning = true
        scope.launch { drainQueue() }
    }

    private suspend fun drainQueue() {
        try {
            while (true) {
                val plan = mutex.withLock {
                    if (plans.isEmpty()) {
                        workerRunning = false
                        return
                    }
                    plans.removeFirst()
                }

                // Register the lazy job before exposing RUNNING, so cancel()
                // can never observe a running item without its worker handle.
                val worker = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val result = orchestrator.execute(plan) { progress ->
                            scope.launch {
                                mutex.withLock {
                                    update(plan.jobId) { it.copy(progress = progress) }
                                persist(plan.jobId)
                                }
                            }
                        }
                        mutex.withLock {
                            update(plan.jobId) {
                                if (result.isSuccess) {
                                    it.copy(
                                        status = RenderJobStatus.SUCCEEDED,
                                        outputUri = result.renderResult?.outputUri,
                                        issues = emptyList(),
                                    )
                                } else {
                                    it.copy(
                                        status = RenderJobStatus.FAILED,
                                        issues = result.issues.ifEmpty {
                                            listOf(RenderIssue(
                                                result.renderResult?.errorCode ?: "render_failed",
                                                "render",
                                                result.renderResult?.errorMessage ?: "Render did not complete successfully."
                                            ))
                                        },
                                    )
                                }
                            }
                        }
                        mutex.withLock { persist(plan.jobId) }
                    } catch (cancelled: CancellationException) {
                        withContext(NonCancellable) {
                            mutex.withLock {
                                update(plan.jobId) { it.copy(status = RenderJobStatus.CANCELLED) }
                                persist(plan.jobId)
                            }
                        }
                        throw cancelled
                    }
                }
                mutex.withLock {
                    activePlans[plan.jobId] = plan
                    workerJobs[plan.jobId] = worker
                    update(plan.jobId) { it.copy(status = RenderJobStatus.RUNNING) }
                    persist(plan.jobId)
                }
                worker.start()
                try {
                    worker.join()
                } finally {
                    mutex.withLock { workerJobs.remove(plan.jobId); activePlans.remove(plan.jobId) }
                }
            }
        } finally {
            mutex.withLock {
                workerRunning = false
                if (plans.isNotEmpty()) startWorkerIfNeeded()
            }
        }
    }

    private suspend fun persist(jobId: RenderJobId) {
        val persistence = store ?: return
        val snapshot = mutableJobs.value.firstOrNull { it.jobId == jobId } ?: return
        val plan = plans.firstOrNull { it.jobId == jobId }
            ?: activePlans[jobId]
            ?: return
        persistence.save(StoredRenderJob(plan, snapshot))
    }

    private val activePlans = mutableMapOf<RenderJobId, RenderPlan>()

    private fun update(jobId: RenderJobId, transform: (RenderJobSnapshot) -> RenderJobSnapshot) {
        mutableJobs.value = mutableJobs.value.map { if (it.jobId == jobId) transform(it) else it }
    }
}
