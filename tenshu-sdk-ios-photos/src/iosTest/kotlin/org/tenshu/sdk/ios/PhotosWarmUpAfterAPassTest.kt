package org.tenshu.sdk.ios

import app.tenshu.control.di.PhotosOnDeviceAi
import org.tenshu.inference.proxy.PreflightMeasurement
import org.tenshu.inference.proxy.PreflightOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When a check of Photos' on-device AI goes on to warm the text tower
 * (`ios-on-device-inference.md` §14.17). ⚠ The decision only: that the
 * warm-up then asks for consent and opens nothing for a backend that does not serve is
 * `PhotosOnDeviceAiTest`'s.
 */
class PhotosWarmUpAfterAPassTest {

    private val passed = PreflightOutcome.Passed(PreflightMeasurement(thermalAtStart = 0, memoryWarned = false))

    @Test
    fun aCheckWarmsAtLaunchAndAfterAPass() {
        // ⚠ One probe a check, so one answer: when a tower was compiled a second time it is the
        // answer of the probe after that (`ModelPreparer.check`).
        assertTrue(PhotosOnDeviceAi.warmsAfter(launch = false, probed = passed))
        assertTrue(PhotosOnDeviceAi.warmsAfter(launch = true, probed = PreflightOutcome.NotAwaiting))
        assertFalse(PhotosOnDeviceAi.warmsAfter(launch = false, probed = PreflightOutcome.NotAwaiting))
        assertFalse(PhotosOnDeviceAi.warmsAfter(launch = false, probed = PreflightOutcome.Busy))
    }

    @Test
    fun aForegroundOfTheSceneIsOneNumberHoweverOftenItIsAnnounced() {
        PhotosLocalRuntime.modelTransfersSettleNow(inForeground = false)
        // ⚠ Not in the foreground: there is none to count a probe in.
        assertEquals(null, PhotosOnDeviceAi.foregroundEpoch())

        PhotosLocalRuntime.modelTransfersSettleNow(inForeground = true)
        val first: Long? = PhotosOnDeviceAi.foregroundEpoch()
        // ⚠⚠ Announced again (the scene phase reports `.active` more than once, and the first
        // appearance and the phase change both report it): the same foreground.
        PhotosLocalRuntime.modelTransfersSettleNow(inForeground = true)
        assertEquals(first, PhotosOnDeviceAi.foregroundEpoch(), "one foreground was counted as two")

        PhotosLocalRuntime.modelTransfersSettleNow(inForeground = false)
        assertEquals(null, PhotosOnDeviceAi.foregroundEpoch())
        PhotosLocalRuntime.modelTransfersSettleNow(inForeground = true)
        assertEquals(first!! + 1, PhotosOnDeviceAi.foregroundEpoch())
        PhotosLocalRuntime.modelTransfersSettleNow(inForeground = false)
    }
}
