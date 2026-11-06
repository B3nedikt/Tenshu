package app.tenshu.photos.model

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A place the user has been, as Photos needs it — the fields of a `Place` row that matter here. */
data class PhotoPlace(
    val placeId: String,
    /** The place's name. ⚠ Never empty here: an unnamed place is dropped before this — see [nearestPlace]. */
    val label: String,
    val city: String,
    val latE7: Long,
    val lngE7: Long,
) {
    /**
     * How the viewer's subtitle names it — the city, else the place's own label. ⚠ One rule for both
     * shells (PIO-3): Compose's bar and the iOS toolbar used to be free to disagree.
     */
    val displayName: String get() = city.ifBlank { label }
}

/**
 * Build a [PhotoPlace] from field values that may be absent, or `null` when the row cannot support
 * one.
 *
 * ⚠⚠ **Every argument is nullable because `Place` rows are written by a different app.** Timeline
 * populates `:place-service`; Photos only reads it. A row is therefore whatever the Timeline that
 * wrote it knew how to write, and a field this build expects can simply not be there — which the
 * bare `Entity.getFieldValue(...).get()` idiom turns into a `NullPointerException` on the JVM and,
 * on Kotlin/Native, a **`SIGSEGV` that no `catch` can intercept** (`OptionalFieldValue`'s KDoc,
 * measured 2026-08-26). Photos has iOS targets, so that is not hypothetical here.
 *
 * ⚠ The per-field judgement, which is the point of doing this rather than catching:
 * - **[placeId], [latE7], [lngE7]** — required. Without them the row cannot be identified or
 *   placed, so it is dropped.
 * - **[label]** — required *and* must be non-blank, the rule this file already applied: a place is
 *   unnamed until something geocodes it, and "Unnamed place" is not a section a user can act on.
 * - **[city]** — genuinely optional. A place with no city is a place; it becomes `""`.
 */
fun photoPlaceOrNull(
    placeId: String?,
    label: String?,
    city: String?,
    latE7: Long?,
    lngE7: Long?,
): PhotoPlace? {
    if (placeId.isNullOrBlank()) return null
    if (label.isNullOrBlank()) return null
    if (latE7 == null || lngE7 == null) return null
    return PhotoPlace(
        placeId = placeId,
        label = label,
        city = city ?: "",
        latE7 = latE7,
        lngE7 = lngE7,
    )
}

/** A place and the photos taken there — one row of the Places section (`photos-app.md` S4/S6). */
data class PlaceGroup(
    val place: PhotoPlace,
    val photos: List<Photo>,
)

/**
 * Great-circle distance in metres between two E7 coordinates.
 *
 * ⚠ The haversine, not a flat approximation: a degree of longitude is not a fixed distance, which
 * is the same reason `PhotosNear` exists as an operation rather than as a chain predicate. At the
 * radii this is used with (hundreds of metres) the difference is small, but the flat version is
 * wrong in a way that varies with latitude — a bug that only reproduces far from the equator is
 * exactly the kind this codebase should not ship.
 */
fun distanceMetresE7(latE7A: Long, lngE7A: Long, latE7B: Long, lngE7B: Long): Double {
    val earthRadiusM = 6_371_000.0
    val toRad = PI / 180.0
    val latA = latE7A / 1e7 * toRad
    val latB = latE7B / 1e7 * toRad
    val dLat = latB - latA
    val dLng = (lngE7B - lngE7A) / 1e7 * toRad
    val h = sin(dLat / 2).let { it * it } +
        cos(latA) * cos(latB) * sin(dLng / 2).let { it * it }
    return 2 * earthRadiusM * asin(min(1.0, sqrt(h)))
}

/**
 * Group photos by the **nearest named place within [radiusM]**, in one pass.
 *
 * ⚠⚠ **One pass over photos already read, not one `photosNear` per place.** `photosNear` is an
 * O(files-in-realm) scan by its own KDoc, so calling it once per place to build a *list* of places
 * would walk the realm N times. The feed has already read every photo's coordinate; assigning each
 * to a place is then arithmetic, and arithmetic belongs in this Compose-free module where it can be
 * tested without a store.
 *
 * ⚠ A photo is assigned to its **nearest** place, not to every place in range: two cafés fifty
 * metres apart would otherwise both claim the same photograph, and a user reading either would
 * find a picture they did not take there.
 *
 * ⚠ Places with no photos are **dropped**, and so are photos with no coordinate or no place in
 * range. A Places section listing every place the user has ever been, most of them empty, is a
 * worse answer than a short one.
 *
 * ⚠ An **unnamed** place is not a group. `Place.label` is empty until something geocodes it, and
 * "Unnamed place (14)" tells the user nothing they can act on — see `photos-app.md` §13.2 for why
 * Photos cannot geocode one itself yet.
 */
fun groupPhotosByPlace(
    photos: List<Photo>,
    places: List<PhotoPlace>,
    radiusM: Double = DEFAULT_PLACE_RADIUS_M,
): List<PlaceGroup> {
    val named = places.filter { it.label.isNotBlank() }
    if (named.isEmpty()) return emptyList()

    val byPlace = mutableMapOf<String, MutableList<Photo>>()
    for (photo in photos) {
        val latE7 = photo.latE7 ?: continue
        val lngE7 = photo.lngE7 ?: continue
        val nearest = named
            .map { it to distanceMetresE7(latE7, lngE7, it.latE7, it.lngE7) }
            .filter { (_, d) -> d <= radiusM }
            .minByOrNull { (_, d) -> d }
            ?.first
            ?: continue
        byPlace.getOrPut(nearest.placeId) { mutableListOf() } += photo
    }

    return named.mapNotNull { place ->
        byPlace[place.placeId]?.let { PlaceGroup(place, it) }
    }.sortedByDescending { it.photos.size }
}

/**
 * The **nearest named place** to one coordinate, or `null` when nothing named is within [radiusM].
 *
 * ⚠⚠ The single-photo twin of [groupPhotosByPlace], and it must keep the same three refusals or the
 * viewer's subtitle and the Places section will disagree about the same photograph: nearest rather
 * than any-in-range, **named** places only, and `null` rather than a nearest-at-any-distance.
 *
 * ⚠ `null` is the common answer and not a failure. Most photos carry no coordinate at all
 * (`Photo.latE7`'s KDoc), and a coordinate somewhere the user never dwelled belongs to no `Place`
 * even so — `photos-app.md` §13.2. The caller omits the line; it never invents one.
 */
fun nearestPlace(
    latE7: Long?,
    lngE7: Long?,
    places: List<PhotoPlace>,
    radiusM: Double = DEFAULT_PLACE_RADIUS_M,
): PhotoPlace? {
    if (latE7 == null || lngE7 == null) return null
    return places
        .filter { it.label.isNotBlank() }
        .map { it to distanceMetresE7(latE7, lngE7, it.latE7, it.lngE7) }
        .filter { (_, d) -> d <= radiusM }
        .minByOrNull { (_, d) -> d }
        ?.first
}

/**
 * How close a photo must be to count as taken at a place.
 *
 * ⚠ 250 m is a guess made on the safe side, and it is a *product* choice rather than a derived
 * one: too tight and a photo taken across the square from a café is homeless, too loose and a city
 * block collapses into one place. It wants a real device library to tune against, which nobody has
 * pointed at this yet.
 */
const val DEFAULT_PLACE_RADIUS_M = 250.0
