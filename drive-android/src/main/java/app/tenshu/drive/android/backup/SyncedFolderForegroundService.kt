package app.tenshu.drive.android.backup

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
import app.tenshu.ingest.backup.SyncedFolderController
import app.tenshu.ingest.backup.BackupFailureKind
import app.tenshu.ingest.backup.backupNotificationContent
import app.tenshu.liveness.startAndPromoteForeground
import app.tenshu.liveness.LivenessNotificationContent
import app.tenshu.liveness.LivenessNotificationHandle
import app.tenshu.liveness.NotificationLiveness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import app.tenshu.ingest.backup.foregroundServiceState
import org.koin.android.ext.android.inject

/**
 * Drive-side foreground service for a Synced Folders backup **the user started** (*Back up now*)
 * — ⚠⚠ never for an automatic drain, which Play's `dataSync` policy does not allow
 * (`drive-synced-folders-phase2.md` §4.1a; automatic backup is `BackupWorker`'s). Started only by
 * [SyncedFolderServiceLauncher], and it renders [foregroundServiceState]: once the user's run ends,
 * an automatic drain's progress reads as idle here, so the service stops rather than carrying it.
 *
 * The analogue of `FolderUploadForegroundService` (`drive-synced-folders.md` §5 step 6),
 * sharing the generic [NotificationLiveness] edge logic (§9 "generalise it to observe either
 * controller, don't fork it") with the backup copy mapper [backupNotificationContent].
 *
 * The backup loop runs in the **Drive** process (gallery enumeration needs Drive's
 * `READ_MEDIA_IMAGES` — §3.3); started for the lifetime of the user's run, this
 * keeps the Drive process (hence the loop) alive and carries the progress
 * notification. Isolation invariant: rendered from Drive-owned controller state
 * only; never touches Control / the proxy.
 */
class SyncedFolderForegroundService : Service() {

    private val controller: SyncedFolderController by inject()

    private val livenessScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val userRunState = controller.state.map(::foregroundServiceState)
            .stateIn(livenessScope, SharingStarted.Eagerly, foregroundServiceState(controller.state.value))
        NotificationLiveness(
            state = userRunState,
            toContent = { state ->
                backupNotificationContent(state) { kind ->
                    when (kind) {
                        BackupFailureKind.COULD_NOT_CHECK_REALM -> getString(R.string.synced_folders_failure_could_not_check)
                    }
                }
            },
            handle = ForegroundServiceHandle(),
        ).launchIn(livenessScope)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        livenessScope.cancel()
        super.onDestroy()
    }

    private inner class ForegroundServiceHandle : LivenessNotificationHandle {
        /** Whether the OS let us promote; a refused promotion must not post an ongoing notification. */
        private var promoted = false

        // ⚠ Promotion is refusable — see startAndPromoteForeground. Degrading keeps the backup
        // drain running without its notification rather than killing Drive.
        override fun onActive(content: LivenessNotificationContent) {
            promoted = startAndPromoteForeground(
                startService = {
                    ContextCompat.startForegroundService(
                        this@SyncedFolderForegroundService,
                        Intent(this@SyncedFolderForegroundService, SyncedFolderForegroundService::class.java)
                    )
                },
                startForeground = {
                    ServiceCompat.startForeground(
                        this@SyncedFolderForegroundService,
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

        @SuppressLint("MissingPermission")
        override fun onContentChanged(content: LivenessNotificationContent) {
            // Retry a refused promotion rather than post a notification with no service behind it.
            if (!promoted) {
                onActive(content)
                return
            }
            NotificationManagerCompat.from(this@SyncedFolderForegroundService)
                .notify(NOTIFICATION_ID, buildNotification(content, ongoing = true))
        }

        @SuppressLint("MissingPermission")
        override fun onTerminal(content: LivenessNotificationContent) {
            ServiceCompat.stopForeground(
                this@SyncedFolderForegroundService,
                ServiceCompat.STOP_FOREGROUND_DETACH
            )
            NotificationManagerCompat.from(this@SyncedFolderForegroundService)
                .notify(NOTIFICATION_ID, buildNotification(content, ongoing = false))
        }

        override fun onIdle() {
            ServiceCompat.stopForeground(
                this@SyncedFolderForegroundService,
                ServiceCompat.STOP_FOREGROUND_REMOVE
            )
            stopSelf()
        }
    }

    private fun buildNotification(content: LivenessNotificationContent, ongoing: Boolean): Notification {
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
            .setName(getString(R.string.synced_folders_channel))
            .setVibrationEnabled(false)
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "tenshu_drive_synced_folders"
        const val NOTIFICATION_ID = 0xD0BA
    }
}
