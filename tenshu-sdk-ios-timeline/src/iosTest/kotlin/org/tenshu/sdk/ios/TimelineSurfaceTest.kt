package org.tenshu.sdk.ios

import app.tenshu.timeline.ui.screens.moment.MomentDetailUiState
import app.tenshu.timeline.ui.screens.place.PlaceDetailUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Reactive coverage of [TimelineSurface] over the explicitly-installed
 * [app.tenshu.timeline.data.MockTimelineRepository] — the coverage the in-app init-time smokes can't
 * reach (Main is blocked there). Proves the SwiftUI app's VM factories build and drive real mock
 * data on the `iosSimulatorArm64Test` gate.
 *
 * ⚠ The mock is a **test/preview** surface, not the runtime default — the runtime default is the
 * honest [NotConnectedTimelineRepository] — so every test here installs it first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimelineSurfaceTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun home_vm_loads_days_and_a_moment_and_place_resolve() = runTest(dispatcher) {
        // Fresh mock so the assertions don't depend on other tests' installs.
        TimelineSurface.installMock()

        val home = TimelineSurface.homeViewModel()
        advanceUntilIdle()
        val state = home.uiState.value
        assertTrue(state.days.isNotEmpty(), "Home feed should surface mock days")

        // A moment from the loaded feed resolves through the detail factory.
        val momentId = state.days.firstNotNullOf { it.moments.firstOrNull() }.id
        val moment = TimelineSurface.momentViewModel(momentId)
        advanceUntilIdle()
        assertTrue(moment.uiState.value is MomentDetailUiState.Loaded, "moment $momentId should load")

        // Places tab data is loaded onto the same Home VM; a place resolves via its factory.
        assertTrue(state.places.isNotEmpty(), "Places should surface mock places")
        val place = TimelineSurface.placeViewModel(state.places.first().id)
        advanceUntilIdle()
        assertTrue(place.uiState.value is PlaceDetailUiState.Loaded, "place should load")
    }
}
