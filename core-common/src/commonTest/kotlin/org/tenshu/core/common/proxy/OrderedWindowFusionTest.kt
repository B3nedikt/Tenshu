package org.tenshu.core.common.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TString
import org.tenshu.core.TValue
import org.tenshu.core.collection.list.Drop
import org.tenshu.core.collection.list.Filter
import org.tenshu.core.collection.list.Select
import org.tenshu.core.collection.list.Selector
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.collection.list.SortBy
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.Take
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.SyntheticParent
import org.tenshu.core.common.SyntheticSpecialData
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.TIntegerDto
import org.tenshu.core.common.impl.array.TListDto
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.valueProperty
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.isA
import org.tenshu.core.lowercase
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.relation.StaticPropertyRelation
import org.tenshu.core.relation.valueProperty
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.fieldRef
import org.tenshu.core.schema.toType
import org.tenshu.core.substringAfterLast
import org.tenshu.core.schema.operation as operationSchema
import org.tenshu.core.toTBoolean
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Ordered-window fusion ([tryOrderedWindowFusion] + the [evaluateCallstack]
 * gate) — `docs/proposals/core-ordered-window-fusion.md` §5. Counting
 * delegates on `SortBy`/`Drop`/`Take` pin WHICH path ran: the fused path must
 * never dispatch them; every fall-through must dispatch the in-memory sort.
 * Result assertions pin semantic parity with the unfused ordering
 * ([SortByOperationTest] is the reference for that ordering).
 */
class OrderedWindowFusionTest {

    private val sizeProp = valueProperty<TInteger>("test/synthetic/owfSizeProp", TInteger.Schema)

    /**
     * Every entity / list-entry read is at least one `getObject`, so the count is the read
     * cost of a shape — the same yardstick `FilesListingEfficiencyTest` uses. A row assertion
     * alone passes fused or not; the count is what tells the two paths apart.
     */
    private class CountingObjectStore(
        private val delegate: ObjectStore = InMemoryObjectStore(),
    ) : ObjectStore by delegate {
        var reads = 0
        override fun getObject(dataIdHash: Long): ObjectStore.DbEntry? {
            reads++
            return delegate.getObject(dataIdHash)
        }
    }

    private lateinit var testProxy: TestProxy
    private lateinit var store: CountingObjectStore
    private var sortExecutions = 0
    private var dropExecutions = 0
    private var takeExecutions = 0

    @BeforeTest
    fun setup() {
        val registry = createSyntheticTestRegistry()
        registry.registerConfig(
            tModule {
                valueProperty(
                    fromId = SyntheticTestData.ID,
                    property = sizeProp,
                ) { TIntegerDto(it as Long) }
            }
        )
        store = CountingObjectStore()
        testProxy = TestProxy(schemaRegistry = registry, objectStore = store)
        sortExecutions = 0
        dropExecutions = 0
        takeExecutions = 0

        testProxy.additionalImplementations[SortBy.ID] = countingDelegate(
            delegate = SortByImplementation(),
            onExecute = { sortExecutions++ },
        )
        testProxy.additionalImplementations[Drop.ID] = countingDelegate(
            delegate = ListDropImplementation(),
            onExecute = { dropExecutions++ },
        )
        testProxy.additionalImplementations[Take.ID] = countingDelegate(
            delegate = ListTakeImplementation(),
            onExecute = { takeExecutions++ },
        )
    }

    // ─── fused path ───

    @Test
    fun sortTakeSelect_fuses_withoutDispatchingSortOrWindow() = runTest {
        val ref = populateArray(
            listOf("A" to 3L, "B" to 7L, "C" to 1L, "D" to 5L, "E" to 6L),
        )

        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = ref,
            callstack = listOf(
                sortOp(descending = true, selector = valueSelector()),
                takeOp(3),
                selectOp(SyntheticTestData.Companion.Field.NAME, SyntheticTestData.Companion.Field.VALUE),
            ),
            allowDeferring = false,
        )

