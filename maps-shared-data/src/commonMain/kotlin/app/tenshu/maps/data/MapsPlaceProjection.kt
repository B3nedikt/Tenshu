package app.tenshu.maps.data

import app.tenshu.maps.model.Coord
import app.tenshu.maps.model.GeoPoint
import app.tenshu.maps.model.Place
import app.tenshu.maps.model.PlaceCategory
import app.tenshu.maps.model.PlaceSection

/** The thin place fields the `:place-service` store actually carries (`Place` schema). */
data class PlaceInput(
    val id: String,
    val label: String,
    val latE7: Long,
    val lngE7: Long,
    /**
     * A **confirmed** `PlaceRole` token (`home` / `work`), or `""` (`place-roles.md` PR-4). Only
     * confirmed roles reach here — a suggestion is the machine's guess and must not change how a
     * place is drawn before the user has answered.
     */
    val role: String = "",
)

/**
 * Projects a stored `:place-service` place → the Maps UI [Place] (Ph9, `maps-app.md` §6).
 *
 * **Honest thin-screens tradeoff (as Timeline/Fit made — `ReferenceTimelineSource` "spine-only…
 * thinner than the hand-built mock"):** the store carries only `{placeId, latE7, lngE7, capturedAt,
 * label}`, so `category` / `section` / `saved` / `photos` / `reason` / `note` / visit history have
 * **no home** and project to honest defaults/empties. The consequence is deliberate and documented:
 * all real places land under **Yours** (the Saved / For-you tabs are empty), and place detail loses
 * photos + visit history — until `:place-service` graduates those fields (or a Timeline-`Visit`
 * moment join lands). What this *does* light up: the **real per-place coordinate** ([location]),
 * so every snippet centres on the actual place, not a mock projection.
 */
object MapsPlaceProjection {

    private const val E7 = 1e7

    // ⚠ Duplicated rather than imported: `:maps-shared-data` deliberately does not depend on
    // Timeline's model, and `:place-service`'s constants are wire strings. Two consumers agreeing on
    // a string is the same posture `MovementModes` takes.
    private const val ROLE_HOME = "home"
    private const val ROLE_WORK = "work"

    fun place(input: PlaceInput): Place = Place(
        id = input.id,
        name = input.label,
        // PR-4: the first real category the store can actually answer for. Everything else still
        // has no home and keeps its honest default — a place with no role is not "a shop", it is
        // "we do not know", and `Store` remains the stand-in for that.
        // ⚠ Category only, not `label`: the chip text is user-facing and would have to be
        // localised, and this module is string-free by construction.
        category = when (input.role) {
            ROLE_HOME -> PlaceCategory.Home
            ROLE_WORK -> PlaceCategory.Work
            else -> PlaceCategory.Store
        },
        section = PlaceSection.Yours, // Saved / Recommended have no store → those tabs stay empty
        label = null,
        address = "",
        coord = Coord(0.5, 0.5), // schematic-only; the live map uses `location`
        location = GeoPoint(latitude = input.latE7 / E7, longitude = input.lngE7 / E7),
        seed = stableSeed(input.id),
        saved = false,
        savedList = null,
        visitCount = 0,
        // lastVisit / firstVisit / recentVisits / photos / reason / note: honest empties.
    )

    fun places(inputs: List<PlaceInput>): List<Place> = inputs.map { place(it) }

    /** A stable schematic seed from the id (deterministic across runs — `String.hashCode` is). */
    private fun stableSeed(id: String): Int = (id.hashCode() and 0x7fffffff) % 1000
}
