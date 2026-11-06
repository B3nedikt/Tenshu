package org.tenshu.commerce

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.commerce.routing.StripeSupport
import org.tenshu.commerce.stripe.FakePrices
import org.tenshu.commerce.stripe.FakeStripeClient
import org.tenshu.commerce.store.InMemoryCommerceStore
import org.tenshu.commerce.store.Rail
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CE-3: the dev payment rail.
 *
 * ⚠ The assertions deliberately land on the **outcome in the store** — an entitlement ledger row
 * and a statutory invoice — rather than on the rail's own HTTP responses. The rail is a fixture;
 * what is under test is that a confirm click drives the *real* webhook path (signature check,
 * account create-or-find from the collected email, owner bind, ledger, invoice) exactly as a Stripe
 * event would. A test that only checked the redirect would pass just as well against a rail that
 * granted the tier directly, which is the design this explicitly rejects (D1).
 */
class FakeStripeRailTest {

    private val store = InMemoryCommerceStore()
    private val clock = AtomicLong(1_700_000_000_000L)
    private val webhookSecret = "whsec_fake_test"
    private val fake = FakeStripeClient(publicBaseUrl = "")

    /** Captures what the rail signs, and replays it into the app's own webhook route. */
    private var lastPayload: String? = null
    private var lastSignature: String? = null

    private fun appTest(block: suspend (HttpClient) -> Unit) = testApplication {
        lateinit var appClient: HttpClient
        application {
            module(
                store = store,
                emailSender = RecordingEmailSender(),
                now = { clock.get() },
                stripe = StripeSupport(
                    client = fake,
                    priceIds = FakeStripeClient.fakePriceIds(),
                    webhookSecret = webhookSecret,
                    successUrl = "tenshu://checkout-result?status=success",
                    cancelUrl = "tenshu://checkout-result?status=cancel",
                    portalReturnUrl = "tenshu://checkout-result?status=portal",
                ),
                fakeStripe = fake,
                fakeStripeWebhookPost = { payload, signature ->
                    lastPayload = payload
                    lastSignature = signature
                    // The self-call production makes, expressed against the test host: a genuine
                    // request through the real webhook route, not an internal function call.
                    appClient.post("/commerce/webhook/stripe") {
                        header("Stripe-Signature", signature)
                        setBody(payload)
                    }.status.isSuccess()
                },
            )
        }
        appClient = client
        block(client)
    }

    private fun HttpStatusCode.isSuccess() = value in 200..299

    private suspend fun HttpClient.startCheckout(ownerId: String, productId: String): String {
        val response = post("/commerce/checkout") {
            contentType(ContentType.Application.Json)
            setBody("""{"ownerId":"$ownerId","productId":"$productId","withdrawalAcknowledged":true,"tosVersion":"2026-08-06"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        // The URL is `<base><CHECKOUT_PATH>/<sessionId>`; base is "" here so it is already a path.
        return response.bodyAsText().substringAfter("\"checkoutUrl\":\"").substringBefore("\"")
    }

    /** A verified account with a live session, as `/verify` leaves it. */
    private fun signIn(email: String, ownerId: String? = null): String {
        val account = store.findAccountByEmail(email) ?: store.createAccount(email, Rail.Stripe, clock.get())
        ownerId?.let { store.bindOwner(account.id, it, clock.get()) }
        val token = "session-${account.id}"
        store.createSession(SessionTokens.hash(token), account.id, clock.get(), clock.get() + 100_000)
        return token
    }

    /**
     * A native checkout carrying a session — signed in exactly as a web one is, but sent back to the
     * deep link the test app's success URL already is.
     */
    private suspend fun HttpClient.signedInCheckout(sessionToken: String, ownerId: String, productId: String) =
        post("/commerce/checkout") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"ownerId":"$ownerId","productId":"$productId","withdrawalAcknowledged":true,""" +
                    """"tosVersion":"2026-08-06","sessionToken":"$sessionToken"}""",
            )
        }

    /** Check out and confirm while signed in; the email is the session's, so the form sends none. */
    private suspend fun HttpClient.buySignedIn(sessionToken: String, ownerId: String, productId: String) {
        val response = signedInCheckout(sessionToken, ownerId, productId)
        assertEquals(HttpStatusCode.OK, response.status)
        val checkoutUrl = response.bodyAsText().substringAfter("\"checkoutUrl\":\"").substringBefore("\"")
        val confirm = post("$checkoutUrl/confirm") { contentType(ContentType.Application.FormUrlEncoded) }
        assertTrue(confirm.status.value in 200..399, "confirm should redirect to the success page")
    }

    private fun refusal(body: String) = body.substringAfter("\"error\":\"").substringBefore("\"")

    @Test
    fun aConfirmedCheckoutGrantsThroughTheRealWebhookPath() = appTest { client ->
        val checkoutUrl = client.startCheckout("owner-1", ProductCatalog.PREMIUM)

        // The hosted page exists and asks for the email — the field the whole money path hangs on.
        val page = client.get(checkoutUrl)
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.bodyAsText().contains("email"), "the rail must collect an email, as Stripe does")

        val confirm = client.post("$checkoutUrl/confirm") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("email=buyer@example.com")
        }
        assertTrue(confirm.status.value in 200..399, "confirm should redirect to the success deep-link")

