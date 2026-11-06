package org.tenshu.mapregion.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure coverage of the manifest trust check (D3/R4) and the tile-schema filter (D3), over a
 * deterministic [SignatureVerifier] fake — no real crypto backend, so this compiles + runs on
 * every commonTest target (JVM/Android/iOS).
 */
class RegionCatalogVerifierTest {

    private val pinnedKey = byteArrayOf(1, 2, 3, 4)

    // A stand-in "signature scheme": a signature is `sig:<message>`, valid only under the pinned
    // key. Models detached-signature verification's accept/reject behaviour without Ed25519.
    private fun fakeSign(message: ByteArray): ByteArray =
        ("sig:" + message.decodeToString()).encodeToByteArray()

    private val verifier = SignatureVerifier { key, message, signature ->
        key.contentEquals(pinnedKey) && signature.contentEquals(fakeSign(message))
    }

    @Test
    fun acceptsAValidSignature() {
        val subject = RegionCatalogVerifier(verifier, pinnedKey)
        val bytes = "catalog-json".encodeToByteArray()
        assertTrue(subject.isAuthentic(bytes, fakeSign(bytes)))
    }

    @Test
    fun rejectsTamperedBytes() {
        val subject = RegionCatalogVerifier(verifier, pinnedKey)
        val signed = "catalog-json".encodeToByteArray()
        val signature = fakeSign(signed)
        // A CDN swaps the bytes but replays the old signature → rejected (R4).
        assertFalse(subject.isAuthentic("tampered-json".encodeToByteArray(), signature))
    }

    @Test
    fun rejectsAWrongSigningKey() {
        val subject = RegionCatalogVerifier(verifier, byteArrayOf(9, 9, 9, 9))
        val bytes = "catalog-json".encodeToByteArray()
        assertFalse(subject.isAuthentic(bytes, fakeSign(bytes)))
    }

    @Test
    fun filtersOutUnrenderableTileSchemaVersions() {
        val regions = listOf(
            regionEntry("v3", tileSchemaVersion = 3),
            regionEntry("v4", tileSchemaVersion = 4),
            regionEntry("v5", tileSchemaVersion = 5),
        )
        // Supported = 4: the future v5 build is dropped so a v4 style never renders it blank (D3).
        val kept = renderableRegions(regions, supportedTileSchema = 4).map { it.regionId }
        assertEquals(listOf("v3", "v4"), kept)
    }

    private fun regionEntry(id: String, tileSchemaVersion: Int) = RegionEntryData(
        regionId = id,
        name = id,
        coverage = "",
        byteSize = 1L,
        sha256 = "x",
        tileSchemaVersion = tileSchemaVersion,
        path = "regions/2026-07/$id.pmtiles",
    )
}
