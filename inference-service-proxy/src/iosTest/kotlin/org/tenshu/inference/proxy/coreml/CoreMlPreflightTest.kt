package org.tenshu.inference.proxy.coreml

import kotlinx.cinterop.ExperimentalForeignApi
import org.tenshu.inference.proxy.PreflightTower
import org.tenshu.inference.proxy.onnx.VisionModel
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The preflight's two iOS halves (`ios-on-device-inference.md` §14): its towers over models Core
 * ML really compiled and loaded, and its readings off this process.
 *
 * ⚠ The simulator is a Mac: no reading here is a phone's. What is under test is that each one is
 * taken at all, and that asking does not crash.
 */
@OptIn(ExperimentalForeignApi::class)
class CoreMlPreflightTest {

    private val fixtures = CoreMlFixtures()
    private val model = VisionModel.PinnedSigLip

    @AfterTest
    fun cleanUp() = fixtures.cleanUp()

    @Test
    fun itsTowersAreSessionsOverTheCompiledModelsAndCarryTheSessionsGuards() {
        // ⚠ By role, not by fixture name: the cache keeps a model under the first 16 characters of
        // its digest, and the stand-in digests of two names that both begin `fixture_` share them,
        // so preparing the second would remove the first.
        val vision: String = fixtures.compiled("fixture_vision", CoreMlTowerSpec.vision(model), CoreMlFixtures.digestFor("vision")) ?: return
        val text: String = fixtures.compiled("fixture_text", CoreMlTowerSpec.text(model), CoreMlFixtures.digestFor("text")) ?: return
        val towers = CoreMlPreflightTowers()

        val image: PreflightTower = towers.openVision(vision)
        val query: PreflightTower = towers.openText(text)
        assertEquals(model.dimensions, image.predict(FloatArray(3 * model.side * model.side) { 0.25f }).size)
        assertEquals(model.dimensions, query.predict(IntArray(model.textSequenceLength) { 1 }).size)
        image.close()
        query.close()

        // The shape guard is the session's own: a text tower is not a vision tower to probe.
        assertFails { towers.openVision(text) }
        assertFails { towers.openText(vision) }
        assertFails { towers.openVision("${fixtures.root}/nowhere.mlmodelc") }
    }

    @Test
    fun aMemoryWarningDuringAProbeIsCounted() {
        val before: Long = IosPreflightDevice.memoryWarnings()

        NSNotificationCenter.defaultCenter.postNotificationName(UIApplicationDidReceiveMemoryWarningNotification, null)

        assertEquals(before + 1, IosPreflightDevice.memoryWarnings())
    }

    @Test
    fun everyReadingIsTakenAndNoneCrashes() {
        assertTrue(IosPreflightDevice.thermalState() in 0..3)
        assertTrue(assertNotNull(IosPreflightDevice.footprintBytes(), "phys_footprint could not be read") > 0)
        assertTrue(IosPreflightDevice.nowEpochMs() > 1_700_000_000_000L)
        // ⚠ Looked up by name and called through a function pointer. Unknown here, or a figure.
        val available: Long? = IosPreflightDevice.availableBytes()
        println("PREFLIGHT-DEVICE available=$available footprint=${IosPreflightDevice.footprintBytes()} (a simulator process, not a phone)")
        assertTrue(available == null || available > 0)
    }
}
