package app.tenshu.home.ui.screens.devices

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tenshu.home.data.DeviceControlSource
import app.tenshu.home.data.DevicePairingSource
import app.tenshu.home.data.DeviceSource
import app.tenshu.home.data.MockHomeDevicesRepository
import app.tenshu.home.data.SyncedDeviceAwareHomeDevicesRepository
import app.tenshu.home.data.SyncedDeviceInfo
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.tenshu.home.device.DeviceState
import org.tenshu.home.device.HomeDevice
import app.tenshu.home.composeVmTest

/**
 * Compose-render coverage for the Home tab (mirrors Fit/Timeline's screen tests): the
 * seeded household flows through [DevicesViewModel] into per-kind rows — including the
 * storage row's free/total line — and the row menu removes a device from the rendered list.
 */
@OptIn(ExperimentalTestApi::class)
class DevicesScreenTest {

    private class FakeDeviceSource(initial: List<SyncedDeviceInfo>) : DeviceSource {
        val flow = MutableStateFlow(initial)
        override fun devices(): Flow<List<SyncedDeviceInfo>> = flow
    }

    private class RecordingPairingSource : DevicePairingSource {
        val requests = mutableListOf<Pair<String, Int>>()
        override suspend fun openPairingWindow(bridgeId: String, seconds: Int): Boolean {
            requests += bridgeId to seconds
            return true
        }
    }

    private class RecordingControlSource : DeviceControlSource {
        val powerCalls = mutableListOf<Pair<String, Boolean>>()
        val commandIds = mutableListOf<String>()

        override suspend fun setPower(deviceId: String, on: Boolean, commandId: String): Boolean {
            powerCalls += deviceId to on
            commandIds += commandId
            return true
        }

        override suspend fun setLevel(deviceId: String, percent: Int, commandId: String): Boolean {
            commandIds += commandId
            return true
        }
    }

