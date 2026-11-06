package app.tenshu.liveness

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers both foreground-service start helpers.
 *
 * The property under test is the one that crashed Control and Fit on a real device (2026-07-30): a
 * refused `startForegroundService()` must degrade, never propagate.
 * `ForegroundServiceStartNotAllowedException` cannot be constructed off-device, but it *is* an
 * `IllegalStateException` — exactly what both helpers narrow to — so the fakes throw the supertype
 * and drive the same path.
 *
 * ⚠ The `inFlight` flow here is a [MutableSharedFlow], not a `StateFlow`, wherever repeated
 * emissions matter: a Boolean `StateFlow` conflates `true → true` away, so it could not reproduce
 * the retry storm at all. Production passes a `map`ped progress flow, which really does re-emit
 * `true` for every progress tick.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundServiceStartTest {

    // ── startAndPromoteForeground ────────────────────────────────────────────

    private class Promotion(
        var refuseStart: Boolean = false,
        var refuseForeground: Boolean = false,
    ) {
        var startCalls = 0
        var foregroundCalls = 0
        var stopCalls = 0

        fun run(): Boolean = startAndPromoteForeground(
            startService = {
                startCalls++
                if (refuseStart) throw IllegalStateException("not allowed")
            },
            startForeground = {
                foregroundCalls++
                if (refuseForeground) throw IllegalStateException("not allowed")
            },
            stopStartedLifecycle = { stopCalls++ },
        )
    }

    @Test
    fun promotionSucceedsWhenTheOsAllowsBothHalves() {
        val fx = Promotion()
        assertTrue(fx.run())
        assertEquals(1, fx.startCalls)
        assertEquals(1, fx.foregroundCalls)
        assertEquals(0, fx.stopCalls)
    }

    @Test
    fun aRefusedStartReportsFailureAndUnwindsNothing() {
        val fx = Promotion(refuseStart = true)
        assertFalse(fx.run())
        assertEquals(1, fx.startCalls)
        assertEquals(0, fx.stopCalls, "nothing was started, so there is nothing to unwind")
        assertEquals(0, fx.foregroundCalls)
    }

    @Test
    fun aStartThatCannotBePromotedEndsTheStartedLifecycle() {
        val fx = Promotion(refuseForeground = true)
        assertFalse(fx.run())
        assertEquals(1, fx.startCalls)
        assertEquals(1, fx.foregroundCalls)
        assertEquals(
            1,
            fx.stopCalls,
            "the startForeground deadline is ticking — end it rather than be killed for missing it",
        )
    }

    @Test
    fun enterForegroundSucceedsForAnAlreadyStartedService() {
        var foregroundCalls = 0
        var stopCalls = 0
        assertTrue(enterForegroundOrStop({ foregroundCalls++ }, { stopCalls++ }))
        assertEquals(1, foregroundCalls)
        assertEquals(0, stopCalls)
    }

    @Test
    fun aRefusedEnterForegroundStopsRatherThanBeingKilled() {
        var stopCalls = 0
        val ok = enterForegroundOrStop(
            startForeground = { throw IllegalStateException("not allowed") },
            stopStartedLifecycle = { stopCalls++ },
        )
        assertFalse(ok)
        assertEquals(
            1,
            stopCalls,
            "missing the startForeground deadline is fatal — end the started lifecycle instead",
        )
    }

    // ── armForegroundService ─────────────────────────────────────────────────

    private class Starter(var refuse: Boolean = false) {
        var attempts = 0

        /**
         * ⚠⚠ A property **holding** a lambda, not a `() -> Unit` supertype on the class.
         * Kotlin/JS refuses `class Starter : () -> Unit` outright — *"Implementing a function
         * interface is prohibited in JavaScript"* — so the supertype form compiled on every
         * target the local gate builds and broke the one it does not.
         *
         * ⚠ It still has to be a class rather than a bare lambda: the assertions read
         * [attempts] and flip [refuse] between ticks, which is state a lambda has nowhere to put.
         */
        val start: () -> Unit = {
            attempts++
            if (refuse) throw IllegalStateException("not allowed")
        }
    }

    /** A progress-shaped flow: re-emits `true` on every tick, exactly as `map`ped status does. */
    private fun progressFlow() = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 128)

    @Test
    fun startsOncePerInFlightPeriod() = runTest {
        val inFlight = MutableStateFlow(false)
        val foreground = MutableStateFlow(true)
        val starter = Starter()
        val job = launch { armForegroundService(inFlight, foreground, starter.start) }

        inFlight.value = true
        advanceUntilIdle()
        assertEquals(1, starter.attempts)

        inFlight.value = false
        advanceUntilIdle()
        inFlight.value = true
        advanceUntilIdle()
        assertEquals(2, starter.attempts, "a later period must re-arm")
        job.cancel()
    }

    @Test
    fun attemptsEvenWhileBackgrounded() = runTest {
        val inFlight = MutableStateFlow(false)
        val foreground = MutableStateFlow(false)
        val starter = Starter()
        val job = launch { armForegroundService(inFlight, foreground, starter.start) }

        inFlight.value = true
        advanceUntilIdle()

        // A backgrounded app may still hold a while-in-use allowance (Drive backgrounded right
        // after the user started an upload). Refusing to even try would lose that legal start.
        assertEquals(1, starter.attempts)
        job.cancel()
    }

    @Test
    fun aRefusedStartDoesNotPropagate() = runTest {
        val inFlight = MutableStateFlow(false)
        val foreground = MutableStateFlow(false)
        val starter = Starter(refuse = true)
        var escaped: Throwable? = null
        val job = launch {
            try {
                armForegroundService(inFlight, foreground, starter.start)
            } catch (t: Throwable) {
                escaped = t
            }
        }

        inFlight.value = true
        advanceUntilIdle()

        assertEquals(1, starter.attempts)
        assertNull(escaped, "a refused start must not propagate out of the launcher")
        job.cancel()
    }

    @Test
    fun doesNotRetryStormWhileStillRefusedAndBackgrounded() = runTest {
        val inFlight = progressFlow()
        val foreground = MutableStateFlow(false)
        val starter = Starter(refuse = true)
        val job = launch { armForegroundService(inFlight, foreground, starter.start) }

        inFlight.emit(true)
        advanceUntilIdle()
        assertEquals(1, starter.attempts)

        // 50 progress ticks within the same in-flight period.
        repeat(50) { inFlight.emit(true) }
        advanceUntilIdle()
        assertEquals(
            1,
            starter.attempts,
            "only a period boundary or a foreground transition may re-arm after a refusal",
        )
        job.cancel()
    }

    @Test
    fun retriesOnceOnTheNextForegroundTransition() = runTest {
        val inFlight = progressFlow()
        val foreground = MutableStateFlow(false)
        val starter = Starter(refuse = true)
        val job = launch { armForegroundService(inFlight, foreground, starter.start) }

        inFlight.emit(true)
        advanceUntilIdle()
        assertEquals(1, starter.attempts)

        // Opening the app re-arms exactly one attempt, and it succeeds this time.
        starter.refuse = false
        foreground.value = true
        advanceUntilIdle()
        assertEquals(2, starter.attempts)

        // Once started, nothing further re-attempts.
        repeat(10) { inFlight.emit(true) }
        foreground.value = false
        advanceUntilIdle()
        foreground.value = true
        advanceUntilIdle()
        assertEquals(2, starter.attempts, "a started service must not be re-started")
        job.cancel()
    }

    @Test
    fun aStillRefusedRetryBacksOffAgain() = runTest {
        val inFlight = progressFlow()
        val foreground = MutableStateFlow(false)
        val starter = Starter(refuse = true)
        val job = launch { armForegroundService(inFlight, foreground, starter.start) }

        inFlight.emit(true)
        advanceUntilIdle()
        foreground.value = true // retry #2, still refused
        advanceUntilIdle()
        assertEquals(2, starter.attempts)

        repeat(20) { inFlight.emit(true) }
        advanceUntilIdle()
        assertEquals(2, starter.attempts, "a second refusal backs off just like the first")
        job.cancel()
    }
}
