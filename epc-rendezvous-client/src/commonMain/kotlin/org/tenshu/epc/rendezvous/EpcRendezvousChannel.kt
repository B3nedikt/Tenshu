package org.tenshu.epc.rendezvous

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.tenshu.core.common.epc.EpcFrameType
import org.tenshu.core.common.epc.EpcHandshake
import org.tenshu.core.common.epc.EpcHandshakeMessage
import org.tenshu.core.common.epc.EpcRole
import org.tenshu.core.common.epc.EpcSession
import org.tenshu.core.common.transport.TaskTransport
import org.tenshu.core.security.SyncCryptoManager

/**
 * The client side of a **full-duplex** EPC channel over the relay rendezvous mailbox — the
 * "onCallback over rendezvous" slice (proposal §10; closes the 3b lock-step gap). It replaces the
 * earlier request/response-only rendezvous transport: it carries proxy-pushed callbacks
 * (`onCallback`) alongside replies, so observers/streams can push to a paired app.
 *
 * Where the lock-step transport polled the proxy→client direction once per `call`, this runs **one
 * ordered background reader** over that direction. Every proxy→client frame is `EpcSession.open`ed in
 * arrival order (so the session's strictly-increasing sequence holds across replies *and* callbacks,
 * which share the one proxy→client key), and the first plaintext byte — an [EpcFrameType] tag — routes
 * it: [EpcFrameType.DATA] completes the head of the FIFO of waiting [call]s; [EpcFrameType.CALLBACK]
 * goes to [onCallback]. The tag lives inside the seal, so the relay still sees only opaque ciphertext.
 *
 * Pair with `EpcRendezvousAcceptor` on the proxy. `EpcConnector`/`EpcTaskTransport` remain the
 * loopback/in-process path (no relay, no tags) — this is the relay-specific channel.
 */
