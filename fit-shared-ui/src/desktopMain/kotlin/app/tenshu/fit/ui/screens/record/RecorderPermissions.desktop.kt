package app.tenshu.fit.ui.screens.record

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Desktop has no runtime-permission model — every capability reports granted, so the UI's priming
 *  + request flow proceeds immediately with no dialog. */
@Composable
actual fun rememberRecorderPermissionGate(): RecorderPermissionGate = remember {
    object : RecorderPermissionGate {
        override fun isGranted(capability: RecorderCapability): Boolean = true
        override fun request(capability: RecorderCapability, onResult: (Boolean) -> Unit) = onResult(true)
    }
}
