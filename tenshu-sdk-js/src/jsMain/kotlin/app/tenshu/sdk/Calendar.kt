@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import app.tenshu.sdk.internal.okResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import kotlinx.datetime.LocalDate
import org.tenshu.calendar.CalendarPreferences
import org.tenshu.calendar.CalendarService
import org.tenshu.calendar.CalendarSubscription
import org.tenshu.calendar.Event
import org.tenshu.calendar.EventOverride
import org.tenshu.calendar.Reminder
import org.tenshu.calendar.calendarId
import org.tenshu.calendar.client.calendarService
import org.tenshu.calendar.common.ics.CalendarEventIds
import org.tenshu.calendar.common.ics.ExportableEvent
import org.tenshu.calendar.common.ics.IcsExport
import org.tenshu.calendar.common.ics.IcsImport
import org.tenshu.calendar.common.ics.ImportedEvent
import org.tenshu.calendar.common.recurrence.EventTime
import org.tenshu.calendar.common.recurrence.RecurrenceRule
import org.tenshu.calendar.allDay
import org.tenshu.calendar.deleteCalendar
import org.tenshu.calendar.deleteEvent
import org.tenshu.calendar.deleteOccurrence
import org.tenshu.calendar.deleteReminder
import org.tenshu.calendar.deleteSubscription
import org.tenshu.calendar.endLocal
import org.tenshu.calendar.eventId
import org.tenshu.calendar.exceptionDates
import org.tenshu.calendar.location
import org.tenshu.calendar.minutesBefore
import org.tenshu.calendar.name
import org.tenshu.calendar.notes
import org.tenshu.calendar.raw
import org.tenshu.calendar.recurrenceId
import org.tenshu.calendar.recurrenceRule
import org.tenshu.calendar.saveCalendar
import org.tenshu.calendar.saveEvent
import org.tenshu.calendar.saveEventOverride
import org.tenshu.calendar.saveEventSource
import org.tenshu.calendar.saveReminder
import org.tenshu.calendar.saveSubscription
import org.tenshu.calendar.setReminderState
import org.tenshu.calendar.startLocal
import org.tenshu.calendar.subscriptionId
import org.tenshu.calendar.timeZoneId
import org.tenshu.calendar.title
import org.tenshu.calendar.url
import org.tenshu.core.Data
import app.tenshu.error.errorCopyKey
import org.tenshu.core.TError
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.flow
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.Preference
import org.tenshu.preference.RealmSetting
import org.tenshu.core.entity.Entity
import org.tenshu.core.fold
import org.tenshu.core.get
import org.tenshu.preference.client.preferenceService
import org.tenshu.preference.client.set
import org.tenshu.preference.client.setForDevice
import org.tenshu.preference.preferenceId
import org.tenshu.preference.value
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.toList
import kotlin.js.Promise

/**
 * Calendar on web (`web-app-suite.md` Wave 2): the `@JsExport` surface behind
 * `@tenshu/sdk/calendar`, mirroring the native `CalendarRepository` op for op (its real
 * implementation is `TenshuCalendarRepository`). Each call dispatches a `:calendar-service` op
 * through the in-process [JsRuntime], the same op code the natives run.
 *
 * Every call resolves `{ ok: true, … }` or `{ ok: false, error: { kind, message } }`, `kind` a
 * catalogue key (`error_…`, [CalendarWebErrorKeys] or the service error's derived key). Reads that
 * are Flows natively also have an `observe…` push form, whose `onError` is terminal.
 *
 * ⚠ Dates in, dates out (`calendar-app.md` §3 D3): windows are civil `YYYY-MM-DD`, times civil ISO
 * strings. Instants are derived per occurrence ([OccurrenceJs.startEpochMs]), in the reader's zone
 * where the event names none: the `readerZone` argument, or the browser's own zone when blank.
 * ⚠⚠ A window never fails over a zone it cannot name: a browser zone the bundled database lacks
 * falls back to the browser's clock (`readerZoneFallback: true`), an event zone it lacks to the
 * reader's (`zoneUnresolved: true`). Only an explicit, unknown `readerZone` is refused.
 *
 * ⚠⚠ Failure, never a default: every read here that fails answers `{ ok: false }`, the preference
 * reads included (the client's `readOnce` folds a failed read into the preference's default; these
 * do not). The **one** fallback is a browser with no sync device id, which cannot happen after the
 * runtime is ready but is not a crash if it does: the DEVICE-scoped hidden set reads as "nothing
 * hidden" (showing everything is the safe failure) and its write is refused (`error_calendar_no_device`).
 *
 * ⚠⚠ What is **not** here, on purpose (`calendar-app.md` §Web scope):
 *  - `refreshSubscriptions` (W2: a browser fetches no feed; the plugin maps no implementation);
 *  - reminder *firing* (W1: no `DeliverDueReminders`, no timer). The reminder data is here;
 *  - `ensureDefaultCalendar` (W3: a fresh browser holds nothing until its first pull, so a seed at
 *    load is the seed-before-sync race at its worst).
 *
 * Not-found: a write that names a missing event, calendar or subscription is
 * `error_calendar_not_found` (an override on nothing would be invisible, not an edit). Deletes of a
 * missing id succeed, because the service treats them as idempotent: mirrored, not invented.
 */
@JsExport
object Calendar {

    // ── reads ────────────────────────────────────────────────────────────────

