package app.tenshu.timeline.data.geocode

import java.util.prefs.Preferences
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Round-trips [DesktopPlaceGeocodingControl] against a **scratch** prefs node (removed after each
 * test) so the user's real preference tree — and the real `DEFAULT_NODE` — is never touched.
 */
class DesktopPlaceGeocodingControlTest {

    private val node: Preferences =
        Preferences.userRoot().node("tenshu-test/geocoding-${Random.nextLong().toULong()}")

    @AfterTest
    fun cleanUp() {
        node.removeNode()
    }

    @Test
    fun defaultsToDisabled() {
        assertFalse(DesktopPlaceGeocodingControl(node).enabled.value)
    }

    @Test
    fun optInPersistsAcrossInstances() {
        DesktopPlaceGeocodingControl(node).setEnabled(true)

        // A fresh instance over the same node = "process restart".
        assertTrue(DesktopPlaceGeocodingControl(node).enabled.value)
    }

    @Test
    fun optOutPersistsAndUpdatesTheFlow() {
        val control = DesktopPlaceGeocodingControl(node)
        control.setEnabled(true)
        control.setEnabled(false)

        assertFalse(control.enabled.value)
        assertFalse(DesktopPlaceGeocodingControl(node).enabled.value)
    }
}
