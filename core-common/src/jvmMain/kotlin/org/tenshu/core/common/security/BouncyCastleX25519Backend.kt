package org.tenshu.core.common.security

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

// JVM X25519 backend (covers both Android and Desktop). Uses
// BouncyCastle's pure-Java X25519 impl, mirroring the Ed25519 backend
// from slice A. Lives in `jvmMain` so the same class is shared by
// `androidMain` and `desktopMain`.
class BouncyCastleX25519Backend : X25519Backend {

    private val secureRandom = SecureRandom()

    override fun generateKeyPair(): SyncKeyPair {
        val privateKey = X25519PrivateKeyParameters(secureRandom)
        val publicKey = privateKey.generatePublicKey()
        return SyncKeyPair(
            publicKey = publicKey.encoded,
            privateKey = privateKey.encoded,
        )
    }

    override fun deriveKeyPair(seed: ByteArray): SyncKeyPair {
        require(seed.size == X25519Backend.X25519_KEY_SIZE) {
            "X25519 seed must be ${X25519Backend.X25519_KEY_SIZE} bytes; got ${seed.size}"
        }
        // BouncyCastle treats the 32 bytes as the raw scalar and clamps on
        // use; `.encoded` returns the same scalar so it round-trips through
        // `calculateSharedSecret`'s `X25519PrivateKeyParameters(privateKey, 0)`.
        val privateKey = X25519PrivateKeyParameters(seed, 0)
        val publicKey = privateKey.generatePublicKey()
        return SyncKeyPair(
            publicKey = publicKey.encoded,
            privateKey = privateKey.encoded,
        )
    }

    override fun calculateSharedSecret(
        privateKey: ByteArray,
        otherPublicKey: ByteArray,
    ): ByteArray {
        require(privateKey.size == X25519Backend.X25519_KEY_SIZE) {
            "X25519 private key must be ${X25519Backend.X25519_KEY_SIZE} bytes; got ${privateKey.size}"
        }
        require(otherPublicKey.size == X25519Backend.X25519_KEY_SIZE) {
            "X25519 public key must be ${X25519Backend.X25519_KEY_SIZE} bytes; got ${otherPublicKey.size}"
        }
        val priv = X25519PrivateKeyParameters(privateKey, 0)
        val pub = X25519PublicKeyParameters(otherPublicKey, 0)
        val agreement = X25519Agreement().apply { init(priv) }
        val sharedSecret = ByteArray(X25519Backend.X25519_SHARED_SECRET_SIZE)
        agreement.calculateAgreement(pub, sharedSecret, 0)
        return sharedSecret
    }
}
