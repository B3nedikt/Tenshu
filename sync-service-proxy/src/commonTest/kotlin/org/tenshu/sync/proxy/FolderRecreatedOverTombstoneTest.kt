package org.tenshu.sync.proxy

import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.file.proxy.FolderMerger
import org.tenshu.file.proxy.initFileService
import org.tenshu.sync.DataBlockPayload
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.EnvelopeAuth
import org.tenshu.sync.common.SemanticMergerRegistry
import org.tenshu.sync.common.SubtreeReapOrdering
import org.tenshu.sync.common.TombstoneEmitter
import org.tenshu.sync.common.TombstoneEmitterHolder
import org.tenshu.sync.common.resolveListField
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * ⚠⚠ A folder deleted and re-created at the same path must not bring its **old** children back on a
 * peer (incident 2026-11-20: `rm -rf` + `rsync` of `Cats` / `Sintra` into the macOS File Provider
 * mount; the Mac showed 14 / 25 files, a phone that synced the same log showed 16 / 39).
 *
 * The deleting device cascades locally and emits **one** tombstone, for the folder (ADR 0006 #7); every
 * peer is meant to replay the cascade at the tombstone's HLC T. A peer that applies the tombstone
 * **after** the re-created folder's block — every block of a page is applied before its tombstones, and
 * a joiner's backfill reads newest first — finds the folder newer than T and correctly keeps it. Before
 * the fix it then did nothing else: the old children were never reaped, and `FolderMerger`'s union had
 * already listed them in the new folder.
 *
 * The rule pinned here is the one `SubtreeReapOrdering` states for a deleted folder, applied to a
 * superseded one: everything under the folder at or below T is reaped, everything newer is kept.
 */
class FolderRecreatedOverTombstoneTest {

    private companion object {
        const val FOLDER = "/Cats"
        const val OLD = "/Cats/20.jpg"
        const val NEW = "/Cats/new.jpg"
        const val OLD_SUB = "/Cats/old-sub"
        const val OLD_SUB_CHILD = "/Cats/old-sub/x.jpg"
        const val T = 500L
    }

    private val testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry()).apply {
        schemaRegistry.registerConfig(syncServiceCommonModule)
        schemaRegistry.registerConfig(fileServiceCommonModule)
    }
    private val tContext = testProxy.tContext
    private val crypto = tContext.syncCryptoManager()
    private val syncManager = SyncManagerImpl(
        context = tContext,
        cryptoManager = crypto,
        storageDelegate = InMemorySecureStorageDelegate(),
    ).apply { initialize("mock phrase") }
    private val keys = requireNotNull(syncManager.getDerivedKeys())
    private val serializer = BinarySerializer()

    private val bytes = InMemoryFileDatabase()

    private val registry = TombstoneRegistry(
        executionContext = testProxy.executionContext,
        wireIdFor = WireIdResolver(syncManager)::ofOrNull,
        localEventHlc = { syncManager.hlcManager.tick() },
    )
    private val holder = TombstoneEmitterHolder().also { holder ->
        testProxy.executionContext.addDataObserver(registry)
        holder.emitter = object : TombstoneEmitter {
            override val reapOrdering: SubtreeReapOrdering = registry
            override fun emit(targetEntityIdHash: String, targetEntityType: String, revokedDeviceId: String?): String? = null
            override fun emit(targetEntityId: DataId, targetEntityType: String, revokedDeviceId: String?): String? = null
        }
        testProxy.executionContext.initFileService(tombstoneEmitterHolder = holder)
    }
    private val processor = SyncProcessor(
        wireIdResolver = WireIdResolver(syncManager),
        executionContext = testProxy.executionContext,
        syncManager = syncManager,
        cryptoManager = crypto,
        tombstoneRegistry = registry,
        mergerRegistry = SemanticMergerRegistry().apply { register(FolderMerger(fileDatabase = bytes)) },
    )

    private fun id(path: String): DataId =
        DataId.StringDataId.create(context = tContext, type = File.ID, keyValue = path)

    private fun row(path: String) = testProxy.database.getData(id(path))

    private fun hlc(atMillis: Long) = HybridLogicalClock.create(tContext, atMillis, 0, "device-mac")

    private fun block(value: org.tenshu.core.Data, atMillis: Long, blockId: String): SyncBlock =
        EnvelopeAuth.sealSyncBlock(
            crypto = crypto,
            context = tContext,
            blockId = blockId,
            ownerId = "user-1",
            hlc = hlc(atMillis),
            targetEntityIdHash = null,
            plaintext = BlockPayloadEnvelope.seal(
                serializer.serialize(DataBlockPayload.create(context = tContext, propertyId = null, value = value)),
                testProxy.versionStampBytes(),
            ),
            identityKey = keys.sharedIdentityKey,
        )

    private fun ingestFile(path: String, atMillis: Long) =
        processor.processSyncBlock(block(File.create(tContext, path), atMillis, "file-$path-$atMillis"))

    private fun ingestFolder(path: String, children: List<String>, atMillis: Long) = processor.processSyncBlock(
        block(
            Folder.create(tContext, path, files = children.map { File.create(tContext, it) }),
            atMillis,
            "folder-$path-$atMillis",
        ),
    )

    private fun ingestTombstone(path: String, atMillis: Long) = processor.processDeleteBlock(
        EnvelopeAuth.tagTombstone(
            tContext,
            DeleteBlock.create(
                context = tContext,
                blockId = "delete-$path-$atMillis",
                ownerId = "user-1",
                hlc = hlc(atMillis),
                targetEntityIdHash = id(path).wireIdIn(syncManager),
                targetEntityType = File.ID,
            ),
            keys.sharedIdentityKey,
        ),
    )

    private fun childPaths(path: String): List<String> {
        val stored = assertNotNull(testProxy.database.getData(id(path), asEntity = true), "$path is present")
        return testProxy.executionContext
            .resolveListField(stored, Folder.Fields.FILES)
            .filterIsInstance<File>()
            .map { it.path.get() }
            .sorted()
    }

    /** The Mac's log before the delete: `/Cats` holding an old photo and an old subfolder with a child. */
    private fun oldTree() {
        ingestFolder(FOLDER, listOf(OLD, OLD_SUB), atMillis = 50L)
        ingestFile(OLD, atMillis = 100L)
        ingestFolder(OLD_SUB, listOf(OLD_SUB_CHILD), atMillis = 110L)
        ingestFile(OLD_SUB_CHILD, atMillis = 120L)
    }

    /** The re-create, written after the delete at T. */
    private fun newTree() {
        ingestFolder(FOLDER, listOf(NEW), atMillis = 900L)
        ingestFile(NEW, atMillis = 910L)
    }

    private fun assertOnlyTheNewTreeSurvives() {
        assertEquals(listOf(NEW), childPaths(FOLDER), "the re-created $FOLDER lists only what was written after T")
        assertNotNull(row(NEW), "$NEW (written after T) is kept")
        assertNull(row(OLD), "$OLD (written before T) is reaped")
        assertNull(row(OLD_SUB), "$OLD_SUB (written before T) is reaped")
        assertNull(row(OLD_SUB_CHILD), "and its child with it")

        // A stale block for the old content arriving later must not bring it back either.
        ingestFile(OLD, atMillis = 100L)
        ingestFolder(FOLDER, listOf(OLD, OLD_SUB), atMillis = 50L)
        assertNull(row(OLD), "a re-delivered block at or below T is refused")
        assertEquals(listOf(NEW), childPaths(FOLDER), "and a re-delivered old listing adds nothing")
    }

    @Test
    fun tombstoneAppliedAfterTheRecreate_reapsTheOldChildren() {
        // One page: every block first, then the tombstone (SyncScheduler applies a page that way).
        oldTree()
        newTree()
        // This peer had opened the old photo, and the new one: their plaintext is on disk.
        bytes.writeRawByteArray(OLD, byteArrayOf(1))
        bytes.writeRawByteArray(NEW, byteArrayOf(2))
        ingestTombstone(FOLDER, atMillis = T)

        assertNull(bytes.readBytes(OLD), "a reaped leaf's plaintext goes with it")
        assertNotNull(bytes.readBytes(NEW), "a kept one's stays")
        assertOnlyTheNewTreeSurvives()
    }

    @Test
    fun joinerReadingNewestFirst_reapsTheOldChildren() {
        // A joiner's backfill: the re-create's page arrives before the page holding the old tree.
        newTree()
        oldTree()
        ingestTombstone(FOLDER, atMillis = T)

        assertOnlyTheNewTreeSurvives()
    }

    @Test
    fun tombstoneAppliedBeforeTheRecreate_alreadyReapedTheOldChildren() {
        oldTree()
        ingestTombstone(FOLDER, atMillis = T)
        newTree()

        assertOnlyTheNewTreeSurvives()
    }

    @Test
    fun aRecreatedSubfolderKeepsItsNewChildrenAndLosesItsOldOnes() {
        // Both levels re-created: `/Cats/old-sub` exists again after T with a new child.
        oldTree()
        ingestFolder(FOLDER, listOf(NEW, OLD_SUB), atMillis = 900L)
        ingestFile(NEW, atMillis = 910L)
        ingestFolder(OLD_SUB, listOf("/Cats/old-sub/y.jpg"), atMillis = 920L)
        ingestFile("/Cats/old-sub/y.jpg", atMillis = 930L)
        ingestTombstone(FOLDER, atMillis = T)

        assertEquals(listOf(NEW, OLD_SUB), childPaths(FOLDER))
        assertEquals(listOf("/Cats/old-sub/y.jpg"), childPaths(OLD_SUB), "the re-created subfolder lists only its new child")
        assertNull(row(OLD), "$OLD is reaped")
        assertNull(row(OLD_SUB_CHILD), "$OLD_SUB_CHILD (written before T) is reaped under the kept subfolder")
        assertNotNull(row("/Cats/old-sub/y.jpg"))
    }

    @Test
    fun aNewMemberStillAPlaceholderWhenTheTombstoneLands_isKept() {
        // Newest first, with the re-created folder's listing in a page of its own: `NEW` is listed but its
        // own block has not arrived when the tombstone is applied. Reaping it would hide a file created
        // after the delete.
        ingestFolder(FOLDER, listOf(NEW), atMillis = 900L)
        ingestTombstone(FOLDER, atMillis = T)
        assertEquals(listOf(NEW), childPaths(FOLDER), "a placeholder member is kept")

        ingestFile(NEW, atMillis = 910L)
        assertNotNull(row(NEW))
        assertEquals(listOf(NEW), childPaths(FOLDER))
    }

    @Test
    fun anOldChildBlockArrivingAfterTheTombstone_isRefusedUnderTheRecreatedFolder() {
        // Newest first, split so the tombstone is applied before the old children's own blocks. The old
        // listing (150, older than the re-create) is dropped by the newer-wins gate; the old child's block
        // names no tombstone of its own, and `FolderFilesMembership` lists a saved File under its path's
        // folder — so only the folder's deletion point can refuse it.
        newTree()
        ingestFolder(FOLDER, listOf(OLD), atMillis = 150L)
        ingestTombstone(FOLDER, atMillis = T)
        ingestFile(OLD, atMillis = 100L)
        ingestFolder(OLD_SUB, listOf(OLD_SUB_CHILD), atMillis = 110L)
        ingestFile(OLD_SUB_CHILD, atMillis = 120L)

        assertNull(row(OLD), "a child block at or below the folder's T is refused")
        assertNull(row(OLD_SUB_CHILD), "and so is a grandchild's, through the reaped-at-T chain")
        assertEquals(listOf(NEW), childPaths(FOLDER), "so the re-created folder lists only its new member")

        ingestFile("/Cats/later.jpg", atMillis = 950L)
        assertEquals(listOf("/Cats/later.jpg", NEW), childPaths(FOLDER), "a child written after T is accepted")
    }
}
