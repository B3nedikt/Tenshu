package app.tenshu.photos.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Tells a reader of the whole library whether it has to read it again (`photos-ios.md` §8.22.3).
 *
 * The contract is one sentence: **while [watching] is true, anything the Collections tab reads cannot
 * change without [revision] changing.** So a reader that noted the revision when a read began, and
 * finds [watching] true and the revision the same, holds what a new read would return.
 *
 * ⚠⚠ It errs one way only. A revision may move with nothing to show for it — a density change
 * rebuilds the feed's observer, a row is written and written back — and the reader then walks the
 * library once for nothing. It must never stay still across a change; where that cannot be promised
 * [watching] is false, and the reader re-reads as if there were no watch.
 * ⚠ Every change of [watching], either way, moves [revision] too: a reader need not ask whether the
 * watch was interrupted between its read and now.
 */
interface LibraryWatch {
    val watching: StateFlow<Boolean>
    val revision: StateFlow<Long>
}

/** A [LibraryWatch] whose owner says what happened. Safe from any thread. */
class MutableLibraryWatch(watching: Boolean = false) : LibraryWatch {
    private val _watching = MutableStateFlow(watching)
    private val _revision = MutableStateFlow(0L)
    override val watching: StateFlow<Boolean> = _watching.asStateFlow()
    override val revision: StateFlow<Long> = _revision.asStateFlow()

    /** Something a reader would see has changed — or may have. */
    fun changed() {
        _revision.update { it + 1 }
    }

    /** ⚠ The revision moves FIRST when watching ends and LAST when it begins: never watching over a stale one. */
    fun setWatching(value: Boolean) {
        if (_watching.value == value) return
        if (value) {
            changed()
            _watching.value = true
        } else {
            _watching.value = false
            changed()
        }
    }
}
