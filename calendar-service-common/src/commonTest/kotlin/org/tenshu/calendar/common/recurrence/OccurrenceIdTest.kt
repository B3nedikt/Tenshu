package org.tenshu.calendar.common.recurrence

import kotlinx.datetime.LocalDateTime
import org.tenshu.core.entity.DataId
import org.tenshu.core.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The occurrence identity (`control-cross-app-open.md` CO-D5).
 *
 * ⚠⚠ These are pure-value tests with no context, using `DataId`'s context-free `create` arm — the
 * shape is what is under test, not the store, and an occurrence never reaches a store anyway.
 */
class OccurrenceIdTest {

    @Test
    fun theTypeStringMatchesTheOneThePlatformRoutesOn() {
        // ⚠⚠ `core-common`'s `HandlerTable` spells this literal out rather than depending on
        // `:calendar-service-common` — the platform must not learn what a calendar is to route to
        // one. This test is the other half of that deliberate duplication, and the cheap version of
        // what Rei's declared `handles` set does properly at TT-4. If it fails, an occurrence is
        // addressable and unroutable, which looks like "nothing happened" at the tap.
        assertEquals("tenshu/calendar/eventOccurrence", OccurrenceId.TYPE)
    }

    @Test
    fun bothPartsSurviveARoundTrip() {
        val id = OccurrenceId.create(
            context = null,
            eventId = "evt-7",
            recurrenceId = formatRecurrenceId(LocalDateTime(2026, 7, 16, 10, 0)),
        )

        assertEquals("evt-7" to "2026-07-16T10:00", OccurrenceId.parse(id))
    }

    @Test
    fun theRecurrenceHalfIsTheCanonicalJoinKey() {
        // ⚠ `formatRecurrenceId` is single-sourced precisely because two sides formatting it
        // differently silently orphans every override. A third speller here would be that bug in a
        // new place, so this pins that the id carries exactly what that function produces.
        val start = LocalDateTime(2026, 7, 16, 10, 0)
        val id = OccurrenceId.create(null, eventId = "evt-7", recurrenceId = formatRecurrenceId(start))

        assertEquals(formatRecurrenceId(start), OccurrenceId.parse(id)?.second)
    }

    @Test
    fun anIdOfAnotherTypeIsNotAnOccurrence() {
        val event = DataId.StringDataId.create(type = "tenshu/calendar/event", keyValue = "evt-7")

        assertNull(OccurrenceId.parse(event))
    }

    @Test
    fun aCompositeIdWithTheWrongPartsIsRefusedRatherThanGuessedAt() {
        // ⚠⚠ Order is the contract — `keyValues` is positional like every composite here — so the
        // part types are checked rather than assumed. A mis-ordered id must fail HERE, loudly,
        // instead of opening the wrong occurrence.
        val swapped = DataId.CompositeEntityDataId.create(
            type = OccurrenceId.TYPE,
            keyValues = listOf(
                DataId.StringDataId.create(
                    type = "${OccurrenceId.TYPE}/recurrenceId",
                    keyValue = "2026-07-16T10:00",
                ),
                DataId.StringDataId.create(type = "${OccurrenceId.TYPE}/eventId", keyValue = "evt-7"),
            ),
        )

        assertNull(OccurrenceId.parse(swapped))
    }
}
