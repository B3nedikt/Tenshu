package app.tenshu.calendar.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.calendar.data.CalendarMirrorRepository
import app.tenshu.calendar.data.CalendarMirrorSummary
import app.tenshu.calendar.data.CalendarRepository
import app.tenshu.calendar.data.CalendarSubscriptionSummary
import app.tenshu.calendar.data.CalendarSummary
import app.tenshu.calendar.data.external.MirrorRefresher
import app.tenshu.calendar.model.CalendarPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.tenshu.core.common.util.bestEffort
import org.tenshu.core.common.util.runCatchingCancellable

/** The calendar being edited, or created when [calendarId] is blank. */
data class CalendarDraft(
    val calendarId: String = "",
    val name: String = "",
    val colorArgb: Int = CalendarPalette.DEFAULT,
    val sensitive: Boolean = false,
) {
    val isCreating: Boolean get() = calendarId.isBlank()
}

data class CalendarManagementUiState(
    val calendars: List<CalendarSummary> = emptyList(),
    /** Keyed by the calendar each feed fills, so a row can look itself up. */
    val subscriptionsByCalendar: Map<String, CalendarSubscriptionSummary> = emptyMap(),
    /** Keyed by the calendar each mirror fills (CX-1b). */
    val mirrorsByCalendar: Map<String, CalendarMirrorSummary> = emptyMap(),
    /** Non-null while the subscribe dialog is open, holding what has been typed. */
    val subscribeDraft: SubscribeDraft? = null,
    val defaultCalendarId: String = "",
    /** Non-null while the add/edit sheet is open. */
    val draft: CalendarDraft? = null,
    /** Set when the draft cannot be saved, and why. */
    val draftError: String? = null,
    /** Set while a delete is awaiting confirmation. */
    val confirmingDelete: CalendarSummary? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
) {
    /**
     * ⚠⚠ The last calendar cannot be deleted, and the screen must *say* so
     * rather than let the delete fail. Deleting it would cascade away every event
     * the user has and leave `ensureDefaultCalendar` to silently re-seed an empty
     * "Personal" on the next launch — a data loss dressed up as a fresh start.
     */
    val canDelete: Boolean get() = calendars.size > 1

    /** The feed filling [calendarId], if it is a subscribed calendar. */
    fun subscriptionFor(calendarId: String): CalendarSubscriptionSummary? =
        subscriptionsByCalendar[calendarId]

    /** The mirror filling [calendarId], if it is kept in sync with another calendar. */
    fun mirrorFor(calendarId: String): CalendarMirrorSummary? = mirrorsByCalendar[calendarId]
}

/** The subscribe dialog's contents (CA-7b). */
data class SubscribeDraft(
    val url: String = "",
    val name: String = "",
    /** Set when the URL cannot be subscribed to, and why. */
    val error: String? = null,
    val isSaving: Boolean = false,
)

/**
 * The calendars screen (CA-5f).
 *
 * ⚠⚠ **This screen exists because two toggles had nowhere to live.** The
 * per-calendar `sensitive` flag (Q10) has been on the row, accepted by
 * `SaveCalendar` and honoured by `ReminderPlanner` since CA-5a — with no way for
 * a user to set it. `setCalendarVisible` has been on `CalendarViewModel` since
 * CA-2 with no UI at all. Both are one-line calls; what was missing was a place
 * to put them, and that place turns out to be a calendar list with add, rename,
 * recolour and delete on it.
 *
 * ⚠ Visibility and sensitivity sit side by side and are **not** the same kind of
 * setting — one is device-local view state, the other a synced property of the
 * data. The screen has to make that legible; see the field KDocs.
 */
