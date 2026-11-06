package app.tenshu.timeline.data.capture

import app.tenshu.outbox.OutboxEntry
import app.tenshu.outbox.OutboxStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The **in-progress ride** (`docs/proposals/timeline-movement-capture.md` §12.6's deferred half):
 * the segmenter's open buffer, surfaced live instead of staying invisible until it flushes.
 *
 * Three things are under test here, and they are separable:
 *  - the segmenter *emits* progress for an open leg, and stops the moment that leg becomes a row;
 *  - the two live states are mutually exclusive — you are dwelling or moving, never both;
 *  - a leg left open by a **dead process** is recognised from the durable journal at app open.
 *
 * ⚠ [OngoingMoveState] / [OngoingVisitState] are process-local singletons, so every test resets
 * both around itself — otherwise one test's live ride leaks into the next one's assertions.
 */
class OngoingMoveStateTest {

    private val t0 = 1_800_000_000_000L
    private val min = 60_000L
    private val originLat = 525_000_000L
    private val originLng = 134_000_000L

    /**
     * A straight run of fixes, one per minute, ~133 m apart — walking pace.
     *
     * ⚠ Sized against the recordability floor deliberately: **4 fixes** is the shortest run that
     * clears all three of `toCapturedMoveOrNull`'s thresholds (≥3 fixes, ≥3 min span, ≥250 m), so
     * `line(4)` is "just recordable" and `line(3)` is "just not" — which is what makes the
     * threshold tests below sharp rather than incidental.
     */
    private fun line(count: Int, startLat: Long = originLat, startMs: Long = t0, dLatE7: Long = 12_000) =
        (0 until count).map { CapturedFix(startLat + it * dLatE7, originLng, startMs + it * min) }

    /**
     * A **mid-bucket** walk: ~76 m/min ≈ 1.26 m/s, which sits near the centre of the walk bucket
     * (`[0, 2.5)` m/s) rather than against its edge.
     *
     * ⚠ Separate from [line] on purpose. `line`'s 133 m/min is 2.23 m/s — a hair under the
     * walk/run boundary — so its *margin* term alone caps confidence around 22 no matter how long
     * it runs. That is fine for the recordability fixtures (which care about distance) but it would
     * make the confidence-gate tests test the wrong variable. With margin held near 1, the only
     * thing separating the two tests below is **leg count**, which is exactly the claim the gate
     * makes.
     */
    private fun steadyWalk(count: Int) = line(count = count, dLatE7 = 6_800)

    private fun progress(events: List<CaptureEvent>) =
        events.filterIsInstance<CaptureEvent.MoveProgress>().map { it.open }

    private fun moves(events: List<CaptureEvent>) =
        events.filterIsInstance<CaptureEvent.Move>().map { it.move }

    /** In-memory [OutboxStore] — plays the part a real file does across a "process death". */
    private class MemoryStore : OutboxStore {
        val rows = LinkedHashMap<String, String>()
        override fun append(id: String, payload: String) { rows[id] = payload }
        override fun entries(): List<OutboxEntry> = rows.map { OutboxEntry(it.key, it.value) }
        override fun remove(id: String) { rows.remove(id) }
    }

    @BeforeTest
    fun reset() {
        OngoingMoveState.clear()
        OngoingVisitState.clear()
    }

    @AfterTest
    fun tearDown() = reset()

    // ── the segmenter surfaces the leg it is still accumulating ───────────────

    @Test
    fun anOpenLegIsEmittedAsProgressOnceItWouldBeRecordable() {
        val segmenter = MovementSegmenter()

        // Below the recordability floor there is nothing honest to show yet — the same bar a
        // flushed leg must clear, so the feed can never advertise a ride capture would discard.
        val early = line(count = 3).flatMap { segmenter.onFix(it) }
        assertEquals(emptyList(), progress(early), "3 fixes / 2 min span is not yet a journey")

        // The 4th fix takes it over all three thresholds at once.
        val open = progress(segmenter.onFix(line(count = 5)[3]))
        assertEquals(1, open.size)
        assertEquals(t0, open.single().startMs, "the leg is dated from its first fix, not the latest")
        assertEquals(t0 + 3 * min, open.single().lastFixMs)
        assertTrue(open.single().distanceM >= 250.0, "distance so far, was ${open.single().distanceM}")
    }

    @Test
    fun progressStopsTheMomentTheLegBecomesARow() {
        // The no-duplicate invariant at its source: the call that flushes a leg to the writer must
        // not also still describe it as open, and nothing may re-open it afterwards — otherwise the
        // feed would show the live overlay beside the row it just became.
        val segmenter = MovementSegmenter()
        line(count = 10).forEach { segmenter.onFix(it) }

        // Stand still at the far end. The dwell confirms at minDwellMs (5 min) and flushes the
        // transit that led here.
        val restLat = originLat + 10 * 12_000
        val restStart = t0 + 10 * min
        val perFix = (0..12).map { i -> segmenter.onFix(CapturedFix(restLat, originLng, restStart + i * min)) }

        val flushAt = perFix.indexOfFirst { moves(it).isNotEmpty() }
        assertTrue(flushAt >= 0, "precondition: standing still must eventually flush the transit")
        assertEquals(1, perFix.sumOf { moves(it).size }, "flushed exactly once")

        // ⚠ Before the flush, progress legitimately KEEPS firing: the segmenter cannot yet know the
        // user has stopped — the cluster is still undecided and may fold back into the move. Pinned
        // so the behaviour reads as chosen rather than accidental.
        assertTrue(
            perFix.take(flushAt).any { progress(it).isNotEmpty() },
            "an undecided pause is still an open journey",
        )

        assertEquals(emptyList(), progress(perFix[flushAt]), "the flushing call reports nothing open")
        assertTrue(
            perFix.drop(flushAt + 1).all { progress(it).isEmpty() },
            "and sitting there never re-opens it",
        )
    }

    // ── dwelling or moving, never both ────────────────────────────────────────

    @Test
    fun theTwoLiveStatesAreMutuallyExclusive() {
        // Enforced by construction rather than by every call site remembering: a set of either
        // clears the other, so "both current" is unrepresentable.
        OngoingVisitState.set(OngoingVisit(originLat, originLng, t0))
        assertNotNull(OngoingVisitState.current.value)

        OngoingMoveState.set(OngoingMove(t0, t0 + 5 * min, 900.0, classifyMode(line(6))))
        assertNotNull(OngoingMoveState.current.value, "the move is now current")
        assertNull(OngoingVisitState.current.value, "…and the place no longer is")

        OngoingVisitState.set(OngoingVisit(originLat, originLng, t0 + 10 * min))
        assertNotNull(OngoingVisitState.current.value, "the place is current again")
        assertNull(OngoingMoveState.current.value, "…and the ride is not")
    }

    @Test
    fun aWholeCapturedJourneyNeverLeavesBothStatesSet() {
        // The invariant across a realistic event sequence, dispatched exactly as the engine does:
        // walk → arrive → sit → leave → walk. Checked after *every* event, not just at the end.
        val segmenter = MovementSegmenter()
        val restLat = originLat + 10 * 12_000
        val fixes = line(count = 10) +
            (0..8).map { CapturedFix(restLat, originLng, t0 + (10 + it) * min) } +
            line(count = 6, startLat = restLat, startMs = t0 + 19 * min)

        for (fix in fixes) {
            for (event in segmenter.onFix(fix)) {
                dispatchLikeTheEngine(event)
                assertTrue(
                    OngoingVisitState.current.value == null || OngoingMoveState.current.value == null,
                    "both live states set after $event",
                )
            }
        }
        // And the journey really did exercise both arms, or the assertion above proves nothing.
        assertTrue(sawVisit && sawMove, "fixture must produce both a dwell and a ride (visit=$sawVisit move=$sawMove)")
    }

    private var sawVisit = false
    private var sawMove = false

    /** The [LocationCaptureEngine] dispatch, minus the writers (this test is about live state). */
    private fun dispatchLikeTheEngine(event: CaptureEvent) {
        when (event) {
            is CaptureEvent.Dwell -> {
                OngoingMoveState.clear()
                if (event.ongoing) {
                    sawVisit = true
                    OngoingVisitState.set(OngoingVisit(event.visit.latE7, event.visit.lngE7, event.visit.arrivalMs))
                } else {
                    OngoingVisitState.clear()
                }
            }
            is CaptureEvent.Move -> {
                OngoingVisitState.clear()
                OngoingMoveState.clear()
            }
            is CaptureEvent.MoveProgress -> {
                sawMove = true
                OngoingMoveState.set(event.open)
            }
        }
    }

    // ── the provisional mode is not presented as settled fact ─────────────────

    @Test
    fun aLowConfidenceModeIsNotNamed() {
        // A four-leg guess is a guess. The row still appears — with its live distance and duration —
        // it simply does not put a mode on screen it cannot yet stand behind.
        val young = classifyMode(steadyWalk(count = 5))
        assertTrue(
            young.confidence < OngoingMove.MIN_NAMEABLE_CONFIDENCE,
            "precondition: a 4-leg classification is below the gate, was ${young.confidence}",
        )
        assertEquals(MovementMode.WALK, young.mode, "precondition: the classifier did pick a bucket")
        assertEquals(
            MovementMode.UNKNOWN,
            OngoingMove(t0, t0 + 4 * min, 530.0, young).provisionalMode,
            "the picked bucket must not be surfaced below the gate",
        )
    }

    @Test
    fun aConfidentModeIsNamed() {
        // Twenty legs of clean, unambiguous walking saturates the classifier's sample arm.
        val settled = classifyMode(steadyWalk(count = 21))
        assertTrue(
            settled.confidence >= OngoingMove.MIN_NAMEABLE_CONFIDENCE,
            "precondition: a 20-leg classification clears the gate, was ${settled.confidence}",
        )
        assertEquals(MovementMode.WALK, OngoingMove(t0, t0 + 20 * min, 1_510.0, settled).provisionalMode)
    }

    // ── app open: the journey a dead process left behind ──────────────────────

    @Test
    fun aJourneyOpenInTheJournalIsRecognisedOnAFreshStart() {
        // The payoff of the durable journal (§12.6). Process A walks for 20 minutes and is killed
        // — no onEnd, no flush, exactly what an OEM force-kill does. Process B opens the app.
        val store = MemoryStore()
        val killed = MovementSegmenter(journal = JsonOpenMoveJournal(store))
        val emitted = line(count = 20).flatMap { killed.onFix(it) }
        // Precondition — this is exactly the case the feature exists for: the leg was never
        // flushed, so nothing was written and nothing would ever have been shown.
        assertEquals(emptyList(), moves(emitted), "precondition: the killed process recorded no leg")

        // A fresh process: the live state died with the old one.
        OngoingMoveState.clear()
        assertNull(OngoingMoveState.current.value, "precondition: a new process starts with no live state")

        val recovered = OngoingMoveState.restoreFrom(
            JsonOpenMoveJournal(store),
            nowMs = t0 + 20 * min, // opened the app a minute after the last fix
        )

        assertNotNull(recovered, "the open leg is picked up from the journal")
        assertEquals(t0, recovered.startMs, "and it is still the journey that started 20 min ago")
        assertEquals(recovered, OngoingMoveState.current.value, "…and it is published, not just returned")
    }

    @Test
    fun aStaleJournalIsNotRecognisedAsAJourneyYouAreOn() {
        // Precondition: the identical journal *is* recovered when it is fresh.
        val store = MemoryStore()
        MovementSegmenter(journal = JsonOpenMoveJournal(store)).also { line(count = 20).forEach(it::onFix) }
        assertNotNull(
            OngoingMoveState.restoreFrom(JsonOpenMoveJournal(store), nowMs = t0 + 20 * min),
            "precondition: the same journal recovers when fresh",
        )
        OngoingMoveState.clear()

        // Two hours later that leg is over — the segmenter would flush it as a finished leg rather
        // than resume it, so the feed must not still call it current.
        assertNull(
            OngoingMoveState.restoreFrom(JsonOpenMoveJournal(store), nowMs = t0 + 20 * min + 2 * 60 * min),
            "a leg past maxResumeGapMs is not ongoing",
        )
        assertNull(OngoingMoveState.current.value)
    }

    @Test
    fun anUnrecordableJournalIsNotRecognised() {
        // Precondition: four fixes from the same journal do recover.
        val enough = MemoryStore()
        MovementSegmenter(journal = JsonOpenMoveJournal(enough)).also { line(count = 4).forEach(it::onFix) }
        assertNotNull(
            OngoingMoveState.restoreFrom(JsonOpenMoveJournal(enough), nowMs = t0 + 3 * min),
            "precondition: a just-recordable path recovers",
        )
        OngoingMoveState.clear()

        // A few metres of GPS wander is not a ride, and recovery must not lower the bar capture
        // itself applies.
        val wander = MemoryStore()
        JsonOpenMoveJournal(wander).checkpoint(
            (0..4).map { CapturedFix(originLat + it * 20, originLng, t0 + it * min) },
        )
        assertTrue(wander.rows.isNotEmpty(), "precondition: the wander path really was journalled")
        assertNull(OngoingMoveState.restoreFrom(JsonOpenMoveJournal(wander), nowMs = t0 + 4 * min))
    }

    @Test
    fun recoveryNeverOverwritesLiveState() {
        // Ordering hazard: capture may already be running by the time a launch path gets here.
        // Fresher truth must win over a journal that is by definition at least one fix behind.
        val store = MemoryStore()
        MovementSegmenter(journal = JsonOpenMoveJournal(store)).also { line(count = 20).forEach(it::onFix) }

        val live = OngoingMove(t0 + 99 * min, t0 + 100 * min, 4_000.0, classifyMode(line(10)))
        OngoingMoveState.set(live)

        assertNull(
            OngoingMoveState.restoreFrom(JsonOpenMoveJournal(store), nowMs = t0 + 20 * min),
            "recovery declines when something is already published",
        )
        assertEquals(live, OngoingMoveState.current.value, "the live value is untouched")
    }

    @Test
    fun recoveryLeavesTheJournalIntact() {
        // ⚠ The reader must not consume the record: the segmenter owns it, and a launch that
        // emptied it would destroy the very durability §12.6 exists to provide — the leg would be
        // lost if the process died again before the next flush.
        val store = MemoryStore()
        MovementSegmenter(journal = JsonOpenMoveJournal(store)).also { line(count = 20).forEach(it::onFix) }
        val before = store.rows.toMap()
        val pathBefore = JsonOpenMoveJournal(store).read()
        // ⚠ Not "20": the journal checkpoints every 3rd fix, so it legitimately trails the live
        // buffer by up to two fixes (§12.6's bounded-tail decision). What must not change is the
        // record itself.
        assertTrue(pathBefore.size >= 18, "precondition: the journal holds the leg, was ${pathBefore.size}")

        assertNotNull(OngoingMoveState.restoreFrom(JsonOpenMoveJournal(store), nowMs = t0 + 20 * min))

        assertEquals(before, store.rows.toMap(), "the journal is unchanged by a read")
        assertEquals(pathBefore, JsonOpenMoveJournal(store).read(), "and still holds the same path")
    }
}
