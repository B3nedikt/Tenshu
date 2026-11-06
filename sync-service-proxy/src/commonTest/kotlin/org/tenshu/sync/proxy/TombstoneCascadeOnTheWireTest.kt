package org.tenshu.sync.proxy

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TResult
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.schema.currentVersionVector
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.PushTombstone
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.DeviceSchemaVersion
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ⭐ A tombstone carries its subtree on the wire (`content-plane-cr2-b3-wire-spec.md` §3.2).
 *
 * The drain pushes each tombstone through the real `PushTombstone` implementation, which reads the
 * cascade persisted with the pending tombstone and sends it as the `CascadedTombstonePush`'s
 * `cascade`. The relay removes the whole subtree's edges in that push's transaction, so the drain
 * makes no separate undeclare call.
 *
 * The failure directions are asymmetric: a cascade lost before its push leaves edges standing (a
 * leak), so every assertion here is about the cascade reaching the push it belongs to, and surviving
 * until it has.
 */
class TombstoneCascadeOnTheWireTest {

    /** A [MockSyncRelay] whose tombstone pushes fail for [failing] block ids. */
    private class Relay(
        private val inner: MockSyncRelay,
        private val context: TContext,
        val failing: MutableSet<String>,
    ) : SyncRelay by inner {
        val cascadesPushed = mutableMapOf<String, List<String>>()

        override suspend fun pushTombstone(block: DeleteBlock, cascade: List<String>): TResult<Data?> {
            if (block.blockId.get() in failing) {
                return TResult.Failure.create(context, GenericError.create(context, "relay refused"))
            }
            cascadesPushed[block.blockId.get()] = cascade
            return inner.pushTombstone(block, cascade)
        }
    }

