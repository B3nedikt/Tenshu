package app.tenshu.photos.ui.screens.feed

import app.tenshu.photos.model.MotionTurn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The feed's one player, rotating over the visible video tiles (`photos-video-support.md` §1.2,
 * §5 / M5 — D1, D7).
 *
 * The rule, measured off Google Photos and implemented verbatim: **one player. It plays a visible
 * video tile through once, then hands over to the next visible video tile in document order,
 * wrapping. With one video on screen, "next" is itself.** Looping is not a separate behaviour.
 *
 * ⚠⚠ **Feed state, not tile state** (§5.2). A tile cannot know what the next tile is, and a
 * tile-owned player would re-create the PH-G9 flood — 17 loads for 10 photographs — with a payload
 * two orders of magnitude larger. So this lives beside the view model and owns three things: the
 * visible video rows, the current turn, and **the single fetch slot**.
 *
 * ⚠⚠ **Only a tile whose clip is already in hand is in the rotation** (§5.1). The player is never
 * handed to a tile that would then have to fetch — that is what turns a rotation into a stall. A
 * video with no motion class (never minted, uploaded before M4, over the cap) is simply not a
 * member and its tile stays a static poster, which is today's behaviour. Zero eligible tiles ⇒
 * nothing animates and the grid is exactly what it is today: the feature is additive by
 * construction.
 *
 * ⚠⚠ **One blob in flight, pipelined with the turn** (§5.3, D7). While tile *N* plays (~3 s) the
 * next tile's clip is fetched, so on hand-over it is already there. `MOTION` must never join the
 * feed's bulk thumbnail pull — that would fetch a clip for every video the user scrolls past, the
 * exact opposite of §1.3's point. **A miss is not an error**: on a byte-less device `null` means
 * "has not arrived yet", so it is never remembered as an answer — the tile is skipped this cycle
 * and asked for again on the next (a wrap) or when the visible set changes.
 *
 * ⚠ Everything here runs on [scope]'s dispatcher — `viewModelScope`, `Main.immediate` on every
 * platform — and the state is confined to it. No locks, by design; a test drives it on a single
 * test dispatcher the same way.
 */
class MotionRotation(
    private val scope: CoroutineScope,
    /** The `MOTION` class for a path, or `null` for "not in hand on this device (yet)". */
    private val fetch: suspend (path: String) -> ByteArray?,
    private val store: MotionClipStore = MotionClipStore(),
) {
    private val enabled = MutableStateFlow(true)
    private val paused = MutableStateFlow(false)
    private val visible = MutableStateFlow<List<String>>(emptyList())

    private val _turn = MutableStateFlow<MotionTurn?>(null)

    /** The tile that holds the player right now, or `null` while nothing animates. */
    val turn: StateFlow<MotionTurn?> = _turn.asStateFlow()

    /** The visible video paths the rotation is currently allowed to use — empty while paused or off. */
    private var active: List<String> = emptyList()

    /** Where the rotation was last, so a restart continues in document order rather than at the top. */
    private var anchor: String? = null

    private var serial = 0L
    private var fetching = false

    /** Paths that missed this cycle (§5.3) — cleared on a wrap and on a change of the visible set. */
    private val missed = mutableSetOf<String>()

    init {
        scope.launch {
            combine(enabled, paused, visible) { on, halted, paths ->
                if (on && !halted) paths else emptyList()
            }
                .distinctUntilChanged()
                .collect { paths ->
                    active = paths
                    // A different set of tiles is a different cycle: a clip that had not arrived
                    // when this path was last asked for may have by now.
                    missed.clear()
                    reconcile()
                }
        }
    }

    /** D2 — the Settings boolean. Off ⇒ nothing animates and nothing is fetched. */
    fun setEnabled(value: Boolean) {
        enabled.value = value
    }

    /** Q1/Q2 — off-screen, backgrounded, or scrubbing: the player stops and hands nothing over. */
    fun setPaused(value: Boolean) {
        paused.value = value
    }

    /** The video tiles on screen, in document order, as the grid reports them. */
    fun setVisible(paths: List<String>) {
        visible.value = paths
    }

    /** The tile at [path] played its clip through once — hand over. */
    fun turnFinished(path: String) {
        val current = _turn.value ?: return
        if (current.path != path) return
        _turn.value = null
        reconcile()
    }

    private fun reconcile() {
        val paths = active
        if (paths.isEmpty()) {
            _turn.value = null
            return
        }
        val current = _turn.value
        if (current != null && current.path in paths) {
            // Keep playing: a scroll that leaves the current tile on screen never restarts it.
            prefetchAfter(current.path)
            return
        }
        val next = MotionRotationRules.turnAfter(anchor, paths, store::has)
        if (next == null) {
            _turn.value = null
            // Nothing in hand — fetch the first candidate; the turn starts when it lands.
            prefetchAfter(anchor)
            return
        }
        if (MotionRotationRules.wraps(anchor, next, paths)) missed.clear()
        anchor = next
        val clip = store.get(next) ?: return prefetchAfter(anchor)
        _turn.value = MotionTurn(path = next, clip = clip, serial = ++serial)
        prefetchAfter(next)
    }

    private fun prefetchAfter(after: String?) {
        if (fetching) return
        val target = MotionRotationRules.prefetchTarget(after, active, inHand = store::has, skip = missed)
            ?: return
        fetching = true
        scope.launch {
            val bytes = try {
                fetch(target)
            } catch (e: CancellationException) {
                fetching = false
                throw e
            } catch (e: Throwable) {
                null
            }
            if (bytes != null && bytes.isNotEmpty()) store.put(target, bytes) else missed += target
            fetching = false
            reconcile()
        }
    }
}

