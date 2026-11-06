package app.tenshu.home.ui.screens

import app.tenshu.home.data.MockHomeDevicesRepository
import app.tenshu.home.model.DeviceKind
import app.tenshu.home.ui.screens.devices.DevicesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelsTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun devices_seedFlowsIntoStateWithRooms() = vmTest { vms ->
        val vm = vms.own(DevicesViewModel(MockHomeDevicesRepository()))
        val state = vm.uiState.value
        assertTrue(state.loaded)
        assertTrue(state.devices.isNotEmpty())
        assertTrue(state.rooms.isNotEmpty())
        assertEquals("Living room", vm.roomName(state.devices.first()))
    }

    // ⚠⚠ `devices_addRemoveAndReorderRoundTrip` and `devices_blankNameIsRejected` were deleted
    // with the affordances they covered (2026-08-26). Both exercised writes that only ever mutated
    // an in-memory mock household — a round trip through an object that persisted nothing and was,
    // on screen, indistinguishable from real devices. Adding a real device is HD-2's permit-join
    // flow, which `DevicesViewModel.startPairing` covers.
}
