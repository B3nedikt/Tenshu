package app.tenshu.timeline.data.wifi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [WifiPlaceFingerprint] — the consent-gated recognise-or-learn over the three device-local seams
 * (`timeline-recognition-and-place-naming.md` §4.5), including the §8.4 **F2** venue-shaped steps
 * that stop a multi-AP venue minting one place per access point.
 *
 * Coordinates below use the fragmentation the device showed (§8.2), moved by whole grid cells: `cap:490320000,209030000`
 * and `cap:490320000,209040000` are the two office cells, 73 m apart.
 */
class WifiPlaceFingerprintTest {

    private val officeA = "cap:490320000,209030000"
    private val officeB = "cap:490320000,209040000" // 73 m east — the second grid cell
    private val officeBLatE7 = 490320000L
    private val officeBLngE7 = 209040000L

    private class FakeControl(enabled: Boolean) : WifiFingerprintControl {
        override val enabled = MutableStateFlow(enabled)
        override fun setEnabled(enabled: Boolean) {
            (this.enabled as MutableStateFlow).value = enabled
        }
    }

    private class FakeStore : WifiFingerprintStore {
        val map = mutableMapOf<String, String>()
        val ssids = mutableMapOf<String, String>()
        override fun remember(bssid: String, placeId: String) { map[bssid] = placeId }
        override fun placeFor(bssid: String): String? = map[bssid]
        override fun rememberSsid(ssid: String, placeId: String) { ssids[ssid] = placeId }
        override fun placeForSsid(ssid: String): String? = ssids[ssid]
        override fun knownPlaceIds(): Collection<String> = (map.values + ssids.values).toSet()
    }

    private class FakeWifi(val bssid: String?, val ssid: String? = null) : ConnectedWifi {
        override fun currentBssid(): String? = bssid
        override fun currentSsid(): String? = ssid
    }

    private fun fingerprint(
        enabled: Boolean,
        bssid: String?,
        ssid: String? = null,
        store: FakeStore = FakeStore(),
    ) = WifiPlaceFingerprint(FakeControl(enabled), FakeWifi(bssid, ssid), store) to store

    @Test
    fun consentOffIsAnIdentityAndNeverLearns() {
        val (fp, store) = fingerprint(enabled = false, bssid = "a4:2b:8c:11:22:33")
        assertEquals("cand", fp.resolvePlaceId("cand"))
        assertTrue(store.map.isEmpty(), "must not fingerprint anything while disabled")
    }

    @Test
    fun noOrRedactedBssidIsAnIdentity() {
        assertEquals("cand", fingerprint(enabled = true, bssid = null).first.resolvePlaceId("cand"))
        assertEquals("cand", fingerprint(enabled = true, bssid = "02:00:00:00:00:00").first.resolvePlaceId("cand"))
        assertEquals("cand", fingerprint(enabled = true, bssid = "  ").first.resolvePlaceId("cand"))
    }

    @Test
    fun learnsANewNetworkThenRecognisesItDespiteGpsDrift() {
        val (fp, store) = fingerprint(enabled = true, bssid = "a4:2b:8c:11:22:33")
        // First visit here: the coordinate-derived place is learned for this network, returned as-is.
        assertEquals("place-A", fp.resolvePlaceId("place-A"))
        assertEquals("place-A", store.map["a4:2b:8c:11:22:33"])
        // A later visit on the same network but a GPS-drifted centroid folds onto the known place.
        assertEquals("place-A", fp.resolvePlaceId("place-B-drifted"))
    }

    // ---- §8.4 F2 ------------------------------------------------------------------------------

    @Test
    fun aSecondAccessPointOnTheSameSsidFoldsOntoTheVenuesPlace() {
        val store = FakeStore()
        // Visit 1 — the office's first AP, learned against the first grid cell.
        val (first, _) = fingerprint(true, bssid = "a4:2b:8c:10:00:01", ssid = "Office", store = store)
        assertEquals(officeA, first.resolvePlaceId(officeA, 490320000L, 209030000L))
        // Visit 2 — a *different* AP of the same office, and the drifted centroid lands in the
        // neighbouring cell. Pre-F2 this minted a second "Office". Now the SSID names the venue.
        val (second, _) = fingerprint(true, bssid = "a4:2b:8c:10:00:02", ssid = "Office", store = store)
        assertEquals(officeA, second.resolvePlaceId(officeB, officeBLatE7, officeBLngE7))
        // …and the new radio is now bound to it, so the next visit is a direct hit.
        assertEquals(officeA, store.map["a4:2b:8c:10:00:02"])
    }

