package app.tenshu.reachability

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What [retryingOnThrow] does that `catch { emit(empty) }` could not.
 *
 * ⚠⚠ The difference is not cosmetic: `catch` **completes** the flow, so the old shape's screen was
 * dead from the first throw onwards — and it emitted an empty list on the way out, which every
 * consumer reads as *"you have nothing"*. Timeline's `unreachable(e)` did exactly that, and its
 * Home view model sets `daysLoaded = true` on any emission, so a single throw during launch
 * rendered a permanently empty timeline that no reconnect could repair.
 */
class RetryingReadsTest {

    @Test
    fun aThrowIsRetriedRatherThanEndingTheStream() = runTest {
        var attempts = 0
        val logged = mutableListOf<String>()

        val values = flow {
            attempts += 1
            if (attempts < 3) error("boom $attempts")
            emit("data")
        }.retryingOnThrow("moments") { message, _ -> logged += message }.take(1).toList()

        assertEquals(listOf("data"), values, "the read recovered instead of dying at the first throw")
        assertEquals(3, attempts)
        assertEquals(2, logged.size, "each throw is named, so the log says which read is looping")
        assertTrue(logged.first().contains("moments"), "got ${logged.first()}")
    }

    /**
     * ⚠⚠ The property the old `catch` inverted. A failing read must emit **nothing** — not an
     * empty list — because a consumer cannot tell an empty answer from an absent one, and the one
     * it assumes is "you have nothing".
     */
    @Test
    fun aFailingReadEmitsNothingRatherThanAnEmptyAnswer() = runTest {
        var attempts = 0
        val seen = mutableListOf<List<String>>()

        val job = backgroundScope.launch {
            flow<List<String>> {
                attempts += 1
                error("always")
            }.retryingOnThrow("places") { _, _ -> }.collect { seen += it }
        }

        testScheduler.advanceTimeBy(5_000)
        assertTrue(attempts > 1, "kept retrying, attempts=$attempts")
        assertEquals(emptyList(), seen, "a failing read must never claim the list is empty")
        job.cancel()
    }

    /**
     * ⚠ `retryWhen` does **not** exempt [CancellationException], so without the explicit arm a
     * cancelled screen's read would retry forever instead of stopping.
     */
    @Test
    fun cancellationPropagatesInsteadOfRetrying() = runTest {
        var attempts = 0

        assertFailsWith<CancellationException> {
            flow<String> {
                attempts += 1
                throw CancellationException("the screen went away")
            }.retryingOnThrow("traces") { _, _ -> }.first()
        }
        assertEquals(1, attempts, "cancellation is not a retryable failure")
    }

    /** ⚠ The backoff is a ceiling, not a give-up — a read that starts working again is served. */
    @Test
    fun theBackoffCeilingDoesNotStopTheRetrying() {
        assertEquals(15_000L, READ_RETRY_BACKOFF_MS[READ_RETRY_BACKOFF_MS.size - 1])
        assertTrue(
            (1 until READ_RETRY_BACKOFF_MS.size).all { READ_RETRY_BACKOFF_MS[it] > READ_RETRY_BACKOFF_MS[it - 1] },
            "monotonic",
        )
    }
}
