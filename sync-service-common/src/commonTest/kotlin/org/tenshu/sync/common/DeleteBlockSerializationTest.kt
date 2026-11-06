package org.tenshu.sync.common

import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.sync.DeleteBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DeleteBlockSerializationTest {

    private val registry = createSyntheticTestRegistry().also {
        it.registerConfig(syncServiceCommonModule)
    }
    private val context = createTestContext(registry)
    private val serializer = BinarySerializer()
    private val parser = BinaryParser(registry, registry)

    @Test
    fun deleteBlock_serializesAndDeserializes() {
        val original = DeleteBlock.create(
            context = context,
            blockId = "block-delete-1",
            ownerId = "user-1",
            hlc = HybridLogicalClock.create(
                context = context,
                physicalTime = 1234L,
                logicalCounter = 5L,
                deviceId = "device-a"
            ),
            targetEntityIdHash = "hash-deadbeef",
            targetEntityType = "tenshu/file/file"
        )

        val bytes = serializer.serialize(original)
        val parsed = parser.parse(bytes) as DeleteBlock

        assertEquals(original.blockId.get(), parsed.blockId.get())
        assertEquals(original.ownerId.get(), parsed.ownerId.get())
        assertEquals(original.hlc.physicalTime.get(), parsed.hlc.physicalTime.get())
        assertEquals(original.hlc.logicalCounter.get(), parsed.hlc.logicalCounter.get())
        assertEquals(original.hlc.deviceId.get(), parsed.hlc.deviceId.get())
        assertEquals(original.targetEntityIdHash.get(), parsed.targetEntityIdHash.get())
        assertEquals(original.targetEntityType.get(), parsed.targetEntityType.get())
    }

    @Test
    fun deleteBlock_revokedDeviceId_roundTripsWhenSet() {
        // Device-revocation tombstone (docs/adr/0006-sync-tombstones.md Phase 4 / T-K-02): the relay
        // extracts revokedDeviceId server-side to revoke the KnownDevicesStore entry.
        val original = DeleteBlock.create(
            context = context,
            blockId = "tombstone-revoke",
            ownerId = "user-1",
            hlc = HybridLogicalClock.create(context, 100L, 0, "admin-device"),
            targetEntityIdHash = "device-hash",
            targetEntityType = "tenshu/identity/device",
            revokedDeviceId = "device-being-revoked"
        )
        val parsed = parser.parse(serializer.serialize(original)) as DeleteBlock
        assertEquals("device-being-revoked", parsed.revokedDeviceId?.get())
    }

    @Test
    fun deleteBlock_revokedDeviceId_emptyOrNullWhenAbsent() {
        // Non-revocation tombstones (e.g. file deletes) leave the field unset. The
        // binary serializer's known quirk is that an absent optional TString round-trips
        // as an empty string rather than null — receivers MUST treat both as "no
        // device to revoke" (the relay's `/sync/push-tombstone` handler does so via a
        // `takeIf { it.isNotEmpty() }` guard).
        val original = DeleteBlock.create(
            context = context,
            blockId = "tombstone-file",
            ownerId = "user-1",
            hlc = HybridLogicalClock.create(context, 200L, 0, "device-a"),
            targetEntityIdHash = "file-hash",
            targetEntityType = "tenshu/file/file"
        )
        val parsed = parser.parse(serializer.serialize(original)) as DeleteBlock
        // Either null or empty — both mean "absent". Pin the semantically meaningful
        // assertion: the revoked device id is empty, not some stray value.
        assertEquals("", parsed.revokedDeviceId?.get() ?: "")
    }

    @Test
    fun deleteBlock_registeredInModuleConfig() {
        val schema = registry.getSchema(DeleteBlock.ID)
        assertNotNull(schema, "DeleteBlock schema should be registered via syncServiceCommonModule")
        assertEquals(DeleteBlock.ID, schema.id.get())
    }
}
