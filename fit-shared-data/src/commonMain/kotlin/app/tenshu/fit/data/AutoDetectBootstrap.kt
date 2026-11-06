package app.tenshu.fit.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.TimeZone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.tenshu.core.common.util.runCatchingCancellable

/**
 * Promotes Timeline-captured movements into workouts, once per launch
 * (`fit-automatic-activity-detection.md` D5).
 *
 * The retrospective half of D5, and the reason Fit needs no detector of its own: Timeline senses
 * live, this looks *backwards* at traces that are already closed and written. There is no race to
 * lose, so there is nothing to coordinate between the two apps.
 *
 * ⚠ **Gated on the realm preference**, read once here rather than observed. A user who turns the
 * toggle on mid-session gets their backlog at the next launch, not the next tick — which is the
 * honest behaviour for a pass that walks every trace, and avoids a toggle flip triggering a bulk
 * write while they are still deciding.
 *
 * ⚠⚠ **Every path reports why, including the boring ones.** The first version returned silently
 * when the toggle was off, when there were no traces, and when nothing was promotable — so on a
 * device "nothing happened" was indistinguishable from "it is broken", which is exactly what the
 * first device pass ran into. An unattended pass leaves no other witness, and an empty result
 * proves nothing about its cause.
 */
class AutoDetectBootstrap(
    private val repository: FitRepository,
    private val preferences: AutoDetectPreferences,
    scope: CoroutineScope,
    /** ⚠ Must match the zone the repository projected its workouts with (see supersession). */
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    private val promoter: DetectedWorkoutPromoter = DetectedWorkoutPromoter(repository),
) {

    /**
     * What the pass concluded. Returned as well as logged so a test can assert the *reason*,
     * not merely that nothing was written — a pass that no-ops for the wrong reason looks
     * identical to one that no-ops correctly.
     */
    sealed interface Outcome {
        /** The realm toggle is off; the user has not asked for this. */
        data object Disabled : Outcome
        /** Timeline has captured nothing — not installed, not recording, or nothing yet. */
        data object NoTraces : Outcome
        /**
         * Traces exist but none clears the promotion bar.
         *
         * [byReason] is what makes this actionable: "0 of 15" says the feature is quiet, while
         * "NotAnExercise=12, TooShort=3" says whether the floor is wrong or the traces simply
         * are not workouts (§10 Q1). Settling a threshold wants evidence, not intuition.
         */
        data class NonePromotable(
            val traceCount: Int,
            val byReason: Map<WorkoutPromotion.Rejection, Int> = emptyMap(),
        ) : Outcome
        /**
         * Nothing new to write: every promotable movement is either already promoted or already
         * covered by a real workout.
         *
         * ⚠ The two are worth not conflating in the message — "already promoted" would be a lie
         * about a movement that was *never* promoted because Health Connect already logged it,
         * and this whole diagnostic exists so a quiet launch says something true.
         */
        data class NothingNew(val traceCount: Int) : Outcome
        data class Promoted(val count: Int, val traceCount: Int) : Outcome
        data class Failed(val error: Throwable) : Outcome
    }

    private companion object {
        /** Enough to characterise a corpus without turning a launch into a log dump. */
        const val DETAIL_LIMIT = 25
    }

    /** Serialises passes (the launch pass and any re-run). */
    private val passLock = kotlinx.coroutines.sync.Mutex()

    /** Whether the last pass was held by the realm gate; guarded by [passLock]. */
    private var heldByRealmGate = false

    init {
        // ⚠⚠ ADR 0025 §8.6 (Codex P1 on d2a13fe22): this pass WRITES — promotions, and the
        // supersession deletes — and used to run straight away at launch, the supersession with no
        // gate at all. Now it waits for the realm gate, every delete goes through it, and a pass the
        // gate refuses re-runs on the next release. The marker is taken before any work (the
        // subscribe-before-work rule), and the re-run decides under [passLock], after the held
        // pass has recorded that it was held — so the order of release and refusal cannot matter.
        val releasesSeen = repository.realmReleaseMarker()
        scope.launch {
            repository.onEachRealmRelease(since = releasesSeen) { runGated(onlyIfHeld = true) }
        }
        scope.launch { runGated(onlyIfHeld = false) }
    }

    private suspend fun runGated(onlyIfHeld: Boolean) {
        repository.awaitRealmWritable()
        passLock.withLock {
            if (onlyIfHeld && !heldByRealmGate) return@withLock
            val outcome = runOnce()
            heldByRealmGate =
                outcome is Outcome.Failed && outcome.error is app.tenshu.reachability.RealmWritesHeldException
            if (heldByRealmGate) fitLog("auto-detect: held by the realm gate — re-runs on the next release")
        }
    }

    internal suspend fun runOnce(): Outcome {
        val outcome = try {
            promoteOnce()
        } catch (e: Throwable) {
            Outcome.Failed(e)
        }
        when (outcome) {
            Outcome.Disabled -> fitLog("auto-detect: skipped — the realm toggle is off")
            Outcome.NoTraces -> fitLog(
                "auto-detect: nothing to do — no movement traces are readable " +
                    "(Timeline not installed, not recording, or nothing captured yet)"
            )
            is Outcome.NonePromotable -> fitLog(
                "auto-detect: 0 of ${outcome.traceCount} trace(s) are workout material — " +
                    outcome.byReason.entries
                        .sortedByDescending { it.value }
                        .joinToString { "${it.key}=${it.value}" }
            )
            is Outcome.NothingNew -> fitLog(
                "auto-detect: ${outcome.traceCount} trace(s), nothing new to write " +
                    "(already promoted, or already covered by a real workout)"
            )
            is Outcome.Promoted -> fitLog(
                "auto-detect: promoted ${outcome.count} workout(s) from ${outcome.traceCount} trace(s)"
            )
            is Outcome.Failed -> fitLog("auto-detect: FAILED", outcome.error)
        }
        return outcome
    }

    /**
     * Per-trace detail, for tuning the promotion floor against real data (§10 Q1).
     *
     * ⚠⚠ **Deliberately carries no location and no timestamp.** Not coordinates, not a place
     * name, and not the `traceId` — which embeds the movement's wall-clock start, so a pasted log
     * would say when its owner went where. Mode, duration, distance and confidence are what a
     * threshold decision needs and are the most this may say. This repository is public and these
     * are someone's real movements.
     *
     * ⚠ Capped, and says so when it caps — a truncated list that looks complete is how "we
     * covered everything" becomes false.
     */
    private fun logDecisions(
        label: String,
        decisions: List<Pair<app.tenshu.fit.model.DetectedMovement, WorkoutPromotion.Decision>>,
    ) {
        if (decisions.isEmpty()) return
        fitLog("auto-detect: $label — ${decisions.size}")
        decisions.take(DETAIL_LIMIT).forEachIndexed { i, (m, d) ->
            val verdict = (d as? WorkoutPromotion.Decision.Reject)?.reason?.toString() ?: "PROMOTE"
            val source = if (m.traceId.startsWith("step:")) "step" else "trace"
            val distance = m.distanceM?.let { "${it}m" } ?: "—"
            fitLog(
                "auto-detect:   [$i] $source mode=${m.mode} ${m.durationSec}s $distance " +
                    "conf=${m.modeConfidence} -> $verdict"
            )
        }
        if (decisions.size > DETAIL_LIMIT) {
            fitLog("auto-detect:   … ${decisions.size - DETAIL_LIMIT} more not listed")
        }
    }

    /** What was actually written, as written — the only honest source for this line. */
    private fun logWritten(written: List<app.tenshu.fit.model.Workout>) {
        if (written.isEmpty()) return
        fitLog("auto-detect: wrote — ${written.size}")
        written.take(DETAIL_LIMIT).forEachIndexed { i, w ->
            val source = if (WorkoutPromotion.traceIdOf(w.workoutId)?.startsWith("step:") == true) "step" else "trace"
            val distance = w.distanceM?.let { "${it}m" } ?: "—"
            fitLog("auto-detect:   [$i] $source ${w.activityType} ${w.durationSec}s $distance")
        }
        if (written.size > DETAIL_LIMIT) {
            fitLog("auto-detect:   … ${written.size - DETAIL_LIMIT} more not listed")
        }
    }

    private suspend fun promoteOnce(): Outcome {
        if (!preferences.enabledOnce()) return Outcome.Disabled

        // ⚠ Two sources, one promotion path: Timeline's GPS traces (Tier 2) and the platform's
        // step data (Tier 1). Tier 1 is what works on a phone whose health store has steps but no
        // exercise sessions — and it is the only source on a device without Timeline.
        val traces = repository.detectedMovements().first()
        val bouts = repository.detectedStepBouts()
        val movements = traces + bouts
        if (movements.isEmpty()) return Outcome.NoTraces

        val decisions = movements.map { it to WorkoutPromotion.decide(it) }

        // ⚠⚠ Logged on EVERY launch, not only when nothing promotes. The first version gated this
        // on a total refusal, so the moment a single movement promoted the other hundred-odd
        // refusals went silent — and those refusals are the whole evidence for whether the floor
        // is right. On a real device 17 of 178 promoted, which hid 161 verdicts behind one
        // success.
        val refused = decisions.filter { it.second is WorkoutPromotion.Decision.Reject }
        if (refused.isNotEmpty()) {
            fitLog(
                "auto-detect: refused ${refused.size} of ${decisions.size} — " +
                    refused.mapNotNull { (_, d) -> (d as? WorkoutPromotion.Decision.Reject)?.reason }
                        .groupingBy { it }.eachCount().entries
                        .sortedByDescending { it.value }
                        .joinToString { "${it.key}=${it.value}" }
            )
            logDecisions("refused detail", refused)
        }

        if (decisions.none { it.second is WorkoutPromotion.Decision.Promote }) {
            return Outcome.NonePromotable(
                traceCount = movements.size,
                byReason = decisions
                    .mapNotNull { (_, d) -> (d as? WorkoutPromotion.Decision.Reject)?.reason }
                    .groupingBy { it }.eachCount(),
            )
        }

        // The existing ids are a cost optimisation only — the write is idempotent by
        // construction — so reading them is best-effort and an empty set is safe.
        val existing = runCatchingCancellable { repository.workouts().first() }.getOrDefault(emptyList())

        // ⚠⚠ Through the realm gate, one check per promotion (ADR 0025 §8.6, Codex P1 on b25d524d2):
        // the entry wait in `runGated` samples the gate once, and reading traces takes time. A
        // refusal propagates — `runOnce` records it as held, and the release re-runs the pass.
        val promoted = promoter.promote(movements, existing, beforeEachWrite = { repository.refuseIfRealmWritesHeld() })
        // ⚠⚠ Logged from what the promoter actually WROTE, never from `decide()`. The first
        // version logged the raw verdict, which runs before the promoter's coverage and
        // already-present guards — it announced "promoting 160" on a launch that wrote nothing.
        // A log that overstates what happened is worse than no log; it is a false witness in the
        // one place there is no other.
        logWritten(promoted)

        // ⚠⚠ The other half of D3, and the gap that shipped: supersession used to run only inside
        // an import, but a promotion happening *after* the last import left a duplicate standing
        // until the next one — two "Walking" rows at the same minute, one imported and one
        // detected. Either side can arrive second, so both sides tidy up.
        // ⚠ Through the realm gate, one check per delete (ADR 0025 §8.6). A refusal propagates —
        // `runOnce` records it as held, and the release re-runs the pass — instead of being
        // swallowed as a failed tidy-up.
        val superseded = try {
            repository.supersedeDetectedWorkouts(zone, beforeEachDelete = { repository.refuseIfRealmWritesHeld() })
        } catch (e: app.tenshu.reachability.RealmWritesHeldException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            0
        }
        if (superseded > 0) {
            fitLog("auto-detect: $superseded promotion(s) dropped — a real workout already covers them")
        }

        return if (promoted.isEmpty()) Outcome.NothingNew(movements.size)
        else Outcome.Promoted(promoted.size - superseded, movements.size)
    }
}
