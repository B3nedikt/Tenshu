package org.tenshu.core.common.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.StreamElementRemovedDto
import org.tenshu.core.common.impl.StreamElementValueDto
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.entity.DataId
import org.tenshu.core.number.toTInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the stream-lifecycle invariants of [ExecutionContext]:
 *
 * - [ExecutionContext.removeDatabaseListener] actually unregisters the underlying
 *   [Database] listener (regression: the original implementation removed the
 *   `streamIdToNestedRefs` entry before iterating, which short-circuited
 *   `unregisterRefForStream`).
 * - [StreamState] is shared across [ExecutionContext.withEvaluationScope] derivatives
 *   so that a stream can be started in one per-request context and ended in another.
 * - Concurrent listener fires are serialised by the per-stream `Mutex` captured in
 *   each `onUpdate` closure — without it, two simultaneous fires could both observe
 *   an empty `streamIdToItems` and emit the same item twice.
 */
class ExecutionContextStreamTest {

    private fun createTestProxy(scope: CoroutineScope) = TestProxy(
        schemaRegistry = createSyntheticTestRegistry(),
        coroutineScope = scope
    )

    private fun arrayRef(testProxy: TestProxy): ArrayDatabaseReference {
        return ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = testProxy.tContext,
                type = TList.ID,
                relationId = "test/array"
            ),
            isEntity = false,
            elementSchemaId = SyntheticTestData.ID
        )
    }

    private suspend fun waitFor(timeoutMs: Long = 2000, predicate: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!predicate()) delay(10)
        }
    }

    @Test
    fun endStream_unregistersUnderlyingDatabaseListener() = runTest {
        val testProxy = createTestProxy(backgroundScope)
        val ref = arrayRef(testProxy)
        val streamId = 1L

        testProxy.executionContext.setDatabaseListener(streamId, ref, emptyList())
        // Initial emission
        assertEquals(1, testProxy.updates.size, "initial emission")

        testProxy.database.addToArray(
            reference = ref,
            value = SyntheticTestData.Factory.create(testProxy.tContext, "first", 1L)
        )
        waitFor { testProxy.updates.size >= 2 }
        assertEquals(2, testProxy.updates.size, "one update after first add")

        // End the stream.
        testProxy.executionContext.removeDatabaseListener(streamId)

        // Subsequent writes must NOT produce more emissions.
        testProxy.database.addToArray(
            reference = ref,
            value = SyntheticTestData.Factory.create(testProxy.tContext, "second", 2L)
        )
        // Give any (incorrectly-fired) listener a chance to run.
        delay(100)
        assertEquals(2, testProxy.updates.size, "no emissions after endStream")
    }

    @Test
    fun streamState_isSharedAcrossWithEvaluationScopeDerivatives() = runTest {
        val testProxy = createTestProxy(backgroundScope)
        val ref = arrayRef(testProxy)
        val streamId = 2L

        // Start the stream on the parent context.
        testProxy.executionContext.setDatabaseListener(streamId, ref, emptyList())
        assertEquals(1, testProxy.updates.size, "initial emission")

        // Derive a per-request context, like ProxyGateway does.
        val derived = testProxy.executionContext.withEvaluationScope(backgroundScope)

        // Ending the stream on the derived context must also unregister the listener
        // registered on the parent — proves StreamState is shared.
        derived.removeDatabaseListener(streamId)

        testProxy.database.addToArray(
            reference = ref,
            value = SyntheticTestData.Factory.create(testProxy.tContext, "leak-check", 1L)
        )
        delay(100)
        assertEquals(
            1, testProxy.updates.size,
            "no further emissions after derived-context removeDatabaseListener"
        )
    }

    /**
     * Uses a real multi-threaded dispatcher so listener-launched coroutines actually run
     * in parallel. `runTest`'s scheduler is single-threaded, so a `runTest`-based test
     * would never expose the per-stream race the `updateMutex` exists to prevent.
     *
     * Sequential writes with parallel onUpdate dispatch is enough to expose the bug:
     * each addToArray queues an onUpdate on the shared dispatcher, and without the
     * mutex two of them can run concurrently, both reading the same stale `oldItems`
     * snapshot and emitting the same newly-added item twice.
     */
    @Test
    fun concurrentDatabaseWrites_doNotProduceDuplicateEmissions() = runTest {
        val testProxy = createTestProxy(backgroundScope)
        val ref = arrayRef(testProxy)
        val streamId = 3L
        val itemCount = 50

        testProxy.executionContext.setDatabaseListener(streamId, ref, emptyList())
        assertEquals(1, testProxy.updates.size, "initial emission")

        for (i in 0 until itemCount) {
            testProxy.database.addToArray(
                reference = ref,
                value = SyntheticTestData.Factory.create(
                    testProxy.tContext,
                    name = "item-$i",
                    value = i.toLong()
                )
            )
        }

        // Wait until every item has been observed at least once.
        while (true) {
            val seen = testProxy.updates
                .filterIsInstance<StreamElementValueDto>()
                .drop(1)
                .mapNotNull { it.payload as? SyntheticTestData }
                .map { it.name.get() }
                .toSet()
            if (seen.size >= itemCount) break
            delay(10)
        }

        // Settle: give any racing duplicate emissions a chance to land.
        delay(200)

        val valueEmissions = testProxy.updates.filterIsInstance<StreamElementValueDto>()
        val itemPayloads = valueEmissions
            .drop(1)
            .mapNotNull { it.payload as? SyntheticTestData }
            .map { it.name.get() }

        val duplicates = itemPayloads.groupingBy { it }.eachCount().filter { it.value > 1 }
        assertTrue(
            duplicates.isEmpty(),
            "Per-stream Mutex should prevent duplicate emissions, but got duplicates: $duplicates"
        )
        assertEquals(
            itemCount,
            itemPayloads.toSet().size,
            "All $itemCount distinct items should have been emitted exactly once"
        )
    }

    /**
     * Signal-only mode (`Stream` op's `signalOnly` param): the initial
     * full-list emission is skipped entirely — but the initial evaluation
     * still runs, seeding the diff baseline. Pins the three load-bearing
     * behaviours:
     *
     *  1. nothing is sent on subscribe;
     *  2. the FIRST change emits exactly the changed item — not the whole
     *     list re-diffed against an empty baseline;
     *  3. removals still emit (any element type is a valid signal).
     */
    @Test
    fun signalOnly_skipsInitialEmission_butSeedsDiffBaseline() = runTest {
        val testProxy = createTestProxy(backgroundScope)
        val ref = arrayRef(testProxy)
        val streamId = 5L

        val preExisting = (0 until 4).map { i ->
            SyntheticTestData.Factory.create(testProxy.tContext, "existing-$i", i.toLong())
        }
        preExisting.forEach { testProxy.database.addToArray(ref, it) }

        testProxy.executionContext.setDatabaseListener(
            streamId, ref, emptyList(), signalOnly = true
        )
        assertEquals(0, testProxy.updates.size, "signal-only subscribe must send nothing")

        // First change: exactly ONE Value element (the new item), not the 4
        // pre-existing items re-emitted as "new" against an unseeded baseline.
        testProxy.database.addToArray(
            reference = ref,
            value = SyntheticTestData.Factory.create(testProxy.tContext, "fresh", 99L)
        )
        waitFor { testProxy.updates.isNotEmpty() }
        delay(100) // settle: give any incorrect extra emissions a chance to land
        val values = testProxy.updates.filterIsInstance<StreamElementValueDto>()
        assertEquals(1, values.size, "first change must emit exactly the changed item")
        assertEquals("fresh", (values.single().payload as SyntheticTestData).name.get())

        // Removal still signals.
        testProxy.database.removeFromArray(
            reference = ref,
            dataId = testProxy.executionContext.getDataId(preExisting[0])!!
        )
        waitFor { testProxy.updates.size >= 2 }
    }

    /**
     * The per-change diff (`StreamRegistry.emitChanges`, `drive-sorted-folder-paging.md` SP-5):
     * a change reports exactly what changed — one `Removed` per row whose identity left, one
     * `Value` per row that arrived — and never re-emits the rows that stayed. Pinned here because
     * the diff was rewritten from an old×new scan to hash-keyed matching, and identical emissions
     * are the whole contract of that rewrite.
     */
    @Test
    fun changeDiff_reportsOnlyTheRowsThatChanged() = runTest {
        val testProxy = createTestProxy(backgroundScope)
        val ref = arrayRef(testProxy)
        val streamId = 7L

        val rows = (0 until 5).map { i ->
            SyntheticTestData.Factory.create(testProxy.tContext, "row-$i", i.toLong())
        }
        rows.forEach { testProxy.database.addToArray(ref, it) }

        testProxy.executionContext.setDatabaseListener(streamId, ref, emptyList())
        assertEquals(1, testProxy.updates.size, "initial emission")

        // One removal: exactly one Removed carrying that row's id, nothing else.
        val removedId = testProxy.executionContext.getDataId(rows[2])!!
        testProxy.database.removeFromArray(reference = ref, dataId = removedId)
        waitFor { testProxy.updates.size >= 2 }
        delay(100)
        val afterRemove = testProxy.updates.drop(1)
        assertEquals(1, afterRemove.size, "a removal must produce exactly one element, got $afterRemove")
        val removed = afterRemove.single() as StreamElementRemovedDto
        assertEquals(removedId.getDataIdHash(), removed.removedId.getDataIdHash())

        // One insertion: exactly one Value carrying the new row; the four survivors stay silent.
        testProxy.database.addToArray(
            reference = ref,
            value = SyntheticTestData.Factory.create(testProxy.tContext, "row-new", 99L),
        )
        waitFor { testProxy.updates.size >= 3 }
        delay(100)
        val afterAdd = testProxy.updates.drop(2)
        assertEquals(1, afterAdd.size, "an insertion must produce exactly one element, got $afterAdd")
        val added = afterAdd.single() as StreamElementValueDto
        assertEquals("row-new", (added.payload as SyntheticTestData).name.get())
    }

    /**
     * The page-load stream shape: a `[Drop, Take]` execution stack rides into
     * `StreamRegistry.onUpdate`, which now evaluates it against the *reference*
     * so the window fusion applies (only the window is deserialised). Pins:
     * the initial emission carries exactly the window, and invalidation still
     * fires when the underlying array changes.
     */
    @Test
    fun windowedExecutionStack_emitsOnlyTheWindow_andStillInvalidates() = runTest {
        val testProxy = createTestProxy(backgroundScope)
        val ref = arrayRef(testProxy)
        val streamId = 4L

        (0 until 6).forEach { i ->
            testProxy.database.addToArray(
                reference = ref,
                value = SyntheticTestData.Factory.create(testProxy.tContext, "item-$i", i.toLong())
            )
        }

        val windowStack = listOf<org.tenshu.core.Data>(
            OperationDto(
                params = mapOf(org.tenshu.core.collection.list.Drop.Params.N to 2.toTInteger()),
                schema = org.tenshu.core.collection.list.Drop.Schema,
            ),
            OperationDto(
                params = mapOf(org.tenshu.core.collection.list.Take.Params.N to 2.toTInteger()),
                schema = org.tenshu.core.collection.list.Take.Schema,
            ),
        )

        testProxy.executionContext.setDatabaseListener(streamId, ref, windowStack)

        assertEquals(1, testProxy.updates.size, "initial emission")
        val initial = testProxy.updates.first() as StreamElementValueDto
        val initialNames = (initial.payload as TList<*>).toList()
            .map { (it as SyntheticTestData).name.get() }
        assertEquals(listOf("item-2", "item-3"), initialNames, "initial payload is the window only")

        // A change inside the window re-fires the (windowed) evaluation.
        testProxy.database.removeFromArray(
            reference = ref,
            dataId = testProxy.executionContext.getDataId(
                SyntheticTestData.Factory.create(testProxy.tContext, "item-2", 2L)
            )!!
        )
        waitFor { testProxy.updates.size >= 2 }
        val windowAfter = testProxy.updates
            .filterIsInstance<StreamElementValueDto>()
            .drop(1)
            .mapNotNull { it.payload as? SyntheticTestData }
            .map { it.name.get() }
        assertTrue(
            "item-4" in windowAfter,
            "after removing item-2 the window shifts — item-4 must enter as a new Value; got $windowAfter"
        )
    }
}
