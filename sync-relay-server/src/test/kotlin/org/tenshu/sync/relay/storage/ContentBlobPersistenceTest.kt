package org.tenshu.sync.relay.storage

import org.tenshu.sync.relay.mailbox.ContentBlobRegistry
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The content plane: bytes in a [FileBlobStore], last-reference index in SQLite.
 * Pins the retention/orphan GC semantics + persistence across restart.
 */
class ContentBlobPersistenceTest {

    private lateinit var dbFile: File
    private lateinit var blobDir: File
    private lateinit var url: String

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("relay-content-test", ".db")
        blobDir = File.createTempFile("relay-content-blobs", "").also { it.delete(); it.mkdirs() }
        url = "jdbc:sqlite:${dbFile.absolutePath}"
    }

    @AfterTest
    fun teardown() {
        dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
        blobDir.deleteRecursively()
    }

    @Test
    fun fileBlobStore_roundTrip_overwrite_delete() {
        val store = FileBlobStore(blobDir)
        assertNull(store.get("owner/ref-x"))
        store.put("owner/ref-x", byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), store.get("owner/ref-x"))
        store.put("owner/ref-x", byteArrayOf(9)) // overwrite
        assertContentEquals(byteArrayOf(9), store.get("owner/ref-x"))
        store.delete("owner/ref-x")
        assertNull(store.get("owner/ref-x"))
        store.delete("owner/ref-x") // idempotent
    }

    @Test
    fun contentBlob_put_get_roundTrip_and_blobCount() {
        RelayDatabase(url).use { db ->
            val store = SqliteContentBlobStore(db, FileBlobStore(blobDir))
            store.put("alice", "blob-1", byteArrayOf(1, 2, 3), nowMs = 1_000)
            store.put("alice", "blob-2", byteArrayOf(4), nowMs = 1_000)
            assertContentEquals(byteArrayOf(1, 2, 3), store.get("alice", "blob-1", nowMs = 1_000))
            assertNull(store.get("alice", "missing", nowMs = 1_000))
            assertEquals(2, store.blobCount("alice"))
        }
    }

    @Test
    fun contentBlob_gcSweep_dropsUntouched_keepsRefreshed_andDeletesBytes() {
        RelayDatabase(url).use { db ->
            val blobs = FileBlobStore(blobDir)
            val store = SqliteContentBlobStore(db, blobs, contentRetentionWindowMs = 100)
            store.put("alice", "stale", byteArrayOf(1), nowMs = 0)
            store.put("alice", "fresh", byteArrayOf(2), nowMs = 0)

            // Refresh "fresh" via a GET inside the window; "stale" is left untouched.
            store.get("alice", "fresh", nowMs = 150)

            val dropped = store.gcSweep(nowMs = 200) // threshold = 100; stale(0) < 100, fresh(150) not
            assertEquals(1, dropped)
            assertEquals(1, store.blobCount("alice"))
            assertNull(store.get("alice", "stale", nowMs = 200))            // index gone
            assertNull(blobs.get("alice/stale"))                            // bytes deleted too
            assertContentEquals(byteArrayOf(2), store.get("alice", "fresh", nowMs = 200))
        }
    }

    @Test
    fun contentBlob_maxRetention_disablesGc() {
        RelayDatabase(url).use { db ->
            val store = SqliteContentBlobStore(db, FileBlobStore(blobDir), contentRetentionWindowMs = Long.MAX_VALUE)
            store.put("alice", "blob-1", byteArrayOf(1), nowMs = 0)
            assertEquals(0, store.gcSweep(nowMs = Long.MAX_VALUE))
            assertEquals(1, store.blobCount("alice"))
        }
    }

    @Test
    fun contentBlob_bytesUsed_tracksPutsAndGc() {
        RelayDatabase(url).use { db ->
            val store = SqliteContentBlobStore(db, FileBlobStore(blobDir), contentRetentionWindowMs = 100)
            assertEquals(0L, store.bytesUsed("alice"))
            store.put("alice", "blob-1", ByteArray(10), nowMs = 0)
            store.put("alice", "blob-2", ByteArray(20), nowMs = 0)
            store.put("bob", "blob-3", ByteArray(99), nowMs = 0) // other owner, not counted
            assertEquals(30L, store.bytesUsed("alice"))
            // A re-PUT of an existing ref does not double-count (content-addressed, same length).
            store.put("alice", "blob-1", ByteArray(10), nowMs = 0)
            assertEquals(30L, store.bytesUsed("alice"))
            // GC frees the quota: blob-1 ages out, blob-2 stays.
            store.get("alice", "blob-2", nowMs = 150)
            store.gcSweep(nowMs = 200)
            assertEquals(20L, store.bytesUsed("alice"))
        }
    }

    @Test
    fun contentBlob_putWithinQuota_enforcesAndIsIdempotent() {
        RelayDatabase(url).use { db ->
            val store = SqliteContentBlobStore(db, FileBlobStore(blobDir))
            // Quota = 30 bytes. First 20-byte blob fits.
            assertEquals(
                ContentBlobRegistry.PutResult.STORED,
                store.putWithinQuota("alice", "blob-1", ByteArray(20), quotaBytes = 30, nowMs = 0),
            )
            // A new 20-byte ref would total 40 > 30 → rejected, nothing stored.
            assertEquals(
                ContentBlobRegistry.PutResult.OVER_QUOTA,
                store.putWithinQuota("alice", "blob-2", ByteArray(20), quotaBytes = 30, nowMs = 0),
            )
            assertEquals(20L, store.bytesUsed("alice"))
            assertNull(store.get("alice", "blob-2", nowMs = 0))
            // Re-PUT of the already-stored ref is always accepted (idempotent, not re-counted) even
            // though the owner sits at quota.
            assertEquals(
                ContentBlobRegistry.PutResult.STORED,
                store.putWithinQuota("alice", "blob-1", ByteArray(20), quotaBytes = 20, nowMs = 1),
            )
            assertEquals(20L, store.bytesUsed("alice"))
        }
    }

    @Test
    fun contentBlob_survivesReopen() {
        RelayDatabase(url).use { db ->
            SqliteContentBlobStore(db, FileBlobStore(blobDir)).put("alice", "blob-1", byteArrayOf(7, 7, 7), nowMs = 1_000)
        }
        // New process: fresh DB connection + fresh FileBlobStore over the same dir.
        RelayDatabase(url).use { db ->
            val store = SqliteContentBlobStore(db, FileBlobStore(blobDir))
            assertContentEquals(byteArrayOf(7, 7, 7), store.get("alice", "blob-1", nowMs = 2_000))
            assertEquals(1, store.blobCount("alice"))
        }
    }

    /**
     * Account-lifecycle reclamation on the DURABLE backend (Face A / D4). The in-memory twin
     * lives in `ContentBlobReclaimTest`; what this adds is the half a map cannot check — that
     * the blob **bodies** leave the filesystem, not just the index rows, and that the deletion
     * survives a reopen.
     */
    @Test
    fun contentBlob_reclaimOwner_dropsBodiesAndIndex_andSparesOtherOwners() {
        val blobs = FileBlobStore(blobDir)
        RelayDatabase(url).use { db ->
            val store = SqliteContentBlobStore(db, blobs)
            store.put("alice", "blob-1", ByteArray(10), nowMs = 1_000)
            store.put("alice", "blob-2", ByteArray(30), nowMs = 1_000)
            store.put("bob", "blob-1", ByteArray(20), nowMs = 1_000)

            val result = store.reclaimOwner("alice")

            assertEquals(2, result.blobsDropped)
            assertEquals(40L, result.bytesFreed)
            assertEquals(0, store.blobCount("alice"))
            // ⚠ The index row going is not enough — an orphaned body would keep consuming disk
            // forever with nothing left to name it.
            assertNull(blobs.get("alice/blob-1"))
            assertNull(blobs.get("alice/blob-2"))
            // ⚠ Same ref, different owner: the key is (ownerId, ref), and a reclaim that
            // matched on ref alone would delete a stranger's file.
            assertNotNull(blobs.get("bob/blob-1"))
            assertEquals(1, store.blobCount("bob"))
        }

        RelayDatabase(url).use { db ->
            val reopened = SqliteContentBlobStore(db, FileBlobStore(blobDir))
            assertEquals(0, reopened.blobCount("alice"), "the reclaim is durable")
            assertEquals(20L, reopened.bytesUsed("bob"))
        }
    }

    @Test
    fun contentBlob_reclaimOwner_worksWithTimeGcDisabled() {
        RelayDatabase(url).use { db ->
            // ⚠⚠ gcSweep short-circuits at Long.MAX_VALUE (durable content is never time-reaped;
            // the production default). Reclaim is a different trigger and must not inherit that guard.
            val store = SqliteContentBlobStore(
                db,
                FileBlobStore(blobDir),
                contentRetentionWindowMs = Long.MAX_VALUE,
            )
            store.put("alice", "blob-1", ByteArray(10), nowMs = 1_000)

            assertEquals(0, store.gcSweep(nowMs = Long.MAX_VALUE / 2))
            assertEquals(1, store.reclaimOwner("alice").blobsDropped)
        }
    }

    @Test
    fun contentBlob_reclaimOwner_rejectsABlankOwnerId() {
        RelayDatabase(url).use { db ->
            val store = SqliteContentBlobStore(db, FileBlobStore(blobDir))
            store.put("alice", "blob-1", ByteArray(10), nowMs = 1_000)

            assertFailsWith<IllegalArgumentException> { store.reclaimOwner("") }
            assertEquals(1, store.blobCount("alice"))
        }
    }

    /**
     * ⚠⚠ A failed write must not leave its temp file behind (ST-1,
     * `drive-upload-stop-and-reclamation.md` §2).
     *
     * `put` writes `<name>.tmp` then renames. When the write itself throws — which is what a full
     * volume does — a surviving temp file leaks one file per attempt, and the client retries
     * forever. A store that leaks under the one condition it is most likely to meet exhausts inodes.
     *
     * ⚠ **How the failure is simulated, and why not the obvious way.** A unit test cannot fill a
     * disk. A read-only *directory* does not reproduce it: the temp file is never created at all, so
     * there is no leftover to find, and the test passes without the cleanup. On a full volume the
     * file is created and the *write* fails. Here the
     * `.tmp` path is pre-created as a **directory**, so `writeBytes` throws with the path already
     * present — the leftover exists, exactly as it does on a full volume.
     */
    @Test
    fun aFailedWriteLeavesNoTempFileBehind() {
        val store = FileBlobStore(blobDir)
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("blob-doomed".encodeToByteArray())
        val tmpPath = File(blobDir, "$encoded.tmp")
        check(tmpPath.mkdirs()) { "the stand-in for a write that fails mid-flight" }

        assertFailsWith<Throwable> { store.put("blob-doomed", ByteArray(16) { 1 }) }

        val leftovers = blobDir.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertEquals(emptyList(), leftovers, "a failed put must clean up its temp file")
    }

    @Test
    fun aSuccessfulWriteLeavesNoTempFileEither() {
        val store = FileBlobStore(blobDir)
        store.put("blob-ok", ByteArray(8) { 2 })
        store.put("blob-ok", ByteArray(8) { 3 })  // overwrite: the rename-retry path

        val leftovers = blobDir.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertEquals(emptyList(), leftovers)
        assertContentEquals(ByteArray(8) { 3 }, store.get("blob-ok"))
    }

    // --- ST-3b: dropRefs on the DURABLE backend (the one production runs) ------------------------

    /**
     * ⭐ The end-to-end shape with artificial data: several blobs on disk and in the index, a
     * subset named for deletion, and everything checked — the bytes are gone from the filesystem,
     * the index rows are gone, the owner's usage drops by exactly the right amount, and the blobs
     * that were NOT named survive untouched.
     *
     * ⚠ The unit tests around this ran against the in-memory registry. This is the SQLite +
     * FileBlobStore path, which is what the relay actually runs, and the only one where "deleted"
     * means a file left the disk.
     */
    @Test
    fun dropRefs_removesNamedBlobsFromDiskAndIndex_andLeavesTheRestAlone() {
        RelayDatabase(url).use { db ->
            val blobs = FileBlobStore(blobDir)
            val store = SqliteContentBlobStore(db, blobs, contentRetentionWindowMs = Long.MAX_VALUE)

            store.put("alice", "blob-keep", ByteArray(100) { 1 })
            store.put("alice", "blob-drop-1", ByteArray(250) { 2 })
            store.put("alice", "blob-drop-2", ByteArray(400) { 3 })
            assertEquals(3, store.blobCount("alice"))
            assertEquals(750L, store.bytesUsed("alice"))

            val result = store.dropRefs("alice", listOf("blob-drop-1", "blob-drop-2"))

            assertEquals(2, result.blobsDropped)
            assertEquals(650L, result.bytesFreed, "freed bytes are measured, not estimated")
            assertEquals(1, store.blobCount("alice"), "the index row goes with the bytes")
            assertEquals(100L, store.bytesUsed("alice"), "usage reflects what is actually gone")

            assertNull(store.get("alice", "blob-drop-1"), "dropped content must not read back")
            assertNull(store.get("alice", "blob-drop-2"))
            assertNotNull(store.get("alice", "blob-keep"), "an unnamed blob must survive")

            // ⚠ And the bytes really left the filesystem — an index-only delete would still read
            // as "gone" through the registry while the disk stayed full, which is the exact
            // failure this whole track exists to fix.
            assertEquals(
                1,
                blobDir.listFiles()?.count { !it.name.endsWith(".tmp") } ?: 0,
                "exactly one blob file should remain on disk",
            )
        }
    }

    /** ⚠⚠ Owner scoping at the storage layer, below the route's own check. */
    @Test
    fun dropRefs_cannotTouchAnotherOwnersBlobs() {
        RelayDatabase(url).use { db ->
            val store = SqliteContentBlobStore(db, FileBlobStore(blobDir), contentRetentionWindowMs = Long.MAX_VALUE)
            store.put("alice", "blob-shared-name", ByteArray(10))
            store.put("mallory", "blob-shared-name", ByteArray(20))

            val result = store.dropRefs("mallory", listOf("blob-shared-name"))

            assertEquals(1, result.blobsDropped)
            assertEquals(20L, result.bytesFreed)
            assertNotNull(
                store.get("alice", "blob-shared-name"),
                "the SAME ref under another owner is a different blob and must survive",
            )
        }
    }

    /** Idempotent, and an absent ref frees nothing rather than erroring. */
    @Test
    fun dropRefs_isIdempotentAndToleratesUnknownRefs() {
        RelayDatabase(url).use { db ->
            val store = SqliteContentBlobStore(db, FileBlobStore(blobDir), contentRetentionWindowMs = Long.MAX_VALUE)
            store.put("alice", "blob-one", ByteArray(64))

            assertEquals(1, store.dropRefs("alice", listOf("blob-one")).blobsDropped)
            assertEquals(
                ContentBlobRegistry.ReclaimResult.NOTHING,
                store.dropRefs("alice", listOf("blob-one")),
                "a retried reclamation must report nothing rather than fail",
            )
            assertEquals(0, store.dropRefs("alice", listOf("blob-never-existed")).blobsDropped)
            assertEquals(ContentBlobRegistry.ReclaimResult.NOTHING, store.dropRefs("alice", emptyList()))
        }
    }

    /** ⚠ A repeated ref in one batch must not double-count what it freed. */
    @Test
    fun dropRefs_deduplicatesWithinABatch() {
        RelayDatabase(url).use { db ->
            val store = SqliteContentBlobStore(db, FileBlobStore(blobDir), contentRetentionWindowMs = Long.MAX_VALUE)
            store.put("alice", "blob-dup", ByteArray(128))

            val result = store.dropRefs("alice", listOf("blob-dup", "blob-dup", "blob-dup"))

            assertEquals(1, result.blobsDropped)
            assertEquals(128L, result.bytesFreed)
        }
    }
}
