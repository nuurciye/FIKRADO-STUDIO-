package com.cuscus.ffmpeggui.core.production.render

import kotlinx.coroutines.CancellationException

data class RenderExecutionResult(
    val engineId: EngineId? = null,
    val renderResult: EngineRenderResult? = null,
    val issues: List<RenderIssue> = emptyList(),
) {
    val isSuccess: Boolean get() = renderResult?.success == true && issues.isEmpty()
}

/**
 * Executes a previously validated plan through its selected adapter.
 *
 * This is deliberately a single-job coordinator, not a queue. Scheduling,
 * persistence and bounded concurrency belong to the host application/worker.
 */
class RenderOrchestrator(
    private val registry: EngineRegistry,
) {
    suspend fun execute(
        plan: RenderPlan,
        onProgress: (EngineProgress) -> Unit = {},
    ): RenderExecutionResult {
        val adapter = registry.find(plan.engineId)
            ?: return RenderExecutionResult(
                engineId = plan.engineId,
                issues = listOf(RenderIssue(
                    "engine_unavailable",
                    "engineId",
                    "Planned engine '${plan.engineId.value}' is no longer registered."
                ))
            )

        val request = EngineRenderRequest(
            jobId = plan.jobId,
            project = plan.project,
            sceneIds = plan.sceneIds,
            output = plan.output,
        )

        return try {
            val validationIssues = adapter.validate(request)
            if (validationIssues.isNotEmpty()) {
                RenderExecutionResult(engineId = adapter.id, issues = validationIssues)
            } else {
                val result = adapter.render(request) { progress ->
                    onProgress(progress)
                }
                RenderExecutionResult(engineId = adapter.id, renderResult = result)
            }
        } catch (cancelled: CancellationException) {
            // Cancellation is control flow; never convert it into a render failure.
            throw cancelled
        } catch (failure: Exception) {
            RenderExecutionResult(
                engineId = adapter.id,
                issues = listOf(RenderIssue(
                    "engine_execution_failed",
                    "engineId",
                    failure.message?.takeIf { it.isNotBlank() }
                        ?: failure::class.simpleName
                        ?: "Engine execution failed."
                ))
            )
        }
    }
}
