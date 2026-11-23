package com.cuscus.ffmpeggui.core.production

import com.cuscus.ffmpeggui.core.production.model.*
import com.cuscus.ffmpeggui.core.production.timeline.ProductionSceneTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionSceneTimelineTest {
    private val asset = ProductionAsset(
        id = AssetId("asset-1"), kind = AssetKind.VIDEO, uri = "content://video/1",
        displayName = "source.mp4", durationMs = 10_000L,
    )
    private val layer = ProductionLayer(
        id = LayerId("layer-1"), kind = LayerKind.VIDEO, startMs = 0L,
        durationMs = 10_000L, assetId = asset.id,
    )
    private val scene = ProductionScene(SceneId("scene-1"), "Scene 1", 10_000L, listOf(layer))

    @Test fun splitPreservesSourceRangesAndTotalDuration() {
        val split = ProductionSceneTimeline.split(scene, listOf(asset), 4_000L, "layer-2")
        assertEquals(2, split.layers.size)
        assertEquals(10_000L, split.durationMs)
        assertEquals("0", split.layers[0].properties[ProductionSceneTimeline.SOURCE_IN_KEY])
        assertEquals("4000", split.layers[0].properties[ProductionSceneTimeline.SOURCE_OUT_KEY])
        assertEquals("4000", split.layers[1].properties[ProductionSceneTimeline.SOURCE_IN_KEY])
        assertEquals("10000", split.layers[1].properties[ProductionSceneTimeline.SOURCE_OUT_KEY])
        assertEquals(4_000L, split.layers[1].startMs)
    }

    @Test fun moveReordersLayersAndRecomputesTimelineStarts() {
        val second = layer.copy(id = LayerId("layer-2"), startMs = 10_000L, durationMs = 5_000L)
        val secondAsset = asset.copy(id = AssetId("asset-2"), displayName = "second.mp4", durationMs = 5_000L)
        val secondLayer = second.copy(assetId = secondAsset.id)
        val two = scene.copy(durationMs = 15_000L, layers = listOf(layer, secondLayer))
        val moved = ProductionSceneTimeline.move(two, listOf(asset, secondAsset), "layer-2", -1)
        assertEquals(listOf("layer-2", "layer-1"), moved.layers.map { it.id.value })
        assertEquals(listOf(0L, 5_000L), moved.layers.map { it.startMs })
        assertEquals(15_000L, moved.durationMs)
    }

    @Test fun duplicateAndRemoveKeepSceneDurationConsistent() {
        val duplicate = ProductionSceneTimeline.duplicate(scene, listOf(asset), "layer-1", "layer-copy")
        assertEquals(2, duplicate.layers.size)
        assertEquals(20_000L, duplicate.durationMs)
        val removed = ProductionSceneTimeline.remove(duplicate, listOf(asset), "layer-copy")
        assertEquals(listOf("layer-1"), removed.layers.map { it.id.value })
        assertEquals(10_000L, removed.durationMs)
    }

    @Test fun trimEndUpdatesClipDurationAndSourceOutPoint() {
        val trimmed = ProductionSceneTimeline.trimEnd(scene, listOf(asset), "layer-1", 7_000L)
        assertEquals(7_000L, trimmed.durationMs)
        assertEquals(7_000L, trimmed.layers.single().durationMs)
        assertEquals("7000", trimmed.layers.single().properties[ProductionSceneTimeline.SOURCE_OUT_KEY])
    }

    @Test fun conversionRoundTripsSourceInAndOutPoints() {
        val trimmed = ProductionSceneTimeline.split(scene, listOf(asset), 4_000L, "layer-2")
        val timeline = ProductionSceneTimeline.toTimeline(trimmed, listOf(asset))
        val restored = ProductionSceneTimeline.apply(trimmed, listOf(asset), timeline)
        assertEquals(trimmed.layers.map { it.properties }, restored.layers.map { it.properties })
        assertTrue(restored.durationMs == timeline.durationMs)
    }
}
