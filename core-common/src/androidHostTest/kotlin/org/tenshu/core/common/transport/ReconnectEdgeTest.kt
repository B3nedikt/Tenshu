package org.tenshu.core.common.transport

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `core-client-resubscription.md` R7, the Binder half: which landed connections announce.
 *
 * ⚠ This pins the RULE, not the wiring. The unbind → rebind wiring is pinned against the real
 * transport by [BinderTaskTransportRebindTest]; where it reports a caller turned away — a refused
 * `bindService`, an `awaitConnected` that ran out — is still covered by reading and by a device.
 */
class ReconnectEdgeTest {

    @Test
    fun aFirstConnectNobodyWaitedOnIsSilent() {
        assertFalse(ReconnectEdge().landed(), "nothing to replay, nobody waiting")
    }

    /** The hole: a client that was never connected was never "re"-connected either. */
    @Test
    fun aFirstConnectAfterACallerWasTurnedAwayAnnounces() {
        val edge = ReconnectEdge()
        edge.turnedAway()
        assertTrue(edge.landed(), "the caller that was told 'not connected' is waiting on this")
    }

    @Test
    fun aConnectAfterALossAnnounces() {
        val edge = ReconnectEdge()
        edge.landed()
        edge.lost()
        assertTrue(edge.landed())
    }

    /** One announcement per debt — a replay resets every live stream, so a spare one is not free. */
    @Test
    fun theDebtIsPaidOnce() {
        val edge = ReconnectEdge()
        edge.turnedAway()
        edge.turnedAway()
        assertTrue(edge.landed())
        assertFalse(edge.landed())
    }

    /**
     * The lost update (`docs/tech-debt.md`, 2026-11-15): `landed()` runs on main in
     * `onServiceConnected` while `turnedAway()` arrives from an IPC coroutine or `Dispatchers.IO`.
     * Forced, not stressed: the other thread's `turnedAway()` is run to completion inside `landed()`,
     * right after it reads the debt (`afterRead`). A read-then-clear erases it there, so the caller
     * that was told "not connected" is never announced and its parked retry stays parked (R7).
     */
    @Test
    fun aCallerTurnedAwayWhileTheConnectionLandsIsNotForgotten() {
        val edge = ReconnectEdge()
        edge.afterRead = {
            edge.afterRead = null
            val io = Thread { edge.turnedAway() }
            io.start()
            io.join()
        }
        val announcedNow = edge.landed()
        val announcedNext = edge.landed()
        assertTrue(
            announcedNow || announcedNext,
            "a turnedAway() that raced landed() was erased: nobody will ever announce to that caller",
        )
        assertFalse(announcedNow && announcedNext, "one debt, one announcement")
    }
}
