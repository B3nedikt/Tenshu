@file:OptIn(ExperimentalJsExport::class)

package app.tenshu.sdk

import app.tenshu.error.ErrorCopyKey
import app.tenshu.error.errorCopyKey
import app.tenshu.sdk.internal.failureResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toInstant
import org.tenshu.calendar.CalendarService
import org.tenshu.calendar.CalendarSubscription
import org.tenshu.calendar.Event
import org.tenshu.calendar.EventOverride
import org.tenshu.calendar.EventSource
import org.tenshu.calendar.Reminder
import org.tenshu.calendar.allDay
import org.tenshu.calendar.calendarId
import org.tenshu.calendar.colorArgb
import org.tenshu.calendar.enabled
import org.tenshu.calendar.endLocal
import org.tenshu.calendar.eventId
import org.tenshu.calendar.exceptionDates
import org.tenshu.calendar.lastFetchedEpochMs
import org.tenshu.calendar.location
import org.tenshu.calendar.minutesBefore
import org.tenshu.calendar.name
import org.tenshu.calendar.notes
import org.tenshu.calendar.raw
import org.tenshu.calendar.recurrenceId
import org.tenshu.calendar.recurrenceRule
import org.tenshu.calendar.sensitive
import org.tenshu.calendar.startLocal
import org.tenshu.calendar.subscriptionId
import org.tenshu.calendar.timeZoneId
import org.tenshu.calendar.title
import org.tenshu.calendar.url
import org.tenshu.calendar.common.recurrence.EventTime
import org.tenshu.calendar.common.recurrence.OccurrenceResolver
import org.tenshu.calendar.common.recurrence.OverrideInput
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence
import org.tenshu.calendar.common.recurrence.SeriesInput
import org.tenshu.core.Data
import org.tenshu.core.TError
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.entity.Entity
import org.tenshu.core.fold
import org.tenshu.core.get
import kotlin.js.Date
import org.tenshu.calendar.Calendar as CalendarRow

/*
 * The Calendar **book** on web (`web-app-suite.md` Wave 2): the row reads and the row shapes
 * behind `Calendar`. It mirrors `TenshuCalendarRepository` (`:calendar-shared-logic`) without
 * compiling that module to JS, as `ContactsBook.kt` does.
 *
 * ⚠⚠ That makes this the second reader of the same relations. What it copies, with its reason:
 *  - the projections (one per relation, used by both the one-shot and the live read);
 *  - overrides keep **absence** (`?.get()`), never `""`, or a moved occurrence loses its title;
 *  - the occurrence fold is the pure `OccurrenceResolver`, unchanged.
 * Where it deliberately differs from native: a failed read is a **failure**, never an empty list
 * (the web has no reachability banner to explain a blank month), and every write's `TResult` is
 * carried rather than dropped.
 */

/** A calendar plus this browser's visibility (`HiddenCalendars` is DEVICE-scoped). */
@JsExport
class CalendarJs internal constructor(
    val calendarId: String,
    val name: String,
    /** ARGB, alpha byte included (`CalendarPalette`). */
    val colorArgb: Int,
    /** The lock-screen control (Q10): synced, unlike [visible]. */
    val sensitive: Boolean,
    val visible: Boolean,
)

/**
 * One occurrence of a series in a window, after overrides.
 *
 * ⚠ [startLocal]/[endLocal] are **civil** ISO strings (`2026-10-26T09:00`) in the event's own
 * zone ([zoneId], `""` = floating); [recurrenceId] is the occurrence's **original** start and the
 * handle to edit or skip it. [startEpochMs]/[endEpochMs] are the instants, resolved in [zoneId], or
 * in the reader's zone for a floating or all-day event.
 *
 * ⚠⚠ [zoneUnresolved]: [zoneId] is not in this runtime's zone database (a device with a newer
 * tzdb wrote it). The occurrence still renders — its instants are placed in the **reader's** zone,
 * as if it were floating — and the flag is what lets the UI say so. Editing it stays refused
 * (`error_calendar_unknown_zone`): a save would have to pick a zone the user never chose.
 */
