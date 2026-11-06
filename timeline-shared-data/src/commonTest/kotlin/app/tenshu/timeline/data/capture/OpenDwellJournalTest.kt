package app.tenshu.timeline.data.capture

import app.tenshu.outbox.OutboxEntry
import app.tenshu.outbox.OutboxStore
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **§29 — one stay, one row, across a process death.**
 *
 * A dwell is emitted on *confirmation* (`ongoing = true`, duration ≈ `minDwellMs`) and corrected on
 * *departure*. A process killed in between therefore leaves a row **stuck at five minutes**, and the
 * next process — starting with no anchor — mints a fresh `cap-visit:<placeId>:<arrivalMs>` and so a
 * **second row for the same stay**. Measured on a real device across a day of OEM kills: eight
 * `dwell_ongoing` events against one `dwell_closed`.
 *
 * ⚠ The open *move* has survived process death since M3; the open *dwell* never did. These cases pin
 * the two halves of that asymmetry: the stay keeps its identity, and it keeps growing.
 */
class OpenDwellJournalTest {

    private val t0 = 1_800_000_000_000L
    private val originLat = 481_400_000L
    private val originLng = 115_800_000L

    private fun latE7(m: Double): Long = (m / 0.011132).roundToLong()

    private fun fix(northM: Double, sec: Int) = CapturedFix(
        latE7 = originLat + latE7(northM),
        lngE7 = originLng,
        timestampMs = t0 + sec * 1000L,
        accuracyM = 12,
    )

    /** An in-memory [OutboxStore] — the same fake shape `OpenMoveJournalTest` uses. */
    private class MemoryStore : OutboxStore {
        private val rows = LinkedHashMap<String, String>()
        override fun append(id: String, payload: String) { rows[id] = payload }
        override fun entries(): List<OutboxEntry> = rows.map { OutboxEntry(it.key, it.value) }
        override fun remove(id: String) { rows.remove(id) }
    }

    private fun dwellJournal(store: OutboxStore) =
        JsonOpenMoveJournal(store, entryId = JsonOpenMoveJournal.DWELL_ENTRY_ID)

    /** Sitting still: fixes a minute apart, none of them moving. */
    private fun sitting(fromSec: Int, count: Int) = (0 until count).map { fix(0.0, fromSec + it * 60) }

    private fun visitsOf(events: List<CaptureEvent>) = events.filterIsInstance<CaptureEvent.Dwell>()

    @Test
    fun aStayResumedAfterAKillKeepsItsArrivalAndSoItsIdentity() {
        val store = MemoryStore()
        val killed = MovementSegmenter(dwellJournal = dwellJournal(store))
        val firstHalf = sitting(0, 8).flatMap { killed.onFix(it) } // crosses minDwellMs, confirms
        assertTrue(visitsOf(firstHalf).isNotEmpty(), "precondition: the stay was confirmed")
        val arrival = visitsOf(firstHalf).first().visit.arrivalMs

        // The process dies here — no onEnd, so the journal survives, exactly as a kill leaves it.
        val resumed = MovementSegmenter(dwellJournal = dwellJournal(store))
        val secondHalf = sitting(480, 8).flatMap { resumed.onFix(it) }

        val after = visitsOf(secondHalf)
        assertTrue(after.isNotEmpty(), "the resumed stay must still emit")
        assertEquals(
            arrival,
            after.first().visit.arrivalMs,
            "same arrival ⇒ same content-addressed id ⇒ one row, not two",
        )
    }

    @Test
    fun theDepartureAfterAResumeSpansTheWholeStayNotJustTheSecondHalf() {
        // ⚠ The other half of the bug. An open dwell's duration is corrected only on **departure**
        // (§11.2 — the live count-up is device-local and deliberately not re-synced), so a stay whose
        // departure never runs stays stuck at `minDwellMs`. Resuming the cluster is what lets the
        // eventual departure account for the *whole* stay rather than only the part after the kill.
        val store = MemoryStore()
        val killed = MovementSegmenter(dwellJournal = dwellJournal(store))
        sitting(0, 8).forEach { killed.onFix(it) }             // ~7 min, then the process dies

        val resumed = MovementSegmenter(dwellJournal = dwellJournal(store))
        sitting(480, 20).forEach { resumed.onFix(it) }         // ~19 min more, same place
        val departure = visitsOf((0 until 8).flatMap { resumed.onFix(fix(300.0 + it * 300.0, 2_200 + it * 60)) })
            .filterNot { it.ongoing }

        assertTrue(departure.isNotEmpty(), "walking away must close the stay")
        val span = departure.first().visit.let { it.departureMs - it.arrivalMs }
        assertTrue(
            span > 20 * 60_000L,
            "the closed stay should span both halves, got ${span / 60_000} min",
        )
    }

    @Test
    fun withoutTheJournalTheStaySplits() {
        // The twin that makes the above evidence: identical input, no journal, two arrivals.
        val killed = MovementSegmenter()
        val first = visitsOf(sitting(0, 8).flatMap { killed.onFix(it) })
        val resumed = MovementSegmenter()
        val second = visitsOf(sitting(480, 8).flatMap { resumed.onFix(it) })

        assertTrue(first.isNotEmpty() && second.isNotEmpty())
        assertTrue(
            first.first().visit.arrivalMs != second.first().visit.arrivalMs,
            "pre-§29 behaviour: a fresh segmenter mints a new stay",
        )
    }

    @Test
    fun aStaleRecordIsNotResumed() {
        // ⚠ Beyond `maxResumeGapMs` we cannot claim continuity — the user may have left and returned,
        // and asserting one unbroken stay would be a worse error than an extra row.
        val store = MemoryStore()
        val killed = MovementSegmenter(dwellJournal = dwellJournal(store))
        val first = visitsOf(sitting(0, 8).flatMap { killed.onFix(it) })

        val resumed = MovementSegmenter(dwellJournal = dwellJournal(store))
        val second = visitsOf(sitting(60 * 60, 8).flatMap { resumed.onFix(it) }) // an hour later

        assertTrue(second.isEmpty() || second.first().visit.arrivalMs != first.first().visit.arrivalMs)
    }

    @Test
    fun aRecordFromSomewhereElseIsNotResumed() {
        // ⚠ The user really did leave: the new fix does not hold in the recovered cluster's anchor.
        val store = MemoryStore()
        val killed = MovementSegmenter(dwellJournal = dwellJournal(store))
        val first = visitsOf(sitting(0, 8).flatMap { killed.onFix(it) })

        val resumed = MovementSegmenter(dwellJournal = dwellJournal(store))
        val elsewhere = (0 until 8).map { fix(5_000.0, 480 + it * 60) } // 5 km away
        val second = visitsOf(elsewhere.flatMap { resumed.onFix(it) })

        assertTrue(second.isEmpty() || second.first().visit.arrivalMs != first.first().visit.arrivalMs)
    }

    @Test
    fun aClosedStayLeavesNoRecordBehind() {
        val store = MemoryStore()
        val seg = MovementSegmenter(dwellJournal = dwellJournal(store))
        sitting(0, 8).forEach { seg.onFix(it) }
        // Walk away: the cluster closes.
        (0 until 8).forEach { seg.onFix(fix(300.0 + it * 300.0, 600 + it * 60)) }

        assertTrue(
            dwellJournal(store).read().isEmpty(),
            "a departed stay must not be resumable by the next process",
        )
    }
}
