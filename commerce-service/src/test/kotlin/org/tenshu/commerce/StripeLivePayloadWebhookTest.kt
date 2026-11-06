package org.tenshu.commerce

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.commerce.routing.StripeSupport
import org.tenshu.commerce.store.InMemoryCommerceStore
import org.tenshu.commerce.stripe.BillingSubscription
import org.tenshu.commerce.stripe.ChargeRefund
import org.tenshu.commerce.stripe.CheckoutSessionRequest
import org.tenshu.commerce.stripe.InvoicePaymentEvidence
import org.tenshu.commerce.stripe.StripeClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `invoice.paid` in the shape live Stripe actually sends it (test mode, API `2026-06-24.dahlia`,
 * trimmed from the first real purchase). The hand-written fixtures elsewhere carry `tax`,
 * `customer_details` and a payment reference on the invoice; the real event carries none of the
 * three, and the invoice row came out with no country, no VAT and no payment reference.
 */
class StripeLivePayloadWebhookTest {

    private val store = InMemoryCommerceStore()
    private val webhookSecret = "whsec_test"

    private class LookupStripe(private val evidence: InvoicePaymentEvidence?) : StripeClient {
        val lookedUp = mutableListOf<String>()
        override suspend fun createCheckoutSession(request: CheckoutSessionRequest): String? = null
        override suspend fun createPortalSession(customerId: String, returnUrl: String): String? = null
        override suspend fun cancelActiveSubscriptions(customerId: String): Int = 0
        override suspend fun billingSubscriptions(customerId: String): List<BillingSubscription>? = emptyList()
        override suspend fun refundsForCharge(chargeId: String): List<ChargeRefund>? = emptyList()
        override suspend fun invoicePaymentEvidence(invoiceId: String): InvoicePaymentEvidence? {
            lookedUp += invoiceId
            return evidence
        }
    }

