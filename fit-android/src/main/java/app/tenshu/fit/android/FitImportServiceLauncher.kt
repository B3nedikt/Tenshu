package app.tenshu.fit.android

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.tenshu.fit.data.FitRepository
import app.tenshu.fit.data.fitImportNotificationContent
import app.tenshu.liveness.LivenessPhase
import app.tenshu.liveness.armForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Starts [FitImportForegroundService] on the idle → in-flight edge of an import.
 *
 * The service owns all the notification/FGS edge logic, but a *started* service only exists once
 * something starts it. This is the always-on piece: created once at app start ([FitApplication]), it
 * observes `FitRepository.importStatus()` — the same StateFlow the service reads — and starts the
 * service on the rising edge. The service then drives itself and `stopSelf`s once the import reaches
 * its summary, so the launcher only re-arms. ⚠ A re-arm can start the service while it is still
 * stopping from the previous run; the service answers every start for exactly that reason.
 *
 * ⚠⚠ **That start is refusable, and treating it as infallible took the process down.** A storage
 * format bump wiped Fit's store on 2026-07-30, the resulting re-import went in-flight while Fit sat
 * in the background, and `startForegroundService()` threw `ForegroundServiceStartNotAllowedException`
 * from a coroutine with no caller to catch it — three fatal crashes in a row. The trigger is
 * ordinary (any import beginning while Fit is off screen), so it is reachable after every lockstep
 * wipe. [armForegroundService] owns the attempt/degrade/retry policy and the reasoning for it.
 */
class FitImportServiceLauncher(
    private val appContext: Context,
    private val repository: FitRepository,
    /** Process foregroundedness — the retry signal after a refused start. */
    private val foreground: Flow<Boolean>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    fun start() {
        scope.launch {
            armForegroundService(
                inFlight = repository.importStatus().map { status ->
                    fitImportNotificationContent(status)?.phase == LivenessPhase.IN_FLIGHT
                },
                foreground = foreground,
                startService = {
                    ContextCompat.startForegroundService(
                        appContext,
                        Intent(appContext, FitImportForegroundService::class.java),
                    )
                },
            )
        }
    }
}
