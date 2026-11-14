package org.tenshu.core.common

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Wait until this state satisfies [predicate], and return the state that did — **the value you
 * then assert on** (`docs/testing-flakes.md`, category A).
 *
 * The sampling race this replaces: a test reads `.value` once, at a moment it has no right to
 * name, while the producer is still on another thread (`ModelsLedgerTest`: a claim released in a
 * `finally` a moment after the answer, 1 in 9 on Linux), or waits on one signal and asserts another
 * (`Zigbee2MqttBridgeTest`, `FitPipelineIntegrationTest`). Awaiting the predicate makes the read a
 * fact. ⚠ Assert on the returned value, not on a fresh `.value` read afterwards — that is the same
 * race one line later.
 *
 * ⚠⚠ [timeout] is a **hang guard, not a latency budget**, and it is real time only where the
 * caller's clock is: use this from `runBlocking` or a real dispatcher. Under `runTest` the deadline
 * is virtual and can fire the instant the test coroutine idles while a real thread is still
 * working — there, call `first(predicate)` and let `runTest`'s own timeout bound it.
 *
 * On timeout the failure names [what], the last state seen and `elapsed / budget`. The ratio is
 * the evidence the repo's own rule asks for before anyone raises a number (`docs/tech-debt.md`,
 * *"Measure the wait before raising a timeout"*): a wait that normally takes 50 ms and died at its
 * budget is a signal that never arrived, not a slow machine.
 */
suspend fun <T> StateFlow<T>.awaitState(
    what: String,
    timeout: Duration = 20.seconds,
    predicate: (T) -> Boolean,
): T {
    val started = TimeSource.Monotonic.markNow()
    return try {
        withTimeout(timeout) { first(predicate) }
    } catch (e: TimeoutCancellationException) {
        throw AssertionError(
            "Timed out waiting for $what after ${started.elapsedNow()} / $timeout; last state = $value",
            e,
        )
    }
}
