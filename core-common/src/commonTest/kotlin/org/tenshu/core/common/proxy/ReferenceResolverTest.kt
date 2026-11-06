package org.tenshu.core.common.proxy

import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.toTString
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.trait.traitResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the contract of `ReferenceResolver` — the "what shapes can a list-valued field
 * carry and how do I read each" question that used to live as three near-duplicate
 * `when`-blocks in `ExecutionContext` and `DbEntryMapper`.
 *
 * Each shape branch (ArrayDatabaseReference, DatabaseReference + ArrayOperationContext,
 * DatabaseReference + non-array context, TList, Sequence, anything else) is exercised
 * directly so a future refactor can't silently drop a branch.
 */
class ReferenceResolverTest {

    @Test
    fun resolveList_returnsNullForNull() {
        val resolver = createResolver()
        assertNull(resolver.resolveList(null))
    }

    @Test
    fun resolveList_returnsNullForUnsupportedType() {
        val resolver = createResolver()
        assertNull(resolver.resolveList("not a Data shape"))
        assertNull(resolver.resolveList(42))
    }

    @Test
    fun resolveList_arrayDatabaseReference_resolvesToDatabaseContents() {
        val arrayRef = makeArrayRef("/folder")
        val payload: List<Data> = listOf(makeFakeData("a"), makeFakeData("b"))
        val resolver = createResolver(
            getArrayData = { ref ->
                if (ref == arrayRef) payload.asSequence() else null
            }
        )

        val resolved = resolver.resolveList(arrayRef)!!.toList()

        assertEquals(payload, resolved)
    }

    @Test
    fun resolveList_databaseReferenceWithArrayContext_resolvesViaInnerArrayRef() {
        // DatabaseReference is the supertype; when its operation-context is an
        // ArrayOperationContext, resolveList should read the *contained* array.
        val arrayRef = makeArrayRef("/folder")
        val payload: List<Data> = listOf(makeFakeData("x"))
        val resolver = createResolver(
            getArrayData = { ref ->
                if (ref == arrayRef) payload.asSequence() else null
            }
        )

        // ArrayDatabaseReference IS a DatabaseReference, and its
        // getOperationContext() returns an ArrayOperationContext — exactly the
        // case `resolveList`'s `is DatabaseReference -> ...` arm covers.
        val wrapped: DatabaseReference = arrayRef

        val resolved = resolver.resolveList(wrapped)!!.toList()

        assertEquals(payload, resolved)
    }

    @Test
    fun resolveList_databaseReferenceWithNonArrayContext_returnsNull() {
        val resolver = createResolver()
        val nonArrayRef = object : DatabaseReference {
            override fun getOperationContext(): OperationContext = object : OperationContext {
                override var executionStack: List<Data> = emptyList()
            }
        }
        assertNull(resolver.resolveList(nonArrayRef))
    }

    @Test
    fun resolveList_tList_passesThroughAsSequence() {
        val ctx = createTestContext(createCoreTestRegistry())
        val items: List<Data> = listOf(makeFakeData("p"), makeFakeData("q"))
        val tList: TList<Data> = items.toTList(ctx)
        val resolver = createResolver()

        val resolved = resolver.resolveList(tList)!!.toList()
        assertEquals(items, resolved)
    }

    @Test
    fun resolveList_rawSequence_passesThrough() {
        val items: List<Data> = listOf(makeFakeData("s"))
        val resolver = createResolver()

        val resolved = resolver.resolveList(items.asSequence())!!.toList()
        assertEquals(items, resolved)
    }

    @Test
    fun getArrayData_recordsReferenceWhileCollectorIsActive() {
        val arrayRef = makeArrayRef("/folder")
        val payload: List<Data> = listOf(makeFakeData("a"))
        val resolver = createResolver(
            getArrayData = { ref ->
                if (ref == arrayRef) payload.asSequence() else null
            }
        )

        val collected = mutableSetOf<ArrayDatabaseReference>()
        val resolved = resolver.collectingReferences(into = collected) {
            resolver.getArrayData(arrayRef)?.toList()
        }

        assertEquals(payload, resolved)
        assertEquals(setOf(arrayRef), collected)
    }

