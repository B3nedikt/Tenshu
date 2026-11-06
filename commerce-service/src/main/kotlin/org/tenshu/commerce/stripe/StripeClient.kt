package org.tenshu.commerce.stripe

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger

/**
 * The outbound-Stripe seam (`commerce-accounts-and-sessions.md` §6). We are the **seller of
 * record**; Stripe is only the processor, so this is a thin client over Stripe's REST API — no
 * `stripe-java` SDK (it would pull a large dependency + its own HTTP client, against this module's
 * hand-rolled grain, mirroring how the device-side commerce clients hand-roll their HTTP). Card
 * data never touches us: Checkout is hosted, so PCI scope stays with Stripe.
 *
 * The interface is the injection point the routing layer depends on; [RealStripeClient] is the live
 * impl and tests substitute a fake (or drive [RealStripeClient] over a `MockEngine`). Live Stripe is
 * not exercised in CI — a real test-mode round-trip is deploy-time verification (like the other
 * commerce phases' on-device passes).
 */
interface StripeClient {

    /**
     * Create a hosted Checkout session (subscription mode, Stripe Tax on) and return its redirect
     * URL. `client_reference_id` carries our `ownerId` and `metadata[product_id]` our tier, so the
     * webhook can bind the owner + grant the right entitlement (§6). Stripe collects the email.
     */
    suspend fun createCheckoutSession(request: CheckoutSessionRequest): String?

    /**
     * Create a Billing-Portal session for [customerId] (cancellation/management — no in-app
     * dark-pattern cancel, §5.2) and return its URL.
     */
    suspend fun createPortalSession(customerId: String, returnUrl: String): String?

    /**
     * Cancel every still-billing subscription belonging to [customerId]. Returns how many were
     * cancelled (**0 is a success** — nothing was billing), or `null` if the call failed.
     *
     * ⚠⚠ **Closing an account must call this**, or the customer keeps being charged for a service
     * the webhook now refuses to grant (CE-5 made a closed account un-grantable; without this that
     * turns "closed" into "paying for nothing"). Surfaced by CE-5, built here.
     *
     * ⚠ Keyed by **customer**, not by a stored subscription id: we never persisted one, and a
     * customer can legitimately hold several subscriptions after re-subscribing. Cancelling by
     * customer needs no schema change and cannot miss the second one.
     *
     * ⚠ Idempotent by construction — an already-cancelled subscription is simply not in the
     * billing set, so a retry cancels nothing and reports 0. That is what lets a sweep re-run this
     * forever without state to track.
     */
    suspend fun cancelActiveSubscriptions(customerId: String): Int?

    /**
     * What an `invoice.paid` event no longer says about the payment that settled [invoiceId]: its
     * PaymentIntent (the reference a later chargeback names) and the card's issuing country (the
     * second location evidence). `null` when the lookup failed or the invoice has no payment.
     *
     * ⚠⚠ A read at webhook time, because the invoice row is append-only (§5.2): a later event
     * cannot fill these in. Measured against a live test-mode payload on API `2026-06-24.dahlia` —
     * the invoice carries neither `payment_intent`, `charge` nor an unexpanded `payments`.
     */
    suspend fun invoicePaymentEvidence(invoiceId: String): InvoicePaymentEvidence?

    /**
     * Every subscription of [customerId] that still bills or can resume billing, or `null` if the
     * call failed. Empty is an answer: nothing is billing.
     *
     * ⚠⚠ Checkout asks this before selling, because a hosted Checkout always starts a NEW
     * subscription. Buying a tier twice, or "upgrading" through it, left two subscriptions billing
     * side by side (first live pass: Basic bought three times, all three charging).
     */
    suspend fun billingSubscriptions(customerId: String): List<BillingSubscription>?

    /**
     * The succeeded refunds on [chargeId], or `null` if the call failed.
     *
     * ⚠⚠ `charge.refunded` stopped listing them: on the live pass (API `2026-06-24.dahlia`) the
     * event carried `amount_refunded` and no `refunds` at all, so every refund was acknowledged
     * and credited nothing.
     */
    suspend fun refundsForCharge(chargeId: String): List<ChargeRefund>?

