package app.tenshu.fit.android.recorder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import app.tenshu.fit.android.MainActivity
import app.tenshu.fit.android.R
import app.tenshu.fit.data.RecordingJournalCodec
import app.tenshu.fit.data.isResumable
import java.io.File

/**
 * Tells the user, within a couple of minutes, that their recording died — the half of surviving an
 * OEM kill that the app can actually deliver (§13.5e).
 *
 * ⚠⚠ **It notifies; it does not restart capture itself.** The obvious implementation — a background
 * job that restarts `RecordingService` — was built, measured and thrown away: location is a
 * *while-in-use* permission, so a `location`-typed foreground service started from the background
 * throws `SecurityException`, and the uncaught throw inside `onStartCommand` **crash-looped the app**
 * (measured on-device 2026-08-15). Battery-optimisation exemption does not lift that; only
 * `ACCESS_BACKGROUND_LOCATION` would, and Fit deliberately does not hold it (`AndroidManifest.xml`:
 * "GPS is acquired ONLY during an active workout"). So: the alarm notices, the notification tells,
 * and the user's tap brings the app to the foreground — which is what makes the engine's resume
 * legal. One tap instead of a lost run.
 *
 * ⚠ **AlarmManager, not WorkManager.** Timeline's watchdog is a 15-minute `PeriodicWorkRequest`,
 * whose floor would routinely lose half a 25-minute run, and adding `work-runtime` to this module
 * breaks its resource link (its manifest's `@bool/enable_system_job_service_default` does not
 * resolve here, though the same dependency is fine in `:timeline-android`). An alarm needs no
 * dependency, survives the process death that matters, and re-arms itself. ⚠ `setAndAllowWhileIdle`
 * is inexact and is throttled to roughly one firing per 9 minutes in Doze — a phone being carried on
 * a run is generally not in Doze, and the exact-alarm permission is not worth its user-visible cost
 * for a nudge.
 */
internal object RecorderWatchdog {

    private const val INTERVAL_MS = 2 * 60 * 1000L
    private const val CHANNEL_ID = "fit_recording_interrupted"
    private const val NOTIFICATION_ID = 44
    private const val OPEN_REQUEST_CODE = 44
    private const val ALARM_REQUEST_CODE = 45

    /** Arm the next check. Called when a session starts, and by each check that finds one owed. */
    fun schedule(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            manager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + INTERVAL_MS,
                alarmIntent(context),
            )
        }
    }

    /** The session ended (stopped, saved or discarded) — stop watching and drop any nudge. */
    fun cancel(context: Context) {
        runCatching { context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context)) }
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
    }

    /**
     * "Your phone stopped the recording — tap to pick it up." One notification id, re-posted rather
     * than duplicated, so a session that stays interrupted stays exactly one line in the shade.
     */
    fun notifyInterrupted(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.recording_interrupted_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        val open = PendingIntent.getActivity(
            context,
            OPEN_REQUEST_CODE,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(RecordingService.EXTRA_OPEN_RECORDING, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.recording_interrupted_title))
            .setContentText(context.getString(R.string.recording_interrupted_text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        ALARM_REQUEST_CODE,
        Intent(context, RecorderWatchdogReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/**
 * Where the check actually happens. After a kill this runs in a **fresh process with no engine**, so
 * the journal file is the only source of truth about whether a session is still owed — the same file
 * the engine reads, by the same codec.
 */
class RecorderWatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val file = File(context.filesDir, RECORDING_JOURNAL_FILE)
        if (!file.exists()) return
        val lines = runCatching { file.readLines() }.getOrNull() ?: return
        val recovered = RecordingJournalCodec.decode(lines) ?: return
        // Not resumable = stopped by the user, or aged out of the resume window. Either way the
        // chain ends here: no notification, no re-arm.
        if (!recovered.isResumable(System.currentTimeMillis())) return
        RecorderWatchdog.notifyInterrupted(context)
        RecorderWatchdog.schedule(context)
    }
}

/** Shared with [AndroidRecordingEngine]: one name for the journal, so the two readers cannot drift. */
internal const val RECORDING_JOURNAL_FILE = "recording.journal"
