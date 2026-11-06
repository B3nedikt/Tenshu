package org.tenshu.sync.common

import org.koin.dsl.koinApplication
import org.tenshu.core.common.security.Ed25519Backend
import org.tenshu.core.common.security.cryptoBackendsModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §7d (`admission-revocation-names-the-capability.md`): every signed admission / membership
 * message is length-prefixed — `LP(tag) ‖ LP(field₁) ‖ … ‖ LP(fieldₙ)` — so a field boundary can
 * never move without changing the bytes.
 *
 * Two kinds of pin, both in `commonTest` so they run on **every** target that compiles it — JVM
 * (BouncyCastle) in the gate, iOS (libsodium) under `iosTestCheck`:
 *
 *  * **message vectors** — the exact bytes each builder produces. Client and relay must agree
 *    byte-for-byte, so a silent encoding change is a wire break, and this is where it shows.
 *  * **signature vectors** — the real platform Ed25519 backend (`cryptoBackendsModule()`, never the
 *    `BootstrapSyncCryptoManager` stub) over those bytes. Generated once on the JVM; the iOS run
 *    must reproduce them exactly (RFC 8032 signatures are deterministic).
 */
class SignedMessageEncodingTest {

    private val ed25519: Ed25519Backend =
        koinApplication { modules(cryptoBackendsModule()) }.koin.get()

    private val seed = hex("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")
    private val key = ByteArray(32) { (0xA0 + it).toByte() }
    private val pub get() = ed25519.derivePublicKey(seed)

    // ---- the encoding itself ----

    /** S4: `|x|` is a BYTE length — "é" is one character and two UTF-8 bytes. */
    @Test
    fun aLengthPrefixCountsUtf8BytesNotCharacters() {
        assertEquals(
            "00000001" + "74" + "00000002" + "c3a9",
            lengthPrefixedSignedMessage("t", signedField("é")).toHex(),
        )
        assertEquals(
            "00000003" + "e282ac" + "00000004" + "f09f9880",
            lengthPrefixedSignedMessage("€", signedField("😀")).toHex(),
        )
    }

    @Test
    fun theEncodingPrefixesTheTagAndEveryField() {
        assertEquals(
            "00000003" + "746167" + "00000000" + "00000002" + "0102",
            lengthPrefixedSignedMessage("tag", ByteArray(0), byteArrayOf(1, 2)).toHex(),
        )
    }

    // ---- message vectors ----

    @Test
    fun admissionCertMessage_vector() = assertEquals(
        CERT_HEX,
        AdmissionCapabilityCert.message("owner", key, "device-a", 7L).toHex(),
    )

    @Test
    fun admissionRevocationMessage_vector() = assertEquals(
        REVOKE_HEX,
        AdmissionRevocation.message("owner", "device-a", 7L, 9L, key).toHex(),
    )

    @Test
    fun admissionAnnouncementMessage_vector() = assertEquals(
        ANNOUNCE_HEX,
        admissionKeyAnnouncementMessage("device-a", key).toHex(),
    )

    @Test
    fun membershipCapabilityMessage_vector() = assertEquals(
        MEMBER_CAP_HEX,
        MembershipCapability.message("realm", key, 0L, 9L).toHex(),
    )

    @Test
    fun memberDeviceBindingMessage_vector() = assertEquals(
        MEMBER_BIND_HEX,
        MemberDeviceBinding.message("realm", "device-a", key, 9L).toHex(),
    )

    @Test
    fun membershipRevocationMessage_vector() = assertEquals(
        MEMBER_REVOKE_HEX,
        MembershipRevocation.message("realm", key, 0L, 9L).toHex(),
    )

    // ---- signature vectors (real backend; JVM-generated, iOS must reproduce) ----

    @Test
    fun theSignerPublicKeyMatchesTheJvmVector() = assertEquals(PUB_HEX, pub.toHex())

    @Test
    fun signaturesOverEveryMessageMatchTheJvmVectorsAndVerify() {
        val cases = listOf(
            "cert" to (AdmissionCapabilityCert.message("owner", key, "device-a", 7L) to CERT_SIG),
            "revoke" to (AdmissionRevocation.message("owner", "device-a", 7L, 9L, key) to REVOKE_SIG),
            "announce" to (admissionKeyAnnouncementMessage("device-a", key) to ANNOUNCE_SIG),
            "member-cap" to (MembershipCapability.message("realm", key, 0L, 9L) to MEMBER_CAP_SIG),
            "member-bind" to (MemberDeviceBinding.message("realm", "device-a", key, 9L) to MEMBER_BIND_SIG),
            "member-revoke" to (MembershipRevocation.message("realm", key, 0L, 9L) to MEMBER_REVOKE_SIG),
        )
        for ((name, case) in cases) {
            val (message, expected) = case
            val signature = ed25519.sign(seed, message)
            assertEquals(expected, signature.toHex(), "$name: signature drifted from the JVM vector")
            assertTrue(ed25519.verify(pub, message, signature), "$name: own signature must verify")
        }
    }

