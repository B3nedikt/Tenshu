@file:OptIn(SharedReplicaPlatformApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package org.tenshu.sdk.ios

import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.proxy.db.sqlite.ReplicaBuild
import org.tenshu.core.common.proxy.db.sqlite.ReplicaLimits
import org.tenshu.core.common.proxy.db.sqlite.ReplicaOpenResult
import org.tenshu.core.common.proxy.db.sqlite.RowTooLarge
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaDeletion
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaPlatformApi
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaStore
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaWipeStart
import org.tenshu.core.entity.DataId
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.SubjectKind
import org.tenshu.core.timeline.SubjectRef
import org.tenshu.core.timeline.TimelineService
import org.tenshu.sync.common.RelationSliceSnapshot
import org.tenshu.timeline.common.slice.MomentSliceEntry
import org.tenshu.timeline.proxy.MomentSliceSnapshotProducer
import org.tenshu.timeline.test.createTimelineProxy
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSThread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The S0b `MomentSlice` blocker (`ios-shared-store-sqlite.md` §4.2.1, `sync-device-catch-up.md`
 * §11.4.4), over the **real** iOS SQLite store and its `ROW_MAX` (512 KiB). A slice snapshot is a
 * content-plane blob, never a store row: what a joiner writes from one is one ordinary `Moment` row
 * per entry. So even a slice over `ROW_MAX` — which a peer on a build before the writer's budget may
 * have sealed — applies here, where the same bytes in one row are `RowTooLarge`. Every name is made up.
 */
class MomentSliceRowMaxSharedReplicaTest {

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
        val directory = NSTemporaryDirectory() + "momentslice-rowmax-" + Random.nextLong().toString(16) + "/replica"
        NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
        val app = App()
        val binding = SharedReplicaBinding(
            directory = directory,
            appName = "app.example.timeline",
            seams = Seams(),
            adapterFactory = { onJobOutcome ->
                SharedReplicaUiKitAdapter(
                    app = app,
                    queue = DispatchReplicaWorkerQueue("org.tenshu.replica.worker.momentslice-" + Random.nextLong().toString(16)),
                    log = {},
                    onJobOutcome = onJobOutcome,
                )
            },
        )
        binding.installAtLaunch()
        return binding.open(build)
    }

    /** 2026-08-06T00:00:00Z. */
    private val midnight = 1_785_974_400_000L

    /** 600 visits with 1 KiB place names: over `ROW_MAX` as one slice, ~1 KiB as each row. */
    private val entries = List(600) { i ->
        val at = midnight + i * 60_000L
        val placeId = "ChIJ" + i.toString().padStart(23, '0')
        MomentSliceEntry(
            momentId = "gv:$placeId:$at",
            timestampMillis = at,
            kind = Moment.MomentKind.VISIT,
            title = "Gasthaus zur alten Mühle " + "x".repeat(1_000),
            subjectKind = SubjectKind.PLACE,
            subjectId = placeId,
            durationSec = 1_800,
            supersededBy = "",
        )
    }
    private val bytes = MomentSliceEntry.encode(entries)

    @Test
    fun aSliceOverRowMaxAppliesRowByRow() {
        val store = sqliteStore() // on the test's thread: `installAtLaunch` is main-only
        assertTrue(bytes.size > ReplicaLimits().rowMaxBytes, "the slice must be over ROW_MAX: ${bytes.size}")
        val proxy = createTimelineProxy(objectStore = store, lock = store.databaseLock)
        val ctx = proxy.executionContext

        val created = MomentSliceSnapshotProducer().applySlice(
            ctx,
            TimelineService.Relations.MOMENTS,
            MomentSliceEntry.dayKey(midnight),
            bytes, isDeleted = { false },
        )

        assertEquals(entries.size, created)
        entries.forEach { entry ->
            assertNotNull(
                ctx.database.getData(DataId.StringDataId.create(ctx.context, Moment.ID, entry.momentId)),
                "moment ${entry.momentId} must be in the store",
            )
        }
    }

    @Test
    fun theSameSliceInOneRowIsRefusedByTheStore() {
        // The control that makes the test above mean something: what the S0b row feared, the
        // slice's bytes inlined into one row, is refused at this size.
        val store = sqliteStore()
        val proxy = createTimelineProxy(objectStore = store, lock = store.databaseLock)
        val ctx = proxy.executionContext
        assertFailsWith<RowTooLarge> {
            ctx.saveData(
                Moment.create(
                    context = ctx.context,
                    momentId = "inlined-slice",
                    timestamp = UnixInstantDto(midnight),
                    kind = Moment.MomentKind.VISIT,
                    title = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') },
                    subject = SubjectRef.create(ctx.context, SubjectKind.NONE, ""),
                    durationSec = 0,
                    sources = emptyList(),
                ),
            )
        }
    }

    @Test
    fun theWriterBudgetIsUnderRowMax() {
        // What the writer may publish fits a row with room to spare, so persisting a fetched slice
        // locally one day cannot bring the blocker back (§11.4.4).
        assertTrue(RelationSliceSnapshot.MAX_ENCODED_BYTES + 64 * 1024 <= ReplicaLimits().rowMaxBytes)
    }
}
