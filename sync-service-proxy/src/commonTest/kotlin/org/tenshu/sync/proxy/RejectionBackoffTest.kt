package org.tenshu.sync.proxy

import org.tenshu.core.GenericError
import org.tenshu.core.TenshuException
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit coverage for the 402 (entitlement-enforcement) push-loop backoff — Track A P0 of
 * `docs/proposals/enforcement-followup-tracks.md`. Proves the storm-stopping behaviour
 * ([RejectionBackoff]) and the marker-based rejection detection ([isEntitlementRejection]) in
 * isolation, over a controllable clock, without standing up a relay.
 */
class RejectionBackoffTest {

    private class FakeClock(var now: Long = 0L) {
        fun advance(ms: Long) { now += ms }
    }

    private fun backoff(clock: FakeClock, base: Long = 2_000L, max: Long = 120_000L) =
        RejectionBackoff(nowMs = { clock.now }, baseDelayMs = base, maxDelayMs = max)

    @Test
    fun freshBackoff_notBlocked_notBackingOff() {
        val b = backoff(FakeClock())
        assertFalse(b.isBlocked, "a fresh backoff is not blocked")
        assertFalse(b.isBackingOff(), "a fresh backoff is not backing off")
        assertEquals(0L, b.currentDelayMs)
    }

    @Test
    fun firstRejection_backsOffForBaseDelay_thenAllowsRetry() {
        val clock = FakeClock()
        val b = backoff(clock, base = 2_000L)

        b.onRejected()
        assertTrue(b.isBlocked)
        assertTrue(b.isBackingOff(), "within the window it must back off")
        assertEquals(2_000L, b.currentDelayMs)

        clock.advance(1_999L)
        assertTrue(b.isBackingOff(), "still inside the first window")

        clock.advance(1L) // now == nextAttemptAt
        assertFalse(b.isBackingOff(), "window elapsed → a probing retry is allowed")
        assertTrue(b.isBlocked, "…but still blocked until a push succeeds/clears")
    }

    @Test
    fun repeatedRejections_growWindowExponentially_cappedAtMax() {
        val clock = FakeClock()
        val b = backoff(clock, base = 2_000L, max = 120_000L)

        val expected = longArrayOf(2_000, 4_000, 8_000, 16_000, 32_000, 64_000, 120_000, 120_000)
        expected.forEach { want ->
            b.onRejected()
            assertEquals(want, b.currentDelayMs, "delay grows geometrically, capped at max")
        }
    }

    @Test
    fun onCleared_resetsWindow_soNextPushIsImmediate() {
        val clock = FakeClock()
        val b = backoff(clock)
        b.onRejected()
        b.onRejected()
        assertTrue(b.isBackingOff())

        b.onCleared()
        assertFalse(b.isBlocked, "a successful push clears the block")
        assertFalse(b.isBackingOff(), "and drops the window immediately (eager re-drive)")
        assertEquals(0L, b.currentDelayMs)
    }

    @Test
    fun reset_dropsBlockPromptly_evenMidWindow() {
        val clock = FakeClock()
        val b = backoff(clock, base = 60_000L)
        b.onRejected()
        b.onRejected() // long window
        assertTrue(b.isBackingOff())

        // An external "situation may differ" signal (reconnect / credential acquired).
        b.reset()
        assertFalse(b.isBackingOff(), "reset re-enables pushing without waiting the window out")
        assertFalse(b.isBlocked)
    }

    @Test
    fun rejectionAfterClear_restartsFromBaseDelay() {
        val clock = FakeClock()
        val b = backoff(clock, base = 2_000L)
        repeat(4) { b.onRejected() } // grown to 16s
        assertEquals(16_000L, b.currentDelayMs)

        b.onCleared()
        b.onRejected() // a fresh 402 after recovery starts small again
        assertEquals(2_000L, b.currentDelayMs)
    }

    // --- isEntitlementRejection marker detection ---

    @Test
    fun tenshuException_withMarker_isDetected() {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val ctx = proxy.tContext
        val err = GenericError.create(ctx, "$ENTITLEMENT_REJECTION_MARKER: push rejected (402)")
        val ex: Throwable = TenshuException(err)
        assertTrue(ex.isEntitlementRejection())
    }

    @Test
    fun tenshuException_withoutMarker_isNotDetected() {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val ctx = proxy.tContext
        val err = GenericError.create(ctx, "Server error: 500 Internal Server Error")
        val ex: Throwable = TenshuException(err)
        assertFalse(ex.isEntitlementRejection(), "a non-402 relay error must not trigger backoff")
    }

    @Test
    fun wrappedException_withMarkerInCauseChain_isDetected() {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val ctx = proxy.tContext
        val root = TenshuException(
            GenericError.create(ctx, "$ENTITLEMENT_REJECTION_MARKER: content PUT rejected (402)")
        )
        val wrapped = RuntimeException("drain failed", root)
        assertTrue(wrapped.isEntitlementRejection(), "marker is found through the cause chain")
    }

    @Test
    fun plainNetworkError_isNotDetected() {
        val ex: Throwable = RuntimeException("Network error")
        assertFalse(ex.isEntitlementRejection())
    }
}