        assertEquals(listOf("B", "E", "D"), names(result))
        assertEquals(0, sortExecutions, "fusion must not dispatch the in-memory sort")
        assertEquals(0, takeExecutions, "fusion must consume the take")
    }

    @Test
    fun dropTakeWindow_slicesTheOrderedList() = runTest {
        val ref = populateArray(
            listOf("A" to 3L, "B" to 7L, "C" to 1L, "D" to 5L, "E" to 6L),
        )

        // Ascending: C(1) A(3) D(5) E(6) B(7); drop(1).take(2) → A, D.
        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = ref,
            callstack = listOf(
                sortOp(descending = false, selector = valueSelector()),
                dropOp(1),
                takeOp(2),
                selectOp(SyntheticTestData.Companion.Field.NAME, SyntheticTestData.Companion.Field.VALUE),
            ),
            allowDeferring = false,
        )

        assertEquals(listOf("A", "D"), names(result))
        assertEquals(0, sortExecutions)
        assertEquals(0, dropExecutions)
        assertEquals(0, takeExecutions)
    }

    @Test
    fun equalKeys_keepEncounterOrder_stableUnderTheBoundedSelection() = runTest {
        val ref = populateArray(
            listOf("first" to 1L, "second" to 1L, "third" to 1L, "fourth" to 1L),
        )

        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = ref,
            callstack = listOf(
                sortOp(descending = false, selector = valueSelector()),
                takeOp(3),
                selectOp(SyntheticTestData.Companion.Field.NAME, SyntheticTestData.Companion.Field.VALUE),
            ),
            allowDeferring = false,
        )

        assertEquals(listOf("first", "second", "third"), names(result))
        assertEquals(0, sortExecutions)
    }

    @Test
    fun thenByKey_breaksTies_fused() = runTest {
        val ref = populateArray(
            listOf("A" to 2L, "B" to 1L, "C" to 2L, "D" to 1L),
        )

        // Primary value ascending, thenBy name DESCENDING: (1,D) (1,B) (2,C) (2,A).
        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = ref,
            callstack = listOf(
                sortOp(
                    descending = false,
                    selector = valueSelector(),
                    thenSelectors = listOf(nameSelector() to true),
                ),
                takeOp(3),
                selectOp(SyntheticTestData.Companion.Field.NAME, SyntheticTestData.Companion.Field.VALUE),
            ),
            allowDeferring = false,
        )

        assertEquals(listOf("D", "B", "C"), names(result))
        assertEquals(0, sortExecutions)
    }

    @Test
    fun takeZero_yieldsEmpty_fused() = runTest {
        val ref = populateArray(listOf("A" to 1L, "B" to 2L))

        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = ref,
            callstack = listOf(
                sortOp(descending = false, selector = valueSelector()),
                takeOp(0),
                selectOp(SyntheticTestData.Companion.Field.NAME, SyntheticTestData.Companion.Field.VALUE),
            ),
            allowDeferring = false,
        )

        assertEquals(emptyList(), names(result))
        assertEquals(0, sortExecutions)
    }

    @Test
    fun propertyHandleKey_fuses_readingTheStoredValueProperty() = runTest {
        // Entities carrying a stored scalar value property (the
        // `sortByDescending(File.LastModifiedAt)` shape) — the fused read must
        // keep the property through the projection ([Projection.keepProperties])
        // and order by it without the in-memory sort.
        val ref = entityArray(
            listOf("A" to 30L, "B" to 10L, "C" to 40L, "D" to 20L),
        )

        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = ref,
            callstack = listOf(
                sortOp(
                    descending = true,
                    selector = SimpleTList(listOf(sizeProp.selector() as StaticPropertyRelation)),
                ),
                takeOp(2),
                selectOp(SyntheticTestData.Companion.Field.NAME, SyntheticTestData.Companion.Field.VALUE),
            ),
            allowDeferring = false,
        )

        assertEquals(listOf("C", "A"), names(result))
        assertEquals(0, sortExecutions, "property-key sort must fuse")
    }

    @Test
    fun fusedRead_recordsRefForStreamCollectors() = runTest {
        val ref = populateArray(listOf("A" to 1L, "B" to 2L))
        val collected = mutableSetOf<ArrayDatabaseReference>()

        testProxy.executionContext.referenceResolver.collectingReferences(collected) {
            evaluateCallstack(
                executionContext = testProxy.executionContext,
                contextData = ref,
                callstack = listOf(
                    sortOp(descending = false, selector = valueSelector()),
                    takeOp(1),
                    selectOp(SyntheticTestData.Companion.Field.NAME, SyntheticTestData.Companion.Field.VALUE),
                ),
                allowDeferring = false,
            )
        }

        assertTrue(
            collected.any { it.arrayId == ref.arrayId },
            "fused read must record the array ref for stream invalidation",
        )
    }

    // ─── fall-throughs (the in-memory sort MUST run) ───

    @Test
    fun noSelectDownstream_fallsBackToInMemorySort() = runTest {
        val ref = populateArray(listOf("A" to 2L, "B" to 1L, "C" to 3L))

        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = ref,
            callstack = listOf(
                sortOp(descending = false, selector = valueSelector()),
                takeOp(2),
            ),
            allowDeferring = false,
        )

        assertEquals(listOf("B", "A"), names(result))
        assertEquals(1, sortExecutions, "no downstream select ⇒ unfused path")
        assertEquals(1, takeExecutions)
    }

    @Test
    fun unboundedWindow_dropOnly_fallsBack() = runTest {
        val ref = populateArray(listOf("A" to 2L, "B" to 1L, "C" to 3L))

        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = ref,
            callstack = listOf(
                sortOp(descending = false, selector = valueSelector()),
                dropOp(1),
                selectOp(SyntheticTestData.Companion.Field.NAME, SyntheticTestData.Companion.Field.VALUE),
            ),
            allowDeferring = false,
        )

        assertEquals(listOf("A", "C"), names(result))
        assertEquals(1, sortExecutions, "an unbounded window must not fuse")
    }

    @Test
    fun opAheadOfSort_disablesFusion() = runTest {
        val identityOpSchema = operationSchema(id = "test/owf-identity")
        testProxy.additionalImplementations["test/owf-identity"] = object : SyncOperationImplementation {
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

        val ref = populateArray(listOf("A" to 2L, "B" to 1L, "C" to 3L))

        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = ref,
            callstack = listOf(
                OperationDto(params = emptyMap(), schema = identityOpSchema),
                sortOp(descending = false, selector = valueSelector()),
                takeOp(2),
                selectOp(SyntheticTestData.Companion.Field.NAME, SyntheticTestData.Companion.Field.VALUE),
            ),
            allowDeferring = false,
        )

        assertEquals(listOf("B", "A"), names(result))
        assertEquals(1, sortExecutions, "an op ahead of the sort must disable fusion")
    }

    @Test
    fun propertyKeyOverACompositeArray_ordersInBothDirections() = runTest {
        // ⚠⚠ The regression behind Drive's dead "Date modified" / "Date created"
        // direction toggle. `isEntity = false` is the ORDINARY case, not an exotic
        // one: an array's reference is entity-typed only when its element schema is
        // itself an `Entity` subtype, and `File : Composite` is not — so
        // `root.files` deserialises plain composites, `element as? Entity<*>` is
        // null, and the property key read `null` on EVERY row. Every comparison then
        // ties and the order falls to whatever breaks ties, identically in both
        // directions. The rows are stored AS entities (that is where the property
        // lives); only the read shape differs, which is exactly Drive's shape.
        //
        // Both directions are asserted because one alone can coincide with encounter
        // order and pass while broken — how the existing coverage missed this.
        val ref = compositeArrayOfEntityRows(
            listOf("A" to 30L, "B" to 10L, "C" to 40L, "D" to 20L),
        )

        fun ordered(descending: Boolean): List<String> {
            sortExecutions = 0
            val result = evaluateCallstack(
                executionContext = testProxy.executionContext,
                contextData = ref,
                callstack = listOf(
                    sortOp(
                        descending = descending,
                        selector = SimpleTList(listOf(sizeProp.selector() as StaticPropertyRelation)),
                    ),
                    selectOp(
                        SyntheticTestData.Companion.Field.NAME,
                        SyntheticTestData.Companion.Field.VALUE,
                    ),
                ),
                allowDeferring = false,
            )
            assertEquals(1, sortExecutions, "no window ⇒ the unfused sort must run")
            return names(result)
        }

        assertEquals(listOf("B", "D", "A", "C"), ordered(descending = false), "10, 20, 30, 40")
        assertEquals(listOf("C", "A", "D", "B"), ordered(descending = true), "40, 30, 20, 10")
    }

    @Test
    fun propertyKeyOverACompositeArray_thenByCannotHideADeadPrimaryKey() = runTest {
        // Drive's actual shape — `sortBy(property).thenBy { name }`, which puts TWO
        // SortBy ops on the chain (the fold appends a full-key op and the prior one
        // stays). A `thenBy` is what turned the dead primary key into a *plausible*
        // listing: the ties fell through to an always-ascending name key, so the
        // screen showed a sorted-looking list that ignored the direction toggle.
        val ref = compositeArrayOfEntityRows(
            listOf("A" to 30L, "B" to 10L, "C" to 40L, "D" to 20L),
        )

        fun ordered(descending: Boolean): List<String> {
            val key = SimpleTList<Data>(listOf(sizeProp.selector() as StaticPropertyRelation))
            val result = evaluateCallstack(
                executionContext = testProxy.executionContext,
                contextData = ref,
                callstack = listOf(
                    sortOp(descending = descending, selector = key),
                    sortOp(
                        descending = descending,
                        selector = key,
                        thenSelectors = listOf(nameSelector() to false),
                    ),
                    selectOp(
                        SyntheticTestData.Companion.Field.NAME,
                        SyntheticTestData.Companion.Field.VALUE,
                    ),
                ),
                allowDeferring = false,
            )
            return names(result)
        }

        assertEquals(listOf("B", "D", "A", "C"), ordered(descending = false))
        assertEquals(listOf("C", "A", "D", "B"), ordered(descending = true))
    }

    @Test
    fun propertyKeyOverACompositeArray_fuses_inOneStoreReadPerRow() = runTest {
        // `drive-sorted-folder-paging.md` §3 B2 (SP-1). This shape used to be refused: the
        // fused read kept the key on the row's `properties` map and a composite has none.
        // Now a kept property makes the mapper read the row AS AN ENTITY, so the key is
        // there — one read per row, no `DataId` derivation, no re-read as an entity.
        val ref = compositeArrayOfEntityRows(
            listOf("A" to 30L, "B" to 10L, "C" to 40L, "D" to 20L),
        )

        fun ordered(descending: Boolean): List<String> {
            sortExecutions = 0
            store.reads = 0
            val result = evaluateCallstack(
                executionContext = testProxy.executionContext,
                contextData = ref,
                callstack = listOf(
                    sortOp(
                        descending = descending,
                        selector = SimpleTList(listOf(sizeProp.selector() as StaticPropertyRelation)),
                    ),
                    takeOp(3),
                    selectOp(
                        SyntheticTestData.Companion.Field.NAME,
                        SyntheticTestData.Companion.Field.VALUE,
                    ),
                ),
                allowDeferring = false,
            )
            assertEquals(0, sortExecutions, "a composite-row property key must fuse")
            // The array entry is read twice (existence probe + member list) and each of the
            // four rows exactly once. The unfused path read every row twice — once as the
            // composite, once more as an entity to reach the property it had dropped.
            assertEquals(
                4 + 2, store.reads,
                "fused property-key page must cost one store read per row (plus the array entry)",
            )
            return names(result)
        }

        // Both directions, because one alone can coincide with encounter order.
        assertEquals(listOf("B", "D", "A"), ordered(descending = false), "10, 20, 30")
        assertEquals(listOf("C", "A", "D"), ordered(descending = true), "40, 30, 20")
    }

    // ─── the thenBy run (the fifth blocker) ───

    @Test
    fun thenByRun_foldsToItsLastOp_andFuses() = runTest {
        // `sortBy(A).thenBy(B).thenBy(C)` arrives as THREE SortBy ops, each carrying the keys
        // of the one before plus its own (`SortByRo.foldThenKey` appends, it cannot replace).
        // The gate must see the window through the run, not fall through at `SortBy(A)`.
        val ref = populateArray(
            listOf("A" to 2L, "B" to 1L, "C" to 2L, "D" to 1L),
        )
        val value = valueSelector()
        val name = nameSelector()

        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = ref,
            callstack = listOf(
                sortOp(descending = false, selector = value),
                sortOp(descending = false, selector = value, thenSelectors = listOf(name to true)),
                takeOp(3),
                selectOp(SyntheticTestData.Companion.Field.NAME, SyntheticTestData.Companion.Field.VALUE),
            ),
            allowDeferring = false,
        )

        // Value ascending, then name DESCENDING: (1,D) (1,B) (2,C) — identical to the single
        // full-key op, which is the semantics the fold relies on.
        assertEquals(listOf("D", "B", "C"), names(result))
        assertEquals(0, sortExecutions, "a thenBy run must fold and fuse, not fall through")
        assertEquals(0, takeExecutions)
    }

    @Test
    fun unrelatedConsecutiveSorts_areNotARun_andStayUnfused() = runTest {
        // `sortBy { a }.sortBy { b }` is two independent sorts: `b`'s ties keep `a`'s order.
        // Folding them to the last would drop `a`, so they must not fold — and the gate, seeing
        // a SortBy where it expects a window, falls through to two in-memory passes.
        val ref = populateArray(
            listOf("B" to 1L, "A" to 1L, "D" to 2L, "C" to 2L),
        )

        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = ref,
            callstack = listOf(
                sortOp(descending = false, selector = nameSelector()),
                sortOp(descending = false, selector = valueSelector()),
                takeOp(4),
                selectOp(SyntheticTestData.Companion.Field.NAME, SyntheticTestData.Companion.Field.VALUE),
            ),
            allowDeferring = false,
        )

        // By value, ties in name order — which only the first pass provides.
        assertEquals(listOf("A", "B", "C", "D"), names(result))
        assertEquals(2, sortExecutions, "two unrelated sorts must both run in memory")
    }

    // ─── type-test key (SP-2) ───

    @Test
    fun typeTestKey_putsSubtypeRowsFirst_fused_overCompositeRows() = runTest {
        // `sortByDescending { isA<Folder>() }` — folders-first as a FIRST sort key rather than
        // a client-side partition. Composite rows: the type is the row's own `schema`.
        val ref = mixedArray(
            listOf(
                Row("plain-b", 1L, special = false),
                Row("special-z", 2L, special = true),
                Row("plain-a", 3L, special = false),
                Row("special-y", 4L, special = true),
            ),
        )

        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = ref,
            callstack = listOf(
                sortOp(
                    descending = true,
                    selector = captureSelector<SyntheticTestData>(SyntheticTestData.Schema) {
                        isA<SyntheticSpecialData>()
                    },
                    thenSelectors = listOf(nameSelector() to false),
                ),
                takeOp(3),
                selectOp(SyntheticTestData.Companion.Field.NAME, SyntheticTestData.Companion.Field.VALUE),
            ),
            allowDeferring = false,
        )

        assertEquals(listOf("special-y", "special-z", "plain-a"), names(result))
        assertEquals(0, sortExecutions, "a type-test key must fuse")
    }

    @Test
    fun typeTestKey_combinedWithAPropertyKey_readsTheTypeOffTheEntity() = runTest {
        // ⚠⚠ The trap `drive-sorted-folder-paging.md` §4.4 kind 2 names: a property key makes
        // the scan read AS ENTITIES, and an entity's `schema` is always `Entity.Schema` — the
        // real type is `Entity.type`. Reading `schema` here would call every row not-special
        // and interleave them by size. Drive's date sort is exactly this combination.
        val ref = mixedArray(
            listOf(
                Row("plain-big", 40L, special = false),
                Row("special-small", 10L, special = true),
                Row("plain-small", 20L, special = false),
                Row("special-big", 30L, special = true),
            ),
            withSize = true,
        )

        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = ref,
            callstack = listOf(
                sortOp(
                    descending = true,
                    selector = captureSelector<SyntheticTestData>(SyntheticTestData.Schema) {
                        isA<SyntheticSpecialData>()
                    },
                    thenSelectors = listOf(
                        SimpleTList<Data>(listOf(sizeProp.selector() as StaticPropertyRelation)) to true,
                    ),
                ),
                takeOp(4),
                selectOp(SyntheticTestData.Companion.Field.NAME, SyntheticTestData.Companion.Field.VALUE),
            ),
            allowDeferring = false,
        )

        assertEquals(
            listOf("special-big", "special-small", "plain-big", "plain-small"),
            names(result),
            "specials first (30, 10), then the rest by size (40, 20)",
        )
        assertEquals(0, sortExecutions, "type test + property key must fuse together")
    }

    @Test
    fun typeTestKey_unfused_ordersIdentically() = runTest {
        // No window ⇒ the in-memory sort runs and evaluates `isA` per row through its
        // implementation — the semantics-parity half of the fusion contract.
        val ref = mixedArray(
            listOf(
                Row("plain-b", 1L, special = false),
                Row("special-z", 2L, special = true),
                Row("plain-a", 3L, special = false),
                Row("special-y", 4L, special = true),
            ),
        )

        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = ref,
            callstack = listOf(
                sortOp(
                    descending = true,
                    selector = captureSelector<SyntheticTestData>(SyntheticTestData.Schema) {
                        isA<SyntheticSpecialData>()
                    },
                    thenSelectors = listOf(nameSelector() to false),
                ),
                selectOp(SyntheticTestData.Companion.Field.NAME, SyntheticTestData.Companion.Field.VALUE),
            ),
            allowDeferring = false,
        )

        assertEquals(listOf("special-y", "special-z", "plain-a", "plain-b"), names(result))
        assertEquals(1, sortExecutions, "no window ⇒ the unfused sort must run")
    }

    @Test
    fun typeTestAsAFilterPredicate_keepsOnlySubtypeRows() = runTest {
        // The predicate shape: `filter { isA<Folder>() }` is the first real folder filter —
        // `filter<Folder> { … }` only narrows the predicate's type and filters nothing.
        val ref = mixedArray(
            listOf(
                Row("plain-b", 1L, special = false),
                Row("special-z", 2L, special = true),
                Row("plain-a", 3L, special = false),
            ),
        )

        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = ref,
            callstack = listOf(
                OperationDto(
                    params = mapOf(
                        Filter.Params.PREDICATE to captureSelector<SyntheticTestData>(SyntheticTestData.Schema) {
                            isA<SyntheticSpecialData>()
                        },
                    ),
                    schema = Filter.Schema,
                ),
            ),
            allowDeferring = false,
        )

        assertEquals(listOf("special-z"), names(result))
    }

    // ─── field-transform key (SP-4) ───

    @Test
    fun fieldLowercaseKey_fuses_andOrdersCaseInsensitively() = runTest {
        // `sortBy { path.lowercase() }` — D4(a): a stored field through a pure scalar op is
        // still a key the thin row carries, so the case-insensitive Name key and tie-break
        // stay on the fused path. Mixed case on purpose: a case-SENSITIVE compare would put
        // "Banana"/"Cherry" before "apple".
        val ref = populateArray(
            listOf("Banana" to 1L, "apple" to 2L, "Cherry" to 3L, "date" to 4L),
        )

        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = ref,
            callstack = listOf(
                sortOp(
                    descending = false,
                    selector = captureSelector<SyntheticTestData>(SyntheticTestData.Schema) { name.lowercase() },
                ),
                takeOp(3),
                selectOp(SyntheticTestData.Companion.Field.NAME, SyntheticTestData.Companion.Field.VALUE),
            ),
            allowDeferring = false,
        )

        assertEquals(listOf("apple", "Banana", "Cherry"), names(result))
        assertEquals(0, sortExecutions, "a field through a pure scalar op must fuse")
    }

    @Test
    fun fieldThroughAnOpWithParams_fallsBack() = runTest {
        // Only the parameterless pure transforms are admitted; anything else keeps the
        // interpreter — correct, just not fast.
        val ref = populateArray(
            listOf("x-b" to 1L, "y-a" to 2L, "z-c" to 3L),
        )

        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = ref,
            callstack = listOf(
                sortOp(
                    descending = false,
                    selector = captureSelector<SyntheticTestData>(SyntheticTestData.Schema) {
                        name.substringAfterLast("-")
                    },
                ),
                takeOp(2),
                selectOp(SyntheticTestData.Companion.Field.NAME, SyntheticTestData.Companion.Field.VALUE),
            ),
            allowDeferring = false,
        )

        assertEquals(listOf("y-a", "x-b"), names(result))
        assertEquals(1, sortExecutions, "an op with params is not a fusable transform")
    }

    @Test
    fun multiStepSelector_fallsBack() = runTest {
        // A two-step selector (`{ child.name }` through a composite field) is not
        // fusable — the unfused per-element callstack walk must run and produce
        // the correct order.
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = testProxy.tContext,
                type = TList.ID,
                relationId = "test/owf-parent-array",
            ),
            isEntity = false,
            elementSchemaId = SyntheticParent.ID,
        )
        listOf("Zed" to "p1", "Amy" to "p2", "Mia" to "p3").forEach { (childName, parentName) ->
            testProxy.database.addToArray(
                ref,
                SyntheticParent.Factory.create(
                    testProxy.tContext,
                    parentName,
                    SyntheticTestData.Factory.create(testProxy.tContext, childName, 0L),
                ),
            )
        }

        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = ref,
            callstack = listOf(
                sortOp(
                    descending = false,
                    selector = captureSelector<SyntheticParent>(SyntheticParent.Schema) { child.name },
                ),
                takeOp(2),
                selectOp(SyntheticParent.Companion.Field.NAME, SyntheticParent.Companion.Field.CHILD),
            ),
            allowDeferring = false,
        )

        assertNotNull(result)
        val parentNames = (result as TList<*>).toList().map { item ->
            when (item) {
                is SyntheticParent -> item.name.get()
                is Entity<*> -> item.getFieldValue<TString>(SyntheticParent.Companion.Field.NAME).get()
                else -> error("unexpected element: $item")
            }
        }
        assertEquals(listOf("p2", "p3"), parentNames) // Amy, Mia
        assertEquals(1, sortExecutions, "a multi-step selector must not fuse")
    }

    // ─── helpers ───

    private fun sortOp(
        descending: Boolean,
        selector: TList<Data>,
        thenSelectors: List<Pair<TList<Data>, Boolean>> = emptyList(),
    ): OperationDto {
        val base = mapOf(
            SortBy.Params.SELECTOR to selector,
            SortBy.Params.DESCENDING to descending.toTBoolean(testProxy.tContext),
        )
        val thens = if (thenSelectors.isEmpty()) emptyMap() else mapOf(
            SortBy.Params.THEN_SELECTORS to SimpleTList<Data>(thenSelectors.map { it.first }),
            SortBy.Params.THEN_DESCENDINGS to SimpleTList<Data>(
                thenSelectors.map { it.second.toTBoolean(testProxy.tContext) },
            ),
        )
        return OperationDto(params = base + thens, schema = SortBy.Schema)
    }

    private fun dropOp(n: Int) = OperationDto(
        params = mapOf(Drop.Params.N to n.toTInteger()),
        schema = Drop.Schema,
    )

    private fun takeOp(n: Int) = OperationDto(
        params = mapOf(Take.Params.N to n.toTInteger()),
        schema = Take.Schema,
    )

    private fun selectOp(vararg fieldIds: String) = OperationDto(
        params = mapOf(
            Select.Params.SELECTORS to TListDto(
                fieldIds.map {
                    Selector.FieldSelector.create<SyntheticTestData>(
                        testProxy.tContext,
                        fieldReference = fieldRef(it),
                    )
                }
            )
        ),
        schema = Select.Schema,
    )

    private fun valueSelector(): TList<Data> =
        captureSelector<SyntheticTestData>(SyntheticTestData.Schema) { value }

    private fun nameSelector(): TList<Data> =
        captureSelector<SyntheticTestData>(SyntheticTestData.Schema) { name }

    /** Captures a selector lambda as a callstack — the [SortByOperationTest] recipe. */
    private fun <T : Data> captureSelector(
        schema: CompositeSchema,
        selector: T.() -> TValue,
    ): TList<Data> {
        val context = testProxy.tContext
        val captured = LocalRequestChain(testProxy.localTaskExecutor, context)
            .addNestedArray { arrayChain ->
                val elementRo = context.roProvider().createRequestObject<T>(
                    type = schema.toType(),
                    requestChain = arrayChain,
                    context = context,
                )
                (selector(elementRo) as RequestObject).requestChain
            }

        @Suppress("UNCHECKED_CAST")
        return captured.getCallstack().last() as TList<Data>
    }

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

    private fun populateArray(items: List<Pair<String, Long>>): ArrayDatabaseReference {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = testProxy.tContext,
                type = TList.ID,
                relationId = "test/owf-array",
            ),
            isEntity = false,
            elementSchemaId = SyntheticTestData.ID,
        )
        items.forEach { (name, value) ->
            testProxy.database.addToArray(
                ref,
                SyntheticTestData.Factory.create(testProxy.tContext, name, value),
            )
        }
        return ref
    }

    /**
     * The same stored entities as [entityArray], behind a reference that reads them
     * back as **composites** (`isEntity = false`) — what every array whose element
     * schema is not an `Entity` subtype does, `defaultFileSystem.root.files`
     * included. The property is on the stored row; only the read shape differs.
     */
    private fun compositeArrayOfEntityRows(
        items: List<Pair<String, Long>>,
    ): ArrayDatabaseReference {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = testProxy.tContext,
                type = TList.ID,
                relationId = "test/owf-composite-array",
            ),
            isEntity = false,
            elementSchemaId = SyntheticTestData.ID,
        )
        items.forEach { (name, size) ->
            val entity = Entity.create<SyntheticTestData>(
                context = testProxy.tContext,
                composite = SyntheticTestData.Factory.create(testProxy.tContext, name, 0L),
            ) {
                set(sizeProp, size)
            }
            testProxy.database.addToArray(ref, entity)
        }
        return ref
    }

    private class Row(val name: String, val value: Long, val special: Boolean)

    /**
     * Base-type and subtype rows in ONE array typed by the base (`root.files` holds `File`s
     * and `Folder`s the same way). With [withSize], every row is stored as an entity carrying
     * [sizeProp], so a property key makes the fused scan read entities — the shape whose type
     * must be read off `Entity.type`.
     */
    private fun mixedArray(rows: List<Row>, withSize: Boolean = false): ArrayDatabaseReference {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = testProxy.tContext,
                type = TList.ID,
                relationId = "test/owf-mixed-array",
            ),
            isEntity = false,
            elementSchemaId = SyntheticTestData.ID,
        )
        rows.forEach { row ->
            val composite = if (row.special) {
                SyntheticSpecialData.Factory.create(testProxy.tContext, row.name, row.value)
            } else {
                SyntheticTestData.Factory.create(testProxy.tContext, row.name, row.value)
            }
            val stored: Data = if (withSize) {
                Entity.create<SyntheticTestData>(testProxy.tContext, composite) { set(sizeProp, row.value) }
            } else {
                composite
            }
            testProxy.database.addToArray(ref, stored)
        }
        return ref
    }

    /** Entities with [sizeProp] set — the stored-value-property fixture. */
    private fun entityArray(items: List<Pair<String, Long>>): ArrayDatabaseReference {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = testProxy.tContext,
                type = TList.ID,
                relationId = "test/owf-entity-array",
            ),
            isEntity = true,
            elementSchemaId = SyntheticTestData.ID,
        )
        items.forEach { (name, size) ->
            val entity = Entity.create<SyntheticTestData>(
                context = testProxy.tContext,
                composite = SyntheticTestData.Factory.create(testProxy.tContext, name, 0L),
            ) {
                set(sizeProp, size)
            }
            testProxy.database.addToArray(ref, entity)
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
