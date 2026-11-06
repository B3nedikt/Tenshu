package org.tenshu.mapregion.proxy

import app.tenshu.vectortile.TransitLineIndex
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.util.sha256
import org.tenshu.core.common.util.toHexLower
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **§33 L4 — the overlay tier.**
 *
 * ⚠⚠ Most of this suite is about **refusals**, and that is the point. A line label is an enrichment:
 * every way of not knowing — no coverage, no artefact, an unreadable format, a failed fetch, a
 * digest that does not match — must answer *empty* so the leg reads exactly as it did before the
 * overlay existed. Making a timeline read able to fail for a missing label would be a far worse
 * trade than not showing one.
 */
class CatalogTransitLineOverlaysTest {

    // Synthetic Munich-ish coordinates. ⚠ E7 means ×10^7, so 48.1° is 481_000_000 and NOT
    // 48_100_000 — the latter is 4.81°, off the coast of Africa, and every region lookup misses.
    // 1 m ≈ 89.8 E7, so 30 000 E7 ≈ 334 m between the two stops.
    private val ostLat = 481_000_000L
    private val odeonLat = 481_030_000L
    private val lng = 115_000_000L

    private val artefact = TransitLineIndex.encode(
        listOf(
            TransitLineIndex.Stop(ostLat.toInt(), lng.toInt(), setOf("U5")),
            TransitLineIndex.Stop(odeonLat.toInt(), lng.toInt(), setOf("U3", "U4", "U5", "U6")),
        ),
    )

    private fun entry(
        overlayPath: String = "regions/2026-08/de-bayern.tlne",
        byteSize: Long = artefact.size.toLong(),
        sha: String = sha256(artefact).toHexLower(),
        formatVersion: Int = TransitLineIndex.FORMAT_VERSION,
    ) = RegionEntryData(
        regionId = "de-bayern",
        name = "Bayern",
        coverage = "11.0,48.0,12.0,49.0",
        byteSize = 1,
        sha256 = "aa",
        tileSchemaVersion = 4,
        path = "regions/2026-08/de-bayern.pmtiles",
        overlayPath = overlayPath,
        overlayByteSize = byteSize,
        overlaySha256 = sha,
        overlayFormatVersion = formatVersion,
    )

    private fun catalog(vararg entries: RegionEntryData) = object : RegionCatalogSource {
        override suspend fun load() =
            RegionCatalogData(schemaVersion = 1, buildDate = "2026-08", regions = entries.toList())
    }

    private class CountingFetcher(private val bytes: ByteArray?) : RegionRangeFetcher {
        var calls = 0
        override suspend fun fetchRange(path: String, offset: Long, length: Int): ByteArray {
            calls++
            return bytes ?: throw RuntimeException("network down")
        }
    }

    private fun source(
        fetcher: RegionRangeFetcher?,
        vararg entries: RegionEntryData,
    ) = CatalogTransitLineOverlays(catalog(*entries), fetcher)

    private suspend fun CatalogTransitLineOverlays.ride() =
        linesServing(ostLat, lng, odeonLat, lng, withinM = 300.0)

    // ── the answer ─────────────────────────────────────────────────────────────

    @Test
    fun aStopPairResolvesToTheIntersection() = runTest {
        assertEquals(setOf("U5"), source(CountingFetcher(artefact), entry()).ride())
    }

    @Test
    fun theArtefactIsFetchedOnceAndCachedPerRegion() = runTest {
        // The feed re-renders far more often than a build changes, and the artefact is immutable for
        // a build (its path carries the build date), so re-fetching per leg would be pure waste.
        val fetcher = CountingFetcher(artefact)
        val tier = source(fetcher, entry())
        repeat(4) { tier.ride() }
        assertEquals(1, fetcher.calls)
    }

    // ── the refusals ───────────────────────────────────────────────────────────

    @Test
    fun noFetcherWiredAnswersEmptyRatherThanThrowing() = runTest {
        assertTrue(source(fetcher = null, entry()).ride().isEmpty())
    }

    @Test
    fun aRegionWithNoOverlayAnswersEmptyAndIsNotReFetched() = runTest {
        // ⚠ The negative result is cached too: a region with no rail must not cost a request per leg.
        val fetcher = CountingFetcher(artefact)
        val tier = source(fetcher, entry(overlayPath = "", byteSize = 0))
        repeat(3) { assertTrue(tier.ride().isEmpty()) }
        assertEquals(0, fetcher.calls)
    }

    @Test
    fun aCoordinateOutsideEveryCatalogueRegionAnswersEmpty() = runTest {
        val tier = source(CountingFetcher(artefact), entry().copy(coverage = "1.0,1.0,2.0,2.0"))
        assertTrue(tier.ride().isEmpty())
    }

    @Test
    fun aFutureFormatVersionIsRefusedRatherThanGuessedAt() = runTest {
        val fetcher = CountingFetcher(artefact)
        val tier = source(fetcher, entry(formatVersion = TransitLineIndex.FORMAT_VERSION + 1))
        assertTrue(tier.ride().isEmpty())
        assertEquals(0, fetcher.calls, "and not even fetched — the catalogue already said so")
    }

    @Test
    fun aTamperedArtefactIsRefusedByItsDigest() = runTest {
        // ⚠⚠ The integrity check. `catalog.json` is signed as bytes, so the digest inside it is
        // authenticated; verifying the fetched bytes against it is the whole story for this artefact.
        val tampered = artefact.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertTrue(source(CountingFetcher(tampered), entry()).ride().isEmpty())
    }

    @Test
    fun aTruncatedResponseIsRefused() = runTest {
        assertTrue(source(CountingFetcher(artefact.copyOf(artefact.size / 2)), entry()).ride().isEmpty())
    }

    @Test
    fun aFailedFetchAnswersEmptyAndIsRetriedNextTime() = runTest {
        // ⚠ Unlike the structural refusals above, a network failure is NOT cached — it can change,
        // and caching it would make one bad moment permanent for the life of the process.
        val fetcher = CountingFetcher(bytes = null)
        val tier = source(fetcher, entry())
        assertTrue(tier.ride().isEmpty())
        assertTrue(tier.ride().isEmpty())
        assertEquals(2, fetcher.calls, "a transient failure must be retried")
    }

    @Test
    fun bytesThatDoNotDecodeAnswerEmpty() = runTest {
        val junk = "not an overlay at all".encodeToByteArray()
        val tier = source(
            CountingFetcher(junk),
            entry(byteSize = junk.size.toLong(), sha = sha256(junk).toHexLower()),
        )
        assertTrue(tier.ride().isEmpty())
    }
}
