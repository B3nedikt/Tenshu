package app.tenshu.timeline.android

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * The periodic watchdog that resurrects capture after an OEM mid-session force-stop (the ColorOS
 * failure mode). WorkManager is far more resilient to OEM kills than a bare foreground service — the
 * OS keeps re-scheduling the job — so it is the right vehicle to *detect* that the FGS died and
 * bring it back.
 *
 * Framework-instantiated, so it takes no injected state: everything it needs (the persisted opt-in,
 * the running-service check) it reads from the OS/prefs directly. Runs at WorkManager's minimum
 * periodic cadence (~15 min), so worst-case capture is down that long after a kill before recovery —
 * a large improvement over "dead until the user reopens the app", and complementary to the
 * user-side OEM auto-start allowlist which prevents the kill in the first place.
 */
class CaptureWatchdogWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        CaptureHealthStore(applicationContext).recordWatchdogTick(System.currentTimeMillis())
        CaptureRearm.rearmIfNeeded(applicationContext, trigger = "watchdog")
        // Always success: a "nothing to do" tick and a refused start are both normal outcomes, not
        // failures to retry — the next periodic tick is the retry.
        return Result.success()
    }
}
