package org.tenshu.core.common

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * When the client **received** each stream element, so [DefaultInteractorProvider] can tell an
 * element of the subscription it routes to from one an earlier subscription of the same id left
 * queued.
 *
 * ⚠⚠ Stream ids are callstack hashes, so the same chain subscribed again reuses the id, and every
 * delivery path queues elements in front of one consumer that can lag
 * (`forwardStreamElementsInOrder` in process, `RemoteBinaryTaskExecutor`'s push lane over IPC). A
 * subscription that ended with its snapshot still queued left it addressed to the id, and the next
 * subscription of the chain was handed it ahead of its own: a stale first emission, and — since
 * `TList.flow` merges — a row deleted in between that stayed for good.
 *
 * The rule is causal, so it needs no generation on the wire: an interactor's own elements can only
 * be produced after the proxy received its start, and the provider sends that start **after** it
 * registers the interactor. So anything received at or before the registration stamp belongs to an
 * earlier subscription and is dropped. The stamp is taken where the transport hands an element over
 * — in arrival order, before the queue — never by the consumer, whose lag is the whole problem.
 *
 * One process-wide counter: only the order between a receipt and a registration matters, never which
 * transport or provider either belongs to.
 */
@OptIn(ExperimentalAtomicApi::class)
object StreamElementReceipts {
    private val counter = AtomicLong(0L)

    /** A fresh receipt stamp, for an element received now. Called on the transport's thread; never suspends. */
    fun stamp(): Long = counter.incrementAndFetch()

    /** The latest stamp handed out — what a registration made now compares later receipts against. */
    fun current(): Long = counter.load()
}

/**
 * The receipt stamp of the stream element being dispatched, carried as a context element so it
 * reaches [DefaultInteractorProvider.dispatchElement] through the apps' push handlers
 * (`wireClientPushDispatch` and its inline copies) without changing their signature.
 *
 * ⚠ Absent means "not stamped": the element is routed as before. Only the two queues stamp; a test
 * double dispatching directly keeps the old routing.
 */
class StreamElementReceipt(val stamp: Long) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<StreamElementReceipt>
}
