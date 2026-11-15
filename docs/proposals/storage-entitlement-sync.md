# Storage entitlement — cross-device sync

**Status:** Slices 1–4a **landed** (2026-06; this header was stale). The cross-device carrier this
doc designed is built: the synced `Entitlement` schema + `EntitlementSignature` (canonical message +
`publicKeyFor` registry) + verify-on-merge, the device-side `EntitlementStorageProvider` /
`initEntitlementStorage`, and the `StorageSyncGate` joiner-bootstrap (`joinedFundedRealm`). Remaining
work (real server-side signer, tombstone-based revocation) is folded into
[`publishing-commerce-reconciliation.md`](publishing-commerce-reconciliation.md) §7/§10. Original
design draft below.

**Owner:** Benedikt

**Supersedes / amends:** extends [ADR 0019](../adr/0019-storage-capacity-gating.md)
*When-to-revisit #1*; resolves the bootstrap tension the local-first amendment
left open. Consumes [`tenshu-publisher.md`](tenshu-publisher.md) §10.2's
`Entitlement` surface.

---

## 1. The symptom

A user subscribes to storage on device **A**. On device **A** the warning in
Drive → Files clears and Control → Services shows the account. On a second paired
device **B**, nothing changes — Drive keeps the "files are local-only" banner and
Control keeps the "no account" state, indefinitely.

The user's expectation: *the entitlement should follow the user to all their
devices.* Today it does not.

## 2. Why — and why it is not (mostly) a design rewrite

The entitlement today is **device-local and mocked**:

- `MockPublisherClient` (`:control-shared`) holds a `MutableStateFlow<PublisherAccount?>`
  persisted to this device's `SecureStorageDelegate`. Subscribing sets it.
- `MockStorageProvider.observe()` derives `StorageFigures(hasStorage = account != null, …)`
  from *that local field*.
- `initStorageService` upserts the `StorageStatus` row from those figures; Drive
  reads it reactively over IPC.

So there is no carrier between two installs at all — A's `SecureStorage` field
never reaches B. ADR 0019 Decision 4 already names this: `StorageStatus` "is
**locally derived, not `Syncable`** — each device's proxy derives it from
entitlement + usage", and *When-to-revisit #1* anticipates swapping the mock for
"relay-metadata + the real `Entitlement` composite".

