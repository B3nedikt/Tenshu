package org.tenshu.commerce.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import org.tenshu.commerce.BillingRules
import org.tenshu.commerce.PurchaseRail
import org.tenshu.commerce.SessionTokens
import org.tenshu.commerce.constantTimeEquals
import org.tenshu.commerce.play.GooglePlayClient
import org.tenshu.commerce.play.PlayLookup
import org.tenshu.commerce.play.PlaySubscription
import org.tenshu.commerce.store.AccountStatus
import org.tenshu.commerce.store.CommerceStore
import org.tenshu.commerce.store.EntitlementHistory
import org.tenshu.commerce.store.EntitlementSource
import org.tenshu.commerce.store.Rail
import java.util.Base64

private val log = LoggerFactory.getLogger("org.tenshu.commerce.routing.GooglePlayRouting")
private val playJson = Json { ignoreUnknownKeys = true }

/**
 * The Google Play rail (`commerce-store-link-out.md` D2, P2), bundled like [StripeSupport]. Null
 * (unconfigured) → both routes answer `503` and the Google rail sells nothing.
 */
class GooglePlaySupport(
    val client: GooglePlayClient,
    /** Play subscription id → our product id. */
    val products: Map<String, String>,
    /** Shared secret Pub/Sub must put in the push URL (`?token=…`); see the RTDN route. */
    val rtdnToken: String,
)

/**
 * `POST /commerce/iap/google/verify` — a device bought a subscription through Play and hands over
 * the purchase token; commerce checks it with Play and books it to the signed-in account.
 *
 * `POST /commerce/iap/google/rtdn` — Play's real-time developer notifications (renewal, cancel,
 * refund, expiry), pushed by Pub/Sub.
 *
 * ⚠⚠ A purchase commerce refuses is **not acknowledged**, and Play refunds an unacknowledged
 * purchase after three days. That is the never-pay-twice backstop (D6) for a purchase that should
 * not have been offered: the money goes back without anyone acting.
 */
