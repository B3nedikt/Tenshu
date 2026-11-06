package org.tenshu.sdk.ios

import app.tenshu.timeline.data.wifi.IosWifiFingerprintControl
import app.tenshu.timeline.data.wifi.IosWifiFingerprintStore
import app.tenshu.timeline.data.wifi.IosWifiRecognition
import app.tenshu.timeline.data.wifi.RefreshableWifi
import app.tenshu.timeline.data.wifi.WifiPlaceFingerprint
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Swift-facing half of Wi-Fi place recognition (§4.5): the availability gate, the opt-in
 * passthrough, and the status keys `SettingsView` switches on.
 *
 * ⚠ These pin the **producer** side of a string-keyed bridge. Swift maps the same four keys to
 * copy, so a rename here without a rename there silently shows the wrong line — which is exactly
 * what this file exists to make a red test instead.
 */
class TimelineWifiSurfaceTest {

    private val suite = "tenshu.test.wifisurface"
    private val defaults = NSUserDefaults(suiteName = suite)!!

    @AfterTest
    fun tearDown() {
        TimelineSurface.installWifi(null)
        NSUserDefaults.standardUserDefaults.removePersistentDomainForName(suite)
    }

    @Test
    fun statusKeysAreTheOnesSwiftSwitchesOn() {
        // Literals, deliberately: asserting a constant against itself proves nothing. These four
        // strings appear verbatim in `timeline-ios/Sources/SettingsView.swift`.
        assertEquals("unknown", TimelineSurface.STATUS_UNKNOWN)
        assertEquals("no_network", TimelineSurface.STATUS_NO_NETWORK)
        assertEquals("withheld", TimelineSurface.STATUS_WITHHELD)
        assertEquals("working", TimelineSurface.STATUS_WORKING)
    }

    /** With no live runtime the toggle must not appear, and every accessor must be inert. */
    @Test
    fun recognitionIsUnavailableWithoutALiveRuntime() = runTest {
        TimelineSurface.installWifi(null)

        assertFalse(TimelineSurface.wifiRecognitionAvailable())
        assertFalse(TimelineSurface.wifiRecognitionEnabled())
        assertEquals(TimelineSurface.STATUS_UNKNOWN, TimelineSurface.probeWifiRecognition())
        // Must not throw — the shell calls this on every foreground, live or not.
        TimelineSurface.refreshWifi()
    }

    @Test
    fun installingAStackExposesTheOptInAndPersistsIt() {
        TimelineSurface.installWifi(stack(bssid = null))

        assertTrue(TimelineSurface.wifiRecognitionAvailable())
        assertFalse(TimelineSurface.wifiRecognitionEnabled())

        TimelineSurface.setWifiRecognitionEnabled(true)

        assertTrue(TimelineSurface.wifiRecognitionEnabled())
        assertTrue(IosWifiFingerprintControl(defaults).enabled.value, "must survive a fresh read")
    }

    /** ⚠ Consent off reports `unknown`, not `no_network` — "off" is not "broken". */
    @Test
    fun probeReportsUnknownWhileTheOptInIsOff() = runTest {
        TimelineSurface.installWifi(stack(bssid = "a4:2b:8c:00:11:22"))

        assertEquals(TimelineSurface.STATUS_UNKNOWN, TimelineSurface.probeWifiRecognition())
    }

    @Test
    fun probeReportsWorkingOnAKnownNetworkAndNoNetworkWithout() = runTest {
        val known = stack(bssid = "a4:2b:8c:00:11:22")
        TimelineSurface.installWifi(known)
        TimelineSurface.setWifiRecognitionEnabled(true)
        // A binding this device already holds — probe recognises it without learning.
        IosWifiFingerprintStore(defaults).remember("a4:2b:8c:00:11:22", "cap:home")

        assertEquals(TimelineSurface.STATUS_WORKING, TimelineSurface.probeWifiRecognition())

        TimelineSurface.installWifi(stack(bssid = null))
        assertEquals(TimelineSurface.STATUS_NO_NETWORK, TimelineSurface.probeWifiRecognition())
    }

    private fun stack(bssid: String?): IosWifiRecognition {
        val radio = FakeWifi(bssid)
        val store = IosWifiFingerprintStore(defaults)
        val control = IosWifiFingerprintControl(defaults)
        return IosWifiRecognition(
            fingerprint = WifiPlaceFingerprint(control, radio, store),
            control = control,
            connected = radio,
            store = store,
        )
    }

    private class FakeWifi(private val bssid: String?) : RefreshableWifi {
        private var fetched = false
        override fun currentBssid(): String? = bssid.takeIf { fetched }
        override fun currentSsid(): String? = null
        override suspend fun refresh() { fetched = true }
        override fun refreshAsync() = Unit
    }
}
