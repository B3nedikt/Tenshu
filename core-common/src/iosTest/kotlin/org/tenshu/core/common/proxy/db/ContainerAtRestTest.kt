@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.tenshu.core.common.proxy.db

import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** D3/D3b (`ios-shared-store-sqlite.md` §2.4, S0b-4 slice 2): [ContainerAtRest]. Every path is made up. */
class ContainerAtRestTest {

    private fun tempDirectory() = NSTemporaryDirectory() + "at-rest-" + NSProcessInfo.processInfo.globallyUniqueString + "/files"

    private class Recording(
        var reported: String? = ContainerAtRest.EXPECTED_CLASS,
        var set: Boolean = true,
        var excluded: Boolean = true,
        /** Whether the first unlock since boot has happened (the class keys exist). */
        var classKeys: Boolean = true,
        /** What the directory itself reports, when not [reported]. */
        var directoryReports: (() -> String?)? = null,
        /** Paths that vanish the moment their class is set (a checkpoint removed a -wal). */
        val vanishing: MutableSet<String> = HashSet(),
    ) : ContainerAtRest.Platform {
        val classed = ArrayList<String>()
        var excludedAsked = 0
        val gone = HashSet<String>()
        override fun setProtection(path: String): Boolean {
            classed += path.substringAfterLast('/')
            if (path in vanishing) { gone += path; return false }
            return set
        }
        override fun protectionOf(path: String): String? =
            if (path in gone) null else if (!path.substringAfterLast('/').contains('.') && directoryReports != null) directoryReports!!() else reported
        override fun excludeFromBackup(path: String): Boolean = excluded.also { excludedAsked++ }
        override fun isExcludedFromBackup(path: String): Boolean = excluded
        override fun classKeysAvailable(directory: String): Boolean = classKeys
        override fun exists(path: String): Boolean = path !in gone && NSFileManager.defaultManager.fileExistsAtPath(path)
        override fun makeDirectory(path: String, withClass: Boolean): Boolean =
            NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
    }

    @Test
    fun aLockedPhoneAfterTheFirstUnlockStillGetsBothFlags() {
        // ⚠⚠ Fix round 1, S1 (B.21.6 (410)): `isProtectedDataAvailable` is false whenever the phone is
        // locked, not only before the first unlock. A location wake or a BGTask opens the store then,
        // and iCloud backup runs then: the class keys exist, so the class is set and read back.
        val directory = tempDirectory()
        NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
        NSFileManager.defaultManager.createFileAtPath("$directory/objects.db", null, null)
        val platform = Recording(classKeys = true)
        val outcome = ContainerAtRest(deviceBuild = true, platform = platform)
            .protect(directory, files = listOf("$directory/objects.db"), protectedDataAvailable = { false })
        assertEquals(ContainerAtRest.Outcome.PROTECTED, outcome)
        assertEquals(listOf("files", "objects.db"), platform.classed, "the class is asserted on a locked open")
        assertEquals(1, platform.excludedAsked)
    }

    @Test
    fun beforeTheFirstUnlockTheClassIsDeferredNotRefusedAndTheExclusionIsStillTried() {
        val directory = tempDirectory()
        val platform = Recording(classKeys = false, excluded = false)
        val logged = ArrayList<String>()
        val outcome = ContainerAtRest(deviceBuild = true, platform = platform)
            .protect(directory, protectedDataAvailable = { false }, log = { logged += it })
        assertEquals(ContainerAtRest.Outcome.DEFERRED_UNTIL_FIRST_UNLOCK, outcome)
        assertEquals(emptyList(), platform.classed, "no class can be set before the first unlock")
        assertEquals(1, platform.excludedAsked, "the exclusion needs no protected data: always tried")
        assertTrue(logged.any { "deferred" in it }, "$logged")
    }

