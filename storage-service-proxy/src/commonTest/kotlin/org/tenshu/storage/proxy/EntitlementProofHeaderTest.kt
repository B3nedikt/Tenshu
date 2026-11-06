package org.tenshu.storage.proxy

import org.tenshu.identity.EntitlementSignature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [EntitlementRecordCredentialProvider.proofHeader] — the presentation half of the entitlement
 * credential, which had **no test at all** until the free-grant write-death fix.
 *
 * The contract it pins: a record is withheld only before its window OPENS. An expired one is still
 * presented, because the relay floors an expired record to the free tier — withholding it made a
 * lapse indistinguishable from having no record, and left the owner unable to write anything while
 * metadata sync carried on regardless.
 */
class EntitlementProofHeaderTest {

    private fun record(validFrom: Long, validUntil: Long) = EntitlementSignature.SignedEntitlement(
        ownerId = "owner-1",
        publisherId = "tenshu.commerce",
        productId = "tenshu.drive.free",
        validFrom = validFrom,
        validUntil = validUntil,
        signature = ByteArray(64) { it.toByte() },
    )

    @Test
    fun noRecord_headerIsNull() {
        assertNull(EntitlementRecordCredentialProvider().proofHeader(nowMs = 5_000L))
    }

    @Test
    fun recordInsideWindow_isPresented() {
        val provider = EntitlementRecordCredentialProvider()
        provider.update(record(validFrom = 1_000L, validUntil = 9_000L))
        assertNotNull(provider.proofHeader(nowMs = 5_000L))
    }

    @Test
    fun expiredRecord_isStillPresented() {
        // ⚠⚠ The regression. A free grant has no session to renew from, so "expired" is its steady
        // state; withholding the record here is what turned a lapse into write-death.
        val provider = EntitlementRecordCredentialProvider()
        provider.update(record(validFrom = 1_000L, validUntil = 9_000L))
        assertNotNull(
            provider.proofHeader(nowMs = 9_001L),
            "an expired record must still be presented — the relay floors it to Free",
        )
    }

    @Test
    fun longExpiredRecord_isStillPresented() {
        // No staleness cliff: the relay decides the tier, and a record a year old is still the
        // issuer's statement that this owner exists.
        val provider = EntitlementRecordCredentialProvider()
        provider.update(record(validFrom = 1_000L, validUntil = 9_000L))
        assertNotNull(provider.proofHeader(nowMs = 9_000L + 365L * 24 * 60 * 60 * 1000))
    }

    @Test
    fun notYetValidRecord_isWithheld() {
        // The one case that stays withheld: a window that has not opened is not evidence, and unlike
        // expiry it is not a state an owner reaches by waiting.
        val provider = EntitlementRecordCredentialProvider()
        provider.update(record(validFrom = 5_000L, validUntil = 9_000L))
        assertNull(provider.proofHeader(nowMs = 4_999L))
    }

    @Test
    fun expiredThenRenewed_presentsTheRenewal() {
        val provider = EntitlementRecordCredentialProvider()
        provider.update(record(validFrom = 1_000L, validUntil = 9_000L))
        val lapsed = provider.proofHeader(nowMs = 20_000L)
        provider.update(record(validFrom = 10_000L, validUntil = 30_000L))
        val renewed = provider.proofHeader(nowMs = 20_000L)
        assertNotNull(lapsed)
        assertNotNull(renewed)
        // Both present, but the renewal is a different credential — the header tracks `current`.
        assertEquals(false, lapsed == renewed)
    }
}
