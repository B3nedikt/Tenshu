package org.tenshu.sync.relay.storage

import org.tenshu.core.common.util.HlcStamp
import org.tenshu.sync.relay.mailbox.ContentBlobRegistry
import org.tenshu.sync.relay.mailbox.ContentBlobStore
import org.tenshu.sync.relay.mailbox.ContentEdgeRegistry
import org.tenshu.sync.relay.mailbox.ContentEdgeRegistry.EdgeWrite
import org.tenshu.sync.relay.mailbox.ContentEdgeStore
import org.tenshu.sync.relay.mailbox.ContentFreedStore
import org.tenshu.sync.relay.mailbox.ContentFreeing
import org.tenshu.sync.relay.mailbox.ContentRefRegistry
import org.tenshu.sync.relay.mailbox.ContentRefStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CR-2 B5r's G2 clock (`content-plane-cr2-b5-spec.md` §1.1, §3 "Clock" and "Seed", §1.4's held set),
 * pinned against BOTH backends: `content_unreferenced` and the in-memory twin.
 *
 * Every way a content's live-edge count goes from one to zero sets `since`: an overwrite, a `none`, a
 * tombstone, a cascade, a reap. A new live edge clears it; a free clears it; two holders losing one sets
 * nothing; a stale write changes nothing.
 */
abstract class ContentClockContract {

    protected class Plane(
        val edges: ContentEdgeRegistry,
        val refs: ContentRefRegistry,
        val blobs: ContentBlobRegistry,
        val freeing: ContentFreeing,
        val clock: Clock,
    )

    protected class Clock(var now: Long = 1_000)

    protected abstract fun <T> withPlane(body: Plane.() -> T): T

    /** Whether this backend has a free queue (SQLite): the held set then includes queued refs. */
    protected abstract val hasQueue: Boolean

    private fun hlc(physical: Long) = HlcStamp(physical, 0, "dev-a")

    private fun Plane.edge(holder: String, physical: Long, vararg edges: Pair<String, String?>) =
        this.edges.writeEdges(OWNER, holder, hlc(physical), edges.map { (p, c) -> EdgeWrite(p, c) })

    private fun Plane.since(contentId: String) = edges.unreferencedSince(OWNER, contentId)

    @Test
    fun oneToZero_byOverwrite_stampsTheOldContent_andNotTheNew() = withPlane {
        edge("h1", 1, "p" to "c1")
        clock.now = 5_000
        edge("h1", 2, "p" to "c2")
        assertEquals(5_000L, since("c1"))
        assertNull(since("c2"))
    }

    @Test
    fun oneToZero_byNone_stampsIt() = withPlane {
        edge("h1", 1, "p" to "c1")
        clock.now = 7_000
        edge("h1", 2, "p" to null)
        assertEquals(7_000L, since("c1"))
    }

    @Test
    fun oneToZero_byTombstone_andByItsCascade_stampsEveryContentLeftWithoutAnEdge() = withPlane {
        edge("folder", 1, "p" to "cf")
        edge("child-1", 1, "p" to "c1")
        edge("child-2", 1, "p" to "c2")
        clock.now = 9_000
        // A tombstone of `folder` carrying its cascade: one removal, three holders.
        edges.removeHolders(OWNER, listOf("folder", "child-1", "child-2"), hlc(10))
        assertEquals(9_000L, since("cf"))
        assertEquals(9_000L, since("c1"))
        assertEquals(9_000L, since("c2"))
    }

    @Test
    fun oneToZero_byReap_stampsIt() = withPlane {
        edge("child", 1, "p" to "c1")
        clock.now = 11_000
        edges.removeHolders(OWNER, listOf("child"), hlc(5))
        assertEquals(11_000L, since("c1"))
    }

    @Test
    fun aNewLiveEdge_clearsTheClock() = withPlane {
        edge("h1", 1, "p" to "c1")
        edge("h1", 2, "p" to null)
        assertEquals(1_000L, since("c1"))
        edge("h2", 3, "p" to "c1")
        assertNull(since("c1"), "a holder names it again")
    }

    @Test
    fun twoHolders_removingOne_setsNothing() = withPlane {
        edge("h1", 1, "p" to "c1")
        edge("h2", 1, "p" to "c1")
        edges.removeHolders(OWNER, listOf("h1"), hlc(5))
        assertNull(since("c1"), "h2 still holds it")
        edges.removeHolders(OWNER, listOf("h2"), hlc(5))
        assertEquals(1_000L, since("c1"))
    }

    @Test
    fun aStaleWrite_neitherStampsNorClears() = withPlane {
        edge("h1", 5, "p" to "c1")
        edge("h1", 3, "p" to null) // older: a no-op on the register
        assertNull(since("c1"))
        edges.removeHolders(OWNER, listOf("h1"), hlc(2)) // below the register's stamp: kept
        assertNull(since("c1"))
    }

    @Test
    fun anEarlierSince_isNeverExtended() = withPlane {
        edge("h1", 1, "p" to "c1")
        edge("h1", 2, "p" to null)
        clock.now = 99_000
        assertEquals(false, edges.startClockIfUnreferenced(OWNER, "c1", clock.now))
        assertEquals(1_000L, since("c1"))
    }

