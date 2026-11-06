package app.tenshu.home.data

import app.tenshu.home.model.ActivityKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.tenshu.routine.RoutineRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Activity feed over the engine's receipts (`routines-engine.md` RE-1).
 *
 * ⭐ The doc's claim — *"the receipt and the history row are the same row"* — is what makes this
 * testable without a second history type to keep in step.
 */
class SyncedHomeActivityRepositoryTest {

    private class FakeRuns(private val rows: List<SyncedRoutineRunInfo>) : RoutineSource {
        override fun routines() = flowOf(emptyList<SyncedRoutineInfo>())
        override fun runs(): Flow<List<SyncedRoutineRunInfo>> = flowOf(rows)
    }

    private fun run(
        firingId: String = "f1",
        name: String = "Good morning",
        state: String = RoutineRun.STATE_RAN,
        atMs: Long = 1_000L,
    ) = SyncedRoutineRunInfo(
        firingId = firingId,
        routineId = "r1",
        routineName = name,
        state = state,
        claimedAtEpochMs = atMs,
    )

    @Test
    fun aFiringBecomesOneAutomationRow() = runTest {
        val events = SyncedHomeActivityRepository(FakeRuns(listOf(run()))).events().first()

        val event = events.single()
        assertEquals(ActivityKind.Automation, event.kind)
        assertEquals("Good morning", event.title)
        assertEquals("Ran", event.detail)
    }

    /**
     * ⚠⚠ **Skipped is not failed.** A skipped routine's conditions were false, which is it working
     * correctly; a user told "Failed" would go looking for a fault that is not there.
     */
    @Test
    fun aSkippedRunSaysItsConditionsWereNotMet_notThatItFailed() = runTest {
        val detail = detailOf(RoutineRun.STATE_SKIPPED)

        assertTrue(detail.contains("Conditions"), detail)
        assertTrue(!detail.contains("Failed"), detail)
    }

    /** ⚠ Missed is the one outcome where the *home* did not act, rather than the routine deciding not to. */
    @Test
    fun aMissedRunSaysTheDeviceWasNotAwake() = runTest {
        assertTrue(detailOf(RoutineRun.STATE_MISSED).contains("not awake"))
    }

    /**
     * ⚠ A `Claimed` row is a firing that has started and not reported: it has no outcome to
     * describe, and becomes `Ran` or `Failed` moments later. Showing "started…" would be a row that
     * changes under the reader for no benefit.
     */
    @Test
    fun aClaimedRunIsNotYetAFeedRow() = runTest {
        val events = SyncedHomeActivityRepository(
            FakeRuns(listOf(run(state = RoutineRun.STATE_CLAIMED)))
        ).events().first()

        assertTrue(events.isEmpty(), "an in-flight firing has no outcome to report yet")
    }

    /**
     * ⚠⚠ A firing that happened is a fact about the home, so deleting the routine does not delete
     * its history — the row stays and says whose it was.
     */
    @Test
    fun aRunFromADeletedRoutineKeepsItsRow() = runTest {
        val events = SyncedHomeActivityRepository(
            FakeRuns(listOf(run(name = "")))
        ).events().first()

        assertEquals("A deleted routine", events.single().title)
    }

    private suspend fun detailOf(state: String): String =
        SyncedHomeActivityRepository(FakeRuns(listOf(run(state = state))))
            .events().first().single().detail
}
