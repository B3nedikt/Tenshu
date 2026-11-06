package app.tenshu.timeline.data.wifi

import kotlinx.coroutines.flow.StateFlow

/**
 * The **user's opt-in for Wi-Fi place recognition** (`docs/proposals/timeline-recognition-and-place-naming.md`
 * §4.5) — persisted intent, decoupled from the sampling that enacts it. Mirrors [PlaceGeocodingControl]:
 * the shared Settings screen renders a toggle bound to this **only when a platform binds one** (Koin
 * `getOrNull`); platforms with no Wi-Fi seam bind nothing and the toggle simply doesn't appear.
 *
 * When on, the app remembers which Wi-Fi network (BSSID) it was on at a place the user named, and
 * reuses that name when it sees the same network again — sidestepping the ±100 m indoor GPS floor
 * that makes a coarse fix name the neighbour instead of the place you were in.
 *
 * **Off by default.** BSSIDs are more sensitive than a city name (they pin a location precisely), so
 * this stays opt-in and — in v1 — **entirely device-local**: fingerprints are never synced and never
 * leave the device (no relay, no `INTERNET`; House Rule #1 intact). Flipping [enabled] off stops all
 * sampling immediately (the capture + rename hooks check it every time).
 */
interface WifiFingerprintControl {
    /** Whether the user has opted into Wi-Fi place recognition. Survives process death (platform-persisted). */
    val enabled: StateFlow<Boolean>

    /** Record the user's choice. */
    fun setEnabled(enabled: Boolean)
}
