package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.proxy.SliceSnapshotProducer
import org.tenshu.core.common.proxy.SliceSnapshotProducerRegistry
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.TimelineService
import org.tenshu.file.client.fileServiceClientModule
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.file.common.slice.FileSliceEntry
import org.tenshu.file.proxy.FileSliceSnapshotProducer
import org.tenshu.file.proxy.FolderMerger
import org.tenshu.file.proxy.fileServiceProxyImplementationFactory
import org.tenshu.file.proxy.initFileService
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.common.EnvelopeAuth
import org.tenshu.sync.common.RelationSliceSnapshot
import org.tenshu.sync.common.SemanticMergerRegistry
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.timeline.common.slice.MomentSliceEntry
import org.tenshu.timeline.common.timelineServiceCommonModule
import org.tenshu.timeline.proxy.MomentSliceSnapshotProducer
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A slice never brings back a row whose tombstone this device has already ingested
 * (`sync-device-catch-up.md` §11.4.4). The case: a joiner applies the root slice, the drain then
 * ingests a tombstone for one of its rows, and the app is relaunched before catch-up completes. The
 * reader runs again, the slice is the same, and the tombstone cursor is past the tombstone, so a
 * recreated row would stay for good. Real registry, real processor, real producers; "relaunch" is a
 * fresh reader and a fresh registry over the same store.
 */
class SliceResurrectionTest {

    private val testProxy = TestProxy(
        schemaRegistry = createSyntheticTestRegistry(),
        additionalComponents = mapOf(
            SliceSnapshotProducer.ID to SliceSnapshotProducerRegistry(
                listOf(FileSliceSnapshotProducer(), MomentSliceSnapshotProducer()),
            ),
        ),
    ) { id, proxy ->
        fileServiceProxyImplementationFactory(id = id, fileDatabase = proxy.fileDatabase)
    }.apply {
        schemaRegistry.registerConfig(syncServiceCommonModule)
        schemaRegistry.registerConfig(fileServiceCommonModule)
        schemaRegistry.registerConfig(fileServiceClientModule)
        schemaRegistry.registerConfig(timelineServiceCommonModule)
    }
    private val ctx = testProxy.executionContext
    private val tContext = testProxy.tContext
    private val crypto = tContext.syncCryptoManager()
    private val syncManager = SyncManagerImpl(
        context = tContext,
        cryptoManager = crypto,
        storageDelegate = InMemorySecureStorageDelegate(),
    ).apply { initialize("mock phrase") }
    private val keys = requireNotNull(syncManager.getDerivedKeys())
    private val store = InMemoryContentBlobStore(tContext)

    private fun registry() = TombstoneRegistry(
        executionContext = ctx,
        wireIdFor = WireIdResolver(syncManager)::ofOrNull,
        localEventHlc = { syncManager.hlcManager.tick() },
    )

    private val liveRegistry = registry()
    private val processor = SyncProcessor(
        wireIdResolver = WireIdResolver(syncManager),
        executionContext = ctx,
        syncManager = syncManager,
        cryptoManager = crypto,
        tombstoneRegistry = liveRegistry,
        mergerRegistry = SemanticMergerRegistry().apply { register(FolderMerger()) },
    )

    init {
        ctx.addDataObserver(liveRegistry)
        ctx.initFileService()
        // A joiner part-way through C2's backward walk — the only state a slice is applied in.
        syncManager.backfillCursor = 400
        syncManager.backfillTarget = 0
        syncManager.lastSyncTimestamp = 500
        syncManager.lastTombstoneSyncTimestamp = 500
    }

    /** One process's reader, deciding deletion by [registry] — a fresh one is a relaunch. */
    private fun reader(registry: TombstoneRegistry) = SliceSnapshotReader(
        executionContext = ctx,
        syncManager = syncManager,
        cryptoManager = crypto,
        contentBlobStore = store,
        isDeleted = { id -> registry.deletionPointOf(id) != null },
    )

    private fun publish(relationId: String, sliceKey: String, entryCount: Int, entries: ByteArray) {
        val realmId = assertNotNull(syncManager.currentRealmContext()).ownerId
        store.chunks[RelationSliceSnapshot.ref(realmId, relationId, sliceKey)] = crypto.encrypt(
            RelationSliceSnapshot.encode(RelationSliceSnapshot(300, relationId, sliceKey, entryCount, entries)),
            keys.sharedIdentityKey,
        )
    }

