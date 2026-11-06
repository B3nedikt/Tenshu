package org.tenshu.sync.relay.auth

import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import org.tenshu.identity.EntitlementRecordCredential
import org.tenshu.identity.EntitlementSignature
import org.tenshu.sync.relay.RelayConfig
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.tenshu.core.common.entitlement.StorageTiers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The **cross-module contract test** for the `signed-record.v1` entitlement proof: the *client's*
 * encoder ([EntitlementRecordCredential.encode]) feeding the *relay's* real decoder+verifier
 * ([RelayEntitlementVerifier.verifyProof]), with genuine Ed25519 signatures.
 *
 * Why this exists (`docs/tech-debt.md` → "Entitlement enforcement is untested end-to-end"): these
 * two halves are one wire contract, but nothing exercised them *together*. The client side only
 * asserted its output against a golden JSON string *believed* to match the decoder, and the relay
 * side only decoded payloads a test hand-built — so an inconsistency between them was invisible to
 * both suites. That is the shape of a bug that reached a real device (every `POST /sync/push`
 * refused `402` while the client held a valid, correctly-owner-bound, in-window record).
 *
 * It became possible only once the encoder moved to `:identity-service`, beside
 * [EntitlementSignature] — the relay cannot see the app layer where it used to live.
 */
@OptIn(ExperimentalEncodingApi::class)
class EntitlementProofRoundTripTest {

    private val ed25519 = BouncyCastleEd25519Backend()

    /** A real issuer keypair — no stubbed crypto, so a signature mismatch would actually fail. */
    private val issuerSeed = ByteArray(32) { (it + 65).toByte() }
    private val issuerPublicKey = ed25519.derivePublicKey(issuerSeed)

    private val now = 5_000L

    private fun verifier(publicKey: ByteArray = issuerPublicKey): RelayEntitlementVerifier =
        RelayEntitlementVerifier.fromConfig(
            config = RelayConfig(
                devMode = true,
                bindHost = "127.0.0.1",
                port = 8080,
                entitlementIssuerPublicKeyB64 = Base64.Default.encode(publicKey),
            ),
            ed25519 = ed25519,
            nowMs = { now },
        ) ?: error("verifier must be built when an issuer key is configured")

    /** Signs exactly what the commerce issuer signs: [EntitlementSignature.SignedEntitlement.message]. */
    private fun signedRecord(
        ownerId: String = "owner-1",
        productId: String = "tenshu.drive.free",
        validFrom: Long = 1_000L,
        validUntil: Long = 9_000L,
    ): EntitlementSignature.SignedEntitlement {
        val unsigned = EntitlementSignature.SignedEntitlement(
            ownerId = ownerId,
            publisherId = "tenshu.commerce",
            productId = productId,
            validFrom = validFrom,
            validUntil = validUntil,
            signature = ByteArray(0),
        )
        return unsigned.copy(
            signature = ed25519.sign(issuerSeed, unsigned.message()),
        )
    }

    @Test
    fun clientEncodedProof_isAcceptedByTheRelayVerifier() {
        val header = EntitlementRecordCredential.encode(signedRecord())

        val result = verifier().verifyProof(header)

        val valid = assertIs<RelayEntitlementVerifier.ProofResult.Valid>(
            result,
            "the relay must accept a proof the client encoded from a genuinely signed record — " +
                "this is the contract whose two halves were never tested together",
        )
        assertEquals("owner-1", valid.ownerId, "the asserted owner must survive the round trip")
    }

    @Test
    fun proofSignedByAnotherIssuer_isRejected() {
        val header = EntitlementRecordCredential.encode(signedRecord())
        val strangerKey = ed25519.derivePublicKey(ByteArray(32) { (it + 7).toByte() })

        // The relay pins ONE issuer key; a record signed by anything else must not verify.
        assertIs<RelayEntitlementVerifier.ProofResult.Invalid>(
            verifier(publicKey = strangerKey).verifyProof(header),
        )
    }

    @Test
    fun expiredRecordFloorsToFree_whileNotYetValidIsRejected() {
        val expired = EntitlementRecordCredential.encode(
            signedRecord(validFrom = 1_000L, validUntil = now - 1),
        )
        // ⚠ Past the skew leeway — a `now + 1` record is deliberately *accepted* now (issuer clock
        // drift), so testing the window needs a genuinely future-dated one.
        val future = EntitlementRecordCredential.encode(
            signedRecord(
                validFrom = now + RelayEntitlementVerifier.VALID_FROM_SKEW_LEEWAY_MS + 1,
                validUntil = now + 9_000_000L,
            ),
        )

        // ⚠⚠ Expired is NOT rejected: it floors to Free. A lapsed grant must downgrade, never go
        // write-dead — the free tier has no session to renew from, so expiry is its steady state.
        val lapsed = verifier().verifyProof(expired)
        assertIs<RelayEntitlementVerifier.ProofResult.Valid>(lapsed)
        assertEquals(StorageTiers.FREE_BYTES, lapsed.quotaBytes)

        // Not-yet-valid still is: its window never opened, which no owner reaches by waiting.
        assertIs<RelayEntitlementVerifier.ProofResult.Invalid>(verifier().verifyProof(future))
    }

    @Test
    fun tamperedPayload_isRejected() {
        val header = EntitlementRecordCredential.encode(signedRecord(ownerId = "owner-1"))
        // Re-encode the same record claiming a different owner but keeping the signature: the
        // signature covers `message()`, so the swap must fail verification rather than grant the
        // attacker another owner's quota.
        val forged = EntitlementRecordCredential.encode(
            signedRecord(ownerId = "owner-1").copy(ownerId = "owner-2"),
        )
        assertTrue(header != forged)

        assertIs<RelayEntitlementVerifier.ProofResult.Invalid>(verifier().verifyProof(forged))
    }

    @Test
    fun unknownProofType_isRejectedWithoutTouchingTheSignedRecordPath() {
        // The header is a generic `<type>.v<n>:` channel; an unregistered type must be refused,
        // not misparsed as a signed record.
        assertIs<RelayEntitlementVerifier.ProofResult.Invalid>(
            verifier().verifyProof("chain-proof.v1:whatever"),
        )
    }
}