fun Route.configureGooglePlayRouting(
    play: GooglePlaySupport?,
    stripe: StripeSupport?,
    store: CommerceStore,
    now: () -> Long = System::currentTimeMillis,
) {
    post("/commerce/iap/google/verify") {
        if (play == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "google_unavailable"))
            return@post
        }
        val request = runCatching { call.receive<GoogleVerifyRequest>() }.getOrNull()
        if (request == null || request.sessionToken.isBlank() || request.purchaseToken.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request"))
            return@post
        }
        val nowMs = now()
        val account = store.getSession(SessionTokens.hash(request.sessionToken))
            ?.takeIf { it.isValid(nowMs) }
            ?.let { store.findAccountById(it.accountId) }
        if (account == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            return@post
        }
        if (account.status == AccountStatus.Closed) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "account_closed"))
            return@post
        }
        val subscription = when (val lookup = play.client.subscription(request.purchaseToken)) {
            PlayLookup.Failed -> {
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to "google_error"))
                return@post
            }
            PlayLookup.NotFound -> {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "unknown_purchase"))
                return@post
            }
            is PlayLookup.Found -> lookup.subscription
        }
        // ⚠⚠ The purchase must name THIS account. Without the check, a token from one account's
        // purchase could be replayed by another session to get the plan for free.
        if (subscription.obfuscatedAccountId != account.id) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "account_mismatch"))
            return@post
        }
        val productId = subscription.productId?.let(play.products::get)
        if (productId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "unknown_product"))
            return@post
        }
        if (!subscription.entitlesAt(nowMs)) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to "not_active"))
            return@post
        }
        // ⚠⚠ Never a second Google subscription beside the one this account already holds. The
        // app replaces it (a subscription replacement, whose new token links back to the old
        // one); a new token that does not link to the one we hold while that one still entitles
        // is a second subscription billing in parallel. Refused ⇒ not acknowledged ⇒ refunded.
        val held = account.iapOriginalTxnId?.takeIf { account.rail == Rail.IapGoogle }
        if (held != null && held != request.purchaseToken && subscription.linkedPurchaseToken != held) {
            when (val previous = play.client.subscription(held)) {
                PlayLookup.Failed -> {
                    call.respond(HttpStatusCode.BadGateway, mapOf("error" to "google_error"))
                    return@post
                }
                PlayLookup.NotFound -> Unit
                // ⚠ On hold or paused counts as held: Play may still recover the payment and renew
                // it, yet queryPurchases does not return it, so the app bought without replacing.
                is PlayLookup.Found -> if (previous.subscription.stillHeldAt(nowMs)) {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to "google_subscription_not_replaced"))
                    return@post
                }
            }
        }
        // Never pay twice (D6), across every account of the realm — the rule `/billing` offers by.
        // Refused ⇒ not acknowledged ⇒ Play refunds it. Another rail's plan on THIS account is
        // stopped below instead.
        val reason = BillingRules.realmRefusal(
            store.realmPlans(store.realmAccountIds(account.id), nowMs), account.id, productId, PurchaseRail.Google,
        )
        if (reason != null) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to reason))
            return@post
        }

        recordGoogleGrant(store, account.id, productId, subscription, nowMs)
        store.setStoreRail(account.id, Rail.IapGoogle, request.purchaseToken)
        if (!subscription.acknowledged &&
            !play.client.acknowledge(subscription.productId!!, request.purchaseToken)
        ) {
            // Retryable: the grant is idempotent, and an unacknowledged purchase stays refundable.
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to "google_error"))
            return@post
        }
        // A Stripe plan on this account moves here (D6): refund its unused time and cancel it.
        val customerId = account.stripeCustomerId?.takeIf { it.isNotBlank() }
        if (customerId != null && stripe != null) {
            val billing = stripe.client.billingSubscriptions(customerId)
            if (billing == null || (billing.isNotEmpty() && stripe.client.refundUnusedAndCancel(customerId, nowMs) == null)) {
                // Retryable: verify is idempotent and the Stripe refund carries an idempotency key.
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to "previous_plan_not_stopped"))
                return@post
            }
            store.endEntitlements(account.id, EntitlementSource.Stripe, nowMs)
        }
        call.respond(
            HttpStatusCode.OK,
            GoogleVerifyResponse(productId = productId, validUntil = subscription.expiryMs ?: nowMs),
        )
    }

    post("/commerce/iap/google/rtdn") {
        if (play == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "google_unavailable"))
            return@post
        }
        // ⚠ The push URL carries a shared secret; without it anyone could make us re-read a token.
        // Re-reading is harmless (Play is the source of truth), but a refusal costs nothing.
        val token = call.request.queryParameters["token"].orEmpty()
        if (!constantTimeEquals(token, play.rtdnToken)) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            return@post
        }
        val notification = parseRtdn(call.receiveText())
        if (notification == null) {
            // Malformed or a test notification: acknowledged so Pub/Sub does not redeliver it forever.
            call.respond(HttpStatusCode.OK, mapOf("status" to "ignored"))
            return@post
        }
        val nowMs = now()
        val subscription = when (val lookup = play.client.subscription(notification.purchaseToken)) {
            // ⚠ Not acknowledged: a non-2xx makes Pub/Sub redeliver, which is the retry we want.
            PlayLookup.Failed -> {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "google_error"))
                return@post
            }
            PlayLookup.NotFound -> null
            is PlayLookup.Found -> lookup.subscription
        }
        val account = subscription?.obfuscatedAccountId?.let(store::findAccountById)
        if (subscription == null || account == null) {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ignored"))
            return@post
        }
        val productId = subscription.productId?.let(play.products::get)
        if (account.iapOriginalTxnId != notification.purchaseToken) {
            // ⚠⚠ Only the account's CURRENT token may change its grants. An upgrade inside Play
            // replaces the purchase with a new token, and the old one then reports EXPIRED —
            // ending the account's Google grants on that would take away the plan it upgraded to.
            // The new token is adopted instead (Google's own guidance): it names this account
            // (found by its obfuscatedAccountId) and links back to the token we hold.
            val linksToHeld = account.iapOriginalTxnId != null &&
                subscription.linkedPurchaseToken == account.iapOriginalTxnId &&
                account.rail == Rail.IapGoogle
            if (!linksToHeld) {
                call.respond(HttpStatusCode.OK, mapOf("status" to "ignored"))
                return@post
            }
            if (account.status == AccountStatus.Closed) {
                // A re-signup in Play's subscription centre (it links back too) on a closed account:
                // stopped like any renewal of one, below — never adopted.
                if (!cancelForClosedAccount(play, subscription, notification.purchaseToken)) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "google_error"))
                    return@post
                }
                call.respond(HttpStatusCode.OK, mapOf("status" to "closed"))
                return@post
            }
            if (productId == null || !subscription.entitlesAt(nowMs)) {
                call.respond(HttpStatusCode.OK, mapOf("status" to "ignored"))
                return@post
            }
            // ⚠⚠ The realm rule, as verify applies it. A linked token is not only an in-app
            // upgrade: a re-signup of a cancelled-but-running subscription in Play's subscription
            // centre links back too, without our app and without /billing. Adopting it over a plan
            // another account of the realm pays for would bill twice. Refused ⇒ ignored and left
            // unacknowledged ⇒ Play refunds it — the same net verify relies on.
            val reason = BillingRules.realmRefusal(
                store.realmPlans(store.realmAccountIds(account.id), nowMs), account.id, productId, PurchaseRail.Google,
            )
            if (reason != null) {
                log.warn("RTDN: not adopting a linked Google purchase ({}); Play refunds it unacknowledged", reason)
                call.respond(HttpStatusCode.OK, mapOf("status" to "ignored"))
                return@post
            }
            store.setStoreRail(account.id, Rail.IapGoogle, notification.purchaseToken)
        }
        if (account.status == AccountStatus.Closed) {
            // A closed account is never granted (CE-5). Closing cancelled its renewals; a renewal
            // arriving anyway (the user restored it, or the close-time call failed) cancels again,
            // and a failure asks Pub/Sub to redeliver.
            if (!cancelForClosedAccount(play, subscription, notification.purchaseToken)) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "google_error"))
                return@post
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "closed"))
            return@post
        }
        // ⚠ The re-read state alone decides. A voided-purchase notification can be a refund of one
        // renewal while the subscription itself stays active; when it ends the subscription, the
        // re-read says so.
        when {
            !subscription.entitlesAt(nowMs) -> store.endEntitlements(account.id, EntitlementSource.IapGoogle, nowMs)
            productId != null -> {
                recordGoogleGrant(store, account.id, productId, subscription, nowMs)
                // An adopted replacement is acknowledged here (nobody else may), or Play refunds it.
                if (!subscription.acknowledged &&
                    !play.client.acknowledge(subscription.productId!!, notification.purchaseToken)
                ) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "google_error"))
                    return@post
                }
            }
        }
        call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
    }
}

