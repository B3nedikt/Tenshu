package org.tenshu.sdk.ios

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSLock

/**
 * ⚠⚠ **Test seam: the route-1 graphs a test assembled, so its `@AfterTest` can stop them**
 * (`docs/testing-flakes.md`, the `PhotosPeopleGroupingTest` row).
 *
 * [FirstPartyLocalRuntime.assembleBase] records each graph it builds over a **test replica** (a
 * non-null `replicaDirectory`, which production never passes: B.22). An app runtime adds the
 * scopes it makes outside the graph through [FirstPartyLocalRuntime.Base.stopsWithTestGraph]. A
 * production graph is never recorded.
 *
 * Without it every `assemble` in a test outlives its test, with its sync loops, scheduler and
 * index runner on `Dispatchers.Default`. Measured over `:tenshu-sdk-ios-photos`' suite: 0.00 cores
 * busy while the test thread slept at the start, 2.99 at the end. That is all of
 * `Dispatchers.Default` on CI's 3-core runner, where later tests' collectors were never dispatched.
 *
 * ⚠ [stopAll] cancels and joins. It does not close the graphs' Koin (a coroutine still inside a
 * lookup would meet an emptied graph, and a non-cancellation throw aborts K/N) and retires no
 * process-wide state (on-device AI unloads, surfaces): the hooks that own those run first.
 */
object TestGraphs {
    private val lock = NSLock()
    private val running = ArrayList<Job>()

    internal fun track(job: Job) {
        lock.lock()
        try {
            running += job
        } finally {
            lock.unlock()
        }
    }

    internal fun track(scope: CoroutineScope) {
        scope.coroutineContext[Job]?.let(::track)
    }

    /**
     * Cancels every recorded graph and scope, and waits up to 10 s for them to end. Call it
     * **last** in each test class's `@AfterTest`: the hooks before it (a `deprovision`, a surface
     * reset) may still need the graph. A no-op when nothing was recorded.
     */
    fun stopAll() {
        lock.lock()
        val stopping = try {
            running.toList().also { running.clear() }
        } finally {
            lock.unlock()
        }
        if (stopping.isEmpty()) return
        stopping.forEach { it.cancel() }
        runBlocking { withTimeoutOrNull(10_000) { stopping.joinAll() } }
    }
}
