@file:OptIn(DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.ThumbnailPreferences
import org.tenshu.file.client.declareWantedThumbnailClasses
import org.tenshu.preference.client.preferenceService
import org.tenshu.preference.client.readOnceForDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⚠⚠ **The preference plane exists on web at all** — it did not until this change, and that
 * absence is what blocked X-5's wanted-class declaration
 * (`drive-xxlarge-thumbnail-rung.md` §4.8, §9.7).
 *
 * ⚠⚠ This asserts a **round trip through the real runtime**, deliberately, because the failure mode
 * it guards is the one `WantedThumbnailClasses.declare` hides: that function swallows every
 * `Throwable`, so an unregistered service makes the declaration a permanent silent no-op that looks
 * wired. A test that only checked `FirstPartyPlugins` contains the plugin would pass against
 * exactly that. Write a row, read it back, or the plane is not there.
 */
class WebPreferencePlaneTest {

    @Test
    fun aDeviceSettingRowSurvivesAWriteAndAReadBack() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val prefs = JsRuntime.tenshuClient.preferenceService
        val device = "web-pref-plane-test-device"

        val wrote = prefs.declareWantedThumbnailClasses(
            deviceId = device,
            classes = setOf(GetStoredThumbnail.CLASS_XXLARGE, GetStoredThumbnail.CLASS_XLARGE),
            beforeWrite = {},
        )
        assertTrue(wrote, "the first declaration writes the row — a false here means it was a no-op")

        val stored = prefs.readOnceForDevice(ThumbnailPreferences.WantedClasses, device)
        assertEquals(
            listOf(GetStoredThumbnail.CLASS_XXLARGE, GetStoredThumbnail.CLASS_XLARGE).sorted(),
            stored.sorted(),
            "the classes this browser draws must come back out of the row it just wrote",
        )
    }

    /** ⚠ Declaring twice writes nothing the second time, so a launch-time call costs no block. */
    @Test
    fun aSecondDeclarationOfTheSameClassesWritesNothing() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val prefs = JsRuntime.tenshuClient.preferenceService
        val device = "web-pref-plane-idempotence-device"
        val classes = setOf(GetStoredThumbnail.CLASS_XXLARGE)

        assertTrue(prefs.declareWantedThumbnailClasses(device, classes, beforeWrite = {}), "first call writes")
        assertEquals(
            false,
            prefs.declareWantedThumbnailClasses(device, classes, beforeWrite = {}),
            "a repeat declaration must not put a block on the wire every launch",
        )
    }
}
