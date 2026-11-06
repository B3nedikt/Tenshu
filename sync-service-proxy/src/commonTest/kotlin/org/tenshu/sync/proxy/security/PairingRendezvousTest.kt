package org.tenshu.sync.proxy.security

import org.tenshu.core.common.PairingWireLimits
import org.tenshu.core.common.security.createDefaultSyncCryptoManager
import org.tenshu.core.common.util.sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * F7 (`relay-metadata-minimisation.md`): what a relay can learn from a pairing request. Real
 * crypto (XChaCha20-Poly1305), because the claim under test is that the ciphertext is opaque.
 */
class PairingRendezvousTest {

    private val crypto = createDefaultSyncCryptoManager()
    private val secret = "6f1c2a7e-0b9d-4c55-9a51-2f3e4d5c6b7a" // the shape `generateId()` mints
    private val deviceId = "joiner-device-1"
    private val ecdh = ByteArray(32) { 1 }
    private val signing = ByteArray(32) { 2 }

    private fun seal(name: String, id: String = deviceId) =
        PairingRendezvous.sealJoinDeviceName(crypto, secret, id, ecdh, signing, name)

    private fun open(
        sealed: ByteArray,
        sec: String = secret,
        id: String = deviceId,
        publicKey: ByteArray = ecdh,
        signingPublicKey: ByteArray = signing,
    ) = PairingRendezvous.openJoinDeviceName(crypto, sec, id, publicKey, signingPublicKey, sealed)

