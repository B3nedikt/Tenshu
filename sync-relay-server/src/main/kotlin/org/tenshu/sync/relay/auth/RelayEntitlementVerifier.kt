package org.tenshu.sync.relay.auth

import io.ktor.server.application.ApplicationCall
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tenshu.core.common.entitlement.StorageTiers
import org.tenshu.core.common.security.Ed25519Backend
import org.tenshu.core.common.security.EntitlementTokenVerifier
import org.tenshu.identity.EntitlementSignature
import org.tenshu.sync.relay.RelayConfig

/**
 * The relay's entitlement check (`commerce-billing-plane.md` §8 / D2): verifies a presented
 * credential against the configured commerce public key (A29) and surfaces the tier/quota.
 * Verifier-only, stateless, no callback to commerce on the hot path — exactly the posture of
 * [JwtAuthenticator] for device keys.
 *
 * **Two *separate*, single-purpose credential channels** — kept on distinct headers so the strict
 * token path can never be entangled with the looser proof path (`second-device-entitlement-enforcement.md`
 * §2, Q3 resolved: separate header, not a discriminator on one header):
 *
 *  1. **Bearer token — [ENTITLEMENT_HEADER] (`X-Entitlement`).** The short, session-gated, blinding-
 *     ready relay credential (`commerce-billing-plane.md` D2). Verified via [verifyHeader] — checked
 *     **as strictly as possible** and *only* as a token; a value on this header is never re-interpreted
 *     as anything else. This is the first-class path.
 *  2. **Entitlement proof — [PROOF_HEADER] (`X-Entitlement-Proof`).** A generic, self-describing,
 *     type-tagged *proof of entitlement* the relay verifies cryptographically itself (zero-trust of
 *     commerce liveness), for callers that can't hold a live token. The value is `<type>.v<n>:<payload>`;
 *     the relay dispatches on the type prefix to a registered [ProofVerifier] ([verifyProof]). **v1
 *     registers exactly one type** — [SIGNED_RECORD_PROOF_TYPE] (`signed-record.v1:`), the long-lived,
 *     A29-signed `Entitlement` record every paired device already holds (closes the paired-second-device
 *     gap: a joiner inherits the realm + record but no commerce session, so it can't fetch a token).
 *     Future proof sources (e.g. a Rei on-chain purchase proof) slot in as **new registered verifiers**
 *     — additive, no change to the header wiring or the token path.
 *
 * **Phase 1 gates nothing.** Standing the verifier up makes the entitlement *available* to
 * endpoints; it does not enforce quota or reject unentitled writes. Enforcement (gating writes on a
 * live entitlement) is the separate `ENTITLEMENT_ENFORCE` flag (Phase 2), applied by
 * `requireEntitlement` / `resolveEntitlement`.
 *
 * Built only when [RelayConfig.entitlementIssuerPublicKeyB64] is set; otherwise the relay runs
 * without it (the default, unchanged behaviour). See [fromConfig].
 */
