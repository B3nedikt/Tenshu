package app.tenshu.timeline.data.capture

import app.tenshu.outbox.OutboxEntry
import app.tenshu.outbox.OutboxStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The crash-recovery proof** for the open-move journal
 * (`docs/proposals/timeline-movement-capture.md` §12.0 — "capture loss is permanent").
 *
 * Process death is simulated the way it actually happens: a [MovementSegmenter] is fed part of a
 * journey and then simply **abandoned** — no `onEnd`, no flush, exactly what an OEM force-kill or an
 * iOS suspension does — and a *new* segmenter is built over the same durable store, as the next
 * capture start would. The assertions are the two that matter: the leg survives, and it is not
 * duplicated when it had already been flushed.
 *
 * The store is an in-memory [OutboxStore] fake, so the real [JsonOpenMoveJournal] (and therefore the
 * real JSON round-trip of every fix) is under test — only the platform's file I/O is stubbed.
 */
class OpenMoveJournalTest {

    private val t0 = 1_800_000_000_000L
    private val min = 60_000L
    private val originLat = 525_000_000L
    private val originLng = 134_000_000L

    /** A straight run of fixes, one per [dMs], stepping [dLatE7] north each time. */
    private fun line(count: Int, startLat: Long, startMs: Long, dLatE7: Long = 12_000, dMs: Long = min) =
        (0 until count).map { CapturedFix(startLat + it * dLatE7, originLng, startMs + it * dMs) }

    private fun moves(events: List<CaptureEvent>) = events.filterIsInstance<CaptureEvent.Move>().map { it.move }

    /** In-memory [OutboxStore] — survives the "process death" (a new segmenter) the way a file does. */
    private class MemoryStore : OutboxStore {
        val rows = LinkedHashMap<String, String>()
        var appends = 0
        override fun append(id: String, payload: String) {
            appends++
            rows[id] = payload
        }
        override fun entries(): List<OutboxEntry> = rows.map { OutboxEntry(it.key, it.value) }
        override fun remove(id: String) {
            rows.remove(id)
        }
    }

    // ── The bug this fixes ────────────────────────────────────────────────────

    @Test
    fun withoutAJournalAKilledWalkIsLostEntirely() {
        // The shipped behaviour, pinned so the fix's value stays legible: 20 minutes of walking,
        // killed mid-journey, emit nothing at all — the buffer only ever existed in the dead heap.
        val killed = MovementSegmenter()
        val emitted = line(count = 20, startLat = originLat, startMs = t0).flatMap { killed.onFix(it) }
        assertEquals(emptyList(), moves(emitted))

        // A fresh segmenter has nothing to pick up: whatever it goes on to record begins at the
        // restart, and the first 20 minutes are unrecoverable — no re-run can produce them.
        val resumed = MovementSegmenter()
        val after = line(count = 6, startLat = originLat + 20 * 12_000, startMs = t0 + 20 * min)
            .flatMap { resumed.onFix(it) } + resumed.onEnd()
        assertEquals(t0 + 20 * min, moves(after).single().startMs)
    }

    // ── Recovery ──────────────────────────────────────────────────────────────

    @Test
    fun aKilledJourneyResumesAsOneWholeLeg() {
        val store = MemoryStore()

        // 20 minutes of walking, then the process is killed: no onEnd, no flush, nothing written.
        val killed = MovementSegmenter(journal = JsonOpenMoveJournal(store))
        val beforeDeath = line(count = 20, startLat = originLat, startMs = t0).flatMap { killed.onFix(it) }
        assertEquals(0, moves(beforeDeath).size, "the leg is still open at the moment of death")
        assertTrue(store.rows.isNotEmpty(), "the open path must be durable before the kill")

        // Capture restarts a minute later and the walk continues to a place where the user dwells.
        val resumed = MovementSegmenter(journal = JsonOpenMoveJournal(store))
        val walkOn = line(count = 5, startLat = originLat + 20 * 12_000, startMs = t0 + 20 * min)
        val placeLat = originLat + 25 * 12_000 + 13_000
        val dwell = (0..3).map { CapturedFix(placeLat + it * 500, originLng, t0 + (25 + it * 2) * min) }
        val after = (walkOn + dwell).flatMap { resumed.onFix(it) }

        val move = moves(after).single()
        assertEquals(t0, move.startMs, "the recovered leg must start at the *pre-death* first fix")
        assertEquals(MovementMode.WALK, move.mode)
        // 20 pre-death fixes + 5 after + the dwell endpoint = 26; a kill costs at most a stride's
        // worth of tail (the journal is checkpointed every 3rd fix), never the leg.
        assertTrue(move.fixes.size >= 24, "expected ≥24 of 26 fixes, got ${move.fixes.size}")
    }

