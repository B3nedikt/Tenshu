package app.tenshu.sdk.internal

import app.tenshu.sdk.SdkRuntimeTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The one-runtime-per-origin guard (`RuntimeLease`, `docs/web/architecture.md` "Cross-tab").
// Two tabs are modelled as two leases over one fake lock table and one fake broadcast bus — the
// real `navigator.locks` cannot host two "tabs" in one page, but the last test pins that the real
// runtime does take the real lock.
class RuntimeLeaseTest : SdkRuntimeTest() {

    /** A lock table shared by every "tab", with Web Locks' semantics for the calls we use. */
    private class FakeLocks(override val supported: Boolean = true) : LockBackend {
        private val holder = MutableStateFlow<(() -> Unit)?>(null)

        override suspend fun tryAcquire(name: String, onLost: () -> Unit): LockHandle? {
            if (holder.value != null) return null
            holder.value = onLost
            return LockHandle { holder.value = null }
        }

        override suspend fun awaitFree(name: String) {
            holder.first { it == null }
        }

        var steals = 0

        override suspend fun steal(name: String) {
            steals++
            val lost = holder.value ?: return
            holder.value = null
            lost()
        }

        val held: Boolean get() = holder.value != null
    }

    private class FakeBus {
        private val listeners = mutableListOf<Pair<Any, (String) -> Unit>>()
        fun channel(): LeaseChannel = object : LeaseChannel {
            override fun post(message: String) {
                listeners.filter { it.first !== this }.forEach { it.second(message) }
            }
            override fun listen(onMessage: (String) -> Unit): () -> Unit {
                val entry: Pair<Any, (String) -> Unit> = this to onMessage
                listeners += entry
                return { listeners.remove(entry) }
            }
        }
    }

    @Test
    fun aSecondRuntimeIsRefusedWhileTheLockIsHeld_andAllowedAfterRelease() = runTest {
        val locks = FakeLocks()
        val first = RuntimeLease(locks, null, backgroundScope)
        val second = RuntimeLease(locks, null, backgroundScope)

        assertEquals(LeaseState.HELD, first.acquire {})
        assertEquals(LeaseState.ELSEWHERE, second.acquire {})

        first.yieldNow()
        assertEquals(LeaseState.DISPLACED, first.state.value)
        assertTrue(!locks.held, "yielding releases the lock")

        // A fresh page load (a new lease) now gets it.
        assertEquals(LeaseState.HELD, RuntimeLease(locks, null, backgroundScope).acquire {})
    }

    @Test
    fun useHere_asksTheHolderToFlushThenRelease() = runTest {
        val locks = FakeLocks()
        val bus = FakeBus()
        var lockHeldDuringShutdown: Boolean? = null
        val holder = RuntimeLease(locks, bus.channel(), backgroundScope)
        val other = RuntimeLease(locks, bus.channel(), backgroundScope)

        holder.acquire { lockHeldDuringShutdown = locks.held }
        assertEquals(LeaseState.ELSEWHERE, other.acquire {})

        other.useHere()

        // ⚠ The order is the point: the stores close while the lock is still held, so the next
        // tab's hydration cannot start before the last write lands.
        assertEquals(true, lockHeldDuringShutdown, "shutdown runs before the release")
        assertEquals(LeaseState.DISPLACED, holder.state.value)
        assertTrue(!locks.held)
    }

    @Test
    fun useHere_stealsOnlyWhenTheHolderDoesNotAnswer_andTheStolenTabDropsItsQueue() = runTest {
        val locks = FakeLocks()
        var flushed = false
        var abandoned = false
        // No channel on the holder: it can never hear the request (a frozen tab).
        val holder = RuntimeLease(locks, null, backgroundScope)
        holder.acquire(onLost = { abandoned = true }) { flushed = true }

        RuntimeLease(locks, FakeBus().channel(), backgroundScope).useHere()
        testScheduler.runCurrent() // ⚠ not advanceUntilIdle: that ignores backgroundScope work

        assertEquals(1, locks.steals)
        assertEquals(LeaseState.DISPLACED, holder.state.value, "a stolen lease stops the runtime")
        // ⚠⚠ A stolen tab must DROP its queue. Flushing it would land
        // stale rows after the new holder hydrated — behind the new holder's cursor.
        assertTrue(abandoned, "the stolen tab abandons its queue")
        assertTrue(!flushed, "and never flushes it")
    }

