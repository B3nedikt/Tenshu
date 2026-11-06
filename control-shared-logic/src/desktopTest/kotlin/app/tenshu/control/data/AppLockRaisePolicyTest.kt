package app.tenshu.control.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The half of the window raise that *is* testable.
 *
 * ⚠⚠ The case this exists for is [nothingWasLocked_soNoRaise]: the first version of the raise fired
 * on any change to lock state **or** visibility, so the tray's *Open Control* — where nothing was
 * ever locked — activated Control on top of the tray's own activation, and the tray's popover
 * stopped closing. A device pass caught it; this table would have caught it first.
 */
class AppLockRaisePolicyTest {

    @Test
    fun unlockingAVisibleWindow_raises() {
        assertTrue(shouldRaiseOnUnlock(wasLocked = true, isLocked = false, windowVisible = true))
    }

    @Test
    fun nothingWasLocked_soNoRaise() {
        assertFalse(
            shouldRaiseOnUnlock(wasLocked = false, isLocked = false, windowVisible = true),
            "the tray's Open Control must not trigger a second activation — that is the regression",
        )
    }

    @Test
    fun stillLocked_doesNotRaise() {
        assertFalse(shouldRaiseOnUnlock(wasLocked = true, isLocked = true, windowVisible = true))
    }

    /** ⚠ A close-to-tray / autostarted-hidden window stays where it is. */
    @Test
    fun aHiddenWindowIsNeverPulledForward() {
        assertFalse(shouldRaiseOnUnlock(wasLocked = true, isLocked = false, windowVisible = false))
    }

    /** Re-locking is not an unlock; the direction of the transition is the whole point. */
    @Test
    fun lockingDoesNotRaise() {
        assertFalse(shouldRaiseOnUnlock(wasLocked = false, isLocked = true, windowVisible = true))
    }
}
