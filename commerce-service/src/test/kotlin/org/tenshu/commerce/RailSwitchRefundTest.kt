package org.tenshu.commerce

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.tenshu.commerce.stripe.InvoiceLineShare
import org.tenshu.commerce.stripe.RealStripeClient
import org.tenshu.commerce.stripe.unusedShareOfInvoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stripe's half of a rail switch (D6): refund the unused time pro rata, once, then cancel. */
class RailSwitchRefundTest {

    private fun line(amount: Long, start: Long, end: Long) = InvoiceLineShare(amount, start, end)

    @Test
    fun unusedShare_isTheShareOfTheRemainingPeriod_roundedDown() {
        assertEquals(500, unusedShareOfInvoice(1000, listOf(line(1000, 0, 100)), nowMs = 50))
        assertEquals(333, unusedShareOfInvoice(999, listOf(line(999, 0, 3)), nowMs = 2))
        assertEquals(0, unusedShareOfInvoice(1000, listOf(line(1000, 0, 100)), nowMs = 150))
        assertEquals(1000, unusedShareOfInvoice(1000, listOf(line(1000, 100, 200)), nowMs = 0))
        assertEquals(0, unusedShareOfInvoice(1000, listOf(line(1000, 100, 100)), nowMs = 0))
    }

    @Test
    fun unusedShare_scalesTheLinesToWhatWasPaid_andNetsAProrationPair() {
        // Tax-exclusive €8.40 + VAT = €9.99 paid: half the period left refunds half of what was paid.
        assertEquals(499, unusedShareOfInvoice(999, listOf(line(840, 0, 1000)), nowMs = 500))
        // A portal upgrade at 500: credit -150 for Basic's rest, +500 for Premium's rest; 350 paid.
        // At 750 half of that stretch is left: 175.
        assertEquals(175, unusedShareOfInvoice(350, listOf(line(-150, 500, 1000), line(500, 500, 1000)), nowMs = 750))
        // A credit note-like invoice refunds nothing.
        assertEquals(0, unusedShareOfInvoice(0, listOf(line(-150, 500, 1000)), nowMs = 750))
    }

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private val oneInvoice = """[{"id":"in_1","status":"paid","amount_paid":999,
        "lines":{"data":[{"amount":999,"period":{"start":0,"end":1000}}]}}]"""

