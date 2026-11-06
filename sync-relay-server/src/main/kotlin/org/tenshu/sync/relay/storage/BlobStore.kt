package org.tenshu.sync.relay.storage

import java.io.File

/**
 * Opaque large-object storage by string key — the durable home for the content plane's
 * chunk bodies and (Phase 3b) share chunks (`docs/proposals/sync-relay-persistence.md`).
 *
 * Multi-MB ciphertext doesn't belong in the SQL row, so the SQLite stores keep a small
 * index row and put the bytes here. SPI so the v1 [FileBlobStore] (local volume,
 * single-node) can be swapped for an S3-compatible backend when storage decouples from
 * compute — no caller change. Values are opaque ciphertext; the relay never decrypts
 * (House Rule #1).
 */
interface BlobStore {
    /** Store [bytes] under [key], overwriting. (Content is immutable + content-addressed.) */
    fun put(key: String, bytes: ByteArray)

    /** Read the bytes under [key], or null if absent. */
    fun get(key: String): ByteArray?

    /** Whether [key] is present — cheap (no read), for first-write-wins / orphan checks. */
    fun exists(key: String): Boolean

    /**
     * Stored byte length of [key], or null if absent. The default reads the blob; a backend
     * overrides it when it can answer without one (file length, S3 `HEAD`).
     */
    fun sizeOf(key: String): Long? = get(key)?.size?.toLong()

    /** Remove [key]. Idempotent — a no-op if absent. */
    fun delete(key: String)

    /**
     * Remove every key in [keys]. Idempotent, and absent keys are not an error — the same contract
     * as [delete], because every caller is a GC re-running over rows whose bytes may already be
     * gone.
     *
     * ⚠⚠ **This exists because a loop over [delete] is not equivalent once the store is remote.**
     * The four callers (`gcSweep`, `reclaimOwner`, `dropRefs`, the share GC) were written when
     * deleting a blob was a local `File.delete()` — microseconds — so a serial loop was free.
     * Against [S3BlobStore] each iteration became an HTTP round-trip inside a request handler:
     * `POST /sync/content/delete` caps at 512 refs (~10 s at 20 ms RTT), and reclaiming a 2 TiB
     * owner is ~1M round-trips, hours. Same class as the serial chunk `PUT`s that
     * `content-plane-transfer-concurrency.md` Ph1 fixed for uploads; nobody had done it for deletes
     * because deletes used to be free.
     *
     * The default implementation *is* that loop, so [FileBlobStore] keeps its exact behaviour and
     * a backend only overrides this when it has a batch API worth using.
     */
    fun deleteAll(keys: Collection<String>) {
        keys.forEach(::delete)
    }

    /**
     * Prove the backing store still **accepts a write**, throwing if it does not. Leaves nothing
     * behind.
     *
     * ⚠⚠ A write, specifically — a read would have been useless. On 2026-08-14 this volume filled
     * and the relay answered 500 to every push while serving reads perfectly and reporting `200` on
     * `GET /`. Nothing that only *reads* could have told the two states apart, which is why the
     * commerce-side `SELECT 1` is the wrong shape here (`drive-upload-stop-and-reclamation.md` §4.6).
     */
    fun probe()

    /** Remove every object whose key starts with [prefix]. */
    fun deleteByPrefix(prefix: String) {}
}

/**
 * Filesystem [BlobStore] — one file per key under [root]. The key (`<ownerId>/<ref>`) is
 * base64url-encoded into a single flat filename so arbitrary key bytes are filesystem-safe
 * and collision-free; GC works off the SQL index, so the store is never *listed* by owner.
 */
class FileBlobStore(val root: File) : BlobStore {

    init {
        root.mkdirs()
    }

    private fun fileFor(key: String): File =
        File(root, java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key.encodeToByteArray()))

    override fun deleteByPrefix(prefix: String) {
        root.listFiles()?.forEach { file ->
            try {
                val decoded = java.util.Base64.getUrlDecoder().decode(file.name).decodeToString()
                if (decoded.startsWith(prefix)) {
                    file.delete()
                }
            } catch (_: Exception) {}
        }
    }

    override fun put(key: String, bytes: ByteArray) {
        // Write to a temp sibling then atomic-rename, so a concurrent/crashing write never
        // exposes a partial blob to a reader.
        //
        // ⚠⚠ The `finally` is not tidiness. `writeBytes` throws when the volume is full, and
        // without cleanup each failed write leaves its empty `.tmp` behind — while the client
        // retries forever, so the leak is one file per attempt. Production accumulated **8,682**
        // stale `.tmp` files in under two hours that way (2026-08-14). They were only 740 KB, so
        // they did not cause the fill, but a store that leaks under the one condition it is most
        // likely to meet will exhaust inodes long before bytes.
        val target = fileFor(key)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                // renameTo can fail if the target exists on some filesystems; overwrite + retry.
                target.delete()
                if (!tmp.renameTo(target)) {
                    target.writeBytes(bytes)
                }
            }
        } finally {
            // A no-op on the success path: the rename consumed it.
            tmp.delete()
        }
    }

    override fun get(key: String): ByteArray? = fileFor(key).let { if (it.exists()) it.readBytes() else null }

    override fun exists(key: String): Boolean = fileFor(key).exists()

    override fun sizeOf(key: String): Long? = fileFor(key).let { if (it.exists()) it.length() else null }

    override fun delete(key: String) {
        fileFor(key).delete()
    }

    /**
     * Deliberately **not** routed through [put]: that would share one `<key>.tmp` name between
     * concurrent probes, so two monitors could fight over the same temp file. [VolumeWriteProbe]
     * carries the rest of the rationale — it is shared with the database-volume probe, which
     * `/health` needs once [S3BlobStore] moves the blobs off that volume.
     */
    override fun probe() = VolumeWriteProbe(root).probe()
}
