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
import org.tenshu.commerce.store.AcceptanceKind
import org.tenshu.commerce.store.InMemoryCommerceStore
import org.tenshu.commerce.stripe.FakeStripeClient
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The purchase-time acknowledgement that the service starts immediately, which is what disapplies
 * the EU 14-day right of withdrawal (policy §5.4).
 *
 * ⚠⚠ **The checkbox lives in the client, but the rule lives here.** A UI-only checkbox is
 * decoration: `/commerce/checkout` is an ordinary HTTP route anyone can POST directly, so if the
 * server accepted a request without the acknowledgement, the terms would claim a waiver we could
 * not evidence. These tests pin the refusal and the durable record, not the tick.
 */
class WithdrawalAcknowledgementTest {

    private val store = InMemoryCommerceStore()
    private val sender = RecordingEmailSender()
    private val clock = AtomicLong(1_700_000_000_000L)
    private val webhookSecret = "whsec_ack"
    private val tosVersion = "2026-08-06"

    private fun appTest(block: suspend (HttpClient) -> Unit) = testApplication {
        val fake = FakeStripeClient(publicBaseUrl = "")
        lateinit var appClient: HttpClient
        application {
            module(
                store = store,
                emailSender = sender,
                now = { clock.get() },
                stripe = StripeSupport(
                    client = fake,
                    priceIds = FakeStripeClient.fakePriceIds(),
                    webhookSecret = webhookSecret,
                    successUrl = "tenshu://checkout-result?status=success",
                    cancelUrl = "c",
                    portalReturnUrl = "p",
                ),
                fakeStripe = fake,
                fakeStripeWebhookPost = { payload, signature ->
                    appClient.post("/commerce/webhook/stripe") {
                        header("Stripe-Signature", signature)
                        setBody(payload)
                    }.status.value in 200..299
                },
            )
        }
        appClient = client
        block(client)
    }

    private suspend fun HttpClient.checkout(body: String) = post("/commerce/checkout") {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun aCheckoutWithoutTheAcknowledgementIsRefused() = appTest { client ->
        val response = client.checkout(
            """{"ownerId":"owner-1","productId":"${ProductCatalog.PREMIUM}"}""",
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(
            response.bodyAsText().contains("acknowledgement_required"),
            "the refusal should name its reason so a client can render the right prompt",
        )
    }

    @Test
    fun anExplicitlyFalseAcknowledgementIsAlsoRefused() = appTest { client ->
        // Absent and false must behave identically — otherwise "the field defaulted" becomes a way
        // through, and a default is exactly what a waiver may never be.
        val response = client.checkout(
            """{"ownerId":"owner-1","productId":"${ProductCatalog.PREMIUM}","withdrawalAcknowledged":false,"tosVersion":"$tosVersion"}""",
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun anAcknowledgementWithoutATermsVersionIsRefused() = appTest { client ->
        // A waiver that cannot say *what* was acknowledged is not evidence of anything.
        val response = client.checkout(
            """{"ownerId":"owner-1","productId":"${ProductCatalog.PREMIUM}","withdrawalAcknowledged":true}""",
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun anAcknowledgedCheckoutProceeds() = appTest { client ->
        val response = client.checkout(
            """{"ownerId":"owner-1","productId":"${ProductCatalog.PREMIUM}","withdrawalAcknowledged":true,"tosVersion":"$tosVersion"}""",
        )
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun completingThePurchaseRecordsTheWaiverAgainstTheAccount() = appTest { client ->
        // The evidence trail, end to end: the acknowledgement rides to the rail as metadata and
        // comes back on the event, because at checkout time there is no account to attach it to —
        // the account is created from the email the rail collects.
        val checkoutUrl = client.checkout(
            """{"ownerId":"owner-1","productId":"${ProductCatalog.PREMIUM}","withdrawalAcknowledged":true,"tosVersion":"$tosVersion"}""",
        ).bodyAsText().substringAfter("\"checkoutUrl\":\"").substringBefore("\"")

        client.post("$checkoutUrl/confirm") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("email=waiver@example.com")
        }

        val account = assertNotNull(store.findAccountByEmail("waiver@example.com"))
        val waiver = store.acceptances()
            .filter { it.accountId == account.id && it.kind == AcceptanceKind.PurchaseWithdrawalWaiver }
        assertEquals(1, waiver.size, "a completed purchase must leave exactly one waiver record")
        assertEquals(tosVersion, waiver.single().tosVersion, "and it must say which terms were acknowledged")
    }

    @Test
    fun aRenewalDoesNotWriteAFreshWaiver() = appTest { client ->
        // A renewal is not a new contract, so it carries no acknowledgement and must not
        // manufacture one. Absence here is correct rather than a gap.
        val account = store.createAccount(
            "renewal@example.com",
            rail = org.tenshu.commerce.store.Rail.Stripe,
            createdAt = clock.get(),
        )
        val payload = """{"id":"evt_renewal","type":"invoice.paid","data":{"object":{"id":"in_1","customer_email":"renewal@example.com","amount_paid":999,"currency":"eur","metadata":{"product_id":"${ProductCatalog.PREMIUM}"}}}}"""
        val ts = clock.get() / 1000
        val signature = "t=$ts,v1=${hmacSha256Hex(webhookSecret, "$ts.$payload")}"
        val response = client.post("/commerce/webhook/stripe") {
            header("Stripe-Signature", signature)
            setBody(payload)
        }
        assertEquals(HttpStatusCode.OK, response.status)

        assertTrue(
            store.acceptances().none {
                it.accountId == account.id && it.kind == AcceptanceKind.PurchaseWithdrawalWaiver
            },
            "a renewal must not fabricate an acknowledgement the customer never gave",
        )
    }

    @Test
    fun theHostedPageStatesWhatIsBeingAcknowledged() = appTest { client ->
        // The dev rail's page stands in for Stripe Checkout. Wherever the purchase is confirmed,
        // the immediate-start consequence has to be legible at that moment — not only in the terms.
        val checkoutUrl = client.checkout(
            """{"ownerId":"owner-1","productId":"${ProductCatalog.PREMIUM}","withdrawalAcknowledged":true,"tosVersion":"$tosVersion"}""",
        ).bodyAsText().substringAfter("\"checkoutUrl\":\"").substringBefore("\"")

        val page = client.get(checkoutUrl).bodyAsText()
        assertTrue(page.contains("starts immediately", ignoreCase = true), "page was: $page")
    }
}
