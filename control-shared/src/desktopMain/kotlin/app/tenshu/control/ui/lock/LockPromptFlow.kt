package app.tenshu.control.ui.lock

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.tenshu.control.data.AppLockAuthResult
import app.tenshu.control.data.DesktopAppLock
import app.tenshu.control.data.EmbeddedAuthSlot
import kotlinx.coroutines.delay

/**
 * What the desktop lock overlay asks for, and when — kept out of the composable so
 * `LockPromptFlowTest` can drive it without a window.
 *
 * ⚠⚠ **With Touch ID usable there is no *Unlock* button to press first.** The first version fell
 * back to one whenever Touch ID was not on screen, and the commonest way to get there was macOS
 * cancelling the check because Control was not the active app (just opened from the tray, or another
 * app clicked) — so the user met a button that only re-asked for the fingerprint. Now Touch ID runs
 * whenever the window is focused (the composable keys [touchId] on focus), a cancel while focused
 * asks again, and closing a sheet the user *asked for* goes back to Touch ID. [Route.Retry] — the
 * button — is for when Touch ID cannot help: no usable sensor, or it has just failed.
 *
 * ⚠⚠ A sheet that Touch ID's failure opened must NOT lead back to Touch ID when closed. Touch ID can
 * fail at once while still reporting itself available (the view cannot bind, the window is not found,
 * the macOS 27 beta's -1007), and returning there re-opens the sheet the user just closed, forever.
 */
internal class LockPromptFlow(
    private val appLock: DesktopAppLock,
    private val onUnlocked: () -> Unit,
) {
    enum class Route {
        /** Deciding — nothing below the title yet, so no button flashes up. */
        Checking,

        /** The Touch ID slot and *Use password*. */
        TouchId,

        /** The system password sheet is up. */
        Password,

        /** No usable Touch ID and the sheet was closed: the *Unlock* button. */
        Retry,
    }

    enum class TouchIdOutcome { Unlocked, NeedsPassword }

    /** Why [choose] runs. */
    enum class Request {
        /** Opening, or the *Unlock* button: Touch ID if it can be used. */
        Auto,

        /** The user pressed *Use password*. */
        UserPassword,

        /** Touch ID ended in a failure (wrong fingers, a lockout, or it could not be shown). */
        TouchIdFailed,
    }

    var route by mutableStateOf(Route.Checking)
        private set

    /** Pick the route for [request]. */
    suspend fun choose(reason: String, request: Request) {
        if (request == Request.Auto && appLock.embeddedAvailable()) {
            route = Route.TouchId
            return
        }
        route = Route.Password
        if (appLock.authenticate(reason) == AppLockAuthResult.Success) {
            onUnlocked()
            return
        }
        // Back to Touch ID only from a sheet the user chose over a working Touch ID — see the class KDoc.
        route = if (request == Request.UserPassword && appLock.embeddedAvailable()) Route.TouchId else Route.Retry
    }

    /**
     * Touch ID until it unlocks or needs the password. Call only while the window is focused, and
     * cancel on focus loss — macOS cancels a check for an app that is not active anyway.
     *
     * ⚠⚠ [AppLockAuthResult.Failure] MUST go to the password: five wrong fingers lock Touch ID until
     * the login password is entered (device-verified 2026-10-15), so asking again would be a dead end.
     */
    suspend fun touchId(reason: String, slot: suspend () -> EmbeddedAuthSlot): TouchIdOutcome {
        while (true) {
            when (appLock.authenticateEmbedded(slot(), reason)) {
                AppLockAuthResult.Success -> {
                    onUnlocked()
                    return TouchIdOutcome.Unlocked
                }
                // Taken down while we still have focus; a pause keeps an unexplained repeat from spinning.
                AppLockAuthResult.Cancelled -> delay(RETRY_AFTER_CANCEL_MS)
                AppLockAuthResult.Failure, AppLockAuthResult.Unavailable -> return TouchIdOutcome.NeedsPassword
            }
        }
    }

    companion object {
        const val RETRY_AFTER_CANCEL_MS = 1_000L
    }
}
