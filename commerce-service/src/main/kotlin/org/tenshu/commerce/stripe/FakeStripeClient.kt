package org.tenshu.commerce.stripe

import org.tenshu.commerce.ProductCatalog
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * The **dev-only** payment rail (`commerce-end-to-end-dry-run.md` CE-3 / D1) — a stand-in for
 * Stripe that lets the whole purchase flow be walked end to end with no Stripe account, no keys and
 * no public callback URL.
 *
 * ⚠⚠ **What is faked is exactly one thing: the payment processor.** The rail hands back a URL to a
 * commerce-hosted confirm page; confirming it posts a **correctly HMAC-signed** Stripe-shaped event
 * to the **real** `POST /commerce/webhook/stripe`. So signature verification, the replay window,
 * idempotency by event id, account create-or-find from the collected email, owner rebinding, the
 * entitlement ledger and the statutory invoice row all execute unmodified. Going live is then a
 * config flip (`STRIPE_MODE=real` + keys), not a different code path — which is the point: the part
 * of the money path most worth rehearsing is the part *after* the processor, and a fake that
 * shortcut straight to "grant the tier" would rehearse none of it.
 *
 * ⚠ Deliberately **not** wired straight into the grant: `CommerceConfig.validate()` refuses
 * `STRIPE_MODE=fake` when `DEV_MODE=false`, so this cannot reach a real deployment.
 *
 * ⚠ Sessions, customers and subscriptions live in memory only. A restart forgets them — acceptable
 * for a dev rail, and it keeps the fake from needing a schema.
 */
