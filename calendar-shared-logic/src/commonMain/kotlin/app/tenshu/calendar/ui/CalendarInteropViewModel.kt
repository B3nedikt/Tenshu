package app.tenshu.calendar.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.calendar.data.CalendarMirrorRepository
import app.tenshu.calendar.data.CalendarRepository
import app.tenshu.calendar.data.CalendarSummary
import app.tenshu.calendar.data.IcsImportResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.tenshu.core.common.util.runCatchingCancellable

/**
 * Why an import or export stopped short.
 *
 * ⚠ Typed rather than a message, so the copy lives with the UI (ADR 0016) and the
 * platform picker never has to invent user-facing English — the
 * `ContactImportProblem` precedent.
 */
enum class InteropProblem {
    /** The picker returned something that could not be read or written. */
    Unreadable,

    /** It was read, and held nothing at all. */
    EmptyFile,

    /** It parsed, and held no events this app could place in time. */
    NothingImported,

    /** The write itself failed after the user chose a destination. */
    ExportFailed,
}

data class CalendarInteropUiState(
    /** The calendar being imported into or exported from; blank when the sheet is shut. */
    val calendarId: String = "",
    val calendarName: String = "",
    /**
     * `.ics` text that arrived **before** a calendar was chosen — the host OS handed
     * us a file (a macOS document open, an iOS *Open in*) rather than the user
     * starting from a row.
     *
     * ⚠⚠ This is the whole reason the sheet has two entry orders. Every in-app path
     * answers "which calendar?" first, by being reached from that calendar's row;
     * a file from outside answers it last, because the OS knows nothing about
     * calendars. Holding the text here is what lets one flow serve both.
     */
    val pendingImportText: String? = null,
    /** The file name that came with [pendingImportText]; free text, shown but never parsed. */
    val pendingImportOrigin: String = "",
    /** A picker is up, or its text is being written through the ops. */
    val isBusy: Boolean = false,
    /**
     * What the last import did. Kept after the picker closes so the sheet can
     * report it — an import that silently does nothing visible is
     * indistinguishable from one that failed.
     */
    val lastImport: IcsImportResult? = null,
    /** True once an export has been written. */
    val exported: Boolean = false,
    val problem: InteropProblem? = null,
    /**
     * The calendars an outside file may be imported into.
     *
     * ⚠⚠ **Subscribed calendars are filtered out**, and the rule lives here rather
     * than in the sheet that draws it. Importing into a feed-backed calendar loses
     * the events at the next refresh, which replaces its whole contents — a trap
     * worth removing from a list the user picks from blind. A row's own *Import or
     * export…* still offers them, because there the user named that calendar and
     * export is the point.
     *
     * ⚠ Held by this view model, not passed in, so the sheet can be hosted by any
     * screen — which is what lets a file opened from the Finder appear over the
     * calendar the user was looking at instead of navigating them away from it.
     */
    val importTargets: List<CalendarSummary> = emptyList(),
    /** Calendars a feed or a mirror fills — see [canImport]. */
    val filledElsewhere: Set<String> = emptySet(),
) {
    /**
     * ⚠⚠ Whether the open calendar takes an import. Not one filled by a subscription or a mirror:
     * its next refresh would delete what the file added, after the sheet had reported success.
     * Export stays available.
     */
    val canImport: Boolean get() = calendarId.isNotBlank() && calendarId !in filledElsewhere

    /**
     * ⚠ [problem] counts on its own, because a file the OS handed over can fail
     * *before* there is a calendar or any text to hold — and a sheet that stays shut
     * over a failed open is a double-click that visibly did nothing.
     */
    val visible: Boolean
        get() = calendarId.isNotBlank() || pendingImportText != null || problem != null

    /**
     * The sheet is asking which calendar a handed-over file goes into.
     *
     * ⚠ Both halves matter: once [chooseCalendar] has answered the question the
     * calendar is set and the import is already in flight, so a check on
     * [pendingImportText] alone would leave the chooser up over a running import.
     */
    val choosingCalendar: Boolean get() = pendingImportText != null && calendarId.isBlank()

    /**
     * The file name an export suggests; the user may change it on every platform.
     *
     * ⚠ Runs of separators **collapse**. Mapping each non-alphanumeric to `-`
     * one for one turns "Work & Life" into `work---life`, which looks like a bug
     * in the file name of every export a user ever makes.
     *
     * ⚠ Not ASCII-folded: `ünïcode.ics` is a perfectly good file name on every
     * platform we ship, and transliterating would mangle names in scripts that
     * have no Latin equivalent at all.
     */
    val suggestedFileName: String
        get() = calendarName.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .split('-')
            .filter { it.isNotEmpty() }
            .joinToString("-")
            .ifBlank { "calendar" } + ".ics"

    /** True while nothing has happened yet — the sheet shows its two choices. */
    val showingChoices: Boolean
        get() = !isBusy && !choosingCalendar && lastImport == null && !exported && problem == null
}

