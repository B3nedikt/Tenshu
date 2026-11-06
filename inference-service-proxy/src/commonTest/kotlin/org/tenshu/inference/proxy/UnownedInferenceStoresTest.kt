package org.tenshu.inference.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.inference.ModelProvenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The fourth lock (`ios-on-device-inference.md` §3.1): a store that is not this process's. */
class UnownedInferenceStoresTest {

    private val model = ModelProvenance("siglip", 1)

    @Test
    fun theVectorStoreRefusesEveryWriteAndHoldsNothing() = runTest {
        val store: EmbeddingStore = UnownedEmbeddingStore
        assertFalse(store.put("/a.jpg", floatArrayOf(1f), model))
        assertFalse(store.markVisited("/a.jpg"))
        store.rebase(model)
        store.clear()
        store.remove("/a.jpg")
        assertEquals(0, store.forget(listOf("/a.jpg")).removed)

        assertEquals(0, store.size())
        assertNull(store.provenance(), "a rebase did not stamp it")
        assertFalse(store.visited("/a.jpg"))
        assertTrue(store.entries().isEmpty())
        assertTrue(store.remembered().isEmpty())
    }

    @Test
    fun theTemplateStoreRefusesEveryWriteAndHoldsNothing() = runTest {
        val store: FaceTemplateStore = UnownedFaceTemplateStore
        val face = StoredFace(FaceBox(0f, 0f, 0.1f, 0.1f), floatArrayOf(1f, 0f))
        assertFalse(store.put("/a.jpg", listOf(face), model))
        store.clear()
        store.remove("/a.jpg")
        assertEquals(0, store.forget(listOf("/a.jpg")).removed)

        assertEquals(0, store.size())
        assertNull(store.provenance())
        assertFalse(store.visited("/a.jpg"))
        assertTrue(store.rows().isEmpty())
        assertTrue(store.facesOf("/a.jpg").isEmpty())
        assertTrue(store.remembered().isEmpty())
    }
}