    @Test
    fun aHolderThatAcknowledges_isWaitedFor_evenThroughASlowFlush() = runTest {
        val locks = FakeLocks()
        val bus = FakeBus()
        val holder = RuntimeLease(locks, bus.channel(), backgroundScope)
        // A multi-MB file flush: far longer than the ack timeout.
        holder.acquire { delay(60_000) }

        RuntimeLease(locks, bus.channel(), backgroundScope).useHere()

        assertEquals(0, locks.steals, "an acknowledged hand-over is never turned into a steal")
        assertEquals(LeaseState.DISPLACED, holder.state.value)
        assertTrue(!locks.held)
    }

    // Without Web Locks nothing stops a second tab — but its open claims the fence, so
    // the first runtime's next write is refused. That must STOP the first runtime and surface the
    // notice (state DISPLACED is what RuntimeLeaseNotice / RuntimeBanner render), never leave a
    // silent read-only ghost.
    @Test
    fun unguarded_aFencedOutWriteDisplacesTheRuntime_andDropsItsQueue() = runTest {
        val first = RuntimeLease(FakeLocks(supported = false), null, backgroundScope)
        var abandoned = false
        assertEquals(LeaseState.UNGUARDED, first.acquire(onLost = { abandoned = true }) {})

        val dbName = "test-fence-unguarded-${kotlin.js.Date.now().toLong()}"
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val firstStore = IndexedDbObjectStore.open(dbName, scope, onFencedOut = first::fencedOut)
        // The second tab's runtime opens the same database — and claims it.
        IndexedDbObjectStore.open(dbName, scope)

        firstStore.storeObject(
            org.tenshu.core.common.proxy.db.ObjectStore.DbEntry(
                "tenshu/test/fence", 7L, listOf(org.tenshu.core.common.proxy.db.ObjectStore.DbValue.StringValue("x")),
            ),
        )
        firstStore.flush()

        assertEquals(LeaseState.DISPLACED, first.state.value, "the fenced-out runtime is displaced — the notice shows")
        assertTrue(abandoned, "and drops its queue")
    }

    @Test
    fun onlyAnUnguardedRuntimeRefusesADurableCursor() {
        LeaseState.entries.forEach { state ->
            assertEquals(state == LeaseState.UNGUARDED, refusesDurableCursor(state), "$state")
        }
    }

    @Test
    fun noWebLocks_runsUnguarded() = runTest {
        val lease = RuntimeLease(FakeLocks(supported = false), null, backgroundScope)
        assertEquals(LeaseState.UNGUARDED, lease.acquire {})
    }

    @Test
    fun theRealRuntimeHoldsTheRealLock() = runTest {
        JsRuntime.awaitReady()
        assertEquals(LeaseState.HELD, JsRuntime.awaitLease())

        // A second "tab" over the browser's own Web Locks is refused, and opens nothing.
        val second = RuntimeLease(
            backend = WebLocksBackend(),
            channel = null,
            scope = CoroutineScope(Dispatchers.Default),
        )
        assertEquals(LeaseState.ELSEWHERE, second.acquire {})

        val probe: Boolean = probeLockFree(RuntimeLease.LOCK_NAME).await()
        assertEquals(false, probe, "navigator.locks reports ${RuntimeLease.LOCK_NAME} as held")
    }

    private fun probeLockFree(name: String): Promise<Boolean> {
        val options: dynamic = js("({ ifAvailable: true })")
        return js("navigator.locks").request(name, options) { lock: dynamic -> lock != null }
            .unsafeCast<Promise<Boolean>>()
    }
}