/**
 * Import and export, as a flow (CA-6b).
 *
 * ⚠⚠ **Knows nothing about pickers.** The platform layer hands it `.ics` *text* and
 * takes text back; everything either side of that is CA-6a's repository seam. That
 * split is what keeps this testable — the parts that can only be exercised on a
 * device (a `ContentResolver` stream, an AWT file dialog) contain no logic beyond
 * moving bytes. `ContactImportViewModel` is the precedent, and the shape transfers
 * exactly.
 */
class CalendarInteropViewModel(
    private val repository: CalendarRepository,
    /** ⚠ Optional (CX-1b): a mirrored calendar is read-only, so it is no import target either. */
    private val mirrors: CalendarMirrorRepository? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CalendarInteropUiState())
    val uiState: StateFlow<CalendarInteropUiState> = _uiState

    init {
        // ⚠ Live, and collected for this view model's life: the sheet can be opened by
        // a file arriving from the OS at any moment, so its chooser cannot be a list
        // fetched when some other screen was entered.
        viewModelScope.launch {
            combine(
                repository.observeCalendars(),
                repository.observeSubscriptions(),
                mirrors?.observeMirrors() ?: flowOf(emptyList()),
            ) { calendars, subscriptions, mirrored ->
                // ⚠ The one rule for "something else fills this calendar": a feed or a mirror
                // replaces its contents on the next refresh, so a file imported into either would
                // be lost.
                val filledElsewhere = subscriptions.mapTo(mutableSetOf()) { it.calendarId } +
                    mirrored.map { it.calendarId }
                calendars.filterNot { it.calendarId in filledElsewhere } to filledElsewhere
            }.collect { (targets, filledElsewhere) ->
                _uiState.update { it.copy(importTargets = targets, filledElsewhere = filledElsewhere) }
            }
        }
    }

    /**
     * A fresh sheet, **keeping the chooser's contents**.
     *
     * ⚠⚠ Every entry point resets this state wholesale, which is right — reopening
     * must not show the last import's result. But `importTargets` is not part of
     * "which calendar is this sheet about"; it is a live list that arrives on its own
     * schedule, and dropping it here would empty the chooser until the next emission,
     * which for a `StateFlow`-backed read may never come.
     */
    private fun freshState(
        calendarId: String = "",
        calendarName: String = "",
        pendingImportText: String? = null,
        pendingImportOrigin: String = "",
        problem: InteropProblem? = null,
    ) = CalendarInteropUiState(
        calendarId = calendarId,
        calendarName = calendarName,
        pendingImportText = pendingImportText,
        pendingImportOrigin = pendingImportOrigin,
        problem = problem,
        importTargets = _uiState.value.importTargets,
        filledElsewhere = _uiState.value.filledElsewhere,
    )

    fun open(calendarId: String, calendarName: String) {
        _uiState.value = freshState(calendarId = calendarId, calendarName = calendarName)
    }

    /**
     * Opens the sheet for a file the **host OS** handed over — a macOS document open,
     * an iOS *Open in* — before any calendar has been chosen.
     *
     * ⚠⚠ Nothing is written until [chooseCalendar]. A file arriving from outside the
     * app is not consent to put its contents somewhere in particular, and there is no
     * defensible guess: the default calendar is where *this device's* new events go,
     * which says nothing about where a stranger's file belongs. Asking is the only
     * honest move, and it is also the recoverable one — the wrong calendar means
     * hand-deleting every event in the file.
     *
     * ⚠ [origin] is the file's name, carried through to `EventSource.origin` so the
     * events can say where they came from. Free text; never parsed.
     */
    fun openForImport(text: String, origin: String = "") {
        if (text.isBlank()) return openFailed(origin, InteropProblem.EmptyFile)
        _uiState.value = freshState(pendingImportText = text, pendingImportOrigin = origin)
    }

    /**
     * The host handed over a file that could not be read at all.
     *
     * ⚠⚠ Still opens the sheet. The alternative is a double-click that launches the
     * app and then appears to do nothing, which the user reads as "this app cannot
     * open .ics files" — the opposite of what the file association just claimed.
     */
    fun openFailed(origin: String, problem: InteropProblem) {
        _uiState.value = freshState(pendingImportOrigin = origin, problem = problem)
    }

    /**
     * Answers [openForImport]'s question and starts the import straight away.
     *
     * ⚠ A no-op unless a file is actually waiting, so a stray tap on a chooser that
     * has already been answered cannot re-import the same file into a second
     * calendar.
     */
    fun chooseCalendar(calendarId: String, calendarName: String) {
        val pending = _uiState.value.pendingImportText ?: return
        val origin = _uiState.value.pendingImportOrigin
        // ⚠ Cleared *before* the import runs, not after: `importText` is what sets
        // `isBusy`, and leaving the text pending across an in-flight write is what
        // would put the chooser back up underneath it.
        _uiState.update {
            it.copy(
                calendarId = calendarId,
                calendarName = calendarName,
                pendingImportText = null,
            )
        }
        importText(pending, origin)
    }

    /** Closes the sheet and drops the last outcome — reopening starts clean. */
    fun dismiss() {
        _uiState.value = freshState()
    }

    /** Back to the two choices after a result — "do the other one too". */
    fun acknowledgeResult() = _uiState.update {
        it.copy(lastImport = null, exported = false, problem = null)
    }

    /** A picker was launched; the sheet shows progress until it comes back. */
    fun pickStarted() = _uiState.update {
        it.copy(isBusy = true, lastImport = null, exported = false, problem = null)
    }

    /** The user backed out. Not a failure — say nothing, show the choices again. */
    fun pickCancelled() = _uiState.update { it.copy(isBusy = false) }

    fun pickFailed(problem: InteropProblem) = _uiState.update {
        it.copy(isBusy = false, problem = problem)
    }

    /**
     * Imports `.ics` text a platform picker produced.
     *
     * ⚠⚠ **Zero events out of a non-empty file is a failure, not a quiet success.**
     * The file had content and none of it landed, which is exactly the case a user
     * needs told — the `ContactImportViewModel` precedent. ⚠ *Skipped* events are
     * not a failure though: some landed, and the count is reported alongside.
     */
    fun importText(text: String, origin: String = "") {
        val calendarId = _uiState.value.calendarId
        if (calendarId.isBlank() || !_uiState.value.canImport) return
        if (text.isBlank()) {
            pickFailed(InteropProblem.EmptyFile)
            return
        }
        _uiState.update { it.copy(isBusy = true, lastImport = null, problem = null) }
        viewModelScope.launch {
            runCatchingCancellable { repository.importIcs(text, calendarId, origin) }.fold(
                onSuccess = { result ->
                    _uiState.update {
                        if (result.total == 0) {
                            it.copy(isBusy = false, problem = InteropProblem.NothingImported)
                        } else {
                            it.copy(isBusy = false, lastImport = result)
                        }
                    }
                },
                onFailure = {
                    _uiState.update {
                        it.copy(isBusy = false, problem = InteropProblem.Unreadable)
                    }
                },
            )
        }
    }

    /**
     * Produces the export text and hands it to [onReady].
     *
     * ⚠⚠ **Two steps, and they must be in this order.** The text is generated
     * *before* the save dialog opens, so a failure to read the calendar surfaces as
     * an error rather than as a file picker that appears and then writes nothing.
     * ⚠ The callback rather than a return value is what lets the caller be a
     * `@Composable` holding a launcher.
     */
    fun exportText(onReady: (String) -> Unit) {
        val calendarId = _uiState.value.calendarId
        if (calendarId.isBlank()) return
        _uiState.update { it.copy(isBusy = true, exported = false, problem = null) }
        viewModelScope.launch {
            runCatchingCancellable { repository.exportIcs(calendarId) }.fold(
                onSuccess = { text -> onReady(text) },
                onFailure = {
                    _uiState.update {
                        it.copy(isBusy = false, problem = InteropProblem.Unreadable)
                    }
                },
            )
        }
    }

    fun exportSaved() = _uiState.update { it.copy(isBusy = false, exported = true) }

    fun exportFailed() = _uiState.update {
        it.copy(isBusy = false, problem = InteropProblem.ExportFailed)
    }
}
