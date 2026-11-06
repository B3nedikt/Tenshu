package app.tenshu.drive.android.upload

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.tenshu.drive.ui.common.folderupload.FolderUploadController
import app.tenshu.liveness.armForegroundService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The commonMain → Android start seam for the Drive folder-upload foreground
 * service (`docs/proposals/drive-folder-upload.md` §5d "as implemented").
 *
 * `FolderUploadForegroundService` owns all notification/FGS edge logic (via
 * the Android-free `FolderUploadLiveness`), but a *started* service only
 * exists once something starts it. This launcher is the minimal always-on
 * piece: created once at app start ([app.tenshu.drive.android.DriveApplication]),
 * it observes the Drive-owned [FolderUploadController.state] — the exact
 * StateFlow the ViewModel observes — and `startForegroundService`s the
 * service on the idle → in-flight edge. The service then observes the same
 * state itself for promote / progress-refresh / terminal-demote / idle-stop
 * (it `stopSelf()`s when the upload clears), so the launcher does nothing
 * further — structurally identical to how Control's always-running
 * `ProxyService` hosts `ForegroundTaskLiveness`, except Drive's service is
 * started rather than bound.
 *
 * Repeated `startForegroundService` while the service is already running is a
 * benign no-op (Android coalesces); the launcher only acts on the rising
 * edge to keep the start calls minimal.
 *
 * **Isolation invariant (§5d).** Reads only Drive-owned controller state;
 * never touches Control / the proxy.
 */
class FolderUploadServiceLauncher(
    private val appContext: Context,
    private val controller: FolderUploadController,
    /** Process foregroundedness — the retry signal after a refused start. */
    private val foreground: Flow<Boolean>,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    fun start() {
        scope.launch {
            armForegroundService(
                inFlight = controller.state.map { it != null && it.outcome == null },
                foreground = foreground,
                startService = {
                    ContextCompat.startForegroundService(
                        appContext,
                        Intent(appContext, FolderUploadForegroundService::class.java)
                    )
                },
            )
        }
    }
}
