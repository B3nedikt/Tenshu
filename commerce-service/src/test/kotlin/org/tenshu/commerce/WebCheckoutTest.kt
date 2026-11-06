package org.tenshu.commerce

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.tenshu.commerce.routing.CheckoutResponse
import org.tenshu.commerce.routing.StripeSupport
import org.tenshu.commerce.routing.WebReturnUrls
import org.tenshu.commerce.store.CommerceStore
import org.tenshu.commerce.store.InMemoryCommerceStore
import org.tenshu.commerce.store.Rail
import org.tenshu.commerce.stripe.BillingSubscription
import org.tenshu.commerce.stripe.ChargeRefund
import org.tenshu.commerce.stripe.CheckoutSessionRequest
import org.tenshu.commerce.stripe.InvoicePaymentEvidence
import org.tenshu.commerce.stripe.StripeClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The browser half of the checkout rail: CORS admission, and sending a paid customer back to a page
 * their browser can actually open.
 */
class WebCheckoutTest {

    private val store: CommerceStore = InMemoryCommerceStore()
    private val json = Json { ignoreUnknownKeys = true }

    private class RecordingStripe : StripeClient {
        var lastCheckout: CheckoutSessionRequest? = null
        var lastPortalReturn: String? = null
        var subscriptions: List<BillingSubscription>? = emptyList()
        /** Per customer, where a test needs two customers to differ; otherwise [subscriptions]. */
        val subscriptionsOf = mutableMapOf<String, List<BillingSubscription>?>()
        val lookedUp = mutableListOf<String>()
        override suspend fun createCheckoutSession(request: CheckoutSessionRequest): String? {
            lastCheckout = request
            return "https://checkout.stripe.com/c/pay/cs_1"
        }

        override suspend fun createPortalSession(customerId: String, returnUrl: String): String? {
            lastPortalReturn = returnUrl
            return "https://billing.stripe.com/p/session/bps_1"
        }

        override suspend fun billingSubscriptions(customerId: String): List<BillingSubscription>? {
            lookedUp += customerId
            return if (customerId in subscriptionsOf) subscriptionsOf[customerId] else subscriptions
        }
        override suspend fun refundsForCharge(chargeId: String): List<ChargeRefund>? = emptyList()
        override suspend fun invoicePaymentEvidence(invoiceId: String): InvoicePaymentEvidence? = null
        override suspend fun cancelActiveSubscriptions(customerId: String): Int = 0
    }

    private fun support(client: StripeClient, webOrigin: String?) = StripeSupport(
        client = client,
        priceIds = mapOf(ProductCatalog.BASIC to "price_basic"),
        webhookSecret = "whsec_test",
        successUrl = NATIVE_SUCCESS,
        cancelUrl = "tenshu://checkout-result?status=cancel",
        portalReturnUrl = NATIVE_PORTAL,
        webReturnUrls = webOrigin?.let(::WebReturnUrls),
    )

    private fun appTest(
        stripe: StripeSupport?,
        webAppOrigin: String? = WEB_ORIGIN,
        block: suspend (HttpClient) -> Unit,
    ) = testApplication {
        application {
            module(store = store, stripe = stripe, webAppOrigin = webAppOrigin, now = { 1_700_000_000_000 })
        }
        block(client)
    }

    private suspend fun postCheckout(client: HttpClient, body: String) =
        client.post("/commerce/checkout") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    // -- the buyer is signed in ----------------------------------------------

    /**
     * ⚠⚠ The email is the session's, never one typed into Stripe. The webhook finds the account by
     * the email on the event, and a browser fetches its plan with a session — so a free-typed
     * address put the paid plan on an account the buyer was not signed in to.
     */
    @Test
    fun webCheckout_pinsTheSignedInEmail() {
        val stripe = RecordingStripe()
        appTest(support(stripe, WEB_ORIGIN)) { client ->
            assertEquals(HttpStatusCode.OK, postCheckout(client, webCheckoutBody()).status)
            assertEquals(BUYER, stripe.lastCheckout?.customerEmail)
            assertNull(stripe.lastCheckout?.customerId)
        }
    }

