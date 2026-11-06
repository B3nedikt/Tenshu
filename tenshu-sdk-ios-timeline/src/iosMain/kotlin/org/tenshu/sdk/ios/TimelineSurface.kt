package org.tenshu.sdk.ios

import app.tenshu.timeline.data.MockTimelineRepository
import app.tenshu.timeline.data.RegionBundleResolver
import app.tenshu.timeline.data.TimelineRepository
import app.tenshu.timeline.data.geocode.GeocodingBackfillTrigger
import app.tenshu.timeline.data.geocode.IosPlaceGeocodingControl
import app.tenshu.timeline.data.geocode.PlaceGeocodingControl
import app.tenshu.timeline.data.geocode.PlaceLabelEnricher
import app.tenshu.timeline.data.wifi.IosWifiRecognition
import app.tenshu.timeline.data.wifi.WifiRecognitionState
import app.tenshu.timeline.ui.screens.home.TimelineHomeViewModel
import app.tenshu.timeline.ui.screens.moment.MomentDetailViewModel
import app.tenshu.timeline.ui.screens.place.PlaceDetailViewModel
import app.tenshu.timeline.ui.screens.trip.TripDetailViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.tenshu.core.common.transport.TaskTransport

/**
 * Wires the **real** Timeline view-model layer (`:timeline-shared-logic`) to iOS so the SwiftUI
 * `timeline-ios` app can resolve each screen's view model and observe its `StateFlow` through SKIE
 * (proposal §9.3 "share view models + repos, native UI"). This is the Timeline twin of
 * [DriveReadSurface] / [ControlSurface].
 *
 * Timeline's Koin DI lives in `:timeline-shared-ui` (Compose, not on iOS), so this object is the
 * minimal hand-wiring the SwiftUI client needs instead of `timelineModules()`.
 *
 * The data source is an **injected** [TimelineRepository] — the single install seam:
 *  - the runtime default is the honest [NotConnectedTimelineRepository] (empty reads +
 *    `controlReachable = false`), so an unprovisioned app is *empty and says why* rather than full
 *    of fabricated places and trips;
 *  - [MockTimelineRepository] stays for tests and previews only ([installMock]);
 *  - the route-1 runtime / live-EPC tier installs a real `TimelineProjectionRepository`
 *    ([installRepository] / [installRealClient]) with zero Swift change — exactly the Drive
 *    Tier-1/Tier-2 shape.
 */
object TimelineSurface {

    // The repository every factory binds. Default = the honest "not connected" surface, NOT the
    // mock: until Control provisions route-1 key material (or a DEV_HARNESS EPC client pairs) there
    // is nothing real to show, and fabricated data would mislead (the Fit §11.7 precedent).
    private var installed: TimelineRepository = NotConnectedTimelineRepository()

    // Place-name lookups (place-geocoding.md G5). The consent flag is app-global and persisted
    // (NSUserDefaults) regardless of tier; the enricher + its opt-in-edge backfill trigger exist
    // only while a live client is installed — the mock tier has nothing to look up or write to.
    private val geocodingControl: PlaceGeocodingControl = IosPlaceGeocodingControl()

    /** The same control, for the route-1 runtime's repository (its transit-stop + picker gate). */
    internal val placeGeocodingControl: PlaceGeocodingControl get() = geocodingControl
    private var installedEnricher: PlaceLabelEnricher? = null
    private var geocodingScope: CoroutineScope? = null

    /**
     * The offline-basemap region resolver every detail view model binds (`maps-region-delivery.md`
     * §7). Null on the not-connected / mock tiers — exactly like the Compose DI's `getOrNull()`, so
     * a route renders on the bundled demo basemap rather than failing. Installed alongside the
     * repository by whichever tier brought a live [org.tenshu.core.common.TenshuClient]
     * ([installRealClient] for EPC, [TimelineLocalRuntime.install] for route 1), because the
     * resolver reaches `MapRegionService` over that same client.
     */
    private var regions: RegionBundleResolver? = null

    /**
     * Install the [TimelineRepository] subsequently-built view models bind. The live-EPC tier
     * installs a real `TimelineProjectionRepository`; the mock is the v1 default.
     */
    fun installRepository(repository: TimelineRepository) {
        installed = repository
    }

