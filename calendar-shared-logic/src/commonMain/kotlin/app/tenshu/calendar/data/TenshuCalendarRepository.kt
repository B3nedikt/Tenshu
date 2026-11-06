package app.tenshu.calendar.data

import app.tenshu.calendar.data.external.ExternalCalendar
import app.tenshu.calendar.data.external.ExternalCopyBatches
import app.tenshu.calendar.data.external.ExternalCopyIds
import org.tenshu.calendar.common.ics.CalendarEventIds
import app.tenshu.calendar.data.external.ExternalSnapshot
import app.tenshu.calendar.model.CalendarPalette
import app.tenshu.reachability.ControlReachability
import app.tenshu.reachability.UnreachableReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import org.tenshu.core.common.util.runCatchingCancellable
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import org.tenshu.calendar.Calendar
import org.tenshu.calendar.CalendarMirror
import org.tenshu.calendar.deleteCalendarMirror
import org.tenshu.calendar.saveCalendarMirror
import org.tenshu.calendar.mirrorId
import org.tenshu.calendar.mirrorCalendarId
import org.tenshu.calendar.mirrorDeviceId
import org.tenshu.calendar.sourceKind
import org.tenshu.calendar.sourceKey
import org.tenshu.calendar.sourceLabel
import org.tenshu.calendar.remindersEnabled
import org.tenshu.calendar.CalendarMirrorStamp
import org.tenshu.calendar.saveCalendarMirrorStamp
import org.tenshu.calendar.stampDeviceId
import org.tenshu.calendar.stampMirrorId
import org.tenshu.calendar.stampedEpochMs
import org.tenshu.calendar.CalendarPreferences
import org.tenshu.calendar.CalendarSubscription
import org.tenshu.calendar.deleteSubscription
import org.tenshu.calendar.enabled
import org.tenshu.calendar.lastFetchedEpochMs
import org.tenshu.calendar.refreshSubscriptions
import org.tenshu.calendar.saveSubscription
import org.tenshu.calendar.subscriptionId
import org.tenshu.calendar.url
import org.tenshu.calendar.Event
import org.tenshu.calendar.EventOverride
import org.tenshu.calendar.allDay
import org.tenshu.calendar.calendarId
import org.tenshu.calendar.colorArgb
import org.tenshu.calendar.endLocal
import org.tenshu.calendar.eventId
import org.tenshu.calendar.exceptionDates
import org.tenshu.calendar.location
import org.tenshu.calendar.name
import org.tenshu.calendar.notes
import org.tenshu.calendar.recurrenceId
import org.tenshu.calendar.recurrenceRule
import org.tenshu.calendar.sensitive
import org.tenshu.calendar.startLocal
import org.tenshu.calendar.timeZoneId
import org.tenshu.calendar.title
import org.tenshu.calendar.client.calendarService
import org.tenshu.calendar.deleteCalendar
import org.tenshu.calendar.deleteEvent
import org.tenshu.calendar.deleteOccurrence
import org.tenshu.calendar.saveCalendar
import org.tenshu.calendar.saveEvent
import org.tenshu.calendar.Reminder
import org.tenshu.calendar.deleteReminder
import org.tenshu.calendar.minutesBefore
import org.tenshu.calendar.saveEventOverride
import org.tenshu.calendar.saveEventSource
import org.tenshu.calendar.applyExternalEvents
import org.tenshu.calendar.saveReminder
import org.tenshu.calendar.setReminderState
import org.tenshu.calendar.EventSource
import org.tenshu.calendar.origin
import org.tenshu.calendar.raw
import org.tenshu.calendar.common.ics.ExportableEvent
import org.tenshu.calendar.common.ics.IcsExport
import org.tenshu.calendar.common.ics.IcsImport
import org.tenshu.calendar.common.ics.ImportedEvent
import org.tenshu.calendar.common.recurrence.OccurrenceResolver
import org.tenshu.calendar.common.recurrence.OverrideInput
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence
import org.tenshu.calendar.common.recurrence.SeriesInput
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.orThrow
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.entity.Entity
import org.tenshu.core.get
import org.tenshu.preference.LongAdapter
import org.tenshu.preference.deviceId
import org.tenshu.preference.preferenceId
import org.tenshu.preference.value
import org.tenshu.preference.client.preferenceService
import org.tenshu.preference.client.read
import org.tenshu.preference.client.readForDevice
import org.tenshu.preference.client.readOnceForDevice
import org.tenshu.preference.client.set
import org.tenshu.preference.client.setForDevice

/**
 * [CalendarRepository] over the real `:calendar-service`.
 *
 * ## How a window query actually works
 *
 * Read the series rows and their overrides, then hand both to the **pure**
 * `OccurrenceResolver`, which expands the rules and applies the overrides.
 *
 * ⚠⚠ The expansion deliberately does **not** happen in a query. There is no
 * predicate over stored rows that can answer "what happens next Tuesday",
 * because occurrences are not stored — the rule is. So the read is a scan plus
 * a pure fold, and the interesting logic lives somewhere unit-testable rather
 * than inside a chain expression.
 *
 * ⚠ A full scan of the event rows per window. Correct at calendar scale
 * (thousands of rows) and honest about it: narrowing by date is not possible
 * without an index over *expanded* occurrences, which is a materialisation this
 * design rejects (§3 D2). If it ever matters, the fix is an index over series
 * *bounds*, not a different query.
 */
