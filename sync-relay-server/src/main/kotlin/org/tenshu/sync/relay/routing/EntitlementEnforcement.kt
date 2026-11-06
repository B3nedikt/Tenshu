package org.tenshu.sync.relay.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory
import org.tenshu.core.common.security.EntitlementTokenVerifier
import org.tenshu.sync.relay.logId
import org.tenshu.sync.relay.auth.RelayEntitlementVerifier

/**
 * Relay-side **write-gating** on a live entitlement (`commerce-billing-plane.md` §8 Phase 2 / §9
 * Q5). Call right after [requireOwnerId] on a *write* endpoint; returns `true` to proceed, or
 * `false` after writing a typed error — the caller `return@post`/`return@put`s on `false`.
 *
 * **Opt-in.** When [enforce] is `false` (the default) or no [verifier] is configured, this is a
 * no-op that always proceeds — the Phase-1 verify-only posture, so unconfigured relays and
 * deployments whose clients don't yet present a credential are unaffected. Enforcement is flipped on
 * (`ENTITLEMENT_ENFORCE=true` + `ENTITLEMENT_ISSUER_PUBKEY` set) only once clients present the
 * `X-Entitlement` token (or `X-Entitlement-Proof`) on writes.
 *
 * When enforcing, a write must carry a valid, live, **owner-bound** credential on **one of two
 * separate headers** (`second-device-entitlement-enforcement.md` §2, Q3 — separate headers, not a
 * discriminator on one). They are checked **in order**, and the token path is single-purpose:
 *
 * **1. Bearer token — `X-Entitlement` (the strict, first-class path).** If this header is present it
 * is verified *only* as a token, and its answer is final (no fall-through to the proof header — a
 * bad token is a bad answer):
 *  - present but signature / expiry / algorithm invalid → `402 entitlement_invalid`
 *  - valid but bound to a different owner (`sub` ≠ authenticated [ownerId]) → `403
 *    entitlement_owner_mismatch` (**T-CB-02**). This owner-binding check is deliberately *not* in
 *    [EntitlementTokenVerifier] (which never inspects `ownerId`) so the strong/blinded variant —
 *    whose tokens carry no `sub` — verifies through the identical path; a token with no `sub`
 *    therefore passes here.
 *  - valid → allowed (carrying the token's `quotaBytes`).
 *
 * **2. Entitlement proof — `X-Entitlement-Proof` (consulted only when no token header is present).**
 * A generic, self-describing `<type>.v<n>:<payload>` proof the relay verifies itself. **v1's one type
 * is `signed-record.v1`** — the long-lived, commerce-signed `Entitlement` record every paired device
 * already holds (closes the paired-second-device gap: a joiner has the realm + record but no commerce
 * session, so it can't fetch a token). Verified via [RelayEntitlementVerifier.verifyProof] with the
 * **owner-binding mandatory** (the record always carries `ownerId`). Any failure — unknown type,
 * malformed, bad signature, expired, or `ownerId` ≠ authenticated [ownerId] — collapses to `402
 * entitlement_invalid` (the proof path keeps the exact 402 contract; the token form's `403`
 * owner-mismatch is unique to the blinded-token nuance). The `signed-record.v1` proof's quota is
 * derived from its record's `productId` (`RelayEntitlementVerifier.quotaForProduct`), so a
 * proof-authorised write is metered against its tier exactly like a token write —
 * `retention-quota-model.md` D8, closing the second-device unmetered-storage bypass.
 *
 * **Neither header present → `402 entitlement_required`** — the bearer must obtain a credential (a
 * free-tier token suffices — issuance is uniform, §9 Q2).
 *
 * Response bodies are content-free machine codes (`{"error":"…"}`); the verifier's typed failure
 * reason is never echoed to the client (it can name a `kid` / rotation gap). **Reads stay ungated**
 * (§9 Q5) — only write handlers call this, so an expired credential pauses new writes but never
 * blocks a download of already-stored data.
 *
 * **Quota (§8 Phase 2b).** This entry point enforces only *having* a live, owner-bound entitlement.
 * A handler that also needs *how many bytes* the tier grants (content-blob `PUT`) calls
 * [resolveEntitlement] instead, which carries `quotaBytes` through on the allow path; the per-owner
 * byte accounting lives in the content store, not here.
 */
