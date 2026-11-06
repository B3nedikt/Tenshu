package org.tenshu.sync.relay.auth

import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import org.tenshu.core.common.security.EntitlementTokenClaims
import org.tenshu.core.common.security.EntitlementTokenFailure
import org.tenshu.core.common.security.EntitlementTokenVerifier
import org.tenshu.core.common.security.encodeEntitlementToken
import org.tenshu.identity.EntitlementSignature
import org.tenshu.sync.relay.RelayConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.tenshu.core.common.entitlement.StorageTiers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The relay's verifier-only entitlement check (`commerce-billing-plane.md` §8 Phase 1): a token
 * the commerce issuer signs verifies against the relay's configured public key; an absent header
 * is a no-op (Phase 1 gates nothing — the token is optional); expired / forged tokens are typed
 * failures. Self-contained — mints with a local seed, no `:commerce-service` dependency.
 */
@OptIn(ExperimentalEncodingApi::class)
class RelayEntitlementVerifierTest {

    private val ed = BouncyCastleEd25519Backend()
    private val issuerSeed = ByteArray(32) { (it + 65).toByte() }
    private val issuerPublicKeyB64 = Base64.Default.encode(ed.derivePublicKey(issuerSeed))

    private fun configWithIssuer() = RelayConfig(
        devMode = true,
        bindHost = "127.0.0.1",
        port = 8080,
        entitlementIssuerPublicKeyB64 = issuerPublicKeyB64,
    )

    private fun mintToken(nowSec: Long, ttlSec: Long = 3600, ownerId: String? = "owner-1"): String =
        encodeEntitlementToken(
            EntitlementTokenClaims(
                ownerId = ownerId,
                tier = "tenshu.drive.pro",
                quotaBytes = 50L * 1024 * 1024 * 1024,
                issuedAtSec = nowSec,
                expiresAtSec = nowSec + ttlSec,
            ),
        ) { msg -> ed.sign(issuerSeed, msg) }