    @Test
    fun anUnknownNetworkStandingAtAKnownPlaceAdoptsItByProximity() {
        val store = FakeStore()
        val (first, _) = fingerprint(true, bssid = "a4:2b:8c:20:00:01", store = store)
        assertEquals(officeA, first.resolvePlaceId(officeA, 490320000L, 209030000L))
        // A guest AP with no SSID match, 73 m away — inside VENUE_RADIUS_M.
        val (guest, _) = fingerprint(true, bssid = "02:00:00:50:00:01", ssid = "Guest", store = store)
        assertEquals(officeA, guest.resolvePlaceId(officeB, officeBLatE7, officeBLngE7))
    }

    @Test
    fun theSameSsidFarAwayIsADifferentVenue() {
        val store = FakeStore()
        val (munich, _) = fingerprint(true, bssid = "a4:2b:8c:10:00:01", ssid = "Office", store = store)
        assertEquals(officeA, munich.resolvePlaceId(officeA, 490320000L, 209030000L))
        // Same chain SSID, ~71 km away: must NOT claim the first place.
        val berlin = "cap:491450000,218500000"
        val (other, _) = fingerprint(true, bssid = "a4:2b:8c:40:00:01", ssid = "Office", store = store)
        assertEquals(berlin, other.resolvePlaceId(berlin, 491450000L, 218500000L))
    }

    @Test
    fun withoutCoordinatesTheVenueStepsStayInertAndItJustLearns() {
        // A caller that cannot supply coordinates gets exactly the pre-F2 behaviour.
        val store = FakeStore()
        val (first, _) = fingerprint(true, bssid = "aa:bb:cc:dd:ee:01", ssid = "Office", store = store)
        assertEquals(officeA, first.resolvePlaceId(officeA))
        val (second, _) = fingerprint(true, bssid = "aa:bb:cc:dd:ee:02", ssid = "Office", store = store)
        assertEquals(officeB, second.resolvePlaceId(officeB))
    }

    @Test
    fun renamingBindsTheCurrentNetworkToTheNamedPlace() {
        val store = FakeStore()
        val (fp, _) = fingerprint(true, bssid = "a4:2b:8c:10:00:01", ssid = "Office", store = store)
        fp.bindCurrentNetwork(officeA)
        assertEquals(officeA, store.map["a4:2b:8c:10:00:01"])
        assertEquals(officeA, store.ssids["Office"])
    }

    @Test
    fun renameBindingOverridesAStaleBindingButNeverRunsWhileConsentIsOff() {
        val store = FakeStore()
        val (fp, _) = fingerprint(true, bssid = "a4:2b:8c:10:00:01", ssid = "Office", store = store)
        fp.resolvePlaceId(officeB, officeBLatE7, officeBLngE7) // learned against the wrong cell
        fp.bindCurrentNetwork(officeA) // the user names the right one while standing here
        assertEquals(officeA, store.map["a4:2b:8c:10:00:01"])

        val (offFp, offStore) = fingerprint(false, bssid = "a4:2b:8c:10:00:01", ssid = "Office")
        offFp.bindCurrentNetwork(officeA)
        assertTrue(offStore.map.isEmpty(), "consent off must not learn from a rename either")
    }
}

/**
 * The distance guard on the **BSSID** branch (`timeline-movement-capture.md` §18 family).
 *
 * ⚠⚠ That branch was the only one of the three with no distance test at all: the SSID branch checks
 * `SSID_VENUE_RADIUS_M` and `nearestKnownPlace` checks its own radius, while a bound BSSID was
 * returned unconditionally — so one travelling radio could claim a place from any distance and
 * outrank a perfectly good coordinate. Surfaced by a real feed showing a café's name in the middle
 * of an unbroken stay somewhere else.
 */
