package org.tenshu.core.common.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.tenshu.core.Data
import org.tenshu.core.PureOperationRunner
import org.tenshu.core.StreamElement
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.toTString
import org.tenshu.core.trait.traitResolver
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ⚠⚠ One session's stream registrations arrive **concurrently** — a screen `combine`s several
 * reads, each subscribing on its own worker — and `StreamRegistry`'s maps were plain, unguarded
 * hash maps. Two registrations writing at once could lose one's registration token, and that
 * stream's initial evaluation then judged itself ended and sent **no snapshot**: the read never
 * emitted. On iOS, Contacts' four-relation `combine` stayed blank in 12 of 150 assemblies
 * (`ContactsLocalRuntimeTest.aWrittenContactIsReadBack`'s "subscribe-window race"); a lost update
 * callback or ref listener set is the same race's other face, a stream deaf to its invalidations.
 * `core-client-resubscription.md` §4.9.
 *
 * Two tests, because a lost hash-map write cannot be forced deterministically:
 *  - [aRegistrationIsExclusiveOfAnotherSessionsRegistration] is the deterministic guard: a second
 *    registration must not make progress while the first is inside its own. Red without the lock.
 *  - [concurrentRegistrationsEachSendTheirSnapshotAndHearTheirChange] is the symptom itself under
 *    contention: every stream sends its snapshot and re-evaluates on its array's change.
 */
class StreamRegistryConcurrentRegistrationTest {

    @Test
    fun aRegistrationIsExclusiveOfAnotherSessionsRegistration() = runBlocking {
        val harness = harness()
        val first = ref("/first")
        val second = ref("/second")
        val secondReachedItsRegistration = CountDownLatch(1)
        var secondRanInsideFirst: Boolean? = null
        val pool = Executors.newFixedThreadPool(2)
        try {
            harness.db.onSetArrayListener = { reference ->
                when (reference) {
                    // Inside the first registration: let the second one run, and see how far it gets.
                    first -> {
                        pool.submit {
                            runBlocking { harness.registry.setDatabaseListener(harness.ec, 2L, second, emptyList()) }
                        }
                        secondRanInsideFirst = secondReachedItsRegistration.await(1, TimeUnit.SECONDS)
                    }
                    second -> secondReachedItsRegistration.countDown()
                }
            }
            pool.submit {
                runBlocking { harness.registry.setDatabaseListener(harness.ec, 1L, first, emptyList()) }
            }.get(10, TimeUnit.SECONDS)
            assertTrue(secondReachedItsRegistration.await(10, TimeUnit.SECONDS), "the second registration never completed")
        } finally {
            pool.shutdownNow()
        }
        assertEquals(
            false,
            secondRanInsideFirst,
            "a second registration reached its array listener while the first held the registry: " +
                "the registry's maps are being written from two threads at once",
        )
        assertEquals(setOf(1L, 2L), harness.connection.snapshotsByStream().keys, "both streams send their snapshot")
    }

    /**
     * ⚠ An `EndStream` that lands between a registration's stamp and its install must leave
     * nothing behind: before the install re-checked liveness it registered the array listener and
     * the callback anyway — a dead stream re-evaluating on every change until the id was
     * subscribed again — and raised `activeStreamCount` with nothing left to lower it, which holds
     * `SyncScheduler` on its fast tick.
     */
    @Test
    fun aStreamEndedBeforeItsInstallLeavesNoListenerCallbackOrCount() = runBlocking {
        val harness = harness()
        harness.registry.afterRegistrationStamped = {
            harness.registry.removeDatabaseListener(harness.ec, 7L)
        }
        harness.registry.setDatabaseListener(harness.ec, 7L, ref("/ended"), emptyList())

        assertEquals(0, harness.db.listenerCount(), "an ended stream left an array listener installed")
        assertEquals(0, harness.registry.activeStreamCount.value, "an ended stream is still counted as active")
        assertEquals(emptyMap(), harness.connection.snapshotsByStream(), "an ended stream sent a snapshot")
    }

    @Test
    fun concurrentRegistrationsEachSendTheirSnapshotAndHearTheirChange() {
        val streams = 16
        val pool = Executors.newFixedThreadPool(streams)
        try {
            repeat(ROUNDS) { round ->
                val harness = harness()
                val refs = (0 until streams).map { ref("/r$round/$it") }
                val go = CountDownLatch(1)
                val done = (0 until streams).map { i ->
                    pool.submit {
                        go.await()
                        runBlocking { harness.registry.setDatabaseListener(harness.ec, i.toLong(), refs[i], emptyList()) }
                    }
                }
                go.countDown()
                done.forEach { it.get(10, TimeUnit.SECONDS) }

                val snapshots = harness.connection.snapshotsByStream()
                val missing = (0L until streams).filter { it !in snapshots }
                if (missing.isNotEmpty()) fail("round $round: streams $missing never sent their initial snapshot")

                // Every stream must still hear its array: a lost callback or listener set is deaf.
                harness.db.rows = listOf("row".toTString())
                harness.db.fireAll()
                val changed = harness.connection.snapshotsByStream().filterValues { it >= 2 }.keys
                val deaf = (0L until streams).filter { it !in changed }
                if (deaf.isNotEmpty()) fail("round $round: streams $deaf never re-evaluated on their array's change")
            }
        } finally {
            pool.shutdownNow()
        }
    }

    // --- fixture ---

    private class Harness(val registry: StreamRegistry, val ec: ExecutionContext, val db: ThreadSafeDatabase, val connection: RecordingConnection)

    private fun harness(): Harness {
        val schemaRegistry = createCoreTestRegistry()
        val tContext = createTestContext(schemaRegistry)
        val db = ThreadSafeDatabase()
        val registry = StreamRegistry()
        val connection = RecordingConnection()
        val ec = ExecutionContext(
            context = tContext,
            database = db,
            traitResolver = tContext.traitResolver(),
            implementationFactory = { null },
            clientConnection = connection,
            pureOperationRunner = tContext.components[PureOperationRunner.ID] as PureOperationRunner,
            materializer = CallstackMaterializer(schemaRegistry, tContext),
            // Unconfined: a change's re-evaluation runs on the thread that fired it, so the test
            // can count it as soon as `fireAll` returns.
            sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            streamRegistry = registry,
        )
        return Harness(registry, ec, db, connection)
    }

    private class RecordingConnection : ClientConnection {
        val sent = CopyOnWriteArrayList<Data>()
        override fun init() {}
        override fun send(data: Data) { sent += data }
        override fun destroy() {}
        fun snapshotsByStream(): Map<Long, Int> =
            sent.filterIsInstance<StreamElement<*>>().groupingBy { it.streamId.get() }.eachCount()
    }

    private class ThreadSafeDatabase : Database {
        private val listeners = ConcurrentHashMap<Long, Pair<ArrayDatabaseReference, (Data, WriteOrigin) -> Unit>>()
        private val nextHandle = java.util.concurrent.atomic.AtomicLong()

        @Volatile var onSetArrayListener: (ArrayDatabaseReference) -> Unit = {}
        @Volatile var rows: List<Data> = emptyList()

        fun listenerCount() = listeners.size

        fun fireAll() = listeners.values.toList().forEach { (_, onAdded) -> onAdded("added".toTString(), WriteOrigin.USER) }

        override fun getData(dataId: DataId, asEntity: Boolean, projection: Projection?): Data? = null
        override fun saveData(data: Data) {}
        override fun deleteData(dataId: DataId) {}
        override fun getArrayData(reference: ArrayDatabaseReference, projection: Projection?): Sequence<Data> = rows.asSequence()
        override fun getComposite(reference: CompositeDatabaseReference): Data? = null
        override fun addToArray(reference: ArrayDatabaseReference, value: Data, origin: WriteOrigin) {}
        override fun removeFromArray(reference: ArrayDatabaseReference, dataId: DataId, origin: WriteOrigin) {}
        override fun setArrayListener(
            reference: ArrayDatabaseReference,
            onAdded: (Data, WriteOrigin) -> Unit,
            onRemoved: (DataId, WriteOrigin) -> Unit,
        ): Long {
            onSetArrayListener(reference)
            val handle = nextHandle.getAndIncrement()
            listeners[handle] = reference to onAdded
            return handle
        }
        override fun removeArrayListener(handle: Long) { listeners.remove(handle) }
        override fun <R> transaction(block: () -> R): R = block()
    }

    private fun ref(path: String) = ArrayDatabaseReference(
        arrayId = DataId.EntityFieldDataId.create(
            type = TList.ID,
            parentEntity = DataId.StringDataId.create(type = "tenshu/file", keyValue = path),
            fieldRef = "tenshu/file/files",
        ),
        isEntity = false,
    )

    private companion object {
        const val ROUNDS = 400
    }
}
