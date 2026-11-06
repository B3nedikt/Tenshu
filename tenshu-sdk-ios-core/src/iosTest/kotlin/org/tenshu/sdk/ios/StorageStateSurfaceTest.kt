@file:OptIn(org.tenshu.core.common.proxy.db.sqlite.SharedReplicaPlatformApi::class)

package org.tenshu.sdk.ios

import kotlinx.coroutines.Dispatchers
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore.DbEntry
import platform.Foundation.NSRecursiveLock
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.TimeSource

/**
 * Appendix B.17 (257), Q39: **the shared store's state for Swift.** A snapshot and a callback, the
 * copy in one Kotlin table, a cover for every state but READY and "no graph", and a generation
 * that moves when a rebuilt graph serves. Over hosts made here; every name is made up.
 */
class StorageStateSurfaceTest {

    private val savedContext = StorageStateSurface.callbackContext
    private val savedReachable = StorageStateSurface.controlReachable
    private val savedName = StorageStateSurface.appName

    @BeforeTest
    fun setUp() {
        StorageStateSurface.detachForTests()
        StorageStateSurface.callbackContext = Dispatchers.Default
        StorageStateSurface.controlReachable = { true }
        StorageStateSurface.refreshReachability()
        StorageStateSurface.appName = { "Example" }
    }

    @AfterTest
    fun tearDown() {
        StorageStateSurface.detachForTests()
        StorageStateSurface.callbackContext = savedContext
        StorageStateSurface.controlReachable = savedReachable
        StorageStateSurface.appName = savedName
    }

    private fun host(release: Boolean = true) =
        SharedReplicaHost(isForegroundSource = { true }, release = { release }, unbindLoops = {}, mainContext = EmptyCoroutineContext, log = {})

    private fun standIn(state: SharedReplicaHostState, willRetry: Boolean = true, wipe: Boolean = false) =
        NotReadyReplicaStore(SharedReplicaHostStatus(state, "made up", willRetry = willRetry, wipe = wipe))

    /** Neither SQLite nor the stand-in: what the host sees as a store that serves. */
    private object Serving : ObjectStore {
        override fun storeObject(dbEntry: DbEntry) = Unit
        override fun getObject(dataIdHash: Long): DbEntry? = null
        override fun setListener(dataIdHash: Long, listener: (update: DbEntry) -> Unit) = Unit
        override fun removeListener(dataIdHash: Long) = Unit
        override fun removeObject(dataIdHash: Long) = Unit
    }

    @Test
    fun withNoHostTheSnapshotIsReadyCoversNothingAndNeverChanges() {
        // ⚠⚠ The switch off: no binding, so no host is ever attached.
        val snapshot = StorageStateSurface.snapshot()
        assertEquals("READY", snapshot.state)
        assertFalse(snapshot.covers)
        assertNull(snapshot.title)
        assertFalse(snapshot.canTryAgain)
        assertEquals(0L, snapshot.generation)
        // B.18: nothing of the damaged store's copy either.
        assertFalse(snapshot.canExportDamagedData)
        assertTrue(snapshot.damagedDataFiles.isEmpty())
        assertFalse(snapshot.offersOpenControl)
        assertFalse(StorageStateSurface.tryAgain(), "nothing to try")
        val seen = Seen()
        val observation = StorageStateSurface.observe(seen::add)
        seen.awaitCount(1)
        Thread.sleepMs(200)
        assertEquals(listOf(snapshot), seen.all(), "one READY, then nothing")
        observation.cancel()
    }

