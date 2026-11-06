package org.tenshu.commerce.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.tenshu.commerce.BillingRules
import org.tenshu.commerce.PurchaseRail
import org.tenshu.commerce.SessionTokens
import org.tenshu.commerce.store.AccountStatus
import org.tenshu.commerce.store.CommerceStore
import org.tenshu.commerce.stripe.CheckoutSessionRequest
import org.tenshu.commerce.stripe.StripeClient

/**
 * Everything the Stripe rail needs at routing time (`commerce-accounts-and-sessions.md` §6),
 * bundled so the Ktor `module` threads one nullable param rather than six. Null (unconfigured
 * Stripe) → the checkout / portal / webhook routes answer `503`; the free/redeem paths are
 * unaffected.
 */
class StripeSupport(
    val client: StripeClient,
    /** Our-productId → Stripe price id. */
    val priceIds: Map<String, String>,
    /** Webhook signing secret used by [configureStripeWebhookRouting]. */
    val webhookSecret: String,
    val successUrl: String,
    val cancelUrl: String,
    val portalReturnUrl: String,
    /**
     * Where a **browser** customer is sent back to, derived from `WEB_APP_ORIGIN`. Null when no web
     * origin is configured, which is what makes `client=web` answer 503 rather than redirecting a
     * paid customer into a `tenshu://` deep link no browser can follow.
     */
    val webReturnUrls: WebReturnUrls? = null,
)

/**
 * Post-checkout redirect targets for the web client, derived from the configured app origin.
 *
 * ⚠⚠ Derived, never request-supplied. A caller-chosen redirect on an unauthenticated route is an
 * open redirect, and this one carries a customer who has just paid.
 */
class WebReturnUrls(webAppOrigin: String) {
    private val base = "${webAppOrigin.trimEnd('/')}/control/services/checkout-result"
    val successUrl: String = "$base?status=success"
    val cancelUrl: String = "$base?status=cancel"
    val portalReturnUrl: String = "$base?status=portal"
}

/**
 * Which client started the flow. Decides only where Stripe sends the customer afterwards — never
 * what they are charged or what they get.
 *
 * ⚠ Defaults to [Native] so every existing client is byte-identically unaffected: the field is
 * absent from their requests and the native deep-links stay in force.
 */
@Serializable
enum class CheckoutClient { Native, Web }

/**
 * The customer-facing Stripe endpoints (§6): start a hosted Checkout (`/commerce/checkout`) and open
 * the billing portal (`/commerce/portal`). We are the seller of record; Stripe is the processor, so
 * card data never touches us (PCI scope stays with Stripe). Content-free errors throughout (the
 * relay's StatusPages posture).
 */
