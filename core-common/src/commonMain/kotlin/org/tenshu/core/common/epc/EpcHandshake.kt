package org.tenshu.core.common.epc

import org.tenshu.core.common.util.hkdfSha256
import org.tenshu.core.security.SyncCryptoManager

/**
 * The authenticated key exchange that establishes an [EpcSession] — whitepaper
 * *External Proxy Connection* measure 1, Phase 2 of
 * `docs/proposals/ios-proxy-architecture.md`.
 *
 * Deliberately mirrors the device-pairing handshake
 * (`InitiatePairingImplementation` + `PairingSas.derivePairingSas`): both ends
 * mint an ephemeral X25519 keypair, exchange public keys, run ECDH, and derive
 * a session secret + a 6-digit **Short Authentication String** from the shared
 * secret. The SAS is the human-checked MITM authenticator — a relay or network
 * attacker substituting its own ephemeral key yields a different shared secret
 * on one side, so the SAS shown on the app and on the proxy diverge and the
 * user catches it. No third party is trusted (whitepaper EPC §, measure 1).
 *
 * The difference from device pairing is *what the channel is for*: pairing
 * admits a new realm **device** (and hands over master keys); EPC pairs a
 * **client app** with a proxy as an untrusted endpoint that only ever sees what
 * the proxy chooses to expose. So this handshake produces a *channel*
 * ([EpcSession]), not a device admission — no master-key delegation here.
 *
 * Usage (each side, transport-agnostic — no `suspend`, no I/O):
 * ```
 * val pending = EpcHandshake.begin(crypto, EpcRole.CLIENT)  // app side
 * // …send pending.localPublicKey to the proxy, receive its public key…
 * val established = EpcHandshake.complete(crypto, pending, proxyPublicKey)
 * showForConfirmation(established.sas)   // user compares against the proxy's SAS
 * val channel = established.session
 * ```
 */
object EpcHandshake {
    private val SESSION_INFO = "tenshu/epc/session/v1".encodeToByteArray()
    private val SAS_INFO = "tenshu/epc/sas/v1".encodeToByteArray()

    /** Ephemeral keypair + role, retained between [begin] and [complete] on one side. */
    class Pending internal constructor(
        val role: EpcRole,
        val localPublicKey: ByteArray,
        internal val localPrivateKey: ByteArray,
    )

    /** Result of a completed handshake: the live [session] and the [sas] to confirm. */
    class Established internal constructor(
        val session: EpcSession,
        val sas: String,
    )

    /** Mint this side's ephemeral keypair. Send [Pending.localPublicKey] to the peer. */
    fun begin(crypto: SyncCryptoManager, role: EpcRole): Pending {
        val keyPair = crypto.generateKeyPair()
        return Pending(role, keyPair.publicKey, keyPair.privateKey)
    }

    /**
     * Complete the exchange against the peer's [remotePublicKey]: ECDH → session
     * secret + SAS → an [EpcSession]. Both ends order the two public keys
     * identically (initiator = [EpcRole.CLIENT] first) so the derived secret and
     * SAS match regardless of which side computes them.
     */
    fun complete(
        crypto: SyncCryptoManager,
        pending: Pending,
        remotePublicKey: ByteArray,
    ): Established {
        val shared = crypto.calculateSharedSecret(pending.localPrivateKey, remotePublicKey)
        val (initiatorPublicKey, responderPublicKey) = when (pending.role) {
            EpcRole.CLIENT -> pending.localPublicKey to remotePublicKey
            EpcRole.PROXY -> remotePublicKey to pending.localPublicKey
        }
        val salt = initiatorPublicKey + responderPublicKey
        val sessionSecret = hkdfSha256(shared, salt, SESSION_INFO, EpcSession.KEY_SIZE)
        val sas = deriveSas(shared, salt)
        val session = EpcSession.fromSessionSecret(crypto, sessionSecret, pending.role)
        return Established(session, sas)
    }

    /**
     * The 6-digit EPC SAS — same construction as `PairingSas.derivePairingSas`
     * but in its own HKDF info domain so an EPC SAS can never collide with a
     * device-pairing SAS for the same key material.
     */
    private fun deriveSas(shared: ByteArray, salt: ByteArray): String {
        val sasBytes = hkdfSha256(shared, salt, SAS_INFO, 4)
        val be32 = ((sasBytes[0].toInt() and 0xFF) shl 24) or
            ((sasBytes[1].toInt() and 0xFF) shl 16) or
            ((sasBytes[2].toInt() and 0xFF) shl 8) or
            (sasBytes[3].toInt() and 0xFF)
        val truncated = (be32 ushr 12) % 1_000_000
        return truncated.toString().padStart(6, '0')
    }
}
