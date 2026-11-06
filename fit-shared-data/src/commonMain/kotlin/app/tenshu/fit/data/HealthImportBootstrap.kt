package app.tenshu.fit.data

import app.tenshu.fit.model.CategoryId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import org.tenshu.core.common.util.runCatchingCancellable

/**
 * Fit's data-refresh pass: replays the durable recorder outbox
 * ([FitRepository.drainPendingWorkouts] — always), then refreshes from the platform health
 * source (Health Connect today) through the [FitRepository.startImport] seam (gated on [HealthImporter.autoRefreshReady]).
 *
 * This used to live in `InProcessFitProxy.init`, where it doubled as first-run *seeding* of an
 * in-memory store. Now that health rows land in Control's durable store, it is only a refresh: the
 * record ops are content-addressed, so re-importing a day or a workout folds onto the existing row
 * instead of duplicating it. Nothing here creates data the store didn't already have — a workout
 * recorded in Fit is written by the recorder and stays written.
 *
 * **[HealthImporter.autoRefreshReady] gates it**, and that guard is the important part. With
 * no live source there is nothing to refresh, and an unattended pass against an ungranted one
 * would only park a permission banner. (It once also kept the [ReferenceHealthImporter] fixture out
 * of the synced realm; that is now `FitProjectionRepository`'s refusal to persist any
 * [ImportOutcome.Sample], manual or automatic — `fit-app.md` §9.15.)
 *
 * ⚠⚠ **It runs on every foreground, not once per launch.** It was once-per-*process*, which is a
 * different thing on Android than it looks: a Fit process survives the app being swiped away and
 * lives for days. Diagnosed 2026-08-13 on the maintainer's phone — the process had been up
 * 13 h 26 m, so the morning's ride was never pulled out of Health Connect and History's newest row
 * was the previous evening's. Android's shell calls [refresh] from `ON_START`;
 * [minRefreshInterval] collapses the cold-start pair (this `init` plus the first `ON_START`) and a
 * rotation's `ON_START` into one, and two overlapping passes would in any case be caught by
 * `startImport`'s own in-flight lock.
 *
 * iOS drives the same [refresh] from `scenePhase == .active`, through
 * `FitSurface.refreshHealthImport()` — both tiers (route-1 `FitLocalRuntime` and the EPC
 * `EpcFitClient`) hand their bootstrap to that seam rather than constructing and discarding it,
 * which is what left iOS re-reading nothing on a re-foreground.
 *
 * Fire-and-forget by design: an unreachable Control (or a health source the user hasn't granted)
 * must not take the app down at launch, so the failure is caught and reported rather than thrown
 * into the app scope's handler.
 */
