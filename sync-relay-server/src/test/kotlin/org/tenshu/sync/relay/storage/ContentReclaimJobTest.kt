package org.tenshu.sync.relay.storage

import org.tenshu.core.common.util.HlcStamp
import org.tenshu.sync.relay.mailbox.ContentEdgeRegistry

import org.tenshu.sync.common.manifestRefOf
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
 * The operator reclamation of undescribed ciphertext (`content-plane-cr2-quorumless.md` §12.1),
 * against a real SQLite file and a real [FileBlobStore]. Every test is about a way it could delete
 * something live.
 */
class ContentReclaimJobTest {

    private lateinit var dbFile: File
    private lateinit var blobDir: File
    private lateinit var url: String

    private val day = 24 * 3_600_000L
    private val now = 10 * day

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("relay-reclaim-test", ".db")
        blobDir = File.createTempFile("relay-reclaim-blobs", "").also { it.delete(); it.mkdirs() }
        url = "jdbc:sqlite:${dbFile.absolutePath}"
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

    private fun RelayDatabase.describe(owner: String, contentId: String, vararg refs: String) = withConnection { conn ->
        conn.prepareStatement("INSERT INTO content_ref(owner_id, content_id, ref) VALUES(?, ?, ?)").use { ps ->
            for (r in refs) {
                ps.setString(1, owner); ps.setString(2, contentId); ps.setString(3, r); ps.executeUpdate()
            }
        }
    }

    private fun RelayDatabase.edge(owner: String, holder: String, contentId: String) = withConnection { conn ->
        conn.prepareStatement(
            "INSERT INTO content_edge(owner_id, holder_hash, property_id, content_id, stamp) VALUES(?, ?, 'p', ?, '1:0:d')",
        ).use { ps ->
            ps.setString(1, owner); ps.setString(2, holder); ps.setString(3, contentId); ps.executeUpdate()
        }
    }

    private class Env(val db: RelayDatabase, val files: FileBlobStore, val store: SqliteContentBlobStore, val job: ContentReclaimJob)

    /** Enqueue exactly what a plan taken right now shows: the operator flow. */
    private fun Env.enqueuePlanned(owner: String = "alice"): ContentReclaimJob.EnqueueResult =
        job.enqueue(owner, day, now, job.plan(owner, day, now).candidateDigest)

    private fun env(blobs: BlobStore? = null, block: Env.() -> Unit) {
        RelayDatabase(url).use { db ->
            val files = FileBlobStore(blobDir)
            val backing = blobs ?: files
            Env(db, files, SqliteContentBlobStore(db, backing), ContentReclaimJob(db, backing)).block()
        }
    }

    @Test
    fun thePlanCountsOnlyIdleUndescribedBlobs() = env {
        store.put("alice", ref("described"), byteArrayOf(1), nowMs = 0)
        db.describe("alice", "c1", ref("described"))
        store.put("alice", ref("garbage"), byteArrayOf(1, 2), nowMs = 0)
        store.put("alice", ref("uploading"), byteArrayOf(1), nowMs = now - 1_000)
        // An undescribed manifest of a content the owner has an edge for: the one blob the relay can name.
        db.edge("alice", "h1", "known")
        store.put("alice", manifestRefOf("known"), byteArrayOf(1), nowMs = 0)
        db.describe("alice", "known", ref("known-chunk"))

        val plan = job.plan("alice", minIdleMs = day, nowMs = now)

        assertEquals(1, plan.candidateBlobs, "only the idle, undescribed, unprotected blob")
        assertEquals(2L, plan.candidateBytes)
        assertEquals(1, plan.describedBlobs)
        assertEquals(1, plan.protectedManifests)
        assertEquals(1, plan.recentlyTouched)
        assertEquals(0, plan.edgeOnlyWithManifestCount)
    }

