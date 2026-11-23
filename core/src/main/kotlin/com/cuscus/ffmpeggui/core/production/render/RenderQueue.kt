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
) {
    private val mutex = Mutex()
    private val mutableJobs = MutableStateFlow<List<RenderJobSnapshot>>(emptyList())
    private val workerJobs = mutableMapOf<RenderJobId, Job>()
    private val plans = ArrayDeque<RenderPlan>()
    private var workerRunning = false

    val jobs: StateFlow<List<RenderJobSnapshot>> = mutableJobs.asStateFlow()

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
                    } catch (cancelled: CancellationException) {
                        withContext(NonCancellable) {
                            mutex.withLock {
                                update(plan.jobId) { it.copy(status = RenderJobStatus.CANCELLED) }
                            }
                        }
                        throw cancelled
                    }
                }
                mutex.withLock {
                    workerJobs[plan.jobId] = worker
                    update(plan.jobId) { it.copy(status = RenderJobStatus.RUNNING) }
                }
                worker.start()
                try {
                    worker.join()
                } finally {
                    mutex.withLock { workerJobs.remove(plan.jobId) }
                }
            }
        } finally {
            mutex.withLock {
                workerRunning = false
                if (plans.isNotEmpty()) startWorkerIfNeeded()
            }
        }
    }

    private fun update(jobId: RenderJobId, transform: (RenderJobSnapshot) -> RenderJobSnapshot) {
        mutableJobs.value = mutableJobs.value.map { if (it.jobId == jobId) transform(it) else it }
    }
}
