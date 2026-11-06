# Tenshu Sync Service: Module Conventions

This file covers the `:sync-service*` module family and `:sync-relay-server`. It was split out
of `docs/agent.md` (which previously covered sync inline) so sync-specific conventions have a
home; platform-wide patterns stay in [`docs/agent.md`](../docs/agent.md).

## Read these first

The sync architecture is documented, not folklore. In order of authority:

- **[Threat Model](../docs/sync/threat-model.md)** — the security contract. Changes touching
  assets A1–A4 or A9, the relay's data shape, or the sync protocol require a threat-model
  amendment **in the same PR** (AGENTS.md house rule 2).
- **[Sync Design](../docs/sync/design.md)** — block format, merge pipeline, tombstones (§2.6 +
  [ADR 0006](../docs/adr/0006-sync-tombstones.md)), synced device management (§2.2 +
  [ADR 0007](../docs/adr/0007-sync-device-management.md)).
- **[Pairing Flow](../docs/sync/pairing-flow.md)** + [Sync Roadmap](../docs/sync/roadmap.md).
- In-flight proposals: [relay API](../docs/proposals/sync-relay-api.md),
  [relay hardening plan](../docs/proposals/sync-backend-relay-plan.md),
  [merge strategies](../docs/proposals/sync-merge-strategies.md) (not started; renames
  `SemanticMergerRegistry` → `MergerRegistry`). Status pins live in `AGENTS.md`.

## Module layout

Follows the standard service split (`docs/agent.md` §8), with no `-test-utils` module today:

- **`:sync-service`** — schemas + operation interfaces. Core data-plane operations:
  `PushBlock`, `GetChanges`, `PushTombstone`, `SyncNow`; plus the pairing (`InitiatePairing`,
  `ApprovePairingRequest`, `RespondToPairingOffer`, …), realm (`CreateRealm`,
  `CreateSharedRealm`), recovery (`RecoveryClaim`, `GetRecoveryPhrase`, `UnlockLocal`),
  shared-realm-grant (`GrantSharedRealmMembership`, `AcceptSharedRealmGrant`,
  `RemoveSharedRealmMember`) and background-schedule (`Schedule`, `ListScheduled`,
  `TransitionTask`, `RenewTaskLease`) families.
  ⚠ `AcknowledgeBlock` and `FetchOnDemandProperty` were removed and are not operations any
  more — on-demand content is served through the `OnDemandResolution` component and the
  relay's content plane ([relay API](../docs/proposals/sync-relay-api.md)).
- **`:sync-service-common`** — DTOs, `SyncManager`, and `SemanticMerger` (see below).
- **`:sync-service-client`** — ROs (e.g. `ApprovePairingRequestRo` is the canonical
  RO-chain-construction example cited in `docs/agent.md` §2).
- **`:sync-service-proxy`** — operation implementations, `SyncObserver`, scheduling.
- **`:sync-relay-server`** — Ktor-on-Netty relay. **Ciphertext only** (AGENTS.md house
  rule 1, Zero-Trust Backend): server-side logic that decrypts user data is a hard reject.

## Conventions & seams

- **Outbound emit is decoupled from `saveData`.** `SyncObserver.onDataSaved` only enqueues;
  a single consumer coroutine does serialize + AEAD + HLC tick. Tests that inspect
  `pendingBlocks` right after `saveData` must join via `SyncObserver.awaitEmitIdle()`.
  Detail: [`core-proxy/proxy-agent.md`](../core-proxy/proxy-agent.md) §2 +
  [ADR 0015](../docs/adr/0015-loop-db-stream-unification.md).
- **Semantic merging.** `SemanticMerger` (`:sync-service-common`) is the per-type merge seam,
  registered on `SemanticMergerRegistry`. `FolderMerger` (`:file-service-proxy`) was the first;
  there are now **11 across six services** (calendar, file, health, place, sync, timeline).
- **⚠⚠ A merger reads a collection field with `resolveListField`, never `getField`.**
  `merge`'s two sides arrive in different representations — `existing` often carries lazy
  database references, `incoming` is materialised off the wire — so a direct read works on
  one side and silently yields an empty or wrong member set on the other. This was the
  long-standing "lazy-vs-materialised seam"; it is **settled**, in
  [ADR 0024](../docs/adr/0024-merger-field-resolution.md). `FolderMerger`, `TripMerger` and
  `MomentMerger` are the reference shapes. ⚠ The read is all it settles: removal semantics
  still come only from `purgeDeadMembers` + the tombstone registry.
- **Cross-process errors are typed codes, not strings.** Sync status/errors crossing the
  proxy ↔ client boundary follow the localisation rule in `docs/agent.md` §19 (typed `Error`
  subtypes; the client maps to localised strings).

When making a non-trivial design choice in this area, update the relevant doc above in the
same session — decisions persist in docs, not commit messages.
