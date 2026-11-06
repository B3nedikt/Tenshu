package app.tenshu.control.data

import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop (macOS) [BiometricUnlockDelegate] — gates the unlock wrap-key behind
 * **Touch ID / the login password** (`docs/proposals/desktop-masterkeystore.md`
 * §4, Phase 2). The JVM cannot call `Security.framework` /
 * `LocalAuthentication`, so this spawns the Swift [`KeychainHelper`]
 * (`control-desktop/macos/KeychainHelper/`) for each op — the same shell-out
 * shape Phase 1 uses for the at-rest wrapping key. The helper performs the
 * keychain op and presents the OS auth sheet.
 *
 * **Dormant unless a helper is configured.** [helperPath] resolves from
 * `-Dtenshu.keychainHelper` / `$TENSHU_KEYCHAIN_HELPER` (a bundled location is
 * wired when packaging lands, Phase 4). With no executable helper — i.e. every
 * current `:control-desktop:run` dev session, and all non-macOS hosts —
 * [isAvailable] returns `false` and the UI stays on passphrase-only, exactly as
 * before. The biometric path therefore only "lights up" in a packaged, signed
 * build that ships the helper. The data-protection keychain item also needs a
 * real signing identity (see the helper README) — under ad-hoc signing `enroll`
 * reports failure and the UI falls back to passphrase.
 *
 * Enroll-first contract (shared with Android): the UI generates a random
 * wrap-key, calls [enrollWrapKey], and only on `Success` calls
 * `setUpBiometricUnlock(wrapKey)` on the proxy.
 */
class DesktopBiometricUnlockDelegate(
    helperPath: String? = resolveHelperPath(),
    /**
     * ⚠⚠ **Raises Control back to the front after a helper sheet, or authenticating looks like a
     * crash.** Same mechanism as [DesktopAppLock] — the helper has no bundle identity, so macOS
     * hands it the foreground and never hands it back. This delegate has its **own** runner, which
     * is why it needed its own raise: fixing the soft lock and Ph-reauth left the hard unlock
     * (`LockedScreen` → [unwrapWrapKey]) and [enrollWrapKey] behaving exactly as the original bug
     * did. Injected so the policy is testable while the effect stays a device concern.
     */
    private val raiseToForeground: () -> Unit = ::requestForegroundForThisProcess,
) : BiometricUnlockDelegate {

    // An explicitly-supplied path is trusted as-is (tests / env override); only
    // auto-resolution is macOS-gated, so the result is OS-independent for tests.
    private val helper: File? = helperPath?.let(::File)?.takeIf { it.canExecute() }

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        helper?.let { run(it, "is-available", timeoutSeconds = QUICK_TIMEOUT)?.token == "yes" } ?: false
    }

    override suspend fun hasEnrolled(): Boolean = withContext(Dispatchers.IO) {
        helper?.let { run(it, "has-enrolled", timeoutSeconds = QUICK_TIMEOUT)?.token == "yes" } ?: false
    }

    override suspend fun enrollWrapKey(
        wrapKey: ByteArray,
        host: BiometricPromptHost,
        copy: BiometricPromptCopy,
    ): EnrollResult = withContext(Dispatchers.IO) {
        val h = helper ?: return@withContext EnrollResult.NotAvailable
        val res = run(h, "enroll", reason(copy), stdin = Base64.getEncoder().encodeToString(wrapKey))
        // ⚠ Before the early return: a non-answering helper is still not a raise (see
        // [helperSheetWasPresented]), but the ordering keeps the two decisions in one place.
        if (helperSheetWasPresented("enroll", res?.token)) raiseToForeground()
        res ?: return@withContext EnrollResult.Failure("keychain helper did not respond")
        when (val token = res.token) {
            "ok" -> EnrollResult.Success
            "cancelled" -> EnrollResult.CancelledByUser
            "unavailable" -> EnrollResult.NotAvailable
            else -> EnrollResult.Failure(failureMessage(token))
        }
    }

    override suspend fun unwrapWrapKey(
        host: BiometricPromptHost,
        copy: BiometricPromptCopy,
    ): UnwrapResult = withContext(Dispatchers.IO) {
        val h = helper ?: return@withContext UnwrapResult.NotAvailable
        val res = run(h, "unwrap", reason(copy))
        if (helperSheetWasPresented("unwrap", res?.token)) raiseToForeground()
        res ?: return@withContext UnwrapResult.Failure("keychain helper did not respond")
        val token = res.token
        when {
            token.startsWith("ok:") -> {
                val decoded = try {
                    Base64.getDecoder().decode(token.removePrefix("ok:"))
                } catch (e: IllegalArgumentException) {
                    return@withContext UnwrapResult.Failure("malformed wrap-key payload")
                }
                UnwrapResult.Success(decoded)
            }
            token == "cancelled" -> UnwrapResult.CancelledByUser
            token == "not_found" -> UnwrapResult.NotAvailable
            token == "invalidated" -> UnwrapResult.KeyInvalidated
            token == "unavailable" -> UnwrapResult.NotAvailable
            else -> UnwrapResult.Failure(failureMessage(token))
        }
    }

    override fun clear() {
        helper?.let { run(it, "clear", timeoutSeconds = QUICK_TIMEOUT) }
    }

    private fun reason(copy: BiometricPromptCopy): String =
        copy.description ?: copy.subtitle ?: copy.title

    private fun failureMessage(token: String): String? =
        token.removePrefix("failure:").trim().ifBlank { null }

    private data class HelperResult(val token: String)

    /** Runs `<helper> <command> [reason]`, returns the first stdout line, or `null` on any failure. */
    private fun run(
        helper: File,
        command: String,
        reason: String? = null,
        stdin: String? = null,
        timeoutSeconds: Long = PROMPT_TIMEOUT,
    ): HelperResult? = try {
        val cmd = buildList {
            add(helper.absolutePath)
            add(command)
            reason?.let { add(it) }
        }
        val proc = ProcessBuilder(cmd).start()
        proc.outputStream.use { out -> stdin?.let { out.write(it.toByteArray()) } }
        val stdout = proc.inputStream.readBytes().decodeToString()
        if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            null
        } else {
            HelperResult(stdout.lineSequence().firstOrNull()?.trim().orEmpty())
        }
    } catch (e: Exception) {
        null
    }

    private companion object {
        // The auth prompt waits on the user; metadata ops are fast.
        const val PROMPT_TIMEOUT = 120L
        const val QUICK_TIMEOUT = 10L

        fun resolveHelperPath(): String? {
            System.getProperty("tenshu.keychainHelper")?.takeIf { it.isNotBlank() }?.let { return it }
            System.getenv("TENSHU_KEYCHAIN_HELPER")?.takeIf { it.isNotBlank() }?.let { return it }
            // Packaged Control.app: the helper ships at Contents/Helpers/ next to
            // the tray agent (Slice 2). Resolve it relative to the running launcher
            // so biometric works with NO env var, in every launch context — the
            // tray cold-launch and the login LaunchAgent have no shell environment.
            return bundledKeychainHelperFor(ProcessHandle.current().info().command().orElse(null))
        }
    }
}