    @Test
    fun joinHash_isLowerHex64_andIsNotTheSecretOrItsPlainSha256() {
        val hash = PairingRendezvous.joinSecretHash(secret)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(hash), "the relay's validator expects this shape: $hash")
        assertNotEquals(secret, hash)
        // Domain separation: not the offer/hub construction over the same bytes.
        assertNotEquals(sha256(secret.encodeToByteArray()).toHexString(), hash)
        assertEquals(hash, PairingRendezvous.joinSecretHash(secret), "deterministic: both sides derive it")
        assertNotEquals(hash, PairingRendezvous.joinSecretHash(secret + "x"))
    }

    @Test
    fun hubOfferHash_isDomainSeparated_fromThePlainSha256_andTheJoinHash() {
        val printed = ByteArray(16) { (it * 3).toByte() }
        val hash = PairingRendezvous.hubOfferHash(printed)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(hash), "the relay's hub-offer key shape: $hash")
        // T-P-17: no longer the bare SHA-256 the label secret used to be addressed by.
        assertNotEquals(sha256(printed).toHexString(), hash)
        assertNotEquals(PairingRendezvous.joinSecretHash(printed.decodeToString()), hash)
        assertEquals(hash, PairingRendezvous.hubOfferHash(printed.copyOf()), "deterministic: hub and phone agree")
        assertEquals(
            sha256("tenshu/pairing/hub/rendezvous/v1".encodeToByteArray() + byteArrayOf(0) + printed).toHexString(),
            hash,
            "the documented construction, byte for byte",
        )
    }

    @Test
    fun joinName_roundTrips_andTheCiphertextCarriesNoClearName() {
        val name = "Ben's Pixel 9 Pro"
        val sealed = seal(name)

        assertFalse(sealed.decodeToString().contains(name), "the sealed bytes must not contain the name")
        assertFalse(sealed.decodeToString().contains("Pixel"), "nor any part of it")
        assertEquals(name, open(sealed))
    }

    @Test
    fun joinName_doesNotOpen_underAnotherSecret_forAnotherDevice_orTampered() {
        val sealed = seal("Tablet")

        assertFailsWith<Throwable> { open(sealed, sec = "other-secret") }
        assertFailsWith<Throwable> { open(sealed, id = "other-device") }
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFailsWith<Throwable> { open(tampered) }
    }

    @Test
    fun joinName_doesNotOpen_underSwappedKeys() {
        // The relay reposting a genuine sealed name under keys of its own: the AAD binds both, so
        // the seal itself proves the secret-holder chose them (T-P-16).
        val sealed = seal("Tablet")

        assertFailsWith<Throwable> { open(sealed, publicKey = ByteArray(32) { 7 }) }
        assertFailsWith<Throwable> { open(sealed, signingPublicKey = ByteArray(32) { 7 }) }
        // Canonical framing: moving a byte across the key boundary is a different AAD.
        assertFailsWith<Throwable> {
            open(sealed, publicKey = ecdh + signing.copyOfRange(0, 1), signingPublicKey = signing.copyOfRange(1, 32))
        }
        assertEquals("Tablet", open(sealed))
    }

    @Test
    fun joinName_lengthIsBucketed() {
        val short = seal("A")
        val longer = seal("A much longer name, still <62")
        assertEquals(short.size, longer.size, "names in one 64-byte bucket must seal to one length")
    }

    @Test
    fun joinName_isFreshPerSeal() {
        val a = seal("Tablet")
        val b = seal("Tablet")
        assertFalse(a.contentEquals(b), "a random nonce: two posts of one name are unlinkable by ciphertext")
    }

    @Test
    fun hubName_roundTrips_andIsBoundToTheArmAndTheHub() {
        val printed = ByteArray(16) { it.toByte() }
        val ephemeral = ByteArray(32) { 7 }
        val sealed = PairingRendezvous.sealHubDeviceName(crypto, printed, ephemeral, "hub-1", "Living-room hub")

        assertFalse(sealed.decodeToString().contains("Living-room"))
        assertEquals("Living-room hub", PairingRendezvous.openHubDeviceName(crypto, printed, ephemeral, "hub-1", sealed))
        assertFailsWith<Throwable> {
            PairingRendezvous.openHubDeviceName(crypto, printed, ByteArray(32) { 8 }, "hub-1", sealed)
        }
        assertFailsWith<Throwable> {
            PairingRendezvous.openHubDeviceName(crypto, printed, ephemeral, "hub-2", sealed)
        }
        assertFailsWith<Throwable> {
            PairingRendezvous.openHubDeviceName(crypto, ByteArray(16) { 9 }, ephemeral, "hub-1", sealed)
        }
        // A join-domain key cannot open a hub-domain blob even over the same secret bytes.
        assertFailsWith<Throwable> {
            PairingRendezvous.openJoinDeviceName(crypto, printed.decodeToString(), "hub-1", ephemeral, ephemeral, sealed)
        }
    }

    // ── Name length vs the relay's cap (PairingWireLimits) ──────────────────────────────────────

    /** A UUID device id: the shape `generateId()` mints, and the budget the KDoc quotes. */
    private val uuidDeviceId = "0b6c8a1e-3d2f-4a5b-9c7d-1e2f3a4b5c6d"

    @Test
    fun anOverlongMultiByteJoinName_isFittedOnACodePointBoundary_andSealsUnderTheRelayCap() {
        // 400 × "🎉" (4 UTF-8 bytes each) = 1600 bytes — the case that used to be a relay 400.
        val requested = "🎉".repeat(400)
        val fitted = PairingRendezvous.fitJoinDeviceName(secret, uuidDeviceId, ecdh, signing, requested)

        val budget = 766 // 1024 − 40 (nonce+tag) − 4 (AAD frame) − 180 (join AAD) → 768-byte bucket − 2
        assertTrue(fitted.encodeToByteArray().size <= budget, "fitted: ${fitted.encodeToByteArray().size}")
        assertTrue(requested.startsWith(fitted), "a prefix, so the user still recognises it")
        assertEquals("🎉".repeat(budget / 4), fitted, "whole code points only: 191 emoji, never a broken one")
        assertFalse(fitted.contains('\uFFFD'), "no replacement character from a split sequence")

        val sealed = PairingRendezvous.sealJoinDeviceName(crypto, secret, uuidDeviceId, ecdh, signing, fitted)
        assertTrue(
            sealed.size <= PairingWireLimits.MAX_SEALED_DEVICE_NAME_BYTES,
            "the relay's cap is the one constant both sides read: ${sealed.size}",
        )
        assertEquals(fitted, PairingRendezvous.openJoinDeviceName(crypto, secret, uuidDeviceId, ecdh, signing, sealed))
    }

    @Test
    fun theFittedJoinName_fillsTheLastBucket_soTheBudgetIsTightNotJustSafe() {
        val fitted = PairingRendezvous.fitJoinDeviceName(secret, uuidDeviceId, ecdh, signing, "x".repeat(2000))
        val sealed = PairingRendezvous.sealJoinDeviceName(crypto, secret, uuidDeviceId, ecdh, signing, fitted)
        assertTrue(
            sealed.size > PairingWireLimits.MAX_SEALED_DEVICE_NAME_BYTES - PairingWireLimits.SEALED_DEVICE_NAME_PAD_BLOCK,
            "one more bucket would cross the cap: ${sealed.size}",
        )
    }

    @Test
    fun aLongDeviceId_shrinksTheBudget_becauseTheAadRidesInsideTheCiphertext() {
        val longId = "d".repeat(256) // the relay's MAX_PAIRING_REQUEST_FIELD_LENGTH
        val fitted = PairingRendezvous.fitJoinDeviceName(secret, longId, ecdh, signing, "n".repeat(2000))
        val sealed = PairingRendezvous.sealJoinDeviceName(crypto, secret, longId, ecdh, signing, fitted)
        assertTrue(sealed.size <= PairingWireLimits.MAX_SEALED_DEVICE_NAME_BYTES, "${sealed.size}")
        assertTrue(fitted.length < 766)
    }

    @Test
    fun aShortName_isUntouched() {
        assertEquals("Ben's Pixel 9 Pro", PairingRendezvous.fitJoinDeviceName(secret, uuidDeviceId, ecdh, signing, "Ben's Pixel 9 Pro"))
    }

    @Test
    fun anUnfittedName_isRefusedBySealing_notSentForTheRelayTo400() {
        assertFailsWith<IllegalArgumentException> {
            PairingRendezvous.sealJoinDeviceName(crypto, secret, uuidDeviceId, ecdh, signing, "🎉".repeat(400))
        }
        assertFailsWith<IllegalArgumentException> {
            PairingRendezvous.sealHubDeviceName(crypto, ByteArray(16), ByteArray(32), uuidDeviceId, "🎉".repeat(400))
        }
    }

    @Test
    fun anOverlongHubName_isFittedAndSealsUnderTheRelayCap() {
        val ephemeral = ByteArray(32) { 7 }
        val fitted = PairingRendezvous.fitHubDeviceName(ephemeral, uuidDeviceId, "ü".repeat(1000)) // 2 bytes each
        assertTrue(fitted.isNotEmpty() && "ü".repeat(1000).startsWith(fitted))
        val sealed = PairingRendezvous.sealHubDeviceName(crypto, ByteArray(16), ephemeral, uuidDeviceId, fitted)
        assertTrue(sealed.size <= PairingWireLimits.MAX_SEALED_DEVICE_NAME_BYTES, "${sealed.size}")
    }

    @Test
    fun truncateUtf8_neverSplitsASequence() {
        // "aé🎉" = 1 + 2 + 4 bytes; every cut lands on a boundary at or below the budget.
        val s = "aé🎉"
        assertEquals("", PairingRendezvous.truncateUtf8(s, 0))
        assertEquals("a", PairingRendezvous.truncateUtf8(s, 1))
        assertEquals("a", PairingRendezvous.truncateUtf8(s, 2))
        assertEquals("aé", PairingRendezvous.truncateUtf8(s, 3))
        assertEquals("aé", PairingRendezvous.truncateUtf8(s, 6))
        assertEquals(s, PairingRendezvous.truncateUtf8(s, 7))
    }
}
