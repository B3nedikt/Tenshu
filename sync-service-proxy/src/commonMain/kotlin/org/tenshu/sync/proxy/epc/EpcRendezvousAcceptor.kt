package org.tenshu.sync.proxy.epc

import kotlin.concurrent.Volatile
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tenshu.core.common.epc.EpcFrameType
import org.tenshu.core.common.epc.EpcResponder
import kotlinx.coroutines.CancellationException
import org.tenshu.core.common.epc.EpcSession
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.epc.rendezvous.RendezvousDirection
import org.tenshu.epc.rendezvous.RendezvousFetch
import org.tenshu.epc.rendezvous.RendezvousRelayClient
import org.tenshu.epc.rendezvous.RendezvousRelayException
import kotlin.coroutines.coroutineContext

/**
 * Proxy-side accept loop over the relay's EPC rendezvous mailbox — Phase 3b of
 * `docs/proposals/ios-proxy-architecture.md`, extended with **server push** (the "onCallback over
 * rendezvous" slice). The counterpart of `EpcRendezvousChannel`: it [provision]s the JWT-gated
 * session (the admission gate), then [serve]s the client→proxy direction, driving the Phase-2
 * [EpcResponder]:
 *
 *  - the first client frame is the `ClientHello` → `accept` it, post the `ServerHello` back, and keep
 *    the established [EpcSession];
 *  - each subsequent frame is a sealed operation → open it, run [serve]'s handler, and post the sealed
 *    reply, tagged [EpcFrameType.DATA].
 *
 * Beyond the lock-step reply path, [pushCallback] seals an **unsolicited** proxy→client frame, tagged
 * [EpcFrameType.CALLBACK], so observers/streams can push to a paired app. Replies and callbacks share
 * the one proxy→client [EpcSession] (key + sequence), so [sealAndAppend] serialises every seal+append
 * under [sealMutex] — the relay preserves frame order per direction, and the client reads in that
 * order, keeping the session sequence monotonic across both.
 *
 * The handler stands in for the proxy's gateway; on a real proxy it forwards the plaintext request
 * bytes into the operation pipeline (no proxy-core change — it still sees plaintext after the acceptor
 * decrypts).
 */
class EpcRendezvousAcceptor(
    private val crypto: SyncCryptoManager,
    private val client: RendezvousRelayClient,
    private val sessionId: String,
    private val pollIntervalMs: Long = 50,
) {
    /** The SAS of the established channel — set once the handshake completes (for confirmation). */
    @Volatile
    var establishedSas: String? = null
        private set

    // The proxy→client session, published once the handshake completes so [pushCallback] (which runs
    // off the serve loop) can seal against it. Null until then — callbacks before establishment drop.
    @Volatile
    private var session: EpcSession? = null

    private val sealMutex = Mutex()

    /** Provision the session (idempotent). Must complete before the app posts its `ClientHello`. */
    suspend fun provision() {
        when (client.provision(sessionId)) {
            RendezvousRelayClient.ProvisionOutcome.PROVISIONED -> Unit
            RendezvousRelayClient.ProvisionOutcome.CAP_EXCEEDED ->
                throw RendezvousRelayException("EPC session cap reached")
            RendezvousRelayClient.ProvisionOutcome.OWNER_MISMATCH ->
                throw RendezvousRelayException("EPC session id already owned by another owner")
        }
    }

    /**
     * Poll-loop the channel until cancelled. [handler] processes each decrypted client request and
     * returns the plaintext response.
     */
    suspend fun serve(handler: suspend (ByteArray) -> ByteArray) {
        val responder = EpcResponder(crypto)
        var clientToProxyCursor = 0L
        while (coroutineContext.isActive) {
            when (val fetch = client.fetch(sessionId, RendezvousDirection.CLIENT_TO_PROXY, clientToProxyCursor)) {
                is RendezvousFetch.Frames -> {
                    for (frame in fetch.frames) {
                        clientToProxyCursor = frame.seq
                        val current = session
                        if (current == null) {
                            val accepted = responder.accept(frame.payload)
                            session = accepted.session
                            establishedSas = accepted.sas
                            // The `ServerHello` is the pre-session handshake reply: plaintext, untagged.
                            client.append(sessionId, RendezvousDirection.PROXY_TO_CLIENT, accepted.serverHello)
                        } else {
                            val request = try {
                                current.open(frame.payload)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                // Replay/corrupt frame — expected untrusted-relay input: the
                                // counter-based nonce makes every redelivered frame fail auth by
                                // design, and garbage can throw anywhere in open() (the framing
                                // parse throws IllegalArgumentException before the AEAD is even
                                // consulted). Must not end hosting, and on Kotlin/Native an
                                // escaped exception in the host's coroutine can abort the whole
                                // app (the mirror of the client-side reader crash). Drop it,
                                // keep serving.
                                println("[epc-acceptor] dropping unauthenticatable frame seq=${frame.seq} (replay/corrupt)")
                                continue
                            }
                            val response = try {
                                handler(request)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                // A throwing dispatch (e.g. a reply that fails to SERIALIZE —
                                // ProxyGateway's executor catch covers parse+impl errors, but
                                // serialization of the result sits outside it) must not kill
                                // the serve loop: every op behind it would starve into
                                // timeouts (observed live as Fit's import-write timeouts).
                                // The client matches replies to calls by FIFO order, so we
                                // MUST still send one reply — an empty body fails that one
                                // call with a parse error client-side, keeping the sequence
                                // aligned and the channel alive.
                                println("[epc-acceptor] handler threw for frame seq=${frame.seq}: ${e.message ?: e::class.simpleName} — empty reply to keep sequence")
                                ByteArray(0)
                            }
                            sealAndAppend(current, EpcFrameType.DATA, response)
                        }
                    }
                }
                RendezvousFetch.NoSession ->
                    throw RendezvousRelayException("EPC session $sessionId is gone")
            }
            delay(pollIntervalMs)
        }
    }

    /**
     * Seal [plaintext] as an unsolicited proxy→client push and append it. A no-op returning `false`
     * before the handshake completes (no session yet); returns `true` once pushed. Safe to call
     * concurrently with [serve]'s reply path — both go through [sealAndAppend].
     */
    suspend fun pushCallback(plaintext: ByteArray): Boolean {
        val current = session ?: return false
        sealAndAppend(current, EpcFrameType.CALLBACK, plaintext)
        return true
    }

    private suspend fun sealAndAppend(session: EpcSession, type: Byte, body: ByteArray) {
        sealMutex.withLock {
            val sealed = session.seal(byteArrayOf(type) + body)
            client.append(sessionId, RendezvousDirection.PROXY_TO_CLIENT, sealed)
        }
    }
}
