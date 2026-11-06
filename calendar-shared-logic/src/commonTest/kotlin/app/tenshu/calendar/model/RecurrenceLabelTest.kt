package app.tenshu.calendar.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The recurrence picker's words and choices (CA-8d).
 *
 * ⚠ Both were private to the Compose editor until the SwiftUI one needed them. Two copies of
 * user-visible copy is how "Yearly" becomes "Annually" on one platform — these tests are what stop
 * the shared version drifting instead.
 */
class RecurrenceLabelTest {

    /**
     * ⚠⚠ `CUSTOM` is absent from the offer list, and its absence is the whole point: it is a state
     * an event can already be in, never a choice a user can make. Offering it would let someone
     * turn a rule we understand into one we deliberately refuse to touch.
     */
    @Test
    fun custom_is_never_offered_as_a_choice() {
        assertFalse(RecurrencePreset.CUSTOM in RecurrencePreset.OFFERABLE)
        assertEquals(5, RecurrencePreset.OFFERABLE.size)
    }

    @Test
    fun the_offer_list_is_in_picker_order() {
        assertEquals(
            listOf("Never", "Daily", "Weekly", "Monthly", "Yearly"),
            RecurrencePreset.OFFERABLE.map { it.label() },
        )
    }

    /** ⚠ …but `CUSTOM` still has a label: the editor must be able to *show* an imported rule. */
    @Test
    fun custom_can_still_be_shown() {
        assertEquals("Custom", RecurrencePreset.CUSTOM.label())
    }

    @Test
    fun every_preset_has_a_label_of_its_own() {
        val labels = RecurrencePreset.entries.map { it.label() }

        assertEquals(labels.size, labels.distinct().size, "two presets sharing a word is unreadable")
        assertTrue(labels.none { it.isBlank() })
    }
}
