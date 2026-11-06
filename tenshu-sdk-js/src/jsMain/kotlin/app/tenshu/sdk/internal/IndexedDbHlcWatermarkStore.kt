package app.tenshu.sdk.internal

import kotlinx.coroutines.suspendCancellableCoroutine
import org.tenshu.sync.common.HlcWatermark
import org.tenshu.sync.common.HlcWatermarkStore
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The browser [HlcWatermarkStore] (`sync-hlc-tip-device-identity.md` TC-2a) — the implementation
 * that actually fixes the multi-runtime false positive on web.
 *
 * ## Why the web needs its own, and why it is the *easy* platform
 *
 * The device ID is persisted in per-origin IndexedDB so it survives reload, and IndexedDB is
 * shared by **every tab of that origin** — while each tab builds its own `JsRuntime`, its own
 * `SyncManagerImpl` and therefore its own `HLCManager`, with no leader election between them
 * (`IndexedDbObjectStore` puts cross-tab propagation explicitly out of scope). Two tabs of web
 * Drive are structurally the same bug as two first-party apps on an iPhone, and rather easier to
 * hit, because people open tabs without thinking about it.
 *
 * The upside is that the browser hands us the primitive for free. Tabs already share the store, so
 * they already share this watermark — no container, no entitlement, no lock file. The interface's
 * invariant ("the watermark describes the store it sits beside") holds because both live in the
 * same origin's IndexedDB.
 *
 * ## ⚠⚠ Why not reuse `IndexedDbSecureStorageDelegate`
 *
 * Every other small record in this SDK is cache-aside with a fire-and-forget put. That is exactly
 * wrong here: two tabs racing a read-modify-write would each read the pre-race value and the later
 * put would win, silently lowering the watermark and re-creating the backward step this class
 * exists to prevent. So the merge runs **inside one `readwrite` transaction**, which IndexedDB
 * serialises across tabs, and there is deliberately **no in-memory cache** — a cached read is a
 * stale read the moment another tab advances.
 *
 * Its own database (`tenshu-sdk-hlc`), like the session store and the file bytes have theirs, so
 * clearing one plane never disturbs another.
 */
internal class IndexedDbHlcWatermarkStore private constructor(
    private val db: dynamic,
) : HlcWatermarkStore {

    override suspend fun read(): HlcWatermark = suspendCancellableCoroutine { cont ->
        val tx = db.transaction(OBJECT_STORE_NAME, "readonly")
        val request = tx.objectStore(OBJECT_STORE_NAME).get(KEY)
        request.onsuccess = { _: dynamic -> cont.resume(decode(request.result)) }
        request.onerror = { _: dynamic ->
            cont.resumeWithException(IllegalStateException("HLC watermark read failed: ${request.error}"))
        }
    }

    override suspend fun raiseTo(candidate: HlcWatermark): HlcWatermark =
        suspendCancellableCoroutine { cont ->
            // One transaction spans the get and the put. IndexedDB serialises overlapping
            // readwrite transactions on the same store, across tabs — that serialisation IS the
            // cross-runtime exclusion, and splitting these into two transactions would throw it
            // away without any visible symptom until two tabs happened to race.
            val tx = db.transaction(OBJECT_STORE_NAME, "readwrite")
            val store = tx.objectStore(OBJECT_STORE_NAME)
            val getRequest = store.get(KEY)
            getRequest.onsuccess = { _: dynamic ->
                val current = decode(getRequest.result)
                if (candidate <= current) {
                    // Write-on-advance: a quiet tab polls every 30 s and must not write each time.
                    cont.resume(current)
                } else {
                    val record: dynamic = js("({})")
                    record.physicalTime = candidate.physicalTime.toDouble()
                    record.logicalCounter = candidate.logicalCounter.toDouble()
                    val putRequest = store.put(record, KEY)
                    putRequest.onsuccess = { _: dynamic -> cont.resume(candidate) }
                    putRequest.onerror = { _: dynamic ->
                        cont.resumeWithException(
                            IllegalStateException("HLC watermark write failed: ${putRequest.error}"),
                        )
                    }
                }
                Unit
            }
            getRequest.onerror = { _: dynamic ->
                cont.resumeWithException(
                    IllegalStateException("HLC watermark read failed: ${getRequest.error}"),
                )
            }
        }

    companion object {
        internal const val DB_NAME = "tenshu-sdk-hlc"
        private const val OBJECT_STORE_NAME = "watermark"
        private const val KEY = "personal"

        suspend fun open(dbName: String = DB_NAME): IndexedDbHlcWatermarkStore =
            IndexedDbHlcWatermarkStore(openDatabase(dbName))

        /**
         * Absent or malformed ⇒ [HlcWatermark.NONE]. This is an optimisation over a clock that
         * still works without it, so a corrupt record must degrade to "start from scratch" rather
         * than take sync down at startup.
         *
         * ⚠ Stored as JS numbers (doubles). Exact to 2^53, which a millisecond epoch will not
         * reach for ~285,000 years; the logical counter resets on every physical-time advance and
         * never approaches it either.
         */
        private fun decode(raw: dynamic): HlcWatermark {
            if (raw == null || raw == undefined) return HlcWatermark.NONE
            val physical = (raw.physicalTime as? Number)?.toDouble() ?: return HlcWatermark.NONE
            val counter = (raw.logicalCounter as? Number)?.toDouble() ?: return HlcWatermark.NONE
            if (physical < 0.0 || counter < 0.0) return HlcWatermark.NONE
            return HlcWatermark(physical.toLong(), counter.toLong())
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
    }
}
