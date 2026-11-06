package app.tenshu.fit.ui.screens.record

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * The full permission set for a [RecorderCapability], version-gated to mirror the manifest
 * declarations (§9.8). Requested together in one launch so the capability's OS dialogs run
 * back-to-back after a single priming card.
 */
private fun RecorderCapability.permissions(): Array<String> = when (this) {
    RecorderCapability.Motion -> buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    RecorderCapability.HeartRate -> buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        // pre-S: BLUETOOTH / BLUETOOTH_ADMIN are normal (manifest) permissions — nothing to request.
    }.toTypedArray()
}

/**
 * The **critical** permission whose grant the caller keys off — location for [Motion], and
 * Bluetooth-connect for [HeartRate]. `null` means the capability has no runtime-gated critical
 * permission on this API level (pre-S Bluetooth), i.e. it is effectively always granted.
 */
private fun RecorderCapability.criticalPermission(): String? = when (this) {
    RecorderCapability.Motion -> Manifest.permission.ACCESS_FINE_LOCATION
    RecorderCapability.HeartRate ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else null
}

@Composable
actual fun rememberRecorderPermissionGate(): RecorderPermissionGate {
    val context = LocalContext.current
    // The pending pair bridges the launcher's async result back to the requesting call.
    val pending = remember { mutableStateOf<Pair<RecorderCapability, (Boolean) -> Unit>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val (capability, onResult) = pending.value ?: return@rememberLauncherForActivityResult
        pending.value = null
        val critical = capability.criticalPermission()
        // Trust the just-returned result for the critical perm; re-check for the no-runtime-critical case.
        val granted = critical == null || result[critical] == true ||
            ContextCompat.checkSelfPermission(context, critical) == PackageManager.PERMISSION_GRANTED
        onResult(granted)
    }
    return remember(launcher, context) {
        object : RecorderPermissionGate {
            override fun isGranted(capability: RecorderCapability): Boolean {
                val critical = capability.criticalPermission() ?: return true
                return ContextCompat.checkSelfPermission(context, critical) == PackageManager.PERMISSION_GRANTED
            }

            override fun request(capability: RecorderCapability, onResult: (Boolean) -> Unit) {
                if (isGranted(capability)) {
                    onResult(true)
                    return
                }
                pending.value = capability to onResult
                launcher.launch(capability.permissions())
            }
        }
    }
}
