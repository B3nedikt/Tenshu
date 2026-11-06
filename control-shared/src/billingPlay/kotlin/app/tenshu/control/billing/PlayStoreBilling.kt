package app.tenshu.control.billing

import android.app.Activity
import android.content.Context
import app.tenshu.control.data.BillingRail
import app.tenshu.control.data.StoreBilling
import app.tenshu.control.data.StorePurchaseHost
import app.tenshu.control.data.StorePurchaseOutcome
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * Play Billing, the store rail of a Play build (`commerce-store-link-out.md` D2, P3).
 *
 * ⚠⚠ This class never decides a purchase counts. It returns Play's purchase token, and commerce
 * verifies and acknowledges it ([app.tenshu.control.data.PublisherClient.verifyStorePurchase]);
 * an unacknowledged purchase is refunded by Play after three days.
 */
class PlayStoreBilling(context: Context) : StoreBilling {

    override val rail: BillingRail = BillingRail.GOOGLE_PLAY

    private val packageName = context.packageName

    /** The purchase awaiting Play's answer: which product, and where the answer goes. */
    private class PendingPurchase(val productId: String) {
        val answer = CompletableDeferred<Pair<BillingResult, List<Purchase>?>>()
    }

    /**
     * Play reports a purchase's result to one listener. ⚠ Set BEFORE the sheet opens, so an answer
     * that arrives at once is never missed. Null between purchases: a late or unprompted update (a
     * pending payment completing) is left to restore and to Play's notifications.
     */
    @Volatile
    private var pending: PendingPurchase? = null

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases ->
            val waiting = pending ?: return@setListener
            // ⚠ The listener also hears updates nobody asked for now (a pending payment of an
            // earlier purchase completing). An OK naming another product is not this sheet's
            // answer; a non-OK one (cancelled, failed) can only be.
            val forThisSheet = result.responseCode != BillingResponseCode.OK ||
                purchases.orEmpty().any { waiting.productId in it.products }
            if (forThisSheet) waiting.answer.complete(result to purchases)
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    /** One purchase sheet at a time: two would race for the single listener above. */
    private val purchaseLock = Mutex()

    /** One connection attempt at a time: `startConnection` while connecting is an error. */
    private val connectLock = Mutex()