    @Test
    fun getArrayDataWindow_recordsReferenceWhileCollectorIsActive() {
        // The windowed read changes *what is deserialised*, not *what is
        // observed* — the ref-recording side effect must match getArrayData's
        // exactly, or windowed page-load streams lose invalidation.
        val arrayRef = makeArrayRef("/folder")
        val payload: List<Data> = listOf(makeFakeData("a"), makeFakeData("b"), makeFakeData("c"))
        val resolver = createResolver(
            getArrayData = { ref ->
                if (ref == arrayRef) payload.asSequence() else null
            }
        )

        val collected = mutableSetOf<ArrayDatabaseReference>()
        val resolved = resolver.collectingReferences(into = collected) {
            resolver.getArrayDataWindow(arrayRef, offset = 1, limit = 1)?.toList()
        }

        // The stub Database has no override, so the interface default
        // (full read + drop/take) serves the window.
        assertEquals(listOf(payload[1]), resolved)
        assertEquals(setOf(arrayRef), collected)
    }

    @Test
    fun getArrayData_doesNotRecordWhenNoCollector() {
        val arrayRef = makeArrayRef("/folder")
        val resolver = createResolver(getArrayData = { _ -> emptySequence() })

        // No collectingReferences scope around this call — no observer to record into.
        // Behaviour is verified by the absence of a crash and the value being returned.
        val resolved = resolver.getArrayData(arrayRef)?.toList()
        assertEquals(emptyList<Data>(), resolved)
    }

    @Test
    fun collectingReferences_restoresPreviousCollectorOnExit() {
        // Nested scopes must compose: the inner scope's collector receives the inner
        // refs, the outer scope's collector resumes seeing refs after the inner exits.
        val outerRef = makeArrayRef("/outer")
        val innerRef = makeArrayRef("/inner")
        val tailRef = makeArrayRef("/tail")
        val resolver = createResolver(getArrayData = { _ -> emptySequence() })

        val outer = mutableSetOf<ArrayDatabaseReference>()
        val inner = mutableSetOf<ArrayDatabaseReference>()

        resolver.collectingReferences(into = outer) {
            resolver.getArrayData(outerRef)
            resolver.collectingReferences(into = inner) {
                resolver.getArrayData(innerRef)
            }
            // The inner scope must have been popped — this ref lands in `outer`, not `inner`.
            resolver.getArrayData(tailRef)
        }

        assertEquals(setOf(outerRef, tailRef), outer)
        assertEquals(setOf(innerRef), inner)
    }

