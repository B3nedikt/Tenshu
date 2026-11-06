package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.PushTombstone
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A tombstone stamped for another realm is **left queued, never pushed**.
 *
 * ⚠⚠ The queue is global and the push authenticates as whichever realm is current, so the two can
 * disagree: an owner adoption (`installReceivedPairingPayload` seating a different owner) between an
 * emit and its drain leaves a block from the superseded realm here. Pushing it earns a `403` — and
 * the caller was told it was emitted long ago and has already deleted its row, so the failure is
 * invisible where it happens and permanent where it lands.
 *
 * ⚠ The emit side refuses this too, but only the drain can be *certain*: it is the point that knows
 * which realm the push will actually authenticate as. The emit's check and this one are the same
 * rule at the two ends of a window that cannot be closed with a lock the codebase does not have —
 * `SyncManagerImpl` holds its realm in plain fields with no mutual exclusion at all.
 */
class TombstoneDrainRealmGuardTest {

    private val pushedBlockIds = mutableListOf<String>()

    @Test
    fun aBlockStampedForASupersededRealmIsNotPushed() = runTest {
        val proxy = harness()
        val syncManager = proxy.second
        val foreignOwnerId = "owner-from-the-realm-this-device-left"
        assertTrue(
            syncManager.currentOwnerId != foreignOwnerId,
            "precondition: the queued block belongs to a realm that is not the current one",
        )

        proxy.first.executionContext.database.addToArray(
            tombstonesRef(proxy.first),
            DeleteBlock.create(
                context = proxy.first.tContext,
                blockId = "t-foreign",
                ownerId = foreignOwnerId,
                hlc = syncManager.hlcManager.tick(),
                targetEntityIdHash = "deadbeef",
                targetEntityType = "tenshu/file/system/file",
                revokedDeviceId = null,
            ),
        )

        proxy.third.scheduler.drainNow()

        assertEquals(
            emptyList(),
            pushedBlockIds,
            "pushing it authenticates as the current realm and earns a 403 the emitter can no " +
                "longer act on — its row is already gone",
        )
        assertEquals(
            listOf("t-foreign"),
            queuedBlockIds(proxy.first),
            "and it stays queued: only a device leaving that realm for good may discard it, which " +
                "is what purgeOutboundQueues is for",
        )
    }

    @Test
    fun aBlockStampedForTheCurrentRealmStillDrains() = runTest {
        val proxy = harness()
        val syncManager = proxy.second

        proxy.first.executionContext.database.addToArray(
            tombstonesRef(proxy.first),
            DeleteBlock.create(
                context = proxy.first.tContext,
                blockId = "t-current",
                ownerId = requireNotNull(syncManager.currentOwnerId),
                hlc = syncManager.hlcManager.tick(),
                targetEntityIdHash = "deadbeef",
                targetEntityType = "tenshu/file/system/file",
                revokedDeviceId = null,
            ),
        )

        proxy.third.scheduler.drainNow()

        assertEquals(
            listOf("t-current"),
            pushedBlockIds,
            "the guard must not cost the ordinary case — this is the whole traffic of the queue",
        )
        assertTrue(queuedBlockIds(proxy.first).isEmpty(), "and a pushed block leaves the queue")
    }

    // ── harness ──────────────────────────────────────────────────────────────────────────────

    private fun tombstonesRef(proxy: TestProxy) = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = proxy.tContext,
            type = DeleteBlock.ID,
            relationId = SyncService.Collections.PENDING_TOMBSTONES,
        ),
        isEntity = false,
        elementSchemaId = DeleteBlock.ID,
    )

    private fun queuedBlockIds(proxy: TestProxy): List<String> =
        proxy.executionContext.database.getArrayData(tombstonesRef(proxy))
            ?.filterIsInstance<DeleteBlock>()
            ?.map { it.blockId.get() }
            ?.toList()
            ?: emptyList()

    private val recordingPush = object : SyncOperationImplementation {
        @Suppress("UNCHECKED_CAST")
        override fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?,
        ): T {
            val block = params?.get(PushTombstone.Params.BLOCK) as DeleteBlock
            pushedBlockIds += block.blockId.get()
            return null as T
        }
    }

    private fun harness(): Triple<TestProxy, SyncManagerImpl, SyncProxyComponents> {
        pushedBlockIds.clear()
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        proxy.additionalImplementations[PushTombstone.ID] = recordingPush

        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext,
        )
        syncManager.initialize("mock phrase")

        val components = proxy.executionContext.initSyncService(
            syncManager,
            crypto,
            startBackgroundLoops = false,
            reclamationSources = ReclamationSources(),
            platformScheduler = NoOpPlatformScheduler,
        )
        return Triple(proxy, syncManager, components)
    }
}