    @Test
    fun webCheckout_reusesTheStripeCustomerOfAReturningBuyer() {
        val stripe = RecordingStripe()
        appTest(support(stripe, WEB_ORIGIN)) { client ->
            val body = webCheckoutBody(signIn(customerId = "cus_existing"))
            assertEquals(HttpStatusCode.OK, postCheckout(client, body).status)
            assertEquals("cus_existing", stripe.lastCheckout?.customerId)
            // Stripe rejects `customer` and `customer_email` together.
            assertNull(stripe.lastCheckout?.customerEmail)
        }
    }

    @Test
    fun webCheckout_withoutASession_isRefusedBeforeStripeIsAsked() {
        val stripe = RecordingStripe()
        appTest(support(stripe, WEB_ORIGIN)) { client ->
            assertEquals(HttpStatusCode.Unauthorized, postCheckout(client, webCheckoutBody(sessionToken = null)).status)
            assertEquals(HttpStatusCode.Unauthorized, postCheckout(client, webCheckoutBody(sessionToken = "unknown")).status)
            assertNull(stripe.lastCheckout)
        }
    }

    // -- one subscription per customer ----------------------------------------

    /**
     * ⚠⚠ Hosted Checkout always starts a NEW subscription. On the first live pass Basic was bought
     * three times and all three kept billing.
     */
    @Test
    fun buyingTheTierAlreadyBilling_isRefused() {
        val stripe = RecordingStripe().apply {
            subscriptions = listOf(BillingSubscription("sub_1", listOf("price_basic")))
        }
        appTest(support(stripe, WEB_ORIGIN)) { client ->
            val response = postCheckout(client, webCheckoutBody(signIn(customerId = "cus_existing")))
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue("already_subscribed" in response.bodyAsText())
            assertNull(stripe.lastCheckout)
        }
    }

    /** Another tier billing is a plan change on that subscription, never a second one beside it. */
    @Test
    fun buyingAnotherTierWhileOneIsBilling_isAPlanChange() {
        val stripe = RecordingStripe().apply {
            subscriptions = listOf(BillingSubscription("sub_1", listOf("price_premium")))
        }
        appTest(support(stripe, WEB_ORIGIN)) { client ->
            val response = postCheckout(client, webCheckoutBody(signIn(customerId = "cus_existing")))
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue("plan_change_required" in response.bodyAsText())
            assertNull(stripe.lastCheckout)
        }
    }

    /** Not knowing is a refusal: the wrong guess is a second charge on the customer's card. */
    @Test
    fun whenStripeCannotSayWhatIsBilling_nothingIsSold() {
        val stripe = RecordingStripe().apply { subscriptions = null }
        appTest(support(stripe, WEB_ORIGIN)) { client ->
            val response = postCheckout(client, webCheckoutBody(signIn(customerId = "cus_existing")))
            assertEquals(HttpStatusCode.BadGateway, response.status)
            assertNull(stripe.lastCheckout)
        }
    }

    @Test
    fun aCustomerWhoseSubscriptionsAllEnded_mayBuyAgain() {
        val stripe = RecordingStripe()
        appTest(support(stripe, WEB_ORIGIN)) { client ->
            val response = postCheckout(client, webCheckoutBody(signIn(customerId = "cus_existing")))
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("cus_existing", stripe.lastCheckout?.customerId)
        }
    }

    // -- one subscription per REALM --------------------------------------------

    /** The realm already pays under another email; this account has no Stripe customer at all. */
    private fun realmAlsoPaysUnder(email: String, customerId: String) {
        val other = store.createAccount(email, Rail.Stripe, NOW)
        store.attachStripeCustomer(other.id, customerId)
        store.bindOwner(other.id, "owner-1", NOW)
    }

    /**
     * ⚠⚠ An owner binding is superseded per account, so a realm that signs in with a second email
     * is bound to both — and the new account has no customer for the per-account check to find. On
     * the live pass one realm bought Basic three times under three emails.
     */
    @Test
    fun aRealmAlreadyPayingUnderAnotherEmail_isRefused() {
        val stripe = RecordingStripe().apply {
            subscriptionsOf["cus_other"] = listOf(BillingSubscription("sub_9", listOf("price_basic")))
        }
        realmAlsoPaysUnder("first@x.com", "cus_other")
        appTest(support(stripe, WEB_ORIGIN)) { client ->
            val response = postCheckout(client, webCheckoutBody())
            assertEquals(HttpStatusCode.Conflict, response.status)
            // Its own answer: this session cannot manage that subscription, so it is not a plan change.
            assertTrue("subscribed_under_another_account" in response.bodyAsText())
            assertNull(stripe.lastCheckout)
        }
    }