    private fun tombstonesRef(proxy: TestProxy) = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = proxy.tContext,
            type = DeleteBlock.ID,
            relationId = SyncService.Collections.PENDING_TOMBSTONES,
        ),
        isEntity = false,
        elementSchemaId = DeleteBlock.ID,
    )

    private class Harness(
        val proxy: TestProxy,
        val syncManager: SyncManagerImpl,
        val relay: Relay,
        val components: SyncProxyComponents,
    ) {
        fun fileAt(path: String) = DataId.StringDataId.create(proxy.tContext, "tenshu/file/system/file", path)
        val resolver = WireIdResolver(syncManager)
        fun nameOf(path: String) = resolver.of(fileAt(path))

        fun queueTombstone(blockId: String, holderHash: String) {
            proxy.executionContext.database.addToArray(
                ArrayDatabaseReference(
                    arrayId = DataId.RelationDataId.create(
                        context = proxy.tContext,
                        type = DeleteBlock.ID,
                        relationId = SyncService.Collections.PENDING_TOMBSTONES,
                    ),
                    isEntity = false,
                    elementSchemaId = DeleteBlock.ID,
                ),
                DeleteBlock.create(
                    context = proxy.tContext,
                    blockId = blockId,
                    ownerId = requireNotNull(syncManager.currentOwnerId),
                    hlc = syncManager.hlcManager.tick(),
                    targetEntityIdHash = holderHash,
                    targetEntityType = "tenshu/file/system/file",
                    revokedDeviceId = null,
                ),
            )
        }

        /** What B3b's store still holds for [blockId] — read fresh, as a restarted process would. */
        fun cascadeLeft(blockId: String) =
            TombstoneCascades(DatabaseTombstoneCascadeStore(proxy.executionContext)).holdersOf(blockId)
    }

    private fun harness(failing: Set<String> = emptySet()): Harness {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
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
        val relay = Relay(MockSyncRelay(proxy.tContext), proxy.tContext, failing.toMutableSet())
        // ⚠ The REAL `PushTombstone`: what it reads from the store is the subject of these tests.
        proxy.additionalImplementations[PushTombstone.ID] =
            requireNotNull(syncProxyImplementationFactory(PushTombstone.ID, relay, syncManager, crypto))
        val components = proxy.executionContext.initSyncService(syncManager, crypto, startBackgroundLoops = false, platformScheduler = NoOpPlatformScheduler)
        return Harness(proxy, syncManager, relay, components)
    }

    @Test
    fun aPushedFolderTombstoneCarriesEveryEntityItsDeleteRemoved() = runTest {
        // ⚠⚠ ADR 0006 sends one tombstone for a folder delete, and the relay removes edges by
        // tombstone, so without the cascade every file inside kept its edge and nothing was ever
        // reclaimable (§9.14 — 929 files measured, zero candidates offered).
        val h = harness()
        val folder = h.fileAt("/f")
        val removed = listOf(h.fileAt("/f/a.jpg"), h.fileAt("/f/sub"), h.fileAt("/f/sub/b.jpg"))

        val blockId = requireNotNull(h.components.tombstoneEmitter.emitWithCascade(folder, "tenshu/file/system/file", removed))
        h.components.scheduler.drainNow()

        assertEquals(
            listOf(h.nameOf("/f/a.jpg"), h.nameOf("/f/sub"), h.nameOf("/f/sub/b.jpg")),
            h.relay.cascadesPushed[blockId],
        )
        assertTrue(h.cascadeLeft(blockId).isEmpty(), "a cascade that went out with its push is forgotten")
    }

    @Test
    fun aPlainTombstoneCarriesAnEmptyCascade() = runTest {
        val h = harness()
        val blockId = requireNotNull(h.components.tombstoneEmitter.emit(h.fileAt("/a.jpg"), "tenshu/file/system/file"))
        h.components.scheduler.drainNow()

        assertEquals(emptyList(), h.relay.cascadesPushed[blockId])
    }

    @Test
    fun aFailedPushKeepsItsCascade_andTheRetryCarriesIt() = runTest {
        // ⚠⚠ The tombstone stays queued; its cascade must stay with it, or the retried push names no
        // subtree and every descendant's edge stands for ever.
        val h = harness(failing = setOf("t-folder"))
        h.queueTombstone("t-folder", "folder-holder")
        h.components.tombstoneCascades.record("t-folder", listOf(h.nameOf("/f/a.jpg")))

        h.components.scheduler.drainNow()
        assertNull(h.relay.cascadesPushed["t-folder"], "precondition: the push was refused")
        assertEquals(listOf(h.nameOf("/f/a.jpg")), h.cascadeLeft("t-folder"), "kept for the retried push")

        h.relay.failing.clear()
        h.components.scheduler.drainNow()

        assertEquals(listOf(h.nameOf("/f/a.jpg")), h.relay.cascadesPushed["t-folder"])
        assertTrue(h.cascadeLeft("t-folder").isEmpty())
    }

    @Test
    fun aCascadeRecordedBeforeARestartReachesTheWireAfterIt() = runTest {
        // ⭐ B3b's store, end to end (audit W5): the cascade is written by one process and read by the
        // push of another. Nothing in memory carries it across — `PushTombstone` reads the store.
        val h = harness()
        h.queueTombstone("t-folder", "folder-holder")
        // "Before the restart": a separate instance, as the process that deleted the folder held.
        TombstoneCascades(DatabaseTombstoneCascadeStore(h.proxy.executionContext))
            .record("t-folder", listOf(h.nameOf("/f/a.jpg"), h.nameOf("/f/b.jpg")))

        // "After the restart": this harness's drain, which has never seen that instance.
        h.components.scheduler.drainNow()

        assertEquals(listOf(h.nameOf("/f/a.jpg"), h.nameOf("/f/b.jpg")), h.relay.cascadesPushed["t-folder"])
    }

    @Test
    fun aSecondSessionsFolderDeleteRidesTheSharedDrain() = runTest {
        // ⚠⚠ Every connected app gets its own observer and all of them share one scheduler
        // (SyncRuntimeHolder). A cascade kept on an observer reached the drain only for the first
        // session; any other app's folder delete pushed the folder alone.
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry(), coroutineScope = backgroundScope)
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext,
        )
        syncManager.initialize("mock phrase")
        val relay = Relay(MockSyncRelay(proxy.tContext), proxy.tContext, mutableSetOf())
        proxy.additionalImplementations[PushTombstone.ID] =
            requireNotNull(syncProxyImplementationFactory(PushTombstone.ID, relay, syncManager, crypto))
        val holder = SyncRuntimeHolder()
        fun session() = proxy.executionContext.initSyncService(
            syncManager, crypto, startBackgroundLoops = false, runtimeHolder = holder,
            platformScheduler = NoOpPlatformScheduler,
        )
        val first = session()
        val second = session()
        try {
            fun fileAt(path: String) = DataId.StringDataId.create(proxy.tContext, "tenshu/file/system/file", path)

            val blockId = second.tombstoneEmitter.emitWithCascade(fileAt("/f"), "tenshu/file/system/file", listOf(fileAt("/f/a.jpg")))
            first.scheduler.drainNow()

            assertEquals(listOf(WireIdResolver(syncManager).of(fileAt("/f/a.jpg"))), relay.cascadesPushed[blockId])
        } finally {
            first.scheduler.stop()
            first.scheduleDrainLoop.stop()
        }
    }

    @Test
    fun aCascadeIsRecordedBeforeItsTombstoneCanBePushed() = runTest {
        // ⚠⚠ `emit` queues the block and wakes the drain. A cascade recorded after that could lose
        // the race to a drain that pushes the block first, which would then carry no subtree.
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext,
        ).apply { initialize("mock phrase") }
        val queuedAtRecord = mutableListOf<Boolean>()
        val probe = object : TombstoneCascades(DatabaseTombstoneCascadeStore(proxy.executionContext)) {
            override fun record(blockId: String, holderNames: List<String>) {
                val queued = proxy.executionContext.database.getArrayData(tombstonesRef(proxy))
                    .orEmpty().filterIsInstance<DeleteBlock>().any { it.blockId.get() == blockId }
                queuedAtRecord += queued
                super.record(blockId, holderNames)
            }
        }
        val observer = SyncObserver(
            executionContext = proxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            tombstoneRegistry = TombstoneRegistry(proxy.executionContext, wireIdFor = localTestNaming),
            tombstoneCascades = probe,
        )
        fun fileAt(path: String) = DataId.StringDataId.create(proxy.tContext, "tenshu/file/system/file", path)

        val blockId = observer.emitWithCascade(fileAt("/f"), "tenshu/file/system/file", listOf(fileAt("/f/a.jpg")))

        assertEquals(listOf(false), queuedAtRecord, "the cascade must exist before its block is pushable")
        assertEquals(1, probe.holdersOf(requireNotNull(blockId)).size)
    }

    @Test
    fun twoSessionsOfOneRuntimeContendForOneEmitKey() = runTest {
        // ⚠⚠ Each session gets its own observer, so an ownership table on the observer would let an
        // overwrite through one app never contend with an emit through another. Held by session
        // one, the key must make session two WAIT.
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry(), coroutineScope = backgroundScope)
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext,
        ).apply { initialize("mock phrase") }
        val holder = SyncRuntimeHolder()
        fun session() = proxy.executionContext.initSyncService(
            syncManager, crypto, startBackgroundLoops = false, runtimeHolder = holder,
            platformScheduler = NoOpPlatformScheduler,
        )
        val first = session()
        val second = session()
        try {
            val file = DataId.StringDataId.create(proxy.tContext, "tenshu/file/system/file", "/a.bin")
            var secondEntered = false
            first.syncObserver.withContentEmitOwnership(file, "p") {
                val waiter = this@runTest.async {
                    second.syncObserver.withContentEmitOwnership(file, "p") { secondEntered = true }
                }
                repeat(5) { kotlinx.coroutines.yield() }
                assertTrue(!secondEntered, "the other session must wait while this one owns the key")
                waiter
            }.await()
            assertTrue(secondEntered, "and proceed once it is released")
        } finally {
            first.scheduler.stop()
            first.scheduleDrainLoop.stop()
        }
    }
}