    @Test
    fun seededDevicesRenderWithPerKindStateLines() = composeVmTest { vms ->
        val vm = vms.own(DevicesViewModel(MockHomeDevicesRepository()))
        setContent {
            TenshuTheme {
                DevicesScreen(viewModel = vm, onAskMia = {})
            }
        }

        waitUntil {
            onAllNodesWithText("Tenshu Hub", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // The storage row carries the free-of-total line (the "Connected Storage" tile).
        onNodeWithText("Hub storage", useUnmergedTree = true).assertIsDisplayed()
        onAllNodesWithText("588 GB free of 1 TB", substring = true, useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
        // The offline camera renders its disconnected state.
        onNodeWithText("Front door camera", useUnmergedTree = true).assertIsDisplayed()
    }


    /**
     * HD-2: with a bridge present, the Add FAB opens the *pairing* flow —
     * permit-join, a countdown, and the devices that actually joined —
     * rather than the mock household's add-a-device form.
     */
    @Test
    fun addDeviceOpensThePairingFlowWhenABridgeIsPresent() = composeVmTest { vms ->
        val source = FakeDeviceSource(
            listOf(
                SyncedDeviceInfo(
                    deviceId = HomeDevice.BRIDGE_ZIGBEE2MQTT,
                    kind = HomeDevice.KIND_BRIDGE,
                    bridgeId = HomeDevice.BRIDGE_ZIGBEE2MQTT,
                    capabilities = listOf(HomeDevice.CAP_PERMIT_JOIN),
                    name = "Zigbee bridge",
                    room = "",
                    stateFresh = true,
                    online = true,
                ),
            ),
        )
        val pairing = RecordingPairingSource()
        val vm = vms.own(DevicesViewModel(MockHomeDevicesRepository(), source, pairing))
        setContent {
            TenshuTheme {
                DevicesScreen(viewModel = vm, onAskMia = {})
            }
        }

        waitUntil {
            onAllNodesWithText("Add device", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Add device", useUnmergedTree = true).performClick()

        // The pairing dialog, not the mock form (which would show "Name").
        waitUntil {
            onAllNodesWithText("Open pairing window", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Pairing through Zigbee bridge", useUnmergedTree = true).assertIsDisplayed()

        onNodeWithText("Open pairing window", useUnmergedTree = true).performClick()
        waitUntil {
            onAllNodesWithText("Pairing open", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // A device joins through ordinary sync while the window is open.
        source.flow.value = source.flow.value + SyncedDeviceInfo(
            deviceId = "zigbee2mqtt:0x000b57fffec6a5b2",
            kind = HomeDevice.KIND_LIGHT,
            bridgeId = HomeDevice.BRIDGE_ZIGBEE2MQTT,
            capabilities = listOf(HomeDevice.CAP_POWER),
            name = "Living room lamp",
            room = "",
            stateFresh = true,
            online = true,
        )
        waitUntil {
            onAllNodesWithText("Living room lamp", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(HomeDevice.BRIDGE_ZIGBEE2MQTT, pairing.requests.first().first)
    }

    /**
     * Actuation: a real, switchable device renders a power control that issues an
     * absolute command; a mock row renders none (there is nothing to switch).
     */
    @Test
    fun aRealDeviceRendersAPowerSwitchThatIssuesACommand() = composeVmTest { vms ->
        val source = FakeDeviceSource(
            listOf(
                SyncedDeviceInfo(
                    deviceId = LAMP_ID,
                    kind = HomeDevice.KIND_LIGHT,
                    bridgeId = HomeDevice.BRIDGE_ZIGBEE2MQTT,
                    capabilities = listOf(HomeDevice.CAP_POWER),
                    name = "Living room lamp",
                    room = "",
                    stateFresh = true,
                    online = true,
                    powerOn = false,
                ),
            ),
        )
        val control = RecordingControlSource()
        val vm = vms.own(DevicesViewModel(
            // The production decorator: real devices replace the mock household
            // wholesale, which is what puts a switchable row on screen at all.
            repository = SyncedDeviceAwareHomeDevicesRepository(
                delegate = MockHomeDevicesRepository(),
                source = source,
            ),
            deviceSource = source,
            controlSource = control,
        ))
        setContent {
            TenshuTheme {
                DevicesScreen(viewModel = vm, onAskMia = {})
            }
        }

        waitUntil {
            onAllNodesWithContentDescription("Power", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // One control for the one real device — the mock household is replaced
        // wholesale, and the infrastructure tiles that remain are not switchable.
        assertEquals(
            1,
            onAllNodesWithContentDescription("Power", useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )

        onNodeWithContentDescription("Power", useUnmergedTree = true).performClick()

        waitUntil { control.powerCalls.isNotEmpty() }
        assertEquals(LAMP_ID to true, control.powerCalls.single())

        // The command is out; the hub has not answered, so the row says so.
        waitUntil {
            vm.uiState.value.controls[LAMP_ID]?.status == DeviceCommandStatus.Pending
        }

        // The hub reports a failure on the device's own state row.
        source.flow.value = source.flow.value.map {
            it.copy(
                lastCommandId = control.commandIds.single(),
                lastCommandOutcome = DeviceState.OUTCOME_FAILED,
            )
        }
        waitUntil {
            onAllNodesWithText("That didn't work", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Dismiss", useUnmergedTree = true).performClick()
        waitUntil {
            onAllNodesWithText("That didn't work", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun aMockOnlyHouseholdRendersNoControls() = composeVmTest { vms ->
        val vm = vms.own(DevicesViewModel(MockHomeDevicesRepository()))
        setContent {
            TenshuTheme {
                DevicesScreen(viewModel = vm, onAskMia = {})
            }
        }
        waitUntil {
            onAllNodesWithText("Tenshu Hub", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(
            0,
            onAllNodesWithContentDescription("Power", useUnmergedTree = true)
                .fetchSemanticsNodes().size,
            "the mock household is a fallback, not a switchable house",
        )
    }

    @Test
    fun askMiaEntryNavigates() = composeVmTest { vms ->
        val vm = vms.own(DevicesViewModel(MockHomeDevicesRepository()))
        var asked = false
        setContent {
            TenshuTheme {
                DevicesScreen(viewModel = vm, onAskMia = { asked = true })
            }
        }
        waitUntil {
            onAllNodesWithText("Ask Mia", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Ask Mia", useUnmergedTree = true).performClick()
        waitUntil { asked }
    }

    private companion object {
        const val LAMP_ID = "zigbee2mqtt:0x000b57fffec6a5b2"
    }
}
