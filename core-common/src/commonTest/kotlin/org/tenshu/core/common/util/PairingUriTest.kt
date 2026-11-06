package org.tenshu.core.common.util

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pinned vectors for the canonical role-host pairing URIs
 * (`docs/proposals/sync-pairing-inverted-direction.md` §8 "Test vectors"):
 * round-trips for both hosts, legacy `tenshu://pair` rejection (pinned to the
 * exact string shape of the retired `SyncManagerImpl.getPairingString()` — the
 * rejection is deliberate, not accidental), and unknown-host rejection (the
 * "update your app" path).
 */
class PairingUriTest {

    private val publicKey = ByteArray(32) { it.toByte() }
    private val signingKey = ByteArray(32) { (it + 200).toByte() }
    private val secret = "test-pairing-secret".encodeToByteArray()

    // ── join round-trip ──────────────────────────────────────────────────────

    @Test
    fun joinUri_roundTrips() {
        val uri = PairingUri.joinUri(
            ownerId = "owner-abc123",
            hostDeviceId = "device-xyz789",
            hostPublicKey = publicKey,
            pairingSecret = secret,
        )
        assertTrue(uri.startsWith("tenshu://join?"), "join URI must live on the join host: $uri")

        val parsed = assertIs<ParsedPairingUri.Join>(PairingUri.parse(uri))
        assertEquals("owner-abc123", parsed.ownerId)
        assertEquals("device-xyz789", parsed.hostDeviceId)
        assertContentEquals(publicKey, parsed.hostPublicKey)
        assertContentEquals(secret, parsed.pairingSecret)
    }

    @Test
    fun joinUri_pinnedShape() {
        // The exact wire form is a cross-platform contract (Kotlin + TS parse
        // it); pin it so an accidental encoding change fails loudly.
        val uri = PairingUri.joinUri(
            ownerId = "o1",
            hostDeviceId = "d1",
            hostPublicKey = ByteArray(32),
            pairingSecret = "s".encodeToByteArray(),
        )
        assertEquals(
            "tenshu://join?o=o1&d=MQYQ&k=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&s=OM",
            uri,
        )
    }

    @Test
    fun joinUri_survivesWhitespaceAndCaseInsensitiveBase32() {
        val uri = PairingUri.joinUri("o", "device", publicKey, secret)
        val mangled = "  " + uri.replaceAfter("?", uri.substringAfter("?").lowercase()) + "\n"
        val parsed = assertIs<ParsedPairingUri.Join>(PairingUri.parse(mangled))
        assertContentEquals(publicKey, parsed.hostPublicKey)
        assertContentEquals(secret, parsed.pairingSecret)
        assertEquals("device", parsed.hostDeviceId)
    }

    @Test
    fun joinUri_rejectsEmptyKeyAndSecretAtGeneration() {
        assertFailsWith<IllegalArgumentException> {
            PairingUri.joinUri("o", "d", ByteArray(0), secret)
        }
        assertFailsWith<IllegalArgumentException> {
            PairingUri.joinUri("o", "d", publicKey, ByteArray(0))
        }
    }

    @Test
    fun joinUri_stubCryptoShortKeysRoundTrip() {
        // Test stubs (`BootstrapSyncCryptoManager`) mint short placeholder
        // keys; the URI layer is transport and must not enforce key sizes.
        val stubKey = "dummy".encodeToByteArray()
        val uri = PairingUri.joinUri("o", "d", stubKey, secret)
        val parsed = assertIs<ParsedPairingUri.Join>(PairingUri.parse(uri))
        assertContentEquals(stubKey, parsed.hostPublicKey)
    }

    // ── offer round-trip ─────────────────────────────────────────────────────

    @Test
    fun offerUri_roundTrips() {
        val uri = PairingUri.offerUri(
            offererPublicKey = publicKey,
            offererSigningKey = signingKey,
            offererDeviceId = "offerer-device-1",
            pairingSecret = secret,
            deviceName = "Ben's Laptop",
        )
        assertTrue(uri.startsWith("tenshu://offer?"), "offer URI must live on the offer host: $uri")

        val parsed = assertIs<ParsedPairingUri.Offer>(PairingUri.parse(uri))
        assertContentEquals(publicKey, parsed.offererPublicKey)
        assertContentEquals(signingKey, parsed.offererSigningKey)
        assertEquals("offerer-device-1", parsed.offererDeviceId)
        assertContentEquals(secret, parsed.pairingSecret)
        assertEquals("Ben's Laptop", parsed.deviceName)
    }

    @Test
    fun offerUri_deviceNameSurvivesUnicodeAndReservedChars() {
        for (name in listOf("Büro-PC (Erdgeschoss)", "写真&videos=fun", "a+b c")) {
            val uri = PairingUri.offerUri(publicKey, signingKey, "dev", secret, name)
            val parsed = assertIs<ParsedPairingUri.Offer>(PairingUri.parse(uri))
            assertEquals(name, parsed.deviceName, "device name must round-trip: $name")
        }
    }

