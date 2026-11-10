package org.tenshu.core.common.transport

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.GroupPrincipal
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal

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
 *
 * ### Windows (NTFS): the same gate, as a DACL
 *
 * NTFS has no mode bits, so the POSIX arm has nothing to read there; the directory's DACL plays
 * the role of `0700`, read and written through [AclFileAttributeView]. The decision is the pure
 * [aclVerdict] (unit-tested on every host with synthetic entries); the native read and write are
 * thin wrappers around it. See [aclVerdict] for the exact permission sets, and [prepareAcl] for
 * what `setAcl` does and does not do to inheritance.
 *
 * ⚠ **SYSTEM and Administrators are trusted, not "other users".** Both can already read and
 * rewrite every file on the machine (Administrators by taking ownership), so excluding them from
 * the DACL buys nothing — and refusing on them would call every stock profile directory
 * squattable, because `%USERPROFILE%` grants exactly *user + SYSTEM + Administrators* full
 * control and a fresh `~\.tenshu` inherits that. The default directory therefore reads [Verdict.Sound]
 * as created, with no repair and no log line.
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
     * On a filesystem with ACLs instead of POSIX modes (Windows/NTFS) this is [prepareAcl]; on one
     * with neither it is silently a no-op.
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
        if (!supportsPosix(dir)) {
            if (supportsAcl(dir)) prepareAcl(dir, log)
            return
        }
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
        /**
         * Owner-only — on NTFS, owner + SYSTEM + Administrators — or a filesystem with neither POSIX
         * modes nor ACLs, where this check does not apply.
         */
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
        if (!supportsPosix(dir)) return if (supportsAcl(dir)) inspectAcl(dir) else Verdict.Sound
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

    // ---------------------------------------------------------------------------------------
    // Windows (NTFS) arm
    // ---------------------------------------------------------------------------------------

    /**
     * Rights on the directory that let a holder **create or replace** the socket, or hand
     * themselves that ability: add a file / subdirectory, delete children, delete or re-own the
     * directory, rewrite its DACL. (`ADD_FILE` and `ADD_SUBDIRECTORY` are the JDK's aliases of
     * `WRITE_DATA` and `APPEND_DATA`.)
     */
    internal val ACL_SQUAT: Set<AclEntryPermission> = setOf(
        AclEntryPermission.WRITE_DATA,
        AclEntryPermission.APPEND_DATA,
        AclEntryPermission.WRITE_ACL,
        AclEntryPermission.WRITE_OWNER,
        AclEntryPermission.DELETE_CHILD,
        AclEntryPermission.DELETE,
    )

    /** Rights that let a holder list the directory or traverse it to an existing socket. */
    internal val ACL_REACH: Set<AclEntryPermission> = setOf(
        AclEntryPermission.READ_DATA,
        AclEntryPermission.EXECUTE,
    )

    /**
     * The pure NTFS decision: what [dir]'s [owner] and [acl] mean for a process running as [self],
     * with [trusted] the principals that are not "another user" (SYSTEM, Administrators — see the
     * class KDoc for why).
     *
     * - [Verdict.Squattable] if the owner is neither [self] nor [trusted], or any `ALLOW` entry
     *   for any other principal holds a right in [ACL_SQUAT].
     * - [Verdict.Reachable] if other principals hold only rights in [ACL_REACH].
     * - [Verdict.Sound] otherwise.
     *
     * ⚠ Group entries count as other users: `Users`, `Authenticated Users` and `Everyone` all
     * contain [self], but they contain everyone else too, which is the point.
     *
     * `DENY` entries are ignored, which can only over-estimate access — the safe direction.
     * `INHERIT_ONLY` entries are ignored because they do not apply to the directory itself, and
     * creating the socket needs `ADD_FILE` *on the directory*.
     */
    internal fun aclVerdict(
        dir: Path,
        owner: UserPrincipal?,
        acl: List<AclEntry>,
        self: UserPrincipal,
        trusted: Set<UserPrincipal>,
    ): Verdict {
        if (owner != null && owner != self && owner !in trusted) {
            return Verdict.Squattable("$dir is owned by '${owner.name}', not '${self.name}'")
        }
        val others = acl.filter {
            it.type() == AclEntryType.ALLOW &&
                AclEntryFlag.INHERIT_ONLY !in it.flags() &&
                it.principal() != self &&
                it.principal() !in trusted
        }
        val writers = others.filter { (it.permissions() intersect ACL_SQUAT).isNotEmpty() }
        if (writers.isNotEmpty()) {
            return Verdict.Squattable(
                "$dir grants ${describe(writers, ACL_SQUAT)} — another user can place a socket there"
            )
        }
        val readers = others.filter { (it.permissions() intersect ACL_REACH).isNotEmpty() }
        if (readers.isNotEmpty()) {
            return Verdict.Reachable(
                "$dir grants ${describe(readers, ACL_REACH)} — another user can reach the socket"
            )
        }
        return Verdict.Sound
    }

    private fun describe(entries: List<AclEntry>, rights: Set<AclEntryPermission>): String =
        entries.joinToString("; ") { e ->
            "${e.principal().name}: " + (e.permissions() intersect rights).joinToString(",") { it.name }
        }

    /**
     * The DACL [prepareAcl] writes: full control for [self] and each of [trusted], inheritable by
     * files and subdirectories (so the socket file gets the same three), nothing else.
     */
    internal fun ownerOnlyAcl(
        self: UserPrincipal,
        trusted: Set<UserPrincipal>,
    ): List<AclEntry> =
        (listOf(self) + trusted.filter { it != self }).map { principal ->
            AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(principal)
                .setPermissions(AclEntryPermission.entries.toSet())
                .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                .build()
        }

    /**
     * The current user and the trusted principals, resolved once.
     *
     * ⚠ Resolved **by name**, because the JDK offers no SID lookup: `LookupAccountName` does not
     * accept SID strings (`S-1-5-18` fails). `SYSTEM` resolves on every locale; `Administrators`
     * is localised (`Administratoren`, `Administrateurs`), so on a non-English Windows it may not
     * resolve. That fails *closed and self-consistently*: an Administrators entry then counts as
     * another user, and [prepareAcl] writes a DACL without it, which then reads Sound.
     */
    private class AclPrincipals(
        val self: UserPrincipal?,
        val trusted: Set<UserPrincipal>,
    )

    private val aclPrincipals: AclPrincipals by lazy {
        val lookup = FileSystems.getDefault().userPrincipalLookupService
        fun first(vararg names: String?) = names.filterNotNull().firstNotNullOfOrNull { name ->
            runCatching { lookup.lookupPrincipalByName(name) }.getOrNull()
        }
        val user = System.getProperty("user.name")
        val domain = System.getenv("USERDOMAIN")
        // The owner of a file this process just created is its own token's user — no name to resolve, so no
        // chance of a same-named account elsewhere in the domain. An elevated administrator's new files are
        // owned by the Administrators group instead; only then fall back to the name.
        val created = runCatching {
            val probe = Files.createTempFile("tenshu-owner", ".probe")
            try { Files.getOwner(probe) } finally { Files.deleteIfExists(probe) }
        }.getOrNull()?.takeIf { it !is GroupPrincipal }
        val self = created
            ?: first(if (domain != null && user != null) "$domain\\$user" else null, user)?.takeIf { it !is GroupPrincipal }
        val trusted = setOfNotNull(
            first("NT AUTHORITY\\SYSTEM", "SYSTEM"),
            first("BUILTIN\\Administrators", "Administrators"),
        )
        AclPrincipals(self, trusted)
    }

    private fun inspectAcl(dir: Path): Verdict {
        val view = Files.getFileAttributeView(dir, AclFileAttributeView::class.java) ?: return Verdict.Sound
        val acl = runCatching { view.acl }.getOrNull() ?: return Verdict.Sound
        val owner = runCatching { view.owner }.getOrNull()
        val self = aclPrincipals.self
            ?: return Verdict.Reachable("$dir: could not resolve the current Windows user, so its DACL was not checked")
        return aclVerdict(dir, owner, acl, self, aclPrincipals.trusted)
    }

    /**
     * Server side on NTFS: if [dir] is not [Verdict.Sound], replace its DACL with [ownerOnlyAcl].
     * Same best-effort contract as the POSIX arm: logged, never thrown.
     *
     * A Sound directory is left untouched, so the stock `~\.tenshu` (which inherits exactly
     * user + SYSTEM + Administrators from `%USERPROFILE%`) is never rewritten and never logged.
     *
     * ### What `setAcl` does to inheritance (observed on Windows 11, JDK 25)
     *
     * The JDK writes the DACL with `SetFileSecurity`, which cannot set `SE_DACL_PROTECTED`
     * itself; Windows decides protection by comparing the written DACL with what the parent would
     * pass down:
     * - If the written DACL **omits** an ACE the parent passes down (the repair case: an inherited
     *   `Everyone: F` is dropped), the result is all-explicit and **protected** — a later grant on
     *   the parent, even a propagating `icacls /grant (OI)(CI)`, no longer reaches it.
     * - If it **equals** what the parent passes down (user + SYSTEM + Administrators under a stock
     *   profile), Windows re-marks the ACEs as inherited and leaves the DACL unprotected, so a
     *   later loosening of the parent would propagate in.
     *
     * ⚠ The second case is a residual, not a hole: only someone who can rewrite the parent's DACL
     * — the user, SYSTEM, an administrator — can propagate anything, and the next [inspect] or
     * daemon start catches and repairs the result. Forcing protection needs
     * `SetSecurityInfo(PROTECTED_DACL_SECURITY_INFORMATION)`, which pure NIO does not expose.
     */
    private fun prepareAcl(dir: Path, log: (String) -> Unit) {
        val view = Files.getFileAttributeView(dir, AclFileAttributeView::class.java) ?: return
        val self = aclPrincipals.self ?: run {
            log("[ipc] could not resolve the current Windows user — socket directory $dir left as is")
            return
        }
        val acl = runCatching { view.acl }.getOrNull() ?: return
        val owner = runCatching { view.owner }.getOrNull()
        val before = aclVerdict(dir, owner, acl, self, aclPrincipals.trusted)
        if (before == Verdict.Sound) return
        // ⚠ A directory owned by someone else cannot be repaired by rewriting its DACL: the owner keeps the
        // implicit right to change it back. Say so rather than log a repair that did not happen.
        if (owner != null && owner != self && owner !in aclPrincipals.trusted) {
            log(
                "[ipc] socket directory $dir is owned by ${owner.name}, not this user — not repaired, because its " +
                    "owner can always change it back. Remove it or point TENSHU_IPC_DIR elsewhere.",
            )
            return
        }
        val reason = when (before) {
            is Verdict.Squattable -> before.reason
            is Verdict.Reachable -> before.reason
            Verdict.Sound -> ""
        }
        runCatching { view.acl = ownerOnlyAcl(self, aclPrincipals.trusted) }
            .onSuccess {
                log("[ipc] tightened socket directory DACL ($reason) to ${(listOf(self) + aclPrincipals.trusted).joinToString(" + ") { it.name }} only")
            }
            .onFailure {
                log("[ipc] could not tighten socket directory DACL ($reason): ${it.message} — other local users may be able to reach or squat the socket")
            }
    }

    private fun supportsAcl(dir: Path): Boolean =
        runCatching {
            Files.getFileStore(dir).supportsFileAttributeView(AclFileAttributeView::class.java)
        }.getOrDefault(false)
}