    /** A customer with one subscription; [invoices] its paid invoices, [refunds] those already on pi_*. */
    private fun stripe(
        log: MutableList<String>,
        invoices: String = oneInvoice,
        refunds: String = "[]",
        refundStatus: HttpStatusCode = HttpStatusCode.OK,
        refundBody: String = """{"id":"re_1"}""",
    ): RealStripeClient {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            log += "${request.method.value} $path" + when {
                request.method == HttpMethod.Post && path == "/v1/refunds" ->
                    " " + (request.body as FormDataContent).formData.let {
                        "${it["amount"]}@${it["payment_intent"]}|${it["metadata[reason]"]}/${it["metadata[subscription]"]}" +
                            "|key=${request.headers["Idempotency-Key"]}"
                    }
                else -> ""
            }
            when {
                request.method == HttpMethod.Get && path == "/v1/subscriptions" ->
                    respond("""{"data":[{"id":"sub_1","status":"active","latest_invoice":"in_1"}]}""", HttpStatusCode.OK, json)
                path == "/v1/invoices" -> {
                    assertEquals("sub_1", request.url.parameters["subscription"])
                    assertEquals("paid", request.url.parameters["status"])
                    respond("""{"data":$invoices}""", HttpStatusCode.OK, json)
                }
                path == "/v1/invoice_payments" -> {
                    val invoice = request.url.parameters["invoice"]!!
                    // in_9 was paid without a Stripe payment (out of band, or credit alone).
                    if (invoice == "in_9") return@MockEngine respond("""{"data":[]}""", HttpStatusCode.OK, json)
                    respond(
"""{"data":[{"payment":{"payment_intent":{"id":"pi_${invoice.removePrefix("in_")}"}}}]}""", HttpStatusCode.OK, json)
                }
                request.method == HttpMethod.Get && path == "/v1/refunds" -> respond("""{"data":$refunds}""", HttpStatusCode.OK, json)
                request.method == HttpMethod.Post && path == "/v1/refunds" -> respond(refundBody, refundStatus, json)
                request.method == HttpMethod.Delete && path == "/v1/subscriptions/sub_1" ->
                    respond("""{"id":"sub_1","status":"canceled"}""", HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        }
        return RealStripeClient(HttpClient(engine), "sk_test", "https://api.stripe.test")
    }

    @Test
    fun refundsTheUnusedHalf_taggedForTheSwitch_thenCancels() = runBlocking {
        val log = mutableListOf<String>()
        val refunded = stripe(log).refundUnusedAndCancel("cus_1", nowMs = 500_000)
        assertEquals(499, refunded)
        assertEquals(
            "POST /v1/refunds 499@pi_1|rail_switch/sub_1|key=rail-switch-refund-sub_1-in_1-499",
            log.single { it.startsWith("POST /v1/refunds") },
        )
        assertEquals("DELETE /v1/subscriptions/sub_1", log.last(), "cancelled only after the refund")
    }

    @Test
    fun aRetryAfterAnEarlierRefund_findsItOnThePayment_andRefundsNothingTwice() = runBlocking {
        val log = mutableListOf<String>()
        val earlier = """[{"id":"re_0","status":"succeeded","amount":499,
            "metadata":{"reason":"rail_switch","subscription":"sub_1"}}]"""
        // Later: the amount has moved, and the old idempotency key may long be pruned.
        assertEquals(499, stripe(log, refunds = earlier).refundUnusedAndCancel("cus_1", nowMs = 600_000))
        assertTrue(log.none { it.startsWith("POST /v1/refunds") }, "the earlier refund is the proof of done")
        assertEquals("DELETE /v1/subscriptions/sub_1", log.last())
    }

    @Test
    fun anIdempotencyError_isRetryLater_neverSuccess() = runBlocking {
        // Stripe replays a failed FIRST attempt under its key; reading that as "refunded" skipped it.
        val log = mutableListOf<String>()
        val client = stripe(log, refundStatus = HttpStatusCode.BadRequest, refundBody = """{"error":{"type":"idempotency_error"}}""")
        assertNull(client.refundUnusedAndCancel("cus_1", nowMs = 600_000))
        assertEquals(0, log.count { it.startsWith("DELETE") }, "not cancelled while the refund is owed")
    }

    @Test
    fun aFailedRefund_neitherCancelsNorReportsSuccess() = runBlocking {
        val log = mutableListOf<String>()
        val client = stripe(log, refundStatus = HttpStatusCode.PaymentRequired, refundBody = """{"error":{"type":"card_error"}}""")
        assertNull(client.refundUnusedAndCancel("cus_1", nowMs = 500_000))
        assertEquals(0, log.count { it.startsWith("DELETE") })
    }

    @Test
    fun afterAPortalUpgrade_thePeriodInvoiceAndTheProrationBothCount() = runBlocking {
        val log = mutableListOf<String>()
        // Newest first: the proration invoice of an upgrade at 500 s, then the period's own invoice.
        val invoices = """[
            {"id":"in_2","status":"paid","amount_paid":350,"lines":{"data":[
              {"amount":-150,"period":{"start":500,"end":1000}},
              {"amount":500,"period":{"start":500,"end":1000}}]}},
            {"id":"in_1","status":"paid","amount_paid":299,"lines":{"data":[
              {"amount":299,"period":{"start":0,"end":1000}}]}},
            {"id":"in_0","status":"paid","amount_paid":299,"lines":{"data":[
              {"amount":299,"period":{"start":-1000,"end":0}}]}}]"""
        // At 750 s: half of the proration's stretch (175) + a quarter of the period invoice (74).
        assertEquals(249, stripe(log, invoices = invoices).refundUnusedAndCancel("cus_1", nowMs = 750_000))
        val posts = log.filter { it.startsWith("POST /v1/refunds") }
        assertEquals(listOf("175@pi_2", "74@pi_1"), posts.map { it.substringAfter("refunds ").substringBefore("|") })
    }

    @Test
    fun anInvoiceWithNoPayment_refundsNothing_butTheSwitchStillCompletes() = runBlocking {
        // Paid out of band, or by credit alone: nothing to refund is an answer, not a failure.
        val log = mutableListOf<String>()
        val noPayment = """[{"id":"in_9","status":"paid","amount_paid":999,
            "lines":{"data":[{"amount":999,"period":{"start":0,"end":1000}}]}}]"""
        assertEquals(0, stripe(log, invoices = noPayment).refundUnusedAndCancel("cus_1", nowMs = 500_000))
        assertTrue(log.none { it.startsWith("POST /v1/refunds") })
        assertEquals("DELETE /v1/subscriptions/sub_1", log.last())

        val log2 = mutableListOf<String>()
        val outOfBand = """[{"id":"in_1","status":"paid","amount_paid":999,"paid_out_of_band":true,
            "lines":{"data":[{"amount":999,"period":{"start":0,"end":1000}}]}}]"""
        assertEquals(0, stripe(log2, invoices = outOfBand).refundUnusedAndCancel("cus_1", nowMs = 500_000))
        assertEquals("DELETE /v1/subscriptions/sub_1", log2.last())
    }

    @Test
    fun aPendingSwitchRefund_countsAsDone_aFailedOneDoesNot() = runBlocking {
        val log = mutableListOf<String>()
        val pending = """[{"id":"re_p","status":"pending","amount":499,
            "metadata":{"reason":"rail_switch","subscription":"sub_1"}}]"""
        assertEquals(499, stripe(log, refunds = pending).refundUnusedAndCancel("cus_1", nowMs = 500_000))
        assertTrue(log.none { it.startsWith("POST /v1/refunds") }, "in flight: refunding again could refund twice")

        val log2 = mutableListOf<String>()
        val failed = """[{"id":"re_f","status":"failed","amount":499,
            "metadata":{"reason":"rail_switch","subscription":"sub_1"}}]"""
        assertEquals(499, stripe(log2, refunds = failed).refundUnusedAndCancel("cus_1", nowMs = 500_000))
        assertEquals(1, log2.count { it.startsWith("POST /v1/refunds") }, "a failed refund is made again")
    }

    @Test
    fun aRefundMadeByHand_capsWhatIsLeftToRefund() = runBlocking {

        val log = mutableListOf<String>()
        val byHand = """[{"id":"re_h","status":"succeeded","amount":900,"metadata":{}}]"""
        assertEquals(99, stripe(log, refunds = byHand).refundUnusedAndCancel("cus_1", nowMs = 500_000))
    }
}
