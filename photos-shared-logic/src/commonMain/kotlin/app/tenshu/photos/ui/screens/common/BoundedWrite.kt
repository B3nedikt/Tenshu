package app.tenshu.photos.ui.screens.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/** How one write ended (`photos-ios.md` §8.16). */
enum class WriteOutcome {
    /** It happened — answered, or confirmed against the store after its answer was lost. */
    Done,

    /** It was answered NO. Nothing changed. */
    Refused,

    /**
     * No answer in time, and the store does not show it yet. ⚠ It may still land — the write is never
     * cancelled by a timeout — so this is never worded as a refusal.
     */
    Unconfirmed,
}

/**
 * Runs one write, bounded, and returns how it FINALLY ended. The caller holds its single-flight
 * lock for the whole call.
 *
 * 0. [alreadyDone] is asked FIRST. True → [WriteOutcome.Done] with nothing written: there is nothing to
 *    do. Unanswered or false → the write runs.
 * 1. Answered within [timeoutMs] — counted from the START of (0), so the whole call is bounded by
 *    [timeoutMs] + [settleMs] — → [WriteOutcome.Done] / [WriteOutcome.Refused].
 * 2. Not answered → [onUnconfirmed] runs — the caller says "still waiting" — and the write is awaited
 *    up to [settleMs] MORE: a late answer is returned as Done / Refused; none is
 *    [WriteOutcome.Unconfirmed], and the hung call is let go of.
 *
 * ⚠⚠ **A timed-out write is never confirmed from a VALUE**. "The store now has
 * this name" cannot tell this rename from a peer's identical one, nor from a value that was there
 * before; such a check confirms a write still able to commit, releases the lock, and lets a
 * following delete be undone by the late upsert. Only the write's own answer confirms it; without
 * one, the caller publishes what the store holds WITHOUT claiming this write put it there.
 *
 * ⚠⚠ Why the lock is held through (2): a Binder transaction cannot be interrupted, so a write that
 * has not answered can still commit, and a conflicting edit run beside it reorders them. ⚠⚠ Why (2)
 * ends: a lost reply never returns, and an unbounded watcher per retry keeps the view model alive
 * for good. ⚠ What is left — a write that commits after BOTH bounds — only server-side ordering can
 * close; it is recorded, not hidden (`photos-ios.md` §8.16).
 */
suspend fun CoroutineScope.boundedWrite(
    timeoutMs: Long,
    settleMs: Long,
    write: suspend () -> Boolean,
    alreadyDone: suspend () -> Boolean,
    onUnconfirmed: () -> Unit,
): WriteOutcome {
    // ⚠⚠ ONE window for the pre-check AND the write's first wait: timed one after the other, a
    // pre-check whose reply is lost would spend a whole window before the write got its own two —
    // three windows with no way out, and no "still waiting" until the second had passed.
    val check = detached { alreadyDone() }
    var started: Deferred<Boolean>? = null
    val answered = withTimeoutOrNull(timeoutMs) {
        if (check.await()) return@withTimeoutOrNull WriteOutcome.Done
        val answer = detached { write() }.also { started = it }.await()
        if (answer) WriteOutcome.Done else WriteOutcome.Refused
    }
    if (answered != null) return answered
    check.cancel()
    // The pre-check never answered → the write still runs (0), inside what is left: the settle window.
    val running = started ?: detached { write() }
    println("boundedWrite -> unanswered after ${timeoutMs}ms; still waiting")
    onUnconfirmed()
    return when (withTimeoutOrNull(settleMs) { running.await() }) {
        true -> WriteOutcome.Done
        false -> WriteOutcome.Refused
        null -> {
            println("boundedWrite -> still unanswered after a further ${settleMs}ms; abandoned")
            running.cancel()
            WriteOutcome.Unconfirmed
        }
    }
}

/**
 * One reconciliation read, bounded. ⚠⚠ The read runs in its OWN job and only the WAIT is timed —
 * `CollectionsViewModel.refresh`'s rule. A Binder read cannot be interrupted, so
 * `withTimeoutOrNull { landed() }` would wait for a cancellation that never completes, and hold
 * the lock with it. Abandoned, the read is cancelled and left to finish (or not) on its own; `null`
 * means unknown.
 */
suspend fun boundedRead(timeoutMs: Long, read: suspend () -> Boolean): Boolean? = boundedCall(timeoutMs, read)

/**
 * Any one call, bounded and [detached] — [boundedRead]'s rule for a call that returns a value
 * (`photos-ios.md` §8.17): only the wait is timed, and an abandoned call holds nothing but its own
 * closure, which must close over locals, never a view model. `null` means unknown.
 * ⚠ For READS only: the call is cancelled when its caller is. A write is [boundedWrite]'s.
 */
suspend fun <T : Any> boundedCall(timeoutMs: Long, call: suspend () -> T): T? {
    val job = detached { call() }
    try {
        return withTimeoutOrNull(timeoutMs) { job.await() }
    } finally {
        // ⚠ Let go of on EVERY way out, a cancelled caller included (`photos-ios.md` §8.22.2): a
        // search superseded by the next keystroke is cancelled while its read is still queued or
        // running, and nobody is left to take the answer. Where the read runs on a dispatcher — iOS —
        // one that has not started then never does, instead of a walk of the library per keystroke
        // piling up behind the one that counts. (A no-op once the call has answered.)
        job.cancel()
    }
}

/**
 * Starts [block] OUTSIDE the caller's job. ⚠⚠ A Binder call ignores cancellation, so an abandoned
 * one never completes; as a structured child it would keep the caller's job — and with it the view
 * model, the repository and every closure — alive for good, one more per retry. Detached, it holds
 * only its own call; the caller's job completes, and cancelling the caller does not reach the call,
 * which a Binder transaction would not honour anyway.
 */
private suspend fun <T> detached(block: suspend () -> T): Deferred<T> {
    val context = currentCoroutineContext().minusKey(Job)
    return CoroutineScope(context + SupervisorJob()).async { block() }
}
