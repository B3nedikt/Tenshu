package app.tenshu.timeline.data.wifi

import platform.Foundation.NSUserDefaults

/**
 * iOS [WifiFingerprintStore]: a **device-local, never-synced** `network → placeId` map in
 * `NSUserDefaults` — one `b:<bssid>` entry per radio and one `s:<ssid>` entry per venue, the same
 * two-index shape as `AndroidWifiFingerprintStore`.
 *
 * Network identifiers pin a location precisely, so they stay on the device that observed them. The
 * synced half is the hashed-tag [SyncedWifiFingerprintStore] wrapped *around* this one; SSIDs never
 * leave the device either way.
 *
 * ⚠ **Why per-key entries rather than one dictionary.** `NSUserDefaults` is atomic per key but a
 * read-modify-write of a stored dictionary is not, and this store has two writers — the capture
 * worker and the rename path — on different coroutines. Per-key writes make a lost update
 * impossible without a lock. The cost lands on [knownPlaceIds], which has to filter the domain; see
 * its note.
 */
class IosWifiFingerprintStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : WifiFingerprintStore {

    override fun remember(bssid: String, placeId: String) {
        defaults.setObject(placeId, bssidKey(bssid))
    }

    override fun placeFor(bssid: String): String? = defaults.stringForKey(bssidKey(bssid))

    override fun rememberSsid(ssid: String, placeId: String) {
        defaults.setObject(placeId, ssidKey(ssid))
    }

    override fun placeForSsid(ssid: String): String? = defaults.stringForKey(ssidKey(ssid))

    /**
     * Every fingerprinted placeId — the input to the proximity fallback (step 3 of the resolve
     * ladder).
     *
     * ⚠ Unlike Android's dedicated preferences file, `NSUserDefaults` is one flat app-wide domain,
     * so this filters [PREFIX] out of the whole representation rather than reading a small file.
     * Affordable because it runs only on an **unmatched** dwell — on iOS a dwell is a `CLVisit`, so
     * a handful of times a day, not per fix.
     */
    override fun knownPlaceIds(): Collection<String> =
        defaults.dictionaryRepresentation()
            .entries
            .mapNotNull { (key, value) ->
                (value as? String)?.takeIf { (key as? String)?.startsWith(PREFIX) == true }
            }
            .toSet()

    /** Case-insensitive: BSSIDs can come back with mixed casing across reads. */
    private fun bssidKey(bssid: String) = "${PREFIX}b:${bssid.lowercase()}"

    /** ⚠ SSIDs are case-*sensitive* by spec, so unlike a BSSID this key is not lowercased. */
    private fun ssidKey(ssid: String) = "${PREFIX}s:$ssid"

    private companion object {
        /** Namespaces both indexes inside the shared `NSUserDefaults` domain. */
        const val PREFIX = "tenshu_timeline_wifi_fp."
    }
}
