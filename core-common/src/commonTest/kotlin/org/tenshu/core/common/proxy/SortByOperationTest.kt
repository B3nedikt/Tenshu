package org.tenshu.core.common.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.TValue
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.collection.list.SortBy
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.impl.TIntegerDto
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.valueProperty
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.number.TInteger
import org.tenshu.core.relation.StaticPropertyRelation
import org.tenshu.core.relation.valueProperty
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTBoolean
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Engine-level test for the `SortBy` operation — drives
 * [SortByImplementation] directly through
 * [ExecutionContext.execute] with a hand-assembled `caller` + `params`, in the
 * style of [MaybeGetOperationTest]. The selector callstack is captured the same
 * way [org.tenshu.core.common.impl.array.SortByRo] captures it (the chain DSL is
 * the source of a real field-access callstack), then fed straight to the engine.
 */
class SortByOperationTest {

    private lateinit var testProxy: TestProxy

    /** A stored scalar value property — the `File.LastModifiedAt` / `File.Size` shape. */
    private val sizeProp = valueProperty<TInteger>("test/synthetic/sortBySizeProp", TInteger.Schema)

    @BeforeTest
    fun setup() {
        val registry = createSyntheticTestRegistry()
        registry.registerConfig(
            tModule {
                valueProperty(fromId = SyntheticTestData.ID, property = sizeProp) {
                    TIntegerDto(it as Long)
                }
            }
        )
        testProxy = TestProxy(schemaRegistry = registry)
    }

    @Test
    fun sortsAscendingByIntegerKey() = runTest {
        val result = execSortBy(
            items = listOf("A" to 3L, "B" to 1L, "C" to 2L),
            descending = false,
        ) { value }
        assertEquals(listOf("B", "C", "A"), result)
    }

    @Test
    fun sortsDescendingByIntegerKey() = runTest {
        val result = execSortBy(
            items = listOf("A" to 3L, "B" to 1L, "C" to 2L),
            descending = true,
        ) { value }
        assertEquals(listOf("A", "C", "B"), result)
    }

    @Test
    fun sortsAscendingByStringKey() = runTest {
        // Kotlin String order is by char code: 'B'(66) < 'C'(67) < 'a'(97).
        val result = execSortBy(
            items = listOf("Charlie" to 1L, "alice" to 2L, "Bob" to 3L),
            descending = false,
        ) { name }
        assertEquals(listOf("Bob", "Charlie", "alice"), result)
    }

    @Test
    fun isStableForEqualKeys() = runTest {
        val result = execSortBy(
            items = listOf("first" to 1L, "second" to 1L, "third" to 1L),
            descending = false,
        ) { value }
        assertEquals(listOf("first", "second", "third"), result)
    }

    // ─── value-property keys (`sortBy(File.LastModifiedAt)`) ─────────────────
    //
    // The four tests above all use a *lambda* selector, which reads a stored
    // FIELD off the row. The property-handle arm is a different read — a stored
    // relation — and had no coverage here at all. These four pin it, and the
    // discriminator is the SHAPE THE ROWS ARE READ BACK IN, which is a property
    // of the array reference rather than of the sort:
    //
    //   `isEntity = true`  — the element schema is itself an `Entity` subtype;
    //                        rows carry their `properties` map.
    //   `isEntity = false` — it is not (`File : Composite`, so `root.files`);
    //                        rows come back as plain composites, no properties.
    //
    // The second is the ordinary case and was silently unsortable: the engine
    // read the key off an entity that wasn't there and got `null` for every row,
    // so every comparison tied and ASC and DESC returned the identical list.

    @Test
    fun sortsByValueProperty_overACompositeArray_ascending() = runTest {
        val ref = storedArray(SIZED_ITEMS, isEntity = false, relationId = "test/sortby-composite")
        assertEquals(listOf("B", "D", "A", "C"), execSortByProperty(ref, descending = false))
    }

    @Test
    fun sortsByValueProperty_overACompositeArray_descending() = runTest {
        // ⚠⚠ Asserting the reverse of the ascending case is the whole point: with a
        // dead key BOTH directions come back in encounter order (A, B, C, D), so a
        // test that only checked one direction could pass while the sort did nothing.
        val ref = storedArray(SIZED_ITEMS, isEntity = false, relationId = "test/sortby-composite")
        assertEquals(listOf("C", "A", "D", "B"), execSortByProperty(ref, descending = true))
    }

    @Test
    fun sortsByValueProperty_overAnEntityArray_ascending() = runTest {
        // The shape that always worked — kept beside its composite twin so the
        // difference between them stays visible rather than being a footnote.
        val ref = storedArray(SIZED_ITEMS, isEntity = true, relationId = "test/sortby-entity")
        assertEquals(listOf("B", "D", "A", "C"), execSortByProperty(ref, descending = false))
    }

    @Test
    fun sortsByValueProperty_overAnEntityArray_descending() = runTest {
        val ref = storedArray(SIZED_ITEMS, isEntity = true, relationId = "test/sortby-entity")
        assertEquals(listOf("C", "A", "D", "B"), execSortByProperty(ref, descending = true))
    }

