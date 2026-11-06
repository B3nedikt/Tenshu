package app.tenshu.photos.ui.screens.duplicates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.photos.data.DuplicateGroup
import app.tenshu.photos.data.DuplicatesResult
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.collections.CollectionsViewModel
import app.tenshu.photos.ui.screens.common.WriteOutcome
import app.tenshu.photos.ui.screens.common.boundedCall
import app.tenshu.photos.ui.screens.common.boundedWrite
import app.tenshu.photos.ui.screens.feed.FeedError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.tenshu.core.common.util.runCatchingCancellable

/**
 * The Duplicates review surface (`photos-app.md` S4 / PH-D2).
 *
 * ⚠⚠ **Nothing here happens without a tap.** No group is merged, resolved or cleaned up on load,
 * and there is no "fix all". Every competitor that ships a duplicate finder — Apple, Amazon,
 * Synology, digiKam — asks first, and the reason is that the user cannot tell a silent merge from
 * a loss. This screen therefore only ever *shows* until acted on.
 *
 * ⚠⚠ Every rule and sentence of the screen is here, for both shells (`photos-ios.md` §8.18, PIO-D2):
 * a copy's identity on this screen is its PATH — two copies may not be told apart by anything else.
 */
data class DuplicatesUiState(
    val groups: List<DuplicateGroup> = emptyList(),
    /** How many files the walk covered — the denominator behind "N sets found". */
    val filesScanned: Int = 0,
    /** The first read, or a retry the user asked for. ⚠ A re-read after a write leaves the list up. */
    val loading: Boolean = true,
    val error: FeedError? = null,
    /**
     * Whether *"delete the rest"* destroys the copies instead of trashing them (PH-D4's checkbox).
     *
     * ⚠ Defaults to **off**: the reversible action is the default one, and the irreversible one is
     * a thing the user turns on for the case that motivates it — being out of quota, where a move
     * to the trash frees nothing at all.
     */
    val deletePermanently: Boolean = false,
    /**
     * A keep-only is in flight. The shells offer nothing while it is — no tile, no switch, no way
     * out: the writes are single-flight (`photos-ios.md` §8.17).
     */
    val writing: Boolean = false,
    /**
     * What the last keep-only that did not simply happen says. ⚠⚠ State, not a callback: a
     * result dropped here makes a refused trash or delete look exactly like one that worked
     * (`photos-app.md` §22). The shells show it until [DuplicatesViewModel.dismissReport].
     */
    val report: DuplicatesReport? = null,
    /**
     * The PATHS of copies whose write was sent and did NOT answer yes — refused, or abandoned
     * unanswered. ⚠⚠ Where such a copy is, is not known: `trash` answers NO after
     * it has MOVED the file when recording the move fails, a delete can be refused having deleted, and
     * an unanswered write may still land. ⚠⚠ Locked for the life of this screen: never written again,
     * and ⚠⚠ **never offered as the copy to keep** — it may already be gone, and removing the others
     * would then leave none. ⚠ No read unlocks it: a NO from a dropped transport can come before the
     * write commits, so a read that still lists the copy proves nothing (`photos-ios.md` §8.18).
     */
    val uncertain: Set<String> = emptySet(),
) {
    /** ⚠ Empty only after a read succeeded — "could not look" is never "no duplicates" (§40). */
    val isEmpty: Boolean get() = groups.isEmpty() && !loading && error == null

    /** What resolving every group would free, if each kept one copy. */
    val reclaimableBytes: Long get() = groups.sumOf { it.reclaimableBytes }

    /**
     * What stands in place of the list — the error, with a retry, or the empty sentence — or `null`.
     *
     * ⚠⚠ An error replaces the list even when groups are still held: they are what an earlier read
     * saw, and a keep-only over a list that could not be re-read acts on copies that may be gone.
     */
    val status: DuplicatesStatus?
        get() = when {
            loading -> null
            error != null -> DuplicatesStatus(
                text = if (error == FeedError.Unreachable) DuplicatesCopy.UNREACHABLE else DuplicatesCopy.COULD_NOT_LOOK,
                canRetry = true,
            )
            isEmpty -> DuplicatesStatus(DuplicatesCopy.empty(filesScanned), canRetry = false)
            else -> null
        }

    /** "3 sets of identical photos, out of 812 files." */
    val summary: String get() = DuplicatesCopy.summary(groups.size, filesScanned)

    /** The switch waits on a write too: the one running took the mode it was confirmed with. */
    val canSetDeletePermanently: Boolean get() = !writing

    /** ⚠ No leaving while a write runs (`photos-ios.md` §8.16 note 18, §8.17 notes 4 and 6). */
    val canLeave: Boolean get() = !writing

    /** The copies a keep-only of [keep] would act on — every other one that is not locked. */
    private fun others(group: DuplicateGroup, keep: Photo): List<Photo> =
        group.photos.filter { it.path != keep.path && it.path !in uncertain }

    /**
     * Whether [keep] may be chosen as the copy to keep. ⚠ Not while a write runs, not over a list
     * that could not be re-read, never a locked copy, and only with something left to remove.
     */
    fun canKeep(group: DuplicateGroup, keep: Photo): Boolean =
        !writing && error == null && keep.path !in uncertain &&
            group.photos.any { it.path == keep.path } && others(group, keep).isNotEmpty()

    /** The line above a set's tiles. ⚠ It only says "tap" when a tap does something. */
    fun heading(group: DuplicateGroup): String =
        if (group.photos.any { it.path in uncertain } && group.photos.none { canKeepIgnoringWrite(group, it) }) {
            DuplicatesCopy.lockedHeading(group.photos.size)
        } else {
            DuplicatesCopy.heading(group.photos.size)
        }

    private fun canKeepIgnoringWrite(group: DuplicateGroup, keep: Photo): Boolean =
        keep.path !in uncertain && others(group, keep).isNotEmpty()

    /**
     * What the user must read before [keep] is kept — or `null` when it may not be chosen.
     *
     * ⚠⚠ It carries what it promised: the copies it counted and the mode it named are the ones
     * [DuplicatesViewModel.keepOnly] acts with, whatever the switch or the list say by then.
     */
    fun confirmation(group: DuplicateGroup, keep: Photo): DuplicatesConfirmation? {
        if (!canKeep(group, keep)) return null
        val others = others(group, keep)
        return DuplicatesConfirmation(
            contentHash = group.contentHash,
            keepPath = keep.path,
            otherPaths = others.map { it.path },
            permanently = deletePermanently,
            title = DuplicatesCopy.confirmTitle(deletePermanently),
            keeping = DuplicatesCopy.keeping(keep.name),
            consequence = DuplicatesCopy.consequence(others.size, deletePermanently),
            confirm = DuplicatesCopy.confirmAction(deletePermanently),
            cancel = DuplicatesCopy.CANCEL,
        )
    }
}