`tenshu-publisher.md` §10 already designed the carrier correctly: the Publisher
mints a **Publisher-signed `Entitlement` record** and **writes it into the user's
realm** (§10.1: "writes the entitlement directly into the user's realm via a new
sync block … mirrors how the recovery blob is delivered today"). It then syncs to
all the user's devices like any `Syncable`, verified by a signature check on
merge. **That model is correct and is kept.** The entitlement is owner-scoped
realm data; we do not need a separate authenticated pull channel or a dedicated
"account realm".

### 2.1 The one real gap: the sync gate is device-local

The local-first reframe (ADR 0019 amendment, 2026-06-06) added a gate on the
**personal realm's** relay traffic:

```kotlin
// StorageSyncGate (:control-shared)
override suspend fun shouldSync(): Boolean = storageAllowsSync(capacityHolder.figures)
internal fun storageAllowsSync(figures: StorageFigures?): Boolean = figures?.hasStorage == true
```

```kotlin
// SyncScheduler.tickOnce() (:sync-service-proxy) — abridged
if (storageGate.shouldSync()) {
    realmRegistrar?.ensureRegistered()   // POST /sync/realm genesis
    drainPendingBlocks(); drainPendingTombstones()
    pullChanges()                        // ← inbound pull is INSIDE the gate
}
drainSharedRealms(); pullSharedRealms()  // shared realms exempt (owner-funded)
```

The figure is **this device's own** local `hasStorage`. The personal realm's
**inbound pull** — including a freshly-paired joiner's very first pull — is inside
the gate. Shared realms (a different `ownerId`) are exempt; the personal realm is
not.

Now trace a joiner:

```
Owner subscribes on A → realm registered on relay; Entitlement written into realm; pushed.
B pairs (ALLOWED — pairing rides the relay mailbox, which exists only because the
         owner registered the realm, which required the owner's subscription).
B adopts the host ownerId (PairingCompletion.initializeWithKeys) → shares the ONE
         personal realm.
B.tickOnce() → storageGate reads B's LOCAL hasStorage = false → personal pull SKIPPED.
B never pulls the realm → never receives the Entitlement → B.hasStorage stays false.  ⟲
```

**Pairing admission *is* owner-gated** — the user's intuition is right. The gap is
narrower: the *sync gate* was written reasoning about the **first device**
(local-only until it subscribes) and never reconciled with the **joiner** (a
device on an already-funded realm that has no *local* subscription of its own).
"Pairing a 2nd device requires a subscription" means the *owner's* subscription
enabling the mailbox; it was never wired into the joiner's own sync gate.

## 3. Design

Three pieces. #1 and #2 make the entitlement converge across devices; #3 removes
the bootstrap deadlock.

### 3.1 `Entitlement` becomes a synced, Publisher-signed realm record

Adopt `tenshu-publisher.md` §10.2 verbatim:

```kotlin
interface Entitlement : Composite {
    val ownerId: TString
    val publisherId: TString          // dev key id in the mock slice
    val productId: TString            // "tenshu.drive.basic"
    val validFrom: UnixInstant
    val validUntil: UnixInstant
    val publisherSignature: TByteArray
}
```

- Home: `:identity-service` (per §10.2; promote to a dedicated module only if a
  v2 surface justifies it).
- `Syncable`, wire-registered in the shared common module loaded into **both**
  Control's and Drive's `coreSchemaConfigs` (the registration symmetry that
  `storage-service-common` already follows — see the wire-registration house
  rule).
- A `MergerRegistry` (`sync-merge-strategies.md`) entry verifies
  `publisherSignature` against the known publisher-key registry on merge; an
  invalid signature drops the record. v1: one hardcoded dev key.

### 3.2 The real `StorageProvider` derives figures from the synced `Entitlement`

Replace `MockStorageProvider`'s "read the local account field" with an
`EntitlementStorageProvider` whose figures are derived from the realm's current
`Entitlement` by a pure mapper:

```
hasStorage  = a valid (now within [validFrom, validUntil]) Entitlement exists
limitBytes  = quota for its productId
usedBytes   = relay-reported usage (still 0 in the mock; real value is When-to-revisit #1)
```

**Read mechanism (implemented deviation).** The original sketch read the
entitlement via a client chain-DSL relation. The provider is *proxy-side*, so the
more direct reactive read is a **`DataObserver`** (mirroring
`DeviceRelationMaintainer`) that recomputes the figures on every `Entitlement` save
— local *or* sync-merge ingest — plus a seed read at session start. No new
`IdentityService` relation / RO / client accessor, and no in-process IPC round
trip. The observer feeds the holder `EntitlementStorageProvider`, which
`initStorageService` already collects — so everything downstream
(`StorageStatus` row → IPC stream → Drive banner / Tier-2 `StorageCapacityHolder`
guard) is unchanged, and every device in the realm converges on the same figures.

**Control's Services tab** combines its device-local `publisherClient.account`
with the synced `StorageStatus` (read via `client.storageService.status()`): the
subscribing device shows the full account card (plan + identity + management CTAs),
while a *2nd* device — no local account but active synced storage — shows a
read-only "active, managed elsewhere" card (storage figures only; **no email /
identity / disconnect**, which are device-local PII). Shipped in the Control
Services UI slice (3b, §5).

### 3.3 The personal-realm sync gate becomes owner-scoped for the bootstrap

Introduce a persistent, device-level signal that this device **joined a realm it
did not itself fund**:

```kotlin
// SyncManager — new persistent latch, set once at pairing completion
var joinedFundedRealm: Boolean
```

Set `joinedFundedRealm = true` in `PairingCompletion.tryComplete` (joiner side).
Completing pairing is a **sound** proxy for owner-funding, and the soundness is
worth stating precisely:

- Pairing is **strictly relay-mediated** — there is no local/LAN/offline path
  (both `InitiatePairing` and the host's approval go through relay HTTP).
- A joiner only completes by obtaining the **encrypted master key**, delivered by
  the host via `POST /sync/pairing/response`. The relay **rejects that with `404
  "Realm not found"` unless `realmStore.get(ownerId)` exists** — i.e. unless the
  realm was registered via `POST /sync/realm`.
- Realm registration is gated on the owner's subscription (lazy genesis).

So *pairing completes ⟹ host delivered the master key ⟹ realm is registered ⟹
owner was subscribed*. The QR payload itself carries no "funded" bit (it's the
host ephemeral key + secret + `ownerId` + `deviceId`); the proof is the
relay-gated **completion**, not the invite. Note the realm-epoch fetch during
completion is **soft-fail** and is *not* the proof — the master-key delivery is.

⚠ This proves the realm *was* registered (funded at registration time), not that
it is *currently* funded — registration is a once-ever latch that survives an
unsubscribe. The ongoing limiter is relay-side quota enforcement (§6), not this
local latch.

The gate then opens on either signal:

```kotlin
// StorageSyncGate
override suspend fun shouldSync(): Boolean =
    storageAllowsSync(capacityHolder.figures) || syncManager.joinedFundedRealm
```

- **First device:** `joinedFundedRealm == false` → gate is purely local
  `hasStorage`, **unchanged** from today. It stays local-only until it subscribes;
  if it subscribes, writes the `Entitlement` into the realm, pushes.
- **Joiner:** `joinedFundedRealm == true` → bootstrap pull runs → the `Entitlement`
  lands → §3.2 flips `hasStorage` true authoritatively → self-sustaining.

Do **not** overload the existing `realmRelayRegistered` latch for this: that latch
means "I ran `POST /sync/realm` genesis" and is load-bearing for *not re-running
genesis*. A joiner must **not** run genesis (the owner already did), and a first
device that later unsubscribes must still gate off — both of which a separate
`joinedFundedRealm` keeps clean.

### 3.3a The mirror gap: the genesis device, subscription on a *different* device

§3.3 reasoned about **entitlement flowing genesis → joiner**: the owner subscribes
on the genesis device, which writes + pushes the `Entitlement`, and the joiner
needs `joinedFundedRealm` to run the bootstrap pull that receives it. The mirror
case was missed: **entitlement flowing joiner → genesis.** A user whose genesis
device is, say, their desktop, who then subscribes on a *paired phone*, leaves the
desktop with `joinedFundedRealm == false` (it never joined — it *created* the
realm) and a local `hasStorage == false` (its figure derives from a synced
`Entitlement` it doesn't have). Its `StorageSyncGate` is closed, so it never
*pulls*, so it never receives the phone's `Entitlement` — the same bootstrap
deadlock, opposite role, not covered by the joiner latch. (The same closed-gate
state also strands a device paired *before* slice 1 shipped, since the latch is
set only at pairing completion and is never backfilled.)

**Fix (shipped): split the gate — gate PUSH, not PULL, on the local figure.**
`SyncScheduler.tickOnce()` no longer wraps the whole personal-realm cycle in one
`storageGate.shouldSync()` check. Instead:

```kotlin
val pushAllowed = storageGate.shouldSync()            // genesis + outbound drain
if (pushAllowed) { realmRegistrar?.ensureRegistered(); drainPendingBlocks(); drainPendingTombstones() }

val pullAllowed = pushAllowed || syncManager.realmRelayRegistered   // + inbound pull
if (pullAllowed) { pullChanges(since); pullTombstones(since) }
```

- **Push / genesis / drain** stay gated on `storageGate` exactly as before — an
  unsubscribed device still uploads nothing and never registers a realm it isn't
  funding.
- **Pull** additionally opens on `realmRelayRegistered`. That latch is true only
  for a realm that was funded *at least once* (genesis ran, or a joiner has pulled
  once and then ticked with its gate open — `ensureRegistered` latches it on that
  push-allowed tick, not on the pull itself), so a never-funded realm — `realmRelayRegistered == false` — still
  makes **no relay request at all** (ADR 0019's local-first floor is preserved for
  the unregistered case). For an already-registered realm, pulling is read-only,
  consumes no quota, and is exactly what delivers both new entitlements *and* future
  revocation tombstones to every owner device.
- This subsumes the migration case (a pre-slice-1 device with
  `realmRelayRegistered == true` now pulls) and keeps `joinedFundedRealm` doing its
  job of opening the **joiner's** pulls. ⚠ It is not only the *first* pull: a joiner
  latches `realmRelayRegistered` on its next push-allowed tick, which `joinedFundedRealm`
  guarantees, and since nothing ever clears `joinedFundedRealm` a paired joiner's gate
  never shuts. Checked 2026-11-13 after the stepped system-test harness made a gated
  joiner observable (`docs/testing.md` §12).

This is a deliberate, narrow relaxation of ADR 0019's "no relay contact when
unsubscribed": a device on a once-funded realm now issues a read-only pull per tick
even while its own subscription is unbound. That is desirable — it is how the device
converges on a re-subscription or a revocation — and the ongoing *limiter* is
relay-side quota enforcement (§6), never this local gate. Tests:
`SyncSchedulerGateTest.registeredRealm_pullsDespiteClosedStorageGate` (pull runs,
push/genesis stay gated) + `unregisteredRealm_closedStorageGate_skipsPull` (the
local-first floor holds).

### 3.4 Where the Publisher writes the entitlement

Unchanged from `tenshu-publisher.md` §10:

- **Mock slice (this design's scope):** Control's subscribe flow, instead of only
  setting the local `SecureStorage` field, mints a self-signed `Entitlement`
  (`validUntil = now + 30d`) and writes it into the owner's realm via the
  sync-block path. The first device pushes it; joiners pull it.
- **Real Publisher (later):** the `/webhook/...` handler verifies the receipt and
  posts the signed `Entitlement` into the owner's realm (or, when Rei exists, into
  Rei), addressed by `ownerId`. The on-device contract (§3.2/§3.3) is identical.

## 4. Threat model

No new asset class and no House-Rule-#1 violation:

- The `Entitlement` record is `ownerId` + `productId` + validity + signature — **no
  email, no payment PII** (those stay on the Publisher, asset A11 when it ships).
  The relay carries it as an opaque, encrypted realm block exactly like every
  other `Syncable`; it cannot forge a "you're entitled" claim (Publisher
  signature, **verified on-device at merge** — slice 4a, `SyncProcessor`'s
  `isUnverifiedEntitlementBlock`). The v1 dev signing key is committed/insecure by
  design, so the *mechanism* is real but the boundary only becomes meaningful when
  the real Publisher holds the key server-side (KMS, slice 4b).
- A malicious/amnesiac relay can *withhold* a block — denial-of-service, already
  inside the AD5 posture — but cannot fabricate entitlement.
- `joinedFundedRealm` only *opens relay contact* for a device that has already
  completed an owner-funded pairing handshake; it strictly cannot let an
  unpaired/never-funded device reach the relay. The change reduces, not expands,
  the conditions under which an unfunded device contacts the relay — consistent
  with the amendment's "client-side timing only" note.

Record this as a timing/clarification note in `docs/sync/threat-model.md` when the
gate change lands; no A1–A4 / A9 / wire-shape amendment. The signed-`Entitlement`
forge/verify threat (and the publisher-key registry) lands with the real Publisher
per `tenshu-publisher.md` §6.

## 5. Phasing

1. **Gate fix in isolation** ✅ **SHIPPED** (`feat/entitlement-gate-fix`).
   `SyncManager.joinedFundedRealm` latch + set it at `PairingCompletion` +
   `StorageSyncGate` reads it (`personalRealmSyncAllowed`). Smallest landable unit;
   even before a real `Entitlement`, this unblocks the joiner's pull (and, in fact,
   *all* joiner personal-realm sync). Unit-tested; cross-device verify pending.
2. **`Entitlement` schema** ✅ **SHIPPED.** The Publisher-signed,
   **`Syncable`** `Entitlement` composite in `:identity-service`, wire-registered in
   `identityServiceCommonModule` (loaded into both Control and Drive graphs), keyed
   by `ownerId` via the generic `StringDataIdProvider`. Round-trip + Syncable-trait
   test. **Deliberately *just* the schema** — the `GetCurrentEntitlements` op and the
   signature-verify-on-merge `MergerRegistry` entry were *not* bundled here (the repo
   rule: no speculative additions without a consumer). They move to where their
   consumer/signer actually exists:
   - the op (if needed at all — the provider may read the chain-DSL relation
     directly) lands with **slice 3**;
   - the **signature-verify-on-merge** entry lands with **slice 4**, where a real
     signer first produces a signature to verify (verifying nothing is dead code).
3. **`EntitlementStorageProvider` + mock writer** ✅ **SHIPPED.** Swapped
   the `StorageProvider` binding to derive figures from the synced `Entitlement`
   via a proxy-side `DataObserver` (§3.2), **and** folded in the *mock writer*
   (originally slice 4): on subscribe, the proxy upserts an **unsigned**
   `Entitlement` into the realm so it syncs out. Reader-without-writer would have
   broken the dev mock (nothing would write an entitlement), so the two shipped
   together. `MockStorageProvider` deleted. Pure-mapper + reader/writer
   integration tests. **Deferred:** real Publisher signing + the `MergerRegistry`
   signature-verify-on-merge entry (slice 4); cross-device revocation (proposal §6).
3b. **Control Services UI** ✅ **SHIPPED.** The Services tab now combines
   the device-local `account` with the synced `StorageStatus` (§3.2): a 2nd device
   shows a read-only "active, managed elsewhere" card instead of the Sign-up empty
   state. Closes the maintainer's original "Control → Services *and* Drive → Files"
   expectation. (Tier label and management on a synced device would need the
   entitlement `productId` read surface — not pursued; figures suffice.)
4a. **Publisher-signature + verify-on-merge** ✅ **SHIPPED.** The mock
   writer now **signs** the entitlement (Ed25519 over a canonical message via
   `EntitlementSignature`, committed dev key), and the sync-merge path **verifies
   and drops** an entitlement whose signature doesn't check against the known
   publisher key — closing the slice-3 unsigned gap so a peer can't mint itself a
   tier. **Implemented as a pre-merge reject hook** in `SyncProcessor`
   (`isUnverifiedEntitlementBlock`, next to the T-K-02 `isStaleDeviceBlock`), **not**
   a `MergerRegistry` entry — that sidesteps the unstarted `sync-merge-strategies.md`
   rename, and the `SemanticMerger` interface has no veto anyway. Real Ed25519
   (BouncyCastle) is reused; the dev key is **insecure by design** (committed,
   public) — a *mechanism* so the real-Publisher swap is mechanical, not a security
   boundary. **Deferred (4b):** the real `:tenshu-publisher` backend (Ktor + KMS
   key custody + Stripe/IAP, `tenshu-publisher.md` §7) — a separate infra track;
   cross-device revocation (§6).
4b. **Real Publisher backend** — the webhook path that mints + signs entitlements
   server-side with a KMS-held key, replacing the in-process mock writer + committed
   dev key. Out of code-design scope here (`tenshu-publisher.md` §7 / relay Phase 4).

Slice 1 fixes the *mechanism*; slices 2–4 supply the *payload*. Verify cross-device
on two real desktop installs against the dev relay (the symptom is multi-device, so
in-process tests can't prove it — mirror the existing shared-realm round-trip system
test).

## 6. Open questions

- **Lapsed owner subscription.** Once `joinedFundedRealm` is set it stays set, so a
  joiner keeps contacting the relay even if the owner later unsubscribes. Is the
  authoritative limit then (a) the expired `Entitlement` (`validUntil` in the past
  → §3.2 derives `hasStorage = false`, naturally gating the joiner off — but it
  can't pull the *revocation* once gated, a mild staleness), or (b) relay-side
  quota enforcement rejecting the push (When-to-revisit #1)? Leaning (b) as the
  real backstop, with (a) as the optimistic local view. Decide when relay
  enforcement is sized.
- **Does the *first* device's gate ever need to re-derive from the realm
  `Entitlement` rather than the local subscribe action?** If a user subscribes on
  B (a joiner) rather than A, the write-into-realm path (§3.4) must work from any
  device, and A must pick it up — which it does once A's provider is §3.2. Confirm
  there's no first-device-only assumption left in the subscribe write path.
- **Usage (`usedBytes`).** Still mock-zero here; real per-owner usage is relay
  metadata (When-to-revisit #1) and is orthogonal to entitlement *existence*.
- **Multiple entitlements / product changes.** §10.2 models a list; v1 collapses
  to "the one valid entitlement". Revisit if tiers stack.

## 7. References

- [ADR 0019](../adr/0019-storage-capacity-gating.md) — the storage gate, Decision
  4 (locally-derived `StorageStatus`), the local-first amendment (the gate this
  doc reconciles), When-to-revisit #1 (the swap this doc performs).
- [`tenshu-publisher.md`](tenshu-publisher.md) §10.2 — the `Entitlement` composite +
  `GetCurrentEntitlements` op this doc consumes; §10.1 entitlement-into-realm
  delivery; §6 the Publisher threat-model entries.
- [`sync/design.md`](../sync/design.md) §2.2 — synced device management / pairing;
  §2.6 tombstones (the shape an entitlement *revocation* would take).
- [`sync-merge-strategies.md`](sync-merge-strategies.md) — `MergerRegistry`, where
  the signature-verify-on-merge entry lives.
- Code touchpoints: `:sync-service-common/SyncManager.kt` (new latch),
  `:sync-service-proxy/impl/PairingCompletion.kt` (set latch),
  `:control-shared/.../StorageSyncGate.kt` (read latch),
  `:control-shared/.../MockStorageProvider.kt` → `EntitlementStorageProvider`,
  `:storage-service-proxy/.../StorageProxyInitialization.kt` (unchanged consumer).
