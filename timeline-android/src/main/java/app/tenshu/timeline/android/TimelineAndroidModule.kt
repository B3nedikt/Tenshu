package app.tenshu.timeline.android

import app.tenshu.timeline.capture.TimelineCaptureControl
import app.tenshu.timeline.data.capture.AndroidLocationCaptureSource
import app.tenshu.timeline.data.capture.AndroidRawFixJournalControl
import app.tenshu.timeline.data.capture.AndroidStepCounter
import app.tenshu.timeline.data.capture.RawFixJournal
import app.tenshu.timeline.data.capture.RawFixJournalControl
import app.tenshu.timeline.data.capture.SegmentationReplaySweep
import app.tenshu.timeline.data.capture.androidRawFixJournal
import app.tenshu.timeline.data.capture.AndroidVisitOutboxStore
import app.tenshu.timeline.data.capture.CapturedVisitOutbox
import app.tenshu.timeline.data.capture.LocationCaptureSource
import app.tenshu.timeline.android.geocode.ProxyReverseGeocoder
import app.tenshu.timeline.data.geocode.AndroidReverseGeocoder
import app.tenshu.timeline.data.geocode.ChainedReverseGeocoder
import app.tenshu.timeline.data.geocode.MomentTransitAdjacency
import app.tenshu.timeline.data.geocode.PlaceGeocodingControl
import app.tenshu.timeline.data.geocode.PlaceGeocodingEgress
import app.tenshu.timeline.data.TimelineImportControl
import app.tenshu.timeline.data.geocode.PlaceLabelEnricher
import app.tenshu.timeline.data.geocode.ReverseGeocoder
import app.tenshu.timeline.data.wifi.AndroidConnectedWifi
import app.tenshu.timeline.data.wifi.AndroidWifiFingerprintControl
import app.tenshu.timeline.data.wifi.AndroidWifiFingerprintStore
import app.tenshu.timeline.data.wifi.ConnectedWifi
import app.tenshu.timeline.data.wifi.WifiFingerprintControl
import app.tenshu.timeline.data.wifi.SyncedWifiFingerprintStore
import app.tenshu.timeline.data.wifi.WifiFingerprintStore
import app.tenshu.timeline.data.wifi.WifiPlaceFingerprint
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Android-only bindings layered over `timelineModules()` (I3b-2, mirrors Fit's `fitAndroidModule()`):
 * the live [LocationCaptureSource] (platform `LocationManager`), the persisted [TimelineCaptureControl]
 * opt-in the Settings toggle drives, and the foreground-service launcher `MainActivity` acts on. The
 * capture foreground service resolves the source + `InProcessTimelineProxy` (bound in the shared
 * module) from Koin. Bound only here, so desktop/iOS have no capture surface — off by default.
 *
 * G2 (place-geocoding.md) adds the place-name-lookup rail: the device-tier [ReverseGeocoder]
 * (`android.location.Geocoder`), its persisted [PlaceGeocodingControl] opt-in (binding it is what
 * makes the Settings toggle appear), and the consent-gated [PlaceLabelEnricher] the capture service
 * and app-start backfill share. Android stays **device-tier-only** (no Photon fallback): the
 * G3 chain would need the INTERNET permission this app's manifest deliberately strips
 * (place-geocoding.md §4.3 as-built — a maintainer opt-in, not a default; the device geocoder
 * egresses via the GMS process, so the app itself keeps "nothing leaves the device" at the OS
 * boundary).
 */
fun timelineAndroidModule(
    /**
     * Whether this build is debuggable — the gate on the **raw-fix corpus** (§18.9).
     *
     * ⚠⚠ Passed in rather than probed here because a `module { }` builder has no `androidContext()`
     * to ask, and the binding has to be *absent* rather than merely inert: absence is what makes the
     * Settings section not render (the bound-only-shows idiom every other opt-in here uses) and what
     * makes a verbatim location log unreachable in a release build by construction rather than by a
     * runtime check somebody could later relax.
     */
    debuggable: Boolean = false,
): Module = module {
    single<LocationCaptureSource> {
        AndroidLocationCaptureSource(
            androidContext(),
            // Absent in release ⇒ RawFixJournal.None ⇒ the capture loop records nothing extra.
            rawFixJournal = getOrNull() ?: RawFixJournal.None,
            // S3 (§19): the pedometer. ⚠ AndroidStepCounter itself reports unavailable when the
            // sensor is absent, and an ungranted ACTIVITY_RECOGNITION simply never delivers — both
            // land as `null` cadence, which LegMode reads as "no evidence" and not as "no steps".
            stepCounter = AndroidStepCounter(androidContext()),
        )
    }
    single { CapturedVisitOutbox(AndroidVisitOutboxStore(androidContext())) }
    single<TimelineCaptureControl> { AndroidTimelineCaptureControl(androidContext()) }
    single { TimelineCaptureServiceLauncher(androidContext()) }
    single<PlaceGeocodingControl> { AndroidPlaceGeocodingControl(androidContext()) }

    // B2 (`takeout-import-and-recall.md`): importing a Takeout export on the phone — the platform
    // where the export actually lands. ⚠ Bound only here, so the Import row appears on Android and
    // is simply absent where nothing can pick a file (the bound-only-shows idiom).
    single {
        AndroidTimelineImportControl(
            resolver = androidContext().contentResolver,
            scope = get(),
            importer = { get() },
            photoImporter = { get() },
        )
    }
    single<TimelineImportControl> { get<AndroidTimelineImportControl>() }
    // P3 (§11.3): POI-aware proxy geocoder first (venue name via Control's Photon egress), then the
    // platform address geocoder (street) as fallback.
    single<ReverseGeocoder> {
        ChainedReverseGeocoder(
            listOf(
                ProxyReverseGeocoder(PlaceGeocodingEgress(get(), get<PlaceGeocodingControl>())),
                AndroidReverseGeocoder(androidContext()),
            ),
        )
    }
    // §31: the trajectory gate. Reads the moment stream once per sweep and only when a coarse
    // candidate exists, so it can tighten the transit-stop radius for a dwell that was confidently
    // walked to and from — never widen it.
    single {
        PlaceLabelEnricher(
            get(), get(), get(), get(),
            trajectory = MomentTransitAdjacency(get())::verdicts,
        )
    }
    // P3c (§4.5): opt-in Wi-Fi place recognition. Binding WifiFingerprintControl is what makes the
    // Settings toggle appear; the facade is fed to the capture engine to fold repeat visits on a
    // known network onto the place the user named. All device-local — no sync, no INTERNET.
    single<WifiFingerprintControl> { AndroidWifiFingerprintControl(androidContext()) }
    single<ConnectedWifi> { AndroidConnectedWifi(androidContext()) }
    // F4 (§9.3): bindings sync as hashed network tags, so a new device inherits them instead of
    // relearning — and relearning is what re-fragments places. The prefs store stays underneath as
    // the durable cache, the offline fallback, and the sole home of the (never-synced) SSID index.
    single {
        SyncedWifiFingerprintStore(
            local = AndroidWifiFingerprintStore(androidContext()),
            client = get(),
            context = get(),
            scope = get(),
        ).also { it.start() }
    }
    single<WifiFingerprintStore> { get<SyncedWifiFingerprintStore>() }
    single { WifiPlaceFingerprint(get(), get(), get()) }

    // §18.9 — the offline-verification instrument. The sweep is bound in every build (it is
    // read-only and answers the S6b half from traces that already exist); the *corpus* that would
    // answer the S6a half is debug-only, because it is a verbatim location log.
    single { SegmentationReplaySweep(get(), getOrNull() ?: RawFixJournal.None) }
    if (debuggable) {
        single<RawFixJournalControl> { AndroidRawFixJournalControl(androidContext()) }
        single<RawFixJournal> { androidRawFixJournal(androidContext(), get()) }
    }
}
