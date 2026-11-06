package org.tenshu.sync.relay

import org.tenshu.core.common.SYNC_CHANGES_PAGE_LIMIT
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.relay.auth.DeviceRegistry
import org.tenshu.sync.relay.mailbox.HlcTipRegistry
import org.tenshu.sync.relay.mailbox.OwnerScopedStore

/**
 * The relay's per-owner mailbox: the ordered sync-block log, the tombstone log, and the
 * in-flight pairing handshake state.
 *
 * Storage SPI (`docs/proposals/sync-relay-persistence.md`, Phase 2b-ii). Two backends:
 *   - [SyncMailbox] — in-memory; the default + test backend.
 *   - `org.tenshu.sync.relay.storage.SqliteSyncMailbox` — durable blocks + tombstones.
 *
 * **Storage vs logic split:** backends implement only the storage *primitives*. The GC
 * machinery ([acknowledgementSet] + [gcSweep]) is **shared default logic** expressed over
 * those primitives, so the subtle quorum/retention rules live in exactly one place rather
 * than being reimplemented per backend. The nested result/data types stay on [SyncMailbox]
 * so existing call sites are untouched; both backends use them.
 *
 * Pairing req/resp are **ephemeral** in-flight handshake state — a backend may keep them
 * in-memory even when blocks/tombstones are durable (they're consumed within seconds).
 * Retention overrides are likewise an admin/test seam, not durable state.
 *
 * [eraseOwner] (account closure, F12) drops the owner's blocks, tombstones, pending pairing
 * requests and retention override, and every live pairing *response* whose queued registration
 * names the owner (`PairingResponseInbox.eraseOwner`). ⚠ Responses are keyed by the secret's hash;
 * they are attributable only through that registration, and must go — a peek after erasure would
 * otherwise re-register a device into the erased realm.
 */
interface MailboxStore : OwnerScopedStore {

    // --- Sync-block log ---
    fun pushBlock(ownerId: String, block: SyncBlock)
    /**
     * Blocks with `seq > sinceTimestamp`, oldest first, **capped at [limit] rows**.
     *
     * `nextCursor` is the highest seq actually returned (or [sinceTimestamp] when nothing
     * matched) — never the log's true tip — so a caller that advances to it has provably
     * received everything up to that point. A caller detects truncation by comparing the row
     * count against the limit it expects; see [SYNC_CHANGES_PAGE_LIMIT] for why that limit is
     * a shared constant rather than a parameter on the wire.
     */
    fun getChanges(
        ownerId: String,
        sinceTimestamp: Long,
        limit: Int = SYNC_CHANGES_PAGE_LIMIT,
    ): SyncMailbox.ChangesResult
    fun fetchEntityBlocks(ownerId: String, entityIdHash: String): List<SyncBlock>