@JsExport
class OccurrenceJs internal constructor(
    val eventId: String,
    val calendarId: String,
    val recurrenceId: String,
    val title: String,
    val location: String,
    val notes: String,
    val startLocal: String,
    val endLocal: String,
    val zoneId: String,
    val allDay: Boolean,
    val isOverridden: Boolean,
    /** The series' raw `RRULE`, `""` for a one-off: the editor's "this or all?" question needs it. */
    val recurrenceRule: String,
    val isRecurring: Boolean,
    val startEpochMs: Double,
    val endEpochMs: Double,
    val zoneUnresolved: Boolean,
)

/** A subscribed feed. ⚠ [lastFetchedEpochMs] `0` means *never fetched*, not the epoch. */
@JsExport
class SubscriptionJs internal constructor(
    val subscriptionId: String,
    val calendarId: String,
    val url: String,
    val enabled: Boolean,
    val lastFetchedEpochMs: Double,
)

/** The catalogue keys this surface mints itself; every other failure carries its derived key. */
internal object CalendarWebErrorKeys {
    /** A date, draft or zone argument that is not the published shape: a caller bug, said loudly. */
    val INVALID_ARGUMENT = ErrorCopyKey("error_calendar_invalid_argument")
    /** A non-blank `RRULE` with no usable `FREQ`: it would silently save as a one-off. */
    val INVALID_RULE = ErrorCopyKey("error_calendar_invalid_rule")
    /** An IANA zone this runtime does not know (an event's, or the reader's). */
    val UNKNOWN_ZONE = ErrorCopyKey("error_calendar_unknown_zone")
    /** The write names an event, calendar or subscription that does not exist. */
    val NOT_FOUND = ErrorCopyKey("error_calendar_not_found")
    /** `.ics` text with no `VEVENT` in it. */
    val NONE_IN_FILE = ErrorCopyKey("error_calendar_none_in_file")
    /** Not `https://` / `webcal://` (`http://` is refused, never upgraded: CA-7a). */
    val SUBSCRIPTION_URL = ErrorCopyKey("error_calendar_subscription_url")
    /** No sync device id yet, so a per-device visibility row has no key to live under. */
    val NO_DEVICE = ErrorCopyKey("error_calendar_no_device")
}

internal fun calendarFailure(error: TError): dynamic =
    failureResult(error.errorCopyKey().value, error.toString())

internal fun calendarFailure(key: ErrorCopyKey, message: String? = null): dynamic =
    failureResult(key.value, message)

// ── time zones ───────────────────────────────────────────────────────────────

/**
 * ⚠⚠ **The IANA database, bundled.** kotlinx-datetime delegates to js-joda on Kotlin/JS, and
 * `@js-joda/core` alone knows UTC and fixed offsets: without this `TimeZone.of("Europe/Berlin")`
 * throws, so every zoned event's instant would be `null` (`app-notify/build.gradle.kts` records the
 * same finding for its tests). ⚠ A bare `external object` emits no import; the property is what
 * pulls the module in, and [zoneOrNull] reads it so production DCE cannot drop it. Do not "clean
 * up" either.
 */
@JsModule("@js-joda/timezone")
@JsNonModule
private external object JsJodaTimeZoneModule

private val jsJodaTimeZoneDatabase: Any = JsJodaTimeZoneModule

/** The zone for [id], or `null` when this runtime does not know it. */
internal fun zoneOrNull(id: String): TimeZone? {
    if (jsJodaTimeZoneDatabase.asDynamic() == null) return null
    return runCatching { TimeZone.of(id) }.getOrNull()
}

/**
 * The browser's own zone (`Intl`), the reader zone a blank argument stands for. A `var` so a test
 * can play a browser whose zone the bundled database does not know.
 */
internal var browserZoneId: () -> String = {
    (js("Intl.DateTimeFormat().resolvedOptions().timeZone") as? String).orEmpty().ifBlank { "UTC" }
}

