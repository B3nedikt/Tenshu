package org.tenshu.core

/**
 * A client-held handle to an in-flight Layer-2 task — the runtime behind
 * [org.tenshu.core.trait.BackgroundOperation]. See
 * `docs/proposals/core-background-processing.md` §8 "Phase L2".
 *
 * The [taskId] is minted on the **client** and carried in the request envelope
 * so the proxy can register `taskId -> requestJob` in its connection-scoped
 * in-flight registry (the id provenance does not matter for correctness — the
 * only requirement is a stable correlation key both sides agree on within one
 * connection; client-assignment avoids a proxy-assigned "task started" ack and
 * the binder double-return problem).
 *
 * [cancel] does two things:
 *  - cancels the client-side wait (cooperative; this alone is the *whole* cancel
 *    for an in-process runtime where there is no wire, e.g. the JS SDK), and
 *  - sends a separate `cancel(taskId)` RPC over the same transport. The proxy
 *    looks the id up and cancels the request coroutine; structured concurrency
 *    then unwinds every suspending IO point (see `ProxyGateway.call`). An
 *    unknown or already-completed id is a no-op on the proxy.
 *
 * [await] suspends for the eventual result. Cancelling after completion is a
 * no-op; awaiting after cancellation throws [kotlinx.coroutines.CancellationException].
 */
interface TaskHandle<out T : Data?> {
    val taskId: String

    suspend fun await(): TResult<T>

    fun cancel()
}