    override suspend fun purchase(host: StorePurchaseHost, productId: String, accountId: String): StorePurchaseOutcome =
        purchaseLock.withLock {
            if (!connect()) return@withLock StorePurchaseOutcome.Failed(UNAVAILABLE)

            val details = client.queryProductDetails(
                QueryProductDetailsParams.newBuilder()
                    .setProductList(
                        listOf(
                            QueryProductDetailsParams.Product.newBuilder()
                                .setProductId(productId)
                                .setProductType(BillingClient.ProductType.SUBS)
                                .build(),
                        ),
                    )
                    .build(),
            )
            val product = details.productDetailsList?.firstOrNull()
                ?: return@withLock StorePurchaseOutcome.Failed(NOT_ON_PLAY)
            // ⚠ The base plan itself — never whichever offer Play lists first, which can be a
            // promotional one at another price (commerce prices the base plan).
            val offer = product.subscriptionOfferDetails
                ?.firstOrNull { it.basePlanId == BASE_PLAN && it.offerId == null }
                ?: return@withLock StorePurchaseOutcome.Failed(NOT_ON_PLAY)

            // ⚠⚠ Never a second subscription beside one Play already bills for us: replace it, and
            // Play charges the prorated difference. Commerce only offers plans above the current
            // one (D6), so a replacement here is always an upgrade.
            // ⚠⚠ Not knowing what is owned is a failure, not "nothing": buying blind is exactly
            // how two subscriptions end up billing side by side.
            val owned = ownedSubscriptions() ?: return@withLock StorePurchaseOutcome.Failed(UNAVAILABLE)
            // ⚠⚠ A Google account can be signed in to two Tenshu accounts on two devices. A plan
            // it pays for another Tenshu account is that account's: replacing it would end their
            // plan, and buying beside it would bill this Google account twice.
            // ⚠ A purchase naming NO account (no obfuscatedAccountId — not one our app made, say a
            // promo code redeemed in the Play Store) is no Tenshu account's: commerce refuses to
            // bind it (verify: account_mismatch) and RTDN finds no account for it. So it is
            // replaced, like this account's own — blocking on it would lock the device out for
            // good, and buying beside it would bill twice.
            if (owned.any { it.accountIdentifiers?.obfuscatedAccountId.let { id -> id != null && id != accountId } }) {
                return@withLock StorePurchaseOutcome.Failed(OTHER_ACCOUNT)
            }

            val replacing = owned.firstOrNull { productId !in it.products }
            val params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(product)
                            .setOfferToken(offer.offerToken)
                            .build(),
                    ),
                )
                // Commerce refuses a purchase that names another account (P2).
                .setObfuscatedAccountId(accountId)
                .apply {
                    if (replacing != null) {
                        @Suppress("DEPRECATION") // its successor is per product; one product here
                        setSubscriptionUpdateParams(
                            BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                                .setOldPurchaseToken(replacing.purchaseToken)
                                .setSubscriptionReplacementMode(
                                    BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_PRORATED_PRICE,
                                )
                                .build(),
                        )
                    }
                }
                .build()

            val waiting = PendingPurchase(productId).also { pending = it }
            try {
                // ⚠ The Activity is taken here, used, and not kept: this call outlives the screen
                // (it runs in the checkout ViewModel), and a rotation must not leak the old one.
                val launched = launchSheet(host, params)
                    ?: return@withLock StorePurchaseOutcome.Failed(NO_SCREEN)
                if (launched.responseCode != BillingResponseCode.OK) return@withLock outcomeOf(launched, null, productId)
                val (result, purchases) = waiting.answer.await()
                outcomeOf(result, purchases, productId)
            } finally {
                pending = null
            }
        }

    /** Opens the sheet; null when [host] holds no Activity. Not suspending, so nothing holds it after. */
    private fun launchSheet(host: StorePurchaseHost, params: BillingFlowParams): BillingResult? {
        val activity = host.take() as? Activity ?: return null
        return client.launchBillingFlow(activity, params)
    }

    override suspend fun ownedPurchaseTokens(): List<String>? =
        ownedSubscriptions()
            ?.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            ?.map { it.purchaseToken }

    override fun manageSubscriptionsUrl(productId: String?): String =
        "https://play.google.com/store/account/subscriptions?package=$packageName" +
            (productId?.let { "&sku=$it" } ?: "")

    private suspend fun ownedSubscriptions(): List<Purchase>? {
        if (!connect()) return null
        val result = client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
        )
        return result.purchasesList.takeIf { result.billingResult.responseCode == BillingResponseCode.OK }
    }

    private suspend fun outcomeOf(result: BillingResult, purchases: List<Purchase>?, productId: String): StorePurchaseOutcome =
        when (result.responseCode) {
            BillingResponseCode.OK -> {
                val purchase = purchases?.firstOrNull { productId in it.products }
                when (purchase?.purchaseState) {
                    Purchase.PurchaseState.PURCHASED -> StorePurchaseOutcome.Purchased(purchase.purchaseToken)
                    Purchase.PurchaseState.PENDING -> StorePurchaseOutcome.Pending
                    else -> StorePurchaseOutcome.Failed(GENERIC)
                }
            }
            BillingResponseCode.USER_CANCELED -> StorePurchaseOutcome.Cancelled
            // Already bought (say, verification never finished): hand the existing purchase over.
            BillingResponseCode.ITEM_ALREADY_OWNED ->
                ownedSubscriptions()?.firstOrNull { productId in it.products }
                    ?.let { StorePurchaseOutcome.Purchased(it.purchaseToken) }
                    ?: StorePurchaseOutcome.Failed(GENERIC)
            BillingResponseCode.BILLING_UNAVAILABLE,
            BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingResponseCode.SERVICE_DISCONNECTED,
            -> StorePurchaseOutcome.Failed(UNAVAILABLE)
            else -> StorePurchaseOutcome.Failed(GENERIC)
        }

    /** Serialised: a second caller waits for the first attempt and then sees `isReady`. */
    private suspend fun connect(): Boolean = connectLock.withLock {
        if (client.isReady) return@withLock true
        suspendCancellableCoroutine { continuation ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (continuation.isActive) continuation.resume(result.responseCode == BillingResponseCode.OK)
                }

                override fun onBillingServiceDisconnected() {
                    if (continuation.isActive) continuation.resume(false)
                }
            })
        }
    }

    private companion object {
        const val BASE_PLAN = "monthly"
        const val NO_SCREEN = "Google Play could not be opened from here."
        const val UNAVAILABLE = "Google Play isn't available on this device right now. Nothing was charged."
        const val NOT_ON_PLAY = "This plan isn't available on Google Play yet."
        const val OTHER_ACCOUNT =
            "This Google account already pays for a Tenshu plan on another Tenshu account. " +
                "Nothing was charged. Manage that plan from Google Play, or sign in with that account."
        const val GENERIC = "Google Play couldn't complete the purchase. Nothing was charged."
    }
}
