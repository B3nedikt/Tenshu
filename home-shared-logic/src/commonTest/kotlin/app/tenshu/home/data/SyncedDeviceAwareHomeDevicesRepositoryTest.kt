package app.tenshu.home.data

import app.tenshu.home.model.DeviceKind
import app.tenshu.home.model.DeviceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.home.device.HomeDevice

/**
 * HD-D5: the Devices tab's real-source mapping — `SyncedDeviceInfo` →
 * `Device` rows — and the two-sided fallback contract: no real devices →
 * the delegate (mock) passes through UNTOUCHED; real devices → they replace
 * the mock household wholesale (infrastructure tiles kept), never mixed.
 */
class SyncedDeviceAwareHomeDevicesRepositoryTest {

    private class FakeDeviceSource : DeviceSource {
        val flow = MutableStateFlow<List<SyncedDeviceInfo>>(emptyList())
        override fun devices(): Flow<List<SyncedDeviceInfo>> = flow
    }

    private fun light(
        deviceId: String = "simulated:light-1",
        name: String = "Simulated light",
        room: String = "",
        on: Boolean = true,
        level: Int = 65,
    ) = SyncedDeviceInfo(
        deviceId = deviceId,
        kind = HomeDevice.KIND_LIGHT,
        name = name,
        room = room,
        stateFresh = true,
        online = true,
        powerOn = on,
        levelPercent = level,
    )

    private fun sensor(
        deviceId: String = "simulated:sensor-1",
        temperatureDeciC: Int? = 215,
        humidityPercent: Int? = 45,
    ) = SyncedDeviceInfo(
        deviceId = deviceId,
        kind = HomeDevice.KIND_SENSOR,
        name = "Climate sensor",
        room = "Office",
        stateFresh = true,
        online = true,
        temperatureDeciC = temperatureDeciC,
        humidityPercent = humidityPercent,
        batteryPercent = 93,
    )

    @Test
    fun emptySource_passesTheMockThroughUntouched() = runTest {
        val source = FakeDeviceSource()
        val delegate = MockHomeDevicesRepository()
        val repository = SyncedDeviceAwareHomeDevicesRepository(delegate, source)

        assertEquals(delegate.devices().first(), repository.devices().first())
        assertEquals(delegate.rooms().first(), repository.rooms().first())
    }

    @Test
    fun realDevices_replaceTheMockHousehold_keepingInfrastructureTiles() = runTest {
        val source = FakeDeviceSource()
        source.flow.value = listOf(light(), sensor())
        val repository = SyncedDeviceAwareHomeDevicesRepository(MockHomeDevicesRepository(), source)

        val devices = repository.devices().first { it.any { d -> d.id == "simulated:light-1" } }

        // Infrastructure tiles (the storage-tile slice's rows) survive...
        assertTrue(devices.any { it.kind == DeviceKind.Hub })
        assertTrue(devices.any { it.kind == DeviceKind.Storage })
        // ...every other mock household row is gone — real and fake never mix.
        assertEquals(
            devices.filter { it.kind != DeviceKind.Hub && it.kind != DeviceKind.Storage }
                .map { it.id }
                .toSet(),
            setOf("simulated:light-1", "simulated:sensor-1"),
        )
        // Sort indices are contiguous from 0 (the repository contract).
        assertEquals(devices.indices.toList(), devices.map { it.sortIndex })
    }

    @Test
    fun lightMapsToLightState_withPowerAndLevel() = runTest {
        val source = FakeDeviceSource()
        source.flow.value = listOf(light(on = true, level = 65))
        val repository = SyncedDeviceAwareHomeDevicesRepository(MockHomeDevicesRepository(), source)

        val device = repository.devices()
            .first { it.any { d -> d.id == "simulated:light-1" } }
            .first { it.id == "simulated:light-1" }

        assertEquals(DeviceKind.Light, device.kind)
        assertEquals("Simulated light", device.name)
        assertNull(device.roomId)
        assertEquals(DeviceState.Light(on = true, brightnessPercent = 65), device.state)
    }

