package app.tenshu.sdk.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.tenshu.core.common.delegate.SecureStorageDelegate
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// IndexedDB-backed `SecureStorageDelegate` for the JS runtime. Holds the
// per-device material that must outlive a single `SyncManagerImpl`
// instance — most importantly the Ed25519 device-ID, which the relay
// keys all subsequent JWTs against, and the `lastSyncTimestamp` cursor.
//
// Without this slice the in-memory delegate generated a fresh device ID
// every page load, so once paired/realm-bootstrapped the relay treated
// every reload as a brand-new (unauthenticated) device and sync broke.
//
// "Secure" here is aspirational: IndexedDB is plain origin storage, not
// keychain. The interface's `storeSecret` vs `storeString` distinction
// mostly matters for mobile (keychain vs SharedPrefs); on the web both
// land in the same IDB store. The right place to harden this is the
// platform-wide crypto pass (deferred — see `docs/web/roadmap.md` Phase
// 4), where master-key material moves into WebCrypto's non-extractable
// CryptoKey storage and never lives in IDB at all. For now this delegate
// matches the production behaviour pre-libsodium pass — same fidelity as
// `:control-shared`'s `InMemorySecureStorageDelegate` Koin binding.
//
// Cache-aside, same pattern as `IndexedDbObjectStore`: hydrate on
// `open(...)` then serve sync `getString` / `storeString` from the cache
// while fire-and-forgetting IDB puts on a child supervisor scope.
internal class IndexedDbSecureStorageDelegate private constructor(
    private val cache: MutableMap<String, String>,
    private val db: dynamic,
    parentScope: CoroutineScope,
    // The fence this database claimed on open (`StoreFence.kt`); null = read-only.
    private val fenceToken: String?,
    // Told on the first write the fence refuses — another runtime claimed this database (F2).
    onFencedOut: () -> Unit,
) : SecureStorageDelegate {

    private val fence = FenceReporter("secrets", onFencedOut)

    private val writeScope: CoroutineScope =
        CoroutineScope(parentScope.coroutineContext + SupervisorJob())

    override fun storeSecret(key: String, value: String) = storeString(key, value)
    override fun getSecret(key: String): String? = getString(key)

    override fun storeString(key: String, value: String) {
        cache[key] = value
        writeScope.launch { fence.report(putRecord(db, fenceToken, key, value)) }
    }

    override fun getString(key: String): String? = cache[key]

    override fun remove(key: String) {
        cache.remove(key)
        writeScope.launch { fence.report(deleteRecord(db, fenceToken, key)) }
    }

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

    // Wipes the in-memory cache + on-disk records. Used by
    // `Session.clear({purgeRealm: true})` so the device-ID resets on
    // revocation cleanup; otherwise a `SyncManagerImpl` constructed
    // post-purge would re-read the same device-ID and the relay would
    // continue treating this browser as the (now revoked) device.
    // Caller must reload the page after purge — `SyncManagerImpl`
    // captures `currentDeviceId` at construction, so the live instance
    // still holds the old ID until next reload.
    suspend fun clearAll() {
        flush()
        cache.clear()
        fence.report(fencedClear(db, OBJECT_STORE_NAME, fenceToken))
    }

    companion object {
        private const val OBJECT_STORE_NAME = "secrets"

        suspend fun open(dbName: String, scope: CoroutineScope, claim: Boolean = true, onFencedOut: () -> Unit = {}): IndexedDbSecureStorageDelegate {
            val db = openDatabase(dbName)
            val fenceToken = if (claim) newFenceToken().also { claimFence(db, OBJECT_STORE_NAME, it) } else null
            val cache = readAll(db)
            return IndexedDbSecureStorageDelegate(cache, db, scope, fenceToken, onFencedOut)
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

        private suspend fun readAll(db: dynamic): MutableMap<String, String> {
            val keys = suspendCancellableCoroutine<Array<String>> { cont ->
                val tx = db.transaction(OBJECT_STORE_NAME, "readonly")
                val store = tx.objectStore(OBJECT_STORE_NAME)
                val request = store.getAllKeys()
                request.onsuccess = { _: dynamic ->
                    cont.resume(request.result.unsafeCast<Array<String>>())
                }
                request.onerror = { _: dynamic ->
                    cont.resumeWithException(
                        IllegalStateException("IndexedDB getAllKeys failed: ${request.error}"),
                    )
                }
            }
            val values = suspendCancellableCoroutine<Array<String>> { cont ->
                val tx = db.transaction(OBJECT_STORE_NAME, "readonly")
                val store = tx.objectStore(OBJECT_STORE_NAME)
                val request = store.getAll()
                request.onsuccess = { _: dynamic ->
                    cont.resume(request.result.unsafeCast<Array<String>>())
                }
                request.onerror = { _: dynamic ->
                    cont.resumeWithException(
                        IllegalStateException("IndexedDB getAll failed: ${request.error}"),
                    )
                }
            }
            val out = mutableMapOf<String, String>()
            for (i in keys.indices) if (keys[i] != FENCE_KEY) out[keys[i]] = values[i]
            return out
        }

        private suspend fun putRecord(db: dynamic, token: String?, key: String, value: String): Boolean =
            fencedWrite(db, arrayOf(OBJECT_STORE_NAME), OBJECT_STORE_NAME, token) { tx ->
                tx.objectStore(OBJECT_STORE_NAME).put(value, key)
            }

        private suspend fun deleteRecord(db: dynamic, token: String?, key: String): Boolean =
            fencedWrite(db, arrayOf(OBJECT_STORE_NAME), OBJECT_STORE_NAME, token) { tx ->
                tx.objectStore(OBJECT_STORE_NAME).delete(key)
            }
    }
}
