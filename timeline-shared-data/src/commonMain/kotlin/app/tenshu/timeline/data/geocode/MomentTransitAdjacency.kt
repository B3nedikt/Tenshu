package app.tenshu.timeline.data.geocode

import app.tenshu.timeline.data.geocode.TransitAdjacency.Leg
import kotlinx.coroutines.flow.first
import org.tenshu.core.TString
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.entity.Entity
import org.tenshu.core.fold
import org.tenshu.core.number.TInteger
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.MovementTrace
import org.tenshu.core.timeline.SubjectRef
import org.tenshu.timeline.client.timelineService

/**
 * Reads the moment stream once and answers, per place, whether its dwells sat inside a transit
 * journey (§31). Feeds [PlaceLabelEnricher]'s stop-radius decision.
 *
 * ⚠ **One read per sweep, not per row.** The enricher asks for the whole map before its loop and only
 * when a coarse candidate exists, so an ordinary sweep over precise dwells costs nothing extra.
 *
 * ⚠ A place with several dwells takes the **strongest** verdict across them: `TRANSIT` if any dwell
 * was bracketed by transit, else `NOT_TRANSIT` only if every dwell said so, else `UNKNOWN`. A station
 * is a station even if you also once walked there — and one confidently-walked visit is not evidence
 * about the others.
 */
class MomentTransitAdjacency(private val client: TenshuClient) {

    private data class Row(val kind: String, val startMs: Long, val endMs: Long, val subjectId: String)

    suspend fun verdicts(): Map<String, TransitAdjacency.Verdict> {
        val moments = readMoments().sortedBy { it.startMs }
        if (moments.isEmpty()) return emptyMap()
        val legs = readLegs()

        val out = mutableMapOf<String, TransitAdjacency.Verdict>()
        moments.forEachIndexed { i, m ->
            if (m.kind != VISIT || m.subjectId.isEmpty()) return@forEachIndexed
            val verdict = TransitAdjacency.verdict(
                inbound = legs[nearestMovementSubject(moments, i, step = -1)],
                outbound = legs[nearestMovementSubject(moments, i, step = +1)],
            )
            out[m.subjectId] = strongest(out[m.subjectId], verdict)
        }
        return out
    }

    /**
     * The trace id of the movement adjacent to `moments[from]` in direction [step], or `null`.
     *
     * ⚠ Stops at the next **visit**: a movement on the far side of another stay is not this dwell's
     * bracket, and treating it as one would import an unrelated journey's verdict.
     */
    private fun nearestMovementSubject(moments: List<Row>, from: Int, step: Int): String? {
        var i = from + step
        while (i in moments.indices) {
            val r = moments[i]
            if (r.kind == VISIT) return null
            if (r.kind == MOVEMENT) return r.subjectId.takeIf { it.isNotEmpty() }
            i += step
        }
        return null
    }

    /** `TRANSIT` beats `UNKNOWN` beats `NOT_TRANSIT` — see the class KDoc. */
    private fun strongest(
        a: TransitAdjacency.Verdict?,
        b: TransitAdjacency.Verdict,
    ): TransitAdjacency.Verdict = when {
        a == null -> b
        a == TransitAdjacency.Verdict.TRANSIT || b == TransitAdjacency.Verdict.TRANSIT ->
            TransitAdjacency.Verdict.TRANSIT
        a == TransitAdjacency.Verdict.UNKNOWN || b == TransitAdjacency.Verdict.UNKNOWN ->
            TransitAdjacency.Verdict.UNKNOWN
        else -> TransitAdjacency.Verdict.NOT_TRANSIT
    }

    private suspend fun readMoments(): List<Row> =
        client.timelineService.moments.select { fields(*MOMENT_FIELDS) }.flow().first()
            .fold(onSuccess = { it.toList().map(::momentRow) }, onFailure = { emptyList() })
            // ⚠ A superseded moment is not on the timeline, so it must not bracket anything either.
            .filterNotNull()

    private fun momentRow(e: Entity<out Moment>): Row? {
        if (e.getFieldValue<TString>(Moment.Fields.SUPERSEDED_BY).get().isNotEmpty()) return null
        val start = e.getFieldValue<UnixInstant>(Moment.Fields.TIMESTAMP).toEpochMilliseconds()
        return Row(
            kind = e.getFieldValue<TString>(Moment.Fields.KIND).get(),
            startMs = start,
            endMs = start + e.getFieldValue<TInteger>(Moment.Fields.DURATION_SEC).get() * 1000,
            subjectId = e.getFieldValue<SubjectRef>(Moment.Fields.SUBJECT).id.get(),
        )
    }

    private suspend fun readLegs(): Map<String, Leg> =
        client.timelineService.traces.select { fields(*TRACE_FIELDS) }.flow().first()
            .fold(
                onSuccess = { rows ->
                    rows.toList().associate { e ->
                        e.getFieldValue<TString>(MovementTrace.Fields.TRACE_ID).get() to Leg(
                            mode = e.getFieldValue<TString>(MovementTrace.Fields.MODE).get(),
                            confidence = e.getFieldValue<TInteger>(MovementTrace.Fields.MODE_CONFIDENCE)
                                .get().toInt(),
                        )
                    }
                },
                onFailure = { emptyMap() },
            )

    private companion object {
        const val VISIT = "Visit"
        const val MOVEMENT = "Movement"

        /** ⚠ Narrow on purpose: adjacency needs order, kind and the subject — never the polyline. */
        val MOMENT_FIELDS = arrayOf(
            Moment.Fields.MOMENT_ID, Moment.Fields.TIMESTAMP, Moment.Fields.KIND,
            Moment.Fields.SUBJECT, Moment.Fields.DURATION_SEC, Moment.Fields.SUPERSEDED_BY,
        )
        val TRACE_FIELDS = arrayOf(
            MovementTrace.Fields.TRACE_ID, MovementTrace.Fields.MODE,
            MovementTrace.Fields.MODE_CONFIDENCE,
        )
    }
}
