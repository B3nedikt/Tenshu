package app.tenshu.mia.android

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import app.tenshu.mia.ui.App
import app.tenshu.ui.theme.TenshuTheme
import org.koin.android.ext.android.getKoin

// Mia Android entry point (docs/proposals/home-and-mia-apps.md §6, M3).
class MainActivity : ComponentActivity() {

    /**
     * AV-1 — bound only on a device that can recognise speech on-device, so `getOrNull` is the
     * whole availability check on this side too (IN-D11).
     */
    private val voice: AndroidVoiceInput? by lazy { getKoin().getOrNull() }

    private var onPermissionResult: (Boolean) -> Unit = {}

    private val requestRecordAudio =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            onPermissionResult(granted)
            onPermissionResult = {}
        }

    override fun onDestroy() {
        // ⚠ Releases the microphone. A recogniser left alive shows in the OS's recording
        // indicator long after the user has left the screen.
        voice?.release()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        voice?.attachPermissionRequester { onGranted ->
            onPermissionResult = onGranted
            requestRecordAudio.launch(Manifest.permission.RECORD_AUDIO)
        }
        setContent {
            TenshuTheme {
                App()
            }
        }

    }
}