/**
 * The zone a window is read in. ⚠⚠ [zone] `null` is the **fallback**, never a failure: the
 * browser's `Intl` zone is not in the bundled database (a newer tzdb than ours, or a non-IANA
 * alias), so civil times are placed with the browser's own clock, `Date`'s local constructor —
 * `getTimezoneOffset` at each instant, DST included. Failing instead would make every window with
 * a blank `readerZone` fail, i.e. the whole calendar unusable. [fallback] is surfaced on the
 * result (`readerZoneFallback`) so the UI can say so.
 */
internal class ReaderZone(val id: String, private val zone: TimeZone?) {
    val fallback: Boolean get() = zone == null

    fun epochMs(local: LocalDateTime): Double =
        if (zone != null) local.toInstant(zone).toEpochMilliseconds().toDouble()
        else Date(local.year, local.month.number - 1, local.day, local.hour, local.minute).getTime()
}

/**
 * [readerZone] as given, or the browser's own when blank; `null` only for an explicit id this
 * runtime does not know, which is a caller bug and refused as `error_calendar_unknown_zone`.
 */
internal fun readerZoneOf(readerZone: String): ReaderZone? {
    if (readerZone.isNotBlank()) return zoneOrNull(readerZone)?.let { ReaderZone(readerZone, it) }
    val browser = browserZoneId()
    return ReaderZone(browser, zoneOrNull(browser))
}

/**
 * An occurrence's instant: the event's own zone, or [reader] when it is floating or all-day — the
 * same rule as `EventOccurrence.startInstant` — or when the event's zone is unknown here
 * ([OccurrenceJs.zoneUnresolved]).
 */
internal fun ResolvedOccurrence.toJs(reader: ReaderZone): OccurrenceJs {
    val ownZone = if (allDay || zoneId.isEmpty()) null else zoneOrNull(zoneId)
    fun epochMs(local: LocalDateTime): Double =
        ownZone?.let { local.toInstant(it).toEpochMilliseconds().toDouble() } ?: reader.epochMs(local)
    return OccurrenceJs(
        eventId = eventId,
        calendarId = calendarId,
        recurrenceId = recurrenceId,
        title = title,
        location = location,
        notes = notes,
        startLocal = startLocal.toString(),
        endLocal = endLocal.toString(),
        zoneId = zoneId,
        allDay = allDay,
        isOverridden = isOverridden,
        recurrenceRule = recurrenceRule,
        isRecurring = isRecurring,
        startEpochMs = epochMs(startLocal),
        endEpochMs = epochMs(endLocal),
        zoneUnresolved = !allDay && zoneId.isNotEmpty() && ownZone == null,
    )
}

// ── row reads ────────────────────────────────────────────────────────────────
//
// ⚠⚠ Each selection is a function, so every terminal gets a fresh chain (chains mutate as they are
// lowered) — the rule `TenshuCalendarRepository` states for the same reason.

private fun <E : Data> TResult<TList<E>>.read(): Read<List<E>> =
    fold(onSuccess = { Read.Ok(it.toList()) }, onFailure = { Read.Failed(it) })

private fun CalendarService.calendarSelection() = calendars.select {
    fields(
        CalendarRow.Field.CALENDAR_ID,
        CalendarRow.Field.NAME,
        CalendarRow.Field.COLOR_ARGB,
        CalendarRow.Field.SENSITIVE,
    )
}

private fun CalendarService.eventSelection() = events.select {
    fields(
        Event.Field.EVENT_ID,
        Event.Field.CALENDAR_ID,
        Event.Field.TITLE,
        Event.Field.LOCATION,
        Event.Field.NOTES,
        Event.Field.START_LOCAL,
        Event.Field.END_LOCAL,
        Event.Field.TIME_ZONE_ID,
        Event.Field.ALL_DAY,
        Event.Field.RECURRENCE_RULE,
        Event.Field.EXCEPTION_DATES,
    )
}

