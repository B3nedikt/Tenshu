package org.tenshu.inference.proxy

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tenshu.inference.ModelProvenance

/**
 * **Where a photo's vector lives** (IN-4b, `inference-service.md` IN-D5/IN-D12) — device-local,
 * never synced, and the thing IN-3a deliberately left unbuilt.
 *
 * ⚠⚠ **Device-local is not a storage preference, it is the decision.** IN-D5 syncs labels and
 * refuses to sync embeddings: a label is a few bytes and useful everywhere the moment one device
 * derives it, while a vector is kilobytes per photo, meaningless without the model that produced
 * it, and re-derivable from bytes every device already has. Putting one in a `Syncable` row would
 * push megabytes per photo through the relay to save work that is local anyway — and would charge
 * it to the user's quota.
 *
 * ⚠⚠ **One store holds one model's vectors, and that is enforced rather than documented.** Cosine
 * between vectors from two different models is meaningless *and computable* — the arithmetic
 * succeeds, the number looks like a similarity, and the results are noise. There is no value a
 * caller could inspect to notice. [put] therefore refuses a foreign stamp, and [rebase] is the only
 * way the stamp moves: it drops everything, because a partially-migrated store is the mixed store
 * this exists to prevent.
 *
 * ⚠ Dimensionality is fixed by the first vector written and enforced the same way. A model swap
 * that keeps the id but changes the width would otherwise produce truncated dot products.
 *
 * ⚠⚠ **Every member suspends, because the store is shared.** The index pass writes while a search
 * scans — one singleton, two coroutines — and an unguarded `LinkedHashMap` read mid-`put` is a
 * `ConcurrentModificationException` on the JVM and a data race elsewhere. Implementations hold a
 * `Mutex`; there is no blocking lock in common code, and a scan is the only reader long enough to
 * matter.
 */
interface EmbeddingStore {

    /** Whose vectors these are, or `null` when the store is empty and will accept anyone's. */
    suspend fun provenance(): ModelProvenance?

    /** How many vectors are held — the number a caller checks before claiming search is available. */
    suspend fun size(): Int

    /**
     * Stores [vector] for [filePath], replacing any earlier one.
     *
     * @return `false` when [producedBy] is not this store's model, or the width disagrees. ⚠ A
     *   **return value, not an exception**: an indexing pass that meets a stale vector mid-library
     *   should count and continue, exactly as it does for an unreadable file.
     */
    suspend fun put(filePath: String, vector: FloatArray, producedBy: ModelProvenance): Boolean

    /**
     * Whether this device has already looked at [filePath] for the store's model — a vector is
     * held, or [markVisited] recorded that there was none to make.
     *
     * ⚠⚠ **The indexer's freshness check for vectors, and it must be this and not the synced
     * marker.** The pass's `MediaLabel` marker syncs (IN-D5); a vector does not. Judging vectors by
     * the marker made every device but the first — and this one after a reinstall — skip photos it
     * had never embedded (`inference-service.md` §32).
     */
    suspend fun visited(filePath: String): Boolean

    /**
     * Records that [filePath] was looked at and yielded **no** vector — a file this device cannot
     * decode — so the next pass does not read it again.
     *
     * ⚠⚠ Only once the store has a model: with no stamp yet there is nothing to say *whose* look it
     * was, and adopting a guessed one would make every later [put] a refusal. Returns `false` then,
     * and the file is simply looked at again.
     */
    suspend fun markVisited(filePath: String): Boolean

    suspend fun remove(filePath: String)

    /**
     * Every path this store remembers anything about — a vector **or** a [markVisited] record.
     *
     * ⚠⚠ **What a reconcile enumerates, and why it is not [entries]** (P0f-a, hard constraint 0).
     * [entries] returns only paths that hold a vector. A photo that could not be decoded is
     * remembered as a visit, and [visited] answers true for it — so a reconcile built on [entries]
     * could never see it, and whatever sat at that path later would be skipped for as long as the
     * model stayed: the same photo restored from trash, or a different file.
     */
    suspend fun remembered(): Set<String>

    /**
     * Forget every path in [paths] — its vector **and** its [markVisited] record. What an index
     * pass's reconcile calls with the paths it confirmed dead (P0f-a,
     * `media-processing-pipeline-sweep.md` F0); [remove] is the same thing for one path.
     *
     * ⚠⚠ **Gone from the disk, not just from the map.** A durable implementation overwrites the
     * dropped rows' bytes in its log rather than hiding them behind a tombstone.
     *
     * @return what happened, whole: rows removed, rows refused, and bytes not yet erased. ⚠ Never
     *   thrown piecemeal — a batch can do all three, and the caller must be able to see each.
     */
    suspend fun forget(paths: Collection<String>): Removal

