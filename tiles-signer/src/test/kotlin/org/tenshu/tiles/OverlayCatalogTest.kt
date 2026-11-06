package org.tenshu.tiles

import app.tenshu.vectortile.TransitLineIndex
import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **§33 L3 — the transit-line overlay rides the signed catalogue.**
 *
 * ⚠⚠ The security property, stated once: the overlay gets **no signature of its own**. `catalog.json`
 * is signed as bytes, so an `overlaySha256` inside it is already covered by the same chain that
 * protects a tile bundle — and a second detached signature would be a second thing to rotate for no
 * extra guarantee. [theOverlayDigestIsCoveredByTheCatalogSignature] is that claim as a test.
 */
class OverlayCatalogTest {

    private fun tmp() = Files.createTempDirectory("overlay-catalog").toFile()

    private fun regionsFile(dir: File) = File(dir, "regions.json").apply {
        writeText(
            """
            { "buildDate": "2026-08", "schemaVersion": 1, "tileSchemaVersion": 4,
              "regions": [
                { "id": "de-bayern", "name": "Bayern", "bbox": [8.9, 47.2, 13.8, 50.5] },
                { "id": "at", "name": "Austria", "bbox": [9.5, 46.3, 17.1, 49.0] }
              ] }
            """.trimIndent(),
        )
    }

    private fun extractsWithBothBundles(dir: File) = File(dir, "extracts").apply {
        mkdirs()
        File(this, "de-bayern.pmtiles").writeBytes("BAYERN".encodeToByteArray())
        File(this, "at.pmtiles").writeBytes("AT".encodeToByteArray())
    }

    private fun overlayBytes() = TransitLineIndex.encode(
        listOf(TransitLineIndex.Stop(481_280_000, 116_050_000, setOf("U5"))),
    )

