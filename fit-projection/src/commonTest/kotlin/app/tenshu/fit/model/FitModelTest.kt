package app.tenshu.fit.model

import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FitModelTest {

    private fun workout(
        durationSec: Int = 1920,
        distanceM: Int? = 5200,
        avgPaceSecPerKm: Int? = null,
    ) = Workout(
        workoutId = "w-test",
        activityType = ActivityType.Running,
        source = Source.Recorded,
        title = "Morning run",
        device = "Recorded in Fit",
        start = LocalDateTime(2026, 5, 24, 7, 14),
        end = LocalDateTime(2026, 5, 24, 7, 46),
        durationSec = durationSec,
        distanceM = distanceM,
        avgPaceSecPerKm = avgPaceSecPerKm,
    )

    @Test
    fun effectivePacePrefersExplicitThenDerives() {
        assertEquals(300, workout(avgPaceSecPerKm = 300).effectivePaceSecPerKm)
        // Derived: 1920 s over 5.2 km ≈ 369 s/km.
        assertEquals(369, workout().effectivePaceSecPerKm)
        assertNull(workout(distanceM = null).effectivePaceSecPerKm)
        assertNull(workout(distanceM = 0).effectivePaceSecPerKm)
    }

    @Test
    fun categoryEmptinessDimsCards() {
        assertTrue(CategorySummary(CategoryId.Cycle, CategoryTeaser.NotSetUp, LastUpdated.Never).isEmpty)
        assertTrue(CategorySummary(CategoryId.Nutrition, CategoryTeaser.NoEntries, LastUpdated.Never).isEmpty)
        assertFalse(
            CategorySummary(
                CategoryId.Vitals,
                CategoryTeaser.RestingHeartRate(62),
                LastUpdated.Today,
            ).isEmpty,
        )
    }

    @Test
    fun importProgressCompletes() {
        assertFalse(ImportProgress(CategoryId.Activity, imported = 3, total = 10).done)
        assertTrue(ImportProgress(CategoryId.Activity, imported = 10, total = 10).done)
    }
}
