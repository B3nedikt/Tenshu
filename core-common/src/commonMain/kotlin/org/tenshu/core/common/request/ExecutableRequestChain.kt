package org.tenshu.core.common.request

import org.tenshu.core.Data
import org.tenshu.core.request.RequestChain

interface ExecutableRequestChain {

    val streamId: Long

    /** This chain with its [streamId] derived from its callstack — see [streamIdOf]. */
    fun createStreamId(signalOnly: Boolean = false): RequestChain
}

val RequestChain.streamId get() = (this as ExecutableRequestChain).streamId

fun RequestChain.createStreamId(signalOnly: Boolean = false) =
    (this as ExecutableRequestChain).createStreamId(signalOnly)

/**
 * A stream's id: its callstack's hash, so two subscriptions of one chain share one interactor (and
 * one proxy registration).
 *
 * ⚠⚠ **A signal-only subscription (`changes()`) lives in its own id space.** The proxy answers it
 * with payload-free signals (`StreamRegistry.setSignalListener`), so a `flow()` of the same chain
 * that joined it would receive no rows at all — an empty list forever. Flipping bit 40 keeps the two
 * apart for every hash: a sign-extended `Int` has bits 32–63 all equal, and the flip breaks that.
 */
fun streamIdOf(callstack: List<Data>, signalOnly: Boolean): Long {
    val id = callstack.hashCode().toLong()
    return if (signalOnly) id xor SIGNAL_ONLY_STREAM_ID_BIT else id
}

private const val SIGNAL_ONLY_STREAM_ID_BIT = 1L shl 40
