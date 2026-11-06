package org.tenshu.sync.proxy

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.tenshu.sync.DrainSync
import org.tenshu.sync.ScheduledRunDeferred

/**
 * WorkManager worker that runs one scheduled, head-less op inside Control when
 * the OS wakes it. The op id is carried in the work's input
 * data (keyed [KEY_OP_ID]); the op runs **inside Control**, so keys/state are
 * wherever they always are — the worker process holds none of Tenshu's crypto
 * (`core-background-processing.md` §5.2).
 *
 * The dispatcher is read lazily from [ScheduledOpDispatcherHolder] (it doesn't
 * exist when the DI graph is built); a cold-boot race before the init hook
 * populates it ⇒ `Result.retry()`. Framework-instantiated, so deps come from Koin,
 * not a constructor.
 *
 * ⚠⚠ The op runs as this worker's **child** (`dispatchScheduledOp`), so it lasts exactly as long as
 * the job: when the OS stops the job (the 10-minute limit, a lost constraint), the op unwinds and
 * WorkManager runs the request again later — the continuation of a long pass. Every scheduled op is
 * idempotent: the media index pass skips what its stamps say is current, and the content backfill
 * walks again from the start (`control-background-work-off-fgs.md` §5.6).
 *
 * This is the sole app-closed background driver: the background drain is the head-less `DrainSync`
 * op scheduled through this generic path, so a `DrainSync` dispatch runs the [PostDrainHook].
 */
class TenshuOperationWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    private val dispatcherHolder: ScheduledOpDispatcherHolder by inject()

    override suspend fun doWork(): Result {
        val opId = inputData.getString(KEY_OP_ID) ?: return Result.failure()
        // ⚠⚠ `runAttemptCount` counts deferrals as well as failures — it is WorkManager's only
        // counter. `ScheduledRetryAccounting` keeps the deferrals apart so they do not spend the
        // failure budget below.
        val workId = id.toString()
        val retries = ScheduledRetryAccounting(SharedPreferencesDeferralCountStore(applicationContext))
        retries.attemptStarted(workId, runAttemptCount)
        return try {
            // ⚠ A cold wake starts this worker ~30 ms before the proxy session that installs the
            // dispatcher (measured on API 37): wait for it briefly rather than answering retry,
            // which costs a second job start and ~38 s.
            val dispatch = dispatcherHolder.awaitDispatch()
            if (dispatch == null) {
                // Proxy not ready yet (cold boot): "not now", like a deferral — not a failure.
                retries.deferred(workId, runAttemptCount)
                return Result.retry()
            }
            dispatch(opId)
            // The `DrainSync` op is the app-closed background drain, so run the post-drain hook
            // here — the OS-elevation pass (central notifications). Isolated so a hook failure
            // never turns a successful drain into a retry.
            if (opId == DrainSync.ID) {
                runCatching { getKoin().getOrNull<PostDrainHook>()?.onDrained() }
            }
            retries.succeeded(workId)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ScheduledRunDeferred) {
            // "Not now" is not a failure. Retry the same request — same
            // constraints — after WorkManager's backoff. Uncapped, unlike the failure branch below:
            // a pass that keeps finding the device busy must keep looking.
            //
            // ⚠⚠ The wait is NOT the exception's `retryAfterMs`, and not a fixed interval.
            // `Result.retry()` carries no delay; WorkManager computes it from the request's
            // backoff and the attempt count (see `WorkBackoff`), capped at 5 h — or, for an
            // idle-mode job such as the media pass, which may carry no backoff, at the next idle
            // window. Only the in-process `ScheduleDrainLoop` honours `retryAfterMs`.
            retries.deferred(workId, runAttemptCount)
            Result.retry()
        } catch (e: Exception) {
            if (retries.failedMayRetry(workId, runAttemptCount)) Result.retry() else Result.failure()
        }
    }

    /**
     * Required for **expedited** work on API 24–30 (minSdk 24), where WorkManager runs an
     * expedited job as a short-lived foreground service and calls this to obtain its
     * notification — without it, an expedited request (the app-closed backlog backstop) throws at
     * runtime. On API 31+ expedited work runs as a JobScheduler
     * expedited job (no foreground service), so this is only the fallback path; it is also what
     * [OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST] degrades to when quota is exhausted.
     * A minimal, low-importance "syncing" notification — the drain itself is invisible work.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        // ⚠⚠ No foreground-service TYPE. This is only called on API 24–30 (from 31 an expedited job
        // is a JobScheduler EJ, no service), and there WorkManager's `SystemForegroundService` is the
        // service started — its manifest entry declares no type, so on API 29–30 passing
        // DATA_SYNC throws ("not a subset of the manifest's foregroundServiceType"). A type is only
        // mandatory from API 34, which never reaches this. Declaring `dataSync` on WorkManager's
        // service instead would put a second dataSync FGS in front of Play for automatic work.
        ForegroundInfo(NOTIFICATION_ID, buildSyncingNotification())

    private fun buildSyncingNotification(): Notification {
        val channel = NotificationChannelCompat.Builder(
            CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_LOW
        ).setName(applicationContext.getString(R.string.background_sync_channel)).build()
        NotificationManagerCompat.from(applicationContext).createNotificationChannel(channel)
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Tenshu")
            .setContentText(applicationContext.getString(R.string.background_sync_syncing))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val KEY_OP_ID = "tenshu.scheduled.opId"

        /** Shared tag so [PlatformScheduler.cancelAll] can cancel every scheduled op at once. */
        const val TAG = "tenshu-scheduled-op"

        private const val CHANNEL_ID = "tenshu_background_sync"
        private const val NOTIFICATION_ID = 0xC048
    }
}

