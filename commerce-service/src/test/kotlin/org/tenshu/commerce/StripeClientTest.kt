package org.tenshu.commerce

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.tenshu.commerce.stripe.CheckoutSessionRequest
import org.tenshu.commerce.stripe.RealStripeClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [RealStripeClient] over a `MockEngine` — verifies the outbound Stripe REST request (form encoding,
 * Bearer auth, Stripe Tax + client_reference_id) and the `url` extraction, without touching live
 * Stripe. Live test-mode round-trips are deploy-time verification.
 */
class StripeClientTest {

    private val apiBase = "https://api.stripe.test"

    @Test
    fun createCheckoutSession_postsFormAndReturnsUrl() {
        var url: String? = null
        var auth: String? = null
        var form: Parameters? = null
        val engine = MockEngine { request ->
            url = request.url.toString()
            auth = request.headers[HttpHeaders.Authorization]
            form = (request.body as FormDataContent).formData
            respond(
                content = """{"id":"cs_1","url":"https://checkout.stripe.com/c/pay/cs_1"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = RealStripeClient(HttpClient(engine), secretKey = "sk_test_123", apiBase = apiBase)

        val result = runBlocking {
            client.createCheckoutSession(
                CheckoutSessionRequest(
                    priceId = "price_basic",
                    ownerId = "owner-1",
                    productId = ProductCatalog.BASIC,
                    successUrl = "tenshu://ok",
                    cancelUrl = "tenshu://no",
                ),
            )
        }

        assertEquals("https://checkout.stripe.com/c/pay/cs_1", result)
        assertTrue(url!!.endsWith("/v1/checkout/sessions"))
        assertEquals("Bearer sk_test_123", auth)
        assertEquals("subscription", form!!["mode"])
        assertEquals("price_basic", form!!["line_items[0][price]"])
        assertEquals("owner-1", form!!["client_reference_id"])
        assertEquals("true", form!!["automatic_tax[enabled]"])
        assertEquals(ProductCatalog.BASIC, form!!["metadata[product_id]"])
    }

    @Test
    fun createPortalSession_postsCustomerAndReturnsUrl() {
        var url: String? = null
        var form: Parameters? = null
        val engine = MockEngine { request ->
            url = request.url.toString()
            form = (request.body as FormDataContent).formData
            respond(
                content = """{"id":"bps_1","url":"https://billing.stripe.com/p/session/bps_1"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = RealStripeClient(HttpClient(engine), secretKey = "sk_test_123", apiBase = apiBase)

        val result = runBlocking { client.createPortalSession("cus_1", "tenshu://back") }

        assertEquals("https://billing.stripe.com/p/session/bps_1", result)
        assertTrue(url!!.endsWith("/v1/billing_portal/sessions"))
        assertEquals("cus_1", form!!["customer"])
        assertEquals("tenshu://back", form!!["return_url"])
    }

    @Test
    fun nonSuccessStatus_returnsNull() {
        val engine = MockEngine {
            respond(
                content = """{"error":{"message":"nope"}}""",
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = RealStripeClient(HttpClient(engine), secretKey = "sk_bad", apiBase = apiBase)

        val result = runBlocking {
            client.createCheckoutSession(
                CheckoutSessionRequest("price_x", "owner-1", ProductCatalog.BASIC, "tenshu://ok", "tenshu://no"),
            )
        }
        assertNull(result)
    }

    @Test
    fun invoicePaymentEvidence_readsThePaymentIntentAndCardCountry() {
        var url: String? = null
        var version: String? = null
        val engine = MockEngine { request ->
            url = request.url.toString()
            version = request.headers["Stripe-Version"]
            // Trimmed from a live test-mode response to the same request.
            respond(
                content = """
                    {"object":"list","data":[{"id":"inpay_1","object":"invoice_payment","invoice":"in_1",
                      "payment":{"type":"payment_intent","payment_intent":{"id":"pi_1","object":"payment_intent",
                        "latest_charge":{"id":"ch_1","object":"charge",
                          "billing_details":{"address":{"country":"DE"}},
                          "payment_method_details":{"card":{"brand":"visa","country":"US"},"type":"card"}}}},
                      "status":"paid"}],"has_more":false}
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = RealStripeClient(HttpClient(engine), secretKey = "sk_test_123", apiBase = apiBase)

        val evidence = runBlocking { client.invoicePaymentEvidence("in_1") }

        assertEquals("pi_1", evidence?.paymentRef)
        assertEquals("US", evidence?.cardCountry)
        assertTrue("/v1/invoice_payments?" in url!!)
        assertTrue("invoice=in_1" in url!!)
        assertTrue("data.payment.payment_intent.latest_charge" in url!!)
        // Not the account default: `/v1/invoice_payments` does not exist on older versions.
        assertEquals(RealStripeClient.API_VERSION, version)
    }

    @Test
    fun invoicePaymentEvidence_isNullWhenStripeRefuses() {
        val engine = MockEngine { respond(content = """{"error":{}}""", status = HttpStatusCode.BadRequest) }
        val client = RealStripeClient(HttpClient(engine), secretKey = "sk_test_123", apiBase = apiBase)

        assertNull(runBlocking { client.invoicePaymentEvidence("in_1") })
    }
}
