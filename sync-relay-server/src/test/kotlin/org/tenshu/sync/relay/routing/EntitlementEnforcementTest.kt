package org.tenshu.sync.relay.routing

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import org.tenshu.core.common.security.EntitlementTokenClaims
import org.tenshu.core.common.security.encodeEntitlementToken
import org.tenshu.core.common.util.sha256
import org.tenshu.identity.EntitlementSignature
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.RelayEntitlementVerifier.Companion.ENTITLEMENT_HEADER
import org.tenshu.sync.relay.auth.RelayEntitlementVerifier.Companion.PROOF_HEADER
import org.tenshu.sync.relay.auth.RelayEntitlementVerifier.Companion.SIGNED_RECORD_PROOF_TYPE
import org.tenshu.sync.relay.testModule
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals

/**
 * Relay-side **write-gating** on a live entitlement (`commerce-billing-plane.md` §8 / §9
 * Q5; `requireEntitlement`). Exercises the gate primarily through `PUT /sync/content/{ref}` (opaque
 * body — no schema construction needed) plus a `POST /sync/push` / `POST /share` rejection to prove
 * the gate is wired on those write endpoints too.
 *
 * Tokens are minted with the real [BouncyCastleEd25519Backend] (the module is wired with the same
 * backend) and the committed dev issuer seed `ByteArray(32){it+65}` — self-contained, no
 * `:commerce-service` dependency, matching `RelayEntitlementVerifierTest`.
 */
@OptIn(ExperimentalEncodingApi::class)
class EntitlementEnforcementTest {

    private val ed = BouncyCastleEd25519Backend()
    private val issuerSeed = ByteArray(32) { (it + 65).toByte() }
    private val issuerPubB64 = Base64.Default.encode(ed.derivePublicKey(issuerSeed))

    private fun config(enforce: Boolean, withIssuer: Boolean = true) = RelayConfig(
        devMode = true,
        bindHost = "127.0.0.1",
        port = 8080,
        entitlementIssuerPublicKeyB64 = if (withIssuer) issuerPubB64 else null,
        entitlementEnforce = enforce,
    )

    /** Mint against the real wall clock (the module's verifier uses `System.currentTimeMillis`). */
    private fun mintToken(
        ownerId: String?,
        ttlSec: Long = 3600L,
        quotaBytes: Long = 50L * 1024 * 1024 * 1024,
    ): String {
        val now = System.currentTimeMillis() / 1000
        return encodeEntitlementToken(
            EntitlementTokenClaims(
                ownerId = ownerId,
                tier = "tenshu.drive.pro",
                quotaBytes = quotaBytes,
                issuedAtSec = now - 5,
                expiresAtSec = now + ttlSec,
            ),
        ) { msg -> ed.sign(issuerSeed, msg) }
    }

    /** A second distinct blob/ref so a test can fill quota with one then push a *new* ref over it. */
    private val ciphertext2 = ByteArray(256) { ((it + 13) and 0x7F).toByte() }
    private val ref2: String = "blob-" + sha256(ciphertext2).joinToString("") {
        ((it.toInt() and 0xFF).toString(16)).padStart(2, '0')
    }

    private val ciphertext = ByteArray(256) { (it and 0x7F).toByte() }
    private val ref: String = "blob-" + sha256(ciphertext).joinToString("") {
        ((it.toInt() and 0xFF).toString(16)).padStart(2, '0')
    }

    /** Wire mirror of the `signed-record.v1` proof DTO the relay decodes (Q3: base64url'd JSON). */
    @Serializable
    private data class RecordDto(
        val ownerId: String,
        val publisherId: String,
        val productId: String,
        val validFrom: Long,
        val validUntil: Long,
        val sig: String,
    )

