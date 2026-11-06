package org.tenshu.storage.proxy

import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.identity.Entitlement
import org.tenshu.identity.common.identityServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The pure [entitlementToFigures] truth table (storage-entitlement-sync.md slice 3,
 * relocated here in web-storage-status.md Bucket 2): a synced [Entitlement] →
 * [StorageFigures]. The reactive wiring is covered by control-shared's
 * `EntitlementStorageInitializationTest`.
 */
class EntitlementToFiguresTest {

    private val context = createTestContext(
        createCoreTestRegistry().also { it.registerConfig(identityServiceCommonModule) },
    )

    private fun entitlement(productId: String, from: Long, until: Long): Entitlement =
        Entitlement.create(
            context = context,
            ownerId = "owner-1",
            publisherId = "tenshu.dev",
            productId = productId,
            validFrom = from,
            validUntil = until,
            publisherSignature = ByteArray(0),
        )

    @Test
    fun noEntitlement_meansNoStorage() {
        val figures = entitlementToFigures(entitlement = null, nowMs = 1_000)
        assertFalse(figures.hasStorage)
        assertEquals(0L, figures.limitBytes)
    }

    @Test
    fun validFree_grantsFiveGiB() {
        val figures = entitlementToFigures(entitlement(EntitlementProducts.FREE, 0, 10_000), nowMs = 5_000)
        assertTrue(figures.hasStorage)
        assertEquals(EntitlementProducts.FREE_LIMIT_BYTES, figures.limitBytes)
    }

    @Test
    fun validBasic_grantsFiftyGiB() {
        val figures = entitlementToFigures(entitlement(EntitlementProducts.BASIC, 0, 10_000), nowMs = 5_000)
        assertTrue(figures.hasStorage)
        assertEquals(EntitlementProducts.BASIC_LIMIT_BYTES, figures.limitBytes)
    }

    @Test
    fun validPremium_grantsTwoTiB() {
        val figures = entitlementToFigures(entitlement(EntitlementProducts.PREMIUM, 0, 10_000), nowMs = 5_000)
        assertTrue(figures.hasStorage)
        assertEquals(EntitlementProducts.PREMIUM_LIMIT_BYTES, figures.limitBytes)
    }

    @Test
    fun validEntitlement_surfacesRelayReportedUsage() {
        // retention-quota-model.md P1 (D5): the relay-reported usedBytes is threaded through.
        val figures = entitlementToFigures(
            entitlement(EntitlementProducts.BASIC, 0, 10_000),
            nowMs = 5_000,
            usedBytes = 3L * 1024 * 1024 * 1024,
        )
        assertTrue(figures.hasStorage)
        assertEquals(3L * 1024 * 1024 * 1024, figures.usedBytes)
        assertEquals(EntitlementProducts.BASIC_LIMIT_BYTES, figures.limitBytes)
    }

    @Test
    fun notYetValidEntitlement_reportsZeroUsage_evenIfRelaySaysNonZero() {
        // No limit to fill against, so usage renders 0 (never "N / 0"). ⚠ The case that still has no
        // limit is NOT-YET-VALID; an expired record has the free floor and shows its usage.
        val figures = entitlementToFigures(
            entitlement(EntitlementProducts.FREE, 10_000_000, 20_000_000),
            nowMs = 1_000, // before validFrom, well past the leeway
            usedBytes = 999L,
        )
        assertFalse(figures.hasStorage)
        assertEquals(0L, figures.usedBytes)
    }

    @Test
    fun expiredEntitlement_surfacesUsageAgainstTheFreeFloor() {
        // ⚠⚠ The regression: reporting 0/no-storage here was the client disagreeing with the relay,
        // which grants an expired record Free. It rendered as Control → Services showing nothing.
        val figures = entitlementToFigures(
            entitlement(EntitlementProducts.FREE, 0, 10_000),
            nowMs = 20_000,
            usedBytes = 999L,
        )
        assertTrue(figures.hasStorage)
        assertEquals(999L, figures.usedBytes)
    }

    @Test
    fun expiredEntitlement_confersTheFreeTier() {
        // now is past validUntil. The relay floors an expired record to Free rather than refusing it,
        // so the client must not be the one place still saying "no plan".
        val figures = entitlementToFigures(entitlement(EntitlementProducts.FREE, 0, 10_000), nowMs = 20_000)
        assertTrue(figures.hasStorage)
        assertEquals(EntitlementProducts.FREE_LIMIT_BYTES, figures.limitBytes)
    }

    @Test
    fun expiredPaidEntitlement_dropsToFree_notItsOwnTier() {
        // A lapsed PREMIUM record must not keep promising 2 TiB: the relay grants Free past
        // `validUntil` whatever the record says, and a remembered higher figure would promise
        // headroom the next write is refused for.
        val figures = entitlementToFigures(
            entitlement(EntitlementProducts.PREMIUM, 0, 10_000),
            nowMs = 20_000,
            relayLimitBytes = EntitlementProducts.PREMIUM_LIMIT_BYTES, // a stale generous report
        )
        assertTrue(figures.hasStorage)
        assertEquals(EntitlementProducts.FREE_LIMIT_BYTES, figures.limitBytes)
    }

    @Test
    fun notYetValidEntitlement_meansNoStorage() {
        // now is before validFrom by well over the skew leeway — a genuinely future-dated record.
        val figures = entitlementToFigures(
            entitlement(EntitlementProducts.FREE, 10_000_000, 20_000_000),
            nowMs = 5_000,
        )
        assertFalse(figures.hasStorage)
        // ⚠ Also the limit, which nothing asserted before: a not-yet-valid record must report 0, or
        // the gauge renders "N / 5 GiB" of storage the owner does not have yet. This is the one case
        // that still confers nothing — expiry no longer does.
        assertEquals(0L, figures.limitBytes)
    }

    /**
     * ⚠⚠ The regression that cost two paid-tier test runs on 2026-08-08. The record is stamped with
     * the issuer's clock and derived against the client's, and the derivation fires on the save —
     * so at the moment of purchase the record is only milliseconds old and *any* positive issuer
     * skew dates it into the future. Commerce ran 1.7 s ahead and a real 2 TiB grant rendered as
     * "no storage" until Control was restarted.
     */
    @Test
    fun recordDatedSecondsIntoTheFuture_stillGrantsStorage() {
        val figures = entitlementToFigures(
            entitlement(EntitlementProducts.PREMIUM, from = 101_700, until = 999_999),
            nowMs = 100_000, // 1.7s behind the issuer, exactly the observed skew
        )
        assertTrue(figures.hasStorage, "issuer clock skew must not read as 'you have no storage'")
        assertEquals(EntitlementProducts.PREMIUM_LIMIT_BYTES, figures.limitBytes)
    }

    /**
     * ⚠ Leeway is one-sided by design. Tolerating a late record would be handing out storage past
     * the window it was sold for — the failure mode the start-bound tolerance specifically is not.
     */
    @Test
    fun expiryGetsNoLeeway_unlikeTheStartBound() {
        val figures = entitlementToFigures(
            entitlement(EntitlementProducts.PREMIUM, from = 0, until = 100_000),
            nowMs = 101_700, // as far past expiry as the case above was before validity
        )
        // ⚠⚠ What the leeway must not do is keep the record on its OWN tier past `validUntil`. It
        // still confers the free floor — that is the relay's answer, not skew tolerance reviving it.
        assertTrue(figures.hasStorage)
        assertEquals(EntitlementProducts.FREE_LIMIT_BYTES, figures.limitBytes)
        assertNotEquals(EntitlementProducts.PREMIUM_LIMIT_BYTES, figures.limitBytes)
    }
}
