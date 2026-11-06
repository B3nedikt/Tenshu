package app.tenshu.timeline.android

import android.app.Application
import app.tenshu.timeline.data.capture.CapturedVisitOutbox
import app.tenshu.timeline.data.capture.CapturedVisitWriter
import app.tenshu.timeline.capture.TimelineCaptureControl
import app.tenshu.timeline.data.TimelineRepository
import app.tenshu.timeline.data.capture.RailRefinementSweep
import app.tenshu.timeline.data.capture.restoreOngoingMove
import app.tenshu.timeline.data.capture.RailRefinementTrigger
import app.tenshu.timeline.data.geocode.GeocodingBackfillTrigger
import app.tenshu.timeline.data.geocode.PlaceGeocodingControl
import app.tenshu.diagnostics.Diagnostics
import app.tenshu.diagnostics.DiagnosticCrashHandler
import app.tenshu.diagnostics.FileDiagnosticSink
import app.tenshu.timeline.data.geocode.PlaceLabelEnricher
import app.tenshu.timeline.di.platformClientModule
import app.tenshu.timeline.di.timelineClientModule
import app.tenshu.timeline.di.timelineModules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import java.io.File
import android.content.pm.ApplicationInfo
import org.koin.core.context.startKoin
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.SCHEMA_LOCKSTEP_LOG_TAG
import org.tenshu.core.common.schemaLockstepLine

