package org.tenshu.core.common.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.EqualTo
import org.tenshu.core.TString
import org.tenshu.core.collection.list.Filter
import org.tenshu.core.collection.list.Select
import org.tenshu.core.collection.list.Selector
import org.tenshu.core.collection.list.SortBy
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.Take
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.SyntheticGrandparent
import org.tenshu.core.common.SyntheticParent
import org.tenshu.core.common.SyntheticSpecialData
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.array.TListDto
import org.tenshu.core.common.impl.reference.DynamicPropertyTypeReferenceDto
import org.tenshu.core.common.impl.reference.PropertyTypeReferenceDto
import org.tenshu.core.common.module.dynamicProperty
import org.tenshu.core.common.module.tModule
import org.tenshu.core.relation.Property
import org.tenshu.core.relation.dynamicProperty
import org.tenshu.core.toTString
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.implementationFactory
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.contains
import org.tenshu.core.entity.DataId
import org.tenshu.core.equalTo
import org.tenshu.core.inRange
import org.tenshu.core.isA
import org.tenshu.core.lowercase
import org.tenshu.core.number.toTInteger
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.fieldRef
import org.tenshu.core.schema.toType
import org.tenshu.core.toTBoolean
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The predicate gate of the filter-aware ordered-window fusion ([classifyFusedPredicate]) and its
 * no-`traverse` shape (`filter · sortBy · take · select` on a raw array) — the engine-level half of
 * `core-ordered-window-fusion.md` §9. The Photos-feed half, with the real `File` predicates, is
 * `:file-service-proxy`'s `FilteredWindowFusionTest`.
 */
class FilteredWindowFusionPredicateTest {

    /**
     * A dynamic property that NAVIGATES two composites deep: the grandparent row's `child`
     * (a parent), then *its* `child`, then that one's name.
     */
    private val grandchildName: Property.Dynamic<TString> =
        dynamicProperty<SyntheticGrandparent, TString>(
            id = "test/synthetic-grandparent/fwfGrandchildName",
            receiver = SyntheticGrandparent.Schema,
        ) { child.child.name }

    private lateinit var testProxy: TestProxy
    private val executions = mutableMapOf<String, Int>()

    @BeforeTest
    fun setup() {
        val registry = createSyntheticTestRegistry()
        registry.registerConfig(
            tModule { dynamicProperty(fromId = SyntheticGrandparent.ID, property = grandchildName) }
        )
        testProxy = TestProxy(schemaRegistry = registry)
        executions.clear()
        for (id in listOf(Filter.ID, SortBy.ID, Take.ID)) {
            val delegate = implementationFactory(id) as SyncOperationImplementation
            testProxy.additionalImplementations[id] = object : SyncOperationImplementation {
                override fun <T : Data?> execute(
                    executionContext: ExecutionContext,
                    operationContext: OperationContext?,
                    implementationFactory: OperationImplementationFactory,
                    caller: Any?,
                    params: Map<String, Data>?,
                ): T {
                    executions[id] = (executions[id] ?: 0) + 1
                    return delegate.execute(
                        executionContext, operationContext, implementationFactory, caller, params,
                    )
                }
            }
        }
    }

    // ─── the classifier ───

    @Test
    fun aFieldThroughPureOperatorsWithConstantOperands_isFusable() {
        assertNotNull(classify(predicate { value.inRange(2L, 6L) }))
        assertNotNull(classify(predicate { value equalTo 3L }))
        assertNotNull(classify(predicate { name.lowercase().contains("a") }))
    }

    @Test
    fun anOpOnTheRowItself_isNot() {
        assertNull(classify(predicate { isA<SyntheticSpecialData>() }), "a type test reads the row")
    }

    @Test
    fun anOperandThatIsItselfACallstack_isNot() {
        // `value equalTo <the row's own value>` — what a row-vs-row comparison encodes as: the
        // operand is a callstack evaluated per row, i.e. a second read the thin row may not have.
        val rowOperand = predicate { value }
        val callstack = listOf(
            rowOperand.first(),
            OperationDto(
                params = mapOf(EqualTo.Params.OTHER to TListDto(rowOperand)),
                schema = EqualTo.Schema,
            ),
        )
        assertNull(classify(callstack))
    }

    @Test
    fun aFieldTheElementSchemaDoesNotHave_isNot() {
        assertNull(
            classify(listOf(fieldRef("test/synthetic/notAField"))),
            "an unknown head never fuses",
        )
    }

    // ─── the no-traverse shape ───

