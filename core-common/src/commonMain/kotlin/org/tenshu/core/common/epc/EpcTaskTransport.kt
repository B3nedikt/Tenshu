package org.tenshu.core.common.epc

import org.tenshu.core.common.transport.TaskTransport

/**
 * Wraps any [TaskTransport] with an established [EpcSession], so every byte that
 * crosses the underlying transport is sealed (whitepaper *External Proxy
 * Connection* — the app↔proxy E2E channel). Phase 2b of
 * `docs/proposals/ios-proxy-architecture.md`.
 *
 * EPC is an **encryption layer over an existing transport**, not a transport of
 * its own: [inner] still owns connect/bind, framing, and wire delivery (the
 * in-process loopback in tests today; relay rendezvous in Phase 3). The proxy on
 * the far side decrypts with the peer [EpcSession] before the bytes reach the
 * gateway, so **the proxy core is unchanged** — it still sees a plaintext
 * `ByteArray` request and returns a plaintext `ByteArray` response.
 *
 * Direction mapping: outbound [call]/[callCancellable] requests seal on the
 * client→proxy key; responses and pushed [onCallback]s open on the proxy→client
 * key. Both response and callback frames share the proxy→client sequence
 * counter, so this assumes the underlying transport **preserves frame order**
 * across the two — true for a single ordered stream (UDS, Binder, a relay
 * mailbox, the loopback). The transport, not EPC, is responsible for that order.
 *
 * The `taskId` of [callCancellable] and [cancel] stays in the clear: it is a
 * transport-level correlation tag the inner transport needs to route frames, not
 * user data (the request/response bodies it tags are sealed).
 */
class EpcTaskTransport(
    private val inner: TaskTransport,
    private val session: EpcSession,
) : TaskTransport {

    override val isConnected: Boolean get() = inner.isConnected

    override fun bind() = inner.bind()

    override fun unbind() = inner.unbind()

    override suspend fun awaitConnected(timeoutMillis: Long): Boolean =
        inner.awaitConnected(timeoutMillis)

    override suspend fun call(request: ByteArray): ByteArray =
        session.open(inner.call(session.seal(request)))

    override suspend fun callCancellable(request: ByteArray, taskId: String): ByteArray =
        session.open(inner.callCancellable(session.seal(request), taskId))

    override fun cancel(taskId: String) = inner.cancel(taskId)

    // Nothing to seal: the answer is a local fact about this device, never a frame on the wire.
    override suspend fun controlInstalled(): Boolean? = inner.controlInstalled()

    override fun sendFile(uri: String) = inner.sendFile(uri)

    override var onCallback: ((ByteArray) -> Unit)? = null
        set(value) {
            field = value
            // Decrypt pushed callbacks before handing them to the real consumer.
            inner.onCallback = value?.let { consumer ->
                { sealed -> consumer(session.open(sealed)) }
            }
        }
}
