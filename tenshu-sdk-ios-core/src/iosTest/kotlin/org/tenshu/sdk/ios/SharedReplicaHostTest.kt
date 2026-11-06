@file:OptIn(SharedReplicaPlatformApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package org.tenshu.sdk.ios

import app.tenshu.control.di.ProxyGraphJob
import app.tenshu.control.notifications.PendingRequestsRepository
import app.tenshu.control.sync.HostPairingMonitor
import app.tenshu.control.di.proxyGraphJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ClientRegistry
import org.tenshu.core.common.proxy.db.DatabaseLock
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore.DbEntry
import org.tenshu.core.common.proxy.db.ObjectStore.DbValue
import org.tenshu.core.common.proxy.db.StoreHandleFenced
import org.tenshu.core.common.proxy.db.sqlite.HoldCeilingExceeded
import org.tenshu.core.common.proxy.db.sqlite.ProtectionExpired
import org.tenshu.core.common.proxy.db.sqlite.ReplicaBuild
import org.tenshu.core.common.proxy.db.sqlite.ReplicaFence
import org.tenshu.core.common.proxy.db.sqlite.ReplicaOpenResult
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaDeletion
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaEntryToken
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaPlatformApi
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaStore
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaWipeStart
import org.tenshu.core.common.proxy.db.sqlite.StoreBeingChecked
import org.tenshu.core.common.proxy.db.sqlite.StoreNeedsForeground
import org.tenshu.core.common.proxy.db.sqlite.StoreUnavailable
import platform.Foundation.NSFileManager
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSThread
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.TimeSource

/**
 * Appendix B.16: **the fence lifecycle core.** The process's [SharedReplicaHost] stops a
 * graph whose store is fenced and rebuilds it through the binding's normal open, at the next
 * foreground (at most once per foreground), at once when the fence lands in the foreground, at a
 * protected entry, or when the runtime asks (Control's forget); and a retryable refusal at open is
 * "not ready, try again" (Q35), never the file store. Over a fake `UIApplication` whose "main
 * thread" is the test's own, the adapter's real worker queue and protection, and real stores in
 * temporary directories. Every name is made up.
 */
class SharedReplicaHostTest {

    /** The slice of `UIApplication` the adapter uses, recording each assertion and its handler. */
    private class HostApp(background: Boolean = false) : UiKitApplication {
        private val main: NSThread = NSThread.currentThread
        private val lock = NSRecursiveLock()
        private var nextId = 0uL
        private val ended = ArrayList<String>()
        private val begun = ArrayList<String>()
        private val names = HashMap<ULong, String>()
        private val handlers = HashMap<String, () -> Unit>()
        private var onBackground: (() -> Unit)? = null
        private var onForeground: (() -> Unit)? = null

        @kotlin.concurrent.Volatile
        var inBackground = background

        @kotlin.concurrent.Volatile
        var grant = true

        // ⚠ `==`, never `===`, on an NSThread (K/N).
        override val isMainThread: Boolean get() = NSThread.currentThread == main
        override fun isInBackground(): Boolean = inBackground
        override fun beginBackgroundTask(name: String, onExpired: () -> Unit): ULong? = locked {
            if (!grant) return@locked null
            val id = ++nextId
            begun += name
            names[id] = name
            handlers[name] = onExpired
            id
        }

        override fun endBackgroundTask(id: ULong) {
            locked { ended += names.getValue(id) }
        }

        override fun backgroundTimeRemainingSeconds(): Double = if (inBackground) 25.0 else Double.MAX_VALUE
        override fun observeLifecycle(onDidEnterBackground: () -> Unit, onWillEnterForeground: () -> Unit) {
            onBackground = onDidEnterBackground
            onForeground = onWillEnterForeground
        }

        override fun runOnMainAsync(block: () -> Unit) = Unit
        override fun isProtectedDataAvailable(): Boolean = true

        private var onProtectedData: ((Boolean) -> Unit)? = null
        override fun observeProtectedData(onChange: (Boolean) -> Unit) {
            onProtectedData = onChange
        }

        /** `UIApplicationProtectedDataDidBecomeAvailable`, posted on the test's "main" thread. */
        fun postProtectedDataAvailable() = onProtectedData?.invoke(true) ?: fail("not observed")

        fun postDidEnterBackground() {
            inBackground = true
            onBackground?.invoke() ?: fail("not observed")
        }

        fun postWillEnterForeground() {
            inBackground = false
            onForeground?.invoke() ?: fail("not observed")
        }

        fun expire(name: String) = locked { handlers.getValue(name) }()
        fun endedNames(): List<String> = locked { ended.toList() }
        fun begunNames(): List<String> = locked { begun.toList() }

        private inline fun <T> locked(block: () -> T): T {
            lock.lock()
            try {
                return block()
            } finally {
                lock.unlock()
            }
        }
    }

    private class Seams(
        /** Null, or a null answer: the production open. */
        var open: (() -> ReplicaOpenResult?)? = null,
    ) : SharedReplicaBinding.Seams {
        private val main: NSThread = NSThread.currentThread
        override val isMainThread: Boolean get() = NSThread.currentThread == main
        override fun isAppExtension(): Boolean = false

        override fun open(directory: String, build: ReplicaBuild, steps: org.tenshu.core.common.proxy.db.sqlite.ReplicaMigrationSteps, appName: String, foreground: Boolean, protectedDataAvailable: () -> Boolean): ReplicaOpenResult =
            open?.invoke() ?: SharedReplicaBinding.PlatformSeams.open(directory, build, steps, appName, foreground, protectedDataAvailable)

        override fun startWipe(directory: String, build: ReplicaBuild, appName: String, reason: String, protectedDataAvailable: () -> Boolean, beforeHandOff: (jobId: Long) -> Unit): SharedReplicaWipeStart =
            SharedReplicaBinding.PlatformSeams.startWipe(directory, build, appName, reason, protectedDataAvailable, beforeHandOff)

        override fun deleteFiles(directory: String): SharedReplicaDeletion = SharedReplicaBinding.PlatformSeams.deleteFiles(directory)
    }

    private fun tempDirectory(): String {
        val directory = NSTemporaryDirectory() + "host-test-" + Random.nextLong().toString(16) + "/replica"
        NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
        return directory
    }

    /** A worker queue that runs nothing until the test drains it, off the test's thread. */
    private class HeldQueue : ReplicaWorkerQueue {
        private val lock = NSRecursiveLock()
        private val held = ArrayList<() -> Unit>()
        override fun dispatch(block: () -> Unit) {
            lock.lock(); try { held += block } finally { lock.unlock() }
        }
        override fun repeating(intervalMs: Long, block: () -> Unit): ReplicaRepeatingTimer = ReplicaRepeatingTimer { }
        fun drain() {
            val blocks = run { lock.lock(); try { held.toList().also { held.clear() } } finally { lock.unlock() } }
            val done = dispatch_semaphore_create(0)
            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
                blocks.forEach { it() }
                dispatch_semaphore_signal(done)
            }
            dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
        }
    }

    private fun binding(app: HostApp, seams: Seams = Seams(), directory: String = tempDirectory(), queue: ReplicaWorkerQueue? = null): SharedReplicaBinding =
        SharedReplicaBinding(
            directory = directory,
            appName = "app.example.host",
            seams = seams,
            adapterFactory = { onJobOutcome ->
                SharedReplicaUiKitAdapter(
                    app = app,
                    queue = queue ?: DispatchReplicaWorkerQueue("org.tenshu.replica.worker.host-" + Random.nextLong().toString(16)),
                    log = {},
                    onJobOutcome = onJobOutcome,
                )
            },
            updatingWaitMs = 30_000,
        ).also {
            // A scheduled rebuild runs on another thread here: the test's thread is "main", and
            // it is busy waiting for the rebuild.
            it.host.mainContext = Dispatchers.Default
        }

    private val build = ReplicaBuild(coreVersion = 22, coreFloor = 22, services = mapOf("example/file" to 1), serviceFloors = mapOf("example/file" to 1))

    private fun row(hash: Long, payloadBytes: Int = 16) =
        DbEntry("example/file", hash, listOf(DbValue.LongValue(hash), DbValue.ByteArrayValue(ByteArray(payloadBytes) { 7 })))

    /** A row over `ROW_MAX` (512 KiB): rule (a), the handle fenced `COMMIT_FAILED`. */
    private fun oversized(hash: Long) = row(hash, payloadBytes = 600 * 1024)

    private fun within(timeoutMs: Long, what: String, done: () -> Boolean) {
        val start = TimeSource.Monotonic.markNow()
        while (!done()) {
            if (start.elapsedNow().inWholeMilliseconds > timeoutMs) fail("$what: not within $timeoutMs ms")
            platform.posix.usleep(10_000u)
        }
    }

    /**
     * A graph as a runtime would hand it to the host: the binding's store, its stop counted, and
     * a rebuild that opens through the binding's normal open and hands the new graph over.
     */
    private class Graphs(private val binding: SharedReplicaBinding, private val build: ReplicaBuild) {
        val stops = AtomicInt(0)
        val rebuilds = AtomicInt(0)

        @kotlin.concurrent.Volatile
        var current: ObjectStore? = null

        @kotlin.concurrent.Volatile
        var probe = true

        /** Run once between a not-ready open and the stand-in graph's install (a race seam). */
        @kotlin.concurrent.Volatile
        var beforeStandInInstall: (() -> Unit)? = null

        fun install() {
            // As the store module does (B.17 (274)): the finishes told before the open began.
            val finishedBefore = binding.host.finishedJobsNow()
            val store: ObjectStore = try {
                binding.open(build).also { binding.host.recordOpen(it, finishedBefore) }
            } catch (e: Exception) {
                val status = hostStatusOfOpenFailure(e)
                beforeStandInInstall?.let { hook -> beforeStandInInstall = null; hook() }
                // As the store module makes it (B.17 (261)): its accesses told to the host.
                NotReadyReplicaStore(status, onAccess = binding.host::onStandInAccess).also { binding.host.recordOpen(it, finishedBefore) }
            }
            current = store
            binding.host.graphInstalled(store, stop = { stops.incrementAndGet() }, rebuild = {
                rebuilds.incrementAndGet()
                install()
            }, mayRebuild = { probe })
        }

        val sqlite: SharedReplicaStore get() = current as? SharedReplicaStore ?: fail("not the SQLite store: $current")
    }

    private fun state(binding: SharedReplicaBinding): SharedReplicaHostState = binding.host.status.value.state

    /** Takes an entry token, fences the store by its expiry, and returns once the fence is seen. */
    private fun fenceByExpiry(app: HostApp, binding: SharedReplicaBinding, store: SharedReplicaStore, beforeTheNextStatement: () -> Unit = {}) {
        assertNotNull(binding.protectEntry("made-up-wake") {}, "time granted")
        val written = dispatch_semaphore_create(0)
        val goOn = dispatch_semaphore_create(0)
        val done = dispatch_semaphore_create(0)
        var refusal: Throwable? = null
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            refusal = runCatching {
                store.databaseLock.withLock {
                    store.storeObject(row(2))
                    dispatch_semaphore_signal(written)
                    dispatch_semaphore_wait(goOn, DISPATCH_TIME_FOREVER)
                    store.storeObject(row(3))
                }
            }.exceptionOrNull()
            dispatch_semaphore_signal(done)
        }
        dispatch_semaphore_wait(written, DISPATCH_TIME_FOREVER)
        // The platform's expiration handler, from another thread: it ends protection, so the
        // transaction's next statement is refused (B.15 (200)) while the drain waits for it.
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) { app.expire("entry:made-up-wake") }
        // Inside the expiry's 2 s drain, which waits for this transaction's rollback.
        platform.posix.usleep(300_000u)
        beforeTheNextStatement()
        dispatch_semaphore_signal(goOn)
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
        assertTrue(isProtectionExpired(refusal), "the expiry refused the next statement: $refusal")
        assertEquals(ReplicaFence.COMMIT_FAILED, store.fencedBy, "B.15 (204): rule (a)")
    }

    /** A function of its own: no type test on a try's value (K/N). */
    private fun isProtectionExpired(t: Throwable?): Boolean = t is ProtectionExpired

    // ── a graph over the store ──

    @Test
    fun aGraphOverTheSqliteStoreIsReadyAndNothingIsOwed() {
        val app = HostApp()
        val binding = binding(app)
        binding.installAtLaunch()
        val graphs = Graphs(binding, build).also { it.install() }
        graphs.sqlite.storeObject(row(1))
        assertEquals(SharedReplicaHostState.READY, state(binding))
        assertFalse(binding.host.isRebuildOwed)
        app.postWillEnterForeground()
        assertEquals(0, graphs.rebuilds.value, "nothing fenced: nothing rebuilt")
    }

    // ── B.15 (204): an access an expiry interrupted ──

    @Test
    fun anExpiryFencedGraphIsStoppedInTheBackgroundAndRebuiltAtTheNextForeground() {
        val app = HostApp()
        val binding = binding(app)
        binding.installAtLaunch()
        // The graph's loops are bound before it is handed over, as `assembleBase` does (251).
        val loops = Recorder()
        binding.loops.bind(loops)
        val graphs = Graphs(binding, build).also { it.install() }
        val fenced = graphs.sqlite
        fenced.storeObject(row(1))
        app.postDidEnterBackground()
        within(15_000, "the background token's quiesce") { "background" in app.endedNames() }
        fenceByExpiry(app, binding, fenced)

        // Steps 1–3, in the background: the state, the graph stopped, custody kept; no rebuild.
        within(10_000, "the host saw the fence") { state(binding) == SharedReplicaHostState.REFRESHING }
        within(10_000, "the graph was stopped") { graphs.stops.value == 1 }
        assertTrue("stop" in loops.calls(), "its loops stopped: ${loops.calls()}")
        assertEquals(0, graphs.rebuilds.value, "never rebuilt in the background (§6 step 4)")
        assertTrue(binding.host.isRebuildOwed)

        // Step 4 at the next foreground: released, opened anew, the new graph serves.
        app.postWillEnterForeground()
        within(10_000, "the foreground's scheduled rebuild") { graphs.rebuilds.value == 1 && state(binding) == SharedReplicaHostState.READY }
        assertEquals(SharedReplicaHostState.READY, state(binding))
        val rebuilt = graphs.sqlite
        assertTrue(rebuilt !== fenced, "a new handle")
        assertNull(rebuilt.fencedBy)
        rebuilt.storeObject(row(4))
        assertTrue(rebuilt.hasObject(1), "the committed row is there")
        assertFalse(rebuilt.hasObject(2), "the interrupted transaction was rolled back")
        assertTrue(rebuilt.hasObject(4), "and the rebuilt graph's writes succeed")
        // The old loops are unbound, not retired: rule P drives nothing of the old graph.
        loops.clear()
        binding.loops.start()
        assertEquals(emptyList(), loops.calls(), "the old graph's loops are never started again")
    }

    @Test
    fun aRegainedQuiesceFenceIsRebuiltInTheSameForeground() {
        // B.15 (204), Q38: the app is foregrounded within the drain after an expiry;
        // protection is back, but the interrupted access is fenced. The fence lands with the app
        // in the foreground, so the rebuild is scheduled at once.
        val app = HostApp()
        val binding = binding(app)
        binding.installAtLaunch()
        val graphs = Graphs(binding, build).also { it.install() }
        val fenced = graphs.sqlite
        fenced.storeObject(row(1))
        app.postDidEnterBackground()
        within(15_000, "the background token's quiesce") { "background" in app.endedNames() }
        fenceByExpiry(app, binding, fenced) {
            // REGAINED: the foreground arrives inside the expiry's drain, before the refusal.
            app.postWillEnterForeground()
            assertEquals(0, graphs.rebuilds.value, "nothing fenced yet at the foreground")
        }
        within(10_000, "the rebuild scheduled at the fence") { graphs.rebuilds.value == 1 && state(binding) == SharedReplicaHostState.READY }
        assertEquals(1, binding.host.rebuilds[SharedReplicaHost.Trigger.FOREGROUND])
        graphs.sqlite.storeObject(row(5))
        assertTrue(graphs.sqlite.hasObject(5))
    }

    // ── §6: at most once per foreground ──

    @Test
    fun aSecondFenceInTheSameForegroundWaitsForTheNextForeground() {
        val app = HostApp()
        val binding = binding(app)
        binding.installAtLaunch()
        val graphs = Graphs(binding, build).also { it.install() }
        graphs.sqlite.storeObject(row(1))
        // A commit failure in the foreground (rule (a)): rebuilt at once, this foreground's one.
        assertFailsWith<org.tenshu.core.common.proxy.db.sqlite.RowTooLarge> { graphs.sqlite.storeObject(oversized(2)) }
        within(10_000, "the first rebuild") { graphs.rebuilds.value == 1 && state(binding) == SharedReplicaHostState.READY }
        // The same failure again in the same foreground: no second rebuild, an error state.
        val second = graphs.sqlite
        assertFailsWith<org.tenshu.core.common.proxy.db.sqlite.RowTooLarge> { second.storeObject(oversized(3)) }
        within(10_000, "the second fence seen") { state(binding) == SharedReplicaHostState.NOT_READY }
        platform.posix.usleep(300_000u)
        assertEquals(1, graphs.rebuilds.value, "⚠⚠ a full disk cannot loop rebuilds: one per foreground")
        assertTrue(binding.host.status.value.willRetry)
        assertSame(second, graphs.current, "the fenced graph stays until the next foreground")
        // The next foreground rebuilds again.
        app.postDidEnterBackground()
        app.postWillEnterForeground()
        within(10_000, "the next foreground's rebuild") { graphs.rebuilds.value == 2 && state(binding) == SharedReplicaHostState.READY }
        assertEquals(SharedReplicaHostState.READY, state(binding))
        graphs.sqlite.storeObject(row(4))
        assertTrue(graphs.sqlite.hasObject(4))
    }

    // ── what is never rebuilt ──

    @Test
    fun aCorruptStoreIsNeverRebuilt() {
        val app = HostApp()
        val directory = tempDirectory()
        val binding = binding(app, directory = directory)
        binding.installAtLaunch()
        val graphs = Graphs(binding, build).also { it.install() }
        graphs.sqlite.storeObject(row(1))
        NSFileManager.defaultManager.createFileAtPath("$directory/CORRUPT", null, null)
        assertNull(graphs.sqlite.getObject(1), "fenced CORRUPT at the next access")
        assertEquals(ReplicaFence.CORRUPT, graphs.sqlite.fencedBy)
        within(10_000, "the host saw it") { state(binding) == SharedReplicaHostState.CORRUPT }
        within(10_000, "stopped") { graphs.stops.value == 1 }
        app.postWillEnterForeground()
        platform.posix.usleep(300_000u)
        assertEquals(0, graphs.rebuilds.value, "§6: never rebuilt, never repaired")
        assertFalse(binding.host.status.value.willRetry)
    }

    @Test
    fun aFenceOfAGraphAlreadyReplacedIsIgnored() {
        val app = HostApp()
        val binding = binding(app)
        binding.installAtLaunch()
        val first = Graphs(binding, build).also { it.install() }
        val old = first.sqlite
        // A newer graph handed to the host (here over the stand-in, one that is never retried):
        // then the old graph's store is fenced.
        val secondStops = AtomicInt(0)
        val status = SharedReplicaHostStatus(SharedReplicaHostState.NOT_READY, "made up", willRetry = false)
        binding.host.graphInstalled(NotReadyReplicaStore(status), stop = { secondStops.incrementAndGet() }, rebuild = { fail("not rebuilt") })
        assertEquals(1, secondStops.value, "a stand-in's graph is stopped at once")
        old.close()
        platform.posix.usleep(300_000u)
        app.postWillEnterForeground()
        platform.posix.usleep(300_000u)
        assertEquals(0, first.stops.value, "the old graph's fence is not acted on")
        assertEquals(1, secondStops.value, "nor taken for the new graph's")
        assertEquals(0, first.rebuilds.value)
        assertSame(status, binding.host.status.value, "the state is the new graph's")
    }

    @Test
    fun aFailedReleaseLatchesTheProcessAndNothingIsRebuilt() {
        var rebuilt = 0
        val host = SharedReplicaHost(isForegroundSource = { true }, release = { false }, unbindLoops = {}, mainContext = EmptyCoroutineContext, log = {})
        val status = SharedReplicaHostStatus(SharedReplicaHostState.NOT_READY, "made up", willRetry = true)
        host.graphInstalled(NotReadyReplicaStore(status), stop = {}, rebuild = { rebuilt++ })
        assertTrue(host.isRebuildOwed)
        assertFalse(host.rebuildNow())
        assertEquals(0, rebuilt, "§6 step 2b: a failed release means no step 4")
        assertFalse(host.status.value.willRetry)
        host.onForeground()
        assertEquals(0, rebuilt, "latched: never")
        assertFalse(host.isRebuildOwed)
    }

    @Test
    fun theShippingGateProbeRefusesARebuildAndLeavesItOwed() {
        val app = HostApp()
        val binding = binding(app)
        binding.installAtLaunch()
        val graphs = Graphs(binding, build).also { it.install() }
        graphs.probe = false
        assertFailsWith<org.tenshu.core.common.proxy.db.sqlite.RowTooLarge> { graphs.sqlite.storeObject(oversized(1)) }
        within(10_000, "the fence seen") { state(binding) == SharedReplicaHostState.REFRESHING }
        platform.posix.usleep(300_000u)
        assertEquals(0, graphs.rebuilds.value, "bridge gone: the app's own gate deprovisions, not the host")
        graphs.probe = true
        app.postWillEnterForeground()
        within(10_000, "rebuilt at the foreground") { graphs.rebuilds.value == 1 }
    }

    @Test
    fun aDeprovisionForgetsTheGraphAndNothingIsRebuilt() {
        val app = HostApp()
        val binding = binding(app)
        binding.installAtLaunch()
        val graphs = Graphs(binding, build).also { it.install() }
        graphs.sqlite.storeObject(row(1))
        // An app's custody purge (B.13 (171)): the store released and wiped, the graph gone for good.
        binding.wipeBeforeCustodyPurge(build) {}
        within(10_000, "stopped") { graphs.stops.value == 1 }
        app.postWillEnterForeground()
        platform.posix.usleep(300_000u)
        assertEquals(0, graphs.rebuilds.value, "a deprovisioned app's graph is never rebuilt")
        assertFalse(binding.host.isRebuildOwed)
    }

    // ── Q35: a retryable refusal at open ──

    @Test
    fun theOpensRefusalsAreToldApartRetryableFromNot() {
        fun of(e: Throwable) = hostStatusOfOpenFailure(e)
        assertEquals(SharedReplicaHostState.NOT_READY, of(ProtectionExpired("made up", 1, 1)).state)
        assertTrue(of(ProtectionExpired("made up", 1, 1)).willRetry, "Q35: ProtectionExpired")
        assertTrue(of(HoldCeilingExceeded(1, 1)).willRetry, "Q35: HoldCeilingExceeded")
        assertTrue(of(StoreUnavailable("made up")).willRetry, "Q35: StoreUnavailable")
        assertEquals(SharedReplicaHostState.CHECKING, of(StoreBeingChecked("made up")).state)
        assertTrue(of(StoreBeingChecked("made up")).willRetry)
        assertEquals(SharedReplicaHostState.UPDATING, of(StoreNeedsForeground("made up")).state)
        assertTrue(of(StoreNeedsForeground("made up")).willRetry)
        assertTrue(of(SharedReplicaNotReady("made up", state = SharedReplicaHostState.UPDATING, retryable = true)).willRetry)
        // Not retryable: the existing paths.
        assertFalse(of(SharedReplicaNotReady("made up", state = SharedReplicaHostState.SKEW)).willRetry)
        assertEquals(SharedReplicaHostState.SKEW, of(SharedReplicaNotReady("made up", state = SharedReplicaHostState.SKEW)).state)
        assertFalse(of(SharedReplicaNotReady("made up", state = SharedReplicaHostState.CORRUPT)).willRetry)
        assertFalse(of(IllegalStateException("made up")).willRetry)
    }

    @Test
    fun theNotReadyStandInAnswersAsAFencedHandle() {
        val store = NotReadyReplicaStore(SharedReplicaHostStatus(SharedReplicaHostState.NOT_READY, "made up", willRetry = true))
        assertNull(store.getObject(1))
        assertFalse(store.hasObject(1))
        assertFalse(store.isEmpty(), "never 'empty': that would tell the sync cursor the data was reset")
        assertNull(store.rowsOfTypes(setOf("example/file")), "null, never an empty list")
        assertNull(store.scanRowsOfType("example/file", 0, 5, 5))
        assertFailsWith<StoreHandleFenced> { store.storeObject(row(1)) }
        assertFailsWith<StoreHandleFenced> { store.removeObject(1) }
        assertFailsWith<StoreHandleFenced> { store.storeSyncCursor(0) }
        var fired = false
        store.setListener(1) { fired = true }
        assertFalse(fired)
        assertEquals(3, store.databaseLock.withLock { store.databaseLock.withLock { 3 } }, "the lock nests")
    }

    private fun runtime(binding: SharedReplicaBinding): FirstPartyLocalRuntime =
        FirstPartyLocalRuntime(FirstPartyLocalRuntime.Config(clientId = "app.example.host", secureStorageService = "test.host")).also {
            it.stillDeprovisioned = { true }
            it.sharedReplicaChoice = { ReplicaChoice.SQLITE }
            it.sharedReplicaSqliteBinding = { binding }
            it.rebuildProbe = { true }
        }

    private val devRelay = "https://relay.tenshu.xyz"

    @Test
    fun q35ARetryableRefusalAtABackgroundLaunchIsNotReadyWritesNoFileStoreAndTheNextEntryRebuilds() {
        // A background launch (relaunched by an event) whose open is refused retryably, here by
        // its protection expiring (the one trigger B.15 added to Q35).
        val app = HostApp(background = true)
        val seams = Seams(open = { throw ProtectionExpired("made up: the launch's protection", 1, 1) })
        val binding = binding(app, seams)
        val runtime = runtime(binding)
        var base: FirstPartyLocalRuntime.Base? = null
        fun install() {
            base = runtime.assembleBase(secureStorage = InMemorySecureStorageDelegate(), relayBaseUrl = devRelay)
            runtime.graphReady()
        }
        runtime.rebuildWith { install() }
        install()
        val notReady = base!!.koin.get<ObjectStore>()
        assertTrue(isNotReadyStandIn(notReady), "⚠⚠ Q35: never the file store")
        assertSame((notReady as NotReadyReplicaStore).databaseLock, base!!.koin.get<DatabaseLock>())
        assertEquals(SharedReplicaHostState.NOT_READY, state(binding))
        assertTrue(binding.host.status.value.willRetry)
        assertTrue(binding.host.isRebuildOwed)

        // The next protected entry retries, under its own token, before its work.
        seams.open = null
        val saved = SharedReplicaEntries.installedBinding
        SharedReplicaEntries.installedBinding = { binding }
        binding.host.mainContext = EmptyCoroutineContext
        try {
            val answer = runBlocking {
                SharedReplicaEntries.withProtection("made-up-wake") {
                    val store = base!!.koin.get<ObjectStore>()
                    store.storeObject(row(1))
                    store.hasObject(1)
                }
            }
            assertEquals(true, answer, "the entry's work ran over the rebuilt graph's SQLite store")
        } finally {
            SharedReplicaEntries.installedBinding = saved
        }
        assertEquals(1, binding.host.rebuilds[SharedReplicaHost.Trigger.ENTRY])
        assertTrue(base!!.koin.get<ObjectStore>() is SharedReplicaStore)
        assertEquals(SharedReplicaHostState.READY, state(binding))
    }

    @Test
    fun q35ANotReadyForegroundLaunchRebuildsAtTheNextForeground() {
        val app = HostApp()
        val seams = Seams(open = { throw StoreUnavailable("made up: protected data") })
        val binding = binding(app, seams)
        val graphs = Graphs(binding, build).also { binding.installAtLaunch(); it.install() }
        assertTrue(isNotReadyStandIn(graphs.current!!))
        within(10_000, "the stand-in's graph is stopped at once") { graphs.stops.value == 1 }
        seams.open = null
        app.postDidEnterBackground()
        app.postWillEnterForeground()
        within(10_000, "rebuilt at the foreground") { graphs.rebuilds.value == 1 && state(binding) == SharedReplicaHostState.READY }
        assertEquals(SharedReplicaHostState.READY, state(binding))
        graphs.sqlite.storeObject(row(1))
    }

    @Test
    fun q40ANewerBuildsOrACorruptStoreAtOpenGetsTheStandInWritesNoFileStoreAndIsGivenUp() {
        // B.17 (254), Q40: no fallback to the file store. A newer build's
        // store, or one marked corrupt, gives the graph the stand-in with its state, nothing is
        // retried in this process, and the graph is given up (238): no rebuild follows.
        listOf(
            ReplicaOpenResult.Skew("made up") to SharedReplicaHostState.SKEW,
            ReplicaOpenResult.Corrupt("made up") to SharedReplicaHostState.CORRUPT,
        ).forEach { (answer, expected) ->
            val app = HostApp()
            val binding = binding(app, Seams(open = { answer }))
            val runtime = runtime(binding)
            val base = runtime.assembleBase(secureStorage = InMemorySecureStorageDelegate(), relayBaseUrl = devRelay)
            var abandoned = 0
            runtime.onCurrentGraphAbandoned { abandoned++ }
            runtime.graphReady()
            val store = base.koin.get<ObjectStore>()
            assertTrue(isNotReadyStandIn(store), "⚠⚠ $expected: never the file store")
            assertSame((store as NotReadyReplicaStore).databaseLock, base.koin.get<DatabaseLock>(), "the lock follows the stand-in")
            assertEquals(expected, state(binding))
            assertFalse(binding.host.status.value.willRetry)
            assertFalse(binding.host.isRebuildOwed)
            assertEquals(1, abandoned, "$expected: given up, once")
            app.postWillEnterForeground()
            platform.posix.usleep(200_000u)
            assertTrue(binding.host.rebuilds.isEmpty(), "$expected: nothing rebuilt at a foreground")
            assertFalse(binding.host.tryAgain(), "$expected: no Try again")
        }
    }

    // ── the runtime's own wiring ──

    @Test
    fun aRuntimesFencedGraphIsReassembledThroughTheAppsReinstall() {
        val app = HostApp()
        val binding = binding(app)
        val runtime = runtime(binding)
        var base: FirstPartyLocalRuntime.Base? = null
        var installs = 0
        fun install() {
            installs++
            base = runtime.assembleBase(secureStorage = InMemorySecureStorageDelegate(), relayBaseUrl = devRelay)
            runtime.graphReady()
        }
        runtime.rebuildWith { install() }
        install()
        val first = base!!
        val store = first.koin.get<ObjectStore>() as? SharedReplicaStore ?: fail("not the SQLite store")
        assertEquals(SharedReplicaHostState.READY, state(binding))
        app.postDidEnterBackground()
        store.close() // made-up fence: the handle released under the graph
        within(10_000, "the host saw the fence") { state(binding) == SharedReplicaHostState.REFRESHING }
        app.postWillEnterForeground()
        within(10_000, "the app's reinstall ran once") { installs == 2 && state(binding) == SharedReplicaHostState.READY }
        val rebuilt = base!!.koin.get<ObjectStore>() as? SharedReplicaStore ?: fail("not the SQLite store")
        assertTrue(rebuilt !== store)
        assertSame(rebuilt.databaseLock, base!!.koin.get<DatabaseLock>())
        assertEquals(SharedReplicaHostState.READY, state(binding))
    }

    // ── cancellation during a rebuild ──

    private class Token : SharedReplicaEntryToken {
        val releases = AtomicInt(0)
        override fun release() {
            releases.incrementAndGet()
        }
    }

    @Test
    fun anEntryCancelledDuringItsRebuildReleasesItsTokenAndLeavesTheHostConsistent() {
        val app = HostApp()
        val binding = binding(app, Seams(open = { throw StoreUnavailable("made up") }))
        binding.installAtLaunch()
        val host = binding.host
        val started = dispatch_semaphore_create(0)
        val gate = dispatch_semaphore_create(0)
        val reinstalls = AtomicInt(0)
        val stand = NotReadyReplicaStore(SharedReplicaHostStatus(SharedReplicaHostState.NOT_READY, "made up", willRetry = true))
        var real: SharedReplicaStore? = null
        host.graphInstalled(stand, stop = {}, rebuild = {
            reinstalls.incrementAndGet()
            dispatch_semaphore_signal(started)
            dispatch_semaphore_wait(gate, DISPATCH_TIME_FOREVER)
            val opened = SharedReplicaBinding.PlatformSeams.open(binding.directory, build, NoMigrationChains, "app.example.host", true) { true }
            val store = (opened as ReplicaOpenResult.Ready).store
            real = store
            host.graphInstalled(store, stop = {}, rebuild = {})
        })
        val token = Token()
        var ran = false
        val scope = CoroutineScope(Dispatchers.Default)
        val caller = scope.launch {
            SharedReplicaEntries.protectedBy("made-up", { token }, retry = { host.retryAtEntry() }) { ran = true }
        }
        // Bounded: an entry that never retries must fail here, not hang the suite.
        assertEquals(0L, dispatch_semaphore_wait(started, platform.darwin.dispatch_time(platform.darwin.DISPATCH_TIME_NOW, 30_000_000_000L)), "the entry's retry started the rebuild")
        // Cancelled while the rebuild runs (on the "main" context): the rebuild goes on to its end.
        val cancelling = scope.launch { caller.cancelAndJoin() }
        platform.posix.usleep(100_000u)
        dispatch_semaphore_signal(gate)
        runBlocking { cancelling.join() }
        assertEquals(1, token.releases.value, "the entry's token is released")
        assertFalse(ran, "a cancelled entry's work never runs")
        assertEquals(1, reinstalls.value, "the rebuild ran once, to its end")
        val rebuiltStore = assertNotNull(real, "the rebuild opened the store")
        assertEquals(SharedReplicaHostState.READY, host.status.value.state, "consistent: the rebuilt graph serves")
        assertFalse(host.isRebuildOwed)
        // And the guard was released: a later fence of the new graph starts a rebuild again.
        assertFailsWith<org.tenshu.core.common.proxy.db.sqlite.RowTooLarge> { rebuiltStore.storeObject(oversized(9)) }
        within(10_000, "the new graph's rebuild started") { host.rebuilds[SharedReplicaHost.Trigger.FOREGROUND] == 1 }
        scope.cancel()
    }

    @Test
    fun anEntryCancelledBeforeItsRebuildStartsLeavesTheRebuildOwed() {
        val host = SharedReplicaHost(isForegroundSource = { false }, release = { true }, unbindLoops = {}, mainContext = Dispatchers.Default, log = {})
        var rebuilt = 0
        host.graphInstalled(NotReadyReplicaStore(SharedReplicaHostStatus(SharedReplicaHostState.NOT_READY, "made up", willRetry = true)), stop = {}, rebuild = { rebuilt++ })
        val token = Token()
        val acquiring = CompletableDeferred<Unit>()
        val scope = CoroutineScope(Dispatchers.Default)
        val caller = scope.launch {
            SharedReplicaEntries.protectedBy("made-up", {
                acquiring.complete(Unit)
                platform.posix.usleep(300_000u)
                token
            }, retry = { host.retryAtEntry() }) { fail("never runs") }
        }
        runBlocking {
            acquiring.await()
            caller.cancelAndJoin()
        }
        assertEquals(1, token.releases.value)
        assertEquals(0, rebuilt, "never started")
        assertTrue(host.isRebuildOwed, "still owed: the next entry or foreground retries")
        scope.cancel()
    }

    // ── B.16.8: nothing heavy on the foreground's notification ──

    @Test
    fun theForegroundOnlySchedulesTheRebuildAndReturnsAtOnce() {
        // B.16 (221): a rebuild (a release, an open, the app's install) never runs inside
        // `willEnterForeground`.
        val gate = dispatch_semaphore_create(0)
        val started = AtomicInt(0)
        val host = SharedReplicaHost(isForegroundSource = { true }, release = { true }, unbindLoops = {}, mainContext = Dispatchers.Default, log = {})
        host.graphInstalled(NotReadyReplicaStore(SharedReplicaHostStatus(SharedReplicaHostState.NOT_READY, "made up", willRetry = true)), stop = {}, rebuild = {
            started.incrementAndGet()
            // A slow rebuild, bounded so that a synchronous one fails the timing below, not hangs.
            dispatch_semaphore_wait(gate, platform.darwin.dispatch_time(platform.darwin.DISPATCH_TIME_NOW, 3_000_000_000L))
        })
        val began = TimeSource.Monotonic.markNow()
        host.onForeground()
        val tookMs = began.elapsedNow().inWholeMilliseconds
        assertTrue(tookMs < 1_000, "the foreground's hook returned at once: $tookMs ms")
        within(10_000, "the scheduled rebuild started") { started.value == 1 }
        dispatch_semaphore_signal(gate)
    }

    @Test
    fun aRebuildNeverWaitsForAJobAndTheJobsFinishRebuildsAgain() {
        // B.16 (227) and (226): a rebuild that meets a wipe the worker runs takes the stand-in at
        // once (UPDATING), never the 30 s wait; the job's finish rebuilds over the finished store.
        val app = HostApp()
        val queue = HeldQueue()
        val binding = binding(app, queue = queue)
        binding.installAtLaunch()
        val graphs = Graphs(binding, build).also { it.install() }
        graphs.sqlite.storeObject(row(1))
        // A wipe handed to the (held) worker, under a hold, as Control's forget does.
        assertEquals(SharedReplicaBinding.CustodyWipe.HANDED_OFF, binding.startCustodyWipe(build, onFinished = {}, rebuildAfter = true))
        val began = TimeSource.Monotonic.markNow()
        binding.host.resume(now = true)
        val tookMs = began.elapsedNow().inWholeMilliseconds
        assertTrue(tookMs < 5_000, "the rebuild did not wait for the worker: $tookMs ms")
        assertTrue(isNotReadyStandIn(graphs.current!!), "the stand-in while the wipe runs")
        assertEquals(SharedReplicaHostState.UPDATING, state(binding))
        assertTrue(binding.host.status.value.willRetry)
        queue.drain()
        within(10_000, "the job's finish rebuilt over the finished store") { state(binding) == SharedReplicaHostState.READY }
        assertEquals(1, binding.host.rebuilds[SharedReplicaHost.Trigger.JOB_FINISHED])
        assertFalse(graphs.sqlite.hasObject(1), "wiped")
        graphs.sqlite.storeObject(row(2))
        assertTrue(graphs.sqlite.hasObject(2))
    }

    @Test
    fun aRuntimesGraphComponentsStopWithItsGraphAndTheOldGraphIsClosedAtTheRebuild() {
        // B.16 (228), (229): what an app registers with onCurrentGraphStop runs once, when its graph
        // is stopped (never before), and the rebuild closes the old Koin before it builds the new.
        val app = HostApp()
        val binding = binding(app)
        val runtime = runtime(binding)
        var base: FirstPartyLocalRuntime.Base? = null
        val stopped = ArrayList<Int>()
        var generation = 0
        fun install() {
            base = runtime.assembleBase(secureStorage = InMemorySecureStorageDelegate(), relayBaseUrl = devRelay)
            val mine = ++generation
            runtime.onCurrentGraphStop { stopped += mine }
            runtime.graphReady()
        }
        runtime.rebuildWith { install() }
        install()
        val first = base!!
        val store = first.koin.get<ObjectStore>() as? SharedReplicaStore ?: fail("not the SQLite store")
        assertEquals(emptyList(), stopped, "nothing stops before a fence")
        app.postDidEnterBackground()
        store.close()
        within(10_000, "the first graph's components stopped") { stopped == listOf(1) }
        assertEquals(0, runtime.closedGraphs, "closed only at the rebuild")
        app.postWillEnterForeground()
        within(10_000, "rebuilt") { generation == 2 && state(binding) == SharedReplicaHostState.READY }
        assertEquals(1, runtime.closedGraphs, "the old graph was closed")
        assertEquals(listOf(1), stopped, "the new graph's components are not stopped")
        assertTrue(runCatching { first.koin.get<ObjectStore>() }.isFailure, "nothing more resolves from the closed graph")
    }

    @Test
    fun controlsPairingMonitorAndPendingRequestsFeedOverTheStandInDoNotAbortTheProcess() {
        // The stand-in's reads answer absent, so the session's `initFileService`
        // saves "/" and the stand-in throws `StoreNotReady`, out of whichever call mints the session.
        // The monitor's resume and the feed's first poll and streams start at construction, on
        // scopes with no exception handler, and an uncaught throw there aborts a K/N process
        // (signal 6): this test then dies rather than fails. ⚠ Racy as a guard: the announcer
        // (which catches) may mint first, and then nothing here meets the throw.
        val app = HostApp()
        val binding = binding(app, Seams(open = { ReplicaOpenResult.Skew("made up") }))
        val runtime = runtime(binding)
        val base = runtime.assembleBase(secureStorage = InMemorySecureStorageDelegate(), relayBaseUrl = devRelay)
        runtime.graphReady()
        assertTrue(isNotReadyStandIn(base.koin.get<ObjectStore>()), "the graph is over the stand-in")
        // As `ControlSurface.installRealRuntime` resolves them.
        val monitor = base.koin.get<HostPairingMonitor>()
        val feed = base.koin.get<PendingRequestsRepository>()
        feed.refresh()
        platform.posix.usleep(1_500_000u)
        assertFalse(monitor.isPairing.value, "nothing adopted over the stand-in")
        assertEquals(emptyList(), feed.requests.value)
        val graph = proxyGraphJob(base.koin)
        listOf(ProxyGraphJob.Owners.HOST_PAIRING_MONITOR, ProxyGraphJob.Owners.PENDING_REQUESTS).forEach { owner ->
            assertTrue(graph.scopes.getValue(owner).coroutineContext[Job]!!.isActive, "$owner's scope stopped")
        }
        graph.cancel()
    }

    @Test
    fun aRebuildCancelsTheOldGraphsScopesAfterItsStopAndNeverTheNewOnes() {
        // Q45 (B.16.8 (229)): the old graph's root job, and every scope its proxy module made from
        // it (serviceScope and the session scopes under it, the announcer's, the stream listener's:
        // what a route-1 assembly resolves; the others are `ProxyGraphJobTeardownTest`'s), is
        // cancelled by the rebuild's Koin close: after its stop (still
        // running while the stopped graph waits for the foreground), never the new graph's.
        val app = HostApp()
        val binding = binding(app)
        val runtime = runtime(binding)
        var base: FirstPartyLocalRuntime.Base? = null
        var generation = 0
        val stopped = ArrayList<Int>()
        fun install() {
            base = runtime.assembleBase(secureStorage = InMemorySecureStorageDelegate(), relayBaseUrl = devRelay)
            val mine = ++generation
            runtime.onCurrentGraphStop { stopped += mine }
            runtime.graphReady()
        }
        runtime.rebuildWith { install() }
        install()
        val first = base!!
        val oldGraph = proxyGraphJob(first.koin)
        val registry = first.koin.get<ClientRegistry>()
        val session = registry.getExecutionContext("app.example.host").sessionScope
        val owners = listOf(
            ProxyGraphJob.Owners.SERVICE_SCOPE,
            ProxyGraphJob.Owners.CLIENT_STREAM,
            ProxyGraphJob.Owners.SCHEMA_ANNOUNCER,
        )
        val oldScopes = owners.associateWith { owner -> assertNotNull(oldGraph.scopes[owner], "the graph made no $owner scope") }
        assertSame(oldScopes.getValue(ProxyGraphJob.Owners.SERVICE_SCOPE), registry.serviceScope)
        val work = oldScopes.getValue(ProxyGraphJob.Owners.SERVICE_SCOPE).launch { awaitCancellation() }

        val store = first.koin.get<ObjectStore>() as? SharedReplicaStore ?: fail("not the SQLite store")
        app.postDidEnterBackground()
        store.close()
        within(10_000, "the first graph stopped") { stopped == listOf(1) }
        // ⚠ The stop does not cancel: a stopped graph serves until the rebuild (B.16 (230)).
        assertTrue(oldGraph.job.isActive, "the graph's scopes were cancelled at the stop, before the rebuild")
        assertTrue(work.isActive)

        app.postWillEnterForeground()
        within(10_000, "rebuilt") { generation == 2 && state(binding) == SharedReplicaHostState.READY }
        assertTrue(oldGraph.job.isCancelled, "the old graph's root job outlived the rebuild")
        oldScopes.forEach { (owner, scope) ->
            assertTrue(scope.coroutineContext[Job]!!.isCancelled, "the old graph's $owner scope outlived the rebuild")
        }
        assertTrue(session.coroutineContext[Job]!!.isCancelled, "the old graph's session scope outlived the rebuild")
        within(10_000, "the old graph's work was cancelled") { work.isCancelled && work.isCompleted }

        val newGraph = proxyGraphJob(base!!.koin)
        assertTrue(newGraph !== oldGraph, "the rebuilt graph shares the old graph's root")
        assertTrue(newGraph.job.isActive, "the rebuilt graph's root job was cancelled")
        assertTrue(newGraph.scopes.values.all { it.coroutineContext[Job]!!.isActive }, "a scope of the rebuilt graph was cancelled")
    }

    // ── B.16.9: components, entries and background rebuilds ──

    @Test
    fun aComponentStartedOverAStandInGraphIsStoppedWithItAndTheRebuildRunsANewOne() {
        // The stand-in graph is stopped as it is handed to the
        // host, which is only at graphReady, after the app's components are installed over it and
        // registered: none is left running over it. Then stand-in → rebuild → the SQLite store.
        val app = HostApp()
        val seams = Seams(open = { throw ProtectionExpired("made up", 1, 1) })
        val binding = binding(app, seams)
        val runtime = runtime(binding)
        val events = ArrayList<String>()
        var generation = 0
        fun install() {
            runtime.assembleBase(secureStorage = InMemorySecureStorageDelegate(), relayBaseUrl = devRelay)
            val mine = ++generation
            runtime.onCurrentGraphStop { events += "stopped $mine" }
            events += "started $mine"
            runtime.graphReady()
        }
        runtime.rebuildWith { install() }
        install()
        assertEquals(listOf("started 1", "stopped 1"), events, "the component over the stand-in is stopped with its graph")
        assertEquals(SharedReplicaHostState.NOT_READY, state(binding))
        seams.open = null
        app.postDidEnterBackground()
        app.postWillEnterForeground()
        within(10_000, "rebuilt over the SQLite store") { generation == 2 && state(binding) == SharedReplicaHostState.READY }
        assertEquals(listOf("started 1", "stopped 1", "started 2"), events, "the rebuilt graph's component runs, the stand-in's stays stopped")
    }

    @Test
    fun aComponentRegisteredOnAGraphAlreadyStoppedIsStoppedAtOnce() {
        val stops = FirstPartyLocalRuntime.GraphStops()
        val seen = ArrayList<String>()
        stops.add { seen += "early" }
        stops.runAll()
        assertEquals(listOf("early"), seen)
        stops.add { seen += "late" }
        assertEquals(listOf("early", "late"), seen, "a late registration never leaves a component over a stopped graph")
        stops.runAll()
        assertEquals(listOf("early", "late"), seen, "each block once")
    }

    @Test
    fun aJobThatFinishesWhileARebuildRunsIsNotLost() {
        // The finish lands between the rebuild's open (`Updating`, the stand-in) and the stand-in
        // graph's install, while the host still shows the old state: latched, and rebuilt from.
        val app = HostApp()
        val queue = HeldQueue()
        val binding = binding(app, queue = queue)
        binding.installAtLaunch()
        val graphs = Graphs(binding, build).also { it.install() }
        graphs.sqlite.storeObject(row(1))
        assertEquals(SharedReplicaBinding.CustodyWipe.HANDED_OFF, binding.startCustodyWipe(build, onFinished = {}, rebuildAfter = true))
        graphs.beforeStandInInstall = {
            queue.drain()
            within(10_000, "the worker's wipe finished") { binding.lastOutcome()?.kind == org.tenshu.core.common.proxy.db.sqlite.SharedReplicaJobOutcome.Kind.REPLACEMENT_FINISHED }
        }
        binding.host.resume(now = true)
        within(10_000, "the missed finish rebuilt over the wiped store") { state(binding) == SharedReplicaHostState.READY }
        assertEquals(1, binding.host.rebuilds[SharedReplicaHost.Trigger.JOB_FINISHED])
        platform.posix.usleep(300_000u)
        // B.17 (287): the install replayed the finish; the rebuild's own check does not again.
        assertEquals(1, binding.host.jobFinishedScheduled, "one finish, one rebuild scheduled")
        assertFalse(graphs.sqlite.hasObject(1), "wiped")
    }

    @Test
    fun anEntrysRebuildKeepsItsBoundedWaitForAJob() {
        // (227) is FOREGROUND, EXPLICIT and JOB_FINISHED only: a background entry that meets a
        // wipe the worker runs waits for it and is served.
        val app = HostApp()
        val queue = HeldQueue()
        val binding = binding(app, queue = queue)
        binding.installAtLaunch()
        val graphs = Graphs(binding, build).also { it.install() }
        graphs.sqlite.storeObject(row(1))
        assertEquals(SharedReplicaBinding.CustodyWipe.HANDED_OFF, binding.startCustodyWipe(build, onFinished = {}, rebuildAfter = true))
        // ⚠ B.17 (289): a BACKGROUND entry, as the case is. A job's finish rebuilds only in the
        // foreground, so the strict check below holds; the foreground race is (283)'s own test.
        binding.host.foregroundForTests = { false }
        binding.host.resume(now = false)
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            platform.posix.usleep(300_000u)
            queue.drain()
        }
        runBlocking { binding.host.retryAtEntry() }
        assertEquals(SharedReplicaHostState.READY, state(binding), "the entry's open waited for the wipe and was served")
        assertEquals(1, binding.host.rebuilds[SharedReplicaHost.Trigger.ENTRY])
        assertNull(binding.host.rebuilds[SharedReplicaHost.Trigger.JOB_FINISHED], "never the stand-in first")
    }

    @Test
    fun aFenceInsideABackgroundEntryRebuildsOnceUntilTheNextForeground() {
        // (232): a fence in the background while an entry runs is rebuilt then,
        // not left until the user opens the app; once, so a failing store cannot loop rebuilds.
        val app = HostApp()
        val binding = binding(app)
        binding.installAtLaunch()
        binding.host.entryActive = { true }
        val graphs = Graphs(binding, build).also { it.install() }
        graphs.sqlite.storeObject(row(1))
        app.postDidEnterBackground()
        within(15_000, "the background token's quiesce") { "background" in app.endedNames() }
        graphs.sqlite.close()
        within(10_000, "rebuilt inside the entry") { graphs.rebuilds.value == 1 && state(binding) == SharedReplicaHostState.READY }
        assertEquals(1, binding.host.rebuilds[SharedReplicaHost.Trigger.ENTRY])
        // (239) Under a token of its own, taken before and released after.
        assertTrue("entry:rebuild" in app.begunNames(), "${app.begunNames()}")
        within(10_000, "the rebuild's token released") { "entry:rebuild" in app.endedNames() }
        graphs.sqlite.close()
        within(10_000, "the second fence seen") { state(binding) == SharedReplicaHostState.REFRESHING }
        platform.posix.usleep(300_000u)
        assertEquals(1, graphs.rebuilds.value, "once until the next foreground")
    }

    @Test
    fun aBackgroundRebuildWithNoProtectedTimeIsNotRun() {
        // (239): never an unprotected open of the App Group store.
        val app = HostApp()
        val binding = binding(app)
        binding.installAtLaunch()
        binding.host.entryActive = { true }
        val graphs = Graphs(binding, build).also { it.install() }
        graphs.sqlite.storeObject(row(1))
        app.postDidEnterBackground()
        within(15_000, "the background token's quiesce") { "background" in app.endedNames() }
        app.grant = false
        graphs.sqlite.close()
        within(10_000, "the fence seen") { state(binding) == SharedReplicaHostState.REFRESHING }
        platform.posix.usleep(500_000u)
        assertEquals(0, graphs.rebuilds.value, "no token, no rebuild")
        assertTrue(binding.host.isRebuildOwed, "left to the next entry or foreground")
    }

    @Test
    fun aGraphGivenUpWithNoRebuildToFollowIsAbandonedOnce() {
        // (238): a corrupt store, a latched process, a refused probe, a deprovision, and a fence
        // after the latch each run the graph's abandon, once.
        val stand = { NotReadyReplicaStore(SharedReplicaHostStatus(SharedReplicaHostState.NOT_READY, "made up", willRetry = true)) }
        // A refused probe.
        run {
            var abandoned = 0
            val host = SharedReplicaHost(isForegroundSource = { true }, release = { true }, unbindLoops = {}, mainContext = EmptyCoroutineContext, log = {})
            host.graphInstalled(stand(), stop = {}, rebuild = { fail("not rebuilt") }, mayRebuild = { false }, abandon = { abandoned++ })
            host.rebuildNow()
            host.rebuildNow()
            assertEquals(1, abandoned, "a refused probe, once")
        }
        // A latched process, then a later fence after the latch.
        run {
            var abandoned = 0
            val host = SharedReplicaHost(isForegroundSource = { true }, release = { false }, unbindLoops = {}, mainContext = EmptyCoroutineContext, log = {})
            host.graphInstalled(stand(), stop = {}, rebuild = { fail("not rebuilt") }, abandon = { abandoned++ })
            assertFalse(host.rebuildNow())
            assertEquals(1, abandoned, "latched")
            val app = HostApp()
            val binding = binding(app)
            binding.installAtLaunch()
            val store = binding.open(build)
            var later = 0
            host.graphInstalled(store, stop = {}, rebuild = { fail("not rebuilt") }, abandon = { later++ })
            store.close()
            within(10_000, "the fence after the latch abandons") { later == 1 }
        }
        // A deprovision.
        run {
            var abandoned = 0
            val host = SharedReplicaHost(isForegroundSource = { true }, release = { true }, unbindLoops = {}, mainContext = EmptyCoroutineContext, log = {})
            host.graphInstalled(stand(), stop = {}, rebuild = {}, abandon = { abandoned++ })
            host.forgetGraph("made up")
            assertEquals(1, abandoned)
        }
        // A corrupt store.
        run {
            val app = HostApp()
            val directory = tempDirectory()
            val binding = binding(app, directory = directory)
            binding.installAtLaunch()
            val store = binding.open(build)
            var abandoned = 0
            binding.host.graphInstalled(store, stop = {}, rebuild = { fail("never rebuilt") }, abandon = { abandoned++ })
            NSFileManager.defaultManager.createFileAtPath("$directory/CORRUPT", null, null)
            assertNull(store.getObject(1))
            within(10_000, "a corrupt store abandons") { abandoned == 1 }
        }
    }

    @Test
    fun aReinstallThatThrowsAfterAssemblingStopsItsHalfBuiltGraph() {
        // (240): the half-built graph's components are not left running.
        val app = HostApp()
        val binding = binding(app)
        val runtime = runtime(binding)
        val events = ArrayList<String>()
        var generation = 0
        var base: FirstPartyLocalRuntime.Base? = null
        fun install() {
            base = runtime.assembleBase(secureStorage = InMemorySecureStorageDelegate(), relayBaseUrl = devRelay)
            val mine = ++generation
            runtime.onCurrentGraphStop { events += "stopped $mine" }
            if (mine == 2) error("made up: the reinstall failed after it assembled")
            runtime.graphReady()
        }
        runtime.rebuildWith { install() }
        install()
        (base!!.koin.get<ObjectStore>() as SharedReplicaStore).close()
        within(10_000, "the rebuild ran and failed") { generation == 2 && state(binding) == SharedReplicaHostState.NOT_READY }
        within(10_000, "the half-built graph's components stopped") { "stopped 2" in events }
        assertEquals(listOf("stopped 1", "stopped 2"), events)
        // Q45: never handed over and never closed, so its root job is cancelled with its components.
        val halfBuilt = proxyGraphJob(base!!.koin)
        within(10_000, "the half-built graph's scopes were cancelled") { halfBuilt.job.isCancelled }
    }

    // ── B.16 (251): a stop and a rebuild never overlap ──

    @Test
    fun aLateStopNeverReachesTheRebuiltGraphsLoops() {
        // The fence collector claims the stop and is paused before its steps; a foreground
        // rebuild meanwhile waits for the stop to end (it never releases or installs early), and
        // the stop, resumed, unbinds only its own graph's loops. The rebuilt graph's stay bound.
        val app = HostApp()
        val binding = binding(app)
        binding.installAtLaunch()
        val loops = DeferredReplicaLoops()
        val loopsA = Recorder()
        loops.bind(loopsA)
        val host = SharedReplicaHost(
            isForegroundSource = { true },
            release = { true },
            unbindLoops = { mine -> loops.unbindIf(mine) },
            currentLoops = { loops.currentBinding() },
            mainContext = Dispatchers.Default,
            log = {},
        )
        val lock = NSRecursiveLock()
        val events = ArrayList<String>()
        fun event(e: String) { lock.lock(); try { events += e } finally { lock.unlock() } }
        fun events(): List<String> { lock.lock(); try { return events.toList() } finally { lock.unlock() } }
        val paused = dispatch_semaphore_create(0)
        val resume = dispatch_semaphore_create(0)
        host.afterStopClaimed = {
            host.afterStopClaimed = null
            dispatch_semaphore_signal(paused)
            dispatch_semaphore_wait(resume, platform.darwin.dispatch_time(platform.darwin.DISPATCH_TIME_NOW, 10_000_000_000L))
        }
        val loopsB = Recorder()
        val storeA = binding.open(build)
        host.graphInstalled(storeA, stop = { event("A stopped") }, rebuild = {
            event("B rebuilt")
            loops.bind(loopsB)
            host.graphInstalled(binding.open(build), stop = {}, rebuild = {})
        })
        storeA.close()
        assertEquals(0L, dispatch_semaphore_wait(paused, platform.darwin.dispatch_time(platform.darwin.DISPATCH_TIME_NOW, 10_000_000_000L)), "the collector claimed the stop")
        host.onForeground()
        platform.posix.usleep(300_000u)
        assertEquals(emptyList(), events(), "⚠⚠ no rebuild while the claimed stop has not ended")
        dispatch_semaphore_signal(resume)
        within(10_000, "rebuilt after the stop") { events() == listOf("A stopped", "B rebuilt") && host.status.value.state == SharedReplicaHostState.READY }
        platform.posix.usleep(200_000u)
        assertSame(loopsB, loops.currentBinding(), "the rebuilt graph's loops are still bound")
        assertFalse("stop" in loopsB.calls(), "and never stopped by the old graph's teardown")
        assertTrue("stop" in loopsA.calls(), "the old graph's loops were stopped")
    }

    @Test
    fun aRebuildHeldBackByAClaimedStopComesBackWhenTheClaimerSchedulesNothing() {
        // (253): the fence collector found the app in the background with no
        // entry, so it schedules nothing after its stop; a foreground rebuild meanwhile met the
        // claimed stop. It must come back, once, when the stop ends.
        val app = HostApp()
        val binding = binding(app)
        binding.installAtLaunch()
        val answers = kotlin.concurrent.AtomicInt(0)
        // The retry asks first (foreground by then), the collector's own check after its stop second.
        val host = SharedReplicaHost(
            isForegroundSource = { answers.incrementAndGet() == 1 },
            release = { true },
            unbindLoops = {},
            mainContext = Dispatchers.Default,
            log = {},
        )
        host.entryActive = { false }
        val rebuilds = kotlin.concurrent.AtomicInt(0)
        val storeA = binding.open(build)
        host.graphInstalled(storeA, stop = {}, rebuild = {
            rebuilds.incrementAndGet()
            host.graphInstalled(binding.open(build), stop = {}, rebuild = {})
        })
        val resume = run {
            val paused = dispatch_semaphore_create(0)
            val go = dispatch_semaphore_create(0)
            host.afterStopClaimed = {
                host.afterStopClaimed = null
                dispatch_semaphore_signal(paused)
                dispatch_semaphore_wait(go, platform.darwin.dispatch_time(platform.darwin.DISPATCH_TIME_NOW, 10_000_000_000L))
            }
            storeA.close()
            assertEquals(0L, dispatch_semaphore_wait(paused, platform.darwin.dispatch_time(platform.darwin.DISPATCH_TIME_NOW, 10_000_000_000L)), "the collector claimed the stop")
            ({ dispatch_semaphore_signal(go) })
        }
        host.onForeground()
        platform.posix.usleep(300_000u)
        assertEquals(0, rebuilds.value, "held back while the stop runs")
        resume()
        within(10_000, "the held-back rebuild came back") { rebuilds.value == 1 && host.status.value.state == SharedReplicaHostState.READY }
        platform.posix.usleep(300_000u)
        assertEquals(1, rebuilds.value, "exactly once")
    }

    @Test
    fun aBackgroundEntryHeldBackByAClaimedStopIsRetriedUnderItsOwnToken() {
        // (253): a background entry meets the claimed stop and ends before the
        // stop does; the collector sees neither the foreground nor an entry. The retry, in the
        // background, takes `entry:rebuild`, and with no time granted runs not at all.
        for (granted in listOf(true, false)) {
            val app = HostApp()
            val binding = binding(app)
            binding.installAtLaunch()
            binding.host.entryActive = { false }
            val graphs = Graphs(binding, build).also { it.install() }
            graphs.sqlite.storeObject(row(1))
            app.postDidEnterBackground()
            within(15_000, "the background token's quiesce") { "background" in app.endedNames() }
            val paused = dispatch_semaphore_create(0)
            val go = dispatch_semaphore_create(0)
            binding.host.afterStopClaimed = {
                binding.host.afterStopClaimed = null
                dispatch_semaphore_signal(paused)
                dispatch_semaphore_wait(go, platform.darwin.dispatch_time(platform.darwin.DISPATCH_TIME_NOW, 10_000_000_000L))
            }
            graphs.sqlite.close()
            assertEquals(0L, dispatch_semaphore_wait(paused, platform.darwin.dispatch_time(platform.darwin.DISPATCH_TIME_NOW, 10_000_000_000L)), "the collector claimed the stop")
            // The entry's retry meets the claimed stop, and the entry ends.
            binding.host.mainContext = EmptyCoroutineContext
            runBlocking { binding.host.retryAtEntry() }
            binding.host.mainContext = Dispatchers.Default
            assertEquals(0, graphs.rebuilds.value)
            app.grant = granted
            dispatch_semaphore_signal(go)
            if (granted) {
                within(10_000, "retried in the background") { graphs.rebuilds.value == 1 && state(binding) == SharedReplicaHostState.READY }
                assertTrue("entry:rebuild" in app.begunNames(), "under its own token: ${app.begunNames()}")
            } else {
                platform.posix.usleep(500_000u)
                assertEquals(0, graphs.rebuilds.value, "⚠⚠ never unprotected: no time, no rebuild")
                assertTrue(binding.host.isRebuildOwed, "still owed")
            }
        }
    }

    // ── B.17 ──

    @Test
    fun q41ALaunchDuringAWipeTakesTheStandInAtOnceAndTheJobsFinishInstalls() {
        // B.17 (258), Q41: no open on the graph's path waits for the worker. A launch that meets a
        // wipe the worker runs is "Refreshing…" on the stand-in at once; the job's finish installs.
        val app = HostApp()
        val queue = HeldQueue()
        val binding = binding(app, queue = queue)
        binding.installAtLaunch()
        Graphs(binding, build).also { it.install() }.sqlite.storeObject(row(1))
        // An app's custody purge hands the wipe to the (held) worker and forgets the graph.
        assertEquals(SharedReplicaBinding.CustodyWipe.HANDED_OFF, binding.startCustodyWipe(build, onFinished = {}, rebuildAfter = false))
        assertEquals(SharedReplicaHostState.NO_GRAPH, state(binding), "B.17 (255): no graph, nothing to cover")
        val began = TimeSource.Monotonic.markNow()
        val launched = Graphs(binding, build).also { it.install() }
        assertTrue(began.elapsedNow().inWholeMilliseconds < 5_000, "⚠⚠ the launch did not wait for the worker")
        assertTrue(isNotReadyStandIn(launched.current!!))
        assertEquals(SharedReplicaHostState.UPDATING, state(binding))
        assertTrue(binding.host.status.value.wipe, "a wipe: \"Refreshing…\", not \"Updating storage…\"")
        queue.drain()
        within(10_000, "the job's finish installed") { state(binding) == SharedReplicaHostState.READY }
        assertEquals(1, binding.host.rebuilds[SharedReplicaHost.Trigger.JOB_FINISHED])
        assertFalse(launched.sqlite.hasObject(1), "wiped")
        launched.sqlite.storeObject(row(2))
    }

    @Test
    fun q41AScheduledRebuildOpensOffTheMainThreadAndTheReinstallFindsTheStoreOpen() {
        // B.17 (258): the release and the open run before the reinstall's turn, off "main"; the
        // reinstall itself opens nothing.
        val app = HostApp()
        val seams = Seams()
        val opens = AtomicInt(0)
        val opensInReinstall = AtomicInt(0)
        val inReinstall = kotlin.concurrent.AtomicInt(0)
        seams.open = {
            opens.incrementAndGet()
            if (inReinstall.value == 1) opensInReinstall.incrementAndGet()
            null
        }
        val binding = binding(app, seams)
        binding.installAtLaunch()
        val graphs = Graphs(binding, build)
        graphs.install()
        val fenced = graphs.sqlite
        val before = opens.value
        // The reinstall, marked: what the main-thread turn does.
        binding.host.graphInstalled(fenced, stop = {}, rebuild = {
            inReinstall.value = 1
            try {
                graphs.install()
            } finally {
                inReinstall.value = 0
            }
        })
        assertFailsWith<org.tenshu.core.common.proxy.db.sqlite.RowTooLarge> { fenced.storeObject(oversized(1)) }
        within(10_000, "rebuilt") { state(binding) == SharedReplicaHostState.READY && graphs.current !== fenced }
        assertTrue(opens.value > before, "the store was opened for the rebuild")
        assertEquals(0, opensInReinstall.value, "⚠⚠ the reinstall's turn opened nothing: the open ran before it, off main")
        assertTrue(graphs.current !== fenced)
    }

    @Test
    fun q42TryAgainRebuildsANotReadyGraphAndALatchedProcessOffersNoTryAgain() {
        // B.17 (260), Q42: "Try again" is one discrete event, outside the per-foreground budget,
        // for any NOT_READY a rebuild can follow, a non-retryable refusal's included.
        val app = HostApp()
        var refuse = true
        val binding = binding(app, Seams(open = { if (refuse) throw IllegalStateException("made up") else null }))
        binding.installAtLaunch()
        val graphs = Graphs(binding, build).also { it.install() }
        assertEquals(SharedReplicaHostState.NOT_READY, state(binding))
        assertFalse(binding.host.isRebuildOwed, "not retryable: nothing retried by itself")
        assertTrue(binding.host.canTryAgain)
        refuse = false
        assertTrue(binding.host.tryAgain())
        within(10_000, "Try again rebuilt") { state(binding) == SharedReplicaHostState.READY }
        assertEquals(1, binding.host.rebuilds[SharedReplicaHost.Trigger.TRY_AGAIN])
        graphs.sqlite.storeObject(row(1))
        assertFalse(binding.host.tryAgain(), "nothing to try while READY")
        // A latched process: no Try again, ever.
        val latched = SharedReplicaHost(isForegroundSource = { true }, release = { false }, unbindLoops = {}, mainContext = EmptyCoroutineContext, log = {})
        latched.graphInstalled(NotReadyReplicaStore(SharedReplicaHostStatus(SharedReplicaHostState.NOT_READY, "made up", willRetry = true)), stop = {}, rebuild = { fail("never") })
        assertFalse(latched.rebuildNow())
        assertFalse(latched.canTryAgain)
        assertFalse(latched.tryAgain())
    }

    @Test
    fun q42TheProtectedDataNotificationRetriesAProtectedDataRefusalOnceAndNothingElse() {
        // B.17 (259), Q42: one automatic retry per stand-in graph, only for ProtectedDataUnavailable.
        val app = HostApp()
        var answer: Throwable? = org.tenshu.core.common.proxy.db.sqlite.ProtectedDataUnavailable("made up: before first unlock")
        val binding = binding(app, Seams(open = { answer?.let { throw it }; null }))
        // ⚠ B.17 (277): a SERIAL "main", as production's, so two scheduled rebuilds run one
        // after the other instead of meeting at the `rebuilding` guard (which would mask the case).
        val main = serialMain()
        binding.host.mainContext = main
        binding.installAtLaunch()
        val graphs = Graphs(binding, build).also { it.install() }
        assertTrue(binding.host.status.value.protectedDataUnavailable)
        // Still unavailable at the first notification: its one retry, refused again. A second
        // notification for the same graph (before the retry's new one) retries nothing.
        app.postProtectedDataAvailable()
        app.postProtectedDataAvailable()
        within(10_000, "the retry ran") { graphs.rebuilds.value == 1 && isNotReadyStandIn(graphs.current!!) }
        platform.posix.usleep(300_000u)
        assertEquals(1, binding.host.rebuilds[SharedReplicaHost.Trigger.PROTECTED_DATA], "one retry per stand-in graph")
        // The stand-in the retry installed is a new graph, with its own one retry at the next one.
        answer = null
        app.postProtectedDataAvailable()
        within(10_000, "the new stand-in's own retry served") { state(binding) == SharedReplicaHostState.READY }
        assertEquals(2, binding.host.rebuilds[SharedReplicaHost.Trigger.PROTECTED_DATA])
        // Another refusal is never retried by the notification.
        val other = binding(HostApp(), Seams(open = { throw StoreUnavailable("made up") }))
        other.installAtLaunch()
        Graphs(other, build).also { it.install() }
        assertFalse(other.host.status.value.protectedDataUnavailable)
        other.host.onProtectedDataAvailable()
        platform.posix.usleep(300_000u)
        assertNull(other.host.rebuilds[SharedReplicaHost.Trigger.PROTECTED_DATA])
        main.close()
    }

    @Test
    fun q43AStandInAccessReopensOnceASiblingsJobIsDoneThrottledAndOnlyInTheForeground() {
        // B.17 (261), Q43: this process is told nothing of a sibling's job; a read of the stand-in
        // is when it looks again, at most every REOPEN_PROBE_INTERVAL_MS.
        val app = HostApp()
        var updating = true
        val opens = AtomicInt(0)
        val binding = binding(app, Seams(open = {
            opens.incrementAndGet()
            if (updating) throw StoreNeedsForeground("made up: a sibling's job") else null
        }))
        var now = 0L
        binding.host.clockMs = { now }
        binding.installAtLaunch()
        val graphs = Graphs(binding, build).also { it.install() }
        assertEquals(SharedReplicaHostState.UPDATING, state(binding))
        val standIn = graphs.current!!
        val launchOpens = opens.value
        // The first access looks once; still updating, nothing rebuilt.
        assertNull(standIn.getObject(1))
        within(10_000, "the first look") { opens.value == launchOpens + 1 }
        platform.posix.usleep(200_000u)
        assertEquals(0, graphs.rebuilds.value, "a look that does not serve rebuilds nothing")
        // The sibling's job is done; an access inside the interval does not look again.
        updating = false
        now += SharedReplicaHost.REOPEN_PROBE_INTERVAL_MS / 2
        standIn.getObject(1)
        platform.posix.usleep(300_000u)
        assertEquals(launchOpens + 1, opens.value, "throttled")
        now += SharedReplicaHost.REOPEN_PROBE_INTERVAL_MS
        standIn.getObject(1)
        within(10_000, "the access found the store served and rebuilt") { state(binding) == SharedReplicaHostState.READY }
        assertEquals(1, binding.host.rebuilds[SharedReplicaHost.Trigger.SIBLING_FINISHED])
        graphs.sqlite.storeObject(row(1))
        // In the background nobody looks.
        val looks = AtomicInt(0)
        val background = SharedReplicaHost(isForegroundSource = { false }, release = { true }, unbindLoops = {}, mainContext = EmptyCoroutineContext, log = {}, preOpen = { _, _ -> looks.incrementAndGet(); null })
        background.graphInstalled(NotReadyReplicaStore(SharedReplicaHostStatus(SharedReplicaHostState.UPDATING, "made up", willRetry = true), onAccess = background::onStandInAccess), stop = {}, rebuild = { fail("never") })
        background.status.value.let { assertEquals(SharedReplicaHostState.UPDATING, it.state) }
        NotReadyReplicaStore(SharedReplicaHostStatus(SharedReplicaHostState.UPDATING, "made up", willRetry = true), onAccess = background::onStandInAccess).getObject(1)
        platform.posix.usleep(300_000u)
        assertEquals(0, looks.value, "never in the background")
    }

    @Test
    fun aRebuiltGraphMovesTheGenerationTheFirstGraphDoesNotAndAForgottenOneIsNoGraph() {
        // B.17 (255), (257): what re-creates the app's root, and what covers nothing.
        val app = HostApp()
        val binding = binding(app)
        assertEquals(SharedReplicaHostState.NO_GRAPH, state(binding), "no graph yet: nothing to cover")
        binding.installAtLaunch()
        val graphs = Graphs(binding, build).also { it.install() }
        assertEquals(0L, binding.host.status.value.generation, "the first graph: the screens are built over it already")
        assertFailsWith<org.tenshu.core.common.proxy.db.sqlite.RowTooLarge> { graphs.sqlite.storeObject(oversized(1)) }
        within(10_000, "rebuilt") { state(binding) == SharedReplicaHostState.READY && graphs.rebuilds.value == 1 }
        assertEquals(1L, binding.host.status.value.generation, "a rebuilt graph re-creates the root")
        binding.host.forgetGraph("made up: a deprovision")
        assertEquals(SharedReplicaHostState.NO_GRAPH, state(binding))
        assertEquals(1L, binding.host.status.value.generation, "kept: only a READY moves it")
    }

    @Test
    fun theServingGraphsForegroundHookRunsAtEachForegroundAndNeverForAStoppedOne() {
        // B.17 (263): Drive's replay of a kept answer rides this.
        val host = SharedReplicaHost(isForegroundSource = { true }, release = { true }, unbindLoops = {}, mainContext = EmptyCoroutineContext, log = {})
        var serving = 0
        host.graphInstalled(NoStore, stop = {}, rebuild = {}, foreground = { serving++ })
        host.onForeground()
        host.onForeground()
        assertEquals(2, serving)
        var stopped = 0
        host.graphInstalled(NotReadyReplicaStore(SharedReplicaHostStatus(SharedReplicaHostState.NOT_READY, "made up", willRetry = false)), stop = {}, rebuild = {}, foreground = { stopped++ })
        host.onForeground()
        assertEquals(0, stopped, "a stand-in's graph is stopped: no hook")
        assertEquals(2, serving, "a replaced graph's hook is never run again")
    }

    /** A store that is neither SQLite nor the stand-in: a file store, as the host sees it. */
    private object NoStore : ObjectStore {
        override fun storeObject(dbEntry: DbEntry) = Unit
        override fun getObject(dataIdHash: Long): DbEntry? = null
        override fun setListener(dataIdHash: Long, listener: (update: DbEntry) -> Unit) = Unit
        override fun removeListener(dataIdHash: Long) = Unit
        override fun removeObject(dataIdHash: Long) = Unit
    }

    @Test
    fun aThrowInTheRetryAfterAClaimedStopIsCaughtNotTheGlobalHandlers() {
        // B.17 (264): `stopDone.invokeOnCompletion`'s handler runs on whatever thread ends the stop;
        // on K/N a throw there reaches the global handler and ends the process.
        val caught = AtomicInt(0)
        var armed = false
        val host = SharedReplicaHost(
            isForegroundSource = { if (armed) throw IllegalStateException("made up") else true },
            release = { true },
            unbindLoops = {},
            mainContext = EmptyCoroutineContext,
            log = { if ("the retry after the stop threw" in it) caught.incrementAndGet() },
        )
        val inStop = dispatch_semaphore_create(0)
        val endStop = dispatch_semaphore_create(0)
        host.afterStopClaimed = {
            dispatch_semaphore_signal(inStop)
            dispatch_semaphore_wait(endStop, DISPATCH_TIME_FOREVER)
        }
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            host.graphInstalled(NotReadyReplicaStore(SharedReplicaHostStatus(SharedReplicaHostState.NOT_READY, "made up", willRetry = true)), stop = {}, rebuild = { fail("never") })
        }
        dispatch_semaphore_wait(inStop, DISPATCH_TIME_FOREVER)
        host.afterStopClaimed = null
        assertFalse(host.rebuild(SharedReplicaHost.Trigger.EXPLICIT), "held back by the claimed stop")
        armed = true
        dispatch_semaphore_signal(endStop)
        within(10_000, "the handler ran and its throw was caught") { caught.value == 1 }
    }

    // ── B.17.9 ──

    /** One thread standing in for the main thread: what runs on it runs in order, as on main. */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun serialMain() = kotlinx.coroutines.newSingleThreadContext("made-up-main")

    private fun standIn(state: SharedReplicaHostState = SharedReplicaHostState.NOT_READY, willRetry: Boolean = true) =
        NotReadyReplicaStore(SharedReplicaHostStatus(state, "made up", willRetry = willRetry))

    @Test
    fun aHoldOrAForgetDuringThePreparesOpenOpensNothingAndAPreparedHandleIsReleasedWhenTheRebuildBails() {
        // B.17 (273): a forget releases the store and starts its
        // wipe while a rebuild's off-main open is under way. The open re-checks its ticket under
        // the binding's lock and opens nothing; a handle opened just before the hold is released
        // by the rebuild that then bails.
        val entered = dispatch_semaphore_create(0)
        val goOn = dispatch_semaphore_create(0)
        val opened = AtomicInt(0)
        val released = AtomicInt(0)
        val host = SharedReplicaHost(isForegroundSource = { true }, release = { true }, unbindLoops = {}, mainContext = Dispatchers.Default, log = {})
        host.preOpen = { _, stillCurrent ->
            dispatch_semaphore_signal(entered)
            dispatch_semaphore_wait(goOn, DISPATCH_TIME_FOREVER)
            if (stillCurrent()) {
                opened.incrementAndGet()
                "made-up-handle"
            } else {
                null
            }
        }
        host.releasePrepared = { released.incrementAndGet() }
        host.graphInstalled(standIn(), stop = {}, rebuild = { fail("never rebuilt over a held graph") })
        assertTrue(host.tryAgain())
        dispatch_semaphore_wait(entered, DISPATCH_TIME_FOREVER)
        host.hold("made up: a forget")
        dispatch_semaphore_signal(goOn)
        within(10_000, "the try ended") { !host.status.value.detail.contains("Trying") || host.rebuilds.isEmpty() }
        platform.posix.usleep(300_000u)
        assertEquals(0, opened.value, "⚠⚠ the hold since the prepare began: nothing opened")

        // A handle opened before the hold: the main turn then finds the graph held and releases it.
        val main = serialMain()
        val second = SharedReplicaHost(isForegroundSource = { true }, release = { true }, unbindLoops = {}, mainContext = main, log = {})
        second.preOpen = { _, _ -> "made-up-handle" }
        second.releasePrepared = { assertEquals("made-up-handle", it); released.incrementAndGet() }
        second.graphInstalled(standIn(), stop = {}, rebuild = { fail("never rebuilt over a held graph") })
        val blockMain = dispatch_semaphore_create(0)
        kotlinx.coroutines.CoroutineScope(main).launch { dispatch_semaphore_wait(blockMain, DISPATCH_TIME_FOREVER) }
        assertTrue(second.tryAgain())
        platform.posix.usleep(300_000u) // the prepare has opened; the main turn waits behind the block
        second.hold("made up: a forget")
        dispatch_semaphore_signal(blockMain)
        within(10_000, "the bailing rebuild released the prepared handle") { released.value == 1 }
        main.close()
    }

    @Test
    fun theBindingsPrepareOpensNothingOnceItsTicketHasMoved() {
        // B.17 (273): the re-check is under the binding's open lock.
        val app = HostApp()
        val opens = AtomicInt(0)
        val binding = binding(app, Seams(open = { opens.incrementAndGet(); null }))
        binding.installAtLaunch()
        val graphs = Graphs(binding, build).also { it.install() }
        // ⚠ The close fences the graph's handle (RELEASED), and in the foreground the host rebuilds a
        // fenced graph on its own (§6, B.16): that rebuild's open, landing after `before` was read,
        // would count as a second open. This test is about `preOpen`'s
        // ticket alone, so the host's own rebuild is refused at step 4's probe.
        graphs.probe = false
        graphs.sqlite.close()
        val before = opens.value
        val done = dispatch_semaphore_create(0)
        var answer: Any? = "unset"
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            answer = binding.preOpen(waitForJob = false, stillCurrent = { false })
            dispatch_semaphore_signal(done)
        }
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
        assertNull(answer)
        assertEquals(before, opens.value, "⚠⚠ superseded: the store was not opened")
    }

    @Test
    fun aJobThatFinishesBetweenALaunchOpenAndItsGraphsInstallIsReplayed() {
        // B.17 (274): the finish was counted with no graph to rebuild.
        val host = SharedReplicaHost(isForegroundSource = { true }, release = { true }, unbindLoops = {}, mainContext = Dispatchers.Default, log = {})
        var rebuilt = 0
        val before = host.finishedJobsNow()
        val updating = standIn(SharedReplicaHostState.UPDATING)
        host.recordOpen(updating, before)
        host.onReplacementFinished() // the worker's finish, before the graph exists
        host.graphInstalled(updating, stop = {}, rebuild = {
            rebuilt++
            host.graphInstalled(NoStore, stop = {}, rebuild = {})
        })
        within(10_000, "the missed finish rebuilt the graph") { rebuilt == 1 }
        assertEquals(1, host.rebuilds[SharedReplicaHost.Trigger.JOB_FINISHED])
        // A finish before the open began is not one this graph waited for.
        val other = SharedReplicaHost(isForegroundSource = { true }, release = { true }, unbindLoops = {}, mainContext = Dispatchers.Default, log = {})
        other.onReplacementFinished()
        val waiting = standIn(SharedReplicaHostState.UPDATING)
        other.recordOpen(waiting, other.finishedJobsNow())
        other.graphInstalled(waiting, stop = {}, rebuild = { fail("nothing was missed") })
        platform.posix.usleep(300_000u)
        assertTrue(other.rebuilds.isEmpty())
    }

    @Test
    fun q46WhileAnObserverIsAttachedAWaitingGraphIsPolledUntilItsStoreServes() {
        // B.17 (276), Q46: a covered root reads nothing.
        val host = SharedReplicaHost(isForegroundSource = { true }, release = { true }, unbindLoops = {}, mainContext = Dispatchers.Default, log = {})
        host.pollIntervalMs = 100
        var serves = false
        host.preOpen = { _, _ -> if (serves) "made-up-handle" else null }
        var rebuilt = 0
        host.graphInstalled(standIn(SharedReplicaHostState.UPDATING), stop = {}, rebuild = {
            rebuilt++
            host.graphInstalled(NoStore, stop = {}, rebuild = {})
        })
        platform.posix.usleep(400_000u)
        assertEquals(0, host.polls, "no observer: no poll")
        host.observerAttached()
        within(10_000, "polled") { host.polls >= 2 }
        assertEquals(0, rebuilt, "a look that does not serve rebuilds nothing")
        serves = true
        within(10_000, "the poll found the store serving") { rebuilt == 1 }
        assertEquals(1, host.rebuilds[SharedReplicaHost.Trigger.POLL])
        val after = host.polls
        platform.posix.usleep(400_000u)
        assertEquals(after, host.polls, "READY: the poll ended")
        host.observerDetached()
    }

    @Test
    fun anUnfinishedForgetsRefreshingIsRebuiltByItsJobsFinish() {
        // B.17 (275): Control's forget with an unfinished wipe leaves REFRESHING, which must be
        // rebuilt by the wipe's job too, not only an UPDATING's finish, or the cover stays up and
        // hides the forget's outcome.
        val host = SharedReplicaHost(isForegroundSource = { true }, release = { true }, unbindLoops = {}, mainContext = Dispatchers.Default, log = {})
        var rebuilt = 0
        host.graphInstalled(NoStore, stop = {}, rebuild = {
            rebuilt++
            host.graphInstalled(NoStore, stop = {}, rebuild = {})
        })
        host.hold("made up: a forget")
        assertFalse(host.resume(now = false), "an unfinished wipe: nothing rebuilt now")
        assertEquals(SharedReplicaHostState.REFRESHING, host.status.value.state)
        assertTrue(host.status.value.awaitsJob)
        host.onReplacementFinished()
        within(10_000, "the wipe's finish rebuilt Control's graph") { rebuilt == 1 && host.status.value.state == SharedReplicaHostState.READY }
    }

    @Test
    fun tryAgainIsOneRebuildPerTapAcrossItsOffMainTurn() {
        // B.17 (277): a serial "main", busy, as production's is while a rebuild's turn waits.
        val main = serialMain()
        val host = SharedReplicaHost(isForegroundSource = { true }, release = { true }, unbindLoops = {}, mainContext = main, log = {})
        var rebuilt = 0
        host.graphInstalled(standIn(), stop = {}, rebuild = {
            rebuilt++
            host.graphInstalled(standIn(), stop = {}, rebuild = {})
        })
        val blockMain = dispatch_semaphore_create(0)
        kotlinx.coroutines.CoroutineScope(main).launch { dispatch_semaphore_wait(blockMain, DISPATCH_TIME_FOREVER) }
        assertTrue(host.tryAgain())
        assertEquals("Trying again…", host.status.value.detail, "said at once, which hides the button")
        assertFalse(host.canTryAgain)
        assertFalse(host.tryAgain(), "⚠ a second tap while the first is under way does nothing")
        assertFalse(host.tryAgain())
        dispatch_semaphore_signal(blockMain)
        within(10_000, "the one rebuild ran") { rebuilt == 1 && host.canTryAgain }
        platform.posix.usleep(300_000u)
        assertEquals(1, rebuilt)
        assertEquals(1, host.rebuilds[SharedReplicaHost.Trigger.TRY_AGAIN])
        main.close()
    }

    @Test
    fun aFailedAssemblysOpenRecordNeverReachesAnotherGraph() {
        // B.17 (279): a stand-in recorded for an assembly that then failed, and a graph over a
        // store that serves: READY, never the failed open's NOT_READY.
        val host = SharedReplicaHost(isForegroundSource = { true }, release = { true }, unbindLoops = {}, mainContext = EmptyCoroutineContext, log = {})
        host.recordOpen(standIn(), host.finishedJobsNow())
        host.graphInstalled(NoStore, stop = {}, rebuild = {})
        assertEquals(SharedReplicaHostState.READY, host.status.value.state)
    }

    @Test
    fun aChoiceThatNoLongerAllowsTheStoreIsHonouredByABackgroundRebuildsOpen() {
        // B.17 (281): the foreground's re-read found no handle while a background prepare waited.
        val app = HostApp()
        val binding = binding(app)
        binding.installAtLaunch()
        val graphs = Graphs(binding, build).also { it.install() }
        graphs.sqlite.close()
        binding.stillChosen = { false }
        var unchosen = 0
        binding.onUnchosen = { unchosen++ }
        val done = dispatch_semaphore_create(0)
        var answer: Any? = "unset"
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            answer = binding.preOpen(waitForJob = false)
            dispatch_semaphore_signal(done)
        }
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
        assertNull(answer, "⚠⚠ not handed to a graph")
        // The close above fences the graph, whose own scheduled rebuild meets the same check.
        assertTrue(unchosen >= 1, "the store fenced and Control told, as at a foreground")
        assertEquals(SharedReplicaHostState.NO_GRAPH, state(binding))
    }

    @Test
    fun anEntryWaitsForAnotherTriggersRebuildBeforeItsWork() {
        // B.17 (283): the entry's own rebuild stands aside at `rebuilding`; its work must not run
        // over the graph that rebuild is replacing.
        val host = SharedReplicaHost(isForegroundSource = { true }, release = { true }, unbindLoops = {}, mainContext = Dispatchers.Default, log = {})
        val inReinstall = dispatch_semaphore_create(0)
        val finishReinstall = dispatch_semaphore_create(0)
        host.graphInstalled(standIn(), stop = {}, rebuild = {
            dispatch_semaphore_signal(inReinstall)
            dispatch_semaphore_wait(finishReinstall, DISPATCH_TIME_FOREVER)
            host.graphInstalled(NoStore, stop = {}, rebuild = {})
        })
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) { host.rebuildNow() }
        dispatch_semaphore_wait(inReinstall, DISPATCH_TIME_FOREVER)
        val returned = AtomicInt(0)
        var stateAtReturn: SharedReplicaHostState? = null
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch {
            host.retryAtEntry()
            stateAtReturn = host.status.value.state
            returned.incrementAndGet()
        }
        platform.posix.usleep(400_000u)
        assertEquals(0, returned.value, "⚠ still waiting for the other rebuild")
        dispatch_semaphore_signal(finishReinstall)
        within(10_000, "the entry went on") { returned.value == 1 }
        assertEquals(SharedReplicaHostState.READY, stateAtReturn, "its work runs over the rebuilt graph")
        scope.cancel()
    }

    // ── B.17.11 ──

    @Test
    fun aBackgroundPreparesWaitForAJobNeverHoldsTheOpenLock() {
        // B.17 (286): the test's thread is "main". While a background
        // prepare waits for the worker's job, an open and a release on main take the lock at once.
        val app = HostApp()
        val queue = HeldQueue()
        val binding = binding(app, queue = queue)
        binding.installAtLaunch()
        Graphs(binding, build).also { it.install() }.sqlite.storeObject(row(1))
        assertEquals(SharedReplicaBinding.CustodyWipe.HANDED_OFF, binding.startCustodyWipe(build, onFinished = {}, rebuildAfter = true))
        val done = dispatch_semaphore_create(0)
        var waited: Any? = "unset"
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            waited = binding.preOpen(waitForJob = true)
            dispatch_semaphore_signal(done)
        }
        platform.posix.usleep(400_000u)
        val began = TimeSource.Monotonic.markNow()
        assertFailsWith<SharedReplicaNotReady> { binding.open(build) }
        assertTrue(began.elapsedNow().inWholeMilliseconds < 2_000, "⚠⚠ main never waits behind another prepare's wait: ${began.elapsedNow()}")
        assertEquals("unset", waited, "and the prepare is still waiting")
        queue.drain()
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
        assertTrue(waited is SharedReplicaStore, "served once the job finished: $waited")
    }

    @Test
    fun aTryAgainWhoseRebuildPublishesNothingBringsTheButtonBackAtOnce() {
        // B.17 (288): the probe refuses; nothing would otherwise move "Trying again…".
        val host = SharedReplicaHost(isForegroundSource = { true }, release = { true }, unbindLoops = {}, mainContext = Dispatchers.Default, log = {})
        host.graphInstalled(standIn(), stop = {}, rebuild = { fail("the probe refused") }, mayRebuild = { false })
        assertTrue(host.tryAgain())
        within(10_000, "NOT_READY again, with its button") { host.status.value.state == SharedReplicaHostState.NOT_READY && host.canTryAgain }
    }

    // ── B.17.13 ──

    @Test
    fun aJobsRunThatEndsForGoodShowsItsStateAtOnceAndGivesTheGraphUp() {
        // B.17 (292): a launch's stand-in waits on a job whose run then
        // finds the store corrupt (or a newer build's). The worker's outcome reaches the host.
        listOf(
            org.tenshu.core.common.proxy.db.sqlite.SharedReplicaJobOutcome.Kind.REPLACEMENT_CORRUPT to SharedReplicaHostState.CORRUPT,
            org.tenshu.core.common.proxy.db.sqlite.SharedReplicaJobOutcome.Kind.REPLACEMENT_SKEW to SharedReplicaHostState.SKEW,
        ).forEach { (kind, expected) ->
            var tell: ((org.tenshu.core.common.proxy.db.sqlite.SharedReplicaJobOutcome) -> Unit)? = null
            val app = HostApp()
            val binding = SharedReplicaBinding(
                directory = tempDirectory(),
                appName = "app.example.host",
                seams = Seams(),
                adapterFactory = { onJobOutcome ->
                    tell = onJobOutcome
                    SharedReplicaUiKitAdapter(app = app, queue = HeldQueue(), log = {}, onJobOutcome = onJobOutcome)
                },
            ).also { it.host.mainContext = Dispatchers.Default }
            binding.installAtLaunch()
            var abandoned = 0
            binding.host.graphInstalled(standIn(SharedReplicaHostState.UPDATING), stop = {}, rebuild = { fail("never rebuilt") }, abandon = { abandoned++ })
            assertEquals(SharedReplicaHostState.UPDATING, state(binding))
            val told = tell ?: fail("no callback handed to the adapter")
            told(org.tenshu.core.common.proxy.db.sqlite.SharedReplicaJobOutcome("/made/up/objects.db", kind, "made up", jobId = 1))
            assertEquals(expected, state(binding), "⚠⚠ $kind: shown at once, never the spinner")
            assertFalse(binding.host.status.value.willRetry)
            assertFalse(binding.host.isRebuildOwed)
            assertEquals(1, abandoned, "$kind: the graph given up (Q40, Q48)")
            assertFalse(binding.host.canTryAgain)
        }
        // A graph that waits on nothing is left alone.
        val host = SharedReplicaHost(isForegroundSource = { true }, release = { true }, unbindLoops = {}, mainContext = EmptyCoroutineContext, log = {})
        host.graphInstalled(NoStore, stop = {}, rebuild = {})
        host.onReplacementEndedForGood(corrupt = true)
        assertEquals(SharedReplicaHostState.READY, host.status.value.state)
    }

    @Test
    fun aTryAgainsFallbackKeepsWhatTheGraphWaitedOn() {
        // B.17 (293): a "Try again" whose rebuild published nothing keeps what the graph waited on.
        val host = SharedReplicaHost(isForegroundSource = { true }, release = { true }, unbindLoops = {}, mainContext = Dispatchers.Default, log = {})
        host.graphInstalled(
            NotReadyReplicaStore(SharedReplicaHostStatus(SharedReplicaHostState.NOT_READY, "made up", willRetry = true, protectedDataUnavailable = true)),
            stop = {}, rebuild = { fail("the probe refused") }, mayRebuild = { false },
        )
        assertTrue(host.tryAgain())
        within(10_000, "back to NOT_READY") { host.status.value.state == SharedReplicaHostState.NOT_READY && host.status.value.detail != SharedReplicaHost.TRYING_AGAIN }
        assertTrue(host.status.value.protectedDataUnavailable, "the protected-data retry still applies")
        val waiting = SharedReplicaHost(isForegroundSource = { true }, release = { true }, unbindLoops = {}, mainContext = Dispatchers.Default, log = {})
        waiting.graphInstalled(standIn(SharedReplicaHostState.UPDATING), stop = {}, rebuild = { fail("the probe refused") }, mayRebuild = { false })
        assertTrue(waiting.tryAgain())
        within(10_000, "back to NOT_READY") { waiting.status.value.state == SharedReplicaHostState.NOT_READY }
        assertTrue(waiting.status.value.awaitsJob, "a job's finish and the poll still act on it")
    }

    @Test
    fun aGraphsTeardownUnbindsOnlyItsOwnLoops() {
        val loops = DeferredReplicaLoops()
        val old = Recorder()
        loops.bind(old)
        val token = loops.currentBinding()
        val successor = Recorder()
        loops.bind(successor)
        loops.unbindIf(token)
        assertSame(successor, loops.currentBinding(), "a successor's loops are left bound")
        assertEquals(emptyList(), successor.calls(), "and never stopped")
        loops.unbindIf(loops.currentBinding())
        assertNull(loops.currentBinding())
        assertEquals(listOf("stop"), successor.calls())
    }

    private class Recorder : org.tenshu.core.common.proxy.db.sqlite.SharedReplicaLoops {
        private val lock = NSRecursiveLock()
        private val seen = ArrayList<String>()
        fun calls(): List<String> {
            lock.lock(); try { return seen.toList() } finally { lock.unlock() }
        }
        fun clear() {
            lock.lock(); try { seen.clear() } finally { lock.unlock() }
        }
        override fun start() {
            lock.lock(); try { seen += "start" } finally { lock.unlock() }
        }
        override fun stop() {
            lock.lock(); try { seen += "stop" } finally { lock.unlock() }
        }
        override fun awaitStopped(timeoutMs: Long) = Unit
    }
}
