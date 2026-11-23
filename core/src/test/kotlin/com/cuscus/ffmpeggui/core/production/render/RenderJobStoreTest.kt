package com.cuscus.ffmpeggui.core.production.render

import com.cuscus.ffmpeggui.core.production.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class RenderJobStoreTest {
    private fun stored(id: String, status: RenderJobStatus = RenderJobStatus.QUEUED): StoredRenderJob {
        val project = ProductionProject(
            id = ProjectId("p1"),
            name = "Persistent project",
            profile = ProductionProfile.DOCUMENTARY,
            settings = ProductionSettings(1920, 1080, 30),
            assets = listOf(ProductionAsset(AssetId("a1"), AssetKind.VIDEO, "asset://clip", "clip.mp4")),
            scenes = listOf(ProductionScene(
                SceneId("s1"), "Scene", 2500,
                listOf(ProductionLayer(LayerId("l1"), LayerKind.VIDEO, 0, 2500, AssetId("a1")))
            ))
        )
        val plan = RenderPlan(
            RenderJobId(id),
            EngineId("ffmpeg"),
            project,
            listOf(SceneId("s1")),
            RenderOutputSpec(1920, 1080, 30, 1, "mp4", "h264", "aac", "file:///$id.mp4")
        )
        return StoredRenderJob(
            plan,
            RenderJobSnapshot(
                jobId = plan.jobId,
                status = status,
                engineId = plan.engineId,
                progress = EngineProgress(0.4f, "Encoding"),
                outputUri = null,
                issues = listOf(RenderIssue("notice", "render", "Persisted test issue"))
            )
        )
    }

    @Test fun savesAndReloadsCompleteRenderJob() = runBlocking {
        val directory = Files.createTempDirectory("fikrado-render-store").toFile()
        try {
            val store = JsonFileRenderJobStore(directory)
            val original = stored("job/one")
            store.save(original)

            val loaded = store.loadAll().single()
            assertEquals(original, loaded)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun updatesExistingJobWithoutCreatingDuplicateFiles() = runBlocking {
        val directory = Files.createTempDirectory("fikrado-render-store").toFile()
        try {
            val store = JsonFileRenderJobStore(directory)
            store.save(stored("job-1"))
            store.save(stored("job-1", RenderJobStatus.SUCCEEDED))

            assertEquals(1, directory.listFiles()?.count { it.name.endsWith(".render.json") })
            assertEquals(RenderJobStatus.SUCCEEDED, store.loadAll().single().snapshot.status)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun deletesOnlyRequestedJob() = runBlocking {
        val directory = Files.createTempDirectory("fikrado-render-store").toFile()
        try {
            val store = JsonFileRenderJobStore(directory)
            store.save(stored("job-1"))
            store.save(stored("job-2"))
            store.delete(RenderJobId("job-1"))

            assertEquals(listOf("job-2"), store.loadAll().map { it.plan.jobId.value })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun missingDirectoryLoadsAsEmpty() = runBlocking {
        val parent = Files.createTempDirectory("fikrado-render-store").toFile()
        val directory = java.io.File(parent, "not-created")
        try {
            assertTrue(JsonFileRenderJobStore(directory).loadAll().isEmpty())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test fun rejectsMismatchedPlanAndSnapshotIdentity() = runBlocking {
        val directory = Files.createTempDirectory("fikrado-render-store").toFile()
        try {
            val original = stored("plan-id")
            val mismatched = original.copy(
                snapshot = original.snapshot.copy(jobId = RenderJobId("other-id"))
            )
            try {
                JsonFileRenderJobStore(directory).save(mismatched)
                fail("Mismatched IDs must be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