    @Test
    fun aDirectoryThatReportsNoClassOnADeviceIsAcceptedOnItsFilesClassAndLogged() {
        // L2: a device that reports no class on a directory must not refuse every open forever.
        val directory = tempDirectory()
        NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
        NSFileManager.defaultManager.createFileAtPath("$directory/objects.db", null, null)
        val logged = ArrayList<String>()
        val platform = Recording(directoryReports = { null })
        ContainerAtRest(deviceBuild = true, platform = platform).protect(directory, files = listOf("$directory/objects.db"), log = { logged += it })
        assertTrue(logged.any { "reports no protection class" in it }, "$logged")
        // A FILE that reports none on a device is still refused.
        assertFailsWith<NotProtectedAtRest> {
            ContainerAtRest(deviceBuild = true, platform = Recording(reported = null, directoryReports = { ContainerAtRest.EXPECTED_CLASS }))
                .protect(directory, files = listOf("$directory/objects.db"))
        }
    }

    @Test
    fun aWalThatVanishesBetweenTheCheckAndTheSetCountsAsAbsent() {
        // L3: a checkpoint or a sibling removed -wal after the existence check.
        val directory = tempDirectory()
        NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
        NSFileManager.defaultManager.createFileAtPath("$directory/objects.db-wal", null, null)
        val platform = Recording(vanishing = mutableSetOf("$directory/objects.db-wal"))
        assertEquals(
            ContainerAtRest.Outcome.PROTECTED,
            ContainerAtRest(deviceBuild = true, platform = platform).protect(directory, files = listOf("$directory/objects.db-wal")),
        )
    }

    @Test
    fun theRealCallsMakeTheDirectoryAndItsExclusionReadsBack() {
        val directory = tempDirectory()
        ContainerAtRest(deviceBuild = false).protect(directory)
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(directory), "made when missing")
        assertTrue(ContainerAtRest.FoundationPlatform.isExcludedFromBackup(directory), "excluded from backup, read from a fresh URL")
        // ⚠ Vacuous on the simulator, which has no Data Protection and reports no class (null): what
        // this pins there is only that the call does not fail. The device's answer is B.21.5 item 1.
        val reported = ContainerAtRest.FoundationPlatform.protectionOf(directory)
        assertTrue(reported == null || reported == ContainerAtRest.EXPECTED_CLASS, "the class, where the platform reports one: $reported")
        assertTrue(ContainerAtRest.FoundationPlatform.classKeysAvailable(directory), "the simulator is always after its first unlock")
    }

    @Test
    fun theClassIsSetOnTheDirectoryAndOnEachFileThatExists() {
        val directory = tempDirectory()
        NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
        NSFileManager.defaultManager.createFileAtPath("$directory/objects.db", null, null)
        val platform = Recording()
        ContainerAtRest(deviceBuild = true, platform = platform).protect(directory, files = listOf("$directory/objects.db", "$directory/objects.db-wal"))
        assertEquals(listOf("files", "objects.db"), platform.classed, "a missing -wal is not made")
    }

    @Test
    fun aDeviceThatReportsNoClassOrAnotherOneIsRefusedTheSimulatorsSilenceIsNot() {
        val directory = tempDirectory()
        NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
        NSFileManager.defaultManager.createFileAtPath("$directory/objects.db", null, null)
        // A file reporting none on a device is refused (the directory alone reporting none is L2's case above).
        assertFailsWith<NotProtectedAtRest> { ContainerAtRest(deviceBuild = true, platform = Recording(reported = null)).protect(directory, files = listOf("$directory/objects.db")) }
        assertFailsWith<NotProtectedAtRest> { ContainerAtRest(deviceBuild = true, platform = Recording(reported = "NSFileProtectionNone")).protect(directory) }
        ContainerAtRest(deviceBuild = false, platform = Recording(reported = null)).protect(directory)
        assertFailsWith<NotProtectedAtRest> { ContainerAtRest(deviceBuild = false, platform = Recording(reported = "NSFileProtectionNone")).protect(directory) }
    }

    @Test
    fun aClassThatCannotBeSetOrAnExclusionThatDoesNotHoldIsRefused() {
        val directory = tempDirectory()
        assertFailsWith<NotProtectedAtRest> { ContainerAtRest(deviceBuild = false, platform = Recording(set = false)).protect(directory) }
        assertFailsWith<NotProtectedAtRest> { ContainerAtRest(deviceBuild = false, platform = Recording(excluded = false)).protect(directory) }
    }
}
