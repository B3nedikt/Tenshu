package app.tenshu.home.ui.screens.devices

import app.tenshu.home.data.DeviceControlSource
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
import org.tenshu.home.device.DeviceState
import org.tenshu.home.device.HomeDevice
import org.tenshu.viewmodel.test.vmTest
import org.tenshu.viewmodel.test.OwnedViewModels

/**
 * Actuation at the view-model level (the HD-3 follow-up): a control exists only
 * for a real device that advertises the capability, a command is *absolute*, and
 * the outcome that resolves it is the command id the hub echoes on the state row
 * — never value equality, which cannot tell our command from the wall switch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceControlViewModelTest {

    private class FakeDeviceSource : DeviceSource {
        val flow = MutableStateFlow<List<SyncedDeviceInfo>>(emptyList())
        override fun devices(): Flow<List<SyncedDeviceInfo>> = flow
    }

    private class FakeControlSource(private val succeeds: Boolean = true) : DeviceControlSource {
        val powerCalls = mutableListOf<Triple<String, Boolean, String>>()
        val levelCalls = mutableListOf<Triple<String, Int, String>>()

        override suspend fun setPower(deviceId: String, on: Boolean, commandId: String): Boolean {
            powerCalls += Triple(deviceId, on, commandId)
            return succeeds
        }

        override suspend fun setLevel(deviceId: String, percent: Int, commandId: String): Boolean {
            levelCalls += Triple(deviceId, percent, commandId)
            return succeeds
        }
    }

    private fun lamp(
        id: String = LAMP_ID,
        capabilities: List<String> = listOf(HomeDevice.CAP_POWER, HomeDevice.CAP_LEVEL),
        powerOn: Boolean? = false,
        levelPercent: Int? = 40,
        lastCommandId: String = "",
        lastCommandOutcome: String = "",
    ) = SyncedDeviceInfo(
        deviceId = id,
        kind = HomeDevice.KIND_LIGHT,
        bridgeId = HomeDevice.BRIDGE_ZIGBEE2MQTT,
        capabilities = capabilities,
        name = "Desk lamp",
        room = "Study",
        stateFresh = true,
        online = true,
        powerOn = powerOn,
        levelPercent = levelPercent,
        lastCommandId = lastCommandId,
        lastCommandOutcome = lastCommandOutcome,
    )

    private fun vm(
        vms: OwnedViewModels,
        source: FakeDeviceSource,
        control: FakeControlSource?,
        ids: List<String> = List(8) { "cmd-$it" },
    ): DevicesViewModel {
        val queue = ids.toMutableList()
        return vms.own(DevicesViewModel(
            repository = MockHomeDevicesRepository(),
            deviceSource = source,
            pairingSource = null,
            controlSource = control,
            newCommandId = { queue.removeFirst() },
        ))
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun controlsExistOnlyForRealDevicesThatCanActuate() = vmTest { vms ->
        // A mock-only Home can look but not touch: no control source, no controls.
        val mockOnly = vms.own(DevicesViewModel(MockHomeDevicesRepository()))
        assertTrue(mockOnly.uiState.value.controls.isEmpty())

        val source = FakeDeviceSource()
        val model = vm(vms, source, FakeControlSource())
        source.flow.value = listOf(
            lamp(),
            // A read-only device advertises no actuable capability.
            lamp(id = "zigbee2mqtt:0xsensor", capabilities = listOf(HomeDevice.CAP_TEMPERATURE)),
            // Nor does a bridge row (it only opens pairing windows).
            lamp(id = "zigbee2mqtt", capabilities = listOf(HomeDevice.CAP_PERMIT_JOIN)),
        )

        val controls = model.uiState.value.controls
        assertEquals(setOf(LAMP_ID), controls.keys)
        assertTrue(controls.getValue(LAMP_ID).canSetPower)
        assertTrue(controls.getValue(LAMP_ID).canSetLevel)
        assertEquals(false, controls.getValue(LAMP_ID).on)
        assertEquals(40, controls.getValue(LAMP_ID).levelPercent)
    }

    @Test
    fun switchingOnIssuesAnAbsoluteCommandAndRendersOptimistically() = vmTest { vms ->
        val source = FakeDeviceSource()
        val control = FakeControlSource()
        val model = vm(vms, source, control)
        source.flow.value = listOf(lamp())

        model.setDevicePower(LAMP_ID, on = true)

        // Absolute: the switch's desired state travels, not a relative flip.
        assertEquals(Triple(LAMP_ID, true, "cmd-0"), control.powerCalls.single())

        // Until the hub answers, the control shows what was asked for — a switch
        // that snapped back for 30 s would read as "the app is broken".
        val pending = model.uiState.value.controls.getValue(LAMP_ID)
        assertEquals(true, pending.on)
        assertEquals(DeviceCommandStatus.Pending, pending.status)
        assertTrue(pending.busy)

        // The hub consumed it and says so on the device's own state row.
        source.flow.value = listOf(
            lamp(powerOn = true, lastCommandId = "cmd-0", lastCommandOutcome = DeviceState.OUTCOME_OK),
        )

        val settled = model.uiState.value.controls.getValue(LAMP_ID)
        assertEquals(DeviceCommandStatus.Idle, settled.status)
        assertFalse(settled.busy)
        assertEquals(true, settled.on)
    }

    @Test
    fun anotherDevicesOutcomeNeverResolvesOurCommand() = vmTest { vms ->
        val source = FakeDeviceSource()
        val model = vm(vms, source, FakeControlSource())
        source.flow.value = listOf(lamp())

        model.setDevicePower(LAMP_ID, on = true)

        // Someone flips the wall switch: the state row moves to exactly the value
        // we asked for, but carries a different command id. Value equality would
        // call this "confirmed"; the correlation must not.
        source.flow.value = listOf(
            lamp(powerOn = true, lastCommandId = "someone-else", lastCommandOutcome = DeviceState.OUTCOME_OK),
        )

        assertEquals(
            DeviceCommandStatus.Pending,
            model.uiState.value.controls.getValue(LAMP_ID).status,
        )
    }

    @Test
    fun aHubReportedFailureIsSurfacedAndDismissible() = vmTest { vms ->
        val source = FakeDeviceSource()
        val model = vm(vms, source, FakeControlSource())
        source.flow.value = listOf(lamp())

        model.setDevicePower(LAMP_ID, on = true)
        source.flow.value = listOf(
            lamp(lastCommandId = "cmd-0", lastCommandOutcome = DeviceState.OUTCOME_FAILED),
        )

        val failed = model.uiState.value.controls.getValue(LAMP_ID)
        assertEquals(DeviceCommandStatus.Failed, failed.status)
        assertTrue(failed.hasNote)
        // The note survives further inventory emissions (they arrive constantly).
        source.flow.value = listOf(lamp(lastCommandId = "cmd-0", lastCommandOutcome = DeviceState.OUTCOME_FAILED))
        assertEquals(DeviceCommandStatus.Failed, model.uiState.value.controls.getValue(LAMP_ID).status)

        model.dismissDeviceCommandNote(LAMP_ID)
        assertEquals(DeviceCommandStatus.Idle, model.uiState.value.controls.getValue(LAMP_ID).status)
    }

    @Test
    fun anUnissuableCommandFailsImmediatelyWithoutWaiting() = vmTest { vms ->
        val source = FakeDeviceSource()
        val model = vm(vms, source, FakeControlSource(succeeds = false))
        source.flow.value = listOf(lamp())

        model.setDevicePower(LAMP_ID, on = true)

        val state = model.uiState.value.controls.getValue(LAMP_ID)
        assertEquals(DeviceCommandStatus.Failed, state.status)
        assertFalse(state.busy, "an unreachable proxy must not leave a spinner running")
        // …and the optimistic value is dropped: the lamp never moved.
        assertEquals(false, state.on)
    }

    @Test
    fun silenceBecomesUnconfirmedNotFailed() = vmTest { vms ->
        val source = FakeDeviceSource()
        val model = vm(vms, source, FakeControlSource())
        source.flow.value = listOf(lamp())

        model.setDevicePower(LAMP_ID, on = true)
        assertEquals(DeviceCommandStatus.Pending, model.uiState.value.controls.getValue(LAMP_ID).status)

        advanceTimeBy(DevicesViewModel.COMMAND_OUTCOME_TIMEOUT_MS + 1_000)

        // We stopped waiting; we did not learn that it failed. The command row is
        // durable and the hub may still act within its own staleness window.
        val state = model.uiState.value.controls.getValue(LAMP_ID)
        assertEquals(DeviceCommandStatus.Unconfirmed, state.status)
        assertTrue(state.hasNote)
        assertFalse(state.busy)
    }

    @Test
    fun aLateOutcomeStillResolvesTheRowAfterGivingUpWaiting() = vmTest { vms ->
        val source = FakeDeviceSource()
        val model = vm(vms, source, FakeControlSource())
        source.flow.value = listOf(lamp())

        model.setDevicePower(LAMP_ID, on = true)
        advanceTimeBy(DevicesViewModel.COMMAND_OUTCOME_TIMEOUT_MS + 1_000)
        assertEquals(DeviceCommandStatus.Unconfirmed, model.uiState.value.controls.getValue(LAMP_ID).status)

        // A slow hub finally reports. The note is not sticky nonsense: the next
        // action clears it, and so does a fresh command.
        model.setDevicePower(LAMP_ID, on = true)
        assertEquals(DeviceCommandStatus.Pending, model.uiState.value.controls.getValue(LAMP_ID).status)
        source.flow.value = listOf(
            lamp(powerOn = true, lastCommandId = "cmd-1", lastCommandOutcome = DeviceState.OUTCOME_OK),
        )
        assertEquals(DeviceCommandStatus.Idle, model.uiState.value.controls.getValue(LAMP_ID).status)
    }

    @Test
    fun aNewerCommandSupersedesTheOneItReplaces() = vmTest { vms ->
        val source = FakeDeviceSource()
        val control = FakeControlSource()
        val model = vm(vms, source, control)
        source.flow.value = listOf(lamp())

        model.setDeviceLevel(LAMP_ID, 30)
        model.setDeviceLevel(LAMP_ID, 70)
        assertEquals(listOf(30, 70), control.levelCalls.map { it.second })

        // The superseded command's outcome is no longer the one we wait for…
        source.flow.value = listOf(
            lamp(lastCommandId = "cmd-0", lastCommandOutcome = DeviceState.OUTCOME_OK),
        )
        assertEquals(DeviceCommandStatus.Pending, model.uiState.value.controls.getValue(LAMP_ID).status)
        assertEquals(70, model.uiState.value.controls.getValue(LAMP_ID).levelPercent)

        // …the newest one's is.
        source.flow.value = listOf(
            lamp(levelPercent = 70, lastCommandId = "cmd-1", lastCommandOutcome = DeviceState.OUTCOME_OK),
        )
        assertEquals(DeviceCommandStatus.Idle, model.uiState.value.controls.getValue(LAMP_ID).status)
    }

    @Test
    fun levelIsClampedAndImpliesPowerOn() = vmTest { vms ->
        val source = FakeDeviceSource()
        val control = FakeControlSource()
        val model = vm(vms, source, control)
        source.flow.value = listOf(lamp())

        model.setDeviceLevel(LAMP_ID, 400)
        assertEquals(100, control.levelCalls.single().second)
        // A level above zero implies on — render it that way rather than showing
        // "off at 100%" until the state row lands.
        assertEquals(true, model.uiState.value.controls.getValue(LAMP_ID).on)
    }

    @Test
    fun commandsAreNeverIssuedForRowsThatCannotTakeThem() = vmTest { vms ->
        val source = FakeDeviceSource()
        val control = FakeControlSource()
        val model = vm(vms, source, control)
        source.flow.value = listOf(
            lamp(capabilities = listOf(HomeDevice.CAP_POWER)), // no level capability
        )

        // A mock row is not a synced device at all.
        model.setDevicePower("mock-device-1", on = true)
        // …and a real device without the capability is not asked for it.
        model.setDeviceLevel(LAMP_ID, 50)

        assertTrue(control.powerCalls.isEmpty())
        assertTrue(control.levelCalls.isEmpty())
        assertNull(model.uiState.value.controls["mock-device-1"])
        assertFalse(model.uiState.value.controls.getValue(LAMP_ID).canSetLevel)
    }

    private companion object {
        const val LAMP_ID = "zigbee2mqtt:0x00124b0022"
    }
}
