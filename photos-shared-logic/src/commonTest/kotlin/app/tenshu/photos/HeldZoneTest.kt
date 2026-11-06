package app.tenshu.photos

import app.tenshu.photos.time.HeldZone
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * [HeldZone] — why a walk over a library resolves the device's zone once (`photos-ios.md` §8.22).
 *
 * ⚠ What this cannot see is a caller going round it: that is `TabSwitchUITests`, which bounds the
 * main thread's CPU on the walk itself.
 */
class HeldZoneTest {

    private val tokyo = TimeZone.of("Asia/Tokyo")
    private val newYork = TimeZone.of("America/New_York")

    @Test
    fun aWalkResolvesOnce() {
        var resolutions = 0
        val held = HeldZone(clock = TestTimeSource()) { resolutions++; tokyo }

        repeat(5_000) { assertEquals(tokyo, held.get()) }

        assertEquals(1, resolutions)
    }

    @Test
    fun aZoneChangeIsSeenOnceTheHoldEnds_andNotBefore() {
        val clock = TestTimeSource()
        var zone = tokyo
        var resolutions = 0
        val held = HeldZone(heldFor = 1.seconds, clock = clock) { resolutions++; zone }
        assertEquals(tokyo, held.get())

        zone = newYork
        clock += 999.milliseconds
        assertEquals(tokyo, held.get(), "still held")

        clock += 1.milliseconds
        assertEquals(newYork, held.get(), "the next read after the hold is the new zone")
        assertEquals(2, resolutions)
    }
}
