package org.tenshu.inference.proxy

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tenshu.inference.ModelProvenance

/**
 * **Where face templates live, and the only place they live** (IN-D5, IN-6).
 *
 * ⚠⚠ **Device-local is not a storage preference, it is the decision** — the same sentence
 * [EmbeddingStore] carries, and here it is load-bearing twice over. A face template is the biometric
 * artifact; putting one on the wire is what IN-D5's per-photo-label design exists to avoid, and what
 * `photos-people-and-pets.md` §8's threat-model entry will say we do not do. Nothing in
 * `InferenceService`'s schema can hold one, so this is not a rule a future PR can break by accident
 * — it would have to add a type.
 *
 * ⚠⚠ **One store holds one model's vectors.** Templates from two face models are not comparable, so
 * [put] refuses a foreign stamp rather than mixing spaces; changing the model means starting over.
 * `EmbeddingStore` learned this the same way and enforces it the same way.
 *
 * ⚠⚠ **Freshness is this store's own answer, never a synced marker.** §32.3 is the scar: a
 * `MediaLabel` marker syncs, so a second device inherits "already looked at" for vectors it does not
 * have and never embeds anything. [visited] answers from here, and it answers **true for a
 * photograph with no faces in it** — which is most of them, and the reason the negative case must be
 * recordable at all.
 */
interface FaceTemplateStore {

    /**
     * Record what one photograph contributed — possibly nothing.
     *
     * @return `false` when [producedBy] does not match the store's stamp, so the caller can stop
     *   rather than write vectors that will never be compared against.
     */
    suspend fun put(filePath: String, faces: List<StoredFace>, producedBy: ModelProvenance): Boolean

    /** The faces recorded for one photograph; empty for a photograph with none, or one never seen. */
    suspend fun facesOf(filePath: String): List<StoredFace>

    /** ⚠ A snapshot, not a view: clustering walks every face and must not race a running pass. */
    suspend fun rows(): List<FaceRow>

    /** Whether this photograph has been looked at by the current model — see the class KDoc. */
    suspend fun visited(filePath: String): Boolean

    /** Forget one photograph — it was deleted, or its bytes changed under the same path. */
    suspend fun remove(filePath: String)

    /**
     * Every photograph this store remembers — with faces or with none. What a reconcile enumerates
     * (P0f-a, hard constraint 0): an empty row is a visit, and a visit must not outlive its file.
     */
    suspend fun remembered(): Set<String>

    /**
     * Forget every photograph in [paths] — what an index pass's reconcile calls with the paths it
     * confirmed dead (P0f-a, `media-processing-pipeline-sweep.md` F0); [remove] is the same thing
     * for one path.
     *
     * ⚠⚠ **Gone from the disk, not just from the map.** A removal that only hid a template behind a
     * tombstone would leave its floats in the file, and for a biometric store that is a deletion
     * that did not happen. A durable implementation overwrites the dropped rows' bytes in its log.
     *
     * @return what happened, whole: rows removed, rows refused, and bytes not yet erased. ⚠ Never
     *   thrown piecemeal — a batch can do all three, and the caller must be able to see each.
     */
    suspend fun forget(paths: Collection<String>): Removal

    /** The model whose space this store holds, or `null` when it is empty. */
    suspend fun provenance(): ModelProvenance?

    /** How many faces are held, across every photograph. */
    suspend fun size(): Int

    /**
     * Drop everything — every template, every visit, and the stamp.
     *
     * ⚠⚠ What a local-store wipe calls. Templates are keyed by file **path**, so a store that
     * outlived a realm reset would answer the next realm's photographs with the previous realm's
     * faces — the same defect `EmbeddingStore.clear` exists for, and worse in kind.
     */
    suspend fun clear()

    /**
     * Every "this face is not X" the user said on this device (IN-6 F6c name suggestions).
     *
     * ⚠⚠ **Here, on the template store, on purpose** — see [FaceRejections]: [clear] drops them and
     * [forget]/[remove] drop a photograph's, so they leave by every exit the templates leave by.
     */
    suspend fun rejections(): List<FaceRejection>

    /**
     * Records [rejections] — ⚠ only those on photographs this store holds templates for, so a
     * rejection never outlives the row whose erasure is what deletes it.
     * @return how many were new.
     */
    suspend fun reject(rejections: List<FaceRejection>): Int
}

/**
 * The F1 implementation: in memory, and deliberately so.
 *
 * ⚠⚠ **There is no durable face store yet because there is no face model yet.** F1 ships the seam
 * against `FakeInferenceBackend` (`photos-people-and-pets.md` §6), so nothing here is a vector worth
 * persisting; F2 gives it the append-only log `DefaultEmbeddingStore` already implements, at the
 * point where losing a pass would cost real work. Shipping durability first would be a format
 * decided before a single real template existed.
 */
class InMemoryFaceTemplateStore : FaceTemplateStore {

    private val lock = Mutex()
    private val rows = mutableMapOf<String, List<StoredFace>>()
    private var stamp: ModelProvenance? = null
    private val rejected = FaceRejections(file = null, path = null)

    override suspend fun put(
        filePath: String,
        faces: List<StoredFace>,
        producedBy: ModelProvenance,
    ): Boolean = lock.withLock {
        val current = stamp
        if (current != null && current != producedBy) return@withLock false
        // ⚠ The width check `EmbeddingStore` makes, for the same reason: one model, one dimension.
        // A mismatch here is a second model wearing the first one's stamp.
        val width = rows.values.firstNotNullOfOrNull { it.firstOrNull() }?.vector?.size
        if (width != null && faces.any { it.vector.size != width }) return@withLock false
        if (current == null) stamp = producedBy
        rows[filePath] = faces
        true
    }

    override suspend fun facesOf(filePath: String): List<StoredFace> =
        lock.withLock { rows[filePath].orEmpty() }

    override suspend fun rows(): List<FaceRow> = lock.withLock {
        val current = stamp ?: return@withLock emptyList()
        rows.map { (path, faces) -> FaceRow(path, faces, current) }
    }

    override suspend fun visited(filePath: String): Boolean = lock.withLock { filePath in rows }

    // ⚠ The durable store's orders, kept here too: rejections before rows, and only beside a row.
    override suspend fun remove(filePath: String) {
        lock.withLock {
            rejected.forget(listOf(filePath))
            rows.remove(filePath)
        }
    }

    override suspend fun remembered(): Set<String> = lock.withLock { rows.keys.toSet() }

    override suspend fun forget(paths: Collection<String>): Removal = lock.withLock {
        rejected.forget(paths)
        Removal(removed = paths.count { rows.remove(it) != null })
    }

    override suspend fun provenance(): ModelProvenance? = lock.withLock { stamp }

    override suspend fun size(): Int = lock.withLock { rows.values.sumOf { it.size } }

    override suspend fun clear() {
        lock.withLock {
            rejected.clear()
            rows.clear()
            stamp = null
        }
    }

    override suspend fun rejections(): List<FaceRejection> = rejected.all()

    override suspend fun reject(rejections: List<FaceRejection>): Int = lock.withLock {
        if (stamp == null) return@withLock 0
        rejected.add(rejections.filter { it.filePath in rows })
    }
}