    /**
     * Encode a signed `Entitlement` record into the `signed-record.v1:` proof form for the
     * `X-Entitlement-Proof` header. Signs the canonical [EntitlementSignature.message]
     * bytes with [signSeed] (the trusted issuer seed by default; pass a different seed to forge).
     * Instants are milliseconds since epoch.
     */
    private fun recordProof(
        ownerId: String,
        validFromMs: Long = System.currentTimeMillis() - 60_000,
        validUntilMs: Long = System.currentTimeMillis() + 3_600_000,
        productId: String = "tenshu.drive.basic",
        publisherId: String = EntitlementSignature.COMMERCE_PUBLISHER_ID,
        signSeed: ByteArray = issuerSeed,
    ): String {
        val message = EntitlementSignature.message(
            ownerId = ownerId,
            publisherId = publisherId,
            productId = productId,
            validFrom = validFromMs,
            validUntil = validUntilMs,
        )
        val dto = RecordDto(
            ownerId = ownerId,
            publisherId = publisherId,
            productId = productId,
            validFrom = validFromMs,
            validUntil = validUntilMs,
            sig = Base64.Default.encode(ed.sign(signSeed, message)),
        )
        val json = Json.encodeToString(RecordDto.serializer(), dto)
        return SIGNED_RECORD_PROOF_TYPE + Base64.UrlSafe.encode(json.encodeToByteArray())
    }

