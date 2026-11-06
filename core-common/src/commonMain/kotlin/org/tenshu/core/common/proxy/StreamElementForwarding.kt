package org.tenshu.core.common.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tenshu.core.StreamElement
import org.tenshu.core.StreamElementDispatcher
import org.tenshu.core.common.StreamElementReceipt
import org.tenshu.core.common.StreamElementReceipts

/**
 * Forward every [StreamElement] this connection receives to [dispatcher] **in the order the proxy
 * sent them**, off the sending thread.
 *
 * ⚠⚠ Not `scope.launch { dispatcher.dispatchElement(data) }` per element. Two launches back to
 * back are two independent coroutines, and a multi-threaded dispatcher promises no order between
 * them. Whenever the later one runs first, a deferred-property *patch* reaches the client before
 * the *snapshot* it patches; `TList.flow()` keeps *enqueue* order, so it presents the patched row
 * first and the marker row on top of it — an "initial emission already carries the preview".
 * `FileDetailsDeferredPreviewForcedInterleavingTest` (`:drive-shared-ui`) forces exactly that
 * interleaving: red with a per-element launch, green with this.
 *
 * Each element is stamped with its receipt ([StreamElementReceipts]) as it is enqueued, so the
 * provider can drop one a re-subscription of the same id would otherwise be handed.
 *
 * One consumer coroutine on [scope]'s dispatcher drains an unbounded channel, so delivery is serial and in
 * arrival order; the sending thread only enqueues — it never suspends and never dispatches, which
 * is the property the per-element launch existed for. A dispatch that throws is logged and the
 * next element is still delivered: an unhandled exception on a plain scope cancels the scope, and
 * every later launch on it is then dead on arrival — the stream would go silent for the rest of
 * the session.
 *
 * The consumer is **cancelled** when the connection is [LocalClientConnection.destroy]ed or
 * [scope]'s job completes, whichever comes first, so a long-lived scope does not accumulate one
 * consumer per session it ever adopted. ⚠ Cancelled, not merely closed: closing the channel would
 * let the consumer drain what is still queued, and a session torn down with frames queued —
 * Control's quiesce / re-mint path — must not deliver them once the next session's consumer is
 * running against the same provider: an old patch or terminal would land on the restarted read.
 * `StreamElementForwardingTest` pins both the order and the discard.
 *
 * ⚠⚠ It is deliberately **not a child** of [scope]. A consumer parked on an empty channel never
 * completes, and a structured scope — `withTimeout`, `runTest`, any `coroutineScope {}` — waits for
 * its children before it returns, and callers hand this scopes they expect to close. It runs on
 * [scope]'s dispatcher and context under
 * a Job of its own, cancelled when [scope]'s job completes.
 */
fun LocalClientConnection.forwardStreamElementsInOrder(
    scope: CoroutineScope,
    dispatcher: StreamElementDispatcher,
): Job {
    val elements = Channel<Received>(Channel.UNLIMITED)
    val owner = scope.coroutineContext[Job]
    val consumer = CoroutineScope(scope.coroutineContext + Job()).launch {
        for ((receipt, element) in elements) {
            // ⚠ `receive` hands out a buffered element WITHOUT checking cancellation, so a
            // consumer cancelled with a backlog would still deliver it; this is the discard.
            ensureActive()
            try {
                // Same dispatcher, so no hop: the receipt only rides along to the provider.
                withContext(StreamElementReceipt(receipt)) { dispatcher.dispatchElement(element) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                println(
                    "StreamElementForwarding -> dispatch failed for stream " +
                        "${element.streamId.get()}: ${e.message}"
                )
                e.printStackTrace()
            }
        }
    }
    // Registration is atomic with `destroy()` and a destroyed connection is remembered
    // (`LocalClientConnection`): the listener is refused and the hook below runs at once, so a
    // destroy that races this install — Control adopting a session while quiescing it — still ends
    // the consumer instead of leaking it on a process-lifetime scope.
    addListener { data ->
        // Stamped HERE, on the sending thread and in send order — not by the consumer, whose lag
        // is what lets an ended subscription's elements reach the next one (`StreamElementReceipts`).
        if (data is StreamElement<*>) elements.trySend(Received(StreamElementReceipts.stamp(), data))
    }
    addOnDestroy {
        consumer.cancel()
        elements.close()
    }
    consumer.invokeOnCompletion { elements.close() }
    owner?.invokeOnCompletion { consumer.cancel() }?.let { handle ->
        consumer.invokeOnCompletion { handle.dispose() }
    }
    return consumer
}

/** An element and its [StreamElementReceipts] stamp, taken when it was enqueued. */
private data class Received(val receipt: Long, val element: StreamElement<*>)
