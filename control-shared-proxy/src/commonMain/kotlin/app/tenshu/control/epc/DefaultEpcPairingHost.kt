package app.tenshu.control.epc

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.tenshu.core.common.util.nextSecureRandomBytes

/**
 * Production [EpcPairingHost] — the proxy side of EPC app pairing (proposal §10.6). On [run] it:
 *
 *  1. mints a fresh pairing secret and builds the `tenshu://epc/pair#…` invite (+ derived sessionId);
 *  2. [provision]s the relay rendezvous session and surfaces the invite URI to render (QR / string);
 *  3. [serve]s the EPC accept loop, dispatching each decrypted op into Control's proxy gateway;
 *  4. surfaces the handshake SAS once a peer connects, then keeps serving until cancelled.
 *
 * The rendezvous I/O is injected via [serverFactory] ([EpcRendezvousServer] wraps the platform-only
 * `EpcRendezvousAcceptor`) and op dispatch via [dispatch] (`ProxyGateway.call`), so this
 * orchestration stays in commonMain and is unit-testable without a live relay.
 *
 * For the established session it also registers a server-push route on [router] (keyed by the same
 * `epc:<sessionId>` clientId the dispatch uses), so the proxy's [EpcClientConnection] for that client
 * can seal observer/stream updates back to the app — torn down with the session.
 */
class DefaultEpcPairingHost(
    private val relayBaseUrl: String,
    private val serverFactory: (sessionId: String) -> EpcRendezvousServer,
    private val dispatch: suspend (clientId: String, request: ByteArray) -> ByteArray,
    private val router: EpcCallbackRouter,
    private val generateSecret: () -> ByteArray = { nextSecureRandomBytes(PAIRING_SECRET_SIZE) },
) : EpcPairingHost {

    override suspend fun run(
        onInvite: (pairingUri: String) -> Unit,
        onSas: (sas: String) -> Unit,
    ) {
        val secret = generateSecret()
        val invite = buildEpcPairingInvite(relayBaseUrl, secret)
        val server = serverFactory(invite.sessionId)
        // One pairing session ↔ one app: a stable session-scoped clientId so the app's
        // ExecutionContext (and any per-session state) persists across the channel's ops.
        val clientId = "$CLIENT_ID_PREFIX${invite.sessionId}"

        server.provision()
        onInvite(invite.uri)

        // The proxy mints an EpcClientConnection for this clientId on its first op; route that
        // client's server-pushes into this session's sealed mailbox while the session is live.
        router.register(clientId) { server.pushCallback(it) }
        try {
            coroutineScope {
                // Serve the accept loop concurrently — it sets the SAS on the first (handshake) frame.
                // Dispatch is logged (size + duration + failure) because a hanging or throwing op
                // starves the strictly-serial reply sequence behind it — when the channel degrades,
                // this log line is the diagnosis (task: EPC op-plane hang).
                launch {
                    server.serve { request ->
                        val mark = kotlin.time.TimeSource.Monotonic.markNow()
                        try {
                            dispatch(clientId, request).also {
                                println("[epc-host] op ${request.size}B → ${it.size}B in ${mark.elapsedNow().inWholeMilliseconds}ms")
                            }
                        } catch (e: Throwable) {
                            println("[epc-host] op ${request.size}B THREW after ${mark.elapsedNow().inWholeMilliseconds}ms: ${e.message ?: e::class.simpleName}")
                            throw e
                        }
                    }
                }
                // Suspends until that handshake completes; a serve-loop failure cancels this scope
                // (structured concurrency), so this never hangs on a dead session.
                onSas(server.awaitEstablishedSas())
                // Fall through: `coroutineScope` keeps suspending on the serve child until the VM
                // cancels run() (the screen was dismissed), which tears the relay session down.
            }
        } finally {
            router.unregister(clientId)
        }
    }

    companion object {
        /** 16 bytes — the `EpcPairingPayload` ≥16-byte secret floor (proposal §10.2). */
        const val PAIRING_SECRET_SIZE = 16

        /** Prefix of the per-session proxy clientId (`epc:<sessionId>`); the connection factory matches it. */
        const val CLIENT_ID_PREFIX = "epc:"
    }
}