class HealthImportBootstrap(
    private val repository: FitRepository,
    private val scope: CoroutineScope,
    /** The platform importer, or `null` where none is bound (desktop) — then no refresh runs. */
    private val importer: HealthImporter?,
    /**
     * How close together two refreshes may run. Short enough that switching back from the health
     * app to see a just-finished workout works, long enough that a cold start's two triggers and a
     * rotation's are one pass.
     */
    private val minRefreshInterval: Duration = 60.seconds,
    /** Monotonic on purpose: this measures an interval, and a wall clock can jump. */
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {

    private val gate = Mutex()
    private var lastRefreshAt: TimeMark? = null

    /**
     * Serialises passes: two never overlap, so a re-run requested while one is in flight runs
     * after it, never beside it (ADR 0025 §8.6 — no concurrent double drain or import).
     */
    private val passLock = Mutex()

    /** [FitRepository.realmHeldStops] as of the start of the last pass; guarded by [passLock]. */
    private var stopsHandled = 0L

    /** Test-only: how many re-run requests have reached their decision (run or skip). */
    internal var rerunDecisions = 0
        private set

    init {
        // ⚠⚠ The subscribe-before-work rule (ADR 0025 §8.6): the release marker is taken HERE,
        // before the launch pass is even requested, so a release that lands while that pass runs
        // (or holds it) still produces the re-run. Every held → writable transition is a release —
        // ABSENT → PRESENT and PENDING → PRESENT/UNKNOWN alike.
        val releasesSeen = repository.realmReleaseMarker()
        val stopsSeen = repository.realmHeldStops()
        scope.launch {
            repository.onEachRealmRelease(since = releasesSeen) {
                refresh(reason = "realm gate released", rerunStopped = true)
            }
        }
        // ⚠⚠ And on every held stop (Codex P2 on d0f9f1a53): a release can land between a refusal
        // and its stop being counted, and the release's handler then sees nothing to re-run. A
        // stop-driven request waits for the gate inside `refresh`, so it runs at once if the release
        // already happened and on the release otherwise — the ordering of the two cannot matter.
        // Duplicates collapse on [passLock] + [stopsHandled].
        scope.launch {
            repository.onEachRealmHeldStop(since = stopsSeen) {
                refresh(reason = "an import was held", rerunStopped = true)
            }
        }
        refresh(reason = "launch")
    }

    /**
     * Run the pass, unless one ran within [minRefreshInterval]. Safe to call from any lifecycle
     * callback; returns immediately, the work runs on [scope].
     */
    fun refresh(reason: String) = refresh(reason, rerunStopped = false)

    /**
     * [rerunStopped]: the realm gate has just released. Bypasses the debounce, but runs only if the
     * gate **held** an import since the last pass began ([FitRepository.realmHeldStops]) — rejected
     * at entry or stopped mid-run, which count the same. That counter is also how a pass that never
     * started is told from one that finished: a held pass bumps it, a finished one does not, so the
     * pass that was simply *waiting* in [FitRepository.awaitRealmWritable] and then ran cleanly is
     * not run twice.
     */
    private fun refresh(reason: String, rerunStopped: Boolean) {
        scope.launch {
            // ⚠⚠ ADR 0025 §Decision 8.6 — BEFORE the debounce, not inside the pass. With Control's
            // device known to have no realm (or the realm read still pending, which is where the
            // launch pass always starts), this suspends: nothing is drained or imported into a store
            // that belongs to no realm. The held pass then runs on its own when a realm appears.
            // Waiting ahead of the debounce means a pile of held foregrounds collapses into one pass
            // on release. Returns at once where no realm probe is wired (iOS route-1, tests) or the
            // read could not tell — today's behaviour. ⚠ Entry only: the pass itself re-checks the
            // gate before every write (the drain per entry, the import per batch).
            repository.awaitRealmWritable()
            passLock.withLock {
                val stops = repository.realmHeldStops()
                if (rerunStopped) rerunDecisions++
                val due = if (rerunStopped) {
                    stops != stopsHandled
                } else {
                    gate.withLock {
                        val since = lastRefreshAt?.elapsedNow()
                        since == null || since >= minRefreshInterval
                    }
                }
                if (!due) {
                    println(
                        if (rerunStopped) "[fit] $reason: no import was held since the last pass — no re-run"
                        else "[fit] $reason refresh skipped — one ran less than $minRefreshInterval ago",
                    )
                    return@withLock
                }
                gate.withLock { lastRefreshAt = timeSource.markNow() }
                stopsHandled = stops
                runPass(reason)
            }
        }
    }

    private suspend fun runPass(reason: String) {
        // Replay recorder saves journalled while Control was unreachable (fit-app.md §9.13)
        // BEFORE the refresh, and regardless of the importer — the outbox holds real
        // recorded data on every platform that binds one, importer or not.
        runCatchingCancellable { repository.drainPendingWorkouts() }
            .onFailure { println("[fit] $reason outbox drain failed: $it") }
        // The refresh runs only where an unattended import can actually succeed
        // ([HealthImporter.autoRefreshReady]): a grant-less source would abort into a
        // permanent banner every launch, an absent one would auto-write fixture data into
        // the durable, synced store. Manual imports stay ungated.
        val source = importer
        val ready = source != null &&
            runCatchingCancellable { source.autoRefreshReady() }
                .onFailure { println("[fit] autoRefreshReady failed: $it") }
                .getOrDefault(false)
        if (ready) {
            runCatchingCancellable { repository.startImport(CategoryId.entries.toSet(), automatic = true) }
                .onFailure { println("[fit] $reason health import failed: $it") }
        } else {
            println("[fit] $reason refresh skipped (importer=${importer?.let { it::class.simpleName } ?: "none"} not auto-refresh-ready)")
        }
    }
}
