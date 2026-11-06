package org.tenshu.sync.proxy

import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.RekeyMigrationMarker
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * RG-1 (`ios-identity-reinstall-gate.md` §4): a reinstall-time discard drops the outbound queues,
 * because anything staged there is addressed to a realm this device is leaving.
 *
 * ⚠⚠ The assertions deliberately check the **rows**, not just the arrays. Emptying the array while
 * leaving the row is the exact shape of the pre-G1 leak — 99.87 % of measured `sync/block` rows —
 * so a purge that only called `removeFromArray` would satisfy a queue-length assertion and still be
 * the bug.
 */
class OutboundQueuePurgeTest {

    private lateinit var testProxy: TestProxy
    private lateinit var syncManager: SyncManagerImpl

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        testProxy.schemaRegistry.registerConfig(org.tenshu.sync.common.syncServiceCommonModule)
        syncManager = SyncManagerImpl(
            cryptoManager = testProxy.tContext.syncCryptoManager(),
            storageDelegate = InMemorySecureStorageDelegate(),
            context = testProxy.tContext,
        )
        syncManager.initialize("mock phrase")
    }

    private fun blocksRef(relationId: String) = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = testProxy.tContext,
            type = SyncBlock.ID,
            relationId = relationId,
        ),
        isEntity = false,
        elementSchemaId = SyncBlock.ID,
    )

    private fun tombstonesRef() = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = testProxy.tContext,
            type = DeleteBlock.ID,
            relationId = SyncService.Collections.PENDING_TOMBSTONES,
        ),
        isEntity = false,
        elementSchemaId = DeleteBlock.ID,
    )

    private fun queueBlock(relationId: String, blockId: String): SyncBlock {
        val block = SyncBlock.create(
            context = testProxy.tContext,
            blockId = blockId,
            ownerId = requireNotNull(syncManager.currentOwnerId),
            hlc = syncManager.hlcManager.tick(),
            encryptedPayload = byteArrayOf(9, 9, 9),
            targetEntityIdHash = null,
        )
        testProxy.executionContext.database.addToArray(blocksRef(relationId), block)
        return block
    }

    private fun queueTombstone(deviceId: String): DeleteBlock {
        val tombstone = DeleteBlock.create(
            context = testProxy.tContext,
            blockId = "tomb-$deviceId",
            ownerId = requireNotNull(syncManager.currentOwnerId),
            hlc = syncManager.hlcManager.tick(),
            targetEntityIdHash = "1234",
            targetEntityType = "tenshu/identity/device",
            revokedDeviceId = deviceId,
        )
        testProxy.executionContext.database.addToArray(tombstonesRef(), tombstone)
        return tombstone
    }

    private fun rowOf(data: org.tenshu.core.Data) =
        testProxy.executionContext.getDataId(data)?.let { testProxy.database.getData(it) }

    private fun queueLength(ref: ArrayDatabaseReference) =
        testProxy.executionContext.database.getArrayData(ref)?.toList()?.size ?: 0

    @Test
    fun purgeEmptiesEveryOutboundQueue_andDeletesTheRowsBehindThem() {
        val pending = queueBlock(SyncService.Collections.PENDING_BLOCKS, "b-1")
        val second = queueBlock(SyncService.Collections.PENDING_BLOCKS, "b-2")
        val tombstone = queueTombstone("dev-gone")

        assertEquals(2, queueLength(blocksRef(SyncService.Collections.PENDING_BLOCKS)))
        assertEquals(1, queueLength(tombstonesRef()))
        assertNotNull(rowOf(pending), "precondition: queueing persisted a row")

        val purged = testProxy.executionContext.purgeOutboundQueues()

        assertEquals(3, purged, "every staged element is reported as purged")
        assertEquals(0, queueLength(blocksRef(SyncService.Collections.PENDING_BLOCKS)))
        assertEquals(0, queueLength(tombstonesRef()))

        // ⚠⚠ The half a `removeFromArray`-only purge would fail: the rows themselves must be gone.
        assertNull(rowOf(pending), "the pending block's row must be deleted, not just dequeued")
        assertNull(rowOf(second), "every queued block's row must be deleted, not just dequeued")
        assertNull(rowOf(tombstone), "the tombstone's row must be deleted, not just dequeued")
    }

    /**
     * ⚠⚠ The rekey retry journal is **owner-scoped** (`…/<ownerId>`), so purging the bare relation
     * clears an array nothing ever writes — a silent no-op. Forget-and-rejoin the same realm then
     * replays a stale marker on unlock, and if the old path has been recreated on a peer meanwhile,
     * the replay deletes the peer's new file.
     */
    @Test
    fun purgeClearsTheOwnerScopedRekeyJournal_notTheBareRelation() {
        val ownerId = "owner-1"
        val scoped = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = testProxy.tContext,
                type = RekeyMigrationMarker.ID,
                relationId = SyncService.Collections.PENDING_REKEY_TOMBSTONES + "/" + ownerId,
            ),
            isEntity = false,
            elementSchemaId = RekeyMigrationMarker.ID,
        )
        testProxy.executionContext.database.addToArray(
            scoped,
            RekeyMigrationMarker.create(
                context = testProxy.tContext,
                markerKey = "m-1",
                oldDataId = DataId.StringDataId.create(
                    context = testProxy.tContext,
                    type = "tenshu/file/system/file",
                    keyValue = "/a.txt",
                ),
                newHash = "newhash",
                entityType = "tenshu/file/system/file",
            ),
        )
        assertEquals(1, queueLength(scoped), "precondition: a retry is staged for that realm")

        testProxy.executionContext.purgeOutboundQueues(ownerId)

        assertEquals(
            0,
            queueLength(scoped),
            "the realm being left must take its rekey retries with it",
        )
    }

    @Test
    fun purgeOnAnEmptyDeviceIsAZeroCountNoOp() {
        // The common case on a reinstall: nothing was staged. It must not throw on the missing
        // arrays — a discard that fails here would strand the user on the fork it was meant to
        // resolve.
        assertEquals(0, testProxy.executionContext.purgeOutboundQueues())
    }
}
