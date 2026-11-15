package org.tenshu.ui.test

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NoMotionTest {

    @Test
    fun aTweenSnapsToItsTargetOnTheFirstFrame() = runComposeUiTest(effectContext = NoMotion) {
        assertEquals(1f, valueOneFrameAfterRetargeting())
    }

    /** The control: without [NoMotion] the same tween is still mid-flight one frame in. */
    @Test
    fun withoutItTheSameTweenIsStillInFlight() = runComposeUiTest {
        assertTrue(valueOneFrameAfterRetargeting() < 1f)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.valueOneFrameAfterRetargeting(): Float {
        var target by mutableStateOf(0f)
        var observed = -1f
        setContent {
            val value by animateFloatAsState(target, tween(durationMillis = TWEEN_MS))
            observed = value
        }
        mainClock.autoAdvance = false
        target = 1f
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        return observed
    }

    private companion object {
        const val TWEEN_MS = 1_000
    }
}
