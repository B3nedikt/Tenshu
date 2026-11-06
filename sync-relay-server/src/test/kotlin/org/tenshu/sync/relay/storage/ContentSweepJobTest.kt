package org.tenshu.sync.relay.storage

import org.tenshu.core.common.util.HlcStamp
import org.tenshu.sync.common.manifestRefOf
import org.tenshu.sync.relay.ContentSweepMode
import org.tenshu.sync.relay.mailbox.ContentBlobStore
import org.tenshu.sync.relay.mailbox.ContentEdgeRegistry
import org.tenshu.sync.relay.mailbox.ContentEdgeRegistry.EdgeWrite
import org.tenshu.sync.relay.mailbox.ContentEdgeStore
import org.tenshu.sync.relay.mailbox.ContentFreedStore
import org.tenshu.sync.relay.mailbox.ContentFreeing
import org.tenshu.sync.relay.mailbox.ContentRefStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CR-2 B5r's sweep (`content-plane-cr2-b5-spec.md` §1.2, §3 "Sweep modes", "Race", "Seed", "ABA") over a
 * real SQLite file and a real [FileBlobStore]: the production pair, because the drain's safety is the
 * store lock and the queue, which the in-memory twin does not have.
 */
class ContentSweepJobTest {

    private lateinit var dbFile: File
    private lateinit var blobDir: File

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("relay-sweep-test", ".db")
        blobDir = File.createTempFile("relay-sweep-blobs", "").also { it.delete(); it.mkdirs() }
    }

    @AfterTest
    fun teardown() {
        dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
        blobDir.deleteRecursively()
    }

    private class Env(
        val db: RelayDatabase,
        val files: BlobStore,
        val blobs: SqliteContentBlobStore,
        val refs: SqliteContentRefStore,
        val edges: ContentEdgeRegistry,
        val freeing: SqliteContentFreedStore,
        val drainer: ContentReclaimJob,
        var now: Long,
        val logs: MutableList<String>,
    ) {
        fun job(mode: ContentSweepMode, edges: ContentEdgeRegistry = this.edges) = ContentSweepJob(
            mode = mode,
            graceMs = T,
            intervalMs = 5 * 60_000L,
            edges = edges,
            freeing = freeing,
            refs = refs,
            blobs = blobs,
            drainer = drainer,
            clock = { now },
            log = { logs += it },
        )

        fun edge(holder: String, physical: Long, contentId: String?) =
            edges.writeEdges(OWNER, holder, HlcStamp(physical, 0, "d"), listOf(EdgeWrite("p", contentId)))

        /** A described content with one chunk and its manifest, both stored. */
        fun upload(contentId: String, chunk: String, size: Int = 100) {
            blobs.put(OWNER, chunk, ByteArray(size), nowMs = now)
            blobs.put(OWNER, manifestRefOf(contentId), ByteArray(10), nowMs = now)
            refs.declareRefs(OWNER, contentId, listOf(chunk, manifestRefOf(contentId)))
        }

        fun stored(ref: String) = files.exists("$OWNER/$ref")

        fun queued(ref: String): Boolean = db.withConnection { conn ->
            conn.prepareStatement("SELECT 1 FROM content_free_queue WHERE owner_id = ? AND ref = ?").use { ps ->
                ps.setString(1, OWNER)
                ps.setString(2, ref)
                ps.executeQuery().use { it.next() }
            }
        }
    }

    private fun env(files: (File) -> BlobStore = ::FileBlobStore, block: Env.() -> Unit) {
        RelayDatabase("jdbc:sqlite:${dbFile.absolutePath}").use { db ->
            val store = files(blobDir)
            val refs = SqliteContentRefStore(db)
            var envRef: Env? = null
            val clock = { envRef!!.now }
            val env = Env(
                db, store, SqliteContentBlobStore(db, store), refs, SqliteContentEdgeStore(db, clock),
                SqliteContentFreedStore(db, refs, clock), ContentReclaimJob(db, store), now = 1_000_000, logs = mutableListOf(),
            )
            envRef = env
            env.block()
        }
    }

    /** c1 uploaded, held by h1, then deleted at `now`. */
    private fun Env.deletedContent(contentId: String = "c1", chunk: String = CHUNK_A) {
        upload(contentId, chunk)
        edge("h1", 1, contentId)
        edge("h1", 2, null)
    }

    // ---- modes -----------------------------------------------------------------------------------

    @Test
    fun off_freesNothing_andLogsNothing() = env {
        deletedContent()
        now += T * 10
        job(ContentSweepMode.OFF).runOnce()
        assertTrue(logs.isEmpty(), logs.toString())
        assertTrue(freeing.freedAmong(OWNER, listOf("c1")).isEmpty())
        assertNotNull(edges.unreferencedSince(OWNER, "c1"), "the clock is kept in off too")
        assertTrue(stored(CHUNK_A))
    }

    @Test
    fun dryRun_logsWhatItWouldFree_andFreesNothing() = env {
        deletedContent()
        now += T
        val report = job(ContentSweepMode.DRY_RUN).runOnce()
        assertEquals(1, report.candidates)
        assertEquals(2, report.wouldFreeRefs)
        assertEquals(110L, report.wouldFreeBytes)
        val line = logs.single { "owner=$OWNER" in it }
        assertTrue("wouldFree=1" in line && "refs=2" in line && "bytes=110" in line && "c1" in line, line)
        assertTrue(freeing.freedAmong(OWNER, listOf("c1")).isEmpty())
        assertEquals(setOf(CHUNK_A, manifestRefOf("c1")), refs.refsOf(OWNER, "c1"))
        assertTrue(stored(CHUNK_A))
        assertNull(freeing.seededAt(), "dry_run never seeds")
    }

    @Test
    fun dryRun_reportsTheBacklogArmingWouldSeed() = env {
        upload("never-held", CHUNK_A)
        val report = job(ContentSweepMode.DRY_RUN).runOnce()
        assertEquals(1, report.pendingSeed)
        assertTrue(logs.any { "would seed 1" in it }, logs.toString())
    }

    @Test
    fun armed_freesAfterT_andNotBefore_andTheDrainDeletesTheBytes() = env {
        val start = now
        deletedContent()
        now = start + T - 1
        val early = job(ContentSweepMode.ARMED).runOnce()
        assertEquals(0, early.freed)
        assertTrue(stored(CHUNK_A))

        now = start + T
        val report = job(ContentSweepMode.ARMED).runOnce()
        assertEquals(1, report.freed)
        assertEquals(2, report.drainedBlobs)
        assertEquals(110L, report.drainedBytes)
        assertEquals(setOf("c1"), freeing.freedAmong(OWNER, listOf("c1")))
        assertNull(edges.unreferencedSince(OWNER, "c1"))
        assertFalse(stored(CHUNK_A), "the drain deleted the chunk")
        assertFalse(stored(manifestRefOf("c1")), "and the manifest")
        assertEquals(0L, blobs.bytesUsed(OWNER))
        assertTrue(logs.any { "freed=1" in it }, logs.toString())
    }

    @Test
    fun armed_aChunkSharedWithLiveContent_isSparedByTheDrain() = env {
        deletedContent("c1", CHUNK_A)
        upload("c2", CHUNK_B)
        refs.declareRefs(OWNER, "c2", listOf(CHUNK_A, CHUNK_B, manifestRefOf("c2")))
        edge("h2", 1, "c2")
        now += T
        job(ContentSweepMode.ARMED).runOnce()
        assertTrue(stored(CHUNK_A), "c2 still describes it")
        assertFalse(stored(manifestRefOf("c1")))
    }

    // ---- the race ------------------------------------------------------------------------------

    @Test
    fun aPushBetweenSelectionAndFree_wins_andTheBytesStay() = env {
        deletedContent()
        now += T
        // The push lands after step 1 selected c1 and before step 3 frees it.
        val racing = object : ContentEdgeRegistry by edges {
            override fun expiredUnreferenced(cutoffMs: Long, limit: Int) =
                edges.expiredUnreferenced(cutoffMs, limit).also { edge("h2", 5, "c1") }
        }
        val report = job(ContentSweepMode.ARMED, edges = racing).runOnce()
        assertEquals(1, report.candidates)
        assertEquals(0, report.freed)
        assertEquals(1, report.notFreed)
        assertTrue(freeing.freedAmong(OWNER, listOf("c1")).isEmpty(), "c1 is live")
        assertEquals(setOf(CHUNK_A, manifestRefOf("c1")), refs.refsOf(OWNER, "c1"))
        assertTrue(stored(CHUNK_A) && stored(manifestRefOf("c1")), "bytes intact")
        assertNull(edges.unreferencedSince(OWNER, "c1"), "the push cleared the clock")
    }

    // ---- the seed (D4) -------------------------------------------------------------------------

    @Test
    fun theFirstArmedRun_seedsDescribedZeroEdgeContentOnly_andFreesItOneTLater() = env {
        upload("backlog", CHUNK_A) // described, never held: ST-3b's stalled backlog
        upload("live", CHUNK_B)
        edge("h1", 1, "live")
        edge("h9", 1, "edge-only") // never described: G4, stays outside the cycle

        val first = job(ContentSweepMode.ARMED).runOnce()
        assertEquals(1, first.seeded)
        assertEquals(0, first.freed, "seeded now, so it enters T rather than going at once")
        assertEquals(now, edges.unreferencedSince(OWNER, "backlog"))
        assertNull(edges.unreferencedSince(OWNER, "live"))
        assertNull(edges.unreferencedSince(OWNER, "edge-only"))

        now += T
        val later = job(ContentSweepMode.ARMED).runOnce() // a fresh job: as after a restart
        assertNull(later.seeded, "seeded once per relay, not per process")
        assertEquals(1, later.freed)
        assertFalse(stored(CHUNK_A))
        assertTrue(stored(CHUNK_B))
    }

    // ---- queue ABA (§10.10) ------------------------------------------------------------------------

    @Test
    fun freeReviveFree_withinOneCadence_neverDeletesTheRevivedBytes() = env {
        deletedContent()
        assertEquals(ContentFreeing.FreeResult.FREED, freeing.free(OWNER, "c1", now))
        // The revive: fresh chunk under a new CEK, the manifest re-PUT (its ref is derived from the id,
        // so it is the very ref still queued), then the push's revive and edge in one transaction.
        blobs.put(OWNER, CHUNK_B, ByteArray(50), nowMs = now)
        blobs.put(OWNER, manifestRefOf("c1"), ByteArray(11), nowMs = now)
        freeing.atomically {
            assertTrue(freeing.unrevivableRefs(OWNER, listOf(CHUNK_B, manifestRefOf("c1"))).isEmpty())
            freeing.revive(OWNER, "c1", listOf(CHUNK_B, manifestRefOf("c1")))
            edge("h2", 5, "c1")
        }
        // The drain of the FIRST free runs only now: it must take the old chunk and nothing revived.
        drainer.drain(OWNER)
        assertFalse(stored(CHUNK_A), "the first generation's chunk goes")
        assertTrue(stored(CHUNK_B), "the revived chunk stays")
        assertTrue(stored(manifestRefOf("c1")), "the re-PUT manifest stays: its PUT dequeued it")
        assertFalse(queued(manifestRefOf("c1")))

        // And the second free, once c1 is unreferenced again and T has passed, frees the new generation.
        edge("h2", 6, null)
        now += T
        val second = job(ContentSweepMode.ARMED).runOnce()
        assertEquals(1, second.freed)
        assertFalse(stored(CHUNK_B))
        assertFalse(stored(manifestRefOf("c1")))
    }

    @Test
    fun theDrainHoldsTheStoreLockAcrossABatch_soARevivesRePutWaits_andItsBytesSurvive() {
        val inDelete = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gated: (File) -> BlobStore = { dir ->
            object : BlobStore by FileBlobStore(dir) {
                override fun deleteAll(keys: Collection<String>) {
                    inDelete.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    FileBlobStore(dir).deleteAll(keys)
                }
            }
        }
        env(gated) {
            deletedContent()
            now += T
            val drained = thread { job(ContentSweepMode.ARMED).runOnce() }
            assertTrue(inDelete.await(10, TimeUnit.SECONDS), "the drain reached its byte delete")

            // The revive's re-PUT of the manifest (the same derived ref the batch is deleting).
            val rePut = thread { blobs.put(OWNER, manifestRefOf("c1"), ByteArray(12), nowMs = now) }
            rePut.join(300)
            assertTrue(rePut.isAlive, "⚠⚠ the PUT must wait for the batch: the drain holds the store lock")

            release.countDown()
            drained.join(10_000)
            rePut.join(10_000)
            assertTrue(stored(manifestRefOf("c1")), "the re-PUT landed after the delete, so its bytes stand")
            assertFalse(queued(manifestRefOf("c1")))
            assertTrue(freeing.unrevivableRefs(OWNER, listOf(manifestRefOf("c1"))).isEmpty(), "a revive may rely on it")
        }
    }

    // ---- status, and the in-memory relay ------------------------------------------------------------

    @Test
    fun theStatusReportsModeTLastRunAndTotals() = env {
        deletedContent()
        now += T
        val job = job(ContentSweepMode.ARMED)
        job.runOnce()
        val status = job.status()
        assertEquals("armed", status.mode)
        assertEquals(T, status.graceMs)
        assertEquals(true, status.durableDrain)
        assertEquals(1L, status.totals.freed)
        assertEquals(2L, status.totals.drainedBlobs)
        assertEquals(1, status.lastRun?.freed)
        assertEquals(0, status.unreferenced)
    }

    @Test
    fun inMemory_armed_freesLogically_andHasNothingToDrain() {
        var now = 1_000L
        val edges = ContentEdgeStore { now }
        val refs = ContentRefStore()
        val blobs = ContentBlobStore()
        val freeing = ContentFreedStore(edges, refs, blobs) { now }
        refs.declareRefs(OWNER, "c1", listOf(CHUNK_A))
        edges.writeEdges(OWNER, "h1", HlcStamp(1, 0, "d"), listOf(EdgeWrite("p", "c1")))
        edges.writeEdges(OWNER, "h1", HlcStamp(2, 0, "d"), listOf(EdgeWrite("p", null)))
        now += T
        val job = ContentSweepJob(ContentSweepMode.ARMED, T, 60_000, edges, freeing, refs, blobs, drainer = null, clock = { now })
        assertEquals(1, job.runOnce().freed)
        assertEquals(setOf("c1"), freeing.freedAmong(OWNER, listOf("c1")))
        assertFalse(job.status().durableDrain)
    }

    private companion object {
        const val OWNER = "alice"
        const val T = 15 * 60_000L
        val CHUNK_A = "blob-" + "a".repeat(64)
        val CHUNK_B = "blob-" + "b".repeat(64)
    }
}