// Timeline Android Application (docs/proposals/timeline-app.md §7 Ph7). Starts Koin with the
// standalone data graph + a best-effort region client that reaches Control over Binder for real
// offline basemaps (maps-region-delivery.md §7); if Control is absent the trip route keeps the
// bundled demo (bindService returning false is non-fatal).
class TimelineApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Schema-lockstep beacon (smoke gate asserts Control + clients share this).
        android.util.Log.i(SCHEMA_LOCKSTEP_LOG_TAG, schemaLockstepLine(packageName))

        // Device-local diagnostic log (single LogItem stream) — installed first, so even a crash
        // during startup is recorded. Breadcrumbs + crashes land here, viewable in Settings →
        // Diagnostics; nothing is uploaded (a future opt-in owns that).
        val diagnostics = FileDiagnosticSink(File(filesDir, "diagnostics/timeline.log"))
        Diagnostics.install(diagnostics)
        DiagnosticCrashHandler.install(diagnostics)
        Diagnostics.i("app", "Timeline process started", code = "app_start")

        val koin = startKoin {
            androidLogger()
            androidContext(this@TimelineApplication)
            modules(listOf(timelineClientModule, platformClientModule) + timelineModules() + timelineAndroidModule(debuggable = isDebuggable()))
        }.koin

        // Replay visits stranded in the durable outbox (writes that failed while Control had no
        // realm / was unreachable — tech-debt "silent visit loss"). Idempotent; a still-dead
        // proxy just retains the entries for the next trigger (next visit / next launch).
        //
        // ⚠⚠ ADR 0025 §Decision 8.6: gated on Control's device having a realm. While it is known to
        // have none (or the realm read is pending, which is where every launch starts), the entries
        // stay in the outbox — and the capture engine journals new visits without writing them.
        // This collector is the RESUMPTION: one drain once the gate first opens, then one on every
        // held → writable edge (a realm appearing; each recovery's realm read). Without a gate
        // (`realmWriteGate == null`) it drains once at start, exactly as before.
        appScope.launch {
            val gate = koin.get<TimelineRepository>().realmWriteGate
            val writer = CapturedVisitWriter(
                client = koin.get(),
                context = koin.get(),
                enricher = koin.get<PlaceLabelEnricher>(),
                outbox = koin.get<CapturedVisitOutbox>(),
                writesHeld = { gate?.realmWritesHeld ?: false },
            )
            // ⚠⚠ Subscribe BEFORE the initial drain (Codex P2 on fde421540): the marker is taken
            // now, so a release that lands while the initial drain runs — or before it starts —
            // still drains once more afterwards. Drains of the shared outbox are serialised by its
            // own lock, so the follow-up never runs beside the initial one.
            if (gate != null) {
                val releasesSeen = gate.realmReleaseMarker
                launch { gate.onEachRealmWritableEdge(since = releasesSeen) { writer.drainOutbox() } }
                gate.awaitRealmWritable()
            }
            writer.drainOutbox()
        }

        // Place-name lookups (place-geocoding.md G2): backfill blank-label places on every opt-in
        // edge — including app start when the toggle is already on. Consent-gated inside the
        // enricher, so with the toggle off this collector just idles.
        appScope.launch {
            // ⚠ ADR 0025 §8.6: the backfill's writes go through the realm gate; a held backfill
            // just ends, and the next launch (or opt-in edge) runs it again.
            val gate = koin.get<TimelineRepository>().realmWriteGate
            val enricher = koin.get<PlaceLabelEnricher>()
            GeocodingBackfillTrigger(
                control = koin.get<PlaceGeocodingControl>(),
                backfill = {
                    try {
                        enricher.backfill(beforeEachWrite = { gate?.refuseIfRealmWritesHeld() })
                    } catch (e: app.tenshu.reachability.RealmWritesHeldException) {
                        Diagnostics.i("geocode", "backfill held by the realm gate", code = "backfill_held")
                    }
                },
            ).run()
        }

        // Surface the journey the user is *still on* (timeline-movement-capture.md §12.6). The live
        // OngoingMoveState died with the previous process — but the OpenMoveJournal did not, and on
        // ColorOS the capture service is force-killed mid-walk routinely, so "open the app, see
        // nothing about the walk you are on" was the ordinary case rather than a corner. Reads the
        // journal (never clears it — the segmenter owns that record) and republishes it if it is
        // still recordable and still fresh.
        //
        // ⚠ Gated on the capture opt-in: a journal from a session the user has since switched
        // capture off for is not a journey they are on. Read straight off the persisted flag, which
        // AndroidTimelineCaptureControl loads synchronously in its constructor.
        if (koin.get<TimelineCaptureControl>().enabled.value) {
            restoreOngoingMove(this)?.let {
                Diagnostics.i(
                    "capture",
                    "resumed open move: ${it.distanceMeters}m since ${it.startMs}",
                    code = "ongoing_move_restored",
                )
            }
        }

        // Keep capture alive across OEM force-stops: arm the WorkManager watchdog whenever the
        // opt-in is on (mirrors Drive's BackupWorkScheduler). The FGS start path was otherwise
        // foreground-only, so a ColorOS-style mid-session kill left capture dead until reopen.
        CaptureWatchdogScheduler(this, koin.get<TimelineCaptureControl>()).start()

        // Rail re-classification (timeline-movement-capture.md M9-6): one catch-up pass on launch,
        // gated on capture being on — the only source of refinable traces (imported ones carry
        // Google's own label and are skipped). Bounded + idempotent, so it converges over history
        // across launches without a scheduler; unavailable tiles just defer to the next run.
        appScope.launch {
            val client = koin.get<TenshuClient>()
            // ADR 0025 §8.6: the sweep's writes go through the realm gate.
            val railGate = koin.get<TimelineRepository>().realmWriteGate
            val sweep = RailRefinementSweep(
                client = client,
                context = koin.get(),
                beforeEachWrite = { railGate?.refuseIfRealmWritesHeld() },
            )
            RailRefinementTrigger(
                control = koin.get<TimelineCaptureControl>(),
                sweep = { sweep.sweep() },
                // ⚠ Load-bearing, not defensive: the sweep's first act is an IPC read to Control, and
                // without this it raced the binding on every cold start — reading an empty store and
                // concluding there was nothing to refine. See the trigger's KDoc.
                proxyConnected = client.proxyConnected,
            ).runOnce()
        }
    }

    /**
     * Is this build debuggable? Gates the §18.9 raw-fix corpus.
     *
     * ⚠ Read from the manifest flag rather than a `BuildConfig.DEBUG` constant: this module does not
     * enable `buildFeatures.buildConfig`, and the flag is the more honest signal anyway — it is what
     * the OS itself treats as "this build is a development build".
     */
    private fun isDebuggable(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