    // ---- shifted boundaries no longer verify ----

    /**
     * ⭐ The property §7d exists for. Move bytes from one variable-length field into its neighbour:
     * under the old bare concatenation the bytes were **identical** (asserted below, so this test
     * cannot pass vacuously), so a signature for one tuple was a signature for the other. Now the
     * bytes differ and the signature does not carry over.
     */
    @Test
    fun aShiftedFieldBoundaryNoLongerVerifies() {
        // ASCII-only key, so moving its first byte into a String field is byte-exact.
        val k = ByteArray(32) { (0x41 + it % 26).toByte() }
        val k0 = k[0].toInt().toChar().toString() // "A"
        val k1 = k.copyOfRange(1, 32)

        // name → (bare concatenation of each tuple's shifted fields, the two signed messages)
        val shifts = listOf(
            Triple(
                "admission revocation: ownerId|deviceId",
                ("ab".encodeToByteArray() + "cd".encodeToByteArray()) to ("abc".encodeToByteArray() + "d".encodeToByteArray()),
                AdmissionRevocation.message("ab", "cd", 1L, 2L, k) to AdmissionRevocation.message("abc", "d", 1L, 2L, k),
            ),
            Triple(
                "admission cert: ownerId|deviceA33Pub",
                ("ab".encodeToByteArray() + k) to ("ab$k0".encodeToByteArray() + k1),
                AdmissionCapabilityCert.message("ab", k, "d", 1L) to AdmissionCapabilityCert.message("ab$k0", k1, "d", 1L),
            ),
            Triple(
                "announcement: deviceId|a33Pub",
                ("d".encodeToByteArray() + k) to ("d$k0".encodeToByteArray() + k1),
                admissionKeyAnnouncementMessage("d", k) to admissionKeyAnnouncementMessage("d$k0", k1),
            ),
            Triple(
                "member binding: realmId|deviceId",
                ("re".encodeToByteArray() + "alm".encodeToByteArray()) to ("real".encodeToByteArray() + "m".encodeToByteArray()),
                MemberDeviceBinding.message("re", "alm", k, 1L) to MemberDeviceBinding.message("real", "m", k, 1L),
            ),
            Triple(
                "member capability: realmId|authorityPub",
                ("r".encodeToByteArray() + k) to ("r$k0".encodeToByteArray() + k1),
                MembershipCapability.message("r", k, 0L, 1L) to MembershipCapability.message("r$k0", k1, 0L, 1L),
            ),
            Triple(
                "member revocation: realmId|authorityPub",
                ("r".encodeToByteArray() + k) to ("r$k0".encodeToByteArray() + k1),
                MembershipRevocation.message("r", k, 0L, 1L) to MembershipRevocation.message("r$k0", k1, 0L, 1L),
            ),
        )
        for ((name, bare, signed) in shifts) {
            // Guard the guard: without this the test would pass on any two unrelated tuples.
            assertTrue(
                bare.first.contentEquals(bare.second),
                "$name: precondition — unprefixed, the two tuples must concatenate identically",
            )
            val (original, shifted) = signed
            assertFalse(original.contentEquals(shifted), "$name: the shifted tuple produced the same bytes")
            val signature = ed25519.sign(seed, original)
            assertTrue(ed25519.verify(pub, original, signature), "$name: precondition — the original verifies")
            assertFalse(
                ed25519.verify(pub, shifted, signature),
                "$name: a signature verified over a tuple with a moved field boundary",
            )
        }
    }

    /**
     * The old revocation pair, now as one shape: the attacker-chosen `deviceId` that made a v1
     * (keyless) message byte-identical to a v2 (keyed) message under a shared tag. With prefixes a
     * deviceId that swallows the trailing fields is simply a longer deviceId.
     */
    @Test
    fun anAttackerChosenDeviceIdCannotAbsorbTheTrailingFields() {
        val tail = signedField(5L) + signedField(0x0B0BL)
        val crafted = ("d".encodeToByteArray() + tail + ByteArray(16) { 0x41 }).decodeToString()
        val honest = AdmissionRevocation.message("owner", "d", 5L, 0x0B0BL, ByteArray(16) { 0x41 } + tail)
        val forged = AdmissionRevocation.message("owner", crafted, 5L, 0x0B0BL, ByteArray(0))
        assertFalse(honest.contentEquals(forged))
    }