    /** Every calendar, by name, with this browser's visibility: `{ ok, calendars }`. */
    fun calendars(): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val rows = service().calendarRows()
        val hidden = hiddenNow()
        when {
            rows is Read.Failed -> calendarFailure(rows.error)
            hidden is Read.Failed -> calendarFailure(hidden.error)
            else -> okResult { it.calendars = (rows as Read.Ok).value.toCalendarsJs((hidden as Read.Ok).value) }
        }
    }

    /** [calendars], live: a rename, recolour, new calendar or visibility toggle arrives on its own. */
    fun observeCalendars(callback: (dynamic) -> Unit, onError: (String) -> Unit): () -> Unit =
        observeJob(onError) {
            combine(service().calendarRowsFlow(), hiddenFlow()) { rows, hidden ->
                when {
                    rows is Read.Failed -> calendarFailure(rows.error)
                    hidden is Read.Failed -> calendarFailure(hidden.error)
                    else -> okResult { it.calendars = (rows as Read.Ok).value.toCalendarsJs((hidden as Read.Ok).value) }
                }
            }.collect { callback(it) }
        }

    /**
     * Occurrences intersecting `[from, to]` (inclusive civil dates), sorted by effective start:
     * `{ ok, occurrences, readerZone, readerZoneFallback }` — the zone the window was read in, and
     * whether it is the browser-clock fallback ([ReaderZone]). ⚠ Hidden calendars are excluded
     * unless [includeHidden], as natively. `to < from` is an empty window, not an error (native).
     * ⚠ *Intersecting*, including one that began before [from] ([resolveIntersecting]).
     */
    fun occurrences(from: String, to: String, includeHidden: Boolean, readerZone: String): Promise<dynamic> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            val window = windowOf(from, to, readerZone) ?: return@promise invalidWindow(from, to, readerZone)
            val service = service()
            val events = service.eventRows()
            val overrides = service.overrideRows()
            val hidden = if (includeHidden) Read.Ok(emptySet()) else hiddenNow()
            when {
                events is Read.Failed -> calendarFailure(events.error)
                overrides is Read.Failed -> calendarFailure(overrides.error)
                hidden is Read.Failed -> calendarFailure(hidden.error)
                else -> occurrencesResult(
                    (events as Read.Ok).value to (overrides as Read.Ok).value,
                    window,
                    (hidden as Read.Ok).value,
                )
            }
        }

    /**
     * `[from, to]` live: re-emits on any event or override change, from anywhere (this tab,
     * an import, another device once the block syncs).
     *
     * ⚠⚠ **Hidden calendars are included**, unlike [occurrences] — the native contract (CA-2g):
     * collect [observeHiddenCalendars] alongside and filter, so a visibility toggle is instant.
     */
    fun observeOccurrences(
        from: String,
        to: String,
        readerZone: String,
        callback: (dynamic) -> Unit,
        onError: (String) -> Unit,
    ): () -> Unit {
        val window = windowOf(from, to, readerZone)
            ?: return observeJob(onError) { callback(invalidWindow(from, to, readerZone)) }
        return observeJob(onError) {
            service().seriesFlow().collect { read ->
                callback(
                    when (read) {
                        is Read.Failed -> calendarFailure(read.error)
                        is Read.Ok -> occurrencesResult(read.value, window, hidden = emptySet())
                    },
                )
            }
        }
    }

    // ── view state ───────────────────────────────────────────────────────────

    /**
     * This browser's hidden-calendar set, `{ ok: true, hidden }`, re-emitting on change.
     *
     * ⚠⚠ The only correct way to observe visibility: a snapshot read right after
     * [setCalendarVisible] can see the value from *before* the write (CA-2f). ⚠ Emits "nothing
     * hidden" first: the device relation never emits on a browser that has never written one.
     */
    fun observeHiddenCalendars(callback: (dynamic) -> Unit, onError: (String) -> Unit): () -> Unit =
        observeJob(onError) {
            hiddenFlow().collect { hidden ->
                callback(
                    when (hidden) {
                        is Read.Failed -> calendarFailure(hidden.error)
                        is Read.Ok -> okResult { it.hidden = hidden.value.sorted().toTypedArray() }
                    },
                )
            }
        }

    /**
     * Show or hide a calendar **in this browser only** (DEVICE scope): `{ ok, changed }`.
     * `changed: false` = it already was. ⚠ Refused (`error_calendar_no_device`) while the browser
     * has no sync device id, never written under a key that is not this device.
     */
    fun setCalendarVisible(calendarId: String, visible: Boolean): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val deviceId = deviceIdOrNull() ?: return@promise calendarFailure(CalendarWebErrorKeys.NO_DEVICE)
        // ⚠⚠ A read-modify-write: a failed read folded to "nothing hidden" would write just this
        // calendar and un-hide every other one.
        val hidden = when (val read = devicePref(CalendarPreferences.HiddenCalendars, deviceId)) {
            is Read.Failed -> return@promise calendarFailure(read.error)
            is Read.Ok -> read.value.toSet()
        }
        val next = if (visible) hidden - calendarId else hidden + calendarId
        // ⚠ Nothing to write: a no-op would burn a sync block (native).
        if (next == hidden) return@promise okResult { it.changed = false }
        JsRuntime.tenshuClient.preferenceService
            .setForDevice(CalendarPreferences.HiddenCalendars, next.sorted(), deviceId)
            .fold(onSuccess = { okResult { r -> r.changed = true } }, onFailure = { calendarFailure(it) })
    }

    /** The calendar a new event lands in when none is named: `{ ok, calendarId }` (`""` = unset). */
    fun defaultCalendar(): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        defaultCalendarResult(realmPref(CalendarPreferences.DefaultCalendarId))
    }

    /**
     * [defaultCalendar], live. ⚠ Seeds with a one-shot read: the realm relation emits nothing on a
     * realm that has never held the row, so a bare collect would wait forever.
     */
    fun observeDefaultCalendar(callback: (dynamic) -> Unit, onError: (String) -> Unit): () -> Unit =
        observeJob(onError) {
            callback(defaultCalendarResult(realmPref(CalendarPreferences.DefaultCalendarId)))
            realmPrefFlow(CalendarPreferences.DefaultCalendarId).collect { callback(defaultCalendarResult(it)) }
        }

    /** Makes [calendarId] the default. ⚠ A calendar that does not exist is `not_found`. */
    fun setDefaultCalendar(calendarId: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        requireCalendar(calendarId)?.let { return@promise it }
        JsRuntime.tenshuClient.preferenceService.set(CalendarPreferences.DefaultCalendarId, calendarId)
            .done()
    }

    // ── calendar management ──────────────────────────────────────────────────

    /**
     * Creates or updates a calendar: `{ ok, calendarId }`. A blank [calendarId] creates, with an id
     * derived from the name (native: two devices creating "Work" offline converge on one row).
     * ⚠⚠ [sensitive] is the lock-screen control (Q10) and syncs.
     */
    fun saveCalendar(calendarId: String, name: String, colorArgb: Int, sensitive: Boolean): Promise<dynamic> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            val id = calendarId.ifBlank { "cal-" + name.trim().lowercase().hashCode().toUInt().toString(16) }
            service().saveCalendar(calendarId = id, name = name, colorArgb = colorArgb, sensitive = sensitive)
                .fold(onSuccess = { okResult { it.calendarId = id } }, onFailure = { calendarFailure(it) })
        }

    /**
     * Deletes a calendar **and every event in it** (the op's cascade). ⚠⚠ Re-points the default when
     * it named this calendar, to the first survivor by id or `""`, as natively: a dangling default
     * would make every editor-less create fail. ⚠ The default is read **before** the delete, and a
     * failed read refuses the delete: deleting and then not knowing whether to re-point is exactly
     * the dangling default this exists to prevent.
     */
    fun deleteCalendar(calendarId: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val service = service()
        val wasDefault = when (val read = realmPref(CalendarPreferences.DefaultCalendarId)) {
            is Read.Failed -> return@promise calendarFailure(read.error)
            is Read.Ok -> read.value == calendarId
        }
        val deleted = service.deleteCalendar(calendarId)
        if (deleted is TResult.Failure) return@promise calendarFailure(deleted.error)
        if (!wasDefault) return@promise okResult()
        val survivor = when (val rows = service.calendarRows()) {
            is Read.Failed -> return@promise calendarFailure(rows.error)
            is Read.Ok -> rows.value.map { it.calendarId.get() }.filterNot { it == calendarId }.minOrNull().orEmpty()
        }
        JsRuntime.tenshuClient.preferenceService.set(CalendarPreferences.DefaultCalendarId, survivor).done()
    }

    // ── events ───────────────────────────────────────────────────────────────

    /**
     * Creates or replaces an event from [draft] (`{ eventId, calendarId, title, startLocal, endLocal,
     * timeZoneId, allDay, location, notes, recurrenceRule }`, every key required): `{ ok, eventId }`.
     *
     * - A blank `eventId` creates; a blank `calendarId` lands in the default (which fails loudly if
     *   unset); a blank `endLocal` takes the default duration (or +1 day all-day); `timeZoneId` `""`
     *   is *floating*, a real value.
     * - ⚠⚠ The series' skipped occurrences (`exceptionDates`) are preserved, never cleared: the
     *   editor does not hold them (native).
     * - ⚠ Refused rather than saved wrongly: an unparseable time, an unknown zone, a non-blank
     *   `RRULE` with no usable `FREQ` (it would save as a one-off), a calendar that does not exist.
     */
    fun saveEvent(draft: dynamic): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val d = eventDraftOf(draft) ?: return@promise calendarFailure(CalendarWebErrorKeys.INVALID_ARGUMENT, "draft")
        validate(d)?.let { return@promise it }
        if (d.calendarId.isNotBlank()) requireCalendar(d.calendarId)?.let { return@promise it }
        val service = service()
        val eventId = d.eventId.ifBlank {
            "evt-" + (d.title + d.startLocal + d.calendarId).hashCode().toUInt().toString(16) +
                "-" + d.startLocal.filter { it.isDigit() }
        }
        val existing = when (val rows = service.eventRows()) {
            is Read.Failed -> return@promise calendarFailure(rows.error)
            is Read.Ok -> rows.value.firstOrNull { it.eventId.get() == eventId }
        }
        service.saveEvent(
            eventId = eventId,
            title = d.title,
            startLocal = d.startLocal,
            endLocal = d.endLocal.ifBlank { null },
            calendarId = d.calendarId.ifBlank { null },
            timeZoneId = d.timeZoneId,
            allDay = d.allDay,
            location = d.location,
            notes = d.notes,
            recurrenceRule = d.recurrenceRule,
            exceptionDates = existing?.exceptionDates?.get().orEmpty(),
        ).fold(onSuccess = { okResult { it.eventId = eventId } }, onFailure = { calendarFailure(it) })
    }

    /**
     * Edits **one occurrence** of a series ([recurrenceId] = its *original* start), leaving the
     * rest: `{ ok }`. ⚠⚠ Not [saveEvent] with a changed start, which would move every occurrence.
     * Only the draft's times, title, location and notes apply; a missing event is `not_found`.
     */
    fun saveOccurrence(draft: dynamic, recurrenceId: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val d = eventDraftOf(draft) ?: return@promise calendarFailure(CalendarWebErrorKeys.INVALID_ARGUMENT, "draft")
        if (recurrenceId.isBlank()) return@promise calendarFailure(CalendarWebErrorKeys.INVALID_ARGUMENT, "recurrenceId")
        validate(d)?.let { return@promise it }
        requireEvent(d.eventId)?.let { return@promise it }
        service().saveEventOverride(
            eventId = d.eventId,
            recurrenceId = recurrenceId,
            startLocal = d.startLocal,
            endLocal = d.endLocal,
            title = d.title,
            location = d.location,
            notes = d.notes,
        ).done()
    }

    /** Deletes a whole series, its overrides and its source. A missing id succeeds (idempotent op). */
    fun deleteEvent(eventId: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        service().deleteEvent(eventId).done()
    }

    /** Skips one occurrence: narrows the rule's output, destroys nothing. A missing event is `not_found`. */
    fun deleteOccurrence(eventId: String, recurrenceId: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        requireEvent(eventId)?.let { return@promise it }
        service().deleteOccurrence(eventId, recurrenceId).done()
    }

    // ── subscriptions: the rows only (W2) ────────────────────────────────────
    //
    // ⚠⚠ A browser fetches no feed (`RefreshSubscriptions` has no implementation here). These read
    // and write the synced subscription rows; the user's phone and desktop do the fetching, and the
    // feed's events reach this browser through ordinary sync.

    /** Every subscribed feed, by URL: `{ ok, subscriptions }`. */
    fun subscriptions(): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        when (val rows = service().subscriptionRows()) {
            is Read.Failed -> calendarFailure(rows.error)
            is Read.Ok -> okResult { it.subscriptions = rows.value.toSubscriptionsJs() }
        }
    }

    /** [subscriptions], live: a native device's fetch moves a row's *last fetched* on its own. */
    fun observeSubscriptions(callback: (dynamic) -> Unit, onError: (String) -> Unit): () -> Unit =
        observeJob(onError) {
            service().subscriptionRowsFlow().collect { rows ->
                callback(
                    when (rows) {
                        is Read.Failed -> calendarFailure(rows.error)
                        is Read.Ok -> okResult { it.subscriptions = rows.value.toSubscriptionsJs() }
                    },
                )
            }
        }

    /**
     * Subscribes to [url], creating the calendar it fills: `{ ok, subscriptionId }`.
     *
     * ⚠⚠ **Creating it is the consent** (Q9) — and from a browser it is consent to *other* devices'
     * egress: every native device fetches it on its schedule. The copy before this call owes that.
     * `https://` and `webcal://` only; anything else is `error_calendar_subscription_url`.
     */
    fun addSubscription(url: String, name: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val normalized = normalizeSubscriptionUrl(url)
            ?: return@promise calendarFailure(CalendarWebErrorKeys.SUBSCRIPTION_URL)
        val subscriptionId = CalendarSubscription.idFor(normalized)
        val calendarId = "$subscriptionId-cal"
        val service = service()
        val saved = service.saveCalendar(
            calendarId = calendarId,
            name = name.trim().ifBlank { DEFAULT_SUBSCRIPTION_NAME },
            colorArgb = SUBSCRIPTION_COLOR,
        )
        if (saved is TResult.Failure) return@promise calendarFailure(saved.error)
        service.saveSubscription(subscriptionId = subscriptionId, calendarId = calendarId, url = normalized)
            .fold(onSuccess = { okResult { it.subscriptionId = subscriptionId } }, onFailure = { calendarFailure(it) })
    }

    /** Pauses or resumes a feed; pausing keeps its events. A missing subscription is `not_found`. */
    fun setSubscriptionEnabled(subscriptionId: String, enabled: Boolean): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val service = service()
        val row = when (val rows = service.subscriptionRows()) {
            is Read.Failed -> return@promise calendarFailure(rows.error)
            is Read.Ok -> rows.value.firstOrNull { it.subscriptionId.get() == subscriptionId }
                ?: return@promise calendarFailure(CalendarWebErrorKeys.NOT_FOUND, subscriptionId)
        }
        // ⚠ No etag, no timestamp: absent keeps what the row holds (native).
        service.saveSubscription(
            subscriptionId = subscriptionId,
            calendarId = row.calendarId.get(),
            url = row.url.get(),
            enabled = enabled,
        ).done()
    }

    /** Unsubscribes **and deletes the calendar the feed filled**. */
    fun removeSubscription(subscriptionId: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        service().deleteSubscription(subscriptionId).done()
    }

    // ── interop: .ics text ───────────────────────────────────────────────────

    /**
     * Imports `.ics` text into [calendarId]: `{ ok, imported, overrides, skipped, failed, warnings }`.
     *
     * ⚠⚠ Idempotent by `UID` (native): re-importing replaces rather than duplicates. Series first,
     * overrides second, so an exception listed before its series still lands. ⚠ `skipped` (blocks the
     * parser refused) and `failed` (writes the service refused) are surfaced, never swallowed. Text
     * with no `VEVENT` is `error_calendar_none_in_file`; a missing calendar is `not_found`.
     * ⚠ Partial failure is [importEvents]'s contract: a series that saved is `imported` even when its
     * source or reminders did not (each in `warnings`), and a failed series takes its overrides with it.
     */
    fun importIcs(text: String, calendarId: String, origin: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        requireCalendar(calendarId)?.let { return@promise it }
        val parsed = IcsImport.read(text)
        if (parsed.events.isEmpty()) {
            return@promise calendarFailure(CalendarWebErrorKeys.NONE_IN_FILE, "skipped=${parsed.skipped}")
        }
        val service = service()
        val inCalendar = when (val rows = service.eventRows()) {
            is Read.Failed -> return@promise calendarFailure(rows.error)
            is Read.Ok -> rows.value.filter { it.calendarId.get() == calendarId }.mapTo(mutableSetOf()) { it.eventId.get() }
        }
        val outcome = importEvents(parsed.events, calendarId, origin, ServiceImportWrites(service), inCalendar)
        okResult {
            it.imported = outcome.imported
            it.overrides = outcome.overrides
            it.skipped = parsed.skipped
            it.failed = outcome.failed
            it.warnings = outcome.warnings.map { w ->
                val o: dynamic = js("({})")
                o.eventId = w.eventId
                o.part = w.part
                o.kind = w.error.errorCopyKey().value
                o.message = w.error.toString()
                o
            }.toTypedArray()
        }
    }

    /**
     * [calendarId] (every calendar when blank) as `.ics` text: `{ ok, ics }`.
     *
     * ⚠⚠ Re-emits each event's stored `EventSource` with the native fields written over it, and
     * each override as its own `VEVENT` with `RECURRENCE-ID`, the shape `importIcs` reads back.
     */
    fun exportIcs(calendarId: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val service = service()
        val calendars = when (val rows = service.calendarRows()) {
            is Read.Failed -> return@promise calendarFailure(rows.error)
            is Read.Ok -> rows.value
        }
        if (calendarId.isNotBlank() && calendars.none { it.calendarId.get() == calendarId }) {
            return@promise calendarFailure(CalendarWebErrorKeys.NOT_FOUND, calendarId)
        }
        val calendarName =
            if (calendarId.isBlank()) "" else calendars.first { it.calendarId.get() == calendarId }.name.get()
        val events = when (val rows = service.eventRows()) {
            is Read.Failed -> return@promise calendarFailure(rows.error)
            is Read.Ok -> rows.value.filter { calendarId.isBlank() || it.calendarId.get() == calendarId }
        }
        val eventIds = events.mapTo(mutableSetOf()) { it.eventId.get() }
        val sources = when (val rows = service.sourceRows()) {
            is Read.Failed -> return@promise calendarFailure(rows.error)
            is Read.Ok -> rows.value.associate { it.eventId.get() to it.raw.get() }
        }
        // ⚠⚠ The sidecar's `UID`, as native `exportIcs` does: imported and fetched events are stored
        // under a namespaced id (§7), and the file must carry the source's identity, not ours —
        // else `UID:work~meet@corp`, which re-imports elsewhere as `home~work~meet@corp`.
        // Overrides take their series' value, or they detach.
        val uidOf = sources.mapValues { (_, raw) -> IcsImport.uidOf(raw) }
        val remindersByEvent = when (val rows = service.reminderRows()) {
            is Read.Failed -> return@promise calendarFailure(rows.error)
            is Read.Ok -> rows.value.filter { it.eventId.get() in eventIds }
                .groupBy({ it.eventId.get() }, { it.minutesBefore.get().toInt() })
        }
        val overrideRows = when (val rows = service.overrideRows()) {
            is Read.Failed -> return@promise calendarFailure(rows.error)
            is Read.Ok -> rows.value.filter { it.eventId.get() in eventIds }
        }
        val seriesOut = events.map { row ->
            ExportableEvent(
                eventId = row.eventId.get(),
                title = row.title.get(),
                startLocal = row.startLocal.get(),
                endLocal = row.endLocal.get(),
                timeZoneId = row.timeZoneId.get(),
                allDay = row.allDay.get(),
                location = row.location.get(),
                notes = row.notes.get(),
                recurrenceRule = row.recurrenceRule.get(),
                exceptionDates = row.exceptionDates.get(),
                reminderMinutes = remindersByEvent[row.eventId.get()].orEmpty().sorted(),
                raw = sources[row.eventId.get()].orEmpty(),
                uid = uidOf[row.eventId.get()].orEmpty(),
            )
        }
        val byId = events.associateBy { it.eventId.get() }
        val exceptionsOut = overrideRows.mapNotNull { override ->
            val parent = byId[override.eventId.get()] ?: return@mapNotNull null
            ExportableEvent(
                eventId = override.eventId.get(),
                // ⚠ Inherit wherever the override is silent: absent means "unchanged".
                title = override.title?.get() ?: parent.title.get(),
                startLocal = override.startLocal?.get() ?: override.recurrenceId.get(),
                endLocal = override.endLocal?.get().orEmpty(),
                timeZoneId = parent.timeZoneId.get(),
                allDay = parent.allDay.get(),
                location = override.location?.get() ?: parent.location.get(),
                notes = override.notes?.get() ?: parent.notes.get(),
                // ⚠ No rule on an override: it *is* one occurrence.
                recurrenceRule = "",
                exceptionDates = "",
                recurrenceId = override.recurrenceId.get(),
                uid = uidOf[override.eventId.get()].orEmpty(),
            )
        }
        val ics = IcsExport.write(seriesOut + exceptionsOut, calendarName)
        okResult { it.ics = ics }
    }

    // ── reminders: the data only (W1) ────────────────────────────────────────

    /** The offsets [eventId] alerts at, ascending, minutes *before* (negative = after): `{ ok, minutes }`. */
    fun remindersFor(eventId: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        requireEvent(eventId)?.let { return@promise it }
        when (val rows = service().reminderRows()) {
            is Read.Failed -> calendarFailure(rows.error)
            is Read.Ok -> okResult {
                it.minutes = rows.value.filter { r -> r.eventId.get() == eventId }
                    .map { r -> r.minutesBefore.get().toInt() }.sorted().toTypedArray()
            }
        }
    }

    /**
     * Makes [eventId]'s reminders exactly [minutesBefore]: `{ ok }`. ⚠⚠ A set, not add/remove, and
     * **diffed**: re-writing an unchanged reminder would cascade-delete its dismissal state.
     */
    fun setReminders(eventId: String, minutesBefore: Array<Int>): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        requireEvent(eventId)?.let { return@promise it }
        applyReminders(service(), eventId, minutesBefore.toList())?.let { calendarFailure(it) } ?: okResult()
    }

    /**
     * The offset a **new** event starts with, `{ ok, minutes }` with `minutes: null` for "no
     * reminder". ⚠⚠ Two preferences: an all-day event's default is minutes after midnight
     * (negative), a timed one's is minutes before, and only the timed one reads negatives as off.
     */
    fun defaultReminderMinutes(allDay: Boolean): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val pref =
            if (allDay) CalendarPreferences.DefaultAllDayReminderMinutes
            else CalendarPreferences.DefaultReminderMinutes
        defaultReminderResult(allDay, realmPref(pref))
    }

    /**
     * Records that the user dismissed one occurrence's reminder: `{ ok }`. ⚠ A synced row, so the
     * phone stops alerting for it too. Nothing in a browser fires reminders (W1).
     */
    fun dismissReminder(reminderId: String, recurrenceId: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        service().setReminderState(reminderId, recurrenceId, dismissed = true).done()
    }

    /** Silences one occurrence's reminder until [untilEpochMs] (an instant: the caller owns the clock). */
    fun snoozeReminder(reminderId: String, recurrenceId: String, untilEpochMs: Double): Promise<dynamic> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            service().setReminderState(
                reminderId = reminderId,
                recurrenceId = recurrenceId,
                snoozedUntilEpochMs = untilEpochMs.toLong(),
            ).done()
        }

    // ── internals ────────────────────────────────────────────────────────────

    private fun service(): CalendarService = JsRuntime.tenshuClient.calendarService

    private class Window(val from: LocalDate, val to: LocalDate, val reader: ReaderZone)

    private fun windowOf(from: String, to: String, readerZone: String): Window? {
        val start = runCatching { LocalDate.parse(from) }.getOrNull() ?: return null
        val end = runCatching { LocalDate.parse(to) }.getOrNull() ?: return null
        val reader = readerZoneOf(readerZone) ?: return null
        return Window(start, end, reader)
    }

    private fun invalidWindow(from: String, to: String, readerZone: String): dynamic {
        val datesOk = runCatching { LocalDate.parse(from); LocalDate.parse(to) }.isSuccess
        return if (datesOk) calendarFailure(CalendarWebErrorKeys.UNKNOWN_ZONE, readerZone)
        else calendarFailure(CalendarWebErrorKeys.INVALID_ARGUMENT, "from=$from to=$to")
    }

    private fun occurrencesResult(
        rows: Pair<List<Entity<Event>>, List<Entity<EventOverride>>>,
        window: Window,
        hidden: Set<String>,
    ): dynamic {
        val series = if (window.to < window.from) emptyList() else seriesInputs(rows.first, hidden)
        val overrides = overrideInputs(rows.second, series.mapTo(mutableSetOf()) { it.eventId })
        val resolved = resolveIntersecting(series, overrides, window.from, window.to)
        return okResult {
            it.occurrences = resolved.map { o -> o.toJs(window.reader) }.toTypedArray()
            it.readerZone = window.reader.id
            it.readerZoneFallback = window.reader.fallback
        }
    }

    private suspend fun hiddenNow(): Read<Set<String>> {
        // No device id ⇒ the declared default, nothing hidden: showing everything is the safe failure.
        val deviceId = deviceIdOrNull() ?: return Read.Ok(CalendarPreferences.HiddenCalendars.default.toSet())
        return devicePref(CalendarPreferences.HiddenCalendars, deviceId).map { it.toSet() }
    }

    /**
     * Seeds "nothing hidden", then switches to this device's row. ⚠⚠ Resolves the device id **once**,
     * with no timer: it runs after [JsRuntime.awaitReady] (inside [observeJob]), and by then
     * `SyncManager.currentDeviceId` is fixed — read or minted when the manager is built over the
     * hydrated secure storage — so it is there, or it never will be in this tab. That is native's
     * `CalendarDeviceIdentity` "never" latch, without its retry: the "not yet" case it retries
     * through is the proxy's startup race, which an in-process runtime does not have. With no id the
     * flow parks on the seed until the subscriber cancels; it does not complete, which [observeJob]
     * would report as a dead stream. ⚠ An open-ended poll here would keep a 1 s timer alive in the tab
     * for every screen's lifetime. [deviceId] is a parameter for the test that pins this.
     */
    internal fun hiddenFlow(deviceId: () -> String? = ::deviceIdOrNull): Flow<Read<Set<String>>> = flow {
        emit(Read.Ok(CalendarPreferences.HiddenCalendars.default.toSet()))
        val id = deviceId() ?: awaitCancellation()
        emitAll(devicePrefFlow(CalendarPreferences.HiddenCalendars, id).map { read -> read.map { it.toSet() } })
    }

    private fun deviceIdOrNull(): String? =
        runCatching { JsRuntime.syncManager.currentDeviceId }.getOrNull()?.takeIf { it.isNotBlank() }

    /** `null` when [calendarId] exists; otherwise the failure to answer with. */
    private suspend fun requireCalendar(calendarId: String): Any? =
        when (val rows = service().calendarRows()) {
            is Read.Failed -> calendarFailure(rows.error)
            is Read.Ok ->
                if (rows.value.any { it.calendarId.get() == calendarId }) null
                else calendarFailure(CalendarWebErrorKeys.NOT_FOUND, calendarId)
        }

    /** `null` when [eventId] exists; otherwise the failure to answer with. */
    private suspend fun requireEvent(eventId: String): Any? =
        when (val rows = service().eventRows()) {
            is Read.Failed -> calendarFailure(rows.error)
            is Read.Ok ->
                if (rows.value.any { it.eventId.get() == eventId }) null
                else calendarFailure(CalendarWebErrorKeys.NOT_FOUND, eventId)
        }

    /** The diffed reminder set; `null` on success, else the first failure. */
    internal suspend fun applyReminders(service: CalendarService, eventId: String, wanted: List<Int>): TError? {
        val existing = when (val rows = service.reminderRows()) {
            is Read.Failed -> return rows.error
            is Read.Ok -> rows.value.filter { it.eventId.get() == eventId }.map { it.minutesBefore.get().toInt() }
        }
        val target = wanted.distinct()
        for (minutes in existing - target.toSet()) {
            val r = service.deleteReminder(Reminder.idFor(eventId, minutes))
            if (r is TResult.Failure) return r.error
        }
        for (minutes in target - existing.toSet()) {
            val r = service.saveReminder(eventId, minutes)
            if (r is TResult.Failure) return r.error
        }
        return null
    }

    /** `null` when [d] can be saved as given; otherwise the refusal. */
    private fun validate(d: EventDraftIn): Any? {
        if (EventTime.parse(d.startLocal, "", "", d.allDay) == null) {
            return calendarFailure(CalendarWebErrorKeys.INVALID_ARGUMENT, "startLocal=${d.startLocal}")
        }
        if (d.endLocal.isNotBlank() && EventTime.parse(d.endLocal, "", "", d.allDay) == null) {
            return calendarFailure(CalendarWebErrorKeys.INVALID_ARGUMENT, "endLocal=${d.endLocal}")
        }
        if (d.timeZoneId.isNotBlank() && zoneOrNull(d.timeZoneId) == null) {
            return calendarFailure(CalendarWebErrorKeys.UNKNOWN_ZONE, d.timeZoneId)
        }
        if (d.recurrenceRule.isNotBlank() && RecurrenceRule.parse(d.recurrenceRule) == null) {
            return calendarFailure(CalendarWebErrorKeys.INVALID_RULE, d.recurrenceRule)
        }
        return null
    }

    /** The file's `UID`, else a content hash (native `idFor`) — namespaced before it is stored ([importEvents]). */
    internal fun idFor(event: ImportedEvent): String =
        event.uid.trim().ifBlank {
            "ics-" + (event.title + event.startLocal + event.recurrenceRule).hashCode().toUInt().toString(16)
        }

    private fun TResult<Data?>.done(): dynamic =
        fold(onSuccess = { okResult() }, onFailure = { calendarFailure(it) })

    /**
     * One observe collector until the returned lambda cancels it. ⚠⚠ Every end but the subscriber's
     * own cancel reaches [onError] exactly once — a throw with its message, a completed flow as
     * [CALENDAR_OBSERVE_ENDED] — so a dead stream never freezes a screen on its last value
     * (the `Contacts.observeJob` contract).
     */
    private fun observeJob(onError: (String) -> Unit, body: suspend () -> Unit): () -> Unit {
        val job = GlobalScope.launch {
            val failure = try {
                JsRuntime.awaitReady()
                body()
                CALENDAR_OBSERVE_ENDED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                e.message ?: e.toString()
            }
            onError(failure)
        }
        return { job.cancel() }
    }

    /** The same rule `TenshuCalendarRepository.normalizeSubscriptionUrl` states, and the proxy enforces. */
    internal fun normalizeSubscriptionUrl(raw: String): String? {
        val text = raw.trim()
        return when {
            text.isEmpty() -> null
            text.startsWith("webcal://", ignoreCase = true) -> "https://" + text.substring(9)
            text.startsWith("webcals://", ignoreCase = true) -> "https://" + text.substring(10)
            text.startsWith("https://", ignoreCase = true) -> text
            else -> null
        }
    }

    internal const val OFF_SENTINEL = -1L
    private const val DEFAULT_SUBSCRIPTION_NAME = "Subscribed calendar"
    /** ⚠ The amber `CalendarPalette` gives feeds natively, so a feed is visibly not the user's. */
    private val SUBSCRIPTION_COLOR = 0xF4B400.toInt()
}

