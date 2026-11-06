package app.tenshu.control.android.server

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
import app.tenshu.liveness.startAndPromoteForeground
import app.tenshu.control.android.MainActivity
import app.tenshu.control.android.R
import app.tenshu.control.sync.HostPairingLiveness
import app.tenshu.control.sync.HostPairingMonitor
import app.tenshu.control.sync.HostPairingNotificationContent
import app.tenshu.control.sync.HostPairingNotificationHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.android.ext.android.inject

/**
 * Foreground service that keeps Control's process resident while a host pairing session is
 * underway (`docs/proposals/control-pairing-ux.md` §3.5 / Phase 1.5).
 *
 * On Android, Control is bound-only by default and OS-reclaimable when backgrounded. The
 * host's ephemeral X25519 pairing key lives in memory only
 * (`SyncManagerImpl.activePairingContext`), so a reclaimed process loses it and the joiner's
 * relay-queued request becomes unanswerable. This `dataSync` FGS — promoted while
 * [HostPairingMonitor.isPairing] and demoted on approve/deny/expiry — keeps the process (and
 * the key) alive exactly for the bounded waiting window, carrying the countdown notification.
 *
 * `ProxyService` (no `android:process`) shares this process, so a single FGS here suffices —
 * no cross-process plumbing. Structurally identical to Drive's
 * `FolderUploadForegroundService`: a low-importance channel, `ServiceCompat.startForeground`
 * with the `dataSync` type, `startForegroundService` before `startForeground`, a
 * `Dispatchers.Main.immediate` service scope cancelled in `onDestroy`.
 *
 * **Phase 3-Android folds in here (§3.6):** the ongoing notification updates to "a device
 * wants to join — tap to review" when a request arrives, and tapping routes to Control's
 * Sync surface where the app-global approval modal (carrying the SAS) shows. The
 * notification itself is PII-free — no device name, no SAS (§6).
 */
class HostPairingForegroundService : Service() {

    private val monitor: HostPairingMonitor by inject()

    // Main-thread so startForeground / notify / stopForeground are posted on the looper; the
    // collected StateFlows are conflated so we never queue more than the latest state.
    private val livenessScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Started (not bound) service: it observes the monitor's session state itself and
        // self-promotes/-demotes on the idle ⇄ pairing edges.
        HostPairingLiveness(
            isPairing = monitor.isPairing,
            remainingSeconds = monitor.remainingSeconds,
            activeRequest = monitor.activeRequest,
            handle = ForegroundServiceHandle(),
        ).launchIn(livenessScope)
    }

    // Started, not bound — nothing binds to it.
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-delivery is harmless: onCreate already wired the observer and the current
        // monitor state immediately re-drives the notification.
        return START_STICKY
    }

    override fun onDestroy() {
        livenessScope.cancel()
        super.onDestroy()
    }

    /**
     * Bridges [HostPairingNotificationHandle] to Android's foreground-service contract.
     * `startForegroundService` first promotes this started service so `startForeground` is
     * permitted (same shape as `ProxyService` / `FolderUploadForegroundService`).
     */
    private inner class ForegroundServiceHandle : HostPairingNotificationHandle {
        /** Whether the OS let us promote; a refused promotion must not post an ongoing notification. */
        private var promoted = false

        // ⚠ Promotion is refusable — see startAndPromoteForeground. Degrading keeps the pairing
        // session running without its countdown notification rather than killing Control.
        override fun onActive(content: HostPairingNotificationContent) {
            promoted = startAndPromoteForeground(
                startService = {
                    ContextCompat.startForegroundService(
                        this@HostPairingForegroundService,
                        Intent(this@HostPairingForegroundService, HostPairingForegroundService::class.java)
                    )
                },
                startForeground = {
                    ServiceCompat.startForeground(
                        this@HostPairingForegroundService,
                        NOTIFICATION_ID,
                        buildNotification(content),
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
        // POST_NOTIFICATIONS is denied; the service stays alive via the startForeground() in
        // onActive — only the countdown text goes unshown.
        @SuppressLint("MissingPermission")
        override fun onContentChanged(content: HostPairingNotificationContent) {
            // Retry a refused promotion rather than post a notification with no service behind it.
            if (!promoted) {
                onActive(content)
                return
            }
            NotificationManagerCompat.from(this@HostPairingForegroundService)
                .notify(NOTIFICATION_ID, buildNotification(content))
        }

        override fun onIdle() {
            ServiceCompat.stopForeground(
                this@HostPairingForegroundService,
                ServiceCompat.STOP_FOREGROUND_REMOVE
            )
            stopSelf()
        }
    }

    private fun buildNotification(content: HostPairingNotificationContent): Notification {
        val text = if (content.requestPending) {
            getString(R.string.host_pairing_request)
        } else {
            val remaining = content.remainingSeconds
            if (remaining != null) {
                getString(R.string.host_pairing_waiting, formatRemaining(remaining))
            } else {
                getString(R.string.host_pairing_waiting_no_countdown)
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.host_pairing_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** m:ss for the countdown text (e.g. 95 → "1:35"). */
    private fun formatRemaining(seconds: Int): String {
        val safe = seconds.coerceAtLeast(0)
        return "${safe / 60}:${(safe % 60).toString().padStart(2, '0')}"
    }

    /** Tap → bring Control forward on its Sync surface; the app-global approval modal (with
     *  the SAS) overlays it once a request is active. */
    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_DEEPLINK, MainActivity.DEEPLINK_SYNC)
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
            .setName(getString(R.string.host_pairing_channel))
            .setVibrationEnabled(false)
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "tenshu_control_host_pairing"
        const val NOTIFICATION_ID = 0xC0DE
    }
}
