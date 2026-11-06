package org.tenshu.core.common.proxy.db.sqlite

import platform.Foundation.NSCondition
import platform.Foundation.NSDate
import platform.Foundation.NSRecursiveLock
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import kotlin.test.fail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Appendix B.12 (159): §4.2.1's second opportunistic trigger, **every 30 s while foregrounded, if
 * anything was committed since**, and the public seam the UIKit adapter drives it through
 * ([SharedReplicaLifecycle], (156)). The timer is the adapter's (its own tests); here, what one
 * tick does. Every store is made up.
 */
@OptIn(SharedReplicaPlatformApi::class)
class ReplicaForegroundCheckpointTest {

    private class Assertions : ProtectionAssertions, SharedReplicaBackgroundTime {
        private val lock = NSRecursiveLock()
        var begun = 0
        var ended = 0
        override fun begin(reason: String, onExpired: () -> Unit): Long? {
            lock.lock()
            try {
                return (++begun).toLong()
            } finally {
                lock.unlock()
            }
        }

        override fun end(id: Long) {
            lock.lock()
            ended++
            lock.unlock()
        }

        override fun backgroundTimeRemainingMs(): Long = 60_000
    }

    private object Inline : ProtectionTimer {
        override fun after(delayMs: Long, block: () -> Unit) = Unit
        override fun sleep(ms: Long) = Unit
        override fun dispatch(block: () -> Unit) = block()
    }

    private fun protection(process: ReplicaProcess, foreground: Boolean) =
        ReplicaProtection(Assertions(), timer = Inline, isMainThread = { false }).also {
            it.attach(process.registry)
            it.install(isForeground = foreground)
        }

    /** The checkpoints the store's checkpointer was asked for, by mode. */
    private fun SharedReplicaStore.recordModes(): MutableList<SqliteCheckpointMode> =
        ArrayList<SqliteCheckpointMode>().also { modes -> checkpointer.fault = { modes += it } }

    @Test
    fun aTickAfterAWriteRunsOnePassiveCheckpointAndTheNextWithNothingWrittenRunsNone() {
        val process = ReplicaProcess(tempReplica())
        val protection = protection(process, foreground = true)
        val store = process.store()
        val modes = store.recordModes()

        store.databaseLock.withLock { store.storeObject(row(1)) }
        assertEquals(1, protection.foregroundCheckpointTick())
        assertEquals(listOf(SqliteCheckpointMode.PASSIVE), modes)
        assertEquals(1, store.foregroundCheckpoints)
        assertEquals(0, process.registry.gate.admittedCount, "the tick's admission was given back")

        assertEquals(0, protection.foregroundCheckpointTick(), "nothing committed since")
        assertEquals(1, modes.size)

        store.databaseLock.withLock { store.storeObject(row(2)) }
        assertEquals(1, protection.foregroundCheckpointTick())
        assertEquals(2, modes.size)
        store.close()
    }

    @Test
    fun anAccessThatOnlyReadsIsNotACommitThatCounts() {
        val process = ReplicaProcess(tempReplica())
        val protection = protection(process, foreground = true)
        val store = process.store()
        val modes = store.recordModes()

        store.databaseLock.withLock { store.getObject(1) }
        store.hasObject(2)
        assertEquals(0, protection.foregroundCheckpointTick())
        assertTrue(modes.isEmpty())
        store.close()
    }

    @Test
    fun outsideTheForegroundNoTickRunsEvenWithACommit() {
        val process = ReplicaProcess(tempReplica())
        val protection = protection(process, foreground = true)
        val store = process.store()
        val modes = store.recordModes()
        store.databaseLock.withLock { store.storeObject(row(1)) }

        // A wake's token keeps the gate open in the background: only the foreground is asked.
        val wake = protection.acquire("a wake")
        protection.enteredBackground()
        assertTrue(protection.isProtected)
        assertEquals(0, protection.foregroundCheckpointTick())
        assertTrue(modes.isEmpty())
        wake?.release()
        store.close()
    }

    @Test
    fun theStoreAsksTheForegroundItselfUnderItsLock() {
        // The protection's own check bypassed: the store's is the one that holds (a tick fired
        // just as the app left the foreground, B.12 (159)).
        val process = ReplicaProcess(tempReplica())
        val store = process.store()
        val modes = store.recordModes()
        store.databaseLock.withLock { store.storeObject(row(1)) }
        process.inBackground()
        assertFalse(store.foregroundCheckpoint())
        assertTrue(modes.isEmpty())
        process.inForeground()
        assertTrue(store.foregroundCheckpoint(), "the commit is still owed its checkpoint")
        assertEquals(1, modes.size)
        store.close()
    }

    @Test
    fun aClosedGateRunsNothingAndKeepsTheCommitOwed() {
        val process = ReplicaProcess(tempReplica())
        val store = process.store()
        val modes = store.recordModes()
        store.databaseLock.withLock { store.storeObject(row(1)) }
        process.registry.gate.beginQuiesce()
        assertFalse(store.foregroundCheckpoint())
        assertTrue(modes.isEmpty())
        process.registry.gate.endQuiesce()
        process.registry.gate.reopen()
        assertTrue(store.foregroundCheckpoint())
        assertEquals(1, modes.size)
        store.close()
    }

