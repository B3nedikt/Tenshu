package org.tenshu.sync.proxy

import kotlinx.coroutines.CancellationException

/**
 * Runs one **derived-view tick** — build the row, then upsert it — absorbing a non-cancellation
 * throw rather than letting it escape the fire-and-forget coroutine that owns it.
 *
 * ## The bug this closes: one test's straggler failed a different test
 *
 * [initSyncHealth], [initContentTransferStatus] and [initContentUploadProgress] each keep a
 * single-row derived view current by collecting a [SyncHealthHolder] flow on a long-lived scope and
 * writing inside `database.transaction { … }`. That write is **synchronous and not cancellable** —
 * `transaction` is a `withLock`, so once entered it runs to completion whatever the job's state.
 *
 * `TestSystem.shutdown` cancels a client's scope and joins it with a **2 s cap** (deliberately, so
 * one stuck coroutine cannot hang the whole suite). When the cap trips, the straggler is abandoned
 * *alive*, `koinApp.close()` empties the Koin graph, and the straggler's next
 * `context.components[…]` lookup returns null into a non-null cast:
 *
 * ```
 * NullPointerException: null cannot be cast to non-null type …SchemaProvider
 *   at SchemaProviderKt.schemaProvider(SchemaProvider.kt:129)
 *   at DatabaseImpl.getDataId(DatabaseImpl.kt:578)
 *   at DatabaseImpl.addToArray(DatabaseImpl.kt:137)
 *   at ContentTransferInitializationKt$initContentTransferStatus$1$2.emit(…:68)
 * ```
 *
 * ⚠⚠ **The damage was not the throw, it was where the blame landed.** A `StandaloneCoroutine` on a
 * scope with no `CoroutineExceptionHandler` hands its exception to kotlinx-coroutines-test's
 * *global* `ExceptionCollector`, which replays it into **the next `runTest` in the JVM** as
 * `UncaughtExceptionsBeforeTest` at `TestScopeImpl.enter`. So the reported victim was whichever
 * `runTest` happened to run next — which is exactly why `docs/tech-debt.md` §"The full gate is
 * flaky under parallel load" records this as *"a different test fails each run"*, and why chasing
 * the named victim never converged. Observed on 2026-09-02 with the harness's own cap-trip log one
 * screen above the failure it caused.
 *
 * ## Why absorbing is correct here, and not suppression
 *
 * All three views are **local derivations that are re-derived on the next tick** — never synced
 * off-device (see each function's KDoc), and the holder's flow ticks every scheduler pass. A
 * dropped upsert means the banner is one tick stale and then correct. Against that, an escaping
 * throw costs the *whole process*: unhandled in a fire-and-forget scope it reaches the platform
 * default handler, which on Android is a crash, and under test it blames a stranger.
 *
 * ⚠ It keeps collecting rather than returning, deliberately: killing the collector on one bad
 * write would leave the view permanently stale for the session, which is worse than the tick it
 * dropped — the same "retry, never emit a wrong empty" reasoning `FitProjectionRepository`'s
 * `retryingOnThrow` records.
 *
 * ⚠ **Not the fix for the straggler itself**, which is a real lifecycle wart. Raising or removing
 * the harness's 2 s cap only narrows the window — a bounded join can always abandon a
 * non-cancellable section — so what has to stop is the *cross-test blame*. The straggler now names
 * itself on stdout instead.
 *
 * ⚠⚠ **The tick, not just the write.** A first attempt guarded only `database.transaction { … }`,
 * because that is where the *observed* production trace threw (`addToArray` → `getDataId` →
 * `schemaProvider`). [DerivedViewStragglerTest] immediately caught that as too narrow: the row
 * *construction* one line earlier — `SyncHealthStatus.create(context = context, …)` →
 * `DataFactory.createDto` → `schemaProvider` — reads the same graph and throws the same way. Which
 * of the two loses the race is a matter of timing, so both belong inside the guard.
 *
 * ⚠ [CancellationException] is rethrown, never logged: it is how a cancelled scope unwinds, and
 * swallowing it would make these collectors un-cancellable. `Error` is deliberately not caught —
 * an `OutOfMemoryError` must not be absorbed to keep a banner current.
 */
internal inline fun guardDerivedViewTick(view: String, tick: () -> Unit) {
    try {
        tick()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println(
            "[sync] derived view '$view': tick dropped — ${e::class.simpleName}: ${e.message}. " +
                "The view re-derives on the next holder tick; if this fired during shutdown, the " +
                "owning scope outlived its Koin graph (docs/tech-debt.md, gate-flake P0b).",
        )
    }
}
