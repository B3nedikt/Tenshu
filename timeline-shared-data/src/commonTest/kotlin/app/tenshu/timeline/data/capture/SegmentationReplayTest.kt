package app.tenshu.timeline.data.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Device-free coverage of the **replay instrument** and the **raw-fix corpus** it needs
 * (`docs/proposals/timeline-movement-capture.md` §18.9).
 *
 * ⚠ The point of these tests is that the instrument must be *trustworthy about its own limits*: a
 * replay that reported unknowns as passes, or that silently had no corpus, would be worse than no
 * replay — it would produce a number that reads like evidence and is not.
 */
class SegmentationReplayTest {

    private val t0 = 1_800_000_000_000L
    private val min = 60_000L
    private val originLat = 525_000_000L
    private val originLng = 134_000_000L

    private fun latE7(m: Double): Long = (m / 0.011132).roundToLong()
    private fun lngE7(m: Double): Long = (m / (0.011132 * cos(52.5 * PI / 180.0))).roundToLong()

    private fun at(north: Double, east: Double, minute: Int, accuracy: Int) = CapturedFix(
        latE7 = originLat + latE7(north),
        lngE7 = originLng + lngE7(east),
        timestampMs = t0 + minute * min,
        accuracyM = accuracy,
    )

    private fun trace(id: String, path: List<CapturedFix>, mode: String = "walk") =
        SegmentationReplay.StoredTracePath(
            traceId = id,
            startMs = path.firstOrNull()?.timestampMs ?: t0,
            mode = mode,
            storedDistanceM = path.pathDistanceMeters().roundToLong(),
            rawPointCount = path.size.toLong(),
            path = path,
        )

    /** A stored phantom: long summed path, tiny extent, coarse fixes — §18.3's shape. */
    private fun phantomPath(): List<CapturedFix> = (0..11).map { i ->
        val n = listOf(0.0, 60.0, -50.0, 40.0, -70.0, 30.0, -40.0, 55.0, -35.0, 45.0, -60.0, 20.0)[i]
        val e = listOf(0.0, -45.0, 55.0, -60.0, 25.0, 50.0, -30.0, 40.0, -55.0, 20.0, 35.0, -25.0)[i]
        at(n, e, i, accuracy = 150)
    }

    /** A real walk: 500 m out, clean fixes. */
    private fun realWalkPath(): List<CapturedFix> = (0..10).map { at(it * 50.0, 0.0, it, accuracy = 10) }

    // ── corpus 1: stored traces ────────────────────────────────────────────────

    @Test
    fun aStoredPhantomIsIdentifiedAndARealWalkIsNot() {
        val report = SegmentationReplay.replayStoredTraces(
            listOf(trace("phantom", phantomPath()), trace("walk", realWalkPath())),
        )

        assertEquals(2, report.examined)
        assertEquals(listOf("phantom"), report.rejected.map { it.trace.traceId })

        // Precondition, so this cannot pass by rejecting everything: the phantom really does clear
        // the pre-S6 distance bar — it is only the extent gate that catches it.
        val phantom = phantomPath()
        assertTrue(phantom.pathDistanceMeters() > MovementDetectionConfig().minMoveDistanceM)
        assertNotNull(phantom.toCapturedMoveOrNull(SegmentationReplay.preS6MoveConfig))
    }

    @Test
    fun theVerdictCarriesTheNumbersItRestedOn() {
        val report = SegmentationReplay.replayStoredTraces(listOf(trace("p", phantomPath())))
        val v = report.rejected.single()
        // ⚠ The bar is read through the SAME function the live gate uses (`extentFloorFor`), which is
        // why this can be asserted as an identity rather than re-derived here — a re-derivation would
        // keep passing if the shipped rule drifted.
        assertEquals(MovementDetectionConfig().extentFloorFor(phantomPath()), v.extentFloorM)
        assertEquals(phantomPath().pathExtentMeters(), v.extentM)
        assertTrue(v.meanAccuracyM > 100.0, "the fixture's coarse accuracy must reach the verdict")
    }

    @Test
    fun anUndecidableTraceIsCountedNotSilentlyPassed() {
        // ⚠⚠ A one-point polyline cannot be judged. Reporting it as "not rejected" would inflate the
        // instrument's apparent coverage — the one failure mode a measuring tool must not have.
        val report = SegmentationReplay.replayStoredTraces(
            listOf(trace("stub", listOf(at(0.0, 0.0, 0, 50))), trace("phantom", phantomPath())),
        )
        assertEquals(1, report.undecidable)
        assertEquals(1, report.rejectedCount)
        assertEquals(2, report.examined)
    }

    @Test
    fun storedDistanceIsCarriedThroughRatherThanRecomputed() {
        // ⚠ The stored figure is the RAW path length; the polyline is decimated. Recomputing from the
        // decoded path would under-report, which is exactly the mistake §18.9 warns the replay away
        // from — so the report must surface what capture measured, not what survived decimation.
        val decimated = listOf(at(0.0, 0.0, 0, 150), at(30.0, 0.0, 4, 150), at(0.0, 0.0, 8, 150))
        val stored = trace("t", decimated).copy(storedDistanceM = 4_000)
        val v = SegmentationReplay.replayStoredTraces(listOf(stored)).rejected.single()
        assertEquals(4_000, v.trace.storedDistanceM)
    }

    // ── corpus 2: raw fixes ────────────────────────────────────────────────────

