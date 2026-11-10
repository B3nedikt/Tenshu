package app.tenshu.windows

/**
 * What the OS says about the process on the far end of an accepted AF_UNIX socket, as read by
 * [WindowsPeerProcess] while it held a handle to that process. Plain data — no FFM types — so the
 * decision below is testable on every host.
 */
data class WindowsPeerFacts(
    val pid: Long,
    /** Epoch millis the process was created. */
    val createdAtMillis: Long,
    /** Whether the process was still running while its handle was held. */
    val alive: Boolean,
    /** `Name_PublisherId`, or `null` for a process without package identity (anything not MSIX). */
    val packageFamilyName: String?,
    /** The running image's full path, or `null` if it could not be read. Logged; W6 gates on it. */
    val imagePath: String?,
)

/** Which publishers may vouch for a package name — the Windows twin of macOS `PeerTeamPolicy`. */
fun interface PeerPublisherPolicy {
    /** The Store publisher IDs (the 13-character hash in a package family name) trusted for [packageName]. */
    fun acceptedPublishersFor(packageName: String): Set<String>
}

sealed interface WindowsPeerAttestation {
    /** The peer is our package [packageName], published by [publisherId]. */
    data class Attested(val packageName: String, val publisherId: String) : WindowsPeerAttestation

    /** The peer could not be tied to one of our packages. [reason] is for the log, never for logic. */
    data class Unattested(val reason: String) : WindowsPeerAttestation
}

/**
 * The Windows peer-gate decision (`docs/proposals/desktop-windows-port.md` §3.2, D1).
 *
 * ⚠⚠ **Identity is the package family name, never a signer.** A Store package is re-signed by
 * Microsoft, and its files live in the OS-protected `WindowsApps` folder with integrity enforced —
 * so "this process belongs to package X" is both an identity and an integrity claim. An Authenticode
 * signature alone is neither (§4.1: a per-user install's jars are user-writable). The side channel's
 * signer + `%ProgramFiles%` arm is W6 and deliberately absent here: until then a process without
 * package identity is unattested, however it is signed.
 *
 * ⚠⚠ **The pid race.** Windows has no audit token: the gate learns a pid and opens it, and a pid is
 * reusable. [WindowsPeerProcess] holds the handle for the whole read, which stops reuse *during* the
 * check; this function rejects a process **created after [verifyStartedAtMillis]** — the real peer
 * connected before the server accepted, so it necessarily existed before verification began. What
 * remains is a peer that exits, and a pid recycled, inside the gap between its connect and the start
 * of verification (threat model T-C-11).
 */
object WindowsPeerDecision {

    /** A package family name's publisher half: 13 characters of Crockford-style base32. */
    private val PUBLISHER_ID = Regex("^[0-9a-hjkmnp-tv-z]{13}$")

    fun decide(
        facts: WindowsPeerFacts,
        verifyStartedAtMillis: Long,
        policy: PeerPublisherPolicy,
    ): WindowsPeerAttestation {
        if (!facts.alive) return unattested("peer pid ${facts.pid} has exited", facts)
        if (facts.createdAtMillis > verifyStartedAtMillis) {
            return unattested(
                "peer pid ${facts.pid} was created after the connection was accepted — a recycled pid",
                facts,
            )
        }
        val family = facts.packageFamilyName
            ?: return unattested("peer has no package identity (not an MSIX)", facts)
        val split = family.lastIndexOf('_')
        if (split <= 0) return unattested("malformed package family name '$family'", facts)
        val name = family.substring(0, split)
        val publisher = family.substring(split + 1)
        if (!PUBLISHER_ID.matches(publisher)) return unattested("malformed publisher id in '$family'", facts)
        if (publisher !in policy.acceptedPublishersFor(name)) {
            return unattested("package '$name' is not one of ours from publisher '$publisher'", facts)
        }
        return WindowsPeerAttestation.Attested(packageName = name, publisherId = publisher)
    }

    private fun unattested(reason: String, facts: WindowsPeerFacts) =
        WindowsPeerAttestation.Unattested("$reason (image ${facts.imagePath ?: "unknown"})")
}
