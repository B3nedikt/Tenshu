package app.tenshu.control.data

import app.tenshu.macos.MacEmbeddedAuthentication
import app.tenshu.macos.MacLocalAuthentication
import java.awt.Desktop
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Desktop (macOS) soft app-lock backend
 * (`docs/proposals/control-keep-device-unlocked.md` §4.2, "Require unlock").
 *
 * Reports [isAvailable] and performs a **keyless Touch ID / login-password** check
 * (`LAContext.evaluatePolicy(.deviceOwnerAuthentication)`) — **in-process** through FFM
 * when [inProcess] is wired ([macInProcessLocalAuth]), else by shelling out to the Swift
 * helper's `authenticate` verb. It touches **no key material** — the realm stays
 * headless; this is a UI barrier only.
 *
 * Unlike the wrap-key verbs, `authenticate` needs no keychain entitlement, so it
 * works under ad-hoc signing. It is still **dormant unless a helper is configured**
 * ([helperPath] resolves from `-Dtenshu.keychainHelper` / `$TENSHU_KEYCHAIN_HELPER`
 * or the packaged bundle) — so a bare `:control-desktop:run` and non-macOS hosts
 * report [isAvailable] `false` and the "Require unlock" toggle stays disabled,
 * exactly like the biometric delegate.
 */
class DesktopAppLock(
    helperPath: String? = resolveAppLockHelperPath(),
    /**
     * The same check made **in this process** ([macInProcessLocalAuth]), preferred over the helper
     * whenever it answers available — the sheet is then titled by Control's own bundle, not by a
     * spawned binary's. `null` (the default, and every test) means helper-only; production wires it
     * in `PlatformProxyModule`, because a live default would put a real sheet on a test run.
     */
    private val inProcess: InProcessLocalAuth? = null,
    /**
     * Touch ID drawn **inside Control's window** ([macEmbeddedLocalAuth]) — the soft lock's first
     * choice, with [authenticate]'s sheet as the password route. `null` (the default, and every
     * test) means sheet-only, for the same reason [inProcess] defaults to null.
     */
    private val embedded: EmbeddedLocalAuth? = null,
    /**
     * Injected so the *decision* to raise is testable while the *effect* stays where it belongs —
     * the same policy/effect split as [app.tenshu.control.data.shouldRaiseOnUnlock]. Whether AppKit
     * actually grants activation needs a device; **when** we ask for it is a table.
     */
    private val raiseToForeground: () -> Unit = ::requestForegroundForThisProcess,
) : AppLockAvailability, Reauthenticator {

    private val helper: File? = helperPath?.let(::File)?.takeIf { it.canExecute() }

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (inProcess?.isAvailable() == true) return@withContext true
        helper?.let { runHelper(it, "is-available", QUICK_TIMEOUT) == "yes" } ?: false
    }

    /**
     * [Reauthenticator] — the presence check in front of an irreversible action.
     * Same helper verb as the soft lock's gate; only the reason string differs, and the helper
     * sheet has no title/subtitle of its own, so only [ReauthPromptCopy.systemReason] is carried.
     *
     * ⚠ Dormant in a normal build: the helper is opt-in (`-Ptenshu.withKeychainHelper`) and
     * signing-gated, so this reports `Unavailable` and callers pass through (`confirm`).
     */
    override suspend fun authenticate(
        host: BiometricPromptHost,
        copy: ReauthPromptCopy,
    ): AppLockAuthResult = authenticate(copy.systemReason)

    /**
     * Present the OS auth sheet; maps the helper token to an [AppLockAuthResult].
     *
     * ⚠⚠ **Raises Control back to the front afterwards, or authenticating LOOKS LIKE A CRASH.**
     * `applock-helper` is a bare binary with no bundle identity, so macOS gives it the foreground to
     * present the `LAContext` sheet and, when it exits, activation does **not** come back to us —
     * Control's window drops behind whatever is frontmost, which users read as the app closing.
     * `Main.kt` raises for the soft lock, but only on an [AppLockController] lock→unlock edge, and
     * [Reauthenticator] deliberately never touches that controller — so the re-auth call sites
     * (forget-device, revoke-device, and the recovery-phrase screen) depend on this raise. Raising
     * here is the one place that knows a sheet was actually on screen, so it covers every caller of
     * either role at once.
     *
     * ⚠ Deliberately duplicated with `Main.kt`'s raise rather than replacing it: that one is
     * device-verified, and its `windowVisible` guard keeps a close-to-tray window from being yanked
     * forward. Asking for the foreground twice in a row is idempotent.
     *
     * ⚠ Only when a sheet was **presented** — [helperSheetWasPresented] owns that table and says why
     * it is a whitelist rather than "anything but `unavailable`". For this verb: `Cancelled` and
     * `Failure` DO raise (both are reachable only past the helper's `canEvaluatePolicy` guard, so
     * the sheet was up — a fumbled fingerprint is a `failure:` and needs the raise most of all),
     * while `unavailable` and a non-answering helper do not.
     */
    suspend fun authenticate(reason: String): AppLockAuthResult = withContext(Dispatchers.IO) {
        // ⚠ The in-process sheet keeps the raise too: it is still drawn by `coreautha`, a system
        // process, so Control being the *client* does not by itself prove activation comes back.
        inProcess?.takeIf { it.isAvailable() }?.let { local ->
            val result = local.authenticate(reason)
            if (result != AppLockAuthResult.Unavailable) raiseToForeground()
            return@withContext result
        }
        val h = helper ?: return@withContext AppLockAuthResult.Unavailable
        val token = runHelper(h, "authenticate", PROMPT_TIMEOUT, reason)
        if (helperSheetWasPresented("authenticate", token)) raiseToForeground()
        when (token) {
            "ok" -> AppLockAuthResult.Success
            "cancelled" -> AppLockAuthResult.Cancelled
            "unavailable" -> AppLockAuthResult.Unavailable
            else -> AppLockAuthResult.Failure // null (no response) or failure:<msg>
        }
    }

    /** Whether Touch ID can be drawn in the window right now — false with no sensor or a lockout. */
    suspend fun embeddedAvailable(): Boolean = withContext(Dispatchers.IO) {
        embedded?.isAvailable() == true
    }

    /**
     * Touch ID in [slot], waiting for a finger. No raise: nothing left the window.
     *
     * ⚠⚠ Biometrics only. [AppLockAuthResult.Failure] (five wrong fingers — Touch ID is now locked
     * until the password is entered) and [AppLockAuthResult.Unavailable] both mean "go to the
     * password sheet" ([authenticate]); the caller must, or the user is stuck behind a locked sensor.
     * Cancelling the coroutine takes the view down.
     */
    suspend fun authenticateEmbedded(slot: EmbeddedAuthSlot, reason: String): AppLockAuthResult {
        val auth = embedded ?: return AppLockAuthResult.Unavailable
        // Per call, set BEFORE `cancel()`: a call still queued behind the previous one's teardown has no
        // context for `cancel()` to invalidate, and would otherwise put up a view nobody is waiting on.
        val cancelled = AtomicBoolean(false)
        return coroutineScope {
            val call = async(Dispatchers.IO) { auth.authenticate(slot, reason) { cancelled.get() } }
            try {
                call.await()
            } catch (e: CancellationException) {
                // The blocked call only returns once the context is invalidated; without this the
                // scope would wait on it for the full timeout.
                cancelled.set(true)
                auth.cancel()
                throw e
            }
        }
    }

    /** Follow the reserved slot as the window is resized. */
    suspend fun moveEmbedded(slot: EmbeddedAuthSlot) {
        val auth = embedded ?: return
        withContext(Dispatchers.IO) { auth.move(slot) }
    }

    /** Runs `<helper> <command> [reason]`; returns the first stdout line, or `null` on any failure. */
    private fun runHelper(
        helper: File,
        command: String,
        timeoutSeconds: Long,
        reason: String? = null,
    ): String? = try {
        val cmd = buildList {
            add(helper.absolutePath)
            add(command)
            reason?.let { add(it) }
        }
        val proc = ProcessBuilder(cmd).start()
        proc.outputStream.close() // no stdin; signal EOF so a draining helper finishes
        val stdout = proc.inputStream.readBytes().decodeToString()
        if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            null
        } else {
            stdout.lineSequence().firstOrNull()?.trim()
        }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val PROMPT_TIMEOUT = 120L // the auth sheet waits on the user
        const val QUICK_TIMEOUT = 10L
    }
}