    /**
     * The customer moved their plan to another rail (`commerce-store-link-out.md` D6): refund the
     * unused part of every still-billing subscription's current period, pro rata, then cancel it.
     * Returns the refunded total in minor units (**0 is a success**), or `null` if any step failed.
     *
     * ⚠⚠ Safe to retry, and the caller relies on that: before refunding a payment it lists that
     * payment's refunds, and an earlier one tagged `rail_switch` for the subscription counts as
     * done — so a retry after a failed cancel refunds nothing twice. An idempotency-key error is
     * never read as "already refunded": Stripe also replays a failed first attempt under its key.

     *
     * ⚠ The refund raises `charge.refunded`, which writes the statutory credit note as for any
     * refund (WC-1); nothing here touches the ledger.
     */
    suspend fun refundUnusedAndCancel(customerId: String, nowMs: Long): Long? =
        throw UnsupportedOperationException("refundUnusedAndCancel is not implemented by ${this::class.simpleName}")
}

data class ChargeRefund(val id: String, val amountMinor: Long, val currency: String?)

/** One still-billing subscription and the prices on it. */
data class BillingSubscription(val id: String, val priceIds: List<String>)

/** See [StripeClient.invoicePaymentEvidence]. Either half may be absent on its own. */
data class InvoicePaymentEvidence(val paymentRef: String?, val cardCountry: String?)

/** What [StripeClient.createCheckoutSession] needs; assembled by the checkout route from config. */
data class CheckoutSessionRequest(
    val priceId: String,
    val ownerId: String,
    val productId: String,
    val successUrl: String,
    val cancelUrl: String,
    /**
     * Terms version the customer acknowledged immediate performance against (policy §5.4). Carried
     * as Stripe metadata so it comes back on the event: at checkout time there is no account to
     * attach it to, because the account is created from the email Stripe collects.
     */
    val withdrawalAckTosVersion: String = "",
    /**
     * Who is buying, when the buyer is signed in: their existing Stripe customer, else the account
     * email. Either one fixes the email on the hosted page, so the webhook finds the account the
     * session belongs to instead of minting a second one from whatever was typed. At most one is
     * set — Stripe rejects both together.
     */
    val customerId: String? = null,
    val customerEmail: String? = null,
)

/**
 * Live [StripeClient] over Stripe's REST API. Requests are `application/x-www-form-urlencoded`
 * (Stripe's wire format) with a Bearer secret key; responses are JSON, from which we read only the
 * hosted `url`. [apiBase] is injectable so a `MockEngine` test can point it at a stub.
 */
