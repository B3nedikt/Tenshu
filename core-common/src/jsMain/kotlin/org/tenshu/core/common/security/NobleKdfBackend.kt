package org.tenshu.core.common.security

import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get

// JS Argon2id backend, backed by `@noble/hashes/argon2`'s sync
// `argon2id` (RFC 9106 v0x13). Pure JS — slower than a wasm impl but
// portable, no extra build step, and sufficient for the recovery-flow
// derivation (rare, off-thread). The `@noble/hashes` 1.4+ package
// already declared in `core-common/build.gradle.kts` ships the argon2
// export — no new npm dependency.
//
// Same author and audit posture as `@noble/curves` / `@noble/ciphers`
// already used by Ed25519 / X25519 / AEAD backends. RFC 9106 v0x13 is
// the noble default; matches BouncyCastle's `ARGON2_VERSION_13` so the
// two backends are byte-for-byte interoperable for the same parameters.
@JsModule("@noble/hashes/argon2")
private external object NobleArgon2 {
    fun argon2id(password: Uint8Array, salt: Uint8Array, opts: dynamic): Uint8Array
}

class NobleKdfBackend : KdfBackend {

    override fun argon2id(
        password: ByteArray,
        salt: ByteArray,
        parallelism: Int,
        memoryKiB: Int,
        iterations: Int,
        outputLength: Int,
    ): ByteArray {
        // noble option names: t = iterations, m = memory in KiB, p =
        // parallelism, dkLen = output bytes. `version` defaults to 0x13;
        // pin it explicitly for symmetry with the BC backend.
        val opts: dynamic = js("({})")
        opts.t = iterations
        opts.m = memoryKiB
        opts.p = parallelism
        opts.dkLen = outputLength
        opts.version = 0x13
        return NobleArgon2
            .argon2id(password.toUint8Array(), salt.toUint8Array(), opts)
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
