package app.tenshu.fit.data

import app.tenshu.fit.model.ActivityType
import app.tenshu.fit.model.Source
import app.tenshu.fit.model.Workout
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DetectionSupersessionTest {

    private val zone = TimeZone.UTC
    private val base = 1_700_000_000_000L   // fixed; nothing here reads a clock

    private fun workout(
        id: String,
        source: Source,
        startMs: Long,
        durationSec: Int,
    ) = Workout(
        workoutId = id,
        activityType = ActivityType.Walking,
        source = source,
        title = "",
        device = "",
        start = Instant.fromEpochMilliseconds(startMs).toLocalDateTime(zone),
        end = Instant.fromEpochMilliseconds(startMs + durationSec * 1_000L).toLocalDateTime(zone),
        durationSec = durationSec,
    )

    private fun imported(startMs: Long, durationSec: Int, id: String = "hc-$startMs") =
        workout(id, Source.HealthConnect, startMs, durationSec)

    /**
     * ⚠⚠ Codex P1 on 6ae61f405: supersession's deletes go through the write boundary one by one,
     * so a realm gate that starts holding half-way stops them there and the refusal reaches the
     * caller (the gated import, which then counts a held pass). Paired with an open gate: every
     * superseded detection is deleted.
     */
    @Test
    fun aGateThatHoldsMidSupersessionStopsTheRemainingDeletes() = kotlinx.coroutines.test.runTest {
        for (holdsAfterFirst in listOf(true, false)) {
            val deleted = mutableListOf<String>()
            val repo = object : FitRepository by MockFitRepository() {
                override fun workouts() = kotlinx.coroutines.flow.flowOf(
                    listOf(
                        imported(base, 3_600),
                        workout("det-1", Source.Detected, base + 60_000, 600),
                        workout("det-2", Source.Detected, base + 1_200_000, 600),
                    ),
                )
                override suspend fun deleteWorkout(id: String): Boolean { deleted += id; return true }
            }
            var deletesAllowed = if (holdsAfterFirst) 1 else Int.MAX_VALUE
            val gate: suspend () -> Unit = {
                if (deletesAllowed-- <= 0) throw app.tenshu.reachability.RealmWritesHeldException()
            }

            val outcome = runCatching { repo.supersedeDetectedWorkouts(zone, beforeEachDelete = gate) }

            if (holdsAfterFirst) {
                assertTrue(outcome.exceptionOrNull() is app.tenshu.reachability.RealmWritesHeldException)
                assertEquals(1, deleted.size, "the delete after the gate closed was never made")
            } else {
                assertEquals(2, outcome.getOrThrow())
                assertEquals(setOf("det-1", "det-2"), deleted.toSet())
            }
        }
    }

    @Test
    fun anOverlappingImportSupersedesTheDetectedRow() {
        val ids = DetectionSupersession.supersededIds(
            existing = listOf(
                workout("detected:cap-trace:1", Source.Detected, base, 1_800),
                imported(base + 60_000, 1_800),   // the watch, a minute out
            ),
            zone = zone,
        )
        assertEquals(setOf("detected:cap-trace:1"), ids)
    }

    /** ⚠ The user's own data is never removed by an import, whatever it overlaps. */
    @Test
    fun onlyDetectedRowsAreEverSuperseded() {
        val existing = listOf(
            workout("rec-1", Source.Recorded, base, 1_800),
            workout("hc-1", Source.HealthConnect, base, 1_800),
            workout("man-1", Source.Manual, base, 1_800),
            workout("detected:cap-trace:1", Source.Detected, base, 1_800),
        )
        val ids = DetectionSupersession.supersededIds(existing, zone)
        assertEquals(setOf("detected:cap-trace:1"), ids)
    }

    @Test
    fun aNonOverlappingImportSupersedesNothing() {
        val ids = DetectionSupersession.supersededIds(
            existing = listOf(
                workout("detected:cap-trace:1", Source.Detected, base, 1_800),
                imported(base + 7_200_000, 1_800),   // two hours later
            ),
            zone = zone,
        )
        assertTrue(ids.isEmpty())
    }

    /**
     * ⚠ Half-open: walking to the gym and then lifting are two activities that touch at a single
     * instant. Treating that as an overlap would delete the walk.
     */
    @Test
    fun backToBackSessionsDoNotOverlap() {
        val walk = workout("detected:cap-trace:1", Source.Detected, base, 600)
        val lift = imported(base + 600_000, 1_800)   // starts exactly when the walk ends
        assertTrue(DetectionSupersession.supersededIds(listOf(walk, lift), zone).isEmpty())
    }

    @Test
    fun detectionsAloneSupersedeNothing() {
        val existing = listOf(workout("detected:cap-trace:1", Source.Detected, base, 1_800))
        assertTrue(DetectionSupersession.supersededIds(existing, zone).isEmpty())
    }

    @Test
    fun oneImportCanSupersedeSeveralDetections() {
        val existing = listOf(
            workout("detected:cap-trace:1", Source.Detected, base, 600),
            workout("detected:cap-trace:2", Source.Detected, base + 900_000, 600),
        )
        // A single long session covering both.
        val ids = DetectionSupersession.supersededIds(existing + imported(base, 3_600), zone)
        assertEquals(setOf("detected:cap-trace:1", "detected:cap-trace:2"), ids)
    }

    /**
     * ⚠ Activity type is deliberately not compared — the two sides speak different vocabularies,
     * so requiring a match would leave the duplicate this exists to remove.
     */
    @Test
    fun aDifferentActivityTypeStillSupersedes() {
        val detected = workout("detected:cap-trace:1", Source.Detected, base, 1_800)
            .copy(activityType = ActivityType.Walking)
        val ids = DetectionSupersession.supersededIds(listOf(detected, imported(base, 1_800)), zone)
        assertEquals(setOf("detected:cap-trace:1"), ids)
    }

    /**
     * ⚠⚠ The regression for the shipped ordering gap. The import landed first and the promotion
     * came later, at launch — the old rule only ran inside `runImport`, so this duplicate stood
     * until the next import. It was visible on a device as two "Walking" rows at 20:24.
     */
    @Test
    fun aDetectionPromotedAfterTheImportIsStillSuperseded() {
        val stored = listOf(
            imported(base, 1_200),                                            // arrived earlier
            workout("detected:cap-trace:1", Source.Detected, base, 420),      // promoted later
        )
        assertEquals(setOf("detected:cap-trace:1"), DetectionSupersession.supersededIds(stored, zone))
    }
}