    private fun postInvoicePaid(stripe: StripeClient, payload: String = LIVE_INVOICE_PAID) = testApplication {
        val support = StripeSupport(
            client = stripe,
            priceIds = mapOf(ProductCatalog.BASIC to "price_basic", ProductCatalog.PREMIUM to "price_premium"),
            webhookSecret = webhookSecret,
            successUrl = "tenshu://ok",
            cancelUrl = "tenshu://no",
            portalReturnUrl = "tenshu://back",
        )
        application { module(store = store, stripe = support, now = { FIXED_NOW }) }
        val ts = FIXED_NOW / 1000
        val response = client.post("/commerce/webhook/stripe") {
            contentType(ContentType.Application.Json)
            header("Stripe-Signature", "t=$ts,v1=${hmacSha256Hex(webhookSecret, "$ts.$payload")}")
            setBody(payload)
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun liveInvoice_recordsCountryTaxAndThePaymentBehindIt() {
        val stripe = LookupStripe(InvoicePaymentEvidence(paymentRef = "pi_live_1", cardCountry = "US"))
        postInvoicePaid(stripe)

        val invoice = store.allInvoices().single()
        assertEquals("DE", invoice.billingCountry)
        assertEquals(48, invoice.vatAmountMinor)
        assertEquals(1912, invoice.vatRateBasisPoints) // 48 / (299 - 48)
        assertEquals("pi_live_1", invoice.paymentRef)
        assertEquals("US", invoice.ipCountry)
        assertEquals(listOf("in_live_1"), stripe.lookedUp)
    }

    @Test
    fun failedLookup_stillBooksTheSale_withTheGapsLeftEmpty() {
        postInvoicePaid(LookupStripe(evidence = null))

        val invoice = store.allInvoices().single()
        assertEquals("DE", invoice.billingCountry)
        assertNull(invoice.paymentRef)
        // Never the billing country copied across: two identical evidences are not two evidences.
        assertNull(invoice.ipCountry)
    }

    @Test
    fun payloadThatAlreadyNamesBoth_isNotLookedUp() {
        val stripe = LookupStripe(InvoicePaymentEvidence(paymentRef = "pi_other", cardCountry = "FR"))
        postInvoicePaid(
            stripe,
            LIVE_INVOICE_PAID.replace(
                "\"currency\":\"eur\",",
                "\"currency\":\"eur\",\"payment_intent\":\"pi_inline\"," +
                    "\"payment_method_details\":{\"card\":{\"country\":\"AT\"}},",
            ),
        )

        val invoice = store.allInvoices().single()
        assertEquals("pi_inline", invoice.paymentRef)
        assertEquals("AT", invoice.ipCountry)
        assertEquals(emptyList(), stripe.lookedUp)
    }

    /**
     * ⚠⚠ A plan change made on the subscription does not rewrite the `product_id` metadata written
     * at checkout. Read from metadata alone, a customer who moved to Premium is granted Basic on
     * this invoice and on every renewal after it.
     */
    @Test
    fun planChangeInvoice_grantsTheTierItCharges_notTheOneInStaleMetadata() {
        postInvoicePaid(LookupStripe(evidence = null), PLAN_CHANGE_INVOICE)

        val account = store.findAccountByEmail("buyer@x.com")!!
        assertEquals(ProductCatalog.PREMIUM, store.currentEntitlement(account.id, FIXED_NOW)?.productId)
    }

    @Test
    fun aPriceThisDeploymentDoesNotMap_fallsBackToTheMetadata() {
        postInvoicePaid(LookupStripe(evidence = null), PLAN_CHANGE_INVOICE.replace("price_premium", "price_unknown"))

        val account = store.findAccountByEmail("buyer@x.com")!!
        assertEquals(ProductCatalog.BASIC, store.currentEntitlement(account.id, FIXED_NOW)?.productId)
    }

    private companion object {
        const val FIXED_NOW = 1_700_000_000_000L

        /** Basic → Premium mid-period: a credit for the unused Basic, a charge for the Premium. */
        val PLAN_CHANGE_INVOICE = """
            {"id":"evt_change_1","type":"invoice.paid","data":{"object":{
              "id":"in_change_1","object":"invoice","amount_paid":700,"billing_reason":"subscription_update",
              "currency":"eur","customer":"cus_live_1","customer_email":"buyer@x.com",
              "customer_address":{"country":"DE"},
              "lines":{"object":"list","data":[
                {"id":"il_credit","amount":-299,"period":{"end":${FIXED_NOW / 1000 + 2_678_400},"start":${FIXED_NOW / 1000}},
                 "pricing":{"price_details":{"price":"price_basic","product":"prod_b"},"type":"price_details"}},
                {"id":"il_charge","amount":999,"period":{"end":${FIXED_NOW / 1000 + 2_678_400},"start":${FIXED_NOW / 1000}},
                 "pricing":{"price_details":{"price":"price_premium","product":"prod_p"},"type":"price_details"}}]},
              "metadata":{},
              "parent":{"subscription_details":{
                "metadata":{"owner_id":"owner-live","product_id":"${ProductCatalog.BASIC}"},
                "subscription":"sub_live_1"},"type":"subscription_details"},
              "status":"paid","total":700}}}
        """.trimIndent()

        val LIVE_INVOICE_PAID = """
            {"id":"evt_live_1","type":"invoice.paid","api_version":"2026-06-24.dahlia","data":{"object":{
              "id":"in_live_1","object":"invoice","amount_due":299,"amount_paid":299,
              "billing_reason":"subscription_create","currency":"eur",
              "customer":"cus_live_1",
              "customer_address":{"city":null,"country":"DE","line1":null,"postal_code":null},
              "customer_email":"buyer@x.com",
              "lines":{"object":"list","data":[{"id":"il_1","amount":299,
                "pricing":{"price_details":{"price":"price_basic","product":"prod_b"},"type":"price_details"},
                "metadata":{"owner_id":"owner-live","product_id":"${ProductCatalog.BASIC}"},
                "period":{"end":${FIXED_NOW / 1000 + 2_678_400},"start":${FIXED_NOW / 1000}}}]},
              "metadata":{},
              "parent":{"quote_details":null,"subscription_details":{
                "metadata":{"owner_id":"owner-live","product_id":"${ProductCatalog.BASIC}"},
                "subscription":"sub_live_1"},"type":"subscription_details"},
              "period_end":${FIXED_NOW / 1000},"period_start":${FIXED_NOW / 1000},
              "status":"paid","subtotal":299,"total":299,
              "total_taxes":[{"amount":48,"tax_behavior":"inclusive","taxability_reason":"standard_rated",
                "taxable_amount":251,"type":"tax_rate_details"}]}}}
        """.trimIndent()
    }
}
