package app.tenshu.calendar.ui.external

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberDeviceCalendarAccess(): DeviceCalendarAccess {
    val context = LocalContext.current

    // Bridges the launcher's async answer back to the call that asked (the `ContactImporter`
    // pattern).
    val pending = remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val onResult = pending.value ?: return@rememberLauncherForActivityResult
        pending.value = null
        onResult(granted)
    }

    return remember(context) {
        object : DeviceCalendarAccess {
            override val supported: Boolean = true

            override fun requestRead(onResult: (granted: Boolean) -> Unit) {
                if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    onResult(true)
                    return
                }
                // A request already in flight owns the callback; a second launch would orphan it.
                if (pending.value != null) return
                pending.value = onResult
                request.launch(Manifest.permission.READ_CALENDAR)
            }

            override fun addAccount() {
                // ⚠ Unfiltered: Google is the common case, but Exchange, DAVx5 (iCloud, Nextcloud)
                // and every other sync adapter fill the same store, and the user knows which one
                // they have. ⚠ Caught: an OEM build without the settings screen must not crash.
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_ADD_ACCOUNT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }
}
