package app.tenshu.control.ipc

import app.tenshu.macos.MacPeerAttestation
import app.tenshu.macos.PeerAttestation
import app.tenshu.control.data.FirstPartyDesktopPeers
import app.tenshu.macos.PeerTeamPolicy
import org.tenshu.core.common.transport.PeerCredential
import org.tenshu.core.common.transport.PeerCredentialVerifier
import java.nio.channels.SocketChannel

/**
 * The desktop IPC gate's real verifier: resolves a connecting peer's identity from its
 * **code signature** rather than from anything the peer says
 * (`desktop-ipc-client-identity.md` P1).
 *
 * This is the adapter half. [MacPeerAttestation] owns the cryptography and lives in
 * `:macos-helpers` — a Compose-free, stdlib-only JVM module that already compiles against a
 * final-FFM JDK. Keeping it there is what lets `:core-common` supply the
 * [PeerCredentialVerifier] seam without gaining a toolchain requirement that would ripple
 * into its Android and iOS targets.
 *
 * ### ⚠⚠ This is ENFORCING (P3) — it was observe-only only through P1
 *
 * `Main.kt` builds `PeerCredentialPolicy(strict = true)` and wires it into both the Unix-socket
 * and native-bridge servers: a peer whose running code does not satisfy a requirement is
 * rejected and its channel closed. [PeerCredential.appId] is also load-bearing now — it is the
 * session identity (`UnixSocketServer` falls back to `unattested-<uuid>` without it), and
 * `AppRegistration.verified` reads it through `PeerAttestationProbe`. The per-connection log
 * remains, but it is no longer the only deliverable.
 */
internal class AttestedPeerCredentialVerifier(
    private val policy: PeerTeamPolicy,
    private val log: (String) -> Unit = {},
) : PeerCredentialVerifier {

    override fun verify(channel: SocketChannel): PeerCredential =
        when (val outcome = MacPeerAttestation.attest(channel, policy)) {
            is PeerAttestation.Attested -> {
                log("[ipc] peer attested: ${outcome.identifier} (team ${outcome.teamId})")
                PeerCredential(
                    verified = true,
                    appId = outcome.identifier,
                    reason = "attested as ${outcome.identifier} (team ${outcome.teamId})",
                )
            }

            is PeerAttestation.Unattested -> {
                log("[ipc] peer unattested: ${outcome.reason}")
                // ⚠ appId stays null — failing closed into *anonymous* is correct; failing
                // closed into a fallback identity is the bug this whole track exists to fix.
                PeerCredential(verified = false, appId = null, reason = outcome.reason)
            }
        }
}

/**
 * The first-party trust policy: which of our Team IDs may vouch for a given identifier.
 *
 * ⚠ **A set of teams, not one.** Moving Tenshu to a different Apple team (a company entity
 * rather than a personal one) would otherwise be a flag day — every requirement changes at once,
 * re-keying each user's login-Keychain ACL and TCC grants with no transition window. With a set,
 * a migration is additive: ship a build accepting `{old, new}`, roll the fleet, drop `old`.
 *
 * ⚠ **Narrowed to identifiers we actually ship** (`control-app-catalogue.md` C2). It previously
 * returned our teams for *any* identifier, which was not an impersonation hole — a binary's
 * identifier is fixed by its signature — but did mean an identifier we had never heard of was
 * admitted, gained full proxy access, and never registered (the allowlist gates *registration*,
 * not *access*). Unconstrained and invisible at once, which is strictly worse than rejected.
 *
 * ⚠⚠ [FirstPartyDesktopPeers] covers apps **and helpers**. Control's own tray agent signs as
 * `tray-agent` and drives `bridge.sock`; deriving this from the app catalogue alone would reject
 * Control's own helper.
 *
 * When third-party publishers arrive this is the function that gains a second source — their
 * identifiers vouched for by *their* team, not ours.
 */
internal class FirstPartyTeamPolicy(
    private val teamIds: Set<String>,
    private val knownIdentifiers: Set<String> = FirstPartyDesktopPeers.IDENTIFIERS,
) : PeerTeamPolicy {

    override fun acceptedTeamsFor(identifier: String): Set<String> =
        if (identifier in knownIdentifiers) teamIds else emptySet()
}