    @Test
    fun aDrainDeletesBytesAndIndex_andEmptiesTheQueue() = env {
        store.put("alice", ref("garbage"), byteArrayOf(1, 2, 3), nowMs = 0)
        store.put("alice", ref("kept"), byteArrayOf(9), nowMs = 0)
        db.describe("alice", "c1", ref("kept"))

        enqueuePlanned()
        val result = job.drain("alice")

        assertEquals(1, result.deletedBlobs)
        assertEquals(3L, result.freedBytes)
        assertEquals(0, result.remaining)
        assertNull(files.get("alice/${ref("garbage")}"), "the bytes are gone")
        assertEquals(1, store.blobCount("alice"), "and so is the index row; the described blob stays")
        assertNotNull(files.get("alice/${ref("kept")}"))
    }

    @Test
    fun enqueueRefuses_whenTheCandidatesMovedSinceThePlan() = env {
        store.put("alice", ref("a"), byteArrayOf(1), nowMs = 0)
        val reviewed = job.plan("alice", day, now).candidateDigest
        store.put("alice", ref("b"), byteArrayOf(1), nowMs = 0) // one more crossed the idle threshold

        assertFailsWith<ContentReclaimJob.Refused> { job.enqueue("alice", day, now, reviewed) }
        assertEquals(0, job.queued("alice"), "nothing was queued")
    }

    @Test
    fun enqueueRefuses_theSameCountWithADifferentSet() = env {
        // A count cannot tell {a} from {b}; the digest can.
        store.put("alice", ref("a"), byteArrayOf(1), nowMs = 0)
        store.put("alice", ref("b"), byteArrayOf(1), nowMs = now - 1_000) // recent: not a candidate yet
        val reviewed = job.plan("alice", day, now).candidateDigest

        store.get("alice", ref("a"), nowMs = now)               // a is read: no longer idle
        store.put("alice", ref("b"), byteArrayOf(1), nowMs = 0) // b goes idle

        assertEquals(1, job.plan("alice", day, now).candidateBlobs, "precondition: the count is unchanged")
        assertFailsWith<ContentReclaimJob.Refused> { job.enqueue("alice", day, now, reviewed) }
    }

    @Test
    fun enqueueRefuses_whileAnEdgeOnlyContentHasItsManifestStored() = env {
        // ⚠⚠ An edge, a stored manifest, no description: its chunks are undescribed and possibly live,
        // and the relay cannot tell them from garbage.
        db.edge("alice", "h1", "unexplained")
        store.put("alice", manifestRefOf("unexplained"), byteArrayOf(1), nowMs = 0)
        store.put("alice", ref("its-chunk"), byteArrayOf(1), nowMs = 0)

        val plan = job.plan("alice", day, now)
        assertEquals(listOf("unexplained"), plan.edgeOnlyWithManifest)
        assertFailsWith<ContentReclaimJob.Refused> { job.enqueue("alice", day, now, plan.candidateDigest) }
    }

    @Test
    fun aNoneRegisterIsNeitherAProtectedManifestNorAnEdgeOnlyContent() = env {
        // ⚠⚠ `content-plane-cr2-b3-wire-spec.md` rule 3: a none register (`content_id IS NULL`) is a
        // stamp, not an edge. Read as one it would hand `manifestRefOf` a null here, and count toward
        // the edge-only refusal.
        SqliteContentEdgeStore(db).writeEdges(
            "alice", "h1", HlcStamp(1, 0, "d"), listOf(ContentEdgeRegistry.EdgeWrite("p", contentId = null)),
        )
        store.put("alice", ref("garbage"), byteArrayOf(1), nowMs = 0)

        val plan = job.plan("alice", day, now)
        assertEquals(0, plan.protectedManifests)
        assertEquals(0, plan.edgeOnlyWithManifestCount)
        assertEquals(1, plan.candidateBlobs)
    }

