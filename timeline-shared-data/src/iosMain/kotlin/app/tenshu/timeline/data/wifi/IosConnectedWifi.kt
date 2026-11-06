package app.tenshu.timeline.data.wifi

import app.tenshu.diagnostics.Diagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.NetworkExtension.NEHotspotNetwork
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume

/**
 * A [ConnectedWifi] whose value has to be **fetched** rather than read — the shape iOS forces (see
 * [IosConnectedWifi]) and the seam that lets a test supply a network without a radio.
 */
interface RefreshableWifi : ConnectedWifi {
    /** Fetch and cache the current network. Bounded; never throws. */
    suspend fun refresh()

    /** Warm the cache without awaiting it. */
    fun refreshAsync()
}

/**
 * iOS [ConnectedWifi] over `NEHotspotNetwork.fetchCurrent` — the modern replacement for the
 * deprecated `CNCopyCurrentNetworkInfo`.
 *
 * ### ⚠⚠ Why this caches instead of reading straight through
 *
 * [ConnectedWifi] is a **synchronous** interface, because the Android side is one `WifiManager`
 * call and the capture read path cannot suspend inside `WifiPlaceFingerprint.resolvePlaceId`.
 * Apple's API is asynchronous *and*, per its own header, delivers its completion **on the
 * application's main queue** — so bridging it synchronously with a semaphore would deadlock the
 * moment it were called from the main thread, which is exactly where the `CLVisit` delegate runs.
 *
 * So the read is split: [refresh] awaits a real fetch, and the accessors serve the last value it
 * stored. The capture path awaits [refresh] immediately before resolving (see `IosWifiRecognition`),
 * which makes the value used for a dwell decision current by construction. Everything else — the
 * rename binding, the Settings status line — reads a cache the shell warms on foreground.
 *
 * ### ⚠⚠ The entitlement gate
 *
 * `fetchCurrent` returns `nil` unless the app carries **`com.apple.developer.networking.wifi-info`**
 * ("Access WiFi Information") *and* holds precise-location authorization. Capture already holds the
 * location grant, so the entitlement is the live gate — and it is unavailable on a free personal
 * signing team, like App Groups (`docs/how-to/ios-signing.md`). Without it every accessor here
 * answers `null`, `WifiPlaceFingerprint` publishes `NoNetwork`, and recognition is inert. That is
 * the correct degradation, but it is **indistinguishable from "not on Wi-Fi"** in the shared
 * [WifiRecognitionState] vocabulary, so the first nil fetch logs [NO_NETWORK_CODE] naming the
 * entitlement — the answer is one diagnostics tail away rather than a mystery.
 *
 * ⚠ `fetchCurrent` also returns `nil` on the **simulator**, which has no Wi-Fi interface of its own.
 */
class IosConnectedWifi(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val fetchTimeoutMs: Long = DEFAULT_FETCH_TIMEOUT_MS,
) : RefreshableWifi {

    private class Snapshot(val bssid: String?, val ssid: String?)

    @Volatile
    private var snapshot: Snapshot? = null

    /** Whether a fetch has ever come back non-nil — gates the one-shot "why is this empty" line. */
    @Volatile
    private var everResolved = false

    @Volatile
    private var loggedUnavailable = false

    /**
     * ⚠ Benign race: two callers can both observe `false` and both fetch. Costs one redundant
     * (cheap, local) query and never a wrong value, so it is not worth a lock on this path.
     */
    @Volatile
    private var refreshInFlight = false

    override fun currentBssid(): String? = snapshot?.bssid

    override fun currentSsid(): String? = snapshot?.ssid

    /**
     * Fetch the current network and update the cache. Bounded by [fetchTimeoutMs] so a wedged
     * completion can never stall a capture write; a timeout leaves the previous value in place
     * rather than blanking it (a stale binding beats minting a duplicate place).
     */
    override suspend fun refresh() {
        refreshInFlight = true
        try {
            val network = withTimeoutOrNull(fetchTimeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    NEHotspotNetwork.fetchCurrentWithCompletionHandler { current ->
                        continuation.resume(current)
                    }
                }
            }
            if (network != null) {
                everResolved = true
                snapshot = Snapshot(bssid = network.BSSID.orBlankNull(), ssid = network.SSID.orBlankNull())
            } else {
                snapshot = Snapshot(bssid = null, ssid = null)
                noteUnavailable()
            }
        } finally {
            refreshInFlight = false
        }
    }

    /** Warm the cache without awaiting it — the foreground / screen-appear hook. Deduplicated. */
    override fun refreshAsync() {
        if (refreshInFlight) return
        scope.launch { refresh() }
    }

    /**
     * Log the entitlement hint **once**, and only while we have never seen a network.
     *
     * ⚠ Transition-only for the same reason `WifiPlaceFingerprint.publish` is: an ordinary
     * off-Wi-Fi device would otherwise write this line on every dwell. Gating on [everResolved]
     * also keeps it out of the log for the ordinary case — a device that has read a network before
     * plainly has the capability and is simply on cellular now.
     */
    private fun noteUnavailable() {
        if (everResolved || loggedUnavailable) return
        loggedUnavailable = true
        Diagnostics.i(
            "wifi",
            "no network from NEHotspotNetwork.fetchCurrent — either not on Wi-Fi, or this build " +
                "lacks the com.apple.developer.networking.wifi-info entitlement (paid signing tier)",
            code = NO_NETWORK_CODE,
        )
    }

    private fun String?.orBlankNull(): String? = this?.takeIf { it.isNotBlank() }

    companion object {
        /** Diagnostics code for the one-shot "recognition can never fire here" line. */
        const val NO_NETWORK_CODE = "wifi_fetch_unavailable"

        /**
         * A local system query; 2 s is generous. It exists to bound a *hung* completion, not a slow
         * one — the capture worker awaits this before writing a dwell.
         */
        const val DEFAULT_FETCH_TIMEOUT_MS = 2_000L
    }
}
