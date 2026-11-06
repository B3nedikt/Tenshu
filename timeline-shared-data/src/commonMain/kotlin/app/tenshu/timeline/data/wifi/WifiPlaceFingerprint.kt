package app.tenshu.timeline.data.wifi

import app.tenshu.diagnostics.Diagnostics
import app.tenshu.timeline.data.capture.distanceMetersE7
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Wi-Fi place-recognition facade (`docs/proposals/timeline-recognition-and-place-naming.md` §4.5)
 * — one consent-gated call the capture loop uses at Dwell-confirmation to both **recognise** and
 * **learn**, over the three device-local seams ([WifiFingerprintControl], [ConnectedWifi],
 * [WifiFingerprintStore]).
 *
 * A captured visit proposes the coordinate-derived placeId (grid-snapped centroid). [resolvePlaceId]
 * answers "which place is this really?" from the network, so an indoor ±100 m fix cannot split a
 * place the user already knows:
 *
 *  1. **known BSSID** → the place already fingerprinted to this radio;
 *  2. **known SSID within [SSID_VENUE_RADIUS_M]** → that place, and this BSSID is bound to it too;
 *  3. **any fingerprinted place within [VENUE_RADIUS_M]** → that place, likewise bound;
 *  4. otherwise the proposal, learned for next time.
 *
 * ⚠⚠ **Steps 2 and 3 are the §8.4 F2 fix, and they are what makes the feature work at all.** The
 * original design learned per-BSSID only, so an *unknown* AP fell straight through to "learn the
 * proposal" — anchoring itself to whatever grid cell the drifting fix reported at that instant. A
 * venue therefore minted up to **one place per access point**: the device that prompted this carried
 * 12 bindings across 8 placeIds for ~4 real venues, and listed the office twice (§8.2). Recognising a
 * *venue* — same network name, or simply standing next to a place we already know — is what folds
 * the second AP onto the first one's place.
 *
 * [bindCurrentNetwork] closes the other half: naming a place while standing in it is the strongest
 * signal the user can give, and the original design ignored it (its "no rename-time hook is needed —
 * the name follows the placeId" holds only while one BSSID dominates a venue).
 *
 * Everything stays on-device (no relay, HR #1); a [control] that is off makes the whole thing an
 * identity function that learns nothing.
 */
class WifiPlaceFingerprint(
    private val control: WifiFingerprintControl,
    private val connected: ConnectedWifi,
    private val store: WifiFingerprintStore,
) {

    private val _state = MutableStateFlow<WifiRecognitionState>(WifiRecognitionState.Unknown)

    /**
     * What recognition last did — the S7 observability surface (§23).
     *
     * ⚠ Read by Settings, written by whichever code path last ran. Capture's foreground service and
     * the UI share one process, so this is simply the singleton's own state; no IPC involved.
     */
    val state: StateFlow<WifiRecognitionState> = _state.asStateFlow()

    /**
     * Look at the current network **without learning anything** (S7).
     *
     * ⚠⚠ **Must never bind.** `resolvePlaceId` learns as a side effect of answering, which is right
     * when a real visit is being recorded and wrong when a screen is merely being opened — a probing
     * Settings screen that minted bindings would fabricate place associations from wherever the
     * phone happened to be. This deliberately shares the *lookup* with `resolvePlaceId` and none of
     * the *writes*.
     *
     * ⚠ Consent is checked **before the radio is touched**, so an opted-out user's BSSID is never
     * read merely to render a status line.
     */
    fun probe(latE7: Long = 0, lngE7: Long = 0): WifiRecognitionState {
        val unavailable = unavailableState()
        if (unavailable != null) return publish(unavailable)
        val bssid = usable(connected.currentBssid()) ?: return publish(WifiRecognitionState.NoNetwork)
        val ssid = usable(connected.currentSsid())
        store.placeFor(bssid)?.let {
            // Same distance sanity as `resolvePlaceId`, or the status line would report a venue
            // that recognition itself would refuse to use.
            return publish(
                if (knownToBeFarFrom(it, latE7, lngE7)) WifiRecognitionState.MovedNetwork(it)
                else WifiRecognitionState.Recognised(it),
            )
        }
        val adopted = ssid?.let { store.placeForSsid(it) }?.takeIf { near(it, latE7, lngE7, SSID_VENUE_RADIUS_M) }
            ?: nearestKnownPlace(latE7, lngE7)
        return publish(
            if (adopted != null) WifiRecognitionState.Adopted(adopted) else WifiRecognitionState.NoNetwork,
        )
    }

    /**
     * Consent off / no network / withheld identity — or `null` when there is a usable network.
     *
     * ⚠⚠ The whole point of S7: these three were one silent `null` before, and only one of them
     * ([WifiRecognitionState.IdentityWithheld]) is something the user can fix.
     */
    private fun unavailableState(): WifiRecognitionState? {
        if (!control.enabled.value) return WifiRecognitionState.ConsentOff
        val raw = connected.currentBssid()?.trim()
        return when {
            raw.isNullOrEmpty() -> WifiRecognitionState.NoNetwork
            raw == REDACTED_BSSID -> WifiRecognitionState.IdentityWithheld
            else -> null
        }
    }

    /**
     * Record the verdict and log it **only when it changes**.
     *
     * ⚠ Transition-only, mirroring `SchemaFenceStatus`'s posture: `resolvePlaceId` runs on every
     * dwell confirmation, and for the default (opted-out) population a per-dwell line would crowd
     * the bounded diagnostics buffer with the least interesting fact in it.
     */
    private fun publish(next: WifiRecognitionState): WifiRecognitionState {
        val previous = _state.value
        _state.value = next
        if (previous != next) {
            Diagnostics.i("wifi", "recognition: ${describe(next)}", code = "wifi_state")
        }
        return next
    }

    private fun describe(state: WifiRecognitionState): String = when (state) {
        WifiRecognitionState.Unknown -> "no reading yet"
        WifiRecognitionState.ConsentOff -> "off (opt-in not enabled) — nothing read from the radio"
        WifiRecognitionState.NoNetwork -> "on, but no Wi-Fi connected"
        WifiRecognitionState.IdentityWithheld ->
            "on, but Android withheld the network id (location services off / permission missing)"
        is WifiRecognitionState.Recognised -> "recognised known AP -> ${state.placeId}"
        is WifiRecognitionState.Adopted -> "adopted nearby known place -> ${state.placeId}"
        is WifiRecognitionState.Learned -> "learned new network -> ${state.placeId}"
        is WifiRecognitionState.MovedNetwork ->
            "known AP is far from where it was learned (${state.placeId}) — ignoring it here"
    }
    /**
     * The placeId a visit at [candidatePlaceId] (centred on [latE7]/[lngE7]) should use, given the
     * currently-connected network — see the class KDoc for the four steps. Identity when consent is
     * off or there is no usable BSSID.
     */
    fun resolvePlaceId(candidatePlaceId: String, latE7: Long = 0, lngE7: Long = 0): String {
        // S7 (§23): the two silent early returns now name themselves. `unavailableState` also splits
        // "no network" from "identity withheld" — the second is the only one the user can act on.
        unavailableState()?.let {
            publish(it)
            return candidatePlaceId
        }
        val bssid = usable(connected.currentBssid()) ?: run {
            publish(WifiRecognitionState.NoNetwork)
            return candidatePlaceId
        }
        val ssid = usable(connected.currentSsid())

        val bound = store.placeFor(bssid)
        if (bound != null) {
            if (!knownToBeFarFrom(bound, latE7, lngE7)) {
                publish(WifiRecognitionState.Recognised(bound))
                return bound
            }
            // ⚠⚠ A known AP, seen far from where it was learned. This branch used to return the
            // binding unconditionally — the only one of the three with no distance test at all,
            // while its SSID sibling and `nearestKnownPlace` both had one — so a single travelling
            // radio (your phone hotspot, a train's Wi-Fi, a relocated router) could claim a place
            // from any distance, outranking a perfectly good coordinate.
            //
            // ⚠ And deliberately **no re-learn**: rebinding here is how a mobile AP would erase a
            // real venue's fingerprint and then go on to claim the next place too. Distrust it
            // *here*, leave the binding alone, and let the coordinate answer.
            publish(WifiRecognitionState.MovedNetwork(bound))
            return nearestKnownPlace(latE7, lngE7) ?: candidatePlaceId
        }

        val adopted = ssid?.let { store.placeForSsid(it) }?.takeIf { near(it, latE7, lngE7, SSID_VENUE_RADIUS_M) }
            ?: nearestKnownPlace(latE7, lngE7)
        if (adopted != null) {
            // A new radio at a venue we already recognise — bind it to the venue's place rather than
            // minting a second one from the drifted centroid (§8.4 F2).
            learn(bssid, ssid, adopted)
            publish(WifiRecognitionState.Adopted(adopted))
            return adopted
        }

        learn(bssid, ssid, candidatePlaceId)
        publish(WifiRecognitionState.Learned(candidatePlaceId))
        return candidatePlaceId
    }

    /**
     * Bind the currently-connected network to [placeId] — called when the user **names** a place
     * (§8.4 F2), the most authoritative association available: they are standing in it, and they
     * said what it is. Overrides any earlier binding for this AP/network. No-op when consent is off
     * or there is no usable BSSID.
     */
    fun bindCurrentNetwork(placeId: String) {
        if (!control.enabled.value || placeId.isBlank()) return
        val bssid = usable(connected.currentBssid()) ?: return
        learn(bssid, usable(connected.currentSsid()), placeId, userAuthored = true)
        Diagnostics.i("wifi", "bound current network to renamed place -> $placeId", code = "wifi_bound")
    }

    private fun learn(bssid: String, ssid: String?, placeId: String, userAuthored: Boolean = false) {
        // ⚠ A rename-time binding syncs as `user` provenance (§9.3 F4), which the merger prefers over
        // any peer's `learned` guess for the same network. A store without the synced half simply
        // records it locally — the distinction only exists on the wire.
        val synced = store as? SyncedWifiFingerprintStore
        if (userAuthored && synced != null) synced.rememberUserBinding(bssid, placeId)
        else store.remember(bssid, placeId)
        if (ssid != null) store.rememberSsid(ssid, placeId)
    }

    /** The nearest fingerprinted place within [VENUE_RADIUS_M] of the candidate centroid. */
    private fun nearestKnownPlace(latE7: Long, lngE7: Long): String? {
        if (latE7 == 0L && lngE7 == 0L) return null
        return store.knownPlaceIds()
            .mapNotNull { id -> coordinatesOf(id)?.let { id to distanceMetersE7(latE7, lngE7, it.first, it.second) } }
            .filter { it.second <= VENUE_RADIUS_M }
            .minWithOrNull(compareBy({ it.second }, { it.first }))
            ?.first
    }

    /**
     * Whether we can **positively establish** that [placeId] is too far away to be where we are.
     *
     * ⚠⚠ Not `!near(...)`, and the difference is the whole guard. `near` answers "is it close",
     * for which "no coordinate" and "far away" are both `false` — and reading absence as *far* is
     * what a first attempt at this did: it disabled AP recognition for every caller that passes no
     * coordinate, which is precisely the GPS-drift correction the BSSID branch exists to provide
     * (`learnsANewNetworkThenRecognisesItDespiteGpsDrift`). Unknown is not elsewhere.
     *
     * So both unknowns trust the binding: no coordinate to judge with, and a bound place that
     * carries no coordinate itself. Only a real distance, measured between two real coordinates,
     * can withdraw trust.
     */
    private fun knownToBeFarFrom(placeId: String, latE7: Long, lngE7: Long): Boolean {
        if (latE7 == 0L && lngE7 == 0L) return false
        val coords = coordinatesOf(placeId) ?: return false
        return distanceMetersE7(latE7, lngE7, coords.first, coords.second) > VENUE_NETWORK_RADIUS_M
    }

    private fun near(placeId: String, latE7: Long, lngE7: Long, radiusM: Double): Boolean {
        if (latE7 == 0L && lngE7 == 0L) return false
        val coords = coordinatesOf(placeId) ?: return false
        return distanceMetersE7(latE7, lngE7, coords.first, coords.second) <= radiusM
    }

    private fun usable(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() && it != REDACTED_BSSID && it != UNKNOWN_SSID }

    private companion object {
        /** Android returns this placeholder when it withholds the real BSSID (no location / off). */
        const val REDACTED_BSSID = "02:00:00:00:00:00"

        /** …and this one for a withheld SSID. */
        const val UNKNOWN_SSID = "<unknown ssid>"

        /**
         * How far a venue's network may reach. Generous — a large office's APs are spread across a
         * building — but bounded, so a chain or default SSID ("FRITZ!Box") in another city, or a
         * travelling phone hotspot, cannot claim a place hundreds of kilometres away.
         */
        const val VENUE_NETWORK_RADIUS_M = 500.0

        /**
         * Kept as the SSID branch's name. Both branches now share one number: the question is the
         * same for a network name and for a single radio — "could a venue's network plausibly reach
         * this far" — and two constants would only invite them to drift apart.
         */
        const val SSID_VENUE_RADIUS_M = VENUE_NETWORK_RADIUS_M

        /**
         * How close an *unnamed* new AP must be to an already-fingerprinted place to read as the
         * same venue. Wider than the ~74 m grid cell whose boundary caused the split, narrower than
         * the SSID radius, since the network name is not corroborating here.
         */
        const val VENUE_RADIUS_M = 150.0

        /**
         * Fingerprinted ids are always capture-minted (`cap:<latE7>,<lngE7>`, `VisitDetector`), so a
         * place's coordinates are recoverable from the id itself. That keeps the store a plain
         * `network → placeId` map: **no format change and no migration**, so bindings learned before
         * this fix work under the new rules immediately. Any other id shape yields `null` and is
         * simply skipped by the proximity steps.
         */
        fun coordinatesOf(placeId: String): Pair<Long, Long>? {
            if (!placeId.startsWith("cap:")) return null
            val parts = placeId.removePrefix("cap:").split(',')
            if (parts.size != 2) return null
            val lat = parts[0].toLongOrNull() ?: return null
            val lng = parts[1].toLongOrNull() ?: return null
            return lat to lng
        }
    }
}
