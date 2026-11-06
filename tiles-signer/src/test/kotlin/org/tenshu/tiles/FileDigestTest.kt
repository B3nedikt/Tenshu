package org.tenshu.tiles

import org.tenshu.core.common.util.sha256
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The load-bearing property of [sha256File]: it agrees **bit for bit** with `core-common`'s
 * `sha256(ByteArray)`, which is what the client recomputes over a downloaded bundle before trusting
 * it (R4). If these two ever diverged, every published bundle would fail the client's gate — the
 * catalog would be self-consistently wrong, and nothing else in the pipeline would notice.
 *
 * Why streaming at all: a bundle can be the planet (`global`), and `File.readBytes()` cannot express
 * a file past 2 GiB. That size is not testable here, so what these tests actually pin is the
 * *equivalence* — exercised across chunk boundaries, which is where a chunked digest would break if
 * the feed were wrong.
 */
class FileDigestTest {

    @Test
    fun streamedDigestMatchesTheWholeBufferPrimitiveTheClientUses() {
        val tmp = Files.createTempDirectory("tiles-digest").toFile()
        // Deliberately not a multiple of any buffer size: the last read is a partial chunk, the case
        // an off-by-one in the update(buffer, 0, read) length would corrupt.
        val bytes = Random(20260721).nextBytes(3 * (1 shl 20) + 4097)
        val file = File(tmp, "region.pmtiles").apply { writeBytes(bytes) }

        assertEquals(sha256(bytes).toHex(), sha256File(file).toHex())

        tmp.deleteRecursively()
    }

    @Test
    fun digestIsIndependentOfChunkSize() {
        val tmp = Files.createTempDirectory("tiles-digest-chunks").toFile()
        val bytes = Random(7).nextBytes(64 * 1024 + 13)
        val file = File(tmp, "region.pmtiles").apply { writeBytes(bytes) }
        val expected = sha256(bytes).toHex()

        // A 1-byte buffer forces one update() per byte; 7 and 4096 straddle the SHA-256 block size.
        // All must land on the same digest as the one-shot call — that is what makes the buffer an
        // implementation detail rather than part of the published contract.
        listOf(1, 7, 4096, 1 shl 20).forEach { chunk ->
            assertEquals(expected, sha256File(file, bufferSize = chunk).toHex(), "chunk=$chunk")
        }

        tmp.deleteRecursively()
    }

    @Test
    fun handlesTheEmptyFile() {
        val tmp = Files.createTempDirectory("tiles-digest-empty").toFile()
        val file = File(tmp, "empty.pmtiles").apply { writeBytes(ByteArray(0)) }

        // Not a real bundle, but the loop must terminate on a first read of -1 rather than hang or
        // throw — an empty extract is a plausible pipeline failure and should surface downstream as
        // a size/sha mismatch, not as a crash in the signer.
        assertEquals(sha256(ByteArray(0)).toHex(), sha256File(file).toHex())

        tmp.deleteRecursively()
    }