class EpcRendezvousChannel private constructor(
    private val client: RendezvousRelayClient,
    private val sessionId: String,
    private val session: EpcSession,
    /** The 6-digit SAS the user confirms against the proxy's screen (EPC measure 1). */
    val sas: String,
    private var proxyToClientCursor: Long,
    private val pollIntervalMs: Long,
    private val callTimeoutMs: Long,
) : TaskTransport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pending = ArrayDeque<CompletableDeferred<ByteArray>>()
    private val pendingMutex = Mutex()

    // Serialises [enqueue-pending + seal + append] per call — the client-direction mirror of the
    // acceptor's sealMutex, and it is CORRECTNESS, not politeness: the session's send counter is
    // the AEAD nonce and the proxy opens frames in strict arrival order, so two concurrent calls
    // that seal in one order and reach the relay in the other produce a counter-mismatched frame
    // the proxy must drop (observed live: Fit's import writes racing the UI's polling ops —
    // "dropping unauthenticatable frame seq=5" — starving the import into timeouts). The same
    // race could also cross-match REPLIES: the FIFO in [pending] must enqueue in exactly the
    // order frames reach the proxy, or call A completes with call B's reply.
    private val sendMutex = Mutex()

    override val isConnected: Boolean = true
    override fun bind() {}
    override fun unbind() {
        scope.cancel()
    }

    override fun sendFile(uri: String) {}
    override var onCallback: ((ByteArray) -> Unit)? = null

    override suspend fun call(request: ByteArray): ByteArray {
        val deferred = CompletableDeferred<ByteArray>()
        // Atomic [enqueue + seal + append] under sendMutex — see its doc; holding it across the
        // network append is deliberate (send order must equal seal order on the wire).
        sendMutex.withLock {
            pendingMutex.withLock { pending.addLast(deferred) }
            try {
                // Client→proxy frames are requests only (no tag); the proxy opens them directly.
                client.append(sessionId, RendezvousDirection.CLIENT_TO_PROXY, session.seal(request))
            } catch (t: Throwable) {
                // A failed append must not leave the deferred in the FIFO — every later reply
                // would complete the wrong call (off-by-one), silently cross-matching results.
                pendingMutex.withLock { pending.remove(deferred) }
                throw t
            }
        }
        return try {
            withTimeout(callTimeoutMs) { deferred.await() }
        } catch (t: Throwable) {
            pendingMutex.withLock { pending.remove(deferred) }
            throw t
        }
    }

    private fun startReader() {
        scope.launch {
            while (isActive) {
                try {
                    when (val fetch = client.fetch(sessionId, RendezvousDirection.PROXY_TO_CLIENT, proxyToClientCursor)) {
                        is RendezvousFetch.Frames -> {
                            for (frame in fetch.frames) {
                                proxyToClientCursor = frame.seq
                                // Open in arrival order — replies and callbacks share the proxy→client
                                // key/counter, so the single reader keeps the sequence monotonic.
                                val plaintext = try {
                                    session.open(frame.payload)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    // Expected hostile/degraded input, not a programming error: a
                                    // relay-redelivered frame ALWAYS fails authentication (the
                                    // counter-based nonce makes every replay unauthenticatable —
                                    // that IS the replay protection), and a corrupt/foreign frame
                                    // is untrusted relay bytes that can fail anywhere in open() —
                                    // the framing parse throws IllegalArgumentException before the
                                    // AEAD is even consulted. None of it may kill the process: on
                                    // Kotlin/Native an uncaught coroutine exception aborts the app
                                    // (observed live as a signal-6 crash of Fit's auto-connected
                                    // channel). Drop the frame; the sequence counter is untouched
                                    // by a failed open, so later legitimate frames still open.
                                    println("[epc-channel] dropping unauthenticatable frame seq=${frame.seq} (replay/corrupt)")
                                    continue
                                }
                                val body = plaintext.copyOfRange(1, plaintext.size)
                                when (plaintext[0]) {
                                    EpcFrameType.DATA ->
                                        pendingMutex.withLock { pending.removeFirstOrNull() }?.complete(body)
                                    EpcFrameType.CALLBACK ->
                                        // A throwing push-dispatch handler is the consumer's bug,
                                        // but it must not tear down the transport under every
                                        // other in-flight call (same fatal-on-K/N escape).
                                        runCatching { onCallback?.invoke(body) }
                                            .onFailure { println("[epc-channel] onCallback handler threw: $it") }
                                }
                            }
                        }
                        RendezvousFetch.NoSession -> {
                            failAllPending(RendezvousRelayException("EPC session $sessionId is gone"))
                            return@launch
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Transient relay/network failure: in-flight calls ride their own
                    // callTimeoutMs; the reader just tries again on the next poll.
                    println("[epc-channel] fetch failed (${e.message}) — retrying next poll")
                }
                delay(pollIntervalMs)
            }
        }
    }

    private suspend fun failAllPending(cause: Throwable) {
        pendingMutex.withLock {
            while (pending.isNotEmpty()) pending.removeFirst().completeExceptionally(cause)
        }
    }

    companion object {
        /**
         * Run the EPC handshake over the relay rendezvous mailbox and return the established,
         * reading channel. Throws `RendezvousRelayException` if the relay is unreachable / the
         * session is gone. The caller surfaces [sas] for the user to confirm against the proxy.
         */
        suspend fun connect(
            crypto: SyncCryptoManager,
            client: RendezvousRelayClient,
            sessionId: String,
            pollIntervalMs: Long = 50,
            handshakeTimeoutMs: Long = 15_000,
            callTimeoutMs: Long = 10_000,
        ): EpcRendezvousChannel {
            val pending = EpcHandshake.begin(crypto, EpcRole.CLIENT)
            client.append(
                sessionId,
                RendezvousDirection.CLIENT_TO_PROXY,
                EpcHandshakeMessage.encode(pending.localPublicKey),
            )
            var cursor = 0L
            val serverHello = withTimeout(handshakeTimeoutMs) {
                pollForFrame(client, sessionId) { newCursor -> cursor = newCursor }
            }
            val established = EpcHandshake.complete(
                crypto,
                pending,
                EpcHandshakeMessage.decodePublicKey(serverHello),
            )
            return EpcRendezvousChannel(
                client = client,
                sessionId = sessionId,
                session = established.session,
                sas = established.sas,
                proxyToClientCursor = cursor,
                pollIntervalMs = pollIntervalMs,
                callTimeoutMs = callTimeoutMs,
            ).also { it.startReader() }
        }

        /** Poll the proxy→client direction until the next frame (the `ServerHello`) arrives. */
        private suspend fun pollForFrame(
            client: RendezvousRelayClient,
            sessionId: String,
            onCursor: (Long) -> Unit,
        ): ByteArray {
            var cursor = 0L
            while (true) {
                when (val fetch = client.fetch(sessionId, RendezvousDirection.PROXY_TO_CLIENT, cursor)) {
                    is RendezvousFetch.Frames -> {
                        val frame = fetch.frames.firstOrNull()
                        if (frame != null) {
                            onCursor(frame.seq)
                            return frame.payload
                        }
                    }
                    RendezvousFetch.NoSession ->
                        throw RendezvousRelayException("EPC session $sessionId is gone")
                }
                delay(50)
            }
        }
    }
}
