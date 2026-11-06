package app.tenshu.calendar.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.calendar.data.CalendarRepository
import app.tenshu.calendar.data.CalendarSummary
import app.tenshu.calendar.data.EditScope
import app.tenshu.calendar.data.EventDraft
import app.tenshu.calendar.model.RecurrencePreset
import app.tenshu.calendar.model.toRrule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.number
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence
import org.tenshu.core.common.util.runCatchingCancellable

/**
 * A save that would have overwritten somebody else's change (CA-2k).
 *
 * ⚠⚠ Detected **at the write, not while typing**. The form is a snapshot on purpose
 * (CA-2i): a live form would replace what the user is typing, which is the worse
 * failure. So the check happens where it can still change the outcome — the moment
 * before the write — which is also the moment the user is thinking about this event
 * rather than being interrupted mid-sentence.
 */
/** Which action the conflict interrupted — the two offer different ways out. */
enum class ConflictIntent { SAVE, DELETE }

data class EditorConflict(
    /**
     * How the event reads now.
     *
     * ⚠ `null` means it is **no longer where it was opened** — deleted, or moved to
     * another day by the other device. The two are not distinguished, deliberately:
     * this layer cannot tell them apart without an event-by-id read it does not have,
     * and claiming "deleted" about a moved event would be a confident lie. The copy
     * says what is known.
     */
    val theirs: ResolvedOccurrence?,
    /** The scope the user was acting with, resumed verbatim if they go ahead. */
    val scope: EditScope,
    val intent: ConflictIntent = ConflictIntent.SAVE,
)

data class EventEditorUiState(
    val calendars: List<CalendarSummary> = emptyList(),
    val calendarId: String = "",
    val title: String = "",
    val startDate: LocalDate? = null,
    val startTime: LocalTime = LocalTime(9, 0),
    val endTime: LocalTime = LocalTime(10, 0),
    val allDay: Boolean = false,
    val location: String = "",
    val notes: String = "",
    val recurrence: RecurrencePreset = RecurrencePreset.NEVER,
    /**
     * The offsets this event alerts at, ascending (CA-5e).
     *
     * ⚠ Held in the form rather than written as the user taps, because a *new*
     * event has no id yet — a reminder cannot be filed against an event that
     * does not exist. They are written once, after the save returns an id.
     */
    val reminderMinutes: List<Int> = emptyList(),
    /** Set when editing an existing event; blank when creating. */
    val eventId: String = "",
    /** Set when editing one occurrence of a series. */
    val recurrenceId: String = "",
    /** True once the editor is showing the this-vs-all prompt. */
    /**
     * Set when the event changed elsewhere between opening this form and saving it
     * (CA-2k). Non-null means **nothing has been written** — the user has to choose.
     */
    val conflict: EditorConflict? = null,
    val askingScope: Boolean = false,
    /** Same, for a delete. */
    val askingDeleteScope: Boolean = false,
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val deleted: Boolean = false,
    val errorMessage: String? = null,
) {
    val isEditing: Boolean get() = eventId.isNotBlank()

    /** Whether this edit could apply to more than one occurrence. */
    val isRecurring: Boolean get() = recurrence != RecurrencePreset.NEVER

    /**
     * ⚠ A rule the editor can show but not offer. Saving would rewrite it into
     * one of our presets, so the recurrence control must be read-only here —
     * §3 D2's "refuse to edit rather than silently rewrite".
     */
    val recurrenceIsReadOnly: Boolean get() = recurrence == RecurrencePreset.CUSTOM

    /**
     * The calendars a user may file into — [calendars] minus the read-only ones
     * ([CalendarSummary.readOnly]). ⚠ The picker shows this, never [calendars]: an event added to a
     * subscribed or mirrored calendar is deleted by its next refresh.
     */
    val writableCalendars: List<CalendarSummary> get() = calendars.filterNot { it.readOnly }

    /**
     * ⚠⚠ The event lives in a read-only calendar: shown, never saved or deleted. Its next refresh
     * would overwrite an edit and resurrect a deletion, so offering either is a promise the app
     * cannot keep.
     */
    val isReadOnly: Boolean get() = calendars.any { it.calendarId == calendarId && it.readOnly }

    val canSave: Boolean
        get() = !isSaving && !isReadOnly && title.isNotBlank() && startDate != null &&
            calendarId.isNotBlank() && (allDay || endTime >= startTime)

    /** ⚠ Surfaced so the form can explain *why* save is disabled, not just grey it out. */
    val endBeforeStart: Boolean get() = !allDay && endTime < startTime
}

