package org.tenshu.sync.relay


import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.relay.auth.DeviceRegistry
import org.tenshu.sync.relay.mailbox.HlcTipRegistry
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Most pending pairing requests the relay keeps per owner before evicting the oldest.
 *
 * ⚠⚠ **A bound, not a tuning knob.** `POST /sync/pairing/request` is necessarily unauthenticated —
 * an unpaired joiner holds no credentials — so anyone who has ever seen a QR knows the `ownerId`
 * and can post well-formed requests indefinitely. `removePairingRequest` only drops entries
 * matching an *exact* secret, so without eviction a request with a random secret is never removed
 * and the per-owner list grows until the relay restarts.
 *
 * ⚠ **T-P-13 does not cover this.** That threat hardens the *shape* of a junk request (a malformed
 * key that broke SAS derivation); this is about the *number* of well-formed ones.
 *
 * Oldest-first eviction, because a flood should not be able to push out the request the user is
 * actually waiting on — the legitimate joiner's is the newest, and under a flood the honest user
 * retries. ⚠ It is still a DoS *within* one owner: an attacker who can post faster than the user
 * can approve wins the list. Bounding memory is what this fixes; the rate limit is the other half.
 */
const val MAX_PENDING_PAIRING_REQUESTS_PER_OWNER = 32

/** Debug-only trace of the in-memory mailbox. Owner and device ids go through [logId]. */
private val mailboxLog = LoggerFactory.getLogger("org.tenshu.sync.relay.SyncMailbox")

/**
 * In-memory storage for the relay's per-owner mailboxes (sync blocks, tombstones,
 * pairing requests, pairing responses). The durable backend is SQLite
 * (`sync-relay-persistence.md`).
 *
 * One instance per [Application.module] — i.e. one per relay process in
 * production, one per [TestSystem] in SystemTests. ⚠ Never a top-level `object`: a JVM-wide
 * singleton accumulates per-owner state across tests sharing a JVM (a fresh
 * [TestSystemBuilder.build] constructs fresh [KnownDevicesStore] / [RealmStore] / [HlcTipStore]
 * instances, and would inherit the same mailbox).
 *
 * Concurrency note: every per-owner list lives behind `ConcurrentHashMap.computeIfAbsent`,
 * NOT `getOrPut`. The Kotlin extension `getOrPut` is non-atomic — two concurrent calls
 * for the same key can both observe an absent entry, both create a fresh list, and only
 * one ends up in the map; the loser's reference points at a list that is not in the map,
 * so any subsequent `add` to it is silently dropped. `computeIfAbsent` is the
 * `ConcurrentHashMap` primitive that ensures exactly-once init.
 *
 * Reads (`getChanges`, `getTombstones`, `fetchEntityBlocks`, `getPairingRequests`) operate
 * on a snapshot of the per-owner list under the list's monitor lock; that's enough
 * because the list itself is the synchronization point and is never replaced after
 * `computeIfAbsent` puts it.
 */
