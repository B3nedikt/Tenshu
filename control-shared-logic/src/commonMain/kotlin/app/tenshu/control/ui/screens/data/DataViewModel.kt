package app.tenshu.control.ui.screens.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.control.data.RealmDataClient
import app.tenshu.control.data.RealmDataResetOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DataUiState(
    /**
     * Realm-data type ids that hold data ([RealmDataClient.hasData] not `false`), ordered for
     * display (by [realmDataPresentation] `sortOrder`).
     */
    val typeIds: List<String> = emptyList(),
    /** `true` until the first presence check finishes — an empty [typeIds] means nothing yet. */
    val loading: Boolean = true,
    /**
     * The subset of [typeIds] a whole-type reset can actually reach
     * ([RealmDataClient.isResettable]). Derived from the schema, not a policy list — a type
     * outside it renders its card with no *Delete all…* action rather than a button that
     * would delete nothing.
     */
    val resettableTypeIds: Set<String> = emptySet(),
    /** Type id whose destructive confirmation is open, or `null`. */
    val pendingResetTypeId: String? = null,
    /** Type id whose reset is running — the card's action is disabled while set. */
    val resettingTypeId: String? = null,
    /** The last finished reset, for the result message. Cleared by [DataViewModel.onResetResultShown]. */
    val resetResult: DataResetResult? = null,
)

/**
 * One finished reset: [outcome] plus the [typeId] it applied to, because every message names
 * the data ("Deleted 12 workouts", "Couldn't delete your Workouts") and the outcome alone
 * cannot say which card it came from.
 */
data class DataResetResult(
    val typeId: String,
    val outcome: RealmDataResetOutcome,
)

/**
 * Data section ViewModel (`control-ia.md` §5 / `docs/proposals/core-realm-data.md`).
 * Drives a **generic** card list — one card per realm-data type, with no hard-coded type list:
 * a future type appears automatically once it declares the `RealmData` marker. Ordering is the
 * consumer's concern, applied here via [realmDataPresentation] (`sortOrder`).
 *
 * ⚠ Only types that **hold data** get a card. Control registers every first-party service's
 * schema whether or not its app is installed, so the bare schema walk would list *Routines* and
 * *Runs* to a user who only has Drive. The enumeration is a synchronous schema walk; the
 * presence check ([RealmDataClient.hasData]) is the async part, re-run by [refresh] when the
 * screen is shown and after every reset.
 *
 * It also owns the destructive verb (`docs/proposals/control-data-reset.md` R1): click →
 * confirm → `DeleteRealmData` → result message, mirroring `SharesViewModel`'s revoke flow.
 *
 * ⚠ There is deliberately **no per-device choice** (§6 D3) and therefore no scope state to
 * hold: a device-local delete would be undone by the next pull. The reset is realm-wide, and
 * the confirmation copy in the screen says so.
 *
 * ⚠ No undo (§10 Q2). Tombstones converge, so undoing would mean re-emitting every row; the
 * confirmation *is* the guard, which is why the action never fires straight from a click.
 */
class DataViewModel(
    private val realmDataClient: RealmDataClient,
) : ViewModel() {

    /** Every declared realm-data type, in display order — the candidates [refresh] filters. */
    private val declaredTypeIds: List<String> = realmDataClient.realmDataTypeIds()
        .sortedBy { realmDataSortOrder(it) }

    private val _uiState = MutableStateFlow(
        DataUiState(
            resettableTypeIds = declaredTypeIds
                .filter { realmDataClient.isResettable(it) }
                .toSet(),
        ),
    )
    val uiState: StateFlow<DataUiState> = _uiState.asStateFlow()

    /** The running reset, so [onStopResetClicked] can cancel it. */
    private var resetJob: Job? = null

    /** The running presence check, so a newer [refresh] replaces rather than races it. */
    private var refreshJob: Job? = null

    // ⚠ Last, after every field: on K/N `viewModelScope` is Main.immediate, so the launch in
    // `refresh` runs inline and would read fields declared below it as null.
    init {
        refresh()
    }

    /**
     * Re-check which types hold data. Cheap (one array count per type), so the screen calls it
     * on every visit — data synced or written since the last visit shows up then.
     */
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val present = declaredTypeIds.filter { realmDataClient.hasData(it) != false }
            _uiState.update { state ->
                // Never drop the card the user is acting on mid-flow — its dialog or progress
                // would lose its anchor. A finished reset refreshes again anyway.
                val busy = setOfNotNull(state.pendingResetTypeId, state.resettingTypeId)
                state.copy(
                    typeIds = declaredTypeIds.filter { it in present || it in busy },
                    loading = false,
                )
            }
        }
    }

    /** Arm the destructive confirmation for [typeId]. Never deletes on its own. */
    fun onDeleteAllClicked(typeId: String) {
        // Guard the un-resettable case here too, not only in the UI: a stale card that
        // slipped through would otherwise open a dialog promising something the op can't do.
        if (typeId !in _uiState.value.resettableTypeIds) return
        _uiState.update { it.copy(pendingResetTypeId = typeId) }
    }

    /** Dismiss the confirmation without deleting anything. */
    fun onDeleteAllDismissed() {
        _uiState.update { it.copy(pendingResetTypeId = null) }
    }

    /** Confirm: run the realm-wide reset for the armed type and keep its outcome to report. */
    fun onDeleteAllConfirmed() {
        val typeId = _uiState.value.pendingResetTypeId ?: return
        _uiState.update { it.copy(pendingResetTypeId = null, resettingTypeId = typeId) }
        val job = viewModelScope.launch {
            // The outcome is always kept — a dropped Failure would leave the user believing
            // data is gone when it is not.
            val outcome = realmDataClient.deleteAll(typeId)
            _uiState.update {
                it.copy(
                    resettingTypeId = null,
                    resetResult = DataResetResult(typeId, outcome),
                )
            }
        }
        resetJob = job
        // A cancelled reset never reaches the block above, so the terminal state is
        // reported from the completion handler instead — otherwise *Stop* would leave the
        // card spinning forever. `update` does not suspend, so this is safe here.
        job.invokeOnCompletion { cause ->
            resetJob = null
            if (cause != null) {
                val outcome =
                    if (cause is CancellationException) RealmDataResetOutcome.Stopped
                    else RealmDataResetOutcome.Failed
                _uiState.update {
                    it.copy(
                        resettingTypeId = null,
                        resetResult = DataResetResult(typeId, outcome),
                    )
                }
            }
            // A whole-type reset usually empties the type, so its card should go — and after a
            // stop or failure part of it may be gone too. After the update above, so the card is
            // no longer held as busy when the check lands.
            refresh()
        }
    }

    /**
     * Stop a running reset. What is already deleted stays deleted and converges — the op
     * tombstones each chunk before removing it, so stopping costs progress, never
     * consistency. Re-running finishes the job.
     */
    fun onStopResetClicked() {
        resetJob?.cancel()
    }

    /** The result message has been shown (snackbar dismissed) — stop re-showing it. */
    fun onResetResultShown() {
        _uiState.update { it.copy(resetResult = null) }
    }
}
