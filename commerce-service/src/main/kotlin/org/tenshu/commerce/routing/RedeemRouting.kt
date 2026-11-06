package org.tenshu.commerce.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.tenshu.commerce.SessionTokens
import org.tenshu.commerce.store.EntitlementHistory
import org.tenshu.commerce.store.EntitlementSource
import org.tenshu.commerce.EntitlementMinter
import org.tenshu.commerce.OtpCodes
import org.tenshu.commerce.store.CommerceStore
import java.util.Base64

/**
 * `POST /commerce/entitlement/redeem {code, ownerId}` — the advanced redemption path
 * (`commerce-accounts-and-sessions.md` §2.3 / D3). **Identity-light:** no email, no account, no
 * session — only Stripe-paid EU sales need captured identity (§5), and a redeemed code is not a
 * Stripe sale. A valid code mints the signed entitlement directly for the caller's `ownerId` and
 * skips checkout; the only state touched is the code's `used_count`.
 *
 * The whitelist's old founder/debug-grant role lives here now (§4.3): codes are seeded from
 * `REDEMPTION_CODES` at startup.
 */
fun Route.configureRedeemRouting(
    store: CommerceStore,
    minter: EntitlementMinter,
    now: () -> Long = System::currentTimeMillis,
) {
    post("/commerce/entitlement/redeem") {
        val request = runCatching { call.receive<RedeemRequest>() }.getOrNull()
        if (request == null || request.code.isBlank() || request.ownerId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request"))
            return@post
        }

        val nowMs = now()
        val code = store.getRedemptionCode(OtpCodes.hash(request.code))
        if (code == null || !code.isRedeemable(nowMs)) {
            // Unknown, exhausted, or expired → uniform 403, no mint, no detail leaked.
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
            return@post
        }

        // ⚠⚠ Resolve the session BEFORE spending the code. A supplied-but-invalid session must not
        // consume a redemption: the caller would get a signed record whose tier the account ledger
        // never learns, `/token` would keep reporting the old tier, and the code would be gone. That
        // is exactly the failure this binding exists to prevent, so it must not be reachable by
        // presenting a stale session.
        // ⚠ Two calls on purpose. `gateBySession` owns the authorization semantics (validity,
        // expiry-with-cleanup, and the closed-account refusal) and must stay the single place those
        // live — but it returns `Authorized(ownerId, productId)` and never exposes the account id,
        // which is what the ledger row needs. So: gate for the decision, session for the identity.
        val boundAccountId: String? = request.sessionToken?.let { token ->
            when (store.gateBySession(token, nowMs)) {
                // `NoEntitlement` is a REAL account, and in fact the common case here: a fresh Free
                // signup has a session and a ledger row, but a redeem may also precede any grant.
                is SessionGateResult.Authorized, SessionGateResult.NoEntitlement ->
                    store.getSession(SessionTokens.hash(token))?.accountId
                SessionGateResult.NoSession, SessionGateResult.AccountClosed -> {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "session_invalid"))
                    return@post
                }
            }
        }

        store.incrementRedemptionUse(code.codeHash)
        val signed = minter.mint(ownerId = request.ownerId, productId = code.productId, nowMs = nowMs)

        // Bind the tier to the account, exactly as the Stripe rail does. `currentEntitlement` picks
        // the highest-quota row among the valid ones, so a Premium redemption simply outranks the
        // Free row a signup left behind — no precedence rule to invent, and no downgrade when it
        // expires. ⚠ `EntitlementSource.Redeem` has existed unused since the ledger was written;
        // this is the wiring it was waiting for.
        //
        // ⚠⚠ Without this, a redeemed tier is invisible to the money path: /token mints from the
        // ACCOUNT's entitlement, the relay takes the token as final, and the user sees (and is
        // metered at) their old tier while holding a perfectly good signed record. Observed live on
        // 2026-08-08 — a Premium redemption that left the device on 5 GiB.
        boundAccountId?.let { accountId ->
            store.appendEntitlementHistory(
                EntitlementHistory(
                    accountId = accountId,
                    productId = code.productId,
                    validFrom = signed.validFrom,
                    validUntil = signed.validUntil,
                    source = EntitlementSource.Redeem,
                    issuedAt = nowMs,
                ),
            )
        }
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

@Serializable
data class RedeemRequest(
    val code: String,
    val ownerId: String,
    /**
     * Optional commerce session. ⚠ Absent keeps the historical identity-light behaviour (§2.3 / D3)
     * — a code with no email, no account, no session. Present binds the redeemed tier to that
     * account's ledger, which is what makes `/token` and `/mint` report it.
     */
    val sessionToken: String? = null,
)
