@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.tenshu.sdk.ios

import app.tenshu.mapregion.app.IosRegionBundleStager
import app.tenshu.mapregion.app.RegionTerminus
import app.tenshu.timeline.model.GeoPoint
import app.tenshu.timeline.ui.screens.trip.TripDetailUiState
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.mapregion.proxy.MapRegionProxyComponents
import org.tenshu.mapregion.proxy.RegionEntryData
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy
import app.tenshu.timeline.data.RegionBundleResolver as TimelineRegionBundleResolver
import app.tenshu.timeline.data.RouteRegionStream as TimelineRouteRegionStream
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * C3 coverage — the offline-region **data path** on iOS, Timeline's half. Both map-drawing surfaces
 * used to hardcode `regions = null`, which made `regionBundlePath`/`regionStream` permanently null on
 * the platform no matter what the SwiftUI did. Two claims are worth proving, and they are
 * independent:
 *
 *  1. **The surface actually hands the resolver to the view models** — asserted through the VM's own
 *     state, not by reading back the field, so a factory that quietly dropped it would still fail.
 *  2. **The route-1 graph really serves `MapRegionService` in-process** (the "verify rather than
 *     assume" half) and the iOS stager lands its bytes. This one runs the *real* production proxy
 *     graph, installs a region into its store, and drains it end to end — no catalogue fetch, so it
 *     is network-free and deterministic.
 *
 * Fit's half is `FitRegionResolverWiringTest`. ⚠ They were one test until `ios-per-app-frameworks.md`
 * P4 put each app in its own framework module; split, neither needs the other app's code.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimelineRegionResolverWiringTest {

    private val dispatcher = StandardTestDispatcher()
    private val tempDirs = mutableListOf<String>()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        tempDirs.forEach { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
        // Leave the surface on its honest default, as the other surface tests do.
        TimelineSurface.installNotConnected()
    }

    // ── 1. the surface passes the resolver through ─────────────────────────────────────────

    @Test
    fun tripDetailReceivesTheInstalledResolverAndRendersItsStream() = vmTest(dispatcher) { vms ->
        TimelineSurface.installMock()
        val resolver = RecordingTimelineResolver()
        TimelineSurface.installRegionResolver(resolver)

        val vm = vms.own(TimelineSurface.tripViewModel("lisbon-2026-04"))
        advanceUntilIdle()

        assertEquals(5, resolver.seenRoute.size, "the mock trip's whole route reaches the resolver")
        val state = vm.uiState.value
        assertTrue(state is TripDetailUiState.Loaded)
        assertEquals("pt-lisboa", state.regionStream?.regionId, "the resolved stream lands in the VM state")
        assertNotNull(state.offlineOffer, "the offline offer is reachable now, not structurally dead")
        assertEquals(1, state.offlineOffer?.missing?.size)
    }

    @Test
    fun momentAndPlaceDetailAlsoBuildOverTheInstalledResolver() = vmTest(dispatcher) { vms ->
        // The two other factories that carried the same silent `regions = null`. Their resolver use
        // is *conditional* on data the mock does not carry — a place needs non-zero coordinates and
        // a moment needs a MOVEMENT kind with trace ids — so there is no behavioural assertion to
        // make over `MockTimelineRepository` here; `tripDetailReceivesTheInstalledResolver…` is the
        // behavioural proof of the pass-through. What this pins is that both factories still build
        // against a live resolver (they take it in a different argument position from the trip's).
        TimelineSurface.installMock()
        val resolver = RecordingTimelineResolver()
        TimelineSurface.installRegionResolver(resolver)

        val home = vms.own(TimelineSurface.homeViewModel())
        advanceUntilIdle()
        val state = home.uiState.value
        val momentId = state.days.firstNotNullOf { it.moments.firstOrNull() }.id

        vms.own(TimelineSurface.momentViewModel(momentId))
        vms.own(TimelineSurface.placeViewModel(state.places.first().id))
        advanceUntilIdle()

        assertTrue(
            TimelineSurface.installedRegionResolver() === resolver,
            "the surface keeps handing out the installed resolver, not a per-call null",
        )
    }

    @Test
    fun theMockAndNotConnectedTiersCarryNoResolver() = runTest(dispatcher) {
        // Regression guard: a preview/mock run must not reach for the region catalogue. Installing
        // either tier clears whatever a previous live tier left behind.
        TimelineSurface.installRegionResolver(RecordingTimelineResolver())
        TimelineSurface.installMock()
        assertNull(TimelineSurface.installedRegionResolver())

        TimelineSurface.installRegionResolver(RecordingTimelineResolver())
        TimelineSurface.installNotConnected()
        assertNull(TimelineSurface.installedRegionResolver())
    }

    // ── 2. the route-1 graph serves the region ops, and the iOS stager lands the bytes ─────

    @Test
    fun theRouteOneGraphServesARegionDrainThroughTheIosStager() = runBlocking {
        val assembly = TimelineLocalRuntime.assemble(
            replicaDirectory = tempDir("region-replica-"),
            secureStorage = InMemorySecureStorageDelegate(),
        )

        // Land a region in the *proxy's* canonical store — the same store a real `downloadRegion`
        // commits into — so `installed()` + `readRegionRange` have something to serve.
        val bundle = ByteArray(40_000) { (it * 17 % 241).toByte() }
        assembly.koin.get<MapRegionProxyComponents>().store.add(
            entry = RegionEntryData(
                regionId = "it-firenze",
                name = "Firenze",
                coverage = "11.10,43.72,11.35,43.83",
                byteSize = bundle.size.toLong(),
                sha256 = "",
                tileSchemaVersion = 1,
                path = "regions/it-firenze.pmtiles",
            ),
            buildDate = "2026-08-01",
            bytes = bundle,
        )

        val stagingDir = tempDir("region-staging-")
        val terminus = RegionTerminus(assembly.client.tenshuClient, IosRegionBundleStager(stagingDir))

        // Point-agnostic on purpose: this asserts the op round-trip + drain, with no catalogue
        // fetch, so the test never depends on the tile host being reachable.
        val path = terminus.renderableRegionPath()

        assertEquals(
            "$stagingDir/it-firenze.pmtiles",
            path,
            "the in-process MapRegionService must serve installed()+readRegionRange on route 1",
        )
        assertContentEquals(bundle, readFile(path!!), "the staged bundle is byte-identical")
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────

    private class RecordingTimelineResolver : TimelineRegionBundleResolver {
        var seenRoute: List<GeoPoint> = emptyList()

        override suspend fun bundlePath(route: List<GeoPoint>): String? {
            seenRoute = route
            return "/staged/pt-lisboa.pmtiles"
        }

        override suspend fun streamHandle(route: List<GeoPoint>): TimelineRouteRegionStream? {
            seenRoute = route
            return TimelineRouteRegionStream("pt-lisboa", totalSize = 4096) { _, _ -> ByteArray(0) }
        }

        override suspend fun offlineOffer(route: List<GeoPoint>) =
            app.tenshu.timeline.data.TripOfflineOffer(
                regions = listOf(
                    app.tenshu.timeline.data.TripOfflineRegion(
                        regionId = "pt-lisboa",
                        name = "Lisboa",
                        byteSize = 4096,
                        installed = false,
                    ),
                ),
            )
    }

    private fun tempDir(prefix: String): String =
        (NSTemporaryDirectory() + prefix + Random.nextLong().toString(16)).also { tempDirs += it }

    private fun readFile(path: String): ByteArray? =
        NSData.dataWithContentsOfFile(path)?.let { data ->
            val size = data.length.toInt()
            if (size == 0) return@let ByteArray(0)
            ByteArray(size).also { out ->
                out.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
            }
        }
}
