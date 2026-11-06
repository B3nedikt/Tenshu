package app.tenshu.home.ui.screens.devices

import app.tenshu.home.data.DevicePairingSource
import app.tenshu.home.data.DeviceSource
import app.tenshu.home.data.MockHomeDevicesRepository
import app.tenshu.home.data.SyncedDeviceInfo
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.tenshu.home.device.DeviceCommand
import org.tenshu.home.device.HomeDevice
import org.tenshu.viewmodel.test.vmTest

/**
 * HD-2's Add-device flow at the view-model level: a pairing window is offered
 * only when a bridge advertising `CAP_PERMIT_JOIN` exists, it is explicit and
 * time-bounded, and the "a device joined" signal is the ordinary synced
 * inventory (no second reporting channel to keep in step).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DevicePairingViewModelTest {

    private class FakeDeviceSource : DeviceSource {
        val flow = MutableStateFlow<List<SyncedDeviceInfo>>(emptyList())
        override fun devices(): Flow<List<SyncedDeviceInfo>> = flow
    }

    private class FakePairingSource(private val succeeds: Boolean = true) : DevicePairingSource {
        val requests = mutableListOf<Pair<String, Int>>()
        override suspend fun openPairingWindow(bridgeId: String, seconds: Int): Boolean {
            requests += bridgeId to seconds
            return succeeds
        }
    }

    private val bridge = SyncedDeviceInfo(
        deviceId = HomeDevice.BRIDGE_ZIGBEE2MQTT,
        kind = HomeDevice.KIND_BRIDGE,
        bridgeId = HomeDevice.BRIDGE_ZIGBEE2MQTT,
        capabilities = listOf(HomeDevice.CAP_PERMIT_JOIN),
        name = "Zigbee bridge",
        room = "",
        stateFresh = true,
        online = true,
    )

    private fun lamp(id: String, name: String) = SyncedDeviceInfo(
        deviceId = id,
        kind = HomeDevice.KIND_LIGHT,
        bridgeId = HomeDevice.BRIDGE_ZIGBEE2MQTT,
        capabilities = listOf(HomeDevice.CAP_POWER),
        name = name,
        room = "",
        stateFresh = true,
        online = true,
    )

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun withoutABridgeThereIsNoPairingFlowAtAll() = vmTest { vms ->
        // A mock-only Home (no synced seams) must not offer to pair anything.
        val mockOnly = vms.own(DevicesViewModel(MockHomeDevicesRepository()))
        assertFalse(mockOnly.uiState.value.pairing.available)
        assertNull(mockOnly.uiState.value.pairing.bridgeId)

        // …nor does a realm whose devices came from a bridge-less source.
        val source = FakeDeviceSource()
        val pairing = FakePairingSource()
        val vm = vms.own(DevicesViewModel(MockHomeDevicesRepository(), source, pairing))
        source.flow.value = listOf(lamp("simulated:light-1", "Simulated light"))
        assertFalse(vm.uiState.value.pairing.available)

        vm.startPairing()
        assertTrue(pairing.requests.isEmpty(), "no bridge means no command is ever issued")
    }

    @Test
    fun aBridgeAdvertisingPermitJoinMakesPairingAvailable() = vmTest { vms ->
        val source = FakeDeviceSource()
        val vm = vms.own(DevicesViewModel(MockHomeDevicesRepository(), source, FakePairingSource()))

        source.flow.value = listOf(bridge)

        val pairing = vm.uiState.value.pairing
        assertTrue(pairing.available)
        assertEquals(HomeDevice.BRIDGE_ZIGBEE2MQTT, pairing.bridgeId)
        assertEquals("Zigbee bridge", pairing.bridgeName)
        assertFalse(pairing.open, "discovering a bridge must not open its radio")
    }

    @Test
    fun startingPairingOpensABoundedWindowThatClosesItself() = vmTest { vms ->
        val source = FakeDeviceSource()
        val pairing = FakePairingSource()
        val vm = vms.own(DevicesViewModel(MockHomeDevicesRepository(), source, pairing))
        source.flow.value = listOf(bridge)

        vm.startPairing(seconds = 3)
        assertEquals(listOf(HomeDevice.BRIDGE_ZIGBEE2MQTT to 3), pairing.requests)
        assertTrue(vm.uiState.value.pairing.open)
        assertEquals(3, vm.uiState.value.pairing.secondsRemaining)

        advanceTimeBy(1_100)
        assertEquals(2, vm.uiState.value.pairing.secondsRemaining)

        // Asking again while it runs does not re-issue (or reset the clock).
        vm.startPairing(seconds = 3)
        assertEquals(1, pairing.requests.size)

        advanceTimeBy(2_000)
        assertFalse(vm.uiState.value.pairing.open, "the window closes on its own")
    }

    @Test
    fun aWindowIsClampedToTheServicesCeiling() = vmTest { vms ->
        val source = FakeDeviceSource()
        val pairing = FakePairingSource()
        val vm = vms.own(DevicesViewModel(MockHomeDevicesRepository(), source, pairing))
        source.flow.value = listOf(bridge)

        vm.startPairing(seconds = 99_999)
        assertEquals(
            DeviceCommand.PERMIT_JOIN_MAX_SECONDS,
            pairing.requests.single().second,
            "the UI never asks for more than the service's ceiling (the hub clamps too)",
        )
    }

    @Test
    fun devicesThatJoinDuringTheWindowAreNamed() = vmTest { vms ->
        val source = FakeDeviceSource()
        val vm = vms.own(DevicesViewModel(MockHomeDevicesRepository(), source, FakePairingSource()))
        source.flow.value = listOf(bridge, lamp("zigbee2mqtt:0xaaa", "Old lamp"))

        vm.startPairing(seconds = 30)
        assertEquals(emptyList(), vm.uiState.value.pairing.joinedDeviceNames)

        // The hub publishes the new device; it arrives by ordinary sync.
        source.flow.value = source.flow.value + lamp("zigbee2mqtt:0xbbb", "Kitchen lamp")

        assertEquals(listOf("Kitchen lamp"), vm.uiState.value.pairing.joinedDeviceNames)

        // The bridge row itself is never reported as "a device that joined".
        assertFalse(vm.uiState.value.pairing.joinedDeviceNames.contains("Zigbee bridge"))
    }

    @Test
    fun closingEarlyTellsTheBridgeToShutTheWindow() = vmTest { vms ->
        val source = FakeDeviceSource()
        val pairing = FakePairingSource()
        val vm = vms.own(DevicesViewModel(MockHomeDevicesRepository(), source, pairing))
        source.flow.value = listOf(bridge)

        vm.startPairing(seconds = 60)
        vm.stopPairing()

        assertFalse(vm.uiState.value.pairing.open)
        assertEquals(0, vm.uiState.value.pairing.secondsRemaining)
        assertEquals(
            HomeDevice.BRIDGE_ZIGBEE2MQTT to 0,
            pairing.requests.last(),
            "closing is an explicit command, not just a UI state change",
        )

        // …but dismissing without ever opening a window writes nothing.
        val issued = pairing.requests.size
        vm.stopPairing()
        assertEquals(issued, pairing.requests.size)
    }

    @Test
    fun aRefusedCommandSurfacesAsAFailureNotAnOpenWindow() = vmTest { vms ->
        val source = FakeDeviceSource()
        val vm = vms.own(DevicesViewModel(
            MockHomeDevicesRepository(),
            source,
            FakePairingSource(succeeds = false),
        ))
        source.flow.value = listOf(bridge)

        vm.startPairing(seconds = 30)

        assertFalse(vm.uiState.value.pairing.open, "an unreachable hub must not look like an open radio")
        assertTrue(vm.uiState.value.pairing.failed)

        vm.dismissPairingFailure()
        assertFalse(vm.uiState.value.pairing.failed)
    }
}