/** Stop [subscription] renewing because its account is closed. True when nothing will renew. */
private suspend fun cancelForClosedAccount(play: GooglePlaySupport, subscription: PlaySubscription, token: String): Boolean =
    subscription.state in PlaySubscription.ENDED_STATES || play.client.cancelRenewals(token)

/**
 * Book [subscription]'s paid period as a Google grant, once: a renewal extends the expiry and so

 * appends a new row, while a re-delivered verify or notification for the same period adds nothing.
 */
private fun recordGoogleGrant(
    store: CommerceStore,
    accountId: String,
    productId: String,
    subscription: PlaySubscription,
    nowMs: Long,
) {
    val validUntil = subscription.expiryMs ?: return
    // ⚠⚠ Never a grant for a closed account (CE-5), whichever route asks: verify refuses it
    // earlier, and a notification must not book one either.
    if (store.findAccountById(accountId)?.status == AccountStatus.Closed) return
    val booked = store.listEntitlementHistory(accountId).any {
        it.source == EntitlementSource.IapGoogle && it.productId == productId && it.validUntil == validUntil
    }
    if (booked) return
    store.appendEntitlementHistory(
        EntitlementHistory(
            accountId = accountId,
            productId = productId,
            validFrom = nowMs,
            validUntil = validUntil,
            source = EntitlementSource.IapGoogle,
            issuedAt = nowMs,
        ),
    )
}

/**
 * Stop a Google subscription because the account just paid for a plan on Stripe (D6, the
 * Google → Stripe direction). True when nothing is left billing on Google.
 *
 * ⚠ Called by the Stripe webhook **before** it books anything, so a failure can be answered with a
 * redelivery request: the webhook's invoice write is not idempotent.
 */
internal suspend fun stopGoogleForStripePurchase(
    play: GooglePlaySupport?,
    store: CommerceStore,
    email: String,
    nowMs: Long,
): Boolean {
    val account = store.findAccountByEmail(email) ?: return true
    // A closed account is granted nothing (the webhook refuses it), and closing already stopped
    // its Google renewals (SubscriptionCanceller): nothing to revoke on its behalf here.
    if (account.status == AccountStatus.Closed) return true
    val token = account.iapOriginalTxnId?.takeIf { account.rail == Rail.IapGoogle } ?: return true
    if (play == null) {
        val running = store.listEntitlementHistory(account.id).any {
            it.source == EntitlementSource.IapGoogle && it.validUntil >= nowMs
        }
        if (running) log.warn("A Stripe purchase replaces a Google plan, but the Google rail is not configured")
        return !running
    }
    // ⚠⚠ Asked of Play, not of our ledger: in account hold the notifications have ended the
    // Google grants, yet Play may still recover the payment and renew. Only an expired or
    // already revoked subscription is left alone.
    when (val lookup = play.client.subscription(token)) {
        PlayLookup.Failed -> return false
        PlayLookup.NotFound -> Unit
        is PlayLookup.Found ->
            if (lookup.subscription.state !in PlaySubscription.ENDED_STATES &&
                !play.client.revokeWithProratedRefund(token)
            ) {
                return false
            }
    }
    store.endEntitlements(account.id, EntitlementSource.IapGoogle, nowMs)
    return true
}


private class RtdnNotification(val purchaseToken: String, val voided: Boolean)

/** The Pub/Sub push envelope → the notification inside it, or null for a test/unknown message. */
private fun parseRtdn(body: String): RtdnNotification? = runCatching {
    val data = (playJson.parseToJsonElement(body).jsonObject["message"] as? JsonObject)
        ?.let { (it["data"] as? JsonPrimitive)?.contentOrNull } ?: return null
    val inner = playJson.parseToJsonElement(String(Base64.getDecoder().decode(data))).jsonObject
    val subscription = inner["subscriptionNotification"] as? JsonObject
    val voided = inner["voidedPurchaseNotification"] as? JsonObject
    val token = ((subscription ?: voided)?.get("purchaseToken") as? JsonPrimitive)?.contentOrNull
    token?.takeIf { it.isNotBlank() }?.let { RtdnNotification(it, voided = voided != null) }
}.getOrNull()

@Serializable
data class GoogleVerifyRequest(val sessionToken: String, val purchaseToken: String)

@Serializable
data class GoogleVerifyResponse(val productId: String, val validUntil: Long)
