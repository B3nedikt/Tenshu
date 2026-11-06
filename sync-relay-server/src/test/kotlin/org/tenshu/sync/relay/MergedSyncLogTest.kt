package org.tenshu.sync.relay

import org.tenshu.core.common.module.coreCommonModule
import org.tenshu.core.common.proxy.createDefaultSchemaRegistry
import org.tenshu.core.core.DataFactory
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.relay.storage.RelayBinaryCodec
import org.tenshu.sync.relay.storage.RelayDatabase
import org.tenshu.sync.relay.storage.SqliteSyncMailbox
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The merged lane (`sync-lane-cursor-split.md` Option C / L3).
 *
 * ⚠ **Every test runs against BOTH backends.** The in-memory mailbox merges two lists; the
 * SQLite one runs a `UNION ALL … ORDER BY seq LIMIT`. They are entirely different code
 * expressing one contract, and a divergence would show up as data loss only in production —
 * the deployment that uses SQLite. Parameterising over the pair is the cheapest way to make
 * "these two agree" a thing the suite actually checks rather than a thing we hope for.
 */
class MergedSyncLogTest {

    private lateinit var dbFile: File
    private var openDb: RelayDatabase? = null

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("relay-merged-log-test", ".db")
    }

    @AfterTest
    fun teardown() {
        openDb?.close()
        dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
    }

    /** Runs [body] against the in-memory backend and then a fresh SQLite one. */
    private fun bothBackends(body: (MailboxStore, TContext) -> Unit) {
        body(SyncMailbox(), ctx())

        val db = RelayDatabase("jdbc:sqlite:${dbFile.absolutePath}")
        openDb = db
        body(SqliteSyncMailbox(db, RelayBinaryCodec()), ctx())
    }

    @Test
    fun onePageIsCutAcrossBothKinds_soOneCursorCoversBoth() = bothBackends { mb, ctx ->
        // Interleave so neither kind is a contiguous run: any implementation that pages one
        // kind and then the other produces a different answer here.
        mb.pushBlock("alice", block(ctx, "b1"))          // seq 1
        mb.pushTombstone("alice", tombstone(ctx, 10L))   // seq 2
        mb.pushBlock("alice", block(ctx, "b2"))          // seq 3
        mb.pushTombstone("alice", tombstone(ctx, 20L))   // seq 4
        mb.pushBlock("alice", block(ctx, "b3"))          // seq 5

        val page = mb.getSyncLog("alice", sinceTimestamp = 0, limit = 3)

        // The page is the first 3 entries of the MERGED order — seqs 1,2,3 — which is two
        // blocks and one tombstone, not three of either.
        assertEquals(listOf("b1", "b2"), page.blocks.map { it.blockId.get() })
        assertEquals(1, page.tombstones.size)
        assertEquals(3L, page.nextCursor, "cursor must be the merged prefix's end")
        assertTrue(page.hasMore)
    }

    @Test
    fun theDenseLaneCanNoLongerThrottleTheSparseOne() = bothBackends { mb, ctx ->
        // THE BUG, IN ITS ORIGINAL SHAPE (§2.1). On device, 500 blocks spanned ~56 000 seq
        // while 500 tombstones spanned exactly 500, so the shared cursor advanced +500/tick
        // and re-delivered the same blocks ~85 times.
        //
        // Here: 40 tombstones pushed first (dense, low seqs), then 5 blocks (sparse, high
        // seqs). Under two independent lanes with a `min` ceiling the block lane would be
        // pinned behind the tombstone backlog. Under one merged log the question cannot even
        // be posed — there is one order, and paging it walks straight through.
        repeat(40) { mb.pushTombstone("alice", tombstone(ctx, 100L + it)) }
        repeat(5) { mb.pushBlock("alice", block(ctx, "b$it")) }

        var cursor = 0L
        var ticks = 0
        val seenBlocks = mutableListOf<String>()
        var seenTombstones = 0
        while (true) {
            val page = mb.getSyncLog("alice", sinceTimestamp = cursor, limit = 10)
            seenBlocks += page.blocks.map { it.blockId.get() }
            seenTombstones += page.tombstones.size
            ticks++
            check(ticks < 50) { "paging did not terminate — cursor stuck at $cursor" }
            if (!page.hasMore) break
            assertTrue(page.nextCursor > cursor, "a page with more behind it must advance")
            cursor = page.nextCursor
        }

        // Everything arrives exactly once, and the whole 45-entry log takes ceil(45/10) ticks
        // — the page limit is the only thing bounding progress, which is the point.
        assertEquals((0 until 5).map { "b$it" }, seenBlocks)
        assertEquals(40, seenTombstones)
        assertEquals(5, ticks)
    }

    @Test
    fun hasMoreIsExact_evenWhenTheFinalPageIsExactlyFull() = bothBackends { mb, ctx ->
        // The L2 defect this slice gets to fix for free: `rows.size >= limit` reports an
        // exactly-full FINAL page as truncated, costing a wasted tick. The relay knows.
        repeat(3) { mb.pushBlock("alice", block(ctx, "b$it")) }
        repeat(3) { mb.pushTombstone("alice", tombstone(ctx, 200L + it)) }

        val exactlyFull = mb.getSyncLog("alice", sinceTimestamp = 0, limit = 6)
        assertEquals(3, exactlyFull.blocks.size)
        assertEquals(3, exactlyFull.tombstones.size)
        assertFalse(
            exactlyFull.hasMore,
            "a full page that happens to be the last one must NOT report more — that is the " +
                "false positive the row-count inference cannot avoid"
        )

        val truncated = mb.getSyncLog("alice", sinceTimestamp = 0, limit = 5)
        assertTrue(truncated.hasMore)
    }

    @Test
    fun anEmptyPageHoldsTheCursorWhereItWas() = bothBackends { mb, ctx ->
        // Must not jump to the relay's live tip: the counter is shared across owners, so a
        // tip-valued cursor would skip other owners' interleaved seqs. Same rule as the two
        // single-kind lanes.
        mb.pushBlock("alice", block(ctx, "b1"))
        val drained = mb.getSyncLog("alice", sinceTimestamp = 0)
        assertEquals(1L, drained.nextCursor)

        val empty = mb.getSyncLog("alice", sinceTimestamp = drained.nextCursor)
        assertTrue(empty.blocks.isEmpty() && empty.tombstones.isEmpty())
        assertEquals(drained.nextCursor, empty.nextCursor)
        assertFalse(empty.hasMore)
    }

    @Test
    fun oneOwnersLogIsNotAnothers() = bothBackends { mb, ctx ->
        // The seq counter is global, so the filter has to be by owner — a merged query is
        // exactly where that could regress, since it touches two tables at once.
        mb.pushBlock("alice", block(ctx, "a1", owner = "alice"))
        mb.pushTombstone("bob", tombstone(ctx, 10L, owner = "bob"))
        mb.pushBlock("bob", block(ctx, "b1", owner = "bob"))

        val alice = mb.getSyncLog("alice", sinceTimestamp = 0)
        assertEquals(listOf("a1"), alice.blocks.map { it.blockId.get() })
        assertTrue(alice.tombstones.isEmpty())

        val bob = mb.getSyncLog("bob", sinceTimestamp = 0)
        assertEquals(listOf("b1"), bob.blocks.map { it.blockId.get() })
        assertEquals(1, bob.tombstones.size)
    }

    @Test
    fun theMergedPageAgreesWithWhatTheTwoSeparateLanesWouldHaveServed() = bothBackends { mb, ctx ->
        // The migration-safety property: a device that switches lanes must not observe a
        // different log. Drained to exhaustion, both routes must yield the same content —
        // only the paging differs.
        repeat(4) { mb.pushBlock("alice", block(ctx, "b$it")) }
        repeat(4) { mb.pushTombstone("alice", tombstone(ctx, 300L + it)) }

        val merged = mb.getSyncLog("alice", sinceTimestamp = 0, limit = 100)
        val viaBlocks = mb.getChanges("alice", sinceTimestamp = 0, limit = 100)
        val viaTombstones = mb.getTombstones("alice", sinceTimestamp = 0, limit = 100)

        assertEquals(viaBlocks.blocks.map { it.blockId.get() }, merged.blocks.map { it.blockId.get() })
        assertEquals(
            viaTombstones.tombstones.map { it.blockId.get() },
            merged.tombstones.map { it.blockId.get() }
        )
        // And the merged cursor is the max of the two, because it covers both.
        assertEquals(maxOf(viaBlocks.nextCursor, viaTombstones.nextCursor), merged.nextCursor)
    }

    @Test
    fun theBackwardWalkIsNewestFirstAndLossless() = bothBackends { mb, ctx ->
        // ⚠ The point of C2: a rebuilding device must see RECENT state first. Walking down from the
        // tip must still visit every row exactly once — reordering that loses rows would be worse
        // than the slowness it fixes.
        repeat(4) { mb.pushBlock("alice", block(ctx, "b$it")) }
        repeat(4) { mb.pushTombstone("alice", tombstone(ctx, 400L + it)) }

        val newest = mb.getSyncLogBefore("alice", beforeSeq = Long.MAX_VALUE, limit = 3)
        assertTrue(newest.hasMore)
        // The tip end of the log is tombstones (pushed last), so a NEWEST-first page must contain
        // them — a forward page of the same size would contain only blocks.
        assertEquals(3, newest.tombstones.size, "the first page must be the newest rows")
        assertTrue(newest.blocks.isEmpty())

        val seenBlocks = mutableListOf<String>()
        var seenTombstones = 0
        var floor = Long.MAX_VALUE
        var pages = 0
        while (true) {
            val page = mb.getSyncLogBefore("alice", beforeSeq = floor, limit = 3)
            seenBlocks += page.blocks.map { it.blockId.get() }
            seenTombstones += page.tombstones.size
            pages++
            check(pages < 20) { "backward paging did not terminate — floor stuck at $floor" }
            if (!page.hasMore) break
            assertTrue(page.nextCursor < floor, "each page must move the floor DOWN")
            floor = page.nextCursor
        }

        assertEquals((0 until 4).map { "b$it" }.toSet(), seenBlocks.toSet(), "every block, once")
        assertEquals(4, seenTombstones)
    }

    @Test
    fun theTwoDirectionsPartitionTheLog_theyDoNotOverlap() = bothBackends { mb, ctx ->
        // ⚠⚠ The invariant the client's two cursors rest on: forward from `since` and backward from
        // `before` must MEET, not overlap. If they overlapped, rows would be applied twice (wasteful
        // but safe); if they left a gap, rows would be missed (data loss). Neither is acceptable, so
        // pin it directly: seq 4 is the boundary.
        repeat(8) { mb.pushBlock("alice", block(ctx, "b$it")) } // seqs 1..8

        val forward = mb.getSyncLog("alice", sinceTimestamp = 0, limit = 4)   // seqs 1..4
        val backward = mb.getSyncLogBefore("alice", beforeSeq = 5, limit = 4) // seqs 1..4? no: <5

        val fwd = forward.blocks.map { it.blockId.get() }.toSet()
        val bwd = backward.blocks.map { it.blockId.get() }.toSet()
        assertEquals(fwd, bwd, "forward-to-N and backward-from-N+1 must describe the same rows")

        // And the complement: backward from the tip stops exactly where forward reached.
        val tail = mb.getSyncLogBefore("alice", beforeSeq = Long.MAX_VALUE, limit = 4)
        val tailIds = tail.blocks.map { it.blockId.get() }.toSet()
        assertTrue(
            (tailIds intersect fwd).isEmpty(),
            "the newest 4 and the oldest 4 of an 8-row log must not overlap"
        )
        assertEquals(8, (tailIds + fwd).size, "and together they must cover it")
    }

    @Test
    fun anExhaustedBackwardWalkReportsNoMore() = bothBackends { mb, ctx ->
        // The client's termination signal. Without it the walk would re-request page 1 forever.
        mb.pushBlock("alice", block(ctx, "only"))

        val page = mb.getSyncLogBefore("alice", beforeSeq = Long.MAX_VALUE, limit = 10)
        assertEquals(1, page.blocks.size)
        assertFalse(page.hasMore, "reaching the start of the log must terminate the walk")
    }

    @Test
    fun aBackwardWalkBelowTheStartIsEmptyAndTerminates() = bothBackends { mb, ctx ->
        mb.pushBlock("alice", block(ctx, "b1"))

        val page = mb.getSyncLogBefore("alice", beforeSeq = 1, limit = 10)
        assertTrue(page.blocks.isEmpty() && page.tombstones.isEmpty())
        assertFalse(page.hasMore)
    }

    // --- fixtures ---

    private fun ctx(): TContext {
        val ctx = object : TContext {
            override var components: Map<String, TComponent> = emptyMap()
        }
        val registry = createDefaultSchemaRegistry(
            tContextProvider = { ctx },
            extraConfigs = listOf(coreCommonModule, syncServiceCommonModule, identityServiceCommonModule),
        )
        ctx.components = mapOf(
            SchemaProvider.COMPONENT_ID to registry,
            DataFactory.COMPONENT_ID to registry,
        )
        return ctx
    }

    private fun block(
        ctx: TContext,
        blockId: String,
        owner: String = "alice",
    ): SyncBlock = SyncBlock.create(
        context = ctx,
        blockId = blockId,
        ownerId = owner,
        hlc = HybridLogicalClock.create(ctx, 100L, 0, "deviceA"),
        encryptedPayload = "payload-$blockId".encodeToByteArray(),
        targetEntityIdHash = "H-$blockId",
    )

    private fun tombstone(
        ctx: TContext,
        hlc: Long,
        owner: String = "alice",
    ): DeleteBlock = DeleteBlock.create(
        context = ctx,
        blockId = "tomb-$owner-$hlc",
        ownerId = owner,
        hlc = HybridLogicalClock.create(ctx, hlc, 0, "deviceA"),
        targetEntityIdHash = "H-tomb-$hlc",
        targetEntityType = "tenshu/test/entity",
    )
}
