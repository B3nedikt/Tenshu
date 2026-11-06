package org.tenshu.commerce.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.tenshu.commerce.EntitlementMinter
import org.tenshu.commerce.store.CommerceStore
import java.util.Base64

/**
 * `POST /commerce/entitlement/mint` — issues the signed synced-`Entitlement` record fields
 * (`commerce-accounts-and-sessions.md` §4.3).
 *
 * **Session-gated** (Phase 2): the request carries a commerce `sessionToken`; the account's
 * **current entitlement** decides the product and its **live owner** is the bound `ownerId`. No
 * client-supplied email/owner, no whitelist. The response carries the six signed entitlement fields
 * + the base64 signature; an on-device client materialises that into the synced `Entitlement`
 * record, and the existing verify-on-merge path accepts it because the signed bytes come from
 * `EntitlementSignature.message(...)` verbatim.
 */
fun Route.configureMintRouting(
    minter: EntitlementMinter,
    store: CommerceStore,
    now: () -> Long = System::currentTimeMillis,
) {
    post("/commerce/entitlement/mint") {
        val request = runCatching { call.receive<MintRequest>() }.getOrNull()
        if (request == null || request.sessionToken.isBlank()) {
            // Content-free 400 — never echo parse-exception text (relay StatusPages posture).
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
                val signed = minter.mint(ownerId = gate.ownerId, productId = gate.productId, nowMs = nowMs)
                call.respond(
                    HttpStatusCode.OK,
                    MintedEntitlement(
                        ownerId = signed.ownerId,
                        publisherId = signed.publisherId,
                        productId = signed.productId,
                        validFrom = signed.validFrom,
                        validUntil = signed.validUntil,
                        publisherSignature = Base64.getEncoder().encodeToString(signed.signature),
                    ),
                )
            }
        }
    }
}

/** Mint request — just the commerce session; owner + product come from the verified account. */
@Serializable
data class MintRequest(
    val sessionToken: String,
)

/**
 * Mint response — the signed entitlement fields. `publisherSignature` is base64; everything else
 * matches `EntitlementSignature.message(...)`'s field order so the client can re-derive the signed
 * bytes and the existing verifier accepts the materialised record.
 */
@Serializable
data class MintedEntitlement(
    val ownerId: String,
    val publisherId: String,
    val productId: String,
    val validFrom: Long,
    val validUntil: Long,
    val publisherSignature: String,
)
