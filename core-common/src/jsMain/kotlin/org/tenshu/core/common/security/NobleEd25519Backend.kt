package org.tenshu.core.common.security

import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get

// JS Ed25519 backend, backed by `@noble/curves/ed25519`. ~30 KB minified
// when tree-shaken, audited, sync API. Mirrors the existing `HashUtil.js.kt`
// shape — `@JsModule` external + tiny ByteArray ↔ Uint8Array helpers
// duplicated here because they're file-private in HashUtil.
@JsModule("@noble/curves/ed25519")
private external object NobleEd25519 {
    val ed25519: Ed25519Module
}

private external interface Ed25519Module {
    fun sign(message: Uint8Array, privateKey: Uint8Array): Uint8Array
    fun verify(signature: Uint8Array, message: Uint8Array, publicKey: Uint8Array): Boolean
    fun getPublicKey(privateKey: Uint8Array): Uint8Array
}

class NobleEd25519Backend : Ed25519Backend {

    override fun sign(privateKeySeed: ByteArray, message: ByteArray): ByteArray {
        require(privateKeySeed.size == Ed25519Backend.ED25519_SEED_SIZE) {
            "Ed25519 seed must be ${Ed25519Backend.ED25519_SEED_SIZE} bytes; got ${privateKeySeed.size}"
        }
        return NobleEd25519.ed25519
            .sign(message.toUint8Array(), privateKeySeed.toUint8Array())
            .toByteArray()
    }

    override fun verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        if (publicKey.size != Ed25519Backend.ED25519_PUBLIC_KEY_SIZE) return false
        if (signature.size != Ed25519Backend.ED25519_SIGNATURE_SIZE) return false
        // @noble/curves throws on malformed signature/key bytes (e.g. an
        // s value out of range). Treat any throw as verification failure
        // to match the BouncyCastle backend's shape: verify is the source
        // of truth and returns a Boolean, never an exception.
        return try {
            NobleEd25519.ed25519.verify(
                signature.toUint8Array(),
                message.toUint8Array(),
                publicKey.toUint8Array(),
            )
        } catch (t: Throwable) {
            false
        }
    }

    override fun derivePublicKey(privateKeySeed: ByteArray): ByteArray {
        require(privateKeySeed.size == Ed25519Backend.ED25519_SEED_SIZE) {
            "Ed25519 seed must be ${Ed25519Backend.ED25519_SEED_SIZE} bytes; got ${privateKeySeed.size}"
        }
        return NobleEd25519.ed25519.getPublicKey(privateKeySeed.toUint8Array()).toByteArray()
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
