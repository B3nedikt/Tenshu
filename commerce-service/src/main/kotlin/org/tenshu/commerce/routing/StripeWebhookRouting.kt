package org.tenshu.commerce.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.tenshu.commerce.store.Acceptance
import org.tenshu.commerce.store.AcceptanceKind
import org.tenshu.commerce.store.AccountStatus
import org.tenshu.commerce.store.CommerceStore
import org.tenshu.commerce.store.CreditNoteDraft
import org.tenshu.commerce.store.CreditNoteKind
import org.tenshu.commerce.store.EntitlementHistory
import org.tenshu.commerce.store.EntitlementSource
import org.tenshu.commerce.store.Invoice
import org.tenshu.commerce.store.InvoiceDraft
import org.tenshu.commerce.store.Rail
import org.tenshu.commerce.stripe.ChargeRefund
import org.tenshu.commerce.stripe.InvoicePaymentEvidence
import org.tenshu.commerce.stripe.StripeSignature

private val log = LoggerFactory.getLogger("StripeWebhook")
private val webhookJson = Json { ignoreUnknownKeys = true }

/**
 * `POST /commerce/webhook/stripe` — the money path in (`commerce-accounts-and-sessions.md` §6). On a
 * signature-verified `invoice.paid` / `checkout.session.completed`, **create-or-find the account
 * from the collected email** (§3.2), (re-)bind the owner if the checkout carried one, extend the
 * entitlement ledger, and write the statutory invoice row.
 *
 * **Signature verification is load-bearing** (§5.3, T-CB-07): a forged event could mint entitlements
 * or fabricate paid accounts, so an unsigned/invalid body is rejected before any write. The raw body
 * bytes are read verbatim ([ApplicationCall.receiveText] before ContentNegotiation) because the HMAC
 * is over the exact payload.
 *
 * **Money out is recorded too** (WC-1, §4.7): `charge.refunded` and a **lost** `charge.dispute.closed`
 * append credit notes. ⚠⚠ Not a courtesy feature — VAT already remitted on a reversed sale must be
 * **corrected** in a later OSS return, so a money-in-only ledger over-states VAT owed and causes
 * over-remittance. Chargebacks arrive whether or not the product offers refunds at all.
 *
 * ⚠ The field mapping is modelled leniently because the shape is decided by the API version pinned
 * on the webhook endpoint. It was checked against a live test-mode `invoice.paid`
 * (`2026-06-24.dahlia`), which is where `customer_address`, `total_taxes` and the payment lookup come
 * from; live Stripe is still not exercised in CI.
 *
 * Idempotency is by event id — ⚠⚠ **necessary but not sufficient for reversals**: a second partial
 * refund on one charge is a genuinely new event carrying a *cumulative* total, so credit notes are
 * additionally keyed on the Stripe refund/dispute id (`CreditNote.stripeReferenceId`). A
 * mid-processing crash won't reprocess on Stripe's retry (a known limit — events are replayable from the
 * Stripe dashboard).
 */
