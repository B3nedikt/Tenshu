package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.schema.currentVersionVector
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.ChangesResponse
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.GetChanges
import org.tenshu.sync.GetTombstones
import org.tenshu.sync.PushBlock
import org.tenshu.sync.PushTombstone
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncService
import org.tenshu.sync.TombstonesResponse
import org.tenshu.sync.common.DeviceSchemaVersion
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A queue-wide push rejection must stop the **whole** outbound pass, tombstones included.
 *
 * ## The split this exists to prevent
 *
 * `tickOnce` computes `pushAllowed` **once** — `storageGate && !entitlementBackoff.isBackingOff()
 * && !registrationBackoff.isBackingOff()` — and then runs `drainPendingBlocks()` followed by
 * `drainPendingTombstones()`. But a `402`/`401` *inside* `drainPendingBlocks` arms its backoff
 * mid-pass, and `drainPendingTombstones` re-read nothing: its only guard was that stale boolean.
 *
 * ⚠⚠ So on the tick where enforcement first bites, blocks are refused and tombstones are pushed —
 * and the tombstone route is **ungated at the relay**, so they land. That splits a single user
 * action across the two planes. A file delete writes both: `removeFromArray` on the parent
 * `Folder.files` plus a `FileDeletedNotification` (ordinary blocks, gated) *and* the entity
 * tombstone (ungated). Peers would then drop the `File` while their parent folder still lists it.
 *
 * ⚠ The ordering is deliberate and stays — `tickOnce` documents that pre-revoke blocks must reach
 * the relay *before* the tombstone that supersedes them, so receivers short-circuit cleanly. What
 * was missing is that a rejection mid-sequence has to abandon the rest of the sequence.
 */
class DrainSplitOnRejectionTest {

    private var blockPushes = 0
    private var tombstonePushes = 0

    private fun tombstonesRef(proxy: TestProxy) = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = proxy.tContext,
            type = DeleteBlock.ID,
            relationId = SyncService.Collections.PENDING_TOMBSTONES,
        ),
        isEntity = false,
        elementSchemaId = DeleteBlock.ID,
    )

    private fun blocksRef(proxy: TestProxy) = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = proxy.tContext,
            type = SyncBlock.ID,
            relationId = SyncService.Collections.PENDING_BLOCKS,
        ),
        isEntity = false,
        elementSchemaId = SyncBlock.ID,
    )

    private fun stub(body: (ExecutionContext) -> Unit) = object : SyncOperationImplementation {
        @Suppress("UNCHECKED_CAST")
        override fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?,
        ): T {
            body(executionContext)
            return null as T
        }
    }

    /**
     * Drives one real `tickOnce` with a block **and** a tombstone queued, where every `PushBlock`
     * fails carrying [marker] (a queue-wide rejection) while `PushTombstone` would happily succeed.
     */
    private suspend fun tickWithRejection(marker: String): Pair<Int, Int> {
        blockPushes = 0
        tombstonePushes = 0

        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)

        proxy.additionalImplementations[PushBlock.ID] = stub { ctx ->
            blockPushes++
            throw TenshuException(GenericError.create(ctx.context, "$marker: refused"))
        }
        // ⚠ Deliberately succeeds. The relay does NOT gate `/sync/push-tombstone`, so a fake that
        // failed here would hide the very split this test is about.
        proxy.additionalImplementations[PushTombstone.ID] = stub { tombstonePushes++ }
        proxy.additionalImplementations[GetChanges.ID] = object : SyncOperationImplementation {
            @Suppress("UNCHECKED_CAST")
            override fun <T : Data?> execute(
                executionContext: ExecutionContext,
                operationContext: OperationContext?,
                implementationFactory: OperationImplementationFactory,
                caller: Any?,
                params: Map<String, Data>?,
            ): T = ChangesResponse.create(
                context = executionContext.context,
                blocks = emptyList(),
                peerTips = emptyList(),
                nextCursor = 0L,
            ) as T
        }
        proxy.additionalImplementations[GetTombstones.ID] = object : SyncOperationImplementation {
            @Suppress("UNCHECKED_CAST")
            override fun <T : Data?> execute(
                executionContext: ExecutionContext,
                operationContext: OperationContext?,
                implementationFactory: OperationImplementationFactory,
                caller: Any?,
                params: Map<String, Data>?,
            ): T = TombstonesResponse.create(
                context = executionContext.context,
                tombstones = emptyList(),
                nextCursor = 0L,
            ) as T
        }

        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext,
        )
        syncManager.initialize("mock phrase")
        syncManager.currentRealmContext()?.deviceId?.takeIf { it.isNotEmpty() }?.let { deviceId ->
            proxy.executionContext.saveData(
                DeviceSchemaVersion.create(
                    context = proxy.tContext,
                    deviceId = deviceId,
                    versions = proxy.schemaRegistry.currentVersionVector(proxy.tContext),
                ),
                WriteOrigin.SYNC_INGEST,
            )
        }

        val components = proxy.executionContext.initSyncService(
            syncManager,
            crypto,
            // Stepped by hand via `drainNow`, so the loops are never started rather than started
            // and then stopped — a loop that never starts has no first tick to race
            // (`SyncHealthReportingTest.fixture`). ⚠ This proxy is NOT bound to the TestScope, so
            // a started loop would have run on real `Dispatchers.Default` threads.
            startBackgroundLoops = false,
            platformScheduler = NoOpPlatformScheduler,
        )

        val ownerId = requireNotNull(syncManager.currentOwnerId)
        proxy.executionContext.database.addToArray(
            blocksRef(proxy),
            SyncBlock.create(
                context = proxy.tContext,
                blockId = "b-1",
                ownerId = ownerId,
                hlc = syncManager.hlcManager.tick(),
                encryptedPayload = byteArrayOf(1, 2, 3),
                targetEntityIdHash = null,
            ),
        )
        proxy.executionContext.database.addToArray(
            tombstonesRef(proxy),
            DeleteBlock.create(
                context = proxy.tContext,
                blockId = "t-1",
                ownerId = ownerId,
                hlc = syncManager.hlcManager.tick(),
                targetEntityIdHash = "deadbeef",
                targetEntityType = "tenshu/file/system/file",
                revokedDeviceId = null,
            ),
        )

        components.scheduler.drainNow()

        val queued = proxy.executionContext.database
            .getArrayData(tombstonesRef(proxy))?.toList()?.size ?: 0
        return tombstonePushes to queued
    }

    @Test
    fun aBlockRejectionStopsTheTombstoneDrainInTheSameTick_entitlement() = runTest {
        val (pushed, stillQueued) = tickWithRejection(ENTITLEMENT_REJECTION_MARKER)

        assertEquals(
            0,
            pushed,
            "a 402 on the block drain armed the backoff, yet the tombstone still went out in the " +
                "same tick — the delete is now split across a gated and an ungated route",
        )
        assertEquals(1, stillQueued, "the tombstone must stay queued for a tick that can push both")
    }

    @Test
    fun aBlockRejectionStopsTheTombstoneDrainInTheSameTick_unauthorized() = runTest {
        // ⚠ The 401 arms `registrationBackoff` *inside* the block loop rather than after it, so it
        // is armed even earlier than the 402 — same split, different counter.
        val (pushed, stillQueued) = tickWithRejection(UNAUTHORIZED_REJECTION_MARKER)

        assertEquals(0, pushed, "a 401 on the block drain still let the tombstone out")
        assertEquals(1, stillQueued, "the tombstone must stay queued")
    }
}
