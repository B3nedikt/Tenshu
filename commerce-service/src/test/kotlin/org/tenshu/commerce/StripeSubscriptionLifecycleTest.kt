package org.tenshu.commerce

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.commerce.routing.StripeSupport
import org.tenshu.commerce.store.AcceptanceKind
import org.tenshu.commerce.store.InMemoryCommerceStore
import org.tenshu.commerce.stripe.BillingSubscription
import org.tenshu.commerce.stripe.ChargeRefund
import org.tenshu.commerce.stripe.CheckoutSessionRequest
import org.tenshu.commerce.stripe.InvoicePaymentEvidence
import org.tenshu.commerce.stripe.StripeClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Stripe subscription lifecycle across **both** events the deployment subscribes to
 * (`deploy-commerce-digitalocean.md` step 2) — WC-1b.
 *
 * Two field-mapping defects lived here and were **entangled**, which is why they are fixed and
 * tested together:
 *
 *  1. **Renewals granted nothing.** `StripeClient` sets `subscription_data[metadata][product_id]`
 *     so the tier survives past checkout, but Stripe exposes a subscription's metadata on the
 *     invoice as `parent.subscription_details.metadata` (an immutable snapshot at finalization) —
 *     *not* as the invoice's own `metadata`, which was all this code read. Every `invoice.paid`
 *     therefore looked tier-less and was skipped, and a renewal arrives **only** as `invoice.paid`.
 *  2. **The paid window was the TTL, not the billing period.** `current_period_end` is a
 *     *Subscription* field and appears on neither object, so `validUntil` always fell back to
 *     7 days while the customer was billed monthly.
 *
 * Net before the fix: a paying customer got ~7 days of the paid tier and then silently dropped to
 * Free, because the client renewal tick re-mints from `currentEntitlement`, which by then resolves
 * to the ~permanent Free row.
 *
 * ⚠⚠ Fixing (1) alone would have **created** a double-write — with a readable tier on both events,
 * both would book a statutory invoice for one payment. Hence `invoice.paid` is now the sole invoice
 * writer, pinned by [newSubscriptionCheckout_writesExactlyOneStatutoryInvoice].
 *
 * Field shapes verified against `docs.stripe.com/api/invoices/object` +
 * `docs.stripe.com/api/checkout/sessions/object` (2026-08-09). A live test-mode round-trip is still
 * owed (§6) — these fixtures are the documented shapes, not captured payloads.
 */
class StripeSubscriptionLifecycleTest {

    private val store = InMemoryCommerceStore()
    private val webhookSecret = "whsec_test"

    private object NoopStripeClient : StripeClient {
        override suspend fun createCheckoutSession(request: CheckoutSessionRequest): String? = null
        override suspend fun createPortalSession(customerId: String, returnUrl: String): String? = null
        override suspend fun billingSubscriptions(customerId: String): List<BillingSubscription>? = emptyList()
        override suspend fun refundsForCharge(chargeId: String): List<ChargeRefund>? = emptyList()
        override suspend fun invoicePaymentEvidence(invoiceId: String): InvoicePaymentEvidence? = null
        override suspend fun cancelActiveSubscriptions(customerId: String): Int = 0
    }

    private val support = StripeSupport(
        client = NoopStripeClient,
        priceIds = emptyMap(),
        webhookSecret = webhookSecret,
        successUrl = "tenshu://ok",
        cancelUrl = "tenshu://no",
        portalReturnUrl = "tenshu://back",
    )

    private fun appTest(block: suspend (HttpClient) -> Unit) = testApplication {
        application { module(store = store, stripe = support, now = { FIXED_NOW }) }
        block(client)
    }

    private fun signature(payload: String): String {
        val ts = FIXED_NOW / 1000
        return "t=$ts,v1=${hmacSha256Hex(webhookSecret, "$ts.$payload")}"
    }

    private suspend fun HttpClient.postWebhook(payload: String) =
        post("/commerce/webhook/stripe") {
            contentType(ContentType.Application.Json)
            header("Stripe-Signature", signature(payload))
            setBody(payload)
        }

    // --- fixtures ---------------------------------------------------------------------------