    @Test
    fun recoveryPreservesRawFixesNotADecimatedPath() {
        // classifyMode + distanceM are computed over the RAW path (§4). A journal that stored the
        // decimated polyline would look fine here — until the numbers were re-derived over a
        // different geometry. A straight line is the adversarial case: Douglas–Peucker collapses it
        // to its two endpoints, so a decimated journal could not possibly satisfy these counts.
        val whole = line(count = 30, startLat = originLat, startMs = t0, dLatE7 = 200_000) // a drive
        val uninterrupted = MovementSegmenter().let { s -> whole.flatMap { s.onFix(it) } + s.onEnd() }

        val store = MemoryStore()
        MovementSegmenter(journal = JsonOpenMoveJournal(store)).also { killed ->
            whole.take(19).forEach { killed.onFix(it) } // killed here — no onEnd, no flush
        }
        val resumed = MovementSegmenter(journal = JsonOpenMoveJournal(store))
        val recovered = moves(whole.drop(19).flatMap { resumed.onFix(it) } + resumed.onEnd()).single()

        assertTrue(recovered.fixes.size >= whole.size - 2, "raw fixes, not a simplified path")
        assertTrue(recovered.fixes.all { it in whole }, "no synthesised or interpolated points")
        assertEquals(recovered.fixes, recovered.fixes.sortedBy { it.timestampMs }, "still time-ordered")
        // Same journey, same verdict: the classifier must not see the resumed leg differently.
        assertEquals(moves(uninterrupted).single().classification.mode, recovered.classification.mode)
        assertTrue(recovered.distanceM > moves(uninterrupted).single().distanceM * 0.9)
    }

    @Test
    fun aFlushedLegIsNotResurrectedAfterAKill() {
        val store = MemoryStore()
        val journal = JsonOpenMoveJournal(store)

        // Walk → dwell: the leg flushes and is handed to the writer. Then the user sits, and the
        // process is killed while they are still sitting there.
        val killed = MovementSegmenter(journal = journal)
        val walk = line(count = 15, startLat = originLat, startMs = t0)
        val placeLat = originLat + 15 * 12_000 + 13_000
        val dwell = (0..4).map { CapturedFix(placeLat + it * 500, originLng, t0 + (15 + it * 2) * min) }
        val flushed = (walk + dwell).flatMap { killed.onFix(it) }
        assertEquals(1, moves(flushed).size, "the walk flushed into the dwell")
        val firstLeg = moves(flushed).single()

        // Restart: whatever the journal holds must not re-open the leg that was already recorded.
        val resumed = MovementSegmenter(journal = journal)
        val departure = line(count = 6, startLat = placeLat + 300_000, startMs = t0 + 25 * min)
        val after = departure.flatMap { resumed.onFix(it) } + resumed.onEnd()

        val second = moves(after).single()
        assertTrue(
            second.startMs > firstLeg.endMs,
            "the post-restart leg must start after the flushed one, not re-emit it",
        )
        assertTrue(second.fixes.none { it.timestampMs < t0 + 15 * min })
    }

