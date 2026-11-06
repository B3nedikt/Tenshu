package org.tenshu.core.common.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every iOS app that can be *opened* declares the scheme `OpenTargetUrl` will build for it
 * (`control-cross-app-open.md` §5i).
 *
 * ⚠⚠ **This is the iOS shape of §5f.2's rule.** On Android a handler that cannot show the row
 * launches and displays nothing; on iOS an app whose scheme is undeclared cannot be launched at
 * all — `openURL` reports "nothing handles this", which the caller renders as *"that app isn't
 * installed"*. Same class of lie, told by a different mechanism, and the only thing standing
 * between the derived scheme and the declared one is this test.
 *
 * ⚠ `project.yml` is the source: `Info.plist` is generated and gitignored, so asserting on the
 * plist would assert on a build artifact that may not exist.
 */
class DeclaredIosSchemesTest {

    /** ⚠ Must match `HandlerTable`'s values — see the assertion at the bottom. */
    private val openableApps = listOf("drive", "calendar", "timeline", "maps", "fit")

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return dir
    }

    @Test
    fun everyOpenableAppDeclaresItsDerivedScheme() {
        val root = repoRoot()
        openableApps.forEach { app ->
            val projectYml = File(root, "$app-ios/project.yml")
            assertTrue(projectYml.exists(), "no project.yml for $app-ios at ${projectYml.path}")
            val expected = OpenTargetUrl.schemeFor("app.tenshu.$app")
            assertEquals("tenshu-$app", expected, "the derivation changed under this test")
            assertTrue(
                projectYml.readText().contains("- $expected"),
                "$app-ios/project.yml does not declare CFBundleURLSchemes entry '$expected' — " +
                    "an open for it would report 'that app isn't installed'",
            )
        }
    }

    @Test
    fun theListMatchesWhatControlActuallyRoutes() {
        // ⚠⚠ The guard against this test going stale in the direction that matters: a type routed
        // to an app whose scheme is undeclared. Derived from the handler table rather than
        // hand-listed, so adding a route without a scheme fails HERE rather than on a device.
        val routed = listOf(
            "tenshu/calendar/eventOccurrence",
            "tenshu/file",
            "tenshu/place/place",
            "tenshu/timeline/moment",
            "tenshu/health/workout",
        ).flatMap { org.tenshu.core.common.app.HandlerTable.handlersFor(it) }
            .distinct()
            .mapNotNull { OpenTargetUrl.schemeFor(it) }
            .sorted()

        assertEquals(openableApps.map { "tenshu-$it" }.sorted(), routed)
    }
}
