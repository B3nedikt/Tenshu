package app.tenshu.photos

import app.tenshu.photos.model.MotionTurn
import app.tenshu.photos.ui.screens.feed.MotionClipStore
import app.tenshu.photos.ui.screens.feed.MotionRotation
import app.tenshu.photos.ui.screens.feed.MotionRotationRules
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The feed's one rotating player (`photos-video-support.md` §1.2, §5 / M5 — D1, D7).
 *
 * ⚠⚠ The rule under test was *measured* off Google Photos, not designed: one player, document
 * order, wrapping, each tile through once, and a lone tile hands over to itself. The two
 * constraints that make it ours are pinned here too — only a tile whose clip is in hand is a
 * member, and exactly one blob is ever in flight.
 *
 * ⚠ Driven on `backgroundScope` under `runTest`, so every hand-over and every fetch is a
 * deterministic `runCurrent()` away. ⚠⚠ `runCurrent`, not `advanceUntilIdle`: the latter stops as
 * soon as only *background* tasks remain, and everything here is one — it would return at once and
 * every assertion would read a rotation that never started. Nothing here uses virtual time, so
 * "everything runnable now" is the whole schedule.
 */
class MotionRotationTest {

    private fun clip(tag: Char) = ByteArray(64) { tag.code.toByte() }

    /**
     * A fetcher over a fixed set of clips, recording every read. `null` for anything not in it —
     * which is what a byte-less device answers for a clip that has not arrived (§5.3).
     */
    private class Fetcher(private val clips: MutableMap<String, ByteArray>) {
        val reads = mutableListOf<String>()
        suspend fun fetch(path: String): ByteArray? {
            reads += path
            return clips[path]
        }

        fun arrives(path: String, bytes: ByteArray) {
            clips[path] = bytes
        }
    }

    private fun MotionTurn?.pathOrNull() = this?.path

    // ── D1: the rule ────────────────────────────────────────────────────────────────────────

    @Test
    fun aLoneVideoHandsOverToItself() = runTest {
        val fetcher = Fetcher(mutableMapOf("/a.mp4" to clip('a')))
        val rotation = MotionRotation(backgroundScope, fetcher::fetch)

        rotation.setVisible(listOf("/a.mp4"))
        runCurrent()
        val first = assertNotNull(rotation.turn.value)
        assertEquals("/a.mp4", first.path)

        rotation.turnFinished("/a.mp4")
        runCurrent()
        val second = assertNotNull(rotation.turn.value, "with one video on screen, next is itself")
        assertEquals("/a.mp4", second.path)
        // ⚠ Two turns, two identities — the tile keys its player on the turn, so a same-path turn
        // that compared equal would never restart the clip.
        assertNotEquals(first, second)
        assertTrue(second.serial > first.serial)
        assertEquals(listOf("/a.mp4"), fetcher.reads, "in hand after one read — never fetched again")
    }

    @Test
    fun documentOrderWrapping() = runTest {
        val fetcher = Fetcher(mutableMapOf("/a" to clip('a'), "/b" to clip('b'), "/c" to clip('c')))
        val rotation = MotionRotation(backgroundScope, fetcher::fetch)
        rotation.setVisible(listOf("/a", "/b", "/c"))
        runCurrent()

        val played = mutableListOf<String>()
        repeat(4) {
            val turn = assertNotNull(rotation.turn.value)
            played += turn.path
            rotation.turnFinished(turn.path)
            runCurrent()
        }
        assertEquals(listOf("/a", "/b", "/c", "/a"), played)
        // D7: each clip fetched once, pipelined behind the turn before it — never all at once.
        assertEquals(listOf("/a", "/b", "/c"), fetcher.reads)
    }

    // ── §5.1: only tiles in hand are members ────────────────────────────────────────────────

    @Test
    fun aTileWhoseClipHasNotArrivedIsSkippedThisCycleAndAskedAgainAfterTheWrap() = runTest {
        // `/b` has no clip on this device: not minted, over the cap, or simply not synced yet.
        val fetcher = Fetcher(mutableMapOf("/a" to clip('a'), "/c" to clip('c')))
        val rotation = MotionRotation(backgroundScope, fetcher::fetch)
        rotation.setVisible(listOf("/a", "/b", "/c"))
        runCurrent()

        val played = mutableListOf<String>()
        repeat(3) {
            val turn = assertNotNull(rotation.turn.value)
            played += turn.path
            rotation.turnFinished(turn.path)
            runCurrent()
        }
        assertEquals(listOf("/a", "/c", "/a"), played, "the player is never handed to a tile that would have to fetch")
        // Asked once in the first cycle, skipped for its remainder, asked again after the wrap.
        assertEquals(2, fetcher.reads.count { it == "/b" }, "a miss is retried on the next cycle, not hammered: ${fetcher.reads}")
    }

