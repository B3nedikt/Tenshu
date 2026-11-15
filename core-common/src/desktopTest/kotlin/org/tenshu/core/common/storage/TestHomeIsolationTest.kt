package org.tenshu.core.common.storage

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the test-JVM home isolation that root `build.gradle.kts` applies to every desktop/JVM
 * `Test` task. Without it, desktop tests resolved app data into the developer's real
 * `~/.tenshu/<appId>/`, so two gates on one machine (parallel worktrees) shared files there and
 * failed each other — `DesktopMasterKeyStore` renaming the other run's `secrets.enc.tmp` away.
 *
 * ⚠ If this goes red, the build-side block stopped applying: do not weaken the test.
 */
class TestHomeIsolationTest {

    private val savedDataDir = System.getProperty(DesktopAppPaths.DIR_PROPERTY)

    @AfterTest
    fun restore() {
        if (savedDataDir == null) System.clearProperty(DesktopAppPaths.DIR_PROPERTY)
        else System.setProperty(DesktopAppPaths.DIR_PROPERTY, savedDataDir)
    }

    private fun realHome(): String = assertNotNull(
        System.getProperty(DesktopAppPaths.TEST_REAL_HOME_PROPERTY),
        "the test JVM was started without ${DesktopAppPaths.TEST_REAL_HOME_PROPERTY} — " +
            "root build.gradle.kts no longer isolates this task's home",
    )

    @Test
    fun theTestJvmDoesNotRunInTheDevelopersHome() {
        val home = System.getProperty("user.home")
        assertNotEquals(realHome(), home, "user.home is the developer's real home")
        assertTrue(File(home).isDirectory, "the per-run home must exist: $home")
    }

    @Test
    fun theDefaultDataRootLandsInsideThePerRunHome() {
        val home = File(System.getProperty("user.home")).absoluteFile.normalize()
        val root = DesktopAppPaths.root().absoluteFile.normalize()
        // An explicit override (tenshu.data.dir / TENSHU_DATA_DIR) is allowed to point elsewhere;
        // the property this suite controls is that the DEFAULT arm follows the per-run home.
        if (System.getProperty(DesktopAppPaths.DIR_PROPERTY) == null && System.getenv(DesktopAppPaths.DIR_ENV) == null) {
            assertTrue(root.startsWith(home), "data root $root is outside the per-run home $home")
        }
        assertFalse(root.startsWith(File(realHome(), ".tenshu").absoluteFile), "data root is the real ~/.tenshu")
    }

    @Test
    fun resolvingIntoTheRealHomeIsRefused() {
        // `root()` resolves without creating anything, so this never touches the real home.
        System.setProperty(DesktopAppPaths.DIR_PROPERTY, File(realHome(), ".tenshu").path)
        assertFailsWith<IllegalStateException> { DesktopAppPaths.root() }

        System.setProperty(DesktopAppPaths.DIR_PROPERTY, File(realHome(), "Library/Group Containers/X.app.tenshu.shared").path)
        assertFailsWith<IllegalStateException> { DesktopAppPaths.root() }
    }
}
