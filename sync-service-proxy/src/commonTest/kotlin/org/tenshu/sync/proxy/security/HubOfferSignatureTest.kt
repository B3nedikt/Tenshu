package org.tenshu.sync.proxy.security

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The canonical hub-offer message (hub proposal §11.3): the bytes the hub's printed
 * static key signs, verified phone-side. The same long-lived key signs this across
 * years of re-arms, so the encoding must be deterministic, domain-separated from
 * everything else an A4 key signs, and **unambiguous** — no field-boundary confusion
 * between the variable-length `deviceId` and `deviceName`. (End-to-end signature
 * create/verify incl. the tampered/wrong-key rejection runs with real Ed25519 in
 * `HubPairingSystemTest`.)
 */
class HubOfferSignatureTest {

    private val ephemeralKey = ByteArray(32) { it.toByte() }

    @Test
    fun message_isDeterministic() {
        assertContentEquals(
            hubOfferMessage(ephemeralKey, "hub-1", "Living Room Hub"),
            hubOfferMessage(ephemeralKey, "hub-1", "Living Room Hub"),
        )
    }

    @Test
    fun message_isUnambiguousAcrossFieldBoundaries() {
        // Raw concatenation would make ("ab","c") and ("a","bc") sign identical bytes —
        // the length prefixes must keep them distinct.
        assertFalse(
            hubOfferMessage(ephemeralKey, "ab", "c")
                .contentEquals(hubOfferMessage(ephemeralKey, "a", "bc")),
        )
    }

    @Test
    fun message_bindsTheEphemeralKey() {
        val other = ByteArray(32) { (it + 1).toByte() }
        assertFalse(
            hubOfferMessage(ephemeralKey, "hub-1", "Hub")
                .contentEquals(hubOfferMessage(other, "hub-1", "Hub")),
        )
    }

    @Test
    fun message_carriesTheDomainPrefix() {
        // Domain separation from the other A4-signed messages (offer-responses start
        // with their status string, JWTs with base64 JSON, delegations with raw keys).
        val message = hubOfferMessage(ephemeralKey, "hub-1", "Hub")
        val prefix = "tenshu/pairing/hub-offer/v1".encodeToByteArray()
        assertTrue(message.size > prefix.size)
        assertContentEquals(prefix, message.copyOfRange(0, prefix.size))
    }
}
