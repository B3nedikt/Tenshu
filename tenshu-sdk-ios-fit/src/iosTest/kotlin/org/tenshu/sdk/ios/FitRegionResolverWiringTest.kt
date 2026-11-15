package org.tenshu.sdk.ios

import app.tenshu.fit.model.GeoCoord
import app.tenshu.fit.ui.screens.workout.WorkoutDetailUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import app.tenshu.fit.data.RegionBundleResolver as FitRegionBundleResolver
import app.tenshu.fit.data.RouteRegionStream as FitRouteRegionStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * C3 coverage — the offline-region **data path** on iOS, Fit's half: the surface actually hands the
 * resolver to the workout view model (asserted through the VM's own state, not by reading back the
 * field), and the mock and not-connected tiers carry none. Fit's surface used to hardcode
 * `regions = null`, which made `regionStream` permanently null on the platform.
 *
 * Timeline's half, with the route-1 drain through the iOS stager, is `TimelineRegionResolverWiringTest`.
 * ⚠ They were one test until `ios-per-app-frameworks.md` P4 put each app in its own framework
 * module; split, neither needs the other app's code.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FitRegionResolverWiringTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        // Leave the surface on its honest default, as the other surface tests do.
        FitSurface.installNotConnected()
    }

    @Test
    fun workoutDetailReceivesTheInstalledResolverAndRendersItsStream() = vmTest(dispatcher) { vms ->
        FitSurface.installMock()
        val resolver = RecordingFitResolver()
        FitSurface.installRegionResolver(resolver)

        val vm = vms.own(FitSurface.workoutViewModel("w-sun-run"))
        advanceUntilIdle()

        assertTrue(resolver.seenRoute.size >= 2, "the workout's GPS route reaches the resolver")
        val state = vm.uiState.value
        assertTrue(state is WorkoutDetailUiState.Loaded)
        assertEquals("it-firenze", state.regionStream?.regionId, "the resolved stream lands in the VM state")
    }

    @Test
    fun theMockAndNotConnectedTiersCarryNoResolver() = runTest(dispatcher) {
        // Regression guard: a preview/mock run must not reach for the region catalogue. Installing
        // either tier clears whatever a previous live tier left behind.
        FitSurface.installRegionResolver(RecordingFitResolver())
        FitSurface.installMock()
        assertNull(FitSurface.installedRegionResolver())

        FitSurface.installRegionResolver(RecordingFitResolver())
        FitSurface.installNotConnected()
        assertNull(FitSurface.installedRegionResolver())
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────

    private class RecordingFitResolver : FitRegionBundleResolver {
        var seenRoute: List<GeoCoord> = emptyList()

        override suspend fun bundlePath(route: List<GeoCoord>): String? {
            seenRoute = route
            return "/staged/it-firenze.pmtiles"
        }

        override suspend fun streamHandle(route: List<GeoCoord>): FitRouteRegionStream? {
            seenRoute = route
            return FitRouteRegionStream("it-firenze", totalSize = 4096) { _, _ -> ByteArray(0) }
        }
    }
}
