package app.tenshu.timeline

import app.tenshu.timeline.data.geocode.DesktopPlaceGeocodingControl
import app.tenshu.timeline.data.geocode.PhotonReverseGeocoder
import app.tenshu.timeline.data.TimelineImportControl
import app.tenshu.timeline.data.geocode.PlaceGeocodingControl
import app.tenshu.timeline.data.geocode.PlaceLabelEnricher
import app.tenshu.timeline.data.geocode.ReverseGeocoder
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Desktop-only bindings layered over `timelineModules()`, mirroring `timelineAndroidModule()`
 * (place-geocoding.md G3): desktop has no device geocoder, so the common Photon tier is its
 * **only** [ReverseGeocoder]. Binding the persisted [PlaceGeocodingControl] is what makes the
 * shared Settings toggle appear here (the G1 `getOrNull` mechanism); the consent-gated
 * [PlaceLabelEnricher] is shared by the import hook and the app-start backfill trigger.
 *
 * The Photon endpoint stays [PhotonReverseGeocoder.DEFAULT_PUBLIC_ENDPOINT] until the
 * self-hosted instance lands — a config-only swap at this binding site. The `HttpClient` is
 * built here (platform-provided engine, OkHttp on the JVM), not inside the geocoder.
 */
fun timelineDesktopModule(): Module = module {
    single<PlaceGeocodingControl> { DesktopPlaceGeocodingControl() }
    single<ReverseGeocoder> { PhotonReverseGeocoder(HttpClient(OkHttp)) }
    single { PlaceLabelEnricher(get(), get(), get(), get()) }
    // B2: the same Settings → Import row as Android. The window-owning `main` attaches the dialog.
    single { DesktopTimelineImportControl(get(), importer = { get() }, photoImporter = { get() }) }
    single<TimelineImportControl> { get<DesktopTimelineImportControl>() }
}
