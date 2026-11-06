# ADR 0007 — Synced device management + ephemeral pairing relay

- **Status:** Accepted, implemented (Phases 1–4 closed out 2026-05-04;
  threat-model carve-outs closed 2026-05-08)
- **Date:** 2026-05-04
- **Owner:** Benedikt
- **Supersedes:** parts of
  [`sync-backend-relay-plan.md`](../proposals/sync-backend-relay-plan.md) §3.3
  and the device-related portions of §4.2
- **Superseded by:** —

## Context

Pre-this-work the relay maintained `deviceRegistry: ownerId → MutableList<ByteArray>`
(Device DTOs) and exposed custom REST endpoints — `getDevices`, `authorizeDevice`,
`revokeDevice`, `POST /identity/pair-device` — that wrapped that state. Four
concrete problems:

1. **Architectural mismatch.** The whitepaper's zero-trust posture says the
   relay sees ciphertext only — but device names, authorization status, and
   revocation state were plaintext metadata on the relay.
2. **Privacy.** The relay knew the names and IDs of every device in a realm,
   contradicting §A6's intent to minimise relay-visible metadata.
3. **Logic bugs.** The "Invisible Self" bug: a newly-paired device couldn't
   see itself in its own device list until another device approved it,
   because the source of truth was server-side and the server had no notion
   of "this device".
4. **Complexity.** Custom server-side endpoints duplicated functionality that
   ordinary `Syncable` entity sync already provided (pull, push, merge,
   delete).

The fix: move the source of truth for device authorization and metadata from
the relay into the realm's synced database. The relay shrinks to a
block-and-pairing mailbox + a master-public-key store. Identity logic moves
where it always belonged — into the realm itself, signed by realm devices,
replicated like any other Syncable entity.

## Decision

`Device` becomes a `Syncable` entity in `:identity-service`. The relay's
`deviceRegistry` + `/identity/*` endpoints are deleted. Device admission,
revocation, and listing all flow through ordinary Syncable entity sync.

