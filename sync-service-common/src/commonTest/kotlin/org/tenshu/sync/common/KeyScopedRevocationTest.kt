package org.tenshu.sync.common

import org.tenshu.core.security.BootstrapSyncCryptoManager
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1′ (`admission-revocation-names-the-capability.md` §7b.3): a revocation names the **capability
 * key**, so a re-mint clears it for free.
 *
 * ⚠⚠ The defect this closes was live on hardware. Under the epoch rule a device's own re-mint only
 * cleared a revocation because a *fresher epoch* outran the `>=` comparison — so when the
 * in-memory `seededRealmEpoch` was lost to a restart, the device minted below the revocation naming
 * it and the relay answered **403 "Admission capability has been revoked"** (§7b.1).
 */
class KeyScopedRevocationTest {

    private val crypto = BootstrapSyncCryptoManager()

    private val masterKey = ByteArray(32) { (it + 1).toByte() }
    private val keyOld = ByteArray(32) { (it + 60).toByte() }
    private val keyReminted = ByteArray(32) { (it + 90).toByte() }
    private val ownerId = "owner-realm-1"
    private val deviceId = "device-a"

    private fun cert(subjectKey: ByteArray, epoch: Long) = AdmissionCapabilityCert(
        ownerId,
        subjectKey,
        deviceId,
        epoch,
        crypto.signEd25519(
            masterKey,
            AdmissionCapabilityCert.message(ownerId, subjectKey, deviceId, epoch),
        ),
    )

    private fun keyScopedRevocation(key: ByteArray, epoch: Long = 5L) = AdmissionRevocation(
        ownerId = ownerId,
        deviceId = deviceId,
        epoch = epoch,
        revokedAt = 3_000L,
        signature = crypto.signEd25519(
            masterKey,
            AdmissionRevocation.message(ownerId, deviceId, epoch, 3_000L, key),
        ),
        deviceA33PublicKey = key,
    )

    private fun isRevoked(cert: AdmissionCapabilityCert, revocations: List<AdmissionRevocation>) =
        AdmissionChainVerifier.isRevoked(masterKey, cert, revocations, crypto::verifyEd25519)

    @Test
    fun aKeyScopedRevocationStripsTheCapabilityItNames() {
        assertTrue(isRevoked(cert(keyOld, epoch = 1L), listOf(keyScopedRevocation(keyOld))))
    }

    /**
     * ⭐⭐ The whole point of F1′, and the case the live 403 failed.
     *
     * A re-mint issues a NEW key, so the old revocation does not name it — **regardless of epoch**.
     * The re-minted cert here is deliberately minted at epoch 1, *below* the revocation's epoch 5:
     * under the old `revocation.epoch >= cert.mintedAtEpoch` rule this is exactly "born revoked",
     * which is what a lost `seededRealmEpoch` produced on a real desktop.
     */
    @Test
    fun aReMintClearsTheRevocation_evenWhenMintedBelowItsEpoch() {
        val remintedBelowTheRevocation = cert(keyReminted, epoch = 1L)

        assertFalse(
            isRevoked(remintedBelowTheRevocation, listOf(keyScopedRevocation(keyOld, epoch = 5L))),
            "a re-mint issues a new capability; naming the old key must not strip it — this is the " +
                "born-revoked 403 that F1′ exists to remove",
        )
    }

    @Test
    fun aKeyScopedRevocationDoesNotStripADifferentDevicesCapability() {
        val otherDevice = AdmissionCapabilityCert(
            ownerId,
            keyOld,
            "device-b",
            1L,
            crypto.signEd25519(
                masterKey,
                AdmissionCapabilityCert.message(ownerId, keyOld, "device-b", 1L),
            ),
        )

        assertFalse(isRevoked(otherDevice, listOf(keyScopedRevocation(keyOld))))
    }

    /**
     * ⚠⚠ **F5b** (§7g): the epoch fallback is gone. Its old guards pinned both failure directions
     * of removing it on a realm that held keyless revocations — fail-open (T-K-01 restored) and
     * fail-closed (3 of 4 devices unable to admit). The protocol-1 re-cut started every realm
     * with none, which is the precondition §7g.5 named.
     *
     * What is left to pin is that a revocation **without a key** cannot strip anything — not even
     * the empty-key cert that used to be the "pre-F0" shape the epoch rule existed for. The type
     * system already forbids a null key; an empty one is the only keyless value left to send.
     */
    @Test
    fun aKeylessRevocationStripsNothing() {
        val emptyKeyCert = AdmissionCapabilityCert(
            ownerId,
            ByteArray(0),
            deviceId,
            1L,
            crypto.signEd25519(
                masterKey,
                AdmissionCapabilityCert.message(ownerId, ByteArray(0), deviceId, 1L),
            ),
        )
        val keyless = keyScopedRevocation(ByteArray(0), epoch = 5L)

        assertFalse(
            isRevoked(cert(keyOld, epoch = 1L), listOf(keyless)),
            "a keyless revocation stripped a keyed cert — the epoch fallback is back",
        )
        assertFalse(
            isRevoked(emptyKeyCert, listOf(keyless)),
            "an empty key matched an empty key — a keyless revocation must name nothing",
        )
    }

    /**
     * ⚠ The epoch is signed but unread since F5b: a revocation at an epoch far *above* the cert's
     * mint still strips only if it names the cert's key.
     */
    @Test
    fun theRevocationEpochDoesNotAffectTheVerdict() {
        for (epoch in listOf(0L, 1L, 5L, Long.MAX_VALUE)) {
            assertTrue(isRevoked(cert(keyOld, epoch = 3L), listOf(keyScopedRevocation(keyOld, epoch))))
            assertFalse(isRevoked(cert(keyReminted, epoch = 3L), listOf(keyScopedRevocation(keyOld, epoch))))
        }
    }

    /**
     * ⚠⚠ S2: the `deviceId` conjunct in `strips()` is load-bearing. The revoker reads keys from rows a
     * device writes about itself, so an attacker can put a VICTIM's A33 key into its own `Device`
     * row / announcement. Revoking the attacker then names the victim's key under the attacker's id —
     * and must strip nothing of the victim's.
     */
    @Test
    fun aRevocationNamingAnotherDevicesKeyDoesNotStripIt() {
        val victimCert = cert(keyOld, epoch = 1L) // deviceId = "device-a", key = keyOld
        val attackerRevocation = AdmissionRevocation(
            ownerId = ownerId,
            deviceId = "device-attacker",
            epoch = 5L,
            revokedAt = 3_000L,
            signature = crypto.signEd25519(
                masterKey,
                AdmissionRevocation.message(ownerId, "device-attacker", 5L, 3_000L, keyOld),
            ),
            deviceA33PublicKey = keyOld,
        )
        assertFalse(
            isRevoked(victimCert, listOf(attackerRevocation)),
            "a revocation of the attacker stripped the victim whose key it copied",
        )
    }
}