    // --- Tombstone log ---
    /**
     * Append [block] to the owner's tombstone log and return **the relay sequence assigned to it**.
     *
     * ⚠ RH-1a (`sync-retention-horizons.md` §3.3, D9): the seq is the only quantity a reclamation
     * candidate can be compared against. A candidate records `tombstonedAtMs` — a millisecond —
     * while a peer cursor is a sequence, and the two are incommensurable. The value was already
     * being assigned here and discarded.
     *
     * ⚠ The sequence space is **global**, not per-owner (`relay_seq` is one row), so an owner's
     * seqs are a sparse ascending subset. That is fine for the comparison the barrier needs — it
     * asks only whether a cursor is at or past a seq — but it is why a seq must never be read as
     * a count of anything.
     */
    fun pushTombstone(ownerId: String, block: DeleteBlock): Long
    /** Tombstone twin of [getChanges] — same cursor space, same page cap, same semantics. */
    fun getTombstones(
        ownerId: String,
        sinceTimestamp: Long,
        limit: Int = SYNC_CHANGES_PAGE_LIMIT,
    ): SyncMailbox.TombstonesResult
    // --- Merged log (sync-lane-cursor-split.md Option C / L3) ---
    /**
     * One page of the **union** of the block and tombstone logs, oldest first, capped at
     * [limit] rows across BOTH kinds.
     *
     * ## Why this is not "call getChanges and getTombstones and glue the results"
     *
     * Two independent `take(limit)` queries return each kind's own first-[limit] rows, whose
     * max seqs can differ by tens of thousands — so no single number covers both, which is
     * the entire reason the two lanes needed two cursors. This takes the first [limit]
     * entries of the *merged* order, whatever mix that is. The result is a true **prefix** of
     * the combined log, so `nextCursor` is sound for both kinds at once: nothing below it is
     * left unsent in either.
     *
     * That the merge is even possible is not new — [SyncMailbox.arrivalCounter] has always
     * been ONE counter shared by `pushBlock` and `pushTombstone`, so a total order over both
     * already existed. The two endpoints were two views of one log; this is the log.
     *
     * ⚠ `nextCursor` is defined as **the cursor to pass back for the next page in the direction
     * you asked** — the highest seq served here, the lowest for [getSyncLogBefore]. One meaning,
     * two directions.
     *
     * [SyncMailbox.SyncLogResult.hasMore] is answered from the store rather than inferred by
     * the caller from `rows.size >= limit`. The store knows; the caller could only guess, and
     * guessing misreads an exactly-full final page as truncated.
     */
    fun getSyncLog(
        ownerId: String,
        sinceTimestamp: Long,
        limit: Int = SYNC_CHANGES_PAGE_LIMIT,
    ): SyncMailbox.SyncLogResult

    /**
     * One page of the merged log walking **backwards**: the highest [limit] entries with
     * `seq < beforeSeq`, newest first (`sync-device-catch-up.md` C2 / §5.4).
     *
     * ## Why a device wants the log backwards
     *
     * Ascending replay shows a rebuilding device July before it shows yesterday, so it is useless
     * for hours while it crawls forward. Descending, **recent state arrives first** and the device is
     * useful in seconds. This is Matrix's `prev_batch`, and it is why Matrix never needed an index
     * over its log.
     *
     * ⚠⚠ **Applying newest-first is safe because ingest is HLC-guarded, and it is also CHEAPER.**
     * `resolveConflictAndSave` writes nothing at all when an incoming block is older than the row it
     * targets (only the `Manual` strategy records a marker). Forward replay of an entity with 5
     * superseded versions performs 5 writes; backward replay performs **one**, then drops the rest.
     * On the measured log — ~5.5 blocks per live entity — that is most of the work.
     *
     * ⚠ [SyncMailbox.SyncLogResult.nextCursor] is the value to pass back as [beforeSeq] for the next
     * page, i.e. the **lowest** seq in this one — the mirror of the forward query's meaning, not a
     * different concept. `hasMore` likewise means "more exists in the direction you asked".
     */
    fun getSyncLogBefore(
        ownerId: String,
        beforeSeq: Long,
        limit: Int = SYNC_CHANGES_PAGE_LIMIT,
    ): SyncMailbox.SyncLogResult

    /** `targetEntityIdHash → highest-HLC tombstone` for the owner (recreate semantics). */
    fun tombstoneIndex(ownerId: String): Map<String, DeleteBlock>
    /** Owners that have at least one tombstone — the GC sweep's iteration set. */
    fun tombstoneOwnerIds(): Set<String>
    /** Count of `Device`-targeted tombstones (the relay's realm-epoch view). */
    fun deviceTombstoneCount(ownerId: String): Long

    // --- GC body-drop bookkeeping ---
    fun setRetentionWindowMs(ownerId: String, retentionMs: Long)
    fun retentionWindowMs(ownerId: String): Long
    fun droppedBlockIds(ownerId: String): Set<String>
    /**
     * Drop the encrypted payload of every block for [ownerId]/[targetEntityIdHash] whose
     * HLC is below [tombstoneHlcPhysical] and whose body isn't already dropped. Returns the
     * number newly dropped. Idempotent.
     */
    fun dropBodiesFor(ownerId: String, targetEntityIdHash: String, tombstoneHlcPhysical: Long): Int

