package app.tenshu.control.ipc

import app.tenshu.control.data.FirstPartyDesktopPeers
import app.tenshu.windows.PeerPublisherPolicy
import app.tenshu.windows.WindowsPeerAttestation
import app.tenshu.windows.WindowsPeerDecision
import app.tenshu.windows.WindowsPeerProcess
import org.tenshu.core.common.transport.PeerCredential
import org.tenshu.core.common.transport.PeerCredentialVerifier
import java.nio.channels.SocketChannel

/**
 * The Windows twin of [AttestedPeerCredentialVerifier]: a connecting peer's identity comes from the
 * **package identity of the process holding the socket**, never from anything the peer says
 * (`docs/proposals/desktop-windows-port.md` §3.2, D1).
 *
 * [WindowsPeerProcess] reads the facts while holding a handle to the peer process;
 * [WindowsPeerDecision] decides. The attested [PeerCredential.appId] is the package **Name** — the
 * `clientId` of a `ClientBinding.WindowsPackage` — so registration resolves it through the same
 * catalogue as every other platform.
 *
 * ⚠ The verification clock starts **before** the native read: [WindowsPeerDecision] rejects a
 * process created after that instant as a recycled pid, so taking the time afterwards would widen
 * the window it closes.
 */
internal class WindowsPeerCredentialVerifier(
    private val policy: PeerPublisherPolicy,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) : PeerCredentialVerifier {

    override fun verify(channel: SocketChannel): PeerCredential {
        val startedAt = now()
        val outcome = when (val read = WindowsPeerProcess.read(channel)) {
            is WindowsPeerProcess.Result.Unavailable -> WindowsPeerAttestation.Unattested(read.reason)
            is WindowsPeerProcess.Result.Facts -> WindowsPeerDecision.decide(read.facts, startedAt, policy)
        }
        return when (outcome) {
            is WindowsPeerAttestation.Attested -> {
                log("[ipc] peer attested: ${outcome.packageName} (publisher ${outcome.publisherId})")
                PeerCredential(
                    verified = true,
                    appId = outcome.packageName,
                    reason = "attested as ${outcome.packageName} (publisher ${outcome.publisherId})",
                )
            }
            is WindowsPeerAttestation.Unattested -> {
                log("[ipc] peer unattested: ${outcome.reason}")
                // ⚠ appId stays null: fail closed into anonymous, never into a fallback identity.
                PeerCredential(verified = false, appId = null, reason = outcome.reason)
            }
        }
    }
}

/**
 * Which publishers may vouch for which of our package names — the Windows [FirstPartyTeamPolicy].
 * Narrowed to names we ship, so a package of ours we never declared is unattested rather than
 * admitted-and-never-registered (`control-app-catalogue.md` C2).
 */
internal class FirstPartyPublisherPolicy(
    private val publisherIds: Set<String>,
    private val knownPackages: Set<String> = FirstPartyDesktopPeers.WINDOWS_PACKAGES,
) : PeerPublisherPolicy {

    override fun acceptedPublishersFor(packageName: String): Set<String> =
        if (packageName in knownPackages) publisherIds else emptySet()
}
