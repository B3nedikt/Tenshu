package app.tenshu.timeline.data.wifi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

/**
 * The iOS **assembled** Wi-Fi recognition stack: the shared [WifiPlaceFingerprint] facade plus the
 * two refreshes that have to happen *before* it can answer correctly on this platform.
 *
 * Android needs no such wrapper — `WifiManager` is a synchronous read and the fingerprint mirror is
 * kept warm by a long-lived foreground-service process. iOS has neither property, and both gaps
 * land on the same call:
 *
 *  1. **The radio read is asynchronous** ([IosConnectedWifi] — Apple delivers on the main queue, so
 *     it cannot be bridged synchronously). The cache must be filled before the lookup, not after.
 *  2. ⚠⚠ **The synced fingerprint mirror is empty on a cold wake.** A `CLVisit` relaunches the app
 *     into a fresh process; [SyncedWifiFingerprintStore] hydrates asynchronously, so without an
 *     awaited refresh the very first dwell of the wake — the one the process was woken *for* —
 *     reads an empty map, misses a binding another device learned, and mints a duplicate place.
 *     That is precisely the fragmentation the synced half exists to prevent, and iOS is the only
 *     platform where the race is the common case rather than a corner.
 *
 * Both refreshes are bounded and best-effort: a failure leaves the previous value in place and the
 * resolve degrades to the grid-snapped candidate, which is exactly today's behaviour.
 */
class IosWifiRecognition(
    /** The shared facade — also the [WifiPlaceFingerprint.state] the Settings status line reads. */
    val fingerprint: WifiPlaceFingerprint,
    /**
     * The persisted opt-in. Held here rather than reached through [fingerprint], whose own
     * reference is private — the facade *consults* consent, it does not publish it.
     */
    val control: WifiFingerprintControl,
    private val connected: RefreshableWifi,
    private val store: WifiFingerprintStore,
    /**
     * The scope backing the synced mirror's follow-collector, owned here so [close] can end it.
     * `null` when the caller owns the lifecycle (tests, or a store with no collector).
     */
    private val scope: CoroutineScope? = null,
) {

    /**
     * The placeId a dwell at [candidatePlaceId] should use — the capture-path entry point.
     *
     * Awaits both refreshes first, so the verdict is made against the network the device is on
     * *now* and the bindings every device has learned, rather than against whatever this process
     * happened to have cached when it was woken.
     */
    suspend fun resolvePlaceId(candidatePlaceId: String, latE7: Long, lngE7: Long): String {
        refresh()
        return fingerprint.resolvePlaceId(candidatePlaceId, latE7, lngE7)
    }

    /** Fill both caches. Safe to call spuriously; each half swallows its own failure. */
    suspend fun refresh() {
        connected.refresh()
        (store as? SyncedWifiFingerprintStore)?.refresh()
    }

    /**
     * Refresh, then take a **read-only** reading — the Settings-screen entry point.
     *
     * ⚠⚠ [WifiPlaceFingerprint.probe], never `resolvePlaceId`: the latter learns as a side effect of
     * answering, which is right when a real visit is being recorded and wrong when a screen is
     * merely being opened. A probing Settings screen that minted bindings would fabricate place
     * associations from wherever the phone happened to be.
     */
    suspend fun probe(): WifiRecognitionState {
        refresh()
        return fingerprint.probe()
    }

    /** Warm the caches without awaiting — the foreground / Settings-appear hook. */
    fun refreshAsync() {
        connected.refreshAsync()
    }

    /**
     * End the synced mirror's follow-collector — the deprovision hook. Without it a
     * deprovision → re-provision cycle would leave the previous runtime's collector reading a
     * sealed graph.
     */
    fun close() {
        scope?.cancel()
    }
}
