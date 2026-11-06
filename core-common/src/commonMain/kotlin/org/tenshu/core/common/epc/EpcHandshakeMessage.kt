package org.tenshu.core.common.epc

/**
 * The two-message EPC handshake envelope exchanged over the *plain* transport
 * before the [EpcSession] exists — `ClientHello` (app → proxy) and `ServerHello`
 * (proxy → app). Both carry only an ephemeral X25519 **public** key, which is
 * safe in the clear; the SAS authenticates the exchange (see [EpcHandshake]).
 *
 * Shape: `[1-byte version][ephemeral public key]`. The key length is not pinned
 * here (the crypto backend validates it on ECDH), so the stub and real backends
 * round-trip through the same framing. An unknown version is a hard error — same
 * "update the app, no in-protocol negotiation" rule as device pairing
 * (`docs/proposals/sync-pairing-flow-improvements.md` Step 8).
 *
 * Public so the relay-rendezvous client (`EpcRendezvousChannel`, `:epc-rendezvous-client`) can drive
 * the same handshake the in-process `EpcConnector` does — the envelope codec is intentionally part of
 * the EPC client surface, like `EpcConnector`/`EpcSession`.
 */
object EpcHandshakeMessage {
    const val VERSION: Byte = 1

    fun encode(publicKey: ByteArray): ByteArray = byteArrayOf(VERSION) + publicKey

    fun decodePublicKey(message: ByteArray): ByteArray {
        if (message.isEmpty()) {
            throw EpcHandshakeFormatException("empty EPC handshake message")
        }
        if (message[0] != VERSION) {
            throw EpcHandshakeFormatException("unsupported EPC handshake version ${message[0]}")
        }
        return message.copyOfRange(1, message.size)
    }
}

/** A malformed or wrong-version EPC handshake message. */
class EpcHandshakeFormatException(message: String) : RuntimeException(message)