/** The sentence shown in place of the list, and whether a retry goes with it. */
data class DuplicatesStatus(val text: String, val canRetry: Boolean)

/** An alert, as both shells show it. */
data class DuplicatesReport(val title: String, val message: String)

/**
 * A keep-only, as the user confirmed it: which copy stays, which go, and how.
 *
 * ⚠ The dialog names the SURVIVOR and says what happens to the rest — never "are you sure".
 */
data class DuplicatesConfirmation(
    val contentHash: String,
    val keepPath: String,
    /** The copies [consequence] counted — exactly these are acted on, or nothing is. */
    val otherPaths: List<String>,
    /** Destroy rather than trash. ⚠ The shells mark the confirming button destructive only then. */
    val permanently: Boolean,
    val title: String,
    val keeping: String,
    val consequence: String,
    val confirm: String,
    val cancel: String,
)

/**
 * Duplicates' sentences, one copy for both shells (`photos-ios.md` §8.18) — they were Compose's alone.
 *
 * ⚠⚠ The two modes never share a word that matters: "move to the trash … you can restore" against
 * "delete for good … can't be undone", in the title, the consequence and the button.
 */
object DuplicatesCopy {
    const val TITLE = "Duplicates"
    const val RETRY_ACTION = "Try Again"
    const val CANCEL = "Cancel"

    /** ⚠ Stated, not left to be discovered by noticing what is missing. */
    const val EXACT_ONLY = "Only exact copies are listed. An edited or re-encoded photo " +
        "is a different file and won't show up here."

    const val PERMANENT_SWITCH = "Delete permanently"

