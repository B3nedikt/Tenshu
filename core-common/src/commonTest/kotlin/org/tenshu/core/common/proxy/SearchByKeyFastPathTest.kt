package org.tenshu.core.common.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.EqualTo
import org.tenshu.core.Maybe
import org.tenshu.core.collection.list.Search
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.reference.FieldReferenceDto
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.entity.DataId
import org.tenshu.core.number.toTInteger
import org.tenshu.core.toTString
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * By-key search short-circuit ([trySearchByKey] + the [evaluateCallstack]
 * hook): `search { keyField equalTo K }` on a raw array resolves via a
 * content-addressed point read; everything else — and every *miss* — falls
 * back to the unchanged scan. Dispatch of the in-memory [SearchImplementation]
 * is counted to prove which path ran; File/Folder-schema semantics (subtype
 * identity roots, nested descent) are covered in `:file-service-proxy`'s
 * `SearchByKeyIntegrationTest`.
 */
class SearchByKeyFastPathTest {

    private lateinit var testProxy: TestProxy
    private var searchExecutions = 0

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        searchExecutions = 0
        val real = SearchImplementation()
        testProxy.additionalImplementations[Search.ID] = object : SyncOperationImplementation {
            override fun <T : Data?> execute(
                executionContext: ExecutionContext,
                operationContext: OperationContext?,
                implementationFactory: OperationImplementationFactory,
                caller: Any?,
                params: Map<String, Data>?,
            ): T {
                searchExecutions++
                return real.execute(
                    executionContext, operationContext, implementationFactory, caller, params,
                )
            }
        }
    }

    @Test
    fun keyEqualityHit_pointReads_withoutDispatchingScan() = runTest {
        val ref = populate("a", "b", "c", "d")

        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = ref,
            callstack = listOf(searchByName("c")),
            allowDeferring = false,
        )

        assertIs<Maybe.Some<*>>(result)
        assertEquals("c", (result.value as SyntheticTestData).name.get())
        assertEquals(0, searchExecutions, "hit must be served by the point read, not the scan")
    }

    @Test
    fun membershipMiss_fallsBackToScan() = runTest {
        val ref = populate("a", "b")

        // "c" exists in the DATABASE but is not a member of THIS array — the
        // fast path must not leak it in, and must delegate (the scan settles
        // Empty; with nested lists the descent could still find a real match).
        testProxy.database.saveData(
            SyntheticTestData.Factory.create(testProxy.tContext, "c", 3L)
        )

        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = ref,
            callstack = listOf(searchByName("c")),
            allowDeferring = false,
        )

        assertIs<Maybe.Empty<*>>(result)
        assertEquals(1, searchExecutions, "miss must fall back to the scan (nested descent)")
    }

    @Test
    fun nonKeyFieldPredicate_fallsBackToScan() = runTest {
        val ref = populate("a", "b", "c")

        val predicate = listOf<Data>(
            FieldReferenceDto(SyntheticTestData.Companion.Field.VALUE),
            OperationDto(
                params = mapOf(EqualTo.Params.OTHER to 1L.toTInteger()),
                schema = EqualTo.Schema,
            ),
        )
        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = ref,
            callstack = listOf(
                OperationDto(
                    params = mapOf(Search.Params.PREDICATE to tListOf(predicate)),
                    schema = Search.Schema,
                )
            ),
            allowDeferring = false,
        )

        assertIs<Maybe.Some<*>>(result)
        assertEquals("b", (result.value as SyntheticTestData).name.get())
        assertEquals(1, searchExecutions, "non-key predicate must scan")
    }

    @Test
    fun compoundPredicate_fallsBackToScan() = runTest {
        val ref = populate("a", "b")

        // Three-entry predicate (field, equalTo, extra op) — not the exact
        // two-entry key shape, so no fast path.
        val predicate = listOf<Data>(
            FieldReferenceDto(SyntheticTestData.Companion.Field.NAME),
            OperationDto(
                params = mapOf(EqualTo.Params.OTHER to "b".toTString()),
                schema = EqualTo.Schema,
            ),
            OperationDto(
                params = mapOf(EqualTo.Params.OTHER to "b".toTString()),
                schema = EqualTo.Schema,
            ),
        )
        evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = ref,
            callstack = listOf(
                OperationDto(
                    params = mapOf(Search.Params.PREDICATE to tListOf(predicate)),
                    schema = Search.Schema,
                )
            ),
            allowDeferring = false,
        )

        assertEquals(1, searchExecutions, "compound predicate must scan")
    }

    @Test
    fun fastPathHit_recordsArrayRefForStreamCollectors() = runTest {
        val ref = populate("a", "b")
        val collected = mutableSetOf<ArrayDatabaseReference>()

        testProxy.executionContext.referenceResolver.collectingReferences(collected) {
            evaluateCallstack(
                executionContext = testProxy.executionContext,
                contextData = ref,
                callstack = listOf(searchByName("b")),
                allowDeferring = false,
            )
        }

        assertTrue(
            collected.any { it.arrayId == ref.arrayId },
            "a fast-pathed search still observes the array — invalidation must cover it",
        )
        assertEquals(0, searchExecutions)
    }

    // ─── helpers ───

    private fun populate(vararg names: String): ArrayDatabaseReference {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = testProxy.tContext,
                type = TList.ID,
                relationId = "test/search-array",
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

    private fun searchByName(name: String) = OperationDto(
        params = mapOf(
            Search.Params.PREDICATE to tListOf(
                listOf<Data>(
                    FieldReferenceDto(SyntheticTestData.Companion.Field.NAME),
                    OperationDto(
                        params = mapOf(EqualTo.Params.OTHER to name.toTString()),
                        schema = EqualTo.Schema,
                    ),
                )
            )
        ),
        schema = Search.Schema,
    )

    private fun tListOf(items: List<Data>): TList<Data> =
        items.toTList(testProxy.tContext)
}
