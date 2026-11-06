package app.tenshu.timeline.data

import app.tenshu.timeline.data.projection.TimelineProjection
import app.tenshu.timeline.data.projection.TimelineProjection.MomentInput
import app.tenshu.timeline.data.projection.TimelineProjection.PlaceInput
import org.tenshu.core.place.PlaceMerge

/**
 * The **read half of place merging** (`docs/proposals/timeline-recognition-and-place-naming.md`
 * §10.11, P4f): make the `PlaceMerge` rows the proxy writes actually visible.
 *
 * P4d shipped the merge plane and P4e the pass that fills it — but nothing read the result, so the
 * Places tab still rendered one row per stored `Place` and the on-device pass looked like it had done
 * nothing. This is that missing reader.
 *
 * Two operations, applied at the flow boundary so **every** consumer inherits them without a call-site
 * change (the feed, the Places tab, place detail, trips, the suggester):
 *
 *  - [rootsOnly] drops the folded-away places, so a venue is one row;
 *  - [repointToRoots] rewrites each `Visit` moment's subject to the surviving place, so the folded
 *    row's visits count towards — and are labelled by — the row that survives.
 *
 * Deliberately **not** rewriting anything in the store: merging is reversible precisely because the
 * moments still point where they always did (§9.4). The fold lives entirely in the read model.
 */
internal object PlaceMergeFolding {

    /** [places] minus every row that has been folded into another (`mergedInto` non-blank). */
    fun rootsOnly(places: List<PlaceInput>, merges: Map<String, String>): List<PlaceInput> {
        if (merges.isEmpty()) return places
        return places.filter { PlaceMerge.resolveRoot(it.id, merges) == it.id }
    }

    /**
     * [moments] with every `Visit` subject resolved to its surviving place. A moment pointing at a
     * place that has been merged away would otherwise resolve to nothing — no label, no coordinates,
     * and no contribution to the surviving place's visit history.
     */
    fun repointToRoots(moments: List<MomentInput>, merges: Map<String, String>): List<MomentInput> {
        if (merges.isEmpty()) return moments
        return moments.map { m ->
            if (m.subjectKind != TimelineProjection.Subject.PLACE) return@map m
            val root = PlaceMerge.resolveRoot(m.subjectId, merges)
            if (root == m.subjectId) m else m.copy(subjectId = root)
        }
    }
}
