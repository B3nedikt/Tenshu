package app.tenshu.macos

import app.tenshu.macos.ffi.ObjC
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the gate **discriminates**, over a real socket, against real signed binaries.
 *
 * ⚠⚠ Both directions are asserted on purpose. A suite that only shows the imposter being
 * rejected passes just as happily against an implementation that rejects *everything* — the
 * exact trap a previous detector here fell into. So each signing case is paired: the ad-hoc
 * imposter must fail **and** the genuinely-signed peer claiming the identical identifier must
 * pass.
 *
 * The peers are C binaries built and signed at test time rather than checked in, because a
 * committed signature would expire, and because the whole point is that the *signature*, not
 * the bytes, decides.
 *
 * Self-skips unless macOS, a compiler, and a real signing identity are all present — the
 * genuine half cannot be faked, and asserting only the imposter half would reintroduce the
 * trap above.
 */
class MacPeerAttestationTest {

    data class SigningIdentity(val hash: String, val name: String)

    private val mac = ObjC.isAvailable
    private val identity: SigningIdentity? by lazy { firstCodesigningIdentity() }
    private val teamId: String? by lazy { identity?.let { teamIdOf(it.name) } }

    @Test
    fun requirementPinsAnchorTeamAndIdentifier() {
        // Pure string construction — runs everywhere, and guards the one line where a typo
        // would silently widen the gate.
        val requirement = MacPeerAttestation.requirementFor("ABCDE12345", "app.tenshu.drive")
        assertEquals(
            "anchor apple generic and certificate leaf[subject.OU] = \"ABCDE12345\" " +
                "and identifier \"app.tenshu.drive\"",
            requirement,
        )
    }

    @Test
    fun unknownIdentifierIsRejectedWithoutConsultingTheSignature() {
        if (!skipUnlessSignable()) return
        val outcome = attestPeer(signAdHoc = false, claimedIdentifier = "app.tenshu.drive") {
            // Policy vouches for nothing at all.
            PeerTeamPolicy { emptySet() }
        }
        assertTrue(
            outcome is PeerAttestation.Unattested,
            "an identifier no team vouches for must not be attested, got $outcome",
        )
    }

    @Test
    fun adHocImposterClaimingAFirstPartyIdentifierIsRejected() {
        if (!skipUnlessSignable()) return
        val team = teamId!!

        val outcome = attestPeer(signAdHoc = true, claimedIdentifier = "app.tenshu.drive") {
            PeerTeamPolicy { setOf(team) }
        }

        assertTrue(
            outcome is PeerAttestation.Unattested,
            "an ad-hoc binary claiming app.tenshu.drive must be rejected, got $outcome",
        )
        // ⚠ Assert *why*. A rejection caused by broken plumbing (missing --add-opens, no
        // linker) looks identical to a rejection caused by the team pin, so without this the
        // test would keep passing after the mechanism stopped working entirely — which is
        // exactly what happened the first time this suite ran.
        assertTrue(
            outcome.reason.contains("not signed by"),
            "must be rejected by the team pin, not by plumbing — got '${outcome.reason}'",
        )
    }

    @Test
    fun genuinelySignedPeerClaimingTheSameIdentifierIsAttested() {
        if (!skipUnlessSignable()) return
        val team = teamId!!

        val outcome = attestPeer(signAdHoc = false, claimedIdentifier = "app.tenshu.drive") {
            PeerTeamPolicy { setOf(team) }
        }

        assertTrue(
            outcome is PeerAttestation.Attested,
            "a peer signed by $team claiming app.tenshu.drive must be attested, got $outcome",
        )
        assertEquals("app.tenshu.drive", outcome.identifier)
        assertEquals(team, outcome.teamId)
    }

    @Test
    fun signedTeamIdentifierIsReadFromTheRunningPeer() {
        // The store route's team check reads kSecCodeInfoTeamIdentifier, which is absent
        // unless kSecCSSigningInformation is passed — a wrong flag would make that route
        // reject everything, and the fakes in MacPeerAttestationDecisionTest cannot see it.
        if (!skipUnlessSignable()) return
        val team = teamId!!

        assertEquals(team, withPeer(signAdHoc = false, "app.tenshu.drive") { MacPeerAttestation.signedTeamIdOf(it) })
        assertEquals(null, withPeer(signAdHoc = true, "app.tenshu.drive") { MacPeerAttestation.signedTeamIdOf(it) })
    }