    @Test
    fun theFree_clearsTheClock() = withPlane {
        edge("h1", 1, "p" to "c1")
        edge("h1", 2, "p" to null)
        assertEquals(ContentFreeing.FreeResult.FREED, freeing.free(OWNER, "c1"))
        assertNull(since("c1"), "FREED is not UNREFERENCED")
    }

    @Test
    fun aRevive_restartsTheClock_whenNoLiveEdgeHoldsIt_andThePushsEdgeClearsIt() = withPlane {
        blobs.put(OWNER, REF_A, ByteArray(10))
        edge("h1", 1, "p" to "c1")
        edge("h1", 2, "p" to null)
        freeing.free(OWNER, "c1")
        clock.now = 20_000
        freeing.revive(OWNER, "c1", listOf(REF_A))
        assertEquals(20_000L, since("c1"), "described again with no edge: back on the clock, never off it (a leak)")
        edge("h2", 3, "p" to "c1")
        assertNull(since("c1"))
    }

    @Test
    fun theSeed_putsDescribedZeroEdgeContentOnTheClock_once_andNothingElse() = withPlane {
        refs.declareRefs(OWNER, "described-orphan", listOf(REF_A))
        refs.declareRefs(OWNER, "described-live", listOf(REF_B))
        edge("h1", 1, "p" to "described-live")
        edge("h2", 1, "p" to "edge-only") // G4: never described, stays outside
        refs.declareRefs("bob", "bobs-orphan", listOf(REF_A))

        assertEquals(2, freeing.pendingSeedCount())
        assertEquals(2, freeing.seedClockOnce(40_000))
        assertEquals(40_000L, since("described-orphan"))
        assertEquals(40_000L, edges.unreferencedSince("bob", "bobs-orphan"))
        assertNull(since("described-live"))
        assertNull(since("edge-only"))
        assertEquals(40_000L, freeing.seededAt())

        refs.declareRefs(OWNER, "later-orphan", listOf(REF_C))
        assertNull(freeing.seedClockOnce(50_000), "the seed runs once per relay")
        assertNull(since("later-orphan"), "a describe never starts the clock (G4)")
    }

    @Test
    fun heldBytes_countsOnlyRefsNoLiveContentShares() = withPlane {
        blobs.put(OWNER, REF_A, ByteArray(100)) // c-dead only
        blobs.put(OWNER, REF_B, ByteArray(10)) // shared by c-dead and c-live
        blobs.put(OWNER, REF_C, ByteArray(1)) // c-live only
        refs.declareRefs(OWNER, "c-dead", listOf(REF_A, REF_B))
        refs.declareRefs(OWNER, "c-live", listOf(REF_B, REF_C))
        edge("h1", 1, "p" to "c-dead")
        edge("h2", 1, "p" to "c-live")
        assertEquals(0L, freeing.heldBytes(OWNER))

        edge("h1", 2, "p" to null)
        assertEquals(100L, freeing.heldBytes(OWNER), "the shared ref stays charged")

        freeing.free(OWNER, "c-dead")
        // SQLite: the freed refs are queued, the shared one is still described by live content.
        // In-memory: no queue, and the free forgot the refs, so nothing is held any more (spec §1.5).
        assertEquals(if (hasQueue) 100L else 0L, freeing.heldBytes(OWNER))
        assertEquals(0L, freeing.heldBytes("bob"))
    }

    protected companion object {
        const val OWNER = "alice"
        val REF_A = "blob-" + "a".repeat(64)
        val REF_B = "blob-" + "b".repeat(64)
        val REF_C = "blob-" + "c".repeat(64)
    }
}

class InMemoryContentClockTest : ContentClockContract() {
    override val hasQueue = false

    override fun <T> withPlane(body: Plane.() -> T): T {
        val clock = Clock()
        val edges = ContentEdgeStore { clock.now }
        val refs = ContentRefStore()
        val blobs = ContentBlobStore()
        val freeing = ContentFreedStore(edges, refs, blobs) { clock.now }
        return Plane(edges, refs, blobs, freeing, clock).body()
    }
}

class SqliteContentClockTest : ContentClockContract() {
    override val hasQueue = true

    private lateinit var dbFile: File
    private lateinit var blobDir: File

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("relay-clock-test", ".db")
        blobDir = File.createTempFile("relay-clock-blobs", "").also { it.delete(); it.mkdirs() }
    }

    @AfterTest
    fun teardown() {
        dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
        blobDir.deleteRecursively()
    }

    override fun <T> withPlane(body: Plane.() -> T): T =
        RelayDatabase("jdbc:sqlite:${dbFile.absolutePath}").use { db ->
            val clock = Clock()
            val refs = SqliteContentRefStore(db)
            Plane(
                SqliteContentEdgeStore(db) { clock.now },
                refs,
                SqliteContentBlobStore(db, FileBlobStore(blobDir)),
                SqliteContentFreedStore(db, refs) { clock.now },
                clock,
            ).body()
        }

    @Test
    fun theClockAndTheSeedMarker_surviveARestart() {
        withPlane {
            refs.declareRefs(OWNER, "c1", listOf(REF_A))
            freeing.seedClockOnce(3_000)
        }
        withPlane {
            assertEquals(3_000L, edges.unreferencedSince(OWNER, "c1"))
            assertEquals(3_000L, freeing.seededAt())
            assertNull(freeing.seedClockOnce(4_000))
        }
    }
}
