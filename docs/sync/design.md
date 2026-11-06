# Tenshu Platform: E2EE Synchronization Design

This document outlines the architecture and implementation plan for the end-to-end encrypted (E2EE)
synchronization system across the Tenshu platform.

## 1. Overview

The goal is to synchronize both small data blocks (metadata, notifications) and large data blocks (
files) between authorized user devices in a fully encrypted and "blind-backend" manner. The system
handles granular synchronization rules, device management, and conflict resolution using a hybrid
logical clock (HLC).

## 2. Core Concepts

### 2.1 The `Syncable` Trait

The `Syncable` trait allows schemas to define their synchronization behavior at the entity or
property level.

* **Strategy**:
    * `Eager`: Immediate synchronization (metadata, previews).
    * `OnDemand`: Synchronized only when requested or on WiFi (binary data).
* **ConflictStrategy**:
    * `LastWriterWins` (Default): Uses HLC timestamps to resolve conflicts.
    * `Merge`: Semantic merging (e.g., merging contents of folders).
    * `Manual`: Prompts the user or creates a branched version.

### 2.2 Identity & Device Model

The platform manages a "Circle of Devices" — the cryptographic membership set of
devices that share the user's master identity and volume keys. Design rationale
+ alternatives: [ADR 0007](../adr/0007-sync-device-management.md).

#### `Device` is a `Syncable` entity

`Device` lives in `:identity-service` as a regular `Syncable` Composite. The
relay does **not** maintain a device registry — device authorization and
metadata are realm-replicated like every other Syncable entity. The relay
shrinks to a block-and-pairing mailbox + a master-public-key store for the
two endpoints that need it (recovery-claim and pairing-response).

Fields:

* `deviceId` — unique installation identifier; primary key.
* `publicKey` — the device's A4 signing key (per-device, persisted in the
  device keystore).
* `name` — user-friendly label.
* `isAuthorized` — admitted to the realm (`true`) or not (`false`). The
  authoritative true requires a signature traceable through master delegation;
  see "Authority" below.
* `admittedAtEpoch` — realm epoch at which this device was admitted. Carries
  the T-K-02 revocation-safety check; merge-time rejection drops any incoming
  Device block whose `admittedAtEpoch < currentRealmEpoch`.
* `isCurrent` is **local-only** — computed at the UI/repository layer as
  `device.deviceId == syncManager.currentDeviceId`. Not in the sync payload.

`SyncManager.currentRealmEpoch` is a **derived cache**, not a synced field:
it reads `TombstoneRegistry.tombstoneCountForType(Device.ID)` and is seeded
from the relay's authoritative count (`GET /sync/realm-epoch`,
[`sync-relay-api.md`](../proposals/sync-relay-api.md) §3.6) during recovery
so the receiver-side T-K-02 check is meaningful before tombstones drain.

#### Authority — the master-delegation chain

```
master identity (recovery-code-derived; never persisted in plaintext)
  → master delegation (signs admission of A4 keys)
    → device A4 signing key (persisted in keystore; signs every block)
      → block signature
```

A `Device { isAuthorized = true }` block is considered authoritative on
merge when **both** hold:

1. The block is signed by an admitted A4 device key (traceable through the
   master-delegation chain).
2. The block's `admittedAtEpoch` equals the current realm epoch (revocation
   safety, T-K-02).

A block failing either check is rejected and not propagated. Tampering with
`Device` blocks in transit is detectable on merge via signature verification
(new threat T-D-01).

> ⚠ **As built (2026-10-26):** the chain above ends in authority records
> *inside* payloads (admission and delegation signatures). There is **no
> block-level signature on the wire**: `SyncBlock` and `DeleteBlock` carry no
> signature field. A `SyncBlock` payload's integrity rests on AEAD under the
> realm key; its envelope (`hlc`, target hash) is unbound, and a tombstone is
> not protected at all. See the threat model's T-T-02 / T-K-05 amendments.
>
> ⭐ **In the release baseline (relay protocol 1, 2026-10-27):** the envelope is bound into the
> payload's AEAD as associated data, and a tombstone carries a realm-key HMAC
> (`authTag`), both verified before ingest uses anything
> ([`sync-envelope-authentication.md`](../proposals/sync-envelope-authentication.md)).
> Still no *per-device* signature: authenticity means "a realm-key holder wrote
> this", not "device X wrote this".

