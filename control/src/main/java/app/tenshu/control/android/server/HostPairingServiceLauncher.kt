package app.tenshu.control.android.server

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.tenshu.liveness.armForegroundService
import kotlinx.coroutines.flow.Flow
import app.tenshu.control.sync.HostPairingMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The commonMain → Android start seam for [HostPairingForegroundService]
 * (`docs/proposals/control-pairing-ux.md` §3.5 / Phase 1.5).
 *
 * `HostPairingForegroundService` owns all notification/FGS edge logic (via the Android-free
 * `HostPairingLiveness`), but a *started* service only exists once something starts it. This
 * launcher is the minimal always-on piece: created once at app start
 * ([app.tenshu.control.android.ControlApplication]), it observes the process-lifetime
 * [HostPairingMonitor.isPairing] and `startForegroundService`s the service on the
 * idle → pairing edge. The service then observes the monitor itself for promote /
 * countdown-refresh / idle-stop (it `stopSelf()`s when the session clears), so the launcher
 * does nothing further — structurally identical to Drive's `FolderUploadServiceLauncher`.
 *
 * Repeated `startForegroundService` while the service is already running is a benign no-op
 * (Android coalesces); the launcher only acts on the rising edge to keep start calls minimal.
 */
class HostPairingServiceLauncher(
    private val appContext: Context,
    private val monitor: HostPairingMonitor,
    /** Process foregroundedness — the retry signal after a refused start. */
    private val foreground: Flow<Boolean>,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    fun start() {
        scope.launch {
            armForegroundService(
                inFlight = monitor.isPairing,
                foreground = foreground,
                startService = {
                    ContextCompat.startForegroundService(
                        appContext,
                        Intent(appContext, HostPairingForegroundService::class.java)
                    )
                },
            )
        }
    }
}