    @Test
    fun aLegFlushedOnTheVeryLastFixBeforeTheKillIsNotResurrected() {
        // The narrow window the flush-time checkpoint exists for: the kill lands on the *confirming*
        // fix itself, before any cadence checkpoint could have caught up. If the journal still held
        // the pre-flush path, the restart would re-open the already-recorded walk and stitch the
        // next journey onto it — one merged row addressed by the old leg's startMs.
        val store = MemoryStore()
        val journal = JsonOpenMoveJournal(store)

        val killed = MovementSegmenter(journal = journal)
        val walk = line(count = 15, startLat = originLat, startMs = t0)
        val placeLat = originLat + 15 * 12_000 + 13_000
        val dwell = (0..3).map { CapturedFix(placeLat + it * 500, originLng, t0 + (15 + it * 2) * min) }
        val flushed = (walk + dwell).flatMap { killed.onFix(it) } // ← killed on the confirming fix
        assertEquals(1, moves(flushed).size, "the walk flushed into the dwell")
        assertEquals(t0, moves(flushed).single().startMs)

        val resumed = MovementSegmenter(journal = journal)
        val onwards = line(count = 6, startLat = placeLat + 300_000, startMs = t0 + 25 * min)
        val second = moves(onwards.flatMap { resumed.onFix(it) } + resumed.onEnd()).single()

        assertEquals(t0 + 25 * min, second.startMs, "the next journey must not inherit the flushed leg")
        assertTrue(second.fixes.none { it in walk })
    }

    @Test
    fun aCapFlushedLegIsNotResurrectedEither() {
        // Same window as above at the other flush site: the `maxOpenMove` cap on a dwell-less
        // journey. The kill lands on the capping fix itself.
        //
        // ⚠ 19 min, not 20, is deliberate: at one fix per minute a 20-minute cap lands exactly on a
        // checkpoint-stride boundary, so a coincident *cadence* write would mask a missing
        // *flush-time* one and the test would pass for the wrong reason.
        val store = MemoryStore()
        val config = MovementDetectionConfig(maxOpenMoveMs = 19 * min)
        val drive = line(count = 40, startLat = originLat, startMs = t0, dLatE7 = 200_000)

        val killed = MovementSegmenter(moveConfig = config, journal = JsonOpenMoveJournal(store))
        var capped: CapturedMove? = null
        var consumed = 0
        for (fix in drive) {
            consumed++
            capped = moves(killed.onFix(fix)).firstOrNull()
            if (capped != null) break // ← killed the instant the cap flushed
        }
        val cappedLeg = capped ?: error("expected the cap to flush a leg")
        assertEquals(t0, cappedLeg.startMs)

        val resumed = MovementSegmenter(moveConfig = config, journal = JsonOpenMoveJournal(store))
        val onwards = drive.drop(consumed)
        val next = moves(onwards.flatMap { resumed.onFix(it) } + resumed.onEnd()).first()
        assertTrue(
            next.startMs >= cappedLeg.endMs,
            "the post-cap leg must not re-open the capped one (${next.startMs} < ${cappedLeg.endMs})",
        )
    }

    @Test
    fun anOverLongOutageFlushesTheRecoveredLegInsteadOfStitchingIt() {
        val store = MemoryStore()
        val config = MovementDetectionConfig(maxResumeGapMs = 15 * min)

        val killed = MovementSegmenter(moveConfig = config, journal = JsonOpenMoveJournal(store))
        line(count = 20, startLat = originLat, startMs = t0).forEach { killed.onFix(it) } // killed

        // Capture comes back 8 hours later, 500 km away. Stitching would fabricate a straight-line
        // jump and a synthetic ~17 m/s leg into the classifier; the leg must close on its own.
        val resumed = MovementSegmenter(moveConfig = config, journal = JsonOpenMoveJournal(store))
        val far = line(count = 10, startLat = originLat + 45_000_000, startMs = t0 + 8 * 60 * min)
        val after = far.flatMap { resumed.onFix(it) } + resumed.onEnd()

        val recorded = moves(after)
        assertEquals(2, recorded.size, "the recovered leg and the new one are separate journeys")
        val old = recorded.first()
        assertEquals(t0, old.startMs)
        assertTrue(old.endMs < t0 + 20 * min, "the recovered leg ends at its own last fix")
        assertTrue(recorded[1].startMs >= t0 + 8 * 60 * min)
        assertTrue(old.fixes.none { it.timestampMs > t0 + 20 * min }, "no stitching across the outage")
    }

