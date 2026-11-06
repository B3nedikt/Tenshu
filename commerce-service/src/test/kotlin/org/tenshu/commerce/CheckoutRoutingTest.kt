package org.tenshu.commerce

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.tenshu.commerce.routing.CheckoutResponse
import org.tenshu.commerce.routing.PortalResponse
import org.tenshu.commerce.routing.StripeSupport
import org.tenshu.commerce.store.InMemoryCommerceStore
import org.tenshu.commerce.store.Rail
import org.tenshu.commerce.stripe.BillingSubscription
import org.tenshu.commerce.stripe.ChargeRefund
import org.tenshu.commerce.stripe.CheckoutSessionRequest
import org.tenshu.commerce.stripe.InvoicePaymentEvidence
import org.tenshu.commerce.stripe.StripeClient
import kotlin.test.Test
import kotlin.test.assertEquals

/** HTTP coverage for `/commerce/checkout` + `/commerce/portal` (§6) with a fake [StripeClient]. */
class CheckoutRoutingTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val store = InMemoryCommerceStore()

    private class FakeStripeClient(
        val checkoutUrl: String? = "https://checkout.stripe.com/c/pay/cs_1",
        val portalUrl: String? = "https://billing.stripe.com/p/session/bps_1",
    ) : StripeClient {
        var lastCheckout: CheckoutSessionRequest? = null
        var subscriptions: List<BillingSubscription>? = emptyList()
        override suspend fun createCheckoutSession(request: CheckoutSessionRequest): String? {
            lastCheckout = request
            return checkoutUrl
        }

        override suspend fun createPortalSession(customerId: String, returnUrl: String): String? = portalUrl

        // Not exercised here; closure/cancellation lives in SubscriptionCancellerTest.
        override suspend fun billingSubscriptions(customerId: String): List<BillingSubscription>? = subscriptions
        override suspend fun refundsForCharge(chargeId: String): List<ChargeRefund>? = emptyList()
        override suspend fun invoicePaymentEvidence(invoiceId: String): InvoicePaymentEvidence? = null
        override suspend fun cancelActiveSubscriptions(customerId: String): Int = 0
    }

    private fun support(client: StripeClient) = StripeSupport(
        client = client,
        priceIds = mapOf(ProductCatalog.BASIC to "price_basic", ProductCatalog.PREMIUM to "price_premium"),
        webhookSecret = "whsec_test",
        successUrl = "tenshu://ok",
        cancelUrl = "tenshu://no",
        portalReturnUrl = "tenshu://back",
    )

    private fun appTest(stripe: StripeSupport?, block: suspend (HttpClient) -> Unit) = testApplication {
        application { module(store = store, stripe = stripe, now = { FIXED_NOW }) }
        block(client)
    }

    @Test
    fun checkout_knownProduct_returnsUrl() {
        val fake = FakeStripeClient()
        appTest(support(fake)) { client ->
            val response = client.post("/commerce/checkout") {
                contentType(ContentType.Application.Json)
                setBody("""{"ownerId":"owner-1","productId":"${ProductCatalog.BASIC}","withdrawalAcknowledged":true,"tosVersion":"2026-08-06"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                "https://checkout.stripe.com/c/pay/cs_1",
                json.decodeFromString<CheckoutResponse>(response.bodyAsText()).checkoutUrl,
            )
            assertEquals("price_basic", fake.lastCheckout?.priceId)
            assertEquals("owner-1", fake.lastCheckout?.ownerId)
        }
    }

    @Test
    fun checkout_unknownProduct_isBadRequest() = appTest(support(FakeStripeClient())) { client ->
        val response = client.post("/commerce/checkout") {
            contentType(ContentType.Application.Json)
            setBody("""{"ownerId":"owner-1","productId":"tenshu.drive.nonesuch","withdrawalAcknowledged":true,"tosVersion":"2026-08-06"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun checkout_stripeDisabled_isServiceUnavailable() = appTest(stripe = null) { client ->
        val response = client.post("/commerce/checkout") {
            contentType(ContentType.Application.Json)
            setBody("""{"ownerId":"owner-1","productId":"${ProductCatalog.BASIC}","withdrawalAcknowledged":true,"tosVersion":"2026-08-06"}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun portal_withCustomer_returnsUrl() {
        val account = store.createAccount("a@b.com", Rail.Stripe, FIXED_NOW)
        store.attachStripeCustomer(account.id, "cus_1")
        val token = "session-token"
        store.createSession(SessionTokens.hash(token), account.id, FIXED_NOW, FIXED_NOW + 100_000)

        appTest(support(FakeStripeClient())) { client ->
            val response = client.post("/commerce/portal") {
                contentType(ContentType.Application.Json)
                setBody("""{"sessionToken":"$token"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                "https://billing.stripe.com/p/session/bps_1",
                json.decodeFromString<PortalResponse>(response.bodyAsText()).portalUrl,
            )
        }
    }

    @Test
    fun portal_noCustomer_isConflict() {
        val account = store.createAccount("c@d.com", Rail.Free, FIXED_NOW)
        val token = "session-token-2"
        store.createSession(SessionTokens.hash(token), account.id, FIXED_NOW, FIXED_NOW + 100_000)

        appTest(support(FakeStripeClient())) { client ->
            val response = client.post("/commerce/portal") {
                contentType(ContentType.Application.Json)
                setBody("""{"sessionToken":"$token"}""")
            }
            assertEquals(HttpStatusCode.Conflict, response.status)
        }
    }

    @Test
    fun portal_invalidSession_isUnauthorized() = appTest(support(FakeStripeClient())) { client ->
        val response = client.post("/commerce/portal") {
            contentType(ContentType.Application.Json)
            setBody("""{"sessionToken":"nope"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    private companion object {
        const val FIXED_NOW = 1_700_000_000_000L
    }
}
