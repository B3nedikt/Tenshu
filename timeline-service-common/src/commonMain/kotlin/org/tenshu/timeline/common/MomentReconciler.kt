package org.tenshu.timeline.common

import kotlin.math.roundToLong

/**
 * The **device-independent cross-source reconciler** (I6, `docs/proposals/timeline-ingestion.md`
 * §7.1): folds *near-duplicate* moments — the same real-world visit or activity captured by iOS
 * `CLVisit`, Android `FusedLocation`, a smartwatch-imported workout, and a Google backfill — into
 * one **canonical** moment with a **unioned `sources` provenance**. This is the problem the sync key
 * layer *cannot* solve: content-addressed `momentId`s from `(placeRef, start)` only fold *exact*
 * repeats; cross-source captures differ by seconds and metres, so they never collide and no
 * `SemanticMerger` (which merges two states of one key) ever fires.
 *
 * **Pure `commonMain`** — the reusable clustering, reused at the per-device reconciliation pass and
 * (later) inside a `MomentMerger` at merge time, exactly as `VisitDetector`/`TripDetector` are.
 *
 * **Fuzzy identity + convergence without key churn (§7.1).** Two `Visit` moments are the same iff
 * they fall in the same **place cell** (coords snapped to a grid — cross-source captures rarely share
 * a `placeId`, but they share *location*) **and** the same **time bucket**. `Activity` moments dedupe
 * by their **subject** (same `Workout`). The cluster's key is derived **deterministically off that
 * invariant** — `placeCell + timeBucket`, *not* off which sources are present — so two devices
 * reconciling independently produce the **same** canonical key; ordinary sync then collides them and a
 * `MomentMerger` unions the `sources`. Keying off the present-sources set instead would churn the key
 * every time a new source arrived and re-duplicate.
 *
 * ⚠ Known limit (a §7.1 open sub-question): time **bucketing** has boundary cases — two captures of one
 * visit straddling a bucket edge won't fold. A tolerance-overlap identity would catch them but has no
 * stable key; the doc chose determinism. Tune [ReconciliationConfig.visitTimeBucketMs] with real data.
 */
object MomentReconciler {

    /**
     * Cluster near-duplicate moments and elect a canonical per cluster. Input order is irrelevant;
     * output is one [ReconciledMoment] per real-world moment, ordered by canonical start time.
     * Subjectless moments (`Event`/`Quiet`) never dedupe — each is its own canonical.
     */
    fun reconcile(
        moments: List<ReconcilableMoment>,
        config: ReconciliationConfig = ReconciliationConfig(),
    ): List<ReconciledMoment> {
        val clusters = moments.groupBy { clusterKey(it, config) }

        return clusters.values.map { members ->
            val canonical = electCanonical(members, config)
            ReconciledMoment(
                canonicalKey = clusterKey(canonical, config),
                canonicalMomentId = canonical.momentId,
                supersededMomentIds = members.map { it.momentId }.filter { it != canonical.momentId }.sorted(),
                sources = members.map { it.source }.filter { it.isNotEmpty() }.distinct().sorted(),
                kind = canonical.kind,
                startMs = canonical.startMs,
                subjectKind = canonical.subjectKind,
                subjectId = canonical.subjectId,
            )
        }.sortedBy { it.startMs }
    }

    /**
     * The **invariant cluster key** (deterministic across devices, §7.1). Visits key on the place cell
     * + time bucket; activities on their workout subject; subjectless moments never merge (keyed by
     * their own id).
     */
    private fun clusterKey(m: ReconcilableMoment, config: ReconciliationConfig): String = when {
        m.kind == KIND_VISIT -> "v:${placeCell(m, config.placeGridE7)}:${m.startMs / config.visitTimeBucketMs}"
        // ⭐ A movement and the workout Fit promoted *from* it are one thing shown twice. They fold
        // on the trace id they share — an exact identity, not a time-and-proximity guess, because
        // the promoted workout's id embeds the trace it came from. Unrelated workouts that merely
        // overlap a movement are deliberately left alone: that fuzzy case is the wider I6 fold,
        // and guessing there would merge a cycle commute with a gym session that happened during it.
        m.kind == KIND_MOVEMENT && m.subjectId.isNotEmpty() -> "t:${m.subjectId}"
        m.kind == KIND_ACTIVITY && m.subjectId.startsWith(DETECTED_WORKOUT_PREFIX) ->
            "t:${m.subjectId.removePrefix(DETECTED_WORKOUT_PREFIX)}"
        m.kind == KIND_ACTIVITY && m.subjectId.isNotEmpty() -> "a:${m.subjectId}"
        else -> "m:${m.momentId}"
    }