    @Test
    fun filterSortTakeSelect_onARawArray_fuses_andMatchesUnfused() = runTest {
        val ref = populate(
            listOf("a" to 5L, "b" to 1L, "c" to 5L, "d" to 3L, "e" to 9L, "f" to 4L, "g" to 5L),
        )
        val callstack = listOf(
            filterOp(predicate { value.inRange(2L, 6L) }),
            sortOp(descending = true),
            takeOp(3),
            selectOp(),
        )

        val fused = names(evaluateCallstack(testProxy.executionContext, ref, callstack, false))
        assertEquals(0, (executions[Filter.ID] ?: 0) + (executions[SortBy.ID] ?: 0) + (executions[Take.ID] ?: 0))

        // Unfused reference: the filter alone (nothing sorted follows ⇒ no fusion), then the rest
        // in memory. Ties at 5 keep encounter order a, c, g.
        val filtered = evaluateCallstack(testProxy.executionContext, ref, callstack.take(1), false)
        val unfused = names(evaluateCallstack(testProxy.executionContext, filtered, callstack.drop(1), false))
        assertEquals(listOf("a", "c", "g"), unfused)
        assertEquals(unfused, fused)
    }

    @Test
    fun anUnfusablePredicate_makesTheWholeChainFallThrough() = runTest {
        val ref = populate(listOf("a" to 5L, "b" to 1L, "c" to 7L))
        val result = names(
            evaluateCallstack(
                testProxy.executionContext,
                ref,
                listOf(
                    filterOp(predicate { value.inRange(0L, 10L) }),
                    filterOp(predicate { isA<SyntheticTestData>() }),
                    sortOp(descending = true),
                    takeOp(2),
                    selectOp(),
                ),
                false,
            ),
        )
        assertEquals(listOf("c", "a"), result)
        assertEquals(1, executions[SortBy.ID], "one refused predicate ⇒ the unfused sort runs")
        assertEquals(2, executions[Filter.ID], "…and every filter runs as itself")
    }

