package org.tenshu.core.common.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [readOrderedWindow] — the **proxy-side** bounded read
 * (`core-ordered-window-fusion.md` §6 F3, opened 2026-07-30).
 *
 * The chain fusion serves ops and clients; a `DataObserver` has no chain and would otherwise read
 * the whole relation unprojected. These pin that the direct entry point produces the *same* window
 * the fused chain path does — same ordering, same nulls-last, same stability — because two
 * implementations of one semantics is how they drift.
 */
class ReadOrderedWindowTest {

    private lateinit var testProxy: TestProxy

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
    }

    @Test
    fun returnsTheNewestNByTheKey() = runTest {
        val ref = populate(listOf("A" to 3L, "B" to 7L, "C" to 1L, "D" to 5L, "E" to 6L))

        val rows = testProxy.executionContext.readOrderedWindow(
            reference = ref,
            orderByFieldId = SyntheticTestData.Companion.Field.VALUE,
            limit = 3,
        )

        // Identical to the fused chain's `sortByDescending(value).take(3)` (OrderedWindowFusionTest).
        assertEquals(listOf("B", "E", "D"), names(rows))
    }

    @Test
    fun ascendingIsTheSameOrderReversed() = runTest {
        val ref = populate(listOf("A" to 3L, "B" to 7L, "C" to 1L, "D" to 5L, "E" to 6L))
        val rows = testProxy.executionContext.readOrderedWindow(
            reference = ref,
            orderByFieldId = SyntheticTestData.Companion.Field.VALUE,
            limit = 2,
            descending = false,
        )
        assertEquals(listOf("C", "A"), names(rows))
    }

    @Test
    fun equalKeysKeepEncounterOrder() = runTest {
        // Stability is what makes a bounded selection reproducible across devices.
        val ref = populate(listOf("first" to 1L, "second" to 1L, "third" to 1L, "fourth" to 1L))
        val rows = testProxy.executionContext.readOrderedWindow(
            reference = ref,
            orderByFieldId = SyntheticTestData.Companion.Field.VALUE,
            limit = 2,
        )
        assertEquals(listOf("first", "second"), names(rows))
    }

    @Test
    fun aWindowLargerThanTheRelationReturnsEverything() = runTest {
        val ref = populate(listOf("A" to 3L, "B" to 7L))
        assertEquals(2, testProxy.executionContext.readOrderedWindow(
            reference = ref,
            orderByFieldId = SyntheticTestData.Companion.Field.VALUE,
            limit = 100,
        ).size)
    }

    @Test
    fun aNonPositiveLimitReadsNothingAtAll() = runTest {
        // ⚠ The caller's guard, not a silent "everything": a bounded read that quietly degrades to
        // unbounded is precisely the failure this entry point exists to prevent.
        val ref = populate(listOf("A" to 3L, "B" to 7L))
        assertTrue(testProxy.executionContext.readOrderedWindow(
            reference = ref,
            orderByFieldId = SyntheticTestData.Companion.Field.VALUE,
            limit = 0,
        ).isEmpty())
    }

    @Test
    fun anEmptyRelationIsEmpty() = runTest {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = testProxy.tContext,
                type = TList.ID,
                relationId = "test/row-empty",
            ),
            isEntity = false,
            elementSchemaId = SyntheticTestData.ID,
        )
        assertTrue(testProxy.executionContext.readOrderedWindow(
            reference = ref,
            orderByFieldId = SyntheticTestData.Companion.Field.VALUE,
            limit = 5,
        ).isEmpty())
    }

    @Test
    fun theKeyFieldSurvivesTheThinReadEvenWhenNotAskedFor() = runTest {
        // The projection is the caller's, but the key must be added to it or the sort reads null.
        val ref = populate(listOf("A" to 3L, "B" to 7L, "C" to 1L))
        val rows = testProxy.executionContext.readOrderedWindow(
            reference = ref,
            orderByFieldId = SyntheticTestData.Companion.Field.VALUE,
            limit = 2,
            keepFields = setOf(SyntheticTestData.Companion.Field.NAME),
        )
        assertEquals(listOf("B", "A"), names(rows))
    }

    private fun populate(items: List<Pair<String, Long>>): ArrayDatabaseReference {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = testProxy.tContext,
                type = TList.ID,
                relationId = "test/row-array",
            ),
            isEntity = false,
            elementSchemaId = SyntheticTestData.ID,
        )
        items.forEach { (name, value) ->
            testProxy.database.addToArray(ref, SyntheticTestData.Factory.create(testProxy.tContext, name, value))
        }
        return ref
    }

    private fun names(rows: List<Data>): List<String> = rows.map { item ->
        when (item) {
            is SyntheticTestData -> item.name.get()
            is Entity<*> -> item.getFieldValue<TString>(SyntheticTestData.Companion.Field.NAME).get()
            else -> error("unexpected row $item")
        }
    }
}
