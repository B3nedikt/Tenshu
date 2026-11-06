package org.tenshu.sync.common

import org.koin.dsl.koinApplication
import org.tenshu.core.common.security.Ed25519Backend
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.security.cryptoBackendsModule
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.core.common.util.toByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B1 (`admission-revocation-names-the-capability.md` §7d.3): A1, an admitter's A33, a shared-realm
 * master and a device's A4 each sign **several** kinds of message. Before the pairing family was
 * tagged, a signature over one kind could be a signature over another — this pins that it cannot.
 *
 * Real platform Ed25519 (`cryptoBackendsModule()`), so the vectors below run on BouncyCastle in the
 * gate and must be reproduced by libsodium under `iosTestCheck`.
 */
class CrossFamilySignatureTest {

    private val ed25519: Ed25519Backend =
        koinApplication { modules(cryptoBackendsModule()) }.koin.get()

    private val seed = ByteArray(32) { (it + 1).toByte() }
    private val pub get() = ed25519.derivePublicKey(seed)
    private val k = ByteArray(32) { (0xA0 + it).toByte() }

    private val context = TestProxy(schemaRegistry = createCoreTestRegistry()).tContext

    /** An A4-signed HLC tip (`hlcTipCanonicalMessage`, tag `tenshu-hlc-tip-v1`). */
    private fun tip() = hlcTipCanonicalMessage(
        "owner",
        "device-a",
        HybridLogicalClock.create(context, physicalTime = 1_700_000_000_000L, logicalCounter = 3L, deviceId = "device-a"),
    )

    /** One sample of every message one of these keys signs, keyed by family. */
    private fun everyFamily(): Map<String, ByteArray> = mapOf(
        "cert" to AdmissionCapabilityCert.message("owner", k, "device-a", 7L),
        "revoke" to AdmissionRevocation.message("owner", "device-a", 7L, 9L, k),
        "announce" to admissionKeyAnnouncementMessage("device-a", k),
        "member-cap" to MembershipCapability.message("owner", k, 0L, 7L),
        "member-bind" to MemberDeviceBinding.message("owner", "device-a", k, 7L),
        "member-revoke" to MembershipRevocation.message("owner", k, 0L, 7L),
        "delegation" to pairingDelegationMessage(k, "device-a", 7L),
        "bootstrap" to realmBootstrapMessage("owner", k, k, "device-a"),
        "recovery-claim" to recoveryClaimMessage(k, "device-a", 7L.toByteArray()),
        "offer-response" to offerResponseMessage("approved", "owner", k, 7L.toByteArray()),
        "hlc-tip" to tip(),
    )

    @Test
    fun aSignatureOverOneFamilyNeverVerifiesAsAnother() {
        val families = everyFamily()
        for ((signedName, signed) in families) {
            val signature = ed25519.sign(seed, signed)
            assertTrue(ed25519.verify(pub, signed, signature), "$signedName: own signature must verify")
            for ((otherName, other) in families) {
                if (otherName == signedName) continue
                assertFalse(signed.contentEquals(other), "$signedName and $otherName produced the same bytes")
                assertFalse(
                    ed25519.verify(pub, other, signature),
                    "a $signedName signature verified as a $otherName",
                )
            }
        }
    }

    /** Every family opens with LP(its own tag) — the structural reason the test above holds. */
    @Test
    fun everyFamilyStartsWithItsOwnLengthPrefixedTag() {
        val tags = mapOf(
            "cert" to AdmissionCapabilityCert.DOMAIN_TAG,
            "revoke" to AdmissionRevocation.DOMAIN_TAG,
            "announce" to ADMISSION_ANNOUNCE_TAG,
            "member-cap" to MembershipCapability.DOMAIN_TAG,
            "member-bind" to MemberDeviceBinding.DOMAIN_TAG,
            "member-revoke" to MembershipRevocation.DOMAIN_TAG,
            "delegation" to PAIRING_DELEGATION_TAG,
            "bootstrap" to REALM_BOOTSTRAP_TAG,
            "recovery-claim" to RECOVERY_CLAIM_TAG,
            "offer-response" to OFFER_RESPONSE_TAG,
            "hlc-tip" to HLC_TIP_TAG,
        )
        for ((name, message) in everyFamily()) {
            val tag = tags.getValue(name).encodeToByteArray()
            val head = tag.size.toByteArray() + tag
            assertTrue(message.copyOfRange(0, head.size).contentEquals(head), "$name does not start with LP(its tag)")
        }
    }

