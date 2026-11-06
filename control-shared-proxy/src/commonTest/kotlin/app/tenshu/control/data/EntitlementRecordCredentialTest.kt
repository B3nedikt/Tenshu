package app.tenshu.control.data

import org.tenshu.storage.proxy.EntitlementRecordCredentialProvider
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.identity.EntitlementRecordCredential
import org.tenshu.identity.EntitlementSignature.SignedEntitlement

/**
 * Pins that the client encodes the `signed-record.v1` `X-Entitlement-Proof` value in the
 * **exact** wire shape the relay's `RelayEntitlementVerifier` `signed-record.v1` decoder
 * decodes — a `signed-record.v1:` prefix over base64url(JSON) with the six named fields and
 * a standard-base64 `sig`.
 */
@OptIn(ExperimentalEncodingApi::class)
class EntitlementRecordCredentialTest {

    private val record = SignedEntitlement(
        ownerId = "owner-1",
        publisherId = "tenshu.commerce",
        productId = "tenshu.drive.basic",
        validFrom = 1_000L,
        validUntil = 9_000L,
        signature = byteArrayOf(1, 2, 3, 4),
    )

    // The canonical JSON the relay parses: compact, exact field names, sig = standard base64 of the
    // signature bytes (Base64.Default.encode(byteArrayOf(1,2,3,4)) == "AQIDBA==").
    private val goldenJson =
        """{"ownerId":"owner-1","publisherId":"tenshu.commerce","productId":"tenshu.drive.basic",""" +
            """"validFrom":1000,"validUntil":9000,"sig":"AQIDBA=="}"""

    @Test
    fun encode_matchesGoldenWireValue() {
        val golden = "signed-record.v1:" + Base64.UrlSafe.encode(goldenJson.encodeToByteArray())
        assertEquals(golden, EntitlementRecordCredential.encode(record))
    }

    @Test
    fun encode_hasPrefixAndBase64urlWrapperOverExpectedJson() {
        val header = EntitlementRecordCredential.encode(record)
        assertTrue(header.startsWith("signed-record.v1:"), "must carry the signed-record.v1 proof type prefix")

        val payload = header.removePrefix("signed-record.v1:")
        // A clean round-trip through the URL-safe base64 wrapper back to the exact JSON (field names,
        // order, and the standard-base64 sig) the relay decoder expects.
        val decodedJson = Base64.UrlSafe.decode(payload).decodeToString()
        assertEquals(goldenJson, decodedJson)
    }

    @Test
    fun provider_returnsNull_whenNoRecord() {
        val provider = EntitlementRecordCredentialProvider()
        assertNull(provider.proofHeader(nowMs = 5_000L))
    }

    @Test
    fun provider_encodesValidRecord_withinWindow() {
        val provider = EntitlementRecordCredentialProvider()
        provider.update(record)
        assertEquals(EntitlementRecordCredential.encode(record), provider.proofHeader(nowMs = 5_000L))
    }

    @Test
    fun provider_returnsNull_forNotYetValidRecord() {
        val provider = EntitlementRecordCredentialProvider()
        provider.update(record)
        assertNull(provider.proofHeader(nowMs = 500L), "before validFrom → no credential")
    }

    @Test
    fun provider_stillPresents_expiredRecord() {
        // ⚠⚠ Withholding an expired record makes a lapsed free grant WRITE-DEAD: the relay sees
        // no header, answers `entitlement_required` ("the client attached nothing"), and the owner
        // cannot upload a byte while metadata sync carries on. The relay floors an
        // expired record to Free, so presenting it is what makes a lapse a downgrade.
        val provider = EntitlementRecordCredentialProvider()
        provider.update(record)
        assertNotNull(
            provider.proofHeader(nowMs = 10_000L),
            "after validUntil → still presented, so the relay can floor it to Free",
        )
    }

    @Test
    fun provider_clearingRecord_dropsCredential() {
        val provider = EntitlementRecordCredentialProvider()
        provider.update(record)
        provider.update(null)
        assertNull(provider.proofHeader(nowMs = 5_000L))
    }

    @Test
    fun provider_firesFirstAvailableEdge_onlyOnAbsentToPresent() {
        val provider = EntitlementRecordCredentialProvider()
        var fires = 0
        provider.onRecordUsable = { fires++ }

        provider.update(null) // absent→absent: no fire
        assertEquals(0, fires)
        provider.update(record) // absent→present: fire
        assertEquals(1, fires)
        provider.update(record) // present→present: no re-fire
        assertEquals(1, fires)
        provider.update(null) // present→absent: no fire
        provider.update(record) // absent→present again: fire
        assertEquals(2, fires)
    }
}
