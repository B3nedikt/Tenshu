package org.tenshu.sync.proxy

import android.app.Notification
import android.content.Context
import android.os.SystemClock
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Control's own **sync-push job** (`control-background-work-off-fgs.md` §5.3): drains the
 * outbound backlog under a WorkManager job, so an automatic push keeps the process alive — and,
 * measured, keeps the **network**: a job's process gets it even under Doze, which neither a cached
 * Control nor a background client's binding does (§3.3 there).
 *
 * Enqueued by [SyncPushScheduler] (via [WorkManagerScheduler.enqueueSyncPush]) on the rising edge
 * of a pushable backlog. The body is [SyncPushRun]; the follow-up decision is the scheduler's.
 *
 * ⚠ Not dispatched through an op id like [TenshuOperationWorker]: the push is the scheduler's own
 * business, not a schedulable operation, and adding one would move the sync service's schema.
 */
class SyncPushWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val schedulerHolder: SyncSchedulerHolder by inject()
    private val pushScheduler: SyncPushScheduler by inject()
    private val gateway: org.tenshu.core.common.proxy.ProxyGateway by inject()

    override suspend fun doWork(): Result {
        // Cold start: the proxy has not elected its scheduler yet. "Not now", not a failure.
        val scheduler = schedulerHolder.scheduler ?: return Result.retry()
        pushScheduler.workerStarted()
        if (!pushScheduler.shouldRun(isStallRetry = inputData.getBoolean(KEY_STALL_RETRY, false))) {
            // Parked after a stall: the delayed retry owns the backlog (SyncPushScheduler.shouldRun).
            pushScheduler.workerFinished(SyncPushRun.Outcome.STOPPED)
            return Result.success()
        }
        var outcome = SyncPushRun.Outcome.STOPPED
        try {
            outcome = SyncPushRun(
                drain = { scheduler.drainNowStalled() },
                backlog = { scheduler.pushableBacklog() },
                nowMs = SystemClock::elapsedRealtime,
                // Keeps this job — and with it Control's network — up while an automatic client
                // request (a background backup's upload) is still running here.
                clientWorkInFlight = { gateway.inFlightCount.value > 0 },
            ).run()
            // ⚠ The OS-elevation pass (central notifications for pending requests) the retired
            // per-edge DrainSync ran through TenshuOperationWorker: its pulls happen here now, so this
            // job owes it too. Isolated, so a hook failure never turns a push into a retry.
            runCatching { getKoin().getOrNull<PostDrainHook>()?.onDrained() }
            return Result.success()
        } catch (e: CancellationException) {
            // Stopped by the OS (constraint lost, quota, time limit): WorkManager runs this request
            // again, and the drain resumes where the relay last accepted.
            throw e
        } catch (e: Exception) {
            println("SyncPushWorker: push failed, will retry: $e")
            return Result.retry()
        } finally {
            withContext(NonCancellable) { pushScheduler.workerFinished(outcome) }
        }
    }

    /**
     * Required for **expedited** work on API 24–30 (minSdk 24), where WorkManager runs an expedited
     * job as a short foreground service; on API 31+ it is a JobScheduler expedited job and this is
     * not called. A minimal, low-importance notification.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        // ⚠⚠ No foreground-service TYPE. This is only called on API 24–30 (from 31 an expedited job
        // is a JobScheduler EJ, no service), and there WorkManager's `SystemForegroundService` is the
        // service started — its manifest entry declares no type, so on API 29–30 passing
        // DATA_SYNC throws ("not a subset of the manifest's foregroundServiceType"). A type is only
        // mandatory from API 34, which never reaches this. Declaring `dataSync` on WorkManager's
        // service instead would put a second dataSync FGS in front of Play for automatic work.
        ForegroundInfo(NOTIFICATION_ID, buildNotification())

    private fun buildNotification(): Notification {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(applicationContext.getString(R.string.background_sync_channel))
            .build()
        NotificationManagerCompat.from(applicationContext).createNotificationChannel(channel)
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Tenshu")
            .setContentText(applicationContext.getString(R.string.background_sync_syncing))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val TAG = "tenshu-sync-push"

        /** Input flag: this request is a stalled push's delayed retry ([SyncPushRequest.uniqueName]). */
        const val KEY_STALL_RETRY = "tenshu.syncPush.stallRetry"

        // Shared with TenshuOperationWorker's backstop notification on purpose: one "syncing"
        // channel, one id, whichever job happens to need it.
        private const val CHANNEL_ID = "tenshu_background_sync"
        private const val NOTIFICATION_ID = 0xC048
    }
}