/** The terminal diagnostic for a calendar observe stream that completed rather than failed. */
internal const val CALENDAR_OBSERVE_ENDED = "calendar stream ended"

/** The editor's draft, read strictly: a missing key is a caller bug, never a default. */
internal class EventDraftIn(
    val eventId: String,
    val calendarId: String,
    val title: String,
    val startLocal: String,
    val endLocal: String,
    val timeZoneId: String,
    val allDay: Boolean,
    val location: String,
    val notes: String,
    val recurrenceRule: String,
)

internal fun eventDraftOf(d: dynamic): EventDraftIn? {
    if (d == null || jsTypeOf(d) != "object") return null
    // ⚠ Typed locals, never `d[key].let { … }`: a scope function on a `dynamic` compiles to a JS
    // method call (`d.allDay.let(…)`), which does not exist.
    fun str(key: String): String? {
        val v: Any? = d[key]
        return v as? String
    }
    val rawAllDay: Any? = d["allDay"]
    val allDay = rawAllDay as? Boolean ?: return null
    return EventDraftIn(
        eventId = str("eventId") ?: return null,
        calendarId = str("calendarId") ?: return null,
        title = str("title") ?: return null,
        startLocal = str("startLocal") ?: return null,
        endLocal = str("endLocal") ?: return null,
        timeZoneId = str("timeZoneId") ?: return null,
        allDay = allDay,
        location = str("location") ?: return null,
        notes = str("notes") ?: return null,
        recurrenceRule = str("recurrenceRule") ?: return null,
    )
}