/**
 * Resolves the keychain helper embedded in the packaged `Control.app`, relative
 * to the running launcher (`…/Contents/MacOS/<launcher>` → `…/Contents/Helpers/`),
 * mirroring [app.tenshu.control.lifecycle.LaunchAgentManager.trayBinaryFor].
 *
 * Prefers the nested signed `.app` — it carries the provisioning profile the
 * data-protection keychain requires (free-team dev); falls back to a bare binary
 * (Developer ID inside-out signing needs no profile). Returns `null` outside a
 * packaged bundle (e.g. `:control-desktop:run`), leaving biometric dormant.
 *
 * `internal` (not in the private companion) so it's unit-testable without a real
 * `ProcessHandle`.
 */
internal fun bundledKeychainHelperFor(daemonExecPath: String?): String? {
    val helpers = bundledHelpersDirFor(daemonExecPath) ?: return null
    File(helpers, "keychain-helper.app/Contents/MacOS/keychain-helper")
        .takeIf { it.isFile }?.let { return it.absolutePath }
    File(helpers, "keychain-helper")
        .takeIf { it.isFile }?.let { return it.absolutePath }
    // ⚠⚠ Deliberately does NOT fall through to `applock-helper`. That binary is the same source
    // built without entitlements (RA-5, `control-desktop/build.gradle.kts`), so it answers
    // `is-available` "yes" — that verb is only an `LAContext` probe — while `enroll`/`unwrap`
    // cannot work at all. Accepting it here would make the biometric UI offer an enrolment that
    // always fails, which is strictly worse than the honest dormancy of returning null.
    return null
}

/**
 * Resolves a helper able to answer the **entitlement-free** verbs — `is-available` and
 * `authenticate` — for the soft app-lock and the Ph-reauth gates
 * (`docs/proposals/control-keep-device-unlocked.md` §6.1, RA-5).
 *
 * Prefers the full-capability helper when a signed build embedded one, so such a build behaves
 * exactly as before; falls back to `applock-helper`, the ad-hoc-signed swiftc build that ships in
 * **every** macOS bundle. That fallback is what took Ph-reauth on desktop from "always passes
 * through" to actually asking, without an Apple account, a team id, or a GUI-minted profile.
 */
internal fun bundledAppLockHelperFor(daemonExecPath: String?): String? {
    bundledKeychainHelperFor(daemonExecPath)?.let { return it }
    val helpers = bundledHelpersDirFor(daemonExecPath) ?: return null
    return File(helpers, "applock-helper").takeIf { it.isFile }?.absolutePath
}

/** `…/Contents/MacOS/<launcher>` → `…/Contents/Helpers`, or null outside a packaged bundle. */
private fun bundledHelpersDirFor(daemonExecPath: String?): File? {
    val contents = daemonExecPath?.let(::File)?.parentFile?.parentFile ?: return null
    return File(contents, "Helpers")
}

// Inert no-arg marker; the implicit primary constructor is public so the
// `:control-shared` Compose resolver can build it. The macOS auth sheet is presented
// by the helper process, so no window context is needed.
actual class BiometricPromptHost
