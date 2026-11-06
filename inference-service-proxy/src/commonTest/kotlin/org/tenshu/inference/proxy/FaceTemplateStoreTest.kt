package org.tenshu.inference.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.inference.ModelProvenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The device-local face template store (IN-6 F1, IN-D5).
 *
 * ⚠⚠ Every claim here is about **custody**, not storage: these vectors are the biometric artifact,
 * and `photos-people-and-pets.md` §8's threat-model entry says they never leave the device. The
 * tests that matter are the ones about refusing a foreign stamp and about clearing.
 */
class FaceTemplateStoreTest {

    private val model = ModelProvenance("face", 1)
    private val otherModel = ModelProvenance("face", 2)

    private fun face(vararg values: Float) =
        StoredFace(FaceBox(0f, 0f, 0.1f, 0.1f), floatArrayOf(*values))

    @Test
    fun facesSurviveAPutAndComeBackInOrder() = runTest {
        val store = InMemoryFaceTemplateStore()

        store.put("/a.jpg", listOf(face(1f, 0f), face(0f, 1f)), model)

        assertEquals(listOf(face(1f, 0f), face(0f, 1f)), store.facesOf("/a.jpg"))
        assertEquals(2, store.size())
        assertEquals(model, store.provenance())
    }

    /**
     * ⚠⚠ **The majority case, and the one that must be recordable.** Most photographs contain no
     * face. A store that could not tell "looked, found none" from "never looked" would re-read the
     * whole library on every pass, for ever — §32.3's defect in a new place.
     */
    @Test
    fun aPhotographWithNoFacesIsVisitedAndHoldsNothing() = runTest {
        val store = InMemoryFaceTemplateStore()

        store.put("/landscape.jpg", emptyList(), model)

        assertTrue(store.visited("/landscape.jpg"), "looked at it, and that is a fact worth keeping")
        assertEquals(emptyList(), store.facesOf("/landscape.jpg"))
        assertEquals(0, store.size())
    }

    @Test
    fun aPhotographNeverSeenIsNotVisited() = runTest {
        assertFalse(InMemoryFaceTemplateStore().visited("/unseen.jpg"))
    }

    /**
     * ⚠⚠ One store holds one model's vectors. Templates from two face models are not comparable, so
     * mixing them would give a People grid that silently splits everyone in two.
     */
    @Test
    fun aForeignStampIsRefusedRatherThanMixed() = runTest {
        val store = InMemoryFaceTemplateStore()
        store.put("/a.jpg", listOf(face(1f, 0f)), model)

        assertFalse(store.put("/b.jpg", listOf(face(0f, 1f)), otherModel))

        assertEquals(emptyList(), store.facesOf("/b.jpg"))
        assertEquals(model, store.provenance(), "and the stamp is unchanged")
    }

    /** ⚠ A second model wearing the first one's stamp shows up as a width mismatch, and is refused. */
    @Test
    fun aVectorOfTheWrongWidthIsRefused() = runTest {
        val store = InMemoryFaceTemplateStore()
        store.put("/a.jpg", listOf(face(1f, 0f)), model)

        assertFalse(store.put("/b.jpg", listOf(face(1f, 0f, 0f)), model))

        assertEquals(1, store.size())
    }

    @Test
    fun rowsAreASnapshotOfEveryPhotographLookedAt() = runTest {
        val store = InMemoryFaceTemplateStore()
        store.put("/a.jpg", listOf(face(1f, 0f)), model)
        store.put("/empty.jpg", emptyList(), model)

        val rows = store.rows()

        assertEquals(setOf("/a.jpg", "/empty.jpg"), rows.map { it.filePath }.toSet())
        assertTrue(rows.all { it.producedBy == model })
    }

    @Test
    fun removingAPhotographForgetsItEntirely() = runTest {
        val store = InMemoryFaceTemplateStore()
        store.put("/a.jpg", listOf(face(1f, 0f)), model)

        store.remove("/a.jpg")

        assertFalse(store.visited("/a.jpg"), "a removed photograph must be looked at again")
        assertEquals(0, store.size())
    }

    /**
     * ⚠⚠ What a realm reset calls. Templates are keyed by file **path**, so a store that outlived a
     * wipe would answer the next realm's photographs with the previous realm's faces.
     */
    @Test
    fun clearDropsTheTemplatesTheVisitsAndTheModel() = runTest {
        val store = InMemoryFaceTemplateStore()
        store.put("/a.jpg", listOf(face(1f, 0f)), model)
        store.put("/empty.jpg", emptyList(), model)

        store.clear()

        assertEquals(0, store.size())
        assertFalse(store.visited("/a.jpg"))
        assertFalse(store.visited("/empty.jpg"), "a visit is a claim about vectors that are gone")
        assertNull(store.provenance())
        assertEquals(emptyList(), store.rows())
    }

    /** ⚠ And a cleared store takes a new model without complaint — it is empty, so nothing clashes. */
    @Test
    fun aClearedStoreAcceptsADifferentModel() = runTest {
        val store = InMemoryFaceTemplateStore()
        store.put("/a.jpg", listOf(face(1f, 0f)), model)
        store.clear()

        assertTrue(store.put("/b.jpg", listOf(face(1f, 0f, 0f)), otherModel))
        assertEquals(otherModel, store.provenance())
    }
}
