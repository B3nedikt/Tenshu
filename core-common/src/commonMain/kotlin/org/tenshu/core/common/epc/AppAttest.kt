package org.tenshu.core.common.epc

/**
 * App-attestation seam — whitepaper *External Proxy Connection* **measure 2**
 * ("the proxy verifies the app is genuine and unmodified"), Phase 4 of
 * `docs/proposals/ios-proxy-architecture.md`.
 *
 * Measure 2 runs *after* the measure-1 handshake (Phase 2/3), over the
 * established E2E channel: the proxy issues a fresh challenge, the app returns an
 * attestation, the proxy verifies it before exposing anything. The two sides of
 * that exchange are [AppAttestor] (app) and [AppAttestVerifier] (proxy); the
 * channel choreography is [EpcAttestationClient] / [EpcAttestationServer].
 *
 * The interfaces are platform-agnostic and carry **opaque** attestation bytes,
 * so the real Apple App Attest implementation (CBOR attestation object + X.509
 * chain to Apple's App Attest root + assertion signatures) drops in as one
 * implementation pair without touching the protocol — it lands with the iOS app
 * (Phase 6), where real `DCAppAttestService` objects and Apple sample vectors
 * exist to test against. The cross-platform [SoftwareAppAttestor] /
 * [SoftwareAppAttestVerifier] reference pair proves the flow now.
 */

/** Produces an attestation over a proxy-issued challenge (the app side, measure 2). */
fun interface AppAttestor {
    /** Attest [challenge]; the returned bytes are opaque to the channel, interpreted by the verifier. */
    fun attest(challenge: ByteArray): ByteArray
}

/** Verifies an app's attestation against the challenge it was issued (the proxy side, measure 2). */
fun interface AppAttestVerifier {
    fun verify(challenge: ByteArray, attestation: ByteArray): AppAttestResult
}

/** Outcome of [AppAttestVerifier.verify]. */
sealed interface AppAttestResult {
    /**
     * The app is attested. [keyId] is a stable identifier for the attested key
     * (the real verifier returns the App Attest key id; the reference returns a
     * hash of the attestation public key) so the proxy can pin key continuity
     * across reconnections.
     */
    data class Attested(val keyId: String) : AppAttestResult

    /** Attestation failed; [reason] is a short, non-sensitive diagnostic. */
    data class Rejected(val reason: String) : AppAttestResult
}
