package org.tenshu.mapregion.proxy

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.tenshu.core.common.proxy.BackgroundLiveness
import org.tenshu.core.common.util.sha256
import org.tenshu.mapregion.test.FakeRegionCatalogSource
import org.tenshu.mapregion.test.FakeRegionStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

/**
 * [DefaultRegionDownloader] over a MockEngine: fetch → sha256-gate → store. Uses the real
 * multiplatform [sha256] (an expect/actual, target-safe), so the integrity gate is exercised
 * with genuine hashing, not a fake.
 */
class DefaultRegionDownloaderTest {

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun downloadsVerifiesShaAndLandsInTheStore() = runTest {
        val bundle = "PMTILES-BUNDLE-BYTES".encodeToByteArray()
        val entry = RegionEntryData(
            regionId = "de-bayern", name = "Bayern", coverage = "poly",
            byteSize = bundle.size.toLong(), sha256 = sha256(bundle).toHexString(),
            tileSchemaVersion = 4, path = "regions/2026-07/de-bayern.pmtiles",
        )
        val store = FakeRegionStore()
        val downloader = DefaultRegionDownloader(
            httpClient = mockClientServing("/regions/2026-07/de-bayern.pmtiles", bundle),
            baseUrl = "https://tiles.test",
            scope = backgroundScope,
            liveness = BackgroundLiveness(),
            progress = RegionDownloadProgressHolder(),
            catalogSource = FakeRegionCatalogSource(RegionCatalogData(2, "2026-07", listOf(entry))),
            store = store,
        )

        val landed = downloader.download("de-bayern")

        assertEquals("de-bayern", landed.regionId)
        assertEquals(entry.sha256, landed.sha256)
        assertEquals("2026-07", landed.buildDate)
        assertEquals(listOf("de-bayern"), store.installed().map { it.regionId })
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun theBundleIsStreamedInChunks_neverMaterialisedWhole() = runTest {
        // The regression that motivated track 3e: the downloader used to hold the ENTIRE bundle in one
        // ByteArray, and Control died with OutOfMemoryError on the first real (140 MB) region. Memory
        // must now be O(chunk), so the body has to arrive in chunks and be handed to the store in
        // chunks — never accumulated.
        val bundle = ByteArray(10_000) { (it % 251).toByte() }
        val entry = RegionEntryData(
            regionId = "big", name = "Big", coverage = "",
            byteSize = bundle.size.toLong(), sha256 = sha256(bundle).toHexString(),
            tileSchemaVersion = 4, path = "regions/big.pmtiles",
        )
        val store = FakeRegionStore()
        val downloader = DefaultRegionDownloader(
            httpClient = mockClientServing("/regions/big.pmtiles", bundle),
            baseUrl = "https://tiles.test",
            scope = backgroundScope,
            liveness = BackgroundLiveness(),
            progress = RegionDownloadProgressHolder(),
            catalogSource = FakeRegionCatalogSource(RegionCatalogData(1, "2026-07", listOf(entry))),
            store = store,
            chunkBytes = 1024, // a small bound, so the assertion is about the mechanism, not the size
        )

        val landed = downloader.download("big")

        // Landed correctly…
        assertEquals(bundle.size.toLong(), landed.byteSize)
        assertContentEquals(bundle, store.readRange("big", 0, bundle.size))
        // …and every chunk handed to the store respected the memory bound (no whole-bundle write).
        assertTrue(store.stagedChunkSizes.size > 1, "the body must arrive in several chunks")
        assertTrue(
            store.stagedChunkSizes.all { it <= 1024 },
            "no write may exceed the chunk bound: ${store.stagedChunkSizes}",
        )
        assertEquals(bundle.size, store.stagedChunkSizes.sum())
        assertFalse(store.hasStaged("big"), "the staging file must be consumed by the commit")
    }

    @Test
    fun rejectsAShaMismatch_andLeavesNoHalfWrittenBundleBehind() = runTest {
        val bundle = "GOOD-BYTES".encodeToByteArray()
        val entry = RegionEntryData(
            regionId = "x", name = "X", coverage = "",
            byteSize = bundle.size.toLong(), sha256 = "not-the-real-hash",
            tileSchemaVersion = 4, path = "regions/x.pmtiles",
        )
        val store = FakeRegionStore()
        val downloader = DefaultRegionDownloader(
            httpClient = mockClientServing("/regions/x.pmtiles", bundle),
            baseUrl = "https://tiles.test",
            scope = backgroundScope,
            liveness = BackgroundLiveness(),
            progress = RegionDownloadProgressHolder(),
            catalogSource = FakeRegionCatalogSource(RegionCatalogData(1, "2026-07", listOf(entry))),
            store = store,
        )
        assertFailsWith<RegionBundleIntegrityException> { downloader.download("x") }

        // The bytes were streamed to disk BEFORE the gate could run (that is the point of streaming),
        // so the failure path must clean up after itself — and must never promote them.
        assertTrue(store.installed().isEmpty(), "a failed download must not appear installed")
        assertFalse(store.hasStaged("x"), "the staging file must be deleted on failure")
    }

    @Test
    fun aFailedDownloadLeavesAnAlreadyResidentRegionIntact() = runTest {
        // The refresh case: the region is resident and being served while a new copy streams in. The
        // canonical bundle must survive a failed refresh untouched — the map keeps working.
        val resident = InstalledRegionData(
            regionId = "x", name = "X", localPath = "/fake/x.pmtiles",
            byteSize = 3, sha256 = "old-sha", buildDate = "2026-04",
        )
        val store = FakeRegionStore(
            initial = listOf(resident),
            bytes = mapOf("x" to "old".encodeToByteArray()),
        )
        val entry = RegionEntryData(
            regionId = "x", name = "X", coverage = "",
            byteSize = 5, sha256 = "not-the-real-hash", // the new copy will fail its gate
            tileSchemaVersion = 4, path = "regions/x.pmtiles",
        )
        val downloader = DefaultRegionDownloader(
            httpClient = mockClientServing("/regions/x.pmtiles", "newer".encodeToByteArray()),
            baseUrl = "https://tiles.test",
            scope = backgroundScope,
            liveness = BackgroundLiveness(),
            progress = RegionDownloadProgressHolder(),
            catalogSource = FakeRegionCatalogSource(RegionCatalogData(1, "2026-07", listOf(entry))),
            store = store,
        )

        assertFailsWith<RegionBundleIntegrityException> { downloader.download("x") }

        assertEquals("old-sha", store.installed().single().sha256, "the resident copy must be untouched")
        assertContentEquals("old".encodeToByteArray(), store.readRange("x", 0, 3))
        assertFalse(store.hasStaged("x"))
    }

    @Test
    fun rejectsAnUnknownRegion() = runTest {
        val downloader = DefaultRegionDownloader(
            httpClient = mockClientServing("/never", ByteArray(0)),
            baseUrl = "https://tiles.test",
            scope = backgroundScope,
            liveness = BackgroundLiveness(),
            progress = RegionDownloadProgressHolder(),
            catalogSource = FakeRegionCatalogSource(RegionCatalogData(1, "2026-07", emptyList())),
            store = FakeRegionStore(),
        )
        assertFailsWith<UnknownRegionException> { downloader.download("nope") }
    }

    @Test
    fun downloadSurvivesItsCallerBeingCancelled_andLivenessReleasesWhenDone() =
        runTest(timeout = SURVIVE_TIMEOUT) {
            // The whole point of running the fetch on a process-lifetime scope + BackgroundLiveness: when the
            // app backgrounds mid-download, `ProxyService.onDestroy` tears down the RPC/session scope — the
            // download must NOT die with it (that was the §7.1 bug this reworked). We model the app going away
            // by cancelling the coroutine that *called* download(), and assert the region still lands and the
            // liveness is released. Real dispatchers + a gated fetch (no virtual time) so the cross-scope
            // hand-off is exercised honestly — this is the headless proof of what the 11 MB emulator download
            // finished too fast to show (survives being backgrounded mid-fetch).
            val gate = CompletableDeferred<Unit>()
            val bundle = "STREAMED-REGION-BYTES".encodeToByteArray()
            val entry = RegionEntryData(
                regionId = "x", name = "X", coverage = "",
                byteSize = bundle.size.toLong(), sha256 = sha256(bundle).toHexString(),
                tileSchemaVersion = 4, path = "regions/x.pmtiles",
            )
            val engine = MockEngine {
                gate.await() // hold the response open until the test lets the fetch complete
                respond(bundle, HttpStatusCode.OK)
            }
            val store = FakeRegionStore()
            val liveness = BackgroundLiveness()
            // A real scope standing in for the proxy's process-lifetime scope (outlives any one RPC).
            val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val downloader = DefaultRegionDownloader(
                    httpClient = HttpClient(engine),
                    baseUrl = "https://tiles.test",
                    catalogSource = FakeRegionCatalogSource(RegionCatalogData(1, "2026-07", listOf(entry))),
                    store = store,
                    scope = downloadScope,
                    liveness = liveness,
                    progress = RegionDownloadProgressHolder(),
                )

                // A caller (the "RPC") kicks the download, then goes away mid-fetch.
                val caller = launch(Dispatchers.Default) { downloader.download("x") }
                awaitReal { liveness.active.value == 1 } // registered + FGS held
                assertTrue(store.installed().isEmpty(), "still downloading — nothing landed yet")

                caller.cancel() // the app backgrounds / the per-client session scope is torn down

                gate.complete(Unit) // let the still-running fetch finish
                awaitReal { store.installed().isNotEmpty() }
                assertEquals(
                    listOf("x"), store.installed().map { it.regionId },
                    "the download completed even though its caller was cancelled",
                )
                awaitReal { liveness.active.value == 0 }
                assertEquals(0, liveness.active.value, "liveness is released once the download finishes")
            } finally {
                downloadScope.cancel()
            }
        }


