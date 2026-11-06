@file:OptIn(SharedReplicaPlatformApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package org.tenshu.sdk.ios

import app.tenshu.control.di.buildPlatformProxyModule
import app.tenshu.control.di.buildProxyModule
import kotlinx.coroutines.runBlocking
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.proxy.db.DatabaseLock
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore.DbEntry
import org.tenshu.core.common.proxy.db.ObjectStore.DbValue
import org.tenshu.core.common.proxy.db.sqlite.ReplicaBuild
import org.tenshu.core.common.proxy.db.sqlite.ReplicaFence
import org.tenshu.core.common.proxy.db.sqlite.ReplicaOpenResult
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaDeletion
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaJobOutcome
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaPlatformApi
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaStore
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaWipeStart
import org.tenshu.sync.proxy.SyncManagerImpl
import org.tenshu.sync.proxy.purgeOutboundQueues
import org.tenshu.core.entity.getDataIdHash
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSThread
import platform.Foundation.writeToFile
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Appendix B.14: Control's binding of the SQLite shared replica (the same binding as the route-1
 * apps', B.13), the replica choice by which the apps follow Control (190), and
 * Control's forget over the shared store (191). Over a fake `UIApplication`, real worker queues and
 * real stores in temporary directories. Every name is made up.
 */
class ControlSharedReplicaTest {

    private class FakeApp : UiKitApplication {
        private val main: NSThread = NSThread.currentThread

        // ⚠ `==`, never `===`, on an NSThread (K/N).
        override val isMainThread: Boolean get() = NSThread.currentThread == main

        @kotlin.concurrent.Volatile
        var grant = true
        private var onForeground: (() -> Unit)? = null
        private var nextId = 0uL

        override fun isInBackground(): Boolean = false
        override fun beginBackgroundTask(name: String, onExpired: () -> Unit): ULong? = if (grant) ++nextId else null
        override fun endBackgroundTask(id: ULong) = Unit
        override fun backgroundTimeRemainingSeconds(): Double = Double.MAX_VALUE
        override fun observeLifecycle(onDidEnterBackground: () -> Unit, onWillEnterForeground: () -> Unit) {
            onForeground = onWillEnterForeground
        }
        override fun runOnMainAsync(block: () -> Unit) = Unit
        override fun isProtectedDataAvailable(): Boolean = true
        override fun observeProtectedData(onChange: (Boolean) -> Unit) = Unit

        fun postWillEnterForeground() = onForeground?.invoke() ?: fail("not observed")
    }

    private class Seams(
        private val extension: Boolean = false,
        var open: (() -> ReplicaOpenResult)? = null,
        var startWipe: (() -> SharedReplicaWipeStart)? = null,
        var deleteFiles: (() -> SharedReplicaDeletion)? = null,
    ) : SharedReplicaBinding.Seams {
        private val main: NSThread = NSThread.currentThread
        override val isMainThread: Boolean get() = NSThread.currentThread == main
        override fun isAppExtension(): Boolean = extension

        override fun open(directory: String, build: ReplicaBuild, steps: org.tenshu.core.common.proxy.db.sqlite.ReplicaMigrationSteps, appName: String, foreground: Boolean, protectedDataAvailable: () -> Boolean): ReplicaOpenResult =
            open?.invoke() ?: SharedReplicaBinding.PlatformSeams.open(directory, build, steps, appName, foreground, protectedDataAvailable)

        override fun startWipe(directory: String, build: ReplicaBuild, appName: String, reason: String, protectedDataAvailable: () -> Boolean, beforeHandOff: (jobId: Long) -> Unit): SharedReplicaWipeStart =
            startWipe?.invoke() ?: SharedReplicaBinding.PlatformSeams.startWipe(directory, build, appName, reason, protectedDataAvailable, beforeHandOff)

        override fun deleteFiles(directory: String): SharedReplicaDeletion =
            deleteFiles?.invoke() ?: SharedReplicaBinding.PlatformSeams.deleteFiles(directory)
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

    private val devRelay = "https://relay.tenshu.xyz"

    /** The BIP-39 test vector's phrase: made up, valid. */
    private val PHRASE = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    private fun tempRoot(): String {
        val root = NSTemporaryDirectory() + "control-replica-test-" + Random.nextLong().toString(16)
        NSFileManager.defaultManager.createDirectoryAtPath("$root/replica", true, null, null)
        return root
    }

    private fun exists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

    private class Made(val root: String, val app: FakeApp, val binding: SharedReplicaBinding, val choice: SharedReplicaChoice, val adapters: () -> Int) {
        val directory: String get() = "$root/replica"
    }

    private fun made(
        root: String = tempRoot(),
        directory: String = "$root/replica",
        seams: Seams = Seams(),
        queue: ReplicaWorkerQueue? = null,
        appName: String = "app.example.control",
    ): Made {
        val app = FakeApp()
        var adapters = 0
        val binding = SharedReplicaBinding(
            directory = directory,
            appName = appName,
            seams = seams,
            adapterFactory = { onJobOutcome ->
                adapters++
                SharedReplicaUiKitAdapter(
                    app = app,
                    queue = queue ?: DispatchReplicaWorkerQueue("org.tenshu.replica.worker.control-test-" + Random.nextLong().toString(16)),
                    log = {},
                    onJobOutcome = onJobOutcome,
                )
            },
            updatingWaitMs = 30_000,
        )
        val choice = SharedReplicaChoice("$root/replica.choice", log = {})
        // B.16: a rebuild the host schedules runs off the test's thread ("main"), which waits.
        binding.host.mainContext = kotlinx.coroutines.Dispatchers.Default
        return Made(root, app, binding, choice) { adapters }
    }

    private fun control(
        made: Made,
        thisBuild: () -> ReplicaBuild = { replicaBuildOfThisBuild() },
        forgetWaitMs: Long = 30_000,
        cleanUps: MutableList<String>? = null,
    ) = ControlSharedReplica(
        binding = { made.binding },
        choice = { made.choice },
        thisBuild = thisBuild,
        mainContext = EmptyCoroutineContext,
        forgetWaitMs = forgetWaitMs,
        log = {},
        cleanUpCopies = { directory -> cleanUps?.add(directory) },
    )

    /** Control's graph as `ControlSurface.installRealRuntime` assembles it, the test's custody and relay last but one. */
    private fun controlGraph(replicaModule: Module?, relay: String = devRelay): Koin {
        val overrides = module {
            single<SecureStorageDelegate> { InMemorySecureStorageDelegate() }
            single(named("syncRelayUrl")) { relay }
        }
        return koinApplication { modules(listOfNotNull(buildProxyModule(), buildPlatformProxyModule(), overrides, replicaModule)) }.koin
    }

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

    // ── Control's graph over the SQLite store ──

    @Test
    fun controlsGraphBindsTheSqliteStoreInstalledFirstAndDeclaresIt() {
        val made = made()
        val replica = control(made)
        val module = assertNotNull(replica.runtimeModule())
        assertTrue(made.binding.isInstalled, "installed before the graph exists")
        assertEquals(ReplicaChoice.UNDECLARED, made.choice.read(), "declared only once the store is bound")
        val koin = controlGraph(module)
        val store = koin.get<ObjectStore>() as? SharedReplicaStore ?: fail("not the SQLite store")
        assertSame(store.databaseLock, koin.get<DatabaseLock>())
        assertTrue(exists("${made.directory}/objects.db"), "the binding's directory's store")
        assertEquals(ReplicaChoice.SQLITE, made.choice.read())
        replica.bindLoops(koin)
        assertEquals(1, made.adapters(), "one adapter per process")
    }

    @Test
    fun anOpenProtectsReplicaFilesUploadStagingAndTheWatermarkAtRest() {
        // D3 and D3b (§2.4): all excluded from backup once a process opened. ⚠⚠ B.21.6 (414):
        // `sync-hlc/` too, read back from a fresh URL like the others.
        val made = made()
        controlGraph(assertNotNull(control(made).runtimeModule())).get<ObjectStore>()
        val excluded = org.tenshu.core.common.proxy.db.ContainerAtRest.FoundationPlatform
        listOf("replica", "files", "upload-staging", "sync-hlc").forEach { name ->
            assertTrue(excluded.isExcludedFromBackup("${made.root}/$name"), "$name is excluded from backup")
        }
    }

    @Test
    fun aD3bFailureIsLoggedAndNeverRefusesTheStore() {
        val made = made()
        made.binding.containerAtRest = {
            org.tenshu.core.common.proxy.db.ContainerAtRest(platform = object : org.tenshu.core.common.proxy.db.ContainerAtRest.Platform by org.tenshu.core.common.proxy.db.ContainerAtRest.FoundationPlatform {
                override fun excludeFromBackup(path: String): Boolean = false
                override fun isExcludedFromBackup(path: String): Boolean = false
            })
        }
        val koin = controlGraph(assertNotNull(control(made).runtimeModule()))
        assertTrue(koin.get<ObjectStore>() is SharedReplicaStore, "files/ and upload-staging/ are not the store")
    }

    @Test
    fun aControlLaunchOverANewerBuildsStoreGetsTheStandInAndDeclaresNothing() {
        // ⚠⚠ B.17 (254), Q40: no file-store fallback, so a newer sibling's store never moves
        // Control, and with it every app, onto the file store.
        val made = made(seams = Seams(open = { ReplicaOpenResult.Skew("made up") }))
        val koin = controlGraph(control(made).runtimeModule())
        val standIn = koin.get<ObjectStore>() as? NotReadyReplicaStore ?: fail("the stand-in")
        assertSame(standIn.databaseLock, koin.get<DatabaseLock>())
        assertEquals(ReplicaChoice.UNDECLARED, made.choice.read(), "⚠⚠ nothing declared (190)")
    }

    @Test
    fun aGraphOnTheProductionRelayBindsTheSqliteStore() {
        // ⚠⚠ B.22: no dev-lab guard; a release build's relay binds SQLite.
        val made = made()
        val koin = controlGraph(control(made).runtimeModule(), relay = "https://relay.tenshu.app")
        assertTrue(koin.get<ObjectStore>() is SharedReplicaStore, "the SQLite store")
        assertEquals(ReplicaChoice.SQLITE, made.choice.read())
    }

    @Test
    fun nothingDeclaredOrAnUnchangedResetStartsFromTheSqliteStore() {
        listOf<String?>(null, SharedReplicaChoice.RESET).forEach { declared ->
            val made = made()
            if (declared != null) made.choice.declare(declared)
            val koin = controlGraph(assertNotNull(control(made).runtimeModule(), "$declared"))
            assertTrue(koin.get<ObjectStore>() is SharedReplicaStore, "$declared")
            assertEquals(ReplicaChoice.SQLITE, made.choice.read(), "$declared")
        }
    }

    @Test
    fun aDeclarationReadsByItsFirstWord() {
        // A choice an older build wrote: the file store's directory recorded after the word (B.22.9).
        listOf("sqlite", "sqlite absent", "sqlite 1:2:3:4\n").forEach { line ->
            val made = made()
            @Suppress("CAST_NEVER_SUCCEEDS")
            (line as platform.Foundation.NSString).writeToFile(made.choice.declarationPath, true, platform.Foundation.NSUTF8StringEncoding, null)
            assertEquals(ReplicaChoice.SQLITE, made.choice.read(), line)
        }
    }

    // ── the foreground's re-read (196) ──

    @Test
    fun aBoundAppWhoseChoiceChangesIsFencedAtTheNextForeground() {
        val made = made()
        var reading = ReplicaChoice.SQLITE
        val base = appRuntime(made) { reading }.assembleBase(secureStorage = InMemorySecureStorageDelegate(), relayBaseUrl = devRelay)
        val store = base.koin.get<ObjectStore>() as? SharedReplicaStore ?: fail("not the SQLite store")
        val loops = Recorder()
        made.binding.loops.bind(loops)
        made.app.postWillEnterForeground()
        assertNull(store.fencedBy, "still chosen: still served")
        reading = ReplicaChoice.UNDECLARED
        made.app.postWillEnterForeground()
        assertNotNull(store.fencedBy, "no longer chosen: fenced, not a second replica")
        assertEquals(listOf("stop"), loops.calls, "its loops stopped")
        made.binding.loops.start()
        val later = Recorder()
        made.binding.loops.bind(later)
        made.binding.loops.start()
        assertEquals(emptyList(), later.calls, "a later bind is refused")
        assertEquals(listOf("stop"), loops.calls, "for good: no foreground starts them again")
    }

    @Test
    fun controlIsNeverFencedOffTheSqliteStore() {
        // ⚠⚠ Control is the declarer: whatever the choice reads at its foreground, it keeps serving.
        val made = made()
        val replica = control(made)
        val koin = controlGraph(replica.runtimeModule())
        val store = koin.get<ObjectStore>() as? SharedReplicaStore ?: fail("not the SQLite store")
        assertEquals(ReplicaChoice.SQLITE, made.choice.read())
        made.choice.declare(SharedReplicaChoice.RESET)
        made.app.postWillEnterForeground()
        assertNull(store.fencedBy, "Control stays on SQLite")
    }

    @Test
    fun aBindingThatCannotInstallLeavesControlOnTheStandIn() {
        // ⚠⚠ The stand-in, and nothing declared an app could follow.
        val made = made(seams = Seams(extension = true))
        val koin = controlGraph(control(made).runtimeModule())
        assertTrue(isNotReadyStandIn(koin.get<ObjectStore>()), "the stand-in")
        assertEquals(ReplicaChoice.UNDECLARED, made.choice.read(), "nothing declared")
    }

    // ── the replica choice (190) ──

    @Test
    fun theChoiceReadsWhatControlDeclared() {
        val made = made()
        assertEquals(ReplicaChoice.UNDECLARED, made.choice.read())
        made.choice.declare(SharedReplicaChoice.SQLITE)
        assertEquals(ReplicaChoice.SQLITE, made.choice.read())
        made.choice.declare(SharedReplicaChoice.RESET)
        assertEquals(ReplicaChoice.RESET, made.choice.read())
        assertFailsWith<IllegalArgumentException> { made.choice.declare("files") }
        @Suppress("CAST_NEVER_SUCCEEDS")
        ("files" as platform.Foundation.NSString).writeToFile(made.choice.declarationPath, true, platform.Foundation.NSUTF8StringEncoding, null)
        assertEquals(ReplicaChoice.UNDECLARED, made.choice.read(), "an older build's `files` is not a declaration")
        @Suppress("CAST_NEVER_SUCCEEDS")
        ("made-up" as platform.Foundation.NSString).writeToFile(made.choice.declarationPath, true, platform.Foundation.NSUTF8StringEncoding, null)
        assertEquals(ReplicaChoice.UNDECLARED, made.choice.read(), "an unknown word is not a declaration")
    }

    // ── an app follows Control (190) ──

    private fun appRuntime(made: Made, choice: () -> ReplicaChoice): FirstPartyLocalRuntime =
        FirstPartyLocalRuntime(FirstPartyLocalRuntime.Config(clientId = "app.example.follower", secureStorageService = "test.follower")).also {
            it.stillDeprovisioned = { true }
            it.sharedReplicaSqliteBinding = { made.binding }
            it.sharedReplicaChoice = choice
        }

    @Test
    fun anAppBindsTheSqliteStoreOnlyOnceControlHasDeclaredIt() {
        ReplicaChoice.entries.filter { it != ReplicaChoice.SQLITE }.forEach { reading ->
            val made = made()
            val base = appRuntime(made) { reading }.assembleBase(secureStorage = InMemorySecureStorageDelegate(), relayBaseUrl = devRelay)
            assertTrue(isNotReadyStandIn(base.koin.get<ObjectStore>()), "$reading: the stand-in")
        }
        val made = made()
        val base = appRuntime(made) { ReplicaChoice.SQLITE }.assembleBase(secureStorage = InMemorySecureStorageDelegate(), relayBaseUrl = devRelay)
        assertTrue(base.koin.get<ObjectStore>() is SharedReplicaStore)
    }

    @Test
    fun anAppReadsTheRealDeclaration() {
        val made = made()
        val runtime = appRuntime(made) { made.choice.read() }
        assertTrue(isNotReadyStandIn(runtime.assembleBase(secureStorage = InMemorySecureStorageDelegate(), relayBaseUrl = devRelay).koin.get<ObjectStore>()), "undeclared")
        made.choice.declare(SharedReplicaChoice.SQLITE)
        assertTrue(runtime.assembleBase(secureStorage = InMemorySecureStorageDelegate(), relayBaseUrl = devRelay).koin.get<ObjectStore>() is SharedReplicaStore)
    }

    @Test
    fun aSwitchedAppsCustodyPurgeDoesNotAskTheChoice() {
        val made = made()
        val runtime = appRuntime(made) { fail("the purge asked the choice") }
        val delegate = InMemorySecureStorageDelegate().also { it.storeString(SyncManagerImpl.Keys.DEVICE_ID, "made-up-device") }
        assertTrue(runtime.purgeCustody(delegate), "no store to wipe: custody goes")
        assertNull(delegate.getString(SyncManagerImpl.Keys.DEVICE_ID))
    }

    // ── Control's forget over the shared store (191) ──

    private fun custody(): InMemorySecureStorageDelegate =
        InMemorySecureStorageDelegate().also { it.storeString(SyncManagerImpl.Keys.DEVICE_ID, "made-up-device") }

    private fun forget(replica: ControlSharedReplica, delegate: InMemorySecureStorageDelegate): ForgetWipe = runBlocking {
        replica.wipeThenPurgeCustody { delegate.remove(SyncManagerImpl.Keys.DEVICE_ID) }
    }

    @Test
    fun theForgetWaitsForTheWorkersWipeThenPurgesCustody() {
        val made = made()
        made.binding.installAtLaunch()
        val live = made.binding.open(replicaBuildOfThisBuild())
        live.databaseLock.withLock { (1L..5L).forEach { live.storeObject(row(it)) } }
        val delegate = custody()
        val wiped = forget(control(made), delegate)
        assertTrue(wiped.custodyMayGo, "${wiped.note}")
        assertNull(delegate.getString(SyncManagerImpl.Keys.DEVICE_ID), "custody purged after the wipe")
        assertEquals(SharedReplicaJobOutcome.Kind.REPLACEMENT_FINISHED, made.binding.lastOutcome()?.kind, "the worker ran the wipe")
        assertNotNull(live.fencedBy, "Control's own store was released before the wipe")
        assertFalse(live.hasObject(1))
        val after = made.binding.open(replicaBuildOfThisBuild())
        assertTrue(after !== live)
        assertFalse(after.hasObject(1), "wiped")
        assertEquals(ReplicaChoice.RESET, made.choice.read(), "the next Control launch decides again")
        assertTrue(wiped.relaunchControl, "the forget tells the user to relaunch Control (193)")
    }

    @Test
    fun theForgetStopsControlsOldLoopsAndNoForegroundStartsThem() {
        val made = made()
        made.binding.installAtLaunch()
        made.binding.open(replicaBuildOfThisBuild()).let { s -> s.databaseLock.withLock { s.storeObject(row(1)) } }
        val loops = Recorder()
        made.binding.loops.bind(loops)
        assertTrue(forget(control(made), custody()).custodyMayGo)
        made.binding.loops.start()
        made.app.postWillEnterForeground()
        assertEquals(listOf("stop"), loops.calls, "stopped, and no foreground starts the old graph's loops again")
    }

    // ── B.16 (217): Control's graph rebuilt after its forget ──

    /** Control's graph as `ControlSurface.installRealRuntime` builds and hands it to the host, rebuilt the same way. */
    private class ControlGraphs(private val replica: ControlSharedReplica, private val graph: (Module?) -> Koin) {
        var koin: Koin? = null
        var installs = 0

        fun install() {
            installs++
            val made = graph(replica.runtimeModule())
            koin = made
            replica.bindLoops(made)
            replica.graphInstalled(made, stop = {}, rebuild = ::install)
        }

        val store: ObjectStore get() = koin!!.get()
    }

    @Test
    fun aFinishedForgetRebuildsControlsGraphAndTheNewGraphTakesWrites() {
        val made = made()
        val replica = control(made)
        val graphs = ControlGraphs(replica) { controlGraph(it) }
        graphs.install()
        val old = graphs.store as? SharedReplicaStore ?: fail("not the SQLite store")
        old.databaseLock.withLock { (1L..5L).forEach { old.storeObject(row(it)) } }
        assertEquals(SharedReplicaHostState.READY, replica.hostStatus?.value?.state)
        val delegate = custody()
        val wiped = forget(replica, delegate)
        assertTrue(wiped.custodyMayGo, "${wiped.note}")
        assertNull(delegate.getString(SyncManagerImpl.Keys.DEVICE_ID))
        assertEquals(2, graphs.installs, "⚠⚠ rebuilt once, after the forget, never during it")
        assertFalse(wiped.relaunchControl, "B.14 (193) lifted: no relaunch asked")
        val rebuilt = graphs.store as? SharedReplicaStore ?: fail("the rebuilt graph is not on the SQLite store")
        assertTrue(rebuilt !== old)
        assertNull(rebuilt.fencedBy)
        assertFalse(rebuilt.hasObject(1), "the wiped store")
        // Genesis in the same process: the store takes writes again.
        rebuilt.databaseLock.withLock { rebuilt.storeObject(row(9)) }
        assertTrue(rebuilt.hasObject(9))
        assertEquals(SharedReplicaHostState.READY, replica.hostStatus?.value?.state)
        assertEquals(ReplicaChoice.SQLITE, made.choice.read(), "the rebuilt graph declared what it bound")
    }

    @Test
    fun anUnfinishedForgetRebuildsControlsGraphAtTheNextForegroundOnly() {
        val made = made()
        val replica = control(made)
        val graphs = ControlGraphs(replica) { controlGraph(it) }
        graphs.install()
        (graphs.store as SharedReplicaStore).let { s -> s.databaseLock.withLock { s.storeObject(row(1)) } }
        made.app.grant = false
        val wiped = forget(replica, custody())
        assertFalse(wiped.custodyMayGo)
        assertTrue(wiped.relaunchControl, "not rebuilt now: the store is still behind meta.wipe")
        assertEquals(1, graphs.installs)
        assertTrue(made.binding.host.isRebuildOwed)
        // The next foreground: the kick finishes the wipe, and the host's rebuild opens over it.
        made.app.grant = true
        made.app.postWillEnterForeground()
        // Scheduled, never inside the notification (221); a rebuild that meets the wipe still
        // running takes the stand-in without waiting, and the job's finish rebuilds again (226, 227).
        within(30_000, "the rebuilt graph serves") { graphs.installs >= 2 && replica.hostStatus?.value?.state == SharedReplicaHostState.READY }
    }

    @Test
    fun q35ANotReadyControlOpenGetsTheStandInAndDeclaresNothing() {
        val made = made(seams = Seams(open = { throw org.tenshu.core.common.proxy.db.sqlite.ProtectionExpired("made up", 1, 1) }))
        val replica = control(made)
        val graphs = ControlGraphs(replica) { controlGraph(it) }
        graphs.install()
        assertTrue(isNotReadyStandIn(graphs.store), "⚠⚠ Q35: the stand-in")
        assertEquals(ReplicaChoice.UNDECLARED, made.choice.read(), "⚠⚠ nothing declared (190)")
        assertEquals(SharedReplicaHostState.NOT_READY, replica.hostStatus?.value?.state)
        assertTrue(made.binding.host.isRebuildOwed)
    }

    @Test
    fun theForgetReturnsOnlyAfterTheWorkerAndKeepsCustodyUntilThen() {
        val queue = HeldQueue()
        val made = made(queue = queue)
        made.binding.installAtLaunch()
        made.binding.open(replicaBuildOfThisBuild()).let { s -> s.databaseLock.withLock { s.storeObject(row(1)) } }
        val delegate = custody()
        val replica = control(made)
        var answer: ForgetWipe? = null
        val done = dispatch_semaphore_create(0)
        // The forget waits, suspended, for the worker; the test drains the worker meanwhile.
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            answer = runBlocking { replica.wipeThenPurgeCustody { delegate.remove(SyncManagerImpl.Keys.DEVICE_ID) } }
            dispatch_semaphore_signal(done)
        }
        within(30_000, "the wipe handed to the worker") { queue.size() >= 1 }
        assertNull(answer, "not answered before the worker ran")
        assertEquals("made-up-device", delegate.getString(SyncManagerImpl.Keys.DEVICE_ID), "custody kept while the wipe is pending")
        queue.drain()
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
        assertEquals(true, answer?.custodyMayGo)
        assertNull(delegate.getString(SyncManagerImpl.Keys.DEVICE_ID))
    }

    @Test
    fun aWipeThatStopsKeepsCustodyAndSaysWhy() {
        val made = made()
        made.binding.installAtLaunch()
        made.binding.open(replicaBuildOfThisBuild()).let { s -> s.databaseLock.withLock { s.storeObject(row(1)) } }
        made.app.grant = false
        val delegate = custody()
        val wiped = forget(control(made), delegate)
        assertFalse(wiped.custodyMayGo)
        assertTrue("REPLACEMENT_STOPPED" in (wiped.note ?: ""), "${wiped.note}")
        assertEquals("made-up-device", delegate.getString(SyncManagerImpl.Keys.DEVICE_ID), "D3: never custody gone over a store still present")
        assertEquals(ReplicaChoice.RESET, made.choice.read())
        // The marker stays: the next foreground's kick finishes the wipe, custody still kept by it.
        made.app.grant = true
        made.app.postWillEnterForeground()
        within(30_000, "the kick finished the wipe") { made.binding.lastOutcome()?.kind == SharedReplicaJobOutcome.Kind.REPLACEMENT_FINISHED }
        assertEquals("made-up-device", delegate.getString(SyncManagerImpl.Keys.DEVICE_ID), "a late finish never purges Control's custody behind the forget")
        // The user repeats the forget over the finished store: it completes.
        assertTrue(forget(control(made), delegate).custodyMayGo)
        assertNull(delegate.getString(SyncManagerImpl.Keys.DEVICE_ID))
    }

    @Test
    fun aWorkerThatNeverAnswersKeepsCustodyAtTheDeadline() {
        val queue = HeldQueue()
        val made = made(queue = queue)
        made.binding.installAtLaunch()
        made.binding.open(replicaBuildOfThisBuild()).let { s -> s.databaseLock.withLock { s.storeObject(row(1)) } }
        val delegate = custody()
        val wiped = forget(control(made, forgetWaitMs = 300), delegate)
        assertFalse(wiped.custodyMayGo)
        assertTrue("within" in (wiped.note ?: ""), "${wiped.note}")
        assertEquals("made-up-device", delegate.getString(SyncManagerImpl.Keys.DEVICE_ID))
    }

    @Test
    fun withNoStoreTheForgetPurgesCustody() {
        val made = made()
        val delegate = custody()
        assertTrue(forget(control(made), delegate).custodyMayGo)
        assertNull(delegate.getString(SyncManagerImpl.Keys.DEVICE_ID))
        assertTrue(made.binding.isInstalled, "the forget installs the binding when the launch did not")
    }

    @Test
    fun aNewerBuildsStoreHasItsFilesDeletedThenCustodyGoes() {
        val made = made()
        made.binding.installAtLaunch()
        // Made-up services this build does not know: to it, a newer build's store (Skew, D14).
        made.binding.open(build(3)).let { s -> s.databaseLock.withLock { s.storeObject(row(1)) } }
        val delegate = custody()
        assertTrue(forget(control(made), delegate).custodyMayGo)
        assertFalse(exists("${made.directory}/objects.db"), "the files are gone")
        assertNull(delegate.getString(SyncManagerImpl.Keys.DEVICE_ID))
    }

    @Test
    fun aCorruptStoreHasItsFilesDeletedThenCustodyGoes() {
        val made = made()
        made.binding.installAtLaunch()
        made.binding.open(replicaBuildOfThisBuild()).close()
        NSFileManager.defaultManager.createFileAtPath("${made.directory}/CORRUPT", null, null)
        val delegate = custody()
        assertTrue(forget(control(made), delegate).custodyMayGo)
        assertFalse(exists("${made.directory}/objects.db"))
        assertNull(delegate.getString(SyncManagerImpl.Keys.DEVICE_ID))
    }

    @Test
    fun aCorruptStoreAtControlsLaunchIsSnapshottedInTheForegroundAndItsCompleteCopyOfferedForExport() {
        // ⚠⚠ B.18 (Q47), end to end over the real store, the real engine and the real protection.
        val made = made()
        made.binding.installAtLaunch()
        made.binding.open(replicaBuildOfThisBuild()).close()
        ("time=0\napp=app.example.drive\nerror=made up\n" as platform.Foundation.NSString)
            .writeToFile("${made.directory}/CORRUPT", true, platform.Foundation.NSUTF8StringEncoding, null)
        val replica = control(made)
        val koin = controlGraph(replica.runtimeModule())
        assertTrue(isNotReadyStandIn(koin.get<ObjectStore>()), "a corrupt store is never served")
        assertTrue(made.binding.damagedCopies.isExporter, "Control alone takes the snapshot")
        replica.graphInstalled(koin, stop = {}, rebuild = { fail("a corrupt store is never rebuilt") })
        assertEquals(SharedReplicaHostState.CORRUPT, made.binding.host.status.value.state)
        // The binding's foreground hook is what retries it (the follow is `process()`'s, and is DamagedDataCopiesTest's).
        made.app.postWillEnterForeground()
        within(30_000, "the snapshot completes") { made.binding.damagedCopies.current.phase == DamagedCopyPhase.COMPLETE }
        val files = made.binding.damagedCopies.current.files
        assertTrue(files.all { it.startsWith("${made.root}/replica-damaged/") }, "$files")
        assertEquals(listOf("COMPLETE", "CORRUPT", "objects.db"), files.map { it.substringAfterLast('/') }.sorted())
        StorageStateSurface.snapshotOf(made.binding.host, made.binding.host.status.value, copy = made.binding.damagedCopies.current, exporter = true).let {
            assertTrue(it.canExportDamagedData)
            assertEquals(files, it.damagedDataFiles)
        }
        assertTrue(exists("${made.directory}/objects.db"), "the originals stay where they are (S1, D8)")
    }

    @Test
    fun controlCleansTheDamagedCopiesLeftoversAtLaunchOnceAndAfterItsForget() = runBlocking {
        // B.18.8 (316).
        val made = made()
        val cleanUps = ArrayList<String>()
        val replica = control(made, cleanUps = cleanUps)
        assertNotNull(replica.runtimeModule())
        assertEquals(listOf(made.directory), cleanUps, "at launch")
        assertNotNull(replica.runtimeModule())
        assertEquals(1, cleanUps.size, "once per process: a rebuild's assembly does not")
        replica.wipeForForget()
        assertEquals(listOf(made.directory, made.directory), cleanUps, "and after the forget")
    }

    @Test
    fun aBindingThatCannotInstallHasNoSnapshotExporter() {
        val onStandIn = made(seams = Seams(extension = true))
        assertNotNull(control(onStandIn).runtimeModule())
        assertFalse(onStandIn.binding.damagedCopies.isExporter, "no SQLite store to copy")
    }

    @Test
    fun anUnwipeableStoreOpenElsewhereKeepsCustody() {
        val seams = Seams(deleteFiles = { SharedReplicaDeletion.BUSY })
        val made = made(seams = seams)
        made.binding.installAtLaunch()
        made.binding.open(build(3)).close()
        val delegate = custody()
        val wiped = forget(control(made), delegate)
        assertFalse(wiped.custodyMayGo)
        assertTrue("close the other Tenshu apps" in (wiped.note ?: ""), "${wiped.note}")
        assertEquals("made-up-device", delegate.getString(SyncManagerImpl.Keys.DEVICE_ID))
    }

    @Test
    fun anyFailureKeepsCustodyAndNeverThrows() {
        val made = made()
        val delegate = custody()
        val wiped = forget(control(made, thisBuild = { throw IllegalStateException("made up") }), delegate)
        assertFalse(wiped.custodyMayGo)
        assertEquals("made-up-device", delegate.getString(SyncManagerImpl.Keys.DEVICE_ID))
        assertEquals(ReplicaChoice.RESET, made.choice.read(), "declared on every exit")
        val refused = made(seams = Seams(startWipe = { throw IllegalStateException("made up") }))
        assertFalse(forget(control(refused), custody()).custodyMayGo)
    }

    private class Recorder : org.tenshu.core.common.proxy.db.sqlite.SharedReplicaLoops {
        val calls = ArrayList<String>()
        override fun start() {
            calls += "start"
        }
        override fun stop() {
            calls += "stop"
        }
        override fun awaitStopped(timeoutMs: Long) = Unit
    }

    // ── siblings see Control's wipe (§6) ──

    @Test
    fun aSiblingSeesControlsWipeAndIsNeverHandedTheStaleStore() {
        val root = tempRoot()
        // A sibling process: the same files through another path, so it has a registry (and a
        // protection, a worker, a runtime handle) of its own, as another app's process would.
        val alias = NSTemporaryDirectory() + "control-replica-sibling-" + Random.nextLong().toString(16)
        assertTrue(NSFileManager.defaultManager.createSymbolicLinkAtPath(alias, withDestinationPath = "$root/replica", error = null))
        val sibling = made(root = root, directory = alias, appName = "app.example.sibling")
        sibling.binding.installAtLaunch()
        val siblingStore = sibling.binding.open(replicaBuildOfThisBuild())
        siblingStore.databaseLock.withLock { (1L..5L).forEach { siblingStore.storeObject(row(it)) } }
        assertTrue(siblingStore.hasObject(1))

        val control = made(root = root)
        val delegate = custody()
        assertTrue(forget(control(control), delegate).custodyMayGo)

        assertFalse(siblingStore.hasObject(1), "the sibling's next access meets the new epoch: absent")
        assertEquals(ReplicaFence.REPLACED, siblingStore.fencedBy)
        val reopened = sibling.binding.open(replicaBuildOfThisBuild())
        assertTrue(reopened !== siblingStore, "never the stale store")
        assertNull(reopened.fencedBy)
        assertFalse(reopened.hasObject(1), "the sibling sees the wiped store")
        reopened.databaseLock.withLock { reopened.storeObject(row(9)) }
        assertTrue(reopened.hasObject(9))
    }

    @Test
    fun anAppReadingResetWaitsOnTheStandIn() {
        // B.20.9 (376): Control mid-way (its forget) leaves `reset`.
        val made = made()
        val base = appRuntime(made) { ReplicaChoice.RESET }.assembleBase(secureStorage = InMemorySecureStorageDelegate(), relayBaseUrl = devRelay)
        val standIn = base.koin.get<ObjectStore>() as? NotReadyReplicaStore ?: fail("the stand-in")
        assertSame(standIn.databaseLock, base.koin.get<DatabaseLock>(), "the stand-in's own lock")
        assertTrue(standIn.status.willRetry, "rebuilt at the next foreground or entry")
    }
}