    @Test
    fun collectingReferences_restoresCollectorAfterException() {
        // Mirrors the production failure mode: `setDatabaseListener.onUpdate` runs
        // `evaluateCallstack` inside a `collectingReferences` block, and a throw from
        // within must not strand the collector — otherwise a *later, unrelated* stream
        // would silently inherit references into a long-dead set.
        val resolver = createResolver(getArrayData = { _ -> emptySequence() })
        val first = mutableSetOf<ArrayDatabaseReference>()
        val second = mutableSetOf<ArrayDatabaseReference>()
        val laterRef = makeArrayRef("/later")

        val caught = try {
            resolver.collectingReferences(into = first) {
                error("simulated callstack failure")
            }
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertTrue(caught != null, "exception should propagate")

        // The collector must have been restored to its prior (null) state — proven by
        // the next collectingReferences scope working correctly with a fresh `into`.
        resolver.collectingReferences(into = second) {
            resolver.getArrayData(laterRef)
        }
        assertEquals(setOf(laterRef), second)
        assertEquals(emptySet<ArrayDatabaseReference>(), first)
    }

    @Test
    fun arrayReferenceOrNull_extractsFromArrayDatabaseReference() {
        val ref = makeArrayRef("/folder")
        assertSame(ref, arrayReferenceOrNull(ref))
    }

    @Test
    fun arrayReferenceOrNull_extractsFromDatabaseReferenceWithArrayContext() {
        // The wrapped shape is what `evaluateCallstack` can produce when a chain
        // step yields a `DatabaseReference` rather than directly an
        // `ArrayDatabaseReference` (rare in practice but the resolver handles it,
        // and Phase 2's consolidation ensures the serializer wouldn't crash on
        // the same shape — it'd record the inner array's hash instead).
        val ref = makeArrayRef("/folder")
        val wrapped: DatabaseReference = ref
        assertSame(ref, arrayReferenceOrNull(wrapped))
    }

    @Test
    fun arrayReferenceOrNull_returnsNullForNonArrayDatabaseReference() {
        val nonArrayRef = object : DatabaseReference {
            override fun getOperationContext(): OperationContext = object : OperationContext {
                override var executionStack: List<Data> = emptyList()
            }
        }
        assertNull(arrayReferenceOrNull(nonArrayRef))
    }

    @Test
    fun arrayReferenceOrNull_returnsNullForUnrelatedTypes() {
        assertNull(arrayReferenceOrNull(null))
        assertNull(arrayReferenceOrNull("not an array"))
        assertNull(arrayReferenceOrNull(42))
        assertNull(arrayReferenceOrNull(makeFakeData("p")))
    }

    @Test
    fun executionContext_delegatesResolveProperty_toReferenceResolver_passingItself() {
        // Pins the Phase 1b wiring: ExecutionContext.resolveProperty must (a) call
        // the injected resolver and (b) pass `this` as the executionContext
        // parameter. If either regresses, the static-path's `getDataId` callback
        // or the dynamic-path's `evaluateCallstack(executionContext = …)` recursion
        // would silently get the wrong context (or none) at runtime.
        val recordedCalls = mutableListOf<RecordedProperty>()
        val resolver = recordingResolver(recordedCalls)
        val context = createExecutionContextWith(resolver)

        val target = makeFakeData("target")
        val propRef = org.tenshu.core.schema.propertyRef("tenshu/file/data")
        val result = context.resolveProperty(target, propRef, params = null)

        assertEquals(1, recordedCalls.size)
        assertSame(context, recordedCalls.single().executionContext)
        assertSame(target, recordedCalls.single().data)
        assertSame(propRef, recordedCalls.single().propertyReference)
        assertEquals(null, result, "fake resolver always returns null")
    }

    @Test
    fun executionContext_delegatesResolveDynamicProperty_toReferenceResolver_passingItself() {
        val recordedCalls = mutableListOf<RecordedDynamicProperty>()
        val resolver = recordingResolver(dynamicSink = recordedCalls)
        val context = createExecutionContextWith(resolver)

        val target = makeFakeData("target")
        val dynRef = org.tenshu.core.schema.dynamicPropertyRef("tenshu/file/preview")
        context.resolveDynamicProperty(target, dynRef, params = null)

        assertEquals(1, recordedCalls.size)
        assertSame(context, recordedCalls.single().executionContext)
        assertSame(target, recordedCalls.single().data)
        assertSame(dynRef, recordedCalls.single().propertyReference)
    }

    @Test
    fun executionContext_delegatesResolveListToReferenceResolver() {
        // Doubles as wiring-check: ExecutionContext.resolveList is now a thin
        // delegate, and ExecutionContext.referenceResolver must be the same
        // instance that does the work. If the wiring regresses (e.g. someone
        // constructs a fresh ReferenceResolverImpl inside ExecutionContext),
        // this test catches it.
        val arrayRef = makeArrayRef("/folder")
        val payload: List<Data> = listOf(makeFakeData("z"))
        val resolver = createResolver(
            getArrayData = { ref ->
                if (ref == arrayRef) payload.asSequence() else null
            }
        )
        val context = createExecutionContextWith(resolver)

        val viaContext = context.resolveList(arrayRef)?.toList()
        val viaResolver = resolver.resolveList(arrayRef)?.toList()

        assertEquals(payload, viaContext)
        assertEquals(payload, viaResolver)
        assertSame(resolver, context.referenceResolver, "ExecutionContext must hold the injected resolver")
    }

    // --- Fixtures ---------------------------------------------------------------

    private fun createResolver(
        getArrayData: (ArrayDatabaseReference) -> Sequence<Data>? = { null },
        getData: (DataId) -> Data? = { null },
    ): ReferenceResolver {
        val schemaRegistry = createCoreTestRegistry()
        val tContext = createTestContext(schemaRegistry)
        return ReferenceResolverImpl(
            context = tContext,
            database = stubDatabase(getArrayData = getArrayData, getData = getData),
            materializer = CallstackMaterializer(schemaRegistry, tContext),
        )
    }

    private fun createExecutionContextWith(resolver: ReferenceResolver): ExecutionContext {
        val schemaRegistry = createCoreTestRegistry()
        val tContext = createTestContext(schemaRegistry)
        val sessionScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        return ExecutionContext(
            context = tContext,
            database = stubDatabase(),
            traitResolver = tContext.traitResolver(),
            implementationFactory = { null },
            clientConnection = object : ClientConnection {
                override fun init() {}
                override fun send(data: Data) {}
                override fun destroy() {}
            },
            pureOperationRunner = tContext.components[org.tenshu.core.PureOperationRunner.ID]
                    as org.tenshu.core.PureOperationRunner,
            materializer = CallstackMaterializer(schemaRegistry, tContext),
            sessionScope = sessionScope,
            referenceResolver = resolver,
        )
    }

    private fun stubDatabase(
        getArrayData: (ArrayDatabaseReference) -> Sequence<Data>? = { null },
        getData: (DataId) -> Data? = { null },
    ): Database = object : Database {
        override fun getData(dataId: DataId, asEntity: Boolean, projection: Projection?): Data? = getData(dataId)
        override fun saveData(data: Data) {}
        override fun deleteData(dataId: DataId) {}
        override fun getArrayData(reference: ArrayDatabaseReference, projection: Projection?): Sequence<Data>? =
            getArrayData(reference)
        override fun getComposite(reference: CompositeDatabaseReference): Data? = null
        override fun addToArray(reference: ArrayDatabaseReference, value: Data, origin: WriteOrigin) {}
        override fun removeFromArray(reference: ArrayDatabaseReference, dataId: DataId, origin: WriteOrigin) {}
        override fun setArrayListener(
            reference: ArrayDatabaseReference,
            onAdded: (Data, WriteOrigin) -> Unit,
            onRemoved: (DataId, WriteOrigin) -> Unit
        ): Long = 0L
        override fun removeArrayListener(handle: Long) {}
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

    private fun makeFakeData(tag: String): Data = tag.toTString()

    // --- Recording resolver fixture (for wiring tests) -------------------------

    private data class RecordedProperty(
        val executionContext: ExecutionContext,
        val data: Data,
        val propertyReference: org.tenshu.core.reference.PropertyTypeReference,
        val params: TList<Data>?,
    )

    private data class RecordedDynamicProperty(
        val executionContext: ExecutionContext,
        val data: Data,
        val propertyReference: org.tenshu.core.reference.DynamicPropertyTypeReference,
        val params: TList<Data>?,
    )

    private fun recordingResolver(
        propertySink: MutableList<RecordedProperty> = mutableListOf(),
        dynamicSink: MutableList<RecordedDynamicProperty> = mutableListOf(),
    ): ReferenceResolver = object : ReferenceResolver {
        override fun resolveList(data: Any?): Sequence<Data>? = null
        override fun getArrayData(reference: ArrayDatabaseReference, projection: Projection?): Sequence<Data>? = null
        override fun getCompositeData(reference: CompositeDatabaseReference): Data? = null
        override fun resolveServiceType(
            currentServiceId: String?,
            referencedServiceId: String,
        ): org.tenshu.core.reference.ServiceTypeReference =
            error("not used in property-wiring tests")
        override fun resolveServiceData(
            service: org.tenshu.core.reference.ServiceTypeReference,
            ref: org.tenshu.core.reference.Reference,
        ): Data = error("not used in property-wiring tests")
        override fun <T> collectingReferences(
            into: MutableSet<ArrayDatabaseReference>,
            block: () -> T,
        ): T = block()
        override fun resolveProperty(
            executionContext: ExecutionContext,
            data: Data,
            propertyReference: org.tenshu.core.reference.PropertyTypeReference,
            params: TList<Data>?,
        ): Data? {
            propertySink.add(RecordedProperty(executionContext, data, propertyReference, params))
            return null
        }
        override fun resolveDynamicProperty(
            executionContext: ExecutionContext,
            data: Data,
            propertyReference: org.tenshu.core.reference.DynamicPropertyTypeReference,
            params: TList<Data>?,
        ): Data? {
            dynamicSink.add(RecordedDynamicProperty(executionContext, data, propertyReference, params))
            return null
        }
    }
}