    // ── the client direction: attesting the process that LISTENS ──────────────────────
    //
    // The desktop apps check Control (`ControlServerCheck`, desktop-ipc-client-identity.md §11).
    // On a connected *client* socket, LOCAL_PEERTOKEN must name the process that called listen();
    // these two prove it does, over a real socket, both ways round.

    @Test
    fun genuinelySignedServerIsAttestedFromTheClientSide() {
        if (!skipUnlessSignable()) return
        val team = teamId!!

        val outcome = withServer(signAdHoc = false, claimedIdentifier = "app.tenshu.control.ios") {
            MacPeerAttestation.attest(it, PeerTeamPolicy { id -> if (id == "app.tenshu.control.ios") setOf(team) else emptySet() })
        }

        assertTrue(outcome is PeerAttestation.Attested, "a server signed by $team claiming Control's identifier must be attested, got $outcome")
        assertEquals("app.tenshu.control.ios", outcome.identifier)
    }

    @Test
    fun adHocServerClaimingControlIsRejectedFromTheClientSide() {
        if (!skipUnlessSignable()) return
        val team = teamId!!

        val outcome = withServer(signAdHoc = true, claimedIdentifier = "app.tenshu.control.ios") {
            MacPeerAttestation.attest(it, PeerTeamPolicy { id -> if (id == "app.tenshu.control.ios") setOf(team) else emptySet() })
        }

        assertTrue(outcome is PeerAttestation.Unattested, "an ad-hoc server claiming Control must be rejected, got $outcome")
        assertTrue(outcome.reason.contains("not signed by"), "rejected by the team pin, not by plumbing — got '${outcome.reason}'")
    }