    @Test
    fun replayingRawFixesShowsBothHalvesOfS6() {
        // A stationary ±150 m day: the old rule fragments it and mints a phantom; the new one does not.
        val settled = (0..8).map { at(it * 7.0 - 28.0, (it % 3) * 20.0 - 20.0, it, accuracy = 150) }
        val scatter = (9..14).map { at(if (it % 2 == 0) 120.0 else -120.0, 0.0, it, accuracy = 150) }
        val settledB = (15..23).map { at((it - 19) * 6.0, (it % 3) * 15.0 - 15.0, it, accuracy = 150) }
        val fixes = settled + scatter + settledB + at(600.0, 0.0, 30, 150)

        val report = SegmentationReplay.replayRawFixes(fixes)

        assertEquals(fixes.size, report.fixCount)
        assertTrue(report.before.dwellCount > report.after.dwellCount, "S6a must merge fragments")
        assertEquals(1, report.after.dwellCount)
        assertTrue(report.movesSuppressed > 0, "S6b must suppress the phantom the old rule recorded")
        assertEquals(0, report.after.moveCount)
    }

    @Test
    fun replayIsNonDestructiveOfTheLiveJournal() {
        // ⚠ A diagnostic that corrupted the in-progress journey would be worse than no diagnostic.
        // The replay builds its segmenters with OpenMoveJournal.None, so a bound journal is untouched.
        val spy = RecordingOpenMoveJournal()
        SegmentationReplay.replayRawFixes(realWalkPath())
        assertEquals(0, spy.checkpoints, "replay must not write through any journal")
        assertFalse(spy.cleared)
    }

    private class RecordingOpenMoveJournal : OpenMoveJournal {
        var checkpoints = 0
        var cleared = false
        override fun read() = emptyList<CapturedFix>()
        override fun checkpoint(path: List<CapturedFix>) { checkpoints++ }
        override fun clear() { cleared = true }
    }

    // ── the raw-fix journal itself ─────────────────────────────────────────────

    private class FakeControl(initial: Boolean) : RawFixJournalControl {
        private val state = MutableStateFlow(initial)
        override val enabled: StateFlow<Boolean> = state
        override fun setEnabled(enabled: Boolean) { state.value = enabled }
    }

    private class MemoryLog : RawFixLog {
        val entries = mutableListOf<String>()
        override fun append(line: String) { entries += line }
        override fun lines(): List<String> = entries.toList()
        override fun clear() { entries.clear() }
        override fun lineCount(): Int = entries.size
    }

    @Test
    fun theJournalRecordsNothingUntilConsented() {
        val log = MemoryLog()
        val control = FakeControl(initial = false)
        val journal = LoggingRawFixJournal(log, control)

        journal.record(at(0.0, 0.0, 0, 20))
        assertEquals(0, log.entries.size, "off by default — no consent, no corpus")

        // ⚠ Consent is re-read per fix, not latched at construction: the user can flip the switch
        // mid-session and a latched journal would keep writing after it went off.
        control.setEnabled(true)
        journal.record(at(0.0, 0.0, 1, 20))
        assertEquals(1, log.entries.size)
        control.setEnabled(false)
        journal.record(at(0.0, 0.0, 2, 20))
        assertEquals(1, log.entries.size, "flipping consent off must stop recording immediately")
    }

    @Test
    fun anExistingCorpusStaysReadableAfterConsentIsWithdrawn() {
        // ⚠⚠ Turning recording off must not HIDE what is already on disk — a screen that reports an
        // empty corpus while the file exists is the worst outcome: the data is there and the one
        // control that could delete it says there is nothing to delete.
        val log = MemoryLog()
        val control = FakeControl(initial = true)
        val journal = LoggingRawFixJournal(log, control)
        journal.record(at(0.0, 0.0, 0, 20))

        control.setEnabled(false)
        assertEquals(1, journal.readAll().size)
        assertEquals(1, journal.status().fixCount)

        journal.clear()
        assertEquals(0, journal.readAll().size)
    }

    @Test
    fun theJournalStopsAtTheCapRatherThanRotating() {
        val log = MemoryLog()
        val journal = LoggingRawFixJournal(log, FakeControl(true), maxFixes = 3)
        repeat(5) { journal.record(at(it.toDouble(), 0.0, it, accuracy = 20)) }

        assertEquals(3, log.entries.size)
        assertTrue(journal.status().capped)
        // ⚠ The OLDEST fixes survive, not the newest. A ring buffer would silently slide the window
        // and make a replay result unattributable to a period — see the RawFixJournal KDoc.
        assertEquals(at(0.0, 0.0, 0, 20), RawFixCodec.decode(log.entries.first()))
    }

    @Test
    fun fixesRoundTripThroughTheCodecAndATornLineIsDropped() {
        val fix = at(123.0, -45.0, 7, accuracy = 33)
        assertEquals(fix, RawFixCodec.decode(RawFixCodec.encode(fix)))
        // A crash mid-append leaves a partial final line; it must not poison the whole corpus.
        assertNull(RawFixCodec.decode("525000000,134000"))
        assertNull(RawFixCodec.decode(""))

        val log = MemoryLog()
        val journal = LoggingRawFixJournal(log, FakeControl(true))
        journal.record(fix)
        log.entries += "525000000,1340" // torn tail
        assertEquals(listOf(fix), journal.readAll())
    }
}
