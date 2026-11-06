package app.tenshu.control.epc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.tenshu.core.Data
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.proxy.ClientConnection

/**
 * The proxy's [ClientConnection] for an EPC-paired client (`epc:<sessionId>`). When an op on this
 * client's session registers an observer/stream and it fires, the proxy's `StreamRegistry` calls
 * [send]; this serialises the pushed [Data] and routes it (via [EpcCallbackRouter]) into the session's
 * `EpcRendezvousAcceptor.pushCallback`, which seals it onto the relay as a CALLBACK frame the client's
 * `EpcRendezvousChannel.onCallback` then receives.
 *
 * Serialisation matches the other transports' push path (`UnixSocketClientConnection.send` →
 * `serializer.serialize(data)`, inline bytes, no OOB tokeniser — the EPC seal carries everything),
 * so the client parses it with the same `BinaryParser`. A **single ordered consumer** drains the
 * outbound queue so a stream's initial value and later updates reach the app in emission order (the
 * acceptor's seal counter would otherwise be assigned in whatever order racing pushes sealed).
 */
class EpcClientConnection(
    private val clientId: String,
    private val router: EpcCallbackRouter,
    private val serializer: BinarySerializer,
) : ClientConnection {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val outbound = Channel<ByteArray>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (bytes in outbound) {
                // Look up per-push: a torn-down session unregisters, after which pushes drop.
                router.route(clientId)?.invoke(bytes)
            }
        }
    }

    override fun init() {}

    override fun send(data: Data) {
        // Serialise on the calling (stream-registry) thread to capture the value at emission time,
        // then hand off to the ordered consumer. trySend never blocks on an UNLIMITED channel.
        outbound.trySend(serializer.serialize(data))
    }

    override fun destroy() {
        outbound.close()
        scope.cancel()
    }
}
