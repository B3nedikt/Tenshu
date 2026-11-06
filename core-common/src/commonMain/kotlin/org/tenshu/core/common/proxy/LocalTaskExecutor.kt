package org.tenshu.core.common.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.supervisorScope
import org.tenshu.core.Data
import org.tenshu.core.ExpectedOutcome
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.app.AnnounceSession
import org.tenshu.core.common.impl.InternalErrorDto
import org.tenshu.core.common.impl.UnannouncedSessionErrorDto
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.request.AsyncTaskExecutor
import org.tenshu.core.common.request.CancellableLocalTaskExecutor
import org.tenshu.core.common.request.CancellableReadTaskExecutor
import org.tenshu.core.common.request.SynchronousViolationException
import org.tenshu.core.id

class LocalTaskExecutor(
    private val executionContext: ExecutionContext
) : CancellableLocalTaskExecutor, CancellableReadTaskExecutor {

    override fun execute(task: TList<Data>): TResult<Data> {
        val callstack = task.toList()
        val resultValue = try {
            evaluateCallstack(
                executionContext = executionContext,
                contextData = null,
                callstack = callstack,
                allowDeferring = false
            )
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            return wrapException(e, callstack)
        }

        if (resultValue is AsyncReference) {
            throw SynchronousViolationException(
                "Synchronous Context Violation: Operation attempted to perform IO or suspend in a sync-only execution domain."
            )
        }

        return TResult.Success.create(context = executionContext.context, value = resultValue)
    }

    override suspend fun executeAsync(task: TList<Data>): TResult<Data> {
        var currentStack = task.toList()
        var currentData: Any? = null

        while (true) {
            val resultValue = try {
                evaluateCallstack(
                    executionContext = executionContext,
                    contextData = currentData,
                    callstack = currentStack
                )
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                return wrapException(e, currentStack)
            }

            if (resultValue is AsyncReference) {
                val asyncResult = try {
                    resultValue.asyncCall.await()
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    return wrapException(e, currentStack)
                }

                currentData = asyncResult
                currentStack = resultValue.remainingCallstack
            } else {
                return TResult.Success.create(
                    context = executionContext.context,
                    value = resultValue
                )
            }
        }
    }

    /**
     * In-process Phase L2 (`docs/proposals/core-background-processing.md` §8):
     * run [task] with an evaluation scope bound to *this* coroutine and
     * `runBackgroundInRequestScope = true`, so a [org.tenshu.core.trait.BackgroundOperation]
     * runs awaited (not the legacy detached `sessionScope.launch` +
     * `Success(null)`) and cancelling the caller — what `TaskHandle.cancel()`
     * does in-process — cooperatively unwinds it. The mirror of
     * `ProxyGateway`'s cross-process `requestScope` path; no wire involved.
     *
     * ⚠⚠ [supervisorScope], NOT `coroutineScope` — the request scope must be
     * **supervising**, exactly like the `SupervisorJob(parentJob)` its
     * cross-process twin `ProxyGateway.call` builds. `ExecutionContext` runs an
     * `AsyncOperationImplementation` as `evaluationScope.async { … }`, and an
     * ordinary `async` reports its failure to its parent job *in addition to*
     * exposing it through `await()`. Under `coroutineScope` that cancelled this
     * very scope, so a typed op throw (Shape A, `core-error-channel.md` §3.1 —
     * how EVERY impl reports failure) was caught by [executeAsync]'s catch-all,
     * turned into the `TResult.Failure` the caller expects, and *then* rethrown
     * out of the scope anyway as the block completed. In-process runtimes have
     * no wire boundary to absorb that second throw: it unwound the caller and
     * landed on the uncaught-exception handler, which on Kotlin/Native is
     * `abort()`. A routine `AlreadyExists` from the photo-backup drain's
     * `ensureBackupDestination` crashed Drive-on-iOS this way (iOS crash report
     * 2026-08-13; the drain is inside `withBulkUploadTask`, and it is the
     * `withTask` tag that routes an op here rather than to [executeAsync]).
     *
     * Supervision changes nothing else: cancellation still flows *downward*, so
     * `TaskHandle.cancel()` unwinds the op exactly as before — only the upward
     * failure edge, which the catch-all already owns, is cut.
     */
    override suspend fun executeAsyncCancellable(task: TList<Data>): TResult<Data> =
        supervisorScope {
            LocalTaskExecutor(
                executionContext.withEvaluationScope(
                    scope = this,
                    runBackgroundInRequestScope = true
                )
            ).executeAsync(task)
        }

    /**
     * TS-4: the in-process mirror of ProxyGateway threading the L2 envelope's
     * taskId to `ExecutionContext.requestSyncLaneId` — a withTask-tagged
     * constituent call's saves join the task's sync lane without any wire.
     * The explicit-open gate in `SyncObserver` applies identically.
     *
     * ⚠⚠ [supervisorScope] for the reason spelled out on the one-arg overload —
     * and this is the overload the crash arrived on, since a `withTask` block is
     * what routes an op down this path in the first place.
     */
    override suspend fun executeAsyncCancellable(task: TList<Data>, taskId: String?): TResult<Data> =
        supervisorScope {
            LocalTaskExecutor(
                executionContext.withEvaluationScope(
                    scope = this,
                    runBackgroundInRequestScope = true,
                    requestSyncLaneId = taskId
                )
            ).executeAsync(task)
        }

    /**
     * In-process **cancellable read** (`core-background-processing.md` §6.2.1): evaluated in a scope
     * bound to the caller's coroutine, with a [RequestCancellation] that observes that scope's job,
     * so cancelling the caller stops both the suspended steps and the synchronous row walk. The
     * in-process twin of `ProxyGateway.call(readId = …)`.
     *
     * ⚠ Not `runBackgroundInRequestScope`: that is a Layer-2 task's switch, and a read is not one.
     * ⚠⚠ [supervisorScope] for the reason [executeAsyncCancellable] spells out — a typed op throw
     * must reach the caller as a `TResult.Failure`, not escape the scope a second time.
     */
    override suspend fun executeAsyncCancellableRead(task: TList<Data>): TResult<Data> =
        supervisorScope {
            val cancellation = RequestCancellation(observed = coroutineContext[Job])
            try {
                LocalTaskExecutor(
                    executionContext.withEvaluationScope(
                        scope = this,
                        requestCancellation = cancellation,
                    )
                ).executeAsync(task)
            } finally {
                cancellation.disarm()
            }
        }

    /**
     * Cross-process entry point — Phase 3 of `docs/proposals/core-error-channel.md`
     * makes `LocalTaskExecutor` the single catch-all (v0.2 review-conversation
     * Option A). Parses [bytes] into the wire callstack and dispatches to
     * [executeAsync] under a unified catch that:
     *   - unwraps [TenshuException] to `TResult.Failure(typed-error)` —
     *     the dispatcher's `MissingParameter` / `UnknownParameter` /
     *     `WrongParameter` (Phase 2) and any impl-raised typed throw now reach
     *     the wire untouched;
     *   - wraps any other [Exception] as `TResult.Failure(InternalError(message))` —
     *     `InternalError.internalDetail` is **proxy-local only** (redacted on
     *     Phase 6 upload, proposal §5).
     *
     * `ProxyGateway` is therefore pure transport — no try/catch around the
     * pipeline. The previous `println` / `printStackTrace` of `e.message` are
     * gone (Phase 5's `:logging-service` is the replacement diagnostic sink).
     */
    suspend fun executeAsyncFromBytes(
        bytes: ByteArray,
        parser: BinaryParser
    ): TResult<Data> {
        val task = try {
            @Suppress("UNCHECKED_CAST")
            parser.parseCallstack(bytes) as TList<Data>
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            // Parser failure — no usable callstack to inspect.
            return wrapException(e, callstack = emptyList())
        }
        val callstack = task.toList()
        schemaSessionGate(callstack)?.let { return it }
        return executeAsync(task)
    }

    /**
     * Reject-if-silent gate (`core-schema-evolution.md` §6.5), applied ONLY at
     * this serialized-transport entry — in-process callers pass live objects,
     * which cannot positionally garble. A session with no admitted announce
     * (§6.4, [ExecutionContext.sessionAnnouncement]) may execute exactly one
     * thing: the `AnnounceSession` op itself. Everything else fails with the
     * typed [org.tenshu.core.UnannouncedSessionError] instead of risking a
     * skewed client's positional bytes mid-parse — the HR #9 cryptic
     * `error/internal` class this gate exists to kill (§6.7).
     *
     * ⚠⚠ Runs before any op executes, and must stay that way: clients resend a call refused here
     * once, after re-announcing (`RemoteBinaryTaskExecutor`). An `UnannouncedSessionError` returned
     * after an op ran, a write above all, would make that resend run the op twice.
     */
    private fun schemaSessionGate(callstack: List<Data>): TResult<Data>? {
        if (!SchemaSessionPolicy.REJECT_IF_SILENT) return null
        if (executionContext.sessionAnnouncement.announced) return null
        val isAnnounce = callstack.any { it is Operation && it.id == AnnounceSession.ID }
        if (isAnnounce) return null
        return wrapException(
            TenshuException(
                UnannouncedSessionErrorDto(
                    message = "session has no admitted schema announce — " +
                        "call ${AnnounceSession.ID} first (schema-evolution §6.5)"
                )
            ),
            callstack = callstack,
        )
    }

    private fun wrapException(e: Throwable, callstack: List<Data>): TResult<Data> {
        val typedError = when (e) {
            is TenshuException -> e.error
            else -> InternalErrorDto(internalDetail = e.message)
        }
        // Phase 5: fire the proxy-side recorder if wired. Top-level op id +
        // depth only — inner-callstack ids and entity ids are deliberately
        // not read (proposal §4.2). The recorder swallows its own failures;
        // we still wrap defensively so a misbehaving recorder can never
        // escape the catch-all and reach the wire.
        // ⚠ An `ExpectedOutcome` is part of the operation's contract, so it is NOT a diagnostic —
        // see that interface for the measurement (732 of 1 249 events on a real device were one
        // error type that means "working as designed"). The typed failure below is unaffected: the
        // caller still receives it, this only keeps it out of the error log.
        executionContext.errorEventRecorder?.takeIf { typedError !is ExpectedOutcome }?.let { recorder ->
            try {
                val operationId = callstack.firstOrNull { it is Operation }?.id.orEmpty()
                recorder.record(
                    error = typedError,
                    operationId = operationId,
                    callstackDepth = callstack.size,
                )
            } catch (_: Throwable) {
                // Best-effort.
            }
        }
        return try {
            TResult.Failure.create(
                context = executionContext.context,
                error = typedError
            )
        } catch (_: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Building the Failure needs a live context: `create` resolves
            // `dataFactory()` / `schemaProvider()` out of the (DI-backed)
            // component map, and after teardown those resolve to null. The
            // catch-all then died *inside the error path* with a
            // `NullPointerException: null cannot be cast to non-null type
            // DataFactory` naming a collaborator that has nothing to do with
            // the original failure — landing uncaught on whatever dispatcher
            // the op ran on, which in tests is attributed to an unrelated test
            // (`docs/tech-debt.md` "Proxy Background Lifecycle …").
            //
            // There is no honest `TResult` to return here — without a context
            // no `Data` can be constructed at all — so surface the *original*
            // cause instead of the red herring. Callers that already had to
            // handle a throw still do; what they no longer get is a misleading
            // one.
            throw e
        }
    }
}
