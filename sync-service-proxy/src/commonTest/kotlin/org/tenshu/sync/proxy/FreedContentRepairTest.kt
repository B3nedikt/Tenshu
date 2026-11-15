package org.tenshu.sync.proxy

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.ExternalPropertyWriter
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.ContentId
import org.tenshu.core.health.ContentId
import org.tenshu.core.file.File
import org.tenshu.core.file.notifications.ChangeLostNotification
import org.tenshu.core.health.Workout
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.file.proxy.FileBytesPropertyWriter
import org.tenshu.health.common.healthServiceCommonModule
import org.tenshu.health.proxy.WorkoutStreamsPropertyWriter
import org.tenshu.health.proxy.workoutStreamsPath
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.PushBlock
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ⭐ CR-2 B4c §2.2 (`content-plane-cr2-b4-spec.md`): a push the relay refuses as naming **freed** content.
 *
 * - repairable (the row's Primary content, plaintext here) → re-upload under a fresh CEK, persist the
 *   refs as the block's revive **before** retrying, retry with the revive, retire on 200;
 * - not repairable → the row is deleted with no outbound tombstone, every queued block for the holder
 *   naming the id is dropped, and one `ChangeLostNotification` is raised.
 *
 * Driven through `initSyncService`, the real observer, drain and `PushBlock`, over [FreeingSyncRelay]
 * (a relay fake answering B4's 409s) and the real external-property writers for `File` and `Workout`.
 */
class FreedContentRepairTest {

    private val bytes = ByteArray(6_000) { (it * 7 and 0xFF).toByte() }

    /** The two writers production composes, so the repair finds bytes the way production does. */
    private class Writers(private vararg val all: ExternalPropertyWriter) : ExternalPropertyWriter {
        private fun of(propertyId: String) = all.firstOrNull { it.supports(propertyId) }
        override fun supports(propertyId: String) = of(propertyId) != null
        override fun write(executionContext: ExecutionContext, targetEntityId: DataId, propertyId: String, value: Data) {
            of(propertyId)?.write(executionContext, targetEntityId, propertyId, value)
        }
        override fun resolveExternalPath(executionContext: ExecutionContext, targetEntityId: DataId, propertyId: String) =
            of(propertyId)?.resolveExternalPath(executionContext, targetEntityId, propertyId)
        override fun resolveContentId(executionContext: ExecutionContext, targetEntityId: DataId, propertyId: String) =
            of(propertyId)?.resolveContentId(executionContext, targetEntityId, propertyId)
        override fun resolveContentHash(executionContext: ExecutionContext, targetEntityId: DataId, propertyId: String) =
            of(propertyId)?.resolveContentHash(executionContext, targetEntityId, propertyId)
    }

    private class Rig(
        val proxy: TestProxy,
        val relay: FreeingSyncRelay,
        val store: InMemoryContentBlobStore,
        val files: InMemoryFileDatabase,
        val components: SyncProxyComponents,
        val notices: List<ChangeLostNotification>,
    ) {
        val ec get() = proxy.executionContext

        fun queue(relation: String = SyncService.Collections.PENDING_BLOCKS, type: String = SyncBlock.ID) =
            ArrayDatabaseReference(
                arrayId = DataId.RelationDataId.create(context = proxy.tContext, type = type, relationId = relation),
                isEntity = false,
                elementSchemaId = type,
            )

        fun pendingBlocks(): List<SyncBlock> =
            ec.database.getArrayData(queue())?.toList()?.filterIsInstance<SyncBlock>().orEmpty()

        fun pendingTombstones(): List<DeleteBlock> =
            ec.database.getArrayData(queue(SyncService.Collections.PENDING_TOMBSTONES, DeleteBlock.ID))
                ?.toList()?.filterIsInstance<DeleteBlock>().orEmpty()

        /** The queued block whose edges name [contentId]. */
        fun blockNaming(contentId: String): SyncBlock = pendingBlocks().single { block ->
            PendingBlockEdgeStore(ec).edgesOf(block.blockId.get()).any { it.contentId == contentId }
        }
    }

    private fun TestScope.rig(): Rig {
        val files = InMemoryFileDatabase()
        val proxy = TestProxy(
            coroutineScope = backgroundScope,
            schemaRegistry = createSyntheticTestRegistry().apply {
                registerConfig(syncServiceCommonModule)
                registerConfig(fileServiceCommonModule)
                registerConfig(healthServiceCommonModule)
            },
            additionalComponents = mapOf(
                ExternalPropertyWriter.ID to Writers(FileBytesPropertyWriter(files), WorkoutStreamsPropertyWriter(files)),
            ),
        )
        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext,
        ).apply { initialize("mock phrase") }
        val store = InMemoryContentBlobStore(proxy.tContext)
        val relay = FreeingSyncRelay(proxy.tContext, store = store)
        proxy.additionalImplementations[PushBlock.ID] =
            requireNotNull(syncProxyImplementationFactory(PushBlock.ID, relay, syncManager, crypto))
        val notices = mutableListOf<ChangeLostNotification>()
        proxy.executionContext.addDataObserver(object : DataObserver {
            override fun onDataSaved(data: Data, origin: WriteOrigin) {
                if (data is ChangeLostNotification) notices += data
            }
            override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) = Unit
        })
        val components = proxy.executionContext.initSyncService(
            syncManager,
            crypto,
            startBackgroundLoops = false,
            contentBlobStore = store,
            fileDatabase = files,
            platformScheduler = NoOpPlatformScheduler,
        )
        return Rig(proxy, relay, store, files, components, notices)
    }

    private suspend fun Rig.saveFile(path: String, contentId: String): DataId {
        val composite = File.create(proxy.tContext, path)
        ec.saveData(Entity.create<File>(context = proxy.tContext, composite = composite) { set(File.ContentId, contentId) })
        components.syncObserver.awaitEmitIdle()
        return assertNotNull(ec.getDataId(composite))
    }

    @Test
    fun aRefusedFilePush_reuploads_persistsTheRevive_retriesWithIt_andRetires() = runTest {
        val r = rig()
        r.files.writeRawByteArray("/a.txt", bytes)
        r.saveFile("/a.txt", "cid-a")
        val blockId = r.blockNaming("cid-a").blockId.get()
        r.relay.freed += "cid-a"

        r.components.scheduler.drainNow()

        val attempts = r.relay.attempts.filter { it.first == blockId }
        assertEquals(2, attempts.size, "refused once, then retried at once: $attempts")
        assertTrue(attempts[0].second.isEmpty(), "the first push carries no revive")
        val revive = assertNotNull(r.relay.revivesAccepted[blockId]).single()
        assertEquals("cid-a", revive.contentId)
        assertTrue(manifestContentRef("cid-a") in revive.refs, "the manifest is a ref of the revive: ${revive.refs}")
        assertTrue(revive.refs.size >= 2, "chunks + manifest: ${revive.refs}")
        assertTrue(revive.refs.all { it in r.store.chunks }, "every revived ref is on the relay")
        assertTrue(r.store.describedContents.none { it.contentId == "cid-a" }, "a revive sends no describe")
        assertTrue(r.pendingBlocks().none { it.blockId.get() == blockId }, "accepted, so retired")
        assertTrue(PendingBlockReviveStore(r.ec).reviveOf(blockId).isEmpty(), "the revive went with its block")
        assertTrue(r.relay.freed.isEmpty())
        assertTrue(r.notices.isEmpty(), "a repair is not a loss")
    }

    @Test
    fun aCrashBetweenTheUploadAndTheRetry_doesNotUploadAgain() = runTest {
        // ⚠⚠ Each re-upload mints a fresh CEK, so a second one orphans the first's chunks (G4). The revive
        // is persisted before the retry: the next tick sends those refs instead of uploading again.
        val r = rig()
        r.files.writeRawByteArray("/a.txt", bytes)
        r.saveFile("/a.txt", "cid-a")
        val blockId = r.blockNaming("cid-a").blockId.get()
        r.relay.freed += "cid-a"
        r.relay.failNextRevivePushes = 1

        r.components.scheduler.drainNow()

        val persisted = PendingBlockReviveStore(r.ec).reviveOf(blockId)
        assertEquals("cid-a", persisted.single().contentId, "the revive is durable before the retry")
        assertTrue(r.pendingBlocks().any { it.blockId.get() == blockId }, "the failed retry left it queued")
        val puts = r.store.putCount

        r.components.scheduler.drainNow()

        assertEquals(puts, r.store.putCount, "no second upload")
        assertEquals(persisted, r.relay.revivesAccepted[blockId], "the persisted refs were sent")
        assertTrue(r.pendingBlocks().none { it.blockId.get() == blockId })
    }

    @Test
    fun workoutStreams_areRevivedTheSameWay_throughWorkoutStreamsPath() = runTest {
        val r = rig()
        r.files.writeRawByteArray(workoutStreamsPath("w-1"), bytes)
        val composite = Workout.create(
            context = r.proxy.tContext,
            workoutId = "w-1",
            activityType = "run",
            source = "test",
            start = UnixInstantDto(1_000L),
            end = UnixInstantDto(2_000L),
            durationSec = 1_000L,
        )
        r.ec.saveData(Entity.create<Workout>(context = r.proxy.tContext, composite = composite) { set(Workout.ContentId, "cid-w") })
        r.components.syncObserver.awaitEmitIdle()
        val blockId = r.blockNaming("cid-w").blockId.get()
        r.relay.freed += "cid-w"

        r.components.scheduler.drainNow()

        val revive = assertNotNull(r.relay.revivesAccepted[blockId], "Workout.Streams is a repairable Primary").single()
        assertEquals("cid-w", revive.contentId)
        assertTrue(manifestContentRef("cid-w") in revive.refs)
        assertTrue(r.pendingBlocks().none { it.blockId.get() == blockId })
    }

    @Test
    fun notRepairable_retiresTheRow_dropsTheHoldersBlocks_andRaisesOneNotice() = runTest {
        // No plaintext on this device: §9 Q2's retire.
        val r = rig()
        val holder = r.saveFile("/gone.txt", "cid-g")
        // A second queued block for the same holder naming the same content, and another holder's block.
        r.ec.saveData(r.ec.database.getData(holder, asEntity = true)!!)
        r.files.writeRawByteArray("/other.txt", bytes)
        r.saveFile("/other.txt", "cid-o")
        r.components.syncObserver.awaitEmitIdle()
        val holderBlocks = r.pendingBlocks().filter { block ->
            PendingBlockEdgeStore(r.ec).edgesOf(block.blockId.get()).any { it.contentId == "cid-g" }
        }
        assertEquals(2, holderBlocks.size, "precondition: two queued blocks name the freed content")
        r.relay.freed += "cid-g"

        r.components.scheduler.drainNow()

        assertNull(r.ec.database.getData(holder), "the row is retired here")
        assertTrue(r.pendingTombstones().isEmpty(), "SYNC_INGEST: no outbound tombstone")
        val holderIds = holderBlocks.map { it.blockId.get() }.toSet()
        assertTrue(r.pendingBlocks().none { it.blockId.get() in holderIds }, "every block naming it is dropped")
        assertTrue(holderIds.none { PendingBlockEdgeStore(r.ec).edgesOf(it).isNotEmpty() }, "with their sidecars")
        assertTrue(r.relay.inner.pushedBlocks().none { it.blockId.get() in holderIds }, "none reached the log")
        assertTrue(
            r.relay.inner.pushedBlocks().any { b -> r.relay.inner.pushedEdgesOf(b.blockId.get()).orEmpty().any { it.contentId == "cid-o" } },
            "the other holder's block is unaffected",
        )
        val notice = r.notices.single()
        assertEquals("gone.txt", notice.fileName.get())
        assertEquals("/gone.txt", notice.path.get())
    }

    @Test
    fun reviveIncomplete_dropsTheRevive_andReuploadsOnTheNextTick() = runTest {
        val r = rig()
        r.files.writeRawByteArray("/a.txt", bytes)
        r.saveFile("/a.txt", "cid-a")
        val blockId = r.blockNaming("cid-a").blockId.get()
        r.relay.freed += "cid-a"
        r.relay.forceReviveIncomplete = 1

        r.components.scheduler.drainNow()

        assertTrue(PendingBlockReviveStore(r.ec).reviveOf(blockId).isEmpty(), "the incomplete revive is dropped")
        assertTrue(r.pendingBlocks().any { it.blockId.get() == blockId }, "held, not retired")
        val puts = r.store.putCount

        r.components.scheduler.drainNow()

        assertTrue(r.store.putCount > puts, "a fresh re-upload")
        assertNotNull(r.relay.revivesAccepted[blockId])
        assertTrue(r.pendingBlocks().none { it.blockId.get() == blockId })
    }

    @Test
    fun aReviveThatCannotUploadNow_keepsTheBlockQueued_andTheRow() = runTest {
        // ⚠ The bytes exist here, so a closed gate or a failed upload is a wait, never a retire.
        val r = rig()
        r.files.writeRawByteArray("/a.txt", bytes)
        val holder = r.saveFile("/a.txt", "cid-a")
        val blockId = r.blockNaming("cid-a").blockId.get()
        r.relay.freed += "cid-a"
        r.store.failPuts = true

        r.components.scheduler.drainNow()

        assertTrue(r.pendingBlocks().any { it.blockId.get() == blockId }, "still queued")
        assertNotNull(r.ec.database.getData(holder), "the row stays")
        assertTrue(r.notices.isEmpty())

        r.store.failPuts = false
        r.components.scheduler.drainNow()

        assertNotNull(r.relay.revivesAccepted[blockId], "revived once the upload could land")
    }

    @Test
    fun aDescribeRefusedAsFreed_isDropped_withItsUploadJournalEntry() = runTest {
        // §2.2 step 4: content freed while PENDING. Re-describing would be refused for ever.
        val r = rig()
        r.files.writeRawByteArray("/a.txt", bytes)
        val holder = r.saveFile("/a.txt", "cid-a")
        r.store.freedContentIds += "cid-a"

        assertTrue(r.components.syncObserver.emitFileContent(holder, REPAIRABLE_PROPERTY_IDS.first(), "/a.txt"), "the emit completes")

        assertTrue(r.store.describedContents.none { it.contentId == "cid-a" })
        assertEquals(0, r.components.syncObserver.deferredContentFigures().count, "no journal entry is left to retry")
    }
}