    @Test
    fun sensorMapsToReadingSummary_andItsRoomResolvesToTheExistingMockRoom() = runTest {
        val source = FakeDeviceSource()
        source.flow.value = listOf(sensor()) // room "Office" — a mock room of that name exists
        val repository = SyncedDeviceAwareHomeDevicesRepository(MockHomeDevicesRepository(), source)

        val device = repository.devices()
            .first { it.any { d -> d.id == "simulated:sensor-1" } }
            .first { it.id == "simulated:sensor-1" }

        assertEquals(DeviceKind.Sensor, device.kind)
        val reading = assertIs<DeviceState.SensorReading>(device.state)
        assertEquals("21.5° · 45%", reading.summary)

        // Same-name room resolves to the existing delegate room's id...
        val rooms = repository.rooms().first()
        assertEquals(rooms.first { it.name == "Office" }.id, device.roomId)
        // ...and no duplicate synced room is minted for it.
        assertEquals(1, rooms.count { it.name == "Office" })
    }

    @Test
    fun aRoomUnknownToTheMock_becomesASyncedRoom() = runTest {
        val source = FakeDeviceSource()
        source.flow.value = listOf(light(room = "Attic"))
        val repository = SyncedDeviceAwareHomeDevicesRepository(MockHomeDevicesRepository(), source)

        val roomId = SyncedDeviceAwareHomeDevicesRepository.syncedRoomId("Attic")
        val device = repository.devices()
            .first { it.any { d -> d.id == "simulated:light-1" } }
            .first { it.id == "simulated:light-1" }
        assertEquals(roomId, device.roomId)

        val rooms = repository.rooms().first { it.any { r -> r.id == roomId } }
        assertEquals("Attic", rooms.first { it.id == roomId }.name)
    }

    @Test
    fun sensorWithoutState_rendersAnEmptySummary() = runTest {
        val source = FakeDeviceSource()
        source.flow.value = listOf(
            SyncedDeviceInfo(
                deviceId = "simulated:sensor-1",
                kind = HomeDevice.KIND_SENSOR,
                name = "Climate sensor",
                room = "",
            ),
        )
        val repository = SyncedDeviceAwareHomeDevicesRepository(MockHomeDevicesRepository(), source)

        val device = repository.devices()
            .first { it.any { d -> d.id == "simulated:sensor-1" } }
            .first { it.id == "simulated:sensor-1" }

        assertEquals(DeviceState.SensorReading(summary = ""), device.state)
    }

    @Test
    fun sourceGoingEmptyAgain_fallsBackToTheMock() = runTest {
        val source = FakeDeviceSource()
        val delegate = MockHomeDevicesRepository()
        val repository = SyncedDeviceAwareHomeDevicesRepository(delegate, source)

        source.flow.value = listOf(light())
        repository.devices().first { it.any { d -> d.id == "simulated:light-1" } }

        source.flow.value = emptyList()
        val devices = repository.devices().first { it.none { d -> d.id == "simulated:light-1" } }
        assertEquals(delegate.devices().first(), devices)
    }

    @Test
    fun unknownKind_rendersAsAGenericSensorRow() = runTest {
        val source = FakeDeviceSource()
        source.flow.value = listOf(
            SyncedDeviceInfo(
                deviceId = "simulated:widget-1",
                kind = "some-future-kind",
                name = "Mystery widget",
                room = "",
                stateFresh = true,
                online = true,
            ),
        )
        val repository = SyncedDeviceAwareHomeDevicesRepository(MockHomeDevicesRepository(), source)

        val device = repository.devices()
            .first { it.any { d -> d.id == "simulated:widget-1" } }
            .first { it.id == "simulated:widget-1" }

        assertEquals(DeviceKind.Sensor, device.kind)
        assertIs<DeviceState.SensorReading>(device.state)
    }
}
