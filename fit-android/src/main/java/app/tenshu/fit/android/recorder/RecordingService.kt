package app.tenshu.fit.android.recorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.tenshu.fit.android.MainActivity
import app.tenshu.fit.android.R
import app.tenshu.fit.data.RecordingEngine
import app.tenshu.fit.data.RecordingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * The recorder's **foreground service** (`docs/proposals/fit-app.md` §9.8 + §13.1) — kept alive
 * for the duration of an active workout so [AndroidRecordingEngine]'s GPS / BLE-HR capture
 * survives the app going to the background (the OS kills a backgrounded process' sensor callbacks
 * otherwise). It carries no capture logic itself (that lives in the engine singleton); it holds
 * the foreground promotion and acts as the notification **control surface** (RC-1):
 *
 *  - **Tap** → back into the app at the recording surface ([EXTRA_OPEN_RECORDING]; the shared
 *    shell routes to the live or review screen via `RecordSurfaceRequests`).
 *  - **Pause/Resume/Stop actions** drive the engine directly (same-process DI singleton). Stop
 *    routes through review, never saves from the shade: it parks the draft (`engine.stop()`) and
 *    swaps the ongoing notification for a tappable "review and save" cue — Android's
 *    notification-trampoline restriction forbids launching the Activity from the action itself.
 *  - **Live content**: chronometer elapsed + a throttled distance/HR line (30 s / 0.1 km
 *    buckets, silent via `setOnlyAlertOnce`).
 *
 * Declared with `health|location|connectedDevice` service types (step counter + GPS + the BLE
 * strap). The **runtime** type is computed from the permissions actually granted at start time
 * (see [foregroundServiceType]) — Android 14+ rejects a `startForeground` whose declared type is
 * not backed by a held runtime permission, and the recorder's per-capability JIT flow (§9.11)
 * grants location + activity-recognition on Start but defers Bluetooth to the strap opt-in, so a
 * fixed `connectedDevice` type crashed every Start without a paired strap. Started in
 * [AndroidRecordingEngine.start], stopped in its `teardown`. `START_NOT_STICKY` (§13.1's orphan
 * fix): an OS-restarted copy would front an engine whose in-RAM session died with the process —
 * a "Recording…" notification over nothing.
 */
class RecordingService : Service() {