    @Test
    fun anotherAccountOfTheRealmWithNothingBilling_doesNotBlock() {
        val stripe = RecordingStripe()
        realmAlsoPaysUnder("first@x.com", "cus_other")
        appTest(support(stripe, WEB_ORIGIN)) { client ->
            assertEquals(HttpStatusCode.OK, postCheckout(client, webCheckoutBody()).status)
            assertEquals(listOf("cus_other"), stripe.lookedUp)
        }
    }

    @Test
    fun whenStripeCannotSayWhatTheRealmPaysFor_nothingIsSold() {
        val stripe = RecordingStripe().apply { subscriptionsOf["cus_other"] = null }
        realmAlsoPaysUnder("first@x.com", "cus_other")
        appTest(support(stripe, WEB_ORIGIN)) { client ->
            assertEquals(HttpStatusCode.BadGateway, postCheckout(client, webCheckoutBody()).status)
            assertNull(stripe.lastCheckout)
        }
    }

    /** Someone else's realm paying under an account is no reason to refuse this one. */
    @Test
    fun anAccountBoundToADifferentRealm_isNotConsulted() {
        val stripe = RecordingStripe().apply {
            subscriptionsOf["cus_stranger"] = listOf(BillingSubscription("sub_9", listOf("price_basic")))
        }
        val stranger = store.createAccount("stranger@x.com", Rail.Stripe, NOW)
        store.attachStripeCustomer(stranger.id, "cus_stranger")
        store.bindOwner(stranger.id, "owner-2", NOW)
        appTest(support(stripe, WEB_ORIGIN)) { client ->
            assertEquals(HttpStatusCode.OK, postCheckout(client, webCheckoutBody()).status)
            assertTrue(stripe.lookedUp.isEmpty())
        }
    }

    // -- return URLs ---------------------------------------------------------