// ── preference reads that fail as failures ─────────────────────────────────────
//
// ⚠⚠ Not the client's `readOnce` / `read` / `readOnceForDevice` / `readForDevice`: each folds a
// failed relation read into the preference's default, so a broken store reads as "no default
// calendar" or "nothing hidden" — and `deleteCalendar` would then skip re-pointing a default it
// could not see. The same lookup, with the failure kept.

private fun <T> Preference<T>.realmValueIn(rows: List<RealmSetting>): T =
    rows.firstOrNull { it.preferenceId.get() == id }?.let { adapter.fromData(it.value) } ?: default

private fun <T> Preference<T>.deviceValueIn(rows: List<DeviceSetting>, deviceId: String): T {
    val key = DeviceSetting.compositeKey(deviceId, id)
    return rows.firstOrNull { it.compositeKey.get() == key }?.let { adapter.fromData(it.value) } ?: default
}

internal suspend fun <T> realmPref(pref: Preference<T>): Read<T> =
    JsRuntime.tenshuClient.preferenceService.realmSettings.getAll()
        .fold(onSuccess = { Read.Ok(pref.realmValueIn(it.toList())) }, onFailure = { Read.Failed(it) })

internal fun <T> realmPrefFlow(pref: Preference<T>): Flow<Read<T>> =
    JsRuntime.tenshuClient.preferenceService.realmSettings.flow().map { result ->
        result.fold(onSuccess = { Read.Ok(pref.realmValueIn(it.toList())) }, onFailure = { Read.Failed(it) })
    }

