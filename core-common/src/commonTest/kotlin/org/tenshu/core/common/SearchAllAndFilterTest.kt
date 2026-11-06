package org.tenshu.core.common

import kotlinx.coroutines.test.runTest
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.searchAll
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.request.roProvider
import org.tenshu.core.contains
import org.tenshu.core.equalTo
import org.tenshu.core.inRange
import org.tenshu.core.oneOf
import org.tenshu.core.fold
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchAllAndFilterTest {

    private lateinit var testProxy: TestProxy

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
    }

    @Test
    fun testSearchAllDeep() = runTest {
        val item1 = SyntheticTestData.Factory.create(testProxy.tContext, "A", 1L)
        val item2 = SyntheticTestData.Factory.create(testProxy.tContext, "B", 2L)

        val listDto = listOf(item1, item2).toTList(testProxy.tContext)
        val chain = LocalRequestChain(testProxy.localTaskExecutor, testProxy.tContext)
            .addArray(listDto)

        val list = testProxy.tContext.roProvider().createRequestObject<TList<SyntheticTestData>>(
            type = TList.Schema.toType(),
            requestChain = chain,
            context = testProxy.tContext
        )

        val results = list.searchAll<SyntheticTestData> {
            name equalTo "A".toTString(testProxy.tContext)
        }

        val result = (results as RequestObject).requestChain.let { requestChain ->
            requestChain.getResult().fold(
                onSuccess = { it as TList<*> },
                onFailure = { error("SearchAll failed on proxy: $it") }
            )
        }

        assertEquals(1, result.toList().size)
    }

    @Test
    fun testSearchAllContainsIsCaseSensitiveByDefault() = runTest {
        // Uppercase "O" is a SUBSTRING of nothing here case-sensitively ("Report"
        // and "Photo" carry a lowercase 'o') — proving the bare `contains` now
        // matches Kotlin's case-sensitive semantics, while the lowercase "o"
        // matches both. Substring (not exact-equality) semantics still hold.
        val list = syntheticList()

        assertEquals(
            emptyList(),
            containsResults(list, "O"),
            "case-sensitive default: uppercase 'O' must not match lowercase 'o'",
        )
        assertEquals(listOf("Report", "Photo"), containsResults(list, "o"))
    }

    @Test
    fun testSearchAllContainsIgnoreCaseRidesTheWire() = runTest {
        // The explicit ignoreCase opt-in must survive the callstack round-trip —
        // the optional IGNORE_CASE param serialises to the proxy and reaches
        // containsLogic: uppercase "O" now matches "Report" and "Photo".
        val list = syntheticList()

        assertEquals(
            listOf("Report", "Photo"),
            containsResults(list, "O", ignoreCase = true),
        )
    }

    @Test
    fun testSearchAllInRangeNarrowsOverTheWire() = runTest {
        // The half-open window, end to end: RO → serialised params → proxy →
        // `inRangeLogic`. The three rows carry values 1, 2, 3.
        assertEquals(listOf("Report", "Budget"), inRangeResults(1L, 3L), "[1,3) keeps 1 and 2")
        assertEquals(listOf("Budget", "Photo"), inRangeResults(2L, 4L), "[2,4) keeps 2 and 3")
        assertEquals(listOf("Report"), inRangeResults(1L, 2L), "[1,2) keeps only 1")
    }

    @Test
    fun testSearchAllInRangeEmptyWindowKeepsNothing() = runTest {
        // ⚠ An empty range must narrow to nothing, not widen to everything — the
        // failure mode where a caller's arithmetic slip reads as a successful answer.
        assertEquals(emptyList(), inRangeResults(2L, 2L))
    }

    @Test
    fun testSearchAllOneOfNarrowsOverTheWire() = runTest {
        // ⚠⚠ The end-to-end pass that `OneOfLogicTest` cannot give: RO → a **list-valued** param
        // serialised into the predicate callstack → proxy → `oneOfLogic`. Every other predicate
        // ships a scalar `other`/bound, so this is the first one whose param is a collection, and
        // the in-memory unit test would pass just as well if the list never survived the wire.
        assertEquals(
            listOf("Report", "Photo"),
            oneOfResults("Report", "Photo"),
            "a disjunction the chain could not express before",
        )
        assertEquals(listOf("Budget"), oneOfResults("Budget"), "a single candidate is still a set")
    }

    @Test
    fun testSearchAllOneOfEmptyCandidatesKeepsNothing() = runTest {
        // ⚠⚠ Empty means "in none of these", never "unfiltered" — the failure where a caller's
        // empty collection silently widens a read to the whole list rather than narrowing it away.
        assertEquals(emptyList(), oneOfResults())
    }

    @Test
    fun testSearchAllOneOfIgnoresACandidateOfTheWrongType() = runTest {
        // ⚠ Inherits `equalToLogic`: a non-matching type is `false`, not a match and not a crash.
        assertEquals(emptyList(), oneOfResults("Missing"))
    }

    private suspend fun oneOfResults(vararg candidates: String): List<String> {
        val wanted = candidates.map { it.toTString(testProxy.tContext) }
        val results = syntheticList().searchAll<SyntheticTestData> {
            name oneOf wanted
        }
        val result = (results as RequestObject).requestChain.getResult().fold(
            onSuccess = { it as TList<*> },
            onFailure = { error("SearchAll(oneOf) failed on proxy: $it") }
        )
        return result.toList().map { (it as SyntheticTestData).name.get() }
    }

    private suspend fun inRangeResults(startInclusive: Long, endExclusive: Long): List<String> {
        val results = syntheticList().searchAll<SyntheticTestData> {
            value.inRange(startInclusive, endExclusive)
        }
        val result = (results as RequestObject).requestChain.getResult().fold(
            onSuccess = { it as TList<*> },
            onFailure = { error("SearchAll(inRange) failed on proxy: $it") }
        )
        return result.toList().map { (it as SyntheticTestData).name.get() }
    }

    private fun syntheticList(): TList<SyntheticTestData> {
        val report = SyntheticTestData.Factory.create(testProxy.tContext, "Report", 1L)
        val budget = SyntheticTestData.Factory.create(testProxy.tContext, "Budget", 2L)
        val photo = SyntheticTestData.Factory.create(testProxy.tContext, "Photo", 3L)

        val listDto = listOf(report, budget, photo).toTList(testProxy.tContext)
        val chain = LocalRequestChain(testProxy.localTaskExecutor, testProxy.tContext)
            .addArray(listDto)

        return testProxy.tContext.roProvider().createRequestObject(
            type = TList.Schema.toType(),
            requestChain = chain,
            context = testProxy.tContext
        )
    }

    private suspend fun containsResults(
        list: TList<SyntheticTestData>,
        needle: String,
        ignoreCase: Boolean = false,
    ): List<String> {
        val results = list.searchAll<SyntheticTestData> {
            if (ignoreCase) {
                name.contains(needle.toTString(testProxy.tContext), ignoreCase = true)
            } else {
                name contains needle.toTString(testProxy.tContext)
            }
        }
        val result = (results as RequestObject).requestChain.getResult().fold(
            onSuccess = { it as TList<*> },
            onFailure = { error("SearchAll(contains) failed on proxy: $it") }
        )
        return result.toList().map { (it as SyntheticTestData).name.get() }
    }
}