    /** A delegation is only ever over a 32-byte key, so a QR carrying any other is not an offer. */
    @Test
    fun offerUri_withASigningKeyThatIsNot32Bytes_isMalformed() {
        for (size in listOf(1, 31, 33, 64)) {
            val uri = PairingUri.offerUri(publicKey, ByteArray(size) { 9 }, "dev", secret, "n")
            assertIs<ParsedPairingUri.Malformed>(PairingUri.parse(uri), "a $size-byte g= key")
        }
    }

    @Test
    fun offerUri_omitsOwnerParam() {
        // §3.2: the offerer knows no ownerId — the QR must not carry `o=`. It DOES
        // carry the offerer's own `d=` (device id) + `g=` (signing key) so the
        // responder can register it.
        val uri = PairingUri.offerUri(publicKey, signingKey, "dev", secret, "n")
        val query = uri.substringAfter("?").split("&").map { it.substringBefore("=") }
        assertEquals(listOf("k", "g", "d", "s", "n"), query)
    }

    // ── legacy + unknown-host rejection ──────────────────────────────────────

    @Test
    fun retiredPairHost_isUnknownRole_neverPartiallyParsed() {
        // A retired host must never PARTIALLY parse into a usable pairing.
        val bech32Payload = Bech32.encode("tenshu", publicKey + "legacy-secret".encodeToByteArray())
        val legacy = "tenshu://pair?v=1&d=$bech32Payload&ownerId=owner-abc123&deviceId=device-xyz789"
        assertIs<ParsedPairingUri.UnknownRole>(PairingUri.parse(legacy))

        assertIs<ParsedPairingUri.UnknownRole>(PairingUri.parse("tenshu://pair?v=1&d=abc"))
        assertIs<ParsedPairingUri.UnknownRole>(PairingUri.parse("tenshu://pair"))
    }

    @Test
    fun unknownRoleHost_isRejectedAsUnknown_notMalformed() {
        // A role minted by a newer app than this parser: "update your app",
        // never a parse attempt (improvements §8).
        val futureRole = "tenshu://handover?k=AAAA&s=BBBB"
        assertIs<ParsedPairingUri.UnknownRole>(PairingUri.parse(futureRole))

        // iOS EPC pairing owns tenshu://epc/pair#… — not a device-pairing QR;
        // it lands in UnknownRole, not a partial parse.
        assertIs<ParsedPairingUri.UnknownRole>(PairingUri.parse("tenshu://epc/pair#fragment"))
    }

    @Test
    fun malformedInputs_areMalformed() {
        for (input in listOf(
            "",
            "not a uri",
            "https://tenshu.app/join?o=o&d=d&k=k&s=s",
            "tenshu://join", // no params at all
            "tenshu://join?d=MQYQ&k=AAAA&s=OM", // missing o
            "tenshu://join?o=&d=MQYQ&k=AAAA&s=OM", // empty o
            "tenshu://join?o=o&d=MQYQ&k=not!base32&s=OM",
            "tenshu://offer?k=AAAA&s=OM", // missing n
            "tenshu://hub?d=MQYQ&g=AAAA", // missing s
            "tenshu://hub?d=MQYQ&g=not!base32&s=OM",
            "tenshu://hub?d=&g=AAAA&s=OM", // empty d
        )) {
            assertIs<ParsedPairingUri.Malformed>(PairingUri.parse(input), "must be Malformed: '$input'")
        }
    }

    // ── hub round-trip (hub proposal §11.3, H2 printed label) ────────────────

    @Test
    fun hubUri_roundTrips() {
        val uri = PairingUri.hubUri(
            hubDeviceId = "hub-device-42",
            hubVerifyKey = signingKey,
            pairingSecret = secret,
        )
        assertTrue(uri.startsWith("tenshu://hub?"), "hub URI must live on the hub host: $uri")

        val parsed = assertIs<ParsedPairingUri.Hub>(PairingUri.parse(uri))
        assertEquals("hub-device-42", parsed.hubDeviceId)
        assertContentEquals(signingKey, parsed.hubVerifyKey)
        assertContentEquals(secret, parsed.pairingSecret)
    }

    @Test
    fun hubUri_pinnedShape() {
        // The printed label is the LONGEST-lived pairing artifact of all — it is
        // physically attached to hardware — so its exact wire form is pinned hardest.
        val uri = PairingUri.hubUri(
            hubDeviceId = "d1",
            hubVerifyKey = ByteArray(32),
            pairingSecret = "s".encodeToByteArray(),
        )
        assertEquals(
            "tenshu://hub?d=MQYQ&g=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&s=OM",
            uri,
        )
    }

    @Test
    fun hubUri_rejectsEmptyFieldsAtGeneration() {
        assertFailsWith<IllegalArgumentException> {
            PairingUri.hubUri("", signingKey, secret)
        }
        assertFailsWith<IllegalArgumentException> {
            PairingUri.hubUri("d", ByteArray(0), secret)
        }
        assertFailsWith<IllegalArgumentException> {
            PairingUri.hubUri("d", signingKey, ByteArray(0))
        }
    }
}
