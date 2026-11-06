package org.tenshu.commerce

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.commerce.routing.StripeSupport
import org.tenshu.commerce.store.EntitlementSource
import org.tenshu.commerce.store.InMemoryCommerceStore
import org.tenshu.commerce.store.Rail
import org.tenshu.commerce.stripe.BillingSubscription
import org.tenshu.commerce.stripe.ChargeRefund
import org.tenshu.commerce.stripe.CheckoutSessionRequest
import org.tenshu.commerce.stripe.InvoicePaymentEvidence
import org.tenshu.commerce.stripe.StripeClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HTTP coverage for `/commerce/webhook/stripe` (§6): a signature-verified `invoice.paid` creates the
 * account from email, binds the owner, grants the paid entitlement, and writes the statutory invoice;
 * a forged signature is rejected before any write; duplicate event ids are idempotent.
 */
class StripeWebhookRoutingTest {

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

    private suspend fun HttpClient.postWebhook(payload: String, sig: String = signature(payload)) =
        post("/commerce/webhook/stripe") {
            contentType(ContentType.Application.Json)
            header("Stripe-Signature", sig)
            setBody(payload)
        }

    private fun invoicePaid(
        eventId: String = "evt_1",
        email: String = "buyer@x.com",
        productId: String = ProductCatalog.PREMIUM,
    ): String = """
        {"id":"$eventId","type":"invoice.paid","data":{"object":{
          "id":"in_1","customer":"cus_1","customer_email":"$email","client_reference_id":"owner-9",
          "amount_paid":1190,"currency":"eur","tax":190,
          "metadata":{"product_id":"$productId","owner_id":"owner-9"},
          "current_period_end":1701000000}}}
    """.trimIndent()

    @Test
    fun invoicePaid_createsAccountEntitlementInvoiceAndBinds() = appTest { client ->
        val response = client.postWebhook(invoicePaid())
        assertEquals(HttpStatusCode.OK, response.status)

        val account = store.findAccountByEmail("buyer@x.com")
        assertNotNull(account)
        assertEquals(Rail.Stripe, account.rail)
        assertEquals("cus_1", account.stripeCustomerId)
        assertEquals(account.id, store.accountIdForOwner("owner-9"))

        val current = store.currentEntitlement(account.id, FIXED_NOW)
        assertNotNull(current)
        assertEquals(ProductCatalog.PREMIUM, current.productId)
        assertEquals(EntitlementSource.Stripe, current.source)
        assertEquals(1_701_000_000_000L, current.validUntil)

        val invoices = store.allInvoices()
        assertEquals(1, invoices.size)
        assertEquals(1190, invoices[0].amountMinor)
        assertEquals(190, invoices[0].vatAmountMinor)
        assertEquals(1900, invoices[0].vatRateBasisPoints) // 190 / (1190-190) = 19.00%
        assertEquals("in_1", invoices[0].stripeInvoiceId)
    }

    @Test
    fun forgedSignature_isRejectedWithNoWrites() = appTest { client ->
        val payload = invoicePaid()
        val response = client.postWebhook(payload, sig = "t=${FIXED_NOW / 1000},v1=deadbeef")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertNull(store.findAccountByEmail("buyer@x.com"))
        assertTrue(store.allInvoices().isEmpty())
    }

    @Test
    fun duplicateEvent_isIdempotent() = appTest { client ->
        assertEquals(HttpStatusCode.OK, client.postWebhook(invoicePaid(eventId = "evt_dup")).status)
        val second = client.postWebhook(invoicePaid(eventId = "evt_dup"))
        assertEquals(HttpStatusCode.OK, second.status)
        assertTrue("duplicate" in second.bodyAsText())
        // The paid entitlement + invoice were written exactly once.
        assertEquals(1, store.allInvoices().size)
    }

    @Test
    fun missingProductId_isAcknowledgedButGrantsNothing() = appTest { client ->
        val payload = """
            {"id":"evt_2","type":"invoice.paid","data":{"object":{
              "id":"in_2","customer":"cus_2","customer_email":"noproduct@x.com","amount_paid":500}}}
        """.trimIndent()
        val response = client.postWebhook(payload)
        assertEquals(HttpStatusCode.OK, response.status)
        assertNull(store.findAccountByEmail("noproduct@x.com"))
        assertTrue(store.allInvoices().isEmpty())
    }

    @Test
    fun stripeDisabled_isServiceUnavailable() = testApplication {
        application { module(store = store, stripe = null, now = { FIXED_NOW }) }
        val response = client.postWebhook(invoicePaid())
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    private companion object {
        const val FIXED_NOW = 1_700_000_000_000L
    }
}
