package org.tenshu.core.common.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.tenshu.core.common.TestProxy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `desktop-ipc.md` §3 claimed the socket directory "is created with `700` permissions" and
 * nothing in the code ever set it (2026-08-21 IPC audit item #4). These pin both halves: the
 * daemon tightening the directory, and a client's reading of one it did not tighten.
 */
class SocketDirectoryTest {

    private val roots = mutableListOf<Path>()

    @AfterTest
    fun cleanUp() {
        roots.forEach { runCatching { Files.walk(it).sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }

    /**
     * A temp root **short enough to bind a Unix socket under**.
     *
     * ⚠⚠⚠ **This is a real flake fix, not tidying.** `Files.createTempDirectory` puts its
     * directory under `$TMPDIR`, which on macOS is a ~48-character
     * `/var/folders/<2>/<28>/T/` path, and appends a random `Long` whose **digit count varies from
     * 10 to 20**. With the old `"tenshu-socket-dir"` prefix plus `/.tenshu/ipc.sock`, the bound
     * path landed between 93 and 103 bytes depending on that random suffix — and the usable
     * `sun_path` maximum on macOS is **102, measured**. So the bind failed whenever the suffix came
     * out long: roughly one run in three, in a test that looks entirely deterministic.
     *
     * ⚠ `/tmp` rather than `$TMPDIR` because the length is the whole point — it is 4 characters
     * against 48, which buys enough headroom that the random suffix cannot matter. ⚠ The one-letter
     * prefix is deliberate for the same reason; every character here is budget.
     *
     * ⚠ Still cleaned up in [cleanUp] — a short path is not a licence to litter.
     */
    private fun tempRoot(): Path =
        Files.createTempDirectory(Path.of("/tmp"), "t").also { roots.add(it) }

    /**
     * ⚠ Well under the measured 102-byte macOS maximum — the margin absorbs `createTempDirectory`'s
     * variable-length random suffix, which is what made the original failure intermittent.
     */
    private val SAFE_SOCKET_PATH_BYTES = 80

    /**
     * The `sun_path` maximum [UnixSocketServer]'s bind-failure message will name **on this OS**.
     *
     * ⚠⚠ **Both numbers and the OS check are spelled twice on purpose** — `MAC_SUN_PATH_MAX` /
     * `LINUX_SUN_PATH_MAX` are `private` in `UnixSocketServer.kt`, and the message they produce is
     * the whole subject of [aPathTooLongToBindSaysSoInsteadOfJustFailing]. **They must move
     * together**: changing the production constant alone leaves this test green against a message
     * that names the wrong limit, which is precisely the failure it exists to catch. The OS check
     * mirrors `bindFailureMessage` character for character for the same reason — a different
     * spelling of "is this macOS" would assert the wrong arm on some host.
     *
     * ⚠ Hardcoding 102 here is what made this test red on every Linux box: macOS's 102 is measured
     * (103 fails to bind, 102 succeeds) while Linux's 106 is the conservative NUL-adjusted value for
     * its declared `sun_path[108]`, so there is no single correct number to assert.
     */
    private val EXPECTED_SUN_PATH_MAX: Int =
        if (System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)) 102 else 106

    private fun mode(dir: Path): String =
        PosixFilePermissions.toString(Files.getPosixFilePermissions(dir))

    @Test
    fun prepare_createsTheDirectoryOwnerOnly() {
        val dir = tempRoot().resolve("nested").resolve(".tenshu")

        SocketDirectory.prepare(dir) {}

        assertTrue(Files.isDirectory(dir))
        assertEquals("rwx------", mode(dir))
    }

    /**
     * ⚠ The case that actually matters in the field. Correct creation code does nothing for the
     * `~/.tenshu` an earlier build already made `0755` — and that directory is on every machine
     * the old code ever ran on.
     */
    @Test
    fun prepare_repairsAnAlreadyWorldTraversableDirectory() {
        val dir = tempRoot().resolve(".tenshu")
        Files.createDirectories(dir)
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"))

        val logged = mutableListOf<String>()
        SocketDirectory.prepare(dir) { logged += it }

        assertEquals("rwx------", mode(dir))
        assertTrue(logged.any { it.contains("tightened") }, "a repair should say so: $logged")
    }

    @Test
    fun prepare_isQuietWhenTheDirectoryIsAlreadySound() {
        val dir = tempRoot().resolve(".tenshu")
        Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))

        val logged = mutableListOf<String>()
        SocketDirectory.prepare(dir) { logged += it }

        assertEquals("rwx------", mode(dir))
        assertTrue(logged.isEmpty(), "nothing to report, but logged: $logged")
    }

    @Test
    fun inspect_callsAnOwnerOnlyDirectorySound() {
        val dir = tempRoot().resolve(".tenshu")
        Files.createDirectories(dir)
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))

        assertEquals(SocketDirectory.Verdict.Sound, SocketDirectory.inspect(dir))
    }

    /**
     * Write permission for anyone else is the squatting vector — they can bind the socket path
     * before the daemon does and be handed every request the client makes.
     */
    @Test
    fun inspect_refusesADirectoryAnotherUserCanWriteTo() {
        val dir = tempRoot().resolve(".tenshu")
        Files.createDirectories(dir)
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxr-x"))

        assertIs<SocketDirectory.Verdict.Squattable>(SocketDirectory.inspect(dir))
    }

    @Test
    fun inspect_refusesAWorldWritableDirectory() {
        val dir = tempRoot().resolve(".tenshu")
        Files.createDirectories(dir)
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xrwx"))

        assertIs<SocketDirectory.Verdict.Squattable>(SocketDirectory.inspect(dir))
    }

    /**
     * ⚠ Traversable-but-not-writable warns rather than refuses, deliberately: the exposure is
     * real but lesser (the peer gate still stands in front of the proxy), and a refusal here
     * would strand every user whose directory an older build left `0755` until they happened to
     * start the daemon — turning a hardening change into an outage.
     */
    @Test
    fun inspect_warnsButDoesNotRefuseAMerelyTraversableDirectory() {
        val dir = tempRoot().resolve(".tenshu")
        Files.createDirectories(dir)
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"))

        assertIs<SocketDirectory.Verdict.Reachable>(SocketDirectory.inspect(dir))
    }

    /**
     * ⚠⚠ The sticky bit is what separates "/tmp" from "misconfigured", and without this case the
     * check rejects a working configuration: a shared temp directory is world-writable by design
     * and is where every test fixture and `TENSHU_IPC_DIR` dev run puts its socket. This is the
     * case that turned `DesktopIpcIntegrationTest` red when the refusal keyed on the mode alone.
     */
    @Test
    fun inspect_downgradesAStickySharedDirectoryToAWarning() {
        val dir = tempRoot().resolve("shared")
        Files.createDirectories(dir)
        // ⚠ The JDK cannot set the sticky bit — `PosixFilePermissions` does not model it — so
        // this shells out. And it builds the condition rather than reading `java.io.tmpdir`,
        // which is `drwxrwxrwt` on a normal box but not in every container (this one ships
        // `/tmp` as `drwx------`), so asserting against the ambient temp dir tests the
        // environment rather than the code.
        val chmod = runCatching {
            ProcessBuilder("chmod", "1777", dir.toString()).start().waitFor()
        }.getOrNull()
        if (chmod != 0 || !isSticky(dir)) return // no POSIX chmod here; nothing to assert

        assertIs<SocketDirectory.Verdict.Reachable>(
            SocketDirectory.inspect(dir),
            "a world-writable but STICKY directory (i.e. /tmp) must warn, not refuse",
        )
    }

    /** The same `01000` read [SocketDirectory] uses, so the test's premise matches its subject. */
    private fun isSticky(dir: Path): Boolean =
        runCatching { (Files.getAttribute(dir, "unix:mode") as Int) and 0b1_000_000_000 != 0 }
            .getOrDefault(false)

    /**
     * ⚠ The counterpart, and the one that keeps the sticky carve-out honest: world-writable
     * *without* the sticky bit is a genuinely broken configuration, and must still be refused.
     */
    @Test
    fun inspect_stillRefusesAWorldWritableDirectoryWithoutTheStickyBit() {
        val dir = tempRoot().resolve("open")
        Files.createDirectories(dir)
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx"))

        assertIs<SocketDirectory.Verdict.Squattable>(SocketDirectory.inspect(dir))
    }

    /**
     * The wiring, not the helper: a correct `prepare` that nothing calls is the bug this whole
     * item is about (`desktop-ipc.md` §3 has described `0700` since long before any code set it).
     */
    /**
     * ⚠⚠ **The guard on the flake fix, and the reason it is an assertion rather than a comment.**
     * Every socket this suite binds must sit *well* under macOS's measured 102-byte `sun_path`
     * maximum. Reverting [tempRoot] to `$TMPDIR` puts the path between 93 and 103 bytes depending
     * on a random suffix — which fails about one run in three and looks like anything but a path
     * length. This turns that into a deterministic failure that names the cause.
     *
     * ⚠ The margin is asserted, not just the limit: a path that merely *fits today* is one rename
     * away from flaking again.
     */
    @Test
    fun everySocketPathThisSuiteBindsHasRoomToSpare() {
        val path = tempRoot().resolve(".tenshu").resolve("ipc.sock").toString()
        val bytes = path.encodeToByteArray().size
        assertTrue(
            bytes <= SAFE_SOCKET_PATH_BYTES,
            "socket path is $bytes bytes ($path) — macOS binds at most 102, and this suite keeps " +
                "a margin so a longer temp suffix cannot reintroduce the flake",
        )
    }

    /**
     * ⚠⚠ **The half that matters off this machine.** A path that will not fit `sun_path` is refused
     * by the OS with an error that says nothing about length, and the message this used to throw —
     * a bare "could not bind <path>" — sends a reader to permissions, stale sockets and SIP, none
     * of which are the problem. It is not a test-only concern: the production path is
     * `<home>/.tenshu/ipc.sock`, so a long username or a containerised home reaches the limit on a
     * real machine, where the symptom is a daemon that will not start.
     */
    @Test
    fun aPathTooLongToBindSaysSoInsteadOfJustFailing() {
        // ⚠ 120 bytes: comfortably past *both* limits (macOS's measured 102 and Linux's 106), so the
        // bind fails for the one reason this test is about on either OS. Built rather than hardcoded
        // so the test does not depend on this machine's temp root being any particular length.
        val root = tempRoot()
        val padding = "x".repeat(120 - (root.toString().length + "/".length + "/ipc.sock".length))
        val dir = root.resolve(padding)
        Files.createDirectories(dir)
        val socket = dir.resolve("ipc.sock")
        val proxy = TestProxy(coroutineScope = CoroutineScope(Dispatchers.Default))

        val failure = assertFailsWith<IllegalStateException> {
            UnixSocketServer(proxy.gateway, proxy.serializer, socket.toString()).start()
        }

        val message = failure.message.orEmpty()
        // ⚠⚠ The NUMBER and the CAUSE, not just a hint: "may be at most 106" is what turns this
        // from an afternoon into a minute, and asserting only that the word "path" appears would
        // pass against the old message too. ⚠ The number is asserted *joined to the phrase* rather
        // than on its own, so a message that happened to contain the digits elsewhere cannot pass.
        assertTrue(message.contains("at most"), message)
        assertTrue(message.contains("at most $EXPECTED_SUN_PATH_MAX"), message)
        assertTrue(message.contains("120 bytes"), message)
    }

    @Test
    fun startingTheServer_tightensTheDirectoryItBindsIn() {
        val dir = tempRoot().resolve(".tenshu")
        Files.createDirectories(dir)
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"))
        val proxy = TestProxy(coroutineScope = CoroutineScope(Dispatchers.Default))

        UnixSocketServer(
            proxy.gateway,
            proxy.serializer,
            dir.resolve("ipc.sock").toString(),
        ).start()

        assertEquals("rwx------", mode(dir))
    }

    @Test
    fun prepareThenInspect_agree() {
        val dir = tempRoot().resolve(".tenshu")
        Files.createDirectories(dir)
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx"))

        assertIs<SocketDirectory.Verdict.Squattable>(SocketDirectory.inspect(dir))
        SocketDirectory.prepare(dir) {}
        assertEquals(SocketDirectory.Verdict.Sound, SocketDirectory.inspect(dir))
    }
}
