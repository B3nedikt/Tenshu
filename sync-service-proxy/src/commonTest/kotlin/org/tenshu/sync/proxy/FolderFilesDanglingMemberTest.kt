package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.CompositeDatabaseReference
import org.tenshu.core.common.proxy.Database
import org.tenshu.core.common.proxy.db.DatabaseImpl
import org.tenshu.core.common.proxy.db.DbEntryMapper
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.TestDatabaseLock
import org.tenshu.core.collection.list.TList
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.core.trait.Trait
import org.tenshu.core.trait.TraitResolver
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.file.proxy.FolderMerger
import org.tenshu.file.proxy.initFileService
import org.tenshu.sync.DataBlockPayload
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.EnvelopeAuth
import org.tenshu.sync.common.SemanticMergerRegistry
import org.tenshu.sync.common.resolveListField
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A `Folder.FILES` list row must never reference a member row that is not in the store — the
 * defect measured on the desktop Control store 2026-09-13 (`drive-folder-files-dangling-member.md`):
 * one of 830 references in `/Backup/OnePlus CPH2581/WhatsApp Images`'s list row pointed at a hash
 * with no row, and the **nested** read of that list (`DbEntryMapper.deSerialize` with no `DataId`
 * in hand, which materialises the list strictly) threw `Entry not found`.
 *
 * ⚠⚠ "Nested inline list" and "top-level relation" are **one row** for `Folder.FILES`. The list a
 * `saveData(Folder(files = […]))` writes through `writeValuesToDb` lands at
 * `EntityFieldDataId(TList, <folder>, files)`, and that is exactly the hash `RelationMaintainer`
 * (`FolderFilesMembership`) targets with `addToArray` / `removeFromArray`. So the question the
 * incident raised — *does deleting a File maintain the nested list, or only top-level relations?* —
 * has one answer for both, and these tests pin it against every row-removal path a File row has:
 * an ingested tombstone (in the same process and after a restart, where the `DataId` comes back
 * from the durable `ResolvedDataId` row), and a local `ExecutionContext.deleteData` (what
 * `DeleteFile`'s cascade, `DeletedFolderSubtreeReaper` and `OrphanedFileSweeper` all call).
 *
 * The second half pins the re-seed side: a folder block whose members' own blocks never arrive, a
 * tombstone that lands before the block listing its target, and the newest-first walk applying a
 * re-create above an older tombstone. None of them may leave a reference without a row.
 *
 * ⚠ The oracle is deliberately two-fold. [Fixture.assertNoDanglingMember] checks the list row's
 * member hashes against the object store directly — the invariant itself — **and** performs the
 * cold strict read a fresh `DatabaseImpl` does (no `hashToDataId` warm-up ⇒ the list is
 * materialised through the strict `deserializeArray`), which is the read that failed in production.
 */
class FolderFilesDanglingMemberTest {

    // ---- 3(a): does deleting a File maintain the nested folder/files list? ----

    @Test
    fun anIngestedTombstoneUnlinksTheChildFromTheNestedFilesList() = runTest {
        val fixture = Fixture()
        fixture.saveFolderWithMaterialisedList("/bucket", listOf("/bucket/a", "/bucket/b", "/bucket/c"))
        fixture.ingestFileBlock("/bucket/b", atMillis = 1000L)
        fixture.assertMembers("/bucket", listOf("/bucket/a", "/bucket/b", "/bucket/c"), "fixture")

        fixture.ingestTombstoneFor("/bucket/b", atMillis = 2000L)

        assertNull(fixture.row("/bucket/b"), "The tombstone must delete the child's row")
        fixture.assertMembers("/bucket", listOf("/bucket/a", "/bucket/c"), "after the tombstone")
        fixture.assertNoDanglingMember("/bucket")
    }

    /**
     * The production path after Control restarts: the tombstone's target resolves through the
     * durable `ResolvedDataId` row, not the registry's in-memory cache. `RelationMaintainer`
     * derives the parent array from that resolved `DataId`'s path, so the `DataId` that comes
     * back from disk has to be the same shape the maintainer keys on.
     */
    @Test
    fun anIngestedTombstoneStillUnlinksTheChildAfterARestart() = runTest {
        val fixture = Fixture()
        fixture.saveFolderWithMaterialisedList("/bucket", listOf("/bucket/a", "/bucket/b"))
        fixture.ingestFileBlock("/bucket/b", atMillis = 1000L)

        // "Restart": a fresh processor over the same store starts with an empty reverse index.
        val restarted = fixture.newProcessor()
        fixture.ingestTombstoneFor("/bucket/b", atMillis = 2000L, via = restarted)

        assertNull(fixture.row("/bucket/b"), "The tombstone must delete the child's row after a restart")
        fixture.assertMembers("/bucket", listOf("/bucket/a"), "after the tombstone, post-restart")
        fixture.assertNoDanglingMember("/bucket")
    }

    @Test
    fun aLocalDeleteUnlinksTheChildFromTheNestedFilesList() = runTest {
        val fixture = Fixture()
        fixture.saveFolderWithMaterialisedList("/bucket", listOf("/bucket/a", "/bucket/b"))

        // What DeleteFile's cascade, DeletedFolderSubtreeReaper and OrphanedFileSweeper all do.
        fixture.testProxy.executionContext.deleteData(fixture.fileDataId("/bucket/b"))

        assertNull(fixture.row("/bucket/b"))
        fixture.assertMembers("/bucket", listOf("/bucket/a"), "after a local delete")
        fixture.assertNoDanglingMember("/bucket")
    }

    // ---- 3(b): can a re-seed apply a list row whose member rows never arrive? ----

    @Test
    fun aFolderBlockWhoseMembersNeverArriveCreatesEveryMemberRow() = runTest {
        val fixture = Fixture()

        // A peer's folder block, and nothing else — the members' own blocks are still below the
        // walk's floor, or lost. The create-only nested write must mint a row for each of them.
        fixture.ingestFolderListing("/bucket", listOf("/bucket/a", "/bucket/b"), atMillis = 1000L)

        assertNotNull(fixture.row("/bucket/a"), "A member learned only from its parent's block gets a row")
        assertNotNull(fixture.row("/bucket/b"))
        fixture.assertMembers("/bucket", listOf("/bucket/a", "/bucket/b"), "from the folder block alone")
        fixture.assertNoDanglingMember("/bucket")
    }

    @Test
    fun aTombstoneAppliedBeforeTheBlockListingItsTargetLeavesNoDanglingReference() = runTest {
        val fixture = Fixture()

        // Lane drift: the tombstone lands first (nothing to delete yet), then a folder block that
        // still lists the dead child. `purgeDeadMembers` drops it — and the list row must agree.
        fixture.ingestTombstoneFor("/bucket/b", atMillis = 2000L)
        fixture.ingestFolderListing("/bucket", listOf("/bucket/a", "/bucket/b"), atMillis = 3000L)

        assertNull(fixture.row("/bucket/b"), "A tombstoned, absent member must not be re-minted")
        fixture.assertMembers("/bucket", listOf("/bucket/a"), "after tombstone-then-block")
        fixture.assertNoDanglingMember("/bucket")
    }

    /**
     * The newest-first backfill (`sync-device-catch-up.md` C2) offers a re-create *before* the
     * older tombstone it superseded. The HLC guard in `processDeleteBlock` keeps the row, and the
     * list row must keep the reference — the opposite failure (dropping the reference while the
     * row survives) is a listing that hides a live file.
     */
    @Test
    fun aReCreateAppliedAboveAnOlderTombstoneKeepsBothRowAndReference() = runTest {
        val fixture = Fixture()
        fixture.ingestFileBlock("/bucket/b", atMillis = 5000L)
        fixture.ingestFolderListing("/bucket", listOf("/bucket/a", "/bucket/b"), atMillis = 4000L)

        fixture.ingestTombstoneFor("/bucket/b", atMillis = 3000L)

        assertNotNull(fixture.row("/bucket/b"), "An older tombstone must not delete a newer re-create")
        fixture.assertMembers("/bucket", listOf("/bucket/a", "/bucket/b"), "after the stale tombstone")
        fixture.assertNoDanglingMember("/bucket")
    }

    /**
     * A member this device learned of **only** through its parent's block has no `ResolvedDataId`
     * (the nested create-only write fires no observer), so its own tombstone resolves nothing and
     * deletes nothing. ⚠ That is a documented gap — the row lingers as a zombie, tracked in
     * `docs/tech-debt.md` — but it is the *safe* side of this invariant: a reference is only ever
     * left pointing at a row that exists.
     */
    @Test
    fun aTombstoneForAMemberKnownOnlyThroughItsParentLeavesNoDanglingReference() = runTest {
        val fixture = Fixture()
        fixture.ingestFolderListing("/bucket", listOf("/bucket/a", "/bucket/b"), atMillis = 1000L)

        fixture.ingestTombstoneFor("/bucket/b", atMillis = 2000L)

        fixture.assertNoDanglingMember("/bucket")
    }

    // ---- the store-order half of the invariant ----

    /**
     * Why process death cannot produce the shape: every writer enqueues a member's row **before**
     * the list row that names it — `addToArray` saves the value first, `writeValuesToDb`
     * serialises members before the list — and the SQLite store drains its mailbox FIFO in
     * atomic batches, so a kill loses a *suffix* of this order: a member without its membership,
     * never a membership without its member. This pins the writer half on the real writers;
     * `SqliteObjectStoreCommitFailureTest` pins the store half.
     */
    @Test
    fun everyWriterStoresTheMemberRowBeforeTheListRowThatNamesIt() = runTest {
        val fixture = Fixture()
        val listHash = fixture.filesListHash("/bucket")

        // The nested inline write, through real ingest: a peer's folder block whose list arrives
        // materialised (`resolveConflictAndSave` → `saveData` → `writeValuesToDb`).
        fixture.store.writes.clear()
        fixture.ingestFolderListing("/bucket", listOf("/bucket/a", "/bucket/b"), atMillis = 1000L)
        for (path in listOf("/bucket/a", "/bucket/b")) {
            fixture.assertStoredBefore(member = path, listHash = listHash, label = "folder block")
        }

        // The same write a local writer makes (CreateFolder, a merge output): direct `saveData`.
        fixture.store.writes.clear()
        fixture.saveFolderWithMaterialisedList("/bucket", listOf("/bucket/a", "/bucket/b", "/bucket/c"))
        fixture.assertStoredBefore(member = "/bucket/c", listHash = listHash, label = "local saveData")

        // The maintained write: a file's own block, hung on its parent by RelationMaintainer.
        fixture.store.writes.clear()
        fixture.ingestFileBlock("/bucket/d", atMillis = 2000L)
        fixture.assertStoredBefore(member = "/bucket/d", listHash = listHash, label = "addToArray")
    }

    /** An object store that records the order rows are stored in. */
    private class RecordingStore(
        private val delegate: InMemoryObjectStore = InMemoryObjectStore(),
    ) : org.tenshu.core.common.proxy.db.ObjectStore by delegate {
        val writes = mutableListOf<Long>()

        override fun storeObject(dbEntry: org.tenshu.core.common.proxy.db.ObjectStore.DbEntry) {
            writes += dbEntry.dataIdHash
            delegate.storeObject(dbEntry)
        }
    }

    private class Fixture {
        val store = RecordingStore()
        // ⚠ One lock for both views of [store]: the proxy's observers write from `Dispatchers.Default`
        // workers, and a second `DatabaseImpl` under its own lock would not exclude them.
        private val lock = TestDatabaseLock()
        val testProxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry(),
            objectStore = store,
            lock = lock,
        ).apply {
            schemaRegistry.registerConfig(syncServiceCommonModule)
            schemaRegistry.registerConfig(fileServiceCommonModule)
        }
        val tContext = testProxy.tContext
        private val crypto = tContext.syncCryptoManager()
        private val syncManager = SyncManagerImpl(
            context = tContext,
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
        ).apply { initialize("mock phrase") }
        private val keys = requireNotNull(syncManager.getDerivedKeys())
        private val serializer = BinarySerializer()

        /**
         * The observers a Control proxy installs for the file service — the root folder,
         * `DeletedFolderSubtreeReaper`, the history policy. ⚠ `RelationMaintainer` itself is NOT
         * one of them: `ClientRegistry.getExecutionContext` installs it on every context it mints,
         * before any service init runs, so it is present here with or without this call (verified
         * — the delete tests pass with this line removed). That is worth knowing for the incident
         * these tests are about: "the maintainer was not installed yet" is not a state a proxy
         * context can be in.
         */
        private val processor: SyncProcessor = newProcessor().also {
            testProxy.executionContext.initFileService()
        }

        fun newProcessor(): SyncProcessor = SyncProcessor(
            wireIdResolver = WireIdResolver(syncManager),
            executionContext = testProxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            mergerRegistry = SemanticMergerRegistry().apply { register(FolderMerger()) },
        )

        fun fileDataId(path: String): DataId = DataId.StringDataId.create(
            context = tContext,
            type = File.ID,
            keyValue = path,
        )

        fun row(path: String) = testProxy.database.getData(fileDataId(path))

        fun filesListHash(folderPath: String): Long = filesReference(folderPath).arrayId.getDataIdHash()

        /**
         * The member's first store precedes the list row's **last** store: the list may be
         * rewritten several times in one operation (the only-grow union, then the maintainer's
         * probe), and it is the final rewrite that must not run ahead of the row.
         */
        fun assertStoredBefore(member: String, listHash: Long, label: String) {
            val memberFirst = store.writes.indexOf(fileDataId(member).getDataIdHash())
            val listLast = store.writes.lastIndexOf(listHash)
            assertTrue(memberFirst >= 0, "$label: the member row $member was never stored")
            assertTrue(listLast >= 0, "$label: the list row was never stored")
            assertTrue(
                memberFirst < listLast,
                "$label: the member row of $member (index $memberFirst) must be stored before the list " +
                    "row that names it (index $listLast); a crash between them may strand a row, " +
                    "never a reference",
            )
        }

        private fun filesReference(folderPath: String) = ArrayDatabaseReference(
            arrayId = DataId.EntityFieldDataId.create(
                context = tContext,
                type = TList.ID,
                parentEntity = fileDataId(folderPath) as DataId.EntityDataId,
                fieldRef = Folder.Fields.FILES,
            ),
            isEntity = false,
            elementSchemaId = File.ID,
        )

        /** The nested-inline write: the folder's list arrives materialised, as off the wire. */
        fun saveFolderWithMaterialisedList(folderPath: String, childPaths: List<String>) {
            testProxy.executionContext.saveData(
                Folder.create(tContext, folderPath, files = childPaths.map { File.create(tContext, it) }),
            )
        }

        fun ingestFileBlock(path: String, atMillis: Long, via: SyncProcessor = processor) {
            via.processSyncBlock(block(File.create(tContext, path), atMillis, "file-$path-$atMillis"))
        }

        fun ingestFolderListing(
            folderPath: String,
            childPaths: List<String>,
            atMillis: Long,
            via: SyncProcessor = processor,
        ) {
            val folder = Folder.create(tContext, folderPath, files = childPaths.map { File.create(tContext, it) })
            via.processSyncBlock(block(folder, atMillis, "folder-$folderPath-$atMillis"))
        }

        fun ingestTombstoneFor(path: String, atMillis: Long, via: SyncProcessor = processor) {
            via.processDeleteBlock(
                EnvelopeAuth.tagTombstone(
                    tContext,
                    DeleteBlock.create(
                        context = tContext,
                        blockId = "delete-$path-$atMillis",
                        ownerId = "user-1",
                        hlc = HybridLogicalClock.create(tContext, atMillis, 0, "device-remote"),
                        targetEntityIdHash = fileDataId(path).wireIdIn(syncManager),
                        targetEntityType = File.ID,
                    ),
                    keys.sharedIdentityKey,
                )
            )
        }

        private fun block(value: org.tenshu.core.Data, atMillis: Long, blockId: String): SyncBlock {
            val payload = DataBlockPayload.create(context = tContext, propertyId = null, value = value)
            return EnvelopeAuth.sealSyncBlock(
                crypto = crypto,
                context = tContext,
                blockId = blockId,
                ownerId = "user-1",
                hlc = HybridLogicalClock.create(tContext, atMillis, 0, "device-remote"),
                targetEntityIdHash = null,
                plaintext = BlockPayloadEnvelope.seal(
                    serializer.serialize(payload),
                    testProxy.versionStampBytes(),
                ),
                identityKey = keys.sharedIdentityKey,
            )
        }

        fun assertMembers(folderPath: String, expected: List<String>, label: String) {
            val hashes = assertNotNull(
                testProxy.database.getArrayMemberHashes(filesReference(folderPath)),
                "$label: the folder's list row must exist",
            )
            assertEquals(
                expected.map { fileDataId(it).getDataIdHash() }.toSet(),
                hashes.toSet(),
                "$label: the list row's members",
            )
        }

        /**
         * The invariant, checked two ways: every member hash in the list row has a row in the
         * object store, and a **cold** strict read of the folder (a `DatabaseImpl` that has never
         * seen the folder's `DataId`, so it materialises the list rather than deferring it — the
         * exact read that threw in production) succeeds and resolves the same members.
         */
        fun assertNoDanglingMember(folderPath: String) {
            val hashes = testProxy.database.getArrayMemberHashes(filesReference(folderPath)).orEmpty()
            val dangling = hashes.filter { store.getObject(it) == null }
            assertTrue(
                dangling.isEmpty(),
                "The list row of $folderPath references ${dangling.size} member(s) with no row: $dangling",
            )

            val folderHash = fileDataId(folderPath).getDataIdHash()
            val cold = coldDatabase().getComposite(
                CompositeDatabaseReference(dataIdHash = folderHash, schema = Folder.Schema, isEntity = false),
            )
            val folder = assertNotNull(cold as? Folder, "cold strict read of $folderPath")
            val paths = testProxy.executionContext
                .resolveListField(folder, Folder.Fields.FILES)
                .filterIsInstance<File>()
                .map { it.path.get() }
                .sorted()
            assertEquals(
                hashes.size,
                paths.size,
                "The cold strict read must resolve every member the list row holds: $paths",
            )
        }

        /**
         * A `DatabaseImpl` with an empty `hashToDataId`, so a nested list is **materialised** rather
         * than deferred as an `ArrayDatabaseReference` — the read production performed. Whether that
         * read throws on a dangling member (master) or skips it (the reader-side tolerance) does not
         * matter to the oracle: [assertNoDanglingMember] compares the resolved count against the
         * list row's member hashes, so a skip is caught as a short read.
         */
        private fun coldDatabase(): Database {
            val traits = object : TraitResolver {
                override fun <T : Trait> getTrait(typeId: String, traitId: String): T? =
                    testProxy.executionContext.traitResolver.getTrait(typeId, traitId)
            }
            return DatabaseImpl(
                context = tContext,
                objectStore = store,
                dbEntryMapper = DbEntryMapper(
                    context = tContext,
                    schemaProvider = testProxy.schemaRegistry,
                    dataFactory = testProxy.schemaRegistry,
                    traitResolver = traits,
                ),
                traitResolver = traits,
                lock = lock,
            )
        }
    }
}
