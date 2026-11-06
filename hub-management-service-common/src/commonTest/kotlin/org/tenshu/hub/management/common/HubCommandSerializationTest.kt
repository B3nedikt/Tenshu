package org.tenshu.hub.management.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.schema.hasTrait
import org.tenshu.core.trait.Syncable
import org.tenshu.hub.management.HubCommand

/** Wire round-trip for [HubCommand] (see [HubStatusSerializationTest]'s rationale). */
class HubCommandSerializationTest {

    private val registry = createCoreTestRegistry().also {
        it.registerConfig(hubManagementServiceCommonModule)
    }
    private val context = createTestContext(registry)
    private val serializer = BinarySerializer()
    private val parser = BinaryParser(registry, registry)

    @Test
    fun hubCommand_roundTripsAllFields() {
        val original = HubCommand.create(
            context = context,
            commandId = "cmd-42",
            targetDeviceId = "hub-device-1",
            kind = HubCommand.KIND_RESTART,
            mode = HubCommand.MODE_PROXY,
            issuedAtEpochMs = 1_720_000_123_456,
            issuedByDeviceId = "phone-device-7",
        )

        val bytes = serializer.serialize(original)
        val parsed = parser.parse(bytes) as HubCommand

        assertEquals("cmd-42", parsed.commandId.get())
        assertEquals("hub-device-1", parsed.targetDeviceId.get())
        assertEquals(HubCommand.KIND_RESTART, parsed.kind.get())
        assertEquals(HubCommand.MODE_PROXY, parsed.mode.get())
        assertEquals(1_720_000_123_456L, parsed.issuedAtEpochMs.get())
        assertEquals("phone-device-7", parsed.issuedByDeviceId.get())
    }

    @Test
    fun hubCommand_schemaCarriesSyncableTrait() {
        assertTrue(
            HubCommand.Schema.hasTrait(Syncable.ID),
            "HubCommand must be Syncable — sync IS the command channel (H4)"
        )
    }
}
