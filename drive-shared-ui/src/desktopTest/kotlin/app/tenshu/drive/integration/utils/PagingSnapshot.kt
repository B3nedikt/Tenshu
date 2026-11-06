package app.tenshu.drive.integration.utils

import androidx.paging.PagingData
import androidx.paging.testing.SnapshotLoader
import androidx.paging.testing.asSnapshot
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeout

/**
 * A deadline that exists ONLY to make a hang loud. It is not a latency budget.
 *
 * The bounded calls in this suite take **0.03–3.8 s** measured in isolation, so 30 s is a
 * ~10×–1000× margin. ⚠⚠ **If one of these ever fires, do not raise this number.** At that
 * margin a timeout means a signal never arrived, never that the box was slow, and widening the
 * budget hides the real bug (a dropped stream terminal, an event that can never arrive). See
 * `docs/tech-debt.md` § "The full gate is flaky under parallel load".
 */
private const val SNAPSHOT_TIMEOUT_MS = 30_000L

/**
 * [asSnapshot] with a deadline, for a listing that never completes.
 *
 * `asSnapshot` returns on exactly two conditions — the differ's refresh settles, or the
 * upstream flow **completes**. Every Tenshu paged listing (`getFilesPaged`, and the sorted
 * overload) is a live folder listener that never completes, so settling load states are its
 * only exit. Lose them and the call waits *for ever*.
 *
 * ⚠⚠ **A hung test does not go red — it reads GREEN.** The test worker parks, the gate stops
 * making progress, and whoever is waiting eventually kills it — at which point the in-flight
 * case is recorded **SKIPPED** and the run reports `failures="0"`. A lost load-state delivery
 * shows up this way: the worker parked in `joinBlocking` with every dispatcher thread idle
 * (`docs/tech-debt.md` § "A hung test does not go red").
 *
 * ⚠ **Only needed for a `runBlocking` body.** A `runTest` body already carries kotlinx's
 * 60 s default, and wrapping one of those in [withTimeout] would be *harmful*: `runTest`
 * auto-advances **virtual** time whenever the test coroutine is idle, so a virtual deadline
 * can fire the instant the snapshot waits on anything real. The unbounded `asSnapshot` calls
 * in `EventsFeedIntegrationTest` and `PagingSourceTest` are deliberately left alone for that
 * reason — they are bounded already, by a mechanism that understands their clock.
 *
 * ⚠ Prefer this to a hand-written `withTimeout` at the call site so the rule stays in one
 * place and stays greppable; two spellings of it is how the next person misses it.
 */
suspend fun <T : Any> Flow<PagingData<T>>.boundedSnapshot(
    timeoutMillis: Long = SNAPSHOT_TIMEOUT_MS,
    loadOperations: suspend SnapshotLoader<T>.() -> Unit = {},
): List<T> {
    val started = System.nanoTime()
    // When each PagingData generation arrived, in ms since collection began. `pagingSource` builds
    // a fresh `Pager` per change signal through `flatMapLatest`, so this separates the two ways a
    // listing can fail to settle: generations still arriving at 30 s = an invalidation loop
    // (the differ is reset faster than a refresh completes); generations that stop early and then
    // silence = a load that never returned, i.e. a signal that never arrived.
    val generations = mutableListOf<Long>()
    return try {
        withTimeout(timeoutMillis) {
            onEach { generations += (System.nanoTime() - started) / 1_000_000 }
                .asSnapshot(loadOperations = loadOperations)
        }
    } catch (timeout: TimeoutCancellationException) {
        // ⚠⚠ The timeout alone names only the waiter, never the cause — and this hang costs a whole
        // gate run to observe, so the failure message carries the generations and the stacks.
        throw AssertionError(
            buildString {
                appendLine("asSnapshot did not settle within ${timeoutMillis}ms — the listing never settled.")
                appendLine("See docs/tech-debt.md § 'A hung test does not go red'.")
                appendLine("PagingData generations arrived at (ms): $generations")
                appendLine("--- threads mentioning tenshu/paging/coroutines ---")
                append(diagnosticThreadDump())
            },
            timeout,
        )
    }
}

/** Stacks of the threads that could plausibly hold a listing open, trimmed to stay readable. */
private fun diagnosticThreadDump(): String =
    Thread.getAllStackTraces()
        .filter { (thread, stack) ->
            thread.name.contains("DefaultDispatcher") ||
                thread.name.contains("Test worker") ||
                thread.name.contains("kotlinx.coroutines") ||
                stack.any { it.className.startsWith("org.tenshu") || it.className.startsWith("app.tenshu") }
        }
        .entries
        .joinToString("\n") { (thread, stack) ->
            buildString {
                appendLine("\"${thread.name}\" ${thread.state}")
                stack.take(24).forEach { appendLine("\tat $it") }
            }
        }
