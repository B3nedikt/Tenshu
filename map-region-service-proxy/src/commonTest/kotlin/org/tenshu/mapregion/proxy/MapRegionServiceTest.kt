package org.tenshu.mapregion.proxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.getOrNull
import org.tenshu.core.mapregion.catalog
import org.tenshu.core.mapregion.deleteRegion
import org.tenshu.core.mapregion.downloadRegion
import org.tenshu.core.mapregion.installed
import org.tenshu.core.mapregion.railLinesIn
import org.tenshu.core.mapregion.readRegionRange
import org.tenshu.core.mapregion.downloadProgress
import org.tenshu.core.mapregion.streamRegionRange
import org.tenshu.mapregion.client.mapRegionService
import org.tenshu.mapregion.test.FakeRegionCatalogSource
import org.tenshu.mapregion.test.FakeRegionDownloader
import org.tenshu.mapregion.test.FakeRegionRangeFetcher
import org.tenshu.mapregion.test.FakeRegionStore
import org.tenshu.mapregion.test.createMapRegionProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Drives `catalog()` / `installed()` / `deleteRegion()` end-to-end through a real
 * [DefaultTenshuClient] over an in-process proxy (the closest headless approximation of the app
 * calling the proxy over IPC). Proves the whole op pipe: client Ro → wire → proxy impl over the
 * injected seams → return value materialised back on the client.
 */
class MapRegionServiceTest {

    @Test
    fun catalogVerifiesFiltersAndReturns() = runTest {
        val source = FakeRegionCatalogSource(
            RegionCatalogData(
                schemaVersion = 2,
                buildDate = "2026-07",
                regions = listOf(
                    entry("de-bayern", "Bayern", byteSize = 42_000_000L, tileSchemaVersion = 4),
                    // A future schema-bumping build the current style can't render → filtered (D3).
                    entry("future", "Future", byteSize = 9L, tileSchemaVersion = 5),
                ),
            ),
        )
        val client = clientOf(createMapRegionProxy(catalogSource = source, coroutineScope = this))

        val catalog = client.mapRegionService.catalog().getOrNull()
            ?: error("catalog() returned a failure")

        assertEquals(2L, catalog.catalogSchemaVersion.get())
        assertEquals("2026-07", catalog.buildDate.get())

        val regions = catalog.regions.toList()
        assertEquals(listOf("de-bayern"), regions.map { it.regionId.get() })
        assertEquals("Bayern", regions.first().name.get())
        assertEquals(42_000_000L, regions.first().byteSize.get())
        assertEquals(4L, regions.first().tileSchemaVersion.get())
    }

    @Test
    fun catalogSurfacesAFailureRatherThanAnEmptyCatalog() = runTest {
        // A verification/transport error must NOT look like "no regions" — it surfaces as a
        // TResult.Failure (getOrNull == null), the error-channel Shape-A posture.
        val source = FakeRegionCatalogSource(failure = IllegalStateException("bad signature"))
        val client = clientOf(createMapRegionProxy(catalogSource = source, coroutineScope = this))

        assertNull(client.mapRegionService.catalog().getOrNull())
    }

    @Test
    fun installedReflectsTheStoreAndDeleteEvicts() = runTest {
        val store = FakeRegionStore(
            listOf(
                installedRegion("de-bayern", "Bayern"),
                installedRegion("at", "Austria"),
            ),
        )
        val client = clientOf(createMapRegionProxy(regionStore = store, coroutineScope = this))

        val before = client.mapRegionService.installed().getOrNull()
            ?: error("installed() returned a failure")
        assertEquals(setOf("de-bayern", "at"), before.toList().map { it.regionId.get() }.toSet())

        client.mapRegionService.deleteRegion("at")

        val after = client.mapRegionService.installed().getOrNull()
            ?: error("installed() returned a failure")
        assertEquals(setOf("de-bayern"), after.toList().map { it.regionId.get() }.toSet())
    }

    @Test
    fun downloadRegionReturnsTheLandedInstalledRegion() = runTest {
        val downloader = FakeRegionDownloader(
            InstalledRegionData(
                regionId = "de-bayern",
                name = "Bayern",
                localPath = "/regions/de-bayern.pmtiles",
                byteSize = 42_000_000L,
                sha256 = "deadbeef",
                buildDate = "2026-07",
            ),
        )
        val client = clientOf(createMapRegionProxy(downloader = downloader, coroutineScope = this))

        val installed = client.mapRegionService.downloadRegion("de-bayern").getOrNull()
            ?: error("downloadRegion() returned a failure")

        assertEquals("de-bayern", installed.regionId.get())
        assertEquals("/regions/de-bayern.pmtiles", installed.localPath.get())
        assertEquals(42_000_000L, installed.byteSize.get())
    }

