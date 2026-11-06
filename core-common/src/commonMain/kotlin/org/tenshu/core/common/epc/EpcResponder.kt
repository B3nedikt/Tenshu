package org.tenshu.core.common.epc

import org.tenshu.core.security.SyncCryptoManager

/**
 * Proxy-side counterpart of [EpcConnector] — Phase 2c of
 * `docs/proposals/ios-proxy-architecture.md`.
 *
 * Pure compute, **non-`suspend`**: given the bytes of a client's `ClientHello`,
 * it mints the proxy's ephemeral key, completes the [EpcHandshake], and returns
 * the `ServerHello` to send back plus the established [EpcSession] and the SAS.
 * It does no I/O of its own — the surrounding proxy accept path owns reading the
 * hello off the transport and writing the reply, so this slots into either a
 * foregrounded loopback (Phase 2) or the relay-rendezvous accept loop (Phase 3)
 * without change.
 */
class EpcResponder(private val crypto: SyncCryptoManager) {

    fun accept(clientHello: ByteArray): Accepted {
        val clientPublicKey = EpcHandshakeMessage.decodePublicKey(clientHello)
        val pending = EpcHandshake.begin(crypto, EpcRole.PROXY)
        val established = EpcHandshake.complete(crypto, pending, clientPublicKey)
        return Accepted(
            serverHello = EpcHandshakeMessage.encode(pending.localPublicKey),
            sas = established.sas,
            session = established.session,
        )
    }

    /**
     * Result of accepting a `ClientHello`: the [serverHello] bytes to return to
     * the client, the [sas] to display for confirmation, and the live [session]
     * the proxy uses to open subsequent client frames and seal its replies.
     */
    class Accepted internal constructor(
        val serverHello: ByteArray,
        val sas: String,
        val session: EpcSession,
    )
}
