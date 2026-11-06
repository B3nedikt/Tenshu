package org.tenshu.core.common.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlin.coroutines.CoroutineContext

/**
 * Runs a **blocking** transport call on [context] and, if the caller is cancelled while it is still
 * blocked, calls [cancelRemotely] and returns at once (`core-background-processing.md` §6.2.1).
 *
 * ⚠⚠ Why a helper exists at all: a Binder transaction is not a suspension point. Wrapped in
 * `withContext(IO) { proxy.call(…) }`, the caller's cancellation does nothing until the transaction
 * returns — `withContext` waits for its block — so nothing on the client ever *notices* the
 * cancellation in time to tell the proxy, and the proxy walks the read to the end. This awaits the
 * blocking call as a `Deferred` instead, so the cancellation is observed the moment it happens.
 *
 * ⚠ The blocked thread is left to finish on its own, outside the caller's scope: it cannot be
 * interrupted, its reply is no longer wanted, and the remote cancel is what makes it return soon.
 * Its outcome — reply or failure — is discarded. A call that completes normally returns its reply
 * or rethrows its failure exactly as a direct call would.
 *
 * [cancelRemotely] runs on the cancelled caller's thread and must not block (a Binder `oneway`
 * transaction, a socket write); a failure in it is swallowed — the caller is already gone.
 */
internal suspend fun <T> awaitBlockingCallOrCancelRemotely(
    context: CoroutineContext,
    call: () -> T,
    cancelRemotely: () -> Unit,
): T {
    // Not a child of the caller: a child would make the caller's own completion wait for the
    // uninterruptible thread, which is the very wait this exists to remove.
    val pending = CoroutineScope(context).async { runCatching(call) }
    val outcome = try {
        pending.await()
    } catch (e: CancellationException) {
        runCatching(cancelRemotely)
        throw e
    }
    return outcome.getOrThrow()
}
