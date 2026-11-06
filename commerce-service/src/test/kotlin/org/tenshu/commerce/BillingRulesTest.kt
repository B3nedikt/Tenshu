package org.tenshu.commerce

import org.tenshu.commerce.store.EntitlementHistory
import org.tenshu.commerce.store.EntitlementSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The never-pay-twice rules (`commerce-store-link-out.md` D6) without HTTP. */
class BillingRulesTest {

    private val paid = listOf(ProductCatalog.PREMIUM, ProductCatalog.BASIC)

    private fun grant(productId: String, source: EntitlementSource) = EntitlementHistory(
        accountId = "acc", productId = productId, validFrom = 0, validUntil = 10, source = source, issuedAt = 0,
    )

    private fun refusal(current: EntitlementHistory, productId: String, rail: PurchaseRail) =
        BillingRules.refusal(current, BillingRules.billedBy(current), productId, rail)

    @Test
    fun offersOnlyPlansAboveTheCurrentOne_lowestFirst() {
        assertEquals(paid.reversed(), BillingRules.offers(ProductCatalog.FREE, BilledBy.Free, PurchaseRail.Stripe, paid).products)
        assertEquals(listOf(ProductCatalog.PREMIUM), BillingRules.offers(ProductCatalog.BASIC, BilledBy.Stripe, PurchaseRail.Stripe, paid).products)
        assertEquals(emptyList(), BillingRules.offers(ProductCatalog.PREMIUM, BilledBy.Google, PurchaseRail.Stripe, paid).products)
        assertEquals(paid.reversed(), BillingRules.offers(null, null, PurchaseRail.Stripe, paid).products)
    }

    @Test
    fun anAppleBilledPlanIsUpgradedOnlyOnAnAppleDevice() {
        val elsewhere = BillingRules.offers(ProductCatalog.BASIC, BilledBy.Apple, PurchaseRail.Google, paid)
        assertEquals(emptyList(), elsewhere.products)
        assertEquals(BilledBy.Apple, elsewhere.upgradeOn)

        val onApple = BillingRules.offers(ProductCatalog.BASIC, BilledBy.Apple, PurchaseRail.Apple, paid)
        assertEquals(listOf(ProductCatalog.PREMIUM), onApple.products)
        assertNull(onApple.upgradeOn)
    }

    @Test
    fun billedByComesFromTheGrantsSource() {
        assertEquals(BilledBy.Apple, BillingRules.billedBy(grant(ProductCatalog.BASIC, EntitlementSource.IapApple)))
        assertEquals(BilledBy.Google, BillingRules.billedBy(grant(ProductCatalog.BASIC, EntitlementSource.IapGoogle)))
        assertEquals(BilledBy.Code, BillingRules.billedBy(grant(ProductCatalog.BASIC, EntitlementSource.Redeem)))
        assertEquals(BilledBy.Stripe, BillingRules.billedBy(grant(ProductCatalog.BASIC, EntitlementSource.Stripe)))
        assertNull(BillingRules.billedBy(null))
    }

    @Test
    fun aCodeIsNeverBoughtOverWithTheSameOrALowerPlan() {
        val premiumCode = grant(ProductCatalog.PREMIUM, EntitlementSource.Redeem)
        for (rail in PurchaseRail.entries) {
            assertEquals("not_an_upgrade", refusal(premiumCode, ProductCatalog.BASIC, rail))
            assertEquals("not_an_upgrade", refusal(premiumCode, ProductCatalog.PREMIUM, rail))
        }
        assertNull(refusal(grant(ProductCatalog.BASIC, EntitlementSource.Redeem), ProductCatalog.PREMIUM, PurchaseRail.Google))
    }

    @Test
    fun anApplePlanIsNeverBoughtOverOffApple() {
        val apple = grant(ProductCatalog.BASIC, EntitlementSource.IapApple)
        assertEquals("billed_by_apple", refusal(apple, ProductCatalog.PREMIUM, PurchaseRail.Stripe))
        assertEquals("billed_by_apple", refusal(apple, ProductCatalog.PREMIUM, PurchaseRail.Google))
        // On Apple, Apple's own subscription group handles the upgrade.
        assertNull(refusal(apple, ProductCatalog.PREMIUM, PurchaseRail.Apple))
    }

    @Test
    fun stripeAndGooglePlansAreUpgradedAcrossRailsButNeverRebought() {
        val google = grant(ProductCatalog.BASIC, EntitlementSource.IapGoogle)
        assertNull(refusal(google, ProductCatalog.PREMIUM, PurchaseRail.Stripe))
        assertEquals("not_an_upgrade", refusal(google, ProductCatalog.BASIC, PurchaseRail.Stripe))

        val stripe = grant(ProductCatalog.BASIC, EntitlementSource.Stripe)
        assertNull(refusal(stripe, ProductCatalog.PREMIUM, PurchaseRail.Google))
        assertEquals("not_an_upgrade", refusal(stripe, ProductCatalog.BASIC, PurchaseRail.Google))
    }

    @Test
    fun theSameRailIsLeftToThatRail() {
        // Stripe is checked live in checkout; a store manages its own subscription group.
        assertNull(refusal(grant(ProductCatalog.BASIC, EntitlementSource.Stripe), ProductCatalog.BASIC, PurchaseRail.Stripe))
        assertNull(refusal(grant(ProductCatalog.PREMIUM, EntitlementSource.IapGoogle), ProductCatalog.BASIC, PurchaseRail.Google))
        assertNull(refusal(grant(ProductCatalog.FREE, EntitlementSource.Free), ProductCatalog.BASIC, PurchaseRail.Stripe))
    }
}
