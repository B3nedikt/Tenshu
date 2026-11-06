package app.tenshu.calendar.data

import kotlinx.datetime.LocalDate
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence
import org.tenshu.calendar.common.reminder.CalendarAlerts

/** Where a reminder tap wants to land: one occurrence, addressed by id. */
data class ReminderDeepLink(
    val eventId: String,
    val recurrenceId: String,
) {
    /**
     * The day to open, read off the recurrence id.
     *
     * ⚠ A `recurrenceId` is by definition the occurrence's **original** start, so
     * this is where the series says it happens — not necessarily where an
     * override moved it to. That is why [resolve] searches a window around this
     * day rather than asking for it exactly.
     */
    val anchorDate: LocalDate?
        get() = runCatching { LocalDate.parse(recurrenceId.substringBefore('T')) }.getOrNull()

    companion object {
        /** Parses a `CalendarAlerts` tap payload; `null` when it is not one. */
        fun parse(payload: String): ReminderDeepLink? =
            CalendarAlerts.parseTapPayload(payload)?.let { (eventId, recurrenceId) ->
                ReminderDeepLink(eventId, recurrenceId)
            }
    }
}

/**
 * Finds the occurrence a reminder tap names (CA-5f).
 *
 * ⚠⚠ **The payload carries ids, never text** — deliberately, so a *redacted*
 * reminder's title cannot ride back onto the lock screen in a `PendingIntent`
 * extra (`CalendarAlerts.tapPayload`). The cost is exactly this: the app has to
 * resolve them, after the user has unlocked and opened it, which is the point.
 *
 * ⚠ Searches a **window** around the anchor rather than that one day: an override
 * can have moved the occurrence, and its `recurrenceId` still names the original
 * slot. ⚠ One day either side, not a month — an occurrence dragged further than
 * that is rare, and the fallback (opening the anchor day) still lands the user in
 * the right neighbourhood rather than nowhere.
 */
suspend fun CalendarRepository.resolveReminderLink(link: ReminderDeepLink): ResolvedOccurrence? {
    val anchor = link.anchorDate ?: return null
    return occurrences(
        from = anchor.minusDays(SEARCH_RADIUS_DAYS),
        to = anchor.plusDays(SEARCH_RADIUS_DAYS),
        // ⚠⚠ `includeHidden`: a reminder was armed for this event, so the user
        // asked to be told. Silently refusing to open it because the calendar is
        // hidden on *this* device would make the notification unactionable.
        includeHidden = true,
    ).firstOrNull { it.eventId == link.eventId && it.recurrenceId == link.recurrenceId }
}

private const val SEARCH_RADIUS_DAYS = 1

private fun LocalDate.plusDays(days: Int) = LocalDate.fromEpochDays(toEpochDays() + days)
private fun LocalDate.minusDays(days: Int) = LocalDate.fromEpochDays(toEpochDays() - days)
