package app.tenshu.calendar.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ⚠⚠ The palette compares colours **alpha-blind**: rows seeded as `0x4285F4` (the default blue with
 * alpha 0) exist in realms, and every renderer draws them opaque.
 */
class CalendarPaletteTest {

    private val legacyBlue = 0x4285F4

    @Test
    fun the_legacy_alpha_zero_blue_is_the_palette_blue() {
        assertTrue(CalendarPalette.sameColour(legacyBlue, CalendarPalette.DEFAULT))
        assertFalse(CalendarPalette.sameColour(legacyBlue, CalendarPalette.COLORS[1]))
    }

    @Test
    fun a_legacy_blue_counts_as_taken_for_the_next_colour() {
        assertEquals(CalendarPalette.COLORS[1], CalendarPalette.nextAfter(listOf(legacyBlue)))
    }
}
