package org.tenshu.core.common.epc

import org.tenshu.core.common.util.deriveEpcRendezvousSessionId
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EpcPairingUriTest {

    private val secret = ByteArray(16) { it.toByte() }

    @Test
    fun round_trips_through_the_uri() {
        val payload = EpcPairingPayload(
            relayBaseUrl = "https://relay.tenshu.app",
            pairingSecret = secret,
            expiresAtMillis = 1_900_000_000_000L,
        )
        val uri = EpcPairingUri.encode(payload)
        assertTrue(uri.startsWith(EpcPairingUri.PREFIX), "uri: $uri")
        assertEquals(payload, EpcPairingUri.decode(uri))
    }

    @Test
    fun round_trips_without_an_expiry() {
        val payload = EpcPairingPayload(relayBaseUrl = "https://r.example", pairingSecret = secret)
        val decoded = EpcPairingUri.decode(EpcPairingUri.encode(payload))
        assertEquals(payload, decoded)
        assertEquals(null, decoded.expiresAtMillis)
    }

    @Test
    fun round_trips_a_longer_secret_and_unicode_relay() {
        val payload = EpcPairingPayload(
            relayBaseUrl = "https://relay.例え.test/sync",
            pairingSecret = ByteArray(32) { (255 - it).toByte() },
        )
        assertEquals(payload, EpcPairingUri.decode(EpcPairingUri.encode(payload)))
    }

    @Test
    fun the_decoded_secret_derives_the_same_rendezvous_session_id() {
        // The whole point of the payload: both ends derive the same mailbox id.
        val payload = EpcPairingPayload(relayBaseUrl = "https://r.example", pairingSecret = secret)
        val decoded = EpcPairingUri.decode(EpcPairingUri.encode(payload))
        assertContentEquals(
            deriveEpcRendezvousSessionId(secret),
            deriveEpcRendezvousSessionId(decoded.pairingSecret),
        )
    }

    @Test
    fun rejects_a_wrong_scheme() {
        assertFailsWith<EpcPairingUriException> { EpcPairingUri.decode("https://relay.tenshu.app/x") }
        assertFailsWith<EpcPairingUriException> { EpcPairingUri.decode("tenshu://pair?v=1&d=abc") }
        assertFailsWith<EpcPairingUriException> { EpcPairingUri.decode(EpcPairingUri.PREFIX) } // empty fragment
    }

    @Test
    fun rejects_malformed_base64url() {
        assertFailsWith<EpcPairingUriException> { EpcPairingUri.decode(EpcPairingUri.PREFIX + "!!!not base64!!!") }
    }

    @Test
    fun rejects_a_truncated_blob() {
        val full = blob(EpcPairingUri.encode(EpcPairingPayload("https://r", secret)))
        // Drop the last byte (the no-expiry flag) → truncation mid-record.
        val truncated = EpcPairingUri.PREFIX + base64UrlNoPad(full.copyOfRange(0, full.size - 1))
        assertFailsWith<EpcPairingUriException> { EpcPairingUri.decode(truncated) }
    }

    @Test
    fun rejects_trailing_bytes() {
        val full = blob(EpcPairingUri.encode(EpcPairingPayload("https://r", secret)))
        val extended = EpcPairingUri.PREFIX + base64UrlNoPad(full + byteArrayOf(0x42))
        assertFailsWith<EpcPairingUriException> { EpcPairingUri.decode(extended) }
    }

    @Test
    fun rejects_an_unknown_version() {
        val full = blob(EpcPairingUri.encode(EpcPairingPayload("https://r", secret)))
        full[0] = 99 // bump the version byte
        val future = EpcPairingUri.PREFIX + base64UrlNoPad(full)
        val e = assertFailsWith<EpcPairingUriException> { EpcPairingUri.decode(future) }
        assertTrue(e.message!!.contains("update the app"), "message: ${e.message}")
    }

    @Test
    fun rejects_a_too_short_secret() {
        assertFailsWith<IllegalArgumentException> {
            EpcPairingPayload(relayBaseUrl = "https://r", pairingSecret = ByteArray(8))
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun base64UrlNoPad(bytes: ByteArray): String = Base64.UrlSafe.encode(bytes).trimEnd('=')

    @OptIn(ExperimentalEncodingApi::class)
    private fun blob(uri: String): ByteArray {
        val frag = uri.removePrefix(EpcPairingUri.PREFIX)
        return Base64.UrlSafe.decode(frag + "=".repeat((4 - frag.length % 4) % 4))
    }
}