internal suspend fun <T> devicePref(pref: Preference<T>, deviceId: String): Read<T> =
    JsRuntime.tenshuClient.preferenceService.deviceSettings.getAll()
        .fold(onSuccess = { Read.Ok(pref.deviceValueIn(it.toList(), deviceId)) }, onFailure = { Read.Failed(it) })

internal fun <T> devicePrefFlow(pref: Preference<T>, deviceId: String): Flow<Read<T>> =
    JsRuntime.tenshuClient.preferenceService.deviceSettings.flow().map { result ->
        result.fold(
            onSuccess = { Read.Ok(pref.deviceValueIn(it.toList(), deviceId)) },
            onFailure = { Read.Failed(it) },
        )
    }

/** `{ ok, calendarId }` (`""` = unset), or the read's failure. */
internal fun defaultCalendarResult(read: Read<String>): dynamic = when (read) {
    is Read.Failed -> calendarFailure(read.error)
    is Read.Ok -> okResult { it.calendarId = read.value }
}

/**
 * `{ ok, minutes }` from the stored default, `minutes: null` for "no reminder", or the read's
 * failure — never the declared default standing in for a read that failed.
 */
internal fun defaultReminderResult(allDay: Boolean, read: Read<Long>): dynamic = when (read) {
    is Read.Failed -> calendarFailure(read.error)
    is Read.Ok -> {
        val value = read.value
        val minutes: Int? = when {
            value == Calendar.OFF_SENTINEL -> null
            !allDay && value < 0 -> null
            else -> value.toInt()
        }
        okResult { it.minutes = minutes }
    }
}

