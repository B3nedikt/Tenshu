package org.tenshu.core.common.security

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// RFC 8032 §7.1 Ed25519 test vectors. Validates the BouncyCastle backend
// produces canonical signatures for the canonical inputs — sign,
// derivePublicKey, and verify must match the spec across platforms.
//
// Vectors are also used by the JS side's `NobleEd25519BackendTest` so a
// regression in either backend surfaces against the same anchor.
class BouncyCastleEd25519BackendTest {

    private val backend = BouncyCastleEd25519Backend()

    @Test
    fun rfc8032_test1_emptyMessage() {
        val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val expectedPublic = hex(
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
        )
        val message = ByteArray(0)
        val expectedSig = hex(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555f" +
                    "b8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
        )

        assertContentEquals(expectedPublic, backend.derivePublicKey(seed))
        assertContentEquals(expectedSig, backend.sign(seed, message))
        assertTrue(backend.verify(expectedPublic, message, expectedSig))
    }

    @Test
    fun rfc8032_test2_singleByteMessage() {
        val seed = hex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
        val expectedPublic = hex(
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"
        )
        val message = hex("72")
        val expectedSig = hex(
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da08" +
                    "5ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"
        )

        assertContentEquals(expectedPublic, backend.derivePublicKey(seed))
        assertContentEquals(expectedSig, backend.sign(seed, message))
        assertTrue(backend.verify(expectedPublic, message, expectedSig))
    }

    @Test
    fun verify_rejectsTamperedMessage() {
        val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val pub = backend.derivePublicKey(seed)
        val sig = backend.sign(seed, "hello".encodeToByteArray())
        assertFalse(backend.verify(pub, "world".encodeToByteArray(), sig))
    }

    @Test
    fun verify_rejectsWrongPublicKey() {
        val seedA = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val seedB = hex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
        val sig = backend.sign(seedA, "msg".encodeToByteArray())
        assertFalse(backend.verify(backend.derivePublicKey(seedB), "msg".encodeToByteArray(), sig))
    }

    @Test
    fun verify_returnsFalseOnLengthMismatch_doesNotThrow() {
        // Real-world inputs that have the wrong length must be rejected
        // without bubbling an exception — `Session.unlock` and
        // `verifyEd25519` callers assume verify never throws.
        assertFalse(backend.verify(ByteArray(31), ByteArray(0), ByteArray(64)))
        assertFalse(backend.verify(ByteArray(32), ByteArray(0), ByteArray(63)))
    }

    @Test
    fun sign_rejectsWrongSeedLength() {
        assertFailsWith<IllegalArgumentException> {
            backend.sign(ByteArray(31), "msg".encodeToByteArray())
        }
    }

    @Test
    fun derivePublicKey_rejectsWrongSeedLength() {
        assertFailsWith<IllegalArgumentException> {
            backend.derivePublicKey(ByteArray(0))
        }
    }

    @Test
    fun signAndDerive_areDeterministic() {
        // Ed25519 is deterministic by spec — repeated calls with the same
        // (seed, message) must produce byte-identical output.
        val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val msg = "deterministic".encodeToByteArray()
        assertContentEquals(backend.sign(seed, msg), backend.sign(seed, msg))
        assertContentEquals(backend.derivePublicKey(seed), backend.derivePublicKey(seed))
        assertEquals(Ed25519Backend.ED25519_PUBLIC_KEY_SIZE, backend.derivePublicKey(seed).size)
        assertEquals(Ed25519Backend.ED25519_SIGNATURE_SIZE, backend.sign(seed, msg).size)
    }
}

private fun hex(s: String): ByteArray {
    val out = ByteArray(s.length / 2)
    for (i in out.indices) {
        out[i] = ((s[i * 2].digitToInt(16) shl 4) or s[i * 2 + 1].digitToInt(16)).toByte()
    }
    return out
}
