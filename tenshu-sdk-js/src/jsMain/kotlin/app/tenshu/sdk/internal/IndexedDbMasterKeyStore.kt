package app.tenshu.sdk.internal

import kotlinx.coroutines.suspendCancellableCoroutine
import org.tenshu.core.common.security.WrappedMasterKey
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// IDB-backed storage for the wrapped master identity
// private key (asset A1, `docs/web/architecture.md` §4.3). Lives in its
// own IndexedDB database (`tenshu-sdk-master-keys`) so wiping the
// master-key store doesn't touch the protocol metadata, file bytes,
// secure-storage, or session record, and vice-versa.
//
// Records are keyed by **realm `ownerId`** so multiple realms can share
// the same browser without each clobbering the other's wrapped blob.
// Only one realm is active at a time (the apex's session model is
// single-realm); the keying does not rely on that.
//
// The blob itself is a `WrappedMasterKey` produced by `MasterKeyWrapper`
// (`:core-common`) — opaque AEAD bytes the store doesn't interpret. The
// store's job is purely IDB CRUD; the wrap/unwrap algorithm and the
// wrap-key source are owned by the caller.
//
// The producer is the WebAuthn-PRF wrap path in `Session`: `registerPasskey`
// wraps the master key under the authenticator-derived key
// (`wrapMasterKeyForCurrentRealm`) and `Session.unlock` unwraps it
// (`unwrapMasterKeyForCurrentRealm`). Blobs are versioned — v2 also carries the
// BIP-39 entropy so `Recovery.revealPhrase` survives a PRF unlock; v1 blobs are
// keypair-only and re-wrap to v2 on the next passkey registration that has the
// entropy in memory.
//
// `Recovery.claim`/`Realm.create`/`Pairing.submit` do *not* write here — they
// leave the master key in `SyncManagerImpl.currentMasterKeyPair` (in-memory)
// until a passkey is registered.
//
// Cache-aside, same pattern as `IndexedDbSessionStore`: hydrate on
// `open(...)`, sync reads from the in-memory map, writes update the cache
// and fire-and-forget the IDB put. `flush()`/`clearAll()` are suspend so
// the caller (e.g. `Session.clear({purgeRealm: true})`) can guarantee
// the on-disk record is gone before resolving.
internal class IndexedDbMasterKeyStore private constructor(
    private val db: dynamic,
    private val cache: MutableMap<String, WrappedMasterKey>,
    // The fence this database claimed on open (`StoreFence.kt`); null = read-only.
    private val fenceToken: String?,
    // Told on the first write the fence refuses — another runtime claimed this database.
    onFencedOut: () -> Unit,
) {
    private val fence = FenceReporter("masterKeys", onFencedOut)

    fun get(ownerId: String): WrappedMasterKey? = cache[ownerId]

    // Single-realm convenience: returns the lone (ownerId, blob) pair when
    // exactly one realm has a wrapped key, or null when the store is empty.
    // The unlock path uses this on page reload, when the SDK doesn't
    // know the ownerId yet (no in-memory SyncManager state) but needs to
    // pick the wrapped blob to unwrap. Returns null when there are zero or
    // multiple entries — multi-realm-per-browser is out of scope
    // (single-realm session model per `docs/web/architecture.md` §4.3).
    fun singleEntryOrNull(): Pair<String, WrappedMasterKey>? =
        if (cache.size == 1) {
            val entry = cache.entries.first()
            entry.key to entry.value
        } else {
            null
        }

    fun put(ownerId: String, wrapped: WrappedMasterKey) {
        cache[ownerId] = wrapped
        // Fire-and-forget; same durability gap as the other IDB stores.
        // Callers that need durability (the unlock setup) should call
        // `flush()` before resolving the user-facing Promise.
    }

    fun remove(ownerId: String) {
        cache.remove(ownerId)
    }

    suspend fun flush() {
        if (closed) return
        // Replace-on-flush: wipe and write the current snapshot (one record per realm),
        // keeping the fence. Kotlin/JS ByteArray is backed by Int8Array, which IDB
        // serialises as-is. ⚠⚠ Fenced (`StoreFence.kt`): a stolen tab's late flush is refused.
        fence.report(
            fencedWrite(db, arrayOf(OBJECT_STORE_NAME), OBJECT_STORE_NAME, fenceToken) { tx ->
                val store = tx.objectStore(OBJECT_STORE_NAME)
                store.clear()
                store.put(fenceToken, FENCE_KEY)
                for ((k, v) in cache) store.put(v.bytes, k)
            },
        )
    }

    // Set when this runtime gives the lease up (`JsRuntime.yieldToAnotherTab`):
    // a displaced tab must not persist this store again. The fence covers a STOLEN tab; this covers
    // the window between a clean release and the next holder's claim.
    private var closed = false

    fun close() {
        closed = true
    }

    suspend fun clearAll() {
        cache.clear()
        fence.report(fencedClear(db, OBJECT_STORE_NAME, fenceToken))
    }

    companion object {
        private const val OBJECT_STORE_NAME = "masterKeys"

        suspend fun open(dbName: String, claim: Boolean = true, onFencedOut: () -> Unit = {}): IndexedDbMasterKeyStore {
            val db = openDatabase(dbName)
            val fenceToken = if (claim) newFenceToken().also { claimFence(db, OBJECT_STORE_NAME, it) } else null
            val cache = readAll(db)
            return IndexedDbMasterKeyStore(db, cache, fenceToken, onFencedOut)
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

        private suspend fun readAll(db: dynamic): MutableMap<String, WrappedMasterKey> {
            // Same getAllKeys() + getAll() pattern as IndexedDbSessionStore;
            // the cursor's `continue()` clashes with Kotlin's keyword and the
            // store has at most a handful of entries.
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
            val values = suspendCancellableCoroutine<Array<ByteArray>> { cont ->
                val tx = db.transaction(OBJECT_STORE_NAME, "readonly")
                val store = tx.objectStore(OBJECT_STORE_NAME)
                val request = store.getAll()
                request.onsuccess = { _: dynamic ->
                    cont.resume(request.result.unsafeCast<Array<ByteArray>>())
                }
                request.onerror = { _: dynamic ->
                    cont.resumeWithException(
                        IllegalStateException("IndexedDB getAll failed: ${request.error}"),
                    )
                }
            }
            val out = mutableMapOf<String, WrappedMasterKey>()
            for (i in keys.indices) {
                if (keys[i] != FENCE_KEY) out[keys[i]] = WrappedMasterKey(values[i])
            }
            return out
        }
    }
}
