package app.tenshu.sdk.internal

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The write fence behind the one-runtime-per-origin rule (`RuntimeLease`).
 *
 * ⚠⚠ Why the lock alone is not enough: a tab whose lock was **stolen** (it was frozen — iOS Safari
 * freezes background tabs routinely — and "Use here" gave up waiting) still has IndexedDB writes
 * queued. When it thaws they run, *after* the new holder hydrated and wrote: an old row lands
 * behind the new holder's newer cursor, the exact divergence the lease exists to prevent.
 *
 * So every database a runtime writes holds a fence token, and the runtime that opens it claims the
 * fence with a fresh token. Every write transaction first reads the fence **in the same
 * transaction** and aborts unless it is still its own. IndexedDB serialises readwrite transactions
 * on a store, so "check then write" cannot interleave with the new holder's claim — the old tab's
 * late writes are refused, whole, by construction.
 *
 * The token lives at [FENCE_KEY]: in the object store's `meta` store, and as a reserved key inside
 * the single store of every other database (no IndexedDB version bump, which a tab running an older
 * bundle could block indefinitely). Readers skip that key.
 */
internal const val FENCE_KEY = "\u0000tenshu/fence"

internal fun newFenceToken(): String =
    js("Math.random().toString(36).slice(2) + '-' + Date.now().toString(36)").unsafeCast<String>()

/** Make [token] the fence of [db] — called by the runtime that just opened it, under the lock. */
internal suspend fun claimFence(db: dynamic, fenceStore: String, token: String): Unit =
    suspendCancellableCoroutine { cont ->
        val tx = db.transaction(fenceStore, "readwrite")
        tx.objectStore(fenceStore).put(token, FENCE_KEY)
        tx.oncomplete = { _: dynamic -> cont.resume(Unit) }
        tx.onerror = { _: dynamic ->
            if (cont.isActive) cont.resumeWithException(IllegalStateException("fence claim failed: ${tx.error}"))
        }
    }

/**
 * Run [write] in one readwrite transaction over [stores], **only if** [fenceStore] still holds
 * [token]. Returns false — nothing written — when another runtime has claimed the database since
 * (or [token] is null: a store opened without claiming, i.e. read-only).
 *
 * ⚠ [write] runs inside the fence read's success callback, so it is the same transaction: do not
 * suspend in it, only issue requests.
 */
internal suspend fun fencedWrite(
    db: dynamic,
    stores: Array<String>,
    fenceStore: String,
    token: String?,
    write: (tx: dynamic) -> Unit,
): Boolean {
    if (token == null) return false
    return suspendCancellableCoroutine { cont ->
        val tx = db.transaction(stores, "readwrite")
        var fencedOut = false
        val check = tx.objectStore(fenceStore).get(FENCE_KEY)
        check.onsuccess = { _: dynamic ->
            if (check.result != token) {
                fencedOut = true
                tx.abort()
            } else {
                write(tx)
            }
        }
        tx.oncomplete = { _: dynamic -> if (cont.isActive) cont.resume(true) }
        tx.onabort = { _: dynamic ->
            if (cont.isActive) {
                if (fencedOut) cont.resume(false)
                else cont.resumeWithException(IllegalStateException("IndexedDB write aborted: ${tx.error}"))
            }
        }
    }
}

/**
 * Acts on a refused write — once per store: another runtime owns the database now.
 *
 * ⚠⚠ Never only a log line. A fenced-out runtime whose writes silently vanish is a
 * read-only ghost that still looks live — and without Web Locks (UNGUARDED) nothing else would
 * ever tell it: a second tab's open simply claims the fence. [onFencedOut] stops it and surfaces
 * "open in another tab" (`RuntimeLease.fencedOut`).
 */
internal class FenceReporter(private val storeName: String, private val onFencedOut: () -> Unit = {}) {
    private var reported = false
    fun report(written: Boolean) {
        if (written || reported) return
        reported = true
        app.tenshu.diagnostics.Diagnostics.w(
            "StoreFence",
            "$storeName: write refused — another tab holds this database now",
        )
        onFencedOut()
    }
}

/**
 * Empty [store] but keep the fence: a plain `clear()` would delete [FENCE_KEY] with everything else
 * and fence this runtime's own later writes out. Fenced like any write.
 */
internal suspend fun fencedClear(db: dynamic, store: String, token: String?): Boolean =
    fencedWrite(db, arrayOf(store), store, token) { tx ->
        val objects = tx.objectStore(store)
        objects.clear()
        objects.put(token, FENCE_KEY)
    }
