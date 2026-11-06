package org.tenshu.sync.relay.mailbox

import org.tenshu.sync.relay.seedEdge

import org.tenshu.sync.relay.storage.FileBlobStore
import org.tenshu.sync.relay.storage.RelayDatabase
import org.tenshu.sync.relay.storage.SqliteContentBlobStore
import org.tenshu.sync.relay.storage.SqliteContentRefStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CR-6e, the inventory (`content-plane-refcount.md` §11.9, track P2).
 *
 * What these pin is the **boundary between the two mechanisms**, because conflating them is this
 * track's recurring failure: CR-5 frees *described* content no edge names, and the inventory measures
 * what is *not described at all*. A blob can be in exactly one of those populations, and a test
 * suite that only ever declares everything would pass while the two were swapped.
 */
class ContentInventoryTest {

    private val owner = "alice"

    private fun blobRef(n: Int) = "blob-%064x".format(n)

    // ---- in-memory triple ------------------------------------------------------------------

    private fun inventory(
        blobs: ContentBlobRegistry,
        refs: ContentRefRegistry,
        edges: ContentEdgeRegistry,
        ownerId: String = owner,
    ) = computeContentInventory(ownerId, blobs, refs, edges)

    @Test
    fun `a blob no declaration reaches is the headline`() {
        val blobs = ContentBlobStore()
        val refs = ContentRefStore()
        val edges = ContentEdgeStore()

        // Two blobs belong to a described content object; the third is an aborted emit's residue.
        blobs.put(owner, blobRef(1), ByteArray(100))
        blobs.put(owner, blobRef(2), ByteArray(200))
        blobs.put(owner, blobRef(3), ByteArray(400))
        refs.declareRefs(owner, "content-a", listOf(blobRef(1), blobRef(2)))
        edges.seedEdge(owner, "holder-1", "file/content", "content-a", physicalTime = 1_000)

        val report = inventory(blobs, refs, edges)

        assertEquals(3, report.blobCount)
        assertEquals(700L, report.blobBytes)
        assertEquals(1, report.undescribedBlobCount, "only the undeclared blob")
        assertEquals(400L, report.undescribedBlobBytes)
        assertEquals(1, report.describedContentCount)
        assertEquals(0, report.candidateContentCount, "its holder is alive")
    }

    /**
     * ⚠⚠ The distinction the whole file exists for. A CR-5 candidate — described, but no live edge
     * — is **not** in the headline: its bytes are described, so CR-5 owns them and CR-6 never would.
     * If this ever flips, the inventory has started double-counting the population CR-5 already frees.
     */
    @Test
    fun `a CR-5 candidate is described and so is not in the headline`() {
        val blobs = ContentBlobStore()
        val refs = ContentRefStore()
        val edges = ContentEdgeStore()

        blobs.put(owner, blobRef(1), ByteArray(100))
        refs.declareRefs(owner, "content-a", listOf(blobRef(1)))
        // No edge at all: the holder died and undeclared, which is exactly CR-5's candidate shape.

        val report = inventory(blobs, refs, edges)

        assertEquals(1, report.candidateContentCount, "described with no live edge")
        assertEquals(0, report.undescribedBlobCount, "described bytes are never the inventory's")
        assertEquals(0L, report.undescribedBlobBytes)
    }

    @Test
    fun `computing the inventory frees nothing`() {
        val blobs = ContentBlobStore()
        val refs = ContentRefStore()
        val edges = ContentEdgeStore()
        blobs.put(owner, blobRef(1), ByteArray(100))
        blobs.put(owner, blobRef(2), ByteArray(250))

        repeat(3) { inventory(blobs, refs, edges) }

        assertEquals(2, blobs.blobCount(owner), "the inventory is a read")
        assertEquals(350L, blobs.bytesUsed(owner))
        assertEquals(2, inventory(blobs, refs, edges).undescribedBlobCount)
    }

    @Test
    fun `another owner's blobs are never counted`() {
        val blobs = ContentBlobStore()
        val refs = ContentRefStore()
        val edges = ContentEdgeStore()
        blobs.put(owner, blobRef(1), ByteArray(100))
        blobs.put("mallory", blobRef(2), ByteArray(9_999))
        refs.declareRefs("mallory", "content-m", listOf(blobRef(2)))

        val report = inventory(blobs, refs, edges)

        assertEquals(1, report.blobCount)
        assertEquals(100L, report.blobBytes)
        assertEquals(0, report.describedContentCount, "mallory's declaration is not alice's")
        assertEquals(1, report.undescribedBlobCount)
    }