    @Test
    fun precomputedDigestsStandInForABundleThisMachineDoesNotHave() {
        // The D11 split: the droplet cut a 20 GB `global` bundle and uploaded it; this machine has
        // the signing key and only the digests. Signing must succeed anyway, or the release key
        // would have to travel to the build box.
        val tmp = Files.createTempDirectory("tiles-digest-split").toFile()
        val regionsFile = File(tmp, "regions.json").apply {
            writeText(
                """
                { "buildDate": "2026-07", "schemaVersion": 1, "tileSchemaVersion": 4,
                  "regions": [
                    { "id": "global", "name": "World", "bbox": [-180, -90, 180, 90] },
                    { "id": "at", "name": "Austria", "bbox": [9.5, 46.3, 17.1, 49.0] }
                  ] }
                """.trimIndent(),
            )
        }
        // Only `at` is present locally; `global` exists solely as a precomputed digest.
        val extracts = File(tmp, "extracts").apply { mkdirs() }
        val atBytes = "AT-PMTILES-BYTES".encodeToByteArray()
        File(extracts, "at.pmtiles").writeBytes(atBytes)

        val catalog = CatalogBuilder.buildCatalog(
            CatalogBuilder.parseDefinitions(regionsFile),
            extracts,
            precomputed = mapOf("global" to BundleDigest(byteSize = 21_474_836_480L, sha256 = "deadbeef")),
        )

        val global = catalog.regions.single { it.id == "global" }
        assertEquals(21_474_836_480L, global.byteSize)
        assertEquals("deadbeef", global.sha256)
        assertEquals("regions/2026-07/global.pmtiles", global.path)

        // The locally-present bundle is still hashed for real — a precomputed entry is a fallback,
        // never a way to publish an unverified digest for a file you actually hold.
        val at = catalog.regions.single { it.id == "at" }
        assertEquals(sha256(atBytes).toHex(), at.sha256)

        tmp.deleteRecursively()
    }

    @Test
    fun aRegionWithNeitherBundleNorDigestFailsLoudly() {
        val tmp = Files.createTempDirectory("tiles-digest-missing").toFile()
        val regionsFile = File(tmp, "regions.json").apply {
            writeText(
                """
                { "buildDate": "2026-07", "schemaVersion": 1, "tileSchemaVersion": 4,
                  "regions": [ { "id": "global", "name": "World", "bbox": [-180, -90, 180, 90] } ] }
                """.trimIndent(),
            )
        }
        val extracts = File(tmp, "extracts").apply { mkdirs() }

        // Silently emitting a zero-size/empty-hash entry would publish a catalog whose sha256 gate
        // rejects every download — the failure has to land here, at build time.
        val failure = assertFailsWith<IllegalStateException> {
            CatalogBuilder.buildCatalog(CatalogBuilder.parseDefinitions(regionsFile), extracts)
        }
        assertTrue(failure.message!!.contains("global"), "error should name the region: ${failure.message}")

        tmp.deleteRecursively()
    }

    @Test
    fun digestFileRoundTripsThroughTheParserTheCliUses() {
        val tmp = Files.createTempDirectory("tiles-digest-parse").toFile()
        // The exact shape scripts/tiles-build.sh writes via jq.
        val file = File(tmp, "hashes.json").apply {
            writeText(
                """{"buildDate":"2026-07","bundles":{"global":{"byteSize":21474836480,"sha256":"abc123"}}}""",
            )
        }

        val parsed = CatalogBuilder.parseDigests(file)

        assertEquals("2026-07", parsed.buildDate)
        assertEquals(21_474_836_480L, parsed.bundles.getValue("global").byteSize)
        assertEquals("abc123", parsed.bundles.getValue("global").sha256)

        tmp.deleteRecursively()
    }

    @Test
    fun catalogSizeAndDigestComeFromTheFileNotAnInMemoryCopy() {
        val tmp = Files.createTempDirectory("tiles-digest-catalog").toFile()
        val regionsFile = File(tmp, "regions.json").apply {
            writeText(
                """
                { "buildDate": "2026-07", "schemaVersion": 1, "tileSchemaVersion": 4,
                  "regions": [ { "id": "global", "name": "World", "bbox": [-180, -90, 180, 90] } ] }
                """.trimIndent(),
            )
        }
        val extracts = File(tmp, "extracts").apply { mkdirs() }
        val bytes = Random(1).nextBytes((1 shl 20) + 5)
        File(extracts, "global.pmtiles").writeBytes(bytes)

        val catalog = CatalogBuilder.buildCatalog(CatalogBuilder.parseDefinitions(regionsFile), extracts)

        val global = catalog.regions.single()
        assertEquals(bytes.size.toLong(), global.byteSize)
        assertEquals(sha256(bytes).toHex(), global.sha256)

        tmp.deleteRecursively()
    }
}
