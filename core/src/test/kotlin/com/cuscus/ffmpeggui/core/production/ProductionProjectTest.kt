package com.cuscus.ffmpeggui.core.production

import com.cuscus.ffmpeggui.core.production.model.*
import com.cuscus.ffmpeggui.core.production.validation.ProjectValidator
import org.junit.Assert.*
import org.junit.Test

class ProductionProjectTest {
    private fun sample() = ProductionProject(
        id = ProjectId("p1"), name = "Product film", profile = ProductionProfile.ADVERTISING,
        settings = ProductionSettings(1920, 1080, 30000, 1001),
        assets = listOf(ProductionAsset(AssetId("a1"), AssetKind.VIDEO, "content://source/1", "clip.mp4")),
        scenes = listOf(ProductionScene(
            SceneId("s1"), "Opening", 5000,
            listOf(ProductionLayer(LayerId("l1"), LayerKind.VIDEO, 0, 5000, AssetId("a1")))
        ))
    )

    private fun assertDecodeFails(json: String) {
        try {
            ProductionProjectJson.decode(json)
            fail("Expected malformed production project JSON to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: strict decoder must not silently discard or default malformed data.
        }
    }

    @Test fun productionProjectJsonRoundTrips() {
        val source = sample()
        assertEquals(source, ProductionProjectJson.decode(ProductionProjectJson.encode(source)))
    }

    @Test fun decoderRejectsUnsupportedSchemaVersion() {
        val json = ProductionProjectJson.encode(sample()).replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        assertDecodeFails(json)
    }

    @Test fun decoderRejectsMalformedRecordsInsteadOfDroppingThem() {
        val json = ProductionProjectJson.encode(sample()).replace("\"assets\": [", "\"assets\": [null,")
        assertDecodeFails(json)
    }

    @Test fun decoderRejectsMissingRequiredLayerFields() {
        val json = ProductionProjectJson.encode(sample()).replace("\"startMs\": 0,", "")
        assertDecodeFails(json)
    }

    @Test fun decoderRejectsWrongTypesInStringMapsAndExtensions() {
        val mapJson = ProductionProjectJson.encode(sample()).replace("\"metadata\": {},", "\"metadata\": {\"unexpected\": 7},")
        assertDecodeFails(mapJson)

        val extensionJson = ProductionProjectJson.encode(sample()).replace(
            "\"engineExtensions\": {}", "\"engineExtensions\": {\"renderer\": 42}"
        )
        assertDecodeFails(extensionJson)
    }

    @Test fun validatorAcceptsValidProject() {
        assertTrue(ProjectValidator().validate(sample()).isValid)
    }

    @Test fun validatorReportsMissingAssetAndInvalidOpacity() {
        val p = sample().copy(scenes = listOf(ProductionScene(
            SceneId("s1"), "Opening", 5000,
            listOf(ProductionLayer(LayerId("l1"), LayerKind.IMAGE, 0, 5000, AssetId("missing"), opacity = 2f))
        )))
        val result = ProjectValidator().validate(p)
        assertFalse(result.isValid)
        assertTrue(result.issues.any { it.code == "missing_asset" })
        assertTrue(result.issues.any { it.code == "invalid_opacity" })
    }

    @Test fun validatorRejectsDuplicateIdentifiersAndInvalidFrameRate() {
        val p = sample().copy(
            settings = ProductionSettings(1920, 1080, 0, 1),
            assets = sample().assets + sample().assets
        )
        val result = ProjectValidator().validate(p)
        assertTrue(result.issues.any { it.code == "duplicate_asset_id" })
        assertTrue(result.issues.any { it.code == "invalid_frame_rate" })
    }
}
