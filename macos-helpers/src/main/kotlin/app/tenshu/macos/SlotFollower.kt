package app.tenshu.macos

/**
 * Keeps the in-window Touch ID view on the **latest** slot the UI reported — the bookkeeping half of
 * [MacEmbeddedAuthentication.move], kept free of AppKit so [SlotFollowerTest] can play the
 * interleavings in a fixed order.
 *
 * ⚠⚠ A slot reported while no view exists yet is **kept, not dropped**. The slot comes from Compose's
 * UI thread, the view is created from an IO thread, and both reach here in either order; while a
 * window is still appearing (opened from the tray) the layout moves several times in that gap. The
 * first version dropped those moves, and the fingerprint stayed at the first layout's position —
 * drawn over the title and buttons (reported 2026-10-16, never reproduced live).
 * For the same reason [begin]'s slot only fills an empty slot: a move that got here first is newer.
 *
 * [place] runs under this object's lock, so a move and the attach never interleave.
 */
internal class SlotFollower<S : Any, V : Any>(private val place: (V, S) -> Unit) {
    private var view: V? = null
    private var latest: S? = null

    /** A call started with [initial] — the slot the UI held when it asked. */
    @Synchronized
    fun begin(initial: S) {
        if (latest == null) latest = initial
    }

    /** The view exists: put it on the latest slot. */
    @Synchronized
    fun attach(view: V) {
        this.view = view
        latest?.let { place(view, it) }
    }

    @Synchronized
    fun move(slot: S) {
        latest = slot
        view?.let { place(it, slot) }
    }

    /** The view is gone; the next call starts from its own slot. */
    @Synchronized
    fun end() {
        view = null
        latest = null
    }
}