    /** Build + sign a tiny C server, connect to it from here, and hand over the client channel. */
    private fun <T> withServer(signAdHoc: Boolean, claimedIdentifier: String, onConnected: (SocketChannel) -> T): T {
        val dir = Files.createTempDirectory("tenshu-server-attest")
        val socket = dir.resolve("probe.sock")
        val binary = dir.resolve("server")
        try {
            val source = dir.resolve("server.c")
            Files.writeString(
                source,
                """
                #include <sys/socket.h>
                #include <sys/un.h>
                #include <unistd.h>
                #include <string.h>
                int main(int argc, char** argv) {
                    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
                    struct sockaddr_un a; memset(&a, 0, sizeof(a));
                    a.sun_family = AF_UNIX;
                    strncpy(a.sun_path, argv[1], sizeof(a.sun_path) - 1);
                    if (bind(fd, (struct sockaddr*)&a, sizeof(a)) != 0) return 1;
                    if (listen(fd, 1) != 0) return 2;
                    int c = accept(fd, 0, 0);
                    sleep(30);
                    return c < 0 ? 3 : 0;
                }
                """.trimIndent(),
            )
            run("cc", "-o", binary.toString(), source.toString())
            val signer = if (signAdHoc) "-" else identity!!.hash
            run("codesign", "--force", "-s", signer, "-i", claimedIdentifier, binary.toString())

            val server = ProcessBuilder(binary.toString(), socket.toString()).start()
            try {
                // The file appears at bind(), before listen(): retry until a connect is accepted.
                val deadline = System.currentTimeMillis() + 10_000
                while (true) {
                    val client = SocketChannel.open(StandardProtocolFamily.UNIX)
                    val connected = runCatching { client.connect(UnixDomainSocketAddress.of(socket)) }.isSuccess
                    if (connected) client.use { return onConnected(it) }
                    client.close()
                    check(System.currentTimeMillis() < deadline) { "the signed test server never accepted a connection" }
                    Thread.sleep(20)
                }
            } finally {
                server.destroyForcibly()
                server.waitFor()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ── harness ───────────────────────────────────────────────────────────────────

    /**
     * Build + sign a tiny C client, have it connect, and attest it from the accepting side.
     */
    private fun attestPeer(
        signAdHoc: Boolean,
        claimedIdentifier: String,
        policy: () -> PeerTeamPolicy,
    ): PeerAttestation = withPeer(signAdHoc, claimedIdentifier) { MacPeerAttestation.attest(it, policy()) }

    private fun <T> withPeer(
        signAdHoc: Boolean,
        claimedIdentifier: String,
        onAccepted: (SocketChannel) -> T,
    ): T {
        val dir = Files.createTempDirectory("tenshu-peer-attest")
        val socket = dir.resolve("probe.sock")
        val binary = dir.resolve("peer")
        try {
            buildPeerBinary(dir, binary)
            val signer = if (signAdHoc) "-" else identity!!.hash
            run("codesign", "--force", "-s", signer, "-i", claimedIdentifier, binary.toString())

            val address = UnixDomainSocketAddress.of(socket)
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                server.bind(address)
                val peer = ProcessBuilder(binary.toString(), socket.toString()).start()
                try {
                    server.accept().use { accepted: SocketChannel ->
                        return onAccepted(accepted)
                    }
                } finally {
                    peer.destroyForcibly()
                    peer.waitFor()
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun buildPeerBinary(dir: Path, out: Path) {
        val source = dir.resolve("peer.c")
        Files.writeString(
            source,
            """
            #include <sys/socket.h>
            #include <sys/un.h>
            #include <unistd.h>
            #include <string.h>
            int main(int argc, char** argv) {
                int fd = socket(AF_UNIX, SOCK_STREAM, 0);
                struct sockaddr_un a; memset(&a, 0, sizeof(a));
                a.sun_family = AF_UNIX;
                strncpy(a.sun_path, argv[1], sizeof(a.sun_path) - 1);
                if (connect(fd, (struct sockaddr*)&a, sizeof(a)) != 0) return 1;
                sleep(30);
                return 0;
            }
            """.trimIndent(),
        )
        run("cc", "-o", out.toString(), source.toString())
    }

    /** True when the environment can run the signing cases; false ⇒ the test self-skips. */
    private fun skipUnlessSignable(): Boolean =
        mac && identity != null && teamId != null && commandExists("cc")

    private fun firstCodesigningIdentity(): SigningIdentity? {
        if (!mac) return null
        val output = runCatching { capture("security", "find-identity", "-v", "-p", "codesigning") }
            .getOrNull() ?: return null
        // Lines look like:  1) <sha1> "Apple Development: someone (XXXXXXXXXX)"
        val match = Regex("\\s*[0-9]+\\)\\s+([0-9A-F]{40})\\s+\"([^\"]+)\"").find(output) ?: return null
        return SigningIdentity(match.groupValues[1], match.groupValues[2])
    }

    /** The Team ID is the certificate subject's **OU** — not the CN's parenthesised suffix. */
    private fun teamIdOf(identity: String): String? {
        val pem = runCatching { capture("security", "find-certificate", "-c", identity, "-p") }
            .getOrNull() ?: return null
        val subject = runCatching {
            capture(input = pem, command = arrayOf("openssl", "x509", "-noout", "-subject"))
        }.getOrNull() ?: return null
        return Regex("OU\\s*=\\s*([A-Z0-9]+)").find(subject)?.groupValues?.get(1)
    }

    private fun commandExists(name: String): Boolean =
        runCatching { ProcessBuilder("which", name).start().waitFor() == 0 }.getOrDefault(false)

    private fun run(vararg command: String) {
        val exit = ProcessBuilder(*command).redirectErrorStream(true).start().waitFor()
        check(exit == 0) { "${command.joinToString(" ")} failed with $exit" }
    }

    private fun capture(vararg command: String): String =
        ProcessBuilder(*command).redirectErrorStream(true).start()
            .inputStream.bufferedReader().readText()

    private fun capture(input: String, command: Array<String>): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(input) }
        return process.inputStream.bufferedReader().readText()
    }
}