    @Test
    fun aRecoveredLegFlushesEvenIfCaptureStopsBeforeTheNextFix() {
        val store = MemoryStore()
        MovementSegmenter(journal = JsonOpenMoveJournal(store)).also { killed ->
            line(count = 20, startLat = originLat, startMs = t0).forEach { killed.onFix(it) }
        }
        // Restart, then an immediate stop (permission revoked, source unavailable) — no fix ever
        // arrives. The journal must not be cleared out from under an unflushed leg.
        val resumed = MovementSegmenter(journal = JsonOpenMoveJournal(store))
        val move = moves(resumed.onEnd()).single()
        assertEquals(t0, move.startMs)
        assertTrue(store.rows.isEmpty(), "a clean stop clears the journal once the leg is out")
    }

    // ── Cadence, hygiene, and the no-op default ───────────────────────────────

    @Test
    fun theJournalIsWrittenOnCadenceNotOncePerFix() {
        val store = MemoryStore()
        val segmenter = MovementSegmenter(
            // A wide time arm so the fix-stride is what is measured here.
            moveConfig = MovementDetectionConfig(minMoveDurationMs = 60 * min),
            journal = JsonOpenMoveJournal(store),
        )
        line(count = 30, startLat = originLat, startMs = t0).forEach { segmenter.onFix(it) }

        // 30 fixes at a stride of 3 → ~10 writes, not 30. (Boundary flushes may add a few.)
        assertTrue(store.appends in 1..15, "expected cadence-paced writes, got ${store.appends}")
    }

    @Test
    fun aCleanStopLeavesNothingToRecover() {
        val store = MemoryStore()
        val segmenter = MovementSegmenter(journal = JsonOpenMoveJournal(store))
        line(count = 20, startLat = originLat, startMs = t0).forEach { segmenter.onFix(it) }
        segmenter.onEnd()
        assertTrue(store.rows.isEmpty())
        assertEquals(emptyList(), JsonOpenMoveJournal(store).read())
    }

    @Test
    fun aCorruptRecordIsPurgedRatherThanWedgingCapture() {
        val store = MemoryStore()
        store.append("open-move", "{ this is not json")
        val journal = JsonOpenMoveJournal(store)
        assertEquals(emptyList(), journal.read())
        assertTrue(store.rows.isEmpty(), "the unreadable record is purged, not retried forever")

        // And capture carries on normally over the same journal.
        val segmenter = MovementSegmenter(journal = journal)
        val events = line(count = 20, startLat = originLat, startMs = t0).flatMap { segmenter.onFix(it) } +
            segmenter.onEnd()
        assertEquals(1, moves(events).size)
    }

    @Test
    fun aRecordFromAnotherFixModelIsDroppedRatherThanMisread() {
        val store = MemoryStore()
        store.append("open-move", """{"version":99,"fixes":[{"latE7":1,"lngE7":2,"timestampMs":3}]}""")
        assertEquals(emptyList(), JsonOpenMoveJournal(store).read())
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun theNoneJournalKeepsTheOldInMemoryBehaviourExactly() {
        // The default must be inert: same events, and nothing persisted anywhere.
        val fixes = line(count = 30, startLat = originLat, startMs = t0)
        val withDefault = MovementSegmenter().let { s -> fixes.flatMap { s.onFix(it) } + s.onEnd() }
        val withNone = MovementSegmenter(journal = OpenMoveJournal.None)
            .let { s -> fixes.flatMap { s.onFix(it) } + s.onEnd() }
        assertEquals(withDefault, withNone)
        assertNull(OpenMoveJournal.None.read().firstOrNull())
    }
}
