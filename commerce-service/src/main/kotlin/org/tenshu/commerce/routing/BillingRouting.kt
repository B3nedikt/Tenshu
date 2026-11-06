package org.tenshu.commerce.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.tenshu.commerce.BilledBy
import org.tenshu.commerce.BillingRules
import org.tenshu.commerce.PurchaseRail
import org.tenshu.commerce.SessionTokens
import org.tenshu.commerce.store.CommerceStore

/**
 * `POST /commerce/account/billing` (`commerce-store-link-out.md` §0.6 P1): the signed-in account's
 * current plan, who bills it, and what the calling device may buy (D6).
 *
 * ⚠ Session-gated, like `/portal`: a device without a session shows the synced plan and offers no
 * purchase, because any purchase has to name its account.
 *
 * @param sellable the products each rail sells. Stripe's are its configured price map; the store
 *   rails sell nothing until P2 (Google) and P4 (Apple), so a store device gets no offers yet.
 */
fun Route.configureBillingRouting(
    store: CommerceStore,
    sellable: Map<PurchaseRail, Collection<String>>,
    now: () -> Long = System::currentTimeMillis,
) {
    post("/commerce/account/billing") {
        val request = runCatching { call.receive<BillingRequest>() }.getOrNull()
        if (request == null || request.sessionToken.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request"))
            return@post
        }
        val nowMs = now()
        val session = store.getSession(SessionTokens.hash(request.sessionToken))
        val account = session?.takeIf { it.isValid(nowMs) }?.let { store.findAccountById(it.accountId) }
        if (account == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            return@post
        }
        // ⚠⚠ The realm-wide rules, through the same function verify and checkout refuse with: a
        // plan this answer offers is one the purchase will accept.
        val realm = store.realmPlans(store.realmAccountIds(account.id), nowMs)
        val current = realm.first { it.accountId == account.id }.current
        val billedBy = BillingRules.billedBy(current)
        val offers = BillingRules.realmOffers(
            realm = realm,
            buyerId = account.id,
            rail = request.rail,
            sellable = sellable[request.rail].orEmpty(),
        )
        call.respond(
            HttpStatusCode.OK,
            BillingResponse(
                accountId = account.id,
                productId = current?.productId,
                billedBy = billedBy,
                validUntil = current?.validUntil,
                offers = offers.products,
                upgradeOn = offers.upgradeOn,
                refusal = offers.refusal,
            ),
        )
    }
}

/** [accountId] and every other account bound to the same realm (its live owner), [accountId] first. */
internal fun CommerceStore.realmAccountIds(accountId: String): List<String> =
    (listOf(accountId) + (liveOwner(accountId)?.let(::accountIdsForOwner).orEmpty())).distinct()

/** Each of [accountIds] with its current plan, for [BillingRules.realmRefusal]. */
internal fun CommerceStore.realmPlans(accountIds: List<String>, nowMs: Long): List<BillingRules.RealmPlan> =
    accountIds.map { BillingRules.RealmPlan(it, currentEntitlement(it, nowMs)) }

@Serializable
data class BillingRequest(
    val sessionToken: String,
    /** The rail of the build asking. Absent ⇒ Stripe, which every client today is. */
    val rail: PurchaseRail = PurchaseRail.Stripe,
)

@Serializable
data class BillingResponse(
    /**
     * The account a store purchase must name: Play's `obfuscatedAccountId`, Apple's
     * `appAccountToken` (a UUID, which account ids are). Commerce refuses a purchase naming another.
     */
    val accountId: String,
    /** The current plan; null when the account holds no valid entitlement. */
    val productId: String?,
    val billedBy: BilledBy?,
    /** When the current grant ends (epoch ms). For a renewing plan, the end of the paid period. */
    val validUntil: Long?,
    /** What this device may buy, lowest first. Empty means "nothing here", never "nothing exists". */
    val offers: List<String>,
    /** Set when the plan can be upgraded only on that rail's devices (an Apple-billed plan). */
    val upgradeOn: BilledBy? = null,
    /**
     * Why [offers] is empty when the realm's other accounts are the reason (e.g.
     * `subscribed_under_another_account`) — the same code a purchase would be refused with.
     */
    val refusal: String? = null,
)

