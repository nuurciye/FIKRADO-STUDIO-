package com.cuscus.ffmpeggui.core.production.timeline

import com.cuscus.ffmpeggui.core.production.model.AssetId
import com.cuscus.ffmpeggui.core.production.model.AssetKind
import com.cuscus.ffmpeggui.core.production.model.LayerId
import com.cuscus.ffmpeggui.core.production.model.LayerKind
import com.cuscus.ffmpeggui.core.production.model.ProductionAsset
import com.cuscus.ffmpeggui.core.production.model.ProductionLayer
import com.cuscus.ffmpeggui.core.production.model.ProductionScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProductionLayerTimelineTest {
    private val asset = ProductionAsset(
        id = AssetId("video-1"),
        kind = AssetKind.VIDEO,
        uri = "asset://video-1",
        displayName = "clip.mp4",
        durationMs = 12_000L,
    )
    private val layer = ProductionLayer(
        id = LayerId("clip-1"),
        kind = LayerKind.VIDEO,
        startMs = 2_000L,
        durationMs = 5_000L,
        assetId = asset.id,
        properties = mapOf(
            ProductionSceneTimeline.SOURCE_IN_KEY to "1000",
            ProductionSceneTimeline.SOURCE_OUT_KEY to "6000",
        ),
    )
    private val audio = ProductionLayer(
        id = LayerId("audio-1"),
        kind = LayerKind.AUDIO,
        startMs = 0L,
        durationMs = 8_000L,
        zIndex = -1,
    )
    private val scene = ProductionScene(
        id = com.cuscus.ffmpeggui.core.production.model.SceneId("scene-1"),
        name = "Scene 1",
        durationMs = 10_000L,
        layers = listOf(layer, audio),
    )

    @Test fun movePreservesOtherTracksAndClipDuration() {
        val moved = ProductionLayerTimeline.move(scene, "clip-1", 1_500L)
        val video = moved.layers.first { it.id.value == "clip-1" }
        assertEquals(3_500L, video.startMs)
        assertEquals(5_000L, video.durationMs)
        assertEquals(0L, moved.layers.first { it.id.value == "audio-1" }.startMs)
    }

    @Test fun splitKeepsBothSourceRangesAndTimelinePositions() {
        val split = ProductionLayerTimeline.split(scene, "clip-1", 4_000L, "clip-1-b")
        val left = split.layers.first { it.id.value == "clip-1" }
        val right = split.layers.first { it.id.value == "clip-1-b" }
        assertEquals(2_000L, left.startMs)
        assertEquals(2_000L, left.durationMs)
        assertEquals("1000", left.properties[ProductionSceneTimeline.SOURCE_IN_KEY])
        assertEquals("3000", left.properties[ProductionSceneTimeline.SOURCE_OUT_KEY])
        assertEquals(4_000L, right.startMs)
        assertEquals(3_000L, right.durationMs)
        assertEquals("3000", right.properties[ProductionSceneTimeline.SOURCE_IN_KEY])
        assertEquals("6000", right.properties[ProductionSceneTimeline.SOURCE_OUT_KEY])
        assertEquals(0L, split.layers.first { it.id.value == "audio-1" }.startMs)
    }

    @Test fun trimEndUpdatesSourceOutAndDurationWithoutMovingLayer() {
        val trimmed = ProductionLayerTimeline.trimEnd(scene, listOf(asset), "clip-1", -1_500L)
        val video = trimmed.layers.first { it.id.value == "clip-1" }
        assertEquals(2_000L, video.startMs)
        assertEquals(3_500L, video.durationMs)
        assertEquals("4500", video.properties[ProductionSceneTimeline.SOURCE_OUT_KEY])
    }

    @Test fun duplicateCreatesIndependentLayerOnSameTrackAfterOriginal() {
        val duplicated = ProductionLayerTimeline.duplicate(scene, "clip-1", "clip-2")
        val copy = duplicated.layers.first { it.id.value == "clip-2" }
        assertNotEquals(layer.id, copy.id)
        assertEquals(7_000L, copy.startMs)
        assertEquals(layer.zIndex, copy.zIndex)
        assertEquals(12_000L, duplicated.durationMs)
    }

    @Test fun removeRecomputesSceneEndFromRemainingLayers() {
        val removed = ProductionLayerTimeline.remove(scene, "clip-1")
        assertEquals(1, removed.layers.size)
        assertEquals(8_000L, removed.durationMs)
    }
}
