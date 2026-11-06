# Multi-realm-per-device sync runtime (SR-M) — and the shared-realm migration on top (SR-3)

**Status:** In progress. Prerequisite for shared folders. Surfaced 2026-05-31 while
designing SR-3 (shared-realm creation) after SR-1 + SR-2 (the shared-realm crypto + schema
primitives) landed on `feat/shared-realm-primitives`. **SR-M.0** (`RealmContext` seam),
**SR-M.1a** (emit/ingest crypto path reads `RealmContext`), **SR-M.1b-core** (`RealmRegistry` +
per-realm emit routing + realm-namespaced DataIds), **SR-M.1b-drain** (per-realm outbound push),
and **SR-M.1b-pull** (per-realm inbound `SyncProcessor` + cursor) have all landed — each a
no-behaviour-change-on-the-personal-path slice verified by an in-process isolation test plus the
full sync suite + system tests. No shared realm is *registered* in production yet (that is SR-3b,
the first `register()` caller); the multi-realm pipeline is dormant until then.

**Why this doc exists.** `tenshu-rei.md` §2.3 / §5.C decided that a shared folder is **its own
realm** with its own master key and its own change stream, and that creating one migrates a folder
subtree into it (§5.C Q23, option (a)). Implementing that surfaced a prerequisite the roadmap had
not called out: **the sync runtime is single-realm-per-device today.** A shared realm cannot be
synced until one device can participate in *multiple* realms at once. That refactor — "SR-M" — is a
substantial, security-critical change to the most load-bearing code in the system, so it is written
up here for review *before* any `:sync-service*` code is touched.

This doc is the design home for SR-M and the SR-3 migration operation that sits on it. No code
changes accompany it (the maintainer chose "design + pause").

---

## 1. The finding: the runtime is single-realm-per-device

Every realm-coupled decision in the client reads from a single-valued `SyncManager`. Evidence:

- **`sync-service-proxy/.../SyncManagerImpl.kt`** holds one `currentOwnerId`, one `derivedKeys`,
  one `currentMasterKeyPair`, one `hlcManager`, one `lastSyncTimestamp` (persisted under a single
  key). `initialize` / `initializeWithKeys` **overwrite** these — they are not a map.
- **`SyncObserver`** (`generateSyncBlock`, `emitFileContent`) reads `syncManager.currentOwnerId` +
  `syncManager.getDerivedKeys()` and encrypts every block under `keys.sharedIdentityKey` /
  `keys.sharedVolumeKey`. Outbound blocks land in **one** `PENDING_BLOCKS` array, not partitioned
  by realm.
- **`SyncProcessor.decryptAndParse`** decrypts with `syncManager.getDerivedKeys()!!.sharedIdentityKey`
  — there is **no per-block key lookup**; the block's `ownerId` is never consulted for key selection.
- **`SyncScheduler`** drains the single pending queues and pulls one `GetChanges` / `GetTombstones`
  cursor against one realm.
- **DI token providers** (`PlatformProxyModule.kt`, desktop + android) build
  `jwtTokenProvider { ownerIdProvider = { syncManager.currentOwnerId } }`; `RestSyncRelay` /
  `RestContentBlobStore` / `RestShareRelayClient` are each a single per-owner client. **All realm
  scoping is derived live from `syncManager.currentOwnerId` at request time** — which is the lever
  SR-M uses.
- **`core-common/.../db/{DatabaseImpl,ObjectStore}.kt`** is one store keyed by `dataIdHash: Long`.
  `DataId.getDataIdHash()` is `hash(type + key)` with **no realm component** — a `File` at path `/x`
  hashes identically in any realm.

**The relay is already per-owner-scoped** and needs **zero change**: `SyncMailbox` is
`Map<ownerId, …>`, `ContentBlobStore` is per-owner, the JWT `iss = ownerId`, and a shared realm is
just "an ordinary realm" at the relay (`tenshu-rei.md` §3.4). So the entire gap is **client-side**.

After migration the host syncs **both** its personal realm and the shared realm. That requires
multi-realm-per-device, which the client does not support.

---

## 2. SR-M — minimal viable multi-realm runtime

The realm-coupled axes and what each needs:

| Axis | Today | Realm-aware need |
|---|---|---|
| Keys (A2/A12 derived) | `syncManager.getDerivedKeys()` (one) | key set per realm |
| ownerId / JWT | `syncManager.currentOwnerId` → one token | per-realm ownerId + token |
| HLC clock | one `hlcManager` | per-realm (Q27: each realm is its own change stream) |
| Sync cursor | one `lastSyncTimestamp` | per-realm cursor |
| Local DB namespace | one `ObjectStore`, content-hash keys | entities disjoint per realm |
| Outbound queue | one `PENDING_BLOCKS` | per-realm pending |

### 2.1 The two cost-avoiding decisions

**(a) One shared `ObjectStore`, realm-namespaced DataIds — no second physical DB.**
The migration *recreates* each entity under the shared realm anyway, so key the recreated entity
`"$sharedRealmId::$path"` instead of `"$path"`. Consequences:
- No second `ObjectStore` / `DatabaseImpl` / SQLite store — the single largest SR-M cost is gone.
- Observer save-routing becomes a deterministic prefix read (`realmForDataId(dataId)`), not a
  heuristic.
- Personal-realm keys stay byte-identical (no prefix) ⇒ **zero migration of existing data.**

Contained cost: the file-service `StringDataId` providers (keyed on `path`) need a realm-prefix-aware
variant for shared-realm entities. This lives in the migration op's entity recreation + the shared
realm's reads — not a core-wide change. (Surfacing a shared realm in Drive's browsable tree is a
*later* slice; SR-3 only needs the bytes correctly into the shared realm.)

