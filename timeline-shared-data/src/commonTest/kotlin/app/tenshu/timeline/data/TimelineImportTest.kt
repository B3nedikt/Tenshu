package app.tenshu.timeline.data

import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.SubjectKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure coverage of the **ingestion seam's acquisition side** — that [ReferenceTimelineSource]
 * produces the expected service-shaped fixture through the [ImportedTimeline] contract, with
 * subjects wired to the moments that reference them. The end-to-end write path
 * ([TimelineImportWriter] → services → projector) is exercised by
 * `TimelinePipelineIntegrationTest`; this stays client-free and fast.
 */
class TimelineImportTest {

    @Test
    fun referenceSourceProducesTheExpectedFixture() = runTest {
        val imported = ReferenceTimelineSource().load()

        assertFalse(imported.isEmpty)
        assertEquals(1, imported.places.size)
        assertEquals(1, imported.workouts.size)
        assertEquals(2, imported.moments.size)
        assertEquals(1, imported.trips.size)

        // The Visit moment points at the café place; the Activity moment at the run workout —
        // and each subject id resolves to a record actually present in the import.
        val visit = imported.moments.single { it.kind == Moment.MomentKind.VISIT }
        assertEquals(SubjectKind.PLACE, visit.subjectKind)
        assertTrue(imported.places.any { it.placeId == visit.subjectId && it.label == "Blue Bottle" })

        val activity = imported.moments.single { it.kind == Moment.MomentKind.ACTIVITY }
        assertEquals(SubjectKind.WORKOUT, activity.subjectKind)
        assertTrue(imported.workouts.any { it.workoutId == activity.subjectId && it.activityType == "Running" })
    }
}
