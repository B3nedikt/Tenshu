package org.tenshu.sync.proxy

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Behavioural contract for [SyncWakeSignal] — the Phase 4a-bis primitive that
 * lets a user-originated save (or the idle→hot stream edge) shortcut
 * [SyncScheduler]'s poll interval. Virtual-time assertions: `currentTime`
 * after `awaitOrTimeout` tells us whether it returned on a wake (no time
 * elapsed) or on the timeout (exactly the interval elapsed).
 */
class SyncWakeSignalTest {

    @Test
    fun awaitReturnsImmediatelyWhenWakeBuffered() = runTest {
        val signal = SyncWakeSignal()

        // Wake fired with nobody waiting must be retained (level-on-edge).
        signal.wake()
        signal.awaitOrTimeout(timeoutMs = 10_000)

        assertEquals(0, testScheduler.currentTime, "buffered wake must return without consuming the interval")
    }

    @Test
    fun awaitReturnsOnWakeDuringWait() = runTest {
        val signal = SyncWakeSignal()

        launch {
            delay(500)
            signal.wake()
        }

        signal.awaitOrTimeout(timeoutMs = 10_000)

        assertEquals(500, testScheduler.currentTime, "wake during the wait must resume at the wake, not the timeout")
    }

    @Test
    fun awaitFallsThroughOnTimeoutWhenNoWake() = runTest {
        val signal = SyncWakeSignal()

        signal.awaitOrTimeout(timeoutMs = 1_500)

        assertEquals(1_500, testScheduler.currentTime, "no wake ⇒ awaitOrTimeout behaves like delay(interval)")
    }

    @Test
    fun wakesAreConflatedToASinglePermit() = runTest {
        val signal = SyncWakeSignal()

        // A burst of wakes collapses to one pending permit — one drain pass
        // clears whatever accumulated.
        signal.wake()
        signal.wake()
        signal.wake()

        signal.awaitOrTimeout(timeoutMs = 10_000)
        assertEquals(0, testScheduler.currentTime, "first await consumes the single conflated permit")

        // No permit left: the next wait runs the full interval.
        signal.awaitOrTimeout(timeoutMs = 2_000)
        assertEquals(2_000, testScheduler.currentTime, "burst must not bank multiple permits")
    }
}