**(b) Instance-per-realm pipelines — no per-block crypto lookup in the hot path.**
Introduce a `RealmContext` value (`realmId, ownerId, derivedKeys, hlcManager, cursor accessor,
deviceSigningKeyPair, tokenProvider`) and a `RealmRegistry` (`Map<realmId, RealmContext>` plus the
per-realm `SyncScheduler` / `SyncProcessor` / `SyncRelay` client it owns). Each realm's
`SyncProcessor` is constructed with **its own keys**, so `decryptAndParse` never gains a
key-selection branch in the security-critical method — isolation is **structural**. The personal
realm is `RealmContext` #0, behaviour unchanged.

The alternative (one processor + `Map<ownerId, DerivedKeys>` keyed on `block.ownerId`) is ~20 lines
but puts a key-selection branch in the most dangerous method, with a cross-realm-leak failure mode.
Prefer structural isolation.

**(c) Per-realm token provider** reuses the host's existing **device A4 signing key** (the shared
realm's first device = this device), with a different `ownerId`. Relay JWTs verify against
`KnownDevicesStore[(sharedOwnerId, thisDeviceId)]`. No new pairing.

### 2.2 Highest-risk item

The single `SyncObserver`'s **save-routing**: routing a saved entity to the wrong realm emits a
block under the wrong key, crossing the encryption boundary. The namespaced-DataId scheme (2.1a)
collapses this from a heuristic into a deterministic prefix lookup — the mitigation is the design,
not an afterthought.

### 2.3 What SR-M deliberately does NOT build (post-v1)

A second physical DB; a per-block crypto lookup; per-realm bundle/process isolation; per-realm
*device credentials* (the host reuses one device key across realms — see the residual in §5).

---

## 3. SR-3 — the migration operation (on top of SR-M)

