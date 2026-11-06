package org.tenshu.core.common.storage

import org.tenshu.core.common.transport.SocketPathResolver
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopAppPathsTest {

    private val savedDataDir = System.getProperty(DesktopAppPaths.DIR_PROPERTY)
    private val savedTeam = System.getProperty(SocketPathResolver.TEAM_ID_PROPERTY)
    private val savedOs = System.getProperty("os.name")
    private val savedAdopt = System.getProperty(STALE_ADOPT_PROPERTY)

    // ⚠ Every path here is resolved under a throwaway home. `dataDir` CREATES directories, and
    // against the real home this test used to leave `ABCDE12345.app.tenshu.shared` in the
    // user's `~/Library/Group Containers`.
    private val savedHome = System.getProperty("user.home")
    private val fakeHome = java.nio.file.Files.createTempDirectory("tenshu-paths-home").toFile()

    @BeforeTest
    fun isolateHome() {
        System.setProperty("user.home", fakeHome.path)
    }

    @AfterTest
    fun restore() {
        System.setProperty("user.home", savedHome)
        fakeHome.deleteRecursively()
        restore(DesktopAppPaths.DIR_PROPERTY, savedDataDir)
        restore(SocketPathResolver.TEAM_ID_PROPERTY, savedTeam)
        restore("os.name", savedOs)
        restore(STALE_ADOPT_PROPERTY, savedAdopt)
    }

    private fun restore(key: String, value: String?) {
        if (value == null) System.clearProperty(key) else System.setProperty(key, value)
    }

    private val legacyRoot get() = File(fakeHome, ".tenshu")
    private val container get() = File(fakeHome, "Library/Group Containers/$TEAM.app.tenshu.shared")

    /** A packaged macOS launch: the launcher cfg pins the Team ID; no override. */
    private fun packagedMac() {
        System.clearProperty(DesktopAppPaths.DIR_PROPERTY)
        System.setProperty("os.name", "Mac OS X")
        System.setProperty(SocketPathResolver.TEAM_ID_PROPERTY, TEAM)
    }

    /** What a pre-reset unsigned run left behind in `~/.tenshu`. */
    private fun populateLegacy(): File {
        val store = File(legacyRoot, PKG).apply { mkdirs() }
        File(store, "objects.db").writeText("pre-reset identity")
        File(legacyRoot, "control.settings.properties").writeText("legacy=1")
        return store
    }

    @Test
    fun explicitDataDirOverrideWins() {
        System.clearProperty(SocketPathResolver.TEAM_ID_PROPERTY)
        val dir = File(fakeHome, "override").path
        System.setProperty(DesktopAppPaths.DIR_PROPERTY, dir)

        assertEquals(dir, DesktopAppPaths.root().path)
        assertEquals("$dir/$PKG", DesktopAppPaths.dataDir(PKG).path)
        assertEquals("$dir/control.settings.properties", DesktopAppPaths.sharedFile("control.settings.properties").path)
    }

    @Test
    fun fallsBackToHomeTenshu_whenNoOverrideAndNoTeamId() {
        System.clearProperty(DesktopAppPaths.DIR_PROPERTY)
        System.clearProperty(SocketPathResolver.TEAM_ID_PROPERTY)
        System.setProperty("os.name", "Mac OS X")

        assertEquals(legacyRoot.path, DesktopAppPaths.root().path)
        assertEquals(File(legacyRoot, PKG).path, DesktopAppPaths.dataDir(PKG).path)
    }

    @Test
    fun linuxUsesHomeTenshu_evenWithATeamId() {
        // `~/.tenshu` is still the only root on Linux: a Team ID means nothing there.
        System.clearProperty(DesktopAppPaths.DIR_PROPERTY)
        System.setProperty("os.name", "Linux")
        System.setProperty(SocketPathResolver.TEAM_ID_PROPERTY, TEAM)
        val store = populateLegacy()

        assertEquals(legacyRoot.path, DesktopAppPaths.root().path)
        assertEquals(store.path, DesktopAppPaths.dataDir(PKG).path)
        assertEquals("pre-reset identity", File(store, "objects.db").readText())
    }

    @Test
    fun packagedMac_usesOnlyTheGroupContainer() {
        packagedMac()

        assertEquals(container.path, DesktopAppPaths.root().path)
        assertEquals(File(container, PKG).path, DesktopAppPaths.dataDir(PKG).path)
        assertEquals(SocketPathResolver.appGroupContainer(), DesktopAppPaths.root().path)
        assertFalse(legacyRoot.exists(), "a packaged launch must not even create ~/.tenshu")
    }

    @Test
    fun packagedMac_neverAdoptsLegacyData_evenWithTheOldLauncherFlag() {
        // ⚠⚠ The adoption is gone. A bundle signed before its removal still carries the flag in
        // its cfg; it must be inert, and `~/.tenshu` must be left exactly where it was.
        packagedMac()
        System.setProperty(STALE_ADOPT_PROPERTY, "true")
        val legacyStore = populateLegacy()

        val dataDir = DesktopAppPaths.dataDir(PKG)
        val settings = DesktopAppPaths.sharedFile("control.settings.properties")

        assertEquals(File(container, PKG).path, dataDir.path)
        assertTrue(dataDir.list().isNullOrEmpty(), "container must start empty, got ${dataDir.list()?.toList()}")
        assertFalse(settings.exists(), "loose legacy settings must not be carried into the container")
        assertEquals("pre-reset identity", File(legacyStore, "objects.db").readText())
        assertEquals("legacy=1", File(legacyRoot, "control.settings.properties").readText())
        assertFalse(File(legacyRoot, "MOVED.txt").exists())
    }

    private companion object {
        const val TEAM = "ABCDE12345"
        const val PKG = "app.tenshu.control.desktop"

        /** The launcher flag the removed adoption read — kept here only to prove it is inert. */
        const val STALE_ADOPT_PROPERTY = "tenshu.data.adoptLegacyRoot"
    }
}
