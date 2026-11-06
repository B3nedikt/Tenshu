package org.tenshu.core.common.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The contract of [runCatchingCancellable] and [bestEffort]: the one thing they add over
 * `runCatching` is that a [CancellationException] leaves rather than becomes a value.
 *
 * ⚠⚠ Two of these tests assert against a **real** cancellation rather than a thrown
 * `CancellationException`, because only the real one proves the property callers depend on: that
 * the coroutine *stops*. A guard can pass "it rethrows what the block threw" and still leave the
 * work after it running, which is the bug this pair exists to close.
 */
class CancellationGuardsTest {

    // ---- runCatchingCancellable ----

    @Test
    fun runCatchingCancellable_returnsTheValue() {
        assertEquals("ok", runCatchingCancellable { "ok" }.getOrNull())
    }

    @Test
    fun runCatchingCancellable_foldsAnOrdinaryFailure() {
        val outcome = runCatchingCancellable { error("store unavailable") }

        assertTrue(outcome.isFailure, "an ordinary throwable is still folded; got $outcome")
        assertEquals("store unavailable", outcome.exceptionOrNull()?.message)
    }

    @Test
    fun runCatchingCancellable_rethrowsCancellation() {
        assertFailsWith<CancellationException> {
            runCatchingCancellable { throw CancellationException("caller went away") }
        }
    }

    @Test
    fun runCatchingCancellable_aCancelledCallerStopsAtTheGuard() = runTest {
        // The property in full: a coroutine cancelled *inside* the guard does not come back out
        // of it and run the rest of its body.
        val entered = CompletableDeferred<Unit>()
        var ranAfterTheGuard = false

        val job = launch {
            runCatchingCancellable {
                entered.complete(Unit)
                // Suspends until cancelled — the shape of every site this guards.
                CompletableDeferred<Unit>().await()
            }
            ranAfterTheGuard = true
        }
        entered.await()
        job.cancel()
        job.join()

        assertFalse(ranAfterTheGuard, "the cancellation was swallowed and the body carried on")
    }

    // ---- bestEffort ----

    @Test
    fun bestEffort_absorbsAnOrdinaryFailure() {
        var reachedTheNextLine = false

        bestEffort("a test -> writing a note") { error("store unavailable") }
        reachedTheNextLine = true

        assertTrue(reachedTheNextLine, "the failure was absorbed, so the caller continues")
    }

    @Test
    fun bestEffort_rethrowsCancellation() {
        assertFailsWith<CancellationException> {
            bestEffort("a test -> writing a note") { throw CancellationException("caller went away") }
        }
    }

    @Test
    fun bestEffort_aCancelledCallerStopsAtTheGuard() = runTest {
        val entered = CompletableDeferred<Unit>()
        var ranAfterTheGuard = false

        val job = launch {
            bestEffort("a test -> waiting on a lock") {
                entered.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
            ranAfterTheGuard = true
        }
        entered.await()
        job.cancel()
        job.join()

        assertFalse(ranAfterTheGuard, "the cancellation was swallowed and the body carried on")
    }

    // ---- what the bare stdlib guard does, so the difference is pinned rather than assumed ----

    @Test
    fun aBareRunCatchingIsWhatTheseReplace() = runTest {
        var ranAfterTheGuard = false
        val entered = CompletableDeferred<Unit>()

        val job = launch {
            runCatching {
                entered.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
            // ⚠ Reached *after* the cancel below: the bare guard turned it into a value. Asserting
            // it here is what makes the two tests above mean something.
            ranAfterTheGuard = true
            yield()
        }
        entered.await()
        job.cancel()
        job.join()

        assertTrue(ranAfterTheGuard, "precondition: a bare runCatching does swallow cancellation")
    }

    @Test
    fun runCatchingCancellable_isTransparentToAnAsyncResult() = runTest {
        val value = async { runCatchingCancellable { 7 } }.await()

        assertEquals(7, value.getOrNull())
        assertNull(value.exceptionOrNull())
    }
}
