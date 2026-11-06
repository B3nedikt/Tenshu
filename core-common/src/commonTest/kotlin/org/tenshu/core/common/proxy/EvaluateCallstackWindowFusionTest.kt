package org.tenshu.core.common.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TString
import org.tenshu.core.collection.list.Drop
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.Take
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.id
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.operation as operationSchema
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Window fusion at the array-materialisation seam ([evaluateCallstack] +
 * [foldWindowPrefix]): a leading `drop`/`take` prefix on a raw
 * [ArrayDatabaseReference] is consumed into a single windowed read
 * (`getArrayDataWindow`) instead of a full materialisation followed by
 * in-memory windowing. The in-memory `ListDrop`/`ListTake` implementations
 * stay as the fallback for non-fusible chains — the tests pin both when the
 * fusion fires (fallback impls NOT executed) and when it must not.
 */
class EvaluateCallstackWindowFusionTest {

    private lateinit var testProxy: TestProxy
    private var dropExecutions = 0
    private var takeExecutions = 0

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        dropExecutions = 0
        takeExecutions = 0

        // Counting pass-throughs to the real in-memory implementations — the
        // fusion path must never dispatch these; the fallback path must.
        testProxy.additionalImplementations[Drop.ID] = countingDelegate(
            delegate = ListDropImplementation(),
            onExecute = { dropExecutions++ },
        )
        testProxy.additionalImplementations[Take.ID] = countingDelegate(
            delegate = ListTakeImplementation(),
            onExecute = { takeExecutions++ },
        )
    }

    // ─── foldWindowPrefix (pure) ───

    @Test
    fun fold_dropThenTake() {
        val fold = foldWindowPrefix(dropOp(40), listOf(takeOp(20)))
        assertEquals(WindowFold(offset = 40, limit = 20, consumedFromRemaining = 1), fold)
    }

    @Test
    fun fold_takeThenDrop() {
        // take(5).drop(2) = elements 2..4 → offset 2, limit 3.
        val fold = foldWindowPrefix(takeOp(5), listOf(dropOp(2)))
        assertEquals(WindowFold(offset = 2, limit = 3, consumedFromRemaining = 1), fold)
    }

    @Test
    fun fold_dropTakeDrop() {
        // drop(2).take(5).drop(1) = elements 3..6 → offset 3, limit 4.
        val fold = foldWindowPrefix(dropOp(2), listOf(takeOp(5), dropOp(1)))
        assertEquals(WindowFold(offset = 3, limit = 4, consumedFromRemaining = 2), fold)
    }

    @Test
    fun fold_singleDrop_hasOpenLimit() {
        val fold = foldWindowPrefix(dropOp(7), emptyList())
        assertEquals(WindowFold(offset = 7, limit = Int.MAX_VALUE, consumedFromRemaining = 0), fold)
    }

    @Test
    fun fold_negativeCount_abortsEntirely() {
        assertNull(foldWindowPrefix(dropOp(-1), listOf(takeOp(5))))
    }

    @Test
    fun fold_missingParam_abortsEntirely() {
        val opWithoutN = OperationDto(params = emptyMap(), schema = Drop.Schema)
        assertNull(foldWindowPrefix(opWithoutN, emptyList()))
    }

    @Test
    fun fold_stopsAtFirstNonWindowOp() {
        val fold = foldWindowPrefix(
            dropOp(3),
            listOf(OperationDto(params = emptyMap(), schema = Drop.Schema), takeOp(5)),
        )
        // The malformed second op ends the fold; the trailing take is NOT
        // consumed (it will run in-memory on the windowed result).
        assertEquals(WindowFold(offset = 3, limit = Int.MAX_VALUE, consumedFromRemaining = 0), fold)
    }

    // ─── fusion through evaluateCallstack ───

    @Test
    fun dropTake_onRawArray_fusesIntoWindowedRead() = runTest {
        val ref = populateArray(names = (0 until 10).map { "item$it" })

        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = ref,
            callstack = listOf(dropOp(3), takeOp(4)),
            allowDeferring = false,
        )

        assertEquals(listOf("item3", "item4", "item5", "item6"), names(result))
        assertEquals(0, dropExecutions, "fusion must consume drop — no in-memory dispatch")
        assertEquals(0, takeExecutions, "fusion must consume take — no in-memory dispatch")
    }

    @Test
    fun windowPastEnd_returnsEmpty() = runTest {
        val ref = populateArray(names = listOf("a", "b", "c"))

        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = ref,
            callstack = listOf(dropOp(5), takeOp(4)),
            allowDeferring = false,
        )

        assertEquals(emptyList(), names(result))
    }

    @Test
    fun partialLastWindow_clamps() = runTest {
        val ref = populateArray(names = listOf("a", "b", "c", "d", "e"))

        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = ref,
            callstack = listOf(dropOp(3), takeOp(4)),
            allowDeferring = false,
        )

        assertEquals(listOf("d", "e"), names(result))
    }

    @Test
    fun deferringWalk_doesNotFuse_returnsDeferredReference() = runTest {
        val ref = populateArray(names = listOf("a", "b", "c"))

        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = ref,
            callstack = listOf(dropOp(1), takeOp(1)),
            allowDeferring = true,
        )

        // A deferring walk must keep returning the lazy reference shape —
        // callers may still append ops (including `stream`).
        assertTrue(result is DatabaseReference, "expected a deferred reference, got $result")
        val stack = (result as DatabaseReference).getOperationContext().executionStack
        assertEquals(listOf(Drop.ID, Take.ID), stack.map { (it as Operation).id })
        assertEquals(0, dropExecutions)
        assertEquals(0, takeExecutions)
    }

    @Test
    fun pendingNonWindowOp_disablesFusion_fallbackRunsInMemory() = runTest {
        val ref = populateArray(names = (0 until 6).map { "item$it" })

        // A non-window op ahead of the window means the window does NOT apply
        // to the raw array — fusion must stay off and the in-memory
        // implementations must run.
        val identityOpSchema = operationSchema(id = "test/identity")
        testProxy.additionalImplementations["test/identity"] = object : SyncOperationImplementation {
            override fun <T : Data?> execute(
                executionContext: ExecutionContext,
                operationContext: OperationContext?,
                implementationFactory: OperationImplementationFactory,
                caller: Any?,
                params: Map<String, Data>?,
            ): T {
                @Suppress("UNCHECKED_CAST")
                return caller as T
            }
        }
        testProxy.schemaRegistry.registerConfig(tModule {
            operation(identityOpSchema)
            operation(interfaceClass = Operation::class, protocolSchema = identityOpSchema)
        })

        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = ref,
            callstack = listOf(
                OperationDto(params = emptyMap(), schema = identityOpSchema),
                dropOp(2),
                takeOp(2),
            ),
            allowDeferring = false,
        )

        assertEquals(listOf("item2", "item3"), names(result))
        assertEquals(1, dropExecutions, "fallback path must dispatch drop in-memory")
        assertEquals(1, takeExecutions, "fallback path must dispatch take in-memory")
    }

    @Test
    fun fusedRead_recordsRefForStreamCollectors() = runTest {
        val ref = populateArray(names = listOf("a", "b", "c"))
        val collected = mutableSetOf<ArrayDatabaseReference>()

        testProxy.executionContext.referenceResolver.collectingReferences(collected) {
            evaluateCallstack(
                executionContext = testProxy.executionContext,
                contextData = ref,
                callstack = listOf(dropOp(1), takeOp(1)),
                allowDeferring = false,
            )
        }

        assertTrue(
            collected.any { it.arrayId == ref.arrayId },
            "windowed read must record the array ref for stream invalidation",
        )
    }

    // ─── count short-circuit ───

    @Test
    fun count_onRawArray_readsMemberHashes_withoutMaterialising() = runTest {
        var countExecutions = 0
        testProxy.additionalImplementations[org.tenshu.core.collection.list.Count.ID] =
            countingDelegate(
                delegate = ListCountImplementation(),
                onExecute = { countExecutions++ },
            )
        val ref = populateArray(names = (0 until 7).map { "item$it" })

        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = ref,
            callstack = listOf(
                OperationDto(
                    params = emptyMap(),
                    schema = org.tenshu.core.collection.list.Count.Schema,
                )
            ),
            allowDeferring = false,
        )

        assertEquals(7L, (result as org.tenshu.core.number.TInteger).get())
        assertEquals(0, countExecutions, "count must be served from member hashes, not materialisation")
    }

    @Test
    fun count_onMaterialisedList_fallsBackToInMemory() = runTest {
        val items = listOf("a", "b", "c").map {
            SyntheticTestData.Factory.create(testProxy.tContext, it, 0L) as Data
        }
        val list = items.toTList(testProxy.tContext)

        val result = testProxy.executionContext.execute<Data>(
            operationId = org.tenshu.core.collection.list.Count.ID,
            caller = list,
            params = emptyMap(),
        )

        assertEquals(3L, (result as org.tenshu.core.number.TInteger).get())
    }

    // ─── helpers ───

    private fun dropOp(n: Int) = OperationDto(
        params = mapOf(Drop.Params.N to n.toTInteger()),
        schema = Drop.Schema,
    )

    private fun takeOp(n: Int) = OperationDto(
        params = mapOf(Take.Params.N to n.toTInteger()),
        schema = Take.Schema,
    )

    private fun countingDelegate(
        delegate: SyncOperationImplementation,
        onExecute: () -> Unit,
    ) = object : SyncOperationImplementation {
        override fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?,
        ): T {
            onExecute()
            return delegate.execute(
                executionContext, operationContext, implementationFactory, caller, params,
            )
        }
    }

    private fun populateArray(names: List<String>): ArrayDatabaseReference {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = testProxy.tContext,
                type = TList.ID,
                relationId = "test/window-array",
            ),
            isEntity = false,
            elementSchemaId = SyntheticTestData.ID,
        )
        names.forEachIndexed { index, name ->
            testProxy.database.addToArray(
                ref,
                SyntheticTestData.Factory.create(testProxy.tContext, name, index.toLong()),
            )
        }
        return ref
    }

    private fun names(result: Data?): List<String> {
        assertNotNull(result)
        val list = result as? TList<*> ?: error("expected TList, got $result")
        return list.toList().map { item ->
            when (item) {
                is SyntheticTestData -> item.name.get()
                is Entity<*> -> item.getFieldValue<TString>(SyntheticTestData.Companion.Field.NAME).get()
                else -> error("unexpected element: $item")
            }
        }
    }
}
