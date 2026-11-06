package org.tenshu.commerce.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.log
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.ButtonType
import kotlinx.html.FormEncType
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.hiddenInput
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.p
import kotlinx.html.style
import kotlinx.html.unsafe
import org.tenshu.commerce.hmacSha256Hex
import org.tenshu.commerce.stripe.FakePrices
import org.tenshu.commerce.stripe.FakeStripeClient

/**
 * The dev payment rail's browser surface (`commerce-end-to-end-dry-run.md` CE-3 / D1): a hosted
 * "checkout" page standing in for Stripe Checkout, and a confirm action that emits a genuine signed
 * webhook into the **real** handler.
 *
 * ⚠⚠ **The email field is not decoration.** With real Stripe, *Stripe* collects the customer's
 * email and the webhook is the only thing that tells us who paid — `grantFromEvent` creates-or-finds
 * the account from `customer_email`. A fake rail that skipped it would quietly bypass the single
 * most load-bearing field in the whole money path, and the first live Stripe run would be the first
 * time that code ever ran with a real value in it.
 *
 * ⚠ Mounted **only** when the fake rail is selected, which `CommerceConfig.validate()` permits only
 * in dev mode — so these routes cannot exist in a real deployment.
 */
fun Route.configureFakeStripeRouting(
    fake: FakeStripeClient,
    /** The endpoint's signing secret — the fake signs with exactly what the webhook verifies with. */
    webhookSecret: String,
    /**
     * Delivers the signed event to `POST /commerce/webhook/stripe`. Injected so a test can assert on
     * the exact bytes and signature without a live socket; production passes a real self-call.
     */
    postWebhook: suspend (payload: String, signature: String) -> Boolean,
    now: () -> Long = System::currentTimeMillis,
) {
    get("${FakeStripeClient.CHECKOUT_PATH}/{sessionId}") {
        val sessionId = call.parameters["sessionId"].orEmpty()
        val checkout = fake.checkout(sessionId)
        if (checkout == null) {
            call.respondText("Unknown or already-completed checkout session.", status = HttpStatusCode.NotFound)
            return@get
        }
        val gross = FakePrices.grossMinor(checkout.productId)
        call.respondHtml { renderCheckout(checkout, gross) }
    }

    post("${FakeStripeClient.CHECKOUT_PATH}/{sessionId}/confirm") {
        val sessionId = call.parameters["sessionId"].orEmpty()
        val parameters = call.receiveParameters()
        val typedEmail = parameters["email"]
        // Consume the session up front: a reload of the confirm POST must not mint a second event.
        // (Webhook-side idempotency by event id would also catch it, but each retry would carry a
        // *fresh* event id, so this layer is where a double-confirm is actually stopped.)
        val checkout = fake.takeCheckout(sessionId)

        if (checkout == null) {
            call.respondText("Unknown or already-completed checkout session.", status = HttpStatusCode.NotFound)
            return@post
        }
        // A signed-in buyer's email is pinned by the session, as Stripe pins `customer_email`: the
        // form's value is ignored, so the fake cannot bind the purchase to a second account either.
        val email = (checkout.customerEmail ?: typedEmail)?.let(::normaliseEmail)
        if (email == null) {
            call.respondText("A valid email is required.", status = HttpStatusCode.BadRequest)
            return@post
        }

        // Recorded before delivery: once paid, the subscription exists at Stripe whether or not
        // our webhook hears about it.
        val customerId = fake.completeCheckout(checkout, email)
        val nowMs = now()
        // ⚠⚠ BOTH events, in Stripe's order. A real subscription checkout emits
        // `checkout.session.completed` **and** `invoice.paid`, and this rail used to send only the
        // first — which is precisely why WC-1b's defects survived a manual dry run: the statutory
        // invoice, the subscription-metadata read and the billing period all live on the second
        // event, so none of them were ever exercised locally. A fake that models half the traffic
        // hides exactly the half nobody looked at.
        val events = listOf(
            fakeCheckoutCompletedEvent(checkout, email, customerId, nowMs),
            fakeInvoicePaidEvent(checkout, email, customerId, nowMs),
        )
        for (payload in events) {
            // Sign exactly as Stripe does — `t=<unix>,v1=<hmac over "t.payload">` — so the real
            // verifier's timestamp tolerance and HMAC comparison both genuinely run.
            val timestampSeconds = nowMs / 1000
            val signature = "t=$timestampSeconds,v1=${hmacSha256Hex(webhookSecret, "$timestampSeconds.$payload")}"

            val delivered = postWebhook(payload, signature)
            if (!delivered) {
                call.application.log.error("[fake-stripe] webhook delivery failed for session {}", sessionId)
                call.respondText("Webhook delivery failed — see the service log.", status = HttpStatusCode.BadGateway)
                return@post
            }
        }
        call.respondRedirect(checkout.successUrl)
    }

    get(FakeStripeClient.PORTAL_PATH) {
        val returnUrl = call.request.queryParameters["return_url"]
        call.respondHtml { renderPortal(returnUrl) }
    }
}