/**
 * Resolves a helper able to answer the soft lock's two verbs: the env override, else the packaged
 * bundle via [bundledAppLockHelperFor].
 *
 * ⚠ Not necessarily the *same* binary the biometric path uses. Every macOS bundle also carries
 * `applock-helper` — the same Swift source built with `swiftc` and ad-hoc signed, which can serve
 * `is-available`/`authenticate` (pure `LAContext`, no entitlement) but **not** the wrap-key verbs.
 * So this returns non-null in builds where the biometric path is dormant, which is the point: the
 * soft lock and the re-auth check do not inherit a signing gate that was never theirs.
 *
 * `null` only with no override and outside a packaged bundle (a bare `:control-desktop:run`, or a
 * non-macOS host), leaving the soft lock dormant.
 */
internal fun resolveAppLockHelperPath(): String? {
    System.getProperty("tenshu.keychainHelper")?.takeIf { it.isNotBlank() }?.let { return it }
    System.getenv("TENSHU_KEYCHAIN_HELPER")?.takeIf { it.isNotBlank() }?.let { return it }
    return bundledAppLockHelperFor(ProcessHandle.current().info().command().orElse(null))
}

/**
 * `[NSApp activateIgnoringOtherApps:]` via the JVM's route to it — the default [raise][DesktopAppLock]
 * effect, and the same call `:control-shared`'s `RaiseDialogToFront` needs for an async modal that
 * appears while Control is not the active application (hence public rather than internal).
 *
 * ⚠⚠ `Window.toFront()` is NOT a substitute and looks like it should be: it reorders windows *within*
 * an application, and macOS will not let a process that is not active steal activation that way. This
 * is process-level, which is also why it needs no window reference and can live here rather than in
 * `Main.kt`.
 *
 * ⚠ The two guards are what make this safe, not the `catch`: per the JDK source `getDesktop()` throws
 * only `HeadlessException` or `UnsupportedOperationException` when `!isDesktopSupported()`, and
 * `requestForeground` only via the very `isSupported` check made here. Headless is a real case for
 * this file — the unit tests run that way — and it must degrade to a no-op, not an exception.
 *
 * ⚠⚠ The catch LOGS rather than swallows. A silent one would reproduce exactly the bug this exists to
 * fix (window behind, reads as a crash) with no signal anywhere.
 */