    @Test
    fun enforceOff_noToken_putSucceeds() = testApplication {
        application { testModule(config = config(enforce = false), ed25519 = ed) }
        val response = client.put("/sync/content/$ref") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            setBody(ciphertext)
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun enforceOn_noIssuerKey_isNoOp() = testApplication {
        // ENTITLEMENT_ENFORCE=true but no pubkey → no verifier → the gate is a no-op (the startup
        // warning covers the operator footgun). A write without a token still succeeds.
        application { testModule(config = config(enforce = true, withIssuer = false), ed25519 = ed) }
        val response = client.put("/sync/content/$ref") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            setBody(ciphertext)
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun enforceOn_noToken_put402() = testApplication {
        application { testModule(config = config(enforce = true), ed25519 = ed) }
        val response = client.put("/sync/content/$ref") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            setBody(ciphertext)
        }
        assertEquals(HttpStatusCode.PaymentRequired, response.status)
    }

    @Test
    fun enforceOn_validOwnerBoundToken_put204() = testApplication {
        application { testModule(config = config(enforce = true), ed25519 = ed) }
        val response = client.put("/sync/content/$ref") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            header(ENTITLEMENT_HEADER, mintToken(ownerId = "alice"))
            setBody(ciphertext)
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun enforceOn_blindedTokenNoSub_put204() = testApplication {
        // The strong/blinded variant carries no `sub`; the owner-binding check passes it through.
        application { testModule(config = config(enforce = true), ed25519 = ed) }
        val response = client.put("/sync/content/$ref") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            header(ENTITLEMENT_HEADER, mintToken(ownerId = null))
            setBody(ciphertext)
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun enforceOn_tokenBoundToOtherOwner_put403() = testApplication {
        // T-CB-02: a token minted for bob, replayed under alice's request, is rejected.
        application { testModule(config = config(enforce = true), ed25519 = ed) }
        val response = client.put("/sync/content/$ref") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            header(ENTITLEMENT_HEADER, mintToken(ownerId = "bob"))
            setBody(ciphertext)
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun enforceOn_expiredToken_put402() = testApplication {
        application { testModule(config = config(enforce = true), ed25519 = ed) }
        val response = client.put("/sync/content/$ref") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            header(ENTITLEMENT_HEADER, mintToken(ownerId = "alice", ttlSec = -10L))
            setBody(ciphertext)
        }
        assertEquals(HttpStatusCode.PaymentRequired, response.status)
    }

    // ── `signed-record.v1` proof accepted on the `X-Entitlement-Proof` header ────────────────────

    @Test
    fun enforceOn_validRecordProof_put204() = testApplication {
        // A paired second device presents the long-lived, owner-bound signed record it already holds
        // (no commerce session / token needed) on the proof header → accepted, same as a valid token.
        application { testModule(config = config(enforce = true), ed25519 = ed) }
        val response = client.put("/sync/content/$ref") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            header(PROOF_HEADER, recordProof(ownerId = "alice"))
            setBody(ciphertext)
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun enforceOn_recordProofBoundToOtherOwner_put402() = testApplication {
        // A record minted for bob, replayed under alice's request, is rejected (proof owner-binding
        // is mandatory; the proof path keeps the 402 contract, no 403).
        application { testModule(config = config(enforce = true), ed25519 = ed) }
        val response = client.put("/sync/content/$ref") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            header(PROOF_HEADER, recordProof(ownerId = "bob"))
            setBody(ciphertext)
        }
        assertEquals(HttpStatusCode.PaymentRequired, response.status)
    }

    @Test
    fun enforceOn_expiredRecordProof_putSucceedsAtFreeTier() = testApplication {
        // ⚠⚠ Pins: an expired record floors to Free, so the write goes through. A 402 for `now`
        // past `validUntil` would make a lapsed grant WRITE-DEAD — uploads refused while metadata
        // kept syncing, so Drive would list files whose bytes are nowhere.
        application { testModule(config = config(enforce = true), ed25519 = ed) }
        val now = System.currentTimeMillis()
        val response = client.put("/sync/content/$ref") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            header(
                PROOF_HEADER,
                recordProof(ownerId = "alice", validFromMs = now - 7_200_000, validUntilMs = now - 60_000),
            )
            setBody(ciphertext)
        }
        assertNotEquals(HttpStatusCode.PaymentRequired, response.status)
        assertTrue(response.status.isSuccess(), "expected success, got ${response.status}")
    }

    @Test
    fun enforceOn_badSignatureRecordProof_put402() = testApplication {
        // Record signed by a seed the relay does NOT trust → signature fails → rejected.
        application { testModule(config = config(enforce = true), ed25519 = ed) }
        val otherSeed = ByteArray(32) { (it + 9).toByte() }
        val response = client.put("/sync/content/$ref") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            header(PROOF_HEADER, recordProof(ownerId = "alice", signSeed = otherSeed))
            setBody(ciphertext)
        }
        assertEquals(HttpStatusCode.PaymentRequired, response.status)
    }

    @Test
    fun enforceOn_malformedRecordProof_put402() = testApplication {
        // A `signed-record.v1:` prefix over junk that isn't base64url'd JSON is an invalid
        // credential — a 402, never a 500.
        application { testModule(config = config(enforce = true), ed25519 = ed) }
        val response = client.put("/sync/content/$ref") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            header(PROOF_HEADER, SIGNED_RECORD_PROOF_TYPE + "not-a-valid-payload")
            setBody(ciphertext)
        }
        assertEquals(HttpStatusCode.PaymentRequired, response.status)
    }