// ── .ics import: the write loop ────────────────────────────────────────────────

/** The four writes an import makes, as a seam: `null` is success. A test fails one on purpose. */
internal interface IcsImportWrites {
    suspend fun saveSeries(eventId: String, event: ImportedEvent, calendarId: String): TError?
    suspend fun saveSource(eventId: String, event: ImportedEvent, origin: String): TError?
    suspend fun setReminders(eventId: String, minutes: List<Int>): TError?
    suspend fun saveOverride(eventId: String, event: ImportedEvent): TError?
}

internal class ServiceImportWrites(private val service: CalendarService) : IcsImportWrites {
    override suspend fun saveSeries(eventId: String, event: ImportedEvent, calendarId: String) =
        service.saveEvent(
            eventId = eventId,
            title = event.title,
            startLocal = event.startLocal,
            endLocal = event.endLocal,
            calendarId = calendarId,
            timeZoneId = event.timeZoneId,
            allDay = event.allDay,
            location = event.location,
            notes = event.notes,
            recurrenceRule = event.recurrenceRule,
            exceptionDates = event.exceptionDates,
        ).errorOrNull()

    override suspend fun saveSource(eventId: String, event: ImportedEvent, origin: String) =
        service.saveEventSource(eventId, event.raw, origin).errorOrNull()

