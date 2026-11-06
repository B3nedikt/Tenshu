package app.tenshu.drive.android.backup

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.tenshu.liveness.armForegroundService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import app.tenshu.ingest.backup.startsForegroundService
import app.tenshu.ingest.backup.SyncedFolderController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The commonMain → Android start seam for the Synced Folders foreground service — which carries
 * **only a backup the user started** (*Back up now*), never an automatic one
 * (`docs/proposals/drive-synced-folders-phase2.md` §4.1a).
 *
 * ⚠⚠ Play's `dataSync` policy allows a foreground service for a user-initiated transfer and names
 * "daily auto backup of all new photos" as the counter-example. Automatic drains — a new photo, a
 * settings change, the WorkManager job — therefore run without one, under WorkManager's limits
 * (`BackupWorker`). This launcher starts the service on [startsForegroundService]'s edge, which
 * needs a frame stamped [app.tenshu.ingest.backup.BackupState.BackingUp.userInitiated]; a drain
 * with no tap behind it can never start the service, however long it runs.
 *
 * Created once at app start ([app.tenshu.drive.android.DriveApplication]). The service self-demotes
 * / self-stops on terminal/idle. Reads only Drive-owned controller state; never touches Control.
 */
class SyncedFolderServiceLauncher(
    private val appContext: Context,
    private val controller: SyncedFolderController,
    /** Process foregroundedness — the retry signal after a refused start. */
    private val foreground: Flow<Boolean>,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    fun start() {
        scope.launch {
            armForegroundService(
                inFlight = controller.state.map(::startsForegroundService),
                foreground = foreground,
                startService = {
                    ContextCompat.startForegroundService(
                        appContext,
                        Intent(appContext, SyncedFolderForegroundService::class.java)
                    )
                },
            )
        }
    }
}
