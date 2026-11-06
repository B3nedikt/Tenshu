package app.tenshu.timeline.ui.screens

import app.tenshu.timeline.data.MockTimelineRepository
import app.tenshu.timeline.data.RegionBundleResolver
import app.tenshu.timeline.data.TripOfflineOffer
import app.tenshu.timeline.data.TripOfflineRegion
import app.tenshu.timeline.model.GeoPoint
import app.tenshu.timeline.ui.screens.trip.TripDetailUiState
import app.tenshu.timeline.ui.screens.trip.TripDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * "Keep this trip's map areas offline" (`maps-region-delivery.md` §7.1 track 3d).
 *
 * The point of this surface — and the reason it is not just a copy of Maps' — is that a **journey can cross
 * several regions**. The offer is a set, its price is the sum of the ones not yet resident, and keeping it
 * offline must fetch **all** of them: a trip whose far end still needs the network is not "available
 * offline".
 */
class TripOfflineOfferTest {

    private val repo = MockTimelineRepository()

    /** The mock trip that carries a route (the offer is resolved from it). */
    private val tripId = "lisbon-2026-04"

    private class FakeRegions(
        private val offer: TripOfflineOffer?,
        private val failFor: Set<String> = emptySet(),
    ) : RegionBundleResolver {
        val downloaded = mutableListOf<String>()
        override suspend fun bundlePath(route: List<GeoPoint>): String? = null
        override suspend fun offlineOffer(route: List<GeoPoint>): TripOfflineOffer? = offer
        override suspend fun download(regionId: String): Boolean {
            downloaded += regionId
            return regionId !in failFor
        }
    }

    private fun region(id: String, size: Long, installed: Boolean = false) =
        TripOfflineRegion(regionId = id, name = id, byteSize = size, installed = installed)

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun loaded(vm: TripDetailViewModel) = vm.uiState.value as TripDetailUiState.Loaded

    @Test
    fun aTripCrossingTwoRegionsOffersBoth_andKeepingItOfflineFetchesBoth() = vmTest { vms ->
        val regions = FakeRegions(
            TripOfflineOffer(listOf(region("at", 95_000_000L), region("ch", 120_000_000L))),
        )
        val vm = vms.own(TripDetailViewModel(repo, regions, tripId))

        val offer = loaded(vm).offlineOffer!!
        assertEquals(2, offer.missing.size)
        assertEquals(215_000_000L, offer.missingBytes, "the price is the SUM of the missing regions")
        assertFalse(offer.fullyResident)

        vm.keepOffline()

        // Both, not just the one under the centroid — otherwise the far end of the trip still needs a
        // network, which is the whole failure mode this surface exists to avoid.
        assertEquals(listOf("at", "ch"), regions.downloaded)
        assertTrue(loaded(vm).offlineOffer!!.fullyResident)
        assertFalse(loaded(vm).offlineDownloading)
    }

    @Test
    fun anAlreadyResidentRegionIsNotChargedForOrRefetched() = vmTest { vms ->
        val regions = FakeRegions(
            TripOfflineOffer(listOf(region("at", 95_000_000L, installed = true), region("ch", 120_000_000L))),
        )
        val vm = vms.own(TripDetailViewModel(repo, regions, tripId))

        val offer = loaded(vm).offlineOffer!!
        assertEquals(listOf("ch"), offer.missing.map { it.regionId })
        assertEquals(120_000_000L, offer.missingBytes, "a resident region costs nothing to keep")

        vm.keepOffline()

        assertEquals(listOf("ch"), regions.downloaded, "must not re-fetch a region already on disk")
        assertTrue(loaded(vm).offlineOffer!!.fullyResident)
    }

    @Test
    fun partialSuccessIsKept_soARetryOnlyFetchesWhatIsStillMissing() = vmTest { vms ->
        val regions = FakeRegions(
            TripOfflineOffer(listOf(region("at", 95_000_000L), region("ch", 120_000_000L))),
            failFor = setOf("ch"),
        )
        val vm = vms.own(TripDetailViewModel(repo, regions, tripId))

        vm.keepOffline()

        val after = loaded(vm).offlineOffer!!
        assertFalse(after.fullyResident, "one region failed — the trip is not fully offline")
        assertEquals(listOf("ch"), after.missing.map { it.regionId }, "the landed region stays landed")
        assertFalse(loaded(vm).offlineDownloading)
        // …and the partial failure is SURFACED, not swallowed: a multi-minute fetch that quietly does
        // nothing leaves the user staring at an unchanged row.
        assertTrue(loaded(vm).offlineFailed, "a region that failed must reach the UI")
        assertNull(loaded(vm).offlineProgress, "progress is cleared when the run ends")

        // The retry re-fetches only the one that failed.
        regions.downloaded.clear()
        vm.keepOffline()
        assertEquals(listOf("ch"), regions.downloaded)
    }

    @Test
    fun noCoveringRegionsMeansNoRow_andKeepOfflineIsANoOp() = vmTest { vms ->
        val regions = FakeRegions(offer = null)
        val vm = vms.own(TripDetailViewModel(repo, regions, tripId))

        assertEquals(null, loaded(vm).offlineOffer)
        vm.keepOffline()
        assertTrue(regions.downloaded.isEmpty())
    }
}