fun Route.configureStripeWebhookRouting(
    stripe: StripeSupport?,
    store: CommerceStore,
    entitlementTtlMs: Long,
    now: () -> Long = System::currentTimeMillis,
    play: GooglePlaySupport? = null,
) {
    post("/commerce/webhook/stripe") {
        if (stripe == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "stripe_unavailable"))
            return@post
        }
        val payload = call.receiveText()
        val nowMs = now()
        val signature = call.request.headers["Stripe-Signature"]
        if (!StripeSignature.verify(payload, signature, stripe.webhookSecret, nowMs)) {
            // Forged / stale / unsigned → reject before any state change (T-CB-07).
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_signature"))
            return@post
        }

        val event = runCatching { webhookJson.decodeFromString<StripeEvent>(payload) }.getOrNull()
        if (event == null || event.id.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request"))
            return@post
        }
        if (store.stripeEventSeen(event.id)) {
            call.respond(HttpStatusCode.OK, mapOf("status" to "duplicate"))
            return@post
        }

        val obj = event.data.`object`
        val productByPrice = stripe.priceIds.entries.associate { (product, price) -> price to product }
        // ⚠⚠ A Stripe purchase over a Google plan stops the Google subscription (D6) — BEFORE
        // anything is booked, because the invoice write below is not idempotent: a failure here
        // asks for redelivery with nothing written yet.
        if (event.type in PURCHASE_EVENTS) {
            val email = (obj.customerEmail ?: obj.customerDetails?.email)?.trim()?.lowercase()
            if (email != null && !stopGoogleForStripePurchase(play, store, email, nowMs)) {
                log.warn("Stripe purchase over a Google plan: could not stop the Google subscription; asking for redelivery")
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "google_error"))
                return@post
            }
        }
        when (event.type) {
            // ⚠⚠ Both events grant, but only ONE writes the statutory invoice (WC-1b). A new
            // subscription checkout emits both, so a shared writer books one payment twice. The
            // invoice belongs to `invoice.paid` because that event *is* the invoice — a Checkout
            // Session is an intent to buy, has no invoice number, no period and no tax breakdown of
            // its own. Granting from both is harmless by contrast: entitlement rows are additive and
            // `currentEntitlement` picks the highest quota, so the checkout's short TTL grant is
            // simply superseded by the invoice's real billing period seconds later — and the
            // customer is never left unentitled if one event is delayed.
            "checkout.session.completed" ->
                grantFromEvent(store, obj, entitlementTtlMs, nowMs, writesStatutoryInvoice = false, productByPrice = productByPrice)
            "invoice.paid" -> {
                // Asked only for what the payload did not bring: older API shapes carry both.
                val looksUp = obj.payloadPaymentReference() == null || obj.payloadCardCountry() == null
                val evidence = if (looksUp) obj.id?.let { stripe.client.invoicePaymentEvidence(it) } else null
                grantFromEvent(store, obj, entitlementTtlMs, nowMs, writesStatutoryInvoice = true, evidence, productByPrice)
            }
            // Cancellation/lapse: "stop reissuing" is the revocation model — the short-lived paid
            // window simply expires and currentEntitlement falls back to Free (§6). Nothing to write.
            "customer.subscription.deleted" -> log.info("Stripe subscription deleted; letting entitlement lapse")
            // Money out (WC-1, §4.7). Recorded so the OSS return can CORRECT the VAT we already
            // remitted on the reversed sale — a money-in-only ledger over-states VAT owed.
            "charge.refunded" -> {
                // Current API versions no longer list the refunds on the charge; ask for them.
                val refunds = obj.refunds?.data.orEmpty()
                    .mapNotNull { it.asChargeRefund() }
                    .ifEmpty { obj.id?.let { stripe.client.refundsForCharge(it) } }
                if (refunds == null) {
                    // ⚠⚠ Refused, NOT acknowledged: an acknowledged event is never redelivered, and
                    // an uncredited refund leaves VAT remitted on money we gave back. Nothing is
                    // granted on this path, so making Stripe retry costs the customer nothing.
                    log.warn("charge.refunded: could not read the refunds from Stripe; asking for redelivery")
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "stripe_error"))
                    return@post
                }
                creditRefundsFromEvent(store, obj, refunds, nowMs)
            }
            "charge.dispute.closed" -> {
                val accountId = creditLostDisputeFromEvent(store, obj, nowMs)
                if (accountId != null && !revokeAfterLostDispute(store, stripe, accountId, nowMs)) {
                    // ⚠⚠ Refused, NOT acknowledged, for the reason the refund path gives: an
                    // acknowledged event is never redelivered, and a subscription left billing
                    // after its payment was charged back charges the same card again next month.
                    // The credit note and the revocation above are idempotent on redelivery.
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "stripe_error"))
                    return@post
                }
            }
            else -> log.info("Ignoring unhandled Stripe event type '{}'", event.type)
        }

        store.recordStripeEvent(event.id, nowMs)
        call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
    }
}

/**
 * Grant the paid entitlement carried by [obj]: create-or-find the account from the collected email,
 * attach the Stripe customer, (re-)bind the owner if present, extend the ledger, and — when money
 * actually moved — write the statutory invoice. Missing email or tier ⇒ a logged no-op (a webhook we
 * can't act on shouldn't 500 and trigger endless Stripe retries).
 */
