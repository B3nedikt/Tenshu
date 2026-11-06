package app.tenshu.photos.ui.screens.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.data.TrashResult
import app.tenshu.photos.data.TrashedItem
import app.tenshu.photos.ui.screens.collections.CollectionsViewModel
import app.tenshu.photos.ui.screens.collections.photoCount
import app.tenshu.photos.ui.screens.common.WriteOutcome
import app.tenshu.photos.ui.screens.common.boundedCall
import app.tenshu.photos.ui.screens.common.boundedWrite
import app.tenshu.photos.ui.screens.feed.FeedError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The Trash screen (`photos-app.md` S8). */
data class TrashUiState(
    val items: List<TrashedItem> = emptyList(),
    val loading: Boolean = true,
    val error: FeedError? = null,
    /**
     * A restore, a delete or an empty is in flight (`photos-ios.md` §8.17). The shells disable every
     * action while it is: the writes are single-flight.
     */
    val writing: Boolean = false,
    /**
     * What the last write that did not simply happen says — refused, unconfirmed, or an empty that
     * left some behind. ⚠⚠ State, not a callback: a result dropped here is a trash that fails
     * silently (`photos-app.md` §22). The shells show
     * it until [TrashViewModel.dismissReport].
     */
    val report: TrashReport? = null,
    /**
     * Items whose write was abandoned UNANSWERED — it may still land (`boundedWrite`). ⚠⚠ Locked, not
     * re-offered: a restore started over a delete that can still commit races it.
     * Every action on them is ignored and the shells disable their tiles. ⚠⚠ For the life of this
     * screen — no read clears it: `trashed()` drops a row whose own read failed, so "not listed" is
     * not proof of settlement, and a later read would bring the tile back unlocked.
     * ⚠ Reopening the Trash much later is a new view model — the residual `photos-ios.md` §8.16
     * records, which only server-side ordering closes.
     */
    val uncertain: Set<String> = emptySet(),
) {
    /**
     * How many photographs Empty will delete — ⚠ not `items.size`: Empty skips the
     * [uncertain] ones, and a confirmation counting them promises irreversible deletions that never run.
     */
    val emptyCount: Int get() = items.count { it.contentId !in uncertain }

    /** Whether the item [contentId] may be restored or deleted now. */
    fun canAct(contentId: String): Boolean = !writing && contentId !in uncertain

    /** ⚠ Empty only after a read succeeded — the rule every screen in this app follows. */
    val isEmpty: Boolean get() = items.isEmpty() && !loading && error == null

    /** "Empty" is offered only with something to empty, and not while a write runs. */
    val canEmpty: Boolean get() = !writing && emptyCount > 0

    /** The sentence shown in place of a grid — the error, else the empty copy — or `null`. */
    val statusText: String?
        get() = when {
            loading -> null
            error != null -> when (error) {
                FeedError.Unreachable -> "Can't reach Tenshu Control.\nYour photos are safe — Control just isn't running."
                else -> "Something went wrong loading the trash."
            }
            isEmpty -> "Trash is empty.\nDeleted photos wait here until you empty it."
            else -> null
        }
}

/** An alert, as both shells show it. */
data class TrashReport(val title: String, val message: String)

/**
 * The Trash's sentences, one copy for both shells (`photos-ios.md` §8.17) — they were Compose's alone.
 *
 * ⚠ The confirmations say what is LOST, not "are you sure": nothing behind them can be undone, and
 * the sentence is the user's last chance to hear it.
 */
object TrashCopy {
    const val TITLE = "Trash"
    const val EMPTY_ACTION = "Empty"
    const val RESTORE_ACTION = "Restore"
    const val DELETE_ACTION = "Delete for Good"

    const val DELETE_ONE_TITLE = "Delete this photo for good?"
    const val DELETE_ONE_MESSAGE = "This can't be undone. The photo is removed from all your devices."
    const val DELETE_ONE_CONFIRM = "Delete for good"
    const val DELETE_ONE_CANCEL = "Keep"

    const val EMPTY_TITLE = "Empty the trash?"
    const val EMPTY_CONFIRM = "Delete them"
    const val EMPTY_CANCEL = "Keep them"