    @Test
    fun aClipThatArrivesLaterJoinsTheRotation() = runTest {
        val fetcher = Fetcher(mutableMapOf("/a" to clip('a'), "/c" to clip('c')))
        val rotation = MotionRotation(backgroundScope, fetcher::fetch)
        rotation.setVisible(listOf("/a", "/b", "/c"))
        runCurrent()
        assertEquals("/a", rotation.turn.value.pathOrNull())

        // The content plane delivers `/b` while `/a` is still playing.
        fetcher.arrives("/b", clip('b'))
        rotation.turnFinished("/a")
        runCurrent()
        assertEquals("/c", rotation.turn.value.pathOrNull(), "this cycle already skipped /b")
        rotation.turnFinished("/c")
        runCurrent()
        assertEquals("/a", rotation.turn.value.pathOrNull())
        rotation.turnFinished("/a")
        runCurrent()
        assertEquals("/b", rotation.turn.value.pathOrNull(), "fetched on the wrap, in hand for the next cycle")
    }

    @Test
    fun nothingInHandMeansNothingAnimatesAndTheGridIsWhatItWas() = runTest {
        val fetcher = Fetcher(mutableMapOf())
        val rotation = MotionRotation(backgroundScope, fetcher::fetch)
        rotation.setVisible(listOf("/a", "/b"))
        runCurrent()
        assertNull(rotation.turn.value)
        // Each was asked for once; nothing spins.
        assertEquals(listOf("/a", "/b"), fetcher.reads)
    }

    // ── D7: one blob in flight ──────────────────────────────────────────────────────────────

    @Test
    fun exactlyOneFetchIsInFlightAtAnyMoment() = runTest {
        val gates = mutableMapOf<String, CompletableDeferred<ByteArray?>>()
        val reads = mutableListOf<String>()
        val rotation = MotionRotation(backgroundScope, fetch = { path ->
            reads += path
            gates.getOrPut(path) { CompletableDeferred() }.await()
        })
        rotation.setVisible(listOf("/a", "/b", "/c"))
        runCurrent()
        assertEquals(listOf("/a"), reads, "the first candidate, and only it")

        // Scrolling while a fetch is pending does not open a second slot.
        rotation.setVisible(listOf("/a", "/b", "/c", "/d"))
        runCurrent()
        assertEquals(listOf("/a"), reads)

        gates.getValue("/a").complete(clip('a'))
        runCurrent()
        assertEquals("/a", rotation.turn.value.pathOrNull(), "the turn starts the moment the clip lands")
        assertEquals(listOf("/a", "/b"), reads, "and the next tile's clip is now the one in flight")

        gates.getValue("/b").complete(clip('b'))
        runCurrent()
        assertEquals(listOf("/a", "/b", "/c"), reads)
    }

    @Test
    fun aFetchThatThrowsIsAMissNotAStall() = runTest {
        var attempts = 0
        val rotation = MotionRotation(backgroundScope, fetch = { path ->
            attempts++
            if (path == "/a" && attempts == 1) error("proxy hiccup") else clip(path.last())
        })
        rotation.setVisible(listOf("/a", "/b"))
        runCurrent()
        assertEquals("/b", rotation.turn.value.pathOrNull(), "/a missed this cycle; /b plays")
    }

    // ── Q1 / Q2 / D2: paused, scrubbing, off ────────────────────────────────────────────────

    @Test
    fun pausedStopsThePlayerAndResumeContinuesInDocumentOrder() = runTest {
        val fetcher = Fetcher(mutableMapOf("/a" to clip('a'), "/b" to clip('b'), "/c" to clip('c')))
        val rotation = MotionRotation(backgroundScope, fetcher::fetch)
        rotation.setVisible(listOf("/a", "/b", "/c"))
        runCurrent()
        assertEquals("/a", rotation.turn.value.pathOrNull())

        rotation.setPaused(true)
        runCurrent()
        assertNull(rotation.turn.value, "backgrounded or scrubbing: nothing animates")

        rotation.setPaused(false)
        runCurrent()
        assertEquals("/b", rotation.turn.value.pathOrNull(), "picks up after where it was, not from the top")
    }

    @Test
    fun switchedOffFetchesNothingAndSwitchedOnStarts() = runTest {
        val fetcher = Fetcher(mutableMapOf("/a" to clip('a')))
        val rotation = MotionRotation(backgroundScope, fetcher::fetch)
        rotation.setEnabled(false)
        rotation.setVisible(listOf("/a"))
        runCurrent()
        assertNull(rotation.turn.value)
        assertTrue(fetcher.reads.isEmpty(), "the switch is off: no clip is even asked for")

        rotation.setEnabled(true)
        runCurrent()
        assertEquals("/a", rotation.turn.value.pathOrNull())
    }

