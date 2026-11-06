package org.tenshu.core.common.transport

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Filesystem hygiene for the directory that hosts the desktop daemon's sockets — the first bar
 * in [`desktop-ipc.md`](../../../../../../../../docs/protocol/desktop-ipc.md) §3, which the code
 * did not actually implement (2026-08-21 IPC audit item #4).
 *
 * ### Why the directory, and not the socket file
 *
 * Permissions on the socket *inode* are not a portable gate: Linux checks write permission on it
 * for `connect()`, but macOS/BSD ignore them entirely. The containing directory's mode is the one
 * check that holds on both, because reaching the socket at all requires traversing it.
 *
 * ### What it buys, and what it does not
 *
 * `0700` bars **other users** two ways: nobody else can `connect()` to the socket, and nobody
 * else can *squat* the path — creating `ipc.sock` needs write+execute on the directory, and a
 * process that binds it before the daemon starts is handed every client's traffic. It does
 * nothing against a same-user process, which traverses regardless; that is what the
 * peer-credential gate ([PeerCredentialVerifier]) is for. The two are layers, not alternatives.
 *
 * ⚠ Exposure varies by platform, which is why this cannot be left to the environment: macOS and
 * Fedora create `~` as `0700`, Debian/Ubuntu as `0755`. `Files.createDirectories` applies the
 * umask (`0755` under the common default), so the unsigned `~/.tenshu` default — the only option
 * on Linux — was reachable by every local user on a stock Ubuntu box.
 */
object SocketDirectory {

    private val OWNER_ONLY: Set<PosixFilePermission> =
        PosixFilePermissions.fromString("rwx------")

    /** Group/other bits that let another user create or replace the socket file. */
    private val SQUATTABLE: Set<PosixFilePermission> = setOf(
        PosixFilePermission.GROUP_WRITE,
        PosixFilePermission.OTHERS_WRITE,
    )

    /** Group/other bits that let another user reach an existing socket. */
    private val REACHABLE: Set<PosixFilePermission> = setOf(
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ,
        PosixFilePermission.OTHERS_EXECUTE,
    )

    /**
     * Server side: ensure [dir] exists and is owner-only.
     *
     * ⚠ Both halves are needed. A creation-time `FileAttribute` covers only a directory this call
     * creates, and the interesting case is the opposite one — a `~/.tenshu` an earlier build
     * already created `0755`, which no amount of correct creation code will ever fix.
     *
     * Best-effort by design: a failure to tighten an existing directory is logged, never thrown.
     * The daemon refusing to start because it could not chmod a directory would trade a partial
     * exposure for a total outage, and the peer gate is still in front of every connection.
     * Silently a no-op on a filesystem without POSIX permissions (Windows named pipes carry their
     * own ACLs).
     */
    fun prepare(dir: Path, log: (String) -> Unit = { System.err.println(it) }) {
        if (Files.notExists(dir)) {
            runCatching {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
            }.onFailure {
                // Non-POSIX filesystem, or a race with another process creating it first.
                runCatching { Files.createDirectories(dir) }
            }
        }
        if (!supportsPosix(dir)) return
        val current = runCatching { Files.getPosixFilePermissions(dir) }.getOrNull() ?: return
        if (current == OWNER_ONLY) return
        runCatching { Files.setPosixFilePermissions(dir, OWNER_ONLY) }
            .onSuccess {
                log("[ipc] tightened socket directory $dir from ${PosixFilePermissions.toString(current)} to rwx------")
            }
            .onFailure {
                log("[ipc] could not tighten socket directory $dir (${it.message}) — other local users may be able to reach or squat the socket")
            }
    }

    /**
     * Client side: what a connecting process should make of [dir]'s state.
     *
     * A client authenticates nothing about the server today — it connects to a path — so the
     * directory's mode is the only evidence it has that the socket it is about to trust was
     * placed there by the user's own daemon rather than by whoever got there first.
     */
    sealed interface Verdict {
        /** Owner-only (or not a POSIX filesystem, where this check does not apply). */
        data object Sound : Verdict

        /**
         * Another user could **create** the socket here. That is the squatting vector, so this
         * is a refusal rather than a warning: connecting means handing an unknown process every
         * request this client makes.
         */
        data class Squattable(val reason: String) : Verdict

        /**
         * Another user could **reach** an existing socket, but not place one. A lesser exposure
         * — the peer-credential gate still stands between them and the proxy — so this warns
         * rather than refuses, and does not strand a user whose home directory is `0755`.
         */
        data class Reachable(val reason: String) : Verdict
    }

    fun inspect(dir: Path): Verdict {
        if (!supportsPosix(dir)) return Verdict.Sound
        val permissions = runCatching { Files.getPosixFilePermissions(dir) }.getOrNull()
            ?: return Verdict.Sound

        val owner = runCatching { Files.getOwner(dir)?.name }.getOrNull()
        val self = System.getProperty("user.name")
        if (owner != null && self != null && owner != self) {
            return Verdict.Squattable("$dir is owned by '$owner', not '$self'")
        }

        val squattable = permissions intersect SQUATTABLE
        if (squattable.isNotEmpty()) {
            // ⚠⚠ The sticky bit is the difference between "/tmp" and "misconfigured", and this
            // check is worthless without it: a shared temp directory is world-writable **by
            // design** and is where every test fixture and `TENSHU_IPC_DIR` dev run puts its
            // socket, so refusing on the mode alone rejects a working configuration. Sticky
            // means only the owner may remove or rename what they created, so nobody can
            // replace a socket the daemon already bound.
            //
            // ⚠ It is a downgrade, not an exoneration: an attacker who gets there *first*, while
            // no socket exists, can still bind the path. That residual is inherent to putting a
            // socket in a shared directory — which is why the default is `~/.tenshu`, never a
            // temp dir — so it warns rather than passing silently.
            return if (isSticky(dir)) {
                Verdict.Reachable(
                    "$dir is ${PosixFilePermissions.toString(permissions)} (sticky) — shared with other " +
                        "users; a socket bound here first would not be ours"
                )
            } else {
                Verdict.Squattable(
                    "$dir is ${PosixFilePermissions.toString(permissions)} — another user can place a socket there"
                )
            }
        }

        val reachable = permissions intersect REACHABLE
        if (reachable.isNotEmpty()) {
            return Verdict.Reachable(
                "$dir is ${PosixFilePermissions.toString(permissions)} — another user can reach the socket"
            )
        }
        return Verdict.Sound
    }

    /**
     * The `t` bit (`01000`), which `PosixFilePermissions` does not model. Read through the
     * `unix:` attribute view, which exists on Linux and macOS; anything else answers `false`,
     * i.e. the stricter verdict.
     */
    private fun isSticky(dir: Path): Boolean =
        runCatching { (Files.getAttribute(dir, "unix:mode") as Int) and 0b1_000_000_000 != 0 }
            .getOrDefault(false)

    private fun supportsPosix(dir: Path): Boolean =
        runCatching {
            Files.getFileStore(dir).supportsFileAttributeView(PosixFileAttributeView::class.java)
        }.getOrDefault(false)
}
