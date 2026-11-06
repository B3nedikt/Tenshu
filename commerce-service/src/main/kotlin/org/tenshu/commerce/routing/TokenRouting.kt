package org.tenshu.commerce.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.tenshu.commerce.EntitlementTokenMinter
import org.tenshu.commerce.ProductCatalog
import org.tenshu.commerce.store.CommerceStore

/**
 * `POST /commerce/entitlement/token` — issues the short-lived bearer **entitlement token** the
 * relay verifies (`commerce-billing-plane.md` §4 / D2). Parallel to `/commerce/entitlement/mint`
 * (which returns the synced-record signed fields); this returns a signed token string the client
 * presents to the relay alongside its device JWT.
 *
 * **Session-gated** (Phase 2): the account's current entitlement decides the tier, its bound owner
 * is the token's `ownerId`, and the tier's quota comes from [ProductCatalog]. No whitelist.
 */
fun Route.configureTokenRouting(
    tokenMinter: EntitlementTokenMinter,
    store: CommerceStore,
    now: () -> Long = System::currentTimeMillis,
) {
    post("/commerce/entitlement/token") {
        val request = runCatching { call.receive<TokenRequest>() }.getOrNull()
        if (request == null || request.sessionToken.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request"))
            return@post
        }

        val nowMs = now()
        when (val gate = store.gateBySession(request.sessionToken, nowMs)) {
            SessionGateResult.NoSession ->
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            SessionGateResult.NoEntitlement ->
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "no_entitlement"))
            // A closed account holds a valid session but must not be issued credentials while the
            // closure sweep is telling the relay to delete its content (CE-5).
            SessionGateResult.AccountClosed ->
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "account_closed"))
            is SessionGateResult.Authorized -> {
                val tier = gate.productId
                val quotaBytes = ProductCatalog.quotaBytes(tier, fallback = tokenMinter.topTierQuotaBytes)
                val token = tokenMinter.mint(
                    ownerId = gate.ownerId,
                    tier = tier,
                    quotaBytes = quotaBytes,
                    nowMs = nowMs,
                )
                call.respond(HttpStatusCode.OK, TokenResponse(token = token, tier = tier, quotaBytes = quotaBytes))
            }
        }
    }
}

/** Token request — just the commerce session; tier + owner come from the verified account. */
@Serializable
data class TokenRequest(
    val sessionToken: String,
)

/** Token response — the signed token string + the tier/quota it asserts (for client display). */
@Serializable
data class TokenResponse(
    val token: String,
    val tier: String,
    val quotaBytes: Long,
)
