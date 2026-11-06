package org.tenshu.core.common.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The cooperative half of a **cancellable read** (`core-background-processing.md` §6.2.1): lets the
 * proxy's *synchronous* evaluation notice that the read was abandoned.
 *
 * ⚠⚠ Why it exists: cancelling the request's `Job` unwinds only *suspension points*, and almost all
 * of a read is not one. `evaluateCallstack`, `GetAll`'s `toList()`, a filter walk and the ordered-
 * window fusion are plain loops over a row `Sequence`, so a cancelled `Job` let the walk run to the
 * last row and the reply still be serialised — measured on a device as a cancelled feed page whose
 * answer arrived 1.9–8.2 s after its caller had gone (`photos-app.md` §37). [checkpoint] is called
 * per row (the [ExecutionContext] row seams) and per chain step (`evaluateCallstack`), so the walk
 * stops within one row of the cancel.
 *
 * Tripped by one of two things, never by the request merely ending:
 * - [cancel] — the client said so (`ProxyGateway.cancelRead`, a Binder `cancelRead` or the desktop
 *   `TYPE_CANCEL_READ` frame). ⚠ *Not* the request job's own cancellation: a session teardown
 *   cancels that job too, and turning teardown into a mid-walk abort is a separate decision (a write
 *   in the same chain would stop half-way) that this does not take.
 * - [observed] being cancelled — the in-process case, where the job *is* the caller's
 *   (`LocalTaskExecutor.executeAsyncCancellableRead`): there is no other party to cancel it.
 *
 * ⚠⚠ [disarm] is what makes it safe to hang off a per-request [ExecutionContext]. That context can
 * outlive its request — a stream subscribed through it re-evaluates on it at every later store
 * change — and an armed checkpoint there would throw into a listener long after the read it
 * belonged to had finished. Every owner disarms in `finally`.
 */
@OptIn(ExperimentalAtomicApi::class)
class RequestCancellation(private val observed: Job? = null) {

    private val cancelled = AtomicBoolean(false)
    private val armed = AtomicBoolean(true)

    /** The client abandoned the read. Idempotent. */
    fun cancel() {
        cancelled.store(true)
    }

    /** Whether [cancel] was called — read by the gateway to tell an abandoned read from a teardown. */
    val cancelledByClient: Boolean get() = cancelled.load()

    /** The request is over; from here on [checkpoint] never throws. Idempotent. */
    fun disarm() {
        armed.store(false)
    }

    /** Throws [CancellationException] when the read was abandoned and its request is still running. */
    fun checkpoint() {
        if (!armed.load()) return
        if (cancelled.load() || observed?.isCancelled == true) {
            throw CancellationException("Read cancelled by its caller")
        }
    }
}

/** [this] with a [RequestCancellation.checkpoint] before every row; the sequence itself if [cancellation] is null. */
internal fun <T> Sequence<T>.checkpointedBy(cancellation: RequestCancellation?): Sequence<T> =
    if (cancellation == null) this else onEach { cancellation.checkpoint() }
