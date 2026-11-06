package org.tenshu.core.common.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Functional tests for the BouncyCastle Argon2id KDF backend (slice C of
// the Phase 4 crypto pass).
//
// The strongest portable property we assert is **determinism for fixed
// (password, salt, parameters)** — Argon2id v0x13 is deterministic by
// spec, and both BouncyCastle (JVM) and `@noble/hashes` (JS) implement
// the same RFC 9106 algorithm, so the same inputs produce byte-identical
// output across both backends. The JS backend cannot be exercised here
// (`compileTestKotlinJs` is disabled in `core-common/build.gradle.kts`
// pending the JS test-infra phase) — the spec compliance of the two
// audited libraries is the cross-backend guarantee.
//
// We deliberately use small cost parameters in these tests
// (m = 8 KiB, t = 1, p = 1) so each call is fast (single-digit ms).
// The production parameters from `KdfBackend.RECOVERY_KDF_*` are
// memory-hard at 64 MiB / t=3 and would dominate test runtime.
class BouncyCastleKdfBackendTest {

    private val backend = BouncyCastleKdfBackend()

    @Test
    fun argon2id_isDeterministic() {
        val out1 = backend.argon2id(
            password = "password".encodeToByteArray(),
            salt = "somesalt".encodeToByteArray(),
            parallelism = 1,
            memoryKiB = 8,
            iterations = 1,
            outputLength = 32,
        )
        val out2 = backend.argon2id(
            password = "password".encodeToByteArray(),
            salt = "somesalt".encodeToByteArray(),
            parallelism = 1,
            memoryKiB = 8,
            iterations = 1,
            outputLength = 32,
        )
        assertTrue(out1.contentEquals(out2), "Argon2id must be deterministic for fixed inputs")
        assertEquals(32, out1.size)
    }

    @Test
    fun argon2id_outputLengthMatchesRequested() {
        // Argon2id supports any output length ≥ 4 bytes (RFC 9106 §3.4);
        // the BC `Argon2BytesGenerator` uses the H_long expansion for
        // longer outputs. Assert the wrapper passes through the requested
        // length without truncation.
        for (len in listOf(16, 32, 64, 128)) {
            val out = backend.argon2id(
                password = "password".encodeToByteArray(),
                salt = "somesalt".encodeToByteArray(),
                parallelism = 1,
                memoryKiB = 8,
                iterations = 1,
                outputLength = len,
            )
            assertEquals(len, out.size, "outputLength=$len must match requested")
        }
    }

    @Test
    fun argon2id_differentSaltsProduceDifferentOutputs() {
        val a = backend.argon2id(
            password = "password".encodeToByteArray(),
            salt = "saltsalta".encodeToByteArray(),
            parallelism = 1, memoryKiB = 8, iterations = 1, outputLength = 32,
        )
        val b = backend.argon2id(
            password = "password".encodeToByteArray(),
            salt = "saltsaltb".encodeToByteArray(),
            parallelism = 1, memoryKiB = 8, iterations = 1, outputLength = 32,
        )
        assertFalse(a.contentEquals(b), "Different salts must produce different outputs")
    }

    @Test
    fun argon2id_differentPasswordsProduceDifferentOutputs() {
        val a = backend.argon2id(
            password = "passworda".encodeToByteArray(),
            salt = "somesalt".encodeToByteArray(),
            parallelism = 1, memoryKiB = 8, iterations = 1, outputLength = 32,
        )
        val b = backend.argon2id(
            password = "passwordb".encodeToByteArray(),
            salt = "somesalt".encodeToByteArray(),
            parallelism = 1, memoryKiB = 8, iterations = 1, outputLength = 32,
        )
        assertFalse(a.contentEquals(b), "Different passwords must produce different outputs")
    }

    @Test
    fun argon2id_differentParametersProduceDifferentOutputs() {
        // Cost parameters are part of the Argon2id input — changing any
        // of t/m/p must produce a different output. This is what makes
        // a parameter bump a protocol-version change in
        // `KdfBackend.RECOVERY_KDF_*`.
        val baseline = backend.argon2id(
            password = "password".encodeToByteArray(),
            salt = "somesalt".encodeToByteArray(),
            parallelism = 1, memoryKiB = 8, iterations = 1, outputLength = 32,
        )
        val moreIterations = backend.argon2id(
            password = "password".encodeToByteArray(),
            salt = "somesalt".encodeToByteArray(),
            parallelism = 1, memoryKiB = 8, iterations = 2, outputLength = 32,
        )
        val moreMemory = backend.argon2id(
            password = "password".encodeToByteArray(),
            salt = "somesalt".encodeToByteArray(),
            parallelism = 1, memoryKiB = 16, iterations = 1, outputLength = 32,
        )
        assertFalse(baseline.contentEquals(moreIterations), "iterations bump must change output")
        assertFalse(baseline.contentEquals(moreMemory), "memory bump must change output")
    }
}
