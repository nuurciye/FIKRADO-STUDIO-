package com.cuscus.ffmpeggui.core.production.render

import com.cuscus.ffmpeggui.core.production.model.ProductionProject
import com.cuscus.ffmpeggui.core.production.model.SceneId

/**
 * Application-facing submission boundary: plans first, then enqueues only a
 * fully compatible plan. The queue remains responsible for execution,
 * persistence, progress and cancellation.
 */
data class RenderSubmissionResult(
    val planning: RenderPlanResult,
    val queuedJob: RenderJobSnapshot? = null,
) {
    val accepted: Boolean get() = planning.isSuccess && queuedJob != null
}

/**
 * Keeps UI callers from accidentally bypassing capability planning or
 * enqueueing an incomplete render request.
 */
class ProductionRenderSubmission(
    private val planner: RenderPlanner,
    private val queue: RenderQueue,
) {
    suspend fun submit(
        jobId: RenderJobId,
        project: ProductionProject,
        sceneIds: List<SceneId>,
        output: RenderOutputSpec,
        preferredEngineId: EngineId? = null,
    ): RenderSubmissionResult {
        val planning = planner.plan(jobId, project, sceneIds, output, preferredEngineId)
        val plan = planning.plan ?: return RenderSubmissionResult(planning)
        val queued = queue.enqueue(plan)
        return RenderSubmissionResult(planning, queued)
    }
}