class CalendarManagementViewModel(
    private val repository: CalendarRepository,
    /** ⚠ Optional: without it no row is ever a mirror (CX-1b). */
    private val mirrors: CalendarMirrorRepository? = null,
    /** ⚠ Optional: only a device with a calendar store can refresh a mirror. */
    private val refresher: MirrorRefresher? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CalendarManagementUiState())
    val uiState: StateFlow<CalendarManagementUiState> = _uiState

    /** The live read of this screen's lists; swapped by [refresh]. */
    private var subscription: Job? = null

    init {
        refresh()
    }

    /**
     * (Re)subscribes to the three lists this screen renders (CA-2i).
     *
     * ⚠⚠ **A subscription, not a load.** A calendar renamed on the phone, a
     * subscription whose background fetch just landed, a default changed elsewhere —
     * all arrive on their own. Before this the screen read once on entry, which was
     * *nearly* right and quietly stopped being right the moment anything else could
     * write while it was open.
     *
     * ⚠ `refresh()` keeps its name and its callers, iOS's pull-to-refresh included;
     * it now swaps the subscription rather than fetching. The previous one is
     * cancelled first, or every pull would leave another live behind.
     *
     * ⚠ The three flows are combined rather than collected separately: the screen
     * renders them together (a row shows its calendar, its feed, and whether it is
     * the default), and three independent collectors would paint two thirds of a
     * change and then the rest.
     */
    fun refresh() {
        subscription?.cancel()
        subscription = viewModelScope.launch {
            combine(
                repository.observeCalendars(),
                repository.observeDefaultCalendar(),
                repository.observeSubscriptions(),
                mirrors?.observeMirrors() ?: flowOf(emptyList()),
            ) { calendars, default, subscriptions, mirrored ->
                Lists(calendars, default, subscriptions, mirrored)
            }
                .catch { error ->
                    // ⚠ A failing read must not take the screen down with it: this flow
                    // is collected for the life of the screen, so the failure becomes
                    // state, exactly as the one-shot read's `runCatching` did.
                    // ⚠⚠ A cancellation is not such a failure — the screen is leaving — and
                    // `catch` does not exempt it, so it would render as "Could not load".
                    if (error is CancellationException) throw error
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "Could not load calendars",
                        )
                    }
                }
                .collect { (calendars, default, subscriptions, mirrored) ->
                    _uiState.update {
                        it.copy(
                            // ⚠⚠ The *store's* visibility wins here, and it is allowed to:
                            // `setVisible` below writes fire-and-forget and updates this
                            // list optimistically, so the only correction that can arrive
                            // is a write that did not land (CA-2g).
                            calendars = calendars,
                            defaultCalendarId = default,
                            subscriptionsByCalendar = subscriptions.associateBy { s -> s.calendarId },
                            mirrorsByCalendar = mirrored.associateBy { m -> m.calendarId },
                            isLoading = false,
                            errorMessage = null,
                        )
                    }
                }
        }
    }

    // ── view state ───────────────────────────────────────────────────────────

    /**
     * Shows or hides a calendar **on this device**.
     *
     * ⚠ Applied to the local list immediately rather than waiting for a refresh:
     * the write is a preference round trip, and a checkbox that lags behind the
     * finger reads as broken. The refresh that follows is the correction, and it
     * agrees in every non-failing case.
     */
    fun setVisible(calendarId: String, visible: Boolean) {
        _uiState.update { state ->
            state.copy(
                calendars = state.calendars.map {
                    if (it.calendarId == calendarId) it.copy(visible = visible) else it
                }
            )
        }
        viewModelScope.launch {
            runCatchingCancellable { repository.setCalendarVisible(calendarId, visible) }
                .onFailure { refresh() }
        }
    }

    fun setDefault(calendarId: String) {
        // ⚠ Never a read-only calendar: the default is where Mia's headless *create event* files,
        // and that event would be gone at the calendar's next refresh.
        if (_uiState.value.calendars.any { it.calendarId == calendarId && it.readOnly }) return
        _uiState.update { it.copy(defaultCalendarId = calendarId) }
        viewModelScope.launch {
            runCatchingCancellable { repository.setDefaultCalendar(calendarId) }.fold(
                onSuccess = { },
                onFailure = { refresh() },
            )
        }
    }

    // ── the add/edit sheet ───────────────────────────────────────────────────

    fun startCreating() {
        _uiState.update {
            it.copy(
                draft = CalendarDraft(colorArgb = CalendarPalette.nextAfter(it.calendars.map { c -> c.colorArgb })),
                draftError = null,
            )
        }
    }

    fun startEditing(calendar: CalendarSummary) {
        _uiState.update {
            it.copy(
                draft = CalendarDraft(
                    calendarId = calendar.calendarId,
                    name = calendar.name,
                    colorArgb = calendar.colorArgb,
                    sensitive = calendar.sensitive,
                ),
                draftError = null,
            )
        }
    }

    fun dismissDraft() = _uiState.update { it.copy(draft = null, draftError = null) }

    fun setDraftName(name: String) =
        _uiState.update { it.copy(draft = it.draft?.copy(name = name), draftError = null) }

    fun setDraftColor(colorArgb: Int) =
        _uiState.update { it.copy(draft = it.draft?.copy(colorArgb = colorArgb)) }

    /**
     * ⚠⚠ **The one setting on this screen that is about privacy rather than
     * tidiness.** A sensitive calendar's reminders reach the lock screen with
     * their title and location replaced — the redaction happens in
     * `ReminderPlanner`, upstream of every presenter, because on Apple platforms
     * the OS holds the text days in advance and cannot be asked to edit it later.
     */
    fun setDraftSensitive(sensitive: Boolean) =
        _uiState.update { it.copy(draft = it.draft?.copy(sensitive = sensitive)) }

    /**
     * Commits the draft.
     *
     * ⚠⚠ A duplicate name is **refused**, not merged. The id of a new calendar is
     * derived from its name so two devices creating "Work" offline converge on one
     * row — which is right for that case and wrong for a user deliberately making
     * a second "Work", where it would silently replace the first. Refusing here is
     * how that stays a message instead of a disappearance.
     */
    fun saveDraft() {
        val state = _uiState.value
        val draft = state.draft ?: return
        val name = draft.name.trim()
        if (name.isBlank()) {
            _uiState.update { it.copy(draftError = "Give the calendar a name") }
            return
        }
        val clash = state.calendars.any {
            it.name.equals(name, ignoreCase = true) && it.calendarId != draft.calendarId
        }
        if (clash) {
            _uiState.update { it.copy(draftError = "You already have a calendar called \"$name\"") }
            return
        }

        viewModelScope.launch {
            runCatchingCancellable {
                repository.saveCalendar(
                    calendarId = draft.calendarId,
                    name = name,
                    colorArgb = draft.colorArgb,
                    sensitive = draft.sensitive,
                )
            }.fold(
                onSuccess = {
                    _uiState.update { it.copy(draft = null, draftError = null) }
                    refresh()
                },
                onFailure = { error ->
                    _uiState.update { it.copy(draftError = error.message ?: "Could not save") }
                },
            )
        }
    }

    // ── deleting ─────────────────────────────────────────────────────────────

    /**
     * ⚠⚠ Always confirmed, and the confirmation must name the consequence:
     * deleting a calendar cascades to **every event in it**
     * (`DeleteCalendarImplementation`). This is the most destructive action in
     * the app and the only one with no undo.
     */
    fun askDelete(calendar: CalendarSummary) {
        if (!_uiState.value.canDelete) return
        _uiState.update { it.copy(confirmingDelete = calendar) }
    }

    fun dismissDelete() = _uiState.update { it.copy(confirmingDelete = null) }

    fun confirmDelete() {
        val target = _uiState.value.confirmingDelete ?: return
        _uiState.update { it.copy(confirmingDelete = null) }
        viewModelScope.launch {
            runCatchingCancellable { repository.deleteCalendar(target.calendarId) }.fold(
                onSuccess = { refresh() },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(errorMessage = error.message ?: "Could not delete")
                    }
                },
            )
        }
    }

    fun dismissError() = _uiState.update { it.copy(errorMessage = null) }

    // ── subscriptions (CA-7b) ────────────────────────────────────────────────

    fun startSubscribing() = _uiState.update { it.copy(subscribeDraft = SubscribeDraft()) }

    fun dismissSubscribe() = _uiState.update { it.copy(subscribeDraft = null) }

    fun setSubscribeUrl(url: String) =
        _uiState.update { it.copy(subscribeDraft = it.subscribeDraft?.copy(url = url, error = null)) }

    fun setSubscribeName(name: String) =
        _uiState.update { it.copy(subscribeDraft = it.subscribeDraft?.copy(name = name)) }

    /**
     * Subscribes to what the dialog holds.
     *
     * ⚠⚠ **This call is the consent** (§2.4 / Q9). From here the device fetches
     * that publisher on a schedule, disclosing its IP and that the user holds this
     * calendar — which is why the dialog's copy has to say so *before* the button.
     *
     * ⚠ A refused URL keeps the dialog open with a message. `null` back from the
     * repository means "not a scheme we will fetch", and telling the user now
     * beats a subscription that silently never updates.
     */
    fun commitSubscribe() {
        val draft = _uiState.value.subscribeDraft ?: return
        if (draft.url.isBlank()) {
            _uiState.update { it.copy(subscribeDraft = draft.copy(error = "Paste a calendar address")) }
            return
        }
        _uiState.update { it.copy(subscribeDraft = draft.copy(isSaving = true, error = null)) }
        viewModelScope.launch {
            runCatchingCancellable { repository.addSubscription(draft.url, draft.name) }.fold(
                onSuccess = { id ->
                    if (id == null) {
                        _uiState.update {
                            it.copy(
                                subscribeDraft = draft.copy(
                                    isSaving = false,
                                    error = "Only https:// and webcal:// addresses can be subscribed to",
                                )
                            )
                        }
                    } else {
                        _uiState.update { it.copy(subscribeDraft = null) }
                        refresh()
                        // ⚠ Fetched immediately rather than waiting up to six hours
                        // for the schedule: a calendar that stays empty after you
                        // add it reads as broken.
                        bestEffort("CalendarManagement -> the first refresh of a new subscription") {
                            repository.refreshSubscriptions()
                        }
                        refresh()
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            subscribeDraft = draft.copy(
                                isSaving = false,
                                error = error.message ?: "Could not subscribe",
                            )
                        )
                    }
                },
            )
        }
    }

    fun setSubscriptionEnabled(subscriptionId: String, enabled: Boolean) {
        viewModelScope.launch {
            bestEffort("CalendarManagement -> enabling or disabling a subscription") {
                repository.setSubscriptionEnabled(subscriptionId, enabled)
            }
            refresh()
        }
    }

    /** ⚠ Takes the calendar with it — the confirmation copy has to say so. */
    fun removeSubscription(subscriptionId: String) {
        viewModelScope.launch {
            runCatchingCancellable { repository.removeSubscription(subscriptionId) }.fold(
                onSuccess = { refresh() },
                onFailure = { error ->
                    _uiState.update { it.copy(errorMessage = error.message ?: "Could not unsubscribe") }
                },
            )
        }
    }

    fun refreshSubscriptionsNow() {
        viewModelScope.launch {
            bestEffort("CalendarManagement -> refreshing the subscriptions") { repository.refreshSubscriptions() }
            refresh()
        }
    }

    // ── calendars kept in sync (CX-1b) ───────────────────────────────────────

    /** *Refresh now*: the bound device reads its source and applies what changed. */
    fun refreshMirrorNow(mirrorId: String) {
        val refresher = refresher ?: return
        viewModelScope.launch {
            bestEffort("CalendarManagement -> refreshing a kept-in-sync calendar") {
                refresher.refresh(mirrorId)
            }
        }
    }

    fun setMirrorReminders(mirrorId: String, enabled: Boolean) {
        val mirrors = mirrors ?: return
        viewModelScope.launch {
            bestEffort("CalendarManagement -> switching a kept-in-sync calendar's reminders") {
                mirrors.setMirrorReminders(mirrorId, enabled)
                // ⚠ Applied now, not at the next source change: the switch changes what a sync
                // writes, and the digest includes it, so this refresh does the work.
                refresher?.refresh(mirrorId)
            }
        }
    }

    /** ⚠ Keeps the events: the calendar becomes an ordinary, editable one. */
    fun stopMirror(mirrorId: String) {
        val mirrors = mirrors ?: return
        viewModelScope.launch {
            runCatchingCancellable { mirrors.stopMirror(mirrorId) }.onFailure { error ->
                _uiState.update { it.copy(errorMessage = error.message ?: "Could not stop syncing") }
            }
        }
    }
}

private data class Lists(
    val calendars: List<CalendarSummary>,
    val default: String,
    val subscriptions: List<CalendarSubscriptionSummary>,
    val mirrors: List<CalendarMirrorSummary>,
)
