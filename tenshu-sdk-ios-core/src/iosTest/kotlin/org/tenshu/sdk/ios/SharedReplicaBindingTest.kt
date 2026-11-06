@file:OptIn(SharedReplicaPlatformApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package org.tenshu.sdk.ios

import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.db.DatabaseLock
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore.DbEntry
import org.tenshu.core.common.proxy.db.ObjectStore.DbValue
import org.tenshu.core.common.proxy.db.sqlite.ReplicaBuild
import org.tenshu.core.common.proxy.db.sqlite.ReplicaOpenResult
import org.tenshu.core.common.proxy.db.sqlite.ReplicaRunOutcome
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaJobOutcome
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaLoops
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaPlatformApi
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaStore
import org.tenshu.core.common.proxy.db.sqlite.WipeNotFinished
import org.tenshu.core.common.proxy.db.sqlite.WipeRefused
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaWipeStart
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaDeletion
import org.koin.core.qualifier.named
import org.tenshu.core.common.module.schemaMigration
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.schema.migration.schemaMigration as stepOf
import org.tenshu.core.schema.service as serviceSchema
import org.koin.dsl.module
import org.tenshu.sync.proxy.SyncManagerImpl
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSThread
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.DISPATCH_TIME_FOREVER
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

/**
 * Appendix B.13: the binding of the SQLite shared replica into a route-1 runtime, behind its
 * off-by-default switch. Over a fake `UIApplication` whose "main thread" is the test's own, the
 * adapter's real worker queue, and real stores in temporary directories. Every name is made up.
 */
class SharedReplicaBindingTest {

    /** The slice of `UIApplication` the adapter uses; nothing runs on "main" unless the test runs it. */
    private class BindingApp : UiKitApplication {
        private val main: NSThread = NSThread.currentThread

        // ⚠ `==`, never `===`, on an NSThread (K/N).
        override val isMainThread: Boolean get() = NSThread.currentThread == main

        @kotlin.concurrent.Volatile
        var grant = true
        var protectedData = true
        private var nextId = 0uL
        private var onForeground: (() -> Unit)? = null
        private var onProtectedData: ((Boolean) -> Unit)? = null

        override fun isInBackground(): Boolean = false

        override fun beginBackgroundTask(name: String, onExpired: () -> Unit): ULong? = if (grant) ++nextId else null

        override fun endBackgroundTask(id: ULong) = Unit

        override fun backgroundTimeRemainingSeconds(): Double = Double.MAX_VALUE

        override fun observeLifecycle(onDidEnterBackground: () -> Unit, onWillEnterForeground: () -> Unit) {
            onForeground = onWillEnterForeground
        }

        override fun runOnMainAsync(block: () -> Unit) = Unit

        override fun isProtectedDataAvailable(): Boolean = protectedData

        override fun observeProtectedData(onChange: (Boolean) -> Unit) {
            onProtectedData = onChange
        }

        fun postWillEnterForeground() = onForeground?.invoke() ?: fail("not observed")

        fun postProtectedData(available: Boolean) = onProtectedData?.invoke(available) ?: fail("not observed")
    }

    private class Seams(
        private val extension: Boolean = false,
        /** Null: the production wipe. */
        var startWipe: (() -> SharedReplicaWipeStart)? = null,
        /** Null: the production open. */
        var open: (() -> ReplicaOpenResult)? = null,
        var deletions: Int = 0,
    ) : SharedReplicaBinding.Seams {
        /** What the last open was given: the build and its chains (B.24). */
        var openedWith: Pair<ReplicaBuild, org.tenshu.core.common.proxy.db.sqlite.ReplicaMigrationSteps>? = null

        private val main: NSThread = NSThread.currentThread
        override val isMainThread: Boolean get() = NSThread.currentThread == main
        override fun isAppExtension(): Boolean = extension

        override fun open(directory: String, build: ReplicaBuild, steps: org.tenshu.core.common.proxy.db.sqlite.ReplicaMigrationSteps, appName: String, foreground: Boolean, protectedDataAvailable: () -> Boolean): ReplicaOpenResult {
            openedWith = build to steps
            return open?.invoke() ?: SharedReplicaBinding.PlatformSeams.open(directory, build, steps, appName, foreground, protectedDataAvailable)
        }

        override fun startWipe(directory: String, build: ReplicaBuild, appName: String, reason: String, protectedDataAvailable: () -> Boolean, beforeHandOff: (jobId: Long) -> Unit): SharedReplicaWipeStart =
            startWipe?.invoke() ?: SharedReplicaBinding.PlatformSeams.startWipe(directory, build, appName, reason, protectedDataAvailable, beforeHandOff)

        override fun deleteFiles(directory: String): SharedReplicaDeletion {
            deletions++
            return SharedReplicaBinding.PlatformSeams.deleteFiles(directory)
        }
    }

    /** A worker queue that runs nothing until the test drains it, off the test's thread. */
    private class HeldQueue : ReplicaWorkerQueue {
        private val lock = platform.Foundation.NSRecursiveLock()
        private val held = ArrayList<() -> Unit>()
        override fun dispatch(block: () -> Unit) {
            lock.lock(); held += block; lock.unlock()
        }
        override fun repeating(intervalMs: Long, block: () -> Unit): ReplicaRepeatingTimer = ReplicaRepeatingTimer { }
        fun size(): Int { lock.lock(); try { return held.size } finally { lock.unlock() } }

        /** Runs every held block on another thread, in order, and waits for them. */
        fun drain() {
            lock.lock(); val blocks = held.toList(); held.clear(); lock.unlock()
            val done = dispatch_semaphore_create(0)
            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
                blocks.forEach { it() }
                dispatch_semaphore_signal(done)
            }
            dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
        }
    }

    private fun tempDirectory(): String {
        val directory = NSTemporaryDirectory() + "binding-test-" + Random.nextLong().toString(16) + "/replica"
        NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
        return directory
    }

    private fun exists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

    private class Made(val app: BindingApp, val binding: SharedReplicaBinding, val directory: String, val adapters: () -> Int) {
        /** The worker's outcome callback the binding handed its adapter: a test can tell it outcomes. */
        var tell: ((SharedReplicaJobOutcome) -> Unit)? = null
    }

    private fun binding(
        directory: String = tempDirectory(),
        seams: Seams = Seams(),
        app: BindingApp = BindingApp(),
        updatingWaitMs: Long = 30_000,
        queue: ReplicaWorkerQueue? = null,
    ): Made {
        var made = 0
        var told: ((SharedReplicaJobOutcome) -> Unit)? = null
        val binding = SharedReplicaBinding(
            directory = directory,
            appName = "app.example.binding",
            seams = seams,
            adapterFactory = { onJobOutcome ->
                made++
                told = onJobOutcome
                SharedReplicaUiKitAdapter(
                    app = app,
                    queue = queue ?: DispatchReplicaWorkerQueue("org.tenshu.replica.worker.test-" + Random.nextLong().toString(16)),
                    log = {},
                    onJobOutcome = onJobOutcome,
                )
            },
            updatingWaitMs = updatingWaitMs,
        )
        return Made(app, binding, directory) { made }.also { m -> m.tell = { told?.invoke(it) } }
    }

    private val devRelay = "https://relay.tenshu.xyz"

    private fun build(fileVersion: Int) = ReplicaBuild(
        coreVersion = 22,
        coreFloor = 22,
        services = mapOf("example/file" to fileVersion, "example/sync" to 9),
        serviceFloors = mapOf("example/file" to fileVersion, "example/sync" to 9),
    )

    private fun row(hash: Long) = DbEntry("example/file", hash, listOf(DbValue.LongValue(hash), DbValue.StringValue("made-up-$hash")))

    private fun within(timeoutMs: Long, what: String, done: () -> Boolean) {
        val start = kotlin.time.TimeSource.Monotonic.markNow()
        while (!done()) {
            if (start.elapsedNow().inWholeMilliseconds > timeoutMs) fail("$what: not within $timeoutMs ms")
            platform.posix.usleep(10_000u)
        }
    }

    private fun runtime(): FirstPartyLocalRuntime =
        FirstPartyLocalRuntime(FirstPartyLocalRuntime.Config(clientId = "app.example.binding", secureStorageService = "test.binding")).also {
            it.stillDeprovisioned = { true }
            // B.14 (190): Control has declared the SQLite store; ControlSharedReplicaTest covers the rest.
            it.sharedReplicaChoice = { ReplicaChoice.SQLITE }
        }

    // ── B.22: no switch ──

    @Test
    fun theReplicaLivesInItsOwnSubdirectory() {
        assertEquals("replica", SharedReplicaLocation.SUBDIRECTORY)
    }

    /**
     * ⚠⚠ core-common B.19.9 (343): the build every open judges a store by declares the list-segment
     * format, so a store this build has written lists into in segments is `Skew` for an older one.
     */
    @Test
    fun theProductionBuildDeclaresTheListSegmentStamp() {
        val build = replicaBuildOfThisBuild()
        assertEquals(1, build.services["tenshu/store/list-segments"])
        assertEquals(1, build.serviceFloors["tenshu/store/list-segments"])
    }

    /**
     * ⚠⚠ B.24: the graph's open is given **the graph's own schema registry's chains** and a build
     * whose floors those chains set, never `NoMigrationChains` with every floor at its version (which
     * wiped a store one step behind). A made-up service at v2 with a registered 1→2 step rides the
     * production modules in through `coreSchemaConfigs`, so the production versions stay at 1.
     */
    @Test
    fun theGraphsOpenIsGivenTheRegistrysChainsAndFloorsTheyReach() {
        val seams = Seams()
        val made = binding(seams = seams)
        made.binding.installAtLaunch()
        val migrated = "example/migrated"
        val bumped = tModule {
            service(serviceSchema(id = migrated, version = 2))
            schemaMigration(stepOf(migrated, fromVersion = 1) { dropProperty("example/thing", "example/thing/retired") })
        }
        val koin = org.koin.dsl.koinApplication {
            modules(
                app.tenshu.control.di.buildProxyModule(),
                app.tenshu.control.di.buildPlatformProxyModule(),
                module { single<List<org.tenshu.core.common.module.ModuleConfiguration>>(named("coreSchemaConfigs")) { listOf(bumped) } },
                sharedReplicaStoreModule(made.binding),
            )
        }.koin
        assertTrue(koin.get<ObjectStore>() is SharedReplicaStore, "a fresh store opens")
        val (build, steps) = seams.openedWith ?: fail("the graph did not open the store")
        assertEquals(2, build.services[migrated])
        assertEquals(1, build.serviceFloors[migrated], "the floor is where the chain reaches, not the version")
        val step = steps.step(migrated, 1) ?: fail("the registry's step was not bound")
        assertEquals(setOf("example/thing"), step.typeIds, "a strip-only step reads its type's rows")
        val retired = DbEntry("example/thing", 1, listOf(DbValue.LongValue(1)), mapOf("example/thing/retired" to DbValue.LongValue(2), "example/thing/kept" to DbValue.LongValue(3)))
        assertEquals(retired.copy(properties = mapOf("example/thing/kept" to DbValue.LongValue(3))), step.migrate(retired))
        // Every production service at 1 with no step: its floor is its version, and a step asked
        // past this build's version is refused rather than answered "none" (which would wipe).
        assertEquals(build.services - migrated, build.serviceFloors - migrated)
        assertFailsWith<IllegalStateException> { steps.step(migrated, 2) }
    }

    @Test
    fun theProductionPathAlwaysMakesTheBindingATestReplicaDirectoryNever() {
        // ⚠⚠ B.22: no plist switch, no relay guard.
        val made = binding()
        val runtime = runtime()
        runtime.sharedReplicaSqliteBinding = { made.binding }
        assertSame(made.binding, runtime.sqliteBindingFor(replicaDirectory = null))
        assertNull(runtime.sqliteBindingFor(replicaDirectory = tempDirectory()))
    }

    @Test
    fun aTestReplicaDirectoryNeverTakesTheSqliteStore() {
        val runtime = runtime()
        runtime.sharedReplicaSqliteBinding = { fail("a binding was made for a test replica directory") }
        val base = runtime.assembleBase(replicaDirectory = tempDirectory(), secureStorage = InMemorySecureStorageDelegate(), relayBaseUrl = devRelay)
        assertFalse(base.koin.get<ObjectStore>() is SharedReplicaStore, "the test replica")
    }

    // ── the production path ──

    @Test
    fun anAssemblyOpensTheSqliteStoreAttachedAndInstalledFirst() {
        val directory = tempDirectory()
        val made = binding(directory)
        val runtime = runtime()
        runtime.sharedReplicaSqliteBinding = { made.binding }
        val base = runtime.assembleBase(secureStorage = InMemorySecureStorageDelegate(), relayBaseUrl = devRelay)
        val store = base.koin.get<ObjectStore>() as? SharedReplicaStore ?: fail("not the SQLite store")
        assertSame(store.databaseLock, base.koin.get<DatabaseLock>())
        assertTrue(made.binding.isInstalled)
        assertTrue(exists("$directory/objects.db"), "the store is the binding's directory's")
        store.databaseLock.withLock { store.storeObject(row(7)) }
        assertTrue(store.hasObject(7))
        // A second assembly in the same process (a re-provision): the binding is not made again,
        // and the store already open is the one it gets (one runtime handle per path, §2.8).
        val again = runtime.assembleBase(secureStorage = InMemorySecureStorageDelegate(), relayBaseUrl = devRelay)
        assertEquals(1, made.adapters())
        assertSame(store, again.koin.get<ObjectStore>())
    }

    @Test
    fun aReleasedStoreIsOpenedAnewAndALiveOneIsHandedOutAgain() {
        val made = binding()
        made.binding.installAtLaunch()
        val first = made.binding.open(build(3))
        assertSame(first, made.binding.open(build(3)), "a live store is the process's one handle")
        first.close()
        val second = made.binding.open(build(3))
        assertTrue(second !== first, "a released store is replaced")
        assertNull(second.fencedBy)
        second.close()
    }

    @Test
    fun q24AnAppExtensionMakesNoAdapterAndNeverOpens() {
        val directory = tempDirectory()
        val made = binding(directory, Seams(extension = true))
        val refused = assertFailsWith<SharedReplicaNotReady> { made.binding.installAtLaunch() }
        assertTrue("Q24" in (refused.message ?: ""), "${refused.message}")
        assertEquals(0, made.adapters())
        assertFalse(made.binding.isInstalled)
        assertFailsWith<SharedReplicaNotReady> { made.binding.open(build(3)) }
        assertFalse(exists("$directory/objects.db"), "nothing was created")
    }

    @Test
    fun installAtLaunchIsMainOnlyAndOnce() {
        val made = binding()
        val done = dispatch_semaphore_create(0)
        var offMain: Throwable? = null
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            offMain = runCatching { made.binding.installAtLaunch() }.exceptionOrNull()
            dispatch_semaphore_signal(done)
        }
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
        assertNotNull(offMain, "installAtLaunch off the main thread is refused")
        assertEquals(0, made.adapters())
        made.binding.installAtLaunch()
        made.binding.installAtLaunch()
        assertEquals(1, made.adapters())
        assertTrue(made.binding.isInstalled)
    }

    @Test
    fun openBeforeInstallAtLaunchIsRefused() {
        val made = binding()
        assertFailsWith<SharedReplicaNotReady> { made.binding.open(build(3)) }
    }

    @Test
    fun aWipeTheWorkerWasHandedIsNeverWaitedForByTheGraphsOpen() {
        // ⚠⚠ B.17 (258), Q41: Q29's wait is gone. The graph's open answers "not ready" (a wipe:
        // "Refreshing…") at once; once the worker finished, the next open serves the wiped store.
        val made = binding()
        made.binding.installAtLaunch()
        val first = made.binding.open(build(3))
        first.databaseLock.withLock { (1L..5L).forEach { first.storeObject(row(it)) } }
        first.close()
        // v3 is below the newer build's floor (its own version, B.13 (167)): a wipe, handed to the worker.
        val began = kotlin.time.TimeSource.Monotonic.markNow()
        val refused = assertFailsWith<SharedReplicaNotReady> { made.binding.open(build(4)) }
        assertTrue(began.elapsedNow().inWholeMilliseconds < 5_000, "never the wait")
        assertTrue(refused.retryable)
        assertTrue(refused.wipe, "a wipe, so \"Refreshing…\"")
        assertEquals(SharedReplicaHostState.UPDATING, refused.state)
        within(30_000, "the worker finished the wipe") { made.binding.lastOutcome()?.kind == SharedReplicaJobOutcome.Kind.REPLACEMENT_FINISHED }
        val after = made.binding.open(build(4))
        assertFalse(after.hasObject(1), "wiped")
        after.close()
    }

    @Test
    fun theHostsOffMainOpenWaitsForTheJobOnlyWhenAskedAndNeverOnMain() {
        // B.17 (258): the bounded wait survives only in the host's off-main open of a background
        // rebuild (B.16 (227)); on the main thread preOpen refuses to run at all.
        val queue = HeldQueue()
        val made = binding(queue = queue)
        made.binding.installAtLaunch()
        made.binding.open(build(3)).let { s -> s.databaseLock.withLock { s.storeObject(row(1)) }; s.close() }
        assertFailsWith<SharedReplicaNotReady> { made.binding.open(build(4)) } // the wipe, handed off; the build recorded
        val onMain = kotlin.time.TimeSource.Monotonic.markNow()
        assertNull(made.binding.preOpen(waitForJob = true), "never on the main thread")
        assertTrue(onMain.elapsedNow().inWholeMilliseconds < 5_000, "⚠⚠ and never a wait there")
        val done = dispatch_semaphore_create(0)
        var noWait: Boolean? = null
        var waited: Boolean? = null
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            noWait = made.binding.preOpen(waitForJob = false) != null
            dispatch_semaphore_signal(done)
        }
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
        assertEquals(false, noWait, "not asked to wait: not ready at once")
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            waited = made.binding.preOpen(waitForJob = true) != null
            dispatch_semaphore_signal(done)
        }
        platform.posix.usleep(300_000u)
        assertNull(waited, "waiting for the worker")
        queue.drain()
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
        assertEquals(true, waited, "served once the job finished")
        val served = made.binding.open(build(4))
        assertFalse(served.hasObject(1), "the graph's open finds the store the wait opened")
    }

    @Test
    fun aStoppedWipeRunsAgainAtTheNextForegroundsKick() {
        val made = binding()
        made.binding.installAtLaunch()
        made.binding.open(build(3)).close()
        made.app.grant = false
        assertFailsWith<SharedReplicaNotReady> { made.binding.open(build(4)) }
        within(30_000, "the worker's run stopped for want of time") { made.binding.lastOutcome()?.kind == SharedReplicaJobOutcome.Kind.REPLACEMENT_STOPPED }
        made.app.grant = true
        made.app.postWillEnterForeground()
        within(30_000, "the foreground's kick finished the wipe") {
            made.binding.lastOutcome()?.kind == SharedReplicaJobOutcome.Kind.REPLACEMENT_FINISHED
        }
        made.binding.open(build(4)).close()
    }

    @Test
    fun aStoppedWipeRunsAgainAtAWake() {
        val made = binding()
        made.binding.wake() // before install: nothing, and no throw
        made.binding.installAtLaunch()
        made.binding.open(build(3)).close()
        made.app.grant = false
        assertFailsWith<SharedReplicaNotReady> { made.binding.open(build(4)) }
        made.app.grant = true
        made.binding.wake()
        within(30_000, "the wake's kick finished the wipe") {
            made.binding.lastOutcome()?.kind == SharedReplicaJobOutcome.Kind.REPLACEMENT_FINISHED
        }
    }

    @Test
    fun aStoreWrittenByANewerBuildIsNotHandedOut() {
        val made = binding()
        made.binding.installAtLaunch()
        made.binding.open(build(5)).close()
        val skew = assertFailsWith<SharedReplicaNotReady> { made.binding.open(build(4)) }
        assertTrue("newer build" in (skew.message ?: ""), "${skew.message}")
    }

    @Test
    fun protectedDataIsTheMainThreadsLastReadingAndNoReadingIsFalse() {
        val adapter = SharedReplicaUiKitAdapter(app = BindingApp().also { it.protectedData = true }, queue = DispatchReplicaWorkerQueue("org.tenshu.replica.worker.test-pd"), log = {})
        assertFalse(adapter.protectedDataAvailable(), "no reading yet is false")
        adapter.install()
        assertTrue(adapter.protectedDataAvailable())
    }

    @Test
    fun theProtectedDataNotificationsMoveTheReading() {
        val app = BindingApp()
        val adapter = SharedReplicaUiKitAdapter(app = app, queue = DispatchReplicaWorkerQueue("org.tenshu.replica.worker.test-pdn"), log = {})
        adapter.install()
        assertTrue(adapter.protectedDataAvailable())
        app.postProtectedData(false)
        assertFalse(adapter.protectedDataAvailable())
        app.postProtectedData(true)
        assertTrue(adapter.protectedDataAvailable())
    }

    @Test
    fun theWaitForAHandedOffJobIsAnsweredByAReplacementOutcomeNotByAWalks() {
        var told: ((SharedReplicaJobOutcome) -> Unit)? = null
        val app = BindingApp()
        val binding = SharedReplicaBinding(
            directory = tempDirectory(),
            appName = "app.example.binding",
            seams = Seams(),
            adapterFactory = { onJobOutcome ->
                told = onJobOutcome
                SharedReplicaUiKitAdapter(app = app, queue = DispatchReplicaWorkerQueue("org.tenshu.replica.worker.test-wait"), log = {})
            },
        )
        binding.installAtLaunch()
        val tell = told ?: fail("no callback handed to the adapter")
        val path = "/made/up/objects.db"
        val before = binding.outcomesSoFar()
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            platform.posix.usleep(100_000u)
            tell(SharedReplicaJobOutcome(path, SharedReplicaJobOutcome.Kind.WALK_PENDING, "made up"))
            platform.posix.usleep(100_000u)
            tell(SharedReplicaJobOutcome(path, SharedReplicaJobOutcome.Kind.REPLACEMENT_FINISHED, "made up"))
        }
        assertEquals(SharedReplicaJobOutcome.Kind.REPLACEMENT_FINISHED, binding.awaitReplacementOutcome(before)?.kind)
    }

    // ── the custody purge, the D3 order ──

    @Test
    fun theCustodyPurgeGoesOnNowOnlyWithNothingToWipeAndNeverThrows() {
        val seams = Seams()
        val made = binding(seams = seams)
        seams.startWipe = { SharedReplicaWipeStart.NOTHING_TO_WIPE }
        assertTrue(made.binding.wipeBeforeCustodyPurge(build(3)) { fail("no later purge") })
        seams.startWipe = { SharedReplicaWipeStart.HANDED_OFF }
        assertFalse(made.binding.wipeBeforeCustodyPurge(build(3)) { })
        seams.startWipe = { throw WipeNotFinished(ReplicaRunOutcome.Stopped("made up")) }
        assertFalse(made.binding.wipeBeforeCustodyPurge(build(3)) { })
        seams.startWipe = { throw IllegalStateException("made up") }
        assertFalse(made.binding.wipeBeforeCustodyPurge(build(3)) { })
        seams.startWipe = { throw WipeRefused(ReplicaRunOutcome.Stopped("made up")) }
        assertFalse(made.binding.wipeBeforeCustodyPurge(build(3)) { }, "a refusal that is not Skew or Corrupt keeps custody")
        assertEquals(0, seams.deletions)
    }

    @Test
    fun aPurgeOffTheMainThreadOrInAnExtensionReturnsFalseAndNeverThrows() {
        val inExtension = binding(seams = Seams(extension = true))
        assertFalse(inExtension.binding.wipeBeforeCustodyPurge(build(3)) { })
        val offMain = binding()
        val done = dispatch_semaphore_create(0)
        var answer: Result<Boolean>? = null
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            answer = runCatching { offMain.binding.wipeBeforeCustodyPurge(build(3)) { } }
            dispatch_semaphore_signal(done)
        }
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
        assertEquals(false, answer?.getOrThrow())
    }

    private fun delegateWithCustody(): InMemorySecureStorageDelegate =
        InMemorySecureStorageDelegate().also { it.storeString(SyncManagerImpl.Keys.DEVICE_ID, "made-up-device") }

    private fun switchedRuntime(made: Made): FirstPartyLocalRuntime = runtime().also {
        it.sharedReplicaSqliteBinding = { made.binding }
    }

    @Test
    fun theDeprovisionReturnsAtOnceAndCustodyGoesWhenTheWorkersWipeFinishes() {
        val queue = HeldQueue()
        val made = binding(queue = queue)
        // A store THIS build can wipe: one opened at this build's own versions.
        made.binding.installAtLaunch()
        val first = made.binding.open(replicaBuildOfThisBuild())
        first.databaseLock.withLock { (1L..5L).forEach { first.storeObject(row(it)) } }
        val runtime = switchedRuntime(made)
        val delegate = delegateWithCustody()
        assertFalse(runtime.purgeCustody(delegate), "the wipe is the worker's: custody is kept for now")
        assertEquals("made-up-device", delegate.getString(SyncManagerImpl.Keys.DEVICE_ID))
        assertTrue(queue.size() >= 1, "the rest of the wipe waits on the worker's queue, not on this thread")
        queue.drain()
        assertNull(delegate.getString(SyncManagerImpl.Keys.DEVICE_ID), "purged once the worker's wipe finished")
        assertEquals(SharedReplicaJobOutcome.Kind.REPLACEMENT_FINISHED, made.binding.lastOutcome()?.kind)
    }

    @Test
    fun anotherJobsFinishDoesNotReleaseTheHandedOffWipesPurge() {
        val queue = HeldQueue()
        val made = binding(queue = queue)
        made.binding.installAtLaunch()
        made.binding.open(replicaBuildOfThisBuild()).let { s -> s.databaseLock.withLock { s.storeObject(row(1)) } }
        val delegate = delegateWithCustody()
        assertFalse(switchedRuntime(made).purgeCustody(delegate))
        // An earlier job of the same store (a migration, a wipe) finishing now, not this wipe.
        val tell = made.tell ?: fail("no callback")
        tell(SharedReplicaJobOutcome("${made.directory}/objects.db", SharedReplicaJobOutcome.Kind.REPLACEMENT_FINISHED, "made up", jobId = -1L))
        tell(SharedReplicaJobOutcome("${made.directory}/objects.db", SharedReplicaJobOutcome.Kind.REPLACEMENT_FINISHED, "made up", jobId = null))
        assertEquals("made-up-device", delegate.getString(SyncManagerImpl.Keys.DEVICE_ID), "custody stays while this wipe's chunks are pending")
        queue.drain()
        assertNull(delegate.getString(SyncManagerImpl.Keys.DEVICE_ID), "this wipe's own finish purges")
    }

    /** A worker queue whose dispatch runs the block to its end on another thread before it returns. */
    private class InlineQueue : ReplicaWorkerQueue {
        override fun dispatch(block: () -> Unit) {
            val done = dispatch_semaphore_create(0)
            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
                block()
                dispatch_semaphore_signal(done)
            }
            dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
        }
        override fun repeating(intervalMs: Long, block: () -> Unit): ReplicaRepeatingTimer = ReplicaRepeatingTimer { }
    }

    @Test
    fun aWorkerThatFinishesBeforeTheStartReturnsStillFindsThePurge() {
        val made = binding(queue = InlineQueue())
        made.binding.installAtLaunch()
        made.binding.open(replicaBuildOfThisBuild()).let { s -> s.databaseLock.withLock { s.storeObject(row(1)) } }
        val delegate = delegateWithCustody()
        assertFalse(switchedRuntime(made).purgeCustody(delegate), "handed off")
        assertEquals(SharedReplicaJobOutcome.Kind.REPLACEMENT_FINISHED, made.binding.lastOutcome()?.kind)
        assertNull(delegate.getString(SyncManagerImpl.Keys.DEVICE_ID), "the purge was owed before the dispatch")
    }

    @Test
    fun aReProvisionInTheSameProcessNeverGetsThePreWipeStore() {
        val queue = HeldQueue()
        val made = binding(queue = queue, updatingWaitMs = 300)
        made.binding.installAtLaunch()
        val first = made.binding.open(replicaBuildOfThisBuild())
        first.databaseLock.withLock { first.storeObject(row(1)) }
        val runtime = switchedRuntime(made)
        assertFalse(runtime.purgeCustody(delegateWithCustody()), "handed off")
        // While the wipe is the worker's: the open answers Updating (not the stale store), and an
        // assembly waits on the not-ready stand-in. ⚠ B.16 (215): an Updating past the wait is
        // "not ready, try again"; it fell back to the file store (181) before.
        val refused = assertFailsWith<SharedReplicaNotReady> { made.binding.open(replicaBuildOfThisBuild()) }
        assertTrue(refused.retryable)
        val during = runtime.assembleBase(secureStorage = InMemorySecureStorageDelegate(), relayBaseUrl = devRelay)
        assertTrue(isNotReadyStandIn(during.koin.get<ObjectStore>()), "never the file store, never the stale store")
        queue.drain()
        // Once it finished: a fresh store, never the pre-wipe one.
        val fresh = made.binding.open(replicaBuildOfThisBuild())
        assertTrue(fresh !== first, "the cached pre-wipe store was released")
        assertNull(fresh.fencedBy)
        assertFalse(fresh.hasObject(1), "wiped")
        fresh.databaseLock.withLock { fresh.storeObject(row(2)) }
        assertTrue(fresh.hasObject(2))
    }

    @Test
    fun aReProvisionBeforeTheWipeFinishesKeepsCustody() {
        val queue = HeldQueue()
        val made = binding(queue = queue)
        made.binding.installAtLaunch()
        made.binding.open(replicaBuildOfThisBuild()).let { s -> s.databaseLock.withLock { s.storeObject(row(1)) } }
        val runtime = switchedRuntime(made)
        var deprovisioned = true
        runtime.stillDeprovisioned = { deprovisioned }
        val delegate = delegateWithCustody()
        assertFalse(runtime.purgeCustody(delegate))
        deprovisioned = false
        queue.drain()
        assertEquals("made-up-device", delegate.getString(SyncManagerImpl.Keys.DEVICE_ID), "the adoption since is not purged")
    }

    @Test
    fun aNewerBuildsStoreHasItsFilesDeletedAndCustodyPurged() {
        val made = binding()
        made.binding.installAtLaunch()
        // Made-up services this build does not know: a newer build's store to it (Skew).
        made.binding.open(build(3)).let { s -> s.databaseLock.withLock { s.storeObject(row(1)) } }
        val delegate = delegateWithCustody()
        assertTrue(switchedRuntime(made).purgeCustody(delegate))
        assertFalse(exists("${made.directory}/objects.db"), "the files are gone")
        assertNull(delegate.getString(SyncManagerImpl.Keys.DEVICE_ID))
    }

    @Test
    fun aCorruptStoreHasItsFilesDeletedAndCustodyPurged() {
        val made = binding()
        made.binding.installAtLaunch()
        made.binding.open(replicaBuildOfThisBuild()).close()
        NSFileManager.defaultManager.createFileAtPath("${made.directory}/CORRUPT", null, null)
        val delegate = delegateWithCustody()
        assertTrue(switchedRuntime(made).purgeCustody(delegate))
        assertFalse(exists("${made.directory}/objects.db"))
        assertFalse(exists("${made.directory}/CORRUPT"))
        assertNull(delegate.getString(SyncManagerImpl.Keys.DEVICE_ID))
    }

    // ── B.22: the relay guard (B.13 (180)) is gone ──

    @Test
    fun anyRelayBindsTheSqliteStore() {
        listOf("https://relay.tenshu.app", null).forEach { relay ->
            val made = binding()
            val base = switchedRuntime(made).assembleBase(secureStorage = InMemorySecureStorageDelegate(), relayBaseUrl = relay)
            assertTrue(base.koin.get<ObjectStore>() is SharedReplicaStore, "$relay")
        }
    }

    @Test
    fun aGraphOnTheProductionRelayTakesTheSqliteStore() {
        val made = binding()
        val runtime = switchedRuntime(made)
        val overrides = module { single(named("syncRelayUrl")) { "https://relay.tenshu.app" } }
        val base = runtime.assembleBase(secureStorage = InMemorySecureStorageDelegate(), relayBaseUrl = devRelay, appOverrides = overrides)
        val store = base.koin.get<ObjectStore>() as? SharedReplicaStore ?: fail("not the SQLite store")
        assertSame(store.databaseLock, base.koin.get<DatabaseLock>())
    }

    // ── never a crash at assembly (B.13 (181)) ──

    @Test
    fun aSkewStoreGetsTheStandInNotTheFileStore() {
        // ⚠⚠ B.17 (254), Q40: B.13 (181)'s fallback is gone; never a crash all the same.
        val made = binding(seams = Seams(open = { ReplicaOpenResult.Skew("made up") }))
        val runtime = switchedRuntime(made)
        val base = runtime.assembleBase(secureStorage = InMemorySecureStorageDelegate(), relayBaseUrl = devRelay)
        runtime.graphReady()
        assertTrue(isNotReadyStandIn(base.koin.get<ObjectStore>()))
        assertSame((base.koin.get<ObjectStore>() as NotReadyReplicaStore).databaseLock, base.koin.get<DatabaseLock>(), "the stand-in's own lock")
        assertEquals(SharedReplicaHostState.SKEW, made.binding.host.status.value.state)
    }

    @Test
    fun aCorruptStoreGetsTheStandInNotTheFileStore() {
        val made = binding(seams = Seams(open = { ReplicaOpenResult.Corrupt("made up") }))
        val runtime = switchedRuntime(made)
        val base = runtime.assembleBase(secureStorage = InMemorySecureStorageDelegate(), relayBaseUrl = devRelay)
        runtime.graphReady()
        assertTrue(isNotReadyStandIn(base.koin.get<ObjectStore>()))
        assertEquals(SharedReplicaHostState.CORRUPT, made.binding.host.status.value.state)
    }

    // ── rule P's loops ──

    private class Loop(val name: String) {
        val calls = ArrayList<String>()
        var throws = false
        fun asRuntimeLoop() = RuntimeLoop(
            name,
            start = { calls += "start"; if (throws) throw IllegalStateException("made up") },
            stop = { calls += "stop"; if (throws) throw IllegalStateException("made up") },
        )
    }

    @Test
    fun theRuntimesLoopsStartAgainOnlyAfterRulePStoppedThem() {
        val scheduler = Loop("scheduler")
        val drain = Loop("drain")
        val loops = SyncRuntimeLoops { listOf(scheduler.asRuntimeLoop(), drain.asRuntimeLoop()) }
        loops.start()
        assertEquals(emptyList(), scheduler.calls, "a start with no stop before it starts nothing")
        loops.stop()
        loops.start()
        loops.start()
        assertEquals(listOf("stop", "start"), scheduler.calls)
        assertEquals(listOf("stop", "start"), drain.calls)
    }

    @Test
    fun noRuntimeAndAThrowingLoopAreContained() {
        SyncRuntimeLoops { null }.apply { stop(); start(); awaitStopped(1_000) }
        SyncRuntimeLoops { throw IllegalStateException("made up") }.apply { stop(); start() }
        val throwing = Loop("scheduler").also { it.throws = true }
        val after = Loop("drain")
        val loops = SyncRuntimeLoops { listOf(throwing.asRuntimeLoop(), after.asRuntimeLoop()) }
        loops.stop()
        loops.start()
        assertEquals(listOf("stop", "start"), after.calls, "a throwing loop does not keep the next from its call")
    }

    @Test
    fun theDeferredLoopsDelegateOnceBoundAndALaterBindReplaces() {
        val deferred = DeferredReplicaLoops()
        deferred.stop()
        deferred.start()
        val first = Recorder()
        deferred.bind(first)
        deferred.stop()
        val second = Recorder()
        deferred.bind(second)
        deferred.start()
        deferred.awaitStopped(1)
        assertEquals(listOf("stop"), first.calls)
        assertEquals(listOf("start", "await"), second.calls)
    }

    private class Recorder : SharedReplicaLoops {
        val calls = ArrayList<String>()
        override fun start() {
            calls += "start"
        }

        override fun stop() {
            calls += "stop"
        }

        override fun awaitStopped(timeoutMs: Long) {
            calls += "await"
        }
    }
}
