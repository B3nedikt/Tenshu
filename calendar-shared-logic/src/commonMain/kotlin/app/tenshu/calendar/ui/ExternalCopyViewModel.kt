package app.tenshu.calendar.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.calendar.data.CalendarMirrorRepository
import app.tenshu.calendar.data.CalendarRepository
import app.tenshu.calendar.data.ExternalCopyResult
import app.tenshu.calendar.data.external.MirrorBackgroundScheduler
import app.tenshu.calendar.data.external.MirrorRefreshOutcome
import app.tenshu.calendar.data.external.MirrorRefresher
import app.tenshu.calendar.data.external.ExternalCalendar
import app.tenshu.calendar.data.external.ExternalCopyIds
import app.tenshu.calendar.data.external.ExternalCalendarAccessDenied
import app.tenshu.calendar.data.external.ExternalCalendarReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.tenshu.core.common.util.runCatchingCancellable

/** Why a *Copy from this phone* produced nothing. Typed, so the copy lives with the UI. */
enum class ExternalCopyProblem {
    /** The OS said no — never granted, or revoked between asking and reading. */
    PermissionDenied,

    /** The OS's calendar store could not be read at all. */
    Unreadable,

    /** The calendar was read, holds events, and none of them could be placed in time. */
    NothingToCopy,

    /** The read worked and the write did not — usually Control is unreachable. */
    CopyFailed,

    /**
     * *Keep in sync* could not start: this device's sync id is not known yet, or the first
     * refresh failed. ⚠ A mirror may exist by now — it is shown on the calendars screen and will
     * catch up on the next refresh.
     */
    SyncFailed,
}

/** The calendars of one account, as the chooser lists them. */
data class ExternalAccountGroup(
    val accountName: String,
    val accountType: String,
    val calendars: List<ExternalCalendar>,
)

/**
 * A source a *Keep in sync* mirror already brings in (CX-1b) — on this device or another, since the
 * mirror row syncs.
 *
 * ⚠⚠ Why the chooser must know: the same iCloud or Google calendar is visible on several devices, and
 * a *Copy once* of it on a second device lands in a calendar **beside** the mirror (a mirror and a
 * copy never share one — §11), so the user gets every event twice.
 */
data class MirroredSource(
    /** Tenshu's name for the mirror's calendar — the user may have renamed it. */
    val calendarName: String,
    /** Whether **this** device is the one refreshing it. */
    val boundHere: Boolean,
    /** Carried into a rebind, which would otherwise turn the switch off. */
    val remindersEnabled: Boolean,
)

sealed interface ExternalCopyStep {
    data object Closed : ExternalCopyStep

    data object Loading : ExternalCopyStep

    /**
     * The OS's calendars, by account.
     *
     * ⚠ An empty list is a real state with its own copy — a phone with no calendar accounts is
     * common, and the answer to it is *Add an account*, not an error.
     */
    data class Choosing(
        val accounts: List<ExternalAccountGroup>,
        /**
         * Source key → name of the Tenshu calendar an earlier copy made. ⚠ Copying again
         * *updates* that calendar (its id is derived from the source, [ExternalCopyIds]), so its
         * row must not promise a new one. The name is Tenshu's, because the user may have renamed
         * the copy.
         */
        val copiedInto: Map<String, String> = emptyMap(),
        /** Source key → the mirror that already brings it in. Wins over [copiedInto] on a row. */
        val mirroredInto: Map<String, MirroredSource> = emptyMap(),
    ) : ExternalCopyStep

    /**
     * *Copy once* or *Keep in sync* (CX-1b).
     *
     * ⚠ Asked every time rather than defaulted: the two leave very different calendars behind —
     * one editable and frozen, one read-only and live — and a user who got the wrong one would
     * only find out a week later.
     */
    data class Deciding(
        val calendar: ExternalCalendar,
        /**
         * ⚠⚠ Set when a mirror already brings this source in. Then *Copy once* is **not offered**
         * (and [ExternalCopyViewModel.copy] refuses it): the only choices are to keep it in sync
         * from this device instead — a rebind, same calendar — or, if it already is, to update it.
         */
        val mirrored: MirroredSource? = null,
        /**
         * The name of an earlier *Copy once* of this source. ⚠ *Keep in sync* still makes a calendar
         * of its own beside it (an editable copy is never turned into a mirror — its first refresh
         * would sweep what the user added), so the sheet says the copy stays.
         */
        val copiedInto: String? = null,
    ) : ExternalCopyStep

    data class Copying(val calendar: ExternalCalendar) : ExternalCopyStep

    data class Done(val result: ExternalCopyResult) : ExternalCopyStep

    /** A mirror started and its first refresh ran. */
    data class Mirrored(val calendarName: String, val outcome: MirrorRefreshOutcome?) : ExternalCopyStep

    data class Failed(val problem: ExternalCopyProblem) : ExternalCopyStep
}

data class ExternalCopyUiState(val step: ExternalCopyStep = ExternalCopyStep.Closed) {
    val isOpen: Boolean get() = step != ExternalCopyStep.Closed
}