    /** The Checkout Session: carries the tier + the withdrawal ack, but no period of any kind. */
    private fun checkoutSessionCompleted(): String = """
        {"id":"evt_cs","type":"checkout.session.completed","data":{"object":{
          "id":"cs_1","customer":"cus_1","customer_email":"buyer@x.com","client_reference_id":"owner-9",
          "customer_details":{"email":"buyer@x.com","address":{"country":"DE"}},
          "amount_total":1190,"currency":"eur","tax":190,
          "metadata":{"product_id":"${ProductCatalog.PREMIUM}","owner_id":"owner-9",
                      "withdrawal_ack_tos":"2026-08-06"}}}}
    """.trimIndent()

    /**
     * The invoice for that purchase, and monthly thereafter. The tier rides on
     * `parent.subscription_details.metadata`; the invoice's own metadata is empty, as Stripe sends it.
     * [legacyShape] uses the older top-level `subscription_details` spelling.
     */
    private fun invoicePaid(
        eventId: String,
        periodEndSeconds: Long,
        legacyShape: Boolean = false,
        lineItemPeriod: Boolean = true,
    ): String {
        val subscriptionMetadata = """{"metadata":{"product_id":"${ProductCatalog.PREMIUM}","owner_id":"owner-9"}}"""
        val details = if (legacyShape) {
            """"subscription_details":$subscriptionMetadata,"""
        } else {
            """"parent":{"subscription_details":$subscriptionMetadata},"""
        }
        val lines = if (lineItemPeriod) {
            """"lines":{"data":[{"period":{"end":$periodEndSeconds}}]},"""
        } else {
            ""
        }
        return """
            {"id":"$eventId","type":"invoice.paid","data":{"object":{
              "id":"in_$eventId","customer":"cus_1","customer_email":"buyer@x.com",
              "customer_details":{"email":"buyer@x.com","address":{"country":"DE"}},
              "amount_paid":1190,"currency":"eur","tax":190,"metadata":{},
              $details$lines
              "period_start":${FIXED_NOW / 1000},"period_end":$periodEndSeconds,
              "subscription":"sub_1"}}}
        """.trimIndent()
    }

    // --- the double-write, now prevented -----------------------------------------------------

    @Test
    fun newSubscriptionCheckout_writesExactlyOneStatutoryInvoice() = appTest { client ->
        client.postWebhook(checkoutSessionCompleted())
        client.postWebhook(invoicePaid("evt_in", periodEndSeconds = ONE_MONTH_OUT_SECONDS))

        // ⚠⚠ Both events describe ONE payment. The invoice belongs to `invoice.paid`, which IS the
        // invoice; a Checkout Session has no number, no period and no tax breakdown of its own.
        val invoices = store.allInvoices()
        assertEquals(1, invoices.size)
        assertEquals(1190, invoices.single().amountMinor)
        assertEquals(190, invoices.single().vatAmountMinor)
        assertEquals("in_evt_in", invoices.single().stripeInvoiceId, "the invoice event owns the row")
    }

    @Test
    fun theCheckoutEventStillGrantsImmediately_soAPaidCustomerIsNeverLeftWaiting() = appTest { client ->
        client.postWebhook(checkoutSessionCompleted())

        val account = assertNotNull(store.findAccountByEmail("buyer@x.com"))
        assertEquals(ProductCatalog.PREMIUM, store.currentEntitlement(account.id, FIXED_NOW)?.productId)
        assertTrue(store.allInvoices().isEmpty(), "…but it does not book the invoice")
    }

    // --- the dead renewal, now alive ---------------------------------------------------------

    @Test
    fun renewalExtendsThePaidWindow_readingTheTierFromTheSubscriptionSnapshot() = appTest { client ->
        client.postWebhook(checkoutSessionCompleted())
        val account = assertNotNull(store.findAccountByEmail("buyer@x.com"))
        val afterCheckout = assertNotNull(store.currentEntitlement(account.id, FIXED_NOW)).validUntil

        client.postWebhook(invoicePaid("evt_renewal", periodEndSeconds = ONE_MONTH_OUT_SECONDS))

        val extended = assertNotNull(store.currentEntitlement(account.id, FIXED_NOW))
        assertEquals(ProductCatalog.PREMIUM, extended.productId)
        assertTrue(extended.validUntil > afterCheckout, "the renewal must push the window out")
        assertEquals(ONE_MONTH_OUT_SECONDS * 1000, extended.validUntil)
    }