private fun grantFromEvent(
    store: CommerceStore,
    obj: StripeObject,
    entitlementTtlMs: Long,
    nowMs: Long,
    writesStatutoryInvoice: Boolean,
    evidence: InvoicePaymentEvidence? = null,
    productByPrice: Map<String, String> = emptyMap(),
) {
    val email = (obj.customerEmail ?: obj.customerDetails?.email)?.trim()?.lowercase()
    val productId = obj.billedProduct(productByPrice) ?: obj.subscriptionAwareMetadata("product_id")
    if (email.isNullOrBlank() || !email.contains('@') || productId.isNullOrBlank()) {
        log.warn("Stripe event missing email or product_id; skipping grant")
        return
    }

    // ⚠⚠ The customer first, the address second. An ERASED account no longer answers to its
    // address (the tombstone, §9.7), so a renewal that slips past the cancellation — or an event
    // redelivered after the deletion — would otherwise create a brand-new account from the email on
    // the event: re-collecting the very address the person asked us to erase, and granting a plan
    // to an account they never opened. The customer id survives erasure for exactly this lookup.
    val customerAccount = obj.customer?.let(store::findAccountByStripeCustomerId)
    if (customerAccount?.status == AccountStatus.Closed) {
        log.warn("Stripe event for a closed account's customer; refusing to grant (see CE-5, §9.7)")
        return
    }
    val account = store.findAccountByEmail(email) ?: store.createAccount(email, Rail.Stripe, nowMs)

    // ⚠⚠ Never re-entitle a CLOSED account (CE-5). Deliberately a refusal and NOT an automatic
    // reopen: closing does not cancel the customer's Stripe subscription, so recurring
    // `invoice.paid` events keep arriving afterwards — reopening on payment would silently
    // resurrect an account that may have been closed for erasure, on a schedule nobody chose.
    //
    // The "paid but got nothing" corner this would otherwise leave is closed upstream:
    // `SubscriptionCanceller` cancels the account's subscriptions when it is closed, and a sweep
    // retries if that call failed. So by the time an event like this arrives, the subscription
    // should already be gone — and if one slips through, refusing the grant is still right.
    if (account.status == AccountStatus.Closed) {
        log.warn("Stripe event for a closed account; refusing to grant (see CE-5)")
        return
    }

    // ⚠⚠ Checked: the unverified-account sweep may have deleted an old, never-verified Free account
    // between the lookup above and here. Throw before anything else is written against the
    // id — the event is not recorded, Stripe redelivers, and the retry creates a fresh account.
    obj.customer?.let {
        check(store.attachStripeCustomer(account.id, it)) { "account ${account.id.take(8)}… vanished mid-grant; Stripe will retry" }
    }

    // The purchase-time acknowledgement, written where it can finally be keyed: the account now
    // exists. Only on events that carry it — a *renewal* is not a fresh contract and needs no new
    // acknowledgement, so its absence here is correct rather than a gap.
    //
    // ⚠⚠ Deliberately reads the object's OWN metadata, never [subscriptionAwareMetadata]. The ack is
    // set on the Checkout Session only (`StripeClient` does not put it in `subscription_data`), so
    // reading it subscription-aware would let a renewal manufacture a waiver for a contract nobody
    // re-accepted. Keeping the narrow read makes that structurally impossible rather than merely
    // untrue today.
    obj.metadata?.get("withdrawal_ack_tos")?.takeIf { it.isNotBlank() }?.let { tosVersion ->
        val recorded = store.recordAcceptance(
            Acceptance(
                accountId = account.id,
                tosVersion = tosVersion,
                privacyVersion = null,
                acceptedAt = nowMs,
                kind = AcceptanceKind.PurchaseWithdrawalWaiver,
            ),
        )
        check(recorded) { "account ${account.id.take(8)}… vanished mid-grant; Stripe will retry" }
    }
    // ⚠ Re-binding the SAME owner every month would append an `account_owner` row per renewal and
    // supersede the previous one — churn in the A30 history for no change. Bind only on an actual
    // difference; a genuine re-bind still supersedes as before (§3.4).
    (obj.clientReferenceId ?: obj.subscriptionAwareMetadata("owner_id"))?.takeIf { it.isNotBlank() }
        ?.takeIf { it != store.liveOwner(account.id) }
        ?.let { store.bindOwner(account.id, it, nowMs) }

    val validUntil = resolvePaidUntil(obj, nowMs, entitlementTtlMs)
    store.appendEntitlementHistory(
        EntitlementHistory(
            accountId = account.id,
            productId = productId,
            validFrom = nowMs,
            validUntil = validUntil,
            source = EntitlementSource.Stripe,
            issuedAt = nowMs,
        ),
    )

    val amountMinor = obj.amountPaid ?: obj.amountTotal ?: 0
    if (writesStatutoryInvoice && amountMinor > 0) {
        val taxMinor = obj.taxAmount()
        val netMinor = amountMinor - taxMinor
        store.appendInvoice(
            InvoiceDraft(
                accountId = account.id,
                stripeInvoiceId = obj.id,
                amountMinor = amountMinor,
                currency = obj.currency ?: "eur",
                vatAmountMinor = taxMinor,
                // Effective rate in basis points (Stripe Tax computes the amount; derive the rate).
                vatRateBasisPoints = if (netMinor > 0) ((taxMinor * 10_000) / netMinor).toInt() else 0,
                billingCountry = obj.billingCountry(),
                ipCountry = secondLocationEvidence(obj, evidence),
                issuedAt = nowMs,
                paymentRef = paymentReference(obj, evidence),
            ),
        )
    }
}

