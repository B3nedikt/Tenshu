package app.tenshu.calendar.data

import app.tenshu.calendar.data.external.ExternalCalendar
import app.tenshu.calendar.data.external.ExternalSnapshot
import app.tenshu.reachability.UnreachableReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDate
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence

/**
 * A subscribed feed, as a reader sees it.
 *
 * ⚠ [lastFetchedEpochMs] is `0` for *never fetched*, which the UI must render as
 * "not yet" rather than as the epoch — a feed added seconds ago and one that has
 * been failing for a week are different things a user needs to tell apart.
 */
data class CalendarSubscriptionSummary(
    val subscriptionId: String,
    val calendarId: String,
    val url: String,
    val enabled: Boolean,
    val lastFetchedEpochMs: Long,
)

/**
 * What an import did.
 *
 * ⚠⚠ [skipped] is surfaced, not swallowed. An import that says "42 events" when
 * the file held 44 is how a user loses two appointments and never finds out.
 */
data class IcsImportResult(
    val imported: Int,
    val overrides: Int,
    val skipped: Int,
) {
    val total: Int get() = imported + overrides
}

/**
 * What a *Copy once* did (`calendar-external-sources.md` CX-1).
 *
 * @property copied series written, new or not.
 * @property added how many of [copied] were **new** — 0 for a second copy of an unchanged
 *   calendar, which is what lets the UI say "nothing new" instead of "copied 412 events" again.
 * @property skipped ⚠ surfaced, never swallowed — the [IcsImportResult] rule.
 */
data class ExternalCopyResult(
    val calendarId: String,
    val calendarName: String,
    val copied: Int,
    val added: Int,
    val overrides: Int,
    val skipped: Int,
)

/**
 * A calendar, as a reader sees it — the stored row plus this device's view state.
 *
 * ⚠ [visible] is *not* a field on the row: it is derived from the DEVICE-scoped
 * `CalendarPreferences.HiddenCalendars` set, which is why it can differ between
 * a phone and a laptop showing the same realm.
 */
data class CalendarSummary(
    val calendarId: String,
    val name: String,
    val colorArgb: Int,
    val sensitive: Boolean,
    val visible: Boolean,
    /**
     * ⚠⚠ Filled from elsewhere — a CA-7 subscription or a CX-1b mirror — and so never a target
     * for the user's own writes: their next refresh overwrites an edit and sweeps away an event
     * the user added. The editor offers only calendars where this is false and opens their events
     * read-only. One flag, so no screen re-derives the rule from the two tables.
     */
    val readOnly: Boolean = false,
)

/**
 * The Calendar app's data seam (CA-1b).
 *
 * ⚠⚠ **Windowed, never "all events".** Every read takes a civil date range,
 * because a recurring series has no finite occurrence list — asking for "all
 * events" is not a query this data model can answer, and an API shaped that way
 * would force either an invented horizon or an unbounded expansion. The window
 * is the honest interface.
 *
 * ⚠ Dates in, dates out: this seam speaks **civil time**, never instants
 * (`calendar-app.md` §3 D3). A caller that needs an instant asks the occurrence
 * for one, passing its own zone — which keeps "whose zone?" a question the
 * caller answers rather than one this layer guesses.
 *
 * ⚠ Compose-free by construction, so an iOS shell can consume it directly
 * (House Rule #5, the `:basemap-core` D7 precedent).
 */
interface CalendarRepository {

    /**
     * Whether Control can currently serve reads — `null` until the first outcome (`ADR 0025`).
     * Calendar has no store of its own: every read goes over IPC, so an unreachable Control folds
     * the whole grid to empty, and this is what lets the shell say *why* instead of rendering a
     * blank month.
     *
     * ⚠ Defaults to a permanent `true` so the mock, previews and any non-IPC implementation keep
     * compiling and never show a banner they cannot substantiate.
     */
    fun controlReachable(): Flow<Boolean?> = flowOf(true)

    /**
     * *Why* Control is unavailable — `null` while it is fine or not yet known. [controlReachable]
     * decides **whether** the banner shows; this decides what it says, because "not installed",
     * "asleep" and "refusing on a schema version" have three different fixes and only one of them
     * is waiting.
     *
     * ⚠ Defaults to a permanent `null`, which renders the pre-SK-1 words.
     */
    fun controlUnreachableReason(): kotlinx.coroutines.flow.StateFlow<UnreachableReason?> = app.tenshu.reachability.NO_UNREACHABLE_REASON

