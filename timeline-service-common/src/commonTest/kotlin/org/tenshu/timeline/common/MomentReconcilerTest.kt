package org.tenshu.timeline.common

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure, device-free coverage of the **cross-source reconciler** (I6, `timeline-ingestion.md` §7.1):
 * near-simultaneous captures of one place fold to a canonical with unioned sources; visits at
 * different times or places don't; activities dedupe by workout subject; subjectless moments never
 * dedupe; the canonical key is deterministic; and source priority elects the canonical.
 */
class MomentReconcilerTest {

    private val min = 60_000L
    private val hour = 60 * 60 * 1000L
    // Bucket-aligned so the three near-simultaneous captures share a time bucket.
    private val t0 = 7_200_000L * 250_000L
    private val cafeLat = 525_200_000L
    private val cafeLng = 134_050_000L

    private fun visit(id: String, atMs: Long, lat: Long = cafeLat, lng: Long = cafeLng, source: String) =
        ReconcilableMoment(id, MomentReconciler.KIND_VISIT, atMs, lat, lng, subjectKind = "place", source = source)

    private fun activity(id: String, atMs: Long, workoutId: String, source: String) =
        ReconcilableMoment(id, MomentReconciler.KIND_ACTIVITY, atMs, subjectKind = "workout", subjectId = workoutId, source = source)

    private fun movement(id: String, atMs: Long, traceId: String, source: String = "capture") =
        ReconcilableMoment(id, MomentReconciler.KIND_MOVEMENT, atMs, subjectKind = "trace", subjectId = traceId, source = source)

    /**
     * ⭐ A movement and the workout Fit promoted *from* it are one thing shown twice. They fold on
     * the trace id the derived workout's id embeds — an exact identity, not a time-and-proximity
     * guess.
     *
     * ⚠⚠ And the **movement wins**, which inverts the M-track's "a health Workout supersedes the
     * captured MOVEMENT". That rule assumes the workout is independent evidence from a watch; a
     * promoted one was computed from this very movement and carries no route, so electing it
     * would replace the only geometry with a poorer copy of itself.
     */
    @Test
    fun derivedTracePairsFoldOntoTheMovement() {
        val reconciled = MomentReconciler.reconcile(
            listOf(
                movement("mv", t0, traceId = "cap-trace:1000"),
                activity("act", t0, workoutId = "detected:cap-trace:1000", source = "capture"),
            ),
        )
        assertEquals(1, reconciled.size, "the pair is one moment, not two")
        assertEquals("mv", reconciled.single().canonicalMomentId, "the movement keeps the route")
        assertEquals(listOf("act"), reconciled.single().supersededMomentIds)
    }

    /**
     * ⚠ A workout that merely *overlaps* a movement is left alone. Folding on time would merge a
     * cycle commute with a gym session that happened during it — the fuzzy case is the wider I6
     * fold, and guessing is worse than showing both.
     */
    @Test
    fun anUnrelatedWorkoutDoesNotFoldOntoAMovement() {
        val reconciled = MomentReconciler.reconcile(
            listOf(
                movement("mv", t0, traceId = "cap-trace:1000"),
                activity("act", t0, workoutId = "hc-9999", source = "import"),
            ),
        )
        assertEquals(2, reconciled.size)
    }

    /** ⚠ Two unrelated movements stay separate — the trace id is the identity, not the kind. */
    @Test
    fun distinctMovementsNeverFold() {
        val reconciled = MomentReconciler.reconcile(
            listOf(
                movement("a", t0, traceId = "cap-trace:1"),
                movement("b", t0, traceId = "cap-trace:2"),
            ),
        )
        assertEquals(2, reconciled.size)
    }

    @Test
    fun nearSimultaneousCapturesOfOnePlaceFoldToOneCanonical() {
        val reconciled = MomentReconciler.reconcile(
            listOf(
                visit("goog", t0 + 5 * min, source = "google"),
                visit("cap", t0, source = "capture"),
                visit("imp", t0 + 10 * min, source = "import"),
            ),
        )

        assertEquals(1, reconciled.size)
        val r = reconciled.single()
        assertEquals("cap", r.canonicalMomentId) // live capture outranks google + import
        assertEquals(listOf("goog", "imp"), r.supersededMomentIds)
        assertEquals(listOf("capture", "google", "import"), r.sources) // unioned provenance
    }

    @Test
    fun visitsAtDifferentTimesOrPlacesStaySeparate() {
        // Same place, three hours apart → different time buckets.
        assertEquals(2, MomentReconciler.reconcile(listOf(
            visit("a", t0, source = "capture"),
            visit("b", t0 + 3 * hour, source = "capture"),
        )).size)

        // Same time, ~11 km apart → different place cells.
        assertEquals(2, MomentReconciler.reconcile(listOf(
            visit("a", t0, source = "capture"),
            visit("b", t0, lat = cafeLat + 1_000_000, source = "capture"),
        )).size)
    }

    @Test
    fun activitiesDedupeByWorkoutSubject() {
        val reconciled = MomentReconciler.reconcile(
            listOf(
                activity("watch", t0, "workout-run", "import"),
                activity("phone", t0 + min, "workout-run", "capture"),
                activity("other", t0, "workout-swim", "capture"),
            ),
        )
        assertEquals(2, reconciled.size)
        val run = reconciled.single { it.subjectId == "workout-run" }
        assertEquals("phone", run.canonicalMomentId) // capture outranks import
        assertEquals(listOf("watch"), run.supersededMomentIds)
        assertEquals(listOf("capture", "import"), run.sources)
    }

    @Test
    fun subjectlessMomentsNeverDedupe() {
        val e1 = ReconcilableMoment("e1", "Event", t0)
        val e2 = ReconcilableMoment("e2", "Event", t0)
        assertEquals(2, MomentReconciler.reconcile(listOf(e1, e2)).size)
    }

    @Test
    fun reconciliationIsDeterministicRegardlessOfOrder() {
        val moments = listOf(
            visit("cap", t0, source = "capture"),
            visit("goog", t0 + 5 * min, source = "google"),
            visit("x", t0 + 5 * hour, source = "capture"),
        )
        val a = MomentReconciler.reconcile(moments).map { it.canonicalKey to it.canonicalMomentId }
        val b = MomentReconciler.reconcile(moments.reversed()).map { it.canonicalKey to it.canonicalMomentId }
        assertEquals(a, b)
    }
}
