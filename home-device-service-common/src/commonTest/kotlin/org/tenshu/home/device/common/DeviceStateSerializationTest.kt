package org.tenshu.home.device.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.core.TString
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.schema.hasTrait
import org.tenshu.core.toTString
import org.tenshu.core.trait.Syncable
import org.tenshu.home.device.DeviceState

/**
 * Wire round-trip for [DeviceState] (see [HomeDeviceSerializationTest]'s
 * rationale) — covers the generous typed field set, the sentinel encodings,
 * and the `genericData` extras slot.
 */
class DeviceStateSerializationTest {

    private val registry = createCoreTestRegistry().also {
        it.registerConfig(homeDeviceServiceCommonModule)
    }
    private val context = createTestContext(registry)
    private val serializer = BinarySerializer()
    private val parser = BinaryParser(registry, registry)

    @Test
    fun deviceState_roundTripsAllFields() {
        val original = DeviceState.create(
            context = context,
            deviceId = "simulated:light-1",
            online = true,
            power = DeviceState.POWER_ON,
            levelPercent = 65,
            temperatureDeciC = -125, // -12.5 °C — negative temperatures are real
            humidityPercent = 40,
            batteryPercent = 87,
            colorTempMireds = 370,
            powerDrawDeciW = 45,
            lastCommandId = "cmd-1",
            lastCommandOutcome = DeviceState.OUTCOME_OK,
            freshAsOf = 1_720_000_070_000,
            extras = "linkquality=104".toTString(context),
        )

        val bytes = serializer.serialize(original)
        val parsed = parser.parse(bytes) as DeviceState

        assertEquals("simulated:light-1", parsed.deviceId.get())
        assertEquals(true, parsed.online.get())
        assertEquals(DeviceState.POWER_ON, parsed.power.get())
        assertEquals(true, parsed.powerOnOrNull())
        assertEquals(65L, parsed.levelPercent.get())
        assertEquals(-125L, parsed.temperatureDeciC.get())
        assertEquals(40L, parsed.humidityPercent.get())
        assertEquals(87L, parsed.batteryPercent.get())
        assertEquals(370L, parsed.colorTempMireds.get())
        assertEquals(45L, parsed.powerDrawDeciW.get())
        assertEquals("cmd-1", parsed.lastCommandId.get())
        assertEquals(DeviceState.OUTCOME_OK, parsed.lastCommandOutcome.get())
        assertEquals(1_720_000_070_000L, parsed.freshAsOf.get())
        assertEquals("linkquality=104", (parsed.extras as TString).get())
    }

    @Test
    fun deviceState_sentinelDefaultsRoundTrip() {
        val original = DeviceState.create(
            context = context,
            deviceId = "simulated:sensor-1",
            online = false,
            freshAsOf = 42L,
        )

        val parsed = parser.parse(serializer.serialize(original)) as DeviceState

        assertEquals(DeviceState.POWER_NOT_APPLICABLE, parsed.power.get())
        assertNull(parsed.powerOnOrNull())
        assertEquals(DeviceState.NO_PERCENT, parsed.levelPercent.get())
        assertEquals(DeviceState.NO_TEMPERATURE_DECI_C, parsed.temperatureDeciC.get())
        assertEquals(DeviceState.NO_PERCENT, parsed.humidityPercent.get())
        assertEquals(DeviceState.NO_PERCENT, parsed.batteryPercent.get())
        assertEquals(DeviceState.NO_VALUE, parsed.colorTempMireds.get())
        assertEquals(DeviceState.NO_VALUE, parsed.powerDrawDeciW.get())
        assertEquals("", parsed.lastCommandId.get())
        assertEquals("", parsed.lastCommandOutcome.get())
        assertEquals("", (parsed.extras as TString).get())
    }

    @Test
    fun deviceState_schemaCarriesSyncableTrait() {
        assertTrue(
            DeviceState.Schema.hasTrait(Syncable.ID),
            "DeviceState must be Syncable — sync IS the state channel (HD-D1)"
        )
    }
}
