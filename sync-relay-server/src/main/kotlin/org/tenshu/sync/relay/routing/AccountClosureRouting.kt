package org.tenshu.sync.relay.routing

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.tenshu.sync.relay.AccountClosureReclaimer
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.RelayAccountClosureVerifier

/**
 * `POST /sync/account/closure` — the commerce plane hands the relay a signed
 * *"owner O's account closed at time C"* record; past the configured grace window the relay
 * erases everything it holds for that owner — content, ordered log, devices, metadata — except the
 * three revocation fences ([org.tenshu.sync.relay.AccountErasure]; `retention-quota-model.md` Face A /
 * D4, F12).
 *
 * ⚠⚠ **The only inbound surface that can destroy user content.** Its safety does not rest on the
 * transport: the request body **is** the credential (a commerce-signed, alg-pinned EdDSA record
 * verified against `ENTITLEMENT_ISSUER_PUBKEY`), so an attacker who reaches this endpoint without
 * the issuer's private key can do nothing. That is the same posture the entitlement token already
 * has, applied to a heavier verb.
 *
 * ⚠ **The route does not exist unless it is switched on.** With `ACCOUNT_CLOSURE_RECLAIM=false`
 * (the default) or no issuer key configured, nothing is registered — a probe gets a plain 404, not
 * a 403 that confirms the feature. Production runs it ON since 2026-10-24 (maintainer decision, ⚖️
 * counsel review of the retention text owed), so there the route answers `401` to junk.
 *
 * ⚠ **Deliberately re-delivered, not remembered.** The relay stores no closure state: commerce
 * re-posts while the account stays closed, and an account that reopens simply stops being posted.
 * Storing the record would mean the relay needs a *reopen* signal too, and a missed one would delete
 * a live account's files.
 */
fun Route.configureAccountClosureRouting(
    config: RelayConfig,
    verifier: RelayAccountClosureVerifier?,
    reclaimer: AccountClosureReclaimer,
) {
    // ⚠ Registration itself is the gate — see the KDoc. Both conditions are required: the flag
    // without a key would leave a route that can only ever reject.
    if (!config.accountClosureReclaim || verifier == null) return

    post("/sync/account/closure") {
        val token = call.receiveText().trim()
        val claims = token.takeIf { it.isNotEmpty() }?.let { verifier.verify(it) }
        if (claims == null) {
            // ⚠ One opaque response for every rejection — bad signature, expired, wrong key,
            // malformed, missing owner. Distinguishing them would tell an attacker which of the
            // four brakes they had cleared.
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_closure_record"))
            return@post
        }

        when (val outcome = reclaimer.reclaimIfDue(claims, System.currentTimeMillis())) {
            // Unreachable while registration is gated on the flag, but answered rather than
            // crashed: the reclaimer owns the decision, and this route must not assume its verdicts.
            AccountClosureReclaimer.Outcome.Disabled ->
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("status" to "disabled"))

            is AccountClosureReclaimer.Outcome.WithinGrace ->
                // 202: accepted and understood, deliberately not acted on yet. Commerce re-posts,
                // so there is nothing for it to retry differently.
                call.respond(HttpStatusCode.Accepted, WithinGraceResponse(remainingMs = outcome.remainingMs))

            is AccountClosureReclaimer.Outcome.Reclaimed ->
                call.respond(
                    HttpStatusCode.OK,
                    ReclaimedResponse(
                        blobsDropped = outcome.result.blobsDropped,
                        bytesFreed = outcome.result.bytesFreed,
                        rowsErased = outcome.result.rowsErased,
                    ),
                )
        }
    }
}

// ⚠ Typed responses, not `mapOf(...)`: a heterogeneous map has no kotlinx serializer and fails at
// runtime as a 500 — which on this route would read as "the relay is broken", not "not yet due".
@Serializable
private data class WithinGraceResponse(val remainingMs: Long, val status: String = "within_grace")

@Serializable
private data class ReclaimedResponse(
    val blobsDropped: Int,
    val bytesFreed: Long,
    /** F12: metadata rows erased alongside the content (counts only — never what they were). */
    val rowsErased: Int,
    val status: String = "reclaimed",
)
