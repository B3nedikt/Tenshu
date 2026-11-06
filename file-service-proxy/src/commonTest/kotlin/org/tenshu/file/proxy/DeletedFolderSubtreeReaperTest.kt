package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.collection.list.TList
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.file.test.createFileTestRegistry
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A folder delete carries **one** tombstone, for the delete's root — `DeleteFile` cascades
 * to the children only in the originating device's own store
 * ([DeleteFileTombstoneTest.deleteFile_emitsTombstoneForTargetEntity] pins that single
 * emission). So the receiving side of a folder delete is *not* exercised by deleting
 * through the op: it is `SyncProcessor.processDeleteBlock` deleting the folder row and
 * nothing else, which is what this test reproduces.
 *
 * Without `DeletedFolderSubtreeReaper` every descendant entity — and every descendant's
 * plaintext — survives on the peer, unreachable and permanent. For a deleted
 * Synced-Folders backup destination that is an entire photo bucket kept forever on a
 * device whose user deleted it.
 */
class DeletedFolderSubtreeReaperTest {

    @Test
    fun applyingAFolderTombstoneReapsTheWholeSubtreeAndItsBytes() = runTest {
        val testProxy = TestProxy(
            schemaRegistry = createFileTestRegistry(),
            coroutineScope = backgroundScope,
        ).apply {
            executionContext.initFileService(fileDatabase = fileDatabase)
        }
        val executionContext = testProxy.executionContext

        fun fileId(path: String): DataId = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = File.ID,
            keyValue = path,
        )

        // Parents before children: `FolderFilesMembership` adds each saved row to its
        // parent's array, which is the structure the reaper walks.
        executionContext.saveData(Folder.create(testProxy.tContext, "/bucket", files = emptyList()))
        executionContext.saveData(Folder.create(testProxy.tContext, "/bucket/sub", files = emptyList()))
        executionContext.saveData(File.create(testProxy.tContext, "/bucket/photo.jpg"))
        executionContext.saveData(File.create(testProxy.tContext, "/bucket/sub/nested.jpg"))
        testProxy.fileDatabase.writeRawByteArray("/bucket/photo.jpg", byteArrayOf(1, 2, 3))
        testProxy.fileDatabase.writeRawByteArray("/bucket/sub/nested.jpg", byteArrayOf(4, 5, 6))

        assertNotNull(testProxy.database.getData(fileId("/bucket/sub/nested.jpg")), "fixture")
        assertNotNull(testProxy.fileDatabase.readBytes("/bucket/photo.jpg"), "fixture")

        // Exactly what a peer does on ingest: delete the tombstoned row, nothing beneath it.
        executionContext.deleteData(fileId("/bucket"), origin = WriteOrigin.SYNC_INGEST)

        assertNull(
            testProxy.database.getData(fileId("/bucket/photo.jpg")),
            "A direct child must not outlive the folder its only reference came from",
        )
        assertNull(
            testProxy.database.getData(fileId("/bucket/sub")),
            "A child folder must not outlive its parent",
        )
        assertNull(
            testProxy.database.getData(fileId("/bucket/sub/nested.jpg")),
            "The cascade must recurse: a grandchild is as unreachable as a child",
        )
        assertNull(
            testProxy.fileDatabase.readBytes("/bucket/photo.jpg"),
            "Bytes are the point — entities are cheap, a retained photo bucket is not",
        )
        assertNull(
            testProxy.fileDatabase.readBytes("/bucket/sub/nested.jpg"),
            "...including below the first level",
        )
    }

    /**
     * A store that can be made to fail the reaper's array read on demand.
     *
     * ⚠⚠ Deliberately a stub, and the reason matters. A dangling array member does not throw:
     * top-level array reads **skip** missing members and repair the reference. So a fixture that
     * strands a member exercises the happy path, not this branch. The branch is reachable whenever
     * the array read fails for any reason, so the test drives it directly.
     */
    private class FailableStore(
        private val delegate: InMemoryObjectStore = InMemoryObjectStore(),
    ) : ObjectStore by delegate {
        /**
         * Fail reads of exactly this row. ⚠ One hash, not "any array": failing every `TList` read
         * throws from the *other* observers in the delete fanout too, outside the reaper's
         * `runCatching`, so the exception escapes the delete entirely and the test asserts nothing
         * about the branch it targets.
         */
        var failHash: Long? = null

        override fun getObject(dataIdHash: Long): ObjectStore.DbEntry? {
            if (dataIdHash == failHash) error("Entry not found")
            return delegate.getObject(dataIdHash)
        }
    }

    /**
     * ⚠⚠ The degrade path is where orphans are **born**, and it is the only event in the system
     * that knows a subtree has just been stranded.
     *
     * `readChildren` catches deliberately — an escaping throw inside sync ingest's observer fanout
     * takes down the whole tick — but the folder row still dies, so its descendants become
     * unreachable. On a store whose orphaned-file sweep already reads [ObjectStore.SWEEP_COMPLETE]
     * (terminal) nothing would ever collect them. Re-arming the sweep here is what makes that
     * self-healing.
     */
    @Test
    fun aCascadeThatCannotReadItsChildrenReArmsTheSweep() = runTest {
        val store = FailableStore()
        val testProxy = TestProxy(
            schemaRegistry = createFileTestRegistry(),
            objectStore = store,
            coroutineScope = backgroundScope,
        ).apply {
            executionContext.initFileService(fileDatabase = fileDatabase, objectStore = store)
        }
        val executionContext = testProxy.executionContext

        fun fileId(path: String): DataId = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = File.ID,
            keyValue = path,
        )

        executionContext.saveData(Folder.create(testProxy.tContext, "/bucket", files = emptyList()))
        executionContext.saveData(File.create(testProxy.tContext, "/bucket/photo.jpg"))
        executionContext.saveData(File.create(testProxy.tContext, "/bucket/other.jpg"))

        // This device has already finished its sweep — the state this branch matters in. Written
        // after `initFileService`, which spends the one-shot repair and would otherwise clear
        // it straight back out.
        store.storeOrphanedFileSweepCursor(ObjectStore.SWEEP_COMPLETE)

        // The reaper's own read target: the folder's FILES array object.
        store.failHash = DataId.EntityFieldDataId.create(
            context = testProxy.tContext,
            type = TList.ID,
            parentEntity = fileId("/bucket") as DataId.EntityDataId,
            fieldRef = Folder.Fields.FILES,
        ).getDataIdHash()

        executionContext.deleteData(fileId("/bucket"), origin = WriteOrigin.SYNC_INGEST)
        store.failHash = null

        assertNull(
            testProxy.database.getData(fileId("/bucket")),
            "fixture: the folder row dies regardless — that is what strands the rest",
        )
        assertNotNull(
            testProxy.database.getData(fileId("/bucket/other.jpg")),
            "fixture: the cascade degraded, so this sibling really is stranded",
        )
        assertNull(
            store.loadOrphanedFileSweepCursor(),
            "a stranded subtree must re-arm the sweep; leaving SWEEP_COMPLETE in place means " +
                "nothing ever collects it, and it also blocks re-upload of those paths",
        )
    }
}