    @Test
    fun fromConfig_nullWhenNoIssuerKey() {
        val none = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080)
        assertNull(RelayEntitlementVerifier.fromConfig(none, ed))
    }

    @Test
    fun verifiesCommerceMintedToken() {
        val now = 1_700_000_000L
        val verifier = RelayEntitlementVerifier.fromConfig(configWithIssuer(), ed, nowSec = { now })!!
        val result = verifier.verifyHeader(mintToken(now))
        val valid = assertIs<EntitlementTokenVerifier.Result.Valid>(result)
        assertEquals("tenshu.drive.pro", valid.claims.tier)
        assertEquals("owner-1", valid.claims.ownerId)
    }

    @Test
    fun absentHeader_isNullNoOp() {
        val verifier = RelayEntitlementVerifier.fromConfig(configWithIssuer(), ed)!!
        assertNull(verifier.verifyHeader(null))
        assertNull(verifier.verifyHeader("   "))
    }

    @Test
    fun expiredToken_isInvalid() {
        val now = 1_700_000_000L
        val verifier = RelayEntitlementVerifier.fromConfig(configWithIssuer(), ed, nowSec = { now + 100_000 })!!
        val result = verifier.verifyHeader(mintToken(now, ttlSec = 3600))
        val invalid = assertIs<EntitlementTokenVerifier.Result.Invalid>(result)
        assertIs<EntitlementTokenFailure.Expired>(invalid.failure)
    }

    @Test
    fun forgedToken_failsSignature() {
        val now = 1_700_000_000L
        // Token signed by a DIFFERENT seed than the relay is configured to trust.
        val otherSeed = ByteArray(32) { (it + 9).toByte() }
        val forged = encodeEntitlementToken(
            EntitlementTokenClaims("owner-1", "tenshu.drive.pro", 1L, now, now + 3600),
        ) { msg -> ed.sign(otherSeed, msg) }
        val verifier = RelayEntitlementVerifier.fromConfig(configWithIssuer(), ed, nowSec = { now })!!
        assertIs<EntitlementTokenVerifier.Result.Invalid>(verifier.verifyHeader(forged))
    }

    // ── Track B2: the `signed-record.v1` entitlement-proof type (generic proof channel) ───────────

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
     * Encode a signed record into the `signed-record.v1:` proof form (the v1 [PROOF_HEADER] type);
     * sign with [signSeed] (issuer default).
     */
    private fun recordProof(
        ownerId: String = "owner-1",
        validFromMs: Long = 1_000L,
        validUntilMs: Long = 9_000L,
        signSeed: ByteArray = issuerSeed,
    ): String {
        val msg = EntitlementSignature.message(
            ownerId, EntitlementSignature.COMMERCE_PUBLISHER_ID, "tenshu.drive.basic", validFromMs, validUntilMs,
        )
        val dto = RecordDto(
            ownerId, EntitlementSignature.COMMERCE_PUBLISHER_ID, "tenshu.drive.basic",
            validFromMs, validUntilMs, Base64.Default.encode(ed.sign(signSeed, msg)),
        )
        val json = Json.encodeToString(RecordDto.serializer(), dto)
        return RelayEntitlementVerifier.SIGNED_RECORD_PROOF_TYPE + Base64.UrlSafe.encode(json.encodeToByteArray())
    }

    @Test
    fun unknownProofType_isInvalid() {
        // A proof value whose type prefix is not registered → Invalid (never a 500, never a token).
        val verifier = RelayEntitlementVerifier.fromConfig(configWithIssuer(), ed)!!
        assertIs<RelayEntitlementVerifier.ProofResult.Invalid>(verifier.verifyProof("chain-proof.v1:abc"))
        // A bare token value on the proof channel is also just an unknown proof type.
        assertIs<RelayEntitlementVerifier.ProofResult.Invalid>(verifier.verifyProof("eyJ0b2tlbiI6MX0"))
    }

    @Test
    fun verifiesValidRecordProof() {
        // nowMs inside [1000, 9000] → valid.
        val verifier = RelayEntitlementVerifier.fromConfig(configWithIssuer(), ed, nowMs = { 5_000L })!!
        val result = verifier.verifyProof(recordProof())
        val valid = assertIs<RelayEntitlementVerifier.ProofResult.Valid>(result)
        assertEquals("owner-1", valid.ownerId)
        // D8: the proof is now metered — quota derived from the record's productId (basic → 50 GiB).
        assertEquals(
            50L * 1024 * 1024 * 1024,
            valid.quotaBytes,
            "signed-record.v1 quota is derived from its productId (D8), no longer null",
        )
    }

    @Test
    fun quotaForProduct_mapsEachTier_unknownFloorsToFree() {
        // D8 lockstep with EntitlementProducts / ProductCatalog (Free 5 · Basic 50 GiB · Premium 2 TiB).
        assertEquals(5L * 1024 * 1024 * 1024, RelayEntitlementVerifier.quotaForProduct("tenshu.drive.free"))
        assertEquals(50L * 1024 * 1024 * 1024, RelayEntitlementVerifier.quotaForProduct("tenshu.drive.basic"))
        assertEquals(2L * 1024 * 1024 * 1024 * 1024, RelayEntitlementVerifier.quotaForProduct("tenshu.drive.premium"))
        // Unknown tier → Free floor: never unlimited (no bypass), never a false 507 (no wall).
        assertEquals(5L * 1024 * 1024 * 1024, RelayEntitlementVerifier.quotaForProduct("tenshu.drive.future-tier"))
    }

    @Test
    fun expiredRecordProof_floorsToFree() {
        // nowMs past validUntil. The record stays VALID and is metered at the free tier — a lapse is
        // a downgrade, not a lockout. Its own productId is deliberately NOT consulted here, so an
        // expired paid record cannot keep buying paid quota.
        val verifier = RelayEntitlementVerifier.fromConfig(configWithIssuer(), ed, nowMs = { 10_000L })!!
        val result = verifier.verifyProof(recordProof())
        assertIs<RelayEntitlementVerifier.ProofResult.Valid>(result)
        assertEquals(StorageTiers.FREE_BYTES, result.quotaBytes)
    }

    @Test
    fun notYetValidRecordProof_isInvalid() {
        // ⚠ Far enough before `validFrom` (1_000) to clear the skew leeway — otherwise this asserts
        // the tolerance rather than the window.
        val verifier = RelayEntitlementVerifier.fromConfig(
            configWithIssuer(),
            ed,
            nowMs = { 1_000L - RelayEntitlementVerifier.VALID_FROM_SKEW_LEEWAY_MS - 1 },
        )!!
        assertIs<RelayEntitlementVerifier.ProofResult.Invalid>(verifier.verifyProof(recordProof()))
    }

    /**
     * ⚠⚠ The record is stamped with the commerce issuer's clock and checked against the relay's, so
     * a freshly-minted credential — the write right after a purchase — is exactly the one most
     * likely to look future-dated. Rejecting it means `402` on storage the user just bought.
     */
    @Test
    fun recordDatedSlightlyAhead_verifies_ratherThan402ingAFreshPurchase() {
        val verifier = RelayEntitlementVerifier.fromConfig(
            configWithIssuer(),
            ed,
            nowMs = { 1_000L - 2_000L }, // relay 2s behind the issuer, within the leeway
        )!!
        assertIs<RelayEntitlementVerifier.ProofResult.Valid>(verifier.verifyProof(recordProof()))
    }

    /**
     * ⚠ One-sided, and still is: tolerating a late record would extend a grant past what was sold.
     * The leeway that saves a *fresh* purchase from a 2 s clock difference must not also keep a
     * lapsed one on its paid tier.
     *
     * ⚠⚠ What changed with the free-grant fix is the CONSEQUENCE, not the boundary. One millisecond
     * past `validUntil` the record no longer becomes Invalid — it drops to Free. `recordProof()`
     * signs a `tenshu.drive.basic` (50 GiB) record, so a leeway leaking into the end bound would
     * show up here as BASIC_BYTES.
     */
    @Test
    fun expiryGetsNoLeeway_dropsToFreeImmediately() {
        val verifier = RelayEntitlementVerifier.fromConfig(
            configWithIssuer(),
            ed,
            nowMs = { 9_000L + 1 },
        )!!
        val result = verifier.verifyProof(recordProof())
        assertIs<RelayEntitlementVerifier.ProofResult.Valid>(result)
        assertEquals(StorageTiers.FREE_BYTES, result.quotaBytes)
        assertNotEquals(StorageTiers.BASIC_BYTES, result.quotaBytes)
    }

    @Test
    fun forgedRecordProof_isInvalid() {
        val otherSeed = ByteArray(32) { (it + 9).toByte() }
        val verifier = RelayEntitlementVerifier.fromConfig(configWithIssuer(), ed, nowMs = { 5_000L })!!
        assertIs<RelayEntitlementVerifier.ProofResult.Invalid>(
            verifier.verifyProof(recordProof(signSeed = otherSeed)),
        )
    }

    @Test
    fun malformedRecordProof_isInvalid() {
        val verifier = RelayEntitlementVerifier.fromConfig(configWithIssuer(), ed, nowMs = { 5_000L })!!
        assertIs<RelayEntitlementVerifier.ProofResult.Invalid>(
            verifier.verifyProof("signed-record.v1:@@not-base64@@"),
        )
    }
}
