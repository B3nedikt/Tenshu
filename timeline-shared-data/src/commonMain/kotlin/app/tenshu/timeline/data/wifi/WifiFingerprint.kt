package app.tenshu.timeline.data.wifi

/**
 * Reads the **currently-connected** Wi-Fi network (`docs/proposals/timeline-recognition-and-place-naming.md`
 * §4.5): the access point's BSSID — a stable per-AP id — and the network's SSID, which names the
 * *venue* rather than the radio (§8.4 F2). Both are `null` when off Wi-Fi, when the value is
 * unavailable, or on platforms without a binding.
 *
 * ⚠ **Connected AP only** — not a scan of nearby networks (that would need `NEARBY_WIFI_DEVICES` on
 * Android 13+). Both values ride the location permission the app already holds, need no new runtime
 * prompt, and — like everything here — no `INTERNET`.
 */
interface ConnectedWifi {
    /** The connected AP's BSSID (e.g. `a4:2b:8c:…`), or `null`. */
    fun currentBssid(): String?

    /**
     * The connected network's SSID (e.g. `Office`), or `null`. Defaulted so a platform that exposes
     * only the BSSID still binds — venue-level matching then degrades to plain proximity.
     */
    fun currentSsid(): String? = null
}

/**
 * A **device-local, never-synced** map of Wi-Fi network → the `placeId` it was seen at (§4.5), keyed
 * both by **BSSID** (one radio) and by **SSID** (the venue — §8.4 F2). Learned as visits are
 * captured and when the user names a place while connected; consulted when a new visit is captured,
 * so a named place is recognised without trusting a coarse indoor GPS fix.
 *
 * v1 keeps this **off the sync plane entirely** — network identifiers pin a location precisely and
 * are more sensitive than a place name; they stay on the one device that observed them (no relay,
 * HR #1).
 */
interface WifiFingerprintStore {
    /** Learn: remember that [bssid] was seen at [placeId]. */
    fun remember(bssid: String, placeId: String)

    /** Recognise: the `placeId` fingerprinted to [bssid], or `null` if this network is unknown. */
    fun placeFor(bssid: String): String?

    /**
     * Learn the **venue**: remember that the network named [ssid] was seen at [placeId] (§8.4 F2).
     * Defaulted no-op so a simpler store still satisfies the interface.
     */
    fun rememberSsid(ssid: String, placeId: String) {}

    /** The `placeId` fingerprinted to the network named [ssid], or `null`. */
    fun placeForSsid(ssid: String): String? = null

    /**
     * Every `placeId` this device has fingerprinted — the input to the proximity fallback (§8.4 F2
     * step 3): an unknown AP standing beside a place we already recognise belongs to *that* place.
     * Defaulted empty ⇒ the fallback is simply inert.
     */
    fun knownPlaceIds(): Collection<String> = emptyList()
}
