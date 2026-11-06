package app.tenshu.control.indexing

import android.app.Notification
import app.tenshu.control.proxy.R
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.inference.proxy.IndexProgressHolder
import org.tenshu.inference.proxy.IndexProgressSnapshot
import org.tenshu.inference.proxy.ManualIndexLauncher
import org.tenshu.inference.proxy.MediaIndexRunner
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID

/**
 * *Index now* on Android (`control-background-work-off-fgs.md` §5.6.1): the manual pass runs as a
 * WorkManager **long-running worker** with its own foreground notification, the idiomatic home for
 * user-started long work. Control's `ProxyService` does not hold its `dataSync` service for it
 * (`MEDIA_INDEX_PASS` is not in `USER_STARTED_SOURCES`).
 *
 * - **Type:** `mediaProcessing` on API 35+, `dataSync` below ([manualIndexForegroundType]); both are
 *   declared on WorkManager's `SystemForegroundService` in Control's manifest.
 * - **Notification:** what it is doing — *"Indexing photos — 1,240 of 8,500"*, *"Indexing photos…"*
 *   while the library walk has no total yet — and a **Stop** action that cancels this work.
 * - **Stopping** — Stop, the OS, Android 15's 6-hour type limit — cancels the coroutine, which
 *   cancels the pass ([MediaIndexRunner.runManualToCompletion]); it ends at its next photo. The
 *   stamps keep what it did, so the next idle+charging pass continues from there.
 * - **Constraints:** battery not low only (`inference-service.md` §34). Not expedited: an
 *   expedited job is for short work.
 */
class ManualIndexWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val runner: MediaIndexRunner by inject()
    private val progress: IndexProgressHolder by inject()
    private val contextHolder: ManualIndexContextHolder by inject()

    override suspend fun doWork(): Result {
        // Cold start: no proxy session yet. "Not now", like the scheduled worker.
        var waited = 0L
        while (contextHolder.executionContext == null && waited < 10_000) {
            delay(100)
            waited += 100
        }
        val executionContext = contextHolder.executionContext ?: return Result.retry()
        setForeground(getForegroundInfo())
        return coroutineScope {
            // ⚠ Refreshed on a cadence, not per photo: a notification update per image is a binder
            // call per image.
            val updates: Job = launch {
                while (isActive) {
                    delay(NOTIFICATION_REFRESH_MS)
                    runCatching { setForeground(getForegroundInfo()) }
                }
            }
            // ⚠ Ends with its proxy session as well as with this work: a pass left running on a
            // torn-down session writes through a destroyed context.
            val session = executionContext.sessionScope.coroutineContext[Job]
            val pass = this
            val unlink = session?.invokeOnCompletion { pass.coroutineContext[Job]?.cancel() }
            try {
                runner.runManualToCompletion(executionContext)
                Result.success()
            } catch (e: CancellationException) {
                if (session?.isActive == false && isStopped.not()) return@coroutineScope Result.retry()
                throw e
            } catch (e: Exception) {
                println("ManualIndexWorker -> pass failed: $e")
                Result.failure()
            } finally {
                unlink?.dispose()
                updates.cancel()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        manualIndexForegroundInfo(applicationContext, id, progress.progress.value, Build.VERSION.SDK_INT)

    companion object {
        const val UNIQUE_WORK_NAME = "manual-media-index"
        private const val NOTIFICATION_REFRESH_MS = 1_500L

        /**
         * The [ManualIndexLauncher] Android wires into `StartMediaIndexPass`. `KEEP`: a second tap
         * while a pass runs is the same request; after Stop the work is finished, so a tap starts anew.
         */
        fun launcher(context: Context): ManualIndexLauncher = ManualIndexLauncher {
            val request = OneTimeWorkRequestBuilder<ManualIndexWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}

/** The proxy session a manual pass runs in; set by the platform init, like the dispatcher holder. */
class ManualIndexContextHolder {
    @Volatile
    var executionContext: ExecutionContext? = null
}

/** `mediaProcessing` from API 35 (where the type exists), `dataSync` below. */
fun manualIndexForegroundType(sdkInt: Int): Int =
    if (sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
    } else {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    }

/**
 * *"Indexing photos — 1,240 of 8,500"*, numbers in the device's locale; *"Indexing photos…"* while
 * the library walk has not produced a total.
 */
fun manualIndexProgressText(context: Context, snapshot: IndexProgressSnapshot?, locale: Locale): String {
    if (snapshot == null || snapshot.total <= 0) return context.getString(R.string.manual_index_starting)
    val numbers = NumberFormat.getIntegerInstance(locale)
    return context.getString(
        R.string.manual_index_progress,
        numbers.format(snapshot.done.coerceAtMost(snapshot.total)),
        numbers.format(snapshot.total),
    )
}

internal fun manualIndexForegroundInfo(
    context: Context,
    workId: UUID,
    snapshot: IndexProgressSnapshot?,
    sdkInt: Int,
): ForegroundInfo {
    val notification = manualIndexNotification(context, workId, snapshot)
    // ⚠ The typed constructor exists from API 29; below that the type is not a concept.
    return if (sdkInt >= Build.VERSION_CODES.Q) {
        ForegroundInfo(MANUAL_INDEX_NOTIFICATION_ID, notification, manualIndexForegroundType(sdkInt))
    } else {
        ForegroundInfo(MANUAL_INDEX_NOTIFICATION_ID, notification)
    }
}

private fun manualIndexNotification(context: Context, workId: UUID, snapshot: IndexProgressSnapshot?): Notification {
    val channel = NotificationChannelCompat.Builder(MANUAL_INDEX_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
        .setName(context.getString(R.string.manual_index_channel))
        .build()
    NotificationManagerCompat.from(context).createNotificationChannel(channel)
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val known = snapshot != null && snapshot.total > 0
    return NotificationCompat.Builder(context, MANUAL_INDEX_CHANNEL_ID)
        .setContentTitle("Tenshu")
        .setContentText(manualIndexProgressText(context, snapshot, locale))
        .setSmallIcon(android.R.drawable.ic_menu_gallery)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(if (known) snapshot!!.total else 0, if (known) snapshot!!.done.coerceAtMost(snapshot.total) else 0, !known)
        .addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            context.getString(R.string.manual_index_stop),
            WorkManager.getInstance(context).createCancelPendingIntent(workId),
        )
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()
}

private const val MANUAL_INDEX_CHANNEL_ID = "tenshu_media_index"
internal const val MANUAL_INDEX_NOTIFICATION_ID = 0xC049
