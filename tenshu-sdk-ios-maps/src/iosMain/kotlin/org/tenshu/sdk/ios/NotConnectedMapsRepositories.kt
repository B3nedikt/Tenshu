package org.tenshu.sdk.ios

import app.tenshu.maps.data.PlaceRepository
import app.tenshu.maps.data.RegionRepository
import app.tenshu.maps.data.RegionsRead
import app.tenshu.maps.model.Place
import app.tenshu.maps.model.RegionItem
import app.tenshu.maps.model.SavedList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * The iOS **"not connected to Control"** Maps place repository — the exact twin of
 * [NotConnectedTimelineRepository] / [NotConnectedFitRepository], and for the same reason
 * (`ios-fit-timeline-maps-parity.md` D4).
 *
 * Maps holds no data of its own: every place comes from `:place-service`, which lives behind the
 * route-1 in-process proxy over Control's shared replica. Until Control has provisioned the key
 * material there is genuinely nothing to show, and [app.tenshu.maps.data.MockPlaceRepository] would
 * show invented cafés and viewpoints with nothing marking them as fake.
 *
 * Every read is empty and [controlReachable] is `false`, which drives the shell's "Can't reach
 * Tenshu Control" banner (the same signal `:maps-shared-ui`'s `App.kt` renders off).
 */
class NotConnectedPlaceRepository : PlaceRepository {

    override fun controlReachable(): StateFlow<Boolean?> = MutableStateFlow(false)

    override fun places(): Flow<List<Place>> = flowOf(emptyList())

    override fun savedLists(): Flow<List<SavedList>> = flowOf(emptyList())

    override suspend fun place(id: String): Place? = null

    /**
     * A no-op rather than a throw. With every read empty the bookmark toggle is unreachable by
     * construction (it hangs off a place row), so there is no user-visible action to fail loudly —
     * and the real repository's `setSaved` is a documented no-op too, because `:place-service` has
     * no `saved` field.
     */
    override suspend fun setSaved(id: String, saved: Boolean) = Unit
}

/**
 * The region-picker twin of [NotConnectedPlaceRepository]: an empty catalog rather than a fabricated
 * one.
 *
 * The catalog is served by `MapRegionService`, which on route 1 is hosted by the app's *own*
 * in-process proxy — so without a runtime there is no catalog to read. Every interface default is
 * already the honest answer (no stream, no offer, no progress); only the three that would otherwise
 * be abstract are spelled out.
 *
 * ⚠⚠ [regions] answers `Unavailable`, **not an empty catalogue**: an empty list makes the picker
 * state there are no maps to download — the exact shape `ios-control-notifications-and-shares.md`
 * bans: *not-connected is a failure, never an empty success*.
 */
class NotConnectedRegionRepository : RegionRepository {

    override suspend fun regions(): RegionsRead =
        RegionsRead.Unavailable("no runtime on this device, so no catalogue to read")

    override suspend fun download(regionId: String): Boolean = false

    override suspend fun delete(regionId: String) = Unit
}

/**
 * A deterministic region catalog for **previews and the render probes only** — the region-side twin
 * of [app.tenshu.maps.data.MockPlaceRepository], which `:maps-shared-logic` carries for the
 * place side. It exists for the same reason and under the same rule: it is reachable **only** from
 * [MapsSurface.installMock], never from the runtime default and never from a teardown.
 *
 * The rows mirror the real catalog's shape — a bundled city extract plus larger regional bundles —
 * so the picker's three states (available / in-flight / resident) can all be seen. Sizes are the
 * honest order of magnitude for a Planetiler extract, because the size is the whole point of that
 * screen: the map already works without a download.
 *
 * ⚠ [download] flips the row to installed **in memory only**. It is a mock.
 */
class MockRegionRepository : RegionRepository {

    private val installedIds = mutableSetOf("firenze")

    private val catalog = listOf(
        Triple("firenze", "Firenze", 78_000_000L),
        Triple("toscana", "Toscana", 640_000_000L),
        Triple("italia-nord", "Northern Italy", 2_100_000_000L),
    )

    override suspend fun regions(): RegionsRead = RegionsRead.Regions(
        catalog.map { (id, name, size) ->
            RegionItem(regionId = id, name = name, byteSize = size, installed = id in installedIds)
        }
    )

    override suspend fun download(regionId: String): Boolean {
        installedIds += regionId
        return true
    }

    override suspend fun delete(regionId: String) {
        installedIds -= regionId
    }
}