    // ⚠ Set, not add: a re-import converges on what the file says.
    override suspend fun setReminders(eventId: String, minutes: List<Int>) =
        Calendar.applyReminders(service, eventId, minutes)

    override suspend fun saveOverride(eventId: String, event: ImportedEvent) =
        service.saveEventOverride(
            eventId = eventId,
            recurrenceId = event.recurrenceId.orEmpty(),
            startLocal = event.startLocal,
            endLocal = event.endLocal.takeIf { it.isNotBlank() },
            title = event.title,
            location = event.location,
            notes = event.notes,
        ).errorOrNull()

    private fun TResult<Data?>.errorOrNull(): TError? = (this as? TResult.Failure)?.error
}

/** A series that landed while one of its secondary writes did not. [part] is `source` or `reminders`. */
internal class IcsImportWarning(val eventId: String, val part: String, val error: TError)

internal class IcsImportOutcome(
    val imported: Int,
    val overrides: Int,
    val failed: Int,
    val warnings: List<IcsImportWarning>,
)

/**
 * The import's writes, and its partial-failure contract.
 *
 * ⚠⚠ The event row decides. A series whose event saved is **imported** even when its source or
 * reminders then failed: each such write is a [IcsImportWarning], and the event is kept. Rolling
 * it back instead was rejected: on a re-import (same `UID`, the idempotent path) the delete would
 * take the copy the user **already had** with it, turning a lost export detail (the source only
 * feeds `exportIcs`) or a reminder they can re-set into a lost event. A series whose event did
 * *not* save is `failed`, and so is each of its overrides, which are **not written**: an override
 * on nothing is invisible, and on an older copy it would edit an event the file no longer describes.
 * An override whose series is not in this file at all is written as usual (an exceptions-only file
 * for an event already here).
 *
 * ⚠⚠ Ids are the native import's, [CalendarEventIds.inCalendar]: namespaced by [calendarId], so
 * importing into B a `UID` that lives in A copies it rather than moving it, and a bare-`UID` row
 * already in [calendarId] is adopted. ⚠ Diverging from native here would double every event the
 * moment one realm is imported into from both (`calendar-external-sources.md` §7).
 *
 * @param inCalendar the event ids already stored in [calendarId].
 */
