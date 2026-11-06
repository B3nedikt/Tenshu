package app.tenshu.timeline.data.wifi

import kotlinx.coroutines.test.runTest
import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The iOS Wi-Fi place-recognition seams (§4.5) — the store, the opt-in, and the
 * refresh-before-resolve ordering that is the whole reason [IosWifiRecognition] exists.
 *
 * ⚠ [IosConnectedWifi]'s own fetch is **not** exercised here: it calls
 * `NEHotspotNetwork.fetchCurrent`, which needs an app bundle, an entitlement and a radio, and
 * returns nil in a test binary regardless. What is testable — and what actually broke on Android —
 * is the surrounding contract, so that is what these pin. The real fetch is on-device work.
 */
class IosWifiRecognitionTest {

    private val suite = "tenshu.test.wifi.${randomSuffix()}"
    private val defaults = NSUserDefaults(suiteName = suite)!!

    @AfterTest
    fun tearDown() {
        NSUserDefaults.standardUserDefaults.removePersistentDomainForName(suite)
    }

    // ── store ────────────────────────────────────────────────────────────────

    @Test
    fun bssidBindingRoundTripsAndIsCaseInsensitive() {
        val store = IosWifiFingerprintStore(defaults)
        store.remember("A4:2B:8C:00:11:22", "cap:100,200")

        assertEquals("cap:100,200", store.placeFor("a4:2b:8c:00:11:22"))
        assertEquals("cap:100,200", store.placeFor("A4:2B:8C:00:11:22"))
    }

    /** ⚠ SSIDs are case-*sensitive* by spec — unlike a BSSID, "Office" and "office" are two venues. */
    @Test
    fun ssidBindingIsCaseSensitive() {
        val store = IosWifiFingerprintStore(defaults)
        store.rememberSsid("Office", "cap:100,200")

        assertEquals("cap:100,200", store.placeForSsid("Office"))
        assertNull(store.placeForSsid("office"))
    }

    @Test
    fun knownPlaceIdsUnionsBothIndexesAndIgnoresForeignKeys() {
        val store = IosWifiFingerprintStore(defaults)
        // A key from some other subsystem sharing the NSUserDefaults domain — it must not be
        // mistaken for a fingerprint, or the proximity fallback would adopt arbitrary strings.
        defaults.setObject("not-a-place", "some_other_feature_key")
        store.remember("a4:2b:8c:00:11:22", "cap:100,200")
        store.rememberSsid("Office", "cap:300,400")

        assertEquals(setOf("cap:100,200", "cap:300,400"), store.knownPlaceIds().toSet())
    }

    // ── opt-in ───────────────────────────────────────────────────────────────

    @Test
    fun optInIsOffByDefaultAndPersists() {
        assertFalse(IosWifiFingerprintControl(defaults).enabled.value)

        IosWifiFingerprintControl(defaults).setEnabled(true)

        // A second instance reads the same NSUserDefaults — the process-death survival that matters
        // on iOS, where a CLVisit relaunches the app fresh for every event.
        assertTrue(IosWifiFingerprintControl(defaults).enabled.value)
    }

    // ── the ordering ─────────────────────────────────────────────────────────

    /**
     * ⚠⚠ The reason [IosWifiRecognition] exists. On iOS the network is not readable synchronously,
     * so the facade alone sees nothing — the wrapper's awaited refresh is what makes a binding
     * resolvable at all. [FakeWifi] models exactly that: no value until [FakeWifi.refresh] runs.
     */
    @Test
    fun resolveRefreshesTheRadioBeforeLookingUp() = runTest {
        val wifi = recognition(bssid = "a4:2b:8c:00:11:22", consent = true)
        wifi.store.remember("a4:2b:8c:00:11:22", "cap:home")

        val resolved = wifi.recognition.resolvePlaceId("cap:grid-snapped", latE7 = 0, lngE7 = 0)

        assertEquals("cap:home", resolved)
        assertEquals(1, wifi.radio.refreshes)
    }

    /** The control: without the wrapper's refresh the same lookup finds nothing. */
    @Test
    fun facadeAloneCannotResolveBecauseTheRadioWasNeverFetched() {
        val wifi = recognition(bssid = "a4:2b:8c:00:11:22", consent = true)
        wifi.store.remember("a4:2b:8c:00:11:22", "cap:home")

        val resolved = wifi.recognition.fingerprint.resolvePlaceId("cap:grid-snapped", 0, 0)

        assertEquals("cap:grid-snapped", resolved)
        assertEquals(0, wifi.radio.refreshes)
    }

    /**
     * ⚠ Consent off must leave the candidate alone **and never touch the radio** — the facade checks
     * the opt-in before reading, so an opted-out user's BSSID is not read merely to answer.
     */
    @Test
    fun consentOffReturnsTheCandidateAndLearnsNothing() = runTest {
        val wifi = recognition(bssid = "a4:2b:8c:00:11:22", consent = false)

        val resolved = wifi.recognition.resolvePlaceId("cap:grid-snapped", latE7 = 0, lngE7 = 0)

        assertEquals("cap:grid-snapped", resolved)
        assertTrue(wifi.store.knownPlaceIds().isEmpty())
    }

    /** An unknown network binds to the proposed place, so the *next* visit recognises it. */
    @Test
    fun anUnknownNetworkLearnsTheProposedPlace() = runTest {
        val wifi = recognition(bssid = "a4:2b:8c:00:11:22", consent = true)

        assertEquals("cap:new", wifi.recognition.resolvePlaceId("cap:new", latE7 = 0, lngE7 = 0))
        assertEquals("cap:new", wifi.store.placeFor("a4:2b:8c:00:11:22"))
    }

    /** No radio value (the entitlement-less / off-Wi-Fi case) degrades to the grid-snapped id. */
    @Test
    fun noNetworkDegradesToTheCandidate() = runTest {
        val wifi = recognition(bssid = null, consent = true)

        assertEquals("cap:grid", wifi.recognition.resolvePlaceId("cap:grid", latE7 = 0, lngE7 = 0))
        assertTrue(wifi.store.knownPlaceIds().isEmpty())
    }

    /** Before any refresh the real source reports nothing — the inert default, no API call made. */
    @Test
    fun realSourceIsEmptyUntilRefreshed() {
        val connected = IosConnectedWifi()

        assertNull(connected.currentBssid())
        assertNull(connected.currentSsid())
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private class Assembled(
        val recognition: IosWifiRecognition,
        val radio: FakeWifi,
        val store: IosWifiFingerprintStore,
    )

    private fun recognition(bssid: String?, consent: Boolean): Assembled {
        val radio = FakeWifi(bssid)
        val store = IosWifiFingerprintStore(defaults)
        val control = IosWifiFingerprintControl(defaults).also { it.setEnabled(consent) }
        return Assembled(
            recognition = IosWifiRecognition(
                fingerprint = WifiPlaceFingerprint(control, radio, store),
                control = control,
                connected = radio,
                store = store,
            ),
            radio = radio,
            store = store,
        )
    }

    /**
     * A radio whose value only becomes visible after [refresh] — the iOS property the synchronous
     * Android seam does not have.
     */
    private class FakeWifi(private val bssid: String?) : RefreshableWifi {
        var refreshes = 0
        private var fetched = false

        override fun currentBssid(): String? = bssid.takeIf { fetched }
        override fun currentSsid(): String? = null
        override suspend fun refresh() {
            refreshes++
            fetched = true
        }

        override fun refreshAsync() = Unit
    }

    private fun randomSuffix(): String = NSUserDefaults.standardUserDefaults.hashCode().toString() +
        this.hashCode().toString()
}