    @Test
    fun `an owner with no blobs still reports its declarations`() {
        val blobs = ContentBlobStore()
        val refs = ContentRefStore()
        val edges = ContentEdgeStore()
        // Bytes live on another device; the declaration reached the relay from here.
        refs.declareRefs(owner, "content-a", listOf(blobRef(1)))

        val report = inventory(blobs, refs, edges)

        assertEquals(0, report.blobCount)
        assertEquals(1, report.describedContentCount)
        assertEquals(1, report.candidateContentCount)
        assertEquals(0, report.undescribedBlobCount)
    }

    /**
     * §11.5's `W`. An in-flight emit has `PUT` its chunks but not declared, so it is undescribed —
     * and must be **reported, not excluded**, or the headline hides bytes behind whoever is
     * uploading.
     */
    @Test
    fun `recently touched blobs are reported but still counted as undescribed`() {
        val blobs = ContentBlobStore()
        val refs = ContentRefStore()
        val edges = ContentEdgeStore()
        val now = 10_000_000L
        blobs.put(owner, blobRef(1), ByteArray(100), nowMs = now - 5_000_000L)  // long settled
        blobs.put(owner, blobRef(2), ByteArray(250), nowMs = now - 1_000L)      // mid-emit

        val report = computeContentInventory(owner, blobs, refs, edges, nowMs = now, recentWindowMs = 60_000L)

        assertEquals(2, report.undescribedBlobCount, "the recent one is NOT subtracted")
        assertEquals(350L, report.undescribedBlobBytes)
        assertEquals(1, report.recentlyTouchedBlobCount, "only the one inside the window")
        assertEquals(250L, report.recentlyTouchedBlobBytes)
    }

    /**
     * ⚠⚠ The documented over-count, pinned so nobody later "optimises" the window into an exclusion.
     * `last_referenced_at` is refreshed by `get` as well as `put`, so **downloading** an old blob
     * makes it look mid-upload. Subtracting on this signal would hide live old bytes behind a read.
     */
    @Test
    fun `a download makes an old blob look recent, which is why it is never excluded`() {
        val blobs = ContentBlobStore()
        val refs = ContentRefStore()
        val edges = ContentEdgeStore()
        val now = 10_000_000L
        blobs.put(owner, blobRef(1), ByteArray(100), nowMs = now - 5_000_000L)

        val before = computeContentInventory(owner, blobs, refs, edges, nowMs = now, recentWindowMs = 60_000L)
        assertEquals(0, before.recentlyTouchedBlobCount)

        blobs.get(owner, blobRef(1), nowMs = now - 1_000L)   // a read, not an upload

        val after = computeContentInventory(owner, blobs, refs, edges, nowMs = now, recentWindowMs = 60_000L)
        assertEquals(1, after.recentlyTouchedBlobCount, "a GET refreshed the touch time")
        assertEquals(1, after.undescribedBlobCount, "and the headline is unmoved — reported, not excluded")
    }

    // ---- the legacy byte_len population (SQLite only) ---------------------------------------

    private lateinit var dbFile: File
    private lateinit var blobDir: File
    private var db: RelayDatabase? = null

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("relay-inventory", ".db")
        blobDir = File.createTempFile("relay-inventory-blobs", "").also { it.delete(); it.mkdirs() }
    }

    @AfterTest
    fun teardown() {
        db?.close()
        dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
        blobDir.deleteRecursively()
    }

    /**
     * A row recording 0 bytes (a stored zero-length body — no legitimate blob is one) must be reported
     * separately rather than silently summed into a byte figure that reads as "almost nothing is
     * stranded" — the exact shape of a false number.
     */
    @Test
    fun `zero-length rows are counted separately rather than summed as zero`() {
        val database = RelayDatabase("jdbc:sqlite:${dbFile.absolutePath}").also { db = it }
        val blobs = SqliteContentBlobStore(database, FileBlobStore(blobDir))
        val refs = SqliteContentRefStore(database)
        val edges = ContentEdgeStore()

        blobs.put(owner, blobRef(1), ByteArray(100), nowMs = 1_000)
        blobs.put(owner, blobRef(2), ByteArray(0), nowMs = 1_000)

        val report = computeContentInventory(owner, blobs, refs, edges)

        assertEquals(2, report.blobCount)
        assertEquals(100L, report.blobBytes, "the zero-length row contributes no bytes")
        assertEquals(1, report.zeroLengthBlobCount, "and is named, so the total reads as a lower bound")
        assertEquals(2, report.undescribedBlobCount, "nothing is declared")
        assertEquals(1, report.zeroLengthUndescribedBlobCount)
    }
}