class WifiMovedNetworkTest {

    private val cafe = "cap:490370000,209040000"
    // ~556 m south of the café — the real-world separation that made this visible.
    private val officeLatE7 = 490320000L
    private val officeLngE7 = 209030000L

    private class FakeControl : WifiFingerprintControl {
        override val enabled = MutableStateFlow(true)
        override fun setEnabled(enabled: Boolean) { enabled.also { } }
    }

    private class FakeStore : WifiFingerprintStore {
        val map = mutableMapOf<String, String>()
        val ssids = mutableMapOf<String, String>()
        override fun remember(bssid: String, placeId: String) { map[bssid] = placeId }
        override fun placeFor(bssid: String): String? = map[bssid]
        override fun rememberSsid(ssid: String, placeId: String) { ssids[ssid] = placeId }
        override fun placeForSsid(ssid: String): String? = ssids[ssid]
        override fun knownPlaceIds(): Collection<String> = (map.values + ssids.values).toSet()
    }

    private class FakeWifi(val bssid: String?) : ConnectedWifi {
        override fun currentBssid(): String? = bssid
        override fun currentSsid(): String? = null
    }

    private fun fingerprint(store: FakeStore) =
        WifiPlaceFingerprint(FakeControl(), FakeWifi("a4:2b:8c:11:22:33"), store)

    @Test
    fun aKnownApFarFromItsBindingDoesNotClaimThePlace() {
        val store = FakeStore().apply { remember("a4:2b:8c:11:22:33", cafe) }
        val resolved = fingerprint(store)
            .resolvePlaceId("cap:490320000,209030000", officeLatE7, officeLngE7)

        assertTrue(
            resolved != cafe,
            "an AP bound 556 m away must not name this dwell — that is the café-in-the-middle-of-" +
                "the-stay symptom",
        )
    }

    @Test
    fun theBindingIsLeftAloneRatherThanRewritten() {
        // ⚠ Deliberate: rebinding here is how a travelling hotspot would erase a real venue's
        // fingerprint and then go on to claim the next place too.
        val store = FakeStore().apply { remember("a4:2b:8c:11:22:33", cafe) }
        fingerprint(store).resolvePlaceId("cap:490320000,209030000", officeLatE7, officeLngE7)

        assertEquals(cafe, store.map["a4:2b:8c:11:22:33"], "the café's own binding must survive")
    }

    @Test
    fun anApAtItsOwnVenueStillRecognisesNormally() {
        // The guard is a sanity bound, not a second opinion: standing where the AP was learned must
        // behave exactly as before.
        val store = FakeStore().apply { remember("a4:2b:8c:11:22:33", cafe) }
        val resolved = fingerprint(store).resolvePlaceId("cand", 490370000L, 209040000L)

        assertEquals(cafe, resolved)
    }

    @Test
    fun aDwellWithNoCoordinateStillTrustsTheBinding() {
        // ⚠⚠ Unknown is not elsewhere. A first cut of this guard used `!near(...)`, which reads a
        // missing coordinate as "far" — and that disables the GPS-drift correction the BSSID branch
        // exists for (`learnsANewNetworkThenRecognisesItDespiteGpsDrift` caught it). Only a real
        // measured distance may withdraw trust.
        val store = FakeStore().apply { remember("a4:2b:8c:11:22:33", cafe) }
        val resolved = fingerprint(store).resolvePlaceId("cand")

        assertEquals(cafe, resolved)
    }

    @Test
    fun aBindingWithNoCoordinateOfItsOwnIsTrustedToo() {
        // The other unknown: the bound place carries no coordinate, so there is nothing to measure
        // against. Same rule — no measurement, no withdrawal of trust.
        val store = FakeStore().apply { remember("a4:2b:8c:11:22:33", "place-without-coordinates") }
        val resolved = fingerprint(store)
            .resolvePlaceId("cand", officeLatE7, officeLngE7)

        assertEquals("place-without-coordinates", resolved)
    }
}