    /**
     * The user asked to check again ("Try again" on the checking gate's retry wall): re-probe Control
     * and re-read the realm now rather than at the next poll. A no-op without an engine.
     */
    fun recheckControl() = Unit

    /** Every calendar, with this device's visibility applied. */
    suspend fun calendars(): List<CalendarSummary>

    /**
     * Occurrences intersecting `[from, to]` inclusive, sorted by effective start.
     *
     * ⚠ Hidden calendars are excluded by default — the caller usually wants
     * *what this device shows*, and having to remember to filter is how a hidden
     * calendar reappears in one surface. Pass `includeHidden = true` for the
     * cases that genuinely want everything (an export, a reminder scheduler).
     */
    suspend fun occurrences(
        from: LocalDate,
        to: LocalDate,
        includeHidden: Boolean = false,
    ): List<ResolvedOccurrence>

    /** Convenience for a single day. */
    suspend fun occurrencesOn(day: LocalDate, includeHidden: Boolean = false): List<ResolvedOccurrence> =
        occurrences(day, day, includeHidden)

    /**
     * `[from, to]` as a **live** read: re-emits whenever an event or an override in
     * the window changes, whoever changed it — this app, Mia, an `.ics` import, a
     * subscription refresh, or another device once the block syncs.
     *
     * ⚠⚠ **Hidden calendars are included, unlike [occurrences].** Visibility is a
     * device preference, and joining it in here would put a preference read back on
     * the write's own code path — the CA-2g bug — as well as paying for that read on
     * every unrelated event change. Collect [observeHiddenCalendars] alongside and
     * filter; `CalendarViewModel` is the worked example, and doing it there is what
     * makes a visibility toggle instant instead of a round trip.
     *
     * ⚠ A failed read emits an empty list rather than throwing. This flow is
     * collected for the life of a screen, and a throw would take the screen with it.
     */
    fun observeOccurrences(from: LocalDate, to: LocalDate): Flow<List<ResolvedOccurrence>>

    /** [calendars] as a live read — a rename, recolour or new calendar arrives on its own. */
    fun observeCalendars(): Flow<List<CalendarSummary>>

    /** [subscriptions] as a live read, so a background fetch updates its own row. */
    fun observeSubscriptions(): Flow<List<CalendarSubscriptionSummary>>

    /** [defaultCalendar] as a live read. */
    fun observeDefaultCalendar(): Flow<String>

    // ── view state ───────────────────────────────────────────────────────────

    /** Show or hide a calendar **on this device only**. */
    suspend fun setCalendarVisible(calendarId: String, visible: Boolean)

    /**
     * This device's hidden-calendar set, re-emitting whenever it changes.
     *
     * ⚠⚠ **A `Flow`, and the only correct way to observe visibility — a snapshot
     * read taken after [setCalendarVisible] returns observes the value from
     * *before* the write.** The write lands through the store's write mailbox, so
     * "write, then re-read to see what changed" is a race that loses more often
     * than it wins. It is also exactly how CA-2f shipped a sidebar whose
     * checkboxes reverted and whose grid never changed, with the preference row
     * sitting correctly in the store the whole time.
     *
     * Collect this as the source of truth, treat writes as fire-and-forget, and
     * update local state optimistically for instant feedback — the emission is the
     * correction. The same rule the Drive settings seams follow.
     *
     * ⚠ Emits the default (**nothing hidden**) first, then the stored rows: on a
     * device whose relation holds no rows the underlying stream never emits at all,
     * so a consumer waiting for a first emission would wait forever.
     */
    fun observeHiddenCalendars(): Flow<Set<String>>

    // ── calendar management (CA-5f) ──────────────────────────────────────────

    /**
     * Creates or updates a calendar, keyed on [calendarId].
     *
     * ⚠ An upsert, matching the op: the row is last-writer-wins, so a separate
     * "update" would give the caller a distinction storage does not make. A
     * blank [calendarId] means create, and the id comes back.
     *
     * ⚠⚠ [sensitive] is the **lock-screen control** (Q10). It is a synced field,
     * unlike visibility: which of your calendars are private is a statement about
     * the data, not about the device you are holding.
     */
    suspend fun saveCalendar(
        calendarId: String,
        name: String,
        colorArgb: Int,
        sensitive: Boolean,
    ): String

    /**
     * Deletes a calendar **and every event in it**.
     *
     * ⚠⚠ The cascade is the op's, not this seam's — see
     * `DeleteCalendarImplementation`. What *is* this seam's job is re-pointing
     * `DefaultCalendarId` when the deleted calendar was it: leaving it dangling
     * would make every editor-less create (Mia's path) fail with "no default
     * calendar", from a screen that never mentioned defaults.
     */
    suspend fun deleteCalendar(calendarId: String)