class FakeStripeClient(
    /** Absolute origin the confirm page is reachable at, e.g. `http://localhost:8081`. */
    private val publicBaseUrl: String,
) : StripeClient {

    /** A checkout the user has been sent to but not yet confirmed. */
    data class PendingCheckout(
        val sessionId: String,
        val ownerId: String,
        val productId: String,
        /** The price the checkout route resolved; what the recorded subscription bills on. */
        val priceId: String,
        val successUrl: String,
        val cancelUrl: String,
        /** Echoed into the fake event's metadata, exactly as Stripe echoes ours (policy §5.4). */
        val withdrawalAckTosVersion: String = "",
        /** Set when the buyer was signed in; the confirm page then offers no other address. */
        val customerEmail: String? = null,
        /** The existing customer the checkout was opened for; the purchase stays on it. */
        val customerId: String? = null,
    )

    /** One subscription this rail has sold; [cancelled] ones no longer bill. */
    private data class FakeSubscription(val id: String, val priceId: String, val cancelled: Boolean = false)

    private val pending = ConcurrentHashMap<String, PendingCheckout>()

    /** Customer id → the email it was created with, as Stripe keeps it on the Customer object. */
    private val customers = ConcurrentHashMap<String, String>()

    /** Customer id → every subscription sold to it. */
    private val subscriptions = ConcurrentHashMap<String, List<FakeSubscription>>()

    override suspend fun createCheckoutSession(request: CheckoutSessionRequest): String? {
        // Stripe refuses a session for a customer it does not know; so does the rail (e.g. one
        // created before a restart), rather than silently selling on a fresh customer.
        val customerEmail = request.customerId?.let { customers[it] ?: return null } ?: request.customerEmail
        val sessionId = "cs_fake_${randomToken()}"
        pending[sessionId] = PendingCheckout(
            sessionId = sessionId,
            ownerId = request.ownerId,
            productId = request.productId,
            priceId = request.priceId,
            successUrl = request.successUrl,
            cancelUrl = request.cancelUrl,
            withdrawalAckTosVersion = request.withdrawalAckTosVersion,
            customerEmail = customerEmail,
            customerId = request.customerId,
        )
        return "$publicBaseUrl$CHECKOUT_PATH/$sessionId"
    }

    /**
     * The payment went through: find-or-create the customer and start the subscription, and return
     * the customer id both events must carry.
     *
     * ⚠⚠ The id the webhook attaches to the account is the id the guard later looks up, so it must
     * be the key the subscription is recorded under — anything else leaves the guard asking about
     * a customer this rail has never heard of.
     */
    fun completeCheckout(checkout: PendingCheckout, email: String): String {
        val customerId = checkout.customerId ?: customerIdFor(email)
        customers.putIfAbsent(customerId, email)
        val subscription = FakeSubscription(id = subscriptionIdFor(checkout.sessionId), priceId = checkout.priceId)
        subscriptions.merge(customerId, listOf(subscription)) { held, added -> held + added }
        return customerId
    }

    override suspend fun createPortalSession(customerId: String, returnUrl: String): String =
        "$publicBaseUrl$PORTAL_PATH?customer=$customerId&return_url=$returnUrl"

    /**
     * What this rail has sold [customerId] and not yet cancelled — so the checkout guard refuses a
     * second purchase here exactly as it does against Stripe.
     */
    override suspend fun billingSubscriptions(customerId: String): List<BillingSubscription> =
        subscriptions[customerId].orEmpty()
            .filterNot { it.cancelled }
            .map { BillingSubscription(id = it.id, priceIds = listOf(it.priceId)) }

    /** The fake rail never refunds. */
    override suspend fun refundsForCharge(chargeId: String): List<ChargeRefund> = emptyList()

    /** The fake rail's own event already carries the payment it invented; there is nothing to look up. */
    override suspend fun invoicePaymentEvidence(invoiceId: String): InvoicePaymentEvidence? = null

    /**
     * Cancels what [billingSubscriptions] would list and reports how many. ⚠ Idempotent as Stripe
     * is: a cancelled subscription is no longer billing, so a retry sweep's second call finds 0.
     */
    override suspend fun cancelActiveSubscriptions(customerId: String): Int {
        var cancelled = 0
        subscriptions.computeIfPresent(customerId) { _, held ->
            cancelled = held.count { !it.cancelled }
            held.map { it.copy(cancelled = true) }
        }
        return cancelled
    }

    /** The fake rail moved no money, so a rail switch only cancels; nothing to refund. */
    override suspend fun refundUnusedAndCancel(customerId: String, nowMs: Long): Long {
        cancelActiveSubscriptions(customerId)
        return 0
    }

    fun checkout(sessionId: String): PendingCheckout? = pending[sessionId]

    /** Consume a pending checkout so a double-confirm cannot mint twice at this layer either. */
    fun takeCheckout(sessionId: String): PendingCheckout? = pending.remove(sessionId)

    companion object {
        const val CHECKOUT_PATH = "/dev/stripe/checkout"
        const val PORTAL_PATH = "/dev/stripe/portal"

        private val random = SecureRandom()

        /**
         * A stable fake customer per email, so a second purchase by the same person attaches to the
         * same customer and the billing-portal path has something to resolve.
         */
        fun customerIdFor(email: String): String = "cus_fake_${Integer.toHexString(email.hashCode())}"

        /** The subscription a checkout starts; `invoice.paid` names the same id. */
        fun subscriptionIdFor(sessionId: String): String = "sub_fake_$sessionId"

        private fun randomToken(): String {
            val bytes = ByteArray(12).also { random.nextBytes(it) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        /**
         * Synthetic Stripe price ids so the tiers are purchasable with no Stripe dashboard. The
         * checkout route 400s on a tier absent from the price map, so without these the fake rail
         * would be reachable but unable to sell anything.
         */
        fun fakePriceIds(): Map<String, String> = mapOf(
            ProductCatalog.BASIC to "price_fake_basic",
            ProductCatalog.PREMIUM to "price_fake_premium",
        )
    }
}

/**
 * Prices the fake rail charges, in minor units, mirroring the tier copy the client shows
 * (`commerce-accounts-and-sessions.md` §2.1: Basic €2.99 / Premium €9.99). Only the fake rail reads
 * these — with real Stripe the amount comes off the event, because the price lives in the Stripe
 * dashboard and we must never assert a number the processor did not charge.
 */
object FakePrices {

    /** German standard rate, in basis points — the fake rail's stand-in for Stripe Tax. */
    const val VAT_BASIS_POINTS = 1_900

    /** Gross amount in minor units for [productId], or null if it is not a purchasable tier. */
    fun grossMinor(productId: String): Long? = when (productId) {
        ProductCatalog.BASIC -> 299L
        ProductCatalog.PREMIUM -> 999L
        else -> null
    }

    /**
     * The VAT component of a **VAT-inclusive** [grossMinor] — European display prices include tax,
     * so the tax is backed out of the gross rather than added to a net. Rounded half-up, which is
     * what leaves `net + vat == gross` exactly.
     */
    fun vatMinor(grossMinor: Long): Long {
        val denominator = 10_000L + VAT_BASIS_POINTS
        return (grossMinor * VAT_BASIS_POINTS + denominator / 2) / denominator
    }
}