    /** ⚠ Counted properly: "1 photos will be deleted" was Compose's copy. */
    fun emptyMessage(count: Int): String =
        "${photos(count)} will be deleted for good. This can't be undone — " +
            "${if (count == 1) "it's" else "they're"} removed from all your devices."

    internal fun photos(count: Int) = photoCount(count)
}

class TrashViewModel(
    private val repository: PhotosRepository,
    /** How long a read or a write may take before it is given up on — `CollectionsViewModel`'s bound. */
    private val timeoutMs: Long = CollectionsViewModel.REFRESH_TIMEOUT_MS,
    /**
     * A restore or delete HAPPENED — the shell refreshes what the library shows elsewhere (the
     * Collections tab's counts and years). ⚠ A hook on the view model, not state the screen observes:
     * the write can settle after the user has left (`AlbumDetailViewModel.onEditLanded`'s lesson).
     */
    private val onChanged: () -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow(TrashUiState())
    val state: StateFlow<TrashUiState> = _state.asStateFlow()

    private var inFlight = false
    private var rerunRequested = false

    /** Counts the writes that took an item out; a read notes the count it started at. */
    private var takenCount = 0

    /**
     * Each item taken out of the trash (restored or deleted) with the [takenCount] it made — kept off
     * the grid until a read that STARTED after it lands. See [takeOut].
     */
    private val pendingTakenOut = mutableMapOf<String, Int>()

    // ⚠⚠ AFTER every field above: on K/N `viewModelScope` is `Main.immediate`, so this first read runs
    // INSIDE the constructor (`photos-ios.md` §8.16, the album crash).
    init {
        refresh()
    }

    /**
     * Re-read the trash. ⚠⚠ Single-flight with one queued rerun, bounded, and publishing only the
     * fields a read owns into the LATEST state — the album screen's rules: overlapping reads let an
     * older one win, an unbounded one holds the latch for good, and a whole-state copy puts back
     * fields a write changed meanwhile.
     */
    fun refresh() {
        if (inFlight) {
            rerunRequested = true
            return
        }
        inFlight = true
        viewModelScope.launch {
            val startedAt = takenCount
            // ⚠ Detached, over a local: a structured child stuck in a Binder read would keep this
            // job — and the view model — alive past the timeout, once per retry.
            val repo = repository
            try {
                val result = boundedCall(timeoutMs) { repo.trashed() }
                    ?: run {
                        println("TrashViewModel -> refresh timed out after ${timeoutMs}ms")
                        TrashResult.Failed("timed out")
                    }
                if (result is TrashResult.Items) {
                    // A read that started after an item was taken out already reflects it.
                    pendingTakenOut.entries.removeAll { (_, madeAt) -> madeAt <= startedAt }
                }
                _state.update { latest ->
                    when (result) {
                        is TrashResult.Items -> latest.copy(
                            items = result.items.filterNot { it.contentId in pendingTakenOut },
                            loading = false,
                            error = null,
                        )
                        TrashResult.Unreachable -> latest.copy(loading = false, error = FeedError.Unreachable)
                        is TrashResult.Failed -> latest.copy(loading = false, error = FeedError.Failed(result.message))
                    }
                }
            } finally {
                inFlight = false
                if (rerunRequested) {
                    rerunRequested = false
                    refresh()
                }
            }
        }
    }

    /**
     * Moves a photo to the trash.
     *
     * ⚠ Reversible by construction: the file moves to `/Trash` and keeps its bytes, its content id
     * and its history, so [restore] is another move rather than a re-upload.
     *
     * ⚠⚠ [onDone] reports whether it actually happened, and the caller is expected to use it.
     * Dropped, a failed trash closes the viewer as though the photograph had gone while the grid
     * still shows it. A destructive action that quietly does nothing is worse than one that fails
     * loudly; the screen not closing is what the user sees instead of a lie.
     */
    fun trash(photo: app.tenshu.photos.model.Photo, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val trashed = repository.trash(photo)
            refresh()
            onDone(trashed)
        }
    }

    /** Puts one photo back where it came from. Its outcome, unless it simply happened, is [TrashUiState.report]. */
    fun restore(contentId: String) {
        // ⚠ Locals, never `this`, in every closure `boundedWrite` runs detached (§8.16 note 15).
        val repo = repository
        one(contentId, TrashWrite.Restore) { repo.restore(contentId) }
    }

    /**
     * Deletes one photo for good.
     *
     * ⚠⚠ The only call in this app that destroys a photograph. Every caller reaches it through a
     * confirmation the user has to read — see the screens — because there is no undo behind it.
     */
    fun deleteForever(contentId: String) {
        val repo = repository
        one(contentId, TrashWrite.Delete) { repo.deleteForever(contentId) }
    }

    /**
     * Empties the trash — the items on screen WHEN ASKED, one write each. ⚠ Same irreversibility, once
     * per item. ⚠⚠ Every item's answer is counted, and anything short of all of them is reported:
     * "emptied" over a trash still holding photographs is the §22 shape again.
     */
    fun emptyTrash() {
        val current = _state.value
        if (!current.canEmpty) return
        _state.update { it.copy(writing = true, report = null) }
        // ⚠ Never an uncertain item: its own write may still land (see [TrashUiState.uncertain]).
        val items = current.items.filterNot { it.contentId in current.uncertain }
        val repo = repository
        viewModelScope.launch {
            var refused = 0
            var unconfirmed = 0
            var tried = 0
            val waiting = oneReport(TrashWrite.Delete, WriteOutcome.Unconfirmed)
            try {
                for (item in items) {
                    val id = item.contentId
                    tried++
                    // ⚠ Said at the first bound, as a single write says it: dropped, a stalled
                    // Empty would hold the screen — Back disabled — through the whole settle window
                    // with nothing on it.
                    when (write(id, onUnconfirmed = { _state.update { it.copy(report = waiting) } }) { repo.deleteForever(id) }) {
                        WriteOutcome.Done -> {
                            takeOut(item.contentId)
                            // A late success settles the "still waiting" it had to say; Empty goes on.
                            _state.update { if (it.report == waiting) it.copy(report = null) else it }
                        }
                        WriteOutcome.Refused -> refused++
                        WriteOutcome.Unconfirmed -> {
                            unconfirmed++
                            _state.update { it.copy(uncertain = it.uncertain + item.contentId) }
                            // ⚠⚠ Stop: the transport is not answering, and each further
                            // item would hold the user here — Back disabled — for two more windows, and
                            // leave one more write that may still commit.
                            break
                        }
                    }
                }
            } finally {
                _state.update { it.copy(writing = false) }
            }
            refresh()
            // ⚠ Even when every delete was refused: a NO may have half-happened (see [write]).
            onChanged()
            val notTried = items.size - tried
            if (refused + unconfirmed + notTried > 0) {
                _state.update { it.copy(report = emptyReport(items.size, refused, unconfirmed, notTried)) }
            }
        }
    }

    /** Clears [TrashUiState.report] once the shell has shown it. */
    fun dismissReport() {
        _state.update { it.copy(report = null) }
    }

    private enum class TrashWrite { Restore, Delete }

    private fun one(contentId: String, kind: TrashWrite, write: suspend () -> Boolean) {
        val current = _state.value
        // ⚠ Single-flight; and only for an item still on screen — a second tap on one already taken
        // out would write nothing and report a refusal of a write that worked.
        if (!current.canAct(contentId) || current.items.none { it.contentId == contentId }) return
        _state.update { it.copy(writing = true, report = null) }
        viewModelScope.launch {
            // ⚠ The lock is held until the write SETTLES (`boundedWrite`): "still waiting" is said at
            // the first bound, but nothing else runs beside a write that can still land.
            val waiting = oneReport(kind, WriteOutcome.Unconfirmed)
            val outcome = try {
                write(contentId, onUnconfirmed = { _state.update { it.copy(report = waiting) } }, write)
            } finally {
                _state.update { it.copy(writing = false) }
            }
            when (outcome) {
                WriteOutcome.Done -> {
                    takeOut(contentId)
                    // ⚠ A late success settles the "still waiting" it had to say — an
                    // uncertainty alert left up over a write known to have happened.
                    _state.update { if (it.report == waiting) it.copy(report = null) else it }
                    refresh()
                    onChanged()
                }
                // ⚠ A NO may have half-happened (`restore` moves then fails to clear its record;
                // `deleteForever` clears it even when the file delete fails) — so the report claims
                // nothing about where the photo is, and the grid and Collections re-read the store.
                WriteOutcome.Refused -> {
                    _state.update { it.copy(report = oneReport(kind, outcome)) }
                    refresh()
                    onChanged()
                }
                // Abandoned: locked until a read shows it settled, and the store says what happened.
                WriteOutcome.Unconfirmed -> {
                    _state.update { it.copy(uncertain = it.uncertain + contentId) }
                    refresh()
                    // ⚠ It may have committed — Collections re-reads too, as after Done and a NO.
                    onChanged()
                }
            }
        }
    }

    /**
     * One bounded write (`boundedWrite`).
     *
     * ⚠⚠ **The trash listing is never evidence of a write**. `trashed()` drops a row
     * whose own read failed, and `deleteForever` clears the record even when the file delete fails —
     * so "not listed" can mean a transient read failure, or a delete that did not delete. Used
     * as `alreadyDone` it could skip a write; used to turn a NO into a yes it could hide a failed
     * delete. Neither: nothing is skipped (`alreadyDone` is always no — the item is on screen, so there
     * is something to do), and only the write's own answer counts.
     */
    private suspend fun write(
        @Suppress("UNUSED_PARAMETER") contentId: String,
        onUnconfirmed: () -> Unit,
        write: suspend () -> Boolean,
    ): WriteOutcome =
        // ⚠ On [viewModelScope], never inside `coroutineScope { }` (§8.16 note 12).
        viewModelScope.boundedWrite(
            timeoutMs = timeoutMs,
            settleMs = timeoutMs,
            write = write,
            alreadyDone = { false },
            onUnconfirmed = onUnconfirmed,
        )

    /**
     * Takes an item off the grid AT ONCE, not when the re-read lands — the album removal's rule:
     * the actions unlock as soon as the write answers, and a tile still showing
     * could be acted on again. ⚠ Kept off until a read STARTED after this lands: one already in
     * flight snapshotted the trash with it.
     */
    private fun takeOut(contentId: String) {
        pendingTakenOut[contentId] = ++takenCount
        _state.update { latest -> latest.copy(items = latest.items.filterNot { it.contentId == contentId }) }
    }

    private fun oneReport(kind: TrashWrite, outcome: WriteOutcome): TrashReport = when (outcome) {
        WriteOutcome.Unconfirmed -> TrashReport(
            "Still Waiting for Tenshu Control",
            // ⚠ No promise of an update (§8.16 note 16): an abandoned write is watched by nothing.
            "Tenshu Control hasn't answered yet. The change may still be made — open the trash again later to check.",
        )
        else -> when (kind) {
            // ⚠ Claims nothing about where the photo is: a refusal may have half-happened, and the
            // trash on screen — re-read right after — is what says.
            TrashWrite.Restore -> TrashReport(
                "Couldn't Restore the Photo",
                "Tenshu Control reported a problem. If the photo is still in the trash, make sure Tenshu Control is running, then try again.",
            )
            TrashWrite.Delete -> TrashReport(
                "Couldn't Delete the Photo",
                "Tenshu Control reported a problem. If the photo is still in the trash, make sure Tenshu Control is running, then try again.",
            )
        }
    }

    /**
     * ⚠⚠ OUTCOMES, never a location: a refused delete may have half-happened and an
     * unanswered one may yet land, so "still in the trash" is a claim the refreshed grid could
     * contradict — the single-item report's rule. The grid says where the photographs are.
     */
    private fun emptyReport(total: Int, refused: Int, unconfirmed: Int, notTried: Int): TrashReport {
        val parts = buildList {
            if (refused > 0) add("${TrashCopy.photos(refused)} of $total couldn't be deleted.")
            if (unconfirmed > 0) {
                add(
                    "Tenshu Control didn't answer for ${TrashCopy.photos(unconfirmed)} — " +
                        "${if (unconfirmed == 1) "it" else "they"} may still go; open the trash again later to check.",
                )
            }
            if (notTried > 0) {
                add("${TrashCopy.photos(notTried)} ${if (notTried == 1) "wasn't" else "weren't"} tried, so as not to wait on it.")
            }
        }
        return TrashReport(
            "The Trash Wasn't Emptied",
            parts.joinToString(" ") + " Make sure Tenshu Control is running, then try again.",
        )
    }
}