/**
 * A Stripe-shaped `checkout.session.completed` event. Only the fields `StripeObject` actually reads
 * are populated; `ignoreUnknownKeys` means a real Stripe payload simply carries more.
 *
 * ⚠ Values are hand-assembled JSON rather than a serializer because this must be **raw bytes we
 * control**: the HMAC covers the exact payload string, so any re-encoding between signing and
 * verifying would break the signature — the same reason the webhook route reads `receiveText()`
 * before ContentNegotiation.
 */
internal fun fakeCheckoutCompletedEvent(
    checkout: FakeStripeClient.PendingCheckout,
    email: String,
    customerId: String,
    nowMs: Long,
): String {
    val gross = FakePrices.grossMinor(checkout.productId) ?: 0L
    val vat = FakePrices.vatMinor(gross)
    val periodEndSeconds = (nowMs + FAKE_PERIOD_MS) / 1000
    return """
        {"id":"evt_fake_${checkout.sessionId}",
         "type":"checkout.session.completed",
         "data":{"object":{
           "id":"${checkout.sessionId}",
           "customer":"$customerId",
           "customer_email":"$email",
           "customer_details":{"email":"$email","address":{"country":"DE"}},
           "client_reference_id":"${checkout.ownerId}",
           "amount_total":$gross,
           "currency":"eur",
           "tax":$vat,
           "metadata":{"product_id":"${checkout.productId}","owner_id":"${checkout.ownerId}"${ackMetadata(checkout)}},
           "current_period_end":$periodEndSeconds
         }}}
    """.trimIndent().replace("\n", "").replace("  ", "")
}

/**
 * The `invoice.paid` that follows the session for a subscription — the event that actually books the
 * statutory invoice and carries the billing period (WC-1b).
 *
 * ⚠⚠ Shaped like the real thing in the two ways that matter, both of which were previously untested:
 * the tier rides on **`parent.subscription_details.metadata`** (Stripe's immutable snapshot of the
 * subscription's metadata) while the invoice's own `metadata` is **empty**, and the period comes from
 * the **line item**. Faking the tier at the top level instead would make this event look like the
 * session and re-hide the renewal bug.
 *
 * ⚠ Deliberately carries **no** `withdrawal_ack_tos`: an invoice never does, and a renewal must not
 * manufacture a waiver.
 *
 * ⚠⚠ The payment rides under **`payments.data[].payment.payment_intent`** with a null `charge`, which
 * is the shape a card payment actually produces (an Invoice has no top-level `charge` field at all,
 * and `payments…charge` is documented as populated only when there is no PaymentIntent). Faking it
 * at the top level instead would make WC-6's chargeback link look like it works against a payload
 * Stripe does not send — the same trap as putting the tier in the invoice's own `metadata`.
 */
internal fun fakeInvoicePaidEvent(
    checkout: FakeStripeClient.PendingCheckout,
    email: String,
    customerId: String,
    nowMs: Long,
): String {
    val gross = FakePrices.grossMinor(checkout.productId) ?: 0L
    val vat = FakePrices.vatMinor(gross)
    val periodEndSeconds = (nowMs + FAKE_PERIOD_MS) / 1000
    return """
        {"id":"evt_fake_in_${checkout.sessionId}",
         "type":"invoice.paid",
         "data":{"object":{
           "id":"in_fake_${checkout.sessionId}",
           "customer":"$customerId",
           "customer_email":"$email",
           "customer_details":{"email":"$email","address":{"country":"DE"}},
           "amount_paid":$gross,
           "currency":"eur",
           "tax":$vat,
           "metadata":{},
           "parent":{"subscription_details":{"metadata":{"product_id":"${checkout.productId}","owner_id":"${checkout.ownerId}"}}},
           "lines":{"data":[{"period":{"end":$periodEndSeconds}}]},
           "payments":{"data":[{"payment":{"payment_intent":"pi_fake_${checkout.sessionId}","charge":null}}]},
           "period_start":${nowMs / 1000},
           "period_end":$periodEndSeconds,
           "subscription":"${FakeStripeClient.subscriptionIdFor(checkout.sessionId)}"
         }}}
    """.trimIndent().replace("\n", "").replace("  ", "")
}

