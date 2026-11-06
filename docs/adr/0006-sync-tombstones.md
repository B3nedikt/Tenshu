# ADR 0006 — Deletion via `DeleteBlock` tombstones

- **Status:** Accepted, implemented (closed out 2026-05-08)
- **Date:** 2026-05-04 (Phases 1–3) → 2026-05-08 (Phase 4 GC sweep)
- **Owner:** Benedikt
- **Supersedes:** —
- **Superseded by:** —

## Context

Pre-tombstones, deletion was a local-only operation in
`DeleteFileImplementation` and its peers: the entity was removed from the
parent collection, child blocks recursively deleted, raw byte storage
cleared, and a `*DeletedNotification` emitted. Correct on the device that
ran the delete; **invisible to every other realm device**. A second device
polling `/sync/changes` saw no signal that the entity was gone, and
continued to surface it from its own local DB. Sync-service revocation
(`SyncMailbox.revokeDevice`) was an empty stub — there was no way to
remove a compromised device from the realm. A long-offline device coming
back online had no way to learn that an entity had been deleted while it
was gone.

Three concrete things were broken or missing:

1. **Cross-device deletion propagation** — the central case.
2. **Device revocation** — flagged in `threat-model.md` §9 line 499 as the
   I4 caveat.
3. **Storage hygiene** — without a quorum signal, the relay couldn't
   safely garbage-collect any block (deleted or otherwise). Every block
   sat in the mailbox forever.

The fix is explicit `DeleteBlock`s carried as a dedicated wire-type-level
block kind, with a relay-side GC policy that respects both correctness
(every realm device must get a chance to observe the delete) and
user-facing backup expectations (the body sticks around for a
configurable retention window after quorum).

## Decision