    /**
     * Highest source-priority member wins; ties break by earliest start, then smallest id — total
     * + stable.
     *
     * ⚠⚠ Except that a **movement outranks the workout derived from it**, and that inverts the
     * rule the M-track wrote down ("a health `Workout` supersedes the captured `MOVEMENT`"). That
     * rule assumes the workout is independent evidence from a watch. A *promoted* one is not: it
     * was computed from this very movement and deliberately carries no route (§4.1), so electing
     * it would replace the only geometry with a strictly poorer copy of itself. This is exactly
     * the "unless the workout has no GPS and the capture has a route" carve-out — and for derived
     * pairs it is not an exception, it is always the case.
     */
    private fun electCanonical(members: List<ReconcilableMoment>, config: ReconciliationConfig): ReconcilableMoment =
        members.minWithOrNull(
            compareBy<ReconcilableMoment> { if (it.kind == KIND_MOVEMENT) 0 else 1 }
                .thenBy { sourceRank(it.source, config) }
                .thenBy { it.startMs }
                .thenBy { it.momentId },
        )!!

    private fun sourceRank(source: String, config: ReconciliationConfig): Int {
        val i = config.sourcePriority.indexOf(source)
        return if (i >= 0) i else config.sourcePriority.size // unknown sources sort last
    }

    /** A place identity by physical location (gridded coords), or the subject place id when there are no coords. */
    private fun placeCell(m: ReconcilableMoment, gridE7: Long): String =
        if (m.latE7 != 0L || m.lngE7 != 0L) "${snap(m.latE7, gridE7)},${snap(m.lngE7, gridE7)}"
        else m.subjectId.ifEmpty { m.momentId }

    private fun snap(e7: Long, gridE7: Long): Long = (e7.toDouble() / gridE7).roundToLong() * gridE7

    const val KIND_VISIT = "Visit"
    const val KIND_ACTIVITY = "Activity"
    const val KIND_MOVEMENT = "Movement"

    /**
     * ⚠⚠ A **cross-module contract**, not a local detail. Fit mints an auto-detected workout as
     * `detected:<traceId>` (`WorkoutPromotion.workoutIdFor`), which is the only thing that lets
     * this tell "the workout derived from that movement" from "a workout that happens to overlap
     * it". The two must move together; a change on either side silently un-folds the pair rather
     * than failing, so `derivedTracePairsFoldOntoTheMovement` is the test that would notice.
     *
     * It lives here rather than in Fit because this module is below both, and because the
     * alternative — Timeline parsing an id format it does not own — is the coupling this replaces.
     */
    const val DETECTED_WORKOUT_PREFIX = "detected:"
}

/** A moment reduced to what cross-source reconciliation needs. Coordinates are E7; instants epoch-ms. */
data class ReconcilableMoment(
    val momentId: String,
    val kind: String,
    val startMs: Long,
    val latE7: Long = 0,
    val lngE7: Long = 0,
    val subjectKind: String = "",
    val subjectId: String = "",
    /** Provenance tag (e.g. `capture` / `google` / `import`); drives canonical election + the union. */
    val source: String = "",
)

/**
 * One reconciled real-world moment: the elected [canonicalMomentId], the [supersededMomentIds] it
 * folds (marked superseded, **not** tombstoned — reversible if a source is later removed), and the
 * unioned [sources]. [canonicalKey] is the deterministic invariant key two devices agree on.
 */
data class ReconciledMoment(
    val canonicalKey: String,
    val canonicalMomentId: String,
    val supersededMomentIds: List<String>,
    val sources: List<String>,
    val kind: String,
    val startMs: Long,
    val subjectKind: String,
    val subjectId: String,
)

/** Tuning for [MomentReconciler]. Conservative first guesses — real values need captured data (§7.1). */
data class ReconciliationConfig(
    /** Visits to one place within the same bucket dedupe. Default 2 h. */
    val visitTimeBucketMs: Long = 2 * 60 * 60 * 1000L,
    /** Grid (E7) for place identity — ~55 m, so cross-source captures of one place fold. */
    val placeGridE7: Long = 5_000L,
    /** Canonical election order (highest priority first): live capture > Google import > file import. */
    val sourcePriority: List<String> = MomentSourceTag.PRIORITY,
)