    /** The pre-`parent` API spelling must work too — the endpoint's API version decides which arrives. */
    @Test
    fun legacyTopLevelSubscriptionDetails_isAlsoRead() = appTest { client ->
        client.postWebhook(invoicePaid("evt_legacy", ONE_MONTH_OUT_SECONDS, legacyShape = true))

        val account = assertNotNull(store.findAccountByEmail("buyer@x.com"))
        assertEquals(ProductCatalog.PREMIUM, store.currentEntitlement(account.id, FIXED_NOW)?.productId)
    }

    // --- the window follows the billing period ------------------------------------------------

    @Test
    fun paidWindowFollowsTheInvoicePeriod_notTheSevenDayTtl() = appTest { client ->
        client.postWebhook(invoicePaid("evt_in", periodEndSeconds = ONE_MONTH_OUT_SECONDS))

        val account = assertNotNull(store.findAccountByEmail("buyer@x.com"))
        val sevenDaysMs = CommerceConfig.DEFAULT_ENTITLEMENT_TTL_HOURS * 60 * 60 * 1000
        val validUntil = assertNotNull(store.currentEntitlement(account.id, FIXED_NOW)).validUntil
        assertEquals(ONE_MONTH_OUT_SECONDS * 1000, validUntil)
        assertTrue(validUntil > FIXED_NOW + sevenDaysMs, "a monthly subscription must outlast the TTL")
    }

    /**
     * ⚠ An invoice's top-level `period_start`/`period_end` are legitimately equal when nothing
     * accrued over the span — Stripe's own reference example shows both at the creation timestamp.
     * Using such a period would grant a **zero-length** entitlement, strictly worse than the fallback
     * it replaced, so a non-future candidate is refused.
     */
    @Test
    fun aDegeneratePeriodFallsBackToTheTtl_ratherThanGrantingNothing() = appTest { client ->
        val degenerate = FIXED_NOW / 1000 // period_end == period_start == now
        client.postWebhook(invoicePaid("evt_in", periodEndSeconds = degenerate, lineItemPeriod = false))

        val account = assertNotNull(store.findAccountByEmail("buyer@x.com"))
        val sevenDaysMs = CommerceConfig.DEFAULT_ENTITLEMENT_TTL_HOURS * 60 * 60 * 1000
        assertEquals(
            FIXED_NOW + sevenDaysMs,
            assertNotNull(store.currentEntitlement(account.id, FIXED_NOW)).validUntil,
        )
    }

    // --- things a renewal must NOT do ---------------------------------------------------------

    @Test
    fun aRenewalNeverManufacturesAWithdrawalAcknowledgement() = appTest { client ->
        client.postWebhook(checkoutSessionCompleted())
        client.postWebhook(invoicePaid("evt_renewal", ONE_MONTH_OUT_SECONDS))

        // Exactly one waiver — from the purchase. A renewal is not a fresh contract, and the ack is
        // read from the object's OWN metadata precisely so this cannot drift.
        assertEquals(
            1,
            store.acceptances().count { it.kind == AcceptanceKind.PurchaseWithdrawalWaiver },
        )
    }

    @Test
    fun aRenewalDoesNotReBindTheSameOwner() = appTest { client ->
        client.postWebhook(checkoutSessionCompleted())
        client.postWebhook(invoicePaid("evt_r1", ONE_MONTH_OUT_SECONDS))
        client.postWebhook(invoicePaid("evt_r2", ONE_MONTH_OUT_SECONDS + 100))

        val account = assertNotNull(store.findAccountByEmail("buyer@x.com"))
        assertEquals("owner-9", store.liveOwner(account.id))
        // Churning an `account_owner` row per renewal would pollute the A30 history for no change.
        assertEquals(account.id, store.accountIdForOwner("owner-9"))
    }

    private companion object {
        const val FIXED_NOW = 1_700_000_000_000L
        const val ONE_MONTH_OUT_SECONDS = (FIXED_NOW / 1000) + 30L * 24 * 60 * 60
    }
}