    // --- Ephemeral pairing handshake state ---
    //
    // ⚠⚠ F7: every key below is the joiner's `pairingSecretHash` (a domain-separated SHA-256 of
    // the secret), never the secret — the relay is never sent it. Requests and responses both
    // expire after [PAIRING_REQUEST_TTL_MS]; an acked response leaves a consumed marker
    // ([PairingResponseInbox]), the offer flow's shape.
    fun addPairingRequest(ownerId: String, request: SyncMailbox.PairingRequestData)
    fun getPairingRequests(ownerId: String): List<SyncMailbox.PairingRequestData>
    fun removePairingRequest(ownerId: String, pairingSecretHash: String)
    /** Store the host's sealed response; [PairingResponseInbox.PostResult.CONSUMED] once acked. */
    fun addPairingResponse(
        pairingSecretHash: String,
        encryptedKey: ByteArray,
        registration: PairingResponseInbox.PendingRegistration? = null,
    ): PairingResponseInbox.PostResult
    fun getPairingResponse(pairingSecretHash: String): PairingResponseInbox.Delivery?
    fun ackPairingResponse(pairingSecretHash: String)
    /** [PairingResponseInbox.pendingAdmittedAt] — a queued join-flow admission's stamp. */
    fun pendingPairingAdmittedAt(ownerId: String, deviceId: String): org.tenshu.core.common.util.HlcStamp?
    /** `true` while an acked [pairingSecretHash] is retired — checked before admission side effects. */
    fun isPairingResponseConsumed(pairingSecretHash: String): Boolean

    // --- Shared GC logic (default impls over the primitives above) ---

    /**
     * Devices that have ack'd [tombstone] — every realm device (per [knownDevices]) whose
     * latest tip in [hlcTipStore] has `physicalTime ≥ tombstone.hlc.physicalTime`. Pure
     * logic over the two registries; touches no mailbox state.
     */
    fun acknowledgementSet(
        ownerId: String,
        tombstone: DeleteBlock,
        knownDevices: DeviceRegistry,
        hlcTipStore: HlcTipRegistry,
    ): Set<String> {
        val realmDevices = knownDevices.deviceIds(ownerId)
        if (realmDevices.isEmpty()) return emptySet()
        val tipPhysicalByDevice = hlcTipStore.allTips(ownerId)
            .associate { it.deviceId.get() to it.hlc.physicalTime.get() }
        val targetHlc = tombstone.hlc.physicalTime.get()
        return realmDevices.filter { deviceId ->
            val tip = tipPhysicalByDevice[deviceId] ?: return@filter false
            tip >= targetHlc
        }.toSet()
    }

    /**
     * Periodic GC sweep (`docs/adr/0006-sync-tombstones.md`): for each owner's
     * highest-HLC tombstone per target, once **every** realm device has ack'd AND the
     * retention window has elapsed, drop the matching blocks' payloads. Returns the count
     * dropped. Idempotent. `Long.MAX_VALUE` retention = never GC.
     */
    fun gcSweep(
        knownDevices: DeviceRegistry,
        hlcTipStore: HlcTipRegistry,
        nowMs: Long = System.currentTimeMillis(),
    ): Int {
        var dropped = 0
        for (ownerId in tombstoneOwnerIds()) {
            val perOwnerTombstones = tombstoneIndex(ownerId)
            if (perOwnerTombstones.isEmpty()) continue
            val realmDevices = knownDevices.deviceIds(ownerId)
            if (realmDevices.isEmpty()) continue
            val retention = retentionWindowMs(ownerId)
            for ((targetIdHash, tombstone) in perOwnerTombstones) {
                val tombstoneHlc = tombstone.hlc.physicalTime.get()
                val ackSet = acknowledgementSet(ownerId, tombstone, knownDevices, hlcTipStore)
                if (!ackSet.containsAll(realmDevices)) continue
                if (retention == Long.MAX_VALUE) continue
                if (nowMs <= tombstoneHlc + retention) continue
                dropped += dropBodiesFor(ownerId, targetIdHash, tombstoneHlc)
            }
        }
        return dropped
    }
}
