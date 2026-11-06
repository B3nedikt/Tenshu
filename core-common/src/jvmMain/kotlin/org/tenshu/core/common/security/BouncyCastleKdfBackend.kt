package org.tenshu.core.common.security

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

// JVM Argon2id backend (covers both Android and Desktop). Uses
// BouncyCastle's pure-Java `Argon2BytesGenerator` with `Argon2Parameters`.
// Lives in `jvmMain` so the same class is shared by `androidMain` and
// `desktopMain`; the BouncyCastle dependency is already declared at the
// `jvmMain` source-set level.
//
// Output is byte-identical to the JS noble backend for the same
// parameters (both implement RFC 9106 v0x13). Cross-backend agreement
// is asserted at the test level via shared known-vector fixtures.
class BouncyCastleKdfBackend : KdfBackend {

    override fun argon2id(
        password: ByteArray,
        salt: ByteArray,
        parallelism: Int,
        memoryKiB: Int,
        iterations: Int,
        outputLength: Int,
    ): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withParallelism(parallelism)
            .withMemoryAsKB(memoryKiB)
            .withIterations(iterations)
            .build()
        val generator = Argon2BytesGenerator().apply { init(params) }
        val out = ByteArray(outputLength)
        generator.generateBytes(password, out)
        return out
    }
}