        // The event was genuinely signed in Stripe's scheme...
        val payload = assertNotNull(lastPayload)
        val signature = assertNotNull(lastSignature)
        assertTrue(
            org.tenshu.commerce.stripe.StripeSignature.verify(payload, signature, webhookSecret, clock.get()),
            "the rail must sign exactly what the real verifier accepts",
        )

        // ...and the REAL webhook handler did the work: account created from the collected email,
        // owner bound from client_reference_id, paid entitlement in the ledger.
        val account = assertNotNull(store.findAccountByEmail("buyer@example.com"))
        assertEquals("owner-1", store.liveOwner(account.id))
        val entitlement = assertNotNull(store.currentEntitlement(account.id, clock.get()))
        assertEquals(ProductCatalog.PREMIUM, entitlement.productId)
    }

    @Test
    fun aConfirmedCheckoutWritesTheStatutoryInvoice() = appTest { client ->
        val checkoutUrl = client.startCheckout("owner-2", ProductCatalog.BASIC)
        client.post("$checkoutUrl/confirm") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("email=basic@example.com")
        }

        assertNotNull(store.findAccountByEmail("basic@example.com"))
        val invoices = store.allInvoices()
        assertEquals(1, invoices.size, "money moved, so an invoice row is owed")
        val invoice = invoices.single()
        val gross = assertNotNull(FakePrices.grossMinor(ProductCatalog.BASIC))
        assertEquals(gross, invoice.amountMinor)
        assertEquals("eur", invoice.currency)
        assertEquals(FakePrices.vatMinor(gross), invoice.vatAmountMinor)
    }

    @Test
    fun confirmingTwiceCannotGrantTwice() = appTest { client ->
        val checkoutUrl = client.startCheckout("owner-3", ProductCatalog.PREMIUM)
        val first = client.post("$checkoutUrl/confirm") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("email=twice@example.com")
        }
        assertTrue(first.status.value in 200..399)

        // A reload of the confirm POST must not mint a second event. Webhook-side idempotency keys
        // on the event id, and a retry would carry a fresh one — so the session consumption here is
        // what actually stops it.
        val second = client.post("$checkoutUrl/confirm") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("email=twice@example.com")
        }
        assertEquals(HttpStatusCode.NotFound, second.status)

        assertNotNull(store.findAccountByEmail("twice@example.com"))
        assertEquals(1, store.allInvoices().size, "exactly one purchase, one invoice")
    }

    @Test
    fun confirmWithoutAValidEmailGrantsNothing() = appTest { client ->
        val checkoutUrl = client.startCheckout("owner-4", ProductCatalog.PREMIUM)
        val response = client.post("$checkoutUrl/confirm") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("email=not-an-email")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertNull(lastPayload, "no event may be signed without the field the account is built from")
    }

    @Test
    fun anUnknownSessionIsNotAGrantOracle() = appTest { client ->
        assertEquals(HttpStatusCode.NotFound, client.get("${FakeStripeClient.CHECKOUT_PATH}/cs_fake_nope").status)
        val response = client.post("${FakeStripeClient.CHECKOUT_PATH}/cs_fake_nope/confirm") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("email=attacker@example.com")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertNull(store.findAccountByEmail("attacker@example.com"), "no session, no account")
    }

    // -- the one-subscription guard, on the rail people actually click through ------------------

    /**
     * ⚠⚠ End to end: the customer the webhook attaches to the account is the key the rail recorded
     * the subscription under, so the guard's lookup on `stripeCustomerId` finds it.
     */
    @Test
    fun buyingTheSameTierTwiceIsRefused() = appTest { client ->
        val token = signIn("guard@example.com")
        client.buySignedIn(token, "owner-g1", ProductCatalog.BASIC)

        val account = assertNotNull(store.findAccountByEmail("guard@example.com"))
        val customerId = assertNotNull(account.stripeCustomerId, "the webhook attaches the rail's customer")
        assertEquals(1, fake.billingSubscriptions(customerId).size)

        val again = client.signedInCheckout(token, "owner-g1", ProductCatalog.BASIC)
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("already_subscribed", refusal(again.bodyAsText()))
    }

    @Test
    fun buyingTheOtherTierIsAPlanChange() = appTest { client ->
        val token = signIn("upgrade@example.com")
        client.buySignedIn(token, "owner-g2", ProductCatalog.BASIC)

        val upgrade = client.signedInCheckout(token, "owner-g2", ProductCatalog.PREMIUM)
        assertEquals(HttpStatusCode.Conflict, upgrade.status)
        assertEquals("plan_change_required", refusal(upgrade.bodyAsText()))
    }

    @Test
    fun aCancelledSubscriptionNoLongerBlocksCheckout() = appTest { client ->
        val token = signIn("cancel@example.com")
        client.buySignedIn(token, "owner-g3", ProductCatalog.BASIC)
        val customerId = assertNotNull(store.findAccountByEmail("cancel@example.com")?.stripeCustomerId)

        assertEquals(1, fake.cancelActiveSubscriptions(customerId))
        assertEquals(0, fake.cancelActiveSubscriptions(customerId), "a retry sweep finds nothing left")
        assertTrue(fake.billingSubscriptions(customerId).isEmpty())

        // Bought again on the SAME customer — the checkout now carries it, as it would to Stripe —
        // so the new subscription is the one the guard and the next cancel see.
        client.buySignedIn(token, "owner-g3", ProductCatalog.BASIC)
        assertEquals(customerId, store.findAccountByEmail("cancel@example.com")?.stripeCustomerId)
        val again = client.signedInCheckout(token, "owner-g3", ProductCatalog.BASIC)
        assertEquals("already_subscribed", refusal(again.bodyAsText()))
        assertEquals(1, fake.cancelActiveSubscriptions(customerId))
    }

    @Test
    fun aSecondAccountOnTheSameRealmIsRefused() = appTest { client ->
        client.buySignedIn(signIn("first@example.com"), "owner-g4", ProductCatalog.BASIC)

        // The same realm signs in with a second email that has bought nothing itself.
        val second = signIn("second@example.com", ownerId = "owner-g4")
        val response = client.signedInCheckout(second, "owner-g4", ProductCatalog.BASIC)
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("subscribed_under_another_account", refusal(response.bodyAsText()))
    }

    @Test
    fun vatIsBackedOutOfAVatInclusivePrice() {
        // European display prices include VAT, so the tax is derived from the gross, not added to a
        // net — and the split must reconstruct the gross exactly or the invoice does not balance.
        val gross = 299L
        val vat = FakePrices.vatMinor(gross)
        assertEquals(48L, vat, "19% of a 2.99 gross")
        assertTrue((gross - vat) + vat == gross)
    }
}
