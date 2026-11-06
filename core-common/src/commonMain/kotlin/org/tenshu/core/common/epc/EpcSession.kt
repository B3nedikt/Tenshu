package org.tenshu.core.common.epc

import org.tenshu.core.common.util.hkdfSha256
import org.tenshu.core.security.SyncCryptoManager

/**
 * Which end of an [EpcSession] this instance represents. An EPC channel is
 * inherently asymmetric — the client app *initiates* the pairing, the proxy
 * *responds* — and the two ends use **different** AEAD keys for the two
 * directions (see [EpcSession]) so a frame can never be reflected back at its
 * sender and accepted.
 */
enum class EpcRole { CLIENT, PROXY }

/**
 * An established External Proxy Connection channel (whitepaper *External Proxy
 * Connection*, measure 1) — Phase 2 of `docs/proposals/ios-proxy-architecture.md`.
 *
 * This is the *protocol* layer only: it [seal]s and [open]s opaque byte frames
 * over whatever transport carries them (Phase 2b wires it behind a
 * `TaskTransport` over the in-process loopback; Phase 3 swaps in relay
 * rendezvous underneath — neither changes this class). It holds no sockets and
 * is deliberately non-`suspend`.
 *
 * Construct one via [EpcHandshake.complete]; the constructor is internal so the
 * only way to get a session is to complete an authenticated key exchange.
 *
 * **Frame shape:** `[8-byte big-endian sequence number][AEAD blob]`. The
 * sequence number is also bound into the AEAD's associated data
 * ([SyncCryptoManager.encryptWithAad]), so:
 *  - confidentiality + integrity come from the AEAD (XChaCha20-Poly1305);
 *  - the bound sequence makes a frame undecryptable if its number is altered;
 *  - [open] additionally enforces a **strictly increasing** received sequence,
 *    rejecting replays and reordering on the reliable, in-order transports EPC
 *    runs over (loopback, UDS, Binder, a single relay-rendezvous stream).
 *
 * The two directions use independent keys derived from the session secret, so
 * the client's send key is the proxy's receive key and vice-versa.
 */
class EpcSession internal constructor(
    private val crypto: SyncCryptoManager,
    private val sendKey: ByteArray,
    private val receiveKey: ByteArray,
) {
    private var sendSequence: Long = 0
    private var lastReceivedSequence: Long = -1

    /** Encrypt [plaintext] into the next outbound frame and advance the send counter. */
    fun seal(plaintext: ByteArray): ByteArray {
        val sequence = sendSequence
        sendSequence++
        val sequenceBytes = encodeSequence(sequence)
        val blob = crypto.encryptWithAad(plaintext, sendKey, sequenceBytes)
        return sequenceBytes + blob
    }

    /**
     * Decrypt an inbound [frame] produced by the peer's [seal]. Throws
     * [EpcChannelException] if the frame is malformed or its sequence number is
     * not strictly greater than the last accepted one (replay / reorder). The
     * underlying AEAD throws on a corrupted or wrong-key ciphertext. State only
     * advances on a fully successful decrypt, so a rejected frame never gates
     * out a subsequent legitimate one.
     */
    fun open(frame: ByteArray): ByteArray {
        if (frame.size < SEQUENCE_SIZE) {
            throw EpcChannelException("EPC frame too short: ${frame.size} bytes")
        }
        val sequenceBytes = frame.copyOfRange(0, SEQUENCE_SIZE)
        val sequence = decodeSequence(sequenceBytes)
        if (sequence <= lastReceivedSequence) {
            throw EpcChannelException(
                "EPC sequence not increasing: got $sequence, last was $lastReceivedSequence",
            )
        }
        val blob = frame.copyOfRange(SEQUENCE_SIZE, frame.size)
        val plaintext = crypto.decryptWithAad(blob, receiveKey, sequenceBytes)
        lastReceivedSequence = sequence
        return plaintext
    }

    companion object {
        /** Width of the big-endian sequence-number prefix on every frame. */
        const val SEQUENCE_SIZE = 8

        /** AEAD key size (XChaCha20-Poly1305). */
        const val KEY_SIZE = 32

        private val CLIENT_TO_PROXY_INFO = "tenshu/epc/key/c2p/v1".encodeToByteArray()
        private val PROXY_TO_CLIENT_INFO = "tenshu/epc/key/p2c/v1".encodeToByteArray()

        /**
         * Split the handshake's session secret into the two directional AEAD
         * keys and assign send/receive by [role]. Called by [EpcHandshake].
         */
        internal fun fromSessionSecret(
            crypto: SyncCryptoManager,
            sessionSecret: ByteArray,
            role: EpcRole,
        ): EpcSession {
            val clientToProxy = hkdfSha256(sessionSecret, ByteArray(0), CLIENT_TO_PROXY_INFO, KEY_SIZE)
            val proxyToClient = hkdfSha256(sessionSecret, ByteArray(0), PROXY_TO_CLIENT_INFO, KEY_SIZE)
            return when (role) {
                EpcRole.CLIENT -> EpcSession(crypto, sendKey = clientToProxy, receiveKey = proxyToClient)
                EpcRole.PROXY -> EpcSession(crypto, sendKey = proxyToClient, receiveKey = clientToProxy)
            }
        }

        private fun encodeSequence(value: Long): ByteArray {
            val out = ByteArray(SEQUENCE_SIZE)
            for (i in 0 until SEQUENCE_SIZE) {
                out[i] = (value ushr (8 * (SEQUENCE_SIZE - 1 - i))).toByte()
            }
            return out
        }

        private fun decodeSequence(bytes: ByteArray): Long {
            var value = 0L
            for (i in 0 until SEQUENCE_SIZE) {
                value = (value shl 8) or (bytes[i].toLong() and 0xFF)
            }
            return value
        }
    }
}

/** A malformed, replayed, or reordered EPC frame. */
class EpcChannelException(message: String) : RuntimeException(message)