#### Two paths into a realm

There are exactly two ways for a device to *bootstrap* (i.e. acquire master
keys without an existing realm device to delegate from):

* **First device of a brand-new realm** — `POST /sync/realm` with the master
  public key + device public key + master self-signature. The relay performs
  TOFU on the master public key. Safe because the realm doesn't yet exist.
* **Recovery on a fresh install of an existing realm** — `POST /sync/recovery-claim`
  with a master signature over a relay-issued challenge. The challenge-response
  closes a "hostile relay serves an empty changes feed and tricks the client
  into a forked realm" gap that a local-empty-collection check alone wouldn't
  catch.

From step 5 onward (push the self-authored `Device { isAuthorized = true }`
block) the two paths converge.

> **Lazy first-device registration (local-first, [ADR 0019](../adr/0019-storage-capacity-gating.md)).**
> The first-device `POST /sync/realm` is **deferred until a storage subscription
> binds**. A realm created without a subscription is fully local: keys are
> derived, the self-authored `Device` block is saved locally, but nothing is sent
> to the relay. The genesis runs on the first sync tick after `hasStorage` becomes
> true (`RealmRegistrar`, gated by the same `StorageSyncGate` that gates the
> personal-realm drain/pull), and is latched once-ever via the persistent
> `SyncManager.realmRelayRegistered` flag so it never re-`POST`s on a later launch
> (the relay still holds the realm; relay *amnesia* is recovered separately — never
> by re-running the latched genesis tick). See **Recovering from relay amnesia** below
> for what actually performs that recovery.
> **Consequence:**
> pairing a second device requires a subscription, because the pairing handshake
> rides the relay mailbox, which only exists once the realm is registered. This
> changes *when* the unchanged `POST /sync/realm` happens, not its wire shape or
> the TOFU trust model above.

#### Recovering from relay amnesia

The relay can stop recognising a device that is otherwise perfectly healthy: its data was
restored from a backup predating that device's admission, an operator wiped it
(`deploy/reset-test-data.sh`), the client was pointed at a different relay host, or the
device was revoked from another of the user's devices. Every JWT-authenticated call then
`401`s, and `RealmRegistrar`'s once-ever latch means the genesis tick will never re-run.

Three things perform the recovery. All three re-present the **same** A1-gated capability
(`POST /sync/realm`, falling back to `POST /sync/admit-device` on a `409`) via the single
`RealmRegistrar.reregisterThisDevice`, so none of them grants new authority — threat model
§"Realised — device re-registration".

| Where | Trigger | Notes |
|-------|---------|-------|
| `SyncScheduler.recoverRegistration` | a sustained `401` on the sync path, at a `RejectionBackoff` probing boundary | the general case; one attempt per window, not per tick |
| `HostPairingMonitor.maybeReregisterDevice` | a failed host pairing poll | pre-dates the above; only live while a host session is |
| both pairing-approve paths | the response POST fails against a relay that forgot the realm | request flow and offer flow |

⚠⚠ **This is deliberately not conditioned on *why* the relay said `401`.** The relay
refuses to distinguish "unknown device" from "bad signature" in its response — that
distinction would be a device-existence oracle (`JwtAuth`) — so no client can key on the
cause. The repair is safe under the ambiguity instead: idempotent, no new authority, and it
falls back into the backoff window when the cause was something else (skew, a bad key).