`DeleteBlock` is a first-class peer of `SyncBlock` — distinct schema id,
wire-distinct envelope, indexed separately by the relay for GC purposes.
Receiving devices short-circuit any merge for the target entity at ingest
time. Relay-side body GC is gated on (quorum acknowledgement) AND
(retention window). API + protocol details live in
[`docs/sync/design.md` §2.6](../sync/design.md#26-deletion-via-tombstones).

Eight anchor decisions shape the design:

1. **`DeleteBlock` is a dedicated wire-type-level block kind**, not an
   envelope flag, not an `isDeleted` field-flip on the entity. Distinct
   schema id; the relay indexes by kind. Separates concerns: the relay
   can GC tombstone target bodies independently, the merge dispatcher
   short-circuits on tombstone, and device revocation becomes a special
   case of the same mechanism. Consistent with the eager/lazy block-kind
   choice in [`sync-relay-api.md`](../proposals/sync-relay-api.md) —
   wire-type kinds rather than envelope flags.
2. **Body GC trigger: quorum + retention window, both required.** Quorum
   = every currently-known realm device has ack'd the tombstone via
   `/sync/changes` HLC-tip piggyback (depends on relay-plan Phase 3.1).
   Retention window = a configurable per-volume window elapsed since the
   tombstone's HLC; default 30 days. Setting to 0 = eager GC after
   quorum; "forever" = never GC the body. Both conditions must hold.
3. **Long-offline-device behaviour: tombstone is authoritative.** A
   device returning after retention sees the tombstone but not the body
   and deletes locally on merge. The body is irrecoverable; finite
   retention is the user's choice and is documented up front.
4. **Tombstone authenticity follows the existing block-authenticity
   story.** Not separately signed — same JWT-on-the-wire today, same
   future per-block Ed25519 path. Authority follows realm membership:
   any device with an admitted A4 signing key can issue a tombstone.
   *Amended 2026-10-27* ([`sync-envelope-authentication.md`](../proposals/sync-envelope-authentication.md),
   part of the relay-protocol-1 release baseline): the "existing story" turned out to authenticate nothing on a tombstone, and
   the per-block Ed25519 path was never built. A tombstone now carries `authTag`, an HMAC under a
   child of the **realm** identity key, verified before ingest touches anything. Authority is
   therefore *realm-key holder* — still any admitted device, but no longer the relay.
5. **Short-circuit at the ingest layer, not in the merger.**
   `SemanticMerger.merge(existing, incoming)` operates on materialised
   entities and has no access to "all blocks for this entity". The
   short-circuit lives in `SyncProcessor.mergeData` *before* the merger
   is invoked. The check is a single `tombstoneRegistry.tombstoneHlcFor`
   lookup.
6. **Tombstones themselves don't get GC'd**, only their target bodies.
   The relay keeps `DeleteBlock`s indefinitely — they're the chain of
   provenance for any device that comes online after retention.
7. **One tombstone per delete *operation*, not per cascaded child
   entity.** A recursive folder delete emits one `DeleteBlock` for the
   folder; receiving devices' local recursive-delete handles the
   cascade. Children's prior `SyncBlock`s remain in the relay until the
   GC drops them via the same quorum+retention path.
8. **Wire transport via dedicated ops, not folded into `pushBlock` /
   `getChanges`.** Shipped as `PushTombstone` + `GetTombstones`
   operations over `/sync/push-tombstone` + `/sync/tombstones`
   endpoints. Typing-driven deviation from the original §3.2 sketch —
   the dedicated ops kept the schemas precise (one type per endpoint)
   and let the relay's GC indexing stay clean.

## Consequences

- **`DeleteBlock` is realm-visible by design.** The relay can see that a
  tombstone exists for `targetEntityIdHash X` at `hlc Y`. The body
  encrypted under the realm's keys never leaves the realm — but the
  *existence* of a delete is metadata the relay learns. Documented in
  `threat-model.md` §A6.
- **Revocation becomes a special-case tombstone.** No separate
  revocation-block kind. `DeleteBlock` targeting a `Device` entity *is*
  the revocation. Epoch rotation is handled at the volume-key layer.
  This closed the `threat-model.md` §9 I4 caveat.
- **`TombstoneRegistry` is a new persistent component** in
  `:sync-service-proxy`. Tombstone HLCs survive app restarts; a delete
  observed yesterday still short-circuits today's straggler updates.
- **Three new threat-model entries**: T-T-08 (relay drops or delays a
  tombstone — transport), T-K-05 (forged tombstone by revoked device),
  plus T-K-01 / T-K-02 wording updates. All present in `threat-model.md`.
- **Recreate semantics are well-defined** — a future block with `hlc >
  tombstone.hlc` is accepted; the entity is recreated. CRDT-standard.

## When to revisit

Re-open when any of these are true:

1. **A real user-facing UX surface needs the tombstone existence to be
   suppressed** (e.g. "permanently delete now" that needs to skip the
   30-day retention). Today the retention is per-volume and uniform;
   a per-delete override would change the GC algorithm.
2. **Per-block Ed25519 signing lands platform-wide.** Tombstones inherit
   it; the existing "authenticity follows block-authenticity story" line
   stays correct, but the threat-model amendment can sharpen "forged
   tombstone" handling.
3. **The "one tombstone per delete operation" choice produces a
   correctness gap** under partition + recreate scenarios. We checked
   this for the recursive folder case; if a future syncable type has
   asymmetric child semantics (e.g. children can survive the parent
   meaningfully), the rule may need amendment.

## Alternatives considered

- **`isDeleted` field-flip on the entity.** Rejected — collides with the
  encrypted-payload story (the relay would have to read inside the
  payload to GC), can't carry the HLC needed for merge short-circuit
  cleanly, and conflates two distinct lifecycle states. Wire-distinct
  block kind keeps the merge logic obvious.
- **Per-child tombstones for recursive deletes.** Considered for
  symmetry — Device A deletes folder `/photos` with 5000 files, each
  child emits its own `DeleteBlock`. Rejected: 5000 extra blocks, no
  semantic gain (receiving devices replay the same recursive-delete
  logic locally and reach the same end state via one tombstone), and
  the relay's GC math gets harder. One tombstone per operation is the
  conservation law.
- **Eager body GC (no retention window).** Drop bodies the instant
  quorum is reached. Rejected — backup-style "I deleted this yesterday
  by accident, please restore" UX vanishes. A configurable retention
  window (default 30 days) preserves that affordance without committing
  to "forever".
- **Body GC without quorum.** Drop bodies after a fixed time since
  tombstone. Rejected — a device offline at the tombstone time would
  never see the deletion *or* the body, so its next sync surfaces a
  ghost entity that nothing in the realm can resolve. Quorum is the
  correctness floor.
- **Fold tombstones into `pushBlock` / `getChanges` as an envelope
  flag.** The original sketch in §3.2 of the proposal. Rejected during
  implementation: the typed schema for `DeleteBlock` deserved a typed
  op, and the relay's GC indexing wanted a clean separation. Carried
  one extra ops surface to keep the schema discipline.
- **Tombstone retention forever (no GC).** Considered for the
  simplest-possible relay. Rejected — storage growth is unbounded and a
  motivated relay operator can mine deleted-content metadata
  indefinitely. Time-bounded retention narrows the metadata-mining
  window to the retention period and forces the relay to commit to a
  forgetting deadline.

## References

- `docs/sync/design.md` §2.6 — protocol semantics + merge short-circuit
  + relay GC + revocation interaction.
- `docs/sync/threat-model.md` — T-T-08, T-K-01/02/05 reference the
  tombstone mechanism; §A6 spells out the relay-visible metadata.
- `:sync-service/.../DeleteBlock.kt` — schema declaration.
- `:sync-service-proxy/.../TombstoneRegistry.kt` — persistent
  per-`targetEntityIdHash` HLC map.
- `:sync-service-proxy/.../SyncProcessor.kt::mergeData` — the
  short-circuit call site.
- `:sync-service-proxy/.../SyncMailbox.kt::gcSweep` — nightly body GC
  driver.
- [ADR 0007](0007-sync-device-management.md) — uses `DeleteBlock(Device)` as the
  revocation primitive.
- `sync-relay-api.md` — `PushTombstone` / `GetTombstones` ops +
  `/sync/push-tombstone` + `/sync/tombstones` endpoints; relay-side GC
  ack tracking.
- [ADR 0017](0017-file-content-two-plane-channel.md) — `DeleteBlock` of
  a manifest entity is the trigger for content-store refcount GC; the
  two GCs compose (ordered-log + content-plane).
