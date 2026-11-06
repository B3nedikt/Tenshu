package org.tenshu.core.common.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * `runCatching` for a block that **suspends** — identical in every way except that a
 * [CancellationException] is rethrown instead of folded into a `Result.failure`.
 *
 * ⚠⚠ **A bare `runCatching` around a suspend call breaks structured concurrency.** Cancellation
 * reaches a coroutine as a throw, so `catch (t: Throwable)` — which is what `runCatching` is —
 * swallows it: the cancelled coroutine returns a `Result.failure` and *carries on*, doing the rest
 * of its work for a caller that has gone. The two shapes that has already cost this project:
 *
 * - a fire-and-forget guard around bookkeeping runs the whole tail of the operation after the
 *   caller cancelled (`UploadFileImplementation`'s superseded-content notes);
 * - a guard whose failure branch is user-visible reports a **user-initiated stop as a failure**
 *   (`docs/tech-debt.md`, `TenshuFilesRepository.guardingSourceRead`).
 *
 * This is the drop-in for both: same signature, same `Result`, so a call site changes by one
 * token and keeps whatever `onFailure` / `getOrElse` / `fold` it already had. The rule it
 * enforces is the one `docs/tech-debt.md` already states for every such guard — rethrow
 * `CancellationException`, and name the cause before folding it.
 *
 * ⚠ It is deliberately **not** a blanket replacement for `runCatching`. Around a block that
 * cannot suspend there is no cancellation to catch, and the extra `catch` only obscures that;
 * leave those sites alone.
 *
 * ⚠ [CancellationException] here is `kotlin.coroutines.cancellation.CancellationException`, the
 * stdlib one that `kotlinx.coroutines.CancellationException` aliases, so this file — and every
 * site that inlines it — needs no coroutines dependency of its own.
 */
inline fun <R> runCatchingCancellable(block: () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }

/**
 * Work whose failure must not fail its caller: [what] is logged and the throwable absorbed.
 *
 * The shape is for bookkeeping that runs *after* the thing the caller asked for has already
 * landed — reclamation notes, retiring a superseded intent, a cache-warming write. Failing the
 * operation at that point would have the user retry something that happened.
 *
 * ⚠⚠ Cancellation is not a failure and is rethrown, for [runCatchingCancellable]'s reasons: these
 * blocks suspend, so a cancelled caller is most likely to land *here*, and absorbing it carries
 * the operation on into work nobody is waiting for.
 *
 * ⚠ The log is the other half of the rule. Absorbing is right, but silence is not a diagnosis:
 * the failure has no other surface, and the symptom downstream (bytes the relay never frees, a
 * waiting count that only grows) looks nothing like its cause. [what] names the step, in the
 * caller's own words, e.g. `"DeleteFile -> recording the content it orphaned"`.
 */
inline fun bestEffort(what: String, block: () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        println("$what failed: $t")
    }
}