/**
 * The Stripe payment that settled this invoice — a PaymentIntent id where there is one, else a
 * charge id (WC-6). Null when the payload carries neither.
 *
 * ⚠⚠ This is the key a **chargeback** is resolved by, and it is read from four places because the
 * Invoice object has **no `charge` field at all** in the current API: the payment hangs off
 * `payments.data[].payment`, whose `charge` is documented as populated *only* when the charge has no
 * PaymentIntent — so for ordinary card payments the PaymentIntent is the one that exists. Older API
 * versions put `payment_intent` / `charge` at the top level instead, and which arrives is decided by
 * the version pinned on the **webhook endpoint**, not by us. Reading all four costs nullable hops and
 * removes a silent dependency on a dashboard setting — the same reasoning as WC-1b's metadata read.
 *
 * ⚠ `payments` is an *expandable* field and the live pass showed Stripe does not include it
 * unexpanded, so the event alone names no payment on current API versions. [evidence] is the lookup
 * that fills the gap; a miss on both is logged rather than guessed at.
 */
private fun paymentReference(obj: StripeObject, evidence: InvoicePaymentEvidence?): String? {
    val reference = obj.payloadPaymentReference() ?: evidence?.paymentRef?.takeIf { it.isNotBlank() }
    if (reference == null) {
        log.warn(
            "Invoice carries no PaymentIntent or charge reference and the Stripe lookup found none; " +
                "a later chargeback on it cannot be linked to a country (see WC-6).",
        )
    }
    return reference
}

/** The reference as the event itself spells it, in any of the three shapes Stripe has used. */
private fun StripeObject.payloadPaymentReference(): String? {
    val fromPayments = payments?.data.orEmpty().firstNotNullOfOrNull { entry ->
        entry.payment?.paymentIntent?.takeIf { it.isNotBlank() }
            ?: entry.payment?.charge?.takeIf { it.isNotBlank() }
    }
    return paymentIntent?.takeIf { it.isNotBlank() }
        ?: fromPayments
        ?: charge?.takeIf { it.isNotBlank() }
}

private fun StripeObject.payloadCardCountry(): String? =
    paymentMethodDetails?.card?.country?.takeIf { it.isNotBlank() }

/**
 * ⚠ A Checkout Session spells it `customer_details.address`, an Invoice `customer_address` — reading
 * only the first left every real invoice without a billing country (found on the live test pass).
 */
private fun StripeObject.billingCountry(): String? =
    (customerDetails?.address?.country ?: customerAddress?.country)?.takeIf { it.isNotBlank() }

/**
 * ⚠ The top-level `tax` field is gone from current Invoice shapes; the amount is the sum of
 * `total_taxes[]`. Without the fallback a taxed sale is recorded at 0 % VAT.
 */
private fun StripeObject.taxAmount(): Long = tax ?: totalTaxes?.sumOf { it.amount ?: 0 } ?: 0

/**
 * The tier an invoice actually charges for, read off the **price** on its lines.
 *
 * ⚠⚠ Preferred over the `product_id` metadata because that is written once, at checkout, and a
 * plan change made on the subscription (the billing portal) does not rewrite it: a customer who
 * moved to Premium would keep being granted Basic on every renewal while paying for Premium.
 *
 * ⚠ A plan-change invoice carries TWO lines — a credit for the unused part of the old tier and a
 * charge for the new one — so only charged lines count, the latest service period first. Null (no
 * lines, or a price this deployment does not map) falls back to the metadata.
 */
private fun StripeObject.billedProduct(productByPrice: Map<String, String>): String? =
    lines?.data.orEmpty()
        .filter { (it.amount ?: 0) > 0 }
        .sortedByDescending { it.period?.end ?: 0 }
        .firstNotNullOfOrNull { line -> line.priceId()?.let(productByPrice::get) }

