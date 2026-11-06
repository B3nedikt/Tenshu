package org.tenshu.commerce

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.commerce.play.GooglePlayClient
import org.tenshu.commerce.play.PlayLookup
import org.tenshu.commerce.play.PlaySubscription
import org.tenshu.commerce.routing.BillingResponse
import org.tenshu.commerce.routing.GooglePlaySupport
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
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Google Play rail (`commerce-store-link-out.md` P2): verify, notifications, and the D6 switches
 * between Google and Stripe in both directions.
 */
class GooglePlayRoutingTest {

    private val store = InMemoryCommerceStore()

    private class FakePlay : GooglePlayClient {
        val subscriptions = mutableMapOf<String, PlaySubscription>()
        var failLookups = false
        var failRevoke = false
        val acknowledged = mutableListOf<String>()
        val revoked = mutableListOf<String>()

        override suspend fun subscription(purchaseToken: String): PlayLookup = when {
            failLookups -> PlayLookup.Failed
            else -> subscriptions[purchaseToken]?.let { PlayLookup.Found(it) } ?: PlayLookup.NotFound
        }

        override suspend fun acknowledge(subscriptionId: String, purchaseToken: String): Boolean {
            acknowledged += purchaseToken
            subscriptions.computeIfPresent(purchaseToken) { _, s -> s.copy(acknowledged = true) }
            return true
        }

        override suspend fun revokeWithProratedRefund(purchaseToken: String): Boolean {
            if (failRevoke) return false
            revoked += purchaseToken
            subscriptions.computeIfPresent(purchaseToken) { _, s -> s.copy(state = "SUBSCRIPTION_STATE_REVOKED") }
            return true
        }

        var failCancel = false
        val cancelled = mutableListOf<String>()

        override suspend fun cancelRenewals(purchaseToken: String): Boolean {
            if (failCancel) return false
            cancelled += purchaseToken
            subscriptions.computeIfPresent(purchaseToken) { _, s -> s.copy(state = "SUBSCRIPTION_STATE_CANCELED") }
            return true
        }
    }

    private class RecordingStripe : StripeClient {
        var billing: List<BillingSubscription> = emptyList()
        val railSwitches = mutableListOf<String>()
        override suspend fun createCheckoutSession(request: CheckoutSessionRequest): String? = "https://checkout.example/cs"
        override suspend fun createPortalSession(customerId: String, returnUrl: String): String? = null
        override suspend fun billingSubscriptions(customerId: String): List<BillingSubscription>? = billing
        override suspend fun refundsForCharge(chargeId: String): List<ChargeRefund>? = emptyList()
        override suspend fun invoicePaymentEvidence(invoiceId: String): InvoicePaymentEvidence? = null
        override suspend fun cancelActiveSubscriptions(customerId: String): Int = 0
        override suspend fun refundUnusedAndCancel(customerId: String, nowMs: Long): Long {
            railSwitches += customerId
            billing = emptyList()
            return 150
        }
    }

    private val play = FakePlay()
    private val stripeClient = RecordingStripe()
    private val support = GooglePlaySupport(play, GooglePlayConfig.DEFAULT_PRODUCTS, rtdnToken = "rtdn-secret")
    private val stripe = StripeSupport(
        client = stripeClient,
        priceIds = mapOf(ProductCatalog.BASIC to "price_basic", ProductCatalog.PREMIUM to "price_premium"),
        webhookSecret = WEBHOOK_SECRET,
        successUrl = "tenshu://ok",
        cancelUrl = "tenshu://no",
        portalReturnUrl = "tenshu://back",
    )

    private fun appTest(block: suspend (HttpClient) -> Unit) = testApplication {
        application { module(store = store, stripe = stripe, googlePlay = support, now = { FIXED_NOW }) }
        block(client)
    }

