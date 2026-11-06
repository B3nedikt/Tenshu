package org.tenshu.core.common.request

/**
 * A [TaskExecutor] whose link to the proxy can drop and come back — i.e. one that talks to a
 * **separate proxy process** (`docs/proposals/core-client-resubscription.md` R1).
 *
 * Exists so [org.tenshu.core.common.DefaultInteractorProvider] can self-wire its resubscribe pass
 * without every app's `PlatformClientModule` having to repeat the wiring (there are one per app per
 * platform, and a forgotten one fails *silently* — the streams simply never recover).
 *
 * Deliberately **not** implemented by in-process executors. When the proxy lives in the client's
 * own process (iOS route-1's `FirstPartyLocalRuntime`, the web `JsRuntime`, `LocalTaskExecutor` in
 * tests) there is no connection to lose: the proxy and its client share a fate, so a reconnect is
 * unreachable rather than merely unhandled.
 */
interface ReconnectingTaskExecutor {

    /**
     * Invoked after the transport reconnects to a restarted proxy. Set by the interactor provider;
     * a single slot, matching the `streamElementHandler` convention on
     * [org.tenshu.core.common.transport.RemoteBinaryTaskExecutor].
     */
    var onReconnected: (suspend () -> Unit)?
}