    @Test
    fun anEdgeOnlyContentWithNoManifest_doesNotBlock() = env {
        // A thumbnail class that was never emitted: an edge and no bytes at all. Harmless.
        db.edge("alice", "h1", "never-emitted")
        store.put("alice", ref("garbage"), byteArrayOf(1), nowMs = 0)

        assertEquals(0, job.plan("alice", day, now).edgeOnlyWithManifestCount)
        assertEquals(1, enqueuePlanned().enqueued)
    }

    @Test
    fun aRefDescribedAfterTheEnqueue_isSpared() = env {
        store.put("alice", ref("late"), byteArrayOf(1), nowMs = 0)
        enqueuePlanned()
        db.describe("alice", "c9", ref("late")) // the backfill got to it between enqueue and drain

        val result = job.drain("alice")

        assertEquals(0, result.deletedBlobs)
        assertEquals(1, result.spared)
        assertEquals(0, result.remaining)
        assertNotNull(files.get("alice/${ref("late")}"))
    }

    @Test
    fun aPutAfterTheEnqueue_takesTheRefBackOutOfTheQueue() = env {
        store.put("alice", ref("repaired"), byteArrayOf(1), nowMs = 0)
        enqueuePlanned()
        store.put("alice", ref("repaired"), byteArrayOf(7, 7), nowMs = now) // a repair, or a re-upload

        assertEquals(0, job.queued("alice"))
        job.drain("alice")
        assertContentEquals(byteArrayOf(7, 7), files.get("alice/${ref("repaired")}"))
        assertEquals(1, store.blobCount("alice"))
    }

    @Test
    fun anotherOwnersIdenticalRef_isUntouched() = env {
        store.put("alice", ref("shared"), byteArrayOf(1), nowMs = 0)
        store.put("bob", ref("shared"), byteArrayOf(2), nowMs = 0)

        enqueuePlanned()
        job.drain("alice")

        assertNull(files.get("alice/${ref("shared")}"))
        assertNotNull(files.get("bob/${ref("shared")}"), "keyed by owner: bob's copy is not alice's")
        assertEquals(1, store.blobCount("bob"))
    }

    @Test
    fun theQueueSurvivesARestart() {
        env {
            store.put("alice", ref("garbage"), byteArrayOf(1), nowMs = 0)
            enqueuePlanned()
        }
        env {
            assertEquals(1, job.queued("alice"))
            assertEquals(1, job.drain("alice").deletedBlobs)
            assertNull(files.get("alice/${ref("garbage")}"))
        }
    }

    @Test
    fun aByteDeleteThatFails_isFinishedByTheNextDrain() {
        val failing = object : BlobStore by FileBlobStore(blobDir) {
            override fun deleteAll(keys: Collection<String>) = throw IllegalStateException("storage down")
        }
        env(blobs = failing) {
            store.put("alice", ref("garbage"), byteArrayOf(1), nowMs = 0)
            enqueuePlanned()
            assertFailsWith<IllegalStateException> { job.drain("alice") }
            assertEquals(1, job.queued("alice"), "the queue row outlives the failure")
        }
        env {
            job.drain("alice")
            assertNull(files.get("alice/${ref("garbage")}"), "and the next drain finishes the delete")
            assertEquals(0, job.queued("alice"))
        }
    }

    @Test
    fun aKnownContentsManifest_isNeverQueued_andNeverDeleted() = env {
        // Two layers: the enqueue leaves it out, and the drain re-checks. Each is pinned on its own.
        db.edge("alice", "h1", "known")
        db.describe("alice", "known", ref("known-chunk"))
        store.put("alice", manifestRefOf("known"), byteArrayOf(4), nowMs = 0)
        store.put("alice", ref("garbage"), byteArrayOf(1), nowMs = 0)

        enqueuePlanned()
        assertEquals(1, job.queued("alice"), "only the garbage is queued, not the manifest")
        job.drain("alice")

        assertNotNull(files.get("alice/${manifestRefOf("known")}"))
        assertNull(files.get("alice/${ref("garbage")}"))
    }

