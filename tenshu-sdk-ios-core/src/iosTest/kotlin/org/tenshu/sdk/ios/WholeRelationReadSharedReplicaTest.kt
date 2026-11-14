@file:OptIn(SharedReplicaPlatformApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package org.tenshu.sdk.ios

import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.proxy.db.ARRAY_READ_PAGE_SIZE
import org.tenshu.core.common.proxy.db.sqlite.ReplicaAccessFailed
import org.tenshu.core.common.proxy.db.sqlite.ReplicaBuild
import org.tenshu.core.common.proxy.db.sqlite.ReplicaLimits
import org.tenshu.core.common.proxy.db.sqlite.ReplicaOpenResult
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaDeletion
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaPlatformApi
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaStore
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaWipeStart
import org.tenshu.core.common.proxy.readOrderedWindow
import org.tenshu.core.common.relation.relationReference
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.SubjectKind
import org.tenshu.core.timeline.SubjectRef
import org.tenshu.core.timeline.TimelineService
import org.tenshu.timeline.test.createTimelineProxy
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSThread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.TimeSource

/**
 * A whole-relation read over the **real** iOS SQLite store (`core-ordered-window-fusion.md` §6.1,
 * `ios-shared-store-sqlite.md` S2 / §4.2.1). Every outermost access is one `BEGIN IMMEDIATE`
 * transaction under `HOLD_MAX` (1 s), and a member costs ~2 statements at ~0.11 ms each on a debug
 * simulator — ~4–5k members per second. [members] is several seconds' worth: read in one access it
 * holds the write lock past `HOLD_MAX` and fences the store `COMMIT_FAILED`. Paged
 * ([ARRAY_READ_PAGE_SIZE] members per access) it reads whole and the store keeps serving.
 *
 * The commonTest that pins the per-access bound with a counting lock is `PagedArrayReadTest`
 * (`:core-common`). Every name is made up.
 */
class WholeRelationReadSharedReplicaTest {

    private val build = ReplicaBuild(coreVersion = 22, coreFloor = 22, services = mapOf("example/timeline" to 1), serviceFloors = mapOf("example/timeline" to 1))

    /** The slice of `UIApplication` the binding's adapter uses: always foreground, always protected. */
    private class App : UiKitApplication {
        private val main: NSThread = NSThread.currentThread
        // ⚠ `==`, never `===`, on an NSThread (K/N).
        override val isMainThread: Boolean get() = NSThread.currentThread == main
        private var nextId = 0uL
        override fun isInBackground(): Boolean = false
        override fun beginBackgroundTask(name: String, onExpired: () -> Unit): ULong? = ++nextId
        override fun endBackgroundTask(id: ULong) = Unit
        override fun backgroundTimeRemainingSeconds(): Double = Double.MAX_VALUE
        override fun observeLifecycle(onDidEnterBackground: () -> Unit, onWillEnterForeground: () -> Unit) = Unit
        override fun runOnMainAsync(block: () -> Unit) = Unit
        override fun isProtectedDataAvailable(): Boolean = true
        override fun observeProtectedData(onChange: (Boolean) -> Unit) = Unit
    }

    private class Seams : SharedReplicaBinding.Seams {
        private val main: NSThread = NSThread.currentThread
        override val isMainThread: Boolean get() = NSThread.currentThread == main
        override fun isAppExtension(): Boolean = false
        override fun open(directory: String, build: ReplicaBuild, steps: org.tenshu.core.common.proxy.db.sqlite.ReplicaMigrationSteps, appName: String, foreground: Boolean, protectedDataAvailable: () -> Boolean): ReplicaOpenResult =
            SharedReplicaBinding.PlatformSeams.open(directory, build, steps, appName, foreground, protectedDataAvailable)
        override fun startWipe(directory: String, build: ReplicaBuild, appName: String, reason: String, protectedDataAvailable: () -> Boolean, beforeHandOff: (jobId: Long) -> Unit): SharedReplicaWipeStart =
            SharedReplicaBinding.PlatformSeams.startWipe(directory, build, appName, reason, protectedDataAvailable, beforeHandOff)
        override fun deleteFiles(directory: String): SharedReplicaDeletion = SharedReplicaBinding.PlatformSeams.deleteFiles(directory)
    }

    /** The real store, opened the way an app opens it: a binding installed at launch, then `open`. */
    private fun sqliteStore(): SharedReplicaStore {
        val directory = NSTemporaryDirectory() + "wholerelation-" + Random.nextLong().toString(16) + "/replica"
        NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
        val app = App()
        val binding = SharedReplicaBinding(
            directory = directory,
            appName = "app.example.timeline",
            seams = Seams(),
            adapterFactory = { onJobOutcome ->
                SharedReplicaUiKitAdapter(
                    app = app,
                    queue = DispatchReplicaWorkerQueue("org.tenshu.replica.worker.wholerelation-" + Random.nextLong().toString(16)),
                    log = {},
                    onJobOutcome = onJobOutcome,
                )
            },
        )
        binding.installAtLaunch()
        return binding.open(build)
    }

    /** ~4–5 s of one access on a debug simulator; well past `HOLD_MAX` on anything slower than 20k/s. */
    private val members = 20_000

    /** 2026-08-06T00:00:00Z. */
    private val midnight = 1_785_974_400_000L

    /** [members] visit moments in `MOMENTS`, written in chunks so no write access nears `HOLD_MAX`. */
    private fun seeded(store: SharedReplicaStore) = createTimelineProxy(objectStore = store, lock = store.databaseLock).also { proxy ->
        val ctx = proxy.executionContext
        val ref = ctx.relationReference(TimelineService.Relations.MOMENTS)
        (0 until members).chunked(250).forEach { chunk ->
            ctx.database.addAllToArray(
                ref,
                chunk.map { i ->
                    Moment.create(
                        context = ctx.context,
                        momentId = "visit-$i",
                        timestamp = UnixInstantDto(midnight + i * 60_000L),
                        kind = Moment.MomentKind.VISIT,
                        title = "Example place $i",
                        subject = SubjectRef.create(ctx.context, SubjectKind.PLACE, "place-${i % 97}"),
                        durationSec = 1_800,
                        sources = emptyList(),
                    )
                },
            )
        }
        assertEquals(members, ctx.database.getArrayMemberHashes(ref)?.size)
    }

    @Test
    fun aWholeRelationReadPagesAndTheStoreKeepsServing() {
        val store = sqliteStore() // on the test's thread: `installAtLaunch` is main-only
        val proxy = seeded(store)
        val ctx = proxy.executionContext
        val ref = ctx.relationReference(TimelineService.Relations.MOMENTS)

        val started = TimeSource.Monotonic.markNow()
        val read = ctx.getArrayData(ref)!!.toList()
        println("[whole-relation] $members members read paged in ${started.elapsedNow()}")

        assertEquals(members, read.size, "every member, once")
        assertNull(store.fencedBy, "no page held the write lock past HOLD_MAX")
        // And the store still takes a write after it.
        ctx.database.addAllToArray(
            ref,
            listOf(
                Moment.create(
                    context = ctx.context, momentId = "after-the-read", timestamp = UnixInstantDto(midnight),
                    kind = Moment.MomentKind.VISIT, title = "after", subject = SubjectRef.create(ctx.context, SubjectKind.NONE, ""),
                    durationSec = 0, sources = emptyList(),
                ),
            ),
        )
        assertEquals(members + 1, ctx.database.getArrayMemberHashes(ref)?.size)
    }

    /** The observer passes' read (`PlaceRoleInferencePass`, `MomentReconciliationPass`): a bounded selection over the whole scan. */
    @Test
    fun theObserverPassesBoundedReadPagesToo() {
        val store = sqliteStore()
        val proxy = seeded(store)
        val ctx = proxy.executionContext

        val newest = ctx.readOrderedWindow(
            reference = ctx.relationReference(TimelineService.Relations.MOMENTS),
            orderByFieldId = Moment.Fields.TIMESTAMP,
            limit = 50,
            keepFields = setOf(Moment.Fields.KIND),
        )

        assertEquals(50, newest.size)
        assertNull(store.fencedBy)
    }

    /**
     * The control that makes the two above mean something: the same read inside one
     * `transaction { }` is one access again, and at [members] it is refused at `COMMIT` for its hold.
     * ⚠ If this goes green-by-failing-to-throw on a fast Mac, raise [members]: the size is no longer
     * past `HOLD_MAX` there, and the two tests above prove nothing.
     */
    @Test
    fun theSameReadInOneTransactionIsPastHoldMax() {
        val store = sqliteStore()
        val proxy = seeded(store)
        val ctx = proxy.executionContext
        val ref = ctx.relationReference(TimelineService.Relations.MOMENTS)

        val started = TimeSource.Monotonic.markNow()
        assertFailsWith<ReplicaAccessFailed>("$members members in one access must exceed HOLD_MAX (${ReplicaLimits().holdMaxMs} ms)") {
            ctx.database.transaction { ctx.getArrayData(ref)!!.toList() }
        }
        println("[whole-relation] one-transaction read failed after ${started.elapsedNow()}")
    }
}
