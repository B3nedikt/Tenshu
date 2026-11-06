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
import org.tenshu.sync.common.SyncGate
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D3's fence must stay shut on a tick whose metadata **write** was refused, even when the **read**
 * that follows it succeeds (`content-plane-refcount.md` §6d).
 *
 * ⚠⚠ This is the general form of a defect review found four separate times, each time as a new
 * special case: a `401`, then a `402`, then a `500`. Every one is the same sentence —
 * `noteRelayAccepted()` is only ever called from the PULL paths, so a successful read was
 * certifying that writes are accepted. Both outcomes occur in the **same tick**: the push fails and
 * the pull after it succeeds, so nothing about "was the relay reachable" can distinguish them.
 *
 * A move let through here creates a holder no peer can see, takes no pin and parks no intent, while
 * another device stays free to delete the old holder and reclaim its content.
 *
 * ⚠ The error used is deliberately a plain [GenericError], NOT a 401 or a 402 — those already have
 * their own guards, and a test using one would pass with this one removed.
 */
class FenceClosesOnRefusedWriteTest {

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
     * Drives one real `tickOnce` with a block **and** a tombstone queued. Exactly one of the two
     * pushes fails carrying [marker]; the other succeeds, so each case isolates one write path.
     */
    private var pulls = 0

    private suspend fun tickWithRejection(
        marker: String,
        refuseTombstone: Boolean = false,
        refuseNothing: Boolean = false,
        storageOpen: Boolean = true,
        registered: Boolean = false,
    ): SyncHealthFigures {
        pulls = 0
        blockPushes = 0
        tombstonePushes = 0

        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)

        proxy.additionalImplementations[PushBlock.ID] = object : SyncOperationImplementation {
            @Suppress("UNCHECKED_CAST")
            override fun <T : Data?> execute(
                executionContext: ExecutionContext,
                operationContext: OperationContext?,
                implementationFactory: OperationImplementationFactory,
                caller: Any?,
                params: Map<String, Data>?,
            ): T {
                blockPushes++
                val refuse = !refuseNothing && !refuseTombstone
                if (refuse) throw TenshuException(GenericError.create(executionContext.context, "$marker: refused"))
                return null as T
            }
        }
        // ⚠ Succeeds unless this case is about it: with both failing, either guard alone would
        // pass the test.
        proxy.additionalImplementations[PushTombstone.ID] = stub { ctx ->
            tombstonePushes++
            if (refuseTombstone) throw TenshuException(GenericError.create(ctx.context, "$marker: refused"))
        }
        proxy.additionalImplementations[GetChanges.ID] = object : SyncOperationImplementation {
            @Suppress("UNCHECKED_CAST")
            override fun <T : Data?> execute(
                executionContext: ExecutionContext,
                operationContext: OperationContext?,
                implementationFactory: OperationImplementationFactory,
                caller: Any?,
                params: Map<String, Data>?,
            ): T = also { pulls++ }.let { ChangesResponse.create(
                context = executionContext.context,
                blocks = emptyList(),
                peerTips = emptyList(),
                nextCursor = 0L,
            ) as T }
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
            storageGate = SyncGate { storageOpen },
            platformScheduler = NoOpPlatformScheduler,
        )

        // A realm registered on the relay pulls even with the storage gate shut (`pullAllowed`) —
        // the shape in which a pull can succeed while no push is permitted.
        if (registered) syncManager.realmRelayRegistered = true
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

        return components.syncHealthHolder.current()
    }

    @Test
    fun aRefusedWriteClosesTheFenceEvenWhenTheReadSucceeded() = runTest {
        val figures = tickWithRejection("relay-500")

        assertEquals(
            false,
            figures.relayConfirmedReachable,
            "the push was refused this tick — a pull succeeding afterwards is not evidence that " +
                "writes are landing, and the fence must not open on it",
        )
        assertTrue(
            figures.relayUnreachableForFencing(),
            "so the predicate the three platform modules read reports the fence as shut",
        )
        assertTrue(
            figures.online,
            "while the BANNER still says online, because the relay did answer — the two are " +
                "allowed to disagree, and this is exactly when they must",
        )
    }

    @Test
    fun aRefusedTombstoneClosesTheFenceToo() = runTest {
        val figures = tickWithRejection("relay-500", refuseTombstone = true)

        assertEquals(1, tombstonePushes, "the tombstone push was attempted")
        assertEquals(
            false,
            figures.relayConfirmedReachable,
            "a move's own write includes the tombstone retiring the old holder — a refused one is " +
                "as much a failed write as a refused block",
        )
    }

    /**
     * ⚠⚠ The positive control every case above depends on. Without it, a fixture that can never read
     * `true` makes each "the fence stays shut" assertion vacuous — which is exactly how the first
     * draft of the storage-gate case below passed with its fix deleted.
     */
    @Test
    fun control_aTickWithNothingRefusedAndPushPermitted_confirmsReachability() = runTest {
        val figures = tickWithRejection("unused", refuseNothing = true, storageOpen = true)
        assertEquals(true, figures.relayConfirmedReachable, "the fixture CAN open the fence")
    }

    @Test
    fun aShutStorageGateKeepsTheFenceShut_thoughNothingWasRefused() = runTest {
        // ⚠⚠ Codex P1 on 0edd42b. On a REGISTERED realm with the storage gate shut (startup before
        // storage figures load, a lapsed subscription) every personal push is skipped — so no refusal
        // is recorded — while the pull still runs and succeeds. "Nothing refused" is not "a write
        // would land": a move let through here creates a holder this device cannot publish.
        val figures = tickWithRejection("unused", refuseNothing = true, storageOpen = false, registered = true)

        assertTrue(pulls > 0, "precondition: the pull actually ran")
        assertEquals(0, blockPushes, "precondition: no push was even attempted")
        assertEquals(true, figures.relaySyncConfigured, "precondition: a relay is configured, so the fence is live")
        assertEquals(false, figures.relayConfirmedReachable, "so the fence has no write evidence and stays shut")
    }
}
