package app.tenshu.fit.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pacer's job is to grow its pause as the import runs deeper — that "more backpressure when the
 * proxy is under more pressure" shape is the whole reason a 13,500-record import can finish instead
 * of OOMing Control. These pin the shape without a clock (only `onRecord` suspends).
 */
class ImportPacerTest {

    private val pacer = ImportPacer()

    @Test
    fun theDelayGrowsWithHowFarTheImportHasRun() {
        val early = pacer.delayForTotal(50)
        val middle = pacer.delayForTotal(5_000)
        val late = pacer.delayForTotal(13_000)
        assertTrue(early < middle, "pressure builds, so the pause after 5k records exceeds the pause after 50")
        assertTrue(middle < late, "and keeps building toward the end of a big import")
    }

    @Test
    fun theDelayIsCappedSoItNeverStalls() {
        // Even a pathologically huge import must not pause for minutes at a time.
        assertEquals(pacer.delayForTotal(Int.MAX_VALUE), pacer.delayForTotal(1_000_000))
        assertTrue(pacer.delayForTotal(Int.MAX_VALUE) <= 400)
    }

    @Test
    fun aSmallImportBarelyPacesAtAll() {
        // The Health Connect import (a handful of records) should feel no backpressure worth noticing.
        assertTrue(pacer.delayForTotal(4) <= 50, "a tiny import stays near the base delay")
    }
}