class RealStripeClient(
    private val http: HttpClient,
    private val secretKey: String,
    private val apiBase: String = DEFAULT_API_BASE,
) : StripeClient {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * ⚠⚠ Every request names its API version. Without the header Stripe answers in the ACCOUNT's
     * default version, which need not be the one the webhook endpoint is pinned to — and the
     * lookups below read shapes (`/v1/invoice_payments`, its expansion) that older versions do not
     * have. A miss there is silent: the invoice is booked with the fields empty.
     */
    private fun HttpRequestBuilder.stripeHeaders() {
        header("Authorization", "Bearer $secretKey")
        header("Stripe-Version", API_VERSION)
    }

    override suspend fun createCheckoutSession(request: CheckoutSessionRequest): String? {
        val response = http.submitForm(
            url = "$apiBase/v1/checkout/sessions",
            formParameters = Parameters.build {
                append("mode", "subscription")
                append("client_reference_id", request.ownerId)
                append("success_url", request.successUrl)
                append("cancel_url", request.cancelUrl)
                append("line_items[0][price]", request.priceId)
                append("line_items[0][quantity]", "1")
                append("automatic_tax[enabled]", "true")
                // Propagate the tier onto the subscription so it rides through to invoice.paid too.
                append("metadata[product_id]", request.productId)
                append("metadata[owner_id]", request.ownerId)
                append("subscription_data[metadata][product_id]", request.productId)
                append("subscription_data[metadata][owner_id]", request.ownerId)
                if (request.withdrawalAckTosVersion.isNotBlank()) {
                    append("metadata[withdrawal_ack_tos]", request.withdrawalAckTosVersion)
                }
                if (request.customerId != null) {
                    append("customer", request.customerId)
                    // Stripe Tax needs an address on an existing customer; let Checkout keep it.
                    append("customer_update[address]", "auto")
                } else if (request.customerEmail != null) {
                    append("customer_email", request.customerEmail)
                }
            },
        ) {
            stripeHeaders()
        }
        return response.readUrlOrNull()
    }

    override suspend fun createPortalSession(customerId: String, returnUrl: String): String? {
        val response = http.submitForm(
            url = "$apiBase/v1/billing_portal/sessions",
            formParameters = Parameters.build {
                append("customer", customerId)
                append("return_url", returnUrl)
            },
        ) {
            stripeHeaders()
        }
        return response.readUrlOrNull()
    }

    override suspend fun cancelActiveSubscriptions(customerId: String): Int? {
        // `status=all` on purpose. Filtering server-side on `active` would miss `trialing`,
        // `past_due`, `unpaid` and `paused` — every one of which still bills or converts to
        // billing, so a trial left running on a closed account would charge them later.
        val listed = runCatching {
            http.get("$apiBase/v1/subscriptions") {
                stripeHeaders()
                parameter("customer", customerId)
                parameter("status", "all")
                parameter("limit", SUBSCRIPTION_PAGE_SIZE)
            }
        }.getOrNull() ?: return null
        if (!listed.status.isSuccess()) return null

        val ids = runCatching {
            json.parseToJsonElement(listed.bodyAsText()).jsonObject["data"]?.jsonArray.orEmpty()
                .mapNotNull { element ->
                    val obj = element.jsonObject
                    val status = obj["status"]?.jsonPrimitive?.content
                    // Terminal states cost nothing and cannot resume; anything else is cancelled.
                    if (status in TERMINAL_SUBSCRIPTION_STATES) null
                    else obj["id"]?.jsonPrimitive?.content
                }
        }.getOrNull() ?: return null

        var cancelled = 0
        for (id in ids) {
            val response = runCatching {
                http.delete("$apiBase/v1/subscriptions/$id") {
                    stripeHeaders()
                }
            }.getOrNull()
            // ⚠ A partial failure reports `null`, not the partial count: the caller's contract is
            // "did everything stop billing?", and answering "3" when a fourth is still live would
            // read as success and stop the retry that is meant to finish the job.
            if (response == null || !response.status.isSuccess()) return null
            cancelled++
        }
        return cancelled
    }

    override suspend fun billingSubscriptions(customerId: String): List<BillingSubscription>? {
        val listed = runCatching {
            http.get("$apiBase/v1/subscriptions") {
                stripeHeaders()
                parameter("customer", customerId)
                // `status=all` for the reason [cancelActiveSubscriptions] gives: `trialing`,
                // `past_due` and `paused` all still bill or convert to billing.
                parameter("status", "all")
                parameter("limit", SUBSCRIPTION_PAGE_SIZE)
            }
        }.getOrNull() ?: return null
        if (!listed.status.isSuccess()) return null
        return runCatching {
            json.parseToJsonElement(listed.bodyAsText()).jsonObject["data"]?.jsonArray.orEmpty()
                .mapNotNull { element ->
                    val subscription = element.jsonObject
                    if (subscription.string("status") in TERMINAL_SUBSCRIPTION_STATES) return@mapNotNull null
                    val id = subscription.string("id") ?: return@mapNotNull null
                    val prices = ((subscription["items"] as? JsonObject)?.get("data") as? JsonArray).orEmpty()
                        .mapNotNull { ((it as? JsonObject)?.get("price") as? JsonObject)?.string("id") }
                    BillingSubscription(id, prices)
                }
        }.getOrNull()
    }

    override suspend fun refundsForCharge(chargeId: String): List<ChargeRefund>? {
        val listed = runCatching {
            http.get("$apiBase/v1/refunds") {
                stripeHeaders()
                parameter("charge", chargeId)
                parameter("limit", SUBSCRIPTION_PAGE_SIZE)
            }
        }.getOrNull() ?: return null
        if (!listed.status.isSuccess()) return null
        return runCatching {
            json.parseToJsonElement(listed.bodyAsText()).jsonObject["data"]?.jsonArray.orEmpty()
                .mapNotNull { element ->
                    val refund = element.jsonObject
                    // A pending or failed refund has moved no money (yet); it arrives again.
                    if (refund.string("status") != "succeeded") return@mapNotNull null
                    val id = refund.string("id") ?: return@mapNotNull null
                    val amount = (refund["amount"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                        ?: return@mapNotNull null
                    ChargeRefund(id, amount, refund.string("currency"))
                }
        }.getOrNull()
    }

    override suspend fun invoicePaymentEvidence(invoiceId: String): InvoicePaymentEvidence? {
        val response = runCatching {
            http.get("$apiBase/v1/invoice_payments") {
                stripeHeaders()
                parameter("invoice", invoiceId)
                parameter("status", "paid")
                // One call for both halves: the charge hangs four levels down, Stripe's expand limit.
                parameter("expand[]", "data.payment.payment_intent.latest_charge")
            }
        }.getOrNull() ?: return null
        if (!response.status.isSuccess()) return null
        return runCatching {
            val payment = json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("payment") as? JsonObject ?: return null
            val intent = payment["payment_intent"] as? JsonObject
            // A non-card payment has no PaymentIntent to expand; its charge id is then a bare string.
            val charge = intent?.get("latest_charge") as? JsonObject
            InvoicePaymentEvidence(
                paymentRef = intent?.string("id") ?: (payment["charge"] as? JsonPrimitive)?.contentOrNull,
                cardCountry = ((charge?.get("payment_method_details") as? JsonObject)
                    ?.get("card") as? JsonObject)?.string("country"),
            )
        }.getOrNull()
    }

    override suspend fun refundUnusedAndCancel(customerId: String, nowMs: Long): Long? {
        val listed = runCatching {
            http.get("$apiBase/v1/subscriptions") {
                stripeHeaders()
                parameter("customer", customerId)
                parameter("status", "all")
                parameter("limit", SUBSCRIPTION_PAGE_SIZE)
            }
        }.getOrNull() ?: return null
        if (!listed.status.isSuccess()) return null
        val subscriptionIds = runCatching {
            json.parseToJsonElement(listed.bodyAsText()).jsonObject["data"]?.jsonArray.orEmpty()
                .map { it.jsonObject }
                .filter { it.string("status") !in TERMINAL_SUBSCRIPTION_STATES }
                .mapNotNull { it.string("id") }
        }.getOrNull() ?: return null

        var refunded = 0L
        for (subscriptionId in subscriptionIds) {
            refunded += refundUnusedTime(subscriptionId, nowMs) ?: return null
            val cancelled = runCatching {
                http.delete("$apiBase/v1/subscriptions/$subscriptionId") { stripeHeaders() }
            }.getOrNull()
            if (cancelled == null || !cancelled.status.isSuccess()) return null
        }
        return refunded
    }

    /**
     * Refund what [subscriptionId] was paid for the time after [nowMs]; the amount, or null on any
     * failure (retry later).
     *
     * **The source is the subscription's paid invoices, line by line** — not its latest invoice's
     * first line. After a plan change in the portal the latest invoice can be a proration invoice
     * (a credit line for the unused old plan plus a charge for the new one, over the rest of the
     * period), and the period's own invoice is the one before it. Each paid invoice's unused part is
     * its `amount_paid` × the share of its line amounts that pays for time after now
     * ([unusedShareOfInvoice]): a line counts by how much of its own period is still to come, so a
     * past period counts nothing and both halves of a proration net out. Scaling by `amount_paid`
     * carries tax, discounts and an applied customer balance proportionally, and never refunds more
     * than the invoice took. Only the newest [RECENT_INVOICES] are read: only the current period's
     * can have time left.
     */
    private suspend fun refundUnusedTime(subscriptionId: String, nowMs: Long): Long? {
        val response = runCatching {
            http.get("$apiBase/v1/invoices") {
                stripeHeaders()
                parameter("subscription", subscriptionId)
                parameter("status", "paid")
                parameter("limit", RECENT_INVOICES)
            }
        }.getOrNull() ?: return null
        if (!response.status.isSuccess()) return null
        val invoices = runCatching {
            json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]?.jsonArray.orEmpty().map { it.jsonObject }
        }.getOrNull() ?: return null
        var refunded = 0L
        for (invoice in invoices) {
            val invoiceId = invoice.string("id") ?: return null
            val lines = ((invoice["lines"] as? JsonObject)?.get("data") as? JsonArray).orEmpty().mapNotNull { line ->
                val obj = line as? JsonObject ?: return@mapNotNull null
                val period = obj["period"] as? JsonObject ?: return@mapNotNull null
                InvoiceLineShare(
                    amount = obj.long("amount") ?: return@mapNotNull null,
                    periodStartMs = (period.long("start") ?: return@mapNotNull null) * 1000,
                    periodEndMs = (period.long("end") ?: return@mapNotNull null) * 1000,
                )
            }
            // Paid outside Stripe: nothing Stripe can refund.
            if ((invoice["paid_out_of_band"] as? JsonPrimitive)?.contentOrNull == "true") continue
            val amountPaid = invoice.long("amount_paid") ?: 0

            val unused = unusedShareOfInvoice(amountPaid, lines, nowMs)
            if (unused <= 0) continue
            refunded += refundInvoice(subscriptionId, invoiceId, amountPaid, unused) ?: return null
        }
        return refunded
    }

    /**
     * Refund [unused] of [invoiceId]'s payment, once. The amount refunded (or already refunded for
     * this switch), or null to retry later.
     *
     * ⚠⚠ "Once" is decided by Stripe's refund list, not by the idempotency key. Stripe stores the
     * first response under a key — a 500 included — and prunes keys after 24 h, and the amount
     * moves with the clock. So an earlier switch refund (metadata `reason=rail_switch`, this
     * subscription) on the payment is the proof of done, and its amount is what counts. The key
     * (subscription, invoice, amount) only collapses two attempts racing in the same moment; an
     * idempotency error is "try again later", never "already refunded".
     */
    private suspend fun refundInvoice(subscriptionId: String, invoiceId: String, amountPaid: Long, unused: Long): Long? {
        // ⚠ "Nothing to refund" is an answer, not a failure: an invoice paid out of band (or by
        // credit alone) has no payment to refund, and failing on it would fail the switch forever —
        // Google verify answering 502 for good on a purchase it already acknowledged.
        val paymentRef = when (val payment = invoicePayment(invoiceId)) {
            InvoicePaymentLookup.Failed -> return null
            InvoicePaymentLookup.None -> return 0
            is InvoicePaymentLookup.Ref -> payment.ref
        }
        val paymentParam = if (paymentRef.startsWith("pi_")) "payment_intent" else "charge"
        val listed = runCatching {
            http.get("$apiBase/v1/refunds") {
                stripeHeaders()
                parameter(paymentParam, paymentRef)
                parameter("limit", SUBSCRIPTION_PAGE_SIZE)
            }
        }.getOrNull() ?: return null
        if (!listed.status.isSuccess()) return null
        val refunds = runCatching {
            json.parseToJsonElement(listed.bodyAsText()).jsonObject["data"]?.jsonArray.orEmpty()
                .map { it.jsonObject }
                .filter { it.string("status") !in DEAD_REFUND_STATES }
        }.getOrNull() ?: return null
        // ⚠ A `pending` switch refund counts as done, deliberately. Stripe has accepted it and the
        // money is in flight (non-card methods settle later); refunding again would refund twice if
        // both settle, and waiting would hold the cancel below — leaving the Stripe subscription
        // billing beside the new one. A switch refund that ends `failed` or `canceled` stops
        // counting (DEAD_REFUND_STATES), so a later attempt refunds again; one that fails after the
        // subscription was cancelled is not retried by anything here — Stripe reports it as
        // `refund.failed`, handled by hand like any other failed refund.
        refunds.firstOrNull { refund ->
            val metadata = refund["metadata"] as? JsonObject
            metadata?.string("reason") == RAIL_SWITCH && metadata.string("subscription") == subscriptionId
        }?.let { return it.long("amount") ?: 0 }
        // Never more than is left on the payment: a refund made by hand counts against it.
        val amount = unused.coerceAtMost(amountPaid - refunds.sumOf { it.long("amount") ?: 0 })
        if (amount <= 0) return 0
        val response = runCatching {
            http.submitForm(
                url = "$apiBase/v1/refunds",
                formParameters = Parameters.build {
                    append(paymentParam, paymentRef)
                    append("amount", amount.toString())
                    append("metadata[reason]", RAIL_SWITCH)
                    append("metadata[subscription]", subscriptionId)
                    append("metadata[invoice]", invoiceId)
                },
            ) {
                stripeHeaders()
                header("Idempotency-Key", "rail-switch-refund-$subscriptionId-$invoiceId-$amount")
            }
        }.getOrNull() ?: return null
        return if (response.status.isSuccess()) amount else null
    }

    private sealed interface InvoicePaymentLookup {
        data object Failed : InvoicePaymentLookup
        data object None : InvoicePaymentLookup
        data class Ref(val ref: String) : InvoicePaymentLookup
    }

    /** The PaymentIntent (or charge) that paid [invoiceId]; [InvoicePaymentLookup.None] when nothing did. */
    private suspend fun invoicePayment(invoiceId: String): InvoicePaymentLookup {
        val response = runCatching {
            http.get("$apiBase/v1/invoice_payments") {
                stripeHeaders()
                parameter("invoice", invoiceId)
                parameter("status", "paid")
            }
        }.getOrNull() ?: return InvoicePaymentLookup.Failed
        if (!response.status.isSuccess()) return InvoicePaymentLookup.Failed
        return runCatching {
            val payment = json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("payment") as? JsonObject
                ?: return InvoicePaymentLookup.None
            val intent = payment["payment_intent"]
            val ref = (intent as? JsonObject)?.string("id")
                ?: (intent as? JsonPrimitive)?.contentOrNull
                ?: (payment["charge"] as? JsonObject)?.string("id")
                ?: (payment["charge"] as? JsonPrimitive)?.contentOrNull
            ref?.takeIf { it.isNotBlank() }?.let { InvoicePaymentLookup.Ref(it) } ?: InvoicePaymentLookup.None
        }.getOrDefault(InvoicePaymentLookup.Failed)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private suspend fun io.ktor.client.statement.HttpResponse.readUrlOrNull(): String? {
        if (!status.isSuccess()) return null
        val url = runCatching {
            json.parseToJsonElement(bodyAsText()).jsonObject["url"]?.jsonPrimitive?.content
        }.getOrNull()
        return url?.takeIf { it.isNotBlank() }
    }

    companion object {
        const val DEFAULT_API_BASE = "https://api.stripe.com"

        /** The version every shape here was checked against on the live test-mode pass. */
        const val API_VERSION = "2026-06-24.dahlia"

        /** One page is plenty: a customer with >100 subscriptions is a data problem, not a case. */
        const val SUBSCRIPTION_PAGE_SIZE = 100

        /** Only the current period's invoices can have time left; a few cover a plan change in it. */
        const val RECENT_INVOICES = 10
        const val RAIL_SWITCH = "rail_switch"

        /** Refunds that moved no money and never will. */
        val DEAD_REFUND_STATES = setOf("failed", "canceled")


        /**
         * Stripe statuses that neither bill nor can resume. Everything else — including `trialing`
         * and `past_due` — is cancelled on closure.
         */
        val TERMINAL_SUBSCRIPTION_STATES = setOf("canceled", "incomplete_expired")
    }
}

/** One invoice line as [unusedShareOfInvoice] reads it: its amount (minor units, may be negative) and period. */
internal data class InvoiceLineShare(val amount: Long, val periodStartMs: Long, val periodEndMs: Long)

/**
 * The part of an invoice's [amountPaid] that pays for time after [nowMs]: each line counts by the
 * share of its own period still to come, and the result is that share of the lines' total, applied
 * to what was actually paid (so tax, discounts and an applied balance scale with it). Rounded down,
 * clamped to `[0, amountPaid]`. A proration pair (credit for the old plan's rest, charge for the
 * new one's) nets out to exactly the difference paid for the time left.
 */
internal fun unusedShareOfInvoice(amountPaid: Long, lines: List<InvoiceLineShare>, nowMs: Long): Long {
    val total = lines.sumOf { it.amount }
    if (amountPaid <= 0 || total <= 0) return 0
    // Exact rational arithmetic over a common denominator (the product of the line lengths), so
    // rounding happens once, at the end, and always down.
    val timed = lines.filter { it.periodEndMs > it.periodStartMs }
    val denominator = timed.fold(BigInteger.ONE) { d, line -> d * (line.periodEndMs - line.periodStartMs).toBigInteger() }
    val numerator = timed.fold(BigInteger.ZERO) { n, line ->
        val length = line.periodEndMs - line.periodStartMs
        val remaining = (line.periodEndMs - maxOf(nowMs, line.periodStartMs)).coerceIn(0, length)
        n + line.amount.toBigInteger() * remaining.toBigInteger() * (denominator / length.toBigInteger())
    }
    if (numerator.signum() <= 0) return 0
    val unused = amountPaid.toBigInteger() * numerator / (denominator * total.toBigInteger())
    return unused.toLong().coerceIn(0, amountPaid)
}
