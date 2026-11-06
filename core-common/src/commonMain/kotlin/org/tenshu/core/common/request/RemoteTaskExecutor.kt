package org.tenshu.core.common.request

/**
 * A specialized [AsyncTaskExecutor] for remote communication that tracks connection state.
 */
interface RemoteTaskExecutor : AsyncTaskExecutor {
    val isConnected: Boolean
    fun bind()
    fun unbind()

    /**
     * Register [callback], invoked with the new value whenever [isConnected] changes, and once
     * immediately with the current value (`core-client-resubscription.md` R4).
     *
     * This is the seam a *consumer* observes — see `TenshuClient.proxyConnected`. Default no-op so
     * executors that cannot lose a connection need not implement it.
     */
    fun setOnConnectionChanged(callback: ((Boolean) -> Unit)?) {}

    /**
     * Observe how every round trip **ended**, classified by the executor — see [CallOutcome].
     *
     * ⚠⚠ The companion to [setOnConnectionChanged], and it answers a question that one cannot:
     * whether the proxy *answered*. A channel can be up while the two sides disagree about the
     * schema, and only the layer that owns the parse can tell "no answer" from "an answer I cannot
     * read". Reported to an app as the same thing, the second becomes "Control is unreachable" —
     * an outage the user cannot fix, in place of the update they can.
     *
     * Default no-op so an executor that cannot classify need not pretend to.
     */
    fun setOnCallOutcome(callback: ((CallOutcome) -> Unit)?) {}

    /**
     * See `TaskTransport.controlInstalled` — forwarded so `TenshuClient` can expose it without
     * knowing what transport is underneath. Default `null` (*cannot tell*).
     */
    suspend fun controlInstalled(): Boolean? = null
}
