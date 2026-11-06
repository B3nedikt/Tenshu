package org.tenshu.sync.relay.storage

import org.tenshu.core.common.util.HlcStamp
import org.tenshu.sync.common.manifestRefOf
import org.tenshu.sync.relay.mailbox.ContentEdgeRegistry
import org.tenshu.sync.relay.mailbox.ContentFreeing
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CR-2 B4r's logical free and the FREED set over a real SQLite file and a real [FileBlobStore]
 * (`content-plane-cr2-b4-spec.md` §1.1, §1.2, §1.6). The route behaviour is `ContentFreedRoutingTest`.
 */
class SqliteContentFreedStoreTest {

    private lateinit var dbFile: File
    private lateinit var blobDir: File

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("relay-freed-test", ".db")
        blobDir = File.createTempFile("relay-freed-blobs", "").also { it.delete(); it.mkdirs() }
    }

    @AfterTest
    fun teardown() {
        dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
        blobDir.deleteRecursively()
    }

    private fun ref(label: String): String =
        "blob-" + label.encodeToByteArray().joinToString("") { "%02x".format(it) }.padEnd(64, '0').take(64)

    private class Env(
        val db: RelayDatabase,
        val files: FileBlobStore,
        val blobs: SqliteContentBlobStore,
        val refs: SqliteContentRefStore,
        val edges: SqliteContentEdgeStore,
        val freeing: SqliteContentFreedStore,
        val job: ContentReclaimJob,
    ) {
        fun queued(ownerId: String, ref: String): Long? = db.withConnection { conn ->
            conn.prepareStatement("SELECT byte_len FROM content_free_queue WHERE owner_id = ? AND ref = ?").use { ps ->
                ps.setString(1, ownerId)
                ps.setString(2, ref)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
            }
        }

        fun edge(ownerId: String, holder: String, contentId: String?, physical: Long = 1) =
            edges.writeEdges(ownerId, holder, HlcStamp(physical, 0, "d"), listOf(ContentEdgeRegistry.EdgeWrite("p", contentId)))
    }

    private fun env(block: Env.() -> Unit) {
        RelayDatabase("jdbc:sqlite:${dbFile.absolutePath}").use { db ->
            val files = FileBlobStore(blobDir)
            val refs = SqliteContentRefStore(db)
            Env(
                db, files, SqliteContentBlobStore(db, files), refs,
                SqliteContentEdgeStore(db), SqliteContentFreedStore(db, refs), ContentReclaimJob(db, files),
            ).block()
        }
    }

    @Test
    fun freeRefuses_whileALiveEdgeNamesTheContent_andChangesNothing() = env {
        blobs.put("alice", ref("c1-chunk"), byteArrayOf(1))
        refs.declareRefs("alice", "c1", listOf(ref("c1-chunk")))
        edge("alice", "h1", "c1")

        assertEquals(ContentFreeing.FreeResult.NOT_FREED, freeing.free("alice", "c1"))
        assertEquals(setOf(ref("c1-chunk")), refs.refsOf("alice", "c1"))
        assertNull(queued("alice", ref("c1-chunk")))
        assertTrue(freeing.freedAmong("alice", listOf("c1")).isEmpty())
    }

    @Test
    fun aNoneRegisterIsNotALiveEdge_soTheContentFrees() = env {
        // Rule 3: the holder's property was cleared — a stamp, not an edge.
        edge("alice", "h1", "c1", physical = 1)
        edge("alice", "h1", null, physical = 2)
        assertEquals(ContentFreeing.FreeResult.FREED, freeing.free("alice", "c1"))
    }

    @Test
    fun freeQueuesTheRefsWithTheirLength_dropsTheDescription_andMarksFreed() = env {
        blobs.put("alice", ref("c1-chunk"), byteArrayOf(1, 2, 3))
        blobs.put("alice", manifestRefOf("c1"), byteArrayOf(4))
        refs.declareRefs("alice", "c1", listOf(ref("c1-chunk"), manifestRefOf("c1")))

        assertEquals(ContentFreeing.FreeResult.FREED, freeing.free("alice", "c1", nowMs = 42))

        assertEquals(3L, queued("alice", ref("c1-chunk")))
        assertEquals(1L, queued("alice", manifestRefOf("c1")))
        assertTrue(refs.refsOf("alice", "c1").isEmpty())
        assertEquals(setOf("c1"), freeing.freedAmong("alice", listOf("c1", "other")))
    }

    @Test
    fun freeMarksPendingContentFreed_evenWithNoRefsToMove() = env {
        assertEquals(ContentFreeing.FreeResult.FREED, freeing.free("alice", "pending"))
        assertEquals(setOf("pending"), freeing.freedAmong("alice", listOf("pending")))
        assertEquals(0, job.queued("alice"))
    }

    @Test
    fun freeIsOwnerScoped() = env {
        blobs.put("alice", ref("shared"), byteArrayOf(1))
        blobs.put("bob", ref("shared"), byteArrayOf(1))
        refs.declareRefs("alice", "c1", listOf(ref("shared")))
        refs.declareRefs("bob", "c1", listOf(ref("shared")))

        freeing.free("alice", "c1")

        assertEquals(setOf(ref("shared")), refs.refsOf("bob", "c1"))
        assertNull(queued("bob", ref("shared")))
        assertTrue(freeing.freedAmong("bob", listOf("c1")).isEmpty())
        // And a revive check for bob never sees alice's queue.
        assertTrue(freeing.unrevivableRefs("bob", listOf(ref("shared"))).isEmpty())
        assertEquals(setOf(ref("shared")), freeing.unrevivableRefs("alice", listOf(ref("shared"))))
    }

    @Test
    fun afterAFree_theDrainDeletesTheQueuedBytes() = env {
        // ⚠⚠ The point of step 2: the drain spares described refs and the manifest of any content with an
        // edge or ref. After the free there is neither, so its existing checks let them go.
        blobs.put("alice", ref("c1-chunk"), byteArrayOf(1, 2, 3))
        blobs.put("alice", manifestRefOf("c1"), byteArrayOf(4))
        refs.declareRefs("alice", "c1", listOf(ref("c1-chunk"), manifestRefOf("c1")))
        edge("alice", "h1", "c1", physical = 1)
        edges.removeHolders("alice", listOf("h1"), HlcStamp(2, 0, "d"))

        assertEquals(ContentFreeing.FreeResult.FREED, freeing.free("alice", "c1"))
        val result = job.drain("alice")

        assertEquals(2, result.deletedBlobs)
        assertEquals(4L, result.freedBytes)
        assertEquals(0, result.spared)
        assertEquals(0, result.remaining)
        assertNull(files.get("alice/${ref("c1-chunk")}"))
        assertNull(files.get("alice/${manifestRefOf("c1")}"))
        assertEquals(0, blobs.blobCount("alice"))
    }

    @Test
    fun aRePutAfterTheFree_takesTheRefOutOfTheQueue_soTheDrainSparesIt_andARevivePasses() = env {
        blobs.put("alice", ref("c1-chunk"), byteArrayOf(1))
        blobs.put("alice", manifestRefOf("c1"), byteArrayOf(2))
        refs.declareRefs("alice", "c1", listOf(ref("c1-chunk"), manifestRefOf("c1")))
        freeing.free("alice", "c1")
        assertEquals(setOf(ref("c1-chunk"), manifestRefOf("c1")), freeing.unrevivableRefs("alice", listOf(ref("c1-chunk"), manifestRefOf("c1"))))

        blobs.put("alice", manifestRefOf("c1"), byteArrayOf(2)) // the repair's re-PUT

        assertEquals(setOf(ref("c1-chunk")), freeing.unrevivableRefs("alice", listOf(ref("c1-chunk"), manifestRefOf("c1"))))
        assertTrue(freeing.unrevivableRefs("alice", listOf(manifestRefOf("c1"))).isEmpty())
        assertEquals(setOf(ref("never")), freeing.unrevivableRefs("alice", listOf(ref("never"))), "absent is unrevivable")
    }

    @Test
    fun reviveReplacesTheRefSetAndLeavesFreed_insideAnOuterTransaction() = env {
        freeing.free("alice", "c1")
        assertFailsWith<IllegalStateException> {
            freeing.atomically {
                freeing.revive("alice", "c1", listOf(ref("new")))
                error("the push failed after the revive")
            }
        }
        assertTrue(refs.refsOf("alice", "c1").isEmpty(), "the describe rolled back with the outer transaction")
        assertEquals(setOf("c1"), freeing.freedAmong("alice", listOf("c1")), "and so did the un-free")

        freeing.atomically { freeing.revive("alice", "c1", listOf(ref("new"))) }
        assertEquals(setOf(ref("new")), refs.refsOf("alice", "c1"))
        assertTrue(freeing.freedAmong("alice", listOf("c1")).isEmpty())
    }

    @Test
    fun declareRefsNests_soItNoLongerCommitsAnOuterTransactionEarly() = env {
        // ⚠⚠ The B4r "transactional variant": before it, declareRefs committed on its own and an outer
        // rollback could not undo it (RelayDatabase.inTransaction's old warning).
        refs.declareRefs("alice", "c1", listOf(ref("old")))
        assertFailsWith<IllegalStateException> {
            db.inTransaction {
                refs.declareRefs("alice", "c1", listOf(ref("new")))
                error("boom")
            }
        }
        assertEquals(setOf(ref("old")), refs.refsOf("alice", "c1"))
    }

    @Test
    fun theFreedTableSurvivesAReopen() {
        env { freeing.free("alice", "c1") }
        env { assertNotNull(freeing.freedAmong("alice", listOf("c1")).singleOrNull()) }
    }

    @Test
    fun unfreeReportsWhetherThereWasARow() = env {
        assertFalse(freeing.unfree("alice", "c1"))
        freeing.free("alice", "c1")
        assertTrue(freeing.unfree("alice", "c1"))
        assertTrue(freeing.freedAmong("alice", listOf("c1")).isEmpty())
    }
}