`CreateSharedRealmFromFolder` — an `AsyncOperationImplementation` in `:file-service-proxy` (House
Rule #3: non-suspend interface + `IoOperation` / `TaskInteractor`), reusing folder traversal +
`DeleteFileImplementation`'s tombstone path:

1. **Bootstrap the shared realm** — reuse `CreateRealmImplementation` patterns: generate `Kr` (32
   random bytes — *not* phrase-derived; a shared realm is key-wrapped to members and has no recovery
   phrase) + an Ed25519 realm keypair for `POST /sync/realm`; register at the relay with the host's
   existing device A4 as first device. `DerivedKeys(sharedIdentityKey, sharedVolumeKey)` are derived
   from `Kr`.
2. **Self-join** — wrap `Kr` to the host's **own A13** public key and `saveData` a
   `SharedRealmMembership(sharedRealmId, wrappedMasterKey, role = OWNER)` via SR-2's
   `SharedRealmMembershipKeys.create`. It is `Syncable(Eager)` on the **personal** realm's identity
   service ⇒ **host devices auto-join** (Q23): each device unwraps `Kr` with its A13 (same phrase ⇒
   same A13) and registers the shared `RealmContext`.
3. **Re-encrypt + move the subtree** (post-order over `Folder.Fields.FILES`): for each entity,
   recreate it under the shared realm (namespaced DataId; file content via `emitFileContent` under
   the shared `RealmContext` ⇒ chunks AEAD'd under `Kr.sharedVolumeKey`), **then** tombstone it in
   the personal realm (`TombstoneEmitter.emit`, ADR 0006). **Recreate-before-tombstone** ⇒ an
   interruption leaves the entity in *both* realms (resume re-tombstones), never *neither*
   (no data loss).
4. **Pre-flight** — block with a typed error if any entity *outside* the subtree references one
   *inside* it (Q23 v1 = self-contained subtree only; cross-realm references defer — collides with
   Q27 causality + the reference-property work).
5. **Resumability** — wrap as a Layer-2 `BackgroundOperation` / `ScheduleService` item (closest
   existing pattern: folder-upload P3) with a persisted per-entity `MigrationState`. Idempotent
   because shared DataIds are deterministic and content refs are hash-of-ciphertext (immutable, a
   re-PUT is a no-op).

**Q26 load-bearing invariant (free):** every new write generates a fresh content key wrapped under
the *current* (shared) master key — `emitFileContent`'s existing fresh-per-chunk AEAD under the
realm's volume key already satisfies this; state it explicitly at the content-wrap site during SR-3c.

**SR-3b.1 as-landed.** Steps 1–2 above shipped as `SharedRealmBootstrapper` (`:sync-service-proxy`),
plus three new `SyncCryptoManager` primitives: `generateSharedRealmMasterKey()` (random 32-byte A12,
CSPRNG — *not* phrase-derived), `deriveSharedRealmKeys(masterKey)` (pure HKDF split of A12 into the
two data keys, **no Argon2id** since A12 is already full-entropy; distinct HKDF info tags
`tenshu/shared-realm/shared-{identity,volume}/v1` so the shared key space never collides with the
personal `deriveKeys`), and the previously default-throwing `wrapKeyForIdentity`/`unwrapKeyForIdentity`
now have a deterministic **stub** impl (`BootstrapSyncCryptoManager`) so self-join round-trips
in-process. The realm's **Ed25519 identity** (ownerId + bootstrap self-signature + future device
admission) is distinct from the symmetric **A12** that members are key-wrapped; the bootstrapper holds
the realm Ed25519 keypair in `RealmContext.masterKeyPair`. Bootstrap reuses the device's existing
(personal) relay client — `createRealm` is unauthenticated and takes `ownerId` as a param — and the
realm is registered with **`relay = null`** (no push/pull until SR-3b.2's per-realm relay client).
**Known v1 limitation:** self-join derives the device's A13 from the recovery phrase
(`getRecoveryPhrase()`), so only phrase-holding devices can create or auto-join a shared realm; a
paired joiner (holds only `DerivedKeys`) cannot, and a device whose phrase slot was cleared by lock
setup must be unlocked. Persisting A13 at setup + admitting non-phrase devices via a relay-side
delegation is SR-3b.2+.

**SR-3b.2 as-landed.** Step 3 (register) is now uniform across owner + joiner via two additions.
(a) `RealmRegistry` gained a **relay-factory seam**: a `relayFactory: (ownerId) -> SyncRelay?` ctor
param; `register(realm, relay = null)` resolves `relay ?: relayFactory(ownerId)`, so a registered
realm picks up a per-realm relay automatically. `initSyncService` exposes it as
`sharedRealmRelayFactory` (default null ⇒ no relay, as before — the production `RestSyncRelay`-per-realm
factory is SR-3b.3). (b) `SharedRealmJoinObserver` (a `DataObserver`, wired in `initSyncService`):
on every `SharedRealmMembership` save — local self-join **or** synced in from another device (it
deliberately reacts to `SYNC_INGEST`, unlike `SyncObserver`) — it unwraps `Kr` with this device's A13,
derives the realm keys, and registers a **joiner** `RealmContext` (`masterKeyPair = null`). It skips
realms already registered (`RealmRegistry.isRegistered`), so the creating device's own owner
registration — which the bootstrapper performs **before** the self-join save and which carries the
realm's Ed25519 identity — is never clobbered; a membership wrapped to a *different* member's A13
fails the unwrap and is skipped (not for us). This is the cross-device auto-join mechanism, verified
in-process (the membership is saved directly, as a joiner's `SyncProcessor` would on ingest); the
live two-device convergence over a real relay is SR-3b.4 (the scope split below changed; see §3.1).

**SR-3b.3 as-landed.** The `CreateSharedRealm` SDK op — schema (`:sync-service`) + `CreateSharedRealmRo`
(`:sync-service-client`, registered in `SyncServiceClientModule`) + `operation(CreateSharedRealm.Schema)`
in `SyncServiceCommonModule` + `CreateSharedRealmImplementation` (`:sync-service-proxy`, an
`AsyncOperationImplementation` wrapping `SharedRealmBootstrapper`, returns the new realm's `ownerId` as
a `TString`). Threaded into `syncProxyImplementationFactory` via an **optional** trailing
`realmRegistry: RealmRegistry?` param — the op resolves to `null` (unavailable) when no registry is
wired, so all existing factory callers compile unchanged; production / system-test wirings pass
`SyncProxyComponents.realmRegistry`. In-process verified (`CreateSharedRealmOperationTest`: the factory
gates the op on the registry; invoking it creates + registers a realm and returns its `ownerId`).
**Scope note:** SR-3b.3 was *re-scoped* to just the op once cross-device sync proved blocked on the
two prerequisites in §3.1; the production per-realm relay factory + the live two-device test moved to
SR-3b.4.

### 3.1 Cross-device prerequisites (SR-3b.4 — discovered during SR-3b.3)

Auto-join (SR-3b.2) + the op (SR-3b.3) make a device create + locally register a shared realm, but a
*second* device cannot yet actually sync that realm. Two prerequisites, both touching the threat model:

1. **Relay-side device admission.** `POST /sync/realm` registers only the *creating* device's A4 for
   the shared `ownerId`. A second device's JWT for that realm is rejected (unknown device). The OWNER
   (who holds the realm's Ed25519 identity, stored in `RealmContext.masterKeyPair`) must sign a
   delegation admitting each other device's A4 — the same shape as personal-realm pairing
   (`postPairingResponse` registers `(ownerId, newDeviceId) → pubkey`), reused for the shared `ownerId`.

2. **Key delivery to phrase-less joiners.** SR-3b.2's observer unwraps `Kr` with A13, which is *derived
   from the recovery phrase*. A **paired joiner never receives the phrase** (only `DerivedKeys`), so it
   can't derive A13 and can't unwrap `Kr`. The design's "host devices share the phrase ⇒ same A13"
   assumption holds only for phrase-holding devices, not the normal paired case. Two ways to fix it:

   - **(B) Deliver A13 via the pairing payload — *recommended*.** Add the realm's A13 *private* key to
     the joiner payload (beside `DerivedKeys`, which already travel that handshake-encrypted channel),
     so every paired device holds the same per-realm A13 and can unwrap any `Kr` wrapped to it.
     Membership stays one row, `Kr` wrapped **once** to the realm's A13 (preserves the threat-model's
     "wrap once at master-key level, never per-device" invariant + O(members) membership). Matches the
     A13 asset entry's stated intent ("re-derivable from the recovery code, **covered by pairing**").
     Auto-join then works unchanged once joiners hold A13. Cost: A13's private key rides the pairing
     channel (no *new* trust boundary — that channel already carries `DerivedKeys`, equally sensitive)
     and revocation is coarse (excluding one device cryptographically means rotating A13 across all the
     user's devices — but Tenshu revocation is already A4/epoch/tombstone-based, and the personal realm
     already shares `DerivedKeys` across devices, so this is no regression).
   - **(A) Per-device wrap of `Kr`.** Wrap `Kr` to a *per-device* encryption key instead of the
     per-realm A13 → membership becomes per-`(realm, device)`, O(members×devices) blobs, the OWNER
     re-wraps `Kr` to every new device, and it needs a **new** persistent per-device X25519 asset (A4
     is Ed25519; the pairing key is ephemeral) plus a publication mechanism. Bigger model + threat-model
     change. Its one win — per-device cryptographic exclusion without rotating others' keys — isn't a
     v1 requirement and can be layered on later; **(B) does not preclude it.**

   **Recommendation: (B). Confirmed by the maintainer; landed in SR-3b.4a (below).**

SR-3b.4 (option B confirmed) splits into three sub-slices:
- **SR-3b.4a — A13 delivery to joiners (LANDED).** The pairing key-blob the host encrypts under the
  pairing ECDH secret — previously just the shared identity key — now carries
  `sharedIdentityKey | sharedVolumeKey | A13pub | A13priv` (`PairingKeyBlob`, recovered by length; a
  shorter blob degrades gracefully). The host derives A13 from its phrase at approve time; the joiner
  stores it (persisted in the `J2:` joiner payload, re-hydrated across restarts).
  `SyncManager.getRealmEncryptionKeyPair()` resolves A13 uniformly (host: derive-from-phrase; joiner:
  the delivered key), and both `SharedRealmJoinObserver` + `SharedRealmBootstrapper` now use it — so a
  **paired joiner auto-joins**, closing the phrase-less-joiner blocker. **Client-only — no relay
  change** (the blob rides the existing opaque `encryptedMasterKey` field). Also fixes the prior
  identity-key-as-volume-key pairing simplification. Verified in-process + a dev-relay system test
  asserting the joiner's A13 equals the host's after a real pairing handshake.
- **SR-3b.4b — relay device admission (LANDED).** New unauthenticated, master-delegation-gated
  `POST /sync/admit-device` relay endpoint (`RealmRouting`, sibling of `/sync/realm`): given
  `(ownerId, newDeviceId, newDevicePublicKey, masterDelegation, epoch)` it verifies the delegation
  against the realm's stored `masterPublicKey` (the `MasterDelegationVerifier.pairingResponseMessage`
  shape the pairing-response path already uses) and registers the device in `KnownDevicesStore` — so
  the device's JWT verifies on the shared `ownerId` stream. Decoupled from the pairing handshake (no
  pairingSecret / encrypted-key mailbox — the device already holds the realm's keys). Client:
  `SyncRelay.admitDevice` (+ `RestSyncRelay`/`MockSyncRelay`) + owner-side `SharedRealmDeviceAdmitter`
  (signs with `RealmContext.masterKeyPair`, reuses the personal relay client since admit is
  unauthenticated). Verified: `AdmitDeviceRoutingTest` (4: valid registers / bad delegation 401 + no
  register / unknown realm 404 / missing field 400) + `SharedRealmDeviceAdmitterTest` (owner signs+posts;
  joiner can't admit). **Open (4c):** the *trigger* — which devices the owner admits, and when (enumerate
  the personal realm's `Device` entities on bootstrap / on new-device-appears) — rides 4c's orchestration.
- **SR-3b.4c — integration (logic + wiring LANDED; end-to-end stream test pending).** Three pieces:
  (a) `sharedRealmRelayFactory(baseUrl, httpClient, context, syncManager, cryptoManager, nowEpochSec)`
  — builds a per-realm `RestSyncRelay` (JWT scoped to the shared `ownerId`, same A4 signer) for
  `RealmRegistry`'s `relayFactory`; (b) the **admit trigger**: `SharedRealmBootstrapper` now enumerates
  the personal realm's `IdentityService.Relations.DEVICES` and admits each *other* device via
  `SharedRealmDeviceAdmitter` (best-effort; skips self); (c) **system-test DI wiring** — `RealmRegistryHolder`
  + `sharedRealmRelayFactory` threaded into the system-test `initSyncService` + `CreateSharedRealm` op
  reachable. Verified: `SharedRealmAdmitOnBootstrapTest` (admits the peer, not self) + the full
  `:system-test-utils:test` suite green (DI wiring non-regressive).
- **SR-3b.4c-verify — the headline proof (LANDED).** `SharedRealmStreamSyncSystemTest` (dev relay, real
  crypto): host `CreateSharedRealm` → admits the joiner → writes a namespaced `Notification`
  (`uuid = "$sharedOwnerId::…"`, the simplest registered Syncable; `UnixInstantDto(0)` timestamp) →
  the joiner auto-joins and **pulls it off the shared stream**. The first real cross-device shared-realm
  sync. **Caught a real bug only a live-relay test could:** `SharedRealmBootstrapper` minted the realm's
  Ed25519 master via `cryptoManager.generateKeyPair()` — which returns an **X25519** keypair in the real
  `DefaultSyncCryptoManager` (the bare `generateKeyPair` is the pairing-handshake key; A4/master are
  `generateDeviceSigningKeyPair`) — so the relay's Ed25519 verification of the bootstrap self-signature
  failed, the realm was never created, and the host's shared-stream `pushBlock` was rejected. The stub
  crypto's curve-agnostic sign/verify hid it through every in-process test. **Fixed:** the bootstrapper
  now uses `generateDeviceSigningKeyPair()` for the realm master. Production Control `PlatformProxyModule`
  DI (the `sharedRealmRelayFactory` + the op) is the only remaining piece, gated on the Track-C "create
  shared folder" UI consumer (Android-unverifiable, no UI yet).

### 3.2 SR-3c — shared-folder file model (design; maintainer-reviewed 2026-06-01)

SR-3c migrates a folder subtree into a shared realm. Exploring the file-service surfaced two
implementation realities that this section pins down before any migration code lands.

**(A) Keying — only shared-realm entities are prefixed; the prefix is an opaque storage key, not a
name.** A personal-realm entity keeps its natural unprefixed key (a `File` stays keyed `/test.png` —
byte-identical to today, zero migration of existing data). A *shared*-realm entity is keyed
`"$realmId::$baseKey"` where `realmId = ownerIdForMasterPublicKey(realmMasterPub)` — an **opaque,
stable, collision-resistant** id (hash of the realm's master public key), **never** a human name.
Rationale (and the maintainer's question answered):

  - *Why not `$userName::` / a Rei name?* The prefix is a **storage/wire key**, never shown to the
    user (the UI lists shared folders in their own section and strips the prefix — see (B)), so
    human-readability buys nothing. A username is **mutable** (Rei re-verification, renames) and
    **collidable** (two users pick "alex"); keying durable storage off a mutable identity is a
    classic footgun. `realmId` is stable + globally unique by construction.
  - *How everyone else does it.* Opaque stable namespace id as the key prefix, human name as a
    separate mutable label: multi-tenant DBs prefix rows with a `tenant_id` UUID (never the tenant's
    name); S3 = opaque bucket + key; Git content-addresses by hash; filesystems namespace by
    volume/inode-set, not mount label. The id is allocated globally-unique precisely so names can
    collide freely. The human label (shared-folder name, owner's Rei name) is **presentation** — a
    field / a consumer-registry entry, decoupled from the key — exactly the wire-key-vs-presentation
    split already used for `RealmData`.
  - *Collisions — none to avoid, by construction.* `realmId` is derived from a **fresh random
    256-bit Ed25519 master key minted per `createSharedRealm`** — **not** from the file, path, or
    name. Sharing the same folder twice mints two independent random realms (`R1::…`, `R2::…`) —
    correct semantics (each its own `Kr` + membership, separately revocable), never a collision to
    dedupe. A `realmId` clash needs two *random* keypairs to hash equal (≈ 2⁻²⁵⁶) — randomness is the
    allocator, so **no counting / sequence / consensus is needed** (the username approach we rejected
    is the one that *would* collide — two "alex"s, re-share reuses the name — needing exactly that
    machinery). Within a realm, `$realmId::$path` keys are unique because the migrated subtree's
    paths are; the whole `$realmId::*` keyspace is private to that realm, so no cross-realm or
    personal clash either. Backstop: the relay's `POST /sync/realm` is `createIfAbsent(ownerId)` → a
    409 on the 2⁻²⁵⁶ event; it never fires in practice.

  **Decision: namespace the `path` field with `$realmId::`, strip it at the repository boundary**
  (AskUserQuestion option A). Since `File`'s DataId *is* its `path` (`stringDataId(File.Field.PATH)`)
  and the device has **one** `ObjectStore` (SR-M), the namespace prefix must live in the path-derived
  key to avoid colliding with a personal `/test.png`. A migrated file's stored/wire `path` becomes
  `"$realmId::/report.pdf"` (path relative to the *share root*); `TenshuFilesRepository`/the shared
  UI strips the `"$realmId::"` prefix on read. Least core-invasive (no realm-aware-`DataIdProvider`
  core change — the rejected option B); the prefix's ugliness is contained to the storage/wire layer
  it never escapes. A realm-aware `DataIdProvider` (option B) stays the documented escalation if a
  clean wire `path` ever becomes load-bearing.

**(B) Content-plane routing is a prerequisite (Blocker 2).** `SyncObserver.emitFileContent` is **not**
realm-aware — it hardcodes `syncManager.currentRealmContext()` for *both* the AEAD keys *and* the
content-plane `PUT` target (the personal `ContentBlobStore`). Migrating file **bytes** to a shared
realm needs **SR-3c-prereq**: (i) realm-route the keys (mirror `generateSyncBlock`'s
`realmRegistry?.realmForDataId(targetEntityId) ?: currentRealmContext()`), and (ii) a **per-realm
`ContentBlobStore` factory** (mirror `sharedRealmRelayFactory` — the chunk `PUT`/`GET` must hit the
shared `ownerId`'s content plane with the shared-realm JWT). Until then, only folder *structure +
metadata* (the Eager data blocks) can migrate. This prereq is fork-independent and lands before the
migration op.

*SR-3c-prereq as-landed:* `emitFileContent` now resolves `realmForDataId(targetEntityId)` for the
realm's keys, picks the **per-realm `ContentBlobStore`** (`RealmRegistry.contentBlobStoreFactory` /
`runtimeFor(ownerId).contentBlobStore`, built by `sharedRealmContentBlobStoreFactory` — a JWT scoped
to the shared `ownerId`), and queues the manifest to the per-realm outbound relation
(`PENDING_BLOCKS::$ownerId`) — all three exactly mirroring `generateSyncBlock`. Personal targets are
byte-identical (no registry / unprefixed key → personal store + base queue). `initSyncService` gains
`sharedRealmContentBlobStoreFactory`; production Control DI wiring rides the same UI-gated prod-DI
slice as the relay factory. Verified by `MultiRealmFileContentEmitTest` (namespaced → shared store +
shared queue under shared keys; plain → personal, unchanged). No threat-model amendment — no new
wire/relay surface (the per-realm content store hits the existing `/sync/content` endpoint, scoped by
the SR-3b.4 per-realm JWT; House Rule #1 intact — the store only ever sees ciphertext).

**(C) FileHistory — drop on migrate (v1).** A migrated `File`'s `FileModification` log
(`File.FileHistory`, keyed by the file's DataId via `EntityPropertyDataId`; each mod back-references
`fileId: FilePath`) is **not** carried into the shared realm in v1 — history attribution crosses the
realm boundary (a personal-realm device authored it under the personal realm's keys), and re-keying
the mods + their device references is a separate causal problem (Q27). v1 keeps current state, drops
history. The mods stay in the personal realm and are tombstoned with the file.

**(D) Pre-flight — self-contained subtree only (Q23).** Reject (typed error) if any entity *outside*
the subtree references one *inside* it. Today the only cross-entity reference into files is
`File.FileHistory` (handled by (C), internal to each file) — there is no external pointer *into* a
subtree from a sibling, so the v1 pre-flight is a structural check (the subtree is a self-contained
folder). Cross-realm references defer (collides with Q27 + the reference-property work).

**(E) Migration op shape — `MigrateFolderToSharedRealm` (`:file-service-proxy`,
`AsyncOperationImplementation`).** (1) pre-flight (D); (2) `SharedRealmBootstrapper.createSharedRealm()`
→ the shared realm + admit devices; (3) **post-order** traverse the subtree
(`DeleteFileImplementation.deleteRecursively`'s `resolveList(Folder.Fields.FILES)` pattern); (4) for
each entity, **recreate** it under `"$realmId::$path"` (metadata via `saveData` → routes to the shared
realm; bytes via the (B) realm-routed `emitFileContent`), **then** tombstone the personal-realm
original (`TombstoneEmitter.emit`, recreate-before-tombstone ⇒ an interruption leaves it in *both*
realms, never *neither*); (5) one tombstone for the subtree root. **Sub-slices:** SR-3c-prereq
(content-plane routing, B) → SR-3c-struct (structure+metadata migration + pre-flight, A/C/D/E without
bytes) → SR-3c-content (byte migration on top of the prereq) → SR-3d (resumable Layer-2 op, persisted
`MigrationState`).

**(F) Path-arithmetic friction with the `$realmId::` prefix (discovered building SR-3c-struct — needs a
decision before the migration op).** Folder membership is *derived*: `ParentFilesRelationMaintainer`
(and `MoveFileImplementation`, and the bootstrapped root `Folder(path = "/")`) compute a parent with
`path.substringBeforeLast("/")`. Option-A namespaced keys break this arithmetic:
  - `"$realmId::/report.pdf".substringBeforeLast("/")` → `"$realmId::"` — and `"$realmId::/sub"` →
    `"$realmId::"` too, so both top-level entries want a parent at `"$realmId::"` (the realm prefix with
    *no* path). So a shared realm's **root folder must be bootstrapped at `"$realmId::"`** (not
    `"$realmId::/"`), which the per-proxy `initFileService` root-at-`"/"` bootstrap doesn't create.
  - `"$realmId::".substringBeforeLast("/")` returns `"$realmId::"` unchanged (no `/`) — so the share
    root would try to add *itself* to its own `FILES`. The maintainer's `path == "/"` root guard
    doesn't catch the namespaced root.

  ⇒ Option A as-is requires the file-path arithmetic to become **realm-prefix-aware**: a small
  `(realmPrefix, relativePath)` split helper used by `ParentFilesRelationMaintainer`, `MoveFile`, the
  root-bootstrap, and `FilePath` parsing — compute parent on the *relative* path, re-prefix, and treat
  `relativePath == "/"` as the realm root. **This is a fork-independent prereq (`SR-3c-path-prereq`)
  that must land before the migration op** (`SR-3c-struct`), and it's broad enough (multiple path
  sites + a root-bootstrap-per-realm question) to deserve its own slice + maintainer confirmation that
  realm-prefix-aware path arithmetic is the chosen resolution (vs revisiting the keying to option B —
  a realm-aware `DataIdProvider` — which would keep the `path` field clean and sidestep this entirely,
  at the cost of the core DataId-derivation change option B always carried). **SR-3c-struct is blocked
  on this decision.**

**(G) The deeper question — file *identity* vs *location* (maintainer-raised 2026-06-01).** The (A)/(B)
friction is a symptom: Tenshu's file model **conflates identity with location** — a `File` is keyed by
its `path` (`stringDataId(File.Field.PATH)`), so "one file" *is* "one path". But a shared file inherently
has **multiple locations at once**: its place in the owner's personal FS, its place in the shared realm's
FS, and potentially several shared realms. A single `path`-as-key cannot represent that. Three models, in
increasing power:
  1. **Move** (SR-3 / Q23 as-specced): the file *leaves* the personal FS, lives only in the shared realm;
     a realm-aware DataId (B) suffices. But the file vanishes from its personal position — contradicts
     "the file should stay where it is in the personal FS *and* appear in the share".
  2. **Copy**: independent duplicates per FS; edits diverge (a shared-realm edit never reaches the
     personal copy). Wrong for collaboration.
  3. **Placement / hard-link** (the correct long-term model): **one** file identity, *placed* at multiple
     `(realm, path)` locations, edits propagating to all placements — the Unix inode/hard-link model. This
     **decouples identity from path**: `File` keyed by a stable id; folder membership + paths become
     per-realm *placement* records. A realm-aware `DataIdProvider` (B) is the **floor** of this, not the
     ceiling.

  **This is a core file-model change** (today `File` = `stringDataId(path)`), well beyond SR-3c, and it
  **reshapes the SR-3 "carve a subtree" design (Q23 + `drive-shared-realms.md`)** — "share a folder" likely
  becomes "place a folder subtree into a shared realm" (it stays in personal), not "move" it. **Decision:
  this deserves its own file-model design doc (identity-vs-placement; per-realm placement records; how
  edits propagate across placements; migration vs placement semantics) before any SR-3c implementation.**
  The shared-realm *runtime* (SR-M + SR-3b, all landed + proven end-to-end) is unaffected and complete; it
  is the file-service *application* model on top that this opens up. SR-3c-struct / -content / -path-prereq
  are all parked behind this design.

  **Resolved → [`drive-shared-realm-file-model.md`](drive-shared-realm-file-model.md)** (the dedicated
  design): the placement/hard-link model is chosen — `File` re-keyed to a stable `fileId` (content is
  already content-addressed, so the change is bounded), folder trees become per-realm `Placement` records
  (one file, many placements), and "share a folder" = *add placements* (the file stays in personal), not
  move/copy. The shared realm stays **generic** — any service's Syncables (a future `ChatService` writes
  its own entities, no `Share` supertype, no runtime change); placement is per-service and only where a
  navigable structure is needed. SR-3c implementation re-plans onto that doc's phasing.

---

## 4. Phasing

SR-M.1 proved too large to land in one PR (it touches the encryption hot path), so it was sliced
into in-process-verifiable increments, each a no-op on the personal path:

| Slice | Scope | Gate | Status | Risk |
|---|---|---|---|---|
| **SR-M.0** | Extract `RealmContext` from `SyncManagerImpl` (personal realm = sole instance). Pure refactor. | — | **Landed** | Medium |
| **SR-M.1a** | Emit (`SyncObserver`) + ingest (`SyncProcessor`) crypto path takes keys from a `RealmContext` bundle (still the single personal one). | SR-M.0 | **Landed** | Medium |
| **SR-M.1b-core** | `RealmRegistry` + `RealmRuntime`; per-realm emit routing via realm-namespaced DataIds (`"$realmId::$key"`); per-realm outbound queue. | SR-M.1a | **Landed** | High |
| **SR-M.1b-drain** | Per-realm outbound *push*: each shared realm's queue drains to its own `SyncRelay` (its own ownerId stream), isolated from the personal drain. | SR-M.1b-core | **Landed** | High |
| **SR-M.1b-pull** | Per-realm inbound *pull*: each shared realm gets its own `SyncProcessor` (decrypts under its keys + advances its own HLC) + its own cursor; `SyncScheduler.pullSharedRealms` pulls each shared stream via the realm's relay. | SR-M.1b-drain | **Landed** | High |
| **SR-3b.1** | Shared-realm **bootstrap + host self-join + local registration** (`SharedRealmBootstrapper`): generate the realm's Ed25519 identity + random A12 (`Kr`); `POST /sync/realm`; wrap `Kr` to the host's own A13 + save a `SharedRealmMembership(OWNER)`; `register()` the realm (relay = null). First `register()` caller. | SR-M.1b-pull | **Landed** | Medium |
| **SR-3b.2** | **Auto-join observer** (`SharedRealmJoinObserver`: a `SharedRealmMembership` landing locally → unwrap `Kr` with this device's A13 → register a joiner `RealmContext`, `masterKeyPair = null`; skips already-registered realms so the owner registration isn't clobbered) + a per-realm **relay-factory seam** on `RealmRegistry` (`register` resolves a relay via the factory; `initSyncService` takes `sharedRealmRelayFactory`). In-process verified. | SR-3b.1 | **Landed** | High |
| **SR-3b.3** | The `CreateSharedRealm` **SDK op** (schema + `CreateSharedRealmRo` + `CreateSharedRealmImplementation` wrapping `SharedRealmBootstrapper`; threaded into `syncProxyImplementationFactory` via an optional `RealmRegistry` param). In-process verified. *Re-scoped to just the op* once cross-device proved blocked — see §3.1. | SR-3b.2 | **Landed** | Medium |
| **SR-3b.4a** | **A13 delivery to joiners** (option B, §3.1): pairing key-blob carries `identity\|volume\|A13` (`PairingKeyBlob`); `SyncManager.getRealmEncryptionKeyPair()`; observer + bootstrapper use it → paired joiners auto-join. Client-only (no relay change). | SR-3b.3 | **Landed** | High |
| **SR-3b.4b** | **Relay device admission**: `POST /sync/admit-device` (master-delegation-gated) + `SyncRelay.admitDevice` + owner-side `SharedRealmDeviceAdmitter` (signs with the realm's Ed25519 identity) → registers a member device's A4 for the shared `ownerId`. | SR-3b.4a | **Landed** | High |
| **SR-3b.4c** | **Integration (logic + wiring)**: `sharedRealmRelayFactory` helper + admit *trigger* in `SharedRealmBootstrapper` (enumerate `Relations.DEVICES`, admit each non-self) + system-test DI wiring (`RealmRegistryHolder` + factory). | SR-3b.4b | **Landed** | High |
| **SR-3b.4c-verify** | The two-device dev-relay **shared-stream** sync test (`SharedRealmStreamSyncSystemTest`): host `CreateSharedRealm` → admits joiner → writes a namespaced `Notification` → joiner pulls it off the shared stream. **Caught + fixed a real bug:** the bootstrapper minted the realm's Ed25519 master via `generateKeyPair()` (which is **X25519** in the real crypto) → relay rejected the bootstrap signature → fixed to `generateDeviceSigningKeyPair()`. **The first real end-to-end cross-device shared-realm sync.** | SR-3b.4c | **Landed** | Medium |
| **SR-3b.4-prod-DI** | Production Control `PlatformProxyModule` DI (`sharedRealmRelayFactory` + `RealmRegistry` threaded into the op factory), gated on the create-shared-folder UI (Android-unverifiable, no UI yet). | SR-3b.4c-verify | When UI lands | Low |
| **SR-3c (design)** | Shared-folder file model: keying (`$realmId::` prefix, opaque, repository-stripped), content-plane routing prereq, FileHistory-drop, pre-flight, migration-op shape. See §3.2. | SR-3b.4c-verify | **Landed (doc)** | — |
| **SR-3c-prereq** | Realm-route `emitFileContent` — `realmForDataId(targetEntityId)` selects the realm's keys + a **per-realm `ContentBlobStore`** (`sharedRealmContentBlobStoreFactory` on `RealmRegistry`, mirror `sharedRealmRelayFactory`) + the per-realm manifest queue. Fork-independent; unblocks shared-realm file bytes. | SR-3c (design) | **Landed** | High |
| **SR-3c file-model design** | Identity-vs-location resolved → **placement/hard-link** model in [`drive-shared-realm-file-model.md`](drive-shared-realm-file-model.md): `File` re-keyed to a stable `fileId`, per-realm `Placement` records, "share = add placements", generic realm (no `Share` supertype). | SR-3c-prereq | **Landed (doc)** | — |
| **SR-3c impl** | Re-planned onto the file-model doc's phasing: (1) `File` identity re-key, (2) `Placement` records, (3) `ShareFolderIntoRealm` (= placements, no move), (4) resume/GC. Supersedes the old path-prereq/struct/content split. | SR-3c file-model design | Next | High |

**De-risking proof (as landed):** each SR-M.1b slice ships with an in-process isolation test in
`:sync-service-proxy` — `MultiRealmPushDrainTest` (a shared realm's block drains only to its own
relay) and `MultiRealmPullIsolationTest` (a shared-key-encrypted block applies only when pulled
through the shared realm's processor, and is dropped when it reaches the personal processor) — plus
the full sync suite + `:system-test-utils` proving the personal path is byte-identical. The
two-JWT dev-relay `MultiRealmSystemTest` (two independent streams end-to-end against a live relay)
is folded into **SR-3b**, where a realm is first actually registered.

**Outstanding for SR-M.1b (deferred to where they have a consumer):** tombstone `emit()` and
`emitFileContent` are still personal-realm-only (the pull/drain slices route *data* blocks; shared
realms don't emit tombstones or file content until the migration, SR-3c, needs them). Per-realm
device credentials (the co-membership-residual mitigation, §5) remain deferred — v1 reuses the
host's one device A4 key across both streams.

---

## 5. Threat model + invariants (House Rule #2 — amendments co-land with each code slice)

- **House Rule #1 preserved** — the re-encrypted subtree is ciphertext under `Kr`, never seen by the
  relay; the shared realm is an ordinary realm at the relay (no new relay business logic).
- **§2.3 invariants confirmed:**
  - *Single rotation authority* (Q22) — the OWNER `SharedRealmMembership` row is the sole
    membership/rotation authority.
  - *Fresh content key per write* (Q26) — satisfied by `emitFileContent`'s per-chunk AEAD; state it
    explicitly at the content-wrap site.
  - *Member writes signed under the member's own A1/A4, not `Kr`* (§2.3) — the shared `RealmContext`
    reuses the host's device A4 signing key; the JWT `iss` is the shared `ownerId` but the block
    signature is the member's device key ⇒ attribution among members, confidentiality vs the relay.
- **New accepted residual (must be documented when the code lands):** the host's one device key now
  authenticates to *two* ownerId streams (personal + shared) ⇒ the relay can correlate them into a
  co-membership graph **without decrypting** (Q25 / Q30, threat-model attack-surface #10). The
  deferred mitigation is **per-realm device credentials**; v1 reuses one device key.
- **T-K-02 epoch:** each realm has its own realm epoch — confirm the epoch provider is
  per-`RealmContext`, not global, or the shared realm's stale-device check breaks.

No new threat row is needed for the crypto itself (same curves / AEAD / KDF as SR-1, already covered
by TA3 / T-RC-02 / T-K-04); the runtime amendment is the co-membership residual above.

---

## 6. Open questions for review

1. **`RealmContext` seam shape** — value object returned by a realm-aware `SyncManager` vs a
   first-class `RealmRegistry` owning N `RealmRuntime`s. (This doc recommends the registry +
   instance-per-realm; SR-M.0 lands the `RealmContext` value first so the seam is reviewable in
   isolation.)
2. **Realm-namespaced DataIds** — is `"$sharedRealmId::$key"` acceptable as the keying scheme, or
   should realm be a structured field on `DataId` rather than a string prefix? The prefix avoids a
   wire/format change; a structured field is cleaner but touches `DataId` serialization.
3. **Migration of the personal-realm-side relations** — the subtree's `FileModification` history,
   `File.FileHistory`, and any reference properties: re-created in the shared realm, dropped, or
   left behind? v1 leans "drop history, keep current state" (cheapest, and history attribution
   crosses the realm boundary). Needs a call.
4. **Storage attribution** (Q24, decided owner-pays via a billing-entitlement reference) — the
   shared realm carries a billing-entitlement pointer; wiring it is out of SR-3 scope but the
   bootstrap should leave a place for it.

---

## 7. References

- `docs/proposals/tenshu-rei.md` §2.3 (shared realms as their own realms) + §5.C (Q22–Q32 decided).
- `docs/proposals/drive-shared-realms.md` (the earlier Mode-B sketch).
- `docs/sync/threat-model.md` §8.9 (A12/A13 + T-SR-*), attack-surface #10.
- `docs/adr/0006-sync-tombstones.md` (tombstone discipline the migration reuses).
- [ADR 0017](../adr/0017-file-content-two-plane-channel.md) (content channel — re-encryption under `Kr`).
- `docs/proposals/core-background-processing.md` (Layer-2 `BackgroundOperation` / `ScheduleService`
  for resumability).
- SR-1 + SR-2: `SyncCryptoManager.deriveRealmEncryptionKeyPair` / `wrapKeyForIdentity` /
  `unwrapKeyForIdentity`; `SharedRealmMembership` + `SharedRealmMembershipKeys`.
