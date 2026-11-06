package org.tenshu.core.common.epc

import org.tenshu.core.common.transport.TaskTransport
import org.tenshu.core.security.SyncCryptoManager

/**
 * Client-side driver that establishes an EPC channel over a plain [TaskTransport]
 * — Phase 2c of `docs/proposals/ios-proxy-architecture.md`.
 *
 * It is **not** a schema `Operation`: schema operations are dispatched *through*
 * the established channel, so the step that establishes that channel cannot
 * itself be one (it would be circular). It is plain client-side orchestration —
 * `suspend` because it does one transport round-trip, which is fine: the
 * non-`suspend` `Operation`/`Interactor` rule (HR #3, ADR 0012) governs the
 * schema dispatch layer, not transport bring-up (`TaskTransport.call` is already
 * `suspend`). The eventual iOS app calls this from its client bootstrap /
 * ViewModel.
 *
 * One round-trip: send a `ClientHello`, receive the proxy's `ServerHello`,
 * complete the [EpcHandshake], and hand back an [EpcConnection] whose
 * [EpcConnection.transport] is the same transport wrapped so every later call is
 * sealed. The caller must surface [EpcConnection.sas] for the user to compare
 * against the proxy's before trusting the channel (whitepaper EPC measure 1).
 */
class EpcConnector(private val crypto: SyncCryptoManager) {

    suspend fun connect(transport: TaskTransport): EpcConnection {
        val pending = EpcHandshake.begin(crypto, EpcRole.CLIENT)
        val serverHello = transport.call(EpcHandshakeMessage.encode(pending.localPublicKey))
        val proxyPublicKey = EpcHandshakeMessage.decodePublicKey(serverHello)
        val established = EpcHandshake.complete(crypto, pending, proxyPublicKey)
        return EpcConnection(
            sas = established.sas,
            transport = EpcTaskTransport(transport, established.session),
        )
    }
}

/**
 * A live EPC channel: [transport] is the sealed [EpcTaskTransport] ready for
 * operation traffic, and [sas] is the 6-digit code the user confirms against the
 * proxy's screen to rule out a MITM before relying on it.
 */
class EpcConnection internal constructor(
    val sas: String,
    val transport: TaskTransport,
)
