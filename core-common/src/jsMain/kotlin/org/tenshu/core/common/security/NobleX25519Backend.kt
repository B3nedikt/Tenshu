package org.tenshu.core.common.security

import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.tenshu.core.common.util.nextSecureRandomBytes

// JS X25519 backend, backed by `@noble/curves/ed25519`'s `x25519`
// export. Same package as the Ed25519 backend from slice A — no extra
// bundle cost.
@JsModule("@noble/curves/ed25519")
private external object NobleX25519 {
    val x25519: X25519Module
}

private external interface X25519Module {
    fun getPublicKey(privateKey: Uint8Array): Uint8Array
    fun getSharedSecret(privateKey: Uint8Array, otherPublicKey: Uint8Array): Uint8Array
}

class NobleX25519Backend : X25519Backend {

    override fun generateKeyPair(): SyncKeyPair {
        // @noble's x25519 doesn't expose a `randomPrivateKey()` helper
        // (the keypair is deterministic from the private bytes — any
        // 32 random bytes work). Use the platform CSPRNG via the
        // existing core-common util to keep the entropy source
        // consistent with the rest of the SDK.
        val privateKey = nextSecureRandomBytes(X25519Backend.X25519_KEY_SIZE)
        val publicKey = NobleX25519.x25519.getPublicKey(privateKey.toUint8Array()).toByteArray()
        return SyncKeyPair(publicKey = publicKey, privateKey = privateKey)
    }

    override fun deriveKeyPair(seed: ByteArray): SyncKeyPair {
        require(seed.size == X25519Backend.X25519_KEY_SIZE) {
            "X25519 seed must be ${X25519Backend.X25519_KEY_SIZE} bytes; got ${seed.size}"
        }
        // Byte-identical to generateKeyPair() but with a deterministic seed:
        // @noble derives the public key from the raw 32 private bytes and
        // clamps the scalar at multiplication time (RFC 7748).
        val publicKey = NobleX25519.x25519.getPublicKey(seed.toUint8Array()).toByteArray()
        return SyncKeyPair(publicKey = publicKey, privateKey = seed.copyOf())
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
        return NobleX25519.x25519
            .getSharedSecret(privateKey.toUint8Array(), otherPublicKey.toUint8Array())
            .toByteArray()
    }
}

private fun ByteArray.toUint8Array(): Uint8Array {
    val arr = Uint8Array(size)
    for (i in indices) arr.asDynamic()[i] = this[i].toInt() and 0xFF
    return arr
}

private fun Uint8Array.toByteArray(): ByteArray {
    val result = ByteArray(length)
    for (i in 0 until length) result[i] = this[i]
    return result
}
