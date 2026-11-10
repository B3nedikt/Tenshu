package org.tenshu.core.common.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assume.assumeTrue
import org.tenshu.core.common.TestProxy
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.GroupPrincipal
import java.nio.file.attribute.UserPrincipal
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The NTFS arm of [SocketDirectory]: the DACL standing in for `0700`.
 *
 * Two halves. The decision ([SocketDirectory.aclVerdict]) is pure, so its cases run on **every**
 * host over synthetic entries. The native read/write runs only where the default filesystem has
 * ACLs and no POSIX modes (Windows); elsewhere those cases skip.
 */
class SocketDirectoryAclTest {

    private data class User(private val n: String) : UserPrincipal {
        override fun getName() = n
    }

    private data class Group(private val n: String) : GroupPrincipal {
        override fun getName() = n
    }

    private val self = User("HOST\\me")
    private val system = Group("NT AUTHORITY\\SYSTEM")
    private val admins = Group("BUILTIN\\Administrators")
    private val trusted = setOf<UserPrincipal>(system, admins)
    private val dir = Path.of("profile", ".tenshu")

    private fun allow(
        principal: UserPrincipal,
        permissions: Set<AclEntryPermission>,
        flags: Set<AclEntryFlag> = setOf(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT),
        type: AclEntryType = AclEntryType.ALLOW,
    ): AclEntry = AclEntry.newBuilder()
        .setType(type)
        .setPrincipal(principal)
        .setPermissions(permissions)
        .setFlags(flags)
        .build()

    private val full = AclEntryPermission.entries.toSet()

    /** What a stock `%USERPROFILE%` hands down to a fresh `~\.tenshu`. */
    private val stockProfile = listOf(allow(system, full), allow(admins, full), allow(self, full))

    private fun verdict(acl: List<AclEntry>, owner: UserPrincipal? = self) =
        SocketDirectory.aclVerdict(dir, owner, acl, self, trusted)

    // ---- pure decision: every host ----------------------------------------------------------

    /**
     * ⚠⚠ The case that keeps Control from refusing its own directory: the stock profile grants
     * SYSTEM and Administrators full control, and a fresh `~\.tenshu` inherits it.
     */
    @Test
    fun theStockProfileInheritanceIsSound() {
        assertEquals(SocketDirectory.Verdict.Sound, verdict(stockProfile))
    }

    @Test
    fun everyoneWithFullControlIsSquattable() {
        assertIs<SocketDirectory.Verdict.Squattable>(verdict(stockProfile + allow(Group("\\Everyone"), full)))
    }

    /** Each squatting right on its own is enough — none of them is "only a little" write. */
    @Test
    fun eachSquattingRightAloneIsSquattable() {
        val users = Group("BUILTIN\\Users")
        for (right in SocketDirectory.ACL_SQUAT) {
            assertIs<SocketDirectory.Verdict.Squattable>(
                verdict(stockProfile + allow(users, setOf(right, AclEntryPermission.SYNCHRONIZE))),
                "Users holding $right",
            )
        }
    }

    @Test
    fun otherUsersWhoCanOnlyListOrTraverseAreReachable() {
        val users = Group("BUILTIN\\Users")
        val readExecute = setOf(
            AclEntryPermission.READ_DATA,
            AclEntryPermission.EXECUTE,
            AclEntryPermission.READ_ATTRIBUTES,
            AclEntryPermission.READ_ACL,
            AclEntryPermission.SYNCHRONIZE,
        )
        assertIs<SocketDirectory.Verdict.Reachable>(verdict(stockProfile + allow(users, readExecute)))
        assertIs<SocketDirectory.Verdict.Reachable>(
            verdict(stockProfile + allow(User("S-1-15-3-1"), setOf(AclEntryPermission.EXECUTE), flags = emptySet())),
        )
    }

    /** Reading attributes or the ACL reaches nothing — no listing, no traversal, no write. */
    @Test
    fun metadataOnlyRightsForOthersAreSound() {
        val metadata = setOf(
            AclEntryPermission.READ_ATTRIBUTES,
            AclEntryPermission.READ_ACL,
            AclEntryPermission.SYNCHRONIZE,
        )
        assertEquals(SocketDirectory.Verdict.Sound, verdict(stockProfile + allow(Group("\\Everyone"), metadata)))
    }

    @Test
    fun aDirectoryOwnedByAnotherUserIsSquattable() {
        assertIs<SocketDirectory.Verdict.Squattable>(verdict(stockProfile, owner = User("HOST\\mallory")))
    }

    /** An elevated process's new objects are owned by Administrators by default; that is not a squatter. */
    @Test
    fun aDirectoryOwnedByATrustedPrincipalIsSound() {
        assertEquals(SocketDirectory.Verdict.Sound, verdict(stockProfile, owner = admins))
        assertEquals(SocketDirectory.Verdict.Sound, verdict(stockProfile, owner = system))
        assertEquals(SocketDirectory.Verdict.Sound, verdict(stockProfile, owner = null))
    }

    @Test
    fun denyEntriesNeverMakeADirectoryWorse() {
        assertEquals(
            SocketDirectory.Verdict.Sound,
            verdict(stockProfile + allow(Group("\\Everyone"), full, type = AclEntryType.DENY)),
        )
    }

    /** Inherit-only entries govern children, not the directory where the socket is created. */
    @Test
    fun inheritOnlyEntriesDoNotGovernTheDirectory() {
        val inheritOnly = allow(
            Group("CREATOR OWNER"),
            full,
            setOf(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.INHERIT_ONLY),
        )
        assertEquals(SocketDirectory.Verdict.Sound, verdict(stockProfile + inheritOnly))
    }