    @Test
    fun aFencedStoreAndATickInsideAnAccessRunNothing() {
        val process = ReplicaProcess(tempReplica())
        val store = process.store()
        val modes = store.recordModes()
        store.databaseLock.withLock { store.storeObject(row(1)) }
        // Inside an access on this thread: the checkpoint cannot run inside a transaction.
        store.databaseLock.withLock { assertFalse(store.foregroundCheckpoint()) }
        assertTrue(modes.isEmpty())
        store.close()
        assertFalse(store.foregroundCheckpoint())
        assertTrue(modes.isEmpty())
    }

    @Test
    fun aTickThatThrowsIsLoggedAndNeverThrowsOutOfTheTick() {
        val process = ReplicaProcess(tempReplica())
        val logged = ArrayList<String>()
        val protection = ReplicaProtection(Assertions(), timer = Inline, isMainThread = { false }, log = { logged += it }).also {
            it.attach(process.registry)
            it.install(isForeground = true)
        }
        val store = process.store()
        val modes = store.recordModes()
        val throwing = process.registry.register(ReplicaHandleKind.CHECKPOINT, "a made-up throwing tick")
        throwing.onForegroundTick = { throw IllegalStateException("a made-up failure") }
        store.databaseLock.withLock { store.storeObject(row(1)) }
        // A throw out of a tick on the worker's queue would end the process.
        assertEquals(1, protection.foregroundCheckpointTick())
        assertEquals(1, modes.size)
        assertTrue(logged.any { "a made-up failure" in it }, "$logged")
        process.registry.release(throwing)
        store.close()
    }

    @Test
    fun theLifecycleSurfaceDrivesTheSameTick() {
        val process = ReplicaProcess(tempReplica())
        val lifecycle = SharedReplicaLifecycle.create(Assertions(), workerDispatch = { it() })
        lifecycle.protection.attach(process.registry)
        lifecycle.install(isForeground = true)
        assertTrue(lifecycle.isForeground)
        val store = process.store()
        val modes = store.recordModes()
        store.databaseLock.withLock { store.storeObject(row(1)) }
        assertEquals(1, lifecycle.foregroundCheckpointTick())
        assertEquals(listOf(SqliteCheckpointMode.PASSIVE), modes)
        store.close()
    }

    /**
     * Waits up to [timeoutMs] for [done], polling. ⚠ The mid-drain test's deadlines are 30 s:
     * each is "this happens at all", and 5 s fails under the shared host's load.
     */
    private fun within(timeoutMs: Long, what: String, done: () -> Boolean) {
        val start = kotlin.time.TimeSource.Monotonic.markNow()
        while (!done()) {
            if (start.elapsedNow().inWholeMilliseconds > timeoutMs) fail("$what: not within $timeoutMs ms")
            platform.posix.usleep(10_000u)
        }
    }

    @Test
    fun aForegroundBeforeTheBackgroundsQuiesceEndsReopensTheGateAndAnAccessSucceeds() {
        // B.12: driven through the public lifecycle exactly as the adapter drives it,
        // with the real dispatch, so the background token's release (the quiesce) runs off this thread.
        val process = ReplicaProcess(tempReplica())
        val lifecycle = SharedReplicaLifecycle.create(Assertions(), workerDispatch = { it() })
        lifecycle.protection.attach(process.registry)
        lifecycle.install(isForeground = true)
        val store = process.store()

        // An access held open on another thread keeps the quiesce in its 10 s drain.
        val held = NSCondition()
        var entered = false
        var letGo = false
        var finished = false
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            store.databaseLock.withLock {
                store.storeObject(row(1))
                held.lock()
                entered = true
                held.broadcast()
                while (!letGo) held.waitUntilDate(NSDate.dateWithTimeIntervalSinceNow(10.0))
                held.unlock()
            }
            held.lock()
            finished = true
            held.broadcast()
            held.unlock()
        }
        within(30_000, "the held access began") { held.lock(); val e = entered; held.unlock(); e }

        lifecycle.enteredBackground()
        within(30_000, "the quiesce is draining") { process.registry.gate.isQuiescing }
        lifecycle.enteredForeground()

        held.lock()
        letGo = true
        held.broadcast()
        held.unlock()
        within(30_000, "the held access committed") { held.lock(); val f = finished; held.unlock(); f }
        within(30_000, "the shutdown ended") { lifecycle.protection.quiesceOutcomes.isNotEmpty() }

        assertEquals(listOf(ReplicaQuiesceOutcome.REGAINED), lifecycle.protection.quiesceOutcomes)
        assertTrue(process.registry.gate.isOpen)
        store.databaseLock.withLock { store.storeObject(row(2)) }
        assertTrue(store.hasObject(1) && store.hasObject(2))
        assertEquals(null, store.fencedBy)
        store.close()
    }
}
