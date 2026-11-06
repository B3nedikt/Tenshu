package app.tenshu.fit.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule behind "your phone stopped the recording" (fit-app.md §13.5c). The claim is only worth
 * making when the OS's own kill record lines up with *this* draft's last flush — the platform
 * supplies the timestamp, this decides whether it explains anything.
 */
class SystemKillExplainsTest {

    private val flushedAt = 1_786_793_600_000L

    @Test
    fun aKillMomentsAfterTheLastFlushExplainsIt() {
        // The real case: ColorOS killed the recorder 1.4 s after the last GPS fix.
        assertTrue(systemKillExplains(killAtWallMs = flushedAt + 1_400, interruptedAtWallMs = flushedAt))
    }

    @Test
    fun aKillOneFlushWindowLaterStillExplainsIt() {
        // Samples buffer for up to 15 s between flushes, so the kill can trail the last flush by
        // that much and still be the same event.
        assertTrue(systemKillExplains(flushedAt + 15_000, flushedAt))
        assertTrue(systemKillExplains(flushedAt + 119_000, flushedAt))
    }

    @Test
    fun anOldKillIsNeverPinnedOnThisDraft() {
        // The OS keeps 16 records; yesterday's kill is still in there and explains nothing here.
        // ⚠ This is the case a symmetric window would get wrong.
        assertFalse(systemKillExplains(flushedAt - 60_000, flushedAt))
        assertFalse(systemKillExplains(flushedAt - 86_400_000, flushedAt))
    }

    @Test
    fun aKillLongAfterCaptureStoppedExplainsNothing() {
        // The user reopened hours later and the process died again in between — that death did not
        // end this recording.
        assertFalse(systemKillExplains(flushedAt + 3_600_000, flushedAt))
    }

    @Test
    fun clockJitterAroundTheFlushIsTolerated() {
        assertTrue(systemKillExplains(flushedAt - 2_000, flushedAt))
    }

    @Test
    fun noEvidenceMeansNoClaim() {
        // No kill on record (a crash, a force-stop, or an API below 30), or a draft that never
        // dated itself: the review screen keeps its neutral wording.
        assertFalse(systemKillExplains(null, flushedAt))
        assertFalse(systemKillExplains(flushedAt, null))
        assertFalse(systemKillExplains(null, null))
    }
}