@OptIn(ExperimentalEncodingApi::class)
class RelayEntitlementVerifier(
    private val verifier: EntitlementTokenVerifier,
    private val proofVerifiers: Map<String, ProofVerifier>,
) {
    /**
     * Verify the raw [ENTITLEMENT_HEADER] value **as a bearer token**. `null`/blank → `null` (no
     * token presented — not an error here; the credential is optional). Otherwise the typed verify
     * result. This is the *only* interpretation of that header — the token path is deliberately
     * single-purpose.
     */
    fun verifyHeader(headerValue: String?): EntitlementTokenVerifier.Result? {
        val token = headerValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return verifier.verify(token)
    }

    /** The trimmed, non-empty **token** header ([ENTITLEMENT_HEADER]) off the call, or `null` when absent/blank. */
    fun tokenHeader(call: ApplicationCall): String? =
        call.request.headers[ENTITLEMENT_HEADER]?.trim()?.takeIf { it.isNotEmpty() }

    /** The trimmed, non-empty **proof** header ([PROOF_HEADER]) off the call, or `null` when absent/blank. */
    fun proofHeader(call: ApplicationCall): String? =
        call.request.headers[PROOF_HEADER]?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Verify a [PROOF_HEADER] value — a `<type>.v<n>:<payload>` **entitlement proof**. The type
     * prefix selects a registered [ProofVerifier] ([proofVerifiers]); an unknown type, or any verify
     * failure inside it, collapses to [ProofResult.Invalid] (never a 500). The `ownerId == authenticated
     * owner` check is the caller's step (it needs the request's owner) — see `resolveEntitlement`.
     */
    fun verifyProof(headerValue: String): ProofResult {
        val entry = proofVerifiers.entries.firstOrNull { headerValue.startsWith(it.key) }
            ?: return ProofResult.Invalid
        return entry.value.verify(headerValue.removePrefix(entry.key))
    }

    /**
     * A verifier for one proof *type* (keyed by its `<type>.v<n>:` scheme prefix in [proofVerifiers]).
     * Register additional types for new proof sources — e.g. a Rei on-chain purchase proof — without
     * touching the header wiring or the token path. [verify] receives the header value with the type
     * prefix already stripped.
     */
    fun interface ProofVerifier {
        fun verify(payload: String): ProofResult
    }

    /** The verified outcome of an entitlement proof — see [verifyProof]. */
    sealed interface ProofResult {
        /**
         * Cryptographically valid. [ownerId] is the owner the proof asserts (the caller matches it
         * against the JWT-authenticated owner); [quotaBytes] is the tier's byte grant.
         *
         * ⚠ This KDoc previously said `signed-record.v1` carries **no** quota ("relay-side quota
         * deferred, D6 → unmetered write in v1"). That has been false since D8 closed the
         * second-device bypass: [signedRecordProofVerifier] derives the quota from the record's
         * `productId` via [quotaForProduct], so a proof-authorised write is metered exactly like a
         * token write. The stale sentence caused a real detour on 2026-08-08 — a design was drafted
         * around "the relay cannot know a record's quota" while the implementation 90 lines below
         * had known it all along.
         *
         * `null` remains reachable only for a proof *type* that genuinely carries no quota claim;
         * no such type is registered today.
         */
        data class Valid(val ownerId: String, val quotaBytes: Long?) : ProofResult

        /** Unknown proof type, malformed payload, wrong signature, or expired/not-yet-valid. */
        data object Invalid : ProofResult
    }

    /**
     * Wire shape of the `signed-record.v1` proof payload (Q3). Mirrors the six signed
     * [EntitlementSignature.SignedEntitlement] fields; [sig] is the base64 signature. A small JSON
     * object (base64url'd for header safety) rather than a positional binary blob so the client
     * (Track B3) and the relay agree on field membership without a length-prefix contract.
     */
    @Serializable
    private data class SignedRecordProofDto(
        val ownerId: String,
        val publisherId: String,
        val productId: String,
        val validFrom: Long,
        val validUntil: Long,
        val sig: String,
    )

    companion object {
        /** The header the strict **bearer token** rides on, alongside `Authorization: Bearer <deviceJWT>`. */
        const val ENTITLEMENT_HEADER = "X-Entitlement"

        /**
         * The header the generic **entitlement proof** rides on — separate from [ENTITLEMENT_HEADER]
         * so the token path stays single-purpose. Value form: `<type>.v<n>:<payload>`.
         */
        const val PROOF_HEADER = "X-Entitlement-Proof"

        /**
         * The v1 proof *type* (Q3): the long-lived, A29-signed synced `Entitlement` record, encoded
         * as `signed-record.v1:` + base64url(JSON of the six signed record fields) (Track B2/B3).
         * The first (and, in v1, only) registered [ProofVerifier] type; a Rei on-chain proof would be
         * a sibling type (e.g. `chain-proof.v1:`) registered alongside it.
         */
        const val SIGNED_RECORD_PROOF_TYPE = "signed-record.v1:"

        /**
         * How far into the future a record's `validFrom` may sit and still verify.
         *
         * ⚠⚠ The record is stamped with the **commerce issuer's** clock and presented here against
         * the **relay's**, and these are two independently-NTP'd hosts. A record is at its youngest
         * exactly when it is first used — the write right after a purchase — so any positive issuer
         * skew rejects the freshest, most obviously-legitimate credential there is, and the user
         * sees `402` on the storage they just paid for. Observed 2026-08-08 as a 1.7 s skew between
         * commerce and a client; the same gap between commerce and the relay would land here.
         *
         * ⚠ Start bound only, never `validUntil` — see the client-side twin in
         * `EntitlementStorageProvider.kt`. Being early is a clock artefact on a record that becomes
         * valid regardless; being late is expiry, and honouring an expired record is a free grant.
         * A minute of leeway cannot escalate a tier or forge an owner: the signature still has to
         * verify, and both are covered by it.
         */
        const val VALID_FROM_SKEW_LEEWAY_MS = 60_000L

        /**
         * Build the verifier from [config], or `null` when no issuer public key is configured
         * (entitlement verification disabled — the relay's default). The single configured key is
         * registered under [RelayConfig.entitlementIssuerKeyId] (`null` = the issuer's default key);
         * additional keys for an A29 rollover slot into the map here. [nowSec] clocks the token
         * expiry; [nowMs] clocks the signed-record proof's millisecond validity window (both
         * injectable for tests).
         */
        @OptIn(ExperimentalEncodingApi::class)
        fun fromConfig(
            config: RelayConfig,
            ed25519: Ed25519Backend,
            nowSec: () -> Long = { System.currentTimeMillis() / 1000 },
            nowMs: () -> Long = { System.currentTimeMillis() },
        ): RelayEntitlementVerifier? {
            val b64 = config.entitlementIssuerPublicKeyB64 ?: return null
            val publicKey = Base64.Default.decode(b64.trim())
            val verifier = EntitlementTokenVerifier(
                publicKeysByKeyId = mapOf(config.entitlementIssuerKeyId to publicKey),
                ed25519 = ed25519,
                nowSec = nowSec,
            )
            val proofVerifiers = mapOf(
                SIGNED_RECORD_PROOF_TYPE to signedRecordProofVerifier(ed25519, publicKey, nowMs),
            )
            return RelayEntitlementVerifier(verifier, proofVerifiers)
        }

        /**
         * The `signed-record.v1` [ProofVerifier]: decode the base64url(JSON) payload into a
         * [EntitlementSignature.SignedEntitlement], verify its A29 signature against [issuerPublicKey]
         * over [EntitlementSignature.SignedEntitlement.message] (the exact bytes the commerce issuer
         * signs), and check `now ∈ [validFrom, validUntil]` (the record's instants are wall-clock
         * — the start bound carrying [VALID_FROM_SKEW_LEEWAY_MS] of tolerance, see below —
         * **milliseconds** since epoch). Any decode failure, bad signature, or NOT-YET-VALID record
         * collapses to [ProofResult.Invalid] — never a 500. An **expired** record does not: it floors
         * to `FREE_BYTES` (see the body), so a lapsed grant downgrades rather than going write-dead. Quota is derived from the record's
         * `productId` ([quotaForProduct]) so a proof-authorised write is metered against its tier
         * exactly like a token write (`retention-quota-model.md` D8 — closes the second-device bypass;
         * supersedes the earlier "unmetered in v1, D6" note).
         */
        @OptIn(ExperimentalEncodingApi::class)
        private fun signedRecordProofVerifier(
            ed25519: Ed25519Backend,
            issuerPublicKey: ByteArray,
            nowMs: () -> Long,
        ): ProofVerifier = ProofVerifier { payload ->
            val record = decodeSignedRecord(payload) ?: return@ProofVerifier ProofResult.Invalid
            if (!ed25519.verify(issuerPublicKey, record.message(), record.signature)) {
                return@ProofVerifier ProofResult.Invalid
            }
            val now = nowMs()
            // Not yet valid stays Invalid: a record whose window has not opened is not evidence of
            // anything, and the leeway already absorbs clock skew.
            if (now < record.validFrom - VALID_FROM_SKEW_LEEWAY_MS) {
                return@ProofVerifier ProofResult.Invalid
            }
            // ⚠⚠ An EXPIRED record floors to Free — it does NOT fail closed. A signed record is the
            // issuer's statement that this owner exists, and Free is the floor every owner has, so
            // withdrawing it on expiry buys no confidentiality and costs the owner every write:
            // uploads 402 while metadata keeps syncing, i.e. a Drive listing files whose bytes are
            // nowhere. The free tier has no session to renew from (the redeem path mints one grant
            // and no more), so for it "expired" is the steady state, not an anomaly.
            // Metering is unaffected — an expired record is metered at FREE_BYTES, so the
            // second-device bypass D8 closes stays closed. Same principle as an unknown productId
            // in [quotaForProduct]: never unlimited, never a wall.
            val quota = if (now > record.validUntil) {
                StorageTiers.FREE_BYTES
            } else {
                quotaForProduct(record.productId)
            }
            ProofResult.Valid(ownerId = record.ownerId, quotaBytes = quota)
        }

        /**
         * The tier byte-quota for a commerce product id (`retention-quota-model.md` D8, closing the
         * second-device unmetered-storage bypass).
         *
         * ⚠ This was a **third copy** of the tier constants, kept in lockstep with the client's
         * `EntitlementProducts` and commerce's `ProductCatalog` by comment alone. It now delegates
         * to the shared [StorageTiers] — reachable all along, since the relay already depends on
         * `:core-common`.
         *
         * ⚠ An **unknown** product id (a validly-signed credential for a tier this relay build has
         * never heard of) floors to Free: never unlimited (no bypass) and never `507` (no false
         * wall for someone who paid).
         */
        internal fun quotaForProduct(productId: String): Long =
            StorageTiers.quotaBytes(productId, fallback = StorageTiers.FREE_BYTES)

        @OptIn(ExperimentalEncodingApi::class)
        private fun decodeSignedRecord(payload: String): EntitlementSignature.SignedEntitlement? = try {
            val json = Base64.UrlSafe.decode(payload).decodeToString()
            val dto = Json.decodeFromString(SignedRecordProofDto.serializer(), json)
            EntitlementSignature.SignedEntitlement(
                ownerId = dto.ownerId,
                publisherId = dto.publisherId,
                productId = dto.productId,
                validFrom = dto.validFrom,
                validUntil = dto.validUntil,
                signature = Base64.Default.decode(dto.sig),
            )
        } catch (_: Exception) {
            // Any decode failure (bad base64, bad JSON, missing field) is an invalid credential —
            // never a 500. The specific reason stays server-side, exactly like the token path.
            null
        }
    }
}
