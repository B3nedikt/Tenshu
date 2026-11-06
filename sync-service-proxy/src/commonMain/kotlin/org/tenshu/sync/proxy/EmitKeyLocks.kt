package org.tenshu.sync.proxy

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tenshu.sync.common.PendingContentEmit

/**
 * The content-emit ownership table — one per process runtime, shared by every session's
 * [SyncObserver] and the scheduler's drain.
 *
 * ⚠⚠ Same reason as [TombstoneCascades]: every connected app gets its own observer, and all of them
 * feed one scheduler ([SyncRuntimeHolder]). A table kept per observer would let an overwrite through
 * one session never contend with an emit — or with a move's successor declare — running through
 * another, so the ownership would serialise nothing across apps: two apps overwriting one file would
 * race exactly as if there were no guard.
 */
class EmitKeyLocks {
    class Lock internal constructor() {
        val mutex = Mutex()
        internal var holders = 0
    }

    private val locks = mutableMapOf<String, Lock>()
    private val guard = Mutex()

    suspend fun acquire(emitKey: String): Lock =
        guard.withLock { locks.getOrPut(emitKey) { Lock() }.also { it.holders++ } }

    // ---- What this runtime has already published ----

    /**
     * `(holder, content-keyed emit key)` pairs this runtime has published, so a second emit of the SAME
     * content for the SAME holder is skipped instead of re-uploaded under a fresh CEK.
     *
     * ⚠⚠ Why this exists: every emit mints a fresh CEK, so each one is a NEW chunk set; the manifest
     * address and the relay's describe are last-write-wins, and every losing chunk set stays stored,
     * undescribed, unreachable by any sweep — and the LIVE set can be the undescribed one. Repeated
     * emits of one content can come to a large share of a realm's stored bytes.
     *
     * ⚠ Keyed by holder AND content, never content alone: copies share a contentId across holders, and
     * a skip for a second holder would also skip declaring that holder's edge.
     * ⚠ In memory and per runtime by design: a restart, or another device, re-publishing is caught by
     * the caller asking the relay first (`ensureContentOnRelay`), which is what the minter does.
     * Bounded, oldest evicted: forgetting only means one more — still correct — emit.
     */
    private val published = LinkedHashSet<String>()

    suspend fun wasPublished(key: String): Boolean = guard.withLock { key in published }

    suspend fun markPublished(key: String) {
        guard.withLock {
            published.remove(key)
            published.add(key)
            while (published.size > MAX_PUBLISHED) published.remove(published.first())
        }
    }

    /**
     * Forget [key]: a publish whose declare FAILED — even a forced repair of a pair marked earlier — must
     * leave the pair re-emittable, or the missing declaration is never retried in this runtime.
     */
    suspend fun unmarkPublished(key: String) {
        guard.withLock { published.remove(key) }
    }

    /**
     * The one rule every publish path follows: a DECLARED publish marks the pair, an undeclared one
     * clears it. Setting only on success would leave a stale marker in place after a forced repair whose
     * declare failed.
     */
    suspend fun recordPublish(key: String, declared: Boolean) {
        if (declared) markPublished(key) else unmarkPublished(key)
    }

    // ---- Did any emit of this CONTENT overlap a window? ----

    private class ContentStamp(var value: Long, var active: Int)

    /**
     * Per **content id**, not per emit key: the manifest is content-addressed, so an emit through ANY
     * property replaces it — a stamp per `(content, property)` would let an emit through one property
     * leave another property's stale refs looking current.
     *
     * The stamp moves when a hold of any of the content's keys **begins and when it ends**, and reads as
     * busy (null) while one is in progress. So for a window opened by reading stamp `s`: seeing `s` again,
     * not busy, proves no emit of this content overlapped the window — one that began inside it moved the
     * stamp, and one already running at its start is either still running (busy) or ended inside it
     * (moved). That is what lets [ContentDeclarationBackfill] check without holding any key: no
     * multi-key holder, so no deadlock against an overwrite that holds the old key while it takes the
     * new.
     *
     * ⚠⚠ Globally monotonic, and an evicted content reads [evictedFloor], never 0: a counter restarted
     * after eviction could return the very value a snapshot took. The floor is at least every evicted
     * stamp, so an evicted content reads as moved (spuriously at worst, which only costs a re-declare).
     * ⚠ A busy entry is never evicted — its in-progress hold is the one thing a reader must see.
     */
    private val contentStamps = LinkedHashMap<String, ContentStamp>()
    private var sequence = 0L
    private var evictedFloor = 0L

    suspend fun beginHold(emitKey: String) = touch(emitKey, +1)

    suspend fun endHold(emitKey: String) = touch(emitKey, -1)

    private suspend fun touch(emitKey: String, activeDelta: Int) {
        val contentId = PendingContentEmit.contentIdOfKey(emitKey) ?: return
        guard.withLock {
            val stamp = contentStamps.remove(contentId) ?: ContentStamp(0, 0)
            stamp.value = ++sequence
            stamp.active = maxOf(0, stamp.active + activeDelta)
            contentStamps[contentId] = stamp
            if (contentStamps.size > MAX_STAMPS) {
                val idle = contentStamps.entries.firstOrNull { it.value.active == 0 && it.key != contentId }
                if (idle != null) {
                    evictedFloor = maxOf(evictedFloor, idle.value.value)
                    contentStamps.remove(idle.key)
                }
            }
        }
    }

    /**
     * The newest stamp handed out so far. A pass that reads it BEFORE it enumerates anything can then tell
     * "this content was touched after I started" from any stamp above it — covering the window between
     * reading a holder and snapshotting its content, which a per-content snapshot taken later cannot.
     */
    suspend fun currentSequence(): Long = guard.withLock { sequence }

    /**
     * The content's stamp, or null while an emit of it is in progress. [ownHolds] is how many of the
     * content's keys the CALLER holds — those are not "another emit".
     */
    suspend fun stampOf(contentId: String, ownHolds: Int = 0): Long? = guard.withLock {
        val stamp = contentStamps[contentId] ?: return@withLock evictedFloor
        if (stamp.active > ownHolds) null else stamp.value
    }

    /** ⚠ Drop the map entry once nobody wants it — one file per key would leak a Mutex per upload. */
    suspend fun release(emitKey: String, lock: Lock) {
        guard.withLock { if (--lock.holders == 0) locks.remove(emitKey) }
    }

    private companion object {
        const val MAX_PUBLISHED = 20_000
        const val MAX_STAMPS = 20_000
    }
}
