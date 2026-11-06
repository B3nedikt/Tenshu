package org.tenshu.commerce

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.commerce.routing.StripeSupport
import org.tenshu.commerce.store.CreditNoteKind
import org.tenshu.commerce.store.EntitlementHistory
import org.tenshu.commerce.store.EntitlementSource
import org.tenshu.commerce.store.InMemoryCommerceStore
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
 * Money-*out* coverage for `/commerce/webhook/stripe` (WC-1, §4.7): refunds and lost disputes append
 * credit notes so a later OSS return can **correct** VAT already remitted on a reversed sale.
 *
 * The load-bearing cases here are the ones that would silently mis-state a VAT return rather than
 * fail loudly: cumulative refund totals being double-counted, an unlinked note reporting `0` VAT
 * instead of "unknown", and a second location evidence being manufactured by copying the first.
 */
class StripeReversalWebhookTest {

    private val store = InMemoryCommerceStore()
    private val webhookSecret = "whsec_test"

    private class LookupStripe : StripeClient {
        var refunds: List<ChargeRefund>? = emptyList()
        override suspend fun createCheckoutSession(request: CheckoutSessionRequest): String? = null
        override suspend fun createPortalSession(customerId: String, returnUrl: String): String? = null
        override suspend fun billingSubscriptions(customerId: String): List<BillingSubscription>? = emptyList()
        override suspend fun refundsForCharge(chargeId: String): List<ChargeRefund>? = refunds
        override suspend fun invoicePaymentEvidence(invoiceId: String): InvoicePaymentEvidence? = null
        /** `null` = Stripe could not be told to stop. */
        var cancelResult: Int? = 1
        val cancelledCustomers = mutableListOf<String>()
        override suspend fun cancelActiveSubscriptions(customerId: String): Int? {
            cancelledCustomers += customerId
            return cancelResult
        }
    }

    private val stripeClient = LookupStripe()

