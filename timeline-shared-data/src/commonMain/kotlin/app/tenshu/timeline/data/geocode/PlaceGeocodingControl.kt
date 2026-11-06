package app.tenshu.timeline.data.geocode

import kotlinx.coroutines.flow.StateFlow

/**
 * The **user's opt-in for place-name lookups** (`docs/proposals/place-geocoding.md` D5) — the
 * persisted intent, decoupled from the geocoder that enacts it. The shared Settings screen
 * renders a toggle bound to this **only when a platform binds one** (Koin `getOrNull`), exactly
 * like [app.tenshu.timeline.capture.TimelineCaptureControl]: G2 binds a real Android control,
 * platforms with no geocoder bind nothing and the toggle simply doesn't appear.
 *
 * One gate for **all** enrichment egress — capture-time and import-time alike: an import
 * backfill sends the coordinates of the user's entire history to a provider, exactly as
 * sensitive as live capture egress. "Native ≠ private": Android `Geocoder`/`CLGeocoder` are
 * server-backed, so the coordinate reaches Google/Apple. Off by default; flipping [enabled] to
 * `true` is the explicit consent, and [PlaceLabelEnricher] checks it before every lookup, so
 * disabling stops all lookups immediately. The threat-model amendment lands with the first real
 * geocoder (G2, House Rule #2).
 */
interface PlaceGeocodingControl {
    /** Whether the user has opted into place-name lookups. Survives process death (platform-persisted). */
    val enabled: StateFlow<Boolean>

    /** Record the user's choice. */
    fun setEnabled(enabled: Boolean)
}