    @Test
    fun readRegionRangeStreamsBundleBytesAndClampsAtEof() = runTest {
        val bundle = "PMTILES-bundle-bytes".encodeToByteArray()
        val store = FakeRegionStore(
            listOf(installedRegion("de-bayern", "Bayern")),
            bytes = mapOf("de-bayern" to bundle),
        )
        val client = clientOf(createMapRegionProxy(regionStore = store, coroutineScope = this))

        // A window inside the bundle.
        val head = client.mapRegionService.readRegionRange("de-bayern", offset = 0, length = 7)
            .getOrNull() ?: error("readRegionRange() returned a failure")
        assertEquals("PMTILES", head.get().decodeToString())

        // A window straddling EOF is clamped to the remaining bytes.
        val tail = client.mapRegionService.readRegionRange("de-bayern", offset = 8, length = 999)
            .getOrNull() ?: error("readRegionRange() returned a failure")
        assertEquals("bundle-bytes", tail.get().decodeToString())

        // At/after EOF: empty, not a failure (the drain loop's terminator).
        val past = client.mapRegionService.readRegionRange("de-bayern", offset = 100, length = 16)
            .getOrNull() ?: error("readRegionRange() returned a failure")
        assertEquals(0, past.get().size)
    }

    @Test
    fun readRegionRangeFailsForAnUninstalledRegion() = runTest {
        val client = clientOf(createMapRegionProxy(regionStore = FakeRegionStore(), coroutineScope = this))

        // Not installed → typed failure, never an empty (EOF-looking) array.
        assertNull(client.mapRegionService.readRegionRange("nope", offset = 0, length = 16).getOrNull())
    }

    @Test
    fun streamRegionRangeServesAnUninstalledRegionFromTheTileHost() = runTest {
        // Streamed default (D10): the region is in the catalog but NOT downloaded; the range comes
        // back over the tile-host fetcher, no full download.
        val bundle = "STREAMED-pmtiles-bytes".encodeToByteArray()
        val path = "regions/2026-07/de-bayern.pmtiles"
        val catalog = RegionCatalogData(
            2, "2026-07",
            listOf(RegionEntryData("de-bayern", "Bayern", "poly", bundle.size.toLong(), "sha", 4, path)),
        )
        val streamingSource = StreamingRegionSource(
            catalogSource = FakeRegionCatalogSource(catalog),
            store = FakeRegionStore(), // not installed
            fetcher = FakeRegionRangeFetcher(mapOf(path to bundle)),
        )
        val client = clientOf(
            createMapRegionProxy(streamingSource = streamingSource, coroutineScope = this),
        )

        val head = client.mapRegionService.streamRegionRange("de-bayern", offset = 0, length = 8)
            .getOrNull() ?: error("streamRegionRange() returned a failure")
        assertEquals("STREAMED", head.get().decodeToString())
    }

    // ── railLinesIn (M9-4) ───────────────────────────────────────────────────

    @Test
    fun railLinesInReturnsEmptyWhenNoRegionCoversTheBox() = runTest {
        // Catalogue has Bayern; the query is over Tuscany. An empty list is the honest answer, and
        // the app reads it as "unavailable" rather than "no track" (RailRefinement's guard).
        val catalog = RegionCatalogData(
            2, "2026-07",
            listOf(RegionEntryData("de-bayern", "Bayern", "10.0,47.0,14.0,50.6", 10, "sha", 4, "p")),
        )
        val client = clientOf(
            createMapRegionProxy(
                catalogSource = FakeRegionCatalogSource(catalog),
                tileDecompressor = { bytes, _ -> bytes },
                coroutineScope = this,
            ),
        )
        val lines = client.mapRegionService
            .railLinesIn(minLatE7 = 437_700_000, minLngE7 = 112_400_000, maxLatE7 = 437_900_000, maxLngE7 = 112_700_000)
            .getOrNull() ?: error("railLinesIn() returned a failure")
        assertEquals(0, lines.toList().size)
    }