internal suspend fun requireEntitlement(
    call: ApplicationCall,
    ownerId: String,
    verifier: RelayEntitlementVerifier?,
    enforce: Boolean,
): Boolean = resolveEntitlement(call, ownerId, verifier, enforce) !is EntitlementOutcome.Rejected

/**
 * The outcome of [resolveEntitlement]: either the request is [Rejected] (a typed error has already
 * been written — the caller just `return`s) or [Allowed], carrying the entitlement's [quotaBytes]
 * for a handler that meters per-owner bytes. [quotaBytes] is `null` only when the gate is a no-op
 * (enforcement off, or no issuer key configured) — the handler then stores unmetered. Both real
 * credential paths now carry a quota: the token from its claims, the `signed-record.v1` proof
 * derived from its record's `productId` (D8).
 */
internal sealed interface EntitlementOutcome {
    data object Rejected : EntitlementOutcome
    data class Allowed(val quotaBytes: Long?) : EntitlementOutcome
}

/**
 * The full gate (see [requireEntitlement] for the rejection contract), returning the verified
 * quota on success so a byte-metered handler (content `PUT`, §8 Phase 2b) can enforce it. The
 * owner-binding (T-CB-02) check stays here, not in [EntitlementTokenVerifier], so a blinded token
 * with no `sub` still resolves to [EntitlementOutcome.Allowed] (with its quota) and is metered.
 */
/**
 * Server-side reason log for a refused entitlement. The *client* deliberately receives a
 * content-free code (`entitlement_required` / `entitlement_invalid`) so a caller learns nothing
 * about which credential failed or which key it was checked against — but the operator needs the
 * specific cause, and until now nothing was logged at all. That gap turned a real incident into
 * hours of black-box narrowing: every `POST /sync/push` returned 402 with no way to tell
 * "no credential presented" from "credential rejected" (`docs/tech-debt.md` → "Entitlement
 * enforcement is untested end-to-end").
 *
 * Deliberately never logs the credential itself — only which path failed and why — and names the
 * owner by [logId] prefix only.
 */
private val entitlementLog = LoggerFactory.getLogger("org.tenshu.sync.relay.EntitlementEnforcement")

