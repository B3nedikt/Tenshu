package org.tenshu.core.common.security

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

// JVM Ed25519 backend (covers both Android and Desktop). Uses BouncyCastle's
// pure-Java Ed25519 impl, the same library on both targets — Android's
// standard provider only adds Ed25519 at API 33 and minSdk is 24.
//
// Lives in `jvmMain` so the same class is shared by `androidMain` and
// `desktopMain` via the default hierarchy template — no source-set
// duplication.
class BouncyCastleEd25519Backend : Ed25519Backend {

    override fun sign(privateKeySeed: ByteArray, message: ByteArray): ByteArray {
        require(privateKeySeed.size == Ed25519Backend.ED25519_SEED_SIZE) {
            "Ed25519 seed must be ${Ed25519Backend.ED25519_SEED_SIZE} bytes; got ${privateKeySeed.size}"
        }
        val params = Ed25519PrivateKeyParameters(privateKeySeed, 0)
        val signer = Ed25519Signer().apply {
            init(true, params)
            update(message, 0, message.size)
        }
        return signer.generateSignature()
    }

    override fun verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        if (publicKey.size != Ed25519Backend.ED25519_PUBLIC_KEY_SIZE) return false
        if (signature.size != Ed25519Backend.ED25519_SIGNATURE_SIZE) return false
        val params = Ed25519PublicKeyParameters(publicKey, 0)
        val verifier = Ed25519Signer().apply {
            init(false, params)
            update(message, 0, message.size)
        }
        return verifier.verifySignature(signature)
    }

    override fun derivePublicKey(privateKeySeed: ByteArray): ByteArray {
        require(privateKeySeed.size == Ed25519Backend.ED25519_SEED_SIZE) {
            "Ed25519 seed must be ${Ed25519Backend.ED25519_SEED_SIZE} bytes; got ${privateKeySeed.size}"
        }
        return Ed25519PrivateKeyParameters(privateKeySeed, 0).generatePublicKey().encoded
    }
}