/**
 * The rotation's ordering rules, as pure functions over the visible list — the half of
 * [MotionRotation] a test can pin without a scope.
 */
internal object MotionRotationRules {

    /**
     * The next path in [visible] after [anchor], wrapping, for which [inHand] holds. [anchor]
     * itself comes **last** in the walk, so a lone eligible tile hands over to itself — §1.2's
     * single-video case. An [anchor] that is not visible (scrolled away, or none yet) starts the
     * walk at the top. `null` when nothing visible is in hand.
     */
    fun turnAfter(anchor: String?, visible: List<String>, inHand: (String) -> Boolean): String? {
        if (visible.isEmpty()) return null
        val start = visible.indexOf(anchor)
        val n = visible.size
        for (step in 1..n) {
            val candidate = visible[(start + step).mod(n)]
            if (inHand(candidate)) return candidate
        }
        return null
    }

    /** Whether going from [anchor] to [next] passed the end of [visible] — one cycle done. */
    fun wraps(anchor: String?, next: String, visible: List<String>): Boolean {
        val from = visible.indexOf(anchor)
        val to = visible.indexOf(next)
        return from < 0 || to <= from
    }

    /**
     * What to fetch while the tile at [after] plays: the next visible path after it, wrapping,
     * that is neither in hand nor in [skip] — and never [after] itself. `null` when every other
     * visible tile is already in hand or has missed this cycle.
     */
    fun prefetchTarget(
        after: String?,
        visible: List<String>,
        inHand: (String) -> Boolean,
        skip: Set<String>,
    ): String? {
        if (visible.isEmpty()) return null
        val start = visible.indexOf(after)
        val n = visible.size
        for (step in 1..n) {
            val candidate = visible[(start + step).mod(n)]
            if (candidate == after) continue
            if (!inHand(candidate) && candidate !in skip) return candidate
        }
        return null
    }
}

/**
 * The clips the rotation holds — a small, bytes-bounded LRU, **separate from `PreviewCache`**
 * (§5.3): a 90 KB clip in a cache tuned for 4 KB stills would evict the grid's thumbnails to hold
 * one video. Four clips is the current turn, the next one, and two the user may scroll back to.
 *
 * ⚠ Not thread-safe, deliberately: it is owned by [MotionRotation] and touched only on its scope's
 * dispatcher. A `null` is never stored — see [MotionRotation].
 */
class MotionClipStore(private val maxBytes: Int = DEFAULT_MAX_BYTES) {

    /** Insertion-ordered and re-inserted on read — the same hand-rolled LRU as `PreviewCache`. */
    private val entries = LinkedHashMap<String, ByteArray>()
    private var heldBytes = 0

    fun has(path: String): Boolean = entries.containsKey(path)

    fun get(path: String): ByteArray? = entries.remove(path)?.also { entries[path] = it }

    fun put(path: String, bytes: ByteArray) {
        // A clip larger than the whole budget is served (the turn holds its own reference) but
        // not kept: keeping it would evict everything else to make room for one tile.
        if (bytes.size > maxBytes) return
        entries.remove(path)?.let { heldBytes -= it.size }
        entries[path] = bytes
        heldBytes += bytes.size
        while (heldBytes > maxBytes) {
            val oldest = entries.keys.firstOrNull() ?: break
            heldBytes -= entries.remove(oldest)?.size ?: 0
        }
    }

    /** For tests. */
    val size: Int get() = entries.size

    companion object {
        /** Four clips at the 96 KB cap (`photos-video-support.md` §12.5), with a little slack. */
        const val DEFAULT_MAX_BYTES: Int = 4 * 128 * 1024
    }
}
