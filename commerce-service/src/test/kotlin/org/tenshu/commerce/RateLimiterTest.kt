package org.tenshu.commerce

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The budget primitive behind CE-2's OTP limits. The interesting cases are not "does it count" but
 * the two policy decisions the class makes: the window **rolls over** rather than sliding, and a
 * full key table **fails closed**.
 */
class RateLimiterTest {

    private val windowMs = 60_000L

    @Test
    fun allowsUpToTheLimitThenRefuses() {
        val limiter = RateLimiter(limit = 3, windowMs = windowMs)
        repeat(3) { assertTrue(limiter.tryAcquire("k", nowMs = 1_000L), "attempt ${it + 1} must pass") }
        assertFalse(limiter.tryAcquire("k", nowMs = 1_000L), "the 4th must be refused")
    }

    @Test
    fun keysAreIndependent() {
        val limiter = RateLimiter(limit = 1, windowMs = windowMs)
        assertTrue(limiter.tryAcquire("a", 1_000L))
        assertFalse(limiter.tryAcquire("a", 1_000L))
        assertTrue(limiter.tryAcquire("b", 1_000L), "b's budget must not be spent by a")
    }

    @Test
    fun windowRollsOverAndRestoresTheFullBudget() {
        val limiter = RateLimiter(limit = 2, windowMs = windowMs)
        assertTrue(limiter.tryAcquire("k", 1_000L))
        assertTrue(limiter.tryAcquire("k", 1_000L))
        assertFalse(limiter.tryAcquire("k", 1_000L))
        // Just before the boundary: still refused.
        assertFalse(limiter.tryAcquire("k", 1_000L + windowMs - 1))
        // At the boundary: a fresh window.
        assertTrue(limiter.tryAcquire("k", 1_000L + windowMs))
    }

    @Test
    fun nonPositiveLimitDisablesTheBudget() {
        val limiter = RateLimiter(limit = 0, windowMs = windowMs)
        repeat(1_000) { assertTrue(limiter.tryAcquire("k", 1_000L)) }
        assertEquals(0, limiter.trackedKeys, "a disabled limiter must not track keys either")
    }

    @Test
    fun fullKeyTableRefusesUnseenKeysButStillServesKnownOnes() {
        var tableFullSignals = 0
        val limiter = RateLimiter(
            limit = 5,
            windowMs = windowMs,
            maxTrackedKeys = 2,
            onTableFull = { tableFullSignals++ },
        )
        assertTrue(limiter.tryAcquire("a", 1_000L))
        assertTrue(limiter.tryAcquire("b", 1_000L))

        // Table full of live windows: a NEW key is refused rather than growing the map — the
        // fail-closed decision. Unbounded outbound mail is the worse of the two failure modes.
        assertFalse(limiter.tryAcquire("c", 1_000L), "an unseen key must be refused when the table is full")
        assertEquals(1, tableFullSignals, "the condition must be observable, not silent")

        // An already-tracked key still spends its own budget normally — a full table must not
        // lock out the users already mid-flow.
        assertTrue(limiter.tryAcquire("a", 1_000L))
    }

    @Test
    fun expiredWindowsAreReclaimedSoTheTableRecovers() {
        val limiter = RateLimiter(limit = 5, windowMs = windowMs, maxTrackedKeys = 2)
        assertTrue(limiter.tryAcquire("a", 1_000L))
        assertTrue(limiter.tryAcquire("b", 1_000L))
        assertFalse(limiter.tryAcquire("c", 1_000L))

        // Once a's and b's windows have elapsed, the admission path prunes them and c gets in.
        assertTrue(
            limiter.tryAcquire("c", 1_000L + windowMs),
            "elapsed windows must be reclaimed so the table is not permanently wedged",
        )
    }

    @Test
    fun wouldAllowReportsBudgetWithoutSpendingIt() {
        val limiter = RateLimiter(limit = 1, windowMs = windowMs)
        assertTrue(limiter.wouldAllow("k", 1_000L))
        assertTrue(limiter.wouldAllow("k", 1_000L), "peeking must not consume")
        assertEquals(0, limiter.trackedKeys, "peeking must not even create a window")

        assertTrue(limiter.tryAcquire("k", 1_000L))
        assertFalse(limiter.wouldAllow("k", 1_000L), "and it must report a spent budget honestly")
    }

    @Test
    fun wouldAllowRespectsTheFailClosedTableCeiling() {
        val limiter = RateLimiter(limit = 5, windowMs = windowMs, maxTrackedKeys = 1)
        assertTrue(limiter.tryAcquire("a", 1_000L))
        assertFalse(limiter.wouldAllow("b", 1_000L), "an unseen key must peek false when the table is full")
        assertTrue(limiter.wouldAllow("a", 1_000L), "an already-tracked key is unaffected")
    }

    @Test
    fun pruneExpiredDropsOnlyElapsedWindows() {
        val limiter = RateLimiter(limit = 5, windowMs = windowMs)
        limiter.tryAcquire("old", 1_000L)
        limiter.tryAcquire("new", 1_000L + windowMs)
        limiter.pruneExpired(1_000L + windowMs)
        assertEquals(1, limiter.trackedKeys)
    }
}
