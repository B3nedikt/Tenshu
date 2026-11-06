package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.identity.Device
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.PushTombstone
import org.tenshu.sync.common.EnvelopeAuth
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.PushTombstoneImplementation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * EI-4 (`sync-entity-identity.md` §5b): a tombstone's `targetEntityType` leaves the device as one bit —
 * `Device.ID` or [TombstoneWireType.NON_DEVICE] — mapped at drain time. The sentinel's collision guard
 * over the full schema graph is `TombstoneSentinelCollisionTest` in `:system-test-utils`.
 */
class TombstoneWireTypeTest {

    private val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry().apply { registerConfig(syncServiceCommonModule) })
    private val context = proxy.tContext
    private val key = ByteArray(32) { 3 }

    private fun tombstone(type: String, revoked: String? = null) = DeleteBlock.create(
        context = context,
        blockId = "t-1",
        ownerId = "owner",
        hlc = HybridLogicalClock.create(context, 1_000, 3, "device-a"),
        targetEntityIdHash = "a".repeat(32),
        targetEntityType = type,
        revokedDeviceId = revoked,
    )

    @Test
    fun everyNonDeviceTypeBecomesTheSentinel_andDeviceStaysDevice() {
        listOf("tenshu/file/system/file", "tenshu/health/workout", "tenshu/file/fileModification", "x").forEach {
            assertEquals(TombstoneWireType.NON_DEVICE, TombstoneWireType.of(it), it)
        }
        assertEquals(Device.ID, TombstoneWireType.of(Device.ID))
        assertEquals(TombstoneWireType.NON_DEVICE, TombstoneWireType.of(TombstoneWireType.NON_DEVICE))
    }

    @Test
    fun theWireCopyChangesOnlyTheType() {
        val local = tombstone("tenshu/file/system/file")
        val wire = TombstoneWireType.narrowed(context, local)

        assertEquals(TombstoneWireType.NON_DEVICE, wire.targetEntityType.get())
        assertEquals(local.blockId.get(), wire.blockId.get())
        assertEquals(local.ownerId.get(), wire.ownerId.get())
        assertEquals(local.hlc.physicalTime.get(), wire.hlc.physicalTime.get())
        assertEquals(local.hlc.logicalCounter.get(), wire.hlc.logicalCounter.get())
        assertEquals(local.targetEntityIdHash.get(), wire.targetEntityIdHash.get())
        assertEquals(null, wire.revokedDeviceId?.get())
    }

    @Test
    fun aDeviceTombstoneGoesOutUnchanged_revokedDeviceIdIncluded() {
        val revocation = tombstone(Device.ID, revoked = "device-b")
        assertSame(revocation, TombstoneWireType.narrowed(context, revocation))
    }

    /** No key ⇒ no push: an untagged tombstone must never reach the relay; the queued copy stays. */
    @Test
    fun theDrainRefusesToPushWithoutAKey() = runTest {
        val relay = MockSyncRelay(context)
        val drain = PushTombstoneImplementation(relay) { null }
        assertFailsWith<org.tenshu.core.TenshuException> {
            drain.execute<Data?>(proxy.executionContext, null, { null }, null, mapOf(PushTombstone.Params.BLOCK to tombstone("x")))
        }
        assertEquals(0, (relay.getTombstones(0) as TResult.Success).value!!.tombstones.toList().size)
    }

    /** ⭐ `sync-envelope-authentication.md` §3.1: the tag covers the NARROWED type, which is what peers see. */
    @Test
    fun theWireCopyIsTaggedOverTheNarrowedType() {
        val wire = TombstoneWireType.forWire(context, tombstone("tenshu/file/system/file"), key)
        assertEquals(TombstoneWireType.NON_DEVICE, wire.targetEntityType.get())
        assertTrue(EnvelopeAuth.verifyTombstone(wire, key))
    }

    /** ⭐ The drain maps: a tombstone queued with a concrete type (before an upgrade) leaves as the sentinel. */
    @Test
    fun theDrainPushesTheSentinel_forATombstoneQueuedWithItsConcreteType() = runTest {
        val relay = MockSyncRelay(context)
        val drain = PushTombstoneImplementation(relay) { key }

        for (block in listOf(tombstone("tenshu/file/system/file"), tombstone(Device.ID, revoked = "device-b"))) {
            drain.execute<Data?>(
                executionContext = proxy.executionContext,
                operationContext = null,
                implementationFactory = { null },
                caller = null,
                params = mapOf(PushTombstone.Params.BLOCK to block),
            )
        }

        val pushed = (relay.getTombstones(0) as TResult.Success).value!!.tombstones.toList()
        assertEquals(listOf(TombstoneWireType.NON_DEVICE, Device.ID), pushed.map { it.targetEntityType.get() })
        assertEquals("device-b", pushed[1].revokedDeviceId?.get())
        // ⭐ Including the one queued before the upgrade, with no tag in its queued copy.
        pushed.forEach { assertTrue(EnvelopeAuth.verifyTombstone(it, key), it.blockId.get()) }
        // The relay's one question still has its answer: one device tombstone, so epoch 1.
        assertEquals(1L, (relay.fetchRealmEpoch("owner") as TResult.Success).value!!.get())
    }
}
