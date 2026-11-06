package org.tenshu.inference.proxy

import org.tenshu.inference.ModelProvenance
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The vector sink (IN-4b).
 *
 * ⚠⚠ Most of these pin **refusals**, because the failure they prevent is silent: a store holding
 * two models' vectors ranks them against each other perfectly happily and returns noise. There is
 * no exception, no wrong type and no value a caller could inspect — which is exactly why the check
 * has to be structural rather than a comment.
 */
class EmbeddingStoreTest {

    private val model = ModelProvenance("siglip", 1)
    private val otherModel = ModelProvenance("siglip", 2)

    @Test
    fun anEmptyStoreHasNoOwnerAndAcceptsTheFirstWriter() = runTest {
        val store = InMemoryEmbeddingStore()

        assertNull(store.provenance())
        assertTrue(store.put("/a.jpg", floatArrayOf(1f, 0f), model))
        assertEquals(model, store.provenance())
    }

    @Test
    fun aForeignStampIsRefusedRatherThanMixedIn() = runTest {
        val store = InMemoryEmbeddingStore()
        store.put("/a.jpg", floatArrayOf(1f, 0f), model)

        // ⚠⚠ The whole point. `otherModel` differs only in version, and a version bump is exactly
        // when a re-index is in flight — so this is the *likely* case, not the exotic one.
        assertFalse(store.put("/b.jpg", floatArrayOf(0f, 1f), otherModel))
        assertEquals(1, store.size())
    }

    @Test
    fun aChangedWidthIsRefused() = runTest {
        val store = InMemoryEmbeddingStore()
        store.put("/a.jpg", floatArrayOf(1f, 0f), model)

        // A model that keeps its id and changes its dimensionality would otherwise produce
        // truncated dot products — a similarity computed over the first N components of one vector.
        assertFalse(store.put("/b.jpg", floatArrayOf(0f, 1f, 0f), model))
    }

    @Test
    fun rebaseDropsEverythingRatherThanMigrating() = runTest {
        val store = InMemoryEmbeddingStore()
        store.put("/a.jpg", floatArrayOf(1f, 0f), model)

        store.rebase(otherModel)

        // ⚠ There is no conversion between two models' spaces — that is what a model *is* — so the
        // only honest response to a model change is to discard and re-derive.
        assertEquals(0, store.size())
        assertEquals(otherModel, store.provenance())
        assertTrue(store.put("/b.jpg", floatArrayOf(0f, 1f, 0f), otherModel), "and the width is free again")
    }

    @Test
    fun aSecondPutReplacesRatherThanDuplicates() = runTest {
        val store = InMemoryEmbeddingStore()
        store.put("/a.jpg", floatArrayOf(1f, 0f), model)
        store.put("/a.jpg", floatArrayOf(0f, 1f), model)

        assertEquals(1, store.size())
        assertEquals(listOf(0f, 1f), store.entries().first().second.toList())
    }

    @Test
    fun removeTakesAVectorOutOfTheScan() = runTest {
        val store = InMemoryEmbeddingStore()
        store.put("/a.jpg", floatArrayOf(1f, 0f), model)
        store.put("/b.jpg", floatArrayOf(0f, 1f), model)

        store.remove("/a.jpg")

        assertEquals(listOf("/b.jpg"), store.entries().map { it.first }.toList())
    }

    /**
     * ⚠⚠ What a realm reset owes. Vectors are keyed by file PATH, so one kept across a wipe answers a
     * search in the NEXT realm with the previous realm's photograph — and the store must forget its
     * model too, or the next realm's first vector is judged against an owner whose data is gone.
     */
    @Test
    fun clearDropsTheVectorsTheVisitsAndTheModel() = runTest {
        val store = InMemoryEmbeddingStore()
        store.put("/a.jpg", floatArrayOf(1f, 0f), model)
        store.markVisited("/b.raw")

        store.clear()

        assertEquals(0, store.size())
        assertFalse(store.visited("/a.jpg"))
        assertFalse(store.visited("/b.raw"), "a visit outliving the realm keeps a new photo out of the index")
        assertNull(store.provenance())
        // ⚠ And the cleared store accepts ANY model again — the point of forgetting the stamp.
        assertTrue(store.put("/a.jpg", floatArrayOf(0f, 1f), otherModel))
    }

    /**
     * ⚠⚠ A snapshot, never a live view. A search scans while the index pass writes, and iterating the
     * live map across a `put` is a `ConcurrentModificationException` on the JVM. A later write must
     * not reach a scan already holding its entries.
     */
    @Test
    fun entriesAreASnapshotThatALaterPutDoesNotReach() = runTest {
        for (store in listOf<EmbeddingStore>(InMemoryEmbeddingStore())) {
            store.put("/a.jpg", floatArrayOf(1f, 0f), model)
            val scan = store.entries()

            store.put("/b.jpg", floatArrayOf(0f, 1f), model)

            assertEquals(listOf("/a.jpg"), scan.map { it.first })
            assertEquals(2, store.entries().size)
        }
    }
}