    /** `prepare` and `inspect` agree by construction: the DACL the repair writes reads Sound. */
    @Test
    fun theRepairAclIsSoundAndHoldsExactlyTheTrustedThree() {
        val repaired = SocketDirectory.ownerOnlyAcl(self, trusted)

        assertEquals(SocketDirectory.Verdict.Sound, verdict(repaired))
        assertEquals(setOf<UserPrincipal>(self, system, admins), repaired.map { it.principal() }.toSet())
        assertTrue(repaired.all { it.type() == AclEntryType.ALLOW && it.permissions() == full })
    }

    // ---- real DACLs: Windows only ----------------------------------------------------------

    private val roots = mutableListOf<Path>()

    @AfterTest
    fun cleanUp() {
        roots.forEach { runCatching { Files.walk(it).sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }

    private fun windowsOnly() {
        val views = FileSystems.getDefault().supportedFileAttributeViews()
        assumeTrue("needs an ACL filesystem without POSIX modes (Windows)", "acl" in views && "posix" !in views)
    }

    private fun freshDir(): Path {
        val root = org.tenshu.core.common.shortTempDirectory("t").also { roots.add(it) }
        return root.resolve(".tenshu").also { Files.createDirectories(it) }
    }

    private fun view(dir: Path) = Files.getFileAttributeView(dir, AclFileAttributeView::class.java)

    private fun lookupGroup(vararg names: String): UserPrincipal {
        val lookup = FileSystems.getDefault().userPrincipalLookupService
        return names.firstNotNullOf { runCatching { lookup.lookupPrincipalByGroupName(it) }.getOrNull() }
    }

    private fun grant(dir: Path, principal: UserPrincipal, permissions: Set<AclEntryPermission>) {
        val v = view(dir)
        v.acl = v.acl + allow(principal, permissions)
    }

    @Test
    fun windows_aFreshDirectoryUnderTheTempProfileIsSoundAndLeftAlone() {
        windowsOnly()
        val dir = freshDir()

        val logged = mutableListOf<String>()
        SocketDirectory.prepare(dir) { logged += it }

        assertEquals(SocketDirectory.Verdict.Sound, SocketDirectory.inspect(dir))
        assertTrue(logged.isEmpty(), "a stock directory needs no repair, but logged: $logged")
    }

    @Test
    fun windows_anEveryoneWriteAceIsSquattableAndPrepareRepairsIt() {
        windowsOnly()
        val dir = freshDir()
        val everyone = lookupGroup("Everyone")
        grant(dir, everyone, setOf(AclEntryPermission.WRITE_DATA, AclEntryPermission.READ_DATA))

        assertIs<SocketDirectory.Verdict.Squattable>(SocketDirectory.inspect(dir))

        val logged = mutableListOf<String>()
        SocketDirectory.prepare(dir) { logged += it }

        assertEquals(SocketDirectory.Verdict.Sound, SocketDirectory.inspect(dir))
        assertTrue(view(dir).acl.none { it.principal() == everyone }, "Everyone survived: ${view(dir).acl}")
        assertTrue(logged.any { it.contains("tightened") }, "a repair should say so: $logged")
    }

    @Test
    fun windows_aReadOnlyUsersAceIsReachableAndPrepareRepairsIt() {
        windowsOnly()
        val dir = freshDir()
        grant(dir, lookupGroup("Users"), setOf(AclEntryPermission.READ_DATA, AclEntryPermission.EXECUTE))

        assertIs<SocketDirectory.Verdict.Reachable>(SocketDirectory.inspect(dir))
        SocketDirectory.prepare(dir) {}
        assertEquals(SocketDirectory.Verdict.Sound, SocketDirectory.inspect(dir))
    }

    /**
     * ⚠ Pins what `prepareAcl`'s KDoc claims about inheritance: a repair that **dropped** an
     * inherited ACE leaves an explicit, protected DACL, so a later propagating grant on the parent
     * does not reach it. The parent grant goes through `icacls` because the JDK's own `setAcl`
     * does not propagate to children at all, so it could not falsify the claim.
     */
    @Test
    fun windows_aRepairThatDroppedAnInheritedAceIsNotReinfectedByTheParent() {
        windowsOnly()
        val parent = org.tenshu.core.common.shortTempDirectory("t").also { roots.add(it) }
        grant(parent, lookupGroup("Everyone"), AclEntryPermission.entries.toSet())
        val dir = parent.resolve(".tenshu").also { Files.createDirectories(it) }
        assertIs<SocketDirectory.Verdict.Squattable>(SocketDirectory.inspect(dir), "inherited Everyone: F")

        SocketDirectory.prepare(dir) {}
        assertEquals(SocketDirectory.Verdict.Sound, SocketDirectory.inspect(dir))

        // *S-1-5-32-545 = BUILTIN\Users, by SID so the test is locale-independent.
        val icacls = ProcessBuilder("icacls", parent.toString(), "/grant", "*S-1-5-32-545:(OI)(CI)M")
            .redirectErrorStream(true).start()
        icacls.inputStream.readAllBytes()
        assumeTrue("icacls unavailable", icacls.waitFor() == 0)

        assertEquals(SocketDirectory.Verdict.Sound, SocketDirectory.inspect(dir))
    }

    /** The wiring on Windows: the server's own start repairs the directory it binds in. */
    @Test
    fun windows_startingTheServerTightensTheDirectoryItBindsIn() {
        windowsOnly()
        val dir = freshDir()
        grant(dir, lookupGroup("Everyone"), AclEntryPermission.entries.toSet())
        val proxy = TestProxy(coroutineScope = CoroutineScope(Dispatchers.Default))

        UnixSocketServer(proxy.gateway, proxy.serializer, dir.resolve("ipc.sock").toString()).start()

        assertEquals(SocketDirectory.Verdict.Sound, SocketDirectory.inspect(dir))
    }
}
