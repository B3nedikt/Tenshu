package org.tenshu.inference.proxy.vocabulary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §7.7's *"a label can be true and useless"* view.
 *
 * ⚠⚠ The defect this exists for is **invisible per image** — `indoors` on an indoor photograph is
 * simply correct — and exists only in the aggregate. These cases are therefore all about the
 * aggregate, and none of them needs a model.
 */
class LabelFiringRatesTest {

    /** The measured shape from `models.md` §3.7.4: a label that is right and useless. */
    @Test
    fun aLabelFiringOnMostOfTheLibraryIsVisibleAsARate() {
        val rates = firingRates(mapOf("indoors" to 57, "dog" to 4), consideredImages = 100)

        assertEquals("indoors", rates.first().label)
        assertEquals(0.57, rates.first().rate)
        assertEquals(0.04, rates.last().rate)
    }

    /** ⚠ Descending by count, then by label — stable enough to diff between runs. */
    @Test
    fun ratesAreOrderedByHowOftenTheyFiredThenByName() {
        val rates = firingRates(mapOf("beach" to 5, "apple" to 5, "cat" to 9), consideredImages = 10)

        assertEquals(listOf("cat", "apple", "beach"), rates.map { it.label })
    }

    /** ⚠ Zero considered images is a rate of zero, never a divide — a skipped pass reports 0 %. */
    @Test
    fun noConsideredImagesIsARateOfZeroRatherThanACrash() {
        val rates = firingRates(mapOf("cat" to 0), consideredImages = 0)

        assertEquals(0.0, rates.single().rate)
    }

    /**
     * ⚠⚠ The denominator is named because the number is meaningless without it: an incremental pass
     * over an already-indexed library considers almost nothing, and "indoors 100 %" over three
     * photographs is not the library-wide finding it looks like.
     */
    @Test
    fun theReportNamesItsDenominator() {
        val line = firingRates(mapOf("indoors" to 57), consideredImages = 100).report()

        assertTrue("indoors 57%" in line, line)
        assertTrue("of 100 images considered" in line, line)
    }

    @Test
    fun theReportSingularisesOneImage() {
        val line = firingRates(mapOf("cat" to 1), consideredImages = 1).report()

        assertTrue("of 1 image considered" in line, line)
    }

    /** ⚠ Capped, so a 300-label vocabulary does not turn one pass into 300 lines of log. */
    @Test
    fun theReportShowsOnlyTheWorstOffenders() {
        val counts = (1..20).associate { "label$it" to it }

        val line = firingRates(counts, consideredImages = 20).report(limit = 3)

        assertEquals(3, line.substringBefore(" (of ").split(", ").size)
        assertTrue("label20" in line, line)
        assertTrue("label1 " !in line, "the rarest label should not be reported: $line")
    }

    @Test
    fun nothingFiringIsSaidPlainly() {
        assertEquals("no labels fired", firingRates(emptyMap(), consideredImages = 10).report())
    }
}
