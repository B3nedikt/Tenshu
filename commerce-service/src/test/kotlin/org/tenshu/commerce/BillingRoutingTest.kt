package org.tenshu.commerce

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.tenshu.commerce.routing.BillingResponse
import org.tenshu.commerce.routing.StripeSupport
import org.tenshu.commerce.store.EntitlementHistory
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
import kotlin.test.assertTrue

/**
 * `/commerce/account/billing` and the checkout's never-pay-twice refusal
 * (`commerce-store-link-out.md` D6/D7) over HTTP.
 */
class BillingRoutingTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val store = InMemoryCommerceStore()

    private object NoStripe : StripeClient {
        override suspend fun createCheckoutSession(request: CheckoutSessionRequest): String? = "https://checkout.example/cs"
        override suspend fun createPortalSession(customerId: String, returnUrl: String): String? = null
        override suspend fun billingSubscriptions(customerId: String): List<BillingSubscription>? = emptyList()
        override suspend fun refundsForCharge(chargeId: String): List<ChargeRefund>? = emptyList()
        override suspend fun invoicePaymentEvidence(invoiceId: String): InvoicePaymentEvidence? = null
        override suspend fun cancelActiveSubscriptions(customerId: String): Int = 0
    }

    private val stripe = StripeSupport(
        client = NoStripe,
        priceIds = mapOf(ProductCatalog.BASIC to "price_basic", ProductCatalog.PREMIUM to "price_premium"),
        webhookSecret = "whsec_test",
        successUrl = "tenshu://ok",
        cancelUrl = "tenshu://no",
        portalReturnUrl = "tenshu://back",
    )

    /** An account holding [productId] from [source], signed in, bound to [ownerId]. Returns the session. */
    private fun holder(email: String, rail: Rail, productId: String, source: EntitlementSource, ownerId: String): String {
        val account = store.createAccount(email, rail, FIXED_NOW)
        store.bindOwner(account.id, ownerId, FIXED_NOW)
        store.appendEntitlementHistory(
            EntitlementHistory(
                accountId = account.id, productId = productId, validFrom = FIXED_NOW - 1,
                validUntil = FIXED_NOW + DAY, source = source, issuedAt = FIXED_NOW,
            ),
        )
        val token = "session-$email"
        store.createSession(SessionTokens.hash(token), account.id, FIXED_NOW, FIXED_NOW + DAY)
        return token
    }

    private fun appTest(block: suspend (HttpClient) -> Unit) = testApplication {
        application { module(store = store, stripe = stripe, now = { FIXED_NOW }) }
        block(client)
    }

    private suspend fun HttpClient.billing(token: String, rail: String = "Stripe"): HttpResponse =
        post("/commerce/account/billing") {
            contentType(ContentType.Application.Json)
            setBody("""{"sessionToken":"$token","rail":"$rail"}""")
        }

    private suspend fun HttpClient.checkout(token: String, ownerId: String, productId: String): HttpResponse =
        post("/commerce/checkout") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"ownerId":"$ownerId","productId":"$productId","withdrawalAcknowledged":true,""" +
                    """"tosVersion":"2026-08-06","sessionToken":"$token"}""",
            )
        }

    @Test
    fun billing_freeAccount_offersBothPaidPlans() {
        val token = holder("free@example.com", Rail.Free, ProductCatalog.FREE, EntitlementSource.Free, "owner-f")
        appTest { client ->
            val response = client.billing(token)
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.decodeFromString<BillingResponse>(response.bodyAsText())
            assertEquals(ProductCatalog.FREE, body.productId)
            assertEquals(BilledBy.Free, body.billedBy)
            assertEquals(listOf(ProductCatalog.BASIC, ProductCatalog.PREMIUM), body.offers)
        }
    }

    @Test
    fun billing_appleBilledPlan_offersNothingOffApple() {
        val token = holder("apple@example.com", Rail.IapApple, ProductCatalog.BASIC, EntitlementSource.IapApple, "owner-a")
        appTest { client ->
            val body = json.decodeFromString<BillingResponse>(client.billing(token).bodyAsText())
            assertEquals(BilledBy.Apple, body.billedBy)
            assertEquals(emptyList(), body.offers)
            assertEquals(BilledBy.Apple, body.upgradeOn)
        }
    }

    @Test
    fun billing_storeRailsSellNothingYet() {
        // Until P2/P4 there is no verification for a store purchase, so nothing may be offered.
        val token = holder("play@example.com", Rail.Free, ProductCatalog.FREE, EntitlementSource.Free, "owner-p")
        appTest { client ->
            val body = json.decodeFromString<BillingResponse>(client.billing(token, rail = "Google").bodyAsText())
            assertEquals(emptyList(), body.offers)
        }
    }

    @Test
    fun billing_withoutSession_isUnauthorized() = appTest { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.billing("nope").status)
    }

    @Test
    fun checkout_belowOrAtARedeemedPlan_isRefused() {
        val token = holder("code@example.com", Rail.Redeem, ProductCatalog.PREMIUM, EntitlementSource.Redeem, "owner-c")
        appTest { client ->
            val response = client.checkout(token, "owner-c", ProductCatalog.BASIC)
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue("not_an_upgrade" in response.bodyAsText())
        }
    }

    @Test
    fun checkout_aboveARedeemedPlan_goesThrough() {
        val token = holder("code2@example.com", Rail.Redeem, ProductCatalog.BASIC, EntitlementSource.Redeem, "owner-c2")
        appTest { client ->
            assertEquals(HttpStatusCode.OK, client.checkout(token, "owner-c2", ProductCatalog.PREMIUM).status)
        }
    }

    @Test
    fun checkout_overAnAppleBilledPlan_isRefused() {
        val token = holder("apple2@example.com", Rail.IapApple, ProductCatalog.BASIC, EntitlementSource.IapApple, "owner-a2")
        appTest { client ->
            val response = client.checkout(token, "owner-a2", ProductCatalog.PREMIUM)
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue("billed_by_apple" in response.bodyAsText())
        }
    }

    @Test
    fun checkout_refusesOverAPlanHeldByAnotherAccountOfTheSameRealm() {
        holder("first@example.com", Rail.IapApple, ProductCatalog.BASIC, EntitlementSource.IapApple, "owner-r")
        // The same realm signs in with a second address that holds nothing itself.
        val second = holder("second@example.com", Rail.Free, ProductCatalog.FREE, EntitlementSource.Free, "owner-r")
        appTest { client ->
            val response = client.checkout(second, "owner-r", ProductCatalog.PREMIUM)
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue("billed_by_apple" in response.bodyAsText())
        }
    }

    private companion object {
        const val FIXED_NOW = 1_700_000_000_000L
        const val DAY = 24L * 60 * 60 * 1000
    }
}
