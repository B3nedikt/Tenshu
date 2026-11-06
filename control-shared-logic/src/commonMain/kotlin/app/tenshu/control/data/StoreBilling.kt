package app.tenshu.control.data

/**
 * The rail a build sells paid plans through (`commerce-store-link-out.md` D1–D3). A fact of the
 * build, never a runtime choice: a Play build sells through Play Billing and must never offer Stripe.
 *
 * @property wire the name commerce's `/billing` endpoint takes (`PurchaseRail`).
 */
enum class BillingRail(val wire: String) {
    STRIPE("Stripe"),
    GOOGLE_PLAY("Google"),
    APPLE("Apple"),
}

/**
 * The foreground UI a store's purchase sheet opens from (an Android `Activity`), handed over once.
 *
 * ⚠ The purchase runs in the checkout ViewModel's scope, so it outlives the screen that started it
 * (a rotation recreates the Activity mid-purchase). [take] gives the host out a single time and
 * forgets it, so the purchase never keeps a destroyed Activity alive while it waits for the sheet.
 */
class StorePurchaseHost(host: Any) {
    private var host: Any? = host

    /** The host, the first time; null after. */
    fun take(): Any? = host.also { host = null }
}

/** What the store's purchase sheet answered. */

sealed interface StorePurchaseOutcome {
    /** Paid; [purchaseToken] goes to commerce for verification (which also acknowledges it). */
    data class Purchased(val purchaseToken: String) : StorePurchaseOutcome

    /** Accepted but not yet paid (a slow payment method); commerce sees it once it completes. */
    data object Pending : StorePurchaseOutcome

    data object Cancelled : StorePurchaseOutcome

    data class Failed(val message: String) : StorePurchaseOutcome
}

/**
 * A store's billing on this device: Play Billing in a Play build, later StoreKit. Absent (no
 * binding) on a Stripe build, which is how the UI knows to use the browser checkout instead.
 *
 * ⚠⚠ A purchase only counts once commerce has verified it ([PublisherClient.verifyStorePurchase]).
 * The store's own "purchased" is never trusted for fulfilment, exactly as the Stripe browser return
 * is not (`commerce-accounts-and-sessions.md` §9.5).
 */
interface StoreBilling {
    val rail: BillingRail

    /**
     * Show the store's purchase sheet for [productId], naming [accountId] (commerce's account id;
     * Play's `obfuscatedAccountId`), and suspend until it answers. A plan this store already bills
     * for us is **replaced**, never bought beside it: the store prorates an upgrade itself.
     *
     * @param host the platform's foreground UI the sheet attaches to (an Android `Activity`),
     *   handed over once: take it with [StorePurchaseHost.take] at the moment the sheet opens.
     */
    suspend fun purchase(host: StorePurchaseHost, productId: String, accountId: String): StorePurchaseOutcome

    /**
     * Purchase tokens the store currently holds for our plans, or null if the store could not be
     * asked. Re-verifying them is how a purchase interrupted before verification is recovered —
     * which matters: Play refunds a purchase nobody acknowledged within three days.
     */
    suspend fun ownedPurchaseTokens(): List<String>?

    /** The store's own page for managing (cancelling) the subscription. */
    fun manageSubscriptionsUrl(productId: String?): String
}
