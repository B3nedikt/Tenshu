package org.tenshu.inference.proxy

import org.tenshu.inference.ModelProvenance

/**
 * The stores of a process that **does not own** this device's inference state: empty, and unable
 * to write (`ios-on-device-inference.md` §3.1, the fourth lock).
 *
 * ⚠⚠ Why they exist rather than a [DefaultEmbeddingStore] over an empty directory. On iOS route 1
 * every first-party app runs the proxy graph, and one of them owns the vectors. A real store in
 * any other process still writes: its constructor scrubs the log, and a realm wipe clears it. Both
 * would be a second process's writes to files the owner holds in memory. These hold no
 * [org.tenshu.core.common.proxy.FileDatabase] at all, so there is nothing for them to open.
 *
 * ⚠ A refused write is `false` or a no-op, never a throw: a wipe hook and an op factory resolve
 * these in every process, and neither may fail because this process is not the owner.
 */
object UnownedEmbeddingStore : EmbeddingStore {
    override suspend fun provenance(): ModelProvenance? = null
    override suspend fun size(): Int = 0
    override suspend fun put(filePath: String, vector: FloatArray, producedBy: ModelProvenance): Boolean = false
    override suspend fun visited(filePath: String): Boolean = false
    override suspend fun markVisited(filePath: String): Boolean = false
    override suspend fun remove(filePath: String) = Unit
    override suspend fun remembered(): Set<String> = emptySet()
    override suspend fun forget(paths: Collection<String>): Removal = Removal(removed = 0)
    override suspend fun entries(): List<Pair<String, FloatArray>> = emptyList()
    override suspend fun rebase(producedBy: ModelProvenance) = Unit
    override suspend fun clear() = Unit
}

/** [UnownedEmbeddingStore]'s twin for face templates, for the same reason. */
object UnownedFaceTemplateStore : FaceTemplateStore {
    override suspend fun put(filePath: String, faces: List<StoredFace>, producedBy: ModelProvenance): Boolean = false
    override suspend fun facesOf(filePath: String): List<StoredFace> = emptyList()
    override suspend fun rows(): List<FaceRow> = emptyList()
    override suspend fun visited(filePath: String): Boolean = false
    override suspend fun remove(filePath: String) = Unit
    override suspend fun remembered(): Set<String> = emptySet()
    override suspend fun forget(paths: Collection<String>): Removal = Removal(removed = 0)
    override suspend fun provenance(): ModelProvenance? = null
    override suspend fun size(): Int = 0
    override suspend fun clear() = Unit
    override suspend fun rejections(): List<FaceRejection> = emptyList()
    override suspend fun reject(rejections: List<FaceRejection>): Int = 0
}
