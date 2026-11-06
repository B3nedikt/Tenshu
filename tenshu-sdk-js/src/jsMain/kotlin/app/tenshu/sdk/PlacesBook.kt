@file:OptIn(ExperimentalJsExport::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.okResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.place.LabelSource
import org.tenshu.core.place.PlaceMerge
import org.tenshu.core.place.PlaceRoles

/**
 * The read model behind [Maps] (`web-app-suite.md` Wave 3, Maps run A) — the native
 * `PlaceProjectionRepository`'s surface over **Timeline's** place reads, not a second reader of them.
 *
 * ## What is shared with `TimelineBook.kt`, and why
 *
 *  - **The relation reads** — `placesRead` / `mergesRead` / `rolesRead`, with `PLACE_FIELDS` and the
 *    `placeRow` mapper. One select per relation, so a rename Timeline's read sees is the rename this
 *    read sees.
 *  - **The join** — `timelineFlowOf` over [TimelineSources] with the three timeline-service sources
 *    stubbed empty. That buys the semantics Timeline's review rounds settled, unchanged: a failed
 *    relation is `{ ok: false }` (never an empty list), and a relation that never answers is
 *    `error_timeline_source_silent` (never a hang).
 *  - **Merge folding** — `TimelineSnapshot.places` (`rootsOnly`) and `PlaceMerge.resolveRoot`.
 *    ⚠ Native Maps does **not** fold: it lists a merged place twice. The web follows Timeline here
 *    (one place, one record — `maps-app.md` D2); `maps-app.md` §Web scope 5c.
 *
 * ## What differs from Timeline's `places()`, on purpose
 *
 *  - **No moments are read.** Native Maps has no visit history (`visitCount = 0`), so the list does
 *    not pay for the moment spine, and a moment write does not re-emit it. Visit stats are §Web
 *    scope 5b's question; `timeline.place` already answers them.
 *  - **Only a confirmed role** reaches a row, as native `confirmedRoles()` — a suggestion never
 *    changes how a place is drawn. Asking the question is Timeline's (`setPlaceRole`).
 *  - **Degrees**, not E7: native Maps hands its renderer a `GeoPoint`.
 *
 * ⚠⚠ Saved state, notes and saved lists have **no store** (`maps-app.md` §Web scope 5a): there is
 * no field to read and no method here to write them.
 */
internal fun TenshuClient.mapsPlacesFlow(): Flow<TimelineRead> =
    timelineFlowOf(
        TimelineSources(
            moments = flowOf(Read.Ok(emptyList())),
            places = placesRead(),
            merges = mergesRead(),
            roles = rolesRead(),
            traces = flowOf(Read.Ok(emptyList())),
            trips = flowOf(Read.Ok(emptyList())),
        ),
    )

internal fun mapsPlacesResult(read: TimelineRead): dynamic = when (read) {
    is TimelineRead.Ok -> okResult {
        val s = read.snapshot
        it.places = s.places.map { p -> mapsPlaceJs(p, s.roles[p.id]) }.toTypedArray()
    }
    else -> read.failure()
}

/** `{ ok: true, place }`, `place` **null** when no such place — an answer, not a failure. */
internal fun mapsPlaceResult(read: TimelineRead, placeId: String): dynamic = when (read) {
    is TimelineRead.Ok -> okResult {
        val s = read.snapshot
        val root = PlaceMerge.resolveRoot(placeId, s.merges)
        it.place = s.placeById[root]?.let { p ->
            MapsPlaceDetailJs(
                place = mapsPlaceJs(p, s.roles[root]),
                redirectedFrom = placeId.takeIf { root != placeId },
                mergedPlaceCount = s.merges.count { (id, target) -> target == root && id != root },
            )
        }
    }
    else -> read.failure()
}

internal fun mapsPlaceJs(p: PlaceRow, role: RoleRow?) = MapsPlaceJs(
    placeId = p.id,
    name = p.label,
    nameIsUsers = LabelSource.isUser(p.labelSource) && p.label.isNotBlank(),
    city = p.city,
    countryCode = p.countryCode,
    latitude = p.latE7 / E7,
    longitude = p.lngE7 / E7,
    // Only the two roles the d.ts names; a newer peer's role reads as none, never untyped.
    role = role?.takeIf { it.isConfirmed }?.role?.takeIf { it == PlaceRoles.HOME || it == PlaceRoles.WORK },
)

private const val E7 = 1e7

// ── JS shapes ────────────────────────────────────────────────────────────────

/**
 * One place as Maps draws it. `name` `""` is an **unnamed** place (no geocoder on web — the user's
 * rename is the naming route). `role` is a **confirmed** `home` / `work`, else null.
 */
@JsExport
class MapsPlaceJs internal constructor(
    val placeId: String,
    val name: String,
    val nameIsUsers: Boolean,
    val city: String,
    val countryCode: String,
    val latitude: Double,
    val longitude: Double,
    val role: String?,
)

/**
 * The single-pin detail (D1). `redirectedFrom` is the id asked for when it was folded into
 * `place` (a stale link), else null; `mergedPlaceCount` counts the places folded directly into it.
 */
@JsExport
class MapsPlaceDetailJs internal constructor(
    val place: MapsPlaceJs,
    val redirectedFrom: String?,
    val mergedPlaceCount: Int,
)

/**
 * The id a Maps rename writes: a folded id follows its merge to the survivor, so renaming from a stale
 * link renames the place the detail shows. Unknown merges leave the id as given.
 */
internal fun mapsRenameTarget(read: TimelineRead, placeId: String): String = when (read) {
    is TimelineRead.Ok -> PlaceMerge.resolveRoot(placeId, read.snapshot.merges)
    else -> placeId
}
