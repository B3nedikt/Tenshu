package app.tenshu.control.epc

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Routes a proxy→client server-push for an EPC-paired client to that session's sealed mailbox. It
 * bridges the two halves the gateway can't see across: [DefaultEpcPairingHost] **registers** a
 * session's push fn (sealing into its `EpcRendezvousAcceptor`) under the `epc:<sessionId>` clientId
 * when the channel comes up, and the per-session [EpcClientConnection] the proxy mints for that same
 * clientId **routes** `ClientConnection.send` through it. Closes the loop so observers/streams reach
 * a paired app (the "ops hookup" after the §3c transport-level push).
 *
 * Reads ([route], off the gateway/stream-registry thread) are lock-free over an
 * [AtomicReference]-held immutable map; register/unregister copy-on-write — the same pattern as
 * `ProxyGateway`'s in-flight snapshot.
 */
@OptIn(ExperimentalAtomicApi::class)
class EpcCallbackRouter {

    private val routes = AtomicReference<Map<String, suspend (ByteArray) -> Boolean>>(emptyMap())

    /** Register [push] (seals + appends a CALLBACK frame, returns false before the handshake). */
    fun register(clientId: String, push: suspend (ByteArray) -> Boolean) {
        update { it + (clientId to push) }
    }

    /** Drop a session's route on teardown — a later [route] for it returns null (push dropped). */
    fun unregister(clientId: String) {
        update { it - clientId }
    }

    /** The push fn for [clientId], or null if no EPC session is registered for it. */
    fun route(clientId: String): (suspend (ByteArray) -> Boolean)? = routes.load()[clientId]

    private inline fun update(transform: (Map<String, suspend (ByteArray) -> Boolean>) -> Map<String, suspend (ByteArray) -> Boolean>) {
        while (true) {
            val current = routes.load()
            if (routes.compareAndSet(current, transform(current))) return
        }
    }
}