    @Test
    fun railLinesInIsDisabledWithoutAPlatformDecompressor() = runTest {
        // iOS today: no gzip binding. The op must answer emptily rather than fail or crash — and,
        // crucially, the *absence* of a decompressor is indistinguishable from "no track" on the
        // wire, which is exactly why the app treats this op's empty result as unavailable.
        val catalog = RegionCatalogData(
            2, "2026-07",
            listOf(RegionEntryData("firenze", "Firenze", "11.0,43.0,12.0,44.0", 10, "sha", 4, "p")),
        )
        val client = clientOf(
            createMapRegionProxy(
                catalogSource = FakeRegionCatalogSource(catalog),
                tileDecompressor = null,
                coroutineScope = this,
            ),
        )
        val lines = client.mapRegionService
            .railLinesIn(minLatE7 = 437_700_000, minLngE7 = 112_400_000, maxLatE7 = 437_900_000, maxLngE7 = 112_700_000)
            .getOrNull() ?: error("railLinesIn() returned a failure")
        assertEquals(0, lines.toList().size)
    }

    @Test
    fun railLinesInSurvivesAGarbageBundle() = runTest {
        // The region resolves, but its bytes are not a PMTiles archive. Must degrade to empty, not
        // throw — tile bytes are untrusted input that crossed a network.
        val bundle = "definitely-not-pmtiles".encodeToByteArray()
        val path = "regions/2026-07/firenze.pmtiles"
        val catalog = RegionCatalogData(
            2, "2026-07",
            listOf(RegionEntryData("firenze", "Firenze", "11.0,43.0,12.0,44.0", bundle.size.toLong(), "sha", 4, path)),
        )
        val streamingSource = StreamingRegionSource(
            catalogSource = FakeRegionCatalogSource(catalog),
            store = FakeRegionStore(),
            fetcher = FakeRegionRangeFetcher(mapOf(path to bundle)),
        )
        val client = clientOf(
            createMapRegionProxy(
                catalogSource = FakeRegionCatalogSource(catalog),
                streamingSource = streamingSource,
                tileDecompressor = { bytes, _ -> bytes },
                coroutineScope = this,
            ),
        )
        val lines = client.mapRegionService
            .railLinesIn(minLatE7 = 437_700_000, minLngE7 = 112_400_000, maxLatE7 = 437_900_000, maxLngE7 = 112_700_000)
            .getOrNull() ?: error("railLinesIn() returned a failure")
        assertEquals(0, lines.toList().size)
    }

    @Test
    fun streamRegionRangeFailsForARegionInNeitherStoreNorCatalog() = runTest {
        val client = clientOf(createMapRegionProxy(coroutineScope = this)) // empty catalog + store
        assertNull(client.mapRegionService.streamRegionRange("nope", offset = 0, length = 8).getOrNull())
    }

    @Test
    fun downloadProgressStreamsTheHolderTicks_thenCompletes_overTheWire() = runTest(timeout = STREAM_TIMEOUT) {
        // Progress is a real proxy→client *push*: the device-local holder is ticked per streamed chunk
        // (by DefaultRegionDownloader), and the stream op pushes each tick over the wire, ending when the
        // download finishes. We drive the holder directly to exercise the wire shape, not the fetch. Like
        // the other stream tests (InProcessProxyStreamBehaviorTest) the transport runs on real dispatchers,
        // so we collect on Dispatchers.Default and wait in real time rather than advancing virtual time.
        val holder = RegionDownloadProgressHolder()
        val client = clientOf(createMapRegionProxy(progressHolder = holder, coroutineScope = backgroundScope))

        val seen = MutableStateFlow(emptyList<Long>())
        val completed = MutableStateFlow(false)
        backgroundScope.launch(Dispatchers.Default) {
            client.mapRegionService.downloadProgress("at").collect { seen.value = seen.value + it.writtenBytes.get() }
            completed.value = true
        }

        // Let the subscription register, then tick. StateFlow conflation may collapse rapid intermediate
        // ticks — fine for a progress bar; we assert the latest lands and the stream ends.
        withContext(Dispatchers.Default) { delay(SETTLE_MS) }
        holder.start("at", totalBytes = 4_000L)
        holder.update("at", 1_500L)
        holder.update("at", 4_000L)
        withContext(Dispatchers.Default) { withTimeout(WAIT_MS) { while (4_000L !in seen.value) delay(POLL_MS) } }

        // Finishing the download removes the entry → the stream completes (present→absent is terminal).
        holder.finish("at")
        withContext(Dispatchers.Default) { withTimeout(WAIT_MS) { while (!completed.value) delay(POLL_MS) } }

        assertTrue(4_000L in seen.value, "the latest tick was pushed over the wire")
        assertTrue(completed.value, "the stream completed when the download finished")
    }