    // ---- tags ----

    @Test
    fun everyTagIsDistinctAndNoRetiredTagIsReused() {
        val live = listOf(
            AdmissionCapabilityCert.DOMAIN_TAG,
            AdmissionRevocation.DOMAIN_TAG,
            ADMISSION_ANNOUNCE_TAG,
            MembershipCapability.DOMAIN_TAG,
            MemberDeviceBinding.DOMAIN_TAG,
            MembershipRevocation.DOMAIN_TAG,
            PAIRING_DELEGATION_TAG,
            REALM_BOOTSTRAP_TAG,
            RECOVERY_CLAIM_TAG,
            OFFER_RESPONSE_TAG,
            HLC_TIP_TAG,
        )
        assertEquals(live.size, live.toSet().size, "two message shapes share a domain tag: $live")
        val retired = setOf(
            "tenshu-admit-cap-v1",
            "tenshu-admit-revoke-v1",
            "tenshu-admit-revoke-v2",
            "tenshu-admit-announce-v1",
            "tenshu-member-cap-v1",
            "tenshu-member-bind-v1",
            "tenshu-member-revoke-v1",
        )
        assertTrue(live.none { it in retired }, "a retired tag names a different byte layout; never reuse one")
    }

    /** Generated on the JVM (BouncyCastle), 2026-10-28. ⚠ Regenerate only with a deliberate wire change. */
    private companion object {
        const val PUB_HEX = "79b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad049664"
        const val CERT_HEX =
            "0000001374656e7368752d61646d69742d6361702d7632000000056f776e6572" +
            "00000020a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babb" +
            "bcbdbebf000000086465766963652d61000000080000000000000007"
        const val REVOKE_HEX =
            "0000001674656e7368752d61646d69742d7265766f6b652d7633000000056f77" +
            "6e6572000000086465766963652d610000000800000000000000070000000800" +
            "0000000000000900000020a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4" +
            "b5b6b7b8b9babbbcbdbebf"
        const val ANNOUNCE_HEX =
            "0000001874656e7368752d61646d69742d616e6e6f756e63652d763200000008" +
            "6465766963652d6100000020a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3" +
            "b4b5b6b7b8b9babbbcbdbebf"
        const val MEMBER_CAP_HEX =
            "0000001474656e7368752d6d656d6265722d6361702d7632000000057265616c" +
            "6d00000020a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9ba" +
            "bbbcbdbebf000000080000000000000000000000080000000000000009"
        const val MEMBER_BIND_HEX =
            "0000001574656e7368752d6d656d6265722d62696e642d763200000005726561" +
            "6c6d000000086465766963652d6100000020a0a1a2a3a4a5a6a7a8a9aaabacad" +
            "aeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf000000080000000000000009"
        const val MEMBER_REVOKE_HEX =
            "0000001774656e7368752d6d656d6265722d7265766f6b652d76320000000572" +
            "65616c6d00000020a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7" +
            "b8b9babbbcbdbebf000000080000000000000000000000080000000000000009"
        const val CERT_SIG =
            "ad282d5a5cfb6a196ead0b84a8cfd0a6c38be2138d3ffbb3250fc0a59bc1b43f" +
            "defaf4e5f92b9904fd2133ed58a3fa655299cf5f2fe64f3e4b720bd0005b050b"
        const val REVOKE_SIG =
            "21864ba36079fd1225244548001d4e18c52e6d4cc1c7636153f2ed2aba9b313c" +
            "979fc32102e7c3378e4abe5b88aa481ac38f501384a93a3ee9d47a07e88d7c0d"
        const val ANNOUNCE_SIG =
            "fb15d21ea90452598b1dc08ba57e57920137325a2af85edf6d2d115c71fca500" +
            "14499697ed89e39d8d7db4d9fe3ad141a92a8dc93ae7b09410124fbeb6682704"
        const val MEMBER_CAP_SIG =
            "de74e30ee1703531bf999389781ee89d9a76f63d786604cb2e0966d668eed852" +
            "023401449d65182d4375ae3a79528419143c1e3f46129a3775730633d5c7720e"
        const val MEMBER_BIND_SIG =
            "48dcffacae18853257f53559b238d0fb3d9a102bffdac8c65f4fd880ab6a64cd" +
            "cf68557fab6f92d285705079db89ccb7aa14836e13aca75069414cb72c469701"
        const val MEMBER_REVOKE_SIG =
            "2900785547f217443bbcfb87b11bc11aa35a4baac7bcc851b48db6157dd0b839" +
            "c47f427b8d1053a119c1de55659cd08a8ccd997d6e0621e788c0b72b6c094c02"

        fun hex(s: String): ByteArray =
            ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

        fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}