    @Test
    fun missingValueProperty_sortsLast_ascending() = runTest {
        // A row that genuinely has no key must sort last, not throw — the fallback
        // re-read has to tolerate a row whose property it cannot resolve. ⚠ "bare"
        // is inserted FIRST on purpose: a correct sort has to move "keyed" ahead of
        // it, so the assertion still fails against a dead key (which ties both rows
        // and leaves them in encounter order) instead of passing vacuously.
        val ref = storedArray(
            listOf("bare" to null, "keyed" to 5L),
            isEntity = false,
            relationId = "test/sortby-partial",
        )
        assertEquals(listOf("keyed", "bare"), execSortByProperty(ref, descending = false))
    }

    // ─── helpers ───

    /**
     * Runs `SortBy` through the engine and returns the resulting elements' names
     * in order. Mirrors the `MaybeGetOperationTest` pattern: build `caller` +
     * `params`, call `executionContext.execute(operationId = …)`, assert success.
     */
    private fun execSortBy(
        items: List<Pair<String, Long>>,
        descending: Boolean,
        selector: SyntheticTestData.() -> TValue,
    ): List<String> {
        val context = testProxy.tContext
        val caller = items
            .map { (name, value) -> SyntheticTestData.Factory.create(context, name, value) }
            .toTList(context)

        val result = testProxy.executionContext.execute<Data>(
            operationId = SortBy.ID,
            caller = caller,
            params = mapOf(
                SortBy.Params.SELECTOR to captureSelector(selector),
                SortBy.Params.DESCENDING to descending.toTBoolean(context),
            ),
        )

        val sorted = result as TList<*>
        return sorted.toList().map { it.nameValue() }
    }

    /**
     * Rows written to the store and reached through an [ArrayDatabaseReference] —
     * NOT a hand-built in-memory `TList`. That distinction is the one that matters:
     * a `TList` of `Entity.create { set(prop, …) }` rows carries its properties
     * inline, so it cannot tell the two read shapes apart (which is exactly why
     * `SortByPropertyTest` stayed green while the feature was broken).
     *
     * Every row is stored as an entity — that is where the property lives. Only
     * [isEntity], the read shape the reference asks for, differs.
     */
    private fun storedArray(
        items: List<Pair<String, Long?>>,
        isEntity: Boolean,
        relationId: String,
    ): ArrayDatabaseReference {
        val context = testProxy.tContext
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = context,
                type = TList.ID,
                relationId = relationId,
            ),
            isEntity = isEntity,
            elementSchemaId = SyntheticTestData.ID,
        )
        items.forEach { (name, size) ->
            val row = Entity.create<SyntheticTestData>(
                context = context,
                composite = SyntheticTestData.Factory.create(context, name, 0L),
            ) {
                size?.let { set(sizeProp, it) }
            }
            testProxy.database.addToArray(ref, row)
        }
        return ref
    }

    /**
     * Drives `SortBy` with the single-[StaticPropertyRelation] selector the
     * `sortBy(property)` overload records, against rows read out of the store.
     */
    private fun execSortByProperty(
        ref: ArrayDatabaseReference,
        descending: Boolean,
    ): List<String> {
        val result = testProxy.executionContext.execute<Data>(
            operationId = SortBy.ID,
            caller = ref,
            params = mapOf(
                SortBy.Params.SELECTOR to SimpleTList(
                    listOf<Data>(sizeProp.selector() as StaticPropertyRelation)
                ),
                SortBy.Params.DESCENDING to descending.toTBoolean(testProxy.tContext),
            ),
        )
        return (result as TList<*>).toList().map { it.nameValue() }
    }

    /**
     * Captures the selector lambda as a field-access callstack — identical to
     * what `SortByRo` does at the chain-DSL site, so the engine sees a real
     * callstack rather than a hand-rolled `OperationDto`.
     */
    private fun captureSelector(selector: SyntheticTestData.() -> TValue): TList<Data> {
        val context = testProxy.tContext
        val captured = LocalRequestChain(testProxy.localTaskExecutor, context)
            .addNestedArray { arrayChain ->
                val elementRo = context.roProvider().createRequestObject<SyntheticTestData>(
                    type = SyntheticTestData.Schema.toType(),
                    requestChain = arrayChain,
                    context = context,
                )
                (selector(elementRo) as RequestObject).requestChain
            }

        @Suppress("UNCHECKED_CAST")
        return captured.getCallstack().last() as TList<Data>
    }

    private fun Data.nameValue(): String = when (this) {
        is SyntheticTestData -> name.get()
        is Entity<*> -> getFieldValue<TString>(SyntheticTestData.Companion.Field.NAME).get()
        else -> error("unexpected element type: ${this::class.simpleName}")
    }

    private companion object {
        /**
         * Insertion order (A, B, C, D) deliberately matches neither the key order
         * nor its reverse, so neither assertion can be satisfied by a sort that
         * ignored its key and left the rows where it found them.
         */
        val SIZED_ITEMS: List<Pair<String, Long?>> =
            listOf("A" to 30L, "B" to 10L, "C" to 40L, "D" to 20L)
    }
}