⚠⚠ **The client detects the `401` by a marker `RestSyncRelay` attaches, never by the status
text.** HTTP/2 carries no reason phrase, so the same rejection reads `401 Unauthorized` over
h1 and `401 ` over h2 — and h2 is what the production relay serves behind Caddy. The first
implementation matched the text and was therefore **inert in production**: measured
2026-08-17, a device in exactly this state pushed 386 queued blocks per pass at ~38 req/s
with the backoff, the drain break, the health state and the repair all silent, because none
of them ever saw a `401`. Its unit test passed throughout — Ktor's `MockEngine` supplies the
canonical description, so the fixture produced the one shape the matcher understood. See
`UNAUTHORIZED_REJECTION_MARKER`; the same producer/consumer marker contract the `402` uses.

⚠ **A pairing joiner cannot do any of this** — it holds no master identity, so its `401` is
terminal until it is re-paired or re-admitted. That is the case the user-facing
`SyncHealthStatus.CONNECTIVITY_DEVICE_UNRECOGNIZED` state exists to name; an A1-holding
device usually repairs itself before a human sees anything.

⚠ Until 2026-08-17 this section's claim ("recovered by `ReregisterDevice` on a 401") was
**false on the sync path**: nothing called that operation there. The measured cost was a
device emitting ~8k rejected requests/hour indefinitely, reported in every UI as either
"Offline" or nothing at all.

Every other device admission goes through the **pairing flow** documented in
[`docs/sync/pairing-flow.md`](pairing-flow.md). The pairing relay (ephemeral
`/sync/pairing/{request,response,ack}` endpoints) handles the master-key
handoff; the new device's *existence* + *authorization* are recorded by the
new device itself in a self-pushed block immediately after the master key
arrives. Device A also authors its own peer block for the new device's
entity; field-level LWW resolves both sides to `isAuthorized = true`.

#### Recovery Phrase

* BIP-39 seed phrase to derive the Identity Master Key.

### 2.3 Encryption Scheme

* **Shared Identity Key**: Used to encrypt metadata blocks (metadata is shared among all devices).
* **Shared Volume Key**: Used to encrypt large binary blobs.
* **Key Rotation (Re-keying)**:
    * When a device is revoked, a new `Shared Volume Key` is generated.
    * Remaining devices re-encrypt existing blobs with the new key in the background.

### 2.4 Blind Backend ("Mailbox")

The backend acts as a simple storage relay. It has no visibility into the data.

* Stores encrypted **SyncBlocks**.
* Requires a "Receipt Acknowledgment" from each registered device before a block can be
  garbage-collected.
* Supports **On-Demand Fetching**: Devices can request specific blocks for dynamic properties
  without waiting for the full sync cycle.

### 2.5 Local Sync Metadata

To support conflict resolution without bloating the business entities, the system maintains a
shadow database of `SyncMetadata`.

* **`SyncMetadata` Entity**:
    * `targetDataIdHash`: Links the metadata to a specific business entity.
    * `hlc`: Stores the HLC timestamp of the last *received* update for that entity.
* This metadata is used during the `SyncProcessor` phase to determine if an incoming block is
  newer than the local state.

### 2.6 Deletion via Tombstones

The platform uses explicit tombstones — `DeleteBlock`s — as the first-class deletion
mechanism. Local-only deletion would never reach other realm devices; a `DeleteBlock` is
how the realm learns an entity is gone. Design rationale + alternatives:
[ADR 0006](../adr/0006-sync-tombstones.md).

* **`DeleteBlock` wire shape** (`:sync-service/.../DeleteBlock.kt`): a top-level
  Composite carrying `blockId`, `ownerId`, `hlc`, `targetEntityIdHash`, and
  `targetEntityType`. No `encryptedPayload` — the target id hash + block kind id *is*
  the content. Distinct wire type from `SyncBlock`; the relay indexes by kind.
* **Authority**: any device with an admitted A4 signing key can issue a tombstone.
  Authenticity uses the existing block-authenticity story (JWT on the wire today;
  per-block Ed25519 signatures are a platform-wide future addition that
  `DeleteBlock` inherits).