    /**
     * A dynamic predicate whose derivation navigates into a nested composite
     * (`child.child.name`) must not be evaluated on a row the fused read projected: a downstream
     * select that projects the same composite but omits the navigated one (`children[CHILD]` keeps
     * only the parent's `name`) makes `DbEntryMapper` prune `child.child` before the predicate runs,
     * while the unfused filter sees the complete row. A fused chain throws on the pruned composite
     * where the unfused one answers, so the chain falls through instead.
     */
    @Test
    fun aDynamicPredicateNavigatingIntoANestedComposite_fallsThrough_evenUnderAPruningSelect() =
        runTest {
            val ref = ArrayDatabaseReference(
                arrayId = DataId.RelationDataId.create(
                    context = testProxy.tContext,
                    type = TList.ID,
                    relationId = "test/fwf-grandparents",
                ),
                isEntity = false,
                elementSchemaId = SyntheticGrandparent.ID,
            )
            listOf("p1" to "x", "p2" to "y", "p3" to "x", "p4" to "x", "p5" to "y").forEach { (p, c) ->
                testProxy.database.addToArray(
                    ref,
                    SyntheticGrandparent.Factory.create(
                        testProxy.tContext, p,
                        SyntheticParent.Factory.create(
                            testProxy.tContext, "$p-child",
                            SyntheticTestData.Factory.create(testProxy.tContext, c, 1L),
                        ),
                    ),
                )
            }
            val predicate = listOf<Data>(
                DynamicPropertyTypeReferenceDto(grandchildName.id),
                OperationDto(
                    params = mapOf(EqualTo.Params.OTHER to "x".toTString(testProxy.tContext)),
                    schema = EqualTo.Schema,
                ),
            )
            assertNull(
                classifyFusedPredicate(testProxy.executionContext, ref, predicate),
                "a derivation that navigates into a nested composite must not fuse",
            )

            val callstack = listOf(
                filterOp(predicate),
                OperationDto(
                    params = mapOf(
                        SortBy.Params.SELECTOR to TListDto(listOf(fieldRef(SyntheticGrandparent.Companion.Field.NAME))),
                        SortBy.Params.DESCENDING to true.toTBoolean(testProxy.tContext),
                    ),
                    schema = SortBy.Schema,
                ),
                takeOp(2),
                OperationDto(
                    params = mapOf(
                        Select.Params.SELECTORS to TListDto(
                            listOf(
                                Selector.FieldSelector.create<SyntheticGrandparent>(
                                    testProxy.tContext,
                                    fieldReference = fieldRef(SyntheticGrandparent.Companion.Field.NAME),
                                ),
                                // Keyed by the CHILD field's id, so the read-time projection loads
                                // that composite with only its `name` — its own `child` composite,
                                // which the predicate navigates through, is pruned.
                                Selector.PropertySelector.create<SyntheticGrandparent>(
                                    testProxy.tContext,
                                    propertyReference = PropertyTypeReferenceDto(SyntheticGrandparent.Companion.Field.CHILD),
                                    nested = TListDto(
                                        listOf(
                                            Selector.FieldSelector.create<SyntheticParent>(
                                                testProxy.tContext,
                                                fieldReference = fieldRef(SyntheticParent.Companion.Field.NAME),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    schema = Select.Schema,
                ),
            )

            val shipped = parentNames(evaluateCallstack(testProxy.executionContext, ref, callstack, false))
            assertEquals(1, executions[SortBy.ID], "the navigating predicate must make the chain fall through")

            val filtered = evaluateCallstack(testProxy.executionContext, ref, callstack.take(1), false)
            val unfused = parentNames(evaluateCallstack(testProxy.executionContext, filtered, callstack.drop(1), false))
            assertEquals(listOf("p4", "p3"), unfused)
            assertEquals(unfused, shipped)
        }

    private fun parentNames(result: Data?): List<String> = (result as TList<*>).toList().map { row ->
        when (row) {
            is SyntheticGrandparent -> row.name.get()
            is org.tenshu.core.entity.Entity<*> -> row.getFieldValue<TString>(SyntheticGrandparent.Companion.Field.NAME).get()
            else -> error("unexpected row $row")
        }
    }

    // ─── helpers ───

    private fun classify(callstack: List<Data>): FusedPredicate? =
        classifyFusedPredicate(testProxy.executionContext, populate(emptyList()), callstack)

    /** Capture a predicate lambda over a `SyntheticTestData` row as its callstack. */
    private fun predicate(block: SyntheticTestData.() -> Data): List<Data> {
        val context = testProxy.tContext
        val captured = LocalRequestChain(testProxy.localTaskExecutor, context)
            .addNestedArray { arrayChain ->
                val row = context.roProvider().createRequestObject<SyntheticTestData>(
                    type = SyntheticTestData.Schema.toType(),
                    requestChain = arrayChain,
                    context = context,
                )
                (block(row) as RequestObject).requestChain
            }
        @Suppress("UNCHECKED_CAST")
        return (captured.getCallstack().last() as TList<Data>).toList()
    }

    private fun filterOp(predicate: List<Data>) = OperationDto(
        params = mapOf(Filter.Params.PREDICATE to TListDto(predicate)),
        schema = Filter.Schema,
    )

    private fun sortOp(descending: Boolean) = OperationDto(
        params = mapOf(
            SortBy.Params.SELECTOR to TListDto(predicate { value }),
            SortBy.Params.DESCENDING to descending.toTBoolean(testProxy.tContext),
        ),
        schema = SortBy.Schema,
    )

    private fun takeOp(n: Int) = OperationDto(
        params = mapOf(Take.Params.N to n.toTInteger()),
        schema = Take.Schema,
    )

    private fun selectOp() = OperationDto(
        params = mapOf(
            Select.Params.SELECTORS to TListDto(
                listOf(SyntheticTestData.Companion.Field.NAME, SyntheticTestData.Companion.Field.VALUE).map {
                    Selector.FieldSelector.create<SyntheticTestData>(testProxy.tContext, fieldReference = fieldRef(it))
                },
            ),
        ),
        schema = Select.Schema,
    )

    private fun populate(items: List<Pair<String, Long>>): ArrayDatabaseReference {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = testProxy.tContext,
                type = TList.ID,
                relationId = "test/fwf-array",
            ),
            isEntity = false,
            elementSchemaId = SyntheticTestData.ID,
        )
        items.forEach { (name, value) ->
            testProxy.database.addToArray(ref, SyntheticTestData.Factory.create(testProxy.tContext, name, value))
        }
        return ref
    }

    private fun names(result: Data?): List<String> = (result as TList<*>).toList().map { row ->
        when (row) {
            is SyntheticTestData -> row.name.get()
            is org.tenshu.core.entity.Entity<*> -> row.getFieldValue<TString>(SyntheticTestData.Companion.Field.NAME).get()
            else -> error("unexpected row $row")
        }
    }
}
