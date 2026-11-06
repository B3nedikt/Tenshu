package org.tenshu.inference.proxy

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One sequence at a time, and **a request made while it runs causes at most one more run**
 * (`ios-on-device-inference.md` §14.17): what the launch-and-foreground check of Photos'
 * on-device AI runs under.
 *
 * - A request that finds nothing running runs.
 * - A request that arrives while a run is in flight waits for it, and then runs **once**, for
 *   itself and for every other request that arrived during that same run: whatever that run
 *   had already passed (the journal's resume, the prepare) is done again for them.
 * - A request whose need was covered by a run that **started after it was made** does not run.
 *
 * ⚠⚠ Why not a queue: N requests in one foreground (the launch's check, a rehydration's, a
 * scene phase flickering) would be N sequences one after another, each resuming the journal
 * and probing. Why not "skip when busy": a request that arrives after the running sequence
 * has passed a step needs that step, and would be dropped.
 *
 * @param run the sequence. Its argument is whether any request it covers was a launch's.
 * @param scope where a request runs that nobody waits for ([requestLater]).
 */
class CoalescedRuns(private val scope: CoroutineScope, private val run: suspend (launch: Boolean) -> Unit) {
    private val running = Mutex()
    private val state = SynchronizedObject()

    /** Requests made and not yet covered by a run that started after them. Under [state]. */
    private var wanted: Boolean = false
    private var wantedLaunch: Boolean = false

    /**
     * A request nobody waits for: what a commit makes, from inside the downloader, once its
     * step has ended. Returns at once.
     */
    fun requestLater(launch: Boolean = false) {
        scope.launch {
            try {
                request(launch)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                println("CoalescedRuns -> a requested run failed: $failure")
            }
        }
    }

    /** Returns when a run that started after this request was made has ended. */
    suspend fun request(launch: Boolean) {
        synchronized(state) {
            wanted = true
            wantedLaunch = wantedLaunch || launch
        }
        running.withLock {
            // ⚠ Taken as the run STARTS: what arrives from here on is not covered by it.
            val covering: Boolean? = synchronized(state) {
                if (!wanted) return@synchronized null
                wanted = false
                wantedLaunch.also { wantedLaunch = false }
            }
            // `null`: a run that started after this request was made has ended already.
            if (covering != null) run(covering)
        }
    }
}