    @Test
    fun aManifestThatBecameKnownAfterTheEnqueue_isSparedByTheDrain() = env {
        store.put("alice", manifestRefOf("late"), byteArrayOf(4), nowMs = 0)
        enqueuePlanned() // nobody knows "late" yet: it is garbage-shaped
        db.edge("alice", "h1", "late") // then a declare arrives for it: its edge first...
        assertEquals(1, job.drain("alice").pausedOnEdgeOnly, "halfway through the declare, the drain waits")

        db.describe("alice", "late", ref("late-chunk")) // ...then its description
        val result = job.drain("alice")

        assertEquals(1, result.spared, "the manifest of a now-known content is spared")
        assertNotNull(files.get("alice/${manifestRefOf("late")}"))
    }

    @Test
    fun aBlobReadAfterTheEnqueue_isSpared() = env {
        // A GET refreshes last_referenced_at and proves use, and it also dequeues.
        store.put("alice", ref("in-use"), byteArrayOf(5), nowMs = 0)
        enqueuePlanned()
        store.get("alice", ref("in-use"), nowMs = now + 1)

        assertEquals(0, job.queued("alice"), "the read took it out of the queue")
        assertEquals(0, job.drain("alice").deletedBlobs)
        assertNotNull(files.get("alice/${ref("in-use")}"))
    }

    @Test
    fun aReadDequeues_evenIfItsTimestampNeverLands() = env {
        // A GET overlapping a drain must not be followed by deletion. The read takes the ref off the
        // queue before it reads, so the queue itself does not name it any more.
        store.put("alice", ref("read"), byteArrayOf(3), nowMs = 0)
        enqueuePlanned()

        store.get("alice", ref("read"), nowMs = 0) // a stale timestamp: only the dequeue can save it

        assertEquals(0, job.queued("alice"))
        assertEquals(0, job.drain("alice").deletedBlobs)
        assertNotNull(files.get("alice/${ref("read")}"))
    }

    @Test
    fun aPutWhoseByteWriteFails_hasAlreadyLeftTheQueue() {
        // The dequeue happens BEFORE the fallible byte write, so a PUT that dies there
        // leaves its ref unqueued, never fresh bytes queued for deletion.
        env {
            store.put("alice", ref("retried"), byteArrayOf(1), nowMs = 0)
            enqueuePlanned()
        }
        val failingPut = object : BlobStore by FileBlobStore(blobDir) {
            override fun put(key: String, bytes: ByteArray) = throw IllegalStateException("disk full")
        }
        env(blobs = failingPut) {
            assertFailsWith<IllegalStateException> { store.put("alice", ref("retried"), byteArrayOf(2), nowMs = now) }
            assertEquals(0, job.queued("alice"))
        }
    }

    @Test
    fun aDrainPauses_whileADeclarationIsHalfway() = env {
        // A declare writes its edge, then its refs, in two lock sections. In between,
        // the content is edge-only with a stored manifest and its chunks look like garbage.
        store.put("alice", ref("chunk-of-z"), byteArrayOf(1), nowMs = 0)
        enqueuePlanned()
        db.edge("alice", "h1", "z")
        store.put("alice", manifestRefOf("z"), byteArrayOf(2), nowMs = now)

        val paused = job.drain("alice")
        assertEquals(0, paused.deletedBlobs)
        assertEquals(1, paused.pausedOnEdgeOnly)
        assertNotNull(files.get("alice/${ref("chunk-of-z")}"))

        db.describe("alice", "z", ref("chunk-of-z"), manifestRefOf("z")) // the declare completes
        val resumed = job.drain("alice")
        assertEquals(0, resumed.pausedOnEdgeOnly)
        assertEquals(1, resumed.spared, "now described: spared, not deleted")
        assertNotNull(files.get("alice/${ref("chunk-of-z")}"))
    }