    private val engine: RecordingEngine by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Set once [startForeground] ran — live updates must never post before (or after a bail). */
    private var promoted = false
    private var lastKey: Pair<Boolean, Int>? = null
    private var lastNotifyMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            engine.state().collect { s ->
                // Reviewing/Idle need no ongoing surface — the engine's teardown stops this
                // service; the review cue (if any) is posted by the Stop action handler.
                if (s is RecordingState.Active) maybeUpdateNotification(s)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> engine.pause()
            ACTION_RESUME -> engine.resume()
            ACTION_STOP -> handleStopAction()
            else -> promoteToForeground()
        }
        return START_NOT_STICKY
    }

    private fun promoteToForeground() {
        // Built state-less; the state collector upgrades it with live content right after.
        val notification = buildNotification(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val type = foregroundServiceType()
            // No capture permission granted → we may not legally promote to a typed FGS. Bail out
            // rather than crash; the engine still records while the app is in the foreground.
            if (type == 0) {
                stopSelf()
                return
            }
            // ⚠⚠ Never let this throw. A `location`-typed FGS started while the app is in the
            // background raises SecurityException ("requires … and the app must be in the eligible
            // state/exemptions to access the foreground only permission") because location is a
            // while-in-use permission — and an uncaught throw here is a FATAL crash inside
            // onStartCommand, which is how a watchdog meant to rescue a recording crash-looped the
            // app instead (§13.5e, measured 2026-08-15).
            if (!runCatching { startForeground(NOTIFICATION_ID, notification, type) }.isSuccess) {
                stopSelf()
                return
            }
        } else {
            if (!runCatching { startForeground(NOTIFICATION_ID, notification) }.isSuccess) {
                stopSelf()
                return
            }
        }
        promoted = true
    }

    /**
     * The shade's Stop (§13.1): park the draft for review — never commit a save from here. The
     * engine's teardown stops this service (removing the ongoing notification), so the cue that a
     * draft awaits review is a separate, auto-cancel notification whose tap opens the app; the
     * shared shell then routes to the review screen off the Reviewing state.
     */
    private fun handleStopAction() {
        engine.stop() ?: return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_review_notification_title))
            .setContentText(getString(R.string.recording_review_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent())
            .build()
        manager.notify(REVIEW_NOTIFICATION_ID, notification)
    }

    // ── live notification content ───────────────────────────────────────────────────────────

    private fun maybeUpdateNotification(s: RecordingState.Active) {
        if (!promoted) return
        val key = s.paused to s.distanceM / 100
        val now = SystemClock.elapsedRealtime()
        if (key == lastKey && now - lastNotifyMs < NOTIFICATION_UPDATE_MS) return
        lastKey = key
        lastNotifyMs = now
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(s))
    }

    private fun buildNotification(s: RecordingState.Active?): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(
                getString(
                    if (s?.paused == true) R.string.recording_notification_paused_title
                    else R.string.recording_notification_title,
                ),
            )
            .setContentText(progressText(s) ?: getString(R.string.recording_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppPendingIntent())
        if (s != null && !s.paused) {
            // Ticking elapsed time for free — no per-second notify() churn.
            builder.setWhen(System.currentTimeMillis() - s.elapsedSec * 1000L).setUsesChronometer(true)
        }
        if (s != null) {
            if (s.paused) {
                builder.addAction(0, getString(R.string.recording_action_resume), actionPendingIntent(ACTION_RESUME, 2))
            } else {
                builder.addAction(0, getString(R.string.recording_action_pause), actionPendingIntent(ACTION_PAUSE, 1))
            }
            builder.addAction(0, getString(R.string.recording_action_stop), actionPendingIntent(ACTION_STOP, 3))
        }
        return builder.build()
    }

    /** "1.2 km · 132 bpm" — whichever streams are live; null when neither is yet. */
    private fun progressText(s: RecordingState.Active?): String? {
        if (s == null) return null
        val parts = mutableListOf<String>()
        if (s.distanceM > 0) {
            val km = s.distanceM / 100 / 10.0
            parts += getString(R.string.recording_notification_distance_km, km.toString())
        }
        s.heartRateBpm?.let { parts += getString(R.string.recording_notification_bpm, it) }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun actionPendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, RecordingService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * Tap → back to the app at the recording surface.
     *
     * `FLAG_ACTIVITY_NEW_TASK` is required: a notification's activity PendingIntent launches with
     * no activity context, and without the flag the launch is silently rejected — the notification
     * shows but tapping does nothing. The request code must also differ from
     * [app.tenshu.fit.android.FitImportForegroundService]'s (it uses 0 for the same MainActivity
     * intent): PendingIntent equality ignores flags and extras, so equal request codes collapse
     * both services' intents into one shared record whose flags depend on creation order.
     */
    private fun openAppPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            OPEN_REQUEST_CODE,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_OPEN_RECORDING, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * The FGS type mask restricted to the permissions actually held right now, so every declared
     * type is permission-backed (the Android 14+ contract). `health` (backed by
     * `ACTIVITY_RECOGNITION`) is the recorder's natural anchor and is granted alongside location by
     * the Start-time JIT ask; `connectedDevice` is added only once the user has opted a BLE strap
     * in (Bluetooth granted), never before.
     */
    private fun foregroundServiceType(): Int {
        var type = 0
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (granted(Manifest.permission.ACTIVITY_RECOGNITION)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        }
        if (hasBluetoothPermission()) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        return type
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasBluetoothPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_CONNECT) || granted(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            true // pre-S: BLUETOOTH/BLUETOOTH_ADMIN are normal (manifest) permissions
        }

    companion object {
        private const val CHANNEL_ID = "fit_recording"
        private const val NOTIFICATION_ID = 42
        private const val REVIEW_NOTIFICATION_ID = 43
        private const val OPEN_REQUEST_CODE = 42
        private const val NOTIFICATION_UPDATE_MS = 30_000L

        private const val ACTION_PAUSE = "app.tenshu.fit.recorder.PAUSE"
        private const val ACTION_RESUME = "app.tenshu.fit.recorder.RESUME"
        private const val ACTION_STOP = "app.tenshu.fit.recorder.STOP"

        /** Launch-intent extra: surface the in-flight recording (live or review screen). */
        const val EXTRA_OPEN_RECORDING = "app.tenshu.fit.OPEN_RECORDING"

        /**
         * Starts the recorder service, returning whether the OS allowed it.
         *
         * ⚠ Refusable since API 31 — normally allowed because this follows an on-screen tap, but a
         * caller that is not certain the app is foreground must check the result rather than assume
         * a recording is running. Throwing here would take the process down (see
         * `startAndPromoteForeground`).
         */
        fun start(context: Context): Boolean {
            ensureChannel(context)
            return try {
                ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
                true
            } catch (_: IllegalStateException) {
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.recording_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }
}
