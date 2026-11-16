package org.tenshu.core.common.transport

import org.junit.Assume.assumeFalse
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins how a packaged app finds and runs its `appgroup-claim` helper. The macOS side (that the
 * claim lifts the refusal) is device-verified, not unit-testable. See [AppGroupContainerClaim].
 * Every helper here is a shell script in a temp dir: nothing touches a real container.
 */
class AppGroupContainerClaimTest {

    private val root: File = Files.createTempDirectory("appgroup-claim-test").toFile()

    @AfterTest
    fun cleanup() {
        AppGroupContainerClaim.resetForTest()
        root.deleteRecursively()
    }

    /**
     * Skips a case whose fake helper is a `#!/bin/sh` script, which Windows cannot execute
     * (`CreateProcess error=193`). The claim itself is macOS-only, so nothing is lost there; the
     * lookup cases (`helperFor`, an absent helper) need no script and still run everywhere.
     */
    private fun needsShellScripts() = assumeFalse(
        "shell-script helpers cannot run on Windows",
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true),
    )

    @Test
    fun `a concurrent caller waits until the claim has finished`() {
        needsShellScripts()
        // The helper takes a while and leaves a marker when done. The second caller must not
        // return before the marker exists, or it would open the container before the grant does.
        val done = File(root, "done")
        val launcher = bundle(withHelper = true, script = "#!/bin/sh\nsleep 1\ntouch '${done.path}'\n")
        AppGroupContainerClaim.resetForTest()
        val first = Thread { AppGroupContainerClaim.ensureClaimed("G", launcher.path) }.apply { start() }
        Thread.sleep(200)
        AppGroupContainerClaim.ensureClaimed("G", launcher.path)
        assertTrue(done.exists(), "second caller returned before the claim finished")
        first.join()
    }

    @Test
    fun `the claim runs once per process`() {
        needsShellScripts()
        val count = File(root, "count")
        val launcher = bundle(withHelper = true, script = "#!/bin/sh\necho x >> '${count.path}'\n")
        AppGroupContainerClaim.resetForTest()
        repeat(3) { AppGroupContainerClaim.ensureClaimed("G", launcher.path) }
        assertEquals(1, count.readLines().size)
    }

    @Test
    fun `the claim returns the Foundation-resolved container path`() {
        needsShellScripts()
        val launcher = bundle(
            withHelper = true,
            script = "#!/bin/sh\nprintf '/real/group/container\\n'\n",
        )
        AppGroupContainerClaim.resetForTest()
        assertEquals(
            "/real/group/container",
            AppGroupContainerClaim.ensureClaimed("TEAM.app.tenshu.shared", launcher.path),
        )
    }

    @Test
    fun `a warning on stderr never becomes part of the container path`() {
        needsShellScripts()
        val helper = helperScript(
            File(root, "noisy"),
            "#!/bin/sh\necho 'warning: something' >&2\nprintf '/real/group/container\\n'\n",
        )
        assertEquals("/real/group/container", AppGroupContainerClaim.claimPath(helper, "G"))
    }

    @Test
    fun `output that is not one absolute path is refused`() {
        needsShellScripts()
        val helper = helperScript(File(root, "junk"), "#!/bin/sh\necho 'not a path'\n")
        assertNull(AppGroupContainerClaim.claimPath(helper, "G"))
        val failure = assertNotNull(AppGroupContainerClaim.claim(helper, "G"))
        assertTrue("no container path" in failure, failure)
    }

    private fun bundle(withHelper: Boolean, script: String = "#!/bin/sh\necho \"\$1\"\n"): File {
        val contents = File(root, "TenshuTest.app/Contents").apply { mkdirs() }
        val launcher = File(contents, "MacOS/TenshuTest").apply { parentFile.mkdirs(); writeText("") }
        if (withHelper) helperScript(File(contents, "Helpers/${AppGroupContainerClaim.HELPER_NAME}"), script)
        return launcher
    }

    private fun helperScript(file: File, script: String): File = file.apply {
        parentFile.mkdirs()
        writeText(script)
        setExecutable(true)
    }

    @Test
    fun `no launcher means a dev run, so no helper`() {
        assertNull(AppGroupContainerClaim.helperFor(null))
        assertNull(AppGroupContainerClaim.helperFor(""))
    }

    @Test
    fun `a launcher outside an app bundle is not a packaged run`() {
        val launcher = File(root, "bin/java").apply { parentFile.mkdirs(); writeText("") }
        assertNull(AppGroupContainerClaim.helperFor(launcher.path))
    }

    @Test
    fun `the helper is found beside the launcher in Contents Helpers`() {
        val launcher = bundle(withHelper = true)
        val helper = assertNotNull(AppGroupContainerClaim.helperFor(launcher.path))
        assertEquals(File(launcher.parentFile.parentFile, "Helpers/appgroup-claim"), helper)
    }

    @Test
    fun `a packaged app without the helper yields none rather than failing`() {
        assertNull(AppGroupContainerClaim.helperFor(bundle(withHelper = false).path))
    }

    @Test
    fun `a helper that exits 0 is a successful claim and receives the group id`() {
        needsShellScripts()
        val seen = File(root, "seen")
        val helper = helperScript(File(root, "ok"), "#!/bin/sh\nprintf '%s' \"\$1\" > '${seen.path}'\n")
        assertNull(AppGroupContainerClaim.claim(helper, "TEAM.app.tenshu.shared"))
        assertEquals("TEAM.app.tenshu.shared", seen.readText())
    }

    @Test
    fun `a failing helper is reported with its exit code and output`() {
        needsShellScripts()
        val helper = helperScript(File(root, "fail"), "#!/bin/sh\necho 'no container'\nexit 1\n")
        val failure = assertNotNull(AppGroupContainerClaim.claim(helper, "G"))
        assertTrue("exited 1" in failure && "no container" in failure, failure)
    }

    @Test
    fun `a hanging helper is killed and reported, never blocking startup`() {
        needsShellScripts()
        val helper = helperScript(File(root, "hang"), "#!/bin/sh\nsleep 30\n")
        val failure = assertNotNull(AppGroupContainerClaim.claim(helper, "G", timeoutSeconds = 1))
        assertTrue("timed out" in failure, failure)
    }

    @Test
    fun `a helper that cannot start is reported, not thrown`() {
        val failure = assertNotNull(AppGroupContainerClaim.claim(File(root, "absent"), "G"))
        assertTrue("could not run" in failure, failure)
    }
}
