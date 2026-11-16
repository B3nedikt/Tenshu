package app.tenshu.control.data

import org.junit.Assume.assumeFalse
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Exercises the Kotlin glue of [DesktopBiometricUnlockDelegate] — the dormant
 * fallback and the helper token → [EnrollResult]/[UnwrapResult] mapping — by
 * injecting a fake helper script. The real `Security.framework` round-trip is
 * on-device / signed-build only.
 */
class DesktopBiometricUnlockDelegateTest {

    private val dir: File = Files.createTempDirectory("biohelper").toFile()
    private val copy = BiometricPromptCopy(title = "Unlock", negativeButton = "Use passphrase")
    private val host = BiometricPromptHost()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    /** Writes an executable fake helper that prints [line] for every command (after draining stdin). */
    private fun fakeHelper(line: String): String {
        requireShellScripts()
        val script = File(dir, "helper-${line.hashCode()}.sh")
        script.writeText("#!/bin/sh\ncat >/dev/null 2>&1\necho '$line'\n")
        script.setExecutable(true)
        return script.absolutePath
    }

    /**
     * A `#!/bin/sh` helper cannot run on Windows (`CreateProcess error=193`); the keychain helper it
     * stands in for is macOS-only anyway (desktop-windows-port.md §3.8). Skips the calling test.
     */
    private fun requireShellScripts() = assumeFalse(
        "shell-script helpers cannot run on Windows",
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true),
    )

    @Test
    fun dormant_whenNoHelperConfigured() = runBlocking {
        val d = DesktopBiometricUnlockDelegate(helperPath = null)
        assertFalse(d.isAvailable())
        assertFalse(d.hasEnrolled())
        assertEquals(EnrollResult.NotAvailable, d.enrollWrapKey(ByteArray(32), host, copy))
        assertEquals(UnwrapResult.NotAvailable, d.unwrapWrapKey(host, copy))
        d.clear() // no throw
    }

    @Test
    fun dormant_whenHelperPathMissing() = runBlocking {
        val d = DesktopBiometricUnlockDelegate(helperPath = File(dir, "nope").absolutePath)
        assertFalse(d.isAvailable())
        assertFalse(d.hasEnrolled())
    }

    @Test
    fun isAvailableAndEnrolled_reflectHelperToken() = runBlocking {
        assertTrue(DesktopBiometricUnlockDelegate(fakeHelper("yes")).isAvailable())
        assertFalse(DesktopBiometricUnlockDelegate(fakeHelper("no")).isAvailable())
        assertTrue(DesktopBiometricUnlockDelegate(fakeHelper("yes")).hasEnrolled())
        assertFalse(DesktopBiometricUnlockDelegate(fakeHelper("no")).hasEnrolled())
    }

    @Test
    fun enroll_mapsTokens() = runBlocking {
        assertEquals(EnrollResult.Success, enroll("ok"))
        assertEquals(EnrollResult.CancelledByUser, enroll("cancelled"))
        assertEquals(EnrollResult.NotAvailable, enroll("unavailable"))
        assertEquals(EnrollResult.Failure("boom"), enroll("failure:boom"))
    }

    @Test
    fun enroll_passesBase64WrapKeyOnStdin() = runBlocking {
        requireShellScripts()
        val capture = File(dir, "stdin.txt")
        val script = File(dir, "capture.sh")
        script.writeText("#!/bin/sh\ncat > '${capture.absolutePath}'\necho ok\n")
        script.setExecutable(true)

        val wrapKey = ByteArray(32) { it.toByte() }
        assertEquals(EnrollResult.Success, DesktopBiometricUnlockDelegate(script.absolutePath).enrollWrapKey(wrapKey, host, copy))
        assertEquals(Base64.getEncoder().encodeToString(wrapKey), capture.readText().trim())
    }

    @Test
    fun unwrap_mapsTokens() = runBlocking {
        val bytes = ByteArray(32) { (it + 1).toByte() }
        val ok = DesktopBiometricUnlockDelegate(fakeHelper("ok:${Base64.getEncoder().encodeToString(bytes)}"))
            .unwrapWrapKey(host, copy)
        assertTrue(ok is UnwrapResult.Success && ok.wrapKey.contentEquals(bytes))

        assertEquals(UnwrapResult.CancelledByUser, unwrap("cancelled"))
        assertEquals(UnwrapResult.NotAvailable, unwrap("not_found"))
        assertEquals(UnwrapResult.KeyInvalidated, unwrap("invalidated"))
        assertEquals(UnwrapResult.NotAvailable, unwrap("unavailable"))
        assertEquals(UnwrapResult.Failure("nope"), unwrap("failure:nope"))
    }

    private suspend fun enroll(token: String): EnrollResult =
        DesktopBiometricUnlockDelegate(fakeHelper(token)).enrollWrapKey(ByteArray(32), host, copy)

    private suspend fun unwrap(token: String): UnwrapResult =
        DesktopBiometricUnlockDelegate(fakeHelper(token)).unwrapWrapKey(host, copy)

    // --- bundle-relative resolution (Slice 2) ---
    // Mirrors the packaged layout <App>.app/Contents/{MacOS/<launcher>, Helpers/…},
    // like LaunchAgentManagerTest.trayBinaryForResolvesEmbeddedHelperWhenPresent.

    private fun launcherIn(appName: String): File {
        val macos = File(dir, "$appName/Contents/MacOS").apply { mkdirs() }
        return File(macos, "TenshuControl").apply { writeText("launcher") }
    }

    @Test
    fun bundledHelper_prefersNestedSignedApp() {
        val launcher = launcherIn("Nested")
        val inner = File(dir, "Nested/Contents/Helpers/keychain-helper.app/Contents/MacOS")
            .apply { mkdirs() }
        val helper = File(inner, "keychain-helper").apply { writeText("bin") }
        // A bare binary also present — the nested .app must win (it carries the profile).
        File(dir, "Nested/Contents/Helpers/keychain-helper").writeText("bare")

        assertEquals(helper.absolutePath, bundledKeychainHelperFor(launcher.absolutePath))
    }

    @Test
    fun bundledHelper_fallsBackToBareBinary() {
        val launcher = launcherIn("Bare")
        val bare = File(dir, "Bare/Contents/Helpers/keychain-helper").apply {
            parentFile.mkdirs(); writeText("bare")
        }
        assertEquals(bare.absolutePath, bundledKeychainHelperFor(launcher.absolutePath))
    }

    @Test
    fun bundledHelper_nullWhenAbsentOrNoBundle() {
        assertNull(bundledKeychainHelperFor(launcherIn("Empty").absolutePath))
        assertNull(bundledKeychainHelperFor(null))
    }

    /**
     * ⚠⚠ THE RA-5 guard, and the reason the entitlement-free build has its own name.
     *
     * `applock-helper` is the same Swift source compiled with `swiftc` and ad-hoc signed, so it
     * answers `is-available` **"yes"** — that verb is only an `LAContext` probe — while
     * `enroll`/`unwrap` cannot work without the provisioning profile. If the biometric resolver
     * accepted it, the UI would offer an enrolment that always fails. Dormancy is the honest
     * answer, so this asserts the biometric path stays blind to it.
     */
    @Test
    fun theBiometricPath_ignoresTheEntitlementFreeAppLockHelper() {
        val launcher = launcherIn("AppLockOnly")
        File(dir, "AppLockOnly/Contents/Helpers").apply { mkdirs() }
            .resolve("applock-helper").writeText("bin")

        assertNull(
            bundledKeychainHelperFor(launcher.absolutePath),
            "an entitlement-free helper cannot hold a wrap key — biometric must stay dormant",
        )
    }

    /** …while the soft lock, which needs only the two entitlement-free verbs, does resolve it. */
    @Test
    fun theAppLockPath_usesTheEntitlementFreeHelperWhenItIsAllThereIs() {
        val launcher = launcherIn("AppLockOnly2")
        val helper = File(dir, "AppLockOnly2/Contents/Helpers").apply { mkdirs() }
            .resolve("applock-helper").apply { writeText("bin") }

        assertEquals(helper.absolutePath, bundledAppLockHelperFor(launcher.absolutePath))
    }

    /**
     * ⚠ A signed build must behave exactly as it did before RA-5: the full-capability helper wins,
     * so nothing about the Touch ID path changes for someone who had it working.
     */
    @Test
    fun theAppLockPath_prefersTheFullCapabilityHelperWhenBothArePresent() {
        val launcher = launcherIn("Both")
        val helpers = File(dir, "Both/Contents/Helpers").apply { mkdirs() }
        val inner = File(helpers, "keychain-helper.app/Contents/MacOS").apply { mkdirs() }
        val signed = File(inner, "keychain-helper").apply { writeText("bin") }
        helpers.resolve("applock-helper").writeText("bin")

        assertEquals(signed.absolutePath, bundledAppLockHelperFor(launcher.absolutePath))
    }

    @Test
    fun theAppLockPath_isNullOutsideABundle() {
        assertNull(bundledAppLockHelperFor(launcherIn("Empty2").absolutePath))
        assertNull(bundledAppLockHelperFor(null))
    }

    /**
     * ⚠⚠ The hard unlock and enrolment spawn the helper through this class's **own** runner, so
     * fixing the soft lock and Ph-reauth left them behaving exactly as the original bug did: the
     * sheet takes the foreground, the helper exits, and Control is left behind the frontmost window
     * (`control-keep-device-unlocked.md` §6.1).
     */
    @Test
    fun raisesToForeground_whenTheSheetWasPresented() = runBlocking {
        var raised = 0
        DesktopBiometricUnlockDelegate(fakeHelper("ok")) { raised++ }
            .enrollWrapKey(ByteArray(32), host, copy)
        assertEquals(1, raised, "enroll prompted via .userPresence — activation must come back")

        raised = 0
        val wrapped = Base64.getEncoder().encodeToString(ByteArray(32))
        val unwrapped = DesktopBiometricUnlockDelegate(fakeHelper("ok:$wrapped")) { raised++ }
            .unwrapWrapKey(host, copy)
        assertTrue(unwrapped is UnwrapResult.Success, "precondition: a real successful unwrap")
        assertEquals(1, raised, "unwrap is the hard unlock — the same sheet, the same raise")

        raised = 0
        DesktopBiometricUnlockDelegate(fakeHelper("cancelled")) { raised++ }
            .unwrapWrapKey(host, copy)
        assertEquals(1, raised, "a cancelled sheet was still on screen and still took activation")
    }

    /**
     * ⚠⚠ The half that would be a **worse** bug than the one being fixed. `enroll` under ad-hoc
     * signing fails at `SecItemAdd` *before* anything is presented — the common desktop
     * configuration, not an edge — so a blanket raise would have Control shoving itself in front of
     * a user who was never interrupted. Same for an item that is simply absent.
     */
    @Test
    fun doesNotRaise_whenNoSheetWasShown() = runBlocking {
        for (token in listOf("failure:missing entitlement", "unavailable")) {
            var raised = 0
            DesktopBiometricUnlockDelegate(fakeHelper(token)) { raised++ }
                .enrollWrapKey(ByteArray(32), host, copy)
            assertEquals(0, raised, "enroll '$token' fails before the prompt — nothing to raise from")
        }

        for (token in listOf("not_found", "invalidated", "unavailable")) {
            var raised = 0
            DesktopBiometricUnlockDelegate(fakeHelper(token)) { raised++ }
                .unwrapWrapKey(host, copy)
            assertEquals(0, raised, "unwrap '$token' is refused without prompting")
        }

        var raised = 0
        DesktopBiometricUnlockDelegate(helperPath = null) { raised++ }
            .unwrapWrapKey(host, copy)
        assertEquals(0, raised, "a dormant helper never runs, so it never takes activation")
    }

}
