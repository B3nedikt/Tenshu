package app.tenshu.timeline.data.capture

import platform.Foundation.NSRecursiveLock

/**
 * ⚠⚠ **The manager a detached capture left monitoring** (`ios-shared-store-sqlite.md` B.16 (233),
 * (238)), one at most. A detach parks its manager, still monitoring visits and significant changes
 * so that iOS's relaunch and wake registration survives a rebuild; it is **stopped and released**
 * as soon as no successor needs it:
 * - the next capture's manager has started monitoring (no two managers alive);
 * - ⚠⚠ the user turns capture off, or authorization is denied (a privacy matter: an opt-out must
 *   never leave monitoring registered);
 * - the graph is given up with no rebuild to follow (a corrupt store, a latched process, a refused
 *   probe, a deprovision).
 *
 * Generic over the manager so a test can see what is stopped. Any thread; [stop] is called outside
 * the lock.
 */
class ParkedLocationMonitor<M : Any>(private val stop: (M) -> Unit) {
    private val lock = NSRecursiveLock()
    private var parked: M? = null

    /** Parks [manager]; one parked before it is stopped first. */
    fun park(manager: M) {
        val previous = swap(manager)
        if (previous != null && previous !== manager) stop(previous)
    }

    /** Stops and releases the parked manager, if any. Idempotent. */
    fun stopAndRelease() {
        swap(null)?.let(stop)
    }

    /** The parked manager, for the tests. */
    val current: M? get() = lockedValue { parked }

    private fun swap(next: M?): M? = lockedValue { parked.also { parked = next } }

    private inline fun <T> lockedValue(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
