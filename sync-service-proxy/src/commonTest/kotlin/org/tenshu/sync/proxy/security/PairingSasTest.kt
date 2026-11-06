package org.tenshu.sync.proxy.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PairingSasTest {

    @Test
    fun derivesZeroPaddedSixDigitDecimal() {
        val sas = derivePairingSas(
            sharedSecret = ByteArray(32) { it.toByte() },
            publicKeyA = ByteArray(32) { (it + 100).toByte() },
            publicKeyB = ByteArray(32) { (it + 200).toByte() },
        )
        assertEquals(6, sas.length, "SAS should be 6 characters: '$sas'")
        assertTrue(sas.all { it.isDigit() }, "SAS must be all digits: '$sas'")
    }

    @Test
    fun deterministicForSameInputs() {
        val secret = ByteArray(32) { 7 }
        val a = ByteArray(32) { 3 }
        val b = ByteArray(32) { 5 }
        val sas1 = derivePairingSas(secret, a, b)
        val sas2 = derivePairingSas(secret, a, b)
        assertEquals(sas1, sas2)
    }

    @Test
    fun differsFromSwappedKeys() {
        // Swapping A and B changes the salt → different SAS. Since the
        // protocol concatenates host's pubkey first then requester's,
        // both sides of the handshake must agree on the order or the
        // SAS won't match — locking the convention here.
        val secret = ByteArray(32) { 7 }
        val a = ByteArray(32) { 3 }
        val b = ByteArray(32) { 5 }
        val sasAB = derivePairingSas(secret, a, b)
        val sasBA = derivePairingSas(secret, b, a)
        assertNotEquals(sasAB, sasBA)
    }

    @Test
    fun differsForDifferentSharedSecrets() {
        // The whole point of the SAS: an attacker substituting their own
        // ephemeral key derives a different shared secret with the host
        // than the genuine requester does, so the two SAS values diverge.
        val a = ByteArray(32) { 3 }
        val b = ByteArray(32) { 5 }
        val sasGenuine = derivePairingSas(ByteArray(32) { 7 }, a, b)
        val sasMitm = derivePairingSas(ByteArray(32) { 8 }, a, b)
        assertNotEquals(sasGenuine, sasMitm)
    }

    // ── v1 / v2 vectors ─────────────────────────────────────────────────────────────────────
    //
    // ⚠ Computed by an independent HKDF-SHA-256 (Python `hmac`/`hashlib`), not by this code, so
    // a drift in `hkdfSha256` or in the transcript encoding on ANY platform fails here. The two
    // sides of a pairing run on different platforms (desktop, Android, iOS, the web SDK); this
    // runs on every one this module tests on.

    private val vectorSecret = ByteArray(32) { it.toByte() }
    private val vectorHost = ByteArray(32) { (it + 100).toByte() }
    private val vectorJoiner = ByteArray(32) { (it + 200).toByte() }
    private val vectorSigningKey = ByteArray(32) { 0x51 }

    @Test
    fun v1Vector_isUnchanged_theOfferFlowStillUsesIt() {
        assertEquals("478934", derivePairingSas(vectorSecret, vectorHost, vectorJoiner))
    }

    @Test
    fun v2Vector() {
        // info = "tenshu/pairing/sas/v2" || be32-len-prefixed(signingKey) || lp("joiner-device")
        //        || lp(utf8("Ben's Phone")); salt = host || joiner — threat model T-P-14.
        assertEquals("638997", joinSas())
    }

    private fun joinSas(
        secret: ByteArray = vectorSecret,
        signingKey: ByteArray = vectorSigningKey,
        deviceId: String = "joiner-device",
        deviceName: String = "Ben's Phone",
    ) = deriveJoinPairingSas(
        sharedSecret = secret,
        hostPublicKey = vectorHost,
        joinerPublicKey = vectorJoiner,
        joinerSigningPublicKey = signingKey,
        joinerDeviceId = deviceId,
        joinerDeviceName = deviceName,
    )

    @Test
    fun v2_isNotV1_soAMixedPairShowsDifferentCodes() {
        // Lockstep: no version field, no fallback — a v1 device and a v2 device disagree.
        assertNotEquals(derivePairingSas(vectorSecret, vectorHost, vectorJoiner), joinSas())
    }

    @Test
    fun v2_changesWithTheSigningKey() {
        assertNotEquals(joinSas(), joinSas(signingKey = ByteArray(32) { 0x52 }))
    }

    @Test
    fun v2_changesWithTheDeviceId() {
        assertNotEquals(joinSas(), joinSas(deviceId = "joiner-devicf"))
    }

    @Test
    fun v2_changesWithTheDeviceName() {
        assertNotEquals(joinSas(), joinSas(deviceName = "Printer (trusted)"))
    }

    @Test
    fun v2_lengthPrefixing_keepsFieldBoundariesUnambiguous() {
        // Without the prefixes "ab"+"c" and "a"+"bc" would hash identically: an attacker could
        // move bytes from the name into the id and keep the code.
        assertNotEquals(
            joinSas(deviceId = "joiner-device", deviceName = "X"),
            joinSas(deviceId = "joiner-deviceX", deviceName = ""),
        )
    }

    @Test
    fun v2_stillChangesWithTheSharedSecret() {
        assertNotEquals(joinSas(), joinSas(secret = ByteArray(32) { 8 }))
    }
}
