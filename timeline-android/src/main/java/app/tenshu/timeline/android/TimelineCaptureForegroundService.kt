package app.tenshu.timeline.android

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
import app.tenshu.timeline.data.capture.LocationCaptureEngine
import app.tenshu.timeline.data.capture.LocationCaptureSource
import app.tenshu.timeline.data.capture.CapturedVisitOutbox
import app.tenshu.timeline.data.capture.RailRefinementSweep
import app.tenshu.timeline.data.capture.RailRefinementTrigger
import app.tenshu.timeline.capture.TimelineCaptureControl
import app.tenshu.timeline.data.TimelineRepository
import app.tenshu.diagnostics.Diagnostics
import app.tenshu.timeline.data.geocode.PlaceLabelEnricher
import app.tenshu.timeline.data.wifi.WifiPlaceFingerprint
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.core.TContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * The **live-capture foreground service** (I3b-2, `docs/proposals/timeline-ingestion.md` §4.1). The
 * Timeline app process has no `ScheduleService`/WorkManager substrate of its own, so continuous
 * capture is kept alive by a `location`-typed foreground service (mirrors Drive's
 * `FolderUploadForegroundService`) rather than a scheduled job. While it runs it collects the bound
 * [LocationCaptureSource] through the shared [LocationCaptureEngine] and persists detected dwells
 * **and movement** (movement capture M3) — **through Control's proxy** ([TenshuClient]), so a capture
 * is durable and synced rather than living in a process-local store that dies with the app
 * (`timeline-app.md` §10).
 *
 * The persistent notification is the **required capture indicator** (threat-model A35): the user
 * always sees that their timeline is being recorded, and turns it off from Settings. Started/stopped
 * by [TimelineCaptureServiceLauncher] on the [app.tenshu.timeline.capture.TimelineCaptureControl]
 * opt-in edge; it also self-stops if the source becomes unavailable (permission revoked).
 */
class TimelineCaptureForegroundService : Service() {

    private val source: LocationCaptureSource by inject()
    private val client: TenshuClient by inject()
    private val context: TContext by inject()
    private val enricher: PlaceLabelEnricher by inject()
    private val outbox: CapturedVisitOutbox by inject()
    private val wifi: WifiPlaceFingerprint by inject()
    private val captureControl: TimelineCaptureControl by inject()
    private val repository: TimelineRepository by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        // Durable health trail (readable in Settings/adb): "the FGS started at T". Combined with the
        // watchdog's restart count it tells you how hard the OEM is killing capture overnight.
        createNotificationChannel()
        // ⚠ Starting a *location* FGS from the background (watchdog/boot re-arm) throws a
        // SecurityException on Android 14+ unless the app holds location permission AND is in an
        // eligible state (battery-exempt). That is thrown here, in the service's onCreate — *after*
        // CaptureRearm's own try/catch around startForegroundService — so without this guard it
        // becomes an uncaught crash. (Caught by the M9 diagnostics slice on first run.) A refused
        // start is a graceful stop, not a crash; the next eligible trigger re-arms.
        val start = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                } else {
                    0
                },
            )
        }
        if (start.isFailure) {
            // ⚠⚠ Name the cause. This used to be `runCatching { … }.isSuccess`, which discarded the
            // exception and logged a guess ("no permission / not eligible") — so the one line that
            // could have explained an hour-long capture hole explained nothing, and the actual reason
            // (a background-started FGS being denied the *location type* without
            // ACCESS_BACKGROUND_LOCATION) had to be recovered from `logcat` months later.
            val cause = start.exceptionOrNull()
            Diagnostics.w(
                "capture",
                "foreground start refused — stopping: ${cause?.let { it::class.simpleName }}: ${cause?.message}",
                code = "fgs_start_refused",
            )
            stopSelf()
            return
        }
        CaptureHealthStore(this).recordServiceStarted(System.currentTimeMillis())
        Diagnostics.i("capture", "foreground service started", code = "fgs_started")
        // Rail re-classification, driven by capture rather than by app launches
        // (timeline-movement-capture.md §32.3). The launch pass in TimelineApplication is the
        // catch-up over history; this is the one that keeps *today* correct — on a day the user
        // travels and never opens the app, nothing else ever asks for a sweep, and an unrefined
        // `drive` leg is enough to leave a whole train journey shredded across station stops.
        // Scoped to the service, because outside a capture session nothing produces refinable traces.
        // ADR 0025 §8.6: the sweep's writes go through the realm gate.
        val railSweep = RailRefinementSweep(
            client = client,
            context = context,
            beforeEachWrite = { repository.realmWriteGate?.refuseIfRealmWritesHeld() },
        )
        val railTrigger = RailRefinementTrigger(
            control = captureControl,
            sweep = { railSweep.sweep() },
            proxyConnected = client.proxyConnected,
        )
        scope.launch { railTrigger.runOnNewTraces() }

        scope.launch {
            // Suspends for the lifetime of capture; returns only if the source is unavailable
            // (e.g. permission revoked) or the fix flow closes — then there's nothing to keep alive.
            // Enricher rides along so a freshly captured place gets its name looked up right
            // after the visit lands (consent-gated inside; a declined opt-in makes it a no-op).
            // The outbox makes every detected visit durable before the ops run — a proxy that
            // has no realm yet / is unreachable retains the entry for replay instead of losing
            // the visit and killing this service (tech-debt "silent visit loss").
            LocationCaptureEngine(
                source,
                client,
                context,
                enricher = enricher,
                outbox = outbox,
                wifi = wifi,
                onTraceRecorded = railTrigger::onTraceRecorded,
                // ADR 0025 §Decision 8.6: no visits into a store that belongs to no realm. Held
                // entries stay in the outbox; TimelineApplication's drain collector replays them
                // when a realm appears.
                writesHeld = { repository.realmWriteGate?.realmWritesHeld ?: false },
            ).run()
            // The engine returned → the source went unavailable (permission revoked) or the flow
            // closed. Recorded because it explains a capture gap that isn't an OEM kill.
            Diagnostics.w("capture", "engine stopped — source unavailable; service self-stopping", code = "engine_stopped")
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setSmallIcon(R.drawable.ic_notification_timeline)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.capture_channel))
            .setVibrationEnabled(false)
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "tenshu_timeline_capture"
        const val NOTIFICATION_ID = 0x71CE
    }
}
