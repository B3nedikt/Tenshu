package org.tenshu.timeline.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.readOrderedWindow
import org.tenshu.core.common.relation.relationReference
import org.tenshu.core.entity.Entity
import org.tenshu.core.number.TInteger
import org.tenshu.core.place.PlaceClustering
import org.tenshu.core.place.PlaceCorroboration
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.SubjectKind
import org.tenshu.core.timeline.SubjectRef
import org.tenshu.core.timeline.TimelineService

/**
 * **Time as corroboration** (`docs/proposals/timeline-recognition-and-place-naming.md` §10.12) — the
 * evidence `PlaceReconciler` consults for the one bucket the labels cannot settle: both places named,
 * both names machine-guessed, the guesses disagreeing.
 *
 * The split it draws:
 *  - the two rows' visits **abut or overlap** ⇒ one stay that drifted across a cell boundary ⇒ merge
 *    (a pause beside the town hall on a walk to an ice cream shop — the over-merge that turned out to be *correct*);
 *  - each row has **its own visits, never abutting** ⇒ two venues that happen to be neighbours ⇒
 *    leave alone (the café and the bookshop next door).
 *
 * ⚠⚠ **This may only ever arbitrate that bucket** (§12.2). Abutting proves drift; *non*-abutting does
 * not prove distinctness — repeat visits to one venue are spread across days, and the reporting
 * device's two office rows carried 6 and 3 visits. A "must abut to merge" rule applied one step wider
 * would re-fragment exactly what this track repaired. The narrowing is enforced by the caller
 * ([PlaceClustering.mayMerge] hands over only [PlaceClustering.Verdict.NEEDS_CORROBORATION] pairs) and
 * by returning `null` — never `false` — whenever the window is silent.
 *
 * ⚠ **Lives here, not in `:place-service-proxy`.** The verdict needs `Moment` rows and place is the
 * lower layer — timeline depends on place, never the reverse. The wiring that hosts both hands this to
 * `initPlaceService`, the same shape `PlaceRoleInferencePass` already uses to read across the seam.
 *
 * **Bounded and demand-driven.** One [readOrderedWindow] over the most recent [windowSize] moments per
 * ambiguous pair — not per save, and never the whole relation (the unbounded read that blanked Home at
 * ~10.5k rows). An ambiguous pair is rare by construction: it needs two adjacent places, both named,
 * both guessed, disagreeing, and no shared network tag.
 */
class VisitTimeCorroboration(
    private val executionContext: ExecutionContext,
    /**
     * How many recent moments to consider. Larger than the reconciliation pass's 200 because this
     * looks for *two specific* places inside a mixed feed — a few hundred moments is a couple of
     * months of ordinary capture, which is the horizon on which "these two rows are the same venue"
     * is still a live question.
     */
    private val windowSize: Int = DEFAULT_WINDOW,
    /**
     * How close two visits must be to read as one interrupted stay (§12.7 D2). 30 minutes covers a
     * pause, a walk around the block and the gap the dwell detector leaves between a departure and the
     * next confirmation, while staying far below "came back later the same day".
     */
    private val abuttingGapMs: Long = DEFAULT_ABUTTING_GAP_MS,
) : PlaceCorroboration {

    override fun sameVenue(a: PlaceClustering.Candidate, b: PlaceClustering.Candidate): Boolean? {
        if (a.placeId == b.placeId) return null
        val visits = visitsFor(setOf(a.placeId, b.placeId))
        val first = visits[a.placeId].orEmpty()
        val second = visits[b.placeId].orEmpty()
        // ⚠⚠ null, not false. One side with no visits in the window is the absence of evidence, and
        // the caller turns that into today's behaviour (merge). Reporting `false` here would let a
        // short window quietly re-fragment a pair it simply could not see.
        if (first.isEmpty() || second.isEmpty()) return null
        return first.any { x -> second.any { y -> abuts(x, y) } }
    }

    private fun abuts(x: Interval, y: Interval): Boolean =
        x.startMs <= y.endMs + abuttingGapMs && y.startMs <= x.endMs + abuttingGapMs

    /**
     * `placeId → the visit intervals recorded there`, for the two ids in question, from one bounded
     * read. Failures resolve to "no visits" rather than propagating: this runs inside a save observer,
     * and a timeline-less or locked store must not fail a place write.
     */
    private fun visitsFor(placeIds: Set<String>): Map<String, List<Interval>> = runCatching {
        executionContext.readOrderedWindow(
            reference = executionContext.relationReference(TimelineService.Relations.MOMENTS),
            orderByFieldId = Moment.Fields.TIMESTAMP,
            limit = windowSize,
            descending = true,
            keepFields = setOf(Moment.Fields.SUBJECT, Moment.Fields.DURATION_SEC),
        ).mapNotNull { row ->
            val subject = subjectOf(row) ?: return@mapNotNull null
            if (subject.kind.get() != SubjectKind.PLACE) return@mapNotNull null
            val placeId = subject.id.get()
            if (placeId !in placeIds) return@mapNotNull null
            val start = timestampOf(row)?.toEpochMilliseconds() ?: return@mapNotNull null
            placeId to Interval(start, start + int(row, Moment.Fields.DURATION_SEC) * 1_000L)
        }
            .groupBy({ it.first }, { it.second })
    }.getOrElse { emptyMap() }

    private data class Interval(val startMs: Long, val endMs: Long)

    companion object {
        const val DEFAULT_WINDOW: Int = 500
        const val DEFAULT_ABUTTING_GAP_MS: Long = 30 * 60 * 1_000L

        private fun int(data: Data, fieldId: String): Long = when (data) {
            is Entity<*> -> data.getFieldValue<TInteger>(fieldId).get()
            is Composite -> data.getField<TInteger>(fieldId).get()
            else -> 0L
        }

        private fun timestampOf(data: Data): UnixInstant? = when (data) {
            is Entity<*> -> data.getFieldValue(Moment.Fields.TIMESTAMP)
            is Composite -> data.getField(Moment.Fields.TIMESTAMP)
            else -> null
        }

        private fun subjectOf(data: Data): SubjectRef? = when (data) {
            is Entity<*> -> data.getFieldValue(Moment.Fields.SUBJECT)
            is Composite -> data.getField(Moment.Fields.SUBJECT)
            else -> null
        }
    }
}
