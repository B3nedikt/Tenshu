package app.tenshu.timeline.data.geocode

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The opt-in-edge backfill trigger (place-geocoding.md G2): true edges backfill, false ones don't. */
class GeocodingBackfillTriggerTest {

    @Test
    fun alreadyEnabledAtStartBackfillsOnce() = runTest {
        val control = FakePlaceGeocodingControl(initiallyEnabled = true)
        var runs = 0
        val job = launch { GeocodingBackfillTrigger(control) { runs++ }.run() }

        testScheduler.advanceUntilIdle()
        assertEquals(1, runs)
        job.cancelAndJoin()
    }

    @Test
    fun disabledStartDoesNotBackfillUntilEnabled() = runTest {
        val control = FakePlaceGeocodingControl(initiallyEnabled = false)
        var runs = 0
        val job = launch { GeocodingBackfillTrigger(control) { runs++ }.run() }

        testScheduler.advanceUntilIdle()
        assertEquals(0, runs)

        control.setEnabled(true)
        testScheduler.advanceUntilIdle()
        assertEquals(1, runs)
        job.cancelAndJoin()
    }

    @Test
    fun eachReEnableBackfillsAgainButDisablingDoesNot() = runTest {
        val control = FakePlaceGeocodingControl(initiallyEnabled = true)
        var runs = 0
        val job = launch { GeocodingBackfillTrigger(control) { runs++ }.run() }
        testScheduler.advanceUntilIdle()

        control.setEnabled(false)
        testScheduler.advanceUntilIdle()
        assertEquals(1, runs)

        control.setEnabled(true)
        testScheduler.advanceUntilIdle()
        assertEquals(2, runs)
        job.cancelAndJoin()
    }
}
