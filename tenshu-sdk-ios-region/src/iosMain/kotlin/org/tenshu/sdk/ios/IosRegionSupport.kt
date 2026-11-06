package org.tenshu.sdk.ios

import app.tenshu.mapregion.app.IosRegionBundleStager
import app.tenshu.mapregion.app.LatLng
import app.tenshu.mapregion.app.RegionBundleStager
import app.tenshu.mapregion.app.RegionTerminus
import app.tenshu.mapregion.app.iosMapRegionStagingDirectory
import org.tenshu.core.common.TenshuClient

/**
 * The iOS half of the offline-basemap **render hop** (`maps-region-delivery.md` D5/§7): builds the
 * shared [RegionTerminus] over an app's [TenshuClient] plus the Darwin [IosRegionBundleStager], and
 * adapts it to each app's own `RegionBundleResolver` seam.
 *
 * ⚠ Only the app-agnostic half lives here, in `:tenshu-sdk-ios-region` (`ios-per-app-frameworks.md`
 * §5.3): the stager and the terminus, for Fit, Timeline and Maps. Each app's resolver adapter is
 * that app's own code (`FitRegionResolver.kt`, `TimelineRegionResolver.kt`), so a framework that
 * links this module does not link another app's data layer.
 *
 * Why this exists here rather than being reused: the Compose-side `TerminusRegionBundleResolver`
 * twins live in `:timeline-shared-ui` / `:fit-shared-ui`, which iOS cannot depend on (Compose). The
 * adaptation itself is ~10 lines of delegation per app; single-sourcing it would mean relocating
 * those classes into `:timeline-shared-data` / `:fit-shared-data` and giving `:map-region-app` a
 * `js` target (`:fit-shared-data` has one). Worth doing, but it is a cross-platform build change,
 * not iOS wiring — deliberately left as a follow-up.
 *
 * **Reachability is real, not assumed.** Both iOS first-party apps run route 1
 * (`FirstPartyLocalRuntime`), whose graph is `buildProxyModule()` + the iosMain
 * `platformProxyModule` — and both already register `MapRegionService`: the schema modules ride
 * `coreSchemaConfigs` and the iOS `platformImplementationFactory` binds
 * `mapRegionServiceProxyImplementationFactory` over a real `MapRegionProxyComponents`. So the
 * catalogue, the range-stream and the download op are all in-process here; nothing new had to be
 * hosted. The same holds for the route-2 EPC tier, which reaches Control's proxy.
 *
 * ⚠ **What is NOT durable yet.** The proxy's canonical region store writes bundle bytes through
 * `FileDatabase`, and the iOS `platformProxyModule` binds `InMemoryFileDatabase`. So a *downloaded*
 * region on iOS lives in RAM and dies with the process — the streamed read path (D10, what the
 * detail VMs actually prefer) is unaffected, but "keep this offline" cannot really land until iOS
 * gets a durable `FileDatabase`. The stager below is the render-hop staging layer, a separate
 * concern from that store.
 */
object IosRegionSupport {

    /**
     * The app-local staging store for drained bundles — one per process, since it is stateless
     * beyond its directory. Application Support (see [iosMapRegionStagingDirectory]).
     *
     * Maps does **not** go through a `RegionBundleResolver`: its region seam is `RegionRepository`,
     * the picker's own read surface, and `ClientRegionRepository` takes the stager directly
     * (`MapsLocalRuntime`). Same object, so all three apps stage into the same per-app directory
     * rather than one each.
     */
    val stager: RegionBundleStager by lazy {
        IosRegionBundleStager(iosMapRegionStagingDirectory())
    }

    /** The shared render terminus over [client] — what each app's resolver adapts. */
    fun terminus(client: TenshuClient): RegionTerminus = RegionTerminus(client, stager)

    /**
     * The mean of a route's points — a good-enough camera centre for picking the covering region.
     * Null for an empty route.
     */
    inline fun meanOf(size: Int, lat: (Int) -> Double, lng: (Int) -> Double): LatLng? {
        if (size == 0) return null
        var latSum = 0.0
        var lngSum = 0.0
        for (i in 0 until size) {
            latSum += lat(i)
            lngSum += lng(i)
        }
        return LatLng(latitude = latSum / size, longitude = lngSum / size)
    }
}
