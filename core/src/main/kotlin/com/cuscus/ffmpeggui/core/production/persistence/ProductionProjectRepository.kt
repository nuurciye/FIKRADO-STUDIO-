package com.cuscus.ffmpeggui.core.production.persistence

import com.cuscus.ffmpeggui.core.production.model.ProductionProject
import com.cuscus.ffmpeggui.core.production.model.ProductionProjectJson
import com.cuscus.ffmpeggui.core.production.model.ProjectId
import com.cuscus.ffmpeggui.core.production.validation.ProjectValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Durable storage boundary for production projects.
 *
 * Implementations can later be replaced by Room or cloud-backed storage
 * without coupling the production model to Android or a database.
 */
interface ProductionProjectRepository {
    suspend fun save(project: ProductionProject)
    suspend fun load(projectId: ProjectId): ProductionProject?
    suspend fun loadAll(): List<ProductionProject>
    suspend fun delete(projectId: ProjectId)
}

/**
 * JVM/Android file-backed repository. Each project is one versioned JSON file.
 * Writes are staged in a sibling temporary file and atomically renamed when
 * the filesystem supports it.
 */
class JsonFileProductionProjectRepository(
    private val directory: File,
    private val validator: ProjectValidator = ProjectValidator(),
) : ProductionProjectRepository {

    override suspend fun save(project: ProductionProject) = withContext(Dispatchers.IO) {
        validate(project)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create production-project directory: $directory")
        }

        val destination = fileFor(project.id)
        val temporary = File(directory, "${destination.name}.tmp")
        try {
            temporary.writeText(ProductionProjectJson.encode(project), Charsets.UTF_8)
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
        Unit
    }

    override suspend fun load(projectId: ProjectId): ProductionProject? = withContext(Dispatchers.IO) {
        val file = fileFor(projectId)
        if (!file.isFile) return@withContext null
        decodeFile(file).also { project ->
            require(project.id == projectId) {
                "Stored production-project identity does not match its filename."
            }
        }
    }

    override suspend fun loadAll(): List<ProductionProject> = withContext(Dispatchers.IO) {
        if (!directory.exists()) return@withContext emptyList()
        directory.listFiles { file ->
            file.isFile && file.name.endsWith(FILE_SUFFIX)
        }.orEmpty()
            .sortedBy { it.name }
            .map { file ->
                val project = decodeFile(file)
                require(file.name == fileFor(project.id).name) {
                    "Production-project filename does not match its ID: ${file.name}"
                }
                project
            }
    }

    override suspend fun delete(projectId: ProjectId) = withContext(Dispatchers.IO) {
        val file = fileFor(projectId)
        if (file.exists() && !file.delete()) {
            throw IOException("Could not delete production project: $file")
        }
        Unit
    }

    private fun decodeFile(file: File): ProductionProject {
        val project = try {
            ProductionProjectJson.decode(file.readText(Charsets.UTF_8))
        } catch (failure: Exception) {
            throw IOException("Invalid production-project file: ${file.name}", failure)
        }
        validate(project)
        return project
    }

    private fun validate(project: ProductionProject) {
        val result = validator.validate(project)
        require(result.isValid) {
            result.issues.joinToString(
                prefix = "Invalid production project: ",
                separator = "; ",
            ) { "${it.path}: ${it.message}" }
        }
    }

    /** Hex encoding keeps arbitrary IDs out of filesystem path syntax. */
    private fun fileFor(projectId: ProjectId): File {
        require(projectId.value.isNotBlank()) { "Project ID must not be blank." }
        val safe = projectId.value.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
        return File(directory, "$safe$FILE_SUFFIX")
    }

    private companion object {
        const val FILE_SUFFIX = ".production.json"
    }
}
