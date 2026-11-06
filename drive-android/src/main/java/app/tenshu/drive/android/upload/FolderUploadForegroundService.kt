package app.tenshu.drive.android.upload

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
import app.tenshu.drive.android.MainActivity
import app.tenshu.drive.android.R
import app.tenshu.drive.ui.common.folderupload.FolderUploadController
import app.tenshu.drive.ui.common.folderupload.FolderUploadLiveness
import app.tenshu.drive.ui.common.folderupload.FolderUploadNotificationContent
import app.tenshu.drive.ui.common.folderupload.FolderUploadNotificationHandle
import app.tenshu.liveness.enterForegroundOrStop
import app.tenshu.liveness.startAndPromoteForeground
import app.tenshu.liveness.LivenessPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.android.ext.android.inject

/**
 * Drive-side foreground service for an in-flight folder upload
 * (`docs/proposals/drive-folder-upload.md` §5d; the defect-3 fix in
 * `core-background-processing.md` §"Phase L2-LIVE → Known gap").
 *
 * The folder-upload orchestration loop runs in the **Drive** process (SAF
 * tree enumeration needs Drive's content resolver — §2.1) on the app-scoped
 * `FolderUploadController`. Drive otherwise has no foreground service, so a
 * backgrounded Drive is OS-reclaimable and the loop dies mid-upload. This
 * service, started for the lifetime of an in-flight upload, keeps the Drive
 * process (hence the loop) alive AND carries the rich user-facing
 * notification.
 *
 * **Isolation invariant (§5d).** Everything here is rendered by Drive, in the
 * Drive process, from the Drive-owned [FolderUploadController.state] ONLY. It
 * never touches Control / the proxy / `ProxyService`. Control's separate
 * generic liveness notification is unchanged and carries no
 * filename/progress.
 *
 * Started/stopped by [FolderUploadServiceLauncher], which observes the
 * controller at app start (mirrors Control's `ProxyService` +
 * `ForegroundTaskLiveness` seam exactly: a `NotificationChannelCompat`
 * low-importance channel created in `onCreate`, `ServiceCompat
 * .startForeground` with `dataSync` type, `startForegroundService` before
 * `startForeground`, a `Dispatchers.Main.immediate` service scope cancelled
 * in `onDestroy`).
 */
class FolderUploadForegroundService : Service() {

    private val controller: FolderUploadController by inject()

    // Main-thread so startForeground / notify / stopForeground are posted on
    // the looper; the collected StateFlow is conflated so we never queue more
    // than the latest content.
    private val livenessScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // startForegroundService()'s contract is unconditional: startForeground must be called
        // even when the work this service covers finished before our collector attaches (a
        // fast-failing import/upload) — otherwise Android kills the app with
        // ForegroundServiceDidNotStartInTimeException. Enter the foreground state immediately
        // with a neutral card; the liveness collector then refreshes it or demotes + stops the
        // service (incl. on INITIAL idle) on its first emission.
        // ⚠ Unconditional by contract, but not infallible — the allowance can lapse between the
        // start and here. enterForegroundOrStop ends the started lifecycle instead of throwing.
        enterForegroundOrStop(
            startForeground = {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(
                        FolderUploadNotificationContent(title = "Uploading folder", text = "", phase = LivenessPhase.IN_FLIGHT),
                        ongoing = true,
                    ),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    } else {
                        0
                    },
                )
            },
            stopStartedLifecycle = { stopSelf() },
        )
        // This is a started (not bound) service: it observes the Drive-owned
        // controller state itself and self-promotes/-demotes on the
        // idle ⇄ in-flight edges. Structurally identical to Control's
        // ForegroundTaskLiveness consuming ProxyGateway.inFlightCount.
        FolderUploadLiveness(controller.state, ForegroundServiceHandle())
            .launchIn(livenessScope)
    }

    // Started, not bound — nothing binds to it.
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-delivery is harmless: onCreate already wired the observer and the
        // current controller state immediately re-drives the notification.
        return START_STICKY
    }

    override fun onDestroy() {
        livenessScope.cancel()
        super.onDestroy()
    }

    /**
     * Bridges [FolderUploadNotificationHandle] to Android's foreground-service
     * contract. `startForegroundService` first promotes this started service
     * so `startForeground` is permitted; the service is already running in
     * this process when the edge fires, so the two calls happen back-to-back
     * well within the OS window (same shape as `ProxyService`).
     */
    private inner class ForegroundServiceHandle : FolderUploadNotificationHandle {
        /** Whether the OS let us promote; a refused promotion must not post an ongoing notification. */
        private var promoted = false

        // ⚠ Promotion is refusable — see startAndPromoteForeground. Degrading keeps the upload
        // loop running without its notification rather than killing Drive.
        override fun onActive(content: FolderUploadNotificationContent) {
            promoted = startAndPromoteForeground(
                startService = {
                    ContextCompat.startForegroundService(
                        this@FolderUploadForegroundService,
                        Intent(this@FolderUploadForegroundService, FolderUploadForegroundService::class.java)
                    )
                },
                startForeground = {
                    ServiceCompat.startForeground(
                        this@FolderUploadForegroundService,
                        NOTIFICATION_ID,
                        buildNotification(content, ongoing = true),
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        } else {
                            0
                        }
                    )
                },
                stopStartedLifecycle = { stopSelf() },
            )
        }

        // notify() reusing the FGS NOTIFICATION_ID no-ops (never throws) when
        // POST_NOTIFICATIONS is denied; the service stays alive via the
        // startForeground() in onActive — only the text goes unshown.
        @SuppressLint("MissingPermission")
        override fun onContentChanged(content: FolderUploadNotificationContent) {
            // Retry a refused promotion rather than post a notification with no service behind it.
            if (!promoted) {
                onActive(content)
                return
            }
            NotificationManagerCompat.from(this@FolderUploadForegroundService)
                .notify(NOTIFICATION_ID, buildNotification(content, ongoing = true))
        }

        // Terminal summary: demote so the notification is dismissible and
        // auto-clears, but keep it posted (DETACH) so the user sees the
        // summary for the controller's ~5 s window; the controller then
        // clears its state → onIdle removes it entirely.
        @SuppressLint("MissingPermission")
        override fun onTerminal(content: FolderUploadNotificationContent) {
            ServiceCompat.stopForeground(
                this@FolderUploadForegroundService,
                ServiceCompat.STOP_FOREGROUND_DETACH
            )
            NotificationManagerCompat.from(this@FolderUploadForegroundService)
                .notify(NOTIFICATION_ID, buildNotification(content, ongoing = false))
        }

        override fun onIdle() {
            ServiceCompat.stopForeground(
                this@FolderUploadForegroundService,
                ServiceCompat.STOP_FOREGROUND_REMOVE
            )
            stopSelf()
        }
    }

    private fun buildNotification(
        content: FolderUploadNotificationContent,
        ongoing: Boolean,
    ): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(content.title)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (content.text.isNotEmpty()) builder.setContentText(content.text)
        return builder.build()
    }

    /**
     * Tap → launch Drive `MainActivity` (singleTop). The existing Phase-2
     * ambient bar (`AddFileActionsViewModel` observing the same controller)
     * is where progress + the Cancel control already live; no notification
     * action button in v1 (§5d).
     */
    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannelCompat.Builder(
            CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_LOW
        )
            .setName(getString(R.string.folder_upload_channel))
            .setVibrationEnabled(false)
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "tenshu_drive_folder_upload"
        const val NOTIFICATION_ID = 0xD09E
    }
}