private fun CalendarService.overrideSelection() = eventOverrides.select {
    fields(
        EventOverride.Field.OVERRIDE_ID,
        EventOverride.Field.EVENT_ID,
        EventOverride.Field.RECURRENCE_ID,
        EventOverride.Field.START_LOCAL,
        EventOverride.Field.END_LOCAL,
        EventOverride.Field.TITLE,
        EventOverride.Field.LOCATION,
        EventOverride.Field.NOTES,
    )
}

private fun CalendarService.subscriptionSelection() = subscriptions.select {
    fields(
        CalendarSubscription.Field.SUBSCRIPTION_ID,
        CalendarSubscription.Field.CALENDAR_ID,
        CalendarSubscription.Field.URL,
        CalendarSubscription.Field.ENABLED,
        CalendarSubscription.Field.LAST_FETCHED_EPOCH_MS,
        CalendarSubscription.Field.ETAG,
    )
}

internal suspend fun CalendarService.calendarRows(): Read<List<Entity<CalendarRow>>> =
    calendarSelection().getAll().read()

internal suspend fun CalendarService.eventRows(): Read<List<Entity<Event>>> =
    eventSelection().getAll().read()

internal suspend fun CalendarService.overrideRows(): Read<List<Entity<EventOverride>>> =
    overrideSelection().getAll().read()

internal suspend fun CalendarService.subscriptionRows(): Read<List<Entity<CalendarSubscription>>> =
    subscriptionSelection().getAll().read()

internal suspend fun CalendarService.reminderRows(): Read<List<Entity<Reminder>>> =
    reminders
        .select { fields(Reminder.Field.REMINDER_ID, Reminder.Field.EVENT_ID, Reminder.Field.MINUTES_BEFORE) }
        .getAll()
        .read()

internal suspend fun CalendarService.sourceRows(): Read<List<Entity<EventSource>>> =
    eventSources
        .select { fields(EventSource.Field.EVENT_ID, EventSource.Field.RAW, EventSource.Field.ORIGIN) }
        .getAll()
        .read()

internal fun CalendarService.calendarRowsFlow(): Flow<Read<List<Entity<CalendarRow>>>> =
    calendarSelection().flow().map { it.read() }

internal fun CalendarService.subscriptionRowsFlow(): Flow<Read<List<Entity<CalendarSubscription>>>> =
    subscriptionSelection().flow().map { it.read() }

/**
 * Events × overrides, live. ⚠ `combine`, so a change to *either* re-folds: an override edited
 * without its series being touched must still move the occurrence it overrides.
 */
internal fun CalendarService.seriesFlow(): Flow<Read<Pair<List<Entity<Event>>, List<Entity<EventOverride>>>>> =
    combine(eventSelection().flow().map { it.read() }, overrideSelection().flow().map { it.read() }) { e, o ->
        when {
            e is Read.Failed -> e
            o is Read.Failed -> o
            else -> Read.Ok((e as Read.Ok).value to (o as Read.Ok).value)
        }
    }

// ── mappings ─────────────────────────────────────────────────────────────────

/** Sorted by name, like native `calendars()`: the sidebar and the calendars screen must agree. */
internal fun List<Entity<CalendarRow>>.toCalendarsJs(hidden: Set<String>): Array<CalendarJs> =
    map { row ->
        CalendarJs(
            calendarId = row.calendarId.get(),
            name = row.name.get(),
            colorArgb = row.colorArgb.get().toInt(),
            sensitive = row.sensitive.get(),
            visible = row.calendarId.get() !in hidden,
        )
    }.sortedBy { it.name }.toTypedArray()

internal fun List<Entity<CalendarSubscription>>.toSubscriptionsJs(): Array<SubscriptionJs> =
    map {
        SubscriptionJs(
            subscriptionId = it.subscriptionId.get(),
            calendarId = it.calendarId.get(),
            url = it.url.get(),
            enabled = it.enabled.get(),
            lastFetchedEpochMs = it.lastFetchedEpochMs.get().toDouble(),
        )
    }.sortedBy { it.url }.toTypedArray()

