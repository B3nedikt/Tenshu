package org.tenshu.sdk.ios

import app.tenshu.maps.data.MockPlaceRepository
import app.tenshu.maps.data.PlaceRepository
import app.tenshu.maps.data.RegionRepository
import app.tenshu.maps.ui.screens.detail.PlaceDetailViewModel
import app.tenshu.maps.ui.screens.places.PlacesViewModel
import app.tenshu.maps.ui.screens.regions.RegionPickerViewModel
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/**
 * Wires the **real** Maps view-model layer (`:maps-shared-logic`) to iOS so the SwiftUI `maps-ios`
 * app can resolve each screen's view model and observe its `StateFlow` through SKIE
 * (`ios-proxy-architecture.md` §9.3 "share view models + repos, native UI"). The Maps twin of
 * [TimelineSurface] / [FitSurface] (`ios-fit-timeline-maps-parity.md` §3.3, B-3).
 *
 * Maps' Koin DI lives in `:maps-shared-ui` (Compose, not on iOS), so this object is the minimal
 * hand-wiring the SwiftUI client needs instead of `mapsModules()`.
 *
 * There are **two** injected seams here rather than Timeline's one, because Maps' view models take
 * two: [PlaceRepository] (the POI store, `:place-service`) and [RegionRepository] (the offline
 * basemap catalog, `MapRegionService`). They install and tear down together — both are reads over
 * the same [org.tenshu.core.common.TenshuClient], so there is no state in which one is live and the
 * other is not.
 *
 * The runtime default is the honest not-connected pair ([NotConnectedPlaceRepository] /
 * [NotConnectedRegionRepository]): an unprovisioned app is *empty and says why* rather than full of
 * fabricated cafés (D4).
 * [MockPlaceRepository] survives for tests, previews and the render probes only ([installMock]).
 */
object MapsSurface {

    private var places: PlaceRepository = NotConnectedPlaceRepository()
    private var regions: RegionRepository = NotConnectedRegionRepository()

    /**
     * Install the repositories subsequently-built view models bind. Called by the tier that owns a
     * live client — [MapsLocalRuntime] (route 1).
     */
    fun installRepositories(placeRepository: PlaceRepository, regionRepository: RegionRepository) {
        places = placeRepository
        regions = regionRepository
    }

    /**
     * Restore the honest **not-connected** surface — the teardown / fallback target: empty reads plus
     * `controlReachable = false`, which is what drives the shell's banner.
     */
    fun installNotConnected() {
        places = NotConnectedPlaceRepository()
        regions = NotConnectedRegionRepository()
    }

    /**
     * Install the deterministic mock pair — [MockPlaceRepository] (from `:maps-shared-logic`, the
     * same one the Compose previews use) plus [MockRegionRepository]. **Tests, previews and the
     * render probes only**; it is not a runtime surface and no shipping path reaches it.
     */
    fun installMock() {
        places = MockPlaceRepository()
        regions = MockRegionRepository()
    }

    /**
     * Whether the installed repository can actually reach Control — `false` only on the
     * not-connected surface. The Swift shell renders its banner off this, mirroring the Compose
     * shell's `repository.controlReachable()` read (`:maps-shared-ui` `App.kt`).
     *
     * Read as a plain snapshot rather than the flow: the *repository instance itself* is what changes
     * when the runtime installs, and the shell already remounts on that edge.
     */
    fun controlReachable(): Boolean = places.controlReachable().value != false

    /** The currently-installed place repository — exposed for a smoke/test to assert against. */
    internal fun installedPlaceRepository(): PlaceRepository = places

    /** The currently-installed region repository — exposed for a smoke/test to assert against. */
    internal fun installedRegionRepository(): RegionRepository = regions

    // ── view models (one per screen, `:maps-shared-ui`'s `App.kt` nav graph) ──────────

    /** Places — the single top-level surface; its four tabs live *inside* one VM (maps-app.md D1). */
    fun placesViewModel(): PlacesViewModel = PlacesViewModel(places)

    /** Place detail — identity, visit history, and the offline-region offer for its coordinate. */
    fun placeViewModel(placeId: String): PlaceDetailViewModel =
        PlaceDetailViewModel(places, regions, placeId)

    /** The offline-region picker (`maps-region-delivery.md` D5 — Maps owns it). */
    fun regionPickerViewModel(): RegionPickerViewModel = RegionPickerViewModel(regions)

    // ── date helpers, Swift-facing ───────────────────────────────────────────────────

    /**
     * Today in the device's own zone — what the Compose composition root passes down as `today`.
     *
     * ⚠ The Compose shell sources `MapsMockData.today` (a fixed anchor that keeps the *mock's*
     * relative dates readable). iOS deliberately takes the real one: the shipping surface here is the
     * real `:place-service` store, and against real visit dates a frozen "today" would mis-date every
     * "3 days ago". The mock probe accepts the drift.
     */
    fun today(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())
}