/**
 * *Copy from this phone* (`calendar-external-sources.md` CX-1): pick a calendar the OS already
 * has — Google, Outlook with sync on, anything DAVx5 brings in — and copy it into Tenshu once.
 *
 * ⚠ Knows nothing about permissions. Asking is the platform's job, at the one button the user
 * pressed (the Contacts `READ_CONTACTS` precedent); the UI calls [open] once it holds the grant
 * and [permissionDenied] when it was refused. ⚠ Nor about Android: the [reader] is a seam, absent
 * on a platform that has none, which is what [supported] reports.
 */
class ExternalCopyViewModel(
    private val repository: CalendarRepository,
    private val reader: ExternalCalendarReader?,
    /** ⚠ Both optional: without them the flow is *Copy once* only, as in CX-1a. */
    private val mirrors: CalendarMirrorRepository? = null,
    private val refresher: MirrorRefresher? = null,
    private val background: MirrorBackgroundScheduler? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExternalCopyUiState())
    val uiState: StateFlow<ExternalCopyUiState> = _uiState

    /** Whether this platform can offer the flow at all — the button is hidden otherwise. */
    val supported: Boolean get() = reader != null

    /** Whether *Keep in sync* can be offered beside *Copy once*. */
    val canKeepInSync: Boolean get() = mirrors != null && refresher != null

    /** Opens the chooser. Call only with the OS permission already held. */
    fun open() {
        val reader = reader ?: return
        _uiState.update { it.copy(step = ExternalCopyStep.Loading) }
        viewModelScope.launch {
            runCatchingCancellable { reader.calendars() }.fold(
                onSuccess = { calendars ->
                    val tenshuCalendars = runCatchingCancellable { repository.calendars() }.getOrNull()
                        ?.associate { it.calendarId to it.name }
                    setStep(
                        ExternalCopyStep.Choosing(
                            group(calendars),
                            copiedInto(calendars, tenshuCalendars),
                            mirroredInto(calendars, tenshuCalendars),
                        )
                    )
                },
                onFailure = { setStep(ExternalCopyStep.Failed(problemOf(it))) },
            )
        }
    }

    /**
     * Which of [calendars] already have a Tenshu copy.
     *
     * ⚠ Best effort: if Tenshu's calendars cannot be read, every row reads as a first copy — a
     * label that is wrong is better than a chooser that does not open.
     */
    private fun copiedInto(
        calendars: List<ExternalCalendar>,
        tenshuCalendars: Map<String, String>?,
    ): Map<String, String> {
        val existing = tenshuCalendars ?: return emptyMap()
        return calendars.mapNotNull { source ->
            existing[ExternalCopyIds.calendarId(source)]?.let { source.sourceKey to it }
        }.toMap()
    }

    /**
     * Which of [calendars] a mirror already brings in — from any device.
     *
     * ⚠ Matched by kind **and** key, the pair the mirror's id derives from. ⚠ Best effort like
     * [copiedInto], but the failure is louder: a row that reads as new here invites the duplicate
     * this exists to prevent, so the miss is at least logged.
     */
    private suspend fun mirroredInto(
        calendars: List<ExternalCalendar>,
        tenshuCalendars: Map<String, String>?,
    ): Map<String, MirroredSource> {
        val mirrors = mirrors ?: return emptyMap()
        val rows = runCatchingCancellable { mirrors.mirrors() }.getOrElse {
            println("WARN ExternalCopyViewModel: mirrors unreadable, the chooser cannot mark mirrored sources — $it")
            return emptyMap()
        }
        return calendars.mapNotNull { source ->
            rows.firstOrNull { it.sourceKind == source.kind.name && it.sourceKey == source.sourceKey }
                ?.let { mirror ->
                    source.sourceKey to MirroredSource(
                        calendarName = tenshuCalendars?.get(mirror.calendarId) ?: source.name,
                        boundHere = mirror.boundHere,
                        remindersEnabled = mirror.remindersEnabled,
                    )
                }
        }.toMap()
    }

    /**
     * The OS refused. ⚠ Opens the sheet on a reason rather than doing nothing — a button that
     * silently does nothing after a refusal reads as broken, not as declined.
     */
    fun permissionDenied() = setStep(ExternalCopyStep.Failed(ExternalCopyProblem.PermissionDenied))

    /**
     * Copies [calendar].
     *
     * ⚠ Only from the chooser, and only once: a second tap while the first copy runs would start
     * a second read of the same calendar, and the rows would land twice over the wire for nothing.
     */
    fun copy(calendar: ExternalCalendar) {
        val reader = reader ?: return
        if (!canStartFrom(calendar)) return
        // ⚠⚠ Refused here, not only left off the sheet: a copy beside a mirror of the same source is
        // every event twice, and a second UI (SwiftUI) must not be able to forget the rule.
        if (mirroredFor(calendar) != null) return
        setStep(ExternalCopyStep.Copying(calendar))
        viewModelScope.launch {
            val snapshot = runCatchingCancellable { reader.read(calendar) }.getOrElse {
                setStep(ExternalCopyStep.Failed(problemOf(it)))
                return@launch
            }
            // ⚠⚠ Events that were there and none of which could be placed is a failure, not an
            // empty success — the CA-6 import rule. An empty calendar is not: there is honestly
            // nothing to copy, and the result says so.
            if (snapshot.events.isEmpty() && snapshot.skipped > 0) {
                setStep(ExternalCopyStep.Failed(ExternalCopyProblem.NothingToCopy))
                return@launch
            }
            runCatchingCancellable { repository.copyExternalCalendar(calendar, snapshot) }.fold(
                onSuccess = { setStep(ExternalCopyStep.Done(it)) },
                onFailure = { setStep(ExternalCopyStep.Failed(ExternalCopyProblem.CopyFailed)) },
            )
        }
    }

    /**
     * A calendar was tapped: ask which kind of calendar to make of it — or, where *Keep in sync*
     * cannot be offered, just copy it.
     */
    fun choose(calendar: ExternalCalendar) {
        val step = _uiState.value.step as? ExternalCopyStep.Choosing ?: return
        if (!calendar.holdsEvents) return
        val mirrored = step.mirroredInto[calendar.sourceKey]
        // ⚠ A mirrored source always gets the decision, even where *Keep in sync* is not wired:
        // the copy it would otherwise go straight to is refused, and a tap that silently does
        // nothing reads as broken. The decision at least says why.
        if (canKeepInSync || mirrored != null) {
            setStep(
                ExternalCopyStep.Deciding(
                    calendar,
                    mirrored = mirrored,
                    copiedInto = step.copiedInto[calendar.sourceKey],
                )
            )
        } else {
            copy(calendar)
        }
    }

    /** The mirror already bringing [calendar] in, as the current step knows it. */
    private fun mirroredFor(calendar: ExternalCalendar): MirroredSource? =
        when (val step = _uiState.value.step) {
            is ExternalCopyStep.Choosing -> step.mirroredInto[calendar.sourceKey]
            is ExternalCopyStep.Deciding -> step.mirrored
            else -> null
        }

    /** Back from the decision to the list. */
    fun backToList() = open()

    /**
     * Starts keeping [calendar] in sync from this device, then refreshes it once so the user sees
     * the events straight away rather than at the next change.
     *
     * @param remindersEnabled ⚠ off unless asked for (§5.7): the source's own app already reminds.
     */
    fun keepInSync(calendar: ExternalCalendar, remindersEnabled: Boolean) {
        val mirrors = mirrors ?: return
        val refresher = refresher ?: return
        if (!canStartFrom(calendar)) return
        setStep(ExternalCopyStep.Copying(calendar))
        viewModelScope.launch {
            val mirror = runCatchingCancellable { mirrors.startMirror(calendar, remindersEnabled) }
                .getOrElse {
                    setStep(ExternalCopyStep.Failed(ExternalCopyProblem.SyncFailed))
                    return@launch
                }
            background?.ensureScheduled()
            val outcome = runCatchingCancellable { refresher.refresh(mirror.mirrorId) }.getOrNull()
            setStep(
                when (outcome) {
                    is MirrorRefreshOutcome.Failed ->
                        ExternalCopyStep.Failed(
                            if (outcome.accessDenied) ExternalCopyProblem.PermissionDenied
                            else ExternalCopyProblem.SyncFailed,
                        )
                    else -> ExternalCopyStep.Mirrored(calendar.name, outcome)
                }
            )
        }
    }

    /**
     * ⚠ Only from the list or the decision, and only once: a second tap while a copy runs would
     * read the calendar again and write it twice over the wire for nothing.
     */
    private fun canStartFrom(calendar: ExternalCalendar): Boolean {
        val step = _uiState.value.step
        return calendar.holdsEvents &&
            (step is ExternalCopyStep.Choosing || (step is ExternalCopyStep.Deciding && step.calendar == calendar))
    }

    /**
     * Closes the sheet.
     *
     * ⚠ Ignored while a copy is running: the write carries on regardless, and a sheet that closed
     * would leave the user no way to learn whether it finished.
     */
    fun dismiss() {
        if (_uiState.value.step is ExternalCopyStep.Copying) return
        setStep(ExternalCopyStep.Closed)
    }

    private fun setStep(step: ExternalCopyStep) = _uiState.update { it.copy(step = step) }

    private fun problemOf(failure: Throwable): ExternalCopyProblem =
        if (failure is ExternalCalendarAccessDenied) ExternalCopyProblem.PermissionDenied
        else ExternalCopyProblem.Unreadable

    private fun group(calendars: List<ExternalCalendar>): List<ExternalAccountGroup> =
        calendars
            .groupBy { it.accountType to it.accountName }
            .map { (account, list) ->
                ExternalAccountGroup(
                    accountName = account.second,
                    accountType = account.first,
                    // ⚠ Calendars that hold events first: a disabled row at the top of a list
                    // is the first thing a user taps.
                    calendars = list.sortedWith(compareBy({ !it.holdsEvents }, { it.name.lowercase() })),
                )
            }
            .sortedBy { it.accountName.lowercase() }
}
