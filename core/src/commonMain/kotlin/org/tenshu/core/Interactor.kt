package org.tenshu.core

import kotlinx.coroutines.Job
import org.tenshu.core.request.RequestObject

/**
 * A unified execution adapter that bridges operation chain construction to actual execution.
 */
sealed interface Interactor<out T : Data?>

/**
 * Interactor for synchronous operations.
 */
interface SyncInteractor<out T : Data?> : Interactor<T> {
    /**
     * Executes the operation immediately and returns the result.
     */
    fun execute(): TResult<T>
}

/**
 * Interactor for background operations that outlive the request.
 *
 * Superseded by [CancellableTaskInteractor], the Phase L2 Layer-2 runtime
 * (`docs/proposals/core-background-processing.md` §8). This fire-and-forget
 * `Job` shape has no production call sites and the provider no longer builds
 * it — a [org.tenshu.core.trait.BackgroundOperation] now resolves to a
 * [CancellableTaskInteractor]. Kept for one release; do not add new callers.
 */
@Deprecated(
    "Superseded by CancellableTaskInteractor (Phase L2). A BackgroundOperation " +
        "now resolves to a TaskHandle-returning CancellableTaskInteractor.",
    ReplaceWith("CancellableTaskInteractor<T>")
)
interface BackgroundInteractor<out T : Data?> : Interactor<T> {
    /**
     * Schedules the operation in [TContext]'s service scope and returns the [Job].
     */
    fun execute(): Job
}

/**
 * Interactor for long-running [org.tenshu.core.trait.BackgroundOperation] tasks
 * that the caller can cancel across the IPC boundary — the Phase L2 Layer-2
 * runtime (`docs/proposals/core-background-processing.md` §8).
 *
 * [execute] starts the task and returns a [TaskHandle] immediately (it does
 * not suspend for the result); the caller awaits the handle for the result and
 * may [TaskHandle.cancel] it from an unrelated coroutine (e.g. a Cancel
 * button). Existing [TaskInteractor] callers are untouched — this variant is
 * opt-in by the [org.tenshu.core.trait.BackgroundOperation] trait.
 */
interface CancellableTaskInteractor<out T : Data?> : Interactor<T> {
    fun execute(): TaskHandle<T>
}

/**
 * Extension for DSL authors to get a [TaskInteractor] for a [RequestObject].
 */
suspend fun <T : Data?> RequestObject.asTaskInteractor(): TaskInteractor<T> =
    interactorProvider().getInteractor<T>(this) as? TaskInteractor<T>
        ?: error("Operation is not an IoOperation; use the appropriate as*Interactor extension.")

/**
 * Extension for DSL authors to get a [SyncInteractor] for a [RequestObject].
 */
suspend fun <T : Data?> RequestObject.asSyncInteractor(): SyncInteractor<T> =
    interactorProvider().getInteractor<T>(this) as? SyncInteractor<T>
        ?: error("Operation is async/stream/background; use the appropriate as*Interactor extension.")

/**
 * Extension for DSL authors to get a [BackgroundInteractor] for a [RequestObject].
 */
@Deprecated(
    "Superseded by asCancellableTaskInteractor (Phase L2).",
    ReplaceWith("this.asCancellableTaskInteractor<T>()")
)
@Suppress("DEPRECATION")
suspend fun <T : Data?> RequestObject.asBackgroundInteractor(): BackgroundInteractor<T> =
    interactorProvider().getInteractor<T>(this) as? BackgroundInteractor<T>
        ?: error("Operation is not a BackgroundOperation; use the appropriate as*Interactor extension.")

/**
 * Extension for DSL authors to get a [CancellableTaskInteractor] for a
 * [RequestObject] — the Phase L2 runtime for [org.tenshu.core.trait.BackgroundOperation]
 * ops. The returned interactor's `execute()` yields a [TaskHandle].
 */
suspend fun <T : Data?> RequestObject.asCancellableTaskInteractor(): CancellableTaskInteractor<T> =
    interactorProvider().getInteractor<T>(this) as? CancellableTaskInteractor<T>
        ?: error("Operation is not a BackgroundOperation; use the appropriate as*Interactor extension.")

/**
 * Extension for DSL authors to get a [StreamInteractor] for a [RequestObject].
 */
suspend fun <T : Data> RequestObject.asStreamInteractor(): StreamInteractor<T> =
    interactorProvider().getInteractor<T>(this) as? StreamInteractor<T>
        ?: error("Operation is not a stream; use the appropriate as*Interactor extension.")
