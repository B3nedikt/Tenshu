package org.tenshu.sync.relay.auth

import org.tenshu.core.common.security.Ed25519Backend
import org.tenshu.core.security.stubEd25519Signature

/**
 * Test backend that mirrors `stubEd25519Signature(...)` so existing test fixtures that
 * mint tokens via the stub primitive continue to verify. Production wires
 * `BouncyCastleEd25519Backend` via `Application.module`'s default; tests that exercise
 * relay routes pass an instance of this class instead.
 *
 * Mirroring rather than calling real Ed25519 keeps the tests fast, deterministic, and
 * decoupled from BouncyCastle's keypair derivation — the test fixtures use
 * recognisable byte patterns ("device-key-bytes-padded-to-32-x") rather than RFC 8032
 * seeds.
 */
class StubEd25519Backend : Ed25519Backend {

    override fun sign(privateKeySeed: ByteArray, message: ByteArray): ByteArray =
        stubEd25519Signature(privateKeySeed, message)

    override fun verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray
    ): Boolean = signature.contentEquals(stubEd25519Signature(publicKey, message))

    override fun derivePublicKey(privateKeySeed: ByteArray): ByteArray =
        error("StubEd25519Backend.derivePublicKey is not used by relay tests")
}