/**
 * The event editor (CA-3).
 *
 * ⚠⚠ **The this-vs-all prompt is this class's real job.** Everything else is
 * form state that Material 3 mostly supplies. Both answers are destructive in
 * opposite directions — a series edit moves every occurrence, a single-occurrence
 * edit leaves the change un-applied everywhere else — so the editor asks, and
 * asks *only* when the question is real (a recurring event being edited, not a
 * one-off and not a create).
 */
class EventEditorViewModel(
    private val repository: CalendarRepository,
    /** The date a create starts on; ignored when editing. */
    initialDate: LocalDate? = null,
    /**
     * The time a create starts at; ignored when editing.
     *
     * ⚠ Set when the user tapped a spot in the time grid (CA-4). The gesture
     * already said *when*, and re-defaulting to 09:00 would throw that away and
     * make the tap indistinguishable from pressing the create button.
     */
    initialTime: LocalTime? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        EventEditorUiState(startDate = initialDate).let { state ->
            initialTime?.let { time ->
                state.copy(
                    startTime = time,
                    // ⚠ Keeps the form's default duration rather than the
                    // default *times*: a tap at 14:00 means 14:00–15:00.
                    endTime = LocalTime.fromMinutesOfDay(
                        (time.minutesOfDay() + DEFAULT_DURATION_MINUTES)
                            .coerceAtMost(MAX_MINUTE_OF_DAY)
                    ),
                )
            } ?: state
        }
    )
    val uiState: StateFlow<EventEditorUiState> = _uiState

    /**
     * True once the reminder list is the user's own answer rather than a default.
     *
     * ⚠⚠ Not in the UI state, because it is not something a screen renders — and
     * making it visible would invite a screen to *set* it. It exists so a default
     * can be replaced silently while an explicit choice never is: without it,
     * ticking all-day would either strand a 23:50 alert on a midnight event or
     * throw away reminders the user had just picked, and there is no third
     * behaviour that is right for both.
     */
    private var reminderChoiceIsUsers = false

    /** Set once the calendar is the user's (picked, or loaded with the event): no auto-pick after. */
    private var calendarChoiceIsUsers = false

    init {
        // ⚠⚠ **The chooser's options are live; nothing else on this screen is** (CA-2i).
        // A calendar created or renamed in another window should appear in the picker
        // — that is a list, and a stale list is just wrong. The *form* is not a list:
        // re-reading `title`, the times or the reminders from the store while someone
        // is typing into them would overwrite what they typed, and "the app ate my
        // edit" is a far worse failure than "the app showed me a stale field". The
        // form is seeded once, from the occurrence the caller handed over, and only
        // the user moves it after that.
        viewModelScope.launch {
            // ⚠⚠ The configured default first — the calendar the Calendars screen labels
            // "Default for new events". Preselecting `firstOrNull()` alone filed every new
            // event into whichever calendar sorts first, under a screen that promised
            // otherwise. Live, like the list: a default that arrives (or a calendar that
            // appears) after the form opened still wins, until the user picks one. A blank
            // default, or one naming no calendar, falls back to the first calendar.
            combine(repository.observeCalendars(), repository.observeDefaultCalendar()) { calendars, preferred ->
                calendars to preferred
            }.collect { (calendars, preferred) ->
                _uiState.update { state ->
                    state.copy(
                        calendars = calendars,
                        // ⚠ Preselect only while nobody has chosen: an edit already knows
                        // its calendar (`load` claims the choice), and overwriting it with
                        // "the first one" would silently re-file the event.
                        calendarId = if (calendarChoiceIsUsers && state.calendarId.isNotBlank()) {
                            state.calendarId
                        } else {
                            // ⚠ Never preselect a read-only calendar, even as the default.
                            val writable = calendars.filterNot { it.readOnly }
                            (writable.firstOrNull { it.calendarId == preferred } ?: writable.firstOrNull())
                                ?.calendarId.orEmpty()
                        },
                    )
                }
            }
        }
        // ⚠ Deliberately a one-shot seed, not a subscription: `applyDefaultReminder`
        // exists to fill a *default*, and `reminderChoiceIsUsers` is what stops it
        // replacing an answer the user has given. A live default would have to
        // re-decide that on every emission, for a preference that changes about
        // once a year.
        viewModelScope.launch { applyDefaultReminder() }
    }

    /**
     * Pre-selects the configured default for a **create**.
     *
     * ⚠ One guard, checked twice. `reminderChoiceIsUsers` covers every case there
     * is — the edit (`load` claims it before its own read even starts) and the
     * fast tapper who chose while this was still in flight. An `isEditing` check
     * would read as a second safeguard and be unreachable, which is worse than
     * none: the next person would trust it.
     *
     * ⚠ The re-check *inside* the update is the race. The read is a round trip to
     * the proxy, and the flag can be claimed — or the question changed — while it
     * is out.
     */
    private suspend fun applyDefaultReminder() {
        if (reminderChoiceIsUsers) return
        val allDay = _uiState.value.allDay
        val default = runCatchingCancellable { repository.defaultReminderMinutes(allDay) }.getOrNull()
        _uiState.update { state ->
            if (reminderChoiceIsUsers || state.allDay != allDay) state
            else state.copy(reminderMinutes = listOfNotNull(default))
        }
    }

    /**
     * Loads an existing occurrence into the form.
     *
     * ⚠ Everything it needs is on the occurrence, including the series' raw
     * `RRULE` — the editor never re-reads the row it was handed, so the form
     * cannot disagree with the list that opened it.
     */
    /**
     * What this form was seeded from — the baseline every conflict check compares
     * against. Null for a create, which cannot conflict with anything.
     */
    private var loadedFrom: ResolvedOccurrence? = null

    fun load(occurrence: ResolvedOccurrence) {
        loadedFrom = occurrence
        // ⚠ Claimed before the read returns, not after: the stored list *is* the
        // user's answer, so from this moment no default may replace it — including
        // one already in flight from `init`, which raced this call.
        calendarChoiceIsUsers = true
        reminderChoiceIsUsers = true
        viewModelScope.launch {
            val reminders = runCatchingCancellable { repository.remindersFor(occurrence.eventId) }
                .getOrDefault(emptyList())
            _uiState.update { it.copy(reminderMinutes = reminders) }
        }
        _uiState.update { state ->
            state.copy(
                eventId = occurrence.eventId,
                recurrenceId = occurrence.recurrenceId,
                calendarId = occurrence.calendarId,
                title = occurrence.title,
                startDate = occurrence.startLocal.date,
                startTime = occurrence.startLocal.time,
                endTime = occurrence.endLocal.time,
                allDay = occurrence.allDay,
                location = occurrence.location,
                notes = occurrence.notes,
                // ⚠⚠ Against the **series'** anchor, not this occurrence's date — see
                // `RecurrencePreset.of`, which the read-only detail screens share.
                recurrence = RecurrencePreset.of(occurrence),
            )
        }
    }

    fun setTitle(value: String) = _uiState.update { it.copy(title = value) }
    fun setLocation(value: String) = _uiState.update { it.copy(location = value) }
    fun setNotes(value: String) = _uiState.update { it.copy(notes = value) }
    fun setCalendar(id: String) {
        val state = _uiState.value
        // ⚠ Neither into a read-only calendar nor out of one: moving an event out of a mirror is
        // undone by the mirror's next refresh just as surely as an edit is.
        if (state.isReadOnly || state.calendars.any { it.calendarId == id && it.readOnly }) return
        calendarChoiceIsUsers = true
        _uiState.update { it.copy(calendarId = id) }
    }
    fun setStartDate(date: LocalDate) = _uiState.update { it.copy(startDate = date) }
    /**
     * ⚠⚠ Re-reads the default, because the *question* changed. A timed offset on
     * an all-day event fires at 23:50 the night before — the bug every stock
     * calendar avoids by switching to a time of day the moment you tick this. Only
     * when the user has not chosen for themselves; see [reminderChoiceIsUsers].
     */
    fun setAllDay(value: Boolean) {
        val changed = _uiState.value.allDay != value
        _uiState.update { it.copy(allDay = value) }
        if (changed) viewModelScope.launch { applyDefaultReminder() }
    }

    /**
     * ⭐ Moving the start drags the end with it, preserving the duration — the
     * behaviour every calendar has, and the absence of which makes an editor feel
     * broken (you set 14:00 and the event becomes negative-length).
     */
    fun setStartTime(time: LocalTime) = _uiState.update { state ->
        val durationMinutes = state.endTime.minutesOfDay() - state.startTime.minutesOfDay()
        val end = LocalTime.fromMinutesOfDay(
            (time.minutesOfDay() + durationMinutes.coerceAtLeast(0)).coerceAtMost(MAX_MINUTE_OF_DAY)
        )
        state.copy(startTime = time, endTime = end)
    }

    fun setEndTime(time: LocalTime) = _uiState.update { it.copy(endTime = time) }

    // ── the same three, from a platform picker's integers (CA-8d) ────────────
    //
    // ⭐ The `showMonth` / `selectDay` precedent, for the same structural reason: a native picker
    // reports date *components*, not a date object. SwiftUI's `DatePicker` binds a `Date`, which
    // Foundation turns into components — so the shell would otherwise have to name a
    // kotlinx-datetime constructor, and nothing in this repo's environment can check that it named
    // it correctly.
    //
    // ⚠ Each one delegates rather than duplicating: `setStartFromParts` in particular must keep
    // the drag-the-end-along behaviour, and a second copy of that arithmetic is a second place for
    // it to be wrong.
    //
    // ⚠ Nonsense components are ignored rather than thrown on — they can only arrive from a
    // platform callback, and leaving the field as the user last set it beats crashing an editor
    // with unsaved text in it.

    fun setStartDateFromParts(year: Int, month: Int, day: Int) {
        val date = runCatching { LocalDate(year, month, day) }.getOrNull() ?: return
        setStartDate(date)
    }

    fun setStartTimeFromParts(hour: Int, minute: Int) {
        val time = runCatching { LocalTime(hour, minute) }.getOrNull() ?: return
        setStartTime(time)
    }

    fun setEndTimeFromParts(hour: Int, minute: Int) {
        val time = runCatching { LocalTime(hour, minute) }.getOrNull() ?: return
        setEndTime(time)
    }

    /**
     * Adds or removes one reminder offset.
     *
     * ⚠ A toggle rather than add/remove, because the UI is a row of choices and
     * the same tap means both — and because it makes "already chosen" the
     * check that prevents a duplicate, rather than a `distinct()` somewhere
     * downstream that nobody can see from the call site.
     */
    fun toggleReminder(minutesBefore: Int) {
        reminderChoiceIsUsers = true
        _uiState.update { state ->
            val next =
                if (minutesBefore in state.reminderMinutes) state.reminderMinutes - minutesBefore
                else state.reminderMinutes + minutesBefore
            state.copy(reminderMinutes = next.sorted())
        }
    }

    /** ⚠ Ignored for a `CUSTOM` rule — see [EventEditorUiState.recurrenceIsReadOnly]. */
    fun setRecurrence(preset: RecurrencePreset) = _uiState.update { state ->
        if (state.recurrenceIsReadOnly) state else state.copy(recurrence = preset)
    }

    // ── saving ───────────────────────────────────────────────────────────────

    /**
     * Attempts a save, raising the scope prompt when the question is real.
     *
     * ⚠ The prompt is skipped for a create and for a one-off: a dialog with one
     * meaningful answer trains people to dismiss dialogs without reading them.
     */
    fun save() {
        val state = _uiState.value
        if (!state.canSave) return
        if (state.isEditing && state.isRecurring) {
            _uiState.update { it.copy(askingScope = true) }
        } else {
            commit(EditScope.ALL_OCCURRENCES)
        }
    }

    fun dismissScopePrompt() =
        _uiState.update { it.copy(askingScope = false, askingDeleteScope = false) }

    fun commit(scope: EditScope) {
        if (_uiState.value.isReadOnly) return   // ⚠ public, and the iOS shell calls it directly
        val baseline = loadedFrom
        if (baseline == null) {
            write(scope)
            return
        }
        // ⚠ The check is a read, so it suspends — and `isSaving` goes up first, or the
        // save button stays live and a second tap starts a second write.
        _uiState.update { it.copy(isSaving = true, askingScope = false) }
        viewModelScope.launch {
            when (val check = checkForConflict(baseline)) {
                is ConflictCheck.None -> write(scope)
                is ConflictCheck.Changed ->
                    _uiState.update {
                        it.copy(isSaving = false, conflict = EditorConflict(check.theirs, scope))
                    }
                is ConflictCheck.Gone ->
                    _uiState.update {
                        it.copy(isSaving = false, conflict = EditorConflict(null, scope))
                    }
            }
        }
    }

    /**
     * Go ahead with what the conflict interrupted.
     *
     * ⚠⚠ No re-check. That is what "keep mine" and "delete anyway" mean — a second
     * prompt for a change that arrived in the last half second would be a loop the user
     * cannot get out of.
     */
    fun keepMine() {
        val conflict = _uiState.value.conflict ?: return
        _uiState.update { it.copy(conflict = null) }
        when (conflict.intent) {
            ConflictIntent.SAVE -> write(conflict.scope)
            ConflictIntent.DELETE -> deleteNow(conflict.scope)
        }
    }

    /**
     * Take the other device's version and drop this form's edits.
     *
     * ⚠ Re-seeds through [load], so `loadedFrom` moves too — otherwise the very next
     * save would conflict against a baseline the user has already abandoned.
     *
     * ⚠ For an interrupted **delete** this is the way out that cancels it: the event
     * stays, and the form shows what the other device made of it. Which is the point of
     * asking — you cannot decide whether you still want it gone without seeing it.
     */
    fun useTheirs() {
        val theirs = _uiState.value.conflict?.theirs ?: return
        _uiState.update { it.copy(conflict = null) }
        load(theirs)
    }

    /**
     * Keep both: leave the other device's version alone and save this form as a **new**
     * event.
     *
     * ⚠⚠ Blanking the id is what makes it a create rather than an overwrite — and the
     * scope is forced to `ALL_OCCURRENCES`, because a new event has no series to except
     * a single occurrence from.
     *
     * ⚠ `loadedFrom` is cleared: from here this form is a create, and a create cannot
     * conflict with anything.
     */
    fun saveBoth() {
        if (_uiState.value.conflict == null || _uiState.value.isReadOnly) return
        loadedFrom = null
        _uiState.update { it.copy(conflict = null, eventId = "", recurrenceId = "") }
        write(EditScope.ALL_OCCURRENCES)
    }

    fun dismissConflict() = _uiState.update { it.copy(conflict = null, isSaving = false) }

    private sealed interface ConflictCheck {
        data object None : ConflictCheck
        data class Changed(val theirs: ResolvedOccurrence) : ConflictCheck
        data object Gone : ConflictCheck
    }

    /**
     * ⚠ `includeHidden = true`: whether the other device hides that calendar has
     * nothing to do with whether the event changed, and a hidden calendar would
     * otherwise read as "gone" and offer to recreate an event that exists.
     *
     * ⚠⚠ A failed read is **not** a conflict. If the proxy is unreachable the save will
     * fail on its own and say so; refusing to write on a read failure would turn every
     * outage into a conflict prompt about a change nobody made.
     */
    private suspend fun checkForConflict(baseline: ResolvedOccurrence): ConflictCheck {
        val current = runCatchingCancellable {
            repository
                .occurrencesOn(baseline.startLocal.date, includeHidden = true)
                .firstOrNull {
                    it.eventId == baseline.eventId && it.recurrenceId == baseline.recurrenceId
                }
        }.getOrElse { return ConflictCheck.None }

        return when {
            current == null -> ConflictCheck.Gone
            current.differsFrom(baseline) -> ConflictCheck.Changed(current)
            else -> ConflictCheck.None
        }
    }

    /**
     * ⚠ Compares only what this editor can change. `recurrenceId` is identity, not
     * content, and the reminder rows are their own write — including them would raise a
     * conflict for a change this form cannot even show.
     */
    private fun ResolvedOccurrence.differsFrom(other: ResolvedOccurrence): Boolean =
        title != other.title ||
            location != other.location ||
            notes != other.notes ||
            startLocal != other.startLocal ||
            endLocal != other.endLocal ||
            allDay != other.allDay ||
            recurrenceRule != other.recurrenceRule ||
            calendarId != other.calendarId

    private fun write(scope: EditScope) {
        val state = _uiState.value
        val draft = state.toDraft() ?: return
        _uiState.update { it.copy(isSaving = true, askingScope = false) }
        viewModelScope.launch {
            runCatchingCancellable {
                when (scope) {
                    EditScope.ALL_OCCURRENCES -> {
                        // ⚠⚠ Reminders are written *after* the event, using the
                        // id the save returns: a create has no id until then,
                        // and a reminder filed against a blank one would be
                        // orphaned by construction.
                        val id = repository.saveEvent(draft)
                        repository.setReminders(id, state.reminderMinutes)
                    }
                    // ⚠ A single-occurrence edit does **not** touch reminders.
                    // A reminder belongs to the series (see `Reminder`), so
                    // writing them here would silently change every occurrence
                    // from a screen that promised to change one.
                    EditScope.THIS_OCCURRENCE -> repository.saveOccurrence(draft, state.recurrenceId)
                }
            }.fold(
                onSuccess = { _uiState.update { it.copy(isSaving = false, saved = true) } },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isSaving = false, errorMessage = error.message ?: "Could not save")
                    }
                },
            )
        }
    }

    // ── deleting ─────────────────────────────────────────────────────────────

    fun delete() {
        val state = _uiState.value
        if (!state.isEditing || state.isReadOnly) return
        if (state.isRecurring) {
            _uiState.update { it.copy(askingDeleteScope = true) }
        } else {
            commitDelete(EditScope.ALL_OCCURRENCES)
        }
    }

    /**
     * ⚠⚠ **Already deleted elsewhere is a success, not a conflict.** If the row is gone
     * the user's intent is already the world's state; prompting about it would be
     * asking permission for something nobody can undo and nobody disagrees with. It is
     * also why this reports `deleted` without calling the op: whether a delete of a
     * missing row throws is the op's business, and the honest outcome does not depend
     * on the answer.
     */
    fun commitDelete(scope: EditScope) {
        if (_uiState.value.isReadOnly) return
        val baseline = loadedFrom
        if (baseline == null) {
            deleteNow(scope)
            return
        }
        _uiState.update { it.copy(isSaving = true, askingDeleteScope = false) }
        viewModelScope.launch {
            when (val check = checkForConflict(baseline)) {
                is ConflictCheck.None -> deleteNow(scope)
                is ConflictCheck.Gone ->
                    _uiState.update { it.copy(isSaving = false, deleted = true) }
                is ConflictCheck.Changed ->
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            conflict = EditorConflict(
                                theirs = check.theirs,
                                scope = scope,
                                intent = ConflictIntent.DELETE,
                            ),
                        )
                    }
            }
        }
    }

    private fun deleteNow(scope: EditScope) {
        val state = _uiState.value
        _uiState.update { it.copy(isSaving = true, askingDeleteScope = false) }
        viewModelScope.launch {
            runCatchingCancellable {
                when (scope) {
                    EditScope.ALL_OCCURRENCES -> repository.deleteEvent(state.eventId)
                    EditScope.THIS_OCCURRENCE ->
                        repository.deleteOccurrence(state.eventId, state.recurrenceId)
                }
            }.fold(
                onSuccess = { _uiState.update { it.copy(isSaving = false, deleted = true) } },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isSaving = false, errorMessage = error.message ?: "Could not delete")
                    }
                },
            )
        }
    }

    private fun EventEditorUiState.toDraft(): EventDraft? {
        val date = startDate ?: return null
        // ⚠ An all-day event is a DATE RANGE with an exclusive end, so it ends at
        // 00:00 the next day — not at 23:59, and not at the picked time, which is
        // meaningless when the user has said "all day".
        val start = if (allDay) LocalDateTime(date, LocalTime(0, 0)) else LocalDateTime(date, startTime)
        val end = if (allDay) {
            LocalDateTime(date.plusDays(1), LocalTime(0, 0))
        } else {
            LocalDateTime(date, endTime)
        }
        return EventDraft(
            eventId = eventId,
            calendarId = calendarId,
            title = title.trim(),
            startLocal = start.isoMinutes(),
            endLocal = end.isoMinutes(),
            allDay = allDay,
            location = location.trim(),
            notes = notes.trim(),
            // ⚠ `?: ""` never fires for a savable state: CUSTOM is read-only, so
            // a form that reaches here holds a preset that maps to a rule.
            recurrenceRule = recurrence.toRrule(date) ?: "",
        )
    }

    private companion object {
        const val MAX_MINUTE_OF_DAY = 23 * 60 + 59

        /** ⚠ Matches the form's own 09:00–10:00 default, so a tap behaves like the button. */
        const val DEFAULT_DURATION_MINUTES = 60
    }
}

private fun LocalTime.minutesOfDay(): Int = hour * 60 + minute

private fun LocalTime.Companion.fromMinutesOfDay(value: Int): LocalTime =
    LocalTime(value / 60, value % 60)

private fun LocalDate.plusDays(days: Int): LocalDate =
    LocalDate.fromEpochDays(toEpochDays() + days)

private fun LocalDateTime.isoMinutes(): String {
    val y = year.toString().padStart(4, '0')
    val mo = month.number.toString().padStart(2, '0')
    val d = day.toString().padStart(2, '0')
    val h = hour.toString().padStart(2, '0')
    val mi = minute.toString().padStart(2, '0')
    return "$y-$mo-${d}T$h:$mi"
}
