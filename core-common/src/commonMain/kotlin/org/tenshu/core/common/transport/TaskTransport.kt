package org.tenshu.core.common.transport

/**
 * Interface for the communication layer between client and proxy.
 * This allows mocking the binary communication for integration tests on the host machine.
 */
interface TaskTransport {
    val isConnected: Boolean
    fun bind()
    fun unbind()

    /**
     * Suspend until the transport is connected, returning the connection state
     * once it resolves or [timeoutMillis] elapses.
     *
     * Default: transports whose [bind] connects **synchronously** (e.g. the
     * desktop UDS transport, where `bind()` blocks on `connect()`) are already
     * connected by the time this is called, so the default just reports
     * [isConnected]. Android's Binder bind is **asynchronous** (`bindService` →
     * a later `onServiceConnected`), so [org.tenshu.core.common.transport.BinderTaskTransport]
     * overrides this to actually await the connection. Without that wait, the
     * first IPC call after process start races the bind and the transport throws
     * "Proxy not connected" — which is why early, auto-firing IPC consumers
     * (background backup status reporting, registration) crashed at launch.
     */
    suspend fun awaitConnected(timeoutMillis: Long = 5_000): Boolean = isConnected

    suspend fun call(request: ByteArray): ByteArray

    /**
     * Like [call] but tags the request with the client-minted [taskId] in the
     * wire envelope so the proxy can register it in its in-flight registry
     * (Phase L2, `docs/proposals/core-background-processing.md` §8). Default:
     * fall back to [call] and ignore the id — a transport that does not speak
     * the cancel frame still runs the task to completion, it just cannot be
     * cross-process cancelled (the pre-L2 behaviour; no regression).
     */
    suspend fun callCancellable(request: ByteArray, taskId: String): ByteArray =
        call(request)

    /**
     * [callCancellable] for a task the **user started** (`userInitiatedWork { }`,
     * `docs/proposals/control-background-work-off-fgs.md` §5.1): the proxy may hold Android's
     * foreground service for it, and for nothing else.
     *
     * Default: [callCancellable]. Only a transport to a proxy that runs a foreground service needs
     * the distinction (Binder); everywhere else the proxy is a daemon or in-process and the mark has
     * nothing to change.
     */
    suspend fun callCancellableUserInitiated(request: ByteArray, taskId: String): ByteArray =
        callCancellable(request, taskId)

    /**
     * A plain call the caller may abandon: if the calling coroutine is cancelled while this is in
     * flight, the transport tells the proxy to **stop evaluating it** (`cancellableReads { }`,
     * `docs/proposals/core-background-processing.md` §6.2.1). The transport mints and owns the
     * read's id; nothing above it sees one.
     *
     * ⚠⚠ Not a task: the proxy serves it exactly like [call] — session-parented, never counted
     * towards foreground-service liveness, no sync lane — and only adds a way to stop it.
     *
     * Default: [call]. A transport that cannot stop a read (or reaches a proxy too old to — each
     * implementation negotiates that per connection) still answers, it simply lets the proxy
     * finish; the caller's own cancellation behaves exactly as with [call].
     */
    suspend fun callCancellableRead(request: ByteArray): ByteArray = call(request)

    /**
     * Out-of-band: tell the proxy to cancel the in-flight task [taskId]. Must
     * not block on / be serialised behind an in-flight [callCancellable].
     * Default no-op — matches the proxy's "unknown id is a no-op" contract for
     * transports that do not implement the cancel frame.
     */
    fun cancel(taskId: String) {}

    /**
     * Whether **Control is installed on this device** — `true`/`false`, or `null` for *cannot
     * tell* (`ADR 0025`). Answered by the transport because the transport is what knows how it
     * reaches Control, and on Android it already holds the [android.content.Context] the question
     * needs; every consumer above it would otherwise have to acquire one.
     *
     * ⚠⚠ **The default is `null`, not `false`.** A transport that cannot answer must not be read
     * as "Control is missing" — that would put an install prompt in front of users whose Control
     * is installed and merely asleep. Same shape and same reason as [setOnReconnected] and
     * [setOnConnectionChanged] below: a plain `fun` with a benign default keeps every existing
     * implementation and test double compiling, and only transports that can genuinely observe
     * this override it.
     *
     * ⚠ Local and synchronous by contract — it is consulted *while the channel is down*, so an
     * implementation that needs the proxy to answer has no answer to give and must return `null`.
     */
    suspend fun controlInstalled(): Boolean? = null

    fun sendFile(uri: String)
    var onCallback: ((ByteArray) -> Unit)?

    /**
     * Register [callback], invoked whenever the transport re-establishes a connection to a proxy
     * that **restarted** — i.e. after a disconnect, never on the first connect
     * (`docs/proposals/core-client-resubscription.md` R1).
     *
     * The restarted proxy's `StreamRegistry` is empty (it is in-memory and died with the process),
     * so every stream this client holds is now subscribed to nothing. The transport recovers
     * silently, which is precisely why this signal is needed: without it the client keeps a live
     * channel with zero live subscriptions and renders pre-restart values forever, recovering only
     * when the *client* itself is restarted.
     *
     * A plain `fun` with a no-op default rather than a `var` property so the many existing
     * implementations — including every test double — keep compiling untouched; only transports
     * that can actually observe a reconnect override it.
     */
    fun setOnReconnected(callback: (() -> Unit)?) {}

    /**
     * Register [callback], invoked with the new value whenever [isConnected] *changes*
     * (`docs/proposals/core-client-resubscription.md` R4).
     *
     * [setOnReconnected] reports only the recovery edge, which is what the machinery needs; this
     * reports both edges, which is what a *consumer* needs. Without it a client cannot tell "the
     * proxy has nothing to show" from "the proxy cannot be reached", because every signal it would
     * judge that by is itself read over the channel that is down — so an outage renders as an
     * ordinary empty screen.
     *
     * Same shape as [setOnReconnected] and for the same reason: a `fun` with a no-op default, so
     * every existing implementation and test double keeps compiling and only transports that can
     * observe a disconnect override it. A transport that never reports is read as permanently
     * connected, which is correct for the in-process case where client and proxy share a fate.
     */
    fun setOnConnectionChanged(callback: ((Boolean) -> Unit)?) {}
}
