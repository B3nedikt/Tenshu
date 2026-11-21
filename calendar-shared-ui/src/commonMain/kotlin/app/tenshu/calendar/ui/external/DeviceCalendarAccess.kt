package app.tenshu.calendar.ui.external

import androidx.compose.runtime.Composable
import app.tenshu.calendar.shared.resources.Res
import app.tenshu.calendar.shared.resources.calendar_device_mac
import app.tenshu.calendar.shared.resources.calendar_device_phone
import app.tenshu.calendar.shared.resources.calendar_permission_hint_mac
import app.tenshu.calendar.shared.resources.calendar_permission_hint_phone
import app.tenshu.calendar.shared.resources.calendar_sync_cadence_mac
import app.tenshu.calendar.shared.resources.calendar_sync_cadence_phone
import org.jetbrains.compose.resources.stringResource

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
     * The device the copy names — *"Copy from this phone"*, *"…this Mac"* — and with it where a
     * refused permission is granted again and when a kept-in-sync calendar updates
     * ([DeviceKind.word], [DeviceKind.permissionHint], [DeviceKind.syncCadence]). ⚠ Defaults to
     * Android's, the platform this flow shipped on first.
     *
     * ⚠ A kind, not the words: the sheet's sentences are whole resources per language, and only a
     * renderer in composition can read them.
     */
    val deviceKind: DeviceKind get() = DeviceKind.PHONE

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

/** Which device *Copy from this …* reads, for the words the sheet uses about it. */
enum class DeviceKind { PHONE, MAC }

/** "phone" / "Mac" — always after "this" in the sheet's sentences. */
@Composable
fun DeviceKind.word(): String = stringResource(
    when (this) {
        DeviceKind.PHONE -> Res.string.calendar_device_phone
        DeviceKind.MAC -> Res.string.calendar_device_mac
    },
)

/** Where a refused permission is granted again, as a clause after *"You can allow it in "*. */
@Composable
fun DeviceKind.permissionHint(): String = stringResource(
    when (this) {
        DeviceKind.PHONE -> Res.string.calendar_permission_hint_phone
        DeviceKind.MAC -> Res.string.calendar_permission_hint_mac
    },
)

/**
 * When a kept-in-sync calendar updates, as a clause after *"updated "*. ⚠ Says only what the
 * platform's schedule really does: Android's content trigger fires on change, macOS refreshes while
 * the app is open (`DesktopMirrorScheduler`) — no OS trigger, no background job when it is closed.
 */
@Composable
fun DeviceKind.syncCadence(): String = stringResource(
    when (this) {
        DeviceKind.PHONE -> Res.string.calendar_sync_cadence_phone
        DeviceKind.MAC -> Res.string.calendar_sync_cadence_mac
    },
)
