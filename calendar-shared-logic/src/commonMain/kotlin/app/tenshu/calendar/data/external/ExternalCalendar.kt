package app.tenshu.calendar.data.external

import org.tenshu.calendar.common.ics.ImportedEvent

/**
 * Where a calendar the user already has lives (`calendar-external-sources.md` §3).
 *
 * ⚠ Only the sources that exist are listed. The kind is never stored — it feeds
 * [ExternalCopyIds.calendarId] and the UI's grouping — so adding EventKit or a direct sign-in
 * later costs nothing here.
 */
enum class ExternalSourceKind {
    /** Android's `CalendarContract` — Google, Outlook-with-sync, DAVx5 and any other sync adapter. */
    ANDROID_PROVIDER,

    /** iOS / macOS EventKit — iCloud, Exchange, Google and CalDAV accounts added in Settings (CX-2). */
    APPLE_EVENTKIT,
}

/**
 * One calendar the OS can show us.
 *
 * @property sourceKey **stable** across devices and reinstalls where the source allows it — what
 *   [ExternalCopyIds.calendarId] hashes, so copying the same calendar again (or from a second
 *   phone) lands in the same Tenshu calendar instead of a second one.
 * @property handle the source's **local** id, which the reader needs to read it back. ⚠ Never
 *   stored and never used as identity: on Android it is a provider row id, different on every
 *   device and after every account re-sync.
 * @property holdsEvents false for a calendar the OS lists but does not sync the events of
 *   (Android's `SYNC_EVENTS = 0`). ⚠ Shown, not hidden — a user looking for their work calendar
 *   should see it is there and why it cannot be copied, not conclude it is missing.
 */
data class ExternalCalendar(
    val kind: ExternalSourceKind,
    val sourceKey: String,
    val handle: String,
    val name: String,
    val accountName: String,
    val accountType: String,
    val colorArgb: Int?,
    val holdsEvents: Boolean,
)

/**
 * One calendar's events, as the importer sees them.
 *
 * ⭐ The events are [ImportedEvent]s — the type every `.ics` path already produces — rather than
 * a new neutral record. `calendar-external-sources.md` §4.1 proposed one; it turned out
 * `ImportedEvent` already *is* that record ("one `VEVENT`, mapped onto the fields `SaveEvent`
 * takes, plus the verbatim block"), so a reader's whole job is to produce it.
 *
 * @property skipped events the source holds that could not be placed in time, or overrides whose
 *   series is not in the calendar. ⚠ Counted, never dropped silently — the CA-6 rule.
 */
data class ExternalSnapshot(
    val events: List<ImportedEvent>,
    val skipped: Int,
    /**
     * ⚠⚠ Every `UID` a skipped *series* could have been stored under. A mirror's sweep deletes
     * what the source no longer holds, and a series the reader could not convert this time is
     * still held — so the sweep spares these. All candidates, because the skipped row cannot say
     * which one an earlier, readable refresh chose; sparing too many only delays a deletion.
     */
    val unreadableUids: Set<String> = emptySet(),
    /**
     * ⚠⚠ Series (by `UID`) at least one of whose changed occurrences could not be read. The series
     * itself came through, so without this a mirror would resend it with a shorter override list —
     * and the op *sets* overrides, so a moved occurrence would snap back. Their stored overrides
     * are kept until a complete read says otherwise.
     */
    val incompleteOverridesFor: Set<String> = emptySet(),
    /**
     * ⚠⚠ Where the read was complete, for a reader that can only read a window (EventKit); null
     * means the whole calendar was read (Android's provider). A mirror refresh deletes, and drops
     * exception dates and changed occurrences, only inside it — see [SnapshotWindow].
     */
    val window: SnapshotWindow? = null,
    /**
     * For a windowed read: per series `UID`, the original dates (`yyyy-mm-dd`) of the occurrences
     * the reader actually saw. ⚠ Positive evidence that a slot is occupied as the source says — so
     * a stored move out of the window is dropped once its original slot comes back unmoved.
     */
    val observedOccurrenceDates: Map<String, Set<String>> = emptyMap(),
)

/**
 * Reads the calendars this device's OS holds (`calendar-external-sources.md` §4.2).
 *
 * ⚠ The reader is the app's, not Control's: OS data belongs to the app that uses it, the network
 * to Control (§4.4). ⚠ It assumes permission is already granted — asking is the UI's job, at the
 * one place the user acts — and throws [ExternalCalendarAccessDenied] when it is not.
 */
interface ExternalCalendarReader {

    /** Every calendar the OS lists, including ones that hold no events. */
    suspend fun calendars(): List<ExternalCalendar>

    /** All of [calendar]'s events, series and overrides. */
    suspend fun read(calendar: ExternalCalendar): ExternalSnapshot
}

/**
 * The OS refused the read — the permission is missing or was revoked between asking and reading.
 *
 * ⚠ Its own type so the UI can say *why* rather than "something went wrong": a revoked permission
 * is the one failure here the user can fix.
 */
class ExternalCalendarAccessDenied(cause: Throwable? = null) :
    Exception("Calendar access was denied", cause)
