package app.tenshu.timeline.data.wifi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **S7 — Wi-Fi recognition observability** (`timeline-movement-capture.md` §18.5/§23).
 *
 * ⚠⚠ The point of this slice is the states that were previously **silent**, so most of these tests
 * assert on a *reported reason* rather than on a placeId. A test that only checked the working paths
 * would reproduce exactly the gap F2 left.
 */
class WifiRecognitionStateTest {

    private class FakeControl(initial: Boolean) : WifiFingerprintControl {
        private val state = MutableStateFlow(initial)
        override val enabled: StateFlow<Boolean> = state
        override fun setEnabled(enabled: Boolean) { state.value = enabled }
    }

    /** Records whether the radio was read at all — the consent-ordering claim needs evidence. */
    private class FakeWifi(
        private val bssid: String?,
        private val ssid: String? = "venue",
    ) : ConnectedWifi {
        var reads = 0
        override fun currentBssid(): String? { reads++; return bssid }
        override fun currentSsid(): String? = ssid
    }

    private class MemoryStore : WifiFingerprintStore {
        val byBssid = mutableMapOf<String, String>()
        val bySsid = mutableMapOf<String, String>()
        override fun placeFor(bssid: String): String? = byBssid[bssid]
        override fun placeForSsid(ssid: String): String? = bySsid[ssid]
        override fun remember(bssid: String, placeId: String) { byBssid[bssid] = placeId }
        override fun rememberSsid(ssid: String, placeId: String) { bySsid[ssid] = placeId }
        override fun knownPlaceIds(): Set<String> = byBssid.values.toSet() + bySsid.values.toSet()
    }

    private fun fingerprint(
        consent: Boolean = true,
        bssid: String? = "aa:bb:cc:dd:ee:ff",
        store: MemoryStore = MemoryStore(),
        wifi: FakeWifi = FakeWifi(bssid),
    ) = Triple(WifiPlaceFingerprint(FakeControl(consent), wifi, store), store, wifi)

    // ── the states that used to be silent ──────────────────────────────────────

    @Test
    fun consentOffIsReportedAndTheRadioIsNeverRead() {
        val (fp, _, wifi) = fingerprint(consent = false)

        assertEquals("cap:1,2", fp.resolvePlaceId("cap:1,2"))
        assertEquals(WifiRecognitionState.ConsentOff, fp.state.value)
        // ⚠⚠ Consent is checked BEFORE the radio — an opted-out user's BSSID must never be read,
        // not even to render a status line.
        assertEquals(0, wifi.reads, "the BSSID must not be read while consent is off")
    }

    @Test
    fun aWithheldIdentityIsDistinguishedFromNoNetwork() {
        // ⚠⚠ The distinction S7 exists for. Both were one silent `null` before, and only the first
        // is something the user can fix (location services off).
        val (withheld, _, _) = fingerprint(bssid = "02:00:00:00:00:00")
        withheld.resolvePlaceId("cap:1,2")
        assertEquals(WifiRecognitionState.IdentityWithheld, withheld.state.value)
        assertTrue(withheld.state.value.isActionable)

        val (none, _, _) = fingerprint(bssid = null)
        none.resolvePlaceId("cap:1,2")
        assertEquals(WifiRecognitionState.NoNetwork, none.state.value)
        assertTrue(!none.state.value.isActionable, "not being on Wi-Fi is not a fault to act on")
    }

    @Test
    fun theWorkingPathsStillReportThemselves() {
        val store = MemoryStore().apply { byBssid["aa:bb:cc:dd:ee:ff"] = "cap:9,9" }
        val (fp, _, _) = fingerprint(store = store)

        assertEquals("cap:9,9", fp.resolvePlaceId("cap:1,2"))
        assertEquals(WifiRecognitionState.Recognised("cap:9,9"), fp.state.value)
        assertTrue(fp.state.value.isWorking)
    }

    @Test
    fun anUnseenNetworkReportsThatItLearned() {
        val (fp, store, _) = fingerprint()

        assertEquals("cap:1,2", fp.resolvePlaceId("cap:1,2"))
        assertEquals(WifiRecognitionState.Learned("cap:1,2"), fp.state.value)
        assertEquals("cap:1,2", store.byBssid["aa:bb:cc:dd:ee:ff"])
    }

    // ── probe ─────────────────────────────────────────────────────────────────

    @Test
    fun probeNeverLearns() {
        // ⚠⚠ The trap this guards: `resolvePlaceId` learns as a side effect of answering, which is
        // right for a real visit and wrong for a screen being opened. A probing Settings screen that
        // minted bindings would fabricate place associations from wherever the phone happened to be.
        val (fp, store, _) = fingerprint()

        fp.probe()

        assertTrue(store.byBssid.isEmpty(), "probe must not bind a BSSID: ${store.byBssid}")
        assertTrue(store.bySsid.isEmpty(), "probe must not bind an SSID: ${store.bySsid}")
        // Twin: the same fixture through resolvePlaceId DOES bind — so this cannot pass by the store
        // simply being unreachable.
        fp.resolvePlaceId("cap:1,2")
        assertEquals("cap:1,2", store.byBssid["aa:bb:cc:dd:ee:ff"])
    }

    @Test
    fun probeWithConsentOffDoesNotTouchTheRadioEither() {
        val (fp, _, wifi) = fingerprint(consent = false)
        assertEquals(WifiRecognitionState.ConsentOff, fp.probe())
        assertEquals(0, wifi.reads)
    }

    @Test
    fun probeReportsAKnownNetworkWithoutWriting() {
        val store = MemoryStore().apply { byBssid["aa:bb:cc:dd:ee:ff"] = "cap:7,7" }
        val (fp, _, _) = fingerprint(store = store)

        assertEquals(WifiRecognitionState.Recognised("cap:7,7"), fp.probe())
        assertEquals(1, store.byBssid.size, "probe must not add bindings")
    }

    // ── the reporting posture ─────────────────────────────────────────────────

    @Test
    fun unknownIsNotTreatedAsAFailure() {
        // ⚠ "We have not looked yet" must not read as "it is broken" — a Settings row that cried
        // failure before capture had run would train the user to ignore it.
        val (fp, _, _) = fingerprint()
        assertEquals(WifiRecognitionState.Unknown, fp.state.value)
        assertTrue(fp.state.value.isWorking)
        assertTrue(!fp.state.value.isActionable)
    }

    @Test
    fun bindCurrentNetworkStillWorksAndIsUnaffected() {
        val (fp, store, _) = fingerprint()
        fp.bindCurrentNetwork("cap:5,5")
        assertEquals("cap:5,5", store.byBssid["aa:bb:cc:dd:ee:ff"])
    }

    @Test
    fun aWithheldBssidNeverReachesTheStore() {
        // Belt and braces: the redacted sentinel must not be learned as if it were a real radio,
        // which would bind every location-off device's visits to one shared pseudo-network.
        val (fp, store, _) = fingerprint(bssid = "02:00:00:00:00:00")
        fp.resolvePlaceId("cap:1,2")
        fp.bindCurrentNetwork("cap:3,3")
        assertTrue(store.byBssid.isEmpty(), "the redacted sentinel must never be stored")
        assertNull(store.placeFor("02:00:00:00:00:00"))
    }
}
