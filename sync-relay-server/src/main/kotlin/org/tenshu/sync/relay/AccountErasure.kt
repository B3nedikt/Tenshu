package org.tenshu.sync.relay

import org.tenshu.sync.relay.mailbox.OwnerBytesStore
import org.tenshu.sync.relay.mailbox.OwnerScopedStore
import org.tenshu.sync.relay.mailbox.requireErasableOwner

/**
 * Everything the relay holds for one owner, erased — the body of account closure past grace
 * (`retention-quota-model.md`, `relay-metadata-minimisation.md` F12).
 *
 * ⚠⚠ Closure is not `reclaimOwner`, which drops `content_blob` rows and bytes and nothing else:
 * the ordered log, the device roster, tips, the CR-2 edge/ref/freed/clock tables, shares,
 * invites, grants, rendezvous sessions, pairing offers and the realm row itself would all outlive
 * the "deletion" indefinitely.
 *
 * ## What closure does to each owner-scoped table (SQLite; the in-memory twins match)
 *
 * | Table | On closure | Why |
 * |---|---|---|
 * | `realm` | delete | The account is gone. ⚠ Genesis is master-self-signed and `reassertGenesis` self-heals, so the owner *could* re-create it — `erased_realm` below is what refuses that. |
 * | `device` | delete | No device of a closed realm may authenticate. |
 * | `hlc_tip` | delete | Per-device sync metadata. |
 * | `sync_block`, `delete_block` | delete | The ordered log (tier 1) — envelopes and their ciphertext. |
 * | `content_blob` + bytes | delete (two-phase) | Tier 2. Index rows move to `content_free_queue` in the row transaction; bytes and queue rows go after the commit. |
 * | `content_free_queue` | delete (after bytes) | It is the byte-deletion ledger — see [OwnerBytesStore]. |
 * | `content_ref`, `content_edge`, `holder_watermark`, `content_freed`, `content_unreferenced` | delete | CR-2 refcount metadata: which opaque holder references which content. |
 * | `share_record` + `share/<id>/<n>` bytes | delete (two-phase) | Revoked in the row transaction (links die at commit); row and chunks deleted after. |
 * | `invite`, `grant_mailbox` | delete | Pending handshakes this owner opened. |
 * | `epc_session`, `epc_frame` | delete | Rendezvous sessions (frames joined via the owner's sessions). |
 * | `pairing_offer_response`, `pairing_offer_announce` | delete | Pairing rendezvous rows (TTL'd anyway). |
 * | `member_admission` (`realm_id` = this owner) | delete | Member devices admitted *into this realm*. |
 * | `removed_device` | delete | F2′ never-return ledger — moot once the realm can never return. |
 * | `admission_revocation` | delete | A33 revocations — same. |
 * | `member_revocation` (`realm_id` = this owner) | delete | Revoked member authorities — same. |
 * | `erased_realm` | **write, keep** | The one row an erased owner leaves ([KEPT_TABLES]). |
 * | `share_takedown` | **keep** | The operator's append-only record of acting on an abuse notice (acceptable-use §3): opaque ids, a reason code, the operator's note. Closure must not erase the evidence that a notice was handled ([KEPT_TABLES]; kept 3 years from the takedown, `take-down-a-share-link.md`). |
 *
 * ⚠⚠ **Why one tombstone replaces the three revocation fences.** `ownerId = sha256(masterPub)`, and
 * every capability those fences revoke — a device's master delegation, an A33 admission capability, a
 * member's Q30 capability — is a signature by that same master key, which the owner (and every device
 * that held it) still has. Were the realm re-created after erasure, the old signatures would verify
 * again. `erased_realm` makes `POST /sync/realm` answer 410 Gone for that owner, so it never is, and
 * with no `realm` row every admission, member and recovery route refuses the owner — the fences have
 * nothing left to guard. ⚠ Keeping the fences instead is the alternative; do not reintroduce them
 * without also dropping the tombstone. ⚖️ `erased_realm` (an opaque owner id and a timestamp) is kept
 * while the service runs (`retention-quota-model.md` §3.3a).
 *
 * Not owner-attributable, therefore untouched: the in-memory pairing-response map (keyed by pairing
 * secret), hub offers (an unpaired hub, keyed by secret hash) and recovery challenges (random bytes
 * issued to anyone since F8, keyed by nothing) — all TTL-bounded.
 *
 * ## Order and crash safety
 * 1. Every store's [OwnerScopedStore.eraseOwner] runs inside **one** [transaction] (SQLite: the one
 *    `RelayDatabase`), so the rows commit or roll back together.
 * 2. Then each [OwnerBytesStore] deletes its bytes from the ledger phase 1 left, and the ledger.
 *
 * A crash or throw in 1 rolls everything back; in 2 it leaves ledger rows, and the next closure post
 * (commerce re-posts every sweep) finishes them. Re-running on an erased owner is a no-op that
 * reports zeros. ⚠ Bytes a crash orphaned *before* this ever ran (a PUT that died between its byte
 * write and its index row) have no ledger entry and are not reached — `BlobStore` cannot enumerate
 * by owner. That residual predates F12 and is listed as an open item.
 */
