package org.tenshu.mapregion.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.mapregion.test.FakeRegionCatalogSource
import org.tenshu.mapregion.test.FakeRegionStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [StreamingRegionSource] over a fake range fetcher + in-memory cache (256-byte chunks for compact
 * multi-chunk fixtures): cache-first serving, cross-chunk assembly, EOF, low-zoom prefetch warming,
 * installed-first (no egress), and the unknown-region failure.
 */
class StreamingRegionSourceTest {

    private val bundle = ByteArray(700) { (it % 251).toByte() } // 3 chunks at chunkBytes = 256
    private val entry = RegionEntryData(
        regionId = "de", name = "DE", coverage = "poly",
        byteSize = bundle.size.toLong(), sha256 = "sha", tileSchemaVersion = 4,
        path = "regions/2026-07/de.pmtiles",
    )
    private val catalog = RegionCatalogData(2, "2026-07", listOf(entry))

    /** A fetcher that slices a synthetic bundle and counts its calls (to assert cache hits). */
    private class CountingFetcher(private val bundle: ByteArray) : RegionRangeFetcher {
        var fetches = 0
        override suspend fun fetchRange(path: String, offset: Long, length: Int): ByteArray {
            fetches++
            val end = minOf(bundle.size.toLong(), offset + length).toInt()
            return bundle.copyOfRange(offset.toInt(), end)
        }
    }

    private fun source(
        fetcher: RegionRangeFetcher,
        store: RegionStore = FakeRegionStore(),
        cat: RegionCatalogData = catalog,
        prefetchBytes: Long = 4L * 1024 * 1024,
    ) = StreamingRegionSource(
        catalogSource = FakeRegionCatalogSource(cat),
        store = store,
        fetcher = fetcher,
        chunkBytes = 256,
        lowZoomPrefetchBytes = prefetchBytes,
    )

    @Test
    fun streamsThenServesTheSameChunkFromCache() = runTest {
        val fetcher = CountingFetcher(bundle)
        val src = source(fetcher)

        val a = src.readRange("de", 300, 50) // chunk 1 (256..511)
        assertContentEquals(bundle.copyOfRange(300, 350), a)
        val afterFirst = fetcher.fetches

        src.readRange("de", 320, 30) // same chunk → no new fetch
        assertEquals(afterFirst, fetcher.fetches)
    }

    @Test
    fun assemblesAcrossAChunkBoundary() = runTest {
        val r = source(CountingFetcher(bundle)).readRange("de", 240, 40) // spans the 256 boundary
        assertContentEquals(bundle.copyOfRange(240, 280), r)
    }

    @Test
    fun clampsToEofAndReturnsEmptyPastIt() = runTest {
        val src = source(CountingFetcher(bundle))
        assertContentEquals(bundle.copyOfRange(650, 700), src.readRange("de", 650, 500)) // clamped
        assertTrue(src.readRange("de", 700, 100).isEmpty()) // at/after EOF
    }

    @Test
    fun prefetchLowZoomWarmsThePrefix_soAReadMakesNoFetch() = runTest {
        val fetcher = CountingFetcher(bundle)
        val src = source(fetcher, prefetchBytes = bundle.size.toLong())

        src.prefetchLowZoom("de")
        val afterPrefetch = fetcher.fetches
        assertTrue(afterPrefetch > 0)

        src.readRange("de", 0, 300)
        assertEquals(afterPrefetch, fetcher.fetches) // fully served from the prefetched cache
    }

    @Test
    fun installedRegionServesFromStore_withNoEgress() = runTest {
        val store = FakeRegionStore().apply { add(entry, "2026-07", bundle) }
        val fetcher = CountingFetcher(bundle)

        val r = source(fetcher, store = store).readRange("de", 100, 50)

        assertContentEquals(bundle.copyOfRange(100, 150), r)
        assertEquals(0, fetcher.fetches) // installed-first: never touches the fetcher
    }

    @Test
    fun unknownRegionThrows() = runTest {
        assertFailsWith<UnknownRegionException> {
            source(CountingFetcher(bundle), cat = RegionCatalogData(1, "x", emptyList()))
                .readRange("nope", 0, 10)
        }
    }
}
