package org.tenshu.core

import org.tenshu.core.number.toTInteger
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit coverage for [inRangeLogic] — the half-open numeric containment behind the
 * [inRange] predicate. Complements the end-to-end proxy pass in `SearchAllAndFilterTest`.
 */
class InRangeLogicTest {

    private fun check(value: Long, start: Long, end: Long): Boolean =
        inRangeLogic(value.toTInteger(), start, end).get()

    @Test
    fun theRangeIsHalfOpen() {
        // ⚠⚠ The whole point: `start` is in, `end` is out, so two adjacent windows laid
        // end to end cannot both claim the row that lands exactly on the boundary.
        assertTrue(check(10, 10, 20), "start is inclusive")
        assertFalse(check(20, 10, 20), "end is exclusive")
        assertTrue(check(19, 10, 20))
        assertFalse(check(9, 10, 20))
    }

    @Test
    fun adjacentWindowsPartitionTheLine() {
        val boundary = 100L
        // Exactly one of the two windows may claim the boundary value.
        assertFalse(check(boundary, 0, boundary))
        assertTrue(check(boundary, boundary, 200))
    }

    @Test
    fun anEmptyOrInvertedRangeMatchesNothing() {
        // ⚠ Never widened to "no filter": a caller's arithmetic slip must not read as a
        // successful answer over everything.
        assertFalse(check(10, 10, 10), "the empty half-open interval contains nothing")
        assertFalse(check(15, 20, 10), "an inverted range is empty, not universal")
    }

    @Test
    fun boundsAcceptRawNumbersAndWireIntegers() {
        assertTrue(inRangeLogic(15L.toTInteger(), 10L, 20L).get())
        assertTrue(inRangeLogic(15L.toTInteger(), 10L.toTInteger(), 20L.toTInteger()).get())
        assertTrue(inRangeLogic(15L.toTInteger(), 10, 20).get())
    }

    @Test
    fun aTimestampIsAnOrdinaryIntegerHere() {
        // `Instant : TInteger`, which is what makes one predicate answer every time
        // window in the platform rather than needing a temporal sibling.
        val noon = 1_784_116_800_000L
        assertTrue(check(noon, noon - 1_000, noon + 1_000))
        assertFalse(check(noon, noon + 1, noon + 1_000))
    }

    @Test
    fun aNonNumericReceiverOrBoundIsFalse_neverAGuess() {
        // The `containsLogic` posture: matching a numeric predicate against text is
        // meaningless, so it is false rather than an error or a coercion.
        assertFalse(inRangeLogic("10".toTString(), 0L, 100L).get())
        assertFalse(inRangeLogic(10L.toTInteger(), "0".toTString(), 100L).get())
        assertFalse(inRangeLogic(10L.toTInteger(), 0L, "100".toTString()).get())
    }

    @Test
    fun negativeAndZeroValuesBehaveOrdinarily() {
        assertTrue(check(-5, -10, 0))
        assertFalse(check(0, -10, 0))
        assertTrue(check(0, 0, 10))
    }
}