    @Test
    fun everyStateHasItsCopyAndOnlyReadyAndNoGraphCoverNothing() {
        fun shown(store: ObjectStore, release: Boolean = true): StorageStateSnapshot {
            val host = host(release)
            host.graphInstalled(store, stop = {}, rebuild = {})
            StorageStateSurface.attach(host)
            return StorageStateSurface.snapshot()
        }
        assertFalse(shown(Serving).covers, "READY")
        val noGraph = host().also { StorageStateSurface.attach(it) }
        assertEquals("NO_GRAPH", StorageStateSurface.snapshot().state)
        assertFalse(StorageStateSurface.snapshot().covers, "⚠ no graph: the app's own gate decides (255)")
        assertFalse(noGraph.canTryAgain)

        shown(standIn(SharedReplicaHostState.REFRESHING)).let {
            assertTrue(it.covers); assertEquals("Refreshing…", it.title); assertFalse(it.canTryAgain)
        }
        shown(standIn(SharedReplicaHostState.UPDATING)).let {
            assertTrue(it.covers); assertEquals("Updating storage…", it.title, "a migration")
        }
        shown(standIn(SharedReplicaHostState.UPDATING, wipe = true)).let {
            assertEquals("Refreshing…", it.title, "a wipe")
        }
        shown(standIn(SharedReplicaHostState.CHECKING)).let { assertEquals("Checking storage…", it.title) }
        shown(standIn(SharedReplicaHostState.SKEW, willRetry = false)).let {
            assertTrue(it.covers)
            assertEquals("Update Example", it.title, "§7: on route 1 the app to name is the app itself")
            assertTrue("Example" in (it.message ?: ""))
            assertFalse(it.canTryAgain, "no route, no button")
        }
        shown(standIn(SharedReplicaHostState.CORRUPT, willRetry = false)).let {
            assertEquals("Storage damaged", it.title)
            assertTrue("may be lost" in (it.message ?: ""), "honest about unsynced changes")
            // B.18 (Q47), changed by design: no copy known yet, so it names Control and offers no export here.
            assertTrue("Open Tenshu Control" in (it.message ?: ""), it.message)
            assertFalse(it.canExportDamagedData, "⚠ only Control exports, and only a complete copy")
            assertTrue(it.offersOpenControl)
            assertFalse(it.canTryAgain)
        }
        shown(standIn(SharedReplicaHostState.NOT_READY)).let {
            assertTrue(it.covers); assertEquals("Storage isn't ready", it.title); assertTrue(it.canTryAgain, "Q42")
        }
        shown(standIn(SharedReplicaHostState.NOT_READY, willRetry = false)).let {
            assertTrue(it.canTryAgain, "a non-retryable refusal: the user may still ask (254)")
        }
        // A latched process: "Quit and reopen", no button.
        val latched = host(release = false)
        latched.graphInstalled(standIn(SharedReplicaHostState.NOT_READY), stop = {}, rebuild = { fail("never") })
        assertFalse(latched.rebuildNow())
        StorageStateSurface.attach(latched)
        StorageStateSurface.snapshot().let {
            assertEquals("Storage unavailable", it.title)
            assertTrue("Quit and reopen Example" in (it.message ?: ""))
            assertFalse(it.canTryAgain)
        }
    }

    @Test
    fun anObserverSeesTheCoverThenTheRebuiltGraphsNewGeneration() {
        val host = host()
        val seen = Seen()
        // Observing before any host exists, as a root built before the runtime does.
        val observation = StorageStateSurface.observe(seen::add)
        seen.awaitCount(1)
        StorageStateSurface.attach(host)
        host.graphInstalled(Serving, stop = {}, rebuild = {})
        seen.await("the first graph serves") { it.state == "READY" && it.generation == 0L }
        host.graphInstalled(standIn(SharedReplicaHostState.REFRESHING), stop = {}, rebuild = {})
        seen.await("covered") { it.covers && it.title == "Refreshing…" }
        host.graphInstalled(Serving, stop = {}, rebuild = {})
        val back = seen.await("back to READY over a rebuilt graph") { it.state == "READY" && !it.covers && it.generation == 1L }
        assertNotNull(back)
        observation.cancel()
        Thread.sleepMs(100)
        val count = seen.all().size
        host.graphInstalled(standIn(SharedReplicaHostState.REFRESHING), stop = {}, rebuild = {})
        Thread.sleepMs(300)
        assertEquals(count, seen.all().size, "no callback after cancel")
    }

