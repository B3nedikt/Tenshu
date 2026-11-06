package org.tenshu.inference.proxy

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The unloading of graphs that were torn down, as **the process** sees it
 * (`ios-on-device-inference.md` §14.2, §14.17): what a graph waits for before it loads a tower,
 * so two pairs of towers are never resident.
 *
 * ⚠⚠ **A party of a graph that was torn down waits for no unload at all.** An unload waits for
 * its graph's probe to let go of its towers; a probe waits here before it loads them. When the
 * probe has claimed its towers and *then* its own graph is torn down, waiting here would leave
 * the two waiting for each other for good: the teardown never finishes, and no later graph of the
 * process can load a model.
 * - The rule cannot be about **whose the pending unload is**. That is the head of a chain: tear
 *   down the graph after it too, before the probe gets here, and the head is the *successor's*
 *   unload, which waits for the asker's own behind it.
 * - It is about the asker: every unload published after a graph was torn down chains behind
 *   that graph's own, so **once a graph is retired, anything pending waits for it**. A party
 *   of a retired graph returns at once, and stops at its next step.
 * - And a party of a graph that is *not* retired may wait for the head: it was published
 *   before that graph's teardown, so nothing in its chain is that graph's unload.
 *
 * ⚠⚠ "Retired?" and "what is pending?" are read as **one snapshot** ([lock]), and a teardown
 * retires its graph and publishes its unload inside the same lock: the second point above is
 * an argument about order, and holds only if no teardown can come between the two reads.
 */
class PendingUnloads {
    private val lock = SynchronizedObject()

    /** The last unload published and not yet ended. Under [lock]. */
    private var pending: Job? = null

    /**
     * [readiness]'s graph is torn down: it is retired **now**, and unloaded on [scope] once the
     * unload before it has ended and [probeLetGo] has returned.
     *
     * ⚠ A chain: a teardown can land while an earlier one is still unloading. Each waits for the
     * one before, and the handle always names the last.
     *
     * @param probeLetGo suspends while the graph's probe still holds a pair of towers.
     * @return the unloading.
     */
    fun retire(readiness: AiReadiness, scope: CoroutineScope, probeLetGo: suspend () -> Unit): Job {
        val unloading: Job = synchronized(lock) {
            // ⚠⚠ Retired and published in ONE step, as [awaitFor] reads them in one.
            readiness.retire()
            val earlier: Job? = pending
            // ⚠ Not started under the lock: its first lines may run where they are launched.
            scope.launch(start = CoroutineStart.LAZY) {
                earlier?.join()
                // ⚠⚠ A probe in flight holds a pair of its own, which the backend's close does
                // not reach. It sees the retirement between two of its steps and closes; until
                // it has, this unload is still pending and the next graph loads nothing.
                probeLetGo()
                runCatching { readiness.unloadRetired() }
                    .onFailure { println("PendingUnloads -> the retired runtime's towers were not unloaded: $it") }
            }.also { pending = it }
        }
        // Cleared once done, unless a later teardown has taken its place.
        unloading.invokeOnCompletion { synchronized(lock) { if (pending === unloading) pending = null } }
        unloading.start()
        return unloading
    }

    /**
     * Waits for the unload that is pending, **unless [mine]'s graph was torn down**: then this
     * returns at once, and the caller stops at its next step.
     */
    suspend fun awaitFor(mine: AiReadiness) {
        // ⚠⚠ One snapshot of both. See the class.
        val head: Job = synchronized(lock) { if (mine.isRetired()) null else pending } ?: return
        head.join()
    }

    /** Whether an unload is still pending. */
    fun pending(): Boolean = synchronized(lock) { pending != null }
}
