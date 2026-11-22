package com.cuscus.ffmpeggui.core.production.render

import com.cuscus.ffmpeggui.core.production.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EngineRegistryTest {
    private class TestAdapter(override val id: EngineId) : EngineAdapter {
        override fun capabilities() = EngineCapabilities(
            supportedLayerKinds = setOf(LayerKind.VIDEO, LayerKind.TEXT),
            supportedCodecs = setOf("h264"),
            supportsAudio = true,
        )

        override suspend fun validate(request: EngineRenderRequest) = emptyList<RenderIssue>()

        override suspend fun render(
            request: EngineRenderRequest,
            onProgress: (EngineProgress) -> Unit,
        ) = EngineRenderResult(success = true, outputUri = request.output.outputUri)
    }

    @Test fun registryFindsRegisteredAdapterByStableId() {
        val adapter = TestAdapter(EngineId("ffmpeg"))
        val registry = EngineRegistry(listOf(adapter))

        assertSame(adapter, registry.find(EngineId("ffmpeg")))
        assertTrue(registry.contains(EngineId("ffmpeg")))
        assertEquals(setOf(EngineId("ffmpeg")), registry.ids())
    }

    @Test fun registryRejectsDuplicateIdsInsteadOfReplacingAdapters() {
        val original = TestAdapter(EngineId("ffmpeg"))
        val replacement = TestAdapter(EngineId("ffmpeg"))
        val registry = EngineRegistry(listOf(original))

        try {
            registry.register(replacement)
            fail("Duplicate engine IDs must be rejected")
        } catch (_: IllegalArgumentException) {
            assertSame(original, registry.require(EngineId("ffmpeg")))
        }
    }

    @Test fun registryReturnsStableRegistrationOrder() {
        val first = TestAdapter(EngineId("ffmpeg"))
        val second = TestAdapter(EngineId("remotion"))
        val registry = EngineRegistry(listOf(first, second))

        assertEquals(listOf(first, second), registry.all())
    }

    @Test fun missingEngineIsNotSilentlySubstituted() {
        val registry = EngineRegistry()

        assertNull(registry.find(EngineId("blender")))
        try {
            registry.require(EngineId("blender"))
            fail("Missing engine must be reported")
        } catch (_: NoSuchElementException) {
            // Expected: unavailable engines must never pretend to render.
        }
    }

    @Test fun capabilitiesAreExplicitAndProgressIsBounded() {
        val adapter = TestAdapter(EngineId("ffmpeg"))
        assertTrue(adapter.capabilities().supportedLayerKinds.contains(LayerKind.VIDEO))
        assertFalse(adapter.capabilities().supportedLayerKinds.contains(LayerKind.THREE_D))
        assertEquals(setOf("h264"), adapter.capabilities().supportedCodecs)

        assertEquals(0.5f, EngineProgress(0.5f).fraction)
        try {
            EngineProgress(1.5f)
            fail("Progress outside 0..1 must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test fun successfulRenderMustIdentifyItsOutput() {
        try {
            EngineRenderResult(success = true)
            fail("Successful render must identify output URI")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test fun failedRenderMustExplainFailure() {
        try {
            EngineRenderResult(success = false)
            fail("Failed render must carry an error")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
