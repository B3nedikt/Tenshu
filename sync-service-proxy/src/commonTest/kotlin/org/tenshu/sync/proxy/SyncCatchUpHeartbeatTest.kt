package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.getOrNull
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.proxy.db.ObjectStore.DbEntry
import org.tenshu.core.schema.hasTrait
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.collection.list.TList
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.trait.Syncable
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncCatchUpHeartbeat
import org.tenshu.sync.SyncService
import org.tenshu.sync.client.anyCatchUpHeartbeat
import org.tenshu.sync.client.catchUp
import org.tenshu.sync.client.catchUpOnThisStore
import org.tenshu.sync.client.syncServiceClientModule
import org.tenshu.sync.common.CatchUpHeartbeatTiming
import org.tenshu.sync.common.FirstPullMarker
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.CatchUpFixture.Answer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The catch-up **heartbeat** (`ios-sync-activity-indicator.md` §12): its writer, and the reader
 * that counts any writer's fresh beat — on virtual time, with the wall clock tied to it.
 *
 * Two writers over one store stand for two apps over the shared iOS replica; the same over two
 * route-1 assemblies sharing one test replica is `SyncActivitySharedReplicaTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncCatchUpHeartbeatTest {

    private val timing = CatchUpHeartbeatTiming.Default
    private val h = timing.intervalMs
    private val t = timing.staleAfterMs

    /** A wall clock that IS virtual time, offset so a stamp is never zero. */
    private fun TestScope.wall(offset: Long = 1_700_000_000_000): () -> Long = { offset + testScheduler.currentTime }

    // ---- the writer ----------------------------------------------------------------------------

    @Test
    fun theWriterBeats_everyInterval_whileCatchingUp_andNotOtherwise() = runTest {
        val proxy = proxy()
        val clock = wall()
        val catchUp = proxy.executionContext.initSyncCatchUp(backgroundScope, "app.a", clock, timing)
        runCurrent()
        advanceTimeBy(10 * h)
        assertNull(proxy.beat("app.a"), "idle: no heartbeat row is ever written")

        catchUp.raise()
        val first = clock()
        assertEquals(true to first, proxy.beat("app.a"), "the raise writes a beat before it returns")

        advanceTimeBy(h + 1)
        assertEquals(true to first + h, proxy.beat("app.a"))
        advanceTimeBy(h)
        assertEquals(true to first + 2 * h, proxy.beat("app.a"))

        catchUp.settle(false)
        runCurrent()
        val settledAt = clock()
        assertEquals(false to settledAt, proxy.beat("app.a"), "a settle writes `false` at once")

        advanceTimeBy(10 * h)
        assertEquals(false to settledAt, proxy.beat("app.a"), "and nothing beats after it")
    }

    @Test
    fun aTenPageCatchUpInsideOneInterval_costsOneBeatAndOneClear() = runTest {
        val writes = mutableListOf<Boolean>()
        var stored: SyncCatchUp.Beat? = null
        val catchUp = SyncCatchUp(
            row = object : SyncCatchUp.Row {
                override fun reads(value: Boolean) = true
                override fun write(value: Boolean) {}
            },
            heartbeat = object : SyncCatchUp.Heartbeat {
                override fun read() = stored
                override fun write(catchingUp: Boolean, atMillis: Long) {
                    stored = SyncCatchUp.Beat(catchingUp, atMillis, member = true)
                    writes += catchingUp
                }
            },
            nowMillis = wall(),
            timing = timing,
        )
        catchUp.publishIn(backgroundScope)

        repeat(10) { page ->
            catchUp.raise()
            catchUp.settle(page < 9)
            runCurrent()
        }
        advanceTimeBy(10 * h)

        assertEquals(listOf(true, false), writes)
    }

    @Test
    fun aRaise_putsBackABeatASiblingDroppedFromTheRelation() = runTest {
        // §10.3: a sibling writing the relation from a stale cache can drop this writer's member.
        // A raise inside the interval must not trust the row it can still read by id.
        var stored: SyncCatchUp.Beat? = null
        val writes = mutableListOf<Boolean>()
        val catchUp = SyncCatchUp(
            row = object : SyncCatchUp.Row {
                override fun reads(value: Boolean) = true
                override fun write(value: Boolean) {}
            },
            heartbeat = object : SyncCatchUp.Heartbeat {
                override fun read() = stored
                override fun write(catchingUp: Boolean, atMillis: Long) {
                    stored = SyncCatchUp.Beat(catchingUp, atMillis, member = true)
                    writes += catchingUp
                }
            },
            nowMillis = wall(),
            timing = timing,
        )
        catchUp.publishIn(backgroundScope)
        catchUp.raise()
        stored = stored!!.copy(member = false) // the sibling's write dropped it

        catchUp.raise() // the next page, well inside one interval

        assertEquals(listOf(true, true), writes)
        assertTrue(stored!!.member)
    }

    @Test
    fun stop_endsTheBeat_andClearsTheRow() = runTest {
        val proxy = proxy()
        val catchUp = proxy.executionContext.initSyncCatchUp(backgroundScope, "app.a", wall(), timing)
        catchUp.raise()

        catchUp.stop()
        runCurrent()
        val stoppedRow = proxy.beat("app.a")
        assertEquals(false, stoppedRow?.first)

        advanceTimeBy(10 * h)
        assertEquals(stoppedRow, proxy.beat("app.a"), "no beat after stop")
    }

    @Test
    fun aRelaunch_clearsItsOwnStaleBeat_andAnOrdinaryLaunchWritesNone() = runTest {
        val proxy = proxy()
        proxy.executionContext.initSyncCatchUp(backgroundScope.child(), "app.a", wall(), timing).raise()
        assertEquals(true, proxy.beat("app.a")?.first)

        proxy.executionContext.initSyncCatchUp(backgroundScope, "app.a", wall(), timing)
        assertEquals(false, proxy.beat("app.a")?.first, "its own start clears it, synchronously")

        proxy.executionContext.initSyncCatchUp(backgroundScope, "app.b", wall(), timing)
        assertNull(proxy.beat("app.b"), "an ordinary launch costs no heartbeat write")
    }

    // ---- the reader: two writers over one store ------------------------------------------------

    @Test
    fun aSiblingsPull_showsInThisAppsReader_andAKilledSiblingExpiresWithinT_withNoWrite() = runTest {
        val proxy = proxy()
        val clock = wall()
        val photosScope = backgroundScope.child()
        val controlScope = backgroundScope.child() // the process we will kill
        proxy.executionContext.initSyncCatchUp(photosScope, "app.photos", clock, timing)
        val control = proxy.executionContext.initSyncCatchUp(controlScope, "app.control", clock, timing)
        val photos = Observed(proxy.service().catchUpOnThisStore("app.photos", clock), backgroundScope)
        runCurrent()
        assertEquals(false, photos.last)

        control.raise()
        runCurrent()
        assertEquals(true, photos.last, "Control's pull lights Photos")

        // Control keeps beating: Photos stays lit well past T.
        advanceTimeBy(4 * t)
        assertEquals(true, photos.last)

        // Control is killed: its beat stops, nothing clears its row.
        controlScope.cancel()
        val lastBeat = proxy.beat("app.control")!!.second
        val writesBefore = photos.emissions
        val diedAt = clock()
        val expiry = lastBeat + t - diedAt
        advanceTimeBy(expiry - 1)
        assertEquals(true, photos.last, "still inside T of the last beat")
        advanceTimeBy(2)
        runCurrent()
        assertEquals(false, photos.last, "expired by itself")
        assertEquals(true, proxy.beat("app.control")!!.first, "the row still says true: no write cleared it")
        assertEquals(writesBefore + 1, photos.emissions, "the change came from time alone")
    }

    @Test
    fun aSiblingThatFinishesNormally_clearsThisAppsReaderPromptly() = runTest {
        val proxy = proxy()
        val clock = wall()
        val control = proxy.executionContext.initSyncCatchUp(backgroundScope, "app.control", clock, timing)
        val photos = Observed(proxy.service().catchUpOnThisStore("app.photos", clock), backgroundScope)
        control.raise()
        runCurrent()
        assertEquals(true, photos.last)
        val at = testScheduler.currentTime

        control.settle(false)
        runCurrent()

        assertEquals(false, photos.last)
        assertEquals(at, testScheduler.currentTime, "no wait for T: the `false` beat cleared it")
    }

    @Test
    fun onAStoreThatDoesNotHearASibling_theReReadKeepsItsPullLit_andAKillExpiresWithinT() = runTest {
        // The shared iOS replica: a sibling process's writes reach this process's store but not
        // its listeners (§12.3). Modelled by a second proxy over the same rows whose store is deaf.
        val (control, photos) = deafPair()
        val clock = wall()
        val controlScope = backgroundScope.child()
        control.executionContext.initSyncCatchUp(controlScope, "app.control", clock, timing).raise()
        val seen = Observed(photos.service().catchUpOnThisStore("app.photos", clock), backgroundScope)
        runCurrent()
        assertEquals(true, seen.last, "the first read sees Control's beat")

        advanceTimeBy(4 * t)
        assertEquals(true, seen.last, "Control is still beating, and the re-read sees each beat")

        controlScope.cancel() // killed
        advanceTimeBy(t + h)
        assertEquals(false, seen.last, "a killed sibling expires by itself")
    }

    @Test
    fun onAStoreThatDoesNotHearASibling_aNormalFinishIsSeenWithinOneInterval() = runTest {
        val (control, photos) = deafPair()
        val clock = wall()
        val writer = control.executionContext.initSyncCatchUp(backgroundScope, "app.control", clock, timing)
        writer.raise()
        val seen = Observed(photos.service().catchUpOnThisStore("app.photos", clock), backgroundScope)
        runCurrent()
        assertEquals(true, seen.last)

        writer.settle(false)
        runCurrent()
        advanceTimeBy(h + 1)
        assertEquals(false, seen.last, "within one interval, not T")
    }

    private fun TestScope.deafPair(): Pair<TestProxy, TestProxy> {
        val store = InMemoryObjectStore()
        return proxy(store) to proxy(DeafObjectStore(store))
    }

    @Test
    fun aBeatFarInTheFuture_staysDark_atEverySubscription() = runTest {
        // A killed app's last beat, stamped a day ahead (the clock has since been set back). The
        // surface re-subscribes at every foreground; not one subscription may light it.
        val proxy = proxy()
        val clock = wall()
        proxy.writeBeat("app.control", catchingUp = true, atMillis = clock() + 86_400_000)
        repeat(5) { foreground ->
            val photos = Observed(proxy.service().catchUpOnThisStore("app.photos", clock), backgroundScope.child())
            runCurrent()
            assertEquals(false, photos.last, "foreground $foreground")
            assertEquals(1, photos.emissions, "and never lit, even briefly")
            advanceTimeBy(60_000)
        }
    }

    @Test
    fun aBeatLessThanOneIntervalAhead_isOrdinarySkew_andCounts() = runTest {
        val proxy = proxy()
        val clock = wall()
        proxy.writeBeat("app.control", catchingUp = true, atMillis = clock() + h - 1)
        val photos = Observed(proxy.service().catchUpOnThisStore("app.photos", clock), backgroundScope)
        runCurrent()
        assertEquals(true, photos.last)
        advanceTimeBy(t - 1)
        assertEquals(true, photos.last, "still fresh: it counts from its stamp, which is ahead")
        advanceTimeBy(h + 1)
        assertEquals(false, photos.last, "and expires T after its stamp")
    }

    @Test
    fun aLongCatchUp_neverReadsTheRowAsGoneBetweenBeats() = runTest {
        // A beat rewrites a member in place. Rewritten as remove-then-add, every beat reached the
        // relation stream as "gone, then back" — a list without the row, once per interval, which
        // a reader with no own row turns into `false` between two `true`s.
        val proxy = proxy()
        val clock = wall()
        val control = proxy.executionContext.initSyncCatchUp(backgroundScope, "app.control", clock, timing)
        val lists = mutableListOf<List<String>>()
        proxy.service().syncCatchUpHeartbeats.flow()
            .onEach { r -> lists += r.getOrNull()?.toList().orEmpty().map { it.key.get() } }
            .launchIn(backgroundScope)
        val seen = mutableListOf<Boolean>()
        proxy.service().anyCatchUpHeartbeat(clock).onEach { seen += it }.launchIn(backgroundScope)
        runCurrent()
        control.raise()
        runCurrent()
        val first = lists.indexOfFirst { "app.control" in it }

        advanceTimeBy(20 * h)

        assertTrue(proxy.beat("app.control")!!.second >= clock() - h, "it really was beating")
        assertTrue(first >= 0)
        assertEquals(emptyList(), lists.drop(first).filter { "app.control" !in it }, "never read as gone")
        assertEquals(listOf(false, true), seen, "twenty beats, one appearance")
    }

    @Test
    fun aBeat_isOneStoreWrite() = runTest {
        val counting = CountingObjectStore(InMemoryObjectStore())
        val proxy = proxy(counting)
        val clock = wall()
        val control = proxy.executionContext.initSyncCatchUp(backgroundScope, "app.control", clock, timing)
        control.raise()
        runCurrent()
        val before = counting.writes

        advanceTimeBy(10 * h + 1) // ten beats

        assertEquals(10, counting.writes - before, "one row write per beat, no array rewrite")
    }

    @Test
    fun theRuntimeWritesNoHeartbeat_unlessItsPlatformAsks() = runTest {
        // Android, desktop and web: one runtime per store, and nothing reads the relation.
        val f = CatchUpFixture.create(backgroundScope, writerId = "app.a")
        f.syncManager.realmRelayRegistered = true
        f.syncManager.firstPullMarker = FirstPullMarker.COMPLETED
        f.syncManager.backfillCursor = 0
        f.forward += Answer.Page(blocks = f.junkBlocks(1), nextCursor = 2_000, hasMore = true)
        f.scheduler.tickOnce()
        runCurrent()
        advanceTimeBy(4 * h)

        assertEquals(true, f.row(), "catching up, and its own row says so")
        assertNull(f.proxy.beat("app.a"), "but no heartbeat was written")
        f.scheduler.stop()
    }

    @Test
    fun anIdleStore_doesNoPolling() = runTest {
        // The reader's clock is its probe: every evaluation reads it once. Polling every interval
        // would read it 720 times in an hour.
        val proxy = proxy()
        val clock = wall()
        var reads = 0
        val probed = { reads++; clock() }
        proxy.executionContext.initSyncCatchUp(backgroundScope, "app.photos", clock, timing)
        proxy.writeBeat("app.gone", catchingUp = true, atMillis = clock() - 10 * t) // long dead
        proxy.writeBeat("app.done", catchingUp = false, atMillis = clock())
        val photos = Observed(proxy.service().catchUpOnThisStore("app.photos", probed), backgroundScope)
        runCurrent()
        assertEquals(false, photos.last)
        val settled = reads

        advanceTimeBy(3_600_000)

        assertEquals(settled, reads, "an hour of idle store: not one more evaluation")
    }

    @Test
    fun theTimerEnds_withTheLastFreshRow() = runTest {
        val proxy = proxy()
        val clock = wall()
        var reads = 0
        val probed = { reads++; clock() }
        proxy.writeBeat("app.control", catchingUp = true, atMillis = clock())
        val photos = Observed(proxy.service().catchUpOnThisStore("app.photos", probed), backgroundScope)
        runCurrent()
        assertEquals(true, photos.last)

        advanceTimeBy(t + 1)
        assertEquals(false, photos.last, "expired at T")
        val atExpiry = reads

        advanceTimeBy(3_600_000)
        assertEquals(atExpiry, reads, "and then no timer")
    }

    // ---- the own row, unchanged ----------------------------------------------------------------

    @Test
    fun theOwnRow_stillLights_withNoFreshHeartbeat_andTakesNoTiming() = runTest {
        // The own half is read as it always was: no timing. Here the app's beat is dead (its
        // scope gone, the row written `false` behind it) and its own row still says `true` —
        // which, as before this change, holds until its own next start clears it.
        val proxy = proxy()
        val clock = wall()
        val scope = backgroundScope.child()
        proxy.executionContext.initSyncCatchUp(scope, "app.photos", clock, timing).raise()
        scope.cancel()
        proxy.writeBeat("app.photos", catchingUp = false, atMillis = clock())
        val photos = Observed(proxy.service().catchUpOnThisStore("app.photos", clock), backgroundScope)
        runCurrent()
        assertEquals(true, photos.last)

        advanceTimeBy(100 * t)
        assertEquals(true, photos.last, "the own row never expires")
        assertEquals(true, proxy.service().catchUp("app.photos").first(), "and `catchUp` itself is unchanged")
    }

    @Test
    fun aKilledSiblingsOwnRow_isStillNeverReadByThisApp() = runTest {
        // The own-row reader's rule (§3.1) survives: a sibling's `SyncCatchUpStatus` is never read.
        val proxy = proxy()
        val clock = wall()
        val scope = backgroundScope.child()
        proxy.executionContext.initSyncCatchUp(scope, "app.control", clock, timing).raise()
        scope.cancel()
        advanceTimeBy(t + 1)

        assertEquals(true, proxy.catchUpRow("app.control"), "the killed app's status row is still true")
        assertFalse(proxy.service().catchUp("app.photos").first())
        val photos = Observed(proxy.service().catchUpOnThisStore("app.photos", clock), backgroundScope)
        runCurrent()
        assertEquals(false, photos.last)
    }

    // ---- device-local --------------------------------------------------------------------------

    @Test
    fun heartbeats_enqueueNoSyncBlock_whereASyncableSaveDoes() = runTest {
        assertFalse(SyncCatchUpHeartbeat.Schema.hasTrait(Syncable.ID))

        val f = CatchUpFixture.create(backgroundScope, writerId = "app.a", catchUpHeartbeat = true)
        f.syncManager.realmRelayRegistered = true
        f.syncManager.firstPullMarker = FirstPullMarker.COMPLETED
        f.syncManager.backfillCursor = 0
        f.forward += Answer.Page(blocks = f.junkBlocks(1), nextCursor = 2_000, hasMore = true)
        f.scheduler.tickOnce()
        runCurrent()
        val before = f.pendingBlocks()

        advanceTimeBy(20 * h) // twenty beats
        f.proxy.executionContext.saveData(
            SyncCatchUpHeartbeat.create(f.proxy.tContext, "app.direct", catchingUp = true, beatAtMillis = 1),
        )
        runCurrent()
        assertEquals(true, f.proxy.beat("app.a")?.first, "the fixture really is beating")
        assertEquals(before, f.pendingBlocks(), "not one block for a heartbeat")

        // The positive control: the same observer, on a Syncable save, does enqueue one.
        val entity = Entity.create<SyntheticTestData>(
            context = f.proxy.tContext,
            composite = SyntheticTestData.Factory.create(f.proxy.tContext, name = "x.txt", value = 1L),
            properties = emptyMap(),
        )
        f.proxy.executionContext.saveData(entity, WriteOrigin.USER)
        runCurrent()
        assertTrue(f.pendingBlocks().size > before.size, "a Syncable save does queue a block here")
        f.scheduler.stop()
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** A scope that can be cancelled alone — a process to kill — and still ends with the test. */
    private fun CoroutineScope.child(): CoroutineScope = this + Job(coroutineContext[Job])

    /** Collects [flow] for the test's lifetime. */
    private class Observed(flow: kotlinx.coroutines.flow.Flow<Boolean>, scope: CoroutineScope) {
        var last: Boolean? = null
            private set
        var emissions = 0
            private set

        init {
            flow.onEach {
                last = it
                emissions++
            }.launchIn(scope)
        }
    }

    /** Counts row writes and removals. */
    private class CountingObjectStore(private val inner: ObjectStore) : ObjectStore by inner {
        var writes = 0
            private set

        override fun storeObject(dbEntry: DbEntry) {
            writes++
            inner.storeObject(dbEntry)
        }

        override fun removeObject(dataIdHash: Long) {
            writes++
            inner.removeObject(dataIdHash)
        }
    }

    /** A store whose listeners never fire: what a sibling's writes are to this process (§12.3). */
    private class DeafObjectStore(private val inner: ObjectStore) : ObjectStore by inner {
        override fun setListener(dataIdHash: Long, listener: (DbEntry) -> Unit) {}
        override fun removeListener(dataIdHash: Long) {}
    }

    /** On the test's own scheduler, so the client chain's reads run on virtual time. */
    private fun TestScope.proxy(store: ObjectStore = InMemoryObjectStore()): TestProxy {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry(), coroutineScope = this, objectStore = store)
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        proxy.schemaRegistry.registerConfig(syncServiceClientModule)
        return proxy
    }

    private fun TestProxy.service(): SyncService {
        val executor = LocalTaskExecutor(executionContext)
        return TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = schemaRegistry,
            requestChainFactory = { ctx -> LocalRequestChain(executor, ctx) },
            tContext = tContext,
        ).getService(SyncService.ID)
    }

    private fun CatchUpFixture.pendingBlocks(): List<String> = proxy.executionContext.database.getArrayData(
        ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = proxy.tContext,
                type = SyncBlock.ID,
                relationId = SyncService.Collections.PENDING_BLOCKS,
            ),
            isEntity = false,
            elementSchemaId = SyncBlock.ID,
        ),
    )?.toList()?.filterIsInstance<SyncBlock>()?.map { it.blockId.get() }.orEmpty()
}

private fun heartbeatRef(proxy: TestProxy) = ArrayDatabaseReference(
    arrayId = DataId.RelationDataId.create(
        context = proxy.tContext,
        type = TList.ID,
        relationId = SyncService.Relations.CATCH_UP_HEARTBEAT,
    ),
    isEntity = false,
    elementSchemaId = SyncCatchUpHeartbeat.ID,
)

/** [writer]'s heartbeat row as a reader of the relation would get it: `catchingUp to beatAt`. */
internal fun TestProxy.beat(writer: String): Pair<Boolean, Long>? =
    executionContext.database.getArrayData(heartbeatRef(this))
        ?.mapNotNull { it as? SyncCatchUpHeartbeat }
        ?.firstOrNull { it.key.get() == writer }
        ?.let { it.catchingUp.get() to it.beatAtMillis.get() }

/** Writes [writer]'s heartbeat row directly, as another process would have. */
internal fun TestProxy.writeBeat(writer: String, catchingUp: Boolean, atMillis: Long) {
    val ref = heartbeatRef(this)
    val id = DataId.StringDataId.create(context = tContext, type = SyncCatchUpHeartbeat.ID, keyValue = writer)
    val row = SyncCatchUpHeartbeat.create(tContext, writer, catchingUp, atMillis)
    executionContext.database.transaction {
        executionContext.database.removeFromArray(ref, id)
        executionContext.database.addToArray(ref, row)
    }
}