    @Test
    fun anEmptyGridClearsTheTurn() = runTest {
        val fetcher = Fetcher(mutableMapOf("/a" to clip('a')))
        val rotation = MotionRotation(backgroundScope, fetcher::fetch)
        rotation.setVisible(listOf("/a"))
        runCurrent()
        assertNotNull(rotation.turn.value)
        rotation.setVisible(emptyList())
        runCurrent()
        assertNull(rotation.turn.value)
    }

    // ── scrolling ───────────────────────────────────────────────────────────────────────────

    @Test
    fun aScrollThatKeepsTheTileOnScreenDoesNotRestartIt() = runTest {
        val fetcher = Fetcher(mutableMapOf("/a" to clip('a'), "/b" to clip('b'), "/c" to clip('c')))
        val rotation = MotionRotation(backgroundScope, fetcher::fetch)
        rotation.setVisible(listOf("/a", "/b"))
        runCurrent()
        val turn = assertNotNull(rotation.turn.value)

        rotation.setVisible(listOf("/a", "/b", "/c"))
        runCurrent()
        assertEquals(turn, rotation.turn.value, "same turn, same serial — the player was not touched")
    }

    @Test
    fun theCurrentTileScrollingAwayHandsOver() = runTest {
        val fetcher = Fetcher(mutableMapOf("/a" to clip('a'), "/b" to clip('b'), "/c" to clip('c')))
        val rotation = MotionRotation(backgroundScope, fetcher::fetch)
        rotation.setVisible(listOf("/a", "/b", "/c"))
        runCurrent()
        assertEquals("/a", rotation.turn.value.pathOrNull())

        rotation.setVisible(listOf("/b", "/c"))
        runCurrent()
        assertEquals("/b", rotation.turn.value.pathOrNull(), "an off-screen tile never holds the player")
    }

    @Test
    fun aStaleFinishIsIgnored() = runTest {
        val fetcher = Fetcher(mutableMapOf("/a" to clip('a'), "/b" to clip('b')))
        val rotation = MotionRotation(backgroundScope, fetcher::fetch)
        rotation.setVisible(listOf("/a", "/b"))
        runCurrent()
        val turn = assertNotNull(rotation.turn.value)
        // A tile that no longer holds the player reporting completion must not advance anything.
        rotation.turnFinished("/b")
        runCurrent()
        assertEquals(turn, rotation.turn.value)
    }

    // ── the rules, pure ─────────────────────────────────────────────────────────────────────

    @Test
    fun turnAfterWalksDocumentOrderWrappingWithTheAnchorLast() {
        val visible = listOf("/a", "/b", "/c")
        val all: (String) -> Boolean = { true }
        assertEquals("/a", MotionRotationRules.turnAfter(null, visible, all), "no anchor: the top")
        assertEquals("/a", MotionRotationRules.turnAfter("/zzz", visible, all), "an anchor that scrolled away: the top")
        assertEquals("/b", MotionRotationRules.turnAfter("/a", visible, all))
        assertEquals("/a", MotionRotationRules.turnAfter("/c", visible, all), "wraps")
        assertEquals("/a", MotionRotationRules.turnAfter("/a", listOf("/a"), all), "a lone tile: itself")
        assertEquals("/c", MotionRotationRules.turnAfter("/a", visible) { it == "/c" })
        assertNull(MotionRotationRules.turnAfter("/a", visible) { false })
        assertNull(MotionRotationRules.turnAfter("/a", emptyList(), all))
    }

    @Test
    fun prefetchTargetSkipsTheCurrentTheHeldAndTheMissed() {
        val visible = listOf("/a", "/b", "/c")
        assertEquals(
            "/c",
            MotionRotationRules.prefetchTarget("/a", visible, inHand = { it == "/a" }, skip = setOf("/b")),
        )
        assertNull(
            MotionRotationRules.prefetchTarget("/a", listOf("/a"), inHand = { false }, skip = emptySet()),
            "never the tile that is playing",
        )
        assertEquals(
            "/a",
            MotionRotationRules.prefetchTarget(null, visible, inHand = { false }, skip = emptySet()),
        )
    }

    // ── the store ───────────────────────────────────────────────────────────────────────────

    @Test
    fun theStoreIsBoundedInBytesAndEvictsTheLeastRecentlyUsed() {
        val store = MotionClipStore(maxBytes = 300)
        store.put("/a", ByteArray(100))
        store.put("/b", ByteArray(100))
        store.put("/c", ByteArray(100))
        assertNotNull(store.get("/a"), "a read refreshes /a")
        store.put("/d", ByteArray(100))
        assertTrue(!store.has("/b"), "the least recently used goes, not the oldest inserted")
        assertTrue(store.has("/a") && store.has("/c") && store.has("/d"))

        // A single clip larger than the whole budget is not held — it would evict everything else.
        store.put("/huge", ByteArray(301))
        assertTrue(!store.has("/huge"))
        assertEquals(3, store.size)
    }
}