    /** The calendar a new event lands in when the caller does not name one. */
    suspend fun defaultCalendar(): String

    /** Makes [calendarId] that calendar. */
    suspend fun setDefaultCalendar(calendarId: String)

    // ── first run ────────────────────────────────────────────────────────────

    /**
     * Ensures the realm has at least one calendar and that
     * `CalendarPreferences.DefaultCalendarId` names it.
     *
     * ⭐⭐ **This is what closes CA-1's loose end.** `SaveEvent` resolves an
     * absent calendar from that preference and *fails loudly* when it is unset —
     * correct, because guessing would file events differently on different
     * devices, but it means a fresh realm cannot create an event at all until
     * something seeds it. §6.4 said CA-2 owns that, because CA-2 is the first
     * phase with a place to do it.
     *
     * ⚠⚠ **Idempotent, and it must stay that way.** Every app launch calls this,
     * and it runs on every paired device — so it seeds only when there is *no*
     * calendar at all and no default set. Creating "Personal" unconditionally
     * would mint one per launch per device; writing the preference
     * unconditionally would fight the user's own choice on every start.
     *
     * ⚠ It deliberately does **not** create a calendar when one already exists
     * but no default is set: which of several calendars should receive
     * unattributed events is a question for the user, not a coin toss, and CA-3's
     * editor is where it gets asked.
     */
    suspend fun ensureDefaultCalendar()

    // ── writes (CA-3) ────────────────────────────────────────────────────────

    /** Creates or replaces an event. [EventDraft.eventId] blank means create. */
    suspend fun saveEvent(draft: EventDraft): String

    /**
     * Applies an edit to **one occurrence** of a series, leaving the rest alone.
     *
     * ⚠⚠ Not the same operation as [saveEvent] with a changed start. This writes
     * an override keyed to the occurrence's *original* start; editing the series
     * would move every occurrence. Which of the two a user meant is the question
     * [EditScope] exists to make the editor ask.
     */
    suspend fun saveOccurrence(draft: EventDraft, recurrenceId: String)

    /** Deletes a whole series. */
    suspend fun deleteEvent(eventId: String)

    /** Skips one occurrence — narrows the rule's output, destroys nothing. */
    suspend fun deleteOccurrence(eventId: String, recurrenceId: String)

    // ── subscriptions (CA-7) ─────────────────────────────────────────────────

    /** Every remote feed this realm subscribes to. */
    suspend fun subscriptions(): List<CalendarSubscriptionSummary>

    /**
     * Subscribes to [url], creating the calendar it fills.
     *
     * ⚠⚠ **Creating the subscription is the consent** (§2.4 / Q9): from here the
     * device fetches this publisher on a schedule, disclosing its IP and the fact
     * that the user holds this calendar. The copy that leads to this call owes
     * the user that, and so does the threat model (A5's CA-7 amendment).
     *
     * @return the subscription id, or `null` when [url] is not one we will fetch
     *   — `https://` and `webcal://` only, and plain `http` is refused rather
     *   than upgraded.
     */
    suspend fun addSubscription(url: String, name: String): String?

    /**
     * Pauses or resumes a feed.
     *
     * ⚠ Distinct from removing: pausing stops the egress and **keeps** the events
     * already fetched.
     */
    suspend fun setSubscriptionEnabled(subscriptionId: String, enabled: Boolean)

    /** Unsubscribes, **and deletes the calendar the feed filled**. */
    suspend fun removeSubscription(subscriptionId: String)

    /** Fetches every enabled feed now, rather than waiting for the schedule. */
    suspend fun refreshSubscriptions()

    // ── interop (CA-6) ───────────────────────────────────────────────────────

    /**
     * Imports an `.ics` payload into [calendarId].
     *
     * ⚠⚠ **Idempotent by `UID`.** An event's id is the file's `UID`, so
     * re-importing the same file replaces rather than duplicates — which is what
     * makes "import it again after I fixed something" a safe thing for a user to
     * try. A block with no `UID` gets a content-derived one for the same reason.
     *
     * ⚠ [origin] is free text shown to the user (a file name); never parsed.
     */
    suspend fun importIcs(
        text: String,
        calendarId: String,
        origin: String = "",
    ): IcsImportResult