/** Rows in, occurrences out: the fold both read paths share, as in `TenshuCalendarRepository`. */
internal fun seriesInputs(events: List<Entity<Event>>, hidden: Set<String>): List<SeriesInput> =
    events
        .filter { it.calendarId.get() !in hidden }
        .map { row ->
            SeriesInput(
                eventId = row.eventId.get(),
                calendarId = row.calendarId.get(),
                title = row.title.get(),
                location = row.location.get(),
                notes = row.notes.get(),
                startLocal = row.startLocal.get(),
                endLocal = row.endLocal.get(),
                timeZoneId = row.timeZoneId.get(),
                allDay = row.allDay.get(),
                recurrenceRule = row.recurrenceRule.get(),
                exceptionDates = row.exceptionDates.get(),
            )
        }

internal fun overrideInputs(overrides: List<Entity<EventOverride>>, eventIds: Set<String>): List<OverrideInput> =
    overrides
        .filter { it.eventId.get() in eventIds }
        .map { row ->
            OverrideInput(
                eventId = row.eventId.get(),
                recurrenceId = row.recurrenceId.get(),
                // ⚠ `?.get()` preserves absence: "" would blank every field the override did not set.
                startLocal = row.startLocal?.get(),
                endLocal = row.endLocal?.get(),
                title = row.title?.get(),
                location = row.location?.get(),
                notes = row.notes?.get(),
            )
        }

/**
 * Every occurrence **intersecting** `[from, to]`, sorted by effective start.
 *
 * ⚠⚠ Not a bare `OccurrenceResolver.resolve(…, from, to)`: the resolver keeps only occurrences that
 * *start* in the window (its civil-date re-check), so a two-day trip would vanish from its second
 * day and a 22:00–02:00 shift from the morning it runs into. Each series is resolved over a window
 * reaching back by its own longest span (series or override), then kept when it ends after the
 * window opens. Per series, so one year-long event does not widen everyone's expansion. A web-only
 * fold on purpose: the resolver is also the proxy's reminder scheduler, which this must not move.
 * ⚠ The resolver's other limit stands: an occurrence an override moves in from *outside* the
 * reach-back is still missed (its `KDoc`).
 */
internal fun resolveIntersecting(
    series: List<SeriesInput>,
    overrides: List<OverrideInput>,
    from: LocalDate,
    to: LocalDate,
): List<ResolvedOccurrence> {
    val opens = LocalDateTime(from, LocalTime(0, 0))
    val overridesByEvent = overrides.groupBy { it.eventId }
    return series.flatMap { input ->
        val own = overridesByEvent[input.eventId].orEmpty()
        OccurrenceResolver.resolve(listOf(input), own, from.minus(spanDays(input, own), DateTimeUnit.DAY), to)
            // ⚠ `>=` on the start keeps a zero-length event at midnight; `>` on the end drops an
            // all-day event yesterday, whose exclusive end is exactly [opens].
            .filter { it.startLocal >= opens || it.endLocal > opens }
    }.sortedWith(compareBy({ it.startLocal.toString() }, { it.eventId }))
}

/** How many civil days [input]'s longest occurrence crosses, its overrides included. */
private fun spanDays(input: SeriesInput, overrides: List<OverrideInput>): Int {
    val time = EventTime.parse(input.startLocal, input.endLocal, input.timeZoneId, input.allDay) ?: return 0
    val own = time.startLocal.date.daysUntil(time.endLocal.date)
    val moved = overrides.maxOfOrNull { o ->
        val start = localOrNull(o.startLocal ?: o.recurrenceId) ?: return@maxOfOrNull 0
        val end = localOrNull(o.endLocal) ?: return@maxOfOrNull 0
        start.date.daysUntil(end.date)
    } ?: 0
    return maxOf(own, moved, 0)
}

private fun localOrNull(raw: String?): LocalDateTime? =
    raw?.let { runCatching { LocalDateTime.parse(it.trim()) }.getOrNull() }

internal fun <T, R> Read<T>.map(transform: (T) -> R): Read<R> = when (this) {
    is Read.Ok -> Read.Ok(transform(value))
    is Read.Failed -> this
}