    /** ⚠ What each setting COSTS: the reason to turn it on is running out of space. */
    fun modeDescription(permanently: Boolean): String =
        if (permanently) "Extra copies are removed from all your devices right away."
        else "Extra copies go to the trash, where you can still get them back."

    const val UNREACHABLE = "Can't reach Tenshu Control.\nYour photos are safe — Control just isn't running."

    /** ⚠⚠ "Could not look" is not "no duplicates" (`photos-app.md` §40), and it says so. */
    const val COULD_NOT_LOOK = "Couldn't look for duplicates.\nNothing was compared — that doesn't mean there are none."

    fun empty(filesScanned: Int): String =
        "No duplicates.\nWe compared ${files(filesScanned)}. " +
            "Edited or re-encoded copies aren't counted — they're different files."

    fun summary(sets: Int, filesScanned: Int): String =
        "${if (sets == 1) "1 set" else "$sets sets"} of identical photos, out of ${files(filesScanned)}."

    /** ⚠ A tap means *keep this one* — the destructive reading is the mistake this screen cannot afford. */
    fun heading(copies: Int): String = "$copies identical copies — tap the one to keep"

    /** The line under a set's heading: the name its copies share — the oldest one's, which leads the set. */
    fun caption(group: DuplicateGroup): String = group.photos.firstOrNull()?.name.orEmpty()

    /** Over a set none of whose copies can be chosen: a write on it was refused or went unanswered. */
    fun lockedHeading(copies: Int): String =
        "$copies identical copies — a copy here couldn't be removed cleanly; open Duplicates again later"

    /** The accessibility hint on a tile. */
    const val KEEP_HINT = "Keep this copy"

    fun confirmTitle(permanently: Boolean): String =
        if (permanently) "Delete the other copies for good?" else "Move the other copies to the trash?"

    fun keeping(name: String): String = "Keeping $name."

    fun consequence(others: Int, permanently: Boolean): String =
        if (permanently) {
            "${otherCopies(others)} will be removed from all your devices. This can't be undone."
        } else {
            "${otherCopies(others)} will move to the trash. You can restore ${if (others == 1) "it" else "them"} from there."
        }

    fun confirmAction(permanently: Boolean): String = if (permanently) "Delete for Good" else "Move to Trash"

    /** Said at a write's first bound. ⚠ No promise of an update: an abandoned write is watched by nothing. */
    val STILL_WAITING = DuplicatesReport(
        "Still Waiting for Tenshu Control",
        "Tenshu Control hasn't answered yet. The change may still be made — open Duplicates again later to check.",
    )

    /**
     * The store did not confirm that the copy to keep is still there. ⚠⚠ Nothing is removed without
     * that: the others might be the last copies the library holds (`photos-ios.md` §8.18).
     */
    fun survivorUnconfirmed(name: String) = DuplicatesReport(
        "Couldn't Check the Copy to Keep",
        "Tenshu couldn't confirm that $name is still in your library, so nothing was removed. " +
            "Make sure Tenshu Control is running, then look again.",
    )

    /** The set was not what the confirmation described any more. Nothing was written. */
    val CHANGED = DuplicatesReport(
        "These Copies Changed",
        "The set isn't what it was when you chose. Nothing was removed — look again, then pick the copy to keep.",
    )

    /**
     * What a keep-only that fell short says. ⚠⚠ OUTCOMES, never where a copy is (`photos-ios.md`
     * §8.17 note 13): a refused write may have half-happened and an unanswered one may yet land.
     * ⚠ [unconfirmed] alone is never worded as a refusal.
     */
    fun keepReport(
        total: Int,
        refused: Int,
        unconfirmed: Int,
        notTried: Int,
        unfileable: Int,
        permanently: Boolean,
    ): DuplicatesReport {
        val parts = buildList {
            if (refused > 0) {
                add("${copies(refused)} of $total couldn't be ${if (permanently) "deleted" else "moved to the trash"}.")
            }
            if (unfileable > 0) {
                add(
                    "${copies(unfileable)} can't go to the trash — Tenshu has no content record for " +
                        "${if (unfileable == 1) "it" else "them"}, so only \"$PERMANENT_SWITCH\" can remove " +
                        "${if (unfileable == 1) "it" else "them"}.",
                )
            }
            if (unconfirmed > 0) {
                add(
                    "Tenshu Control didn't answer for ${copies(unconfirmed)} — " +
                        "${if (unconfirmed == 1) "it" else "they"} may still go; open Duplicates again later to check.",
                )
            }
            if (notTried > 0) {
                add(
                    "${copies(notTried)} ${if (notTried == 1) "wasn't" else "weren't"} tried, " +
                        "so as not to wait on ${if (notTried == 1) "it" else "them"}.",
                )
            }
        }
        val title = when {
            refused + unfileable == 0 -> STILL_WAITING.title
            permanently -> "Couldn't Delete Every Copy"
            else -> "Couldn't Move Every Copy to the Trash"
        }
        // ⚠ "Open Duplicates again", not "try again": these copies stay locked on this screen.
        val advice = if (refused + unconfirmed > 0) " Make sure Tenshu Control is running, then open Duplicates again." else ""
        return DuplicatesReport(title, parts.joinToString(" ") + advice)
    }

