package app.tenshu.calendar.data.external

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ExternalCopyIdsTest {

    private fun source(sourceKey: String, handle: String = "1") = ExternalCalendar(
        kind = ExternalSourceKind.ANDROID_PROVIDER,
        sourceKey = sourceKey,
        handle = handle,
        name = "Work",
        accountName = "me@gmail.com",
        accountType = "com.google",
        colorArgb = null,
        holdsEvents = true,
    )

    /** ⭐ A second copy — from this phone or another — lands where the first one did. */
    @Test
    fun the_target_calendar_follows_the_source_not_the_device() {
        assertEquals(
            ExternalCopyIds.calendarId(source("com.google|me|work", handle = "4")),
            ExternalCopyIds.calendarId(source("com.google|me|work", handle = "17")),
        )
        assertNotEquals(
            ExternalCopyIds.calendarId(source("com.google|me|work")),
            ExternalCopyIds.calendarId(source("com.google|me|home")),
        )
    }

    /** ⚠⚠ An invite in two calendars shares its `UID`; it must not become one row. */
    @Test
    fun one_uid_in_two_calendars_is_two_events() {
        assertNotEquals(
            ExternalCopyIds.eventId("copy-a", "meeting@corp", emptySet()),
            ExternalCopyIds.eventId("copy-b", "meeting@corp", emptySet()),
        )
    }

    @Test
    fun copying_again_into_the_same_calendar_converges() {
        assertEquals(
            ExternalCopyIds.eventId("copy-a", "meeting@corp", emptySet()),
            ExternalCopyIds.eventId("copy-a", "meeting@corp", setOf("copy-a~meeting@corp")),
        )
    }

    /** A calendar first brought in as an `.ics` file keeps its rows instead of doubling. */
    @Test
    fun a_row_the_ics_import_already_wrote_in_the_target_is_adopted() {
        assertEquals("meeting@corp", ExternalCopyIds.eventId("copy-a", "meeting@corp", setOf("meeting@corp")))
    }
}
