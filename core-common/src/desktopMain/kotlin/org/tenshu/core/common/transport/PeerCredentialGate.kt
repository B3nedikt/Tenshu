package org.tenshu.core.common.transport

import java.nio.channels.SocketChannel

/**
 * The desktop IPC peer-credential gate (threat-model A14 / A20, `desktop-ipc.md`
 * §3). Every connection accepted by the binary [UnixSocketServer] and the JSON
 * [org.tenshu.core.common.proxy.NativeBridgeServer] is run through this gate before
 * it is served.
 *
 * Today the gate is wired but **does not yet reject** anyone: the real check needs
 * native peer-credential access (`LOCAL_PEERPID` → binary path → code-signing
 * identity) that the JVM does not expose on macOS, so [UnverifiedPeerCredentialVerifier]
 * returns an *unverified* credential and the default [PeerCredentialPolicy] is
 * non-strict (admit all). This makes the **enforcement point** exist as a single,
 * tested chokepoint — flipping it on is a real [PeerCredentialVerifier] plus
 * `PeerCredentialPolicy(strict = true)`, mirroring how `AppRegistration` plumbed
 * `verified = false` so the eventual A14 check is a drop-in. See threat-model
 * **T-C-15** for the residual-risk treatment.
 */

/**
 * A connecting IPC peer's resolved process credential. [verified] is true only when
 * the peer's binary was resolved **and** its code-signing identity matched a known
 * Tenshu publication; until the native resolver lands it is always false on desktop.
 */
data class PeerCredential(
    val verified: Boolean,
    /** Resolved app identity (e.g. the signed bundle id), or null when unresolved. */
    val appId: String? = null,
    /** Human-readable explanation, surfaced in the admit log / a rejection reason. */
    val reason: String,
)

/**
 * Resolves the credential of a freshly-accepted IPC connection. This is the single
 * seam the real A14 check drops into: implement [verify] with native peer-PID +
 * code-signing resolution and the gate already enforces it everywhere.
 */
fun interface PeerCredentialVerifier {
    fun verify(channel: SocketChannel): PeerCredential
}

/**
 * Default desktop verifier: returns an unverified credential because the native
 * peer-credential lookup is not implemented yet (T-C-15). It neither admits nor
 * rejects — that is [PeerCredentialPolicy]'s decision.
 */
object UnverifiedPeerCredentialVerifier : PeerCredentialVerifier {
    override fun verify(channel: SocketChannel): PeerCredential =
        PeerCredential(
            verified = false,
            reason = "desktop peer-credential check not implemented (threat-model T-C-15)",
        )
}

/** The admit/reject outcome of the gate for one connection. */
data class PeerAdmission(val admit: Boolean, val warn: Boolean, val reason: String)

/**
 * The single place the IPC enforcement posture is defined. Given a peer's
 * [PeerCredential] and whether developer mode (A16) is active, decides whether to
 * admit the connection:
 *
 *  - **[strict] = false (today):** admit every peer. The real verifier isn't built,
 *    so the gate point exists but does not reject (T-C-15). Quiet — no per-connection
 *    log, since every bridge call is a fresh connection.
 *  - **[strict] = true (once a real [PeerCredentialVerifier] lands):** an unverified
 *    peer is **rejected**, unless developer mode (A16) downgrades the hard-fail to an
 *    admit-with-warning so unsigned dev builds can still connect.
 */
class PeerCredentialPolicy(private val strict: Boolean = false) {
    fun decide(credential: PeerCredential, developerMode: Boolean = false): PeerAdmission = when {
        credential.verified ->
            PeerAdmission(admit = true, warn = false, reason = "verified peer (${credential.appId ?: "unknown app"})")
        !strict ->
            // Documented posture, not an incident — stay quiet to avoid per-call spam.
            PeerAdmission(admit = true, warn = false, reason = "enforcement not active — ${credential.reason}")
        developerMode ->
            PeerAdmission(admit = true, warn = true, reason = "admitted under developer mode (A16) — ${credential.reason}")
        else ->
            PeerAdmission(admit = false, warn = true, reason = "rejected — ${credential.reason}")
    }
}

/**
 * Runs the gate on a freshly-accepted [channel]: resolves the credential, applies
 * [policy], logs a notable decision, and **closes the channel on reject**.
 *
 * Returns the resolved [PeerCredential] to proceed with, or `null` if the peer was
 * rejected. The credential is returned rather than a bare boolean because the
 * caller needs [PeerCredential.appId] — that *is* the peer's identity, and
 * re-verifying to obtain it would mean checking a second time (and, worse, a peer
 * could exit between the two checks and change the answer). One verification, one
 * answer, used for both the admit decision and the session identity.
 */
fun PeerCredentialVerifier.admit(
    channel: SocketChannel,
    policy: PeerCredentialPolicy,
    developerMode: Boolean = false,
    log: (String) -> Unit = { System.err.println(it) },
): PeerCredential? {
    val credential = verify(channel)
    val admission = policy.decide(credential, developerMode)
    if (admission.warn) log("[ipc] peer-credential gate: ${admission.reason}")
    if (!admission.admit) {
        runCatching { channel.close() }
        return null
    }
    return credential
}