* **One tombstone per delete *operation*, not per cascaded child entity.** A recursive
  folder delete emits one `DeleteBlock` for the folder; receiving devices delete the
  folder locally and the cascade happens via each device's own recursive-delete logic.
  Children's prior `SyncBlock`s remain in the relay until the GC drops them.
* **Wire transport**: `PushTombstone` / `GetTombstones` operations over dedicated
  `/sync/push-tombstone` + `/sync/tombstones` endpoints — separate from
  `pushBlock` / `getChanges` (the typing-driven deviation; see ADR 0006).

#### Merge-time short-circuit

The short-circuit lives in `SyncProcessor.mergeData`, *not* in any `SemanticMerger`.
Mergers operate on materialised entities and have no visibility into "all blocks for
this entity"; the dispatcher does.

```
mergeData(existing, incoming):
    tombstoneHlc = TombstoneRegistry.tombstoneHlcFor(incoming.idHash())
    if tombstoneHlc != null && tombstoneHlc >= incoming.hlc:
        return existing  // dead entity; drop the update
    return merger.merge(existing, incoming) ?: incoming
```

`TombstoneRegistry` (in `:sync-service-proxy`) is a per-`targetEntityIdHash` map to
the highest tombstone HLC observed for that entity. It persists across app restarts
so a tombstone observed yesterday still short-circuits today's straggler updates.

**Recreate semantics**: a future block with `hlc > tombstone.hlc` is *accepted* — the
entity is treated as recreated. Standard CRDT tombstone behaviour. The tombstone for
the prior incarnation stays in the registry indefinitely; it's the merge precondition
for any block with `hlc < tombstone.hlc`.

#### Ingest-time guard: a tombstone is compared against the entity's last write

The short-circuit above is one direction — *tombstone first, later block second*. The
mirror runs in `SyncProcessor.processDeleteBlock`: an arriving tombstone must not delete
an entity whose latest write is **newer** than the tombstone. Ordering cannot supply this
— every block in a tick is applied before any tombstone in that tick whatever the
`relay_seq` order, and per-lane cursors let the two lanes drift arbitrarily far apart — so
the comparison is explicit:

```
processDeleteBlock(block):
    lastWrite = max(SyncMetadata[idHash].hlc,          // last INGESTED write
                    LocalWriteMetadata[idHash].hlc)     // last LOCAL write
    if lastWrite != null && lastWrite > block.hlc:
        return                                          // the entity there now postdates the delete
    delete(entity)
```

⚠⚠ **Both records, always.** `SyncMetadata` is written by the ingest paths only, so it
answers "the last write that arrived over the wire", never "the entity's last write".
Reading it alone was the "ghost file" data loss: a folder recreated locally
carries the *identical* path-derived `DataId` as the deleted one, so the device's own
tombstone — pulled back from the relay and applied to itself — found a `SyncMetadata` HLC
older than the tombstone and deleted a live folder, orphaning its children. The same
comparison fails with no self-echo at all when peer B's delete races device A's local
recreate. `LocalWriteMetadata` (local-only, per-idHash, stamped by `TombstoneRegistry` on
every non-ingest save) is the missing half.

⚠ **The self-echo is not filtered, and must not be.** A device does pull back and apply its
own blocks and tombstones; that is what lets a device which has wiped its store rehydrate
from the relay. Dropping own-authored tombstones on that path would resurrect everything it
had ever deleted. The HLC comparison is the instrument, not the author check.

⚠ **`SyncMetadata` is deliberately not widened to cover local writes.** Its *absence* is
load-bearing in the opposite direction: `resolveConflictAndSave` reads a missing row as
"anything incoming is newer", which is what lets a real block replace the provisional
projection a slice snapshot wrote (`SliceSnapshotProducer.applySlice`). Stamping local
writes into it would also change ingest's `isNewer`, letting a skewed local clock fence out
peer writes permanently.

