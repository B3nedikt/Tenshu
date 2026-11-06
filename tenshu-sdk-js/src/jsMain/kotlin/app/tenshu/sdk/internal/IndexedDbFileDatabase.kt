package app.tenshu.sdk.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.tenshu.core.TByteArray
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.common.proxy.FileDatabase
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// IndexedDB-backed `FileDatabase` for the JS runtime — stores raw file
// bytes keyed by path so `UploadFile` / `GetData` round-trip across page
// reloads.
//
// Same cache-aside pattern as `IndexedDbObjectStore` (slice 9): suspend
// `open(...)` reads every entry into an in-memory `MutableMap<String, ByteArray>`
// at runtime startup; `JsRuntime` exposes the open as a `Deferred` and
// awaits it inside `awaitReady()` along with the protocol-level
// `IndexedDbObjectStore`.
//
// Lives in a separate IDB database (`tenshu-sdk-files`) from the protocol
// store (`tenshu-sdk`) — keeps the slice-9 schema version untouched and
// avoids a version-bump dance to add a second object store. The two stores
// don't share transactions, but `UploadFile` doesn't need atomicity across
// metadata + bytes (the file metadata write happens via the protocol
// `Database`; the byte write is a separate fire-and-forget IDB put). Worst
// case on a tab-close mid-upload: metadata exists, bytes are missing.
// Acceptable for the in-process runtime today; a tighter slice could await
// the byte write before the metadata commit.
internal class IndexedDbFileDatabase private constructor(
    private val cache: MutableMap<String, ByteArray>,
    private val db: dynamic,
    parentScope: CoroutineScope,
    // The fence this database claimed on open (`StoreFence.kt`); null = read-only.
    private val fenceToken: String?,
    // Told on the first write the fence refuses — another runtime claimed this database (F2).
    onFencedOut: () -> Unit,
) : FileDatabase {

    private val fence = FenceReporter("files", onFencedOut)

    private val writeScope: CoroutineScope =
        CoroutineScope(parentScope.coroutineContext + SupervisorJob())

    override fun writeRawByteArray(filePath: String, bytes: ByteArray) {
        cache[filePath] = bytes
        writeScope.launch { fence.report(putBytes(db, fenceToken, filePath, bytes)) }
    }

    override fun writeByteArray(filePath: String, bytes: TByteArray) {
        writeRawByteArray(filePath, bytes.get())
    }

    // ⚠ Queued on the same scope as the puts, so a delete issued after a write runs after it: IDB
    // commits readwrite transactions on one store in creation order.
    override fun deleteFile(filePath: String) {
        cache.remove(filePath)
        writeScope.launch { fence.report(deleteBytes(db, fenceToken, filePath)) }
    }

    override fun readBytes(filePath: String): TByteArray? {
        val bytes = cache[filePath] ?: return null
        return TByteArrayDto(bytes)
    }

    // Suspends until every queued IDB write has committed. Tests use this
    // to guarantee bytes are on disk before reopening the database.
    suspend fun flush() {
        val pending = writeScope.coroutineContext[Job]?.children?.toList().orEmpty()
        pending.joinAll()
    }

    /**
     * Flush every queued write, then refuse further ones (a launch on the cancelled scope never
     * runs) and close the IDB connection. The runtime-lease hand-over (`RuntimeLease.yieldNow`) calls this before it releases the
     * lock, so the next tab's hydration reads everything this one wrote, and nothing lands after.
     */
    suspend fun close() {
        flush()
        writeScope.cancel()
        db.close()
    }

    /** Stop without flushing — the lock was stolen; see `IndexedDbObjectStore.abandon`. */
    fun abandon() {
        writeScope.cancel()
    }

    // Wipes the in-memory cache and the on-disk store. Used by
    // `Session.clear({purgeRealm: true})` for revocation cleanup. Awaits
    // any in-flight writes first so a queued put doesn't resurrect bytes
    // post-clear.
    suspend fun clearAll() {
        flush()
        cache.clear()
        fence.report(fencedClear(db, OBJECT_STORE_NAME, fenceToken))
    }

    companion object {
        private const val OBJECT_STORE_NAME = "files"

        suspend fun open(dbName: String, scope: CoroutineScope, claim: Boolean = true, onFencedOut: () -> Unit = {}): IndexedDbFileDatabase {
            val db = openDatabase(dbName)
            val fenceToken = if (claim) newFenceToken().also { claimFence(db, OBJECT_STORE_NAME, it) } else null
            val entries = readAllEntries(db)
            val cache = HashMap<String, ByteArray>(entries.size).apply {
                entries.forEach { (path, bytes) -> put(path, bytes) }
            }
            return IndexedDbFileDatabase(cache, db, scope, fenceToken, onFencedOut)
        }

        private suspend fun openDatabase(dbName: String): dynamic =
            suspendCancellableCoroutine { cont ->
                val request = js("indexedDB.open(dbName, 1)")
                request.onupgradeneeded = { event: dynamic ->
                    val database = event.target.result
                    if (!database.objectStoreNames.contains(OBJECT_STORE_NAME)) {
                        database.createObjectStore(OBJECT_STORE_NAME)
                    }
                    Unit
                }
                request.onsuccess = { _: dynamic -> cont.resume(request.result) }
                request.onerror = { _: dynamic ->
                    cont.resumeWithException(
                        IllegalStateException("IndexedDB open failed: ${request.error}"),
                    )
                }
            }

        private suspend fun readAllEntries(db: dynamic): List<Pair<String, ByteArray>> =
            suspendCancellableCoroutine { cont ->
                val tx = db.transaction(OBJECT_STORE_NAME, "readonly")
                val store = tx.objectStore(OBJECT_STORE_NAME)
                // Read keys + values via two requests; getAllKeys + getAll
                // both go through the same transaction and resolve in order.
                val keysRequest = store.getAllKeys()
                val valuesRequest = store.getAll()
                tx.oncomplete = { _: dynamic ->
                    val keys = keysRequest.result.unsafeCast<Array<String>>()
                    val values = valuesRequest.result.unsafeCast<Array<ByteArray>>()
                    val out = ArrayList<Pair<String, ByteArray>>(keys.size)
                    for (i in keys.indices) if (keys[i] != FENCE_KEY) out.add(keys[i] to values[i])
                    cont.resume(out)
                }
                tx.onerror = { _: dynamic ->
                    cont.resumeWithException(
                        IllegalStateException("IndexedDB readAll failed: ${tx.error}"),
                    )
                }
            }

        private suspend fun putBytes(db: dynamic, token: String?, filePath: String, bytes: ByteArray): Boolean =
            fencedWrite(db, arrayOf(OBJECT_STORE_NAME), OBJECT_STORE_NAME, token) { tx ->
                tx.objectStore(OBJECT_STORE_NAME).put(bytes, filePath)
            }

        private suspend fun deleteBytes(db: dynamic, token: String?, filePath: String): Boolean =
            fencedWrite(db, arrayOf(OBJECT_STORE_NAME), OBJECT_STORE_NAME, token) { tx ->
                tx.objectStore(OBJECT_STORE_NAME).delete(filePath)
            }
    }
}
