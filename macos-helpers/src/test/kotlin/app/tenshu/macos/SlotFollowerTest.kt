package app.tenshu.macos

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where the in-window Touch ID view ends up, with the interleavings played in a fixed order rather
 * than left to the three threads that race in the app (Compose's UI thread reports the slot, an IO
 * thread creates the view, AppKit's main thread places it).
 *
 * ⚠ Reproduces the 2026-10-16 report — the fingerprint drawn over the title and buttons — as a
 * sequence: the slot moved while the view was being created, the move was dropped, and the view
 * stayed at the first layout's position. Not proven to be *that* occurrence (it never reproduced);
 * proven to be a way to get it.
 */
class SlotFollowerTest {

    private val placed = mutableListOf<Pair<String, String>>()
    private val follower = SlotFollower<String, String> { view, slot -> placed += view to slot }

    @Test
    fun aMoveWhileTheViewIsBeingCreated_isWhereTheViewLands() {
        follower.begin("first layout")
        follower.move("settled layout") // the window finished appearing; no view yet
        follower.attach("view")
        assertEquals("view" to "settled layout", placed.last())
    }

    @Test
    fun aMoveBeforeTheCallStarted_isNotOverwrittenByTheStaleStartSlot() {
        // The UI read its slot, then laid out again before the call reached the native side.
        follower.move("settled layout")
        follower.begin("first layout")
        follower.attach("view")
        assertEquals("view" to "settled layout", placed.last())
    }

    @Test
    fun movesAfterAttaching_followTheSlot() {
        follower.begin("a")
        follower.attach("view")
        follower.move("b")
        assertEquals(listOf("view" to "a", "view" to "b"), placed)
    }

    @Test
    fun afterEnd_aLateMoveTouchesNothing_andTheNextCallStartsClean() {
        follower.begin("a")
        follower.attach("view")
        follower.end()
        follower.move("late")
        assertEquals(listOf("view" to "a"), placed, "a move after the view was removed placed it")
    }
}