    private fun files(count: Int) = if (count == 1) "1 file" else "$count files"

    private fun copies(count: Int) = if (count == 1) "1 copy" else "$count copies"

    private fun otherCopies(count: Int) = if (count == 1) "1 other copy" else "$count other copies"
}

class DuplicatesViewModel(
    private val repository: PhotosRepository,
    /** How long a read or a write may take before it is given up on — `CollectionsViewModel`'s bound. */
    private val timeoutMs: Long = CollectionsViewModel.REFRESH_TIMEOUT_MS,
    /**
     * A keep-only wrote something — the shell refreshes what the library shows elsewhere (the
     * Collections tab's counts and years). ⚠ A hook on the view model, not state the screen observes:
     * the write can settle after the user has left (`TrashViewModel.onChanged`).
     */
    private val onChanged: () -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow(DuplicatesUiState())
    val state: StateFlow<DuplicatesUiState> = _state.asStateFlow()

    private var inFlight = false
    private var rerunRequested = false

    /** Counts the writes that took a copy out; a read notes the count it started at. */
    private var takenCount = 0

    /**
     * Each copy trashed or deleted, by path, with the [takenCount] it made — kept off the list until a
     * read that STARTED after it lands. See [takeOut].
     */
    private val pendingTakenOut = mutableMapOf<String, Int>()

    // ⚠⚠ AFTER every field above: on K/N `viewModelScope` is `Main.immediate`, so this first read runs
    // INSIDE the constructor (`photos-ios.md` §8.16, the album crash).
    init {
        refresh()
    }

    /**
     * Re-read the duplicates. ⚠⚠ Single-flight with one queued rerun, bounded, and publishing only the
     * fields a read owns into the LATEST state — the Trash's rules (`TrashViewModel.refresh`). It used
     * to be none of them: a read that never answered left the spinner up for good.
     */
    fun refresh() {
        if (inFlight) {
            rerunRequested = true
            return
        }
        inFlight = true
        viewModelScope.launch {
            val startedAt = takenCount
            // ⚠ Detached, over a local: a structured child stuck in a Binder read keeps this job — and
            // the view model — alive past the timeout, once per retry.
            val repo = repository
            try {
                // ⚠⚠ A read that THROWS is "could not look" too: escaping this launch it
                // is an uncaught exception — a crash — where a sentence and a retry belong.
                // ⚠ `runCatchingCancellable`, never `runCatching`: a cleared screen's cancellation is not a failure.
                val result = runCatchingCancellable { boundedCall(timeoutMs) { repo.duplicates() } }
                    .getOrElse {
                        println("DuplicatesViewModel -> refresh threw: $it")
                        DuplicatesResult.Failed(it.message ?: "the read failed")
                    }
                    ?: run {
                        println("DuplicatesViewModel -> refresh timed out after ${timeoutMs}ms")
                        // ⚠ "Could not look", never "no duplicates".
                        DuplicatesResult.Failed("timed out")
                    }
                if (result is DuplicatesResult.Groups) {
                    // A read that started after a copy was taken out already reflects it.
                    pendingTakenOut.entries.removeAll { (_, madeAt) -> madeAt <= startedAt }
                }
                _state.update { latest ->
                    when (result) {
                        is DuplicatesResult.Groups -> latest.copy(
                            groups = result.groups.without(pendingTakenOut.keys),
                            filesScanned = result.filesScanned,
                            loading = false,
                            error = null,
                        )
                        DuplicatesResult.Unreachable -> latest.copy(loading = false, error = FeedError.Unreachable)
                        is DuplicatesResult.Failed -> latest.copy(loading = false, error = FeedError.Failed(result.message))
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

    /** The error screen's *Try Again*: the spinner comes back, and the read runs again. */
    fun retry() {
        _state.update { if (it.error == null) it else it.copy(loading = true, error = null) }
        refresh()
    }

    /** ⚠ Not while a write runs: the shells disable the switch, and this ignores a stray call. */
    fun setDeletePermanently(enabled: Boolean) {
        _state.update { if (it.canSetDeletePermanently) it.copy(deletePermanently = enabled) else it }
    }

    /** Clears [DuplicatesUiState.report] once the shell has shown it. */
    fun dismissReport() {
        _state.update { it.copy(report = null) }
    }

    /**
     * Keep one copy and act on the others, exactly as [confirmation] said — one write each.
     *
     * ⚠⚠ The kept copy is **never** written, by construction: the loop runs over the copies the
     * confirmation named, and the kept path is not among them. A partial resolution leaves the user
     * with fewer duplicates; a write that reached the survivor would leave them with none.
     *
     * ⚠⚠ Every write's answer is counted, and anything short of all of them is reported
     * ([DuplicatesUiState.report]). The loop STOPS at the first unanswered write: the transport is
     * not answering, and each further copy would hold the user here for two more windows and leave
     * one more write that may still commit.
     *
     * ⚠⚠ **Nothing is removed until the store confirms the survivor** — a bounded read, now, that a
     * file with this content is at the kept path ([PhotosRepository.holdsContent]). The list is what a
     * read saw earlier; another device, or a write an earlier screen let go of, can have taken the
     * survivor since, and the others would then be the last copies the library holds.
     *
     * ⚠⚠ A copy whose write did not answer YES is locked ([DuplicatesUiState.uncertain]) — a NO too,
     * not only silence: `trash` can refuse having moved the file.
     *
     * ⚠ A copy with no content id cannot be trashed (the trash record is keyed on it) — it is not
     * sent, and the report says why. The permanent path deletes by path and has no such requirement.
     */
    fun keepOnly(confirmation: DuplicatesConfirmation) {
        val current = _state.value
        // Single-flight. ⚠ The shells offer nothing while a write runs, so this drops nothing a user chose.
        if (current.writing) return
        val group = current.groups.firstOrNull { it.contentHash == confirmation.contentHash }
        val targets = group?.photos.orEmpty()
            .filter { it.path in confirmation.otherPaths && it.path !in current.uncertain }
        // ⚠⚠ What was confirmed, or nothing: a re-read can land while the dialog is open. If the
        // survivor is no longer in the set — or is now locked — removing the others could leave no
        // copy at all; if the others are not the ones counted, the dialog promised something else.
        val stillAsConfirmed = current.error == null && group != null &&
            group.photos.any { it.path == confirmation.keepPath } &&
            confirmation.keepPath !in current.uncertain &&
            targets.size == confirmation.otherPaths.size && targets.isNotEmpty()
        if (!stillAsConfirmed) {
            _state.update { it.copy(report = DuplicatesCopy.CHANGED) }
            return
        }
        _state.update { it.copy(writing = true, report = null) }
        // ⚠ Locals, never `this`, in every closure `boundedWrite` runs detached (§8.16 note 15).
        val repo = repository
        val permanently = confirmation.permanently
        val keepPath = confirmation.keepPath
        val contentHash = confirmation.contentHash
        val keepName = keepPath.substringAfterLast('/')
        val bound = timeoutMs
        viewModelScope.launch {
            var refused = 0
            var unconfirmed = 0
            var unfileable = 0
            var tried = 0
            var sent = 0
            val waiting = DuplicatesCopy.STILL_WAITING
            // ⚠⚠ ONE `try` over every repository call this makes, and the lock comes off in its
            // `finally`: a call that THROWS — Control gone mid-call, a failed schema lookup — would
            // otherwise leave `writing` set for good, Back disabled, or escape the launch as a
            // crash.
            try {
                // ⚠⚠ The last line of defence: no positive answer from the store, nothing removed. Bounded
                // and detached like every read here; unanswered — or thrown — counts as not confirmed.
                // ⚠ `runCatchingCancellable`, never `runCatching`: cancelled, this must STOP, not report.
                val survivorHeld = runCatchingCancellable { boundedCall(bound) { repo.holdsContent(keepPath, contentHash) } }
                    .getOrElse {
                        println("DuplicatesViewModel -> the survivor check threw: $it")
                        null
                    } == true
                if (!survivorHeld) {
                    _state.update { it.copy(writing = false, report = DuplicatesCopy.survivorUnconfirmed(keepName)) }
                    refresh()
                    return@launch
                }
                for (copy in targets) {
                    tried++
                    if (!permanently && copy.contentId.isEmpty()) {
                        unfileable++
                        continue
                    }
                    sent++
                    // ⚠ "Still waiting" is said at the first bound, inside the loop too (§8.17 note 15).
                    // ⚠⚠ A write that THROWS is a NO whose copy may be anywhere — Refused, so locked and
                    // counted, and the loop goes on. ⚠ Cancellation is rethrown: swallowed, a cleared
                    // screen's loop would count every remaining copy as refused and report it.
                    val outcome = runCatchingCancellable {
                        write(onUnconfirmed = { _state.update { it.copy(report = waiting) } }) {
                            if (permanently) repo.deletePermanently(copy) else repo.trash(copy)
                        }
                    }.getOrElse {
                        println("DuplicatesViewModel -> a keep-only write threw: $it")
                        WriteOutcome.Refused
                    }
                    when (outcome) {
                        WriteOutcome.Done -> {
                            takeOut(copy.path)
                            // A late success settles the "still waiting" it had to say; the loop goes on.
                            _state.update { if (it.report == waiting) it.copy(report = null) else it }
                        }
                        // ⚠⚠ A NO is not "still there": locked, like silence — but
                        // the loop goes on, the transport is answering.
                        WriteOutcome.Refused -> {
                            refused++
                            _state.update { it.copy(uncertain = it.uncertain + copy.path) }
                        }
                        WriteOutcome.Unconfirmed -> {
                            unconfirmed++
                            _state.update { it.copy(uncertain = it.uncertain + copy.path) }
                            break
                        }
                    }
                }
            } finally {
                _state.update { it.copy(writing = false) }
            }
            refresh()
            // ⚠ After a NO and an unanswered write too — either may have happened (`trash` moves the
            // file, then can fail to record it). Only a keep-only that sent nothing changed nothing.
            if (sent > 0) onChanged()
            val notTried = targets.size - tried
            if (refused + unconfirmed + notTried + unfileable > 0) {
                val report = DuplicatesCopy.keepReport(targets.size, refused, unconfirmed, notTried, unfileable, permanently)
                _state.update { it.copy(report = report) }
            }
        }
    }

    /**
     * One bounded write (`boundedWrite`). ⚠⚠ No pre-check, and only the write's own answer counts
     * (`photos-ios.md` §8.17 note 11): the copy is on screen, so there is something to do, and a
     * listing that omits it afterwards is not proof the write happened.
     */
    private suspend fun write(onUnconfirmed: () -> Unit, write: suspend () -> Boolean): WriteOutcome =
        // ⚠ On [viewModelScope], never inside `coroutineScope { }` (§8.16 note 12).
        viewModelScope.boundedWrite(
            timeoutMs = timeoutMs,
            settleMs = timeoutMs,
            write = write,
            alreadyDone = { false },
            onUnconfirmed = onUnconfirmed,
        )

    /**
     * Takes a copy off the list AT ONCE, not when the re-read lands (`TrashViewModel.takeOut`): the
     * tiles unlock when the loop ends, and one still showing could be chosen as the copy to KEEP —
     * a survivor that is already in the trash. ⚠ Kept off until a read STARTED after this lands.
     */
    private fun takeOut(path: String) {
        pendingTakenOut[path] = ++takenCount
        _state.update { latest -> latest.copy(groups = latest.groups.without(setOf(path))) }
    }
}

/** The groups without [paths] — and without any set that no longer holds two copies. */
private fun List<DuplicateGroup>.without(paths: Set<String>): List<DuplicateGroup> =
    if (paths.isEmpty()) this
    else mapNotNull { group ->
        val left = group.photos.filterNot { it.path in paths }
        when {
            left.size == group.photos.size -> group
            left.size < 2 -> null
            else -> group.copy(photos = left)
        }
    }
