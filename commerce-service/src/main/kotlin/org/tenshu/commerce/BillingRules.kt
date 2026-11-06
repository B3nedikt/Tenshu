package org.tenshu.commerce

import kotlinx.serialization.Serializable
import org.tenshu.commerce.store.EntitlementHistory
import org.tenshu.commerce.store.EntitlementSource

/**
 * The rail a device buys through (`commerce-store-link-out.md` D1–D3): Apple's in-app purchase on
 * Apple's stores, Play Billing in a Play build, Stripe everywhere else (web, desktop, a non-Play APK).
 * A device never chooses it at runtime: it is a fact of the build the request comes from.
 */
@Serializable
enum class PurchaseRail { Stripe, Apple, Google }

/** Who bills the account's current plan, as a device should name it. */
@Serializable
enum class BilledBy { Free, Code, Stripe, Apple, Google }

/**
 * The never-pay-twice rules (`commerce-store-link-out.md` D6, §0.4) as pure functions, so the
 * `/billing` answer and the checkout refusal cannot disagree.
 *
 * ⚠ Plans are ordered by the quota they grant ([ProductCatalog.quotaBytes]), the same order
 * [org.tenshu.commerce.store.pickCurrentEntitlement] uses to choose the current plan.
 */
object BillingRules {

    /** Who bills [current]. Null when the account holds no valid entitlement at all. */
    fun billedBy(current: EntitlementHistory?): BilledBy? = when (current?.source) {
        null -> null
        EntitlementSource.Free -> BilledBy.Free
        EntitlementSource.Redeem -> BilledBy.Code
        EntitlementSource.Stripe -> BilledBy.Stripe
        EntitlementSource.IapApple -> BilledBy.Apple
        EntitlementSource.IapGoogle -> BilledBy.Google
    }

    /** The [BilledBy] a purchase on [rail] produces. */
    fun billedByOf(rail: PurchaseRail): BilledBy = when (rail) {
        PurchaseRail.Stripe -> BilledBy.Stripe
        PurchaseRail.Apple -> BilledBy.Apple
        PurchaseRail.Google -> BilledBy.Google
    }

    /**
     * What a device on [rail] may buy, given the current plan: only plans **above** it, and nothing
     * at all when the plan is billed by Apple and the device is not an Apple device — Apple lets
     * nobody but the user stop an Apple renewal, so a second subscription elsewhere would bill twice.
     *
     * @param sellable the products [rail] sells (for Stripe, the configured price map).
     */
    fun offers(
        currentProductId: String?,
        billedBy: BilledBy?,
        rail: PurchaseRail,
        sellable: Collection<String>,
    ): Offers {
        if (billedBy == BilledBy.Apple && rail != PurchaseRail.Apple) {
            return Offers(products = emptyList(), upgradeOn = BilledBy.Apple)
        }
        val floor = currentProductId?.let(::rank) ?: Long.MIN_VALUE
        return Offers(products = sellable.filter { rank(it) > floor }.sortedBy(::rank), upgradeOn = null)
    }

    /**
     * Why a purchase of [productId] on [rail] must be refused over [current], or null if it may go
     * ahead. Going ahead over another rail's plan means that plan is stopped once the new one is
     * paid (D6): Stripe and Google can be, Apple cannot.
     *
     * ⚠ A plan on the **same** rail is left to that rail: Stripe is checked live in the checkout
     * route (our ledger shows a cancelled plan as live until its period ends, and buying it again
     * then is legitimate), and a store manages its own subscription group, renewals included.
     */
    fun refusal(current: EntitlementHistory?, billedBy: BilledBy?, productId: String, rail: PurchaseRail): String? =
        when {
            current == null || billedBy == null || billedBy == BilledBy.Free -> null
            billedBy == billedByOf(rail) -> null
            billedBy == BilledBy.Apple -> "billed_by_apple"
            rank(productId) <= rank(current.productId) -> "not_an_upgrade"
            else -> null
        }

    /**
     * Why a purchase of [productId] on [rail] by [buyerId] must be refused, looking at **every**
     * account bound to the realm ([realm], the buyer's own included), or null if it may go ahead.
     *
     * ⚠⚠ The one place the realm-wide rule lives: `/billing` (what a device is offered), Google
     * verify and Stripe checkout all call it, so a device is never offered what the purchase then
     * refuses. On top of [refusal] per account: a plan under ANOTHER account that a purchase here
     * would leave billing ([stoppableElsewhere]) refuses with `subscribed_under_another_account` —
     * only the buyer's own other-rail plan is stopped when the new one is paid.
     *
     * @param buyerId null when the buyer has no account yet (a first Stripe checkout).
     */
    fun realmRefusal(realm: List<RealmPlan>, buyerId: String?, productId: String, rail: PurchaseRail): String? {
        for (plan in realm) {
            val billedBy = billedBy(plan.current)
            val reason = refusal(plan.current, billedBy, productId, rail)
                ?: SUBSCRIBED_UNDER_ANOTHER_ACCOUNT.takeIf {
                    plan.accountId != buyerId && billedBy in stoppableElsewhere(rail)
                }
            if (reason != null) return reason
        }
        return null
    }

    /**
     * [offers] for the buyer's own plan, narrowed by [realmRefusal] over the whole realm. When the
     * realm rules leave nothing, [Offers.refusal] says why, so the device can say so too.
     */
    fun realmOffers(
        realm: List<RealmPlan>,
        buyerId: String,
        rail: PurchaseRail,
        sellable: Collection<String>,
    ): Offers {
        val current = realm.firstOrNull { it.accountId == buyerId }?.current
        val own = offers(current?.productId, billedBy(current), rail, sellable)
        val refusals = own.products.associateWith { realmRefusal(realm, buyerId, it, rail) }
        val allowed = own.products.filter { refusals[it] == null }
        // The most telling reason when several apply: "another account pays" explains the rest.
        val reasons = refusals.values.filterNotNull()
        return own.copy(
            products = allowed,
            refusal = if (allowed.isEmpty()) {
                reasons.firstOrNull { it == SUBSCRIBED_UNDER_ANOTHER_ACCOUNT } ?: reasons.firstOrNull()
            } else {
                null
            },
        )

    }

    /**
     * Plans under another account of the realm that a purchase on [rail] would leave billing.
     * ⚠ For Stripe only a Google plan: a Stripe plan elsewhere in the realm is asked of Stripe
     * live by the checkout route (our ledger shows a cancelled plan as live until its period ends).
     */
    private fun stoppableElsewhere(rail: PurchaseRail): Set<BilledBy> = when (rail) {
        PurchaseRail.Stripe -> setOf(BilledBy.Google)
        PurchaseRail.Google, PurchaseRail.Apple -> setOf(BilledBy.Stripe, BilledBy.Google)
    }

    const val SUBSCRIBED_UNDER_ANOTHER_ACCOUNT = "subscribed_under_another_account"

    private fun rank(productId: String): Long = ProductCatalog.quotaBytes(productId, fallback = 0)

    /** One account of a realm and its current plan, as [realmRefusal] sees it. */
    data class RealmPlan(val accountId: String, val current: EntitlementHistory?)

    data class Offers(
        /** Purchasable products, lowest first. */
        val products: List<String>,
        /** Set when the plan can only be upgraded on another rail's devices. */
        val upgradeOn: BilledBy?,
        /** Set by [realmOffers] when the realm's other accounts leave nothing to buy: the reason. */
        val refusal: String? = null,
    )

}