class SyncMailbox(
    // PairingSecretHash -> EncryptedMasterKey, with TTL + consumed marker (F7). A parameter only so
    // a route test can drive the TTL with an injected clock instead of sleeping.
    private val pairingResponses: PairingResponseInbox = PairingResponseInbox(),
) : MailboxStore {
    /**
     * Per-instance monotonic arrival counter. Every [pushBlock] / [pushTombstone]
     * increments this and tags the stored entry with its assigned seq. Reads filter
     * by `seq > since` and `ChangesResponse.nextCursor` / `TombstonesResponse.nextCursor` carry
     * the cursor back to the receiver. ⚠ Not `block.hlc.physicalTime > since`: two ticks in one
     * wall-clock ms collide, and a late-pushed property block (e.g. `UploadFile`'s byte block)
     * is stranded.
     *
     * Shared across blocks AND tombstones so the two cursor-spaces are comparable —
     * `SyncScheduler.tickOnce` takes `max(blocks.nextCursor, tombstones.nextCursor)`
     * after both pulls and advances `lastSyncTimestamp` from it.
     */
    private val arrivalCounter = AtomicLong(0L)

    /**
     * Internal storage shape: `(arrivalSeq, block)` pairs. seq is strictly monotonic
     * per [arrivalCounter]; insertion order through `add` matches seq order so the
     * list never needs explicit sort.
     */
    private data class SyncBlockEntry(val seq: Long, val block: SyncBlock)
    private data class DeleteBlockEntry(val seq: Long, val block: DeleteBlock)

    // OwnerId -> List of (arrivalSeq, SyncBlock)
    private val mailboxes = ConcurrentHashMap<String, MutableList<SyncBlockEntry>>()

    // OwnerId -> List of (arrivalSeq, DeleteBlock). Tombstones themselves are persistent
    // — the relay keeps the marker indefinitely so a long-offline device can still observe
    // the delete. The tombstone *body* (i.e. the target SyncBlock's encryptedPayload bytes)
    // gets GC'd by `gcSweep` once every realm device has acked AND the per-volume
    // retention window has elapsed. See `docs/adr/0006-sync-tombstones.md`
    private val tombstones = ConcurrentHashMap<String, MutableList<DeleteBlockEntry>>()

    // OwnerId -> List of PairingRequests
    private val pairingRequests = PairingRequestInbox()

    // OwnerId -> retention window in milliseconds. Per `docs/adr/0006-sync-tombstones.md`
    // §5.7: the simplest defensible shape — a per-owner override map keyed off the relay-side
    // default (`RelayConfig.DEFAULT_RETENTION_WINDOW_MS`, 30 days). Option (a) — a synced
    // `Volume` entity — would need a new entity type; option (b) — a new admin endpoint — adds
    // public surface the GC sweep does not need to be testable. The override hook is the seam
    // where (b) would plug in.
    //
    // 0 = eager GC after quorum; `Long.MAX_VALUE` = never GC. Both are valid per §2.2.
    private val retentionWindowOverridesMs = ConcurrentHashMap<String, Long>()

    // OwnerId -> blockId -> "tombstone-body-dropped" marker. Per §3.3, once GC drops the
    // payload bytes for a SyncBlock, we record the marker so we don't repeat the work and
    // so subsequent `/sync/changes` queries can still indicate the entity was deleted (the
    // tombstone existence is what's being preserved; the payload is gone). The relay never
    // actually serves payload bytes through `getChanges` — it serves the SyncBlock envelope
    // — so "marker preservation" falls out naturally: an envelope with an emptied
    // `encryptedPayload` is the marker. We keep this index for diagnostics + idempotence.
    private val droppedBodies = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * A pending join-flow request as stored and served (F7): [sealedDeviceName] is AEAD ciphertext
     * under a key derived from the pairing secret, and [pairingSecretHash] is a domain-separated
     * SHA-256 of it. The relay holds neither the secret nor the clear name.
     */
    data class PairingRequestData(
        val deviceId: String,
        val sealedDeviceName: ByteArray,
        /**
         * Joiner's ephemeral X25519 ECDH pubkey (asset A8). Used by the host
         * for SAS derivation; never registered.
         */
        val publicKey: ByteArray,
        /**
         * Joiner's persistent Ed25519 device signing pubkey (asset A4). The
         * host signs the master delegation over this and the relay registers
         * it in `KnownDevicesStore` after verifying the delegation.
         */
        val signingPublicKey: ByteArray,
        val pairingSecretHash: String
    )

    override fun pushBlock(ownerId: String, block: SyncBlock) {
        // computeIfAbsent (not getOrPut) — see file header comment.
        val userMailbox = mailboxes.computeIfAbsent(ownerId) { mutableListOf() }
        val seq = arrivalCounter.incrementAndGet()
        synchronized(userMailbox) {
            userMailbox.add(SyncBlockEntry(seq, block))
            mailboxLog.debug("Mailbox -> Pushed block ${block.blockId.get()} for ${logId(ownerId)}. " +
                "Entity hash: ${block.targetEntityIdHash?.get()} seq=$seq")
        }
    }

    /**
     * Pull result: the matching entries (in arrival order) and the cursor the receiver
     * should pass on the next call. [nextCursor] = `max(seq)` of the returned entries
     * for a non-empty result; **stays at [sinceTimestamp]** for an empty result.
     *
     * Why empty must NOT advance to the relay's current counter (subtle): the counter
     * is shared across blocks AND tombstones, so an empty tombstone response with
     * `nextCursor = counter.get()` would skip past blocks pushed since `sinceTimestamp`
     * that the *blocks* pull hasn't returned yet (e.g. arrived between the tick's
     * `pullChanges` and `pullTombstones`). `SyncScheduler.tickOnce` advances
     * `lastSyncTimestamp` to `max(blocks.nextCursor, tombstones.nextCursor)`, so an
     * over-eager tombstone cursor permanently strands those blocks. Keeping the
     * cursor at `sinceTimestamp` on empty is the safe shape — the next pull will
     * pick the blocks up.
     */
    data class ChangesResult(val blocks: List<SyncBlock>, val nextCursor: Long)
    data class TombstonesResult(val tombstones: List<DeleteBlock>, val nextCursor: Long)

    /**
     * One page of the merged log (`MailboxStore.getSyncLog`). [blocks] and [tombstones] are
     * the two kinds present in ONE seq-ordered prefix — kind-separated for the wire only, so
     * neither list is independently meaningful: it is the pair that forms the page.
     *
     * [hasMore] is authoritative, not inferred from the row count.
     */
    data class SyncLogResult(
        val blocks: List<SyncBlock>,
        val tombstones: List<DeleteBlock>,
        val nextCursor: Long,
        val hasMore: Boolean,
    )

    /**
     * Returns every block whose arrival seq is `> sinceTimestamp` (not its HLC physical time —
     * see [arrivalCounter]).
     *
     * ⚠ No eager-vs-OnDemand filter: property identity is off the wire (zero-trust:
     * `docs/sync/threat-model.md` §"Property-ID exposure to relay"), so the relay cannot tell a
     * property block from any other. That distinction would need a different wire-level marker;
     * no production schema pushes OnDemand-marked properties into `entity.properties`.
     */
    override fun getChanges(ownerId: String, sinceTimestamp: Long, limit: Int): ChangesResult {
        val userMailbox = mailboxes[ownerId]
        if (userMailbox == null) {
            mailboxLog.debug("Mailbox -> Changes for ${logId(ownerId)} since $sinceTimestamp: 0 blocks (no mailbox) cursor=$sinceTimestamp")
            return ChangesResult(emptyList(), sinceTimestamp)
        }
        return synchronized(userMailbox) {
            // Sort before taking: entries are appended in seq order today, but the page cap
            // only yields a correct *prefix* — and hence a `nextCursor` that nothing beyond
            // has been skipped under — if the ordering is explicit. Mirrors the SQL backend's
            // `ORDER BY seq LIMIT ?`.
            val matched = userMailbox.filter { it.seq > sinceTimestamp }
                .sortedBy { it.seq }
                .take(limit)
            val nextCursor = matched.maxOfOrNull { it.seq } ?: sinceTimestamp
            mailboxLog.debug("Mailbox -> Changes for ${logId(ownerId)} since $sinceTimestamp: ${matched.size} blocks cursor=$nextCursor")
            ChangesResult(matched.map { it.block }, nextCursor)
        }
    }

    override fun pushTombstone(ownerId: String, block: DeleteBlock): Long {
        val list = tombstones.computeIfAbsent(ownerId) { mutableListOf() }
        val seq = arrivalCounter.incrementAndGet()
        synchronized(list) {
            list.add(DeleteBlockEntry(seq, block))
            mailboxLog.debug(
                "Mailbox -> Pushed tombstone ${block.blockId.get()} for ${logId(ownerId)} targeting " +
                        "entity ${block.targetEntityIdHash.get()} seq=$seq"
            )
        }
        return seq
    }

    override fun getTombstones(ownerId: String, sinceTimestamp: Long, limit: Int): TombstonesResult {
        val list = tombstones[ownerId]
        if (list == null) {
            mailboxLog.debug("Mailbox -> Tombstones for ${logId(ownerId)} since $sinceTimestamp: 0 entries (no list) cursor=$sinceTimestamp")
            return TombstonesResult(emptyList(), sinceTimestamp)
        }
        return synchronized(list) {
            val matched = list.filter { it.seq > sinceTimestamp }
                .sortedBy { it.seq }
                .take(limit)
            val nextCursor = matched.maxOfOrNull { it.seq } ?: sinceTimestamp
            mailboxLog.debug("Mailbox -> Tombstones for ${logId(ownerId)} since $sinceTimestamp: ${matched.size} entries cursor=$nextCursor")
            TombstonesResult(matched.map { it.block }, nextCursor)
        }
    }

    /**
     * The merged prefix. Both per-owner lists are snapshotted under their own monitors, then
     * merged by seq — the counter is shared, so the orders are directly comparable.
     *
     * ⚠ `hasMore` is computed from the **snapshots this call took**, not from a fresh look at
     * the store. A push landing mid-call must therefore not be able to make it read `false`
     * while rows above the cursor exist — and it cannot: a later push gets a higher seq, so
     * it is invisible to these snapshots and simply arrives on the next pull, exactly as a
     * push landing one microsecond after the call returns would. The value answers "is this
     * page truncated", which is a property of the page.
     */
    override fun getSyncLog(ownerId: String, sinceTimestamp: Long, limit: Int): SyncLogResult {
        val blockEntries = mailboxes[ownerId]
            ?.let { list -> synchronized(list) { list.filter { it.seq > sinceTimestamp } } }
            ?: emptyList()
        val tombstoneEntries = tombstones[ownerId]
            ?.let { list -> synchronized(list) { list.filter { it.seq > sinceTimestamp } } }
            ?: emptyList()

        // Merge by seq and cut ONE page across both kinds — the property that makes a single
        // cursor sound. Sorting explicitly rather than relying on append order, for the same
        // reason getChanges does: a page cap only yields a correct prefix under an explicit
        // order.
        val pageSeqs = (blockEntries.map { it.seq } + tombstoneEntries.map { it.seq })
            .sorted()
            .take(limit)
        val cutoff = pageSeqs.lastOrNull()

        if (cutoff == null) {
            mailboxLog.debug("Mailbox -> Log for ${logId(ownerId)} since $sinceTimestamp: empty cursor=$sinceTimestamp")
            return SyncLogResult(emptyList(), emptyList(), sinceTimestamp, hasMore = false)
        }

        val blocks = blockEntries.filter { it.seq <= cutoff }.sortedBy { it.seq }.map { it.block }
        val tombs = tombstoneEntries.filter { it.seq <= cutoff }.sortedBy { it.seq }.map { it.block }
        val hasMore = blockEntries.any { it.seq > cutoff } || tombstoneEntries.any { it.seq > cutoff }

        mailboxLog.debug(
            "Mailbox -> Log for ${logId(ownerId)} since $sinceTimestamp: ${blocks.size} blocks + " +
                "${tombs.size} tombstones cursor=$cutoff hasMore=$hasMore"
        )
        return SyncLogResult(blocks, tombs, cutoff, hasMore)
    }

    /**
     * The backward page — mirror of [getSyncLog]. Same union, same one-page-across-both-kinds cut;
     * only the direction and the bound differ.
     */
    override fun getSyncLogBefore(ownerId: String, beforeSeq: Long, limit: Int): SyncLogResult {
        val blockEntries = mailboxes[ownerId]
            ?.let { list -> synchronized(list) { list.filter { it.seq < beforeSeq } } }
            ?: emptyList()
        val tombstoneEntries = tombstones[ownerId]
            ?.let { list -> synchronized(list) { list.filter { it.seq < beforeSeq } } }
            ?: emptyList()

        // Take the HIGHEST `limit` seqs below the bound, then keep the page's own ascending order —
        // the caller applies a page as a unit, and within it HLC decides, so intra-page order is
        // irrelevant (`SyncLogResponse`). What matters is that the page is a contiguous suffix of
        // everything below `beforeSeq`, so walking `nextCursor` downward never skips a row.
        val pageSeqs = (blockEntries.map { it.seq } + tombstoneEntries.map { it.seq })
            .sortedDescending()
            .take(limit)
        val floor = pageSeqs.lastOrNull()

        if (floor == null) {
            mailboxLog.debug("Mailbox -> Backfill for ${logId(ownerId)} before $beforeSeq: empty")
            return SyncLogResult(emptyList(), emptyList(), beforeSeq, hasMore = false)
        }

        val blocks = blockEntries.filter { it.seq >= floor }.sortedBy { it.seq }.map { it.block }
        val tombs = tombstoneEntries.filter { it.seq >= floor }.sortedBy { it.seq }.map { it.block }
        val hasMore = blockEntries.any { it.seq < floor } || tombstoneEntries.any { it.seq < floor }

        mailboxLog.debug(
            "Mailbox -> Backfill for ${logId(ownerId)} before $beforeSeq: ${blocks.size} blocks + " +
                "${tombs.size} tombstones floor=$floor hasMore=$hasMore"
        )
        return SyncLogResult(blocks, tombs, floor, hasMore)
    }

    /**
     * Per-owner indexed view: `targetEntityIdHash → highest-HLC tombstone observed`.
     * The "highest HLC" rule mirrors the proxy-side `TombstoneRegistry` (§3.4 recreate
     * semantics): if an entity was tombstoned, recreated, and tombstoned again, only the
     * latest tombstone matters for GC. Returns an empty map if the owner has no tombstones.
     */
    override fun tombstoneIndex(ownerId: String): Map<String, DeleteBlock> {
        val list = tombstones[ownerId] ?: return emptyMap()
        return synchronized(list) {
            list.fold(mutableMapOf<String, DeleteBlock>()) { acc, entry ->
                val t = entry.block
                val key = t.targetEntityIdHash.get()
                val existing = acc[key]
                if (existing == null ||
                    t.hlc.physicalTime.get() > existing.hlc.physicalTime.get()
                ) {
                    acc[key] = t
                }
                acc
            }
        }
    }

    /**
     * Count of Device-targeted tombstones for [ownerId]. The relay's authoritative view of
     * the realm's "current epoch," surfaced by `GET /sync/realm-epoch` per
     * `docs/proposals/sync-relay-api.md` §3.6 to close the recovery-race carve-out from
     * `docs/adr/0007-sync-device-management.md` (a freshly-recovered device would otherwise stamp
     * `admittedAtEpoch = 0` because it hasn't synced tombstones yet).
     *
     * "Device-targeted" is identified by [DeleteBlock.targetEntityType] == [Device.ID]
     * (= `tenshu/identity/device`) — the relay reads the same envelope field for the
     * push-rejection logic in [SyncRouting], so this leans on the existing metadata
     * surface rather than adding a new one.
     *
     * Returns 0 if the owner has no Device tombstones (or no tombstones at all).
     */
    override fun deviceTombstoneCount(ownerId: String): Long {
        val list = tombstones[ownerId] ?: return 0L
        return synchronized(list) {
            list.count {
                it.block.targetEntityType.get() == org.tenshu.identity.Device.ID
            }.toLong()
        }
    }

    /**
     * Per-owner override of the retention window in milliseconds. Per `docs/adr/0006-sync-tombstones.md`
     * §5.7 — until a synced `Volume` entity exists or an admin endpoint ships, this is the
     * seam where retention is configured. `Long.MAX_VALUE` disables GC for that owner;
     * `0` makes GC eager (drop body the moment quorum is reached). Test/admin hook.
     */
    override fun setRetentionWindowMs(ownerId: String, retentionMs: Long) {
        retentionWindowOverridesMs[ownerId] = retentionMs
    }

    /**
     * Returns the effective retention window for [ownerId] — the override if set,
     * otherwise [RelayConfig.DEFAULT_RETENTION_WINDOW_MS].
     */
    override fun retentionWindowMs(ownerId: String): Long =
        retentionWindowOverridesMs[ownerId] ?: RelayConfig.DEFAULT_RETENTION_WINDOW_MS

    /** Owners with at least one tombstone — the GC sweep's iteration set. */
    override fun tombstoneOwnerIds(): Set<String> = tombstones.keys.toSet()

    // `acknowledgementSet` + `gcSweep` are shared default logic on [MailboxStore],
    // expressed over the storage primitives — one impl for both backends.

    override fun dropBodiesFor(
        ownerId: String,
        targetEntityIdHash: String,
        tombstoneHlcPhysical: Long
    ): Int {
        val mailbox = mailboxes[ownerId] ?: return 0
        val droppedSet = droppedBodies.computeIfAbsent(ownerId) { mutableSetOf() }
        var count = 0
        synchronized(mailbox) {
            // Replace each matching SyncBlock with an envelope-only proxy: same blockId,
            // ownerId, hlc, target* fields, but `encryptedPayload` is empty. Keeps
            // `getChanges` returning the entry (so receivers see a placeholder marker)
            // while freeing the bytes. Using a wrapper rather than `SyncBlock.create`
            // means we don't need a TContext on this code path — the relay's mailbox is
            // a passive store, not a Composite-construction site. The entry keeps its
            // original arrival seq so `getChanges` cursor semantics are unaffected by GC.
            for (i in mailbox.indices) {
                val entry = mailbox[i]
                val block = entry.block
                val targetHash = block.targetEntityIdHash?.get() ?: continue
                if (targetHash != targetEntityIdHash) continue
                if (block.hlc.physicalTime.get() >= tombstoneHlcPhysical) continue
                if (block.encryptedPayload.get().isEmpty()) continue
                val blockId = block.blockId.get()
                val newlyDropped = synchronized(droppedSet) { droppedSet.add(blockId) }
                if (!newlyDropped) continue
                mailbox[i] = SyncBlockEntry(entry.seq, EnvelopeOnlySyncBlock(block))
                count++
            }
        }
        return count
    }

    /**
     * Test helper — returns the set of blockIds whose payload has been GC'd for
     * [ownerId]. Lets tests assert on the bookkeeping without re-running [gcSweep].
     */
    override fun droppedBlockIds(ownerId: String): Set<String> =
        droppedBodies[ownerId]?.toSet() ?: emptySet()

    /**
     * Return every block the relay holds for [entityIdHash], regardless of
     * whether it's an entity update or a property update. The relay can't
     * (and mustn't) tell the difference — that distinction lives inside
     * the encrypted `SyncBlock.encryptedPayload` and is recovered by the
     * client after decryption. See `docs/sync/threat-model.md`
     * §"Property-ID exposure to relay".
     */
    override fun fetchEntityBlocks(ownerId: String, entityIdHash: String): List<SyncBlock> {
        val userMailbox = mailboxes[ownerId] ?: return emptyList()
        return synchronized(userMailbox) {
            val result = userMailbox
                .filter { it.block.targetEntityIdHash?.get() == entityIdHash }
                .map { it.block }
            mailboxLog.debug("Mailbox -> Fetch entity blocks for $entityIdHash: ${result.size} block(s)")
            result
        }
    }

    override fun addPairingRequest(ownerId: String, request: PairingRequestData) {
        pairingRequests.add(ownerId, request)
        mailboxLog.debug("Mailbox -> Added pairing request for ${logId(ownerId)} from device ${logId(request.deviceId)}")
    }

    override fun getPairingRequests(ownerId: String): List<PairingRequestData> = pairingRequests.get(ownerId)

    override fun removePairingRequest(ownerId: String, pairingSecretHash: String) {
        pairingRequests.remove(ownerId, pairingSecretHash)
    }

    override fun addPairingResponse(
        pairingSecretHash: String,
        encryptedKey: ByteArray,
        registration: PairingResponseInbox.PendingRegistration?,
    ): PairingResponseInbox.PostResult {
        val result = pairingResponses.post(pairingSecretHash, encryptedKey, registration)
        mailboxLog.debug("Mailbox -> Pairing response: $result") // never the hash: it is the rendezvous capability
        return result
    }

    /**
     * Peek-only read per `sync-relay-api.md` §3.3: the new device polls and gets the
     * encrypted master key, but the entry stays in the mailbox until the device
     * explicitly [ackPairingResponse]s. Fixes the destructive-read footgun where a
     * device that crashes between fetch and processing loses the pairing.
     */
    override fun pendingPairingAdmittedAt(ownerId: String, deviceId: String) =
        pairingResponses.pendingAdmittedAt(ownerId, deviceId)

    override fun getPairingResponse(pairingSecretHash: String): PairingResponseInbox.Delivery? {
        return pairingResponses.peek(pairingSecretHash)
    }

    /**
     * Explicit ack: the new device confirms it has consumed the response payload.
     * Removes the entry and retires the hash for 24 h ([PairingResponseInbox]).
     */
    override fun ackPairingResponse(pairingSecretHash: String) {
        pairingResponses.ack(pairingSecretHash)
    }

    override fun isPairingResponseConsumed(pairingSecretHash: String): Boolean =
        pairingResponses.isConsumed(pairingSecretHash)

    /**
     * Account closure (F12): blocks, tombstones, pending pairing requests, the retention override and
     * the body-drop index, and the owner's queued pairing responses — see [MailboxStore].
     * Counts blocks + tombstones + requests, matching the SQLite twin.
     */
    override fun eraseOwner(ownerId: String): Int {
        org.tenshu.sync.relay.mailbox.requireErasableOwner(ownerId)
        val blocks = mailboxes.remove(ownerId)?.let { synchronized(it) { it.size } } ?: 0
        val deletes = tombstones.remove(ownerId)?.let { synchronized(it) { it.size } } ?: 0
        retentionWindowOverridesMs.remove(ownerId)
        droppedBodies.remove(ownerId)
        return blocks + deletes + pairingRequests.eraseOwner(ownerId) + pairingResponses.eraseOwner(ownerId)
    }
}
