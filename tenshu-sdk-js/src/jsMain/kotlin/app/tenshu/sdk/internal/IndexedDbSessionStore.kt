package app.tenshu.sdk.internal

import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.Int8Array
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Tiny IDB-backed key/value store for the Session API. Lives in its own
// IndexedDB database (`tenshu-sdk-session`) — separate from the protocol
// store (`tenshu-sdk`) and the file bytes (`tenshu-sdk-files`) so wiping
// the session record doesn't touch realm data, and vice-versa.
//
// Three records:
//   - `deviceName: String` — written on Realm.create / Pairing.submit /
//     Recovery.claim success. Read by Session.detect to distinguish
//     `locked` (device known, no/expired session) from `firstTime`
//     (device unknown).
//   - `sessionToken: { bytes: Int8Array, expiresAtMs: Number }` — written
//     by Session.persist after Session.unlock. Read by Session.detect to
//     distinguish `active` from `locked`.
//   - `passkey: { credentialId: Int8Array, publicKey: Int8Array (SPKI),
//     algorithm: Number }` — written by Session.registerPasskey after
//     the apex's `navigator.credentials.create()` ceremony. Read by
//     Session.unlock to verify a fresh assertion.
//
// Same cache-aside pattern as `IndexedDbObjectStore`: hydrate on `open(...)`,
// reads are sync from the in-memory cache, writes update the cache then
// fire-and-forget the IDB put. `clear()` is suspend so `Session.clear` can
// guarantee the on-disk record is gone before resolving.
internal class IndexedDbSessionStore private constructor(
    private val db: dynamic,
    private val cache: MutableMap<String, dynamic>,
    // The fence this database claimed on open (`StoreFence.kt`); null = read-only.
    private val fenceToken: String?,
    // Told on the first write the fence refuses — another runtime claimed this database.
    onFencedOut: () -> Unit,
) {
    private val fence = FenceReporter("session", onFencedOut)

    // Set when this runtime gives the lease up (`JsRuntime.yieldToAnotherTab`):
    // a displaced tab must not persist this store again. The fence covers a STOLEN tab; this covers
    // the window between a clean release and the next holder's claim.
    private var closed = false

    fun close() {
        closed = true
    }

    fun getDeviceName(): String? = cache[KEY_DEVICE_NAME]?.unsafeCast<String?>()

    fun setDeviceName(name: String) {
        cache[KEY_DEVICE_NAME] = name
        // fire-and-forget; same durability gap as IndexedDbObjectStore.
        // The put runs on the calling coroutine's dispatcher; we don't
        // need a separate scope here because writes are cheap (single
        // record).
    }

    fun getSessionToken(): SessionTokenJs? {
        val raw = cache[KEY_SESSION_TOKEN] ?: return null
        return SessionTokenJs(
            bytes = raw.bytes.unsafeCast<Int8Array>(),
            expiresAtMs = (raw.expiresAtMs as Number).toDouble(),
        )
    }

    fun setSessionToken(bytes: Int8Array, expiresAtMs: Double) {
        val obj: dynamic = js("({})")
        obj.bytes = bytes
        obj.expiresAtMs = expiresAtMs
        cache[KEY_SESSION_TOKEN] = obj
    }

    fun getPasskey(): PasskeyRecord? {
        val raw = cache[KEY_PASSKEY] ?: return null
        return PasskeyRecord(
            credentialId = raw.credentialId.unsafeCast<Int8Array>(),
            publicKey = raw.publicKey.unsafeCast<Int8Array>(),
            algorithm = (raw.algorithm as Number).toInt(),
        )
    }

    fun setPasskey(credentialId: Int8Array, publicKey: Int8Array, algorithm: Int) {
        val obj: dynamic = js("({})")
        obj.credentialId = credentialId
        obj.publicKey = publicKey
        obj.algorithm = algorithm
        cache[KEY_PASSKEY] = obj
    }

    suspend fun clear() {
        cache.remove(KEY_DEVICE_NAME)
        cache.remove(KEY_SESSION_TOKEN)
        cache.remove(KEY_PASSKEY)
        if (closed) return
        val cleared = fencedClear(db, OBJECT_STORE_NAME, fenceToken)
        fence.report(cleared)
        if (cleared) SessionHint.write(null, null)
    }

    /** Re-state the landing hint from this (authoritative) store — see [SessionHint]. */
    fun refreshHint() {
        SessionHint.write(getDeviceName(), getSessionToken()?.expiresAtMs)
    }

    suspend fun flush() {
        if (closed) return
        // Persist the current cache snapshot. Called by Session.persist /
        // Session.clear so the IDB put is guaranteed before the Promise
        // resolves — apex routing logic can rely on the next Session.detect
        // seeing the new state even after a reload.
        // ⚠⚠ Fenced (`StoreFence.kt`): a stolen tab's late flush is refused.
        val written = fencedWrite(db, arrayOf(OBJECT_STORE_NAME), OBJECT_STORE_NAME, fenceToken) { tx ->
            val store = tx.objectStore(OBJECT_STORE_NAME)
            for ((k, v) in cache) putRecord(store, k, v)
        }
        fence.report(written)
        if (!written) return
        refreshHint()
    }

    internal data class SessionTokenJs(
        val bytes: Int8Array,
        val expiresAtMs: Double,
    )

    internal data class PasskeyRecord(
        val credentialId: Int8Array,
        val publicKey: Int8Array,
        val algorithm: Int,
    )

    companion object {
        private const val OBJECT_STORE_NAME = "session"
        private const val KEY_DEVICE_NAME = "deviceName"
        private const val KEY_SESSION_TOKEN = "sessionToken"
        private const val KEY_PASSKEY = "passkey"

        suspend fun open(dbName: String, claim: Boolean = true, onFencedOut: () -> Unit = {}): IndexedDbSessionStore {
            val db = openDatabase(dbName)
            val fenceToken = if (claim) newFenceToken().also { claimFence(db, OBJECT_STORE_NAME, it) } else null
            val cache = readAll(db)
            return IndexedDbSessionStore(db, cache, fenceToken, onFencedOut)
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

        private suspend fun readAll(db: dynamic): MutableMap<String, dynamic> {
            // Use getAllKeys() + getAll() instead of openCursor(): the cursor's
            // `continue()` method clashes with Kotlin's `continue` keyword and
            // requires either backtick-escaped dynamic access or the mangled
            // intrinsic name. Two parallel transactions are simpler and the
            // store only ever has a handful of records.
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
            val values = suspendCancellableCoroutine<Array<dynamic>> { cont ->
                val tx = db.transaction(OBJECT_STORE_NAME, "readonly")
                val store = tx.objectStore(OBJECT_STORE_NAME)
                val request = store.getAll()
                request.onsuccess = { _: dynamic ->
                    cont.resume(request.result.unsafeCast<Array<dynamic>>())
                }
                request.onerror = { _: dynamic ->
                    cont.resumeWithException(
                        IllegalStateException("IndexedDB getAll failed: ${request.error}"),
                    )
                }
            }
            val out = mutableMapOf<String, dynamic>()
            for (i in keys.indices) if (keys[i] != FENCE_KEY) out[keys[i]] = values[i]
            return out
        }

        private fun putRecord(store: dynamic, key: String, value: dynamic) {
            store.put(value, key)
        }
    }
}
