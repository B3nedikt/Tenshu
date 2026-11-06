package org.tenshu.calendar.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.tenshu.calendar.CalendarPreferences
import org.tenshu.core.common.createTestContext
import org.tenshu.preference.PreferenceScope

/**
 * Pins the two decisions in `CalendarPreferences.HiddenCalendars` that a later
 * edit could silently reverse — neither of which any behavioural test would
 * catch, because both failure modes look like working code.
 */
class CalendarPreferencesTest {

    private val ctx = createTestContext()

    /**
     * ⭐ Visibility is per-device, not synced (`calendar-app.md` §3 D4). Flipping
     * this to `REALM` would make hiding a calendar on the phone hide it on the
     * desktop, and — on an `Eager` row — turn every toggle into an encrypted
     * block pushed to the relay.
     */
    @Test
    fun visibility_is_device_scoped() {
        assertEquals(PreferenceScope.DEVICE, CalendarPreferences.HiddenCalendars.scope)
    }

    /**
     * ⚠⚠ **The polarity is load-bearing.** The set stores what is *hidden*, so a
     * calendar nobody has an opinion about is visible **by construction** — no
     * write needed at creation, and a device that has never heard of a calendar
     * cannot render it hidden.
     *
     * Storing the *visible* set instead would invert that: every newly created
     * or newly synced calendar would be invisible on every device that had not
     * yet written its own row, which presents to the user as their data having
     * vanished. Nothing about that failure looks like a bug in a test — the code
     * would work exactly as written.
     */
    @Test
    fun the_default_hides_nothing_so_a_new_calendar_is_visible_without_a_write() {
        val default = CalendarPreferences.HiddenCalendars.default
        assertTrue(default.isEmpty(), "the default must hide nothing")

        val neverSeenBefore = "calendar-from-a-device-that-just-paired"
        assertFalse(
            neverSeenBefore in default,
            "an unknown calendar must be visible without any device having written a row",
        )
    }

    /** The id is a stored key: changing it silently orphans every device's row. */
    @Test
    fun the_preference_id_is_stable() {
        assertEquals("calendar.view.hiddenCalendars", CalendarPreferences.HiddenCalendars.id)
    }

    /** Round-trips through the list adapter, including the empty default. */
    @Test
    fun hidden_ids_round_trip_through_the_adapter() {
        val adapter = CalendarPreferences.HiddenCalendars.adapter
        listOf(
            emptyList(),
            listOf("work"),
            listOf("work", "health", "family"),
        ).forEach { value ->
            assertEquals(value, adapter.fromData(adapter.toData(value, ctx)))
        }
    }
}
