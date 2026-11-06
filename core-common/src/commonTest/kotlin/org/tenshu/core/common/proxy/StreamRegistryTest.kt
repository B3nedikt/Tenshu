package org.tenshu.core.common.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.PureOperationRunner
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.toTString
import org.tenshu.core.trait.traitResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit-level coverage of `StreamRegistry` in isolation — complements
 * `ExecutionContextStreamTest`'s integration tests (which exercise the
 * registry through `TestProxy` + real `DatabaseImpl`). The pieces tested
 * here aren't observable through the integration fixture without
 * inspecting registry-internal state directly:
 *
 * - `clear()` drops every per-stream map (verified by checking that
 *   a stream id can be re-registered after clear without side-effects
 *   leaking from the prior registration).
 * - `removeDatabaseListener` for an unknown stream id is a no-op
 *   (no crash, no spurious database-listener removal).
 * - Reference-counting across multiple streams subscribed to the same
 *   `ArrayDatabaseReference`: the underlying `database.setArrayListener`
 *   fires exactly once across N subscribers; `database.removeArrayListener`
 *   fires only when the last subscriber leaves.
 */
class StreamRegistryTest {

    /**
     * ⚠ Stream ids are callstack hashes and get reused. Whatever the transport holds for an id
     * (an Android end that did not go out) must be dropped when the id is registered again or
     * unregistered, or it lands on the next subscription (`core-client-resubscription.md` §4.8).
     */
    @Test
    fun registeringAndUnregisteringAStreamTellsTheTransportToForgetIt() = runTest {
        val harness = createRegistryHarness()
        val ref = makeArrayRef("/folder")

        harness.registry.setDatabaseListener(harness.executionContext, streamId = 7L, ref, emptyList())
        assertEquals(listOf(7L), harness.connection.forgotten)

        harness.registry.removeDatabaseListener(harness.executionContext, streamId = 7L)
        assertEquals(listOf(7L, 7L), harness.connection.forgotten)
    }

    @Test
    fun removeDatabaseListener_forUnknownStreamId_isNoOp() = runTest {
        val (registry, executionContext, db) = createRegistryHarness()

        // Unknown stream id — no prior setDatabaseListener call.
        registry.removeDatabaseListener(executionContext, streamId = 999L)

        assertEquals(0, db.removeArrayListenerCalls.size, "must not touch database for unknown stream")
        assertEquals(0, db.setArrayListenerCalls.size, "must not call setArrayListener")
    }

    @Test
    fun clear_dropsAllState_subsequentRegistrationStartsFresh() = runTest {
        val (registry, executionContext, db) = createRegistryHarness()
        val ref = makeArrayRef("/folder")

        registry.setDatabaseListener(executionContext, streamId = 1L, ref, emptyList())
        assertEquals(1, db.setArrayListenerCalls.size, "first stream subscribes the array listener")

        registry.clear()

        // After clear, the registry has forgotten the stream. A subsequent
        // removeDatabaseListener for the same id is a no-op, *and* a new
        // setDatabaseListener on the same ref freshly subscribes the database
        // listener again (proving the refToStreamCount map was cleared too).
        registry.removeDatabaseListener(executionContext, streamId = 1L)
        assertEquals(
            0,
            db.removeArrayListenerCalls.size,
            "clear must drop the ref so remove is a no-op",
        )

        registry.setDatabaseListener(executionContext, streamId = 1L, ref, emptyList())
        assertEquals(
            2,
            db.setArrayListenerCalls.size,
            "after clear, the second registration must re-subscribe the database listener",
        )
    }

    @Test
    fun multipleStreamsOnSameRef_shareSingleDatabaseListener() = runTest {
        val (registry, executionContext, db) = createRegistryHarness()
        val ref = makeArrayRef("/shared")

        registry.setDatabaseListener(executionContext, streamId = 10L, ref, emptyList())
        registry.setDatabaseListener(executionContext, streamId = 11L, ref, emptyList())
        registry.setDatabaseListener(executionContext, streamId = 12L, ref, emptyList())

        assertEquals(
            1,
            db.setArrayListenerCalls.size,
            "ref-counting: three streams on the same ref share a single underlying database listener",
        )
    }

