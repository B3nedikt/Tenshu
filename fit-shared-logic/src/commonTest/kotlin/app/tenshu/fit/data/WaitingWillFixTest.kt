package app.tenshu.fit.data

import app.tenshu.reachability.UnreachableReason
import app.tenshu.reachability.UpdateTarget
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `waitingWillFix` (`core-schema-skew-surface.md` §8.6) — whether an empty state may say
 * *"Still trying…"*.
 *
 * ⚠⚠ The bug this encodes was found by **looking at a screen**, not by a test: the shell banner
 * said *"Update Control to keep your health data syncing"* while the body directly beneath it said
 * *"Still trying…"*. Every test was green through it, because each half was correct in isolation
 * and only the combination was a contradiction. That is the limit these cases keep running into —
 * a claim can be locally true and globally a lie.
 */
class WaitingWillFixTest {

    /**
     * ⚠ The honest default. Absent a known schema cause the subscription really is retrying, so
     * the reassuring copy is the true one — this must not flip to "updating will fix it" for an
     * ordinary outage that clears itself and which the user can do nothing about.
     */
    @Test
    fun nothingKnownMeansWaitingWillFix() {
        assertTrue(waitingWillFix(null))
    }

    @Test
    fun aTransportOutageRecoversOnItsOwn() {
        assertTrue(waitingWillFix(UnreachableReason.Transport))
    }

    /**
     * ⚠⚠ The case that produced the contradiction on screen. A refused announce rejects the
     * session until the user updates something; no amount of waiting resolves it.
     */
    @Test
    fun aRefusedSessionIsNotFixedByWaiting() {
        assertFalse(
            waitingWillFix(
                UnreachableReason.Skew(UpdateTarget.CONTROL, listOf("tenshu/health/service"))
            )
        )
    }

    /** A fenced device has stopped pulling entirely, so it is not "trying" either. */
    @Test
    fun aFencedDeviceIsNotFixedByWaiting() {
        assertFalse(
            waitingWillFix(
                UnreachableReason.Fenced(UpdateTarget.CONTROL, listOf("tenshu/identity"))
            )
        )
    }

    /** ⚠ An unknown host still means "update something" — it must not fall back to the retry claim. */
    @Test
    fun aFenceWithNoNamedAppIsStillNotFixedByWaiting() {
        assertFalse(waitingWillFix(UnreachableReason.Fenced(update = null)))
    }
}
