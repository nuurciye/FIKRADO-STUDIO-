package com.cuscus.ffmpeggui.core.production.model

import com.cuscus.ffmpeggui.core.project.Json
import com.cuscus.ffmpeggui.core.project.JsonText
import com.cuscus.ffmpeggui.core.project.jsonObject

/**
 * Versioned codec for the new production schema. It is deliberately separate
 * from ModelJson so existing editor project files are not changed.
 *
 * Unlike the legacy editor codec, this codec is strict: malformed records are
 * rejected rather than silently omitted or replaced with defaults.
 */
object ProductionProjectJson {
    fun encode(project: ProductionProject): String = JsonText.write(write(project))

    fun decode(text: String): ProductionProject {
        val root = JsonText.parse(text) as? Json.Obj
            ?: error("Production project must be a JSON object")
        return read(root)
    }

    fun write(p: ProductionProject): Json.Obj = jsonObject {
        put("schemaVersion", p.schemaVersion)
        put("id", p.id.value)
        put("name", p.name)
        put("profile", p.profile.name)
        put("settings", jsonObject {
            put("width", p.settings.width); put("height", p.settings.height)
            put("frameRateNumerator", p.settings.frameRateNumerator)
            put("frameRateDenominator", p.settings.frameRateDenominator)
            put("backgroundColorArgb", p.settings.backgroundColorArgb)
        })
        put("metadata", jsonObject {
            put("createdAtEpochMs", p.metadata.createdAtEpochMs)
            put("modifiedAtEpochMs", p.metadata.modifiedAtEpochMs)
            put("description", p.metadata.description)
        })
        putList("assets", p.assets) { a -> jsonObject {
            put("id", a.id.value); put("kind", a.kind.name); put("uri", a.uri)
            put("displayName", a.displayName); put("mimeType", a.mimeType)
            put("durationMs", a.durationMs); put("checksumSha256", a.checksumSha256)
            put("metadata", stringMap(a.metadata))
        } }
        putList("scenes", p.scenes) { s -> jsonObject {
            put("id", s.id.value); put("name", s.name); put("durationMs", s.durationMs)
            put("backgroundColorArgb", s.backgroundColorArgb)
            putList("layers", s.layers) { l -> jsonObject {
                put("id", l.id.value); put("kind", l.kind.name)
                put("startMs", l.startMs); put("durationMs", l.durationMs)
                put("assetId", l.assetId?.value); put("opacity", l.opacity); put("zIndex", l.zIndex)
                put("transform", jsonObject {
                    put("x", l.transform.x); put("y", l.transform.y)
                    put("scaleX", l.transform.scaleX); put("scaleY", l.transform.scaleY)
                    put("rotationDegrees", l.transform.rotationDegrees)
                })
                put("properties", stringMap(l.properties))
                put("engineExtensions", Json.Obj(l.engineExtensions.mapValues { (_, v) -> stringMap(v) }))
            } }
        } }
    }

