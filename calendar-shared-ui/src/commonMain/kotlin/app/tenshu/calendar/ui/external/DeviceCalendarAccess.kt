package app.tenshu.calendar.ui.external

import androidx.compose.runtime.Composable

/**
 * The OS side of *Copy from this phone* (`calendar-external-sources.md` CX-1): the permission,
 * and the OS's own add-account screen.
 *
 * ⭐ `@Composable`-obtained, the `CalendarFileAccess` / `ContactImporter` shape, because the
 * Android actual has to hold an `ActivityResult` launcher and those may only be registered during
 * composition. ⚠ The *reading* is not here — it is `ExternalCalendarReader`, which needs no
 * composition and is unit-tested through `ProviderConversion`.
 */
interface DeviceCalendarAccess {

    /** False on a platform with no calendar store to read; the entry point is hidden there. */
    val supported: Boolean

    /**
     * The device the copy names — *"Copy from this phone"*, *"…this Mac"*. ⚠ Defaults are Android's
     * words, the platform this flow shipped on first.
     */
    val deviceName: String get() = "phone"

    /** Where a refused permission is granted again, as a clause after *"You can allow it in "*. */
    val permissionHint: String get() = "the phone's Settings, under Apps › Calendar › Permissions"

    /**
     * When a kept-in-sync calendar updates, as a clause after *"updated "*. ⚠ Says only what the
     * platform's schedule really does: Android's content trigger fires on change, macOS refreshes
     * while the app is open.
     */
    val syncCadence: String get() = "whenever this phone's calendar changes"

    /**
     * Asks for read access if it is not already held, and reports the answer.
     *
     * ⚠⚠ Called from the one button that needs it and nowhere else — the Contacts precedent for
     * `READ_CONTACTS`. A user who never copies a calendar is never asked.
     */
    fun requestRead(onResult: (granted: Boolean) -> Unit)

    /**
     * Opens the OS's own *Add account* screen.
     *
     * ⭐ This is the whole of "sign in with Google" on Android: the OS performs the sign-in, its
     * sync adapter fills the calendar store, and Tenshu never sees a credential (§3).
     */
    fun addAccount()
}

@Composable
expect fun rememberDeviceCalendarAccess(): DeviceCalendarAccess
