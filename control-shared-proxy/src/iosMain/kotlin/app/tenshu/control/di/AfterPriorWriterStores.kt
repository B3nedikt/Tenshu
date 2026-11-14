package app.tenshu.control.di

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tenshu.inference.ModelProvenance
import org.tenshu.inference.proxy.EmbeddingStore
import org.tenshu.inference.proxy.FaceRow
import org.tenshu.inference.proxy.FaceRejection
import org.tenshu.inference.proxy.FaceTemplateStore
import org.tenshu.inference.proxy.Removal
import org.tenshu.inference.proxy.StoredFace
import kotlin.concurrent.Volatile

/**
 * ⚠⚠ `ios-shared-store-sqlite.md` B.16 (247): **a store that does not read its log until a
 * stopped graph's writer has ended**. `DefaultEmbeddingStore` and
 * `DefaultFaceTemplateStore` read their log and cache its append offset as they are constructed,
 * and every path that resolves them (the custody, the index runner and its indexer, the query
 * backend, the Models screen's coverage, `ForegroundIndexSource.runnerStanding`) would construct
 * them at once. Behind this wrapper an early resolve is **harmless by construction**: nothing is
 * built, and so nothing is read, until the first call, which first waits for
 * [OwnedInferenceStores.holdOpensUntil]'s writer ([OwnedInferenceStores.awaitPriorWriter], no
 * deadline). A caller meanwhile suspends: it never crashes and never blocks a thread, and the
 * custody stays unusable, so on-device AI reports not-ready.
 */
class AfterPriorWriterEmbeddingStore(private val make: () -> EmbeddingStore) : EmbeddingStore {
    private val building = Mutex()

    @Volatile
    private var made: EmbeddingStore? = null

    /** Whether the real store has been built, so its log read (the tests' window). */
    val isBuilt: Boolean get() = made != null

    /** The real store, once built (the wiring tests). */
    val builtOrNull: EmbeddingStore? get() = made

    private suspend fun store(): EmbeddingStore {
        made?.let { return it }
        OwnedInferenceStores.awaitPriorWriter()
        return building.withLock { made ?: make().also { made = it } }
    }

    override suspend fun provenance(): ModelProvenance? = store().provenance()
    override suspend fun size(): Int = store().size()
    override suspend fun put(filePath: String, vector: FloatArray, producedBy: ModelProvenance): Boolean = store().put(filePath, vector, producedBy)
    override suspend fun visited(filePath: String): Boolean = store().visited(filePath)
    override suspend fun markVisited(filePath: String): Boolean = store().markVisited(filePath)
    override suspend fun remove(filePath: String) = store().remove(filePath)
    override suspend fun remembered(): Set<String> = store().remembered()
    override suspend fun forget(paths: Collection<String>): Removal = store().forget(paths)
    override suspend fun entries(): List<Pair<String, FloatArray>> = store().entries()
    override suspend fun rebase(producedBy: ModelProvenance) = store().rebase(producedBy)
    override suspend fun clear() = store().clear()
}

/** [AfterPriorWriterEmbeddingStore]'s twin for the face templates (B.16 (247)). */
class AfterPriorWriterFaceTemplateStore(private val make: () -> FaceTemplateStore) : FaceTemplateStore {
    private val building = Mutex()

    @Volatile
    private var made: FaceTemplateStore? = null

    /** Whether the real store has been built, so its log read (the tests' window). */
    val isBuilt: Boolean get() = made != null

    /** The real store, once built (the wiring tests). */
    val builtOrNull: FaceTemplateStore? get() = made

    private suspend fun store(): FaceTemplateStore {
        made?.let { return it }
        OwnedInferenceStores.awaitPriorWriter()
        return building.withLock { made ?: make().also { made = it } }
    }

    override suspend fun put(filePath: String, faces: List<StoredFace>, producedBy: ModelProvenance): Boolean = store().put(filePath, faces, producedBy)
    override suspend fun facesOf(filePath: String): List<StoredFace> = store().facesOf(filePath)
    override suspend fun rows(): List<FaceRow> = store().rows()
    override suspend fun visited(filePath: String): Boolean = store().visited(filePath)
    override suspend fun remove(filePath: String) = store().remove(filePath)
    override suspend fun remembered(): Set<String> = store().remembered()
    override suspend fun forget(paths: Collection<String>): Removal = store().forget(paths)
    override suspend fun provenance(): ModelProvenance? = store().provenance()
    override suspend fun size(): Int = store().size()
    override suspend fun clear() = store().clear()
    override suspend fun rejections(): List<FaceRejection> = store().rejections()
    override suspend fun reject(rejections: List<FaceRejection>): Int = store().reject(rejections)
}