    /** A signed-in account bound to [ownerId]; returns (accountId, session token). */
    private fun signedIn(email: String, ownerId: String = "owner-$email"): Pair<String, String> {
        val account = store.createAccount(email, Rail.Free, FIXED_NOW)
        store.bindOwner(account.id, ownerId, FIXED_NOW)
        val token = "session-$email"
        store.createSession(SessionTokens.hash(token), account.id, FIXED_NOW, FIXED_NOW + DAY)
        return account.id to token
    }

    private fun purchase(
        token: String,
        accountId: String,
        productId: String = ProductCatalog.BASIC,
        expiry: Long = FIXED_NOW + MONTH,
        replaces: String? = null,
    ) {
        play.subscriptions[token] = PlaySubscription(
            state = "SUBSCRIPTION_STATE_ACTIVE", productId = productId, expiryMs = expiry,
            obfuscatedAccountId = accountId, acknowledged = false, linkedPurchaseToken = replaces,
        )
    }

    private suspend fun HttpClient.billing(session: String, rail: String): BillingResponse =
        kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(
            post("/commerce/account/billing") {
                contentType(ContentType.Application.Json)
                setBody("""{"sessionToken":"$session","rail":"$rail"}""")
            }.bodyAsText(),
        )

    private suspend fun HttpClient.verify(session: String, purchaseToken: String): HttpResponse =
        post("/commerce/iap/google/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"sessionToken":"$session","purchaseToken":"$purchaseToken"}""")
        }

    private suspend fun HttpClient.rtdn(purchaseToken: String, voided: Boolean = false, secret: String = "rtdn-secret"): HttpResponse {
        val inner = if (voided) {
            """{"packageName":"p","voidedPurchaseNotification":{"purchaseToken":"$purchaseToken"}}"""
        } else {
            """{"packageName":"p","subscriptionNotification":{"notificationType":2,"purchaseToken":"$purchaseToken","subscriptionId":"x"}}"""
        }
        val data = Base64.getEncoder().encodeToString(inner.toByteArray())
        return post("/commerce/iap/google/rtdn?token=$secret") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":{"data":"$data","messageId":"1"},"subscription":"s"}""")
        }
    }

    private fun current(accountId: String) = store.currentEntitlement(accountId, FIXED_NOW + 1)

    @Test
    fun verify_booksTheGrantUntilExpiry_andAcknowledges() {
        val (accountId, session) = signedIn("a@example.com")
        purchase("tok-1", accountId)
        appTest { client ->
            assertEquals(HttpStatusCode.OK, client.verify(session, "tok-1").status)
        }
        val grant = assertNotNull(current(accountId))
        assertEquals(ProductCatalog.BASIC, grant.productId)
        assertEquals(EntitlementSource.IapGoogle, grant.source)
        assertEquals(FIXED_NOW + MONTH, grant.validUntil)
        assertEquals(listOf("tok-1"), play.acknowledged)
        assertEquals(Rail.IapGoogle, store.findAccountById(accountId)?.rail)
    }

    @Test
    fun verify_isIdempotent() {
        val (accountId, session) = signedIn("idem@example.com")
        purchase("tok-i", accountId)
        appTest { client ->
            client.verify(session, "tok-i")
            assertEquals(HttpStatusCode.OK, client.verify(session, "tok-i").status)
        }
        assertEquals(1, store.listEntitlementHistory(accountId).count { it.source == EntitlementSource.IapGoogle })
        assertEquals(listOf("tok-i"), play.acknowledged, "already acknowledged: not again")
    }

    @Test
    fun verify_aPurchaseForAnotherAccount_isRefusedAndNotAcknowledged() {
        val (_, session) = signedIn("thief@example.com")
        val (victim, _) = signedIn("victim@example.com")
        purchase("tok-v", victim)
        appTest { client ->
            val response = client.verify(session, "tok-v")
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue("account_mismatch" in response.bodyAsText())
        }
        assertTrue(play.acknowledged.isEmpty())
    }

    @Test
    fun verify_overAnApplePlan_isRefusedAndLeftForPlayToRefund() {
        val (accountId, session) = signedIn("apple@example.com")
        store.appendEntitlementHistory(grant(accountId, ProductCatalog.BASIC, EntitlementSource.IapApple))
        purchase("tok-a", accountId, ProductCatalog.PREMIUM)
        appTest { client ->
            val response = client.verify(session, "tok-a")
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue("billed_by_apple" in response.bodyAsText())
        }
        assertTrue(play.acknowledged.isEmpty(), "unacknowledged ⇒ Play refunds it within three days")
    }

    @Test
    fun verify_anUpgradeFromStripe_refundsAndCancelsStripe() {
        val (accountId, session) = signedIn("switch@example.com")
        store.attachStripeCustomer(accountId, "cus_s")
        store.appendEntitlementHistory(grant(accountId, ProductCatalog.BASIC, EntitlementSource.Stripe))
        stripeClient.billing = listOf(BillingSubscription("sub_1", listOf("price_basic")))
        purchase("tok-s", accountId, ProductCatalog.PREMIUM)
        appTest { client ->
            assertEquals(HttpStatusCode.OK, client.verify(session, "tok-s").status)
        }
        assertEquals(listOf("cus_s"), stripeClient.railSwitches)
        assertEquals(EntitlementSource.IapGoogle, current(accountId)?.source)
        assertTrue(
            store.listEntitlementHistory(accountId).none { it.source == EntitlementSource.Stripe && it.validUntil > FIXED_NOW },
            "the Stripe grant ends with the subscription",
        )
    }

    @Test
    fun verify_theSameOrALowerPlanThanStripe_isRefused() {
        val (accountId, session) = signedIn("down@example.com")
        store.attachStripeCustomer(accountId, "cus_d")
        store.appendEntitlementHistory(grant(accountId, ProductCatalog.PREMIUM, EntitlementSource.Stripe))
        purchase("tok-d", accountId, ProductCatalog.BASIC)
        appTest { client ->
            val response = client.verify(session, "tok-d")
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue("not_an_upgrade" in response.bodyAsText())
        }
        assertTrue(stripeClient.railSwitches.isEmpty())
    }

    @Test
    fun verify_whenPlayIsDown_isRetryable() {
        val (_, session) = signedIn("down2@example.com")
        play.failLookups = true
        appTest { client -> assertEquals(HttpStatusCode.BadGateway, client.verify(session, "tok-x").status) }
    }

    @Test
    fun rtdn_aRenewalExtendsTheGrant() {
        val (accountId, session) = signedIn("renew@example.com")
        purchase("tok-r", accountId)
        appTest { client ->
            client.verify(session, "tok-r")
            play.subscriptions["tok-r"] = play.subscriptions.getValue("tok-r").copy(expiryMs = FIXED_NOW + 2 * MONTH)
            assertEquals(HttpStatusCode.OK, client.rtdn("tok-r").status)
        }
        assertEquals(FIXED_NOW + 2 * MONTH, current(accountId)?.validUntil)
    }

    @Test
    fun rtdn_aRefundEndsTheGrantNow() {
        val (accountId, session) = signedIn("refund@example.com")
        purchase("tok-f", accountId)
        appTest { client ->
            client.verify(session, "tok-f")
            // A refund that ends the subscription: the re-read says revoked, and that decides.
            play.subscriptions["tok-f"] = play.subscriptions.getValue("tok-f").copy(state = "SUBSCRIPTION_STATE_REVOKED")
            assertEquals(HttpStatusCode.OK, client.rtdn("tok-f", voided = true).status)

        }
        assertNull(current(accountId), "nothing left: the account had no Free row in this test")
    }

    @Test
    fun rtdn_anOldTokenNeverEndsTheCurrentPlan() {
        val (accountId, session) = signedIn("upgrade@example.com")
        purchase("tok-old", accountId, ProductCatalog.BASIC)
        purchase("tok-new", accountId, ProductCatalog.PREMIUM, replaces = "tok-old")

        appTest { client ->
            client.verify(session, "tok-old")
            client.verify(session, "tok-new")
            // Play replaced the Basic purchase; the old token now reports expired.
            play.subscriptions["tok-old"] = play.subscriptions.getValue("tok-old").copy(state = "SUBSCRIPTION_STATE_EXPIRED")
            assertEquals(HttpStatusCode.OK, client.rtdn("tok-old").status)
        }
        assertEquals(ProductCatalog.PREMIUM, current(accountId)?.productId)
    }

    @Test
    fun rtdn_wrongSecret_isUnauthorized_andPlayDown_asksForRedelivery() {
        val (accountId, session) = signedIn("sec@example.com")
        purchase("tok-q", accountId)
        appTest { client ->
            client.verify(session, "tok-q")
            assertEquals(HttpStatusCode.Unauthorized, client.rtdn("tok-q", secret = "nope").status)
            play.failLookups = true
            assertEquals(HttpStatusCode.ServiceUnavailable, client.rtdn("tok-q").status)
        }
    }

    @Test
    fun aStripePurchaseOverAGooglePlan_revokesGoogleBeforeBooking() {
        val (accountId, session) = signedIn("buyer@example.com")
        purchase("tok-g", accountId)
        appTest { client ->
            client.verify(session, "tok-g")
            val response = client.postWebhook(invoicePaid("evt_g", "buyer@example.com"))
            assertEquals(HttpStatusCode.OK, response.status)
        }
        assertEquals(listOf("tok-g"), play.revoked)
        assertEquals(EntitlementSource.Stripe, current(accountId)?.source)
    }

    @Test
    fun aStripePurchaseOverAGooglePlan_whenRevokeFails_booksNothingAndAsksForRedelivery() {
        val (accountId, session) = signedIn("fail@example.com")
        purchase("tok-h", accountId)
        appTest { client ->
            client.verify(session, "tok-h")
            play.failRevoke = true
            assertEquals(HttpStatusCode.ServiceUnavailable, client.postWebhook(invoicePaid("evt_h", "fail@example.com")).status)
        }
        assertTrue(store.allInvoices().none { it.accountId == accountId }, "no invoice before Google is stopped")
        assertFalse(store.listEntitlementHistory(accountId).any { it.source == EntitlementSource.Stripe })
    }

    @Test
    fun verify_aSecondSubscriptionBesideTheHeldOne_isRefusedAndNotAcknowledged() {
        val (accountId, session) = signedIn("twice@example.com")
        purchase("tok-1st", accountId, ProductCatalog.BASIC)
        // Bought without replacing the first (no linkedPurchaseToken): two subscriptions billing.
        purchase("tok-2nd", accountId, ProductCatalog.PREMIUM)
        appTest { client ->
            assertEquals(HttpStatusCode.OK, client.verify(session, "tok-1st").status)
            val response = client.verify(session, "tok-2nd")
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue("google_subscription_not_replaced" in response.bodyAsText())
        }
        assertEquals(listOf("tok-1st"), play.acknowledged, "unacknowledged ⇒ Play refunds the second")
        assertEquals("tok-1st", store.findAccountById(accountId)?.iapOriginalTxnId)
    }

    @Test
    fun verify_aNewSubscriptionAfterTheHeldOneEnded_isAccepted() {
        val (accountId, session) = signedIn("again@example.com")
        purchase("tok-a1", accountId, ProductCatalog.BASIC)
        purchase("tok-a2", accountId, ProductCatalog.PREMIUM)
        appTest { client ->
            client.verify(session, "tok-a1")
            play.subscriptions["tok-a1"] = play.subscriptions.getValue("tok-a1").copy(state = "SUBSCRIPTION_STATE_EXPIRED")
            assertEquals(HttpStatusCode.OK, client.verify(session, "tok-a2").status)
        }
        assertEquals("tok-a2", store.findAccountById(accountId)?.iapOriginalTxnId)
    }

    @Test
    fun rtdn_adoptsAReplacementToken_booksAndAcknowledgesIt() {
        val (accountId, session) = signedIn("inplay@example.com")
        purchase("tok-b", accountId, ProductCatalog.BASIC)
        appTest { client ->
            client.verify(session, "tok-b")
            // Upgraded inside Play; the device never verified the new token.
            purchase("tok-p", accountId, ProductCatalog.PREMIUM, replaces = "tok-b")
            play.subscriptions["tok-b"] = play.subscriptions.getValue("tok-b").copy(state = "SUBSCRIPTION_STATE_EXPIRED")
            assertEquals(HttpStatusCode.OK, client.rtdn("tok-p").status)
            // The old token's EXPIRED arrives after: it no longer speaks for the account.
            assertEquals(HttpStatusCode.OK, client.rtdn("tok-b").status)
        }
        assertEquals("tok-p", store.findAccountById(accountId)?.iapOriginalTxnId)
        assertEquals(ProductCatalog.PREMIUM, current(accountId)?.productId)
        assertTrue("tok-p" in play.acknowledged, "unacknowledged, Play would refund the upgrade")
    }

    @Test
    fun rtdn_aLinkedReSignupOverAnotherAccountsPlan_isNotAdopted_andLeftForPlayToRefund() {
        // A held Google Basic and cancelled it; B, on the same device, then bought on Stripe.
        val (a, sessionA) = signedIn("resign-a@example.com", ownerId = "owner-resign")
        val (b, _) = signedIn("resign-b@example.com", ownerId = "owner-resign")
        purchase("tok-ra", a, ProductCatalog.BASIC)
        appTest { client ->
            client.verify(sessionA, "tok-ra")
            play.subscriptions["tok-ra"] = play.subscriptions.getValue("tok-ra").copy(state = "SUBSCRIPTION_STATE_CANCELED")
            store.appendEntitlementHistory(grant(b, ProductCatalog.PREMIUM, EntitlementSource.Stripe))
            // A re-signs up in Play's subscription centre, without our app: the new token links back.
            purchase("tok-rr", a, ProductCatalog.BASIC, replaces = "tok-ra")
            assertEquals(HttpStatusCode.OK, client.rtdn("tok-rr").status)
        }
        assertEquals("tok-ra", store.findAccountById(a)?.iapOriginalTxnId, "not adopted")
        assertFalse("tok-rr" in play.acknowledged, "unacknowledged ⇒ Play refunds it")
    }

    @Test
    fun rtdn_aLinkedReSignupOnAClosedAccount_isCancelled() {
        val (accountId, session) = signedIn("resign-closed@example.com")
        purchase("tok-cl", accountId)
        appTest { client ->
            client.verify(session, "tok-cl")
            store.closeAccount(accountId, FIXED_NOW)
            purchase("tok-cl2", accountId, replaces = "tok-cl")
            assertEquals(HttpStatusCode.OK, client.rtdn("tok-cl2").status)
        }
        assertTrue("tok-cl2" in play.cancelled)
        assertFalse("tok-cl2" in play.acknowledged)
    }

    @Test
    fun verify_aNewTokenBesideAHeldOneOnHold_isRefused() {
        // Play's queryPurchases does not return a subscription on hold, so the app bought without
        // replacing it; if the old one recovers it would bill again.
        val (accountId, session) = signedIn("onhold@example.com")
        purchase("tok-oh", accountId, ProductCatalog.BASIC)
        purchase("tok-oh2", accountId, ProductCatalog.PREMIUM)
        appTest { client ->
            client.verify(session, "tok-oh")
            for (state in listOf("SUBSCRIPTION_STATE_ON_HOLD", "SUBSCRIPTION_STATE_PAUSED")) {
                play.subscriptions["tok-oh"] = play.subscriptions.getValue("tok-oh").copy(state = state)
                val response = client.verify(session, "tok-oh2")
                assertEquals(HttpStatusCode.Conflict, response.status, state)
                assertTrue("google_subscription_not_replaced" in response.bodyAsText())
            }
        }
        assertEquals("tok-oh", store.findAccountById(accountId)?.iapOriginalTxnId)
    }

    @Test
    fun rtdn_aReplacementLinkingToAnotherAccountsToken_isIgnored() {

        val (a, sessionA) = signedIn("owner-a@example.com")
        val (b, _) = signedIn("owner-b@example.com")
        purchase("tok-a", a)
        appTest { client ->
            client.verify(sessionA, "tok-a")
            purchase("tok-x", b, ProductCatalog.PREMIUM, replaces = "tok-a")
            assertEquals(HttpStatusCode.OK, client.rtdn("tok-x").status)
        }
        assertNull(current(b))
        assertEquals("tok-a", store.findAccountById(a)?.iapOriginalTxnId)
        assertFalse("tok-x" in play.acknowledged)
    }

    @Test
    fun rtdn_aVoidedNotification_whileTheSubscriptionStaysActive_keepsThePlan() {
        val (accountId, session) = signedIn("void@example.com")
        purchase("tok-vd", accountId)
        appTest { client ->
            client.verify(session, "tok-vd")
            // One renewal refunded; Play still reports the subscription active.
            assertEquals(HttpStatusCode.OK, client.rtdn("tok-vd", voided = true).status)
        }
        assertEquals(ProductCatalog.BASIC, current(accountId)?.productId)
    }

    @Test
    fun rtdn_forAClosedAccount_booksNothing_andCancelsRenewals() {
        val (accountId, session) = signedIn("closed@example.com")
        purchase("tok-c", accountId)
        appTest { client ->
            client.verify(session, "tok-c")
            store.closeAccount(accountId, FIXED_NOW)
            play.subscriptions["tok-c"] = play.subscriptions.getValue("tok-c").copy(expiryMs = FIXED_NOW + 2 * MONTH)
            assertEquals(HttpStatusCode.OK, client.rtdn("tok-c").status)
        }
        assertTrue(
            store.listEntitlementHistory(accountId).none { it.validUntil == FIXED_NOW + 2 * MONTH },
            "a renewal for a closed account books nothing",
        )
        assertEquals(listOf("tok-c"), play.cancelled)
    }

    @Test
    fun closingAnAccount_cancelsItsGoogleSubscription_andTheSweepRetriesAFailure() = kotlinx.coroutines.runBlocking {
        val (accountId, session) = signedIn("close-g@example.com")
        purchase("tok-cg", accountId)
        appTest { client -> client.verify(session, "tok-cg") }
        store.closeAccount(accountId, FIXED_NOW)
        val canceller = SubscriptionCanceller(store, stripe = null, play = play) { FIXED_NOW }
        play.failCancel = true
        assertFalse(canceller.cancelFor(store.findAccountById(accountId)!!))
        assertEquals(1, canceller.sweepOnce(windowMs = DAY).failed)
        play.failCancel = false
        assertEquals(1, canceller.sweepOnce(windowMs = DAY).reconciled)
        assertEquals(listOf("tok-cg"), play.cancelled)
    }

    @Test
    fun aStripePurchaseDuringAccountHold_stillRevokesGoogle() {
        val (accountId, session) = signedIn("hold@example.com")
        purchase("tok-hold", accountId)
        appTest { client ->
            client.verify(session, "tok-hold")
            // In account hold the notifications ended the grants, but Play may still recover it.
            store.endEntitlements(accountId, EntitlementSource.IapGoogle, FIXED_NOW - 1)
            play.subscriptions["tok-hold"] = play.subscriptions.getValue("tok-hold").copy(state = "SUBSCRIPTION_STATE_ON_HOLD")
            assertEquals(HttpStatusCode.OK, client.postWebhook(invoicePaid("evt_hold", "hold@example.com")).status)
        }
        assertEquals(listOf("tok-hold"), play.revoked)
    }

    @Test
    fun aStripePurchaseOverAnExpiredGooglePlan_revokesNothing() {
        val (accountId, session) = signedIn("expired@example.com")
        purchase("tok-exp", accountId)
        appTest { client ->
            client.verify(session, "tok-exp")
            play.subscriptions["tok-exp"] = play.subscriptions.getValue("tok-exp").copy(state = "SUBSCRIPTION_STATE_EXPIRED")
            play.failRevoke = true
            assertEquals(HttpStatusCode.OK, client.postWebhook(invoicePaid("evt_exp", "expired@example.com")).status)
        }
        assertTrue(play.revoked.isEmpty())
        assertEquals(EntitlementSource.Stripe, current(accountId)?.source)
    }

    @Test
    fun aStripePurchaseForAClosedAccount_revokesNothingOnItsBehalf() {
        val (accountId, session) = signedIn("closed-s@example.com")
        purchase("tok-cs", accountId)
        appTest { client ->
            client.verify(session, "tok-cs")
            store.closeAccount(accountId, FIXED_NOW)
            assertEquals(HttpStatusCode.OK, client.postWebhook(invoicePaid("evt_cs", "closed-s@example.com")).status)
        }
        assertTrue(play.revoked.isEmpty(), "the grant is refused anyway; closing stops Google")
    }

    @Test
    fun billing_offersNothingTheRealmRefuses_andVerifyRefusesTheSame() {
        // Two accounts on one device (one owner): A pays on Stripe, B is signed in on a Play build.
        val (a, _) = signedIn("realm-a@example.com", ownerId = "owner-realm")
        store.appendEntitlementHistory(grant(a, ProductCatalog.BASIC, EntitlementSource.Stripe))
        val (b, sessionB) = signedIn("realm-b@example.com", ownerId = "owner-realm")
        purchase("tok-rb", b, ProductCatalog.PREMIUM)
        appTest { client ->
            val billing = client.billing(sessionB, rail = "Google")
            assertEquals(emptyList(), billing.offers, "a purchase here would leave A's Stripe plan billing")
            assertEquals("subscribed_under_another_account", billing.refusal)
            val response = client.verify(sessionB, "tok-rb")
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue("subscribed_under_another_account" in response.bodyAsText())
        }
    }

    @Test
    fun billing_onStripe_offersNothingOverAGooglePlanUnderAnotherAccount() {
        val (a, sessionA) = signedIn("realm-g@example.com", ownerId = "owner-realm-g")
        purchase("tok-rg", a)
        val (_, sessionB) = signedIn("realm-s@example.com", ownerId = "owner-realm-g")
        appTest { client ->
            client.verify(sessionA, "tok-rg")
            val billing = client.billing(sessionB, rail = "Stripe")
            assertEquals(emptyList(), billing.offers)
            assertEquals("subscribed_under_another_account", billing.refusal)
        }
    }

    private fun grant(accountId: String, productId: String, source: EntitlementSource) = EntitlementHistory(

        accountId = accountId, productId = productId, validFrom = FIXED_NOW - 1, validUntil = FIXED_NOW + MONTH,
        source = source, issuedAt = FIXED_NOW,
    )

    private suspend fun HttpClient.postWebhook(payload: String): HttpResponse {
        val ts = FIXED_NOW / 1000
        return post("/commerce/webhook/stripe") {
            contentType(ContentType.Application.Json)
            header("Stripe-Signature", "t=$ts,v1=${hmacSha256Hex(WEBHOOK_SECRET, "$ts.$payload")}")
            setBody(payload)
        }
    }

    private fun invoicePaid(eventId: String, email: String) = """
        {"id":"$eventId","type":"invoice.paid","data":{"object":{
          "id":"in_$eventId","customer":"cus_$eventId","customer_email":"$email",
          "amount_paid":999,"currency":"eur","tax":160,
          "metadata":{"product_id":"${ProductCatalog.PREMIUM}"},
          "lines":{"data":[{"period":{"start":${FIXED_NOW / 1000},"end":${(FIXED_NOW + MONTH) / 1000}}}]}
        }}}
    """.trimIndent()

    private companion object {
        const val FIXED_NOW = 1_700_000_000_000L
        const val DAY = 24L * 60 * 60 * 1000
        const val MONTH = 30 * DAY
        const val WEBHOOK_SECRET = "whsec_test"
    }
}
