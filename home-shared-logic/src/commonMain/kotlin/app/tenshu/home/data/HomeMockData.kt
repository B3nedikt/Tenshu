package app.tenshu.home.data

import app.tenshu.home.model.Device
import app.tenshu.home.model.DeviceKind
import app.tenshu.home.model.DeviceState
import app.tenshu.home.model.Room

/**
 * Seed fixture for the mock repository (docs/proposals/home-and-mia-apps.md §7) — a
 * plausible small household: the Tenshu Hub + its storage, a speaker, lamps, a camera, a
 * thermostat, a door sensor. Names double as test anchors (DevicesScreenTest), so change
 * them together.
 */
object HomeMockData {

    val livingRoom = Room(id = "room-living", name = "Living room")
    val kitchen = Room(id = "room-kitchen", name = "Kitchen")
    val office = Room(id = "room-office", name = "Office")

    fun rooms(): List<Room> = listOf(livingRoom, kitchen, office)

    fun devices(): List<Device> = listOf(
        Device(
            id = "device-hub",
            name = "Tenshu Hub",
            roomId = livingRoom.id,
            kind = DeviceKind.Hub,
            state = DeviceState.Connectivity(online = true),
            sortIndex = 0,
        ),
        Device(
            id = "device-hub-storage",
            name = "Hub storage",
            roomId = livingRoom.id,
            kind = DeviceKind.Storage,
            state = DeviceState.Storage(
                usedBytes = 412_000_000_000,
                totalBytes = 1_000_000_000_000,
            ),
            sortIndex = 1,
        ),
        Device(
            id = "device-speaker",
            name = "Living room speaker",
            roomId = livingRoom.id,
            kind = DeviceKind.Speaker,
            state = DeviceState.Connectivity(online = true),
            sortIndex = 2,
        ),
        Device(
            id = "device-lamp-sofa",
            name = "Sofa lamp",
            roomId = livingRoom.id,
            kind = DeviceKind.Light,
            state = DeviceState.Light(on = true, brightnessPercent = 70),
            sortIndex = 3,
        ),
        Device(
            id = "device-lamp-desk",
            name = "Desk lamp",
            roomId = office.id,
            kind = DeviceKind.Light,
            state = DeviceState.Light(on = false, brightnessPercent = 40),
            sortIndex = 4,
        ),
        Device(
            id = "device-camera",
            name = "Front door camera",
            roomId = null,
            kind = DeviceKind.Camera,
            state = DeviceState.Connectivity(online = false),
            sortIndex = 5,
        ),
        Device(
            id = "device-thermostat",
            name = "Kitchen thermostat",
            roomId = kitchen.id,
            kind = DeviceKind.Thermostat,
            state = DeviceState.Thermostat(currentDeciC = 213, targetDeciC = 210),
            sortIndex = 6,
        ),
        Device(
            id = "device-door-sensor",
            name = "Balcony door",
            roomId = livingRoom.id,
            kind = DeviceKind.Sensor,
            state = DeviceState.SensorReading(summary = "Closed"),
            sortIndex = 7,
        ),
    )
}