Architectural details + the master-delegation chain live in
[`docs/sync/design.md` §2.2](../sync/design.md#22-identity--device-model);
the pairing flow's post-handoff `Device` entity blocks live in
[`docs/sync/pairing-flow.md`](../sync/pairing-flow.md).

Eight anchor decisions:

1. **`Device` is a `Syncable` entity.** Not a relay-side registry. Authority
   is the master-delegation chain (recovery-code-derived master identity →
   master delegation → device A4 signing key → block signature), verified on
   merge.
2. **The relay's identity surface shrinks to two purposes**: ephemeral
   pairing handshake (`/sync/pairing/{request,response,ack}`) and the
   master-public-key store for realm bootstrap + recovery-claim. Everything
   else — admission, revocation, listing — is realm-replicated entity sync.
3. **Realm-state lives as a derived cache, not a synced singleton.** Picked
   option (b) of the §"Realm-state data" question:
   `SyncManager.currentRealmEpoch` reads
   `TombstoneRegistry.tombstoneCountForType(Device.ID)`. No new singleton
   entity, no relay-side state. Master public key is cached locally from the
   realm-bootstrap response.
4. **`isCurrent` is local-only**, computed on demand at the UI layer
   (`device.deviceId == syncManager.currentDeviceId`). Not in the sync
   payload. Schemas describe synced state; "this device" is a runtime
   property of the device doing the asking.
5. **Two distinct bootstrap paths.** First device of a brand-new realm uses
   `POST /sync/realm` + master self-signature (TOFU). Recovery on a fresh
   install of an existing realm uses `POST /sync/recovery-claim` +
   challenge-response. The recovery path's challenge-response closes the
   "hostile relay serves an empty changes feed to trick the client into a
   forked realm" gap that a local-empty-collection check alone would miss.
   The two paths converge at step 5 (self-push of own `Device { isAuthorized
   = true }` block).
6. **Joiner self-pushes its own authoritative block** immediately after the
   master key arrives during pairing — not "self-push false, then host
   flips true". Setting `isAuthorized = true` directly is correct because
   reaching that step proves Device A's master delegation cleared the
   relay's `MasterDelegationVerifier`; the joiner is authoritatively
   admitted at the moment the master key arrives. Every other realm device
   verifies through the master-delegation chain rather than trusting the
   field value, so the joiner writing `true` is not a forgery surface. This
   ordering eliminates the "Invisible Self" UX bug at the source.
7. **`admittedAtEpoch` field + merge-time stale-epoch rejection.**
   `SyncProcessor.resolveConflictAndSave` drops any incoming `Device` block
   whose `admittedAtEpoch < currentRealmEpoch`. Future-epoch blocks (`≥`)
   pass through; the only rejection axis is strictly-stale. Recovery flow
   seeds `currentRealmEpoch` from the relay's authoritative count
   (`GET /sync/realm-epoch` in [`sync-relay-api.md`](../proposals/sync-relay-api.md)
   §3.6) so the check is meaningful before tombstones drain.
8. **No per-block Ed25519 signing in this work.** Today's authentication is
   JWT-on-the-wire (relay-plan Phase 2.1); per-block signatures are a future
   platform-wide addition that `Device` entity blocks will inherit when it
   lands. This decision keeps the present scope tight; the chain-of-trust
   reasoning above is forward-compatible with per-block signing arriving
   later.

## Consequences

- **Deleted relay surface.** `POST /identity/pair-device`,
  `POST /identity/revoke-device`, `GET /identity/devices`,
  `deviceRegistry: ownerId → MutableList<ByteArray>`.
- **Retired operations + impls.** `RegisterCurrentDevice`,
  `AuthorizeDevice`, `RevokeDevice` (sync-service / identity-service) and
  their corresponding `*Implementation` classes. Plus the legacy
  `PairDevice` op + `PairingDelegate` cluster also went (`IdentityService.pairDevice`
  REST shim + `InitiatePairingImplementation`'s `executeAsync(PairDevice.ID,
  ...)` call were dead-on-arrival once `/identity/pair-device` was deleted in
  Phase 3).
- **New operations.** `CreateRealm` (first-device bootstrap) and
  `RecoveryClaim` (recovery-flow admission) in `:sync-service`.
- **`GetDevicesImplementation` becomes a relation query** against the local
  database for `IdentityService.Relations.DEVICES`. Returns the resolved list
  via the existing list-resolution pipeline.
- **Five threat-model amendments** landed in `docs/sync/threat-model.md`:
  §A6 wording for device-name metadata reduction; §7 attack surface 3
  wording reduction; §8.5 T-K-01 / T-K-02 wording for `DeleteBlock` /
  `admittedAtEpoch`; §9 line 499 I4 caveat closed; new T-D-01 (relay
  tampers with a Device entity block, signature verification defends).
- **One bounded residual.** T-K-02 has a residual relay-trust window — the
  recovering device's `GET /sync/realm-epoch` trusts the relay's
  tombstone-count number (bounded over- and under-statement; the full
  closure needs a signature-attestation by an existing realm device,
  deferred as a follow-up). Documented in `threat-model.md` T-K-02.

## When to revisit

Re-open when one of these is true:

1. **Per-block Ed25519 signing lands platform-wide.** Tightens the
   "block signature traces through master delegation" wording; might
   simplify the merge-time check.
2. **A `Volume` first-class entity is introduced** (per the retention-window
   storage decision in `docs/sync/design.md` §2.6 "Relay-side body GC").
   Realm-state data (master public key, realm epoch) might move from the
   derived-cache shape into that entity for cleaner multi-realm
   coordination.
3. **The bounded T-K-02 relay-trust residual** stops being acceptable. The
   follow-up is a signed attestation by an existing realm device, replacing
   the relay's count as the source of truth for recovery-time
   `currentRealmEpoch` seeding.
4. **Multi-master / shared-realm scenarios** (multiple users with
   independent recovery codes sharing a realm). Today's single-master
   chain doesn't model this; the master-delegation pattern would need to
   become a multi-master delegation set.

## Alternatives considered

- **Keep `deviceRegistry` on the relay; just hide names from the wire.**
  Considered as a smaller-scope fix. Rejected — the relay still has to
  index and propagate device metadata, the "Invisible Self" bug stays,
  and the architectural inconsistency (server-side identity logic in an
  E2EE platform) doesn't get fixed. The whole class of problems goes
  away when the relay stops being authoritative.
- **`RealmState` singleton synced entity** for master public key + current
  epoch. (Option (a) of the §"Realm-state data" question.) Rejected for
  v1 in favour of (b) — local-only cache derived from observed Device
  tombstones. (b) is simpler and matches the zero-trust posture (no
  realm-state on the relay). Revisit if multi-realm-state coordination
  becomes a real requirement (the `Volume` entity follow-up may force
  this).
- **`isCurrent` as a synced field**, with each device populating its own
  value on materialisation. Rejected — "this device" is meaningful only on
  the device doing the asking; carrying it in the sync payload bloats
  every block for a value that's wrong everywhere except the source. UI
  computation is one line.
- **Self-push with `isAuthorized = false`, host flips true later.** The
  pre-implementation sketch. Rejected during implementation — produced
  the Invisible Self UX bug for the round-trip window before A's
  authoritative block landed. The "joiner writes true directly" ordering
  eliminates the bug; the security reasoning (every device verifies via
  master-delegation chain rather than trusting field values) was always
  correct.
- **Per-block Ed25519 signing inside this proposal.** Considered for
  defence-in-depth. Rejected as scope creep — JWT-on-the-wire (relay-plan
  Phase 2.1) is the current authentication primitive; per-block signing
  is a future platform-wide addition that all blocks (Device entity
  blocks included) inherit. Coupling them produces a bigger PR with no
  isolated benefit.
- **Local-empty-collection check as recovery bootstrap guard.** The naïve
  "if the realm looks empty, treat as first-device" shape. Rejected — a
  hostile relay could fabricate an empty changes feed and trick the
  client into a forked realm. The challenge-response in
  `POST /sync/recovery-claim` closes the gap; the relay can't fabricate a
  valid master signature over a fresh challenge.

## References

- `docs/sync/design.md` §2.2 — protocol semantics + master-delegation
  chain + bootstrap paths.
- `docs/sync/pairing-flow.md` — protocol-level handshake + Step 5
  (`Device` entity blocks after master handoff).
- `docs/sync/threat-model.md` — §A6, §7, T-K-01/02, T-D-01, §9 line 499.
- `:identity-service/.../Device.kt` — `Device.Schema` with `Syncable`
  trait + `admittedAtEpoch` field.
- `:identity-service/.../IdentityService.kt` — `Relations.DEVICES`
  list-relation.
- `:identity-service-proxy/.../GetDevicesImplementation.kt` — relation
  query against the local database.
- `:sync-service/.../CreateRealm.kt`, `RecoveryClaim.kt` — bootstrap
  operations.
- `:sync-service-proxy/.../ApprovePairingRequestImplementation.kt` —
  host's approve flow + peer Device block. ⚠ It admits the request the host
  **pinned when it showed the SAS** (`GetPendingPairingRequestsImplementation`
  → `SyncManager.PairingContext.pinShownRequest`), never one looked up on the
  relay by `deviceId` — threat model T-P-14 (2026-10-02). Since 2026-10-03 that SAS also covers
  the joiner's signing key, `deviceId` and name (v2, `deriveJoinPairingSas`), so the key this ADR's
  Device row records is one the user's code comparison vouched for.
- `:sync-service-proxy/.../SyncProcessor.kt::resolveConflictAndSave` —
  merge-time `admittedAtEpoch < currentRealmEpoch` rejection (T-K-02).
- `:sync-service-proxy/.../SyncManagerImpl.kt::currentRealmEpoch` —
  derived-cache shape + `seedCurrentRealmEpoch(...)` for the recovery
  path.
- `sync-relay-api.md` — `POST /sync/realm`, `POST /sync/recovery-claim`,
  `POST /sync/pairing/{request,response,ack}`, `GET /sync/realm-epoch`.
- `sync-pairing-flow-improvements.md` — protocol-hardening follow-ups
  treated as in-scope by `control-onboarding-ux.md` §4a.
- ADR 0006 (`sync-tombstones`) — revocation primitive (`DeleteBlock` of a
  `Device` entity).