    private fun ingestTombstone(id: DataId) = processor.processDeleteBlock(
        EnvelopeAuth.tagTombstone(
            tContext,
            DeleteBlock.create(
                tContext, "t-1", "user-1",
                HybridLogicalClock.create(tContext, syncManager.hlcManager.current().physicalTime.get() + 1_000L, 0, "device-remote"),
                id.wireIdIn(syncManager), TombstoneWireType.NON_DEVICE, null,
            ),
            keys.sharedIdentityKey,
        ),
    )

    private fun fileId(path: String) = DataId.StringDataId.create(tContext, File.ID, path)

    private fun fileEntry(path: String) = FileSliceEntry(
        entityId = path, name = path.substringAfterLast('/'), isFolder = false, size = 3,
        contentId = "c-$path", contentHash = "h-$path", createdAtMillis = 1, lastModifiedAtMillis = 1,
    )

    @Test
    fun aDeletedFileStaysDeletedWhenTheRootSliceIsAppliedAgainAfterARelaunch() = runTest {
        publish(Folder.Fields.FILES, "/", 2, FileSliceEntry.encode(listOf(fileEntry("/x.txt"), fileEntry("/y.txt"))))

        reader(liveRegistry).bootstrapOnce()
        assertNotNull(ctx.database.getData(fileId("/x.txt")), "precondition: the slice listed /x.txt")
        ingestTombstone(fileId("/x.txt"))
        assertNull(ctx.database.getData(fileId("/x.txt")), "precondition: the tombstone deleted it")

        reader(registry()).bootstrapOnce() // the relaunch

        assertNull(ctx.database.getData(fileId("/x.txt")), "a slice must not bring back a deleted file")
        assertNotNull(ctx.database.getData(fileId("/y.txt")), "and the rest of the slice still applies")
    }

    @Test
    fun aDeletedMomentStaysDeletedWhenItsDaySliceIsAppliedAgainAfterARelaunch() = runTest {
        val noon = syncManager.hlcManager.current().physicalTime.get().coerceAtLeast(1_786_017_600_000L)
        val day = MomentSliceEntry.dayKey(noon)
        fun entry(id: String) = MomentSliceEntry(id, noon, Moment.MomentKind.VISIT, "Visit", "none", "", 60, "")
        val bytes = MomentSliceEntry.encode(listOf(entry("m-x"), entry("m-y")))
        fun momentId(id: String) = DataId.StringDataId.create(tContext, Moment.ID, id)
        val relation = TimelineService.Relations.MOMENTS

        val producer = MomentSliceSnapshotProducer()
        producer.applySlice(ctx, relation, day, bytes) { id -> liveRegistry.deletionPointOf(id) != null }
        assertNotNull(ctx.database.getData(momentId("m-x")), "precondition: the slice created m-x")
        ingestTombstone(momentId("m-x"))
        assertNull(ctx.database.getData(momentId("m-x")), "precondition: the tombstone deleted it")

        val relaunched = registry()
        producer.applySlice(ctx, relation, day, bytes) { id -> relaunched.deletionPointOf(id) != null }

        assertNull(ctx.database.getData(momentId("m-x")), "a slice must not bring back a deleted moment")
        assertNotNull(ctx.database.getData(momentId("m-y")))
    }

