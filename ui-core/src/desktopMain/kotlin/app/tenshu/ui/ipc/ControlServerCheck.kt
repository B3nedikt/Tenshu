package app.tenshu.ui.ipc

import app.tenshu.macos.MacPeerAttestation
import app.tenshu.macos.PeerAttestation
import app.tenshu.macos.PeerTeamPolicy
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.transport.PeerCredential
import org.tenshu.core.common.transport.PeerCredentialVerifier
import org.tenshu.core.common.transport.UnixSocketTaskTransport

/**
 * The client side of the desktop IPC gate (threat model A14; `desktop-ipc-client-identity.md` §11):
 * every desktop app checks that the process serving `ipc.sock` is Tenshu Control, signed by one of
 * our teams, before it sends a byte.
 *
 * Control already checks each client (`AttestedPeerCredentialVerifier`). Without this direction a
 * substituted Control (a replaced `.dmg`, threat model T-MD-01, or any process that bound the socket
 * first) is served by every genuine app on the Mac.
 *
 * The cryptography is [MacPeerAttestation], the same code the server side runs: the peer's audit
 * token, then `SecCodeCheckValidity` against `anchor apple generic` + our team + this identifier, or
 * the Mac App Store route for a store copy. On a connected client socket, `LOCAL_PEERTOKEN` names the
 * process that listened, which is Control's own launcher.
 */
object ControlServerCheck {

    /** Control's bundle identifier on macOS (C4): the identifier its code signature carries. */
    const val CONTROL_IDENTIFIER = "app.tenshu.control.ios"

    /** The policy: only [CONTROL_IDENTIFIER] is vouched for, and only by [teams]. */
    fun policy(teams: Set<String>): PeerTeamPolicy =
        PeerTeamPolicy { identifier -> if (identifier == CONTROL_IDENTIFIER) teams else emptySet() }

    /** The verifier for [UnixSocketTaskTransport]'s `serverVerifier`. */
    fun verifier(teams: Set<String> = ClientPeerTrust.FIRST_PARTY_TEAM_IDS): PeerCredentialVerifier {
        val policy = policy(teams)
        return PeerCredentialVerifier { channel ->
            when (val outcome = MacPeerAttestation.attest(channel, policy)) {
                is PeerAttestation.Attested -> PeerCredential(
                    verified = true,
                    appId = outcome.identifier,
                    reason = "server attested as ${outcome.identifier} (team ${outcome.teamId})",
                )
                is PeerAttestation.Unattested -> PeerCredential(
                    verified = false,
                    appId = null,
                    reason = "the server is not Tenshu Control: ${outcome.reason}",
                )
            }
        }
    }

    /**
     * A16 developer mode, from the flag each app's dev `run` task sets (`-Dtenshu.release=false`),
     * as on Control's side. It lets a dev run reach a dev-run Control, an unsigned `java` process.
     *
     * ⚠⚠ Never inside a packaged app, whatever the flag says: `JAVA_TOOL_OPTIONS` can put any `-D`
     * into a packaged JVM. The jpackage launcher sets [JPACKAGE_APP_PATH] on its own command line,
     * which an injected option cannot remove, so its presence means "packaged" and enforces.
     */
    fun developerMode(): Boolean =
        System.getProperty("tenshu.release") == "false" && System.getProperty(JPACKAGE_APP_PATH) == null

    /** Set by every jpackage launcher (Compose Desktop's packaged apps) to the app's executable. */
    const val JPACKAGE_APP_PATH = "jpackage.app-path"

    /** The transport every desktop app uses to reach Control. */
    fun transport(parser: BinaryParser): UnixSocketTaskTransport =
        UnixSocketTaskTransport(parser, serverVerifier = verifier(), serverDeveloperMode = developerMode())
}
