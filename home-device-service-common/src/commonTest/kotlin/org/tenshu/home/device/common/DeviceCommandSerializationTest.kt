package org.tenshu.home.device.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.schema.hasTrait
import org.tenshu.core.trait.Syncable
import org.tenshu.home.device.DeviceCommand

/** Wire round-trip for [DeviceCommand] (see [HomeDeviceSerializationTest]'s rationale). */
class DeviceCommandSerializationTest {

    private val registry = createCoreTestRegistry().also {
        it.registerConfig(homeDeviceServiceCommonModule)
    }
    private val context = createTestContext(registry)
    private val serializer = BinarySerializer()
    private val parser = BinaryParser(registry, registry)

    @Test
    fun deviceCommand_roundTripsAllFields() {
        val original = DeviceCommand.create(
            context = context,
            commandId = "cmd-42",
            targetDeviceId = "simulated:light-1",
            verb = DeviceCommand.VERB_SET_LEVEL,
            argValue = "65",
            issuedAtEpochMs = 1_720_000_123_456,
            issuedByDeviceId = "phone-device-7",
        )

        val bytes = serializer.serialize(original)
        val parsed = parser.parse(bytes) as DeviceCommand

        assertEquals("cmd-42", parsed.commandId.get())
        assertEquals("simulated:light-1", parsed.targetDeviceId.get())
        assertEquals(DeviceCommand.VERB_SET_LEVEL, parsed.verb.get())
        assertEquals("65", parsed.argValue.get())
        assertEquals(1_720_000_123_456L, parsed.issuedAtEpochMs.get())
        assertEquals("phone-device-7", parsed.issuedByDeviceId.get())
    }

    @Test
    fun deviceCommand_schemaCarriesSyncableTrait() {
        assertTrue(
            DeviceCommand.Schema.hasTrait(Syncable.ID),
            "DeviceCommand must be Syncable — sync IS the command channel (HD-D1)"
        )
    }

    @Test
    fun deviceCommand_stalenessWindowIsTwoMinutes() {
        // HD-D7: physical actuation — the 2-minute window is part of the
        // contract, pinned here so a casual "relax it to 15 like hub
        // management" fails a test instead of shipping silently.
        assertEquals(120_000L, DeviceCommand.STALE_WINDOW_MS)
    }
}
