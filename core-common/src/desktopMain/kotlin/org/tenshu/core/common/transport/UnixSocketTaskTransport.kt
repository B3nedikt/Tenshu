package org.tenshu.core.common.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tenshu.core.common.parser.BinaryParser
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

class UnixSocketTaskTransport(
    private val parser: BinaryParser,
    private val socketPath: String = SocketPathResolver.resolve(),
    /**
     * How long a connection's first `call()` waits for the proxy to answer the wire hello before
     * dropping the connection (`desktop-ipc.md` §2.2). Overridable so the mute-proxy case is
     * testable without a ten-second test.
     */
    private val helloTimeoutMs: Long = HELLO_TIMEOUT_MS,
    /**
     * Who may be on the other end (threat model A14, client direction). Checked on every new
     * connection, after `connect()` and **before a single byte is written**: a server that fails is
     * closed and the connect counts as failed, so the reconnect loop keeps trying and an impostor
     * never sees a request. `null` checks nothing (tests, non-first-party callers); the apps pass
     * `app.tenshu.ui.ipc.ControlServerCheck`.
     */
    private val serverVerifier: PeerCredentialVerifier? = null,
    /** A16 developer mode on the client side: an unverified server is admitted with a warning. */
    private val serverDeveloperMode: Boolean = false,
) : TaskTransport {
    @Volatile
    private var channel: SocketChannel? = null
    private val responseQueue = LinkedBlockingQueue<ByteArray>()

    // Cancellable calls are correlated by client-minted task id rather than
    // the global callLock + single responseQueue (which would let a
    // long-running call block every other call *and the cancel itself*). Frame
    // writes from all paths (normal call, cancellable call, out-of-band cancel)
    // go through `writeLock` so partial frames can't interleave on the socket.
    private val pendingById = ConcurrentHashMap<String, CompletableDeferred<ByteArray>>()
    private val writeLock = ReentrantLock()

    // ── correlated plain calls (desktop-ipc.md §2.2) ─────────────────────────────────────
    //
    // A plain `call()` on a connection whose proxy answered the hello carries a client-minted call
    // id and waits on its own deferred, exactly like `callCancellable` — so calls run concurrently,
    // a caller may abandon its wait, and a reply for an abandoned id is dropped rather than handed
    // to the next caller. ⚠ The call id is a transport tag, never a task id: the proxy serves it as
    // a plain request (no in-flight registration, no detach, no sync lane).

    /** A waiting correlated call, bound to the socket it was written on so a death can fail it. */
    private class PendingCall(val channel: SocketChannel, val reply: CompletableDeferred<ByteArray>)

    private val pendingCalls = ConcurrentHashMap<String, PendingCall>()
    private val nextCallId = AtomicLong()

    /**
     * What [channel]'s proxy answered to the hello. Per channel: a reconnect may reach another build.
     * [revision] is the proxy's wire revision (`StreamFramer.WIRE_REVISION_*`), at least
     * [StreamFramer.WIRE_REVISION_FLOOR] — anything lower is refused in [negotiate]. A feature a
     * later revision adds is gated on it.
     */
    private class Negotiated(val channel: SocketChannel, val revision: Int)

    /**
     * The revision the proxy's last `TYPE_HELLO` announced — written by the reader just before it
     * queues [HELLO_ACK], read by [negotiate] just after it takes it (the queue orders the two).
     */
    @Volatile
    private var lastHelloRevision: Int = StreamFramer.WIRE_REVISION_CORRELATED

    @Volatile
    private var negotiated: Negotiated? = null

    // ── connection lifecycle (core-client-resubscription.md R2) ─────────────────────────
    //
    // Guards every connect/disconnect transition. It also closes a race: `call()` and
    // `callCancellable()` both bind on demand, so two concurrent calls after a drop could
    // otherwise each open a channel and spawn a reader thread.
    private val connectLock = ReentrantLock()

    /** Set by [unbind] so a deliberate teardown is never fought by the reconnect loop. */
    @Volatile
    private var unbound = false

    /** True once a connection has succeeded, so the *next* one is a re-connection. */
    @Volatile
    private var hasConnectedBefore = false

    /** A connect attempt failed since the last success — guarded by [connectLock]. See [openConnection]. */
    private var connectFailed = false

    @Volatile
    private var reconnectThread: Thread? = null

    /** The reconnect loop retries every few seconds; the directory's mode is not news each time. */
    private val lastServerGateLog = java.util.concurrent.atomic.AtomicReference<String?>(null)

    private fun logServerGateOnce(message: String) {
        if (lastServerGateLog.getAndSet(message) != message) System.err.println(message)
    }

    private val warnedAboutSocketDir = java.util.concurrent.atomic.AtomicBoolean(false)

    private var onReconnected: (() -> Unit)? = null

    override fun setOnReconnected(callback: (() -> Unit)?) {
        onReconnected = callback
    }

    /** Writes [framed] on the current channel and returns the channel it went out on. */
    private fun writeFrame(framed: ByteArray): SocketChannel {
        val ch = channel
            ?: error("Could not connect to Tenshu Control Proxy. Please ensure the Control app is running.")
        writeLock.lock()
        try {
            // ⚠ Drained in a loop under the lock: a frame must never be split by another writer's
            // A blocking channel writes everything in one call by contract; the loop
            // keeps the frame whole if a channel is ever made non-blocking.
            ch.writeFully(ByteBuffer.wrap(framed))
        } finally {
            writeLock.unlock()
        }
        return ch
    }

    override val isConnected: Boolean get() = channel?.isConnected == true

    /**
     * See [TaskTransport.controlInstalled] — on desktop this is the *only* way to tell an absent
     * Control from a stopped one. Unlike Android's bind, connecting to a Unix socket that is not
     * there fails identically in both cases: `bind()` cannot distinguish "never installed" from
     * "installed, not running", and the second is by far the more common desktop state.
     *
     * ⚠ On [kotlinx.coroutines.Dispatchers.IO] because [desktopControlInstalled] may spawn
     * `mdfind`. The engine calls this from a probe scope that is `Main.immediate` on some
     * platforms, and a ~100 ms process spawn there would be visible jank.
     */
    override suspend fun controlInstalled(): Boolean? =
        withContext(Dispatchers.IO) { desktopControlInstalled() }

    /** See [TaskTransport.setOnConnectionChanged]. */
    @Volatile
    private var onConnectionChanged: ((Boolean) -> Unit)? = null

    override fun setOnConnectionChanged(callback: ((Boolean) -> Unit)?) {
        onConnectionChanged = callback
        // Report the current state immediately: a consumer registering after the connect landed
        // would otherwise wait for the next *change*, i.e. for something to break.
        callback?.invoke(isConnected)
    }

    override fun bind() {
        val reconnected = connectLock.withLockReturning {
            unbound = false
            if (isConnected) return@withLockReturning false
            openConnection()
        }
        // Fired outside `connectLock`: the callback re-subscribes, which issues calls that bind
        // again. Holding the lock across it would deadlock the moment that work lands on another
        // thread (which it does — the executor hops it onto a coroutine scope).
        if (reconnected) onReconnected?.invoke()
        // ⚠⚠ A proxy that is not there YET is retried in the background too — not only one that
        // died. Started from a reader's death alone, the loop would never run for a client launched
        // before Control: it has no reader to die, and would connect only when some call binds.
        if (!isConnected && !unbound) scheduleReconnect()
    }

    /**
     * Opens the socket and starts a reader for it. **Caller must hold [connectLock].** Returns true
     * when this was a *re*-connection — i.e. the proxy restarted under us, so every stream the
     * client holds is now registered with a `StreamRegistry` that no longer exists
     * (`core-client-resubscription.md`).
     */
    private fun openConnection(): Boolean {
        return try {
            val socketDir = Path.of(socketPath).parent
            // This transport connects to a *path*. The directory's mode is the first evidence that
            // the socket was placed there by the user's own daemon and not by whoever bound it
            // first; [serverVerifier], below, is the proof. A directory another user can
            // write to is a refusal — connecting would hand an unknown process every request
            // this client makes. Merely reachable is a warning: the exposure is real but the
            // peer gate still stands in front of the proxy, and refusing would strand a user
            // whose directory is `0755` from an older build (the daemon repairs it on start,
            // and the reconnect loop retries, so this is self-healing).
            when (val verdict = SocketDirectory.inspect(socketDir)) {
                is SocketDirectory.Verdict.Squattable -> {
                    System.err.println("[ipc] refusing to connect: ${verdict.reason}")
                    channel = null
                    connectFailed = true
                    return false
                }

                is SocketDirectory.Verdict.Reachable ->
                    if (warnedAboutSocketDir.compareAndSet(false, true)) {
                        System.err.println("[ipc] ${verdict.reason}")
                    }

                SocketDirectory.Verdict.Sound -> Unit
            }
            val address = UnixDomainSocketAddress.of(socketPath)
            val ch = SocketChannel.open(StandardProtocolFamily.UNIX).apply { connect(address) }
            // ⚠⚠ Authenticate the server before anything is written. The directory check above only
            // shows who could have bound the path; this shows who did. `admit` closes [ch] on a
            // refusal. One log line per distinct reason: the reconnect loop retries every few seconds.
            if (serverVerifier != null &&
                serverVerifier.admit(ch, SERVER_POLICY, serverDeveloperMode, log = ::logServerGateOnce) == null
            ) {
                channel = null
                connectFailed = true
                return false
            }
            // A new socket starts a new request/response sequence, so anything still queued
            // belongs to the dead one. Carrying it over would hand the first caller on the new
            // connection an answer from the old — the same skew [call] guards against.
            responseQueue.clear()
            channel = ch
            startReader(ch)
            onConnectionChanged?.invoke(true)
            // ⚠⚠ A first connect that follows a FAILED attempt is announced like a reconnect.
            // Whoever met the failure is waiting on this edge — `Stream.changes` parks its retry in
            // `awaitConnected` — and a client launched before Control has never been connected, so
            // "only a RE-connection announces" would leave that retry parked for the life of the
            // process: the listing would load on a manual retry and then never hear a change.
            val isReconnect = hasConnectedBefore || connectFailed
            hasConnectedBefore = true
            connectFailed = false
            isReconnect
        } catch (e: Exception) {
            channel = null
            connectFailed = true
            false
        }
    }

    /**
     * Reads frames off [ch] until it dies. Bound to *its* channel rather than the shared field,
     * which a reconnect may have already swapped underneath it.
     */
    private fun startReader(ch: SocketChannel) {
        thread(isDaemon = true, name = "UnixSocketReader") {
            val headerBuffer = ByteBuffer.allocate(4)
            val typeBuffer = ByteBuffer.allocate(1)
            try {
                while (ch.isConnected) {
                    headerBuffer.clear()
                    if (readFully(ch, headerBuffer) == -1) break
                    headerBuffer.flip()
                    val length = headerBuffer.getInt()
                    if (!StreamFramer.isValidFrameLength(length)) {
                        // Symmetric with the server's check. Without a [serverVerifier] (tests, or a
                        // developer-mode admit) a process that bound the socket path first may be on
                        // the other end of this loop.
                        break
                    }

                    typeBuffer.clear()
                    if (readFully(ch, typeBuffer) == -1) break
                    typeBuffer.flip()
                    val type = typeBuffer.get()

                    val payloadBuffer = ByteBuffer.allocate(length)
                    if (readFully(ch, payloadBuffer) == -1) break
                    payloadBuffer.flip()
                    val payload = payloadBuffer.array().sliceArray(0 until length)

                    handleIncomingPayload(payload, type)
                }
            } catch (e: Exception) {
                // Reader loop terminated
            } finally {
                val wasCurrent = connectLock.withLockReturning {
                    runCatching { ch.close() }
                    val current = channel === ch
                    if (current) channel = null
                    current
                }
                failPendingCallsOn(ch)
                if (wasCurrent) onConnectionChanged?.invoke(false)
                // The proxy died (or the socket broke). Reconnect in the background
                // (`core-client-resubscription.md` R2): a client that only *observes* — every
                // reactive screen — would otherwise stay dark until some unrelated call re-binds.
                if (!unbound) scheduleReconnect()
            }
        }
    }

    /**
     * Retries the connection on a single background thread until it succeeds or [unbind] is called.
     * Backs off so a Control that is genuinely down (or never started) costs a poll every
     * [MAX_RECONNECT_DELAY_MS] rather than a hot loop.
     */
    private fun scheduleReconnect() {
        connectLock.withLockReturning {
            if (unbound || isConnected || reconnectThread?.isAlive == true) return@withLockReturning
            reconnectThread = thread(isDaemon = true, name = "UnixSocketReconnect") {
                var delayMs = INITIAL_RECONNECT_DELAY_MS
                while (!unbound && !isConnected) {
                    try {
                        Thread.sleep(delayMs)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@thread
                    }
                    val reconnected = connectLock.withLockReturning {
                        if (unbound || isConnected) false else openConnection()
                    }
                    if (reconnected) {
                        onReconnected?.invoke()
                        return@thread
                    }
                    delayMs = (delayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
                }
            }
        }
    }

    private fun handleIncomingPayload(payload: ByteArray, type: Byte) {
        when (type) {
            StreamFramer.TYPE_CALLBACK -> onCallback?.invoke(payload)
            StreamFramer.TYPE_CANCELLABLE_RESPONSE -> {
                val (taskId, body) = StreamFramer.unpackTagged(payload)
                pendingById.remove(taskId)?.complete(body)
            }

            // Only ever the answer to our own hello, which is only outstanding under `callLock` —
            // so the negotiator is the one reading the queue.
            StreamFramer.TYPE_HELLO -> {
                lastHelloRevision = StreamFramer.helloRevision(payload)
                responseQueue.put(HELLO_ACK)
            }

            // An id nobody waits for any more belongs to a caller that gave up: drop it. That drop
            // is the whole of why abandoning a correlated call cannot skew another's reply.
            StreamFramer.TYPE_CORRELATED_RESPONSE -> {
                val (callId, body) = StreamFramer.unpackTagged(payload)
                pendingCalls.remove(callId)?.reply?.complete(body)
            }

            StreamFramer.TYPE_CORRELATED_FAILURE -> {
                val (callId, body) = StreamFramer.unpackTagged(payload)
                pendingCalls.remove(callId)?.reply?.completeExceptionally(
                    IllegalStateException("Proxy failed the call: ${body.decodeToString()}")
                )
            }

            // TYPE_RESPONSE (or a type this build does not know): no current proxy sends one, so
            // only a peer that does not speak the hello can — [negotiate] refuses it.
            else -> responseQueue.put(payload)
        }
    }

    private fun readFully(ch: SocketChannel, buffer: ByteBuffer): Int {
        while (buffer.hasRemaining()) {
            val read = try {
                ch.read(buffer)
            } catch (e: Exception) {
                -1
            }
            if (read == -1) return -1
            // Prevent busy spin if for some reason the socket returns 0 but is still open
            if (read == 0) Thread.sleep(1)
        }
        return buffer.position()
    }

    override fun unbind() {
        connectLock.withLockReturning {
            // Deliberate teardown — must not be undone by the reconnect loop.
            unbound = true
            runCatching { channel?.close() }
            channel = null
        }
        onConnectionChanged?.invoke(false)
    }

    private val callLock = Mutex()

    override suspend fun call(request: ByteArray): ByteArray {
        if (!isConnected) {
            bind()
        }
        val ch = channel
            ?: error("Could not connect to Tenshu Control Proxy. Please ensure the Control app is running.")
        negotiationFor(ch)
        return correlatedCall(ch, request)
    }

    /**
     * What [ch]'s proxy answered to the hello, negotiated on first use (`desktop-ipc.md` §2.2).
     *
     * ⚠ Under [callLock] so one hello is outstanding at a time, and in `NonCancellable` so its
     * answer is always consumed here rather than left in [responseQueue] for the next negotiation.
     */
    private suspend fun negotiationFor(ch: SocketChannel): Negotiated {
        negotiated?.takeIf { it.channel === ch }?.let { return it }
        return callLock.withLock {
            negotiated?.takeIf { it.channel === ch }?.let { return@withLock it }
            withContext(NonCancellable + Dispatchers.IO) { negotiate(ch) }
        }
    }

    /**
     * Sends the hello on [ch] and reads what comes back. **Caller holds [callLock].**
     *
     * - `TYPE_HELLO` at [StreamFramer.WIRE_REVISION_FLOOR] or above → a proxy this build talks to.
     * - anything else — a hello below the floor, or a plain `TYPE_RESPONSE` from a peer that does
     *   not speak the hello at all — or nothing within [helloTimeoutMs] → the connection is
     *   **closed** and the call fails. Fail closed: no shipped Control is below the floor (the
     *   pre-hello and revision-1 wires never reached a user), so such a peer is not
     *   ours to guess about. Closing makes any late answer undeliverable, and the reconnect loop
     *   negotiates afresh.
     */
    private fun negotiate(ch: SocketChannel): Negotiated {
        writeOn(ch, StreamFramer.frame(StreamFramer.HELLO_BODY, StreamFramer.TYPE_HELLO))
        val deadline = System.nanoTime() + helloTimeoutMs * 1_000_000
        while (true) {
            val answer = responseQueue.poll(RESPONSE_POLL_MS, TimeUnit.MILLISECONDS)
            if (answer != null) {
                val revision = lastHelloRevision
                if (answer !== HELLO_ACK || revision < StreamFramer.WIRE_REVISION_FLOOR) {
                    runCatching { ch.close() } // the reader sees EOF, drops the channel, reconnects
                    throw IllegalStateException(
                        if (answer !== HELLO_ACK) {
                            "Proxy does not speak the IPC hello; connection dropped"
                        } else {
                            "Proxy wire revision $revision is below ${StreamFramer.WIRE_REVISION_FLOOR}; connection dropped"
                        }
                    )
                }
                return Negotiated(ch, revision).also { negotiated = it }
            }
            if (channel !== ch) throw IllegalStateException("Proxy died mid-call")
            if (System.nanoTime() > deadline) {
                runCatching { ch.close() } // the reader sees EOF, drops the channel, reconnects
                throw IllegalStateException(
                    "Proxy did not answer the IPC hello within $helloTimeoutMs ms; connection dropped"
                )
            }
        }
    }

    /**
     * A plain call on a negotiated connection: correlated by call id, concurrent, and
     * **cancellable** — a caller's own timeout frees it, and nobody else waits behind it.
     *
     * ⚠ Registered *before* the write so a reply can never beat its waiter, and removed in
     * `finally` so an abandoned wait leaves nothing for its late reply to reach. A proxy that dies
     * while this waits fails it through [failPendingCallsOn].
     */
    private suspend fun correlatedCall(
        ch: SocketChannel,
        request: ByteArray,
        stopOnAbandon: Boolean = false,
    ): ByteArray {
        val callId = nextCallId.incrementAndGet().toString()
        val reply = CompletableDeferred<ByteArray>()
        pendingCalls[callId] = PendingCall(ch, reply)
        try {
            // The reader may already have swept this channel's calls before we registered.
            if (channel !== ch) throw IllegalStateException("Proxy died mid-call")
            val framed = StreamFramer.frame(
                StreamFramer.packTagged(callId, request),
                StreamFramer.TYPE_CORRELATED_REQUEST,
            )
            withContext(Dispatchers.IO) { writeOn(ch, framed) }
            return reply.await()
        } catch (e: CancellationException) {
            // §2.3: the caller gave up on a read — tell the proxy to stop walking it. Only for a
            // cancellable read (a plain call is never stopped by its caller), only if the reply is
            // still owed, and only on the socket the request went out on. Best-effort: a cancel
            // the proxy never sees costs only the walk it would have stopped.
            // ⚠ Off the caller's thread: it may be the UI's, and the write waits on [writeLock]
            // behind whatever frame is going out — a desktop frame can carry a whole file inline.
            if (stopOnAbandon && !reply.isCompleted && channel === ch) {
                val cancelFrame = StreamFramer.frame(callId.encodeToByteArray(), StreamFramer.TYPE_CANCEL_READ)
                thread(isDaemon = true, name = "UnixSocketCancelRead") {
                    runCatching { writeOn(ch, cancelFrame) }
                }
            }
            throw e
        } finally {
            pendingCalls.remove(callId)
        }
    }

    /**
     * `cancellableReads { }` over the socket (`desktop-ipc.md` §2.3): a correlated call whose
     * caller's cancellation sends [StreamFramer.TYPE_CANCEL_READ]. Every negotiated proxy serves it:
     * [StreamFramer.WIRE_REVISION_CANCELLABLE_READS] is the floor [negotiate] enforces.
     */
    override suspend fun callCancellableRead(request: ByteArray): ByteArray {
        if (!isConnected) {
            bind()
        }
        val ch = channel
            ?: error("Could not connect to Tenshu Control Proxy. Please ensure the Control app is running.")
        negotiationFor(ch)
        return correlatedCall(ch, request, stopOnAbandon = true)
    }

    /** Fails every correlated call still waiting on [ch] — its answers died with the socket. */
    private fun failPendingCallsOn(ch: SocketChannel) {
        for ((id, pending) in pendingCalls) {
            if (pending.channel === ch && pendingCalls.remove(id, pending)) {
                pending.reply.completeExceptionally(IllegalStateException("Proxy died mid-call"))
            }
        }
    }

    /**
     * Writes on exactly [ch], mapping a dead socket to the `IllegalStateException` that
     * [RemoteBinaryTaskExecutor] already folds into a typed `TResult.Failure`, which the
     * reachability engines read as "Control is gone".
     */
    private fun writeOn(ch: SocketChannel, framed: ByteArray) {
        writeLock.lock()
        try {
            // ⚠ Drained in a loop under the lock: a frame must never be split by another writer's
            // A blocking channel writes everything in one call by contract; the loop
            // keeps the frame whole if a channel is ever made non-blocking.
            ch.writeFully(ByteBuffer.wrap(framed))
        } catch (e: java.io.IOException) {
            throw IllegalStateException("Proxy died mid-call", e)
        } finally {
            writeLock.unlock()
        }
    }

    override suspend fun callCancellable(request: ByteArray, taskId: String): ByteArray {
        if (!isConnected) {
            bind()
        }

        // Correlated by taskId, NOT serialised behind callLock — a long-running
        // cancellable call must not block other calls or its own cancel frame.
        val deferred = CompletableDeferred<ByteArray>()
        pendingById[taskId] = deferred
        try {
            val framed = StreamFramer.frame(
                StreamFramer.packTagged(taskId, request),
                StreamFramer.TYPE_CANCELLABLE_REQUEST
            )
            withContext(Dispatchers.IO) {
                writeFrame(framed)
            }
            return deferred.await()
        } finally {
            // Covers both completion and the caller's coroutine being
            // cancelled by TaskHandle.cancel() — no stale waiter is left.
            pendingById.remove(taskId)
        }
    }

    override fun cancel(taskId: String) {
        if (!isConnected) return
        try {
            writeFrame(
                StreamFramer.frame(taskId.encodeToByteArray(), StreamFramer.TYPE_CANCEL)
            )
        } catch (e: Exception) {
            // Best-effort: the proxy treats an unknown id as a no-op anyway,
            // and the caller has already cancelled the client-side wait.
        }
    }

    override fun sendFile(uri: String) {
        // Not implemented for UDS yet
    }

    override var onCallback: ((ByteArray) -> Unit)? = null

    private inline fun <T> ReentrantLock.withLockReturning(block: () -> T): T {
        lock()
        try {
            return block()
        } finally {
            unlock()
        }
    }

    private companion object {
        const val INITIAL_RECONNECT_DELAY_MS = 250L
        const val MAX_RECONNECT_DELAY_MS = 5_000L

        /**
         * How often the hello's wait re-checks that the socket it wrote on is still the live one.
         *
         * ⚠ A latency floor on nothing: the answer still arrives by `poll` returning it the moment
         * the reader offers it. This only bounds how long a **dead** connection's wait lasts.
         */
        const val RESPONSE_POLL_MS = 50L

        /**
         * A proxy answers the hello from its read loop, before any work, so this bounds only a
         * proxy that answers nothing at all. Generous because the price of expiring is a dropped
         * connection, not a wrong answer.
         */
        const val HELLO_TIMEOUT_MS = 10_000L

        /** Put on [responseQueue] by the reader for a `TYPE_HELLO`; compared by identity. */
        val HELLO_ACK = ByteArray(0)

        /** The server check is always strict; [serverDeveloperMode] is the only downgrade, as on Control's side. */
        val SERVER_POLICY = PeerCredentialPolicy(strict = true)
    }
}

/** Writes every remaining byte of [buffer]; a single `write` may stop short on a non-blocking channel. */
internal fun java.nio.channels.SocketChannel.writeFully(buffer: ByteBuffer) {
    while (buffer.hasRemaining()) write(buffer)
}
