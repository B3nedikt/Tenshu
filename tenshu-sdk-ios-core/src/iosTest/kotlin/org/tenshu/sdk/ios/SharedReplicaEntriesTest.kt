@file:OptIn(SharedReplicaPlatformApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package org.tenshu.sdk.ios

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.tenshu.core.common.proxy.db.ObjectStore.DbEntry
import org.tenshu.core.common.proxy.db.ObjectStore.DbValue
import org.tenshu.core.common.proxy.db.sqlite.ReplicaBuild
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaEntryToken
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaPlatformApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSThread
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_time
import kotlin.concurrent.AtomicInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.TimeSource

/**
 * Appendix B.15: **every entry wrapped.** [SharedReplicaEntries.withProtection] runs an entry's
 * work under an explicit token, releases it after, answers null (the work not done) when no time is
 * granted, and cancels the work when the token expires; with no binding installed it runs the
 * block as it is. And rule P's loops wait for what they cancelled (B.13 (169)).
 * Every name is made up.
 */
class SharedReplicaEntriesTest {

    private class Token : SharedReplicaEntryToken {
        val releases = AtomicInt(0)
        override fun release() {
            releases.incrementAndGet()
        }
    }

    private fun <T> withNoBinding(block: () -> T): T {
        val saved = SharedReplicaEntries.installedBinding
        SharedReplicaEntries.installedBinding = { null }
        try {
            return block()
        } finally {
            SharedReplicaEntries.installedBinding = saved
        }
    }

    // ── no binding installed ──

    @Test
    fun withNoBindingInstalledTheBlockRunsUnwrappedAsBefore() = withNoBinding {
        val before = SharedReplicaEntries.wrapped
        val answer = runBlocking { SharedReplicaEntries.withProtection("made-up") { "ran" } }
        assertEquals("ran", answer)
        assertEquals(before, SharedReplicaEntries.wrapped, "no token was asked for")
    }

    @Test
    fun theProcessHasNoInstalledBindingInTheTestHostSoEveryEntryIsUnwrapped() {
        // The production lookup: the test host installs no binding of its own.
        if (SharedReplicaBinding.processOrNull()?.isInstalled == true) return
        assertNull(SharedReplicaEntries.installedBinding())
    }

    // ── the token ──

    @Test
    fun theBlockRunsUnderTheTokenAndTheTokenIsReleasedAfter() = runBlocking {
        val token = Token()
        val answer = SharedReplicaEntries.protectedBy("made-up", { token }) {
            assertEquals(0, token.releases.value, "held while the block runs")
            "ran"
        }
        assertEquals("ran", answer)
        assertEquals(1, token.releases.value)
    }

    @Test
    fun aBlockThatThrowsStillReleasesItsToken() = runBlocking {
        val token = Token()
        val thrown = runCatching { SharedReplicaEntries.protectedBy("made-up", { token }) { error("made up") } }.exceptionOrNull()
        assertEquals("made up", thrown?.message)
        assertEquals(1, token.releases.value)
    }

    @Test
    fun noTimeGrantedMeansTheBlockNeverRuns() = runBlocking {
        var ran = false
        val refusedBefore = SharedReplicaEntries.refused
        val answer = SharedReplicaEntries.protectedBy("made-up", { null }) { ran = true }
        assertNull(answer)
        assertFalse(ran, "§5.4: an entry never falls back to running unprotected")
        assertEquals(refusedBefore + 1, SharedReplicaEntries.refused)
    }

    @Test
    fun anExpiryCancelsTheBlockAndAnswersNull() = runBlocking {
        val token = Token()
        var expire: (() -> Unit)? = null
        val started = TimeSource.Monotonic.markNow()
        val answer = SharedReplicaEntries.protectedBy("made-up", { onExpired -> expire = onExpired; token }) {
            expire?.invoke()
            delay(20_000)
            "late"
        }
        assertNull(answer, "cancelled by its expiry")
        assertTrue(started.elapsedNow().inWholeMilliseconds < 10_000)
        assertEquals(1, token.releases.value)
    }

    @Test
    fun anExpiryBeforeTheBlockStartsCancelsItAtItsFirstSuspension() = runBlocking {
        val token = Token()
        var reached = false
        val answer = SharedReplicaEntries.protectedBy("made-up", { onExpired -> onExpired(); token }) {
            delay(1)
            reached = true
            "late"
        }
        assertNull(answer)
        assertFalse(reached)
        assertEquals(1, token.releases.value)
    }

    @Test
    fun theCallersOwnCancellationIsRethrownNotTakenForAnExpiry() = runBlocking {
        val token = Token()
        var outcome = "none"
        val scope = CoroutineScope(Dispatchers.Default)
        val caller: Job = scope.launch {
            try {
                SharedReplicaEntries.protectedBy("made-up", { token }) { delay(20_000) }
                outcome = "returned"
            } catch (e: kotlinx.coroutines.CancellationException) {
                outcome = "cancelled"
                throw e
            }
        }
        delay(100)
        caller.cancelAndJoin()
        assertEquals("cancelled", outcome)
        assertEquals(1, token.releases.value)
        scope.cancel()
    }

    @Test
    fun aCallerCancelledWhileItsTokenIsTakenStillReleasesIt() = runBlocking {
        // The token is granted while the caller is being cancelled; it must
        // still be released, and the block must not run.
        val token = Token()
        var ran = false
        val acquiring = CompletableDeferred<Unit>()
        val scope = CoroutineScope(Dispatchers.Default)
        val caller = scope.launch {
            SharedReplicaEntries.protectedBy("made-up", {
                acquiring.complete(Unit)
                platform.posix.usleep(300_000u)
                token
            }) { ran = true }
        }
        acquiring.await()
        caller.cancelAndJoin()
        assertEquals(1, token.releases.value, "released, not leaked until the platform expires it")
        assertFalse(ran, "a cancelled caller's block never runs")
        scope.cancel()
    }

    // ── a real binding ──

    /** The slice of `UIApplication` the adapter uses, recording each assertion. "Main" is the test's thread. */
    private class EntryApp : UiKitApplication {
        private val main: NSThread = NSThread.currentThread
        private val lock = NSRecursiveLock()
        private var nextId = 0uL
        val begun = ArrayList<String>()
        val ended = ArrayList<String>()
        private val names = HashMap<ULong, String>()
        private val handlers = HashMap<String, () -> Unit>()
        private var onBackground: (() -> Unit)? = null

        // ⚠ `==`, never `===`, on an NSThread (K/N).
        override val isMainThread: Boolean get() = NSThread.currentThread == main
        override fun isInBackground(): Boolean = false
        override fun beginBackgroundTask(name: String, onExpired: () -> Unit): ULong? = locked {
            val id = ++nextId
            begun += name
            names[id] = name
            handlers[name] = onExpired
            id
        }

        override fun endBackgroundTask(id: ULong) {
            locked { ended += names.getValue(id) }
        }

        override fun backgroundTimeRemainingSeconds(): Double = 25.0
        override fun observeLifecycle(onDidEnterBackground: () -> Unit, onWillEnterForeground: () -> Unit) {
            onBackground = onDidEnterBackground
        }

        override fun runOnMainAsync(block: () -> Unit) = Unit
        override fun isProtectedDataAvailable(): Boolean = true

        fun postDidEnterBackground() = onBackground?.invoke() ?: fail("not observed")
        fun expire(name: String) = locked { handlers.getValue(name) }()
        fun begunNames(): List<String> = locked { begun.toList() }
        fun endedNames(): List<String> = locked { ended.toList() }

        private inline fun <T> locked(block: () -> T): T {
            lock.lock()
            try {
                return block()
            } finally {
                lock.unlock()
            }
        }
    }

    private fun tempDirectory(): String {
        val directory = NSTemporaryDirectory() + "entries-test-" + Random.nextLong().toString(16) + "/replica"
        NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
        return directory
    }

    private val build = ReplicaBuild(coreVersion = 22, coreFloor = 22, services = mapOf("example/file" to 1), serviceFloors = mapOf("example/file" to 1))

    private fun row(hash: Long) = DbEntry("example/file", hash, listOf(DbValue.LongValue(hash), DbValue.StringValue("made-up-$hash")))

    private fun within(timeoutMs: Long, what: String, done: () -> Boolean) {
        val start = TimeSource.Monotonic.markNow()
        while (!done()) {
            if (start.elapsedNow().inWholeMilliseconds > timeoutMs) fail("$what: not within $timeoutMs ms")
            platform.posix.usleep(10_000u)
        }
    }

    private fun backgroundedBinding(app: EntryApp): SharedReplicaBinding {
        val binding = SharedReplicaBinding(
            directory = tempDirectory(),
            appName = "app.example.entries",
            adapterFactory = { onJobOutcome ->
                SharedReplicaUiKitAdapter(
                    app = app,
                    queue = DispatchReplicaWorkerQueue("org.tenshu.replica.worker.entries-" + Random.nextLong().toString(16)),
                    log = {},
                    onJobOutcome = onJobOutcome,
                )
            },
        )
        binding.installAtLaunch()
        binding.open(build).storeObject(row(1))
        // The background: its token is taken and released, the quiesce closes the gates.
        app.postDidEnterBackground()
        within(15_000, "the background token's quiesce") { "background" in app.endedNames() }
        return binding
    }

    @Test
    fun anInstalledBindingTakesTheEntrysOwnAssertionAndItsWorkIsAdmittedUnderIt() {
        val app = EntryApp()
        val binding = backgroundedBinding(app)
        val store = binding.open(build)
        val saved = SharedReplicaEntries.installedBinding
        SharedReplicaEntries.installedBinding = { binding }
        try {
            val answer = runBlocking {
                SharedReplicaEntries.withProtection("made-up-wake") {
                    store.storeObject(row(2))
                    "written"
                }
            }
            assertEquals("written", answer)
        } finally {
            SharedReplicaEntries.installedBinding = saved
        }
        assertTrue("entry:made-up-wake" in app.begunNames(), "${app.begunNames()}")
        assertFalse("implicit" in app.begunNames(), "the entry's token admitted the write, not an implicit one: ${app.begunNames()}")
        within(15_000, "the entry's release") { "entry:made-up-wake" in app.endedNames() }
    }

    @Test
    fun anEntrysAssertionExpiringCancelsItsWorkAndEndsTheAssertion() {
        val app = EntryApp()
        val binding = backgroundedBinding(app)
        val saved = SharedReplicaEntries.installedBinding
        SharedReplicaEntries.installedBinding = { binding }
        try {
            val answer = runBlocking {
                SharedReplicaEntries.withProtection("made-up-wake") {
                    // The platform's expiration handler, from another thread, as it may come.
                    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 50_000_000), dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
                        app.expire("entry:made-up-wake")
                    }
                    delay(20_000)
                    "late"
                }
            }
            assertNull(answer, "cancelled by its expiry")
        } finally {
            SharedReplicaEntries.installedBinding = saved
        }
        within(15_000, "the expired assertion's end") { "entry:made-up-wake" in app.endedNames() }
    }

    // ── rule P's loops wait (B.13 (169), B.15 (205)) ──

    @Test
    fun awaitStoppedWaitsForTheLoopsOwnStopAndAwaitWhoseCancelRanInsideStop() {
        val cancelled = AtomicInt(0)
        val joined = AtomicInt(0)
        val tickDone = CompletableDeferred<Unit>()
        val scope = CoroutineScope(Dispatchers.Default)
        val loop = RuntimeLoop(
            "scheduler",
            start = {},
            stop = { fail("the loop's stopAndAwait is used when it has one") },
            stopAndAwait = {
                cancelled.value = 1
                tickDone.await()
                joined.value = 1
            },
        )
        val loops = SyncRuntimeLoops(joinScope = scope) { listOf(loop) }
        loops.stop()
        assertEquals(1, cancelled.value, "the cancel ran before stop() returned")
        assertEquals(0, joined.value)
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 200_000_000), dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            tickDone.complete(Unit)
        }
        val started = TimeSource.Monotonic.markNow()
        loops.awaitStopped(10_000)
        assertEquals(1, joined.value, "waited for the cancelled tick")
        assertTrue(started.elapsedNow().inWholeMilliseconds >= 100)
        scope.cancel()
    }

    @Test
    fun awaitStoppedGivesUpAtItsTimeout() {
        val scope = CoroutineScope(Dispatchers.Default)
        val loop = RuntimeLoop("scheduler", start = {}, stop = {}, stopAndAwait = { delay(60_000) })
        val loops = SyncRuntimeLoops(joinScope = scope) { listOf(loop) }
        loops.stop()
        val started = TimeSource.Monotonic.markNow()
        loops.awaitStopped(300)
        val tookMs = started.elapsedNow().inWholeMilliseconds
        assertTrue(tookMs in 250..5_000, "bounded by its timeout: $tookMs ms")
        scope.cancel()
    }
}