internal suspend fun resolveEntitlement(
    call: ApplicationCall,
    ownerId: String,
    verifier: RelayEntitlementVerifier?,
    enforce: Boolean,
): EntitlementOutcome {
    if (!enforce || verifier == null) return EntitlementOutcome.Allowed(quotaBytes = null)

    // 1. Bearer token (`X-Entitlement`) — the strict, single-purpose path. If present, its answer is
    //    final; we never fall through to the proof header on a bad token.
    val tokenHeader = verifier.tokenHeader(call)
    if (tokenHeader != null) {
        return when (val result = verifier.verifyHeader(tokenHeader)) {
            null -> {
                // Unreachable — `tokenHeader` is already non-blank — but keep the exhaustive contract.
                call.respond(HttpStatusCode.PaymentRequired, mapOf("error" to "entitlement_required"))
                EntitlementOutcome.Rejected
            }

            is EntitlementTokenVerifier.Result.Invalid -> {
                // Forged / expired / wrong-alg / unknown-kid all collapse to one content-free code —
                // the specific failure (which can name a kid) stays server-side.
                //
                // ⚠ Note what this branch means operationally: a token, once presented, is FINAL.
                // A client holding a bad token AND a perfectly good `signed-record.v1` proof is
                // refused here and the proof is never consulted (by design — see the comment on the
                // token path above). That combination is easy to hit right after a commerce session
                // goes stale, so the log says so explicitly.
                entitlementLog.warn(
                    "402 entitlement_invalid for owner {} — BEARER TOKEN rejected ({}); " +
                        "the proof header was NOT consulted (token answers are final)",
                    logId(ownerId),
                    result.failure,
                )
                call.respond(HttpStatusCode.PaymentRequired, mapOf("error" to "entitlement_invalid"))
                EntitlementOutcome.Rejected
            }

            is EntitlementTokenVerifier.Result.Valid -> {
                val sub = result.claims.ownerId
                if (sub != null && sub != ownerId) {
                    entitlementLog.warn(
                        "403 entitlement_owner_mismatch — token asserts owner {} but the request " +
                            "authenticated as {}",
                        logId(sub),
                        logId(ownerId),
                    )
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "entitlement_owner_mismatch"),
                    )
                    EntitlementOutcome.Rejected
                } else {
                    // ⚠ The token's own `quota` claim, deliberately — NOT a relay-side lookup from
                    // `tier`, which is how the *proof* path resolves it.
                    //
                    // The two carriers are not equivalent and should not be unified here. A signed
                    // record can only name a `productId`, so the relay must map it to bytes itself.
                    // A token carries an explicit number *because commerce can grant off-catalogue
                    // amounts* — `TokenRouting` mints unknown tiers at `topTierQuotaBytes`, which is
                    // the whole point of a redeem-only, promotional or bespoke grant. Deriving from
                    // `tier` here would silently floor every one of those to Free.
                    //
                    // Drift between the two is prevented structurally instead: commerce and the
                    // relay now read the same `StorageTiers` table, so for a catalogue tier the
                    // minted claim and a relay lookup agree by construction.
                    EntitlementOutcome.Allowed(quotaBytes = result.claims.quotaBytes)
                }
            }
        }
    }

    // 2. Entitlement proof (`X-Entitlement-Proof`) — consulted only when no token header is present.
    //    Owner-binding is mandatory; any failure keeps the 402 contract (no 403 — the proof always
    //    carries an owner, so there is no blinded-proof nuance to distinguish).
    val proofHeader = verifier.proofHeader(call)
    if (proofHeader != null) {
        return when (val result = verifier.verifyProof(proofHeader)) {
            RelayEntitlementVerifier.ProofResult.Invalid -> {
                // Unknown proof type, undecodable payload, bad signature, or out-of-window record.
                entitlementLog.warn(
                    "402 entitlement_invalid for owner {} — PROOF header rejected (unknown type, " +
                        "bad signature, or outside its validity window)",
                    logId(ownerId),
                )
                call.respond(HttpStatusCode.PaymentRequired, mapOf("error" to "entitlement_invalid"))
                EntitlementOutcome.Rejected
            }

            is RelayEntitlementVerifier.ProofResult.Valid -> {
                if (result.ownerId != ownerId) {
                    entitlementLog.warn(
                        "402 entitlement_invalid — proof asserts owner {} but the request " +
                            "authenticated as {}",
                        logId(result.ownerId),
                        logId(ownerId),
                    )
                    call.respond(HttpStatusCode.PaymentRequired, mapOf("error" to "entitlement_invalid"))
                    EntitlementOutcome.Rejected
                } else {
                    EntitlementOutcome.Allowed(quotaBytes = result.quotaBytes)
                }
            }
        }
    }

    // 3. Neither header presented — the client attached no credential at all. Logged distinctly
    //    from the rejected-credential cases above: "sent nothing" and "sent something bad" have
    //    completely different fixes (client wiring vs credential validity), and the wire code the
    //    client sees cannot tell them apart.
    entitlementLog.warn(
        "402 entitlement_required for owner {} — NO credential presented (neither {} nor the " +
            "proof header); the client attached nothing",
        logId(ownerId),
        RelayEntitlementVerifier.ENTITLEMENT_HEADER,
    )

    // 402 Payment Required is the apt status: the bearer must obtain an
    //    entitlement (a free-tier token suffices — issuance is uniform, §9 Q2).
    call.respond(HttpStatusCode.PaymentRequired, mapOf("error" to "entitlement_required"))
    return EntitlementOutcome.Rejected
}
