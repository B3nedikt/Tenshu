package app.tenshu.fit.android

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.tenshu.fit.data.FitRepository
import app.tenshu.fit.data.fitImportNotificationContent
import app.tenshu.liveness.ForegroundNotificationOps
import app.tenshu.liveness.LivenessNotificationContent
import app.tenshu.liveness.LivenessPhase
import app.tenshu.liveness.NotificationLiveness
import app.tenshu.liveness.SummaryThenStopHandle
import app.tenshu.liveness.enterForegroundOrStop
import app.tenshu.liveness.startAndPromoteForeground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.android.ext.android.inject
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps Fit alive — and shows a progress notification — while a Google Takeout import runs.
 *
 * The import writes tens of thousands of records over minutes ([app.tenshu.fit.data.ImportPacer]
 * paces it so it doesn't OOM Control). Without a foreground service, backgrounding Fit lets the OS
 * reclaim the process and the import dies; and the user shouldn't have to sit on a heavy list screen
 * to watch it (that screen itself pressures Control). This service, held for the lifetime of an
 * in-flight import, keeps the process alive and carries the notification.
 *
 * The idle ⇄ in-flight ⇄ terminal edge logic is the generic, Android-free
 * [NotificationLiveness] (module `:app-liveness`, shared with Drive's folder upload — the whole
 * point of extracting it). This is the thin Fit binding: it observes the repository's
 * `importStatus` StateFlow, maps it with [fitImportNotificationContent], and drives the FGS. Started
 * by [FitImportServiceLauncher] on the idle → in-flight edge; it `stopSelf`s as soon as the import
 * reaches a summary (Fit's status never clears back to idle, unlike Drive's), leaving only a
 * dismissible summary card on its own id — [SummaryThenStopHandle] has why.
 */
class FitImportForegroundService : Service() {

    private val repository: FitRepository by inject()

    // Main-thread so startForeground / notify / stopForeground post on the looper; the collected
    // StateFlow is conflated so we never queue more than the latest content.
    private val livenessScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The newest start this instance has answered; every stop names it (see [onStartCommand]). */
    private var lastStartId = -1
    private var collecting = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * ⚠⚠ EVERY start owes a `startForeground`, not just the first. The launcher can start this
     * service again while it is still alive and about to stop itself — a Health Connect refresh
     * ending a beat before a Takeout import begins — and a `stopSelf()` that tears the service down
     * with that start unanswered is `ForegroundServiceDidNotStartInTimeException`, a crash
     * (observed on the emulator 2026-10-30). So each start is answered here, and every stop is
     * `stopSelf(lastStartId)`, which Android ignores while a newer start is pending.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val current = fitImportNotificationContent(repository.importStatus().value)
            ?.takeIf { it.phase == LivenessPhase.IN_FLIGHT }
        // ⚠ Unconditional by contract, but not infallible — the allowance can lapse between the
        // start and here. enterForegroundOrStop ends the started lifecycle instead of throwing.
        val entered = enterForegroundOrStop(
            startForeground = {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(
                        current ?: LivenessNotificationContent(title = "Importing health data", text = "", phase = LivenessPhase.IN_FLIGHT),
                        ongoing = true,
                    ),
                    foregroundServiceType(),
                )
            },
            stopStartedLifecycle = { stopSelf(startId) },
        )
        if (!entered) return START_NOT_STICKY
        if (!collecting) {
            // The first emission refreshes the card, or demotes + stops (incl. on INITIAL idle:
            // work that finished before the collector attached).
            collecting = true
            NotificationLiveness(
                state = repository.importStatus(),
                toContent = ::fitImportNotificationContent,
                handle = SummaryThenStopHandle(ForegroundServiceOps()),
                // The status ticks once per RECORD; Android drops updates past ~5/s (see the KDoc).
                minContentChangeInterval = PROGRESS_REFRESH_INTERVAL,
            ).launchIn(livenessScope)
        } else if (current == null) {
            // A start for a run that is already over (or never surfaced): the collector has nothing
            // new to react to, so nothing else would take this card down.
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        livenessScope.cancel()
        super.onDestroy()
    }

    /** The Android half of [SummaryThenStopHandle], which owns the order these run in. */
    private inner class ForegroundServiceOps : ForegroundNotificationOps {
        private val notifications get() = NotificationManagerCompat.from(this@FitImportForegroundService)

        // ⚠ Promotion is refusable — see startAndPromoteForeground. Degrading here leaves the
        // import running without the notification rather than killing the process.
        override fun promote(content: LivenessNotificationContent): Boolean = startAndPromoteForeground(
            startService = {
                ContextCompat.startForegroundService(
                    this@FitImportForegroundService,
                    Intent(this@FitImportForegroundService, FitImportForegroundService::class.java),
                )
            },
            startForeground = {
                ServiceCompat.startForeground(
                    this@FitImportForegroundService,
                    NOTIFICATION_ID,
                    buildNotification(content, ongoing = true),
                    foregroundServiceType(),
                )
            },
            stopStartedLifecycle = { stopSelf(lastStartId) },
        )

        @SuppressLint("MissingPermission")
        override fun updateOngoing(content: LivenessNotificationContent) =
            notifications.notify(NOTIFICATION_ID, buildNotification(content, ongoing = true))

        override fun removeOngoing() =
            ServiceCompat.stopForeground(this@FitImportForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)

        @SuppressLint("MissingPermission")
        override fun postSummary(content: LivenessNotificationContent) =
            notifications.notify(SUMMARY_NOTIFICATION_ID, buildNotification(content, ongoing = false))

        override fun cancelSummary() = notifications.cancel(SUMMARY_NOTIFICATION_ID)

        override fun stopService() = stopSelf(lastStartId)
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }

    private fun buildNotification(content: LivenessNotificationContent, ongoing: Boolean): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(content.title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (content.text.isNotEmpty()) builder.setContentText(content.text)
        return builder.build()
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName("Health data import")
            .setVibrationEnabled(false)
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "tenshu_fit_import"
        const val NOTIFICATION_ID = 0xF127

        /** The terminal summary's own id — never an update of the ongoing card (see the KDoc). */
        const val SUMMARY_NOTIFICATION_ID = 0xF128

        val PROGRESS_REFRESH_INTERVAL = 1.seconds
    }
}
