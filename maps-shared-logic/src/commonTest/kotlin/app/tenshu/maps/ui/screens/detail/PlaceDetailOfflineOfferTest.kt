package app.tenshu.maps.ui.screens.detail

import app.tenshu.maps.data.RegionsRead
import app.tenshu.maps.data.MockPlaceRepository
import org.tenshu.viewmodel.test.vmTest
import app.tenshu.maps.data.OfflineRegionOffer
import app.tenshu.maps.data.RegionRepository
import app.tenshu.maps.model.GeoPoint
import app.tenshu.maps.model.RegionItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.OwnedViewModels

/**
 * The contextual "keep this area offline" trigger (`maps-region-delivery.md` §7.1 track 3d). The map
 * already renders by streaming, so the offer is an *extra*: it must never re-download a resident region,
 * never claim residency it did not achieve, and never block the surface when there is nothing to offer.
 */
class PlaceDetailOfflineOfferTest {

    private val places = MockPlaceRepository()

    /** The id of a mock place that carries a coordinate (the offer is resolved from it). */
    private val placeId = "home"

    private class FakeRegions(
        private val offer: OfflineRegionOffer?,
        private val downloadSucceeds: Boolean = true,
    ) : RegionRepository {
        var downloads = 0
        override suspend fun regions(): RegionsRead = RegionsRead.Regions(emptyList())
        override suspend fun delete(regionId: String) {}
        override suspend fun offlineOffer(location: GeoPoint?): OfflineRegionOffer? = offer
        override suspend fun download(regionId: String): Boolean {
            downloads++
            return downloadSucceeds
        }
    }

    private fun offer(installed: Boolean = false) =
        OfflineRegionOffer("test-toscana", "Toscana", 140_165_388L, installed = installed)

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /**
     * ⚠⚠ Settles the scheduler, then reads. Every state this VM exposes is produced by `init`'s
     * `viewModelScope.launch`, so reading `uiState.value` without advancing first is a sample, not
     * a fact. [advanceUntilIdle] leaves nothing to run, which is what makes the read sound — see
     * `docs/testing.md` §10.
     */
    private fun TestScope.loaded(vm: PlaceDetailViewModel): PlaceDetailUiState.Loaded {
        advanceUntilIdle()
        return vm.uiState.value as PlaceDetailUiState.Loaded
    }

    /**
     * A [vmTest] with this class's fixture already built. The lifetime handling — and the reason it
     * is not optional — lives in [app.tenshu.maps.OwnedViewModels]; this class was the *reporter*
     * for a leak that `MapsViewModelsTest` was producing, which is why fixing it here alone left
     * the whole-module run still red.
     */
    private fun vmTest(regions: FakeRegions, body: suspend TestScope.(PlaceDetailViewModel) -> Unit) =
        vmTest { vms ->
            // ⚠ The VM's three region lookups run on THIS scheduler, not `Dispatchers.Default`.
            // That is the whole fix: `init`'s work becomes reachable by `advanceUntilIdle()`.
            body(
                vms.own(
                    PlaceDetailViewModel(
                        places,
                        regions,
                        placeId,
                        StandardTestDispatcher(testScheduler),
                    ),
                ),
            )
        }

    @Test
    fun aCoveringRegionIsOffered_andKeepOfflineDownloadsItExactlyOnce() {
        val regions = FakeRegions(offer())
        vmTest(regions) { vm ->
            assertEquals("test-toscana", loaded(vm).offlineOffer!!.regionId)
            assertFalse(loaded(vm).offlineOffer!!.installed)

            vm.keepOffline()

            assertEquals(1, regions.downloads)
            assertTrue(loaded(vm).offlineOffer!!.installed, "a landed download marks the region resident")
            assertFalse(loaded(vm).offlineDownloading)

            // Already resident → tapping again must not re-fetch hundreds of megabytes.
            vm.keepOffline()
            assertEquals(1, regions.downloads)
        }
    }

    @Test
    fun aFailedDownloadLeavesTheOfferStanding_becauseTheMapKeepsStreamingAnyway() {
        val regions = FakeRegions(offer(), downloadSucceeds = false)
        vmTest(regions) { vm ->
            advanceUntilIdle()
            vm.keepOffline()

            assertEquals(1, regions.downloads)
            assertFalse(loaded(vm).offlineOffer!!.installed, "a failed download must not claim residency")
            assertFalse(loaded(vm).offlineDownloading, "the in-flight flag is always cleared")
        }
    }

    @Test
    fun aFailedDownloadIsSurfaced_notSwallowed_andTheRowStaysRetryable() {
        // A hundreds-of-MB fetch that silently does nothing is the worst outcome: the user is left
        // staring at an unchanged row. The failure must be visible — and retryable.
        val regions = FakeRegions(offer(), downloadSucceeds = false)
        vmTest(regions) { vm ->
            advanceUntilIdle()
            vm.keepOffline()

            assertTrue(loaded(vm).offlineFailed, "the failure must reach the UI")
            assertFalse(loaded(vm).offlineDownloading)
            assertNull(loaded(vm).offlineProgress, "progress is cleared when the download ends")
            assertFalse(loaded(vm).offlineOffer!!.installed)

            // Retrying clears the failure flag and tries again.
            vm.keepOffline()
            assertEquals(2, regions.downloads)
        }
    }

    @Test
    fun noCoveringRegionMeansNoRow_andKeepOfflineIsANoOp() {
        val regions = FakeRegions(offer = null)
        vmTest(regions) { vm ->
            assertNull(loaded(vm).offlineOffer)
            vm.keepOffline()
            assertEquals(0, regions.downloads)
        }
    }
}
