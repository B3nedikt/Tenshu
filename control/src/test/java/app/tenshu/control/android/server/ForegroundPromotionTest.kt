package app.tenshu.control.android.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [ForegroundPromotion] — the promote / degrade / retry decisions behind `ProxyService`'s
 * foreground-service liveness.
 *
 * The property under test is the one that crashed Control on a real device (2026-07-30): a refused
 * `startForegroundService()` must degrade the proxy to bound-only, never propagate out of the
 * liveness edge. `ForegroundServiceStartNotAllowedException` cannot be constructed off-device, but
 * it *is* an `IllegalStateException` — which is exactly what the production `catch` narrows to — so
 * the fakes below throw the supertype and drive the same path.
 */
class ForegroundPromotionTest {

    private class Effects(
        var refuseStartService: Boolean = false,
        var refuseStartForeground: Boolean = false,
    ) {
        var startServiceCalls = 0
        var startForegroundCalls = 0
        var stopForegroundCalls = 0
        var stopStartedLifecycleCalls = 0
        var refreshCalls = 0
        val lastCounts = mutableListOf<Int>()
        var nowMs = 0L

        fun promotion() = ForegroundPromotion(
            startService = {
                startServiceCalls++
                if (refuseStartService) throw IllegalStateException("not allowed")
            },
            startForeground = { count ->
                startForegroundCalls++
                lastCounts += count
                if (refuseStartForeground) throw IllegalStateException("not allowed")
            },
            stopForeground = { stopForegroundCalls++ },
            stopStartedLifecycle = { stopStartedLifecycleCalls++ },
            refreshNotification = { refreshCalls++ },
            nowMs = { nowMs },
        )
    }

    @Test
    fun `promotes on the active edge and refreshes the notification thereafter`() {
        val fx = Effects()
        val promotion = fx.promotion()

        promotion.onActive(1)
        assertTrue(promotion.promoted)
        assertEquals(1, fx.startServiceCalls)
        assertEquals(1, fx.startForegroundCalls)

        promotion.onCountChanged(2)
        assertEquals("a promoted service only refreshes", 1, fx.startServiceCalls)
        assertEquals(1, fx.refreshCalls)
        assertEquals(listOf(1), fx.lastCounts)
    }

    @Test
    fun `a refused start degrades to bound-only instead of propagating`() {
        val fx = Effects(refuseStartService = true)
        val promotion = fx.promotion()

        // The crash scenario: the liveness edge fires with no foreground bound client.
        promotion.onActive(1)

        assertFalse("must not claim promotion after a refusal", promotion.promoted)
        assertEquals(1, fx.startServiceCalls)
        assertEquals("nothing was started, so nothing to unwind", 0, fx.stopStartedLifecycleCalls)
        assertEquals(0, fx.startForegroundCalls)
    }

    @Test
    fun `a refused promotion retries on the next count change and recovers`() {
        val fx = Effects(refuseStartService = true)
        val promotion = fx.promotion()

        promotion.onActive(1)
        assertFalse(promotion.promoted)

        // A client came to the foreground — the allowance is back — once the throttle has passed.
        fx.refuseStartService = false
        fx.nowMs += ForegroundPromotion.PROMOTION_RETRY_INTERVAL_MS
        promotion.onCountChanged(3)

        assertTrue("the next count change must re-attempt promotion", promotion.promoted)
        assertEquals(2, fx.startServiceCalls)
        assertEquals(listOf(3), fx.lastCounts)
        assertEquals("a bound-only service must not post a notification", 0, fx.refreshCalls)
    }

    @Test
    fun `a start that succeeds but cannot be promoted ends the started lifecycle`() {
        val fx = Effects(refuseStartForeground = true)
        val promotion = fx.promotion()

        promotion.onActive(1)

        assertFalse(promotion.promoted)
        assertEquals(1, fx.startServiceCalls)
        assertEquals(1, fx.startForegroundCalls)
        assertEquals(
            "the startForeground window is ticking — end it rather than be killed for missing it",
            1,
            fx.stopStartedLifecycleCalls,
        )
    }

    @Test
    fun `idle demotes a promoted service and always ends the started lifecycle`() {
        val fx = Effects()
        val promotion = fx.promotion()

        promotion.onActive(1)
        promotion.onIdle()

        assertFalse(promotion.promoted)
        assertEquals(1, fx.stopForegroundCalls)
        assertEquals(1, fx.stopStartedLifecycleCalls)
    }

    @Test
    fun `idle after a refused promotion does not stop a foreground it never entered`() {
        val fx = Effects(refuseStartService = true)
        val promotion = fx.promotion()

        promotion.onActive(1)
        promotion.onIdle()

        assertEquals(0, fx.stopForegroundCalls)
        assertEquals(1, fx.stopStartedLifecycleCalls)
    }

    @Test
    fun `after a refusal, count changes inside the retry interval do not ask again`() {
        // ⚠⚠ The storm this replaces: one refused startForegroundService per task start and finish,
        // 4,980 of them for a 300-photo background backup (control-background-work-off-fgs.md §3.5).
        val fx = Effects(refuseStartService = true)
        val promotion = fx.promotion()

        promotion.onActive(1)
        repeat(50) { i ->
            fx.nowMs += 100 // 5 s of task churn
            promotion.onCountChanged(1 + i % 3)
        }
        assertEquals("one attempt, then silence until the interval passes", 1, fx.startServiceCalls)

        fx.nowMs += ForegroundPromotion.PROMOTION_RETRY_INTERVAL_MS
        promotion.onCountChanged(2)
        assertEquals(2, fx.startServiceCalls)
    }

    @Test
    fun `a new run after idle may promote at once, even within the interval of an earlier refusal`() {
        // The user leaves, the run ends refused; seconds later they tap again with the app in front.
        val fx = Effects(refuseStartService = true)
        val promotion = fx.promotion()
        promotion.onActive(1)
        promotion.onIdle()

        fx.refuseStartService = false
        fx.nowMs += 1_000 // well inside the retry interval
        promotion.onActive(1)
        assertTrue("a fresh user action must not be throttled by the last run's refusal", promotion.promoted)
    }

    @Test
    fun `a time-out demotes at once and asks for nothing until the work goes idle`() {
        val fx = Effects()
        val promotion = fx.promotion()

        promotion.onActive(1)
        promotion.onTimedOut()

        assertFalse(promotion.promoted)
        assertTrue(promotion.timedOut)
        assertEquals("must leave the foreground within seconds or be crashed", 1, fx.stopForegroundCalls)
        assertEquals(1, fx.stopStartedLifecycleCalls)

        fx.nowMs += 10 * ForegroundPromotion.PROMOTION_RETRY_INTERVAL_MS
        promotion.onCountChanged(2)
        assertEquals("the OS refuses dataSync after its limit; asking again is a refusal per task", 1, fx.startServiceCalls)

        // A new user-started run after idle may ask again — the OS decides.
        promotion.onIdle()
        assertFalse(promotion.timedOut)
        promotion.onActive(1)
        assertEquals(2, fx.startServiceCalls)
    }

    @Test
    fun `a later task re-promotes after the previous one went idle`() {
        val fx = Effects()
        val promotion = fx.promotion()

        promotion.onActive(1)
        promotion.onIdle()
        promotion.onActive(1)

        assertTrue(promotion.promoted)
        assertEquals(2, fx.startServiceCalls)
    }
}
