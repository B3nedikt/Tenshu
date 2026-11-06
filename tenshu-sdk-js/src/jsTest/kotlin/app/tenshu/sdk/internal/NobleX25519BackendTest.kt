package app.tenshu.sdk.internal

import org.tenshu.core.common.security.NobleX25519Backend
import org.tenshu.core.common.security.X25519Backend
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

// RFC 7748 §6.1 X25519 test vectors against the @noble/curves backend.
// Same anchor vectors as `BouncyCastleX25519BackendTest` so a regression
// in either backend surfaces against the same fixed inputs.
class NobleX25519BackendTest {

    private val backend: X25519Backend = NobleX25519Backend()

    @Test
    fun crossBackend_sharedSecretAgreement() {
        // Same anchor vector as
        // `BouncyCastleX25519BackendTest.crossBackend_sharedSecretAgreement`
        // — both backends MUST produce these exact 32 bytes for these
        // inputs, otherwise pairing-handshake interop between the JVM
        // apps and the web SDK silently breaks.
        val alicePrivate = hex(
            "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"
        )
        val bobPublic = hex(
            "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dddc2495e4f29d6e4"
        )
        val expectedShared = hex(
            "31683aff9ef65fa832d34bc2c552895f1146c77d312dd696996a8f9809cfb91e"
        )
        assertContentEquals(expectedShared, backend.calculateSharedSecret(alicePrivate, bobPublic))
    }

    @Test
    fun generateKeyPair_producesCorrectSizes_andRoundTrips() {
        val a = backend.generateKeyPair()
        val b = backend.generateKeyPair()
        assertEquals(X25519Backend.X25519_KEY_SIZE, a.publicKey.size)
        assertEquals(X25519Backend.X25519_KEY_SIZE, a.privateKey.size)
        val sharedAB = backend.calculateSharedSecret(a.privateKey, b.publicKey)
        val sharedBA = backend.calculateSharedSecret(b.privateKey, a.publicKey)
        assertContentEquals(sharedAB, sharedBA)
        assertEquals(X25519Backend.X25519_SHARED_SECRET_SIZE, sharedAB.size)
        assertNotEquals(a.privateKey.toList(), b.privateKey.toList())
    }
}

private fun hex(s: String): ByteArray {
    val out = ByteArray(s.length / 2)
    for (i in out.indices) {
        out[i] = ((s[i * 2].digitToInt(16) shl 4) or s[i * 2 + 1].digitToInt(16)).toByte()
    }
    return out
}
