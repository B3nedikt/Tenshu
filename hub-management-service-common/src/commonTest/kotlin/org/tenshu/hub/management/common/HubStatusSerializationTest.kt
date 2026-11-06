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
import org.tenshu.hub.management.HubStatus

/**
 * Wire round-trip for [HubStatus] — new composites riding sync/callstacks
 * must be provably serialize→parse-able through the registered module
 * (in-process tests miss a missing registration; the known repo footgun).
 */
class HubStatusSerializationTest {

    private val registry = createCoreTestRegistry().also {
        it.registerConfig(hubManagementServiceCommonModule)
    }
    private val context = createTestContext(registry)
    private val serializer = BinarySerializer()
    private val parser = BinaryParser(registry, registry)

    @Test
    fun hubStatus_roundTripsAllFields() {
        val original = HubStatus.create(
            context = context,
            deviceId = "hub-device-1",
            name = "Living Room Hub",
            version = "1.2.3",
            startedAtEpochMs = 1_720_000_000_000,
            replicaFileCount = 1234,
            replicaByteCount = 987_654_321,
            diskFreeBytes = 512_000_000_000,
            diskTotalBytes = 1_000_204_886_016,
            lastBackupPassAtEpochMs = 1_720_000_060_000,
            paired = true,
            freshAsOf = 1_720_000_070_000,
            lastCommandId = "cmd-9",
            lastCommandOutcome = HubStatus.OUTCOME_OK,
        )

        val bytes = serializer.serialize(original)
        val parsed = parser.parse(bytes) as HubStatus

        assertEquals("hub-device-1", parsed.deviceId.get())
        assertEquals("Living Room Hub", parsed.name.get())
        assertEquals("1.2.3", parsed.version.get())
        assertEquals(1_720_000_000_000L, parsed.startedAtEpochMs.get())
        assertEquals(1234L, parsed.replicaFileCount.get())
        assertEquals(987_654_321L, parsed.replicaByteCount.get())
        assertEquals(512_000_000_000L, parsed.diskFreeBytes.get())
        assertEquals(1_000_204_886_016L, parsed.diskTotalBytes.get())
        assertEquals(1_720_000_060_000L, parsed.lastBackupPassAtEpochMs.get())
        assertEquals(true, parsed.paired.get())
        assertEquals(1_720_000_070_000L, parsed.freshAsOf.get())
        assertEquals("cmd-9", parsed.lastCommandId.get())
        assertEquals(HubStatus.OUTCOME_OK, parsed.lastCommandOutcome.get())
    }

    @Test
    fun hubStatus_schemaCarriesSyncableTrait() {
        assertTrue(
            HubStatus.Schema.hasTrait(Syncable.ID),
            "HubStatus must be Syncable — the status IS the cross-device channel (H4)"
        )
    }
}