    @Test
    fun webCheckout_redirectsBackToTheWebApp() {
        val stripe = RecordingStripe()
        appTest(support(stripe, WEB_ORIGIN)) { client ->
            val response = postCheckout(client, webCheckoutBody())
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                "https://checkout.stripe.com/c/pay/cs_1",
                json.decodeFromString<CheckoutResponse>(response.bodyAsText()).checkoutUrl,
            )
            assertEquals("$WEB_ORIGIN/control/services/checkout-result?status=success", stripe.lastCheckout?.successUrl)
            assertEquals("$WEB_ORIGIN/control/services/checkout-result?status=cancel", stripe.lastCheckout?.cancelUrl)
        }
    }

    /**
     * ⚠ The regression that matters most here: an absent `client` field must keep meaning *native*.
     * Every deployed Control build omits it, and a default of Web would redirect phone customers to
     * a web page after taking their money.
     */
    @Test
    fun absentClientField_staysNative() {
        val stripe = RecordingStripe()
        appTest(support(stripe, WEB_ORIGIN)) { client ->
            val response = postCheckout(client, nativeCheckoutBody())
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(NATIVE_SUCCESS, stripe.lastCheckout?.successUrl)
            assertNotEquals(
                "$WEB_ORIGIN/control/services/checkout-result?status=success",
                stripe.lastCheckout?.successUrl,
            )
        }
    }

    /**
     * ⚠⚠ Refuse BEFORE taking money. Without a configured web origin the only redirect available is
     * a `tenshu://` deep link, which a browser cannot follow — so a web customer would pay and then
     * land nowhere.
     */
    @Test
    fun webCheckout_withNoWebOriginConfigured_isRefused() {
        val stripe = RecordingStripe()
        appTest(support(stripe, webOrigin = null), webAppOrigin = null) { client ->
            val response = postCheckout(client, webCheckoutBody())
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertNull(stripe.lastCheckout, "no Stripe session may be created for a refused checkout")
        }
    }

    /** The acknowledgement gate is not weakened for the web client. */
    @Test
    fun webCheckout_withoutAcknowledgement_isRefused() {
        val stripe = RecordingStripe()
        appTest(support(stripe, WEB_ORIGIN)) { client ->
            val response = postCheckout(
                client,
                """{"ownerId":"owner-1","productId":"${ProductCatalog.BASIC}","client":"Web"}""",
            )
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue("acknowledgement_required" in response.bodyAsText())
            assertNull(stripe.lastCheckout)
        }
    }

    // -- CORS ----------------------------------------------------------------

    @Test
    fun preflightFromTheConfiguredOrigin_isAllowed() {
        appTest(support(RecordingStripe(), WEB_ORIGIN)) { client ->
            val response = client.options("/commerce/checkout") {
                header(HttpHeaders.Origin, WEB_ORIGIN)
                header(HttpHeaders.AccessControlRequestMethod, "POST")
                header(HttpHeaders.AccessControlRequestHeaders, "content-type")
            }
            assertEquals(
                WEB_ORIGIN,
                response.headers[HttpHeaders.AccessControlAllowOrigin],
                "the configured origin must be echoed back or the browser drops the response",
            )
        }
    }

    /**
     * ⚠ `/commerce/account/register` is deliberately unauthenticated and sends real mail, so an
     * unlisted origin being refused is the property that keeps a wildcard from turning it into an
     * open mail relay driven through visitors' browsers.
     */
    @Test
    fun preflightFromAnotherOrigin_isNotAllowed() {
        appTest(support(RecordingStripe(), WEB_ORIGIN)) { client ->
            val response = client.options("/commerce/account/register") {
                header(HttpHeaders.Origin, "https://evil.example")
                header(HttpHeaders.AccessControlRequestMethod, "POST")
            }
            assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
        }
    }

    /** With no web origin configured the service keeps its original posture: no browser at all. */
    @Test
    fun withNoWebOriginConfigured_noOriginIsAllowed() {
        appTest(support(RecordingStripe(), webOrigin = null), webAppOrigin = null) { client ->
            val response = client.options("/commerce/checkout") {
                header(HttpHeaders.Origin, WEB_ORIGIN)
                header(HttpHeaders.AccessControlRequestMethod, "POST")
            }
            assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
        }
    }

    /**
     * ⚠ Credentials stay off: the session token rides in the JSON body, never a cookie, so there is
     * no ambient authority for a cross-site request to borrow.
     */
    @Test
    fun credentialsAreNeverAllowed() {
        appTest(support(RecordingStripe(), WEB_ORIGIN)) { client ->
            val response = client.options("/commerce/checkout") {
                header(HttpHeaders.Origin, WEB_ORIGIN)
                header(HttpHeaders.AccessControlRequestMethod, "POST")
            }
            assertNull(response.headers[HttpHeaders.AccessControlAllowCredentials])
        }
    }

    private fun webCheckoutBody(sessionToken: String? = signIn()) =
        """{"ownerId":"owner-1","productId":"${ProductCatalog.BASIC}",""" +
            """"withdrawalAcknowledged":true,"tosVersion":"2026-08-06","client":"Web"""" +
            (sessionToken?.let { ""","sessionToken":"$it"""" } ?: "") + "}"

    /** A verified account with a live session, as `/verify` leaves it. */
    private fun signIn(email: String = BUYER, customerId: String? = null): String {
        val account = store.findAccountByEmail(email) ?: store.createAccount(email, Rail.Stripe, NOW)
        customerId?.let { store.attachStripeCustomer(account.id, it) }
        val token = "session-${account.id}"
        store.createSession(SessionTokens.hash(token), account.id, NOW, NOW + 100_000)
        return token
    }

    private fun nativeCheckoutBody() =
        """{"ownerId":"owner-1","productId":"${ProductCatalog.BASIC}",""" +
            """"withdrawalAcknowledged":true,"tosVersion":"2026-08-06"}"""

    private companion object {
        const val WEB_ORIGIN = "https://tenshu.app"
        const val BUYER = "buyer@x.com"
        const val NOW = 1_700_000_000_000L
        const val NATIVE_SUCCESS = "tenshu://checkout-result?status=success"
        const val NATIVE_PORTAL = "tenshu://checkout-result?status=portal"
    }
}