    /**
     * Exports [calendarId], or every calendar when it is blank.
     *
     * ⚠⚠ Re-emits each event's stored `EventSource` with the native fields
     * written over it, so properties Tenshu never modelled come back out — see
     * `IcsExport`. An export that regenerated from our fields alone would pass
     * every obvious test and quietly make the sidecar pointless.
     */
    suspend fun exportIcs(calendarId: String = ""): String

    // ── calendars the user already has (CX-1) ────────────────────────────────

    /**
     * Copies [snapshot] — one calendar read off this device's OS — into a Tenshu calendar of its
     * own, made on first copy (`calendar-external-sources.md` *Copy once*).
     *
     * ⚠⚠ **Converges, never doubles.** The target calendar's id derives from the source, and each
     * event's from the target plus its `UID`, so copying again — after fixing something at the
     * source, or from a second phone — updates the same rows. ⚠ And an existing target keeps the
     * name and colour the user may have given it since.
     *
     * ⚠ The result is an ordinary, editable calendar; nothing links it back to the source.
     */
    suspend fun copyExternalCalendar(
        source: ExternalCalendar,
        snapshot: ExternalSnapshot,
    ): ExternalCopyResult

    // ── reminders (CA-5e) ────────────────────────────────────────────────────

    /**
     * The offsets an event currently alerts at, ascending.
     *
     * ⚠ Minutes **before** the occurrence starts; negative means after, which is
     * how an all-day reminder ("on the day at 9 a.m.") is expressed. See
     * `Reminder`.
     */
    suspend fun remindersFor(eventId: String): List<Int>

    /**
     * Makes [eventId]'s reminders exactly [minutesBefore].
     *
     * ⚠⚠ **A set, not add/remove.** The editor holds the whole list and hands it
     * over; anything absent is deleted, which is what makes removing the last
     * reminder expressible at all. An add-only API would have no way to say
     * "none".
     */
    suspend fun setReminders(eventId: String, minutesBefore: List<Int>)

    /**
     * The offset a **new** event starts with, or `null` for "no reminder".
     *
     * ⚠⚠ Takes [allDay] because the two are different quantities, not the same
     * one measured twice: an all-day event starts at 00:00, so the timed default
     * of "10 minutes before" would alert at 23:50 the night before. Two
     * preferences, and the caller must pick the right one — which is why this
     * asks rather than exposing both and hoping.
     *
     * ⚠ Negative is the *sentinel* for the timed preference (-1 = off) but a
     * *value* for the all-day one (-540 = 09:00 on the day). Resolving that here
     * is the point of the method; callers see `null` or a usable offset.
     */
    suspend fun defaultReminderMinutes(allDay: Boolean): Int?

    /** Records that the user dismissed one occurrence's reminder. */
    suspend fun dismissReminder(reminderId: String, recurrenceId: String)

    /**
     * Silences one occurrence's reminder until [untilEpochMs].
     *
     * ⚠ An instant, not a duration: "in ten minutes" has to be resolved against
     * a clock *somewhere*, and the caller is the only layer that has one — this
     * seam and everything below it stay clockless.
     */
    suspend fun snoozeReminder(reminderId: String, recurrenceId: String, untilEpochMs: Long)
}

/**
 * Whether an edit to a recurring event applies to one occurrence or all of them.
 *
 * ⚠⚠ **The editor must ask, never assume.** Both answers are destructive in
 * opposite directions: treating a single-occurrence edit as a series edit moves
 * every future occurrence, and treating a series edit as a single one leaves the
 * user's change silently un-applied everywhere else. Stock calendars all ask,
 * and they ask for this reason.
 *
 * ⚠ A non-recurring event has only one answer, and the editor must not show the
 * prompt for it — a dialog with one meaningful option is a dialog that trains
 * people to dismiss dialogs.
 */
enum class EditScope { THIS_OCCURRENCE, ALL_OCCURRENCES }

/**
 * The editable shape of an event — what a form collects, before it becomes rows.
 *
 * ⚠ Times are civil strings in the same ISO form the schema stores
 * (`2026-08-05T09:00`), not instants: the editor edits wall-clock time, which is
 * what the user typed and what §3 D3 keeps canonical.
 */
data class EventDraft(
    /** Blank to create. */
    val eventId: String = "",
    val calendarId: String,
    val title: String,
    val startLocal: String,
    val endLocal: String,
    val timeZoneId: String = "",
    val allDay: Boolean = false,
    val location: String = "",
    val notes: String = "",
    /** A raw `RRULE`, or `""` for a one-off. */
    val recurrenceRule: String = "",
)
