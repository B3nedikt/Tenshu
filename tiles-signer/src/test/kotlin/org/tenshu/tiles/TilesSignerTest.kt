package org.tenshu.tiles

import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import org.tenshu.core.common.util.sha256
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The load-bearing Phase-1 verification (`maps-region-delivery.md` R4): the signer builds a catalog
 * whose sha256/size match the extracts, and its detached signature is accepted by the **same
 * Ed25519** the client's `RegionCatalogVerifier` uses — a real sign→verify→tamper round-trip. Also
 * pins the catalog JSON schema against what `parseRegionCatalog` reads.
 */
class TilesSignerTest {

    @Test
    fun buildsCatalogWithCorrectShaThenSignsAndVerifies() {
        val tmp = Files.createTempDirectory("tiles-signer").toFile()
        val regionsFile = File(tmp, "regions.json").apply {
            writeText(
                """
                { "buildDate": "2026-07", "schemaVersion": 1, "tileSchemaVersion": 4,
                  "regions": [
                    { "id": "de-bayern", "name": "Bayern", "bbox": [8.9, 47.2, 13.8, 50.5] },
                    { "id": "at", "name": "Austria", "bbox": [9.5, 46.3, 17.1, 49.0] }
                  ] }
                """.trimIndent(),
            )
        }
        val extracts = File(tmp, "extracts").apply { mkdirs() }
        val bayernBytes = "BAYERN-PMTILES-BYTES".encodeToByteArray()
        File(extracts, "de-bayern.pmtiles").writeBytes(bayernBytes)
        File(extracts, "at.pmtiles").writeBytes("AT-PMTILES-BYTES".encodeToByteArray())

        val defs = CatalogBuilder.parseDefinitions(regionsFile)
        val catalog = CatalogBuilder.buildCatalog(defs, extracts)

        val bayern = catalog.regions.single { it.id == "de-bayern" }
        assertEquals(bayernBytes.size.toLong(), bayern.byteSize)
        assertEquals(sha256(bayernBytes).toHex(), bayern.sha256) // same primitive the client gates on
        assertEquals(4, bayern.tileSchemaVersion)
        assertEquals("regions/2026-07/de-bayern.pmtiles", bayern.path)

        val bytes = CatalogBuilder.encodeCatalogBytes(catalog)
        val signer = ManifestSigner()
        val kp = signer.generateKeypair()
        val signature = signer.sign(bytes, kp.privateKeySeed)

        // The client verifier is exactly this call (Ed25519Backend::verify).
        val ed = BouncyCastleEd25519Backend()
        assertTrue(ed.verify(kp.publicKey, bytes, signature))

        // A tampered catalog with the old signature is rejected (R4).
        val tampered = bytes.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFalse(ed.verify(kp.publicKey, tampered, signature))

        // A different key is rejected.
        assertFalse(ed.verify(signer.generateKeypair().publicKey, bytes, signature))

        tmp.deleteRecursively()
    }

    @Test
    fun catalogCarriesEachRegionsCoverageBox_soTheClientCanResolveByCoverage() {
        val tmp = Files.createTempDirectory("tiles-signer-coverage").toFile()
        val regionsFile = File(tmp, "regions.json").apply {
            writeText(
                """
                { "buildDate": "2026-07", "schemaVersion": 1, "tileSchemaVersion": 4,
                  "regions": [
                    { "id": "test-firenze", "name": "Firenze (test)", "bbox": [11.14, 43.73, 11.35, 43.83] }
                  ] }
                """.trimIndent(),
            )
        }
        val extracts = File(tmp, "extracts").apply { mkdirs() }
        File(extracts, "test-firenze.pmtiles").writeBytes("X".encodeToByteArray())

        val catalog = CatalogBuilder.buildCatalog(CatalogBuilder.parseDefinitions(regionsFile), extracts)

        // `west,south,east,north` — the order regions.json authors bbox in, and the exact string form
        // the client's GeoBounds.parseBbox reads. Shipped as "" before, which silently disabled
        // coverage-aware region resolution (every parseBbox returned null → always "first region").
        assertEquals("11.14,43.73,11.35,43.83", catalog.regions.single().coverage)

        tmp.deleteRecursively()
    }

    @Test
    fun emittedCatalogCarriesTheClientWireFields() {
        val doc = CatalogDoc(
            schemaVersion = 1, buildDate = "2026-07",
            regions = listOf(CatalogRegion("x", "X", "", 10L, "abcd", 4, "regions/2026-07/x.pmtiles")),
        )
        val jsonStr = CatalogBuilder.encodeCatalogBytes(doc).decodeToString()
        // The field names parseRegionCatalog (:map-region-service-proxy) reads.
        listOf("schemaVersion", "buildDate", "regions", "\"id\"", "\"name\"", "byteSize", "sha256", "tileSchemaVersion", "\"path\"")
            .forEach { assertTrue(jsonStr.contains(it), "catalog.json missing field fragment: $it") }
    }

    @Test
    fun keyHexRoundTrips() {
        val kp = ManifestSigner().generateKeypair()
        assertEquals(kp.privateKeySeed.toList(), kp.privateKeySeed.toHex().hexToBytes().toList())
    }
}
