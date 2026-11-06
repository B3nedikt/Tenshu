package app.tenshu.timeline.data

import org.tenshu.core.place.PlaceGrid
import org.tenshu.core.place.PlaceProximity
import org.tenshu.core.timeline.SubjectKind

/**
 * **Place identity — snap a freshly-minted place onto the place we already know**
 * (`docs/proposals/timeline-recognition-and-place-naming.md` §8.4 **F1**).
 *
 * A captured place's id is minted by snapping its centroid to a ~74 × 111 m grid
 * (`VisitDetector.placeIdFor`). A *fixed* grid makes identity depend on which cell the dwell's
 * centroid happened to fall in, so two visits to one venue — metres apart, but across a cell
 * boundary — become two permanent `Place` rows with separate names and separate visit counts. That
 * is exactly what the device showed: the office twice, 74 m apart, and home three
 * times (§8.2).
 *
 * This step demotes the grid from an *identity* to a **minting scheme for genuinely new places**:
 *
 * > When an incoming place's id is **not already in the store**, and an existing place lies within
 * > the snap radius, adopt the existing id instead of the minted one.
 *
 * ⚠ **An id that already exists in the store is never remapped.** That rule is load-bearing: it
 * preserves every identity decision made upstream — most importantly the Wi-Fi recognition of §4.5,
 * whose resolved id is by construction already stored — and leaves an ordinary repeat visit alone.
 *
 * **Snap radius is accuracy-scaled** rather than a bare constant — see [PlaceProximity], which owns
 * the rule so this resolver and the proxy-side `PlaceReconciler` cannot drift apart (§10.3). ⚠
 * Accepted cost (§8.4): two genuinely distinct venues within ~60 m captured at poor accuracy will
 * merge — no information is lost that the coordinate ever had, and network identity (§4.5) plus
 * rename are the tools that *can* tell them apart.
 *
 * Runs in [TimelineImportWriter], the single write path every source converges on, so Android
 * capture, iOS `CLVisit`, and the Google-Timeline importer all inherit it. Pure `commonMain`.
 */
internal object PlaceIdentityResolver {

    /** A stored place, as far as identity resolution is concerned. */
    data class KnownPlace(
        val placeId: String,
        val latE7: Long,
        val lngE7: Long,
        val accuracyM: Int = 0,
    )

    /**
     * [imported] with every new-and-nearby place folded onto its [known] neighbour — places, the
     * moments referencing them, and those moments' content-addressed ids all remapped together, so
     * a re-capture of the same visit still derives the same `momentId` and stays idempotent.
     *
     * Returns [imported] unchanged when nothing folds (the common case), so the ordinary write path
     * pays only the distance checks.
     */
    fun resolve(imported: ImportedTimeline, known: Collection<KnownPlace>): ImportedTimeline {
        if (imported.places.isEmpty() || known.isEmpty()) return imported
        val storedIds = known.mapTo(HashSet()) { it.placeId }

        val remap = HashMap<String, String>()
        imported.places.forEach { p ->
            if (p.placeId in storedIds) return@forEach // an id we already know — decided upstream
            nearestWithinRadius(p, known)?.let { target -> remap[p.placeId] = target.placeId }
        }
        if (remap.isEmpty()) return imported

        // Fold the remapped places away; a survivor already in `known` needs no re-write, and one
        // that does survive keeps its own row. Duplicates collapse by id, first-wins.
        val places = imported.places
            .filter { it.placeId !in remap }
            .distinctBy { it.placeId }
        val moments = imported.moments.map { m ->
            if (m.subjectKind != SubjectKind.PLACE) return@map m
            val target = remap[m.subjectId] ?: return@map m
            m.copy(subjectId = target, momentId = remapMomentId(m.momentId, m.subjectId, target))
        }
        return imported.copy(places = places, moments = moments)
    }

    /**
     * The nearest stored place within the accuracy-scaled radius, or `null`. Ties break on the
     * lowest id so two devices resolving the same batch agree.
     */
    private fun nearestWithinRadius(p: ImportedPlace, known: Collection<KnownPlace>): KnownPlace? =
        known
            .map { it to PlaceProximity.distanceMeters(p.latE7, p.lngE7, it.latE7, it.lngE7) }
            .filter { (k, d) ->
                // The same two-part predicate the proxy pass uses (§10.10): the accuracy-scaled
                // radius, OR touching grid cells — because the ids being compared were minted by
                // that grid, and no metre radius spans a latitude step of it. Keeping the two layers
                // on one predicate is what stops the pass merging rows this resolver just created.
                d <= PlaceProximity.radiusFor(p.accuracyM, k.accuracyM) ||
                    PlaceGrid.areAdjacent(p.placeId, k.placeId)
            }
            .minWithOrNull(compareBy({ it.second }, { it.first.placeId }))
            ?.first

    /**
     * A captured visit's `momentId` embeds its placeId (`cap-visit:<placeId>:<arrivalMs>`), so a
     * remap has to rewrite it too — otherwise the id would still address the abandoned place and a
     * later re-capture of the same visit (which now resolves to the target) would mint a *second*
     * moment for it. Ids of any other shape are left alone.
     */
    private fun remapMomentId(momentId: String, from: String, to: String): String {
        val prefix = "cap-visit:$from:"
        return if (momentId.startsWith(prefix)) "cap-visit:$to:" + momentId.removePrefix(prefix) else momentId
    }
}
