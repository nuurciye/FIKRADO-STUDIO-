package com.cuscus.ffmpeggui.core.production.persistence

import com.cuscus.ffmpeggui.core.production.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JsonFileProductionProjectRepositoryTest {
    private fun project(id: String = "project/one", name: String = "Documentary") =
        ProductionProject(
            id = ProjectId(id),
            name = name,
            profile = ProductionProfile.DOCUMENTARY,
            settings = ProductionSettings(1920, 1080, 30),
            assets = listOf(
                ProductionAsset(AssetId("asset-1"), AssetKind.VIDEO, "content://media/clip", "clip.mp4")
            ),
            scenes = listOf(
                ProductionScene(
                    SceneId("scene-1"),
                    "Opening",
                    3000,
                    listOf(
                        ProductionLayer(LayerId("layer-1"), LayerKind.VIDEO, 0, 3000, AssetId("asset-1"))
                    )
                )
            )
        )

    @Test fun savesAndLoadsCompleteProject() = runBlocking {
        val directory = Files.createTempDirectory("fikrado-project-store").toFile()
        try {
            val repository = JsonFileProductionProjectRepository(directory)
            val original = project()
            repository.save(original)

            assertEquals(original, repository.load(original.id))
            assertEquals(listOf(original), repository.loadAll())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun savingSameIdReplacesExistingProject() = runBlocking {
        val directory = Files.createTempDirectory("fikrado-project-store").toFile()
        try {
            val repository = JsonFileProductionProjectRepository(directory)
            repository.save(project(name = "First"))
            repository.save(project(name = "Updated"))

            assertEquals("Updated", repository.load(ProjectId("project/one"))?.name)
            assertEquals(1, directory.listFiles()?.count { it.name.endsWith(".production.json") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun deletesOnlyRequestedProject() = runBlocking {
        val directory = Files.createTempDirectory("fikrado-project-store").toFile()
        try {
            val repository = JsonFileProductionProjectRepository(directory)
            repository.save(project("one"))
            repository.save(project("two"))

            repository.delete(ProjectId("one"))

            assertNull(repository.load(ProjectId("one")))
            assertEquals(listOf("two"), repository.loadAll().map { it.id.value })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun missingDirectoryLoadsAsEmpty() = runBlocking {
        val parent = Files.createTempDirectory("fikrado-project-store").toFile()
        val directory = File(parent, "not-created")
        try {
            assertTrue(JsonFileProductionProjectRepository(directory).loadAll().isEmpty())
            assertNull(JsonFileProductionProjectRepository(directory).load(ProjectId("missing")))
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test fun rejectsInvalidProjectsBeforeWriting() = runBlocking {
        val directory = Files.createTempDirectory("fikrado-project-store").toFile()
        try {
            val invalid = project().copy(name = " ")
            try {
                JsonFileProductionProjectRepository(directory).save(invalid)
                fail("Invalid project must not be persisted")
            } catch (_: IllegalArgumentException) {
                assertTrue(directory.listFiles().isNullOrEmpty())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun rejectsCorruptStoredProject() = runBlocking {
        val directory = Files.createTempDirectory("fikrado-project-store").toFile()
        try {
            File(directory, "broken.production.json").writeText("{not-json")
            try {
                JsonFileProductionProjectRepository(directory).loadAll()
                fail("Corrupt project must be rejected")
            } catch (_: java.io.IOException) {
                // Expected: corruption is surfaced rather than silently skipped.
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