class AccountErasure(
    private val stores: List<OwnerScopedStore>,
    /** Runs the row phase as one unit. Identity for the in-memory set; `RelayDatabase.inTransaction` for SQLite. */
    private val transaction: (() -> Unit) -> Unit = { it() },
) {

    /** What one [eraseOwner] call removed — measured by the stores, so an audit line cannot drift. */
    data class Report(val rowsErased: Int, val blobsDropped: Int, val bytesFreed: Long) {
        companion object {
            val NOTHING = Report(0, 0, 0L)
        }
    }

    fun eraseOwner(ownerId: String): Report {
        requireErasableOwner(ownerId)
        var rows = 0
        transaction { rows = stores.sumOf { it.eraseOwner(ownerId) } }
        var blobs = 0
        var bytes = 0L
        for (store in stores.filterIsInstance<OwnerBytesStore>()) {
            val freed = store.eraseOwnerBytes(ownerId)
            blobs += freed.blobsDropped
            bytes += freed.bytesFreed
        }
        return Report(rowsErased = rows, blobsDropped = blobs, bytesFreed = bytes)
    }

    companion object {
        /**
         * The table names [AccountErasure] deliberately leaves behind for an erased owner. Pinned here so
         * the schema-enumeration test (`AccountErasureSchemaTest`) and this KDoc cannot disagree.
         */
        val KEPT_TABLES: Set<String> = setOf("erased_realm", "share_takedown")
    }
}

/**
 * Every owner-scoped store the relay runs, in the order closure erases them — the one list both
 * `Application.module` and the closure tests build [AccountErasure] from, so they cannot drift.
 *
 * ⚠⚠ A new owner-keyed store is a new **required** parameter here. Absent on purpose:
 * `HubOfferRegistry` (an unpaired hub — no owner to erase). See the table in [AccountErasure].
 */
fun ownerScopedStoresOf(
    knownDevices: org.tenshu.sync.relay.auth.DeviceRegistry,
    realmStore: org.tenshu.sync.relay.mailbox.RealmRegistry,
    hlcTipStore: org.tenshu.sync.relay.mailbox.HlcTipRegistry,
    syncMailbox: MailboxStore,
    contentEdgeStore: org.tenshu.sync.relay.mailbox.ContentEdgeRegistry,
    contentRefStore: org.tenshu.sync.relay.mailbox.ContentRefRegistry,
    contentFreeing: org.tenshu.sync.relay.mailbox.ContentFreeing,
    contentBlobStore: org.tenshu.sync.relay.mailbox.ContentBlobRegistry,
    shareStore: org.tenshu.sync.relay.mailbox.ShareRegistry,
    inviteStore: org.tenshu.sync.relay.mailbox.InviteRegistry,
    grantStore: org.tenshu.sync.relay.mailbox.GrantRegistry,
    membershipStore: org.tenshu.sync.relay.mailbox.MembershipRegistry,
    rendezvousStore: org.tenshu.sync.relay.mailbox.RendezvousRegistry,
    pairingOfferStore: org.tenshu.sync.relay.mailbox.PairingOfferRegistry,
    admissionRevocationStore: org.tenshu.sync.relay.mailbox.AdmissionRevocationRegistry,
): List<OwnerScopedStore> = listOf(
    knownDevices, realmStore, hlcTipStore,
    syncMailbox, contentEdgeStore, contentRefStore, contentFreeing, contentBlobStore, shareStore,
    inviteStore, grantStore, membershipStore, rendezvousStore, pairingOfferStore, admissionRevocationStore,
)

/** The row-phase transaction for a SQLite relay: every store's deletes commit or roll back together. */
fun org.tenshu.sync.relay.storage.RelayDatabase.erasureTransaction(): (() -> Unit) -> Unit =
    { block -> inTransaction { block() } }