#### Relay-side body GC

The relay tracks per-`DeleteBlock`:

* **Acknowledgement set**: which realm devices have observed an HLC ≥ `tombstone.hlc`
  via their `/sync/changes` HLC-tip piggyback.
* **Tombstone link**: the set of `SyncBlock`s a tombstone covers
  (entity-id-hash-keyed).
* **Retention deadline**: `tombstone.hlc.physicalTime + volume.retentionWindow`. The
  retention window is per-volume configurable; default 30 days. Setting to 0 = eager
  GC after quorum; "forever" = never GC the body.

A nightly sweep (`SyncMailbox.gcSweep`) drops target bodies once quorum + retention
are both met. **Tombstones themselves are never GC'd** — they're part of the chain of
provenance for any device coming online after the retention window.

A device offline longer than the retention window comes back, pulls
`/sync/changes`, receives the tombstone but not the deleted body, and removes the
local entity. The body is permanently gone — that's the user's choice in setting
retention.

#### Revocation is a tombstone

Per §2.2 above + [ADR 0007](../adr/0007-sync-device-management.md): a
`DeleteBlock` targeting a `Device` entity's id hash *is* the revocation. No separate
revocation-block kind. The realm advances its volume epoch (existing T-K-01
mechanism), the relay refuses subsequent `/sync/push` whose JWT identifies the
revoked device, and remaining devices re-wrap volume keys under the new epoch.

### 2.7 File content: two-plane channel

File-content property blocks are not carried on the ordered sync drain. They
ride a **second transport plane**: a content-addressed, ciphertext-opaque blob
store on the relay, `PUT`/`GET` by opaque ref. The ordered drain only carries
a small per-file **manifest**. Design rationale + alternatives:
[ADR 0017](../adr/0017-file-content-two-plane-channel.md). Production endpoint
surface: [`sync-relay-api.md`](../proposals/sync-relay-api.md) §3.7.

| Plane | Carries | Transport | Drained? |
|---|---|---|---|
| **Metadata** | per-file manifest | `SyncBlock` / `DataBlockPayload` via the existing `pendingBlocks` ordered drain | Yes — always, cheap |
| **Content** | immutable, independently-AEAD-encrypted chunk bodies | content-addressed encrypted blob store on the relay, `PUT`/`GET` by opaque ref | **No** — fetched on demand only |

#### Manifest (metadata plane)

For a file-data property, `SyncObserver` emits **one small block** whose
encrypted `DataBlockPayload` value is a manifest:

* `fileId` / property identity (stays inside the ciphertext, like `propertyId`
  today — the relay never learns it),
* `chunkCount`, `totalSize`,
* per-chunk **opaque content-refs** (the content-plane keys),
* a **whole-file integrity anchor** (hash / Merkle root) + per-chunk hashes.

The manifest is a normal `SyncBlock` — same envelope, same ordered drain, same
tombstone/quorum machinery. Small and bounded, so every device always has every
file's manifest cheaply, regardless of total realm size.

#### Content plane (chunk bodies)

Chunk bodies are immutable, content-addressed, ciphertext-opaque blobs:

* Emit splits the file (read back from `FileDatabase`, one chunk at a time)
  into bounded `CHUNK` (1–4 MiB today) pieces, AEAD-encrypts each independently
  with `(fileId, chunkIndex, chunkCount)` bound into the AAD, and `PUT`s each
  ciphertext blob to the relay's content store under an **opaque ref**.
* The ref MUST be derived from the **ciphertext** (or be random), never from
  plaintext — a plaintext-derived ref is a confirmation-of-file oracle (the
  ADR §iv alternative).
