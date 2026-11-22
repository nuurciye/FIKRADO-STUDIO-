package com.cuscus.ffmpeggui.core.production.model

import com.cuscus.ffmpeggui.core.project.Json
import com.cuscus.ffmpeggui.core.project.JsonText
import com.cuscus.ffmpeggui.core.project.jsonObject

/**
 * Versioned codec for the new production schema. It is deliberately separate
 * from ModelJson so existing editor project files are not changed.
 */
object ProductionProjectJson {
    fun encode(project: ProductionProject): String = JsonText.write(write(project))

    fun decode(text: String): ProductionProject {
        val root = JsonText.parse(text) as? Json.Obj ?: error("Production project must be a JSON object")
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
        val settings = o.obj("settings") ?: error("Missing settings")
        val metadata = o.obj("metadata")
        val assets = o.list("assets", emptyList()) { v ->
            val a = v as? Json.Obj ?: return@list null
            val id = a.stringOrNull("id") ?: return@list null
            val kind = enumValue<AssetKind>(a.stringOrNull("kind")) ?: return@list null
            val uri = a.stringOrNull("uri") ?: return@list null
            val name = a.stringOrNull("displayName") ?: return@list null
            ProductionAsset(AssetId(id), kind, uri, name, a.stringOrNull("mimeType"),
                a.longOrNull("durationMs"), a.stringOrNull("checksumSha256"), readStringMap(a.obj("metadata")))
        }
        val scenes = o.list("scenes", emptyList()) { v ->
            val s = v as? Json.Obj ?: return@list null
            val id = s.stringOrNull("id") ?: return@list null
            val name = s.stringOrNull("name") ?: return@list null
            val duration = s.longOrNull("durationMs") ?: return@list null
            val layers = s.list("layers", emptyList()) { lv ->
                val l = lv as? Json.Obj ?: return@list null
                val lid = l.stringOrNull("id") ?: return@list null
                val kind = enumValue<LayerKind>(l.stringOrNull("kind")) ?: return@list null
                val transform = l.obj("transform")
                val ext = l.obj("engineExtensions")?.fields?.mapNotNull { (key, value) ->
                    val map = value as? Json.Obj ?: return@mapNotNull null
                    key to readStringMap(map)
                }?.toMap().orEmpty()
                ProductionLayer(
                    LayerId(lid), kind, l.long("startMs", 0), l.long("durationMs", 0),
                    l.stringOrNull("assetId")?.let(::AssetId),
                    LayerTransform(
                        transform?.float("x", 0f) ?: 0f, transform?.float("y", 0f) ?: 0f,
                        transform?.float("scaleX", 1f) ?: 1f, transform?.float("scaleY", 1f) ?: 1f,
                        transform?.float("rotationDegrees", 0f) ?: 0f,
                    ),
                    l.float("opacity", 1f), l.int("zIndex", 0), readStringMap(l.obj("properties")), ext
                )
            }
            ProductionScene(id.let(::SceneId), name, duration, layers, s.longOrNull("backgroundColorArgb"))
        }
        val profile = enumValue<ProductionProfile>(o.stringOrNull("profile")) ?: error("Unknown production profile")
        return ProductionProject(
            o.int("schemaVersion", 0),
            ProjectId(o.stringOrNull("id") ?: error("Missing project ID")),
            o.stringOrNull("name") ?: error("Missing project name"),
            profile,
            ProductionSettings(
                settings.int("width", 0), settings.int("height", 0),
                settings.int("frameRateNumerator", 0), settings.int("frameRateDenominator", 0),
                settings.longOrNull("backgroundColorArgb"),
            ),
            scenes, assets,
            ProjectMetadata(metadata?.longOrNull("createdAtEpochMs"), metadata?.longOrNull("modifiedAtEpochMs"), metadata?.stringOrNull("description"))
        )
    }

    private fun stringMap(values: Map<String, String>) = Json.Obj(values.mapValues { Json.Str(it.value) })
    private fun readStringMap(obj: Json.Obj?): Map<String, String> =
        obj?.fields?.mapNotNull { (k, v) -> (v as? Json.Str)?.value?.let { k to it } }?.toMap().orEmpty()

    private inline fun <reified T : Enum<T>> enumValue(value: String?): T? =
        value?.let { name -> enumValues<T>().firstOrNull { it.name == name } }
}
