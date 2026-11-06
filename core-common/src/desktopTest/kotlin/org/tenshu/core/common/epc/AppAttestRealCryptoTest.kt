package org.tenshu.core.common.epc

import org.tenshu.core.common.security.BouncyCastleAeadBackend
import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import org.tenshu.core.common.security.BouncyCastleKdfBackend
import org.tenshu.core.common.security.BouncyCastleX25519Backend
import org.tenshu.core.common.security.DefaultSyncCryptoManager
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * Phase 4a, signature-strength half — EPC measure 2 against the real
 * BouncyCastle Ed25519 backend, so it can prove what the bootstrap stub can't:
 * a tampered attestation fails verification, and a key the proxy doesn't trust
 * is rejected even with a structurally valid signature. Desktop/JVM only.
 */
class AppAttestRealCryptoTest {

    private val crypto = DefaultSyncCryptoManager(
        ed25519 = BouncyCastleEd25519Backend(),
        x25519 = BouncyCastleX25519Backend(),
        aead = BouncyCastleAeadBackend(),
        kdf = BouncyCastleKdfBackend(),
    )

    @Test
    fun realSignatureAttests() {
        val key = crypto.generateDeviceSigningKeyPair()
        val challenge = ByteArray(32) { it.toByte() }
        val result = SoftwareAppAttestVerifier(crypto)
            .verify(challenge, SoftwareAppAttestor(crypto, key).attest(challenge))
        assertIs<AppAttestResult.Attested>(result)
    }

    @Test
    fun tamperedAttestationFailsTheRealSignature() {
        val key = crypto.generateDeviceSigningKeyPair()
        val challenge = ByteArray(32) { it.toByte() }
        val attestation = SoftwareAppAttestor(crypto, key).attest(challenge)
        // Flip a byte in the signature tail.
        val tampered = attestation.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        assertIs<AppAttestResult.Rejected>(SoftwareAppAttestVerifier(crypto).verify(challenge, tampered))
    }

    @Test
    fun aKeyTheProxyDoesNotTrustIsRejected() {
        val trustedKey = crypto.generateDeviceSigningKeyPair()
        val attackerKey = crypto.generateDeviceSigningKeyPair()
        val challenge = ByteArray(32) { it.toByte() }
        // Verifier trusts only `trustedKey`'s public key; the attestation is a
        // valid signature under `attackerKey` — must still be rejected.
        val verifier = SoftwareAppAttestVerifier(crypto) { pub -> pub.contentEquals(trustedKey.publicKey) }
        val result = verifier.verify(challenge, SoftwareAppAttestor(crypto, attackerKey).attest(challenge))
        assertIs<AppAttestResult.Rejected>(result)
    }
}