/**
 * Read [key] from the object's own `metadata`, falling back to the **subscription's** metadata as
 * carried on an invoice (WC-1b).
 *
 * ⚠⚠ The fallback is the whole fix for renewals. `StripeClient` sets
 * `subscription_data[metadata][product_id]` so the tier survives past checkout, but Stripe does not
 * copy a subscription's metadata onto the invoice's own `metadata` — it exposes it as
 * `parent.subscription_details.metadata`, an immutable snapshot taken at invoice finalization.
 * Reading only the top level meant every `invoice.paid` looked like it had no tier, so it was
 * skipped — and since a renewal arrives *only* as `invoice.paid`, nothing ever extended a paid
 * subscription past its first grant.
 *
 * ⚠ Both spellings are accepted because the shape moved under `parent` in recent API versions, and
 * which one arrives is decided by the API version pinned on the *webhook endpoint*, not by us.
 * Checking both costs one nullable hop and removes a silent dependency on a dashboard setting.
 */
private fun StripeObject.subscriptionAwareMetadata(key: String): String? =
    metadata?.get(key)?.takeIf { it.isNotBlank() }
        ?: parent?.subscriptionDetails?.metadata?.get(key)?.takeIf { it.isNotBlank() }
        ?: subscriptionDetails?.metadata?.get(key)?.takeIf { it.isNotBlank() }

/**
 * How long the paid tier is granted for: the **billing period the payment covers**, falling back to
 * the configured TTL (WC-1b).
 *
 * ⚠⚠ `current_period_end` is a *Subscription* field and appears on neither a Checkout Session nor an
 * Invoice, so the old read was always null and every purchase silently got the 7-day TTL while the
 * customer was billed monthly. An invoice carries the period on its **line items** (the service
 * period) and, less precisely, at the top level.
 *
 * ⚠ Every candidate must be **in the future** to be used. An invoice's top-level
 * `period_start`/`period_end` are legitimately *equal* when nothing accrued over a span — Stripe's
 * own reference example shows both set to the creation timestamp — and a zero-length grant would be
 * worse than the fallback it replaced. Ordering prefers the line-item period for the same reason:
 * it is the actual service window rather than a usage-accrual window that may collapse.
 */
private fun resolvePaidUntil(obj: StripeObject, nowMs: Long, entitlementTtlMs: Long): Long {
    val candidatesSeconds = buildList {
        obj.currentPeriodEnd?.let { add(it) }
        obj.lines?.data.orEmpty().mapNotNull { it.period?.end }.maxOrNull()?.let { add(it) }
        obj.periodEnd?.let { add(it) }
    }
    return candidatesSeconds
        .map { it * 1000 }
        .firstOrNull { it > nowMs }
        ?: (nowMs + entitlementTtlMs)
}

/**
 * The **second** of the two non-contradictory location evidences an OSS filing needs (§5) — today
 * the card's issuing (BIN) country, which is what Stripe actually exposes on the payment method.
 *
 * ⚠⚠ Returns null rather than falling back to the billing country, and that is the whole point:
 * two *identical* evidences are not two evidences. Copying `billingCountry` across would
 * manufacture corroboration that does not exist, which is strictly worse than an honest absence —
 * the export (WC-4) can flag a null, but it cannot detect a fabricated match.
 *
 * ⚠ Neither `checkout.session.completed` nor `invoice.paid` carries the payment method, so on the
 * invoice path the country comes from [evidence] — the charge behind the invoice's payment.
 */
private fun secondLocationEvidence(obj: StripeObject, evidence: InvoicePaymentEvidence?): String? {
    val country = obj.payloadCardCountry() ?: evidence?.cardCountry?.takeIf { it.isNotBlank() }
    if (country == null) {
        log.warn(
            "No second location evidence on this Stripe object; invoice will carry billing country only " +
                "(OSS filings need two — see §5 / WC-1)",
        )
    }
    return country
}

/**
 * `charge.refunded` → one [CreditNote] per Stripe **refund**, skipping any already credited.
 *
 * ⚠⚠ Per-refund, never per-charge: `amount_refunded` on the charge is **cumulative**, so a second
 * partial refund arrives as a fresh event whose total already includes the first. Crediting the
 * charge total would double-count; crediting each `refunds.data[]` entry, keyed by its own id,
 * makes both re-delivery and successive partial refunds fall out with no arithmetic at all.
 */