    private val support = StripeSupport(
        client = stripeClient,
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

    private suspend fun HttpClient.postWebhook(payload: String) =
        post("/commerce/webhook/stripe") {
            contentType(ContentType.Application.Json)
            header("Stripe-Signature", signature(payload))
            setBody(payload)
        }

    // --- fixtures ---------------------------------------------------------------------------

    /**
     * The sale being reversed: gross 1190, VAT 190 (19%), billed to DE. [paymentIntent] rides in the
     * shape a real Invoice uses — under `payments.data[].payment` — since the object has no
     * top-level `charge` at all.
     */
    private fun invoicePaid(
        eventId: String = "evt_sale",
        card: String? = null,
        paymentIntent: String? = null,
    ): String {
        val pmd = card?.let { ""","payment_method_details":{"card":{"country":"$it"}}""" } ?: ""
        val payments = paymentIntent
            ?.let { ""","payments":{"data":[{"payment":{"payment_intent":"$it"}}]}""" }
            .orEmpty()
        return """
            {"id":"$eventId","type":"invoice.paid","data":{"object":{
              "id":"in_1","customer":"cus_1","customer_email":"buyer@x.com","client_reference_id":"owner-9",
              "customer_details":{"email":"buyer@x.com","address":{"country":"DE"}},
              "amount_paid":1190,"currency":"eur","tax":190$pmd$payments,
              "metadata":{"product_id":"${ProductCatalog.PREMIUM}","owner_id":"owner-9"},
              "current_period_end":1701000000}}}
        """.trimIndent()
    }

    /**
     * A `charge.refunded`. ⚠ `refunds.data` is **cumulative** in Stripe: the second partial refund's
     * event repeats the first entry alongside the new one — which is exactly the double-count trap.
     */
    private fun chargeRefunded(
        eventId: String,
        refunds: List<Pair<String, Long>>,
        invoice: String? = "in_1",
        customer: String = "cus_1",
    ): String {
        val entries = refunds.joinToString(",") { (id, amount) ->
            """{"id":"$id","amount":$amount,"currency":"eur"}"""
        }
        val invoiceField = invoice?.let { ""","invoice":"$it"""" } ?: ""
        return """
            {"id":"$eventId","type":"charge.refunded","data":{"object":{
              "id":"ch_1","customer":"$customer"$invoiceField,"currency":"eur",
              "refunds":{"data":[$entries]}}}}
        """.trimIndent()
    }

    private fun disputeClosed(
        eventId: String,
        status: String,
        amount: Long = 1190,
        paymentIntent: String? = null,
        charge: String? = null,
    ): String {
        val payment = listOfNotNull(
            paymentIntent?.let { """"payment_intent":"$it"""" },
            charge?.let { """"charge":"$it"""" },
        ).joinToString("") { ",$it" }
        return """
            {"id":"$eventId","type":"charge.dispute.closed","data":{"object":{
              "id":"dp_1","customer":"cus_1","amount":$amount,"currency":"eur","status":"$status"$payment}}}
        """.trimIndent()
    }

    // --- refunds ----------------------------------------------------------------------------

    @Test
    fun fullRefund_creditsTheSaleAndApportionsVatFromTheOriginalInvoice() = appTest { client ->
        client.postWebhook(invoicePaid())
        assertEquals(HttpStatusCode.OK, client.postWebhook(chargeRefunded("evt_r1", listOf("re_1" to 1190))).status)

        val notes = store.allCreditNotes()
        assertEquals(1, notes.size)
        val note = notes.single()
        assertEquals(CreditNoteKind.Refund, note.kind)
        assertEquals("re_1", note.stripeReferenceId)
        assertEquals(1190, note.amountMinor)
        // A full reversal credits back exactly the VAT that was invoiced.
        assertEquals(190, note.vatAmountMinor)
        assertEquals(1900, note.vatRateBasisPoints)
        // The correction has to know WHICH member state's return to correct.
        assertEquals("DE", note.billingCountry)
        assertEquals(store.allInvoices().single().number, note.referencesInvoiceNumber)
    }

    @Test
    fun partialRefund_apportionsVatProportionally() = appTest { client ->
        client.postWebhook(invoicePaid())
        client.postWebhook(chargeRefunded("evt_r1", listOf("re_1" to 595)))

        val note = store.allCreditNotes().single()
        assertEquals(595, note.amountMinor)
        assertEquals(95, note.vatAmountMinor) // 595 * 190 / 1190
    }

    /**
     * ⚠⚠ The double-count trap. Stripe's second partial-refund event is a **new event id** carrying a
     * **cumulative** refund list, so anything keyed on the charge would credit 595 + 1190 = 1785
     * against a 1190 sale. Keying on the per-refund id makes it fall out with no arithmetic.
     */
    @Test
    fun successivePartialRefunds_creditEachRefundExactlyOnce() = appTest { client ->
        client.postWebhook(invoicePaid())
        client.postWebhook(chargeRefunded("evt_r1", listOf("re_1" to 595)))
        client.postWebhook(chargeRefunded("evt_r2", listOf("re_1" to 595, "re_2" to 595)))

        val notes = store.allCreditNotes()
        assertEquals(2, notes.size)
        assertEquals(listOf("re_1", "re_2"), notes.map { it.stripeReferenceId })
        assertEquals(1190, notes.sumOf { it.amountMinor }) // exactly the sale, never more
        assertEquals(190, notes.sumOf { it.vatAmountMinor ?: 0 })
    }

    @Test
    fun redeliveredRefundUnderANewEventId_isNotCreditedTwice() = appTest { client ->
        client.postWebhook(invoicePaid())
        client.postWebhook(chargeRefunded("evt_r1", listOf("re_1" to 1190)))
        // Same refund, fresh event id — webhook-level event dedupe does NOT cover this.
        client.postWebhook(chargeRefunded("evt_r1_replay", listOf("re_1" to 1190)))

        assertEquals(1, store.allCreditNotes().size)
    }

    /**
     * `charge.refunded` as live Stripe sends it (API `2026-06-24.dahlia`): `amount_refunded`, the
     * PaymentIntent, and **no `refunds` list and no `invoice`**. The hand-written fixture above
     * carries both, which is how two real refunds were acknowledged and credited nothing.
     */
    private fun liveChargeRefunded(eventId: String) = """
        {"id":"$eventId","type":"charge.refunded","data":{"object":{
          "id":"ch_1","object":"charge","amount":1190,"amount_refunded":500,"currency":"eur",
          "customer":"cus_1","payment_intent":"pi_sale","refunded":false,"status":"succeeded"}}}
    """.trimIndent()

    @Test
    fun liveRefundEvent_readsTheRefundsFromStripe_andLinksThemByPayment() = appTest { client ->
        client.postWebhook(invoicePaid(paymentIntent = "pi_sale"))
        stripeClient.refunds = listOf(ChargeRefund("re_a", 300, "eur"), ChargeRefund("re_b", 200, "eur"))

        assertEquals(HttpStatusCode.OK, client.postWebhook(liveChargeRefunded("evt_live_refund")).status)

        val notes = store.allCreditNotes()
        assertEquals(listOf("re_a", "re_b"), notes.map { it.stripeReferenceId })
        assertEquals(listOf(300L, 200L), notes.map { it.amountMinor })
        // Linked to the sale through the payment, so VAT is apportioned at its rate.
        assertTrue(notes.all { it.referencesInvoiceNumber != null && it.vatRateBasisPoints == 1900 })
    }

    /** An acknowledged event is never redelivered, so a failed read must not be acknowledged. */
    @Test
    fun whenTheRefundsCannotBeRead_theEventIsRefused_andItsRedeliveryCredits() = appTest { client ->
        client.postWebhook(invoicePaid(paymentIntent = "pi_sale"))
        stripeClient.refunds = null

        assertEquals(HttpStatusCode.ServiceUnavailable, client.postWebhook(liveChargeRefunded("evt_retry")).status)
        assertTrue(store.allCreditNotes().isEmpty())

        stripeClient.refunds = listOf(ChargeRefund("re_a", 300, "eur"))
        assertEquals(HttpStatusCode.OK, client.postWebhook(liveChargeRefunded("evt_retry")).status)
        assertEquals(1, store.allCreditNotes().size)
    }

    @Test
    fun refundForAnUnattributableCustomer_isNotCredited() = appTest { client ->
        val response = client.postWebhook(
            chargeRefunded("evt_r1", listOf("re_x" to 500), invoice = null, customer = "cus_unknown"),
        )
        // Acknowledged (so Stripe stops retrying) but nothing invented.
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(store.allCreditNotes().isEmpty())
    }

    @Test
    fun refundWithNoInvoiceLink_recordsUnknownVatRatherThanZero() = appTest { client ->
        client.postWebhook(invoicePaid()) // establishes the account behind cus_1
        client.postWebhook(chargeRefunded("evt_r1", listOf("re_1" to 500), invoice = null))

        val note = store.allCreditNotes().single()
        assertEquals(500, note.amountMinor)
        // ⚠⚠ Null, not 0: zero would be a false claim that no VAT was involved, and would corrupt
        // the per-country total an OSS return is filled from.
        assertNull(note.vatAmountMinor)
        assertNull(note.vatRateBasisPoints)
        assertNull(note.referencesInvoiceNumber)
    }

    // --- disputes ---------------------------------------------------------------------------

    @Test
    fun lostDispute_linksToItsInvoiceThroughThePaymentIntent() = appTest { client ->
        // ⚠⚠ WC-6, the point of the slice: the dispute finds its invoice through the **payment** it
        // names, so the reversal carries a country and an apportioned VAT and lands in that
        // country's total. Before this it was always unlinked, leaving a fully-reversed sale
        // reported in full while an equal figure sat in the export's `(unreconciled)` bucket.
        client.postWebhook(invoicePaid(paymentIntent = "pi_9"))
        client.postWebhook(disputeClosed("evt_d1", status = "lost", paymentIntent = "pi_9"))

        val note = store.allCreditNotes().single()
        assertEquals(CreditNoteKind.Chargeback, note.kind)
        assertEquals("dp_1", note.stripeReferenceId)
        assertEquals(1190, note.amountMinor)
        assertEquals(190, note.vatAmountMinor, "a full reversal credits back the invoiced VAT")
        assertEquals("DE", note.billingCountry, "…and knows which member state to correct")
        assertEquals(store.allInvoices().single().number, note.referencesInvoiceNumber)
    }

    /** The charge id is the fallback for a payment that has no PaymentIntent. */
    @Test
    fun lostDispute_fallsBackToTheChargeId() = appTest { client ->
        client.postWebhook(invoicePaid(paymentIntent = "ch_direct"))
        client.postWebhook(disputeClosed("evt_d1", status = "lost", charge = "ch_direct"))

        assertEquals("DE", store.allCreditNotes().single().billingCountry)
    }

    /**
     * ⚠ An invoice written before WC-6 stored no payment reference, so its disputes stay unlinked —
     * by construction, since the reference only ever existed in the event. The credit is still
     * recorded; it just keeps the unknown-VAT flag the export surfaces.
     */
    @Test
    fun lostDispute_onAPreWc6Invoice_isStillCreditedButUnlinked() = appTest { client ->
        client.postWebhook(invoicePaid()) // no payments block ⇒ nothing stored to match on
        client.postWebhook(disputeClosed("evt_d1", status = "lost", paymentIntent = "pi_9"))

        val note = store.allCreditNotes().single()
        assertEquals(1190, note.amountMinor)
        assertNull(note.vatAmountMinor, "unlinked ⇒ VAT unknown, never a confident zero")
        assertNull(note.referencesInvoiceNumber)
    }

    /** A dispute naming a payment we never invoiced must not attach itself to some other sale. */
    @Test
    fun lostDispute_withAnUnknownPaymentDoesNotLinkToAnUnrelatedInvoice() = appTest { client ->
        client.postWebhook(invoicePaid(paymentIntent = "pi_9"))
        client.postWebhook(disputeClosed("evt_d1", status = "lost", paymentIntent = "pi_someone_else"))

        assertNull(store.allCreditNotes().single().referencesInvoiceNumber)
    }

    @Test
    fun wonDispute_creditsNothing() = appTest { client ->
        client.postWebhook(invoicePaid())
        client.postWebhook(disputeClosed("evt_d1", status = "won"))

        // A dispute we won reverses no revenue; crediting it would understate what we owe.
        assertTrue(store.allCreditNotes().isEmpty())
    }

    // --- the second location evidence (§5) ---------------------------------------------------

    @Test
    fun secondLocationEvidence_isCapturedWhenTheCardCountryIsPresent() = appTest { client ->
        client.postWebhook(invoicePaid(card = "AT"))

        val invoice = store.allInvoices().single()
        assertEquals("DE", invoice.billingCountry)
        assertEquals("AT", invoice.ipCountry)
    }

    /**
     * ⚠⚠ The mitigation, pinned: an absent card country must stay **null** and must never fall back
     * to the billing country. Two identical evidences are not two evidences, and a fabricated match
     * is worse than an honest absence — the export can flag a null, but cannot detect a fake.
     */
    @Test
    fun secondLocationEvidence_isNeverCopiedFromTheBillingCountry() = appTest { client ->
        client.postWebhook(invoicePaid(card = null))

        val invoice = store.allInvoices().single()
        assertEquals("DE", invoice.billingCountry)
        assertNull(invoice.ipCountry)
    }

    // --- a lost chargeback takes the plan back ------------------------------------------------

    /**
     * ⚠⚠ The payment is gone, so the plan goes with it — and the subscription stops. Before this a
     * charged-back customer kept the storage to the end of the period and was billed again at the
     * next renewal, which re-granted it.
     */
    @Test
    fun lostDispute_endsThePaidPlan_andStopsTheSubscription() = appTest { client ->
        client.postWebhook(invoicePaid(paymentIntent = "pi_sale"))
        val account = assertNotNull(store.findAccountByEmail("buyer@x.com"))
        assertEquals(ProductCatalog.PREMIUM, store.currentEntitlement(account.id, FIXED_NOW)?.productId)

        assertEquals(HttpStatusCode.OK, client.postWebhook(disputeClosed("evt_d1", "lost", paymentIntent = "pi_sale")).status)

        assertNull(store.currentEntitlement(account.id, FIXED_NOW))
        assertEquals(listOf("cus_1"), stripeClient.cancelledCustomers)
    }

    /** What the account holds besides the paid plan is what it falls back to. */
    @Test
    fun lostDispute_leavesTheFreePlanStanding() = appTest { client ->
        client.postWebhook(invoicePaid(paymentIntent = "pi_sale"))
        val account = assertNotNull(store.findAccountByEmail("buyer@x.com"))
        store.appendEntitlementHistory(
            EntitlementHistory(account.id, ProductCatalog.FREE, 0L, Long.MAX_VALUE, EntitlementSource.Free, 0L),
        )

        client.postWebhook(disputeClosed("evt_d1", "lost", paymentIntent = "pi_sale"))

        assertEquals(ProductCatalog.FREE, store.currentEntitlement(account.id, FIXED_NOW)?.productId)
    }

    @Test
    fun wonDispute_keepsThePlan_andCancelsNothing() = appTest { client ->
        client.postWebhook(invoicePaid(paymentIntent = "pi_sale"))
        val account = assertNotNull(store.findAccountByEmail("buyer@x.com"))

        client.postWebhook(disputeClosed("evt_d1", "won", paymentIntent = "pi_sale"))

        assertEquals(ProductCatalog.PREMIUM, store.currentEntitlement(account.id, FIXED_NOW)?.productId)
        assertTrue(stripeClient.cancelledCustomers.isEmpty())
    }

    /**
     * An acknowledged event is never redelivered, and a subscription left billing after its
     * payment was charged back charges the same card again next month.
     */
    @Test
    fun whenTheSubscriptionCannotBeStopped_theEventIsRefused_andItsRedeliveryFinishesTheJob() = appTest { client ->
        client.postWebhook(invoicePaid(paymentIntent = "pi_sale"))
        stripeClient.cancelResult = null

        assertEquals(
            HttpStatusCode.ServiceUnavailable,
            client.postWebhook(disputeClosed("evt_d1", "lost", paymentIntent = "pi_sale")).status,
        )

        stripeClient.cancelResult = 1
        assertEquals(HttpStatusCode.OK, client.postWebhook(disputeClosed("evt_d1", "lost", paymentIntent = "pi_sale")).status)
        // Credited once, however many times it took.
        assertEquals(1, store.allCreditNotes().size)
        assertEquals(2, stripeClient.cancelledCustomers.size)
    }

    // --- the sale path is untouched -----------------------------------------------------------

    @Test
    fun aReversalDoesNotDisturbTheEntitlementLedger() = appTest { client ->
        client.postWebhook(invoicePaid())
        client.postWebhook(chargeRefunded("evt_r1", listOf("re_1" to 1190)))

        // WC-1 is ledger-only: revoking access on refund is a separate product decision, and
        // "stop reissuing" remains the revocation model (§6).
        val account = assertNotNull(store.findAccountByEmail("buyer@x.com"))
        assertNotNull(store.currentEntitlement(account.id, FIXED_NOW))
    }

    private companion object {
        const val FIXED_NOW = 1_700_000_000_000L
    }
}