fun requestForegroundForThisProcess() {
    runCatching {
        if (!Desktop.isDesktopSupported()) return
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
            desktop.requestForeground(true)
        }
    }.onFailure {
        println(
            "AppLock: requestForeground failed (${it::class.simpleName}: ${it.message}) — " +
                "Control will stay behind the frontmost window after the auth sheet closes."
        )
    }
}

/**
 * A presence check this process makes itself, with no helper binary — `LAContext` over FFM on
 * macOS ([macInProcessLocalAuth]). Both calls block; [DesktopAppLock] makes them on `Dispatchers.IO`.
 */
interface InProcessLocalAuth {
    /** Whether a sheet could be shown — answers without prompting. */
    fun isAvailable(): Boolean

    /** Show the sheet with [reason] (macOS: *"<app> is trying to <reason>."*) and wait for the user. */
    fun authenticate(reason: String): AppLockAuthResult
}

/**
 * The macOS [InProcessLocalAuth], or `null` where it cannot run (not macOS, no FFM, the framework
 * missing) — leaving [DesktopAppLock] on its helper.
 */
fun macInProcessLocalAuth(): InProcessLocalAuth? {
    if (!MacLocalAuthentication.isSupported) return null
    return object : InProcessLocalAuth {
        override fun isAvailable(): Boolean = MacLocalAuthentication.isAvailable()

        override fun authenticate(reason: String): AppLockAuthResult =
            when (MacLocalAuthentication.authenticate(reason)) {
                MacLocalAuthentication.Result.Success -> AppLockAuthResult.Success
                MacLocalAuthentication.Result.Cancelled -> AppLockAuthResult.Cancelled
                MacLocalAuthentication.Result.Unavailable -> AppLockAuthResult.Unavailable
                MacLocalAuthentication.Result.Failure -> AppLockAuthResult.Failure
            }
    }
}

/**
 * Where the in-window Touch ID view goes: **points**, top-left origin, relative to the window's
 * content area. [windowTitle] and [contentWidth] pick out the `NSWindow` (see
 * `MacEmbeddedAuthentication.Slot`).
 */
data class EmbeddedAuthSlot(
    val windowTitle: String,
    val contentWidth: Double,
    val contentHeight: Double,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

/** Touch ID drawn inside the app's own window. [authenticate] blocks; [cancel]/[move] are any-thread. */
interface EmbeddedLocalAuth {
    fun isAvailable(): Boolean

    /** Blocks until answered. [isCancelled] is this call's own cancellation — check it before showing anything. */
    fun authenticate(slot: EmbeddedAuthSlot, reason: String, isCancelled: () -> Boolean): AppLockAuthResult

    fun move(slot: EmbeddedAuthSlot)

    fun cancel()
}

/** The macOS [EmbeddedLocalAuth] (`LAAuthenticationView`), or `null` where it cannot run. */
fun macEmbeddedLocalAuth(): EmbeddedLocalAuth? {
    if (!MacEmbeddedAuthentication.isSupported) return null
    fun EmbeddedAuthSlot.native() =
        MacEmbeddedAuthentication.Slot(windowTitle, contentWidth, contentHeight, x, y, width, height)
    return object : EmbeddedLocalAuth {
        override fun isAvailable(): Boolean = MacEmbeddedAuthentication.isAvailable()

        override fun authenticate(
            slot: EmbeddedAuthSlot,
            reason: String,
            isCancelled: () -> Boolean,
        ): AppLockAuthResult =
            when (MacEmbeddedAuthentication.authenticate(slot.native(), reason, isCancelled = isCancelled)) {
                MacEmbeddedAuthentication.Result.Success -> AppLockAuthResult.Success
                MacEmbeddedAuthentication.Result.Cancelled -> AppLockAuthResult.Cancelled
                MacEmbeddedAuthentication.Result.Unavailable -> AppLockAuthResult.Unavailable
                MacEmbeddedAuthentication.Result.Failure -> AppLockAuthResult.Failure
            }

        override fun move(slot: EmbeddedAuthSlot) = MacEmbeddedAuthentication.move(slot.native())

        override fun cancel() = MacEmbeddedAuthentication.cancel()
    }
}
