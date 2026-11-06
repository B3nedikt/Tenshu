package app.tenshu.calendar.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDate
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence

/**
 * The **honest empty** calendar: no calendars, no events, and every write refused
 * with a reason the UI can print.
 *
 * This is what a shell binds when there is no live client behind it — on iOS,
 * a device Control has not provisioned yet. It exists so that state is a
 * *rendered surface* rather than an accident of a failed read.
 *
 * ⚠⚠ **The alternative is worse than it sounds.** A mock behind the shipping gate
 * would put invented appointments in front of someone who has connected nothing —
 * and a calendar's failure mode is not "looks wrong", it is "you were somewhere
 * else".
 *
 * ⚠⚠ **Writes throw; they do not quietly succeed.** [CalendarRepository] has no
 * result type — a write returns `Unit` or an id — so the only two things a
 * disconnected implementation can do are return normally or throw, and returning
 * normally means the editor closes on a save that never happened, which
 * `EventEditorScreen` guards against; every view model here folds a throw into
 * `errorMessage`.
 *
 * ⚠ [ensureDefaultCalendar] is the **one deliberate exception**: it no-ops. It runs
 * unguarded from the shell's launch effect, its whole contract is "make sure, or
 * leave it alone", and on a device with no realm there is nothing to ensure and
 * nothing the user could do about being told.
 */
class NotConnectedCalendarRepository : CalendarRepository {

    /**
     * ⚠ One sentence, and it names *Control* rather than "the network" — which is
     * what is actually missing, and the difference between a blank screen that
     * looks like data loss and one that explains itself.
     */
    private fun refuse(): Nothing =
        throw IllegalStateException(NOT_SET_UP)

    // ── reads: empty, and empty is a *result* ────────────────────────────────

    override suspend fun calendars(): List<CalendarSummary> = emptyList()

    override suspend fun occurrences(
        from: LocalDate,
        to: LocalDate,
        includeHidden: Boolean,
    ): List<ResolvedOccurrence> = emptyList()

    override suspend fun defaultCalendar(): String = ""

    override suspend fun subscriptions(): List<CalendarSubscriptionSummary> = emptyList()

    override suspend fun remindersFor(eventId: String): List<Int> = emptyList()

    /**
     * ⚠ `null` — "no reminder" — rather than a refusal: this is read while building
     * a *form*, and an editor that cannot open is a worse answer than one whose
     * reminder field starts empty. The save is where the refusal belongs.
     */
    override suspend fun defaultReminderMinutes(allDay: Boolean): Int? = null

    // ── writes: refused, out loud ────────────────────────────────────────────

    override suspend fun setCalendarVisible(calendarId: String, visible: Boolean) = refuse()

    /**
     * ⚠ Nothing hidden, not a refusal: this is a *read*, and the rest of this class
     * answers reads with an empty result rather than an error. Hiding a calendar the
     * user could then not unhide is the one wrong answer here.
     */
    override fun observeHiddenCalendars(): Flow<Set<String>> = flowOf(emptySet())

    override fun observeOccurrences(from: LocalDate, to: LocalDate): Flow<List<ResolvedOccurrence>> =
        flowOf(emptyList())

    override fun observeCalendars(): Flow<List<CalendarSummary>> = flowOf(emptyList())

    override fun observeSubscriptions(): Flow<List<CalendarSubscriptionSummary>> =
        flowOf(emptyList())

    override fun observeDefaultCalendar(): Flow<String> = flowOf("")

    override suspend fun saveCalendar(
        calendarId: String,
        name: String,
        colorArgb: Int,
        sensitive: Boolean,
    ): String = refuse()

    override suspend fun deleteCalendar(calendarId: String) = refuse()

    override suspend fun setDefaultCalendar(calendarId: String) = refuse()

    /** ⚠ See the class KDoc — the one write that returns instead of throwing. */
    override suspend fun ensureDefaultCalendar() = Unit

    override suspend fun saveEvent(draft: EventDraft): String = refuse()

    override suspend fun saveOccurrence(draft: EventDraft, recurrenceId: String) = refuse()

    override suspend fun deleteEvent(eventId: String) = refuse()

    override suspend fun deleteOccurrence(eventId: String, recurrenceId: String) = refuse()

    override suspend fun addSubscription(url: String, name: String): String? = refuse()

    override suspend fun setSubscriptionEnabled(subscriptionId: String, enabled: Boolean) = refuse()

    override suspend fun removeSubscription(subscriptionId: String) = refuse()

    override suspend fun refreshSubscriptions() = refuse()

    override suspend fun importIcs(
        text: String,
        calendarId: String,
        origin: String,
    ): IcsImportResult = refuse()

    override suspend fun exportIcs(calendarId: String): String = refuse()

    override suspend fun copyExternalCalendar(
        source: app.tenshu.calendar.data.external.ExternalCalendar,
        snapshot: app.tenshu.calendar.data.external.ExternalSnapshot,
    ): ExternalCopyResult = refuse()

    override suspend fun setReminders(eventId: String, minutesBefore: List<Int>) = refuse()

    override suspend fun dismissReminder(reminderId: String, recurrenceId: String) = refuse()

    override suspend fun snoozeReminder(
        reminderId: String,
        recurrenceId: String,
        untilEpochMs: Long,
    ) = refuse()

    companion object {
        const val NOT_SET_UP: String = "Tenshu Control has not set this device up yet."
    }
}