private fun StripeRefund.asChargeRefund(): ChargeRefund? {
    val refundId = id?.takeIf { it.isNotBlank() } ?: return null
    val amountMinor = amount?.takeIf { it > 0 } ?: return null
    return ChargeRefund(refundId, amountMinor, currency)
}

private fun creditRefundsFromEvent(
    store: CommerceStore,
    obj: StripeObject,
    chargeRefunds: List<ChargeRefund>,
    nowMs: Long,
) {
    val refunds = chargeRefunds.filter { it.amountMinor > 0 }.map { Triple(it.id, it.amountMinor, it.currency) }
    if (refunds.isEmpty()) {
        log.warn("charge.refunded carried no usable refund entries; nothing credited")
        return
    }
    // The charge names its invoice for subscription payments; that link is what lets us apportion
    // VAT at the ORIGINAL rate instead of guessing one.
    // The charge names its invoice directly for subscription payments; the payment reference is the
    // WC-6 fallback, so a refund on a charge whose `invoice` is absent still apportions.
    val invoice = obj.invoice?.takeIf { it.isNotBlank() }?.let { store.findInvoiceByStripeId(it) }
        ?: listOfNotNull(
            obj.paymentIntent?.takeIf { it.isNotBlank() },
            obj.id?.takeIf { it.isNotBlank() }, // on a charge event the object id IS the charge id
        ).firstNotNullOfOrNull { store.findInvoiceByPaymentRef(it) }
    val accountId = invoice?.accountId
        ?: obj.customer?.takeIf { it.isNotBlank() }?.let { store.findAccountByStripeCustomerId(it)?.id }
    if (accountId == null) {
        // ⚠ Skipped, never guessed: a credit we cannot attribute has no country either, so it
        // would be noise in the one total the OSS return is filled from. Loud, because this is a
        // real financial event going unrecorded.
        log.warn("Refund for an unattributable Stripe customer; NOT credited (manual reconciliation needed)")
        return
    }
    refunds.forEach { (refundId, amountMinor, refundCurrency) ->
        val credited = store.appendCreditNote(
            creditNoteDraft(
                accountId = accountId,
                kind = CreditNoteKind.Refund,
                referenceId = refundId,
                amountMinor = amountMinor,
                currency = refundCurrency ?: obj.currency ?: "eur",
                invoice = invoice,
                nowMs = nowMs,
            ),
        )
        if (credited == null) {
            log.info("Refund already credited; skipping")
        } else if (credited.vatAmountMinor == null) {
            log.warn("Credit note {} is unlinked — VAT could not be apportioned", credited.number)
        }
    }
}

/**
 * `charge.dispute.closed` → a [CreditNote] **only when the dispute was lost**. A dispute we won
 * reverses no revenue (the fee is a cost, not a sales reversal), so writing a credit note for it
 * would understate what we owe.
 *
 * ⚠ A dispute names a *charge*, while invoices here are keyed by session/invoice id, so these are
 * normally **unlinked** — VAT unapportionable, flagged for manual reconciliation rather than
 * silently zeroed.
 */
private fun creditLostDisputeFromEvent(store: CommerceStore, obj: StripeObject, nowMs: Long): String? {
    if (obj.status != "lost") {
        log.info("Dispute closed as '{}'; no revenue reversed, nothing credited", obj.status)
        return null
    }
    val disputeId = obj.id?.takeIf { it.isNotBlank() }
    val amount = obj.amount ?: 0
    if (disputeId == null || amount <= 0) {
        log.warn("Lost dispute carried no id/amount; nothing credited")
        return null
    }
    // ⚠⚠ WC-6: resolve the invoice through the **payment** the dispute names. Before this a
    // chargeback was always unlinked, so a fully-reversed sale kept its country total while an equal
    // figure sat in the export's `(unreconciled)` bucket — an operator filing without reconciling
    // would remit VAT on a sale that never stood. The PaymentIntent is tried first because that is
    // what a card payment has; the charge id is the fallback for payments that have no intent.
    val invoice = listOfNotNull(
        obj.paymentIntent?.takeIf { it.isNotBlank() },
        obj.charge?.takeIf { it.isNotBlank() },
    ).firstNotNullOfOrNull { store.findInvoiceByPaymentRef(it) }
    if (invoice == null) {
        // ⚠ Expected for any sale invoiced before WC-6 — there is no reference stored to match.
        log.warn("Lost dispute could not be linked to an invoice; VAT will need manual reconciliation")
    }
    val accountId = invoice?.accountId
        ?: obj.customer?.takeIf { it.isNotBlank() }?.let { store.findAccountByStripeCustomerId(it)?.id }
    if (accountId == null) {
        log.warn("Lost dispute for an unattributable Stripe customer; NOT credited (manual reconciliation needed)")
        return null
    }
    val credited = store.appendCreditNote(
        creditNoteDraft(
            accountId = accountId,
            kind = CreditNoteKind.Chargeback,
            referenceId = disputeId,
            amountMinor = amount,
            currency = obj.currency ?: "eur",
            invoice = invoice,
            nowMs = nowMs,
        ),
    )
    if (credited == null) log.info("Dispute already credited; skipping")
    return accountId
}

