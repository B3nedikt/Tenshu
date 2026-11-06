package app.tenshu.maps.model

import kotlinx.datetime.LocalDate

/**
 * Maps — Places (slim) domain model — Ph1 of docs/proposals/maps-app.md (§6), ported from
 * the design handoff's `maps-agent.md` §5 / `mockup/maps-data.js`.
 *
 * This is *structure*, not a wire format — the real on-device store is `:place-service`
 * ([`timeline-services.md`](../../../../../../../docs/proposals/timeline-services.md) §3,
 * graduated to full strength), which replaces the mock behind `PlaceRepository` (Ph2 seam,
 * Ph9 swap). Kept aligned to that shape so the swap is mechanical. Deliberate deviations
 * from the mock, mirroring `:timeline-shared-logic`:
 *
 *  - **No user-facing strings are composed here.** Counts ride as raw `Int`; the "last
 *    visit 3 days ago" phrasing is a *locale-independent split* in [MapsFormat] that the
 *    UI localises (`:maps-shared-ui`, ADR 0016). This module stays Compose-free.
 *  - **Dates are typed** `LocalDate` (the mock's ISO strings parse at the Ph2 mock seam),
 *    not free-text.
 *  - **A Place holds no route geometry (D1).** A Place is a *point*; visit moments and
 *    trip paths live in Timeline and are referenced by link, never embedded here.
 *  - **`coord` is the mock's normalized basemap position** (`mx`/`my` in 0..1, for placing
 *    markers on the schematic `MapSnippet`). The real `:place-service` store carries SI
 *    lat/lng (`latE7`/`lngE7`); Ph9 projects those into this for the slim schematic map.
 */

/** POI type. Drives the category avatar/glyph and the optional by-category accent (§4). */
enum class PlaceCategory { Home, Work, Cafe, Restaurant, Park, Store, Culture, Transit, Viewpoint }

/** Which Places tab a place belongs to, and the provenance it models (§0.5). */
enum class PlaceSection { Yours, Saved, Recommended }

/** Why a `Recommended` place was proposed — always rendered on the card (§0.5). */
enum class ReasonKind { Timeline, Contact, Curated }

/**
 * Normalized position on the schematic all-places basemap (0..1 on each axis). Mock-only:
 * the real store keys off SI lat/lng — see the class KDoc.
 */
data class Coord(val mx: Double, val my: Double)

/**
 * A geographic point in SI degrees (WGS84) — the real per-place coordinate the live basemap
 * centres on (`docs/proposals/maps-app.md` §6 Ph9). Distinct from [Coord] (the schematic-map
 * placement in 0..1): this is the store's actual location.
 *
 * The `:place-service` store carries the coordinate as E7 integers (degrees × 1e7 — its DSL has
 * no float kind; see `org.tenshu.core.place.Place`). Convert at the edge: `latE7 / 1e7`. The Ph9
 * swap fills this from `latE7`/`lngE7`; the mock projects its normalized [Coord] into a plausible
 * region (`MapsMockData`), so the render seam (`center`) is exercised end to end before real data
 * lands.
 *
 * ⚠ [ObjCName] for the same reason as [Place]: `app.tenshu.timeline.model.GeoPoint` shares the
 * umbrella, and the newcomer must not take the plain Swift name off a shipped shell.
 */
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("MapsGeoPoint")
data class GeoPoint(val latitude: Double, val longitude: Double)

/** A photo placeholder — dominant tone only, never photoreal (Timeline `PhotoFrame` precedent). */
data class PlacePhoto(val tone: String)

/** The provenance chip on a recommended place. */
data class Reason(val kind: ReasonKind, val text: String)

/**
 * A canonical place (POI). One record; Timeline & Photos embed its detail view (D2).
 *
 * ⚠ [ObjCName]: `app.tenshu.timeline.model.Place` is on the **same** `TenshuSdk` umbrella, and
 * Kotlin/Native disambiguates a clash by appending `_` to whichever fq name sorts later — so
 * exporting `:maps-shared-logic` would silently have renamed *Timeline's* `Place` to `Place_` and
 * broken the already-shipped `timeline-ios` Swift. The newcomer takes the qualified name; see
 * [app.tenshu.maps.model.GeoPoint] and the two `PlaceDetail*` types for the same treatment.
 * Kotlin-side (Compose, tests) is untouched — the annotation only affects the Obj-C/Swift export.
 */
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("MapsPlace")
data class Place(
    val id: String,
    val name: String,
    val category: PlaceCategory,
    val section: PlaceSection,
    /** Only for [PlaceSection.Yours] — "Home" / "Work" / a custom label. */
    val label: String? = null,
    val address: String,
    val coord: Coord,
    /**
     * The real SI coordinate the live basemap centres on (`center` in `:ui-map`). Null when the
     * store has no fix yet (a place saved but never located). The mock derives it from [coord];
     * Ph9 fills it from `:place-service`'s `latE7`/`lngE7`.
     */
    val location: GeoPoint? = null,
    /** Deterministic street-layout seed for the detail snippet (schematic map). */
    val seed: Int,
    val saved: Boolean,
    /** The Saved list this place is filed under, when saved. */
    val savedList: String? = null,
    val visitCount: Int,
    /** Null when never visited (e.g. saved-from-a-friend, not been yet). */
    val lastVisit: LocalDate? = null,
    val firstVisit: LocalDate? = null,
    /** Most-recent visit dates for the detail's visit-history strip (newest first). */
    val recentVisits: List<LocalDate> = emptyList(),
    val photos: List<PlacePhoto> = emptyList(),
    /** Present only on [PlaceSection.Recommended] places. */
    val reason: Reason? = null,
    val note: String? = null,
)

/** The organizing layer for the Saved tab — a named bucket of saved places. */
data class SavedList(
    val id: String,
    val name: String,
    val count: Int,
)