    /**
     * ⚠⚠ The B1 attack, reverse direction, in its original form: split an A1-signed root cert C as
     * `K = C[0 : |C|−12]`, `deviceId = "\u0000\u0000\u0000\b"`, `epoch = C`'s last 8 bytes, and the old
     * bare delegation `K ‖ deviceId ‖ epoch` WAS C — so the cert's signature admitted a junk device
     * on the unauthenticated `/sync/admit-device`. The precondition re-builds the old encoding to
     * show the split is real; the new builder refuses that K outright, and no 32-byte K reproduces C.
     */
    @Test
    fun aCertCanNoLongerBeReadAsADelegation() {
        val cert = AdmissionCapabilityCert.message("owner", k, "device-a", 7L)
        val splitKey = cert.copyOfRange(0, cert.size - 12)
        val splitId = cert.copyOfRange(cert.size - 12, cert.size - 8).decodeToString()
        val splitEpoch = cert.copyOfRange(cert.size - 8, cert.size)
        assertEquals("\u0000\u0000\u0000\b", splitId, "precondition: the cert ends LP(epoch)")
        assertTrue(
            (splitKey + splitId.encodeToByteArray() + splitEpoch).contentEquals(cert),
            "precondition: under the old bare encoding this tuple IS the cert",
        )

        assertFailsWith<IllegalArgumentException>("a delegation over a non-Ed25519-sized key must be refused") {
            pairingDelegationMessage(splitKey, splitId, 7L)
        }
        val signature = ed25519.sign(seed, cert)
        val any32 = pairingDelegationMessage(splitKey.copyOf(32), splitId, 7L)
        assertFalse(ed25519.verify(pub, any32, signature), "a cert signature verified as a delegation")
    }

    /** The HLC tip's exact bytes (the other B1 messages are pinned through their signatures). */
    @Test
    fun theHlcTipBytesMatchTheVector() {
        assertEquals(TIP_HEX, tip().toHex())
    }

    // ---- vectors for the B1 family (JVM-generated; iOS must reproduce) ----

    @Test
    fun theB1FamilyMatchesTheJvmVectors() {
        val cases = listOf(
            Triple("delegation", pairingDelegationMessage(k, "device-a", 7L), DELEGATION),
            Triple("bootstrap", realmBootstrapMessage("owner", k, k, "device-a"), BOOTSTRAP),
            Triple("recovery-claim", recoveryClaimMessage(k, "device-a", byteArrayOf(1, 2, 3)), RECOVERY),
            Triple("offer-response", offerResponseMessage("denied", "hash", k, null), OFFER),
            Triple("hlc-tip", tip(), TIP_SIG),
        )
        for ((name, message, expectedSig) in cases) {
            assertEquals(expectedSig, ed25519.sign(seed, message).toHex(), "$name: signature drifted from the JVM vector")
        }
    }

    /** Generated on the JVM (BouncyCastle), 2026-10-28. */
    private companion object {
        const val TIP_HEX =
            "0000001174656e7368752d686c632d7469702d7631000000056f776e65720000" +
            "00086465766963652d61000000080000018bcfe5680000000008000000000000" +
            "0003000000086465766963652d61"
        const val TIP_SIG =
            "4146445150d6aa63e0c6ace69498e62bd5538bb29aa7e899441adce3c7769c77" +
            "1df7db68a570759da77d9b017c70bd6eecdbf6bc80736c3f08b406e4c3483509"
        const val DELEGATION =
            "8f2e6543b831e8a1e9db4d61b8cb2c01929eb50be06900fa3bde49ab3b26f2e1" +
            "0d4a097c3a799a20d4755789a8e64faaba7ad99d43eead58a6038fab721cd30e"
        const val BOOTSTRAP =
            "eebb597be6cd4ddc98a8320e6d2de235f45bf58162875952ff8ccd13f16df381" +
            "edff6e67d45d577907e0efbacb50f7522317681f15fa17c5d88ba45963155e0b"
        const val RECOVERY =
            "6c77b3d03c363eb7bc54507e547136d8e8ff151d1b256f6a15ed8c0de0e6808b" +
            "e1c3255149988a16aa723b6417cff22ab392a497043e2fcb54a06827b207d304"
        const val OFFER =
            "02752577f4fc460646f4cec7623a916efdfc93cee2a0f94ac0a9539f6609674a" +
            "c9dd1c4573412167bf0f62f4e5303b13224840cc4b44af3a8b861c57447bfb0b"

        fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}