fun Route.configureCheckoutRouting(
    stripe: StripeSupport?,
    store: CommerceStore,
    now: () -> Long = System::currentTimeMillis,
) {
    post("/commerce/checkout") {
        if (stripe == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "stripe_unavailable"))
            return@post
        }
        val request = runCatching { call.receive<CheckoutRequest>() }.getOrNull()
        if (request == null || request.ownerId.isBlank() || request.productId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request"))
            return@post
        }
        // ⚠⚠ The withdrawal acknowledgement is enforced HERE, not only in the UI. A checkbox the
        // server does not check is decoration: anyone can POST this route directly, and the whole
        // point of the acknowledgement is that we can later evidence it was given. Refusing without
        // it is what makes policy §5.4's disapplication of the 14-day right actually true.
        if (!request.withdrawalAcknowledged || request.tosVersion.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "acknowledgement_required"))
            return@post
        }
        // A web checkout with no configured web origin would hand a paying customer a
        // `tenshu://` redirect their browser cannot follow. Refuse before taking money.
        val returns = returnUrlsFor(request.client, stripe)
        if (returns == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "stripe_unavailable"))
            return@post
        }
        val priceId = stripe.priceIds[request.productId]
        if (priceId == null) {
            // Unknown / non-purchasable tier → uniform 400, no detail leaked.
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "unknown_product"))
            return@post
        }
        // ⚠⚠ A browser buys signed in (§9.4f). The webhook creates-or-finds the account from the
        // email on the Stripe event, and nothing pushes a plan to a browser: it is fetched with a
        // session. Letting Stripe collect a free-typed email therefore left a paid plan on an
        // account the buyer's session did not belong to — observed on the first live pass, three
        // addresses across two purchases and a sign-in. A session here pins the email instead.
        val buyer = request.sessionToken.takeIf { it.isNotBlank() }
            ?.let { store.getSession(SessionTokens.hash(it)) }
            ?.takeIf { it.isValid(now()) }
            ?.let { store.findAccountById(it.accountId) }
        if (buyer == null && (request.client == CheckoutClient.Web || request.sessionToken.isNotBlank())) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            return@post
        }
        // The webhook would refuse the grant (CE-5); refuse before taking the money.
        if (buyer?.status == AccountStatus.Closed) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "account_closed"))
            return@post
        }
        // ⚠⚠ Never pay twice (commerce-store-link-out.md D6), for the plans Stripe cannot see: a
        // code, or a store subscription. Checked across every account bound to the realm, for the
        // reason given for Stripe below. A Stripe plan is left to the Stripe checks that follow.
        // A Google plan may be bought over (the webhook stops it), but only on the buyer's own
        // account: the webhook stops the subscription of the account it grants to.
        // The same rule `/billing` offers by (BillingRules.realmRefusal), so the two cannot disagree.
        val realmAccounts = (listOfNotNull(buyer?.id) + store.accountIdsForOwner(request.ownerId)).distinct()
        val reason = BillingRules.realmRefusal(
            store.realmPlans(realmAccounts, now()), buyer?.id, request.productId, PurchaseRail.Stripe,
        )
        if (reason != null) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to reason))
            return@post
        }

        // ⚠⚠ A hosted Checkout always starts a NEW subscription, so selling to a customer who
        // already has one bills them twice. Asked of Stripe, not of our ledger: a subscription
        // cancelled at period end still shows a live entitlement here, and only Stripe knows what
        // is actually billing. A failed lookup refuses — the wrong guess costs the customer money.
        buyer?.stripeCustomerId?.let { customerId ->
            val billing = stripe.client.billingSubscriptions(customerId)
            val refusal = when {
                billing == null -> HttpStatusCode.BadGateway to "stripe_error"
                billing.any { priceId in it.priceIds } -> HttpStatusCode.Conflict to "already_subscribed"
                // Another tier is billing: that is a plan change, made on the existing
                // subscription through the billing portal, never a second subscription.
                billing.isNotEmpty() -> HttpStatusCode.Conflict to "plan_change_required"
                else -> null
            }
            if (refusal != null) {
                call.respond(refusal.first, mapOf("error" to refusal.second))
                return@post
            }
        }
        // ⚠⚠ …and the REALM, not only the signed-in account. An owner binding is superseded per
        // account, so a realm that signs in with a second email is bound to both accounts — and the
        // second has no Stripe customer for the check above to find. On the live pass one realm
        // bought Basic three times under three emails. The buyer cannot manage that subscription
        // from this session (the portal is per account), so it is its own answer, not a plan change.
        val otherCustomers = store.accountIdsForOwner(request.ownerId)
            .filter { it != buyer?.id }
            .mapNotNull { store.findAccountById(it)?.stripeCustomerId?.takeIf(String::isNotBlank) }
        for (customerId in otherCustomers) {
            val billing = stripe.client.billingSubscriptions(customerId)
            if (billing == null) {
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to "stripe_error"))
                return@post
            }
            if (billing.isNotEmpty()) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to "subscribed_under_another_account"))
                return@post
            }
        }
        val url = stripe.client.createCheckoutSession(
            CheckoutSessionRequest(
                priceId = priceId,
                ownerId = request.ownerId,
                productId = request.productId,
                successUrl = returns.success,
                cancelUrl = returns.cancel,
                // Rides to Stripe and back on the event. There is no account to attach the
                // acknowledgement to yet — checkout is owner-gated and the account is created from
                // the collected email in the webhook — so the metadata IS the carrier, and the
                // webhook writes the durable acceptance row once the account exists.
                withdrawalAckTosVersion = request.tosVersion,
                customerId = buyer?.stripeCustomerId,
                customerEmail = buyer?.emailNormalised.takeIf { buyer?.stripeCustomerId == null },
            ),
        )
        if (url == null) {
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to "stripe_error"))
            return@post
        }
        call.respond(HttpStatusCode.OK, CheckoutResponse(checkoutUrl = url))
    }

    post("/commerce/portal") {
        if (stripe == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "stripe_unavailable"))
            return@post
        }
        val request = runCatching { call.receive<PortalRequest>() }.getOrNull()
        if (request == null || request.sessionToken.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request"))
            return@post
        }
        // A valid session is enough (no live-entitlement gate — the portal is exactly where a lapsed
        // customer resubscribes / manages billing).
        val session = store.getSession(SessionTokens.hash(request.sessionToken))
        if (session == null || !session.isValid(now())) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            return@post
        }
        val customerId = store.findAccountById(session.accountId)?.stripeCustomerId
        if (customerId == null) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to "no_subscription"))
            return@post
        }
        val portalReturn = returnUrlsFor(request.client, stripe)
        if (portalReturn == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "stripe_unavailable"))
            return@post
        }
        val url = stripe.client.createPortalSession(customerId, portalReturn.portal)
        if (url == null) {
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to "stripe_error"))
            return@post
        }
        call.respond(HttpStatusCode.OK, PortalResponse(portalUrl = url))
    }
}

private class ResolvedReturns(val success: String, val cancel: String, val portal: String)

/** Null for [CheckoutClient.Web] when the deployment has no web origin configured. */
private fun returnUrlsFor(client: CheckoutClient, stripe: StripeSupport): ResolvedReturns? =
    when (client) {
        CheckoutClient.Native -> ResolvedReturns(
            success = stripe.successUrl,
            cancel = stripe.cancelUrl,
            portal = stripe.portalReturnUrl,
        )
        CheckoutClient.Web -> stripe.webReturnUrls?.let {
            ResolvedReturns(
                success = it.successUrl,
                cancel = it.cancelUrl,
                portal = it.portalReturnUrl,
            )
        }
    }

@Serializable
data class CheckoutRequest(
    val ownerId: String,
    val productId: String,
    /**
     * The customer ticked the acknowledgement that the service starts immediately and that this
     * forfeits the EU 14-day right of withdrawal (policy §5.4). **False is a refusal**, not a
     * default — see the route.
     */
    val withdrawalAcknowledged: Boolean = false,
    /** Which terms version the acknowledgement was given against; stored as the evidence. */
    val tosVersion: String = "",
    /** Where to send the customer back to. Absent ⇒ [CheckoutClient.Native], as before. */
    val client: CheckoutClient = CheckoutClient.Native,
    /**
     * The buyer's commerce session. **Required for [CheckoutClient.Web]**; a native client may
     * still omit it and let Stripe collect the email, as before.
     */
    val sessionToken: String = "",
)

@Serializable
data class CheckoutResponse(val checkoutUrl: String)

@Serializable
data class PortalRequest(
    val sessionToken: String,
    val client: CheckoutClient = CheckoutClient.Native,
)

@Serializable
data class PortalResponse(val portalUrl: String)