    @Test
    fun aReadBeforeTheEnqueue_stopsAnEarlierPlanFromQueuingIt() = env {
        // A plan taken before a read must not re-queue the ref after it.
        store.put("alice", ref("read"), byteArrayOf(3), nowMs = 0)
        val earlierPlan = job.plan("alice", day, now).candidateDigest

        store.get("alice", ref("read"), nowMs = now)

        assertFailsWith<ContentReclaimJob.Refused> { job.enqueue("alice", day, now, earlierPlan) }
        assertEquals(0, job.queued("alice"))
    }

    @Test
    fun anEnqueueDuringTheByteRead_alreadySeesTheReadsTimestamp() {
        // The interleaving itself: the byte read runs OUTSIDE the lock, so an enqueue from an earlier
        // plan can run while it is in flight. The refresh must already have landed.
        val files = FileBlobStore(blobDir)
        var duringRead: (() -> Unit)? = null
        val probe = object : BlobStore by files {
            override fun get(key: String): ByteArray? {
                duringRead?.also { duringRead = null }?.invoke()
                return files.get(key)
            }
        }
        env(blobs = probe) {
            store.put("alice", ref("read"), byteArrayOf(3), nowMs = 0)
            val earlierPlan = job.plan("alice", day, now).candidateDigest
            duringRead = { assertFailsWith<ContentReclaimJob.Refused> { job.enqueue("alice", day, now, earlierPlan) } }

            store.get("alice", ref("read"), nowMs = now)

            assertNull(duringRead, "precondition: the enqueue ran during the read")
            assertEquals(0, job.queued("alice"))
        }
    }

    @Test
    fun aDeleteTheBackendSilentlyDropped_staysQueued_andIsReported() {
        // FileBlobStore.delete ignores a failed File.delete().
        val files = FileBlobStore(blobDir)
        var refuse = true
        val silent = object : BlobStore by files {
            override fun deleteAll(keys: Collection<String>) { if (!refuse) files.deleteAll(keys) }
        }
        env(blobs = silent) {
            store.put("alice", ref("stuck"), byteArrayOf(1, 2), nowMs = 0)
            enqueuePlanned()

            val first = job.drain("alice")
            assertEquals(1, first.failedDeletes)
            assertEquals(0, first.deletedBlobs)
            assertEquals(1, first.remaining, "still queued: the queue row is its only record now")

            refuse = false
            val retry = job.drain("alice")
            assertEquals(1, retry.deletedBlobs)
            assertEquals(2L, retry.freedBytes, "the byte count survived the dropped index row")
            assertNull(files.get("alice/${ref("stuck")}"))
        }
    }

    @Test
    fun aPlanDigest_cannotAuthoriseAnotherOwner() = env {
        // Identical refs under two owners must not give identical digests.
        store.put("alice", ref("same"), byteArrayOf(1), nowMs = 0)
        store.put("bob", ref("same"), byteArrayOf(1), nowMs = 0)
        val alicesPlan = job.plan("alice", day, now).candidateDigest

        assertFailsWith<ContentReclaimJob.Refused> { job.enqueue("bob", day, now, alicesPlan) }
        assertEquals(0, job.queued("bob"))
    }

    @Test
    fun aReadOfBytesAFailedDeleteLeft_restoresTheirIndex() {
        // After a failed physical delete the queue row is the only record; a read that
        // dequeues it must put the index back, or the bytes sit in neither table.
        val files = FileBlobStore(blobDir)
        val silent = object : BlobStore by files { override fun deleteAll(keys: Collection<String>) = Unit }
        env(blobs = silent) {
            store.put("alice", ref("survivor"), byteArrayOf(1, 2, 3), nowMs = 0)
            enqueuePlanned()
            assertEquals(1, job.drain("alice").failedDeletes)
            assertEquals(0, store.blobCount("alice"), "precondition: the index row is gone")

            assertContentEquals(byteArrayOf(1, 2, 3), store.get("alice", ref("survivor"), nowMs = now))

            assertEquals(1, store.blobCount("alice"), "the index is back")
            assertEquals(3L, store.bytesUsed("alice"), "and counted against quota again")
            assertEquals(0, job.queued("alice"))
        }
    }
}