    // ── resume (§7.1 track 3f "still open: resume") ──────────────────────────────────────────────

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun aSecondAttemptRangeResumesFromTheStagedBytes() = runTest {
        val bundle = "PMTILES-BUNDLE-BYTES".encodeToByteArray()
        val entry = entryFor(bundle)
        val store = FakeRegionStore()
        // An earlier attempt got 8 bytes down before the connection died.
        store.seedStaged("de-bayern", bundle.copyOfRange(0, 8), entry.sha256)

        val requests = mutableListOf<String?>()
        val client = rangeAwareClient(entry.path, bundle, requests)
        val landed = downloaderFor(entry, store, client, backgroundScope).download("de-bayern")

        assertEquals("bytes=8-", requests.single(), "the tail was requested, not the whole bundle")
        assertEquals(entry.sha256, landed.sha256, "the digest still covers the WHOLE bundle")
        assertContentEquals(bundle, store.bytesOf("de-bayern"), "resumed file is byte-identical")
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun stagedBytesForADifferentBuildAreNotResumedOnto() = runTest {
        val bundle = "PMTILES-BUNDLE-BYTES".encodeToByteArray()
        val entry = entryFor(bundle)
        val store = FakeRegionStore()
        // ⚠ The trap the marker exists for: bytes left by an attempt at the PREVIOUS build. Appending
        // the new build's tail to them would fail the sha gate only after paying for the whole remainder.
        store.seedStaged("de-bayern", "OLD-BUILD".encodeToByteArray(), "a-different-sha")

        val requests = mutableListOf<String?>()
        val landed = downloaderFor(entry, store, rangeAwareClient(entry.path, bundle, requests), backgroundScope)
            .download("de-bayern")

        assertEquals(null, requests.single(), "no Range header — this is a fresh download")
        assertEquals(entry.sha256, landed.sha256)
        assertContentEquals(bundle, store.bytesOf("de-bayern"))
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun aServerThatIgnoresRangeRestartsInsteadOfAppending() = runTest {
        val bundle = "PMTILES-BUNDLE-BYTES".encodeToByteArray()
        val entry = entryFor(bundle)
        val store = FakeRegionStore()
        store.seedStaged("de-bayern", bundle.copyOfRange(0, 8), entry.sha256)

        // ⚠⚠ 200 + the whole body in answer to a Range request. Appending it would double the prefix and
        // corrupt the file — caught only by the sha gate, after paying for the entire download.
        val client = HttpClient(MockEngine { respond(bundle, HttpStatusCode.OK) })
        val landed = downloaderFor(entry, store, client, backgroundScope).download("de-bayern")

        assertEquals(entry.sha256, landed.sha256)
        assertContentEquals(bundle, store.bytesOf("de-bayern"), "taken from the top, not appended")
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun aTransportFailureKeepsThePartialButAShaFailureDoesNot() = runTest {
        val bundle = "PMTILES-BUNDLE-BYTES".encodeToByteArray()
        val entry = entryFor(bundle)

        // Transport failure mid-body: the partial is what the next attempt resumes from, so it must live.
        val dropped = FakeRegionStore()
        val dropping = HttpClient(MockEngine { throw IllegalStateException("connection reset") })
        assertFailsWith<IllegalStateException> { downloaderFor(entry, dropped, dropping, backgroundScope).download("de-bayern") }
        assertTrue(dropped.hasStaged("de-bayern"), "a dropped connection must leave the bytes to resume")

        // sha mismatch: the staged bytes are demonstrably wrong and must NOT survive to be resumed onto,
        // or every later attempt inherits the corruption and fails the same gate forever.
        val corrupt = FakeRegionStore()
        val wrongBytes = HttpClient(MockEngine { respond("NOT-THE-BUNDLE".encodeToByteArray(), HttpStatusCode.OK) })
        assertFailsWith<RegionBundleIntegrityException> {
            downloaderFor(entry, corrupt, wrongBytes, backgroundScope).download("de-bayern")
        }
        assertFalse(corrupt.hasStaged("de-bayern"), "corrupt bytes must not be left to poison a resume")
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun resumedProgressStartsFromTheStagedBytesNotZero() = runTest {
        val bundle = "PMTILES-BUNDLE-BYTES".encodeToByteArray()
        val entry = entryFor(bundle)
        val store = FakeRegionStore()
        store.seedStaged("de-bayern", bundle.copyOfRange(0, 8), entry.sha256)
        val progress = RegionDownloadProgressHolder()

        val seen = mutableListOf<Long>()
        // ⚠⚠ Wait for the collector to be LIVE before downloading, rather than assuming `launch`
        // has started it. `progress` is a StateFlow whose entry exists only while the download is
        // in flight — `finish()` REMOVES it, deliberately (that present→absent edge is the stream's
        // completion signal). So a subscriber that arrives after the download completes sees a map
        // with no `de-bayern` key and collects NOTHING, and `assertTrue(seen.isNotEmpty())` fails
        // with a bare "Expected value to be true".
        //
        // That is not hypothetical: under `StandardTestDispatcher` this `launch` is merely QUEUED,
        // while the download itself runs on a real dispatcher (see `awaitReal` below) — so a loaded
        // machine can finish the whole download before the collector is ever dispatched. It was the
        // most frequent member of the gate flake population, 3 of 4 full gates and 0 of 5 isolated
        // runs (`docs/tech-debt.md`, row 17).
        //
        // ⚠⚠ And the collector must run on a REAL dispatcher, which is the half that is easy to
        // miss: `progress` is a *conflated* StateFlow, so a collector parked on the test dispatcher
        // does not merely observe late — every intermediate value collapses into the final (empty)
        // map before it is ever dispatched, and it observes nothing at all. Awaiting the
        // subscription without this is NOT enough; measured, it makes the isolated case *worse*,
        // because it also removes the accidental mid-download dispatch that used to save it.
        //
        // ⭐ To re-check both halves in one line, insert `withContext(Dispatchers.Default) { delay(300) }`
        // as the first statement of the `launch` below: it fails 3/3 with the exact wild message
        // when either half is missing, and passes with both in place.
        val subscribed = CompletableDeferred<Unit>()
        val job = backgroundScope.launch(Dispatchers.Default) {
            // ⚠⚠ LOAD-BEARING, not a sleep to delete. `progress` is a CONFLATED StateFlow, so with
            // the collector racing the download the `start` snapshot and the first `update` collapse
            // into one value and the assertion below silently stops testing anything — which is how
            // a real snap-to-zero survived in `RegionDownloader` under a guard written to catch it.
            // Descheduling here holds the collector until the download is ready to begin, so every
            // published snapshot is observed. Removing it does not fail the test; it *hollows* it.
            withContext(Dispatchers.Default) { delay(SETTLE_MS) }
            progress.progress
                .onSubscription { subscribed.complete(Unit) }
                .collect { all -> all["de-bayern"]?.let { seen += it.writtenBytes } }
        }
        subscribed.await()
        downloaderFor(entry, store, rangeAwareClient(entry.path, bundle, mutableListOf()), backgroundScope, progress)
            .download("de-bayern")
        job.cancel()

        // The bar must pick up where it stopped rather than snapping to 0 and re-climbing ground the
        // user already paid for.
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.none { it < 8L }, "progress went backwards: $seen")
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun entryFor(bundle: ByteArray) = RegionEntryData(
        regionId = "de-bayern", name = "Bayern", coverage = "poly",
        byteSize = bundle.size.toLong(), sha256 = sha256(bundle).toHexString(),
        tileSchemaVersion = 4, path = "regions/2026-07/de-bayern.pmtiles",
    )

    /**
     * ⚠ [scope] must be the test's `backgroundScope`, never the `TestScope` itself: the downloader runs
     * its fetch on an internal `SupervisorJob` child of whatever it is given, and `runTest` waits a full
     * minute for any child of its own scope before failing with `UncompletedCoroutinesError`.
     */
    private fun downloaderFor(
        entry: RegionEntryData,
        store: FakeRegionStore,
        client: HttpClient,
        scope: CoroutineScope,
        progress: RegionDownloadProgressHolder = RegionDownloadProgressHolder(),
    ) = DefaultRegionDownloader(
        httpClient = client,
        baseUrl = "https://tiles.test",
        scope = scope,
        liveness = BackgroundLiveness(),
        progress = progress,
        catalogSource = FakeRegionCatalogSource(RegionCatalogData(2, "2026-07", listOf(entry))),
        store = store,
    )

    /** Honours `Range: bytes=N-` with a 206 and the tail, like the CDN does; records what was asked. */
    private fun rangeAwareClient(path: String, bytes: ByteArray, requests: MutableList<String?>): HttpClient =
        HttpClient(MockEngine { request ->
            val range = request.headers["Range"]
            requests += range
            if (request.url.encodedPath != "/$path") return@MockEngine respond(ByteArray(0), HttpStatusCode.NotFound)
            val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull()
            if (from == null) respond(bytes, HttpStatusCode.OK)
            else respond(bytes.copyOfRange(from, bytes.size), HttpStatusCode.PartialContent)
        })

    private fun mockClientServing(path: String, bytes: ByteArray): HttpClient {
        val engine = MockEngine { request ->
            if (request.url.encodedPath == path) respond(bytes, HttpStatusCode.OK)
            else respond(ByteArray(0), HttpStatusCode.NotFound)
        }
        return HttpClient(engine)
    }

    /** Poll [condition] in real time (the download runs on a real dispatcher, not virtual time). */
    private suspend fun awaitReal(condition: () -> Boolean) =
        withContext(Dispatchers.Default) { withTimeout(WAIT_MS) { while (!condition()) delay(POLL_MS) } }

    private companion object {
        val SURVIVE_TIMEOUT = 30.seconds
        const val WAIT_MS = 5_000L
        const val POLL_MS = 10L

        /**
         * How long a progress collector is held off so the download is demonstrably ready before it
         * subscribes. See its one use — it exists to stop a conflated StateFlow from collapsing the
         * very snapshot under test, not to wait for anything.
         */
        const val SETTLE_MS = 300L
    }
}