* Bodies are **never** added to `pendingBlocks` and never drained. The relay
  stores them as opaque objects and serves them by ref via `GET`; it never
  decrypts (House Rule #1 unchanged).
* Per-owner JWT-scoped: `PUT`/`GET /sync/content/{ref}` authenticate via the
  existing device-key JWT and ACL on `ownerId`. A device in realm A can neither
  `PUT` into nor `GET` from realm B's content store.

#### Read path (OnDemand)

Opening a file goes through the existing OnDemand resolver: the device already
has the manifest (synced on the metadata plane) → fetches **only that file's**
chunk blobs by ref from the content plane, one at a time → decrypts (verify
per-chunk AAD + whole-file anchor) → reassembles incrementally into
`FileDatabase`. A multi-TB realm syncs only manifests + metadata eagerly; bytes
are pulled lazily per file actually viewed.

`FileBytesPropertyWriter` becomes a *manifest* writer; the OnDemand resolver
becomes the byte-fetch path.

Peak memory at every stage (emit, store, push, fetch, decrypt, reassemble) is
**`O(CHUNK)`** — independent of file size, backlog depth, and realm size.

⚠ **Emit is `O(N × CHUNK)` since the concurrent chunk transfer landed** (`N` =
`DefaultChunkTransferConcurrency`, 4). Chunk `PUT`s overlap because each one is a full round
trip to a *single-region* relay, which made a serial emit round-trip-bound rather than
bandwidth-bound for anyone far from it. Chunk **production** — the positional read, the
order-sensitive whole-file hash fold, the offset advance — stays strictly sequential, and the
fan-out takes its permit *before* producing, so residency is bounded by the permit count rather
than by the file. The property that actually fixed the OOM is independence from file size and
backlog depth, and that is unchanged: a 200-chunk file still peaks at `N`. See
[`content-plane-transfer-concurrency.md`](../proposals/content-plane-transfer-concurrency.md).

#### GC: two sweeps, composed

The content plane carries its own GC, distinct from the ordered-log tombstone
GC of §2.6. The two compose:

* A file delete is a normal `DeleteBlock` against the manifest entity. The §2.6
  sweep frees the manifest body on the ordered plane after quorum + retention.
* The content plane has a **time-based retention/orphan sweep**
  (`lastReferencedAt` refreshed on `PUT` and `GET`, collectible after a window
  ≥ the tombstone-body retention). ⚠⚠ **It is disabled in production and must
  stay that way**: `CONTENT_RETENTION_WINDOW_MS` defaults to `Long.MAX_VALUE`.
  The content plane holds **durable user data**, not a delivery log — a file
  nobody opens for months is still live, and the relay may hold its only
  durable copy — so a time-since-last-touch sweep reaps live data. That is the
  "opened after two years → gone" bug. The mechanism survives only as
  a test hook and as the seam an explicit-window admin reclaim drives.

What actually reclaims content is **owner- or ref-scoped**, never time-driven:

* **Client-driven, per file** — `POST /sync/content/delete` (`dropRefs`,
  ST-3b). The owner's own client holds the plaintext manifests, works out what
  nothing references any more, and names it; the relay stays blind and obeys.
  Live on desktop and Android.
* **Account-lifecycle** — `reclaimOwner`, keyed on `ownerId` after a grace
  period. Built, flag-gated, off by default.

⚠ **This reverses the earlier decision**, recorded here and in ADR 0017 §iv,
that a client "release refs" signal was rejected as a metadata leak worse than
the access-pattern residual. It shipped, because the retention sweep it was
weighed against turned out to be unusable at all (above) — and the leak is
narrower than it looked: `ownerId` comes from the verified device JWT and never
from the request body, so a device can only name its own owner's refs. See
threat model **T-R-13**, and `retention-quota-model.md` for the tier taxonomy
that separates durable content from cache and from the delivery log.

A blob that neither mechanism names still lingers against the owner's quota;
quota is the brake. A manifest refcount remains House-Rule-#1-impossible.

#### Access-pattern residual

The relay observes *which* content refs a device fetches and *when* — so it
can infer which files a user opens and their timing, even though content
stays ciphertext. The all-drain model didn't leak this. v1 accepts the
residual and documents it in `threat-model.md` T-R-09. Batching / padding /
decoy fetches are noted as the mitigation if the residual becomes
unacceptable; oblivious fetch is **not** built for v1.

## 3. Conflict Resolution: Hybrid Logical Clock (HLC)

To ensure reliable "Last Writer Wins" without relying on perfect NTP synchronization:

* Each update carries an HLC timestamp.
* HLC combines physical time with a logical counter to ensure a strict partial ordering of events
  across devices.
* **Comparison Logic**: If physical times are equal, the logical counter breaks ties. If counters
  are also equal, the `deviceId` (string comparison) ensures a deterministic winner.

---

## 4. Architectural Implementation

### 4.1 SyncRelay (Backend Abstraction)

The `SyncRelay` interface abstracts the physical network communication. It uses `suspend`
functions and `TResult` to support real-world network conditions.

* `pushBlock(block)`: Sends an encrypted payload to the mailbox.
* `getChanges(sinceTimestamp)`: Retrieves new blocks since the last sync.
* `fetchProperty(entityId, propertyId)`: Targeted retrieval for `OnDemand` sync strategies.

### 4.2 SyncProcessor (Conflict Logic)

The `SyncProcessor` is the central brain for processing incoming data. It handles:

1. **Decryption**: Uses `SyncCryptoManager` and derived keys.
2. **Parsing**: Converts binary payloads back into Tenshu `Data` objects.
3. **LWW Resolution**: Compares the HLC of the `SyncBlock` with local `SyncMetadata`.
4. **Atomic Save**: Saves both the data and the updated metadata to the database.

### 4.3 Platform Delegates (Android/Desktop)

To handle platform-specific concerns like secure storage and background execution:

* **`SecureStorageDelegate`**: Interface for storing the recovery phrase and stable device ID.
    * *Android*: Implementation using `EncryptedSharedPreferences`.
    * *Desktop*: Implementation using local file encryption or system keychain.
* **WorkManager (Android)**: Integration to ensure sync runs reliably even when the app is
  backgrounded or the device restarts.

---

## 5. UI Implementation Plan (Control & Drive Apps)

To support this synchronization logic, we need to introduce several screens and UI components,
primarily in the **Control** app (for management) and the **Drive** app (for data interaction).

### 4.1 Sync Dashboard (Control App)

* **Device List**: Displays all of the user's realm devices.
    * *Features*: Device Name, Device Type (Android/Desktop), Last Seen, Sync Status.
    * *Actions*: Rename device, Revoke device.
      ⚠⚠ **Corrected 2026-08-21:** this read *"Revoke device (triggers key rotation)"*. The personal
      realm does **not** rotate keys — `RevokeDeviceImplementation` rotates nothing, and the volume
      key is a deterministic function of the recovery phrase, so there is nothing to re-wrap. The
      containment is relay-side revocation alone (threat model T-K-01, amended).
* **Sync Health Overview**: Shows pending uploads/downloads and any synchronization errors.
* **Security Settings**:
    * **Recovery Phrase Screen**: A secure area to view and copy the BIP-39 seed phrase. Includes
      a "Hide/Reveal" toggle for privacy.
    * **Show Pairing QR**: Generates a QR code containing the `IdentityID`, `DeviceID`, and a
      temporary `PairingSecret`.

### 4.2 Pairing Wizard (Control App)

* **Selection Screen**: Option to "Add this Device" (Scan QR) or "Link another Device" (Show QR).
* **QR Scanner**: Integration with the device camera to scan a pairing code.
* **Confirmation**: Displays the name of the device being linked and performs the initial P2P key
  exchange.

### 4.3 On-Demand Content UI (Drive App)

* **Remote-Only Indicators**: Files whose `data` property is not yet downloaded should show a
  specialized icon (e.g., a cloud-down icon).
* **Download Progress**: Linear progress bar or circular indicator when a user clicks an "On-Demand"
  file to trigger the sync.
* **Global Sync Rules**: Toggle in settings to allow/disallow "Sync over Mobile Data."

---

## 6. Implementation Plan (⚠ RETIRED — historical record)

⚠⚠ **This is the original 2025 build checklist, kept only as a record of what was planned.**
Do not read it as a description of the system. Several items name components that were never
built or were explicitly rejected later — `SyncBackendClient`, "Ghost Entity" support, and
`ReKeyingWorker` (bulk re-encryption on key rotation, **declined** — see threat-model
§"A2/A3 epoch rotation will not be built"). For what exists, read §2 and the ADRs; for what is
in flight, `milestones.md`.


### Phase 1: Core & Identity (Foundation)

1. **[Core]** Define `Syncable` trait and `SyncStrategy`/`ConflictStrategy` enums.
2. **[Core]** Implement `HybridLogicalClock` (HLC) utility.
3. **[Identity-Common]** Extend `User` and create `Device` schema.
4. **[Identity-Proxy]** Implement `PairDevice` and `RevokeDevice` operations.
5. **[Crypto]** Create `SyncCryptoManager` (Shared Identity Key and Shared Volume Key management).

### Phase 2: Sync Infrastructure

6. **[Sync-Service]** Create new module `:sync-service` with `SyncBlock` DTOs.
7. **[Sync-Proxy]** Implement `SyncObserver`: Observes database changes and generates `SyncBlocks`.
8. **[Sync-Proxy]** Implement `SyncScheduler`: Evaluates rules (WiFi, battery) and triggers
   push/pull.
9. **[Sync-Client]** Implement `SyncBackendClient`: Interaction with the simplified relay backend.

### Phase 3: Binary Management & Re-keying

10. **[Sync-Proxy]** Implement "Ghost Entity" support (inserting metadata while binary remains
    remote).
11. **[Sync-Proxy]** Implement `ReKeyingWorker`: Background task to re-encrypt blobs when keys
    rotate.

### Phase 4: Integration & UI (Control App)

12. **[Control-App]** Create `SyncDashboardScreen` (Device list and status).
13. **[Control-App]** Create `PairingWizardScreen` (Scanner and QR Generator).
14. **[Control-App]** Create `RecoveryPhraseScreen` (BIP-39 display).

### Phase 5: Integration & UI (Drive App)

15. **[Drive-App]** Implement "On-Demand" fetch UI in the file list (loading states, cloud icons).
16. **[Drive-App]** Create "Sync Preferences" screen (WiFi-only toggle, data limits).

---

## 7. Testing Strategy

### 5.1 Unit Tests

* `HLCTest`: Verify monotonic ordering and clock skew handling.
* `SyncCryptoTest`: Verify envelope encryption and key derivation from recovery phrase.
* `ConflictResolutionTest`: Verify LWW and Merge strategies on sample entities.

### 5.2 Operation Tests

* `PairDeviceOperationTest`: Verify device registration and initial key exchange.
* `SyncMetadataOperationTest`: Verify that changing a `Syncable` entity generates the correct
  `SyncBlock`.

### 5.3 Integration Tests (App Level)

* **Mock Backend**: Create a `MockSyncRelay` that simulates the network and "blind" storage.
* **Multi-Proxy Test**:
    1. Start two `TestProxy` instances representing two devices.
    2. Perform a "Pairing" operation.
    3. Device 1: Add a `File`.
    4. Verify Device 2: Receives metadata immediately (Eager).
    5. Verify Device 2: Binary is NOT downloaded yet (OnDemand).
    6. Device 2: Request the binary, verify it downloads and decrypts correctly.
    7. Revoke Device 1, verify Device 2 loses relay access (401).
       ⚠ Was *"verify Device 2 rotates keys and starts re-encryption"* — that step tests a control
       that does not exist (T-K-01, amended 2026-08-21). A test plan asserting an unbuilt mechanism
       is worse than none: it reads as coverage.
