package app.tenshu.hub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.home.device.DeviceCommand
import org.tenshu.home.device.DeviceState
import org.tenshu.home.device.HomeDevice

/**
 * HD-D4: the simulated bridge's little state machines are real — commands
 * actually mutate the light, the sensor drift is bounded, and the read-only
 * sensor rejects actuation — so the system-test round trip is honest.
 */
class SimulatedDeviceBridgeTest {

    @Test
    fun inventoryCarriesBothDevices_withBridgeScopedIds() = runTest {
        val bridge = SimulatedDeviceBridge()
        val devices = bridge.devices().first()
        assertEquals(
            listOf(SimulatedDeviceBridge.LIGHT_ID, SimulatedDeviceBridge.SENSOR_ID),
            devices.map { it.deviceId },
        )
        assertTrue(devices.all { it.deviceId.startsWith("${HomeDevice.BRIDGE_SIMULATED}:") })
        assertEquals(HomeDevice.KIND_LIGHT, devices[0].kind)
        assertEquals(HomeDevice.KIND_SENSOR, devices[1].kind)
    }

    @Test
    fun toggleAndSetPower_mutateTheLight() = runTest {
        val bridge = SimulatedDeviceBridge()
        assertEquals(false, bridge.currentLight().on)

        val toggled = bridge.execute(SimulatedDeviceBridge.LIGHT_ID, DeviceCommand.VERB_TOGGLE, "")
        assertEquals(DeviceState.OUTCOME_OK, toggled.outcome)
        assertEquals(true, bridge.currentLight().on)
        assertEquals(true, assertNotNull(toggled.newState).powerOn)

        val off = bridge.execute(
            SimulatedDeviceBridge.LIGHT_ID,
            DeviceCommand.VERB_SET_POWER,
            DeviceCommand.ARG_OFF,
        )
        assertEquals(DeviceState.OUTCOME_OK, off.outcome)
        assertEquals(false, bridge.currentLight().on)
        assertEquals(2, bridge.executedCommandCount)
    }

    @Test
    fun setLevel_clampsAndImpliesPower() = runTest {
        val bridge = SimulatedDeviceBridge()
        bridge.execute(SimulatedDeviceBridge.LIGHT_ID, DeviceCommand.VERB_SET_LEVEL, "65")
        assertEquals(65, bridge.currentLight().levelPercent)
        assertEquals(true, bridge.currentLight().on, "a level > 0 turns the light on")

        bridge.execute(SimulatedDeviceBridge.LIGHT_ID, DeviceCommand.VERB_SET_LEVEL, "150")
        assertEquals(100, bridge.currentLight().levelPercent)

        bridge.execute(SimulatedDeviceBridge.LIGHT_ID, DeviceCommand.VERB_SET_LEVEL, "0")
        assertEquals(false, bridge.currentLight().on, "level 0 turns it off")
    }

    @Test
    fun badVerbsAndReadOnlyDevices_reportWithoutMutating() = runTest {
        val bridge = SimulatedDeviceBridge()
        val before = bridge.currentLight()

        assertEquals(
            DeviceState.OUTCOME_UNSUPPORTED,
            bridge.execute(SimulatedDeviceBridge.LIGHT_ID, "discoMode", "").outcome,
        )
        assertEquals(
            DeviceState.OUTCOME_UNSUPPORTED,
            bridge.execute(SimulatedDeviceBridge.SENSOR_ID, DeviceCommand.VERB_TOGGLE, "").outcome,
            "the sensor is read-only",
        )
        assertEquals(
            DeviceState.OUTCOME_FAILED,
            bridge.execute(SimulatedDeviceBridge.LIGHT_ID, DeviceCommand.VERB_SET_LEVEL, "banana").outcome,
        )
        assertEquals(before, bridge.currentLight())
        assertEquals(0, bridge.executedCommandCount)
    }

    @Test
    fun sensorDrift_isBounded_andReflectedInTheStatesFlow() = runTest {
        val bridge = SimulatedDeviceBridge()
        val start = bridge.currentSensor()

        bridge.driftSensorNow(deltaDeciC = 3)
        assertEquals(start.temperatureDeciC + 3, bridge.currentSensor().temperatureDeciC)

        // The walk is clamped to a sane indoor band.
        repeat(1_000) { bridge.driftSensorNow(deltaDeciC = 5) }
        assertEquals(350, bridge.currentSensor().temperatureDeciC)

        val states = bridge.states().first()
        val sensor = states.first { it.deviceId == SimulatedDeviceBridge.SENSOR_ID }
        assertEquals(350, sensor.temperatureDeciC)
    }

    @Test
    fun permitJoin_recordsTheGrantedWindowAndClampsIt() = runTest {
        val bridge = SimulatedDeviceBridge()
        assertEquals(0, bridge.permitJoinSeconds)
        bridge.setPermitJoin(60)
        assertEquals(60, bridge.permitJoinSeconds)
        // The ceiling holds even against a bridge with no radio to protect
        // (HD-D7: the bound is the contract, not an implementation detail).
        bridge.setPermitJoin(99_999)
        assertEquals(DeviceCommand.PERMIT_JOIN_MAX_SECONDS, bridge.permitJoinSeconds)
        bridge.setPermitJoin(0)
        assertEquals(0, bridge.permitJoinSeconds)
    }

    @Test
    fun pairableModeAnnouncesABridgeRowAndJoinsADeviceIntoAnOpenWindow() = runTest {
        // The desktop stand-in for holding a bulb's reset button (HD-2 demo
        // mode): Home's Add-device flow is clickable with no radio present.
        val bridge = SimulatedDeviceBridge(pairable = true)
        val self = bridge.devices().first().first()
        assertEquals(HomeDevice.BRIDGE_SIMULATED, self.deviceId)
        assertEquals(HomeDevice.KIND_BRIDGE, self.kind)
        assertEquals(listOf(HomeDevice.CAP_PERMIT_JOIN), self.capabilityIds)

        val before = bridge.devices().first().size
        bridge.joinSimulatedDevice()
        val after = bridge.devices().first()
        assertEquals(before + 1, after.size)
        val joined = after.last()
        assertEquals(HomeDevice.KIND_LIGHT, joined.kind)
        // …and it is a real little device: commands move it.
        assertEquals(
            DeviceState.OUTCOME_OK,
            bridge.execute(joined.deviceId, DeviceCommand.VERB_SET_LEVEL, "30").outcome,
        )
        assertEquals(
            30,
            bridge.states().first().first { it.deviceId == joined.deviceId }.levelPercent,
        )
    }

    @Test
    fun namesRenameOnlyTheNamedDevices_idsKindsAndCapabilitiesStay() = runTest {
        val plain = SimulatedDeviceBridge(pairable = true).devices().first()
        val named = SimulatedDeviceBridge(
            pairable = true,
            names = mapOf("light" to "Living room lamp", "bridge" to "Zigbee bridge"),
        ).devices().first()
        assertEquals(
            listOf("Zigbee bridge", "Living room lamp", "Simulated climate sensor"),
            named.map { it.defaultName },
        )
        assertEquals(listOf("Simulated bridge", "Simulated light", "Simulated climate sensor"), plain.map { it.defaultName })
        assertEquals(plain.map { it.copy(defaultName = "") }, named.map { it.copy(defaultName = "") })
    }
}