    /**
     * Every `(path, vector)` held, as a **snapshot**.
     *
     * ⚠⚠ A snapshot, never a live view: the caller is a scan that runs while the index pass may be
     * writing, and iterating the live map across a `put` is the crash this interface suspends to
     * prevent. ⚠ It copies references, not vectors — a vector is never mutated once stored, only
     * replaced — so 20 000 photos cost 20 000 pairs, not ~40 MB.
     */
    suspend fun entries(): List<Pair<String, FloatArray>>

    /**
     * Drops **everything** and adopts [producedBy].
     *
     * ⚠⚠ Not a migration. There is no way to convert a vector from one model's space into another's
     * — that is what a model *is* — so the only honest response to a model change is to discard and
     * re-derive. Naming it `rebase` rather than `clear` is deliberate: the caller is stating which
     * model the store now belongs to, and forgetting to say would leave it ownerless.
     */
    suspend fun rebase(producedBy: ModelProvenance)

    /**
     * Drops everything and forgets which model the store belonged to — **what a realm reset owes**.
     *
     * ⚠⚠ Not [rebase]: that states the store's new owner and is for a model change. This is for the
     * realm going away. Vectors are keyed by file PATH, and a path means nothing across realms: the
     * next realm's `/Photos/IMG_0001.jpg` is a different photograph, so a vector kept across a wipe
     * is a wrong answer to a search, and a `visited` mark keeps the new photo out of the index.
     */
    suspend fun clear()
}

/**
 * The in-memory [EmbeddingStore] — the test double, and the honest wiring for a build with no
 * durable path yet.
 *
 * ⚠ Named for what it is, following `InMemoryModelStore`: a `DefaultEmbeddingStore` that quietly
 * forgot every vector on restart would be discovered as "indexing runs from scratch every launch",
 * hours of battery at a time.
 */
class InMemoryEmbeddingStore : EmbeddingStore {

    private val lock = Mutex()
    private val vectors = LinkedHashMap<String, FloatArray>()
    private val withoutVector = mutableSetOf<String>()
    private var stamp: ModelProvenance? = null

    override suspend fun provenance(): ModelProvenance? = lock.withLock { stamp }

    override suspend fun size(): Int = lock.withLock { vectors.size }

    override suspend fun put(filePath: String, vector: FloatArray, producedBy: ModelProvenance): Boolean =
        lock.withLock {
            val current = stamp
            if (current != null && current != producedBy) return@withLock false
            // ⚠ Width is checked against what is already held, not against a constructor argument:
            // the first vector defines the store, so an empty store accepts any model and a populated
            // one accepts only its own shape.
            val width = vectors.values.firstOrNull()?.size
            if (width != null && width != vector.size) return@withLock false
            stamp = producedBy
            vectors[filePath] = vector
            withoutVector.remove(filePath)
            true
        }

    override suspend fun visited(filePath: String): Boolean =
        lock.withLock { filePath in vectors || filePath in withoutVector }

    override suspend fun markVisited(filePath: String): Boolean = lock.withLock {
        if (stamp == null) return@withLock false
        vectors.remove(filePath)
        withoutVector += filePath
        true
    }

    override suspend fun remove(filePath: String) {
        lock.withLock {
            vectors.remove(filePath)
            withoutVector.remove(filePath)
        }
    }

    override suspend fun remembered(): Set<String> = lock.withLock { vectors.keys + withoutVector }

    override suspend fun forget(paths: Collection<String>): Removal = lock.withLock {
        Removal(
            removed = paths.count { path ->
                val hadVector = vectors.remove(path) != null
                val hadVisit = withoutVector.remove(path)
                hadVector || hadVisit
            },
        )
    }

    override suspend fun entries(): List<Pair<String, FloatArray>> =
        lock.withLock { vectors.entries.map { it.key to it.value } }

    override suspend fun rebase(producedBy: ModelProvenance) {
        lock.withLock {
            vectors.clear()
            withoutVector.clear()
            stamp = producedBy
        }
    }

    override suspend fun clear() {
        lock.withLock {
            vectors.clear()
            withoutVector.clear()
            stamp = null
        }
    }
}
