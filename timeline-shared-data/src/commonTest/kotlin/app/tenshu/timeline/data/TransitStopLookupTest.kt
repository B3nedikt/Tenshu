package app.tenshu.timeline.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §36.6a — **a failed lookup must not be memoised as the answer.**
 *
 * The defect these pin came off a device: a transit leg finishing at München Ost rendered as a bare
 * `Transit` for hours, and the *same stored trace with the same coordinates* named itself after a
 * reinstall. `stopNear` collapses every failure to `""` and the cache kept that blank forever, so
 * one failed tile read was final for the life of the process.
 *
 * ⚠ The policy is pure and tested here rather than through `TimelineProjectionRepository`, which
 * needs a whole `TenshuClient` — the same seam `ModeSplitter.holdsAStandstill` uses.
 */
class TransitStopLookupTest {

    private fun lookup(
        origin: String = "Mühldorf (Oberbay)",
        destination: String = "Ostbahnhof",
        line: String = "RB 40",
        attempts: Int = 1,
    ) = StopLookup(origin, destination, line, attempts)

    @Test
    fun anUnaskedTraceIsAsked() {
        assertTrue(StopLookup.shouldAsk(null))
    }

    @Test
    fun aFullyNamedLegIsNeverAskedAgain() {
        // ⚠ The cache's whole purpose survives: a resolved leg costs one lookup per process, not one
        // per emission of the traces flow.
        assertFalse(StopLookup.shouldAsk(lookup()))
    }

    @Test
    fun aBlankIsAskedAgain() {
        // ⚠⚠ The defect itself. Before this, `("", "")` was stored and returned forever.
        assertTrue(StopLookup.shouldAsk(lookup(origin = "", destination = "", line = "")))
    }

    @Test
    fun aPartlyNamedLegIsAskedAgain() {
        // The three lookups fail independently (§33 L5: the line is joined by coordinate, not by
        // stop), so "some names" is not "all the names there were".
        assertTrue(StopLookup.shouldAsk(lookup(destination = "")))
        assertTrue(StopLookup.shouldAsk(lookup(line = "")))
    }

    @Test
    fun retriesAreBounded() {
        // ⚠⚠ The bound is not a performance knob — `NearestTransitStop` falls back to a NETWORK
        // geocoder, so an unbounded retry would re-send a nameless leg's coordinate to a third party
        // on every emission. A leg between two stations is legitimately nameless and common.
        assertTrue(StopLookup.shouldAsk(lookup(origin = "", attempts = StopLookup.MAX_ATTEMPTS - 1)))
        assertFalse(StopLookup.shouldAsk(lookup(origin = "", attempts = StopLookup.MAX_ATTEMPTS)))
    }

    @Test
    fun aRetryNeverTakesANameAway() {
        // ⚠⚠ The trap in making a cache self-healing: attempt 1 found the origin, attempt 2 failed
        // outright. Writing attempt 2 over the cache would make the row LOSE a name it had already
        // shown — a flickering feed instead of a healing one.
        val first = StopLookup("Mühldorf (Oberbay)", "", "RB 40", attempts = 1)
        val secondAttemptFoundNothing = StopLookup("", "", "", attempts = 2)

        val merged = secondAttemptFoundNothing.mergedOver(first)

        assertEquals("Mühldorf (Oberbay)", merged.originStop)
        assertEquals("RB 40", merged.line)
        assertEquals("", merged.destinationStop, "still unknown, and still worth asking")
        assertEquals(2, merged.attempts, "the budget is spent whether or not the attempt found anything")
    }

    @Test
    fun aRetryFillsInWhatWasMissing() {
        val first = StopLookup("Mühldorf (Oberbay)", "", "", attempts = 1)
        val second = StopLookup("Mühldorf (Oberbay)", "Ostbahnhof", "RB 40", attempts = 2)

        val merged = second.mergedOver(first)

        assertEquals("Ostbahnhof", merged.destinationStop)
        assertEquals("RB 40", merged.line)
        assertTrue(merged.isResolved)
        assertFalse(StopLookup.shouldAsk(merged), "resolved ⇒ the retries stop")
    }

    @Test
    fun theFirstAttemptNeedsNoPrevious() {
        val first = lookup(attempts = 1)
        assertEquals(first, first.mergedOver(null))
    }
}
