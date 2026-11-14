package org.tenshu.inference.proxy

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.tenshu.core.common.proxy.FileDatabase

/**
 * One "this face is not [name]" (IN-6 F6c name suggestions) — the proxy's half of
 * `org.tenshu.inference.RejectedFace`. A position and a typed name: **no vector, no score, no model
 * stamp** (`photos-people-and-pets.md` §6a F6c.1's map, `REJECTED` kind only).
 */
@Serializable
data class FaceRejection(
    val filePath: String,
    val name: String,
    val leftPerMille: Int,
    val topPerMille: Int,
    val rightPerMille: Int,
    val bottomPerMille: Int,
)

/**
 * The device-local rejections a [FaceTemplateStore] keeps beside its templates.
 *
 * ⚠⚠ **Held BY the template store, so it shares every one of the templates' exits** — a local
 * store wipe, a realm change, an explicit consent withdrawal, a model or pipeline rebase all call
 * `FaceTemplateStore.clear`, and a photograph that leaves the store (`forget`/`remove`) takes its
 * rejections with it. A separate store would need each of those call sites taught about it, and
 * the one forgotten would keep "this face is not Anna" on disk after the user deleted their face
 * data (threat model A40 *Name suggestions*).
 * ⚠ So a rebase costs the rejections too — unlike F6c.1's full map, which survives one. The price
 * is one repeated question per rejected person after a re-index, in the safe direction.
 *
 * ⚠ Small and bounded ([MAX_RECORDS], newest kept), so the whole set is one JSON file rewritten per
 * change. A rewrite replaces the file, so a dropped record leaves no bytes behind in it.
 *
 * @param file `null` keeps them in memory only (the in-memory template store).
 */
class FaceRejections(
    private val file: FileDatabase?,
    private val path: String?,
) {
    private val lock = Mutex()

    /** The records as last read or written. ⚠ `null` = not known: never cached from a failed read. */
    private var held: List<FaceRejection>? = null

    /**
     * Whether a rejections file may exist on disk. ⚠ Tracked, so a store that never recorded a "No"
     * costs its template log's removals and wipes NO extra I/O — the template store's crash-point
     * sweeps count every call, and a stat per removal on a file that was never made is pure cost.
     * ⚠ `true` whenever it is not KNOWN to be absent (a stat that threw included): a wipe that
     * skipped a file it could not see would keep a name and a box after a withdrawal.
     */
    private var onDisk = false

    init {
        // ⚠ Read once, at construction — as the template log is — so later exits ask memory. A
        // failure here is not cached: the next use reads again.
        runCatching { loaded() }
    }

    /**
     * Every rejection held.
     * @throws RejectionsUnreadable when the file exists and cannot be read or parsed. ⚠⚠ Never an
     *   empty list instead: "nothing was rejected" would fold and ask about every answered No again.
     */
    suspend fun all(): List<FaceRejection> = lock.withLock { loaded() }

    /**
     * Records [rejections], de-duplicated, keeping the newest [MAX_RECORDS].
     * @return how many of them the store now holds that it did not before.
     * @throws RejectionsUnreadable ⚠⚠ rather than write: a write over a file it could not read would
     *   replace every answer in it with this one.
     */
    suspend fun add(rejections: List<FaceRejection>): Int = lock.withLock {
        val current = loaded()
        val known = current.toHashSet()
        val fresh = rejections.distinct().filter { it !in known }
        if (fresh.isEmpty()) return@withLock 0
        val next = (current + fresh).takeLast(MAX_RECORDS)
        persist(next)
        fresh.count { it in next }
    }

    /**
     * Drops every rejection on these photographs — they are leaving the template store.
     *
     * ⚠⚠ An unreadable file is ERASED whole rather than left: these photographs' rejections are in
     * it and cannot be picked out, and a deleted photograph's "not Anna" must not outlive it (A40).
     * The cost is every other answer on this device — repeated questions, the safe direction.
     */
    suspend fun forget(paths: Collection<String>) = lock.withLock {
        val gone = paths.toHashSet()
        val current = try {
            loaded()
        } catch (unreadable: RejectionsUnreadable) {
            erase()
            return@withLock
        }
        if (current.none { it.filePath in gone }) return@withLock
        persist(current.filterNot { it.filePath in gone })
    }

    /**
     * Drops everything, the file included, readable or not. ⚠ Overwritten before deleted: see
     * `DefaultFaceTemplateStore.clear`.
     */
    suspend fun clear() = lock.withLock { erase() }

    private fun erase() {
        val db = file
        val at = path
        if (db != null && at != null && onDisk) {
            db.replaceConfirmed(at, ByteArray(0))
            db.deleteFile(at)
            onDisk = false
        }
        held = emptyList()
    }

    private fun loaded(): List<FaceRejection> {
        held?.let { return it }
        val db = file
        val at = path
        if (db == null || at == null) return emptyList<FaceRejection>().also { held = it }
        val size = try {
            db.fileSize(at)
        } catch (failure: Throwable) {
            onDisk = true
            throw RejectionsUnreadable(at, failure)
        }
        // ⚠ An empty file still exists, and a wipe must still remove it.
        if (size != null) onDisk = true
        if (size == null || size <= 0L) return emptyList<FaceRejection>().also { held = it }
        val read = try {
            val bytes = db.readBytesRange(at, 0, size.toInt()) ?: error("$at vanished while read")
            json.decodeFromString(serializer, bytes.decodeToString())
        } catch (failure: Throwable) {
            throw RejectionsUnreadable(at, failure)
        }
        held = read
        return read
    }

    private fun persist(next: List<FaceRejection>) {
        val db = file
        val at = path
        if (db != null && at != null) {
            onDisk = true
            try {
                db.replaceConfirmed(at, json.encodeToString(serializer, next).encodeToByteArray())
            } catch (failure: Throwable) {
                // ⚠ A write that threw may have landed whole, in part or not at all: what the file
                // holds is unknown, so the next use reads it rather than trusting memory.
                held = null
                throw failure
            }
        }
        held = next
    }

    companion object {
        /** ⚠ A bound on the file and on one read's IPC reply, not a product number. */
        const val MAX_RECORDS: Int = 2_048

        private val serializer = ListSerializer(FaceRejection.serializer())
        private val json = Json { ignoreUnknownKeys = true }

        /** Where a durable template store at [templatesPath] keeps its rejections. */
        fun pathBeside(templatesPath: String): String = "$templatesPath.rejections"
    }
}

/** The rejections file exists and could not be read or parsed — see [FaceRejections.all]. */
class RejectionsUnreadable(path: String, cause: Throwable) :
    RuntimeException("the face rejections at $path could not be read", cause)
