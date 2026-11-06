package app.tenshu.timeline.data.capture

import app.tenshu.timeline.data.ImportedTimeline
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The writer's durability contract (tech-debt "silent visit loss before onboarding"): a failed
 * or hung write never throws into the capture engine, retains the journalled entry, and a later
 * write/drain replays pending entries oldest-first.
 */
class CapturedVisitWriterOutboxTest {

    private val visit = CapturedVisit(
        latE7 = 525_163_000,
        lngE7 = 133_780_000,
        arrivalMs = 1_000_000,
        departureMs = 1_400_000,
    )
    private val laterVisit = visit.copy(latE7 = 525_200_000, arrivalMs = 2_000_000, departureMs = 2_400_000)

    private fun writer(
        outbox: CapturedVisitOutbox?,
        writeTimeoutMs: Long = 30_000,
        writeTimeline: suspend (ImportedTimeline) -> Unit,
    ) = CapturedVisitWriter(
        config = VisitDetectionConfig(),
        enricher = null,
        outbox = outbox,
        writeTimeoutMs = writeTimeoutMs,
        writeTimeline = writeTimeline,
        writesHeld = { false },
    )

    /**
     * ⚠⚠ ADR 0025 §Decision 8.6. A Control with no realm does NOT fail the write — it accepts it
     * into a store that belongs to no realm — so the "a failed write is retained" contract above
     * never engaged for that case. While the gate holds, nothing is attempted and the visit stays
     * journalled; the drain after the gate opens writes it.
     */
    @Test
    fun aHeldGateJournalsWithoutWritingAndTheLaterDrainWrites() = runTest {
        val outbox = CapturedVisitOutbox(FakeVisitOutboxStore())
        var held = true
        val written = mutableListOf<ImportedTimeline>()
        val writer = CapturedVisitWriter(
            config = VisitDetectionConfig(),
            enricher = null,
            outbox = outbox,
            writeTimeoutMs = 30_000,
            writeTimeline = { written.add(it) },
            writesHeld = { held },
        )

        writer.write(visit)
        writer.drainOutbox()
        assertTrue(written.isEmpty(), "no write with no realm")
        assertEquals(1, outbox.pending().size, "the visit is journalled, not lost")

        held = false
        writer.drainOutbox()
        assertEquals(1, written.size, "the held visit lands once the gate opens")
        assertTrue(outbox.pending().isEmpty())
    }

    /** ⚠⚠ Codex P1 on fde421540: the gate is re-checked before every entry, not once per drain. */
    @Test
    fun aGateClosingMidDrainStopsBeforeTheNextEntry() = runTest {
        val outbox = CapturedVisitOutbox(FakeVisitOutboxStore())
        var held = true
        val written = mutableListOf<ImportedTimeline>()
        val writer = CapturedVisitWriter(
            config = VisitDetectionConfig(), enricher = null, outbox = outbox, writeTimeoutMs = 30_000,
            // The realm becomes known absent while the first entry is being written.
            writeTimeline = { written.add(it); held = true },
            writesHeld = { held },
        )
        writer.write(visit)
        writer.write(laterVisit)
        assertEquals(2, outbox.pending().size)

        held = false
        writer.drainOutbox()
        assertEquals(1, written.size, "stopped before the second entry")
        assertEquals(1, outbox.pending().size, "the unwritten entry stays journalled")
    }

    /**
     * Codex P2 on fde421540: a release-edge drain may land while the initial drain is still running.
     * Two writers sharing one outbox (the capture engine's and the app-start one) are serialised by
     * the outbox's own lock — each entry is written exactly once.
     */
    @Test
    fun concurrentDrainsOfOneOutboxNeverWriteAnEntryTwice() = runTest {
        val outbox = CapturedVisitOutbox(FakeVisitOutboxStore())
        val firstWriteMayFinish = kotlinx.coroutines.CompletableDeferred<Unit>()
        val written = mutableListOf<ImportedTimeline>()
        fun sharing() = CapturedVisitWriter(
            config = VisitDetectionConfig(), enricher = null, outbox = outbox, writeTimeoutMs = 30_000,
            writeTimeline = { if (written.isEmpty()) firstWriteMayFinish.await(); written.add(it) },
            writesHeld = { false },
        )
        // Journal two entries without draining them.
        var held = true
        val journaller = CapturedVisitWriter(
            config = VisitDetectionConfig(), enricher = null, outbox = outbox, writeTimeoutMs = 30_000,
            writeTimeline = { error("not reached") }, writesHeld = { held },
        )
        journaller.write(visit)
        journaller.write(laterVisit)

        val initial = launch { sharing().drainOutbox() }
        runCurrent() // the initial drain is now blocked inside its first write
        val followUp = launch { sharing().drainOutbox() }
        runCurrent()
        firstWriteMayFinish.complete(Unit)
        initial.join(); followUp.join()

        assertEquals(2, written.size, "each journalled entry written exactly once")
        assertTrue(outbox.pending().isEmpty())
    }

    @Test
    fun failedWriteNeverThrowsAndRetainsTheEntry() = runTest {
        val outbox = CapturedVisitOutbox(FakeVisitOutboxStore())
        val writer = writer(outbox) { error("proxy has no realm") }

        writer.write(visit) // must not throw — the engine/FGS stays alive

        assertEquals(1, outbox.pending().size)
    }

    @Test
    fun hungWriteTimesOutAndRetainsTheEntry() = runTest {
        val outbox = CapturedVisitOutbox(FakeVisitOutboxStore())
        val writer = writer(outbox, writeTimeoutMs = 100) { awaitCancellation() }

        writer.write(visit) // virtual time advances past the timeout; must not throw

        assertEquals(1, outbox.pending().size)
    }

    @Test
    fun recoveredProxyDrainsPendingOldestFirstThenTheNewVisit() = runTest {
        val outbox = CapturedVisitOutbox(FakeVisitOutboxStore())
        var healthy = false
        val written = mutableListOf<ImportedTimeline>()
        val writer = writer(outbox) { if (healthy) written.add(it) else error("down") }

        writer.write(visit)
        assertEquals(1, outbox.pending().size)

        healthy = true
        writer.write(laterVisit)

        // Both landed — stranded entry first, then the new one — and the outbox is empty.
        assertEquals(2, written.size)
        assertEquals(1, written[0].places.size)
        assertTrue(outbox.pending().isEmpty())
        assertTrue(written[0].moments.single().timestampMs < written[1].moments.single().timestampMs)
    }

    @Test
    fun drainStopsAtFirstFailureAndKeepsTheRest() = runTest {
        val outbox = CapturedVisitOutbox(FakeVisitOutboxStore())
        var calls = 0
        val writer = writer(outbox) { calls++; error("still down") }

        writer.write(visit)
        writer.write(laterVisit)

        // Second write drains from the oldest entry and stops at its failure — the dead proxy
        // is not hammered once per pending entry.
        assertEquals(2, outbox.pending().size)
        assertEquals(2, calls) // one attempt per write trigger, not per entry
    }

    @Test
    fun successfulWriteLeavesNothingPending() = runTest {
        val outbox = CapturedVisitOutbox(FakeVisitOutboxStore())
        val written = mutableListOf<ImportedTimeline>()
        val writer = writer(outbox) { written.add(it) }

        writer.write(visit)

        assertEquals(1, written.size)
        assertTrue(outbox.pending().isEmpty())
    }

    @Test
    fun legacyPathWithoutOutboxStillNeverThrows() = runTest {
        val writer = writer(outbox = null) { error("down") }
        writer.write(visit) // loss (pre-fix behaviour) but no engine kill
    }
}