    private fun sha256Hex(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ── the entry ──────────────────────────────────────────────────────────────

    @Test
    fun aRegionWithAnOverlayCarriesItsPathSizeDigestAndFormatVersion() {
        val dir = tmp()
        val overlays = File(dir, "transit").apply { mkdirs() }
        val bytes = overlayBytes()
        File(overlays, "de-bayern.tlne").writeBytes(bytes)

        val catalog = CatalogBuilder.buildCatalog(
            defs = CatalogBuilder.parseDefinitions(regionsFile(dir)),
            extractsDir = extractsWithBothBundles(dir),
            overlaysDir = overlays,
            overlayFormatVersion = TransitLineIndex.FORMAT_VERSION,
        )

        val bayern = catalog.regions.single { it.id == "de-bayern" }
        assertEquals("regions/2026-08/de-bayern.tlne", bayern.overlayPath)
        assertEquals(bytes.size.toLong(), bayern.overlayByteSize)
        assertEquals(sha256Hex(bytes), bayern.overlaySha256)
        assertEquals(TransitLineIndex.FORMAT_VERSION, bayern.overlayFormatVersion)
    }

    @Test
    fun aRegionWithNoOverlayIsNotAnErrorAndSimplyHasNone() {
        // ⚠⚠ The asymmetry with a missing *bundle*, which IS a hard error. Plenty of regions have no
        // rail, and a region whose overlay has not been built yet must still get a tile catalogue —
        // making the overlay a precondition would let a transit feature block map delivery.
        val dir = tmp()
        val overlays = File(dir, "transit").apply { mkdirs() }
        File(overlays, "de-bayern.tlne").writeBytes(overlayBytes())

        val catalog = CatalogBuilder.buildCatalog(
            defs = CatalogBuilder.parseDefinitions(regionsFile(dir)),
            extractsDir = extractsWithBothBundles(dir),
            overlaysDir = overlays,
        )

        val austria = catalog.regions.single { it.id == "at" }
        assertEquals("", austria.overlayPath)
        assertEquals(0, austria.overlayByteSize)
        assertEquals("", austria.overlaySha256)
        assertEquals(0, austria.overlayFormatVersion, "no overlay ⇒ no format claim either")
    }

    @Test
    fun aBuildWithNoOverlaysAtAllProducesExactlyTodaysCatalogue() {
        // The compatibility claim as a test: adding the feature must not change a build that does not
        // use it, or every existing pipeline's output moves for nothing.
        val dir = tmp()
        val defs = CatalogBuilder.parseDefinitions(regionsFile(dir))
        val extracts = extractsWithBothBundles(dir)

        val without = CatalogBuilder.buildCatalog(defs, extracts)
        assertTrue(without.regions.all { it.overlayPath.isEmpty() && it.overlaySha256.isEmpty() })
    }

    // ── the D11 split ──────────────────────────────────────────────────────────

    @Test
    fun anOverlayDigestCanBePrecomputedLikeABundleDigest() {
        // D11: the machine that built the artefact need not be the machine holding the release key.
        val dir = tmp()
        val bytes = overlayBytes()

        val catalog = CatalogBuilder.buildCatalog(
            defs = CatalogBuilder.parseDefinitions(regionsFile(dir)),
            extractsDir = extractsWithBothBundles(dir),
            precomputedOverlays = mapOf("at" to BundleDigest(bytes.size.toLong(), sha256Hex(bytes))),
            overlayFormatVersion = TransitLineIndex.FORMAT_VERSION,
        )

        val austria = catalog.regions.single { it.id == "at" }
        assertEquals(sha256Hex(bytes), austria.overlaySha256)
        assertEquals("regions/2026-08/at.tlne", austria.overlayPath)
    }

    @Test
    fun aLocalOverlayBeatsAPrecomputedDigestForTheSameRegion() {
        // Same rule as the bundle: hashing what you actually have is strictly better evidence.
        val dir = tmp()
        val overlays = File(dir, "transit").apply { mkdirs() }
        val real = overlayBytes()
        File(overlays, "de-bayern.tlne").writeBytes(real)

        val catalog = CatalogBuilder.buildCatalog(
            defs = CatalogBuilder.parseDefinitions(regionsFile(dir)),
            extractsDir = extractsWithBothBundles(dir),
            overlaysDir = overlays,
            precomputedOverlays = mapOf("de-bayern" to BundleDigest(999, "deadbeef")),
            overlayFormatVersion = TransitLineIndex.FORMAT_VERSION,
        )

        assertEquals(sha256Hex(real), catalog.regions.single { it.id == "de-bayern" }.overlaySha256)
    }

    // ── the security property ──────────────────────────────────────────────────

    @Test
    fun theOverlayDigestIsCoveredByTheCatalogSignature() {
        // ⚠⚠ Why the overlay needs no signature of its own: tamper with the recorded digest and the
        // catalogue's own signature stops verifying. The artefact inherits the chain that already
        // protects the tile bundles.
        val dir = tmp()
        val overlays = File(dir, "transit").apply { mkdirs() }
        File(overlays, "de-bayern.tlne").writeBytes(overlayBytes())

        val catalog = CatalogBuilder.buildCatalog(
            defs = CatalogBuilder.parseDefinitions(regionsFile(dir)),
            extractsDir = extractsWithBothBundles(dir),
            overlaysDir = overlays,
            overlayFormatVersion = TransitLineIndex.FORMAT_VERSION,
        )
        val bytes = CatalogBuilder.encodeCatalogBytes(catalog)

        val signer = ManifestSigner()
        val kp = signer.generateKeypair()
        val signature = signer.sign(bytes, kp.privateKeySeed)
        // The client verifier is exactly this call (Ed25519Backend::verify).
        val ed = BouncyCastleEd25519Backend()
        assertTrue(ed.verify(kp.publicKey, bytes, signature), "precondition: the honest bytes verify")

        val tampered = CatalogBuilder.encodeCatalogBytes(
            catalog.copy(
                regions = catalog.regions.map {
                    if (it.id == "de-bayern") it.copy(overlaySha256 = "0".repeat(64)) else it
                },
            ),
        )
        assertTrue(!tampered.contentEquals(bytes), "precondition: the swap really changed the bytes")
        assertTrue(
            !ed.verify(kp.publicKey, tampered, signature),
            "a swapped overlay digest must break the catalogue signature",
        )
    }
}