/**
 * A lost chargeback takes the payment back, so it takes the plan back too: the account's paid
 * grants end now, and its subscriptions stop billing. Returns false when Stripe could not be told
 * to stop — the caller refuses the event so it is redelivered.
 *
 * ⚠⚠ Without this the customer kept the storage to the end of a period they had been refunded
 * for, and — because the subscription was still live — was charged again at the next renewal,
 * which simply re-granted it.
 *
 * ⚠ ALL of the customer's subscriptions, not only the disputed one: a card that charged one
 * payment back is not one to keep billing, and we hold no subscription id to be more precise with.
 *
 * ⚠ The device keeps the record it already holds until that record expires or it next asks for
 * its plan; the relay stops honouring a fresh bearer token at once, since `/token` reads the ledger.
 */
private suspend fun revokeAfterLostDispute(
    store: CommerceStore,
    stripe: StripeSupport,
    accountId: String,
    nowMs: Long,
): Boolean {
    val ended = store.revokePaidEntitlements(accountId, nowMs)
    log.warn("Lost chargeback: ended {} paid grant(s) for the account", ended)
    val customerId = store.findAccountById(accountId)?.stripeCustomerId?.takeIf { it.isNotBlank() }
        ?: return true // nothing can be billing without a customer
    return stripe.client.cancelActiveSubscriptions(customerId) != null
}

/**
 * Build a credit-note draft, apportioning VAT **proportionally to the original invoice's gross**
 * when the invoice is known.
 *
 * Proportional to gross rather than re-derived from the stored basis points: it is exact for a full
 * reversal (the credited VAT equals the invoiced VAT) and consistent for a partial one, with no
 * second rounding step introduced on top of the rate we already rounded once when deriving it.
 *
 * ⚠ No invoice ⇒ `vatAmountMinor`/`vatRateBasisPoints` stay **null**, not `0` — see [CreditNote].
 */
private fun creditNoteDraft(
    accountId: String,
    kind: CreditNoteKind,
    referenceId: String,
    amountMinor: Long,
    currency: String,
    invoice: Invoice?,
    nowMs: Long,
): CreditNoteDraft = CreditNoteDraft(
    accountId = accountId,
    kind = kind,
    stripeReferenceId = referenceId,
    referencesInvoiceNumber = invoice?.number,
    amountMinor = amountMinor,
    currency = currency,
    vatAmountMinor = invoice
        ?.takeIf { it.amountMinor > 0 }
        ?.let { (amountMinor * it.vatAmountMinor) / it.amountMinor },
    vatRateBasisPoints = invoice?.vatRateBasisPoints,
    billingCountry = invoice?.billingCountry,
    issuedAt = nowMs,
)

// --- Lenient Stripe event shape (only the fields we read; ignoreUnknownKeys covers the rest) ------

@Serializable
data class StripeEvent(val id: String, val type: String, val data: StripeEventData)

@Serializable
data class StripeEventData(val `object`: StripeObject)

