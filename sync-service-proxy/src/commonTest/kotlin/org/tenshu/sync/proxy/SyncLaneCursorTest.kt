package org.tenshu.sync.proxy

import org.tenshu.core.common.SYNC_CHANGES_PAGE_LIMIT
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Per-lane cursor advance (`sync-lane-cursor-split.md` L1) — the replacement for
 * `AdvancedCursorTest`.
 *
 * The invariant is unchanged and still the point: **a cursor never passes a seq the relay did
 * not actually serve that lane.** What changed is how it is upheld. The shared cursor needed a
 * `min`-of-ceilings rule because one number had to be safe for two lanes; per-lane cursors get
 * it by construction, because no lane's cursor is ever moved by the other lane's result.
 *
 * These tests model the exact expression in `drainAndPull`:
 *
 *     lastSyncTimestamp          = max(blocksSince,     blocks.cursor)
 *     lastTombstoneSyncTimestamp = max(tombstonesSince, tombstones.cursor)
 */
class SyncLaneCursorTest {

    /** The advance rule for one lane, mirroring `drainAndPull`. */
    private fun advance(since: Long, outcome: PullOutcome): Long = max(since, outcome.cursor)

    private fun page(cursor: Long) = PullOutcome(cursor = cursor, rowCount = SYNC_CHANGES_PAGE_LIMIT)
    private fun partial(cursor: Long, rows: Int = 3) = PullOutcome(cursor = cursor, rowCount = rows)
    private fun empty(since: Long) = PullOutcome.exhausted(since)

    @Test
    fun aLaneAdvancesToWhatItWasServed() {
        assertEquals(1_000L, advance(since = 0, outcome = partial(1_000)))
        assertEquals(1_000L, advance(since = 0, outcome = page(1_000)))
    }

    @Test
    fun aTruncatedLaneStillOnlyAdvancesToItsLastDeliveredRow() {
        // THE INVARIANT THE OLD CEILING EXISTED FOR. A full page means rows beyond `cursor`
        // exist and were not sent — the cursor must land ON the last delivered row so the
        // remainder is re-asked for next tick, never skipped.
        val served = page(1_000)
        assertEquals(1_000L, advance(since = 0, outcome = served))
        assertTrue(served.truncated, "a full page must read as truncated")
    }

    @Test
    fun theOtherLaneCannotDragThisOneForward() {
        // THE BUG L1 FIXES, AND ITS MIRROR. Blocks reach 71 391 while tombstones were served
        // only to 29 544. Each lane keeps its own position: blocks must NOT be held back to
        // 29 544 (that was the +500/tick throttle), and tombstones must NOT be dragged to
        // 71 391 (that would skip ~42 000 seq of undelivered tombstones).
        val blocks = page(71_391)
        val tombstones = page(29_544)

        assertEquals(71_391L, advance(since = 29_044, outcome = blocks))
        assertEquals(29_544L, advance(since = 29_044, outcome = tombstones))
    }

    @Test
    fun anEmptyLaneHoldsItsOwnCursorAndNothingElse() {
        // An empty lane reports `since` verbatim, so it stands still — and, being its own
        // cursor now, it cannot wedge the other lane the way a shared `min` once could.
        assertEquals(5_000L, advance(since = 5_000, outcome = empty(5_000)))
    }

    @Test
    fun neverRegressesBelowSince() {
        // A failed RPC reports `since` verbatim; a stale/bogus lower cursor must not drag the
        // lane backwards and re-deliver applied history.
        assertEquals(5_000L, advance(since = 5_000, outcome = empty(5_000)))
        assertEquals(5_000L, advance(since = 5_000, outcome = partial(10)))
    }

    @Test
    fun aFullPageIsTheTruncationSignal() {
        // Still the wake-the-scheduler signal even though it no longer feeds a ceiling.
        assertTrue(PullOutcome(cursor = 1, rowCount = SYNC_CHANGES_PAGE_LIMIT).truncated)
        assertFalse(PullOutcome(cursor = 1, rowCount = SYNC_CHANGES_PAGE_LIMIT - 1).truncated)
        assertFalse(PullOutcome.exhausted(1).truncated)

        // Pin the shared rule itself, not just the personal lane's wrapper: `pullSharedRealms`
        // asks `isFullPage` directly (it has no PullOutcome — one lane needs no ceiling), so a
        // change here must move both callers or neither.
        assertTrue(PullOutcome.isFullPage(SYNC_CHANGES_PAGE_LIMIT))
        assertFalse(PullOutcome.isFullPage(SYNC_CHANGES_PAGE_LIMIT - 1))
        assertTrue(
            PullOutcome.isFullPage(SYNC_CHANGES_PAGE_LIMIT + 1),
            "over-full must read as truncated too — a lane must never conclude 'no more' from a " +
                "page bigger than the cap"
        )
    }

    @Test
    fun aSparseLaneIsNoLongerThrottledByADenseOne() {
        // The regression in numbers, straight from the on-device capture (§2.1): blocks are
        // sparse (500 rows spanning ~42 000 seq), tombstones dense (500 rows spanning 500).
        // Under the old shared cursor both advanced +500/tick; each lane now runs at its own
        // pace, so the block backlog closes in ONE tick instead of ~85.
        var blockCursor = 29_044L
        var tombCursor = 29_044L
        val blockTip = 71_391L

        blockCursor = advance(blockCursor, PullOutcome(cursor = blockTip, rowCount = SYNC_CHANGES_PAGE_LIMIT))
        tombCursor = advance(tombCursor, PullOutcome(cursor = 29_544L, rowCount = SYNC_CHANGES_PAGE_LIMIT))

        assertEquals(blockTip, blockCursor, "the block lane must not be pinned to the tombstone lane")
        assertEquals(29_544L, tombCursor, "the tombstone lane must still advance only on its own rows")
    }

    @Test
    fun aBacklogDrainsMonotonicallyToTheTip() {
        // Unchanged in spirit from the old suite: a from-zero catch-up must make progress
        // every tick and land exactly on the tip.
        val tip = SYNC_CHANGES_PAGE_LIMIT * 3L + 17
        var cursor = 0L
        var ticks = 0
        while (cursor < tip) {
            val rows = minOf(tip - cursor, SYNC_CHANGES_PAGE_LIMIT.toLong()).toInt()
            val next = advance(cursor, PullOutcome(cursor = cursor + rows, rowCount = rows))
            assertTrue(next > cursor, "cursor stalled at $cursor — a backlog would never drain")
            cursor = next
            ticks++
        }
        assertEquals(tip, cursor)
        assertEquals(4, ticks)
    }
}