    @Test
    fun theProductionReaderDecidesDeletionByTheProcessorsRegistry() = runTest {
        // The wiring `SyncScheduler` builds, not a hand-made reader: the check handed to a producer
        // must read the registry the drain records tombstones into, or the guard above is inert.
        val handed = mutableListOf<(DataId) -> Boolean>()
        val capture = object : SliceSnapshotProducer {
            override val relationIds = setOf(Folder.Fields.FILES)
            override fun sliceKeys(executionContext: org.tenshu.core.common.proxy.ExecutionContext, relationId: String) = listOf("/")
            override fun bootstrapSliceKeys(executionContext: org.tenshu.core.common.proxy.ExecutionContext, relationId: String) = listOf("/")
            override fun encodeSlice(executionContext: org.tenshu.core.common.proxy.ExecutionContext, relationId: String, sliceKey: String) = null
            override fun applySlice(
                executionContext: org.tenshu.core.common.proxy.ExecutionContext,
                relationId: String,
                sliceKey: String,
                entries: ByteArray,
                isDeleted: (DataId) -> Boolean,
            ): Int {
                handed += isDeleted
                return 0
            }
        }
        lateinit var blobs: InMemoryContentBlobStore
        val f = CatchUpFixture.create(
            this,
            contentBlobStore = { InMemoryContentBlobStore(it).also { store -> blobs = store } },
            withSliceProducer = true,
            sliceProducer = capture,
        )
        f.syncManager.realmRelayRegistered = true
        val realm = assertNotNull(f.syncManager.currentRealmContext())
        blobs.chunks[RelationSliceSnapshot.ref(realm.ownerId, Folder.Fields.FILES, "/")] =
            f.proxy.tContext.syncCryptoManager().encrypt(
                RelationSliceSnapshot.encode(RelationSliceSnapshot(300, Folder.Fields.FILES, "/", 0, ByteArray(1))),
                realm.derivedKeys.sharedIdentityKey,
            )
        val deleted = DataId.StringDataId.create(f.proxy.tContext, File.ID, "/deleted.txt")
        val alive = DataId.StringDataId.create(f.proxy.tContext, File.ID, "/alive.txt")
        f.components.tombstoneRegistry.record(
            DeleteBlock.create(
                f.proxy.tContext, "t-1", "user-1", f.syncManager.hlcManager.tick(),
                deleted.wireIdIn(f.syncManager), TombstoneWireType.NON_DEVICE, null,
            ),
        )

        f.scheduler.tickOnce()

        val isDeleted = handed.single()
        kotlin.test.assertTrue(isDeleted(deleted), "a tombstoned row is reported deleted")
        kotlin.test.assertFalse(isDeleted(alive), "and a live one is not")
    }

    @Test
    fun aFolderDeletedAndRecreatedAtTheSamePathStillGetsItsSlice() = runTest {
        // Its tombstone record outlives the recreate, so the folder-level check must ask only about
        // a folder that is absent here — or a recreated folder never gets a slice again.
        val folder = "/again"
        val producer = FileSliceSnapshotProducer()
        producer.applySlice(ctx, Folder.Fields.FILES, folder, FileSliceEntry.encode(emptyList())) { false }
        ingestTombstone(fileId(folder))
        assertNull(ctx.database.getData(fileId(folder)), "precondition: the tombstone deleted it")
        producer.applySlice(ctx, Folder.Fields.FILES, folder, FileSliceEntry.encode(emptyList())) { false } // recreated
        assertNotNull(ctx.database.getData(fileId(folder)), "precondition: the folder exists again")

        val relaunched = registry()
        val created = producer.applySlice(ctx, Folder.Fields.FILES, folder, FileSliceEntry.encode(listOf(fileEntry("$folder/new.txt")))) { id ->
            relaunched.deletionPointOf(id) != null
        }

        kotlin.test.assertEquals(1, created)
        assertNotNull(ctx.database.getData(fileId("$folder/new.txt")), "the recreated folder's slice applies")
    }

    @Test
    fun aSliceOfADeletedFolderCreatesNothing() = runTest {
        // The folder itself deleted since the slice was sealed: recreating it would hang its whole
        // listing under a folder the log already removed.
        val folder = "/gone"
        val producer = FileSliceSnapshotProducer()
        producer.applySlice(ctx, Folder.Fields.FILES, folder, FileSliceEntry.encode(listOf(fileEntry("$folder/a.txt")))) { false }
        assertNotNull(ctx.database.getData(fileId(folder)), "precondition: the slice created the folder")
        ingestTombstone(fileId(folder))
        ctx.database.getData(fileId("$folder/a.txt"))?.let { ctx.deleteData(fileId("$folder/a.txt")) }

        val relaunched = registry()
        producer.applySlice(ctx, Folder.Fields.FILES, folder, FileSliceEntry.encode(listOf(fileEntry("$folder/a.txt")))) { id ->
            relaunched.deletionPointOf(id) != null
        }

        assertNull(ctx.database.getData(fileId(folder)))
        assertNull(ctx.database.getData(fileId("$folder/a.txt")))
    }
}
