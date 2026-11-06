package org.tenshu.sync.relay.auth

import org.tenshu.core.security.stubEd25519Signature
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MasterDelegationVerifierTest {

    private val masterKey = "master-identity-key".encodeToByteArray()
    private val ed25519 = StubEd25519Backend()

    @Test
    fun verify_validSignature_returnsTrue() {
        val message = "hello".encodeToByteArray()
        val signature = stubEd25519Signature(masterKey, message)
        assertTrue(MasterDelegationVerifier.verify(masterKey, message, signature, ed25519))
    }

    @Test
    fun verify_wrongKey_returnsFalse() {
        val message = "hello".encodeToByteArray()
        val signature = stubEd25519Signature("attacker-key".encodeToByteArray(), message)
        assertFalse(MasterDelegationVerifier.verify(masterKey, message, signature, ed25519))
    }

    @Test
    fun verify_tamperedMessage_returnsFalse() {
        val signature = stubEd25519Signature(masterKey, "hello".encodeToByteArray())
        assertFalse(
            MasterDelegationVerifier.verify(
                masterKey,
                "hello!".encodeToByteArray(),
                signature,
                ed25519
            )
        )
    }

    /**
     * The relay's names are thin aliases over the shared `:sync-service-common` builders (B1) — the
     * one place the bytes are spelled. Pinned so a relay-local re-spelling cannot drift back in.
     */
    @Test
    fun theRelaysMessageBuildersAreTheSharedTaggedOnes() {
        val key = ByteArray(32) { it.toByte() }
        assertContentEquals(
            org.tenshu.sync.common.realmBootstrapMessage("owner-a", key, key, "device-1"),
            MasterDelegationVerifier.realmBootstrapMessage("owner-a", key, key, "device-1"),
        )
        assertContentEquals(
            org.tenshu.sync.common.pairingDelegationMessage(key, "device-1", 7L),
            MasterDelegationVerifier.pairingResponseMessage(key, "device-1", 7L),
        )
        assertContentEquals(
            org.tenshu.sync.common.recoveryClaimMessage(key, "device-1", byteArrayOf(9)),
            MasterDelegationVerifier.recoveryClaimMessage(key, "device-1", byteArrayOf(9)),
        )
        assertContentEquals(
            org.tenshu.sync.common.offerResponseMessage("approved", "h", key, null),
            MasterDelegationVerifier.offerResponseMessage("approved", "h", key, null),
        )
    }
}