/**
 * The acknowledgement metadata Stripe would echo back, when the checkout carried one. Rendered as a
 * suffix rather than interpolated into the object, so the JSON stays hand-assembled — the HMAC
 * covers these exact bytes.
 */
private fun ackMetadata(checkout: FakeStripeClient.PendingCheckout): String =
    if (checkout.withdrawalAckTosVersion.isBlank()) {
        ""
    } else {
        ",\"withdrawal_ack_tos\":\"${checkout.withdrawalAckTosVersion}\""
    }

/** One fake billing period — 30 days, so `current_period_end` is a plausible subscription window. */
private const val FAKE_PERIOD_MS: Long = 30L * 24 * 60 * 60 * 1000

private fun kotlinx.html.HTML.renderCheckout(checkout: FakeStripeClient.PendingCheckout, grossMinor: Long?) {
    body {
        style { unsafe { +DEV_PAGE_CSS } }
        div("card") {
            p("badge") { +"DEV PAYMENT RAIL — no money moves" }
            h1 { +"Confirm your subscription" }
            p {
                +"Plan: "
                +checkout.productId
                if (grossMinor != null) {
                    +" — €${"%.2f".format(grossMinor / 100.0)} / month (VAT incl.)"
                }
            }
            form(action = "${FakeStripeClient.CHECKOUT_PATH}/${checkout.sessionId}/confirm", method = FormMethod.post) {
                encType = FormEncType.applicationXWwwFormUrlEncoded
                label { +"Email (Stripe would collect this — it is what the webhook binds the account to)" }
                input(type = InputType.email, name = "email") {
                    required = true
                    placeholder = "you@example.com"
                    checkout.customerEmail?.let {
                        value = it
                        readonly = true
                    }
                }
                hiddenInput(name = "sessionId") { value = checkout.sessionId }
                button(type = ButtonType.submit) { +"Pay" }
            }
            p("muted") {
                +"Your subscription starts immediately when you pay. Cancelling later stops future "
                +"charges but does not refund the current period; closing your account forfeits it. "
                +"Just close this page to abandon the purchase — nothing is granted until you confirm."
            }
        }
    }
}

private fun kotlinx.html.HTML.renderPortal(returnUrl: String?) {
    body {
        style { unsafe { +DEV_PAGE_CSS } }
        div("card") {
            p("badge") { +"DEV PAYMENT RAIL — no money moves" }
            h1 { +"Billing portal (fake)" }
            p {
                +"With real Stripe this is the hosted portal where a customer updates their card, "
                +"views invoices or cancels. The fake rail has nothing to manage."
            }
            if (returnUrl != null) p { +"Return URL: $returnUrl" }
        }
    }
}

private val DEV_PAGE_CSS = """
    body{font-family:system-ui,-apple-system,sans-serif;background:#f5f5f7;margin:0;padding:48px 16px}
    .card{max-width:420px;margin:0 auto;background:#fff;border-radius:12px;padding:28px;
          box-shadow:0 1px 3px rgba(0,0,0,.12)}
    .badge{display:inline-block;background:#ffe9a8;color:#6b4e00;font-size:12px;font-weight:600;
           padding:4px 10px;border-radius:999px;margin:0 0 16px}
    h1{font-size:20px;margin:0 0 12px}
    label{display:block;font-size:13px;color:#555;margin:20px 0 6px}
    input{width:100%;padding:10px;font-size:15px;border:1px solid #ccc;border-radius:8px;
          box-sizing:border-box}
    button{margin-top:18px;width:100%;padding:12px;font-size:15px;font-weight:600;color:#fff;
           background:#3f51b5;border:0;border-radius:8px;cursor:pointer}
    .muted{color:#777;font-size:13px;margin-top:18px}
""".trimIndent()
