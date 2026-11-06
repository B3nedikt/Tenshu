package org.tenshu.identity

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Encoder for the **`signed-record.v1` entitlement proof** presented on the `X-Entitlement-Proof`
 * header (`second-device-entitlement-enforcement.md` §4, Track B3 — the client side of the relay-side
 * Track B2). A paired second device inherits the realm (and thus the A29-signed
 * [EntitlementSignature.SignedEntitlement] record) but no commerce session, so it can't fetch a
 * bearer token; presenting the record it already holds as a *proof* lets it write under enforcement.
 *
 * The proof rides a **separate** header from the strict `X-Entitlement` bearer token (Q3): the token
 * header stays single-purpose, and `X-Entitlement-Proof` is a generic `<type>.v<n>:<payload>` channel
 * whose v1 (and, so far, only) type is [SIGNED_RECORD_PROOF_TYPE] (`signed-record.v1:`). Future proof
 * sources (e.g. a Rei on-chain purchase proof) are new types on the same header.
 *
 * **Wire contract (must match `RelayEntitlementVerifier`'s `signed-record.v1` decoder, Track B2).**
 * The value is [SIGNED_RECORD_PROOF_TYPE] followed by base64url([Base64.UrlSafe]) of a JSON object
 * with exactly the six signed fields — `ownerId`, `publisherId`, `productId`, `validFrom`,
 * `validUntil` (both ms since epoch), and `sig` (the Ed25519 signature bytes, **standard** base64 /
 * [Base64.Default]).
 *
 * We never re-sign: the record is read already-signed from the local store and its fields are
 * re-encoded. `sig` signs over [EntitlementSignature.SignedEntitlement.message]. The JSON is built
 * by hand (a flat, fixed-key object) so the module takes no `kotlinx-serialization-json` dependency;
 * the relay decodes it with a real JSON parser, which is order-agnostic and needs only the six keys.
 *
 * ⚠ **Lives in `:identity-service`, beside [EntitlementSignature], deliberately.** The encoder and
 * the relay's `signed-record.v1` decoder are two halves of ONE wire contract, and while the encoder
 * sat in `:control-shared-proxy` (an app module) no test could exercise the pair — the relay cannot
 * see the app layer. That gap is how an entitlement the relay refused shipped to a device
 * (`docs/tech-debt.md` → "Entitlement enforcement is untested end-to-end"). Both sides now share a
 * module, and `sync-relay-server`'s `EntitlementProofRoundTripTest` pins the contract end to end.
 */
@OptIn(ExperimentalEncodingApi::class)
object EntitlementRecordCredential {

    /** The v1 proof *type* prefix on the `X-Entitlement-Proof` header (Q3 / Track B2). */
    const val SIGNED_RECORD_PROOF_TYPE = "signed-record.v1:"

    /** Encode [record] into the `signed-record.v1:`-prefixed base64url(JSON) `X-Entitlement-Proof` value. */
    fun encode(record: EntitlementSignature.SignedEntitlement): String {
        val json = buildString {
            append('{')
            append("\"ownerId\":").append(jsonString(record.ownerId)).append(',')
            append("\"publisherId\":").append(jsonString(record.publisherId)).append(',')
            append("\"productId\":").append(jsonString(record.productId)).append(',')
            append("\"validFrom\":").append(record.validFrom).append(',')
            append("\"validUntil\":").append(record.validUntil).append(',')
            append("\"sig\":").append(jsonString(Base64.Default.encode(record.signature)))
            append('}')
        }
        return SIGNED_RECORD_PROOF_TYPE + Base64.UrlSafe.encode(json.encodeToByteArray())
    }

    /** Minimal RFC 8259 string escaper — enough for the six fields (ids, base64) we emit. */
    private fun jsonString(value: String): String = buildString {
        append('"')
        for (c in value) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> if (c < ' ') append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
        }
        append('"')
    }
}