    @Test
    fun tryAgainIsTheHostsAndCallsItsRebuild() {
        val host = host()
        var rebuilt = 0
        host.graphInstalled(standIn(SharedReplicaHostState.NOT_READY, willRetry = false), stop = {}, rebuild = {
            rebuilt++
            host.graphInstalled(Serving, stop = {}, rebuild = {})
        })
        StorageStateSurface.attach(host)
        assertTrue(StorageStateSurface.snapshot().canTryAgain)
        host.mainContext = Dispatchers.Default
        assertTrue(StorageStateSurface.tryAgain())
        val start = TimeSource.Monotonic.markNow()
        while (rebuilt == 0 && start.elapsedNow().inWholeMilliseconds < 10_000) Thread.sleepMs(10)
        assertEquals(1, rebuilt)
        val settled = TimeSource.Monotonic.markNow()
        while (StorageStateSurface.snapshot().covers && settled.elapsedNow().inWholeMilliseconds < 10_000) Thread.sleepMs(10)
        assertFalse(StorageStateSurface.snapshot().covers)
        assertEquals(1L, StorageStateSurface.snapshot().generation)
    }

    @Test
    fun aWaitingCoverOffersTryAgainOnlyAfterItsDelaySoNoCoverIsADeadEnd() {
        // B.17 (278).
        val saved = StorageStateSurface.tryAgainAfterMs
        StorageStateSurface.tryAgainAfterMs = 300
        try {
            val host = host()
            host.graphInstalled(standIn(SharedReplicaHostState.UPDATING), stop = {}, rebuild = {})
            StorageStateSurface.attach(host)
            val seen = Seen()
            val observation = StorageStateSurface.observe(seen::add)
            seen.await("the cover, no button yet") { it.covers && it.title == "Updating storage…" && !it.canTryAgain }
            seen.await("Try again after the delay") { it.covers && it.canTryAgain }
            observation.cancel()
            // A final state never grows one.
            val skew = host()
            skew.graphInstalled(standIn(SharedReplicaHostState.SKEW, willRetry = false), stop = {}, rebuild = {})
            StorageStateSurface.attach(skew)
            val final = Seen()
            val second = StorageStateSurface.observe(final::add)
            Thread.sleepMs(700)
            assertTrue(final.all().none { it.canTryAgain }, "${final.all()}")
            second.cancel()
        } finally {
            StorageStateSurface.tryAgainAfterMs = saved
        }
    }

    @Test
    fun aHoldWithdrawsTryAgainAtOnce() {
        // B.17 (280): `hold` publishes nothing of its own, and the offer depends on it.
        val host = host()
        host.graphInstalled(standIn(SharedReplicaHostState.NOT_READY), stop = {}, rebuild = {})
        StorageStateSurface.attach(host)
        val seen = Seen()
        val observation = StorageStateSurface.observe(seen::add)
        seen.await("offered") { it.canTryAgain }
        host.hold("made up: a forget")
        seen.await("withdrawn") { it.covers && !it.canTryAgain }
        observation.cancel()
    }

    private class Seen {
        private val lock = NSRecursiveLock()
        private val list = ArrayList<StorageStateSnapshot>()
        fun add(snapshot: StorageStateSnapshot) {
            lock.lock(); try { list += snapshot } finally { lock.unlock() }
        }
        fun all(): List<StorageStateSnapshot> {
            lock.lock(); try { return list.toList() } finally { lock.unlock() }
        }
        fun awaitCount(n: Int) {
            val start = TimeSource.Monotonic.markNow()
            while (all().size < n) {
                if (start.elapsedNow().inWholeMilliseconds > 10_000) fail("fewer than $n snapshots: ${all()}")
                Thread.sleepMs(10)
            }
        }
        fun await(what: String, match: (StorageStateSnapshot) -> Boolean): StorageStateSnapshot {
            val start = TimeSource.Monotonic.markNow()
            while (true) {
                all().lastOrNull()?.takeIf(match)?.let { return it }
                if (start.elapsedNow().inWholeMilliseconds > 10_000) fail("$what: ${all()}")
                Thread.sleepMs(10)
            }
        }
    }

    private object Thread {
        fun sleepMs(ms: Int) {
            platform.posix.usleep((ms * 1_000).toUInt())
        }
    }
}
