package app.tenshu.timeline.data.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The watchdog's restart policy — restart iff opted-in AND the service is dead. */
class CaptureWatchdogTest {

    @Test
    fun restartsWhenOptedInButServiceDead() {
        // The whole point: OEM killed the process, opt-in still on → bring it back.
        val v = decideCaptureRestart(CaptureWatchdogState(captureEnabled = true, serviceRunning = false))
        assertTrue(v.restart, v.reason)
    }

    @Test
    fun doesNothingWhenServiceAlreadyRunning() {
        val v = decideCaptureRestart(CaptureWatchdogState(captureEnabled = true, serviceRunning = true))
        assertEquals(false, v.restart)
    }

    @Test
    fun doesNothingWhenOptedOut() {
        // Never resurrect capture the user turned off — even if a stale service reading says dead.
        assertEquals(false, decideCaptureRestart(CaptureWatchdogState(captureEnabled = false, serviceRunning = false)).restart)
        assertEquals(false, decideCaptureRestart(CaptureWatchdogState(captureEnabled = false, serviceRunning = true)).restart)
    }
}
