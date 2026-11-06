package org.tenshu.commerce

import org.tenshu.commerce.store.EntitlementHistory
import org.tenshu.commerce.store.EntitlementSource
import org.tenshu.commerce.store.InMemoryCommerceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The mint-authority selection rule (§4.3): among currently-valid entitlement rows the **highest
 * quota** wins, so a paid tier isn't shadowed by the ~permanent Free row (Phase 4 upgrade path), and
 * a lapsed paid window gracefully falls back to Free.
 */
class CurrentEntitlementSelectionTest {

    private val store = InMemoryCommerceStore()
    private val account = "acct-1"

    private fun grant(productId: String, from: Long, until: Long, source: EntitlementSource) =
        store.appendEntitlementHistory(EntitlementHistory(account, productId, from, until, source, issuedAt = from))

    @Test
    fun paidTierBeatsPermanentFree_whileValid() {
        // Free registered first (10-year window), then a Stripe premium upgrade (short window).
        grant(ProductCatalog.FREE, from = 0, until = NOW + 10_000_000_000L, EntitlementSource.Free)
        grant(ProductCatalog.PREMIUM, from = NOW - 1000, until = NOW + 1000, EntitlementSource.Stripe)

        val current = store.currentEntitlement(account, NOW)
        assertEquals(ProductCatalog.PREMIUM, current?.productId)
    }

    @Test
    fun lapsedPaidTier_fallsBackToFree() {
        grant(ProductCatalog.FREE, from = 0, until = NOW + 10_000_000_000L, EntitlementSource.Free)
        grant(ProductCatalog.PREMIUM, from = NOW - 2000, until = NOW - 1000, EntitlementSource.Stripe) // expired

        val current = store.currentEntitlement(account, NOW)
        assertEquals(ProductCatalog.FREE, current?.productId)
    }

    @Test
    fun noValidRow_isNull() {
        grant(ProductCatalog.PREMIUM, from = NOW - 2000, until = NOW - 1000, EntitlementSource.Stripe)
        assertNull(store.currentEntitlement(account, NOW))
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