    @Test
    fun enforceOn_unknownProofType_put402() = testApplication {
        // An unregistered proof type on the proof header is an invalid credential — a 402, not a 500.
        application { testModule(config = config(enforce = true), ed25519 = ed) }
        val response = client.put("/sync/content/$ref") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            header(PROOF_HEADER, "chain-proof.v1:whatever")
            setBody(ciphertext)
        }
        assertEquals(HttpStatusCode.PaymentRequired, response.status)
    }

    @Test
    fun enforceOn_validRecordProof_syncPush204() = testApplication {
        // The proof credential is honoured on `/sync/push` too (the gate is shared), proving the
        // paired-device gap is closed on the push path, not just content PUT.
        application { testModule(config = config(enforce = true), ed25519 = ed) }
        val response = client.post("/sync/push") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            header(PROOF_HEADER, recordProof(ownerId = "alice"))
            setBody(ByteArray(0))
        }
        // Empty body → the gate passes (proof accepted) and the handler reaches body parsing; the
        // key assertion is that it is NOT a 402 (the credential was accepted).
        assertEquals(true, response.status != HttpStatusCode.PaymentRequired)
    }

    @Test
    fun enforceOn_validTokenTakesPrecedenceOverProof_put204() = testApplication {
        // Both headers present: the token path is checked first and its valid answer is used — the
        // proof header is never consulted. Proves the two channels are independent (a device with a
        // session sends its token; an also-attached proof is simply ignored).
        application { testModule(config = config(enforce = true), ed25519 = ed) }
        val response = client.put("/sync/content/$ref") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            header(ENTITLEMENT_HEADER, mintToken(ownerId = "alice"))
            header(PROOF_HEADER, recordProof(ownerId = "alice"))
            setBody(ciphertext)
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun enforceOn_invalidToken_doesNotFallThroughToProof_put402() = testApplication {
        // Token path is single-purpose and strict: a present-but-invalid token is a final 402, even
        // when a *valid* proof is also attached (no silent fall-through masking a broken token path).
        application { testModule(config = config(enforce = true), ed25519 = ed) }
        val response = client.put("/sync/content/$ref") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            header(ENTITLEMENT_HEADER, mintToken(ownerId = "alice", ttlSec = -10L)) // expired
            header(PROOF_HEADER, recordProof(ownerId = "alice"))
            setBody(ciphertext)
        }
        assertEquals(HttpStatusCode.PaymentRequired, response.status)
    }

    @Test
    fun enforceOn_readsStayUngated() = testApplication {
        // §9 Q5: writes are gated, reads are not. Store with a valid token, then read it back with
        // NO entitlement header — the GET still succeeds.
        application { testModule(config = config(enforce = true), ed25519 = ed) }
        val put = client.put("/sync/content/$ref") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            header(ENTITLEMENT_HEADER, mintToken(ownerId = "alice"))
            setBody(ciphertext)
        }
        assertEquals(HttpStatusCode.NoContent, put.status)
        put.bodyAsBytes()

        val get = client.get("/sync/content/$ref") {
            header("X-Dev-Owner", "alice")
            // deliberately no X-Entitlement
        }
        assertEquals(HttpStatusCode.OK, get.status)
    }

    @Test
    fun enforceOn_syncPush_noToken_rejected() = testApplication {
        // Proves the gate is wired on /sync/push: the rejection fires before the body is parsed, so
        // no valid SyncBlock is needed to exercise it.
        application { testModule(config = config(enforce = true), ed25519 = ed) }
        val response = client.post("/sync/push") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            setBody(ByteArray(0))
        }
        assertEquals(HttpStatusCode.PaymentRequired, response.status)
    }

    @Test
    fun enforceOn_shareDrop_noToken_rejected() = testApplication {
        // Proves the gate is wired on POST /share (the share drop). Rejection precedes body parse.
        application { testModule(config = config(enforce = true), ed25519 = ed) }
        val response = client.post("/share") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", "alice")
            setBody("{}")
        }
        assertEquals(HttpStatusCode.PaymentRequired, response.status)
    }

    // ── §8: per-owner byte quota on the content plane ───────────────────────────────────────────

    @Test
    fun enforceOn_withinQuota_put204() = testApplication {
        // A 256-byte chunk under a 1 KiB quota stores normally.
        application { testModule(config = config(enforce = true), ed25519 = ed) }
        val response = client.put("/sync/content/$ref") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            header(ENTITLEMENT_HEADER, mintToken(ownerId = "alice", quotaBytes = 1024))
            setBody(ciphertext)
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun enforceOn_overQuota_put507() = testApplication {
        // The 256-byte chunk exceeds a 100-byte quota → 507 over_quota, nothing stored.
        application { testModule(config = config(enforce = true), ed25519 = ed) }
        val response = client.put("/sync/content/$ref") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            header(ENTITLEMENT_HEADER, mintToken(ownerId = "alice", quotaBytes = 100))
            setBody(ciphertext)
        }
        assertEquals(HttpStatusCode.InsufficientStorage, response.status)
    }

    @Test
    fun enforceOn_secondNewRefOverQuota_put507() = testApplication {
        // Quota fits exactly one 256-byte blob. The first ref stores; a *different* ref pushes the
        // owner over → 507. Proves the gate meters cumulative per-owner bytes, not just one request.
        application { testModule(config = config(enforce = true), ed25519 = ed) }
        val token = mintToken(ownerId = "alice", quotaBytes = 256)
        val first = client.put("/sync/content/$ref") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            header(ENTITLEMENT_HEADER, token)
            setBody(ciphertext)
        }
        assertEquals(HttpStatusCode.NoContent, first.status)
        val second = client.put("/sync/content/$ref2") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            header(ENTITLEMENT_HEADER, token)
            setBody(ciphertext2)
        }
        assertEquals(HttpStatusCode.InsufficientStorage, second.status)
    }

    @Test
    fun enforceOn_idempotentRePut_doesNotDoubleCount() = testApplication {
        // Quota fits exactly one blob. Re-PUTting the SAME ref must not re-bill — a retried emit by
        // an owner sitting exactly at quota still succeeds (idempotent, content-addressed).
        application { testModule(config = config(enforce = true), ed25519 = ed) }
        val token = mintToken(ownerId = "alice", quotaBytes = 256)
        repeat(2) {
            val response = client.put("/sync/content/$ref") {
                contentType(ContentType.Application.OctetStream)
                header("X-Dev-Owner", "alice")
                header(ENTITLEMENT_HEADER, token)
                setBody(ciphertext)
            }
            assertEquals(HttpStatusCode.NoContent, response.status)
        }
    }

    @Test
    fun enforceOn_blindedToken_stillQuotaMetered() = testApplication {
        // A blinded token (no `sub`) passes the owner-binding check but is still byte-metered: its
        // quota bounds the bearer just the same.
        application { testModule(config = config(enforce = true), ed25519 = ed) }
        val response = client.put("/sync/content/$ref") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            header(ENTITLEMENT_HEADER, mintToken(ownerId = null, quotaBytes = 100))
            setBody(ciphertext)
        }
        assertEquals(HttpStatusCode.InsufficientStorage, response.status)
    }

    @Test
    fun enforceOff_ignoresQuota() = testApplication {
        // With enforcement off the quota is never consulted — even a token-less write of a blob
        // larger than any free-tier quota stores (the pre-2c default; gate is a no-op).
        application { testModule(config = config(enforce = false), ed25519 = ed) }
        val response = client.put("/sync/content/$ref") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            setBody(ciphertext)
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    // ── The write probe answers exactly as `/sync/push`'s gates would ─────────────────────────────

    @Test
    fun writeProbe_enforceOn_noToken_402() = testApplication {
        // ⚠⚠ The case the probe exists for: reads succeed, writes are refused. A probe that answered
        // anything but 402 here would certify a move while every push is being rejected.
        application { testModule(config = config(enforce = true), ed25519 = ed) }
        val response = client.post("/sync/push/probe") { header("X-Dev-Owner", "alice") }
        assertEquals(HttpStatusCode.PaymentRequired, response.status)
    }

    @Test
    fun writeProbe_enforceOn_validToken_204() = testApplication {
        application { testModule(config = config(enforce = true), ed25519 = ed) }
        val response = client.post("/sync/push/probe") {
            header("X-Dev-Owner", "alice")
            header(ENTITLEMENT_HEADER, mintToken(ownerId = "alice"))
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun writeProbe_enforceOff_204() = testApplication {
        application { testModule(config = config(enforce = false), ed25519 = ed) }
        val response = client.post("/sync/push/probe") { header("X-Dev-Owner", "alice") }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun writeProbe_unauthenticated_isRefused() = testApplication {
        application { testModule(config = config(enforce = false), ed25519 = ed) }
        val response = client.post("/sync/push/probe")
        assertEquals(HttpStatusCode.Unauthorized, response.status, "no owner, no write — the auth gate runs first")
    }
}
