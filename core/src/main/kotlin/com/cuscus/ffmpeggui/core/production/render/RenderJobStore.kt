package com.cuscus.ffmpeggui.core.production.render

import com.cuscus.ffmpeggui.core.project.Json
import com.cuscus.ffmpeggui.core.project.JsonText
import com.cuscus.ffmpeggui.core.project.jsonObject
import com.cuscus.ffmpeggui.core.production.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class StoredRenderJob(
    val plan: RenderPlan,
    val snapshot: RenderJobSnapshot,
)

interface RenderJobStore {
    suspend fun save(job: StoredRenderJob)
    suspend fun loadAll(): List<StoredRenderJob>
    suspend fun delete(jobId: RenderJobId)
}

/**
 * Durable one-file-per-job JSON store for JVM/Android file-backed hosts.
 *
 * Writes use a sibling temporary file and an atomic rename when supported.
 * The core queue depends only on RenderJobStore, so Room/SQL or cloud-backed
 * implementations can replace this without changing orchestration.
 */
class JsonFileRenderJobStore(
    private val directory: File,
) : RenderJobStore {
    override suspend fun save(job: StoredRenderJob) = withContext(Dispatchers.IO) {
        require(job.plan.jobId == job.snapshot.jobId) { "Plan and snapshot job IDs must match." }
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create render-job directory: $directory")
        }
        val destination = fileFor(job.plan.jobId)
        val temporary = File(directory, "${destination.name}.tmp")
        try {
            temporary.writeText(RenderJobStoreJson.encode(job), Charsets.UTF_8)
            try {
                Files.move(
                    temporary.toPath(), destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    override suspend fun loadAll(): List<StoredRenderJob> = withContext(Dispatchers.IO) {
        if (!directory.exists()) return@withContext emptyList()
        directory.listFiles { file -> file.isFile && file.name.endsWith(".render.json") }
            .orEmpty()
            .sortedBy { it.name }
            .map { file ->
                val job = try {
                    RenderJobStoreJson.decode(file.readText(Charsets.UTF_8))
                } catch (failure: Exception) {
                    throw IOException("Invalid persisted render job file: ${file.name}", failure)
                }
                require(file.name == fileFor(job.plan.jobId).name) {
                    "Render job filename does not match its job ID: ${file.name}"
                }
                job
            }
    }

    override suspend fun delete(jobId: RenderJobId) = withContext(Dispatchers.IO) {
        val file = fileFor(jobId)
        if (file.exists() && !file.delete()) throw IOException("Could not delete render job: $file")
    }

    private fun fileFor(jobId: RenderJobId): File {
        val safe = jobId.value.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
        return File(directory, "$safe.render.json")
    }
}

private object RenderJobStoreJson {
    fun encode(job: StoredRenderJob): String = JsonText.write(write(job))

    fun decode(text: String): StoredRenderJob {
        val root = JsonText.parse(text) as? Json.Obj
            ?: throw IllegalArgumentException("Stored render job must be a JSON object.")
        return read(root)
    }

    private fun write(job: StoredRenderJob) = jsonObject {
        put("storeVersion", 1)
        put("plan", jsonObject {
            put("jobId", job.plan.jobId.value)
            put("engineId", job.plan.engineId.value)
            put("project", ProductionProjectJson.write(job.plan.project))
            putList("sceneIds", job.plan.sceneIds) { Json.Str(it.value) }
            put("output", jsonObject {
                put("width", job.plan.output.width)
                put("height", job.plan.output.height)
                put("frameRateNumerator", job.plan.output.frameRateNumerator)
                put("frameRateDenominator", job.plan.output.frameRateDenominator)
                put("container", job.plan.output.container)
                put("videoCodec", job.plan.output.videoCodec)
                put("audioCodec", job.plan.output.audioCodec)
                put("outputUri", job.plan.output.outputUri)
            })
        })
        put("snapshot", jsonObject {
            put("jobId", job.snapshot.jobId.value)
            put("status", job.snapshot.status.name)
            put("engineId", job.snapshot.engineId.value)
            put("progress", job.snapshot.progress?.fraction)
            put("progressMessage", job.snapshot.progress?.message)
            put("outputUri", job.snapshot.outputUri)
            putList("issues", job.snapshot.issues) { issue -> jsonObject {
                put("code", issue.code); put("path", issue.path); put("message", issue.message)
            } }
        })
    }

    private fun read(root: Json.Obj): StoredRenderJob {
        require(root.requiredInt("storeVersion") == 1) { "Unsupported render-job store version." }
        val p = root.requiredObject("plan")
        val output = p.requiredObject("output")
        val project = ProductionProjectJson.read(p.requiredObject("project"))
        val sceneIds = p.requiredArray("sceneIds").mapIndexed { index, value ->
            SceneId((value as? Json.Str)?.value ?: error("plan.sceneIds[$index] must be a string."))
        }
        val plan = RenderPlan(
            jobId = RenderJobId(p.requiredString("jobId")),
            engineId = EngineId(p.requiredString("engineId")),
            project = project,
            sceneIds = sceneIds,
            output = RenderOutputSpec(
                width = output.requiredInt("width"),
                height = output.requiredInt("height"),
                frameRateNumerator = output.requiredInt("frameRateNumerator"),
                frameRateDenominator = output.requiredInt("frameRateDenominator"),
                container = output.requiredString("container"),
                videoCodec = output.optionalString("videoCodec"),
                audioCodec = output.optionalString("audioCodec"),
                outputUri = output.requiredString("outputUri"),
            ),
        )
        val s = root.requiredObject("snapshot")
        val snapshotId = RenderJobId(s.requiredString("jobId"))
        val engineId = EngineId(s.requiredString("engineId"))
        require(snapshotId == plan.jobId && engineId == plan.engineId) {
            "Stored plan and snapshot identity mismatch."
        }
        val progress = s.optionalFloat("progress")?.let { fraction ->
            EngineProgress(fraction, s.optionalString("progressMessage"))
        }
        val issues = s.requiredArray("issues").mapIndexed { index, value ->
            val issue = value as? Json.Obj ?: error("snapshot.issues[$index] must be an object.")
            RenderIssue(issue.requiredString("code"), issue.requiredString("path"), issue.requiredString("message"))
        }
        return StoredRenderJob(
            plan,
            RenderJobSnapshot(
                jobId = snapshotId,
                status = s.requiredEnum("status"),
                engineId = engineId,
                progress = progress,
                outputUri = s.optionalString("outputUri"),
                issues = issues,
            )
        )
    }

    private fun Json.Obj.requiredObject(key: String) =
        this[key] as? Json.Obj ?: throw IllegalArgumentException("Missing or invalid object: $key")
    private fun Json.Obj.requiredArray(key: String) =
        (this[key] as? Json.Arr)?.items ?: throw IllegalArgumentException("Missing or invalid array: $key")
    private fun Json.Obj.requiredString(key: String) =
        (this[key] as? Json.Str)?.value ?: throw IllegalArgumentException("Missing or invalid string: $key")
    private fun Json.Obj.optionalString(key: String): String? = when (val value = this[key]) {
        null, Json.Null -> null
        is Json.Str -> value.value
        else -> throw IllegalArgumentException("Invalid string: $key")
    }
    private fun Json.Obj.requiredLong(key: String) =
        (this[key] as? Json.Num)?.text?.toLongOrNull()
            ?: throw IllegalArgumentException("Missing or invalid integer: $key")
    private fun Json.Obj.requiredInt(key: String): Int {
        val value = requiredLong(key)
        require(value in Int.MIN_VALUE..Int.MAX_VALUE) { "Integer out of range: $key" }
        return value.toInt()
    }
    private fun Json.Obj.optionalFloat(key: String): Float? = when (val value = this[key]) {
        null, Json.Null -> null
        is Json.Num -> value.text.toFloatOrNull()?.takeIf { it.isFinite() }
            ?: throw IllegalArgumentException("Invalid number: $key")
        else -> throw IllegalArgumentException("Invalid number: $key")
    }
    private inline fun <reified T : Enum<T>> Json.Obj.requiredEnum(key: String): T {
        val name = requiredString(key)
        return enumValues<T>().firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("Unknown $key value: $name")
    }
}