    @Test
    fun downloadProgressEndsWhenNoDownloadIsInFlight() = runTest(timeout = STREAM_TIMEOUT) {
        // Regression (`core-client-resubscription.md` R3 audit). The holder is in-memory in the proxy,
        // so after a proxy restart it is empty — and a resubscribing client used to fall into the
        // "not started yet, keep waiting" branch and hang FOREVER: no tick, no End, a progress bar
        // frozen with no completion or failure. The same hang was reachable without any restart
        // whenever `downloadRegion` failed before registering its entry.
        val client = clientOf(
            createMapRegionProxy(
                // Nothing in flight — a restarted proxy, or a download that died before registering.
                downloader = FakeRegionDownloader(downloading = false),
                coroutineScope = backgroundScope,
            ),
        )

        val completed = MutableStateFlow(false)
        backgroundScope.launch(Dispatchers.Default) {
            client.mapRegionService.downloadProgress("at").collect { }
            completed.value = true
        }

        withContext(Dispatchers.Default) { withTimeout(WAIT_MS) { while (!completed.value) delay(POLL_MS) } }
        assertTrue(completed.value, "the stream must END rather than wait for ticks that are never coming")
    }

    @Test
    fun downloadProgressKeepsWaitingWhileADownloadIsInFlight() = runTest(timeout = STREAM_TIMEOUT) {
        // The other half, and why this is not just a timeout: DefaultRegionDownloader registers its
        // entry only AFTER `catalogSource.load()`, a network fetch that can legitimately take tens of
        // seconds. A wall-clock cutoff would kill healthy downloads on a slow link, so the wait is
        // unbounded precisely while a download is genuinely in flight.
        val holder = RegionDownloadProgressHolder()
        val client = clientOf(
            createMapRegionProxy(
                downloader = FakeRegionDownloader(downloading = true),
                progressHolder = holder,
                coroutineScope = backgroundScope,
            ),
        )

        val seen = MutableStateFlow(emptyList<Long>())
        val completed = MutableStateFlow(false)
        backgroundScope.launch(Dispatchers.Default) {
            client.mapRegionService.downloadProgress("at").collect { seen.value = seen.value + it.writtenBytes.get() }
            completed.value = true
        }

        // Stay silent well past the registration grace, as a slow catalogue fetch would.
        withContext(Dispatchers.Default) { delay(SLOW_START_MS) }
        assertTrue(!completed.value, "an in-flight download must not be cut off while it is still starting")

        // The entry finally appears — the tick must still reach the client.
        holder.start("at", totalBytes = 100L)
        holder.update("at", 100L)
        withContext(Dispatchers.Default) { withTimeout(WAIT_MS) { while (100L !in seen.value) delay(POLL_MS) } }
        assertTrue(100L in seen.value, "ticks after a slow start are still delivered")
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun entry(id: String, name: String, byteSize: Long, tileSchemaVersion: Int) =
        RegionEntryData(
            regionId = id,
            name = name,
            coverage = "poly",
            byteSize = byteSize,
            sha256 = "deadbeef",
            tileSchemaVersion = tileSchemaVersion,
            path = "regions/2026-07/$id.pmtiles",
        )

    private fun installedRegion(id: String, name: String) = InstalledRegionData(
        regionId = id,
        name = name,
        localPath = "/regions/$id.pmtiles",
        byteSize = 1_000L,
        sha256 = "deadbeef",
        buildDate = "2026-07",
    )

    private fun clientOf(proxy: TestProxy): TenshuClient {
        val env = TenshuEnvironment(
            taskExecutor = proxy.localTaskExecutor,
            schemaRegistry = proxy.schemaRegistry,
            tContext = proxy.tContext,
            requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
        )
        return DefaultTenshuClient(env)
    }

    private companion object {
        val STREAM_TIMEOUT = 30.seconds
        const val WAIT_MS = 5_000L
        const val POLL_MS = 10L
        const val SETTLE_MS = 200L
        /** Comfortably past DownloadProgressImplementation's registration grace (3 s). */
        private const val SLOW_START_MS = 4_000L
    }
}
