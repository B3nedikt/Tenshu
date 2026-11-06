package app.tenshu.calendar.ui.external

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import app.tenshu.macos.MacEventKit
import java.awt.Desktop
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * *Copy from this Mac* (`calendar-external-sources.md` CX-2c): the TCC calendar prompt and System
 * Settings' Internet Accounts, over the FFM EventKit bridge.
 *
 * ⚠ Supported only in a packaged `.app` that declares the usage string ([MacEventKit.isUsable]): an
 * unbundled `gradle run` would be killed by TCC on first use, so there the entry point stays hidden.
 */
@Composable
actual fun rememberDeviceCalendarAccess(): DeviceCalendarAccess {
    val scope = rememberCoroutineScope()
    return remember(scope) {
        object : DeviceCalendarAccess {
            override val supported: Boolean = macCalendarsUsable()
            override val deviceName: String = "Mac"
            override val permissionHint: String =
                "System Settings, under Privacy & Security › Calendars › Tenshu Calendar"

            // ⚠ The schedule really is this: a refresh at launch and every few minutes while the app
            // runs (`DesktopMirrorScheduler`) — no OS trigger, no background job when it is closed.
            override val syncCadence: String = "regularly while Calendar is open on this Mac"

            override fun requestRead(onResult: (granted: Boolean) -> Unit) {
                // ⚠ Blocks on the user's answer, so off the UI thread; the answer comes back on it.
                scope.launch {
                    val granted = withContext(Dispatchers.IO) { MacEventKit.requestFullAccess() }
                    onResult(granted)
                }
            }

            override fun addAccount() {
                runCatching {
                    Desktop.getDesktop().browse(URI(INTERNET_ACCOUNTS))
                }
            }
        }
    }
}

/** System Settings → Internet Accounts (macOS 13+). */
private const val INTERNET_ACCOUNTS = "x-apple.systempreferences:com.apple.Internet-Accounts-Settings.extension"
