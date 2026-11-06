package org.tenshu.home.device.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tenshu.core.TString
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.schema.hasTrait
import org.tenshu.core.toTString
import org.tenshu.core.trait.Syncable
import org.tenshu.home.device.HomeDevice

/**
 * Wire round-trip for [HomeDevice] — new composites riding sync/callstacks
 * must be provably serialize→parse-able through the registered module
 * (in-process tests miss a missing registration; the known repo footgun).
 * Includes the polymorphic `genericData` extras slot.
 */
class HomeDeviceSerializationTest {

    private val registry = createCoreTestRegistry().also {
        it.registerConfig(homeDeviceServiceCommonModule)
    }
    private val context = createTestContext(registry)
    private val serializer = BinarySerializer()
    private val parser = BinaryParser(registry, registry)

    @Test
    fun homeDevice_roundTripsAllFields() {
        val original = HomeDevice.create(
            context = context,
            deviceId = "simulated:light-1",
            bridgeId = HomeDevice.BRIDGE_SIMULATED,
            kind = HomeDevice.KIND_LIGHT,
            manufacturer = "Tenshu",
            model = "SimLight 9000",
            capabilityIds = "${HomeDevice.CAP_POWER} ${HomeDevice.CAP_LEVEL}",
            name = "Desk lamp",
            room = "Office",
            joinedAtEpochMs = 1_720_000_000_000,
            extras = "zigbee:0x00158d0001".toTString(context),
        )

        val bytes = serializer.serialize(original)
        val parsed = parser.parse(bytes) as HomeDevice

        assertEquals("simulated:light-1", parsed.deviceId.get())
        assertEquals(HomeDevice.BRIDGE_SIMULATED, parsed.bridgeId.get())
        assertEquals(HomeDevice.KIND_LIGHT, parsed.kind.get())
        assertEquals("Tenshu", parsed.manufacturer.get())
        assertEquals("SimLight 9000", parsed.model.get())
        assertEquals(
            listOf(HomeDevice.CAP_POWER, HomeDevice.CAP_LEVEL),
            parsed.capabilities(),
        )
        assertEquals("Desk lamp", parsed.name.get())
        assertEquals("Office", parsed.room.get())
        assertEquals(1_720_000_000_000L, parsed.joinedAtEpochMs.get())
        // The genericData escape hatch round-trips its polymorphic payload.
        assertEquals("zigbee:0x00158d0001", (parsed.extras as TString).get())
    }

    @Test
    fun homeDevice_defaultExtrasRoundTripAsEmptyString() {
        val original = HomeDevice.create(
            context = context,
            deviceId = "simulated:sensor-1",
            bridgeId = HomeDevice.BRIDGE_SIMULATED,
            kind = HomeDevice.KIND_SENSOR,
            name = "Climate sensor",
            joinedAtEpochMs = 1L,
        )

        val parsed = parser.parse(serializer.serialize(original)) as HomeDevice

        assertEquals("", (parsed.extras as TString).get())
        assertEquals(emptyList(), parsed.capabilities())
        assertEquals("", parsed.room.get())
    }

    @Test
    fun homeDevice_schemaCarriesSyncableTrait() {
        assertTrue(
            HomeDevice.Schema.hasTrait(Syncable.ID),
            "HomeDevice must be Syncable — sync IS the inventory channel (HD-D1)"
        )
    }
}