    @Test
    fun activeStreamCount_ticksWithRegistrationsAndClear() = runTest {
        val (registry, executionContext, _) = createRegistryHarness()
        val refA = makeArrayRef("/a")
        val refB = makeArrayRef("/b")

        assertEquals(0, registry.activeStreamCount.value, "starts at zero")

        registry.setDatabaseListener(executionContext, streamId = 30L, refA, emptyList())
        assertEquals(1, registry.activeStreamCount.value, "increment on first register")

        registry.setDatabaseListener(executionContext, streamId = 31L, refB, emptyList())
        assertEquals(2, registry.activeStreamCount.value, "increment on second register")

        registry.removeDatabaseListener(executionContext, streamId = 30L)
        assertEquals(1, registry.activeStreamCount.value, "decrement on remove")

        // Unknown stream id is a no-op; must not double-decrement.
        registry.removeDatabaseListener(executionContext, streamId = 999L)
        assertEquals(1, registry.activeStreamCount.value, "unknown stream removal stays at 1")

        registry.clear()
        assertEquals(0, registry.activeStreamCount.value, "clear resets to zero")
    }

    @Test
    fun removeDatabaseListener_keepsUnderlyingListenerUntilLastSubscriberLeaves() = runTest {
        val (registry, executionContext, db) = createRegistryHarness()
        val ref = makeArrayRef("/refcount")

        registry.setDatabaseListener(executionContext, streamId = 20L, ref, emptyList())
        registry.setDatabaseListener(executionContext, streamId = 21L, ref, emptyList())
        assertEquals(1, db.setArrayListenerCalls.size)
        assertEquals(0, db.removeArrayListenerCalls.size)

        // Removing the first stream must NOT detach the database listener — the
        // second stream is still subscribed.
        registry.removeDatabaseListener(executionContext, streamId = 20L)
        assertEquals(
            0,
            db.removeArrayListenerCalls.size,
            "underlying listener stays while another subscriber holds the ref",
        )

        // Removing the last stream detaches it.
        registry.removeDatabaseListener(executionContext, streamId = 21L)
        assertEquals(
            1,
            db.removeArrayListenerCalls.size,
            "last-subscriber removal detaches the underlying database listener",
        )
        assertTrue(
            db.removeArrayListenerCalls.single() == ref,
            "removed listener must be the same ref that was subscribed",
        )
    }

    /**
     * A stream's references are tracked per reference, but an array's listeners are tracked per
     * array — and one stream can hold two references to the same array (they differ in shape:
     * `elementSchemaId`, `executionStack`). Dropping one of them used to take the stream out of
     * the array's listener set although the other was still held, so the stream went deaf to an
     * array it was still reading: the listing stopped updating, with nothing reported anywhere.
     */
    @Test
    fun aStreamStillHoldingAnotherReferenceToAnArrayKeepsHearingIt() = runTest {
        val (registry, executionContext, db) = createRegistryHarness(
            // Unconfined, so a fired listener has re-evaluated by the time the fire returns.
            sessionScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
        )
        val ref = makeArrayRef("/folder")
        val sameArrayOtherShape = ref.copy(elementSchemaId = "tenshu/file")
        var evaluations = 0
        db.onGetArrayData = { read ->
            if (read == ref) {
                evaluations++
                // Only the first evaluation reads the array through the second shape as well.
                if (evaluations == 1) executionContext.getArrayData(sameArrayOtherShape)
            }
        }

        registry.setDatabaseListener(executionContext, streamId = 40L, ref, emptyList())
        assertEquals(1, evaluations, "the initial evaluation")

        db.fireAdded()
        assertEquals(2, evaluations, "the first change re-evaluates, and drops the second shape")

        db.fireAdded()
        assertEquals(3, evaluations, "the stream still holds `ref`, so the next change must reach it")
    }

    /**
     * ⚠⚠ An update already evaluating when its stream ends must send nothing. The evaluation runs
     * on `sessionScope` and `EndStream` does not stop it. The client may already have registered
     * the next subscription of the same id, and an element produced after that registration
     * passes its receipt filter: a row the dead update read, deleted since, reached the new read
     * and stayed (`docs/tech-debt.md`, "A re-subscription is handed the ended subscription's
     * still-queued elements").
     *
     * The forcing is synchronous: the stream ends from inside the update's own array read, the
     * last point before its diff goes out.
     */
    @Test
    fun anUpdateStillRunningWhenItsStreamEndsSendsNothing() = runTest {
        val harness = createRegistryHarness(
            sessionScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
        )
        val (registry, executionContext, db) = harness
        val ref = makeArrayRef("/folder")
        registry.setDatabaseListener(executionContext, streamId = 50L, ref, emptyList())
        assertEquals(1, harness.connection.sent.size, "the initial snapshot")

        db.rows = listOf("x".toTString())
        db.onGetArrayData = { read ->
            if (read == ref) registry.removeDatabaseListener(executionContext, streamId = 50L)
        }
        db.fireAdded()

        assertEquals(
            1,
            harness.connection.sent.size,
            "an update whose stream ended while it evaluated sent ${harness.connection.sent.drop(1)}",
        )
    }