    /**
     * Assemble the **real** Timeline client over an established EPC [channel] and install its
     * [TimelineProjectionRepository][app.tenshu.timeline.data.TimelineProjectionRepository] — every
     * screen built afterwards reads the live `:timeline-service`/`:place-service` spine instead of the
     * mock, with no SwiftUI change. The Timeline twin of [FitSurface.installRealClient]; delegates to
     * [EpcTimelineClient.assembleClient].
     *
     * The live op round-trip needs a Control serving EPC over a reachable relay (the on-device gate);
     * [TimelineLiveClientSession] owns the channel lifecycle and the [installMock] fallback.
     */
    fun installRealClient(channel: TaskTransport) {
        val client = EpcTimelineClient.assembleClient(channel, geocodingControl)
        installed = client.timelineRepository
        installedEnricher = client.placeLabelEnricher
        // The region rail rides the same client (Control's proxy hosts MapRegionService) — see
        // [regions]. Best-effort: a failure to reach the catalog just leaves the demo basemap.
        regions = timelineRegionResolver(client.tenshuClient)
        // The iOS twin of Android's application-scope trigger: every opt-in edge — including
        // install-time with the toggle already on — backfills blank-label synced places.
        geocodingScope?.cancel()
        geocodingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope ->
            val enricher = client.placeLabelEnricher ?: return@also
            // Explicitly open (ADR 0025 §8.6): iOS route-1 wires no realm probe, so there is no gate.
            scope.launch {
                GeocodingBackfillTrigger(geocodingControl) {
                    enricher.backfill(beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)
                }.run()
            }
        }
    }

    /**
     * Restore the honest **not-connected** surface — the runtime teardown / fallback target: empty
     * reads + `controlReachable = false`, so the shell shows the "Can't reach Tenshu Control"
     * banner instead of fabricated data (the Fit `installNotConnected` twin).
     */
    fun installNotConnected() {
        geocodingScope?.cancel()
        geocodingScope = null
        installedEnricher = null
        regions = null
        installed = NotConnectedTimelineRepository()
    }

    /**
     * Install the deterministic [MockTimelineRepository]. **Tests and previews only** — it is no
     * longer a runtime surface (the runtime not-connected state is [installNotConnected]).
     */
    fun installMock() {
        geocodingScope?.cancel()
        geocodingScope = null
        installedEnricher = null
        regions = null
        installed = MockTimelineRepository()
    }

    /**
     * Whether the installed repository can actually reach Control — `false` only on the
     * [NotConnectedTimelineRepository]. The Swift shell renders its banner off this, mirroring the
     * Compose shell's `repository.controlReachable()` read (`timeline-shared-ui` `App.kt`).
     *
     * Read as a plain snapshot rather than the flow: the *repository instance itself* is what
     * changes when the runtime installs, and the shell already remounts on that edge.
     */
    fun controlReachable(): Boolean = installed.controlReachable().value != false

    // ── place-name lookups, Swift-facing (place-geocoding.md G5) ─────────────

    /** Whether the geocoding rail is live (a real client with an enricher is installed). */
    fun geocodingAvailable(): Boolean = installedEnricher != null

    /** The persisted opt-in (threat-model A35(d)) — off by default. */
    fun geocodingEnabled(): Boolean = geocodingControl.enabled.value

    /** Record the user's choice; enabling kicks a backfill via the trigger. */
    fun setGeocodingEnabled(enabled: Boolean) = geocodingControl.setEnabled(enabled)

    // ── Wi-Fi place recognition, Swift-facing (§4.5) ─────────────────────────

    /**
     * The live runtime's recognition stack, or `null` on the not-connected / mock tiers.
     *
     * ⚠ Unlike [geocodingControl], the opt-in is **not** held here as a tier-independent singleton.
     * The control alone is useless without the store it writes into, and that store needs the live
     * client for its synced half — so the whole stack is assembled by
     * [TimelineLocalRuntime] and installed here, and the toggle is hidden until it exists.
     */
    private var wifi: IosWifiRecognition? = null

    /** Whether Wi-Fi place recognition can run at all — the live runtime is installed. */
    fun wifiRecognitionAvailable(): Boolean = wifi != null

    /** The persisted opt-in — off by default. `false` when no live runtime is installed. */
    fun wifiRecognitionEnabled(): Boolean = wifi?.control?.enabled?.value == true

    /** Record the user's choice; enabling warms the caches so the next read is current. */
    fun setWifiRecognitionEnabled(enabled: Boolean) {
        val stack = wifi ?: return
        stack.control.setEnabled(enabled)
        if (enabled) stack.refreshAsync()
    }

    /**
     * Refresh and take a **read-only** reading, returning the key the Settings status line renders
     * (the S7 observability surface — "is this even working?" answerable without a log tail).
     *
     * Suspending rather than a snapshot getter because on iOS the radio read *is* asynchronous:
     * a plain getter would report whatever the cache last held, which on a freshly-opened Settings
     * screen is usually nothing. SKIE bridges this to Swift `async`.
     *
     * ⚠ The key set and its classification mirror `SettingsScreen.WifiStatusLine` in
     * `:timeline-shared-ui` — Compose maps the same four buckets to string resources. Only
     * [WifiRecognitionState.IdentityWithheld] is a *problem*; `Unknown` deliberately is not.
     */
    suspend fun probeWifiRecognition(): String {
        val stack = wifi ?: return STATUS_UNKNOWN
        if (!stack.control.enabled.value) return STATUS_UNKNOWN
        return when (stack.probe()) {
            WifiRecognitionState.IdentityWithheld -> STATUS_WITHHELD
            WifiRecognitionState.NoNetwork -> STATUS_NO_NETWORK
            WifiRecognitionState.Unknown, WifiRecognitionState.ConsentOff -> STATUS_UNKNOWN
            else -> STATUS_WORKING
        }
    }

    /** Warm the radio + mirror caches — the foreground hook. */
    fun refreshWifi() {
        wifi?.refreshAsync()
    }

    /** Install the live runtime's recognition stack; `null` restores the inert state. */
    internal fun installWifi(recognition: IosWifiRecognition?) {
        wifi = recognition
    }

    /** The installed stack — for the capture runtime (one store, one state) and for tests. */
    internal fun installedWifi(): IosWifiRecognition? = wifi

    /** Status keys returned by [probeWifiRecognition]; Swift maps each to its copy. */
    const val STATUS_UNKNOWN: String = "unknown"
    const val STATUS_NO_NETWORK: String = "no_network"
    const val STATUS_WITHHELD: String = "withheld"
    const val STATUS_WORKING: String = "working"

    /**
     * Install the offline-basemap [RegionBundleResolver] subsequently-built detail view models bind
     * — the C3 data-path wiring. Called by the tier that owns the live client; `null` restores the
     * demo-basemap-only behaviour. Internal: Swift never names it (it drives the surface's
     * `install*` entry points), so the exported Obj-C header is unchanged.
     */
    internal fun installRegionResolver(resolver: RegionBundleResolver?) {
        regions = resolver
    }

    /** The currently-installed region resolver — exposed for a test to assert the wiring. */
    internal fun installedRegionResolver(): RegionBundleResolver? = regions

    /** The currently-installed repository — exposed for a smoke/test to assert against. */
    internal fun installedRepository(): TimelineRepository = installed

    /** Home feed + Places + Activities (three tabs inside one screen, one VM). */
    fun homeViewModel(): TimelineHomeViewModel = TimelineHomeViewModel(installed)

    /** Moment detail — [momentId] resolves through a home day or any (nested) trip. */
    fun momentViewModel(momentId: String): MomentDetailViewModel =
        MomentDetailViewModel(installed, momentId, regions)

    /** Trip detail — recursive; a nested trip opens the same screen with its own id. */
    fun tripViewModel(tripId: String): TripDetailViewModel =
        TripDetailViewModel(installed, regions = regions, tripId = tripId)

    /** Place detail — identity + visit history. */
    fun placeViewModel(placeId: String): PlaceDetailViewModel =
        PlaceDetailViewModel(installed, placeId, regions)
}