@Serializable
data class StripeObject(
    val id: String? = null,
    val customer: String? = null,
    @SerialName("customer_email") val customerEmail: String? = null,
    @SerialName("customer_details") val customerDetails: StripeCustomerDetails? = null,
    /** The Invoice's spelling of the billing address; a Checkout Session uses `customer_details`. */
    @SerialName("customer_address") val customerAddress: StripeAddress? = null,
    @SerialName("client_reference_id") val clientReferenceId: String? = null,
    @SerialName("amount_total") val amountTotal: Long? = null,
    @SerialName("amount_paid") val amountPaid: Long? = null,
    val currency: String? = null,
    val tax: Long? = null,
    /** Current Invoice shape for the tax amount, one entry per rate. */
    @SerialName("total_taxes") val totalTaxes: List<StripeTaxAmount>? = null,
    val metadata: Map<String, String>? = null,
    @SerialName("current_period_end") val currentPeriodEnd: Long? = null,
    // --- money out (WC-1) ---
    /** Charge → invoice link; present on subscription charges. Lets a refund apportion VAT. */
    val invoice: String? = null,
    /** Charge refunds. ⚠ Per-refund amounts — the charge's `amount_refunded` is cumulative. */
    val refunds: StripeRefundList? = null,
    /** Dispute amount (the dispute object's own field; charges use `amount_total`/`amount_paid`). */
    val amount: Long? = null,
    /** Dispute outcome — only `"lost"` reverses revenue. */
    val status: String? = null,
    /** Carries the card's issuing (BIN) country — the second location evidence (§5). */
    @SerialName("payment_method_details") val paymentMethodDetails: StripePaymentMethodDetails? = null,
    // --- subscription period + metadata (WC-1b) ---
    /** Invoice-level usage period end, seconds. ⚠ Can equal `period_start`; see [resolvePaidUntil]. */
    @SerialName("period_end") val periodEnd: Long? = null,
    /** Invoice line items — the service period actually paid for. */
    val lines: StripeLineList? = null,
    /** Current API shape: the subscription's metadata snapshot hangs off `parent`. */
    val parent: StripeInvoiceParent? = null,
    /** Older API shape: the same snapshot, top level. Both are read — the endpoint's version picks. */
    @SerialName("subscription_details") val subscriptionDetails: StripeSubscriptionDetails? = null,
    // --- the payment a chargeback names (WC-6) ---
    /** On a Dispute: the disputed PaymentIntent. On older Invoice shapes: the settling one. */
    @SerialName("payment_intent") val paymentIntent: String? = null,
    /** On a Dispute: the disputed charge (always present). On the oldest Invoice shapes: the settling one. */
    val charge: String? = null,
    /** Current Invoice shape: the payment hangs off here, since Invoice has no `charge` field. */
    val payments: StripeInvoicePayments? = null,
)

@Serializable
data class StripeInvoicePayments(val data: List<StripeInvoicePayment> = emptyList())

@Serializable
data class StripeInvoicePayment(val payment: StripeInvoicePaymentTarget? = null)

@Serializable
data class StripeInvoicePaymentTarget(
    @SerialName("payment_intent") val paymentIntent: String? = null,
    val charge: String? = null,
)

@Serializable
data class StripeInvoiceParent(
    @SerialName("subscription_details") val subscriptionDetails: StripeSubscriptionDetails? = null,
)

@Serializable
data class StripeSubscriptionDetails(val metadata: Map<String, String>? = null)

@Serializable
data class StripeLineList(val data: List<StripeLine> = emptyList())

@Serializable
data class StripeLine(
    val period: StripePeriod? = null,
    /** Negative on the credit line of a plan change. */
    val amount: Long? = null,
    /** Current shape: `pricing.price_details.price`. */
    val pricing: StripeLinePricing? = null,
    /** Older shape: an inline `price` object. */
    val price: StripePriceRef? = null,
) {
    fun priceId(): String? = (pricing?.priceDetails?.price ?: price?.id)?.takeIf { it.isNotBlank() }
}

@Serializable
data class StripeLinePricing(@SerialName("price_details") val priceDetails: StripePriceDetails? = null)

@Serializable
data class StripePriceDetails(val price: String? = null)

@Serializable
data class StripePriceRef(val id: String? = null)

@Serializable
data class StripePeriod(val end: Long? = null)

@Serializable
data class StripeCustomerDetails(
    val email: String? = null,
    val address: StripeAddress? = null,
)

@Serializable
data class StripeAddress(val country: String? = null)

@Serializable
data class StripeTaxAmount(val amount: Long? = null)

@Serializable
data class StripeRefundList(val data: List<StripeRefund> = emptyList())

@Serializable
data class StripeRefund(
    val id: String? = null,
    val amount: Long? = null,
    val currency: String? = null,
)

@Serializable
data class StripePaymentMethodDetails(val card: StripeCard? = null)

@Serializable
data class StripeCard(val country: String? = null)

/** The events that grant a paid plan, and so may replace a plan on another rail. */
private val PURCHASE_EVENTS = setOf("checkout.session.completed", "invoice.paid")