    // --- Fixture ----------------------------------------------------------------

    private data class Harness(
        val registry: StreamRegistry,
        val executionContext: ExecutionContext,
        val database: RecordingDatabase,
        val connection: RecordingConnection = RecordingConnection(),
    )

    private class RecordingConnection : ClientConnection {
        val forgotten = mutableListOf<Long>()
        val sent = mutableListOf<Data>()
        override fun init() {}
        override fun send(data: Data) {
            sent += data
        }
        override fun destroy() {}
        override fun forgetStream(streamId: Long) {
            forgotten += streamId
        }
    }

    private fun createRegistryHarness(
        sessionScope: CoroutineScope = CoroutineScope(SupervisorJob()),
    ): Harness {
        val schemaRegistry = createCoreTestRegistry()
        val tContext = createTestContext(schemaRegistry)
        val database = RecordingDatabase()
        val registry = StreamRegistry()
        val connection = RecordingConnection()
        val executionContext = ExecutionContext(
            context = tContext,
            database = database,
            traitResolver = tContext.traitResolver(),
            implementationFactory = { null },
            clientConnection = connection,
            pureOperationRunner = tContext.components[PureOperationRunner.ID] as PureOperationRunner,
            materializer = CallstackMaterializer(schemaRegistry, tContext),
            sessionScope = sessionScope,
            streamRegistry = registry,
        )
        return Harness(registry, executionContext, database, connection)
    }

    private class RecordingDatabase : Database {
        val setArrayListenerCalls = mutableListOf<ArrayDatabaseReference>()
        val removeArrayListenerCalls = mutableListOf<ArrayDatabaseReference>()
        private val handleToRef = mutableMapOf<Long, ArrayDatabaseReference>()
        private val handleToOnAdded = mutableMapOf<Long, (Data, WriteOrigin) -> Unit>()
        private var nextHandle = 0L

        /** Called for every array read, so a test can count evaluations or read further. */
        var onGetArrayData: (ArrayDatabaseReference) -> Unit = {}

        /** What every array read returns. */
        var rows: List<Data> = emptyList()

        /** What `addToArray` does to every registered listener once the write has landed. */
        fun fireAdded() = handleToOnAdded.values.toList().forEach { it("added".toTString(), WriteOrigin.USER) }

        override fun getData(dataId: DataId, asEntity: Boolean, projection: Projection?): Data? = null
        override fun saveData(data: Data) {}
        override fun deleteData(dataId: DataId) {}
        override fun getArrayData(reference: ArrayDatabaseReference, projection: Projection?): Sequence<Data>? {
            onGetArrayData(reference)
            return rows.asSequence()
        }
        override fun getComposite(reference: CompositeDatabaseReference): Data? = null
        override fun addToArray(reference: ArrayDatabaseReference, value: Data, origin: WriteOrigin) {}
        override fun removeFromArray(reference: ArrayDatabaseReference, dataId: DataId, origin: WriteOrigin) {}
        override fun setArrayListener(
            reference: ArrayDatabaseReference,
            onAdded: (Data, WriteOrigin) -> Unit,
            onRemoved: (DataId, WriteOrigin) -> Unit,
        ): Long {
            setArrayListenerCalls.add(reference)
            val handle = nextHandle++
            handleToRef[handle] = reference
            handleToOnAdded[handle] = onAdded
            return handle
        }
        override fun removeArrayListener(handle: Long) {
            handleToOnAdded.remove(handle)
            handleToRef.remove(handle)?.let { removeArrayListenerCalls.add(it) }
        }
        override fun <R> transaction(block: () -> R): R = block()
    }

    private fun makeArrayRef(path: String): ArrayDatabaseReference = ArrayDatabaseReference(
        arrayId = DataId.EntityFieldDataId.create(
            type = TList.ID,
            parentEntity = DataId.StringDataId.create(type = "tenshu/file", keyValue = path),
            fieldRef = "tenshu/file/files",
        ),
        isEntity = false,
    )
}