internal suspend fun importEvents(
    events: List<ImportedEvent>,
    calendarId: String,
    origin: String,
    writes: IcsImportWrites,
    inCalendar: Set<String>,
): IcsImportOutcome {
    val idOf = { event: ImportedEvent -> CalendarEventIds.inCalendar(calendarId, Calendar.idFor(event), inCalendar) }
    var imported = 0
    var overrides = 0
    var failed = 0
    val warnings = mutableListOf<IcsImportWarning>()
    val failedSeries = mutableSetOf<String>()
    val (series, exceptions) = events.partition { it.recurrenceId == null }
    for (event in series) {
        val eventId = idOf(event)
        if (writes.saveSeries(eventId, event, calendarId) != null) {
            failed++
            failedSeries += eventId
            continue
        }
        imported++
        writes.saveSource(eventId, event, origin)?.let { warnings += IcsImportWarning(eventId, "source", it) }
        writes.setReminders(eventId, event.reminderMinutes)?.let { warnings += IcsImportWarning(eventId, "reminders", it) }
    }
    for (event in exceptions) {
        val eventId = idOf(event)
        if (eventId in failedSeries || writes.saveOverride(eventId, event) != null) failed++ else overrides++
    }
    return IcsImportOutcome(imported, overrides, failed, warnings)
}