class TenshuCalendarRepository(
    private val client: TenshuClient,
    /**
     * Resolves this device's id, for the DEVICE-scoped visibility rows.
     *
     * ⚠⚠ A *resolver*, not a `String`. Sync identity is not available the
     * instant the app starts, and taking a value here would mean constructing
     * the repository with a blank one and silently reading another device's view
     * state — or none. [CalendarDeviceIdentity] explains the retry.
     */
    private val deviceIdentity: CalendarDeviceIdentity,
    /**
     * The app's one Control-reachability engine (`ADR 0025`). ⚠ Nullable so a test or preview can
     * construct the repository without one; absent, the shell simply never shows a banner.
     */
    // ⚠ Required, never defaulted (ADR 0025 §8.6): `null` only where no engine exists (tests), stated there.
    private val reachability: ControlReachability?,
) : CalendarRepository, CalendarMirrorRepository {

    override fun controlReachable(): Flow<Boolean?> = reachability?.state ?: flowOf(true)

    override fun controlUnreachableReason(): kotlinx.coroutines.flow.StateFlow<UnreachableReason?> =
        reachability?.reason ?: app.tenshu.reachability.NO_UNREACHABLE_REASON

    override fun recheckControl() {
        reachability?.recheck()
    }

    override suspend fun calendars(): List<CalendarSummary> {
        val hidden = hiddenCalendarIds()
        val readOnly = readOnlyCalendarIds(
            runCatchingCancellable { subscriptionRows() }.getOrDefault(emptyList()),
            runCatchingCancellable { mirrorRows() }.getOrDefault(emptyList()),
        )
        return calendarRows().map { row ->
            CalendarSummary(
                calendarId = row.calendarId.get(),
                name = row.name.get(),
                colorArgb = row.colorArgb.get().toInt(),
                sensitive = row.sensitive.get(),
                visible = row.calendarId.get() !in hidden,
                readOnly = row.calendarId.get() in readOnly,
            )
        }.sortedBy { it.name }
    }

    /** The calendars a refresh fills — see [CalendarSummary.readOnly]. */
    private fun readOnlyCalendarIds(
        subscriptions: List<Entity<CalendarSubscription>>,
        mirrors: List<Entity<CalendarMirror>>,
    ): Set<String> =
        subscriptions.mapTo(mutableSetOf()) { it.calendarId.get() } +
            mirrors.map { it.mirrorCalendarId.get() }

    override suspend fun occurrences(
        from: LocalDate,
        to: LocalDate,
        includeHidden: Boolean,
    ): List<ResolvedOccurrence> {
        if (to < from) return emptyList()

        val hidden = if (includeHidden) emptySet() else hiddenCalendarIds()

        return resolveOccurrences(eventRows(), overrideRows(), from, to, hidden)
    }

    /**
     * Rows in, occurrences out — the fold both read paths share.
     *
     * ⚠⚠ Shared rather than copied between [occurrences] and [observeOccurrences]: a
     * one-shot read and a live read of the same data that
     * disagree about optionality or overrides would be a bug nobody could see from
     * either call site. The two differ only in how the rows arrive.
     */
    private fun resolveOccurrences(
        events: List<Entity<Event>>,
        overrideEntities: List<Entity<EventOverride>>,
        from: LocalDate,
        to: LocalDate,
        hidden: Set<String>,
    ): List<ResolvedOccurrence> {
        val series = events
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
        if (series.isEmpty()) return emptyList()

        val eventIds = series.mapTo(mutableSetOf()) { it.eventId }
        val overrides = overrideEntities
            .filter { it.eventId.get() in eventIds }
            .map { row ->
                OverrideInput(
                    eventId = row.eventId.get(),
                    recurrenceId = row.recurrenceId.get(),
                    // ⚠ `?.get()` preserves absence. Coercing to "" here would
                    // undo the schema's per-field optionality and blank every
                    // field the override did not mention.
                    startLocal = row.startLocal?.get(),
                    endLocal = row.endLocal?.get(),
                    title = row.title?.get(),
                    location = row.location?.get(),
                    notes = row.notes?.get(),
                )
            }

        return OccurrenceResolver.resolve(series, overrides, from, to)
    }

    /**
     * ⚠⚠ **Every occurrence in the window, hidden calendars included — visibility is
     * the collector's business.** Joining the hidden set in here would put a
     * preference read back on the write's own code path, where a read taken after a
     * write observes the value from before it (`calendar-app.md` CA-2g); and a live
     * read that re-queried a preference on every event change would be paying for it
     * forever. `CalendarViewModel` collects
     * [observeHiddenCalendars] separately and filters what it already holds.
     *
     * ⚠ `combine`, so a change to *either* relation re-folds: an override edited
     * without its series being touched must move the occurrence it overrides.
     */
    override fun observeOccurrences(
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<ResolvedOccurrence>> {
        if (to < from) return flowOf(emptyList())
        return combine(eventRowsFlow(), overrideRowsFlow()) { events, overrides ->
            resolveOccurrences(events, overrides, from, to, hidden = emptySet())
        }
    }

    /**
     * ⚠ Joined with [observeHiddenCalendars] rather than reading the preference per
     * emission, for the same reason as above. A collector applying its own optimistic
     * visibility wins for the instant before the store agrees — see `CalendarViewModel`.
     */
    override fun observeCalendars(): Flow<List<CalendarSummary>> =
        combine(
            calendarRowsFlow(),
            observeHiddenCalendars(),
            // ⚠⚠ Both start empty and fall back to empty: a relation this Control does not serve
            // must cost the read-only marks, never the whole list (an un-emitting flow in a
            // `combine` is a blank screen).
            subscriptionRowsFlow().onStart { emit(emptyList()) }.catch { emit(emptyList()) },
            mirrorSelection().flow().map { it.rows() }.onStart { emit(emptyList()) }.catch { emit(emptyList()) },
        ) { rows, hidden, subscriptions, mirrors ->
            val readOnly = readOnlyCalendarIds(subscriptions, mirrors)
            rows.map { row ->
                CalendarSummary(
                    calendarId = row.calendarId.get(),
                    name = row.name.get(),
                    colorArgb = row.colorArgb.get().toInt(),
                    sensitive = row.sensitive.get(),
                    visible = row.calendarId.get() !in hidden,
                    readOnly = row.calendarId.get() in readOnly,
                )
            // ⚠ Sorted like `calendars()`, not left in row order: the sidebar and the
            // calendars screen list the same calendars and must list them the same way.
            }.sortedBy { it.name }
        }

    override suspend fun setCalendarVisible(calendarId: String, visible: Boolean) {
        val hidden = hiddenCalendarIds()
        val next = if (visible) hidden - calendarId else hidden + calendarId
        if (next == hidden) return   // nothing to write; a no-op would burn a sync block
        // ⚠ Refuse rather than substitute. A per-device row written under a key that is not this
        // device is the silent no-op `CalendarDeviceIdentity` exists to prevent — and worse here,
        // since it would also apply to whichever device *does* own that key. Throwing is likewise
        // wrong: this is reached from a visibility toggle, and an uncaught coroutine throw on the
        // main thread crashes the app.
        val deviceId = deviceIdentity.awaitOrNull() ?: run {
            println(
                "WARN TenshuCalendarRepository: cannot record calendar visibility — this device " +
                    "has no obtainable sync id, so the change applies to this session only"
            )
            return
        }
        client.preferenceService.setForDevice(
            pref = CalendarPreferences.HiddenCalendars,
            value = next.sorted(),
            deviceId = deviceId,
        )
    }

    /**
     * ⚠⚠ **Seeds the default, *then* switches to the stored rows**, and both halves are
     * load-bearing. The device relation emits nothing at all on a device that has never
     * written a setting, so a consumer collecting this would otherwise render nothing
     * until the first toggle. And the device id is `await`ed rather than resolved once:
     * a screen holds its subscription for its whole life, so resolving to null at
     * subscribe time and completing would strand it on the default **for the session**
     * (see `CalendarDeviceIdentity`).
     */
    override fun observeHiddenCalendars(): Flow<Set<String>> = flow {
        emit(CalendarPreferences.HiddenCalendars.default.toSet())
        val deviceId = deviceIdentity.awaitOrNull() ?: return@flow
        emitAll(
            client.preferenceService
                .readForDevice(CalendarPreferences.HiddenCalendars, deviceId)
                .map { it.toSet() }
        )
    }

    override suspend fun ensureDefaultCalendar() {
        // ⚠⚠ ADR 0025 §8.6: this runs at launch from `App`'s `LaunchedEffect`, ABOVE the NotSetUp
        // wall — so on a Control with no realm an ungated seed writes the Personal calendar and the
        // default-calendar preference into a store that belongs to no realm. It waits for the realm
        // gate, refuses each write while the gate holds, and on a refusal waits for the next
        // release and runs again — the resumption. Where no probe is wired (iOS route-1, tests) the
        // gate is always open and this is a plain seed.
        val gate = reachability
        while (true) {
            gate?.awaitRealmWritable()
            try {
                seedDefaultCalendar(beforeEachWrite = { gate?.refuseIfRealmWritesHeld() })
                return
            } catch (e: app.tenshu.reachability.RealmWritesHeldException) {
                // The gate started holding mid-seed; wait for the next release and run again.
            }
        }
    }

    private suspend fun seedDefaultCalendar(beforeEachWrite: suspend () -> Unit) {
        val existing = calendarRows()
        val current = defaultCalendarId()

        // Already pointed at a calendar that exists: nothing to do. ⚠ This is
        // the branch that runs on every launch, so it must be the cheap one.
        if (current.isNotBlank() && existing.any { it.calendarId.get() == current }) return

        // A calendar exists but no default is set — deliberately left alone; see
        // the interface KDoc on why picking one is the user's call.
        if (existing.isNotEmpty() && current.isNotBlank()) return
        if (existing.isNotEmpty()) return

        // ⚠⚠ A default naming some OTHER calendar while this store holds none is a realm whose
        // rows have not arrived yet (the preference synced first) — or a dangling default, which
        // `deleteCalendar` never leaves behind. Seeding here would write a Personal the user never
        // had, so wait for the next launch. (docs/tech-debt.md, "Calendar seeds its default
        // calendar before the first sync".) ⚠ A default of "personal" with no rows still passes:
        // it is how a seed whose calendar write was refused gets finished, and the store cannot
        // tell that from a realm still syncing — so a freshly paired device can still reset the
        // realm's Personal (name, colour, `sensitive`). Same tech-debt entry.
        if (current.isNotBlank() && current != FIRST_CALENDAR_ID) return

        // ⚠ The preference FIRST, then the calendar (ADR 0025 §8.6): a refusal between the two
        // must leave a state the retry completes. Default-set-but-no-calendar falls through to
        // creating the calendar again; calendar-without-default would hit the "deliberately left
        // alone" branch above and never get its default. ⚠ Written only while unset, so a
        // retry never re-stamps a preference that is already right.
        if (current.isBlank()) {
            beforeEachWrite()
            client.preferenceService.set(CalendarPreferences.DefaultCalendarId, FIRST_CALENDAR_ID)
        }
        beforeEachWrite()
        client.calendarService.saveCalendar(
            calendarId = FIRST_CALENDAR_ID,
            name = FIRST_CALENDAR_NAME,
            colorArgb = FIRST_CALENDAR_COLOR,
        )
    }

    private suspend fun defaultCalendarId(): String {
        val pref = CalendarPreferences.DefaultCalendarId
        val row = client.preferenceService.realmSettings
            .getAll()
            .let { (it as? TResult.Success)?.value?.toList() }
            ?.firstOrNull { it.preferenceId.get() == pref.id }
        return pref.adapter.fromData(row?.value) ?: pref.default
    }

    /** The stored value of a `Long`-valued realm preference, or null if unset. */
    private suspend fun readRealmLong(preferenceId: String): Long? =
        client.preferenceService.realmSettings
            .getAll()
            .let { (it as? TResult.Success)?.value?.toList() }
            ?.firstOrNull { it.preferenceId.get() == preferenceId }
            ?.let { LongAdapter.fromData(it.value) }

    // ── writes ───────────────────────────────────────────────────────────────

    override suspend fun saveEvent(draft: EventDraft): String {
        // ⚠ A blank id means create, and the id is minted HERE rather than by the
        // op — the caller needs it back to navigate to what it just created, and
        // an op that minted its own would have to return it through a channel
        // the service deliberately does not have (its ops return null).
        val eventId = draft.eventId.ifBlank { newEventId(draft) }
        client.calendarService.saveEvent(
            eventId = eventId,
            title = draft.title,
            startLocal = draft.startLocal,
            endLocal = draft.endLocal,
            calendarId = draft.calendarId,
            timeZoneId = draft.timeZoneId,
            allDay = draft.allDay,
            location = draft.location,
            notes = draft.notes,
            recurrenceRule = draft.recurrenceRule,
            // ⚠⚠ Deliberately NOT passed: exceptionDates. A save from the editor
            // must not clear the occurrences the user has already skipped, and
            // `SaveEvent` treats an omitted optional as absent — so the existing
            // value would be lost if this were threaded through as "".
            exceptionDates = existingExceptionDates(eventId),
        )
        return eventId
    }

    override suspend fun saveOccurrence(draft: EventDraft, recurrenceId: String) {
        client.calendarService.saveEventOverride(
            eventId = draft.eventId,
            recurrenceId = recurrenceId,
            startLocal = draft.startLocal,
            endLocal = draft.endLocal,
            title = draft.title,
            location = draft.location,
            notes = draft.notes,
        )
    }

    override suspend fun deleteEvent(eventId: String) {
        client.calendarService.deleteEvent(eventId)
    }

    override suspend fun deleteOccurrence(eventId: String, recurrenceId: String) {
        client.calendarService.deleteOccurrence(eventId, recurrenceId)
    }

    // ── calendar management ──────────────────────────────────────────────────

    override suspend fun saveCalendar(
        calendarId: String,
        name: String,
        colorArgb: Int,
        sensitive: Boolean,
    ): String {
        val id = calendarId.ifBlank { newCalendarId(name) }
        client.calendarService.saveCalendar(
            calendarId = id,
            name = name,
            colorArgb = colorArgb,
            sensitive = sensitive,
        )
        return id
    }

    override suspend fun deleteCalendar(calendarId: String) {
        // ⚠ Under [mirrorWriteLock]: a mirror refresh mid-write would otherwise upsert events and
        // reminders into the calendar this just deleted, and nothing would ever sweep them.
        mirrorWriteLock.withLock { client.calendarService.deleteCalendar(calendarId) }

        // ⚠⚠ Re-point the default *after* the delete, and only if it pointed
        // here. Leaving it dangling would break every editor-less create — Mia's
        // path fails loudly with "no default calendar", from a screen that never
        // mentioned defaults. ⚠ A blank id is the honest answer when the last
        // calendar is gone: `ensureDefaultCalendar` re-seeds on the next launch,
        // and inventing one here would race it.
        if (defaultCalendarId() != calendarId) return
        // ⚠ Only a writable survivor: the default is where Mia's headless create files, and a
        // subscribed or mirrored calendar would delete that event at its next refresh. None left
        // means blank — the same honest answer as no calendar at all.
        val readOnly = readOnlyCalendarIds(
            runCatchingCancellable { subscriptionRows() }.getOrDefault(emptyList()),
            runCatchingCancellable { mirrorRows() }.getOrDefault(emptyList()),
        )
        val survivor = calendarRows()
            .map { it.calendarId.get() }
            .filterNot { it == calendarId || it in readOnly }
            .minOrNull()
            .orEmpty()
        client.preferenceService.set(CalendarPreferences.DefaultCalendarId, survivor)
    }

    override suspend fun defaultCalendar(): String = defaultCalendarId()

    override suspend fun setDefaultCalendar(calendarId: String) {
        client.preferenceService.set(CalendarPreferences.DefaultCalendarId, calendarId)
    }

    /**
     * ⚠ Derived from the name rather than random, mirroring [newEventId]: two
     * devices that both create "Work" offline converge on one row instead of
     * leaving the user with two identical calendars they cannot tell apart.
     *
     * ⚠ Only ever called for a *create* — a rename passes the existing id — so
     * the collision that matters is two calendars deliberately given the same
     * name, which would silently become one row. That is refused a layer up, in
     * the view model, where there is a name to complain about.
     */
    private fun newCalendarId(name: String): String =
        "cal-" + name.trim().lowercase().hashCode().toUInt().toString(16)

    // ── subscriptions ────────────────────────────────────────────────────────

    override suspend fun subscriptions(): List<CalendarSubscriptionSummary> =
        subscriptionRows().toSummaries()

    /**
     * ⚠ Live, so the row's *last fetched* stamp moves on its own — a refresh is a
     * background op, and a screen that showed a stale "never" until the user left and
     * came back would be reporting the opposite of what just happened.
     */
    override fun observeSubscriptions(): Flow<List<CalendarSubscriptionSummary>> =
        subscriptionRowsFlow().map { it.toSummaries() }

    /**
     * ⚠ Seeds the declared default before the stored row, for the same reason as
     * [observeHiddenCalendars]: the realm relation stays silent on a realm that has
     * never held this setting, so a collector waiting for a first emission waits
     * forever. ⚠ REALM-scoped, unlike visibility — which calendar new events land in
     * is a statement about the data, not about this device.
     */
    override fun observeDefaultCalendar(): Flow<String> = flow {
        emit(CalendarPreferences.DefaultCalendarId.default)
        emitAll(client.preferenceService.read(CalendarPreferences.DefaultCalendarId))
    }

    override suspend fun addSubscription(url: String, name: String): String? {
        // ⚠⚠ Refused here rather than at fetch time, so a user who pastes an
        // `http://` link is told **now** instead of subscribing to something that
        // then silently never updates. The proxy refuses it again — this is the
        // message, that is the guarantee.
        val normalized = normalizeSubscriptionUrl(url) ?: return null
        val subscriptionId = CalendarSubscription.idFor(normalized)

        // ⚠ The calendar id derives from the subscription's, so re-adding a feed
        // lands in the calendar it already filled rather than making a second one.
        val calendarId = "$subscriptionId-cal"
        client.calendarService.saveCalendar(
            calendarId = calendarId,
            name = name.trim().ifBlank { DEFAULT_SUBSCRIPTION_NAME },
            colorArgb = CALENDAR_SUBSCRIPTION_COLOR,
        )
        client.calendarService.saveSubscription(
            subscriptionId = subscriptionId,
            calendarId = calendarId,
            url = normalized,
        )
        return subscriptionId
    }

    override suspend fun setSubscriptionEnabled(subscriptionId: String, enabled: Boolean) {
        val row = subscriptionRows().firstOrNull { it.subscriptionId.get() == subscriptionId }
            ?: return
        client.calendarService.saveSubscription(
            subscriptionId = subscriptionId,
            calendarId = row.calendarId.get(),
            url = row.url.get(),
            enabled = enabled,
            // ⚠ Neither the etag nor the timestamp is passed: absent means "keep
            // what the row holds", so pausing does not force the next resume to
            // re-download a calendar that had not changed.
        )
    }

    override suspend fun removeSubscription(subscriptionId: String) {
        client.calendarService.deleteSubscription(subscriptionId)
    }

    override suspend fun refreshSubscriptions() {
        client.calendarService.refreshSubscriptions()
    }

    private fun subscriptionSelection() = client.calendarService.subscriptions.select {
        fields(
            CalendarSubscription.Field.SUBSCRIPTION_ID,
            CalendarSubscription.Field.CALENDAR_ID,
            CalendarSubscription.Field.URL,
            CalendarSubscription.Field.ENABLED,
            CalendarSubscription.Field.LAST_FETCHED_EPOCH_MS,
            CalendarSubscription.Field.ETAG,
        )
    }

    private suspend fun subscriptionRows(): List<Entity<CalendarSubscription>> =
        subscriptionSelection().getAll().rows()

    private fun subscriptionRowsFlow(): Flow<List<Entity<CalendarSubscription>>> =
        subscriptionSelection().flow().map { it.rows() }

    /** The one mapping both subscription reads share — see `resolveOccurrences` on why. */
    private fun List<Entity<CalendarSubscription>>.toSummaries() = map {
        CalendarSubscriptionSummary(
            subscriptionId = it.subscriptionId.get(),
            calendarId = it.calendarId.get(),
            url = it.url.get(),
            enabled = it.enabled.get(),
            lastFetchedEpochMs = it.lastFetchedEpochMs.get(),
        )
    }.sortedBy { it.url }

    // ── interop ──────────────────────────────────────────────────────────────

    override suspend fun importIcs(
        text: String,
        calendarId: String,
        origin: String,
    ): IcsImportResult {
        val parsed = IcsImport.read(text)
        var imported = 0
        var overrides = 0

        // ⚠⚠ Series first, overrides second, in two passes over one list. A file
        // routinely lists an exception *before* the series it belongs to, and an
        // override written first would be attached to an event that does not yet
        // exist — harmless for storage (nothing validates it) but not for the
        // `existingExceptionDates` read that `saveEvent` does, which would then
        // see no series and silently drop the file's EXDATEs.
        val (series, exceptions) = parsed.events.partition { it.recurrenceId == null }

        // ⚠⚠ Namespaced by the target calendar (§7 of `calendar-external-sources.md`): importing
        // into B a `UID` that lives in A makes a second copy instead of moving A's. Re-importing
        // into the same calendar still converges, and a file exported from a phone copy lands on
        // that copy's rows — both name the event `"$calendarId~$uid"`.
        val existing = eventRows()
            .filter { it.calendarId.get() == calendarId }
            .mapTo(mutableSetOf()) { it.eventId.get() }
        val idOf = { event: ImportedEvent -> CalendarEventIds.inCalendar(calendarId, idFor(event), existing) }

        series.forEach { event ->
            val eventId = idOf(event)
            client.calendarService.saveEvent(
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
            )
            client.calendarService.saveEventSource(eventId, event.raw, origin)
            // ⚠ Set, not add: a re-import must converge on what the file says
            // rather than accumulating the union of every import.
            setReminders(eventId, event.reminderMinutes)
            imported++
        }

        exceptions.forEach { event ->
            client.calendarService.saveEventOverride(
                eventId = idOf(event),
                recurrenceId = event.recurrenceId.orEmpty(),
                startLocal = event.startLocal,
                endLocal = event.endLocal.takeIf { it.isNotBlank() },
                title = event.title,
                location = event.location,
                notes = event.notes,
            )
            overrides++
        }

        return IcsImportResult(imported = imported, overrides = overrides, skipped = parsed.skipped)
    }

    override suspend fun exportIcs(calendarId: String): String {
        val calendars = calendarRows()
        val events = eventRows()
            .filter { calendarId.isBlank() || it.calendarId.get() == calendarId }
        if (events.isEmpty()) return IcsExport.write(emptyList(), calendarNameFor(calendars, calendarId))

        val eventIds = events.mapTo(mutableSetOf()) { it.eventId.get() }
        val sources = sourceRows().associate { it.eventId.get() to it.raw.get() }
        // ⚠ The sidecar's `UID` where there is one: an event copied from another calendar is
        // stored under a namespaced id, and the file must carry the source's identity, not ours
        // (`ExportableEvent.uid`). Overrides take their series' value, or they detach.
        val uidOf = sources.mapValues { (_, raw) -> IcsImport.uidOf(raw) }
        val remindersByEvent = reminderRows()
            .filter { it.eventId.get() in eventIds }
            .groupBy({ it.eventId.get() }, { it.minutesBefore.get().toInt() })

        val series = events.map { row ->
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

        // ⚠⚠ Overrides are exported as their own `VEVENT`s carrying the series'
        // `UID` and a `RECURRENCE-ID` — the shape RFC 5545 defines and the one
        // `IcsImport` reads back. Omitting them would make an export lossy in a
        // way the sidecar cannot rescue, because an override is *our* row and has
        // no raw of its own.
        val byId = events.associateBy { it.eventId.get() }
        val exceptions = overrideRows()
            .filter { it.eventId.get() in eventIds }
            .mapNotNull { override ->
                val parent = byId[override.eventId.get()] ?: return@mapNotNull null
                ExportableEvent(
                    eventId = override.eventId.get(),
                    // ⚠ Inherit from the series wherever the override is silent —
                    // an absent field means "unchanged", and writing "" would
                    // export a blank title for an occurrence that merely moved.
                    title = override.title?.get() ?: parent.title.get(),
                    startLocal = override.startLocal?.get() ?: override.recurrenceId.get(),
                    endLocal = override.endLocal?.get().orEmpty(),
                    timeZoneId = parent.timeZoneId.get(),
                    allDay = parent.allDay.get(),
                    location = override.location?.get() ?: parent.location.get(),
                    notes = override.notes?.get() ?: parent.notes.get(),
                    // ⚠ No rule and no exceptions on an override: it *is* one
                    // occurrence, and an RRULE here would make it a second series.
                    recurrenceRule = "",
                    exceptionDates = "",
                    recurrenceId = override.recurrenceId.get(),
                    uid = uidOf[override.eventId.get()].orEmpty(),
                )
            }

        return IcsExport.write(series + exceptions, calendarNameFor(calendars, calendarId))
    }

    // ── calendars the user already has ───────────────────────────────────────

    override suspend fun copyExternalCalendar(
        source: ExternalCalendar,
        snapshot: ExternalSnapshot,
    ): ExternalCopyResult {
        val calendarId = ExternalCopyIds.calendarId(source)
        val calendars = calendarRows()
        val existing = calendars.firstOrNull { it.calendarId.get() == calendarId }

        // ⚠ An existing target keeps the name and colour it has now — the user may have renamed
        // their copy, and a second copy must not quietly put the source's name back.
        val name = existing?.name?.get() ?: copyNameFor(source, calendars)
        if (existing == null) {
            client.calendarService.saveCalendar(
                calendarId = calendarId,
                name = name,
                // ⚠ Opaque, like every `CalendarPalette` colour: the calendars screen draws
                // `colorArgb` as given, so a source colour stored without its alpha would
                // render as an invisible dot there.
                colorArgb = (source.colorArgb ?: CalendarPalette.DEFAULT) or OPAQUE,
            ).orThrow("Creating the calendar")
        }

        val inTarget = eventRows()
            .filter { it.calendarId.get() == calendarId }
            .mapTo(mutableSetOf()) { it.eventId.get() }
        val (series, exceptions) = snapshot.events.partition { it.recurrenceId == null }
        val written = writeExternal(
            calendarId = calendarId,
            series = series,
            exceptions = exceptions,
            idOf = { ExternalCopyIds.eventId(calendarId, it.uid, inTarget) },
            withReminders = true,
            origin = source.name,
        )

        return ExternalCopyResult(
            calendarId = calendarId,
            calendarName = name,
            copied = series.size - written.tooLarge,
            added = written.added,
            overrides = written.overrides,
            // ⚠ Named, never swallowed: a series too big to send is reported like one the reader
            // could not place.
            skipped = snapshot.skipped + written.tooLarge,
        )
    }

    // ── calendars kept in sync ───────────────────────────────────────────────

    override fun observeMirrors(): Flow<List<CalendarMirrorSummary>> =
        combine(
            mirrorSelection().flow().map { it.rows() },
            // ⚠ Starts and falls back empty: a stamp is "not updated yet" at worst, and an
            // un-emitting flow in a `combine` would hide the mirrors themselves.
            stampSelection().flow().map { it.rows() }.onStart { emit(emptyList()) }.catch { emit(emptyList()) },
        ) { mirrors, stamps -> mirrors.toMirrorSummaries(stamps) }

    override suspend fun mirrors(): List<CalendarMirrorSummary> =
        mirrorSelection().getAll().rows().toMirrorSummaries(stampSelection().getAll().rows())

    override suspend fun thisDeviceId(): String? = deviceIdentity.awaitOrNull()

    /** Under [mirrorWriteLock]: it reads the existing row and writes it back. */
    override suspend fun startMirror(
        source: ExternalCalendar,
        remindersEnabled: Boolean,
    ): CalendarMirrorSummary {
        // ⚠⚠ Resolved BEFORE the lock, and bounded: `awaitOrNull` waits for as long as the id is
        // "not yet" known, and waiting under [mirrorWriteLock] would block every stop, refresh,
        // delete and reminder switch in the process behind a start that never finishes.
        val deviceId = withTimeoutOrNull(DEVICE_ID_WAIT_MS) { deviceIdentity.awaitOrNull() }
            ?: error("This device's sync id is not known yet")
        return mirrorWriteLock.withLock { startMirrorLocked(source, remindersEnabled, deviceId) }
    }

    private suspend fun startMirrorLocked(
        source: ExternalCalendar,
        remindersEnabled: Boolean,
        deviceId: String,
    ): CalendarMirrorSummary {
        val mirrorId = CalendarMirror.idFor(source.kind.name, source.sourceKey)
        val existing = mirrorRows().firstOrNull { it.mirrorId.get() == mirrorId }
        val calendars = calendarRows()
        // ⚠⚠ A mirror that already exists keeps its calendar (a rebind to this device). With none,
        // a calendar under the derived id is what an earlier *Stop syncing (keep events)* left
        // behind — editable since, perhaps edited — and filling it again would make it read-only
        // and let the first refresh sweep what the user added. So a restarted mirror gets a calendar
        // of its own beside it.
        val calendarId = existing?.mirrorCalendarId?.get() ?: run {
            val taken = calendars.mapTo(mutableSetOf()) { it.calendarId.get() }
            val base = CalendarMirror.calendarIdFor(mirrorId)
            generateSequence(1) { it + 1 }
                .map { n -> if (n == 1) base else "$base-$n" }
                .first { it !in taken }
        }

        if (calendars.none { it.calendarId.get() == calendarId }) {
            client.calendarService.saveCalendar(
                calendarId = calendarId,
                name = copyNameFor(source, calendars),
                colorArgb = (source.colorArgb ?: CalendarPalette.DEFAULT) or OPAQUE,
            ).orThrow("Creating the calendar")
        }
        val summary = CalendarMirrorSummary(
            mirrorId = mirrorId,
            calendarId = calendarId,
            sourceKind = source.kind.name,
            sourceKey = source.sourceKey,
            sourceLabel = listOf(source.name, source.accountName).filter { it.isNotBlank() }
                .joinToString(" · "),
            deviceId = deviceId,
            boundHere = true,
            remindersEnabled = remindersEnabled,
            lastRefreshedEpochMs = 0L,
        )
        // ⚠ Rebinding an existing mirror with reminders off is the switch turned off, so it acts
        // like `setMirrorReminders(false)` here and now — not at a refresh that may fail.
        val turnedOff = existing != null && existing.remindersEnabled.get() && !remindersEnabled
        saveMirror(summary)
        // ⚠ This device's stamp starts at 0 — "not updated yet", which the refresher answers with a
        // full reconcile — on a fresh start and a rebind alike. Stamps are per device, so a
        // row this device left from an earlier binding, or an orphan a stale stamp wrote after a
        // stop, must not claim a freshness this binding has not earned.
        saveStamp(mirrorId, deviceId, 0L)
        if (turnedOff) removeMirrorReminders(calendarId)
        return summary
    }

    /**
     * ⚠⚠ Held by a refresh's writes and by [stopMirror]. A refresh can sit in the provider read for
     * a while; if the user stops syncing meanwhile, the calendar becomes editable at once, and a
     * refresh that then wrote its captured snapshot would overwrite their edits and sweep what they
     * added. Under this lock a refresh re-checks that its mirror still exists — same calendar, same
     * device — before writing anything, and a stop waits for a write already under way.
     *
     * ⚠ In-process only, which covers this device's job and screens (one repository per process).
     * A stop from *another* device arrives by sync and can still meet a refresh mid-write here.
     */
    private val mirrorWriteLock = Mutex()

    override suspend fun syncMirror(
        mirror: CalendarMirrorSummary,
        snapshot: ExternalSnapshot,
    ): MirrorSyncResult = mirrorWriteLock.withLock {
        val live = mirrorRows(strict = true).firstOrNull { it.mirrorId.get() == mirror.mirrorId }
        if (live == null ||
            live.mirrorCalendarId.get() != mirror.calendarId ||
            live.mirrorDeviceId.get() != mirror.deviceId
        ) {
            // Stopped, rebound or re-pointed since this refresh began: write nothing.
            return@withLock MirrorSyncResult(written = 0, deleted = 0, unchanged = 0, skipped = 0)
        }
        // ⚠ The live reminder switch, not the one captured when the refresh began: turning
        // reminders off mid-refresh deletes them, and a stale `true` would write them back.
        syncMirrorLocked(mirror.copy(remindersEnabled = live.remindersEnabled.get()), snapshot)
    }

    private suspend fun syncMirrorLocked(
        mirror: CalendarMirrorSummary,
        snapshot: ExternalSnapshot,
    ): MirrorSyncResult {
        val calendarId = mirror.calendarId
        val idOf = { event: ImportedEvent -> ExternalCopyIds.eventId(calendarId, event.uid, emptySet()) }
        val (readSeries, readExceptions) = snapshot.events.partition { it.recurrenceId == null }

        val realm = eventRows(strict = true)
            .filter { it.calendarId.get() == calendarId }
            .associateBy { it.eventId.get() }
        // ⚠⚠ A cancelled occurrence lives in its series' `exceptionDates`, not in an override row.
        // When one of a series' exceptions could not be read (`incompleteOverridesFor`), the
        // stored cancellations are kept alongside what was read, or the occurrence comes back.
        val window = snapshot.window
        val storedOverrides = overrideRows(strict = true).filter { it.eventId.get() in realm }
        val realmOverrides = storedOverrides.groupBy({ it.eventId.get() }, ::overrideKey)
        // ⚠⚠ A windowed read saw only the changed occurrences it was asked about: those whose
        // original slot is outside the window, or that were MOVED outside it (EventKit queries by
        // where an occurrence is now, so one moved from 15 July to 25 June is absent from a July
        // read). Their stored overrides are kept, or the op (which *sets* overrides) would undo them.
        // ⚠ Judged by the occurrence's EFFECTIVE date — where it is now, which is what EventKit
        // queries by: the moved-to start when there is one, else the original slot.
        fun Entity<EventOverride>.effective(): String =
            startLocal?.get().orEmpty().ifBlank { recurrenceId.get() }
        val readKeys = readExceptions.mapTo(mutableSetOf()) { idOf(it) to it.recurrenceId }
        val realmReminders = reminderRows(strict = true)
            .filter { it.eventId.get() in realm }
            .groupBy({ it.eventId.get() }, { it.minutesBefore.get().toInt() })
        // ⚠ The sidecar too: a source that changes only what we cannot model (`RDATE`, `EXRULE`)
        // changes nothing else here, and `exportIcs` gives those back from the sidecar.
        val realmSources = sourceRows(strict = true)
            .filter { it.eventId.get() in realm }
            .associate { it.eventId.get() to it.raw.get() }
        // ⚠⚠ What the reader could not convert this time: a skipped series is still at the source,
        // and treating it as deleted would take its overrides, sidecar and reminders too.
        val unreadable = snapshot.unreadableUids.mapTo(mutableSetOf()) {
            ExternalCopyIds.eventId(calendarId, it, emptySet())
        }
        // ⚠⚠ For a windowed read, every stored series with no occurrence certainly inside the
        // window: the reader never asked about it, so its absence says nothing.
        // ⚠ With its stored moves applied: one moved INTO the window would have been returned, and
        // slots moved OUT of it would not.
        val movesBySeries = storedOverrides.groupBy { it.eventId.get() }
        val unasked = if (window == null) emptySet() else realm.filter { (eventId, row) ->
            val moves = movesBySeries[eventId].orEmpty().filter { it.startLocal?.get().orEmpty().isNotBlank() }
            val movedIn = moves.any { window.coversLocal(it.startLocal?.get().orEmpty()) }
            val movedOut = moves.filterNot { window.coversLocal(it.startLocal?.get().orEmpty()) }
                .mapTo(mutableSetOf()) { it.recurrenceId.get() }
            !movedIn && !window.touchesSeries(
                row.startLocal.get(),
                row.endLocal.get(),
                row.recurrenceRule.get(),
                row.exceptionDates.get(),
                movedOutSlots = movedOut,
            )
        }.keys
        // ⚠⚠ A recurring series absent from a windowed read that still has occurrences OUTSIDE the
        // window: every covered occurrence was cancelled, or the whole series deleted — EventKit
        // cannot say which. Read as cancellations of the covered slots, so history and far-future
        // occurrences survive; a deleted series is cancelled slot by slot as the window advances.
        val readIds = readSeries.mapTo(mutableSetOf(), idOf)
        val partlyCancelled = if (window == null) emptyList() else
            (realm.keys - readIds - unreadable - unasked).mapNotNull { eventId ->
                val row = realm.getValue(eventId)
                val rule = row.recurrenceRule.get()
                val uid = eventId.removePrefix("$calendarId~")
                if (rule.isBlank() || uid == eventId) return@mapNotNull null
                if (!window.reachesBeyond(row.startLocal.get(), rule, row.exceptionDates.get())) return@mapNotNull null
                val movedOut = movesBySeries[eventId].orEmpty()
                    .filterNot { window.coversLocal(it.effective()) }
                    .mapTo(mutableSetOf()) { it.recurrenceId.get() }
                val covered = window.coveredSlots(row.startLocal.get(), rule, row.exceptionDates.get(), movedOut)
                row.toImportedSeries(uid, realmSources[eventId].orEmpty(), realmReminders[eventId].orEmpty())
                    // ⚠ With its existing cancellations: `coveredSlots` leaves those out, and the
                    // merge below keeps stored ones only outside the window.
                    .copy(exceptionDates = (row.exceptionDates.get().dateSet() + covered).sorted().joinToString(","))
            }
        val considered = readSeries + partlyCancelled
        val windowKept = if (window == null) emptyList() else considered.flatMap { event ->
            val eventId = idOf(event)
            // ⚠ Slots the reader saw occupied: a move whose original slot came back is undone.
            val observed = snapshot.observedOccurrenceDates[event.uid].orEmpty()
            storedOverrides
                .filter { row ->
                    row.eventId.get() == eventId &&
                        row.recurrenceId.get().take(10) !in observed &&
                        !window.coversLocal(row.effective())
                }
                .map { it.toImportedOverride(event.uid) }
        }
        // ⚠⚠ A stored move whose effective date IS covered, and that the read did not return, was
        // cancelled at the source: its original slot becomes a cancellation — or, if that slot is
        // outside the window, the original occurrence would come back. ⚠ Indistinguishable from a
        // second move to outside the window (over a year away); the cancellation is the likelier.
        val cancelledMoves: Map<String, Set<String>> = if (window == null) emptyMap() else considered.associate { event ->
            val eventId = idOf(event)
            idOf(event) to storedOverrides
                .filter { row ->
                    row.eventId.get() == eventId &&
                        row.startLocal?.get().orEmpty().isNotBlank() &&
                        window.coversLocal(row.effective()) &&
                        (eventId to row.recurrenceId.get()) !in readKeys
                }
                .mapTo(mutableSetOf()) { it.recurrenceId.get().take(10) }
        }
        val series = considered.map { event ->
            val row = realm[idOf(event)] ?: return@map event
            val stored = row.exceptionDates.get().dateSet()
            val kept = when {
                event.uid in snapshot.incompleteOverridesFor -> stored
                // ⚠⚠ A windowed read inferred cancellations only inside its window: the stored
                // ones outside it are still true, and dropping them brings old occurrences back.
                window != null -> stored.filterTo(mutableSetOf()) { !window.coversLocal(it) }
                else -> return@map event
            }
            // ⚠ A kept move wins over a cancellation inferred for its original slot — the slot
            // is empty because the occurrence moved out of the window, not because it was deleted.
            // ⚠ And a changed occurrence actually read this time beats a stored cancellation of its
            // slot: the source restored it (the resolver applies exception dates first).
            val readMoved = readExceptions.filter { idOf(it) == idOf(event) }
            val occupied = (windowKept.filter { idOf(it) == idOf(event) } + readMoved)
                .mapTo(mutableSetOf()) { it.recurrenceId.orEmpty().take(10) }
            // ⚠ Slots the reader saw occupied are not cancelled, whatever was stored — this covers
            // the margin days `coversLocal` deliberately leaves out.
            val seen = snapshot.observedOccurrenceDates[event.uid].orEmpty()
            val cancelled = cancelledMoves[idOf(event)].orEmpty()
            if (kept.isEmpty() && occupied.isEmpty() && cancelled.isEmpty()) return@map event
            // ⚠ `seen` last: a slot the reader saw occupied is never cancelled, whichever rule
            // above would have cancelled it (a move restored to its slot is ordinary, not read).
            val dates = (kept + event.exceptionDates.dateSet() + cancelled) - seen - occupied
            event.copy(exceptionDates = dates.sorted().joinToString(","))
        }
        // ⚠⚠ A series whose changed occurrence could not be read this time keeps the stored
        // override for it: the op *sets* overrides, so leaving it out would move that occurrence
        // back (`ExternalSnapshot.incompleteOverridesFor`).
        val incompleteKept = snapshot.incompleteOverridesFor.flatMap { uid ->
            val eventId = ExternalCopyIds.eventId(calendarId, uid, emptySet())
            val read = readExceptions.filter { idOf(it) == eventId }.mapTo(mutableSetOf()) { it.recurrenceId }
            storedOverrides
                .filter { it.eventId.get() == eventId && it.recurrenceId.get() !in read }
                .map { it.toImportedOverride(uid) }
        }
        val exceptions = readExceptions + (incompleteKept + windowKept)
            .filter { (idOf(it) to it.recurrenceId) !in readKeys }
            .distinctBy { idOf(it) to it.recurrenceId }
        val wantedOverrides = exceptions.groupBy(idOf)

        val changed = series.filter { event ->
            val id = idOf(event)
            val row = realm[id] ?: return@filter true
            val wantedReminders = if (mirror.remindersEnabled) event.reminderMinutes.toSet() else emptySet()
            !row.matches(event) ||
                realmSources[id].orEmpty() != event.raw ||
                realmReminders[id].orEmpty().toSet() != wantedReminders ||
                realmOverrides[id].orEmpty().toSet() !=
                wantedOverrides[id].orEmpty().map(::overrideKey).toSet()
        }
        val written = writeExternal(
            calendarId = calendarId,
            series = changed,
            exceptions = exceptions,
            idOf = idOf,
            withReminders = mirror.remindersEnabled,
            origin = mirror.sourceLabel,
        )

        // ⚠ The sweep. Safe only because a mirrored calendar is read-only — everything in it came
        // from the source (as with a subscription). ⚠ Per id through `DeleteEvent`, so overrides,
        // sidecars and reminders go with each one; a source rarely deletes more than a handful at a
        // time. ⚠⚠ Minus the unreadable, the unasked and the partly cancelled (all computed above).
        val gone = realm.keys - series.mapTo(mutableSetOf(), idOf) - unreadable - unasked
        gone.forEach { client.calendarService.deleteEvent(it).orThrow("Removing a deleted event") }

        return MirrorSyncResult(
            written = changed.size - written.tooLarge,
            deleted = gone.size,
            unchanged = series.size - changed.size,
            skipped = snapshot.skipped + written.tooLarge,
        )
    }

    /**
     * ⚠⚠ Writes this device's [CalendarMirrorStamp] and **never the mirror row**. Stamping by a
     * read-modify-write of the whole shared row would let a bound phone that was offline while
     * another device stopped the sync, deleted the calendar or switched reminders off re-save the
     * row as it last saw it — a newer block than the stop's tombstone, so the stop would be undone
     * on every device (`docs/sync/design.md` §2.6). `CalendarMirrorStopSystemTest` pins it.
     *
     * ⚠ Only a mirror bound here: a device that has heard of a rebind elsewhere has nothing to
     * stamp, and must never write the new device's row.
     */
    override suspend fun stampMirror(mirrorId: String, epochMs: Long) {
        mirrorWriteLock.withLock {
            val mirror = mirrors().firstOrNull { it.mirrorId == mirrorId && it.boundHere } ?: return
            saveStamp(mirrorId, mirror.deviceId, epochMs)
        }
    }

    override suspend fun setMirrorReminders(mirrorId: String, enabled: Boolean) {
        mirrorWriteLock.withLock {
            val mirror = mirrors().firstOrNull { it.mirrorId == mirrorId } ?: return
            // ⚠ The stamp goes back to 0 — "not updated yet" — which `MirrorRefresher` reads as
            // "reconcile, whatever the digest says". Off deletes the rows here; an off→on on
            // another device would otherwise leave the bound phone's digest matching, and the
            // reminders would never come back while the source stayed the same.
            saveMirror(mirror.copy(remindersEnabled = enabled))
            // ⚠ The bound device's row, from whichever device flipped the switch — the one stamp a
            // device other than its owner writes, and only ever to 0.
            saveStamp(mirrorId, mirror.deviceId, 0L)
            // ⚠⚠ Off takes effect here, on whichever device asked — not at the bound phone's next
            // refresh, which may never come (offline, source gone; and no device but that one
            // refreshes). The reminders are realm rows, so any device can remove them. On needs the
            // source, so only the bound device's refresh can bring them back.
            if (!enabled) removeMirrorReminders(mirror.calendarId)
        }
    }

    /** Deletes every reminder row in [calendarId] — the "off" half of a mirror's reminder switch. */
    private suspend fun removeMirrorReminders(calendarId: String) {
        val inMirror = eventRows()
            .filter { it.calendarId.get() == calendarId }
            .mapTo(mutableSetOf()) { it.eventId.get() }
        reminderRows()
            .filter { it.eventId.get() in inMirror }
            .forEach {
                client.calendarService
                    .deleteReminder(Reminder.idFor(it.eventId.get(), it.minutesBefore.get().toInt()))
                    .orThrow("Removing a reminder")
            }
    }

    override suspend fun stopMirror(mirrorId: String) {
        mirrorWriteLock.withLock {
            client.calendarService.deleteCalendarMirror(mirrorId).orThrow("Stopping the sync")
        }
    }

    private suspend fun saveMirror(mirror: CalendarMirrorSummary) {
        client.calendarService.saveCalendarMirror(
            mirrorId = mirror.mirrorId,
            calendarId = mirror.calendarId,
            deviceId = mirror.deviceId,
            sourceKind = mirror.sourceKind,
            sourceKey = mirror.sourceKey,
            sourceLabel = mirror.sourceLabel,
            remindersEnabled = mirror.remindersEnabled,
        ).orThrow("Saving the sync")
    }

    private suspend fun saveStamp(mirrorId: String, deviceId: String, epochMs: Long) {
        client.calendarService.saveCalendarMirrorStamp(mirrorId, deviceId, epochMs)
            .orThrow("Recording the refresh")
    }

    /**
     * Whether a stored series already says what [event] says.
     *
     * ⚠ A blank wanted end is not compared: the op gives such an event an implied end the source
     * never named, and comparing would call it changed on every refresh. ⚠ Exception dates are
     * compared as a set — an order difference is not a change.
     */
    private fun Entity<Event>.matches(event: ImportedEvent): Boolean =
        title.get() == event.title &&
            startLocal.get() == event.startLocal &&
            (event.endLocal.isBlank() || endLocal.get() == event.endLocal) &&
            timeZoneId.get() == event.timeZoneId &&
            allDay.get() == event.allDay &&
            location.get() == event.location &&
            notes.get() == event.notes &&
            recurrenceRule.get() == event.recurrenceRule &&
            exceptionDates.get().dateSet() == event.exceptionDates.dateSet()

    private fun String.dateSet(): Set<String> =
        split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun overrideKey(row: Entity<EventOverride>): List<String> = listOf(
        row.recurrenceId.get(),
        row.startLocal?.get().orEmpty(),
        row.endLocal?.get().orEmpty(),
        row.title?.get().orEmpty(),
        row.location?.get().orEmpty(),
        row.notes?.get().orEmpty(),
    )

    /** A stored series, back in the shape the writer sends — see the windowed partial cancel. */
    private fun Entity<Event>.toImportedSeries(uid: String, raw: String, reminders: List<Int>) = ImportedEvent(
        uid = uid,
        title = title.get(),
        startLocal = startLocal.get(),
        endLocal = endLocal.get(),
        timeZoneId = timeZoneId.get(),
        allDay = allDay.get(),
        location = location.get(),
        notes = notes.get(),
        recurrenceRule = recurrenceRule.get(),
        exceptionDates = exceptionDates.get(),
        recurrenceId = null,
        reminderMinutes = reminders.distinct().sorted(),
        raw = raw,
    )

    /** A stored override, back in the shape the writer sends — see `incompleteOverridesFor`. */
    private fun Entity<EventOverride>.toImportedOverride(uid: String) = ImportedEvent(
        uid = uid,
        title = title?.get().orEmpty(),
        startLocal = startLocal?.get().orEmpty(),
        endLocal = endLocal?.get().orEmpty(),
        timeZoneId = "",
        allDay = false,
        location = location?.get().orEmpty(),
        notes = notes?.get().orEmpty(),
        recurrenceRule = "",
        exceptionDates = "",
        recurrenceId = recurrenceId.get(),
        reminderMinutes = emptyList(),
        raw = "",
    )

    private fun overrideKey(event: ImportedEvent): List<String> = listOf(
        event.recurrenceId.orEmpty(),
        event.startLocal,
        event.endLocal,
        event.title,
        event.location,
        event.notes,
    )

    private fun mirrorSelection() = client.calendarService.mirrors.select {
        fields(
            CalendarMirror.Field.MIRROR_ID,
            CalendarMirror.Field.CALENDAR_ID,
            CalendarMirror.Field.DEVICE_ID,
            CalendarMirror.Field.SOURCE_KIND,
            CalendarMirror.Field.SOURCE_KEY,
            CalendarMirror.Field.SOURCE_LABEL,
            CalendarMirror.Field.REMINDERS_ENABLED,
        )
    }

    private fun stampSelection() = client.calendarService.mirrorStamps.select {
        fields(
            CalendarMirrorStamp.Field.MIRROR_ID,
            CalendarMirrorStamp.Field.DEVICE_ID,
            CalendarMirrorStamp.Field.LAST_REFRESHED_EPOCH_MS,
        )
    }

    private suspend fun mirrorRows(strict: Boolean = false): List<Entity<CalendarMirror>> =
        mirrorSelection().getAll().rows(strict, "mirrors")

    /**
     * ⚠ A mirror's freshness is its **bound** device's stamp — never another device's, which would be
     * a rebound-away phone's last word — and `0` ("not updated yet") without one.
     */
    private suspend fun List<Entity<CalendarMirror>>.toMirrorSummaries(
        stamps: List<Entity<CalendarMirrorStamp>>,
    ): List<CalendarMirrorSummary> {
        val stamped = stamps.associate {
            CalendarMirrorStamp.idFor(it.stampMirrorId.get(), it.stampDeviceId.get()) to it.stampedEpochMs.get()
        }
        // ⚠ Resolved once per read, and a still-unknown id reads as "not here" — never as "here":
        // a device that cannot tell must not offer to refresh someone else's mirror.
        val here = withTimeoutOrNull(DEVICE_ID_WAIT_MS) { deviceIdentity.awaitOrNull() }
        return map {
            CalendarMirrorSummary(
                mirrorId = it.mirrorId.get(),
                calendarId = it.mirrorCalendarId.get(),
                sourceKind = it.sourceKind.get(),
                sourceKey = it.sourceKey.get(),
                sourceLabel = it.sourceLabel.get(),
                deviceId = it.mirrorDeviceId.get(),
                boundHere = here != null && it.mirrorDeviceId.get() == here,
                remindersEnabled = it.remindersEnabled.get(),
                lastRefreshedEpochMs =
                    stamped[CalendarMirrorStamp.idFor(it.mirrorId.get(), it.mirrorDeviceId.get())] ?: 0L,
            )
        }.sortedBy { it.sourceLabel.lowercase() }
    }

    private data class Written(val added: Int, val overrides: Int, val tooLarge: Int)

    /**
     * Writes [series], each **with its own overrides** from [exceptions], through
     * `ApplyExternalEvents`, in calls bounded by bytes (`ExternalCopyBatches`).
     *
     * ⚠⚠ A series and its overrides travel in the same call: the op *sets* a batch event's
     * overrides, so an override sent in a later call than its series would be deleted by the
     * first and written again by the second — or, past the last call, simply lost. So a series is
     * sized *with* its overrides, and one too big for any call is reported in [Written.tooLarge]
     * rather than failing the rest.
     *
     * @param withReminders false for a mirror with Tenshu reminders off: the op *sets* reminders,
     *   so sending none removes any that were there (§5.7).
     */
    private suspend fun writeExternal(
        calendarId: String,
        series: List<ImportedEvent>,
        exceptions: List<ImportedEvent>,
        idOf: (ImportedEvent) -> String,
        withReminders: Boolean,
        origin: String,
    ): Written {
        val context = client.environment.tContext
        val overridesOf = exceptions.groupBy(idOf)
        val plan = ExternalCopyBatches.plan(series) { event ->
            overridesOf[idOf(event)].orEmpty().sumOf { ExternalCopyBatches.sizeOf(it, withSource = false) }
        }
        var added = 0
        var overrides = 0
        plan.batches.forEach { batch ->
            val chunk = batch.map { it.event }
            val chunkOverrides = chunk.flatMap { event -> overridesOf[idOf(event)].orEmpty() }
            val result = client.calendarService.applyExternalEvents(
                events = chunk.map { event ->
                    Event.create(
                        context = context,
                        eventId = idOf(event),
                        calendarId = calendarId,
                        title = event.title,
                        startLocal = event.startLocal,
                        endLocal = event.endLocal,
                        timeZoneId = event.timeZoneId,
                        allDay = event.allDay,
                        location = event.location,
                        notes = event.notes,
                        recurrenceRule = event.recurrenceRule,
                        exceptionDates = event.exceptionDates,
                    )
                },
                sources = batch.filter { it.withSource }.map { (event, _) ->
                    EventSource.create(context, idOf(event), event.raw, origin = origin)
                },
                reminders = if (!withReminders) emptyList() else chunk.flatMap { event ->
                    event.reminderMinutes.distinct().map { Reminder.create(context, idOf(event), it) }
                },
                overrides = chunkOverrides.map { event ->
                    EventOverride.create(
                        context = context,
                        eventId = idOf(event),
                        recurrenceId = event.recurrenceId.orEmpty(),
                        startLocal = event.startLocal,
                        endLocal = event.endLocal.takeIf { it.isNotBlank() },
                        title = event.title,
                        location = event.location,
                        notes = event.notes,
                    )
                },
            )
            result.orThrow("Copying events")
            added += ((result as? TResult.Success)?.value?.get() ?: 0L).toInt()
            overrides += chunkOverrides.size
        }
        return Written(added = added, overrides = overrides, tooLarge = plan.tooLarge.size)
    }

    /**
     * The name a first copy gets.
     *
     * ⚠ The source's own name unless another calendar already has it — the user's own "Work" and
     * their employer's are different calendars, and two rows named "Work" cannot be told apart in
     * any list. Then the account disambiguates, because that is what actually differs.
     */
    private fun copyNameFor(source: ExternalCalendar, calendars: List<Entity<Calendar>>): String {
        val taken = calendars.mapTo(mutableSetOf()) { it.name.get().trim().lowercase() }
        return listOf(
            source.name,
            "${source.name} (${source.accountName})".takeIf { source.accountName.isNotBlank() },
            "${source.name} (copy)",
        ).firstOrNull { it != null && it.trim().lowercase() !in taken } ?: "${source.name} (${taken.size})"
    }

    private fun calendarNameFor(calendars: List<Entity<Calendar>>, calendarId: String): String =
        if (calendarId.isBlank()) ""
        else calendars.firstOrNull { it.calendarId.get() == calendarId }?.name?.get().orEmpty()

    /**
     * The stored id for an imported block.
     *
     * ⚠⚠ The file's `UID` when it has one, which is what makes a re-import
     * idempotent rather than duplicating. ⚠ A block with no `UID` is rare but
     * real (hand-written files, some exporters), and a random id would duplicate
     * on every import — so it falls back to a content hash of the fields that
     * identify the event.
     */
    private fun idFor(event: ImportedEvent): String =
        event.uid.trim().ifBlank {
            "ics-" + (event.title + event.startLocal + event.recurrenceRule)
                .hashCode().toUInt().toString(16)
        }

    private suspend fun sourceRows(strict: Boolean = false): List<Entity<EventSource>> =
        client.calendarService.eventSources
            .select { fields(EventSource.Field.EVENT_ID, EventSource.Field.RAW, EventSource.Field.ORIGIN) }
            .getAll()
            .rows(strict, "sources")

    // ── reminders ────────────────────────────────────────────────────────────

    override suspend fun remindersFor(eventId: String): List<Int> =
        reminderRows()
            .filter { it.eventId.get() == eventId }
            .map { it.minutesBefore.get().toInt() }
            .sorted()

    override suspend fun setReminders(eventId: String, minutesBefore: List<Int>) {
        val wanted = minutesBefore.distinct()
        val existing = remindersFor(eventId)

        // ⚠ Diffed rather than cleared-and-rewritten. Deleting a reminder
        // cascades to its `ReminderState` rows, so re-writing an unchanged one
        // would silently discard a dismissal the user had already made — the
        // content-derived key means the row would come back identical and
        // un-dismissed.
        (existing - wanted.toSet()).forEach {
            client.calendarService.deleteReminder(Reminder.idFor(eventId, it))
        }
        (wanted - existing.toSet()).forEach {
            client.calendarService.saveReminder(eventId, it)
        }
    }

    override suspend fun defaultReminderMinutes(allDay: Boolean): Int? {
        val pref =
            if (allDay) CalendarPreferences.DefaultAllDayReminderMinutes
            else CalendarPreferences.DefaultReminderMinutes
        val value = readRealmLong(pref.id) ?: pref.default

        // ⚠ The sentinel is asymmetric on purpose. For a timed event any
        // negative offset is meaningless ("10 minutes after it started" is not a
        // warning), so anything negative reads as off — see the preference's
        // KDoc on why 0 could not be the sentinel. For an all-day event negative
        // *is* the normal case (minutes after midnight), so only the explicit
        // -1 is off. ⚠ That does cost the all-day value 00:01, which is not a
        // choice any picker offers and is the cheapest thing to spend.
        return when {
            value == OFF_SENTINEL -> null
            !allDay && value < 0 -> null
            else -> value.toInt()
        }
    }

    override suspend fun dismissReminder(reminderId: String, recurrenceId: String) {
        client.calendarService.setReminderState(reminderId, recurrenceId, dismissed = true)
    }

    override suspend fun snoozeReminder(
        reminderId: String,
        recurrenceId: String,
        untilEpochMs: Long,
    ) {
        client.calendarService.setReminderState(
            reminderId = reminderId,
            recurrenceId = recurrenceId,
            snoozedUntilEpochMs = untilEpochMs,
        )
    }

    private suspend fun reminderRows(strict: Boolean = false): List<Entity<Reminder>> =
        client.calendarService.reminders
            .select { fields(Reminder.Field.REMINDER_ID, Reminder.Field.EVENT_ID, Reminder.Field.MINUTES_BEFORE) }
            .getAll()
            .rows(strict, "reminders")

    /**
     * The event's current exception list, so a series edit preserves it.
     *
     * ⚠ Returns "" for an event that does not exist yet, which is correct for a
     * create and harmless for an edit of something concurrently deleted.
     */
    private suspend fun existingExceptionDates(eventId: String): String =
        eventRows().firstOrNull { it.eventId.get() == eventId }?.exceptionDates?.get().orEmpty()

    /**
     * A new event id.
     *
     * ⚠ Content-derived rather than random, because this module has no random
     * source that is safe on every target and an event id only has to be unique
     * within the realm. Collisions across devices are resolved by
     * last-writer-wins the same way any concurrent edit is — and two devices
     * creating the *same* event at the same instant with the same title is a
     * duplicate the user would want merged anyway.
     */
    private fun newEventId(draft: EventDraft): String =
        "evt-" + (draft.title + draft.startLocal + draft.calendarId).hashCode().toUInt().toString(16) +
            "-" + draft.startLocal.filter { it.isDigit() }

    // ── reads ────────────────────────────────────────────────────────────────

    /**
     * This device's hidden-calendar set, as a **snapshot**.
     *
     * ⚠⚠ A snapshot is right for a read-modify-write ([setCalendarVisible]) and for
     * the windowed reads, and **wrong for anything a screen renders** — taken after a
     * write it answers with the pre-write value. Screens collect
     * [observeHiddenCalendars].
     *
     * Absent, undecodable or an empty relation all resolve to the declared default
     * (`readOnceForDevice`), which for `HiddenCalendars` means *nothing hidden*.
     */
    private suspend fun hiddenCalendarIds(): Set<String> {
        // No device id obtainable ⇒ the same answer as an absent row: the declared
        // default. Showing every calendar is the safe failure — hiding one the user cannot
        // then unhide would not be.
        val deviceId = deviceIdentity.awaitOrNull()
            ?: return CalendarPreferences.HiddenCalendars.default.toSet()
        return client.preferenceService
            .readOnceForDevice(CalendarPreferences.HiddenCalendars, deviceId)
            .toSet()
    }

    // ── row selections ───────────────────────────────────────────────────────
    //
    // ⚠⚠ Each selection is a **function**, so every terminal gets a freshly built
    // chain. Chains mutate as they are lowered, so holding one in a `val` and hanging
    // both `getAll()` and `flow()` off it is not the saving it looks like.
    //
    // ⚠ The projections live here exactly once. A live read that selected a field the
    // one-shot read did not would differ from it only under recurrence — which is the
    // kind of difference nobody finds from either call site.

    private fun calendarSelection() = client.calendarService.calendars.select {
        fields(
            Calendar.Field.CALENDAR_ID,
            Calendar.Field.NAME,
            Calendar.Field.COLOR_ARGB,
            Calendar.Field.SENSITIVE,
        )
    }

    private fun eventSelection() = client.calendarService.events.select {
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

    private fun overrideSelection() = client.calendarService.eventOverrides.select {
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

    private suspend fun calendarRows(): List<Entity<Calendar>> = calendarSelection().getAll().rows()

    private suspend fun eventRows(strict: Boolean = false): List<Entity<Event>> =
        eventSelection().getAll().rows(strict, "events")

    private suspend fun overrideRows(strict: Boolean = false): List<Entity<EventOverride>> =
        overrideSelection().getAll().rows(strict, "overrides")

    private fun calendarRowsFlow(): Flow<List<Entity<Calendar>>> =
        calendarSelection().flow().map { it.rows() }

    private fun eventRowsFlow(): Flow<List<Entity<Event>>> =
        eventSelection().flow().map { it.rows() }

    private fun overrideRowsFlow(): Flow<List<Entity<EventOverride>>> =
        overrideSelection().flow().map { it.rows() }

    private companion object {
        /**
         * ⚠ A fixed id, not a random one: two devices that both run a fresh
         * first launch before syncing would otherwise mint two "Personal"
         * calendars that never converge. A constant id makes the seed
         * idempotent *across devices* as well as across launches — the rows
         * collide on the same key and last-writer-wins resolves them into one.
         */
        const val FIRST_CALENDAR_ID = "personal"
        const val FIRST_CALENDAR_NAME = "Personal"
        /**
         * ⚠⚠ The palette's own blue, alpha byte included. Rows seeded with `0x4285F4` carry alpha
         * **0**; Compose forces the alpha opaque and iOS ignores the byte
         * (`CalendarDayDot.alpha`), so both draw them. This keeps new rows honest ARGB, as
         * `CalendarPalette` says.
         */
        val FIRST_CALENDAR_COLOR: Int = app.tenshu.calendar.model.CalendarPalette.DEFAULT

        /** ⚠ -1, not 0 — zero is the real offset "when it starts". */
        const val OFF_SENTINEL = -1L

        /** ⚠ Matches the amber in `CalendarPalette`, so a feed is visibly not the user's. */
        const val CALENDAR_SUBSCRIPTION_COLOR = 0xF4B400.toInt()

        const val DEFAULT_SUBSCRIPTION_NAME = "Subscribed calendar"


        const val OPAQUE = 0xFF000000.toInt()

        /**
         * How long a mirror read waits for this device's id. ⚠ Bounded, because the wait polls
         * until the id is known, and a list that never emitted would read as "no mirrors".
         */
        const val DEVICE_ID_WAIT_MS = 3_000L

        /**
         * ⚠⚠ The **same rule the proxy enforces**, restated here so the refusal is
         * a message at the moment of typing rather than a subscription that
         * silently never updates. Duplicated on purpose: `:calendar-shared-logic`
         * must not depend on `:calendar-service-proxy`, and a shared constant in
         * `-common` for eleven lines of string matching would be worse than the
         * duplication. ⚠ If one side changes, the symptom is a URL accepted here
         * and refused there — visible, not silent.
         */
        fun normalizeSubscriptionUrl(raw: String): String? {
            val text = raw.trim()
            return when {
                text.isEmpty() -> null
                text.startsWith("webcal://", ignoreCase = true) -> "https://" + text.substring(9)
                text.startsWith("webcals://", ignoreCase = true) -> "https://" + text.substring(10)
                text.startsWith("https://", ignoreCase = true) -> text
                else -> null
            }
        }
    }
}

/**
 * The rows of a chain result, or none.
 *
 * ⚠ A failure reads as **empty**, matching every read path in this file: a screen that
 * cannot load its window shows nothing, and the one thing it must not do is throw out
 * of a `Flow` a view model is collecting for the life of the screen.
 */
private fun <T : Data> TResult<TList<T>>.rows(): List<T> =
    (this as? TResult.Success)?.value?.toList().orEmpty()

/**
 * ⚠⚠ [strict] for a path that **writes from what it read**: a mirror refresh that took an unreadable
 * calendar for an empty one would rewrite what the source holds, sweep nothing, cache the digest,
 * and keep a deleted event forever. Strict throws, so the refresh fails and is retried instead.
 */
private fun <T : Data> TResult<TList<T>>.rows(strict: Boolean, what: String): List<T> =
    if (!strict) rows()
    else (this as? TResult.Success)?.value?.toList() ?: error("Reading the $what failed")