    fun read(o: Json.Obj): ProductionProject {
        val version = o.requiredInt("schemaVersion")
        require(version == ProductionProject.CURRENT_SCHEMA_VERSION) {
            "Unsupported production project schema version: $version"
        }

        val settings = o.requiredObject("settings")
        val metadata = o.optionalObject("metadata")
        val assets = o.requiredArray("assets").mapIndexed { index, value ->
            val a = value as? Json.Obj ?: malformed("assets[$index] must be an object")
            ProductionAsset(
                id = AssetId(a.requiredString("id")),
                kind = a.requiredEnum("kind"),
                uri = a.requiredString("uri"),
                displayName = a.requiredString("displayName"),
                mimeType = a.optionalString("mimeType"),
                durationMs = a.optionalLong("durationMs"),
                checksumSha256 = a.optionalString("checksumSha256"),
                metadata = a.requiredStringMap("metadata"),
            )
        }
        val scenes = o.requiredArray("scenes").mapIndexed { sceneIndex, value ->
            val s = value as? Json.Obj ?: malformed("scenes[$sceneIndex] must be an object")
            val layers = s.requiredArray("layers").mapIndexed { layerIndex, layerValue ->
                val path = "scenes[$sceneIndex].layers[$layerIndex]"
                val l = layerValue as? Json.Obj ?: malformed("$path must be an object")
                val t = l.requiredObject("transform")
                val extensions = l.requiredObject("engineExtensions").fields.mapValues { (engine, value) ->
                    val extension = value as? Json.Obj
                        ?: malformed("$path.engineExtensions.$engine must be an object")
                    extension.requiredStringMapContents("$path.engineExtensions.$engine")
                }
                ProductionLayer(
                    id = LayerId(l.requiredString("id")),
                    kind = l.requiredEnum("kind"),
                    startMs = l.requiredLong("startMs"),
                    durationMs = l.requiredLong("durationMs"),
                    assetId = l.optionalString("assetId")?.let(::AssetId),
                    transform = LayerTransform(
                        x = t.requiredFloat("x"),
                        y = t.requiredFloat("y"),
                        scaleX = t.requiredFloat("scaleX"),
                        scaleY = t.requiredFloat("scaleY"),
                        rotationDegrees = t.requiredFloat("rotationDegrees"),
                    ),
                    opacity = l.requiredFloat("opacity"),
                    zIndex = l.requiredInt("zIndex"),
                    properties = l.requiredStringMap("properties"),
                    engineExtensions = extensions,
                )
            }
            ProductionScene(
                id = SceneId(s.requiredString("id")),
                name = s.requiredString("name"),
                durationMs = s.requiredLong("durationMs"),
                layers = layers,
                backgroundColorArgb = s.optionalLong("backgroundColorArgb"),
            )
        }

        return ProductionProject(
            schemaVersion = version,
            id = ProjectId(o.requiredString("id")),
            name = o.requiredString("name"),
            profile = o.requiredEnum("profile"),
            settings = ProductionSettings(
                width = settings.requiredInt("width"),
                height = settings.requiredInt("height"),
                frameRateNumerator = settings.requiredInt("frameRateNumerator"),
                frameRateDenominator = settings.requiredInt("frameRateDenominator"),
                backgroundColorArgb = settings.optionalLong("backgroundColorArgb"),
            ),
            scenes = scenes,
            assets = assets,
            metadata = ProjectMetadata(
                createdAtEpochMs = metadata?.optionalLong("createdAtEpochMs"),
                modifiedAtEpochMs = metadata?.optionalLong("modifiedAtEpochMs"),
                description = metadata?.optionalString("description"),
            ),
        )
    }

    private fun stringMap(values: Map<String, String>) =
        Json.Obj(values.mapValues { Json.Str(it.value) })

    private inline fun <reified T : Enum<T>> Json.Obj.requiredEnum(key: String): T {
        val name = requiredString(key)
        return enumValues<T>().firstOrNull { it.name == name }
            ?: malformed("Unknown $key value: $name")
    }

    private fun Json.Obj.requiredObject(key: String): Json.Obj =
        this[key] as? Json.Obj ?: malformed("Missing or invalid object: $key")

    private fun Json.Obj.optionalObject(key: String): Json.Obj? = when (val value = this[key]) {
        null, Json.Null -> null
        is Json.Obj -> value
        else -> malformed("Invalid object: $key")
    }

    private fun Json.Obj.requiredArray(key: String): List<Json> =
        (this[key] as? Json.Arr)?.items ?: malformed("Missing or invalid array: $key")

    private fun Json.Obj.requiredString(key: String): String =
        (this[key] as? Json.Str)?.value ?: malformed("Missing or invalid string: $key")

    private fun Json.Obj.optionalString(key: String): String? = when (val value = this[key]) {
        null, Json.Null -> null
        is Json.Str -> value.value
        else -> malformed("Invalid string: $key")
    }

    private fun Json.Obj.requiredLong(key: String): Long =
        ((this[key] as? Json.Num)?.text?.toLongOrNull())
            ?: malformed("Missing or invalid integer: $key")

    private fun Json.Obj.optionalLong(key: String): Long? = when (val value = this[key]) {
        null, Json.Null -> null
        is Json.Num -> value.text.toLongOrNull() ?: malformed("Invalid integer: $key")
        else -> malformed("Invalid integer: $key")
    }

    private fun Json.Obj.requiredInt(key: String): Int {
        val value = requiredLong(key)
        if (value !in Int.MIN_VALUE..Int.MAX_VALUE) malformed("Integer out of range: $key")
        return value.toInt()
    }

    private fun Json.Obj.requiredFloat(key: String): Float {
        val value = (this[key] as? Json.Num)?.text?.toFloatOrNull()
            ?: malformed("Missing or invalid number: $key")
        if (!value.isFinite()) malformed("Non-finite number: $key")
        return value
    }

    private fun Json.Obj.requiredStringMap(key: String): Map<String, String> =
        requiredObject(key).requiredStringMapContents(key)

    private fun Json.Obj.requiredStringMapContents(path: String): Map<String, String> =
        fields.mapValues { (key, value) ->
            (value as? Json.Str)?.value ?: malformed("$path.$key must be a string")
        }

    private fun malformed(message: String): Nothing = throw IllegalArgumentException(message)
}
