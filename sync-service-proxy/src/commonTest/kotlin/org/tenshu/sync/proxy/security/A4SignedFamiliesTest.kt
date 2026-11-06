package org.tenshu.sync.proxy.security

import org.koin.dsl.koinApplication
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.security.Ed25519Backend
import org.tenshu.core.common.security.cryptoBackendsModule
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.sync.common.admissionKeyAnnouncementMessage
import org.tenshu.sync.common.hlcTipCanonicalMessage
import org.tenshu.sync.common.offerResponseMessage
import org.tenshu.sync.common.pairingDelegationMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every kind of message one device key (A4) signs, side by side: the tagged ones from
 * `SignedMessageEncoding.kt`, the HLC tip, the hub offer (ASCII-prefixed `tenshu/pairing/hub-offer/v1`,
 * not LP — safe because no LP message's first byte is printable) and a JWS signing input (`eyJ…`).
 * A signature over any one must verify as none of the others. Real platform Ed25519.
 */
class A4SignedFamiliesTest {

    private val ed25519: Ed25519Backend =
        koinApplication { modules(cryptoBackendsModule()) }.koin.get()
    private val seed = ByteArray(32) { (it + 3).toByte() }
    private val pub get() = ed25519.derivePublicKey(seed)
    private val k = ByteArray(32) { (0x30 + it).toByte() }
    private val context = TestProxy(schemaRegistry = createCoreTestRegistry()).tContext

    private fun tip() = hlcTipCanonicalMessage(
        "owner",
        "device-a",
        HybridLogicalClock.create(context, physicalTime = 1_700_000_000_000L, logicalCounter = 3L, deviceId = "device-a"),
    )

    private fun families(): Map<String, ByteArray> = mapOf(
        "hlc-tip" to tip(),
        "hub-offer" to hubOfferMessage(k, "device-a", "Hub"),
        "jws" to "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJkZXZpY2UtYSJ9".encodeToByteArray(),
        "offer-response" to offerResponseMessage("approved", "owner", k, null),
        "announce" to admissionKeyAnnouncementMessage("device-a", k),
        "delegation" to pairingDelegationMessage(k, "device-a", 3L),
    )

    @Test
    fun aSignatureOverOneA4FamilyNeverVerifiesAsAnother() {
        val all = families()
        for ((name, message) in all) {
            val signature = ed25519.sign(seed, message)
            assertTrue(ed25519.verify(pub, message, signature), "$name: own signature must verify")
            for ((other, otherMessage) in all) {
                if (other == name) continue
                assertFalse(ed25519.verify(pub, otherMessage, signature), "a $name signature verified as a $other")
            }
        }
    }

    /** The structural reason: every LP message starts 0x00; the hub offer and a JWS input start printable. */
    @Test
    fun onlyTheTaggedFamiliesStartWithAZeroByte() {
        for ((name, message) in families()) {
            val zeroFirst = message[0] == 0.toByte()
            assertEquals(name !in setOf("hub-offer", "jws"), zeroFirst, "$name's first byte")
        }
    }

    /**
     * The HLC tip's bytes and real-Ed25519 signature. ⚠ JVM only: this module's iOS tests are
     * disabled, so the iOS reproduction of the tip vector lives in `:sync-service-common`'s
     * `CrossFamilySignatureTest` (which runs under `iosSimulatorArm64Test`).
     */
    @Test
    fun theHlcTipMatchesTheJvmVectors() {
        assertEquals(TIP_HEX, tip().toHex())
        assertEquals(TIP_SIG, ed25519.sign(seed, tip()).toHex())
    }

    /** Generated on the JVM (BouncyCastle), 2026-10-28. */
    private companion object {
        const val TIP_HEX =
            "0000001174656e7368752d686c632d7469702d7631000000056f776e65720000" +
            "00086465766963652d61000000080000018bcfe5680000000008000000000000" +
            "0003000000086465766963652d61"
        const val TIP_SIG =
            "2f17b5e6e9c8ecedf409d9619d827b9d107e88bc2dd1939985a88da0692e7b32" +
            "64e9e693fb9b9b959db1be88b224ec8b539527ee5760aa1836ddc26a5bd79603"

        fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}
