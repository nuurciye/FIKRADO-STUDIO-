package com.cuscus.ffmpeggui.core.production

import com.cuscus.ffmpeggui.core.production.model.*
import com.cuscus.ffmpeggui.core.production.timeline.ProductionAudioTimeline
import org.junit.Assert.*
import org.junit.Test

class ProductionAudioTimelineTest {
    private val music = ProductionAsset(
        AssetId("music"), AssetKind.AUDIO, "content://audio/music", "music.mp3", durationMs = 20_000L,
    )

    private fun layer(
        id: String,
        start: Long,
        duration: Long,
        z: Int = 0,
        sourceIn: Long = 0,
        sourceOut: Long = sourceIn + duration,
    ) = ProductionLayer(
        id = LayerId(id),
        kind = LayerKind.AUDIO,
        startMs = start,
        durationMs = duration,
        assetId = music.id,
        zIndex = z,
        properties = mapOf(
            "sourceStartMs" to sourceIn.toString(),
            "sourceEndMs" to sourceOut.toString(),
        ),
    )

    private fun scene(vararg layers: ProductionLayer) =
        ProductionScene(SceneId("scene"), "Audio edit", 30_000L, layers.toList())

    @Test fun mapsAudioLayersToOutputClockAndSourceTrimRanges() {
        val result = ProductionAudioTimeline.project(scene(layer("voice", 4_000, 3_000, sourceIn = 2_000)), listOf(music))
        assertTrue(result.issues.toString(), result.isValid)
        val clip = result.clips.single()
        assertEquals("voice", clip.layerId)
        assertEquals(4_000L, clip.span.startMs)
        assertEquals(2_000L, clip.span.trimStartMs)
        assertEquals(5_000L, clip.span.trimEndMs)
    }

    @Test fun overlappingAudioClipsArePlacedOnSeparateRowsWithoutChangingTiming() {
        val result = ProductionAudioTimeline.project(
            scene(layer("a", 0, 5_000), layer("b", 2_000, 4_000)),
            listOf(music),
        )
        assertTrue(result.issues.toString(), result.isValid)
        assertEquals(2, result.clips.map { it.lane }.distinct().size)
        assertEquals(setOf(0L, 2_000L), result.clips.map { it.span.startMs }.toSet())
    }

    @Test fun authoredAudioLanesRemainDisjointWhenAnEarlierLaneNeedsOverlapRows() {
        val result = ProductionAudioTimeline.project(
            scene(layer("a", 0, 5_000), layer("b", 2_000, 4_000), layer("c", 0, 3_000, z = 1)),
            listOf(music),
        )
        assertTrue(result.issues.toString(), result.isValid)
        assertEquals(3, result.clips.map { it.lane }.distinct().size)
    }

    @Test fun rejectsAudioLayerThatReferencesVideoAsset() {
        val video = music.copy(kind = AssetKind.VIDEO)
        val result = ProductionAudioTimeline.project(scene(layer("voice", 0, 2_000)), listOf(video))
        assertEquals("asset_not_audio", result.issues.single().code)
    }

    @Test fun rejectsTrimRangeOutsideAudioFile() {
        val result = ProductionAudioTimeline.project(
            scene(layer("voice", 0, 2_000, sourceIn = 19_000, sourceOut = 21_000)),
            listOf(music),
        )
        assertEquals("invalid_audio_range", result.issues.single().code)
    }

    @Test fun rejectsUnsupportedSpeedAndRepeatValues() {
        val invalid = layer("voice", 0, 2_000).copy(properties = mapOf(
            "sourceStartMs" to "0",
            "sourceEndMs" to "2000",
            "speed" to "9.0",
            "repeats" to "1",
        ))
        val result = ProductionAudioTimeline.project(scene(invalid), listOf(music))
        assertEquals("invalid_audio_playback", result.issues.single().code)
    }

    @Test fun emptyAudioLaneProjectionIsValid() {
        val result = ProductionAudioTimeline.project(scene(), listOf(music))
        assertTrue(result.isValid)
        assertTrue(result.clips.isEmpty())
    }
}
