# Relay metadata minimisation: what the relay learns from envelopes, and how to shrink it

**Status:** v0.6 (2026-10-28). ⭐ **Every D13 fix-before-release item has shipped:** F3, F4
(Caddy filter verified live with `caddy:2.11`), F7, F12, Fx and the `DEV_MODE` default; tips and
cursors are pruned on revoke; F6, F9, F10 and F13 are accepted explicitly in the threat model.
**F8** is closed too (moved up — the pairing QR makes ownerId public). The recovery challenge is
rate-limited per IP. Adjacent: the apex has a CSP, and the privacy notice matches the code,
reviewed by the maintainer as counsel.

**Before release, outside the code:** deploy relay, apex, clients and the hub together (F3, F7
and F8 changed the wire in place under D13, with no `RELAY_PROTOCOL_VERSION` bump); `caddy
reload` on the relay box; decide `MIRROR_PRUNE`; fix the erasure tooling (`tech-debt.md`: restore
does not re-apply erasures, no operator erase path). §5's status table is the as-of-master truth.

v0.5 (2026-10-28, `fdbc6733a`): Fx, F12 and `DEV_MODE` closed; F3, F4 and F7 were then still
open.
v0.4 (2026-10-24), re-checked against master `c9bfcc717`: **Phases 1, 3 and 5 have
shipped**, all three inside **CR-2 B3–B6** (relay protocol 4, merged 2026-10-05), as D12
asked; their as-built record is [`content-plane-cr2-b3-wire-spec.md`](content-plane-cr2-b3-wire-spec.md)
§11. **Phase 4 (a) is half done** by the server-logging change (merged 2026-10-23). Phases 2, 4
(b)–(e), 6 and 7 are unscheduled, but **D13 (§8) records what goes in before the first public
release**. §5's status table is the as-of-master truth per finding.

- **F1** closed (§11.3): `fetch-entity-blocks` takes the EI-1 wire id.
- **F2** closed (§11.2): EI-4's narrowing, mapped at drain. No history to scrub, because it landed
  before the CR-2 reset.
- **F5 / N1** closed (§11.1): property ids are keyed per-holder tags.
- **F4** narrowed: no query string and no secret reaches any log, and Caddy access logs are filtered
  and kept ≤ 7 days. ⚠ Path-carried capabilities are still logged (§5).
- The A6, T-R-08 and T-R-09 amendments of §9 were made by CR-2 B3–B6. T-K-05 / T-T-02 / T-T-03 were
  amended 2026-10-26 and Fx itself closed (v0.5).

⚠ **§3, §4 and §4b are the audit as read on 2026-10-03** (master then, and the CR-2 B3–B6 branch). They
are kept as the evidence the findings rest on; their `file:line` citations predate CR-2 B3–B6 and
have drifted. Where a later section cites master, it says so.

Opened from P0f-b ([`media-processing-pipeline-sweep.md`](media-processing-pipeline-sweep.md) §6.4,
decision P0fb-D4). Every row delete sends a `DeleteBlock` whose `targetEntityType` is the
deleted row's schema id, in the clear, for every type. The maintainer asked for the whole family:
*what does the relay learn from the metadata of what clients send it, and what can we close?*

Marks: **[C]** = read in code at the cited `file:line`. **[I]** = inferred, with the reason given.
Unless prefixed, relay paths are under `sync-relay-server/src/main/kotlin/org/tenshu/sync/relay/`,
and `DB:N` means `storage/RelayDatabase.kt` line N.

Related, and load-bearing:
- [`threat-model.md`](../sync/threat-model.md): A6, T-T-07, T-R-02, T-R-08, T-R-09, T-S-02, T-K-05
- [`sync-relay-api.md`](sync-relay-api.md): the canonical wire surface
- [`sync-relay-persistence.md`](sync-relay-persistence.md)
- [`sync-entity-identity.md`](sync-entity-identity.md): EI-1 shipped, EI-4 is design
- [`content-plane-refcount.md`](content-plane-refcount.md) and
  [`content-plane-cr2-quorumless.md`](content-plane-cr2-quorumless.md): the standard of comparison
- [`sync-push-wake.md`](sync-push-wake.md): S4, planned
- [`admission-revocation-names-the-capability.md`](admission-revocation-names-the-capability.md)
- [ADR 0006](../adr/0006-sync-tombstones.md), [ADR 0017](../adr/0017-file-content-two-plane-channel.md),
  [ADR 0018](../adr/0018-file-sharing-mode-a.md)

---

## 0. Headline

⭐ **v0.4:** items 1, 2 and the F5 half of 5 below are **closed on master** (Status). Items 3 and 4
stand. Item 6 is history: CR-2 B3–B6 merged with all three D12 fixes in it.

1. ✅ *(closed by CR-2 B3–B6, B3 wire spec §11.3)* ⚠⚠ **The worst leak found is not `targetEntityType`.** `POST /sync/fetch-entity-blocks` sends the
   relay the **plaintext `DataId`**: type plus key, which for a `File` is its path, and for a
   shared-realm entity is `"$realmId::"` plus the path. The client serialises it at
   `sync-service-proxy/.../RestSyncRelay.kt:305`. The relay hashes it with the **local** FNV hash
   (`routing/SyncRouting.kt:469-473`), but blocks are stored under the EI-1 **keyed** wire id
   (`SyncObserver.kt:2793`; `WireEntityId.kt:75-78`). [I] So since EI-1 the route most likely
   matches nothing, **and** it has been disclosing the path of every on-demand open. That is F1.
2. ✅ *(closed by CR-2 B3–B6, §11.2)* `DeleteBlock.targetEntityType` names the deleted row's type for every type, and is kept
   indefinitely in a clear column and in the retained envelope (DB:150-158;
   `storage/SqliteSyncMailbox.kt:115-136`). The relay reads one bit of it: *is this a device?*
   EI-4's value-narrowing closes it with **no wire-shape change, no `RELAY_PROTOCOL_VERSION` bump,
   no HR 9 bump and no lockstep**. That is F2 and Phase 1.
3. ⚠⚠ **The maintainer's premise for the Device question does not hold.** No separate endpoint
   unpairs a device. `POST /sync/admission/revoke` only strips minting power. The applied `Device`
   tombstone is the **only** caller of `revokeIfNotStale` and `recordRemoval`
   (`auth/DeviceRegistry.kt:72-103`; `SyncRouting.kt:368-383`). It is also the only source of the
   realm epoch (`SqliteSyncMailbox.kt:313-321`). Making it opaque today would leave a removed
   device with a working JWT. EI-4 §5b already lists the four prerequisites and the fleet
   condition; Phase 2 is that list. §6 has the full trace.
4. ⚠⚠ **Found in passing, and not a metadata issue: tombstones are unauthenticated.** `DeleteBlock`
   has no signature or MAC field (`sync-service/.../DeleteBlock.kt:67-78`). `processDeleteBlock`
   applies one after HLC checks alone (`SyncProcessor.kt:213-260`). Meanwhile T-K-05 says
   *"Tombstones are signed"* and T-T-02 says *"Every block is signed"*. [I] A relay that knows any
   wire id can forge a delete for it, and it knows them all. This changes D4.
5. **Siblings, ranked in §5:**
   - Mode-A shares store `fileName`, `mimeType` and `totalSize` in clear, kept forever when the
     share has no expiry.
   - ✅ *(closed by CR-2 B3–B6, §11.1)* `content_edge.property_id` is a plaintext schema id joined to
     the ordered log through the holder hash, and CR-2 B3 is about to make it permanent.
   - ⚠ *(narrowed: queries and secrets no longer logged; path ids still are)* Every
     capability in a path or query string (pairing secret, invite, grant and share ids, owner ids)
     is written to the INFO call log.
   - A shared-realm **owner** reuses its personal `deviceId` and A4 key, so the relay can join the
     personal and shared realms.
   - Pairing requests carry the device name in clear, contradicting A6's claim that names are not
     relay-visible.
   - Payload and chunk sizes are not padded.
   - `GET /sync/realm-epoch`, `GET /sync/recovery-blob` and `GET /sync/recovery-challenge` are
     unauthenticated by `ownerId`, so they act as existence and count oracles.
6. **On CR-2 B3–B6 (v0.2, §4b; merged 2026-10-05 with all three D12 fixes):**
   - Every finding above **still holds**.
   - F5 widens: B3 is built there, and `propertyId` rides every content-bearing push in plaintext,
     "none" slots included. The B3c threat-model amendment does not say so.
   - New there:
     - the cascade subtree persists as an equal-stamp grouping in `holder_watermark`;
     - per-device reaps;
     - edge stamps that carry the authoring `deviceId`.
   - ⚠⚠ Its 426 gate is **per request** on push and tombstone, which changes Phase 1's compatibility
     analysis and makes Phase 2's fleet condition enforceable (§7).
   - ⭐ Three fixes are one-liners **inside** CR-2 B3–B6 and dearer after it (D12): F5's keyed tag, Phase 1,
     and F1 in place.

The content plane (C′, [`content-plane-refcount.md`](content-plane-refcount.md) §6e) is this doc's
yardstick: *a random handle discloses no more than itself, and the relay counts distinct values*
(refcount §6e, around line 1785). Against it, the ordered plane's tombstone and fetch envelopes fall
short. ⚠ But the content plane itself is moving the other way: CR-2 B6 retires the handles, and B3
adds `(holder, property, HLC)` per content-bearing write
([`content-plane-cr2-quorumless.md`](content-plane-cr2-quorumless.md) §10, the table at line 709).

---

## 1. Scope, adversaries and what "accepted" already means

**Adversaries** (threat model §6):

- **AD5 honest-but-curious.** A relay operator who reads the DB, the logs and the backups but sends
  correct responses.
- **AD5 active.** A compromised relay that also forges, withholds and serves differently per user.
- **Secondary observers** of the same data: the S3 provider, which sees object keys
  `<prefix><ownerId>/blob-<hex>` with sizes and times (`storage/S3BlobStore.kt:59-65`); the log
  sink (docker and Caddy); and, once S4 ships, Apple.

**What the threat model already accepts.** Quoted so that "accepted" is not stretched further than
it goes:

- **A6** (`threat-model.md:909-916`): *"Device counts, HLCs, block sizes, block timing, owner IDs,
  sync frequency, tombstone existence (**keyed** entity id + HLC …), per-volume retention windows.
  Not end-to-end encrypted. … device *names* and authorization status live inside encrypted
  `Device` entity blocks and are no longer relay-visible."*
- **T-T-07** (`:2291-2299`): *"What leaks: who syncs with whom, when, how often, block sizes, device
  counts. … Block-size padding to fixed buckets can reduce content-size inference; timing is harder
  … Residual risk: **Accepted, documented.** Full metadata privacy is not a v1 goal."*
- **T-R-02** (`:2349-2353`): device records *"deviceId, device name, public key … leakage by
  design"*. ⚠ This contradicts A6 on names. F7 shows the code sides with T-R-02, but only on the
  pairing path.
- **T-R-09(a)** (`:2770-2794`): the per-open fetch trace over opaque refs is *"accepted as a
  documented residual"*, at Low–Medium.
- **Mode-A reaffirmation** (`:4385-4387`): the relay holds *"file metadata the recipient is about to
  see anyway (`fileName`, `mimeType`, `totalSize`)"*. ⚠ This rationale conflates the recipient with
  the relay (F3).
- **§10** (out of scope): *"Traffic analysis revealing who-syncs-with-whom and when. Mitigations are
  expensive and not planned"*.

⚠ **Nobody has accepted** the following: the tombstone's type (A6 lists *tombstone existence*, not
its kind); the content edge's plaintext `propertyId`; plaintext `DataId`s on any route; call-log
retention of capabilities; or the link between a shared-realm owner and its personal realm. Each is
marked *undecided* in §5.

---

## 2. Method

- Every route was enumerated from `routing/*.kt`, and every table from `RelayDatabase.kt`.
- For each clear field I looked for the relay code that **reads** it. A field the relay stores but
  never branches on is a candidate for an opaque tag; a field it never reads at all is a candidate
  for dropping.
- Client emitters were traced from `SyncObserver`, `RestSyncRelay` and the `*Implementation`s.
- Three parallel read-only sweeps (the mailbox families, the content and commerce planes, and the
  planned docs) were spot-checked against the code before anything was cited here. Where only a
  sweep read a line, the claim is still marked [C], because I re-read the load-bearing ones:
  `share_record`, `content_edge`, `SharedRealmBootstrapper.kt:93`, the `pairingSecret` query
  parameter, the GET touch and closure.

---

## 3. Inventory: built

### 3.1 On every request

| clear | relay use | reveals |
|---|---|---|
| JWT `iss`=ownerId, `sub`=deviceId (`auth/JwtAuth.kt:114-117`) | auth; keys every owner-scoped table | per-device activity and timing on **every** authenticated call; ⚠ `nonce` is parsed and never checked |
| client IP (`Application.kt:388-390`, `XForwardedHeaders`) | per-IP limiters, in memory (`auth/AuthFailureLimiter.kt`) | links anonymous calls (invite response, grant pickup, share open) to authenticated ones from the same IP [I] |
| request URI → `CallLogging` at INFO (`Application.kt:372-374`) | none (ops) | ⚠ every path or query capability is written to the log (F4) |
| ownerId = `hex(sha256(masterPub))` (core-common `OwnerId.kt:19`) | key | stable for the realm's life; deviceId is stable across sessions (`SyncManagerImpl.kt:91-95`) and, on iOS, across reinstall |

### 3.2 Ordered plane: blocks, the log and tips

- **`SyncBlock` envelope** (`sync-service/.../SyncBlock.kt:72-81`): `blockId`, `ownerId`, `hlc`,
  `encryptedPayload`, `targetEntityIdHash?`. `propertyId` is inside the ciphertext (T-R-08).
  ⚠ The `hlc` composite carries `physicalTime`, `logicalCounter` **and `deviceId`**
  (`core/.../HybridLogicalClock.kt:46-52`). So every stored block names its authoring device at
  rest, which the relay otherwise only sees transiently through the JWT `sub` (F9).
  [`sync-relay-api.md`](sync-relay-api.md) L124 lists `deviceId` and `signature` as envelope
  fields; in code, neither is a separate field.
- **`sync_block`** (DB:137-148): `seq`, `owner_id`, `block_id`, `target_entity_id_hash`,
  `hlc_physical`, `envelope`, `body_dropped`.
  - GC drops the body and **keeps the envelope** (`EnvelopeOnlySyncBlock.kt:11-16`).
  - Result: a per-entity write timeline for the realm's whole life (A6: "HLCs, block timing").
- **Payload size is unpadded.** I found no padding in the block encrypt path (grep over
  sync-service-*). [I] Ciphertext length tracks the payload, so it separates a thumbnail-bearing
  row from a preference.
- **`POST /sync/push`** (`SyncRouting.kt:203-226`): reads `ownerId` and checks it against the JWT;
  nothing else.
- **`POST /sync/log`, `/changes`** (`:228-330`): the signed HLC tip is stored verbatim in
  `hlc_tip(owner_id, device_id, tip)` (DB:119-125), which is each device's last wall-clock
  observation. `device_cursor` (DB:455-461) holds per-device sync progress. Both are served to
  peers.
  - [C] Nothing deletes either on revoke (no `DELETE FROM hlc_tip|device_cursor` in the tree). A
    revoked device's tip and cursor stay and keep being served.
  - Uses: the GC quorum (T-T-08), divergence detection and the barrier.
- **`POST /sync/fetch-entity-blocks`** (`:462-483`): **body = a plaintext `DataId`** (F1).
  - Client callers: `OnDemandResolutionImpl.kt:161`, the legacy and shared-realm manifest path
    (`SyncObserver.kt:2783-2793`), and `EntitlementRecovery.kt:85`.

### 3.3 Tombstones

- **`DeleteBlock`** (`DeleteBlock.kt:67-78`): `blockId`, `ownerId`, `hlc`, `targetEntityIdHash`,
  **`targetEntityType`**, `revokedDeviceId?`.
  - There is **no encrypted payload and no signature** (§0 item 4).
  - The KDoc says *"A signed delete tombstone"* (`:15`), but the schema has no field that could
    carry a signature.
- **`delete_block`** (DB:150-158): the same fields, with `target_entity_type TEXT NOT NULL` and the
  full serialised envelope. Kept indefinitely (ADR 0006; `SyncRouting.kt:335-336`).
- **Relay reads of `targetEntityType`**, all one comparison against `Device.ID`:
  - `SyncRouting.kt:368` → `revokeIfNotStale`;
  - `SqliteSyncMailbox.kt:313-321` (`COUNT(*) … target_entity_type = Device.ID`);
  - the in-memory twin at `SyncMailbox.kt:360-366`.
- **Client reads:**
  - `TombstoneRegistry.kt:331-336` keeps per-type counts;
  - the only production reader is `tombstoneCountForType(Device.ID)` at
    `SyncProxyInitialization.kt:478`, the local realm epoch;
  - `processDeleteBlock` resolves the target through `resolveDataId(idHash)`
    (`SyncProcessor.kt:217-218`) and **never reads the type**. ⚠ The brief's "the receiver picks a
    merger with it" is not what the code does: the resolved `DataId` carries the type.
- **Emitters with concrete types** include `FileModification` trims (`threat-model.md:3267-3273`),
  every row delete, and `DataIdMigrator` re-keys (`DataIdMigrator.kt:342-344`, `marker.entityType`
  at `:249`).
- **`GET /sync/realm-epoch?ownerId=`** (`SyncRouting.kt:423-460`): **unauthenticated**. It returns
  the count of Device tombstones, and 404 for an unknown realm.
  - Anyone holding an ownerId learns whether the realm exists and how many removals it has had.
  - [`sync-relay-api.md`](sync-relay-api.md) L592 already calls it *"the one place where the relay
    is no longer fully zero-trust on realm metadata"*.

### 3.4 Device registration, realm and recovery

- **`device(owner_id, device_id, public_key, admitted_hlc_physical, admitted_hlc_counter,
  admitted_hlc_device)`** (DB:92-98, 358-360). `admitted_hlc_device` records **which device
  admitted which**, and when.
- **`removed_device`** (DB:399-405): a permanent ledger, never GC'd by design
  (`DeviceRegistry.kt:99-101`).
- **`admission_revocation`** (DB:311-319) and **`member_revocation`** (DB:241-249) are append-only
  and permanent.
- **`POST /sync/realm`** (`routing/RealmRouting.kt:267-370`): carries `relayProtocolVersion` (a
  client-version fingerprint, `:85-129`) and the realm's `created_at`.
  - ⚠ Shared realms are created through the same route by an owner that **reuses its personal A4
    key and deviceId**: `SharedRealmBootstrapper.kt:93-94`, *"A4 — reused across realms"*.
  - So `(personalOwnerId, D, K)` and `(sharedRealmId, D, K)` are joinable rows (F6).
  - Cross-user members do not have this problem: they use a per-realm key, and their deviceId is
    `sha256(perRealmPub)` (`SharedRealmGrantManager.kt:214-218`).
- **Recovery** (`routing/RecoveryRouting.kt`):
  - `GET /sync/recovery-challenge?ownerId=` is an existence oracle (`:46-63`).
  - `GET /sync/recovery-blob?ownerId=` is **unauthenticated** and returns the A9 ciphertext plus
    `manifestVersion`, which counts backup updates (`:220-242`).
  - `recovery_blob` is kept forever (DB:106-111).

### 3.5 Pairing and EPC

- **`POST /sync/pairing/request`** (`SyncRouting.kt:486-541`, unauthenticated):
  - ⚠ carries `deviceName` in clear (`:499`) and the **raw** `pairingSecret`;
  - held in memory, capped at 32 per owner, with no TTL (`storage/SqliteSyncMailbox.kt:34, 361-378`).
- **`GET /sync/pairing/requests`** (JWT) serves those requests, raw secret and name included, to the
  approving device's prompt. ⚠ The *approve* (join-flow fix, merged 2026-10-03) does not re-fetch
  this list. It admits only the tuple pinned when the SAS was shown. That is a client-side
  integrity fix with no relay change, and it changes nothing in this inventory, because the relay
  sees the same requests from the prompt's fetch. No row here described the approve's re-fetch.
- **`GET /sync/pairing/response?pairingSecret=`** (`:720-733`): the raw secret in a **query
  string**, so it is logged (F4).
- **Offer flow** (`:735-977`): stores `secret_hash`, `owner_id`, the clear `status`
  (approved, denied or scanned), `public_key_a` and a ciphertext payload, for 7 min plus a 24 h
  consumed marker (DB:333-341, 424-431).
  - Reveals the approve/deny decision and the scan→decision latency.
  - The offer and hub flows already send a **hash** of the secret; the join flow does not.
- **Hub offer** (`:979-1049`, in memory only): a hash of a **long-lived printed** secret plus a
  clear `deviceName`. The relay can track one hub across every re-arm.
- **EPC rendezvous** (`routing/EpcRendezvousRouting.kt:49-159`): an HKDF session id, frames of at
  most 64 KiB of ciphertext, and a 15 min TTL (DB:283-298). Reveals the app↔proxy session's
  lifetime and its frame count and timing per direction.

### 3.6 Social plane: invites, grants, membership

- **Invites** (`routing/InviteRouting.kt:46-222`; DB:209-220): `invite_id` (client-random),
  `owner_id`, `created_at`, `expires_at`, `state`, and sealed response and ack blobs, plus
  `responded_at`. The relay never sees `K_link` (A24/A25 hold).
  - Reveals the inviter, the count of open invites and the handshake latency.
  - ⚠ The anonymous invitee's **IP** on the response, ack-poll and done calls, correlated with
    JWT traffic from the same IP, gives an inviter↔invitee edge [I].
- **Grant mailbox** (`routing/MembershipGrantRouting.kt:59-120`; DB:230-238): the address is an
  HKDF of the handshake secret. Stored with the grantor's `owner_id`, `created_at` and a 30 d TTL.
  Pickup is anonymous, and its IP is visible.
- **`POST /sync/realm-member/admit`** (`:127-196`): `realmId`, the member's `memberAuthorityPublicKey`
  (stable per member within the realm), `deviceId`, `perRealmDevicePublicKey`, and the clear
  timestamps `issuedAt` and `boundAt`. Stored in `member_admission` (DB:253-259).
  - The relay can count members per realm and devices per member.
  - It cannot link a member to their personal realm by key; IP and timing can [I].

### 3.7 Content plane

- **`PUT /sync/content/{ref}`** (`routing/ContentRouting.kt:91-180`):
  - The ref is `blob-sha256(ciphertext)`, never of the plaintext, with a fresh CEK and nonce per
    emit (`SyncObserver.kt:2418-2451, 2527-2530`). There is no cross-user dedup.
  - Size is stored as `content_blob.byte_len` (DB:169-176, v9 `:270`).
  - Chunks are unpadded and cut on a 2 MiB stride (`core-common/.../WholeFileHash.kt:37`;
    ciphertext = plaintext + 40 bytes). So the last chunk gives the file size mod 2 MiB, and the
    chunk count gives the rest (F10).
- **`GET /sync/content/{ref}`**: ⚠ writes `last_referenced_at = now` on **read**
  (`storage/SqliteContentBlobStore.kt:175-182`), so the relay stores a per-blob **open-recency**
  trace.
  - T-R-09(a) accepted the trace *in flight*. Persisting it is a step further, and so is the call
    log keeping the full history.
  - Readers: the disabled GC sweep (`RelayConfig.kt:348`, `Long.MAX_VALUE`), the N0 age window
    (`mailbox/ContentEnumerationAgeWindow.kt:22-29`) and inventory (`mailbox/ContentInventory.kt:76`).
- **`POST /sync/content/declare`** (`ContentRouting.kt:419-605`):
  - `edges[] = {holderHash, propertyId, contentId, refs[]}` → `content_edge(owner_id, holder_hash,
    **property_id**, content_id, declared_at)` (DB:488-500).
  - `contents[] = {contentId, refs[], referenceId, hlc}` → `content_ref` and `content_reference`
    (DB:532-588).
  - `holderHash` **is** the EI-1 wire id, so a content object joins to its entity's ordered-log
    rows, including edit times and tombstone time.
  - `propertyId` is a **plaintext schema id** (`ContentRouting.kt:1138-1139`), e.g. file data
    versus a thumbnail rung or a workout stream.
  - [C] The relay never interprets `property_id`. It is only a key component (grep:
    `mailbox/ContentEdgeStore.kt:16-28`, `storage/SqliteContentEdgeStore.kt:16-32`).
- **Manifest ref** `blob-sha256("manifest:"+contentId)` (`ContentManifestRef.kt:16-17`) can be
  derived by the relay from any contentId. It is used by `ContentReclaimJob.kt:320-330`. Accepted;
  noted.
- **`/content/delete`, `/undeclare`, `/reclaim`** reveal which refs die together and a delete
  cascade's descendant holder set (approximately folder-subtree membership,
  `content-plane-cr2-quorumless.md` around line 665).

### 3.8 Shares (Mode A)

- **`POST /share`** (`routing/ShareRouting.kt:53-90, 221-231`) → `share_record(share_id, owner_id,
  **file_name**, **mime_type**, **total_size**, chunk_count, chunk_hashes, expires_at, max_opens,
  created_at, opens_so_far, revoked_at)` (DB:185-198).
- **Retention:** 30 days after revoke or expiry. ⚠ A share with **no expiry and no revoke keeps its
  filename forever** (`storage/SqliteShareStore.kt:146-155`).
- **`GET /share/{id}`** is anonymous and increments `opens_so_far`. Together with the per-open IP,
  that gives a sharer→recipient-IP graph (T-S-02 accepts the IP; the filename is F3).

### 3.9 Entitlement, commerce, closure, ops

- **`X-Entitlement`** claims are `sub`, `tier`, `quota`, `iat`, `exp` and `kid`
  (`core-common/.../EntitlementToken.kt:28-39`). The relay checks `sub` against the JWT owner and
  enforces the quota (`routing/EntitlementEnforcement.kt:101-171`).
  - Blinded tokens (no `sub`) are supported, but commerce mints `sub = ownerId`
    (`commerce-service/.../TokenRouting.kt:48`).
  - Nothing is persisted. ownerId is logged on 402/403.
  - The relay never receives a commerce account id (D5 of the billing plane holds).
- **`POST /sync/account/closure`** (`routing/AccountClosureRouting.kt:42-73`): the token carries an
  unblindable `ownerId` by design (`EntitlementToken.kt:180-206`).
  - ⚠ `reclaimOwner` deletes only `content_blob` rows and bytes. `content_ref`, `content_edge`,
    `content_reference` and `content_free_queue` survive, and `mailbox/ContentReferenceRegistry.kt:76-85`
    says this is owed. The `sync_block`, `delete_block` and `device` planes are untouched (F12).
- **`/health`** returns only fixed fields (T-T-09). **`/ops/content-reclaim/*`** is loopback plus a
  token (`routing/OperatorRouting.kt:63-97`).

---

## 4. Inventory: planned (designed, not built)

| design | adds to relay-visible metadata | note |
|---|---|---|
| **S4 APNs wakes**, [`sync-push-wake.md`](sync-push-wake.md) (v0.2, design only) | `wake_token(owner_id, device_id, provider, environment, topic, token, …, last_sent_at)` (§5.4); the APNs `.p8` key, the relay's first signing secret | well-designed: fixed payload, one token per phone held by Control, no wakes for grants or shared realms (PW-D1/D4/D5), and no new timing because the relay already sees pushes. ⚠ Relay plus Apple can name the person behind an ownerId (§3.3). ⚠ It depends on the unbuilt JWT nonce store (L254-257, matching §3.1 here). Its own Appendix A carries A41/A42; nothing added here. |
| **CR-2 B3**, push carries edges ([`content-plane-cr2-quorumless.md`](content-plane-cr2-quorumless.md) §10.5, line 709) | per content-bearing write: `(holder, property, contentId, HLC)` bound to one block; complete cascade holder lists on tombstones; content-bearing deltas leave the batch, so *which* writes carry content becomes visible | ⚠ the doc already calls this "real privacy cost" (line 644). ⇒ F5 must land **in** B3's wire spec, or B3 cements a plaintext `propertyId` on the push path |
| **CR-2 B5**, relay sweep | `unreferenced_since`, `put_seq` / `freed_seq` per content | more per-content timing state |
| **CR-2 B6** | **removes** `content_reference` and the C′ handles | the yardstick mechanism is retired; holder-keyed edges become the authority |
| **§11 content-head addressing** (optional idea) | blob keys `<contentId>/<gen>/<n>` and `<root>/<derived>/…` | ⚠ puts chunk index, generation and thumbnail→file nesting into **object keys**, visible to the S3 provider with no describe. Recommend: only with opaque per-object keys (D7 note) |
| **T-R-08 carve-out** (`threat-model.md:2428-2434`) | an eager-vs-OnDemand bit per block, once host-side OnDemand push lands | one strategy bit; fine if it stays one bit |
| [`sync-backend-relay-plan.md`](sync-backend-relay-plan.md) §6.1, Postgres (stale, "not started") | a `SyncBlock` metadata column *"target entity / **property**"* (L341) | ⚠⚠ this would reintroduce T-R-08. **Must not be built as written**; flag the line when that doc is next touched |
| `GET /sync/block/{id}`, `LazyBlock`, SSE / long-poll ([`sync-relay-api.md`](sync-relay-api.md) L36-42, L1101-1118) | none beyond today, except that long-poll adds connection-held timing | never built |

---

## 4b. What CR-2 B3–B6 changes (then open; branch `claude/optimistic-feynman-388v4k`, read 2026-10-03)

As read, the change was CR-2 B3–B5: relay-held content references, freed-content refusal and REVIVE, and the G2
sweep (which ships off). It is not merged. Paths below are read with `git show
"origin/claude/optimistic-feynman-388v4k:<path>"` and marked **[C@CR-2]**.

**Protocol state.** `RELAY_PROTOCOL_VERSION = 4` and `RELAY_PROTOCOL_MIN_SUPPORTED_VERSION = 4`
(`RelayProtocol.kt`, history item 4). This is a hard cutover with a relay reset (maintainer,
2026-10-02). `POST /sync/push` now takes an `EdgedPush` and `POST /sync/push-tombstone` a
`CascadedTombstonePush` (`sync-service/.../EdgedPush.kt`). A bare `SyncBlock` or `DeleteBlock` gets
a 426 (`SyncRouting.kt:230-240, 448-461` [C@CR-2]; ⚠ a 400 since the 2026-10 reset). ⚠⚠ Every new body carries
`relayProtocolVersion`, so **the 426 gate is per request on push, push-tombstone and reap**, not only
at genesis. That falsifies a premise of EI-4 §5b and of this doc's v0.1 Phase 1 text (§7 corrects
it).

**Unchanged by CR-2 B3–B6:**
- `DeleteBlock` and `SyncBlock` keep the same shape, with no signature (Fx holds).
- The Device branch on `targetEntityType` (`SyncRouting.kt:505` [C@CR-2]).
- `fetch-entity-blocks` still takes a plaintext `DataId` and hashes it locally
  (`SyncRouting.kt:599-611` [C@CR-2]; client `RestSyncRelay.kt:361` [C@CR-2]). Its comment still
  cites *"threat-model §Property-ID exposure to relay"* as though the route were safe.
- `CallLogging` at INFO (`Application.kt:391-393` [C@CR-2]).
- No change in `ShareRouting`, `RecoveryRouting`, `SqliteContentBlobStore` (GET touch, closure
  reclaim), `SqliteDeviceRegistry` or `SharedRealmBootstrapper` (diff empty).

**The branch's own known-issues list** is `docs/protocol/relay-api.openapi.yaml`, *"Known issues
found during extraction (2026-10-02)"*. Overlaps with this audit, cross-referenced rather than
re-found:
- **#2**: the `fetch-entity-blocks` hash mismatch, *"probably never matches"*. This is F1's
  functional half. ⚠ It does **not** list the plaintext-path exposure, which is F1's metadata half.
- **#4**: `GET /sync/pairing/requests` returns the **raw** pairing secret in the slot named
  `pairingSecretHash`, and join-flow requests have no TTL. This is F7.
- **#1**: `DEV_MODE` defaults to true. Not a metadata leak, but in dev mode `X-Dev-Owner` bypasses
  JWT auth, so a mis-deployed relay discloses everything.
- **#6 / #7**: the auth-failure budget and unbounded responses. Not metadata. #7 does compound F1,
  because a fetch that did match would return every block of the entity unpaged.

**New relay-visible metadata on CR-2 B3–B6** (its B3c, B4r and B5 threat-model amendments record N1–N4
in part):

| # | what | where [C@CR-2] | recorded by CR-2 B3–B6's amendment? |
|---|---|---|---|
| **N1** | **`propertyId` in plaintext on every content-bearing push**, as the row's *complete* edge set, `PushedEdge.propertyId` (`EdgedPush.kt`). Stored as `content_edge(owner_id, holder_hash, property_id, content_id NULL=none, stamp)` (`RelayDatabase.kt:513-519`, migration 22). ⚠ "None" registers mean the relay learns **which content slots a row has**, even empty ones (e.g. how many thumbnail rungs), on every write | `SyncRouting.kt:262-279` | ⚠ **No.** The B3c amendment lists "a content-bearing property bound to a block and its write clock" but never says the property id is plaintext. ⚠⚠ That is the schema-vocabulary profiling T-R-08 closed for `SyncBlock` (*"this user has files-with-bytes, previews…"*, `threat-model.md:2397-2400`), reopened on the push path. On master the same plaintext arrives only on `/content/declare` |
| **N2** | **The cascade list** on push-tombstone: every descendant's wire name, bound to the tombstone. ⚠ **Persisted as a grouping**: `removeHolders` writes the **same** `holder_watermark.stamp` (= the tombstone's HLC) for the target and every cascade holder (`SqliteContentEdgeStore.kt:55-90`), so subtree membership and size stay recoverable from the table after the request | `SyncRouting.kt:471-492` | partly. It states the request-time linkage ("these holders were one subtree, deleted together"), not that it persists as an equal-stamp group |
| **N3** | **Per-device reaps** (`POST /sync/content/reap`, ≤1000 per request): the same subtree once per reaping device, at the folder tombstone's HLC. The relay can count how many devices reaped a subtree, and when | `ContentRouting.kt:752-790` | yes (B3c item 3) |
| **N4** | **Stamps carry the authoring `deviceId`**: `HlcStamp.encode() = "physical:counter:deviceId"` (`core-common/.../HybridLogicalClockUtil.kt`) is stored in `content_edge.stamp` and `holder_watermark.stamp`. That is an index of F9 (which device wrote or deleted each content slot) | `SqliteContentEdgeStore.kt:31-44, 66-73` | partly ("no write clock reaches the relay that the block did not already expose"). True, but the deviceId component is not stated |
| **N5** | `regenerable` per edge: which properties are thumbnails. Validated, **not stored** | `EdgedPush.kt` `PushedEdge` | yes (B3c item 4) |
| **N6** | `content_freed(freed_at)` and `content_unreferenced(since)`: the deletion and unreference time of each content, on the relay's own clock (migrations 23-24) | `RelayDatabase.kt:699-735` | yes (B4r / B5: "no new linkage") |
| **N7** | `relayProtocolVersion` in every push body | — | negligible while MIN = CURRENT |

**What CR-2 B3–B6 removes:** the edge form of `/content/declare` and all of `/content/undeclare`, which
takes away their separate traffic and its timing correlation. `referenceId` becomes optional and B6
retires it. Both are reductions.

---

## 5. Findings, ranked

Rank = (what AD5 learns × how many users and rows it touches) ÷ the cost of closing it.
"Accepted" means quoted in §1; "undecided" means no doc accepts it.

| # | leak | to whom | relay use | status | cheapest fix | cost |
|---|---|---|---|---|---|---|
| **F1** | plaintext `DataId` (type + path, realm prefix) on `fetch-entity-blocks` | AD5, logs | hashed with the **wrong** function; [I] matches nothing since EI-1 | **undecided**; T-R-08 *believes* the route takes a hash (`threat-model.md:2403-2405`) | send the wire id on an additive route; relay compares strings | S. Additive route, no bump. Phase 3 |
| **F2** | `targetEntityType` on every tombstone, retained for ever; per-type delete counts and cadence (labels, workouts, places, `FileModification` trims) | AD5, backups | one bit: `== Device.ID` | **undecided** (A6 lists existence, not kind) | EI-4: narrow the value to `Device.ID` or a sentinel | S. Client only, no bump. Phase 1 |
| **F3** | Mode-A `fileName`, `mimeType`, exact `totalSize`; kept forever without expiry | AD5, backups | none; served back to the viewer | **accepted**, on a rationale that conflates recipient with relay | seal them under `K_share` in the descriptor; relay keeps `chunkCount` | M. Share wire, viewer, web |
| **F4** | capabilities in the URI (raw `pairingSecret`, inviteId, grant address, shareId, EPC sessionId, `?ownerId=`) in the INFO call log | log sink | none | **undecided** | log the route template, not the URI | XS. Ops change, relay redeploy |
| **F5** | `content_edge.property_id` plaintext, joined to the ordered log by `holder_hash` | AD5 | key component only (§3.7) | **undecided**; EI-4 §5b names it as the residual | keyed tag `HMAC(K_entity, propertyId)` | S if done **in** B3's wire spec, M after |
| **F6** | a shared-realm owner reuses its personal A4 key and deviceId, so personal and shared realm are joinable | AD5 | none needs it | **undecided** | a per-realm device identity for owners, as members already have | L. Bootstrap, registrar, route-1 |
| **F7** | `deviceName` in clear on pairing request and hub offer; raw join `pairingSecret`; hub tracked by its stable hash | AD5 (in memory), logs | none for the name; the secret is the map key | **contradicts A6** | seal the name to the approver; send `H(secret)` as the offer flow does | S–M. Additive route |
| **F8** | ownerId oracles: realm existence, removal count (`/realm-epoch`), A9 ciphertext plus version (`/recovery-blob`) to anyone with an ownerId | AD1+ | recovery has no JWT yet | partly accepted (`sync-relay-api.md` L592) | an ownerId-blind recovery capability (e.g. `H(phrase-derived)` lookup key) | M. Recovery flow |
| **F9** | `hlc.deviceId` in every envelope at rest → which device wrote and deleted what, for ever | AD5, backups | [I] none on blocks; the tombstone stamp uses it for ordering | A6 "HLCs" arguably covers it | move it inside the ciphertext for blocks | L. Envelope reshape, protocol bump |
| **F10** | unpadded sizes: block payload length; last chunk = size mod 2 MiB | AD5 | quota (`byte_len`) | **accepted** (T-T-07) | bucket padding inside the AEAD | M. Storage overhead; a reader-first sync-envelope change, not a core-format bump (Phase 7) |
| **F11** | persisted read recency (`last_referenced_at` on GET); `hlc_tip` and `device_cursor` kept for revoked devices; `admitted_hlc_device` | AD5 | age window, inventory, GC (disabled); the barrier | partly accepted (A6 sync frequency) | touch on PUT only; prune tips and cursors on revoke | S each, but each has a consumer to check (§7) |
| **F12** | closure leaves the edge, ref and reference rows and the ordered log | AD5, backups | none after closure | **owed** in code (`ContentReferenceRegistry.kt:76-85`) | extend `reclaimOwner` | S. ⚖️ under retention P3 |
| **F13** | entitlement `sub` = ownerId → tier and quota on every write | AD5 | anti-sharing check | accepted (billing D5) | blinded tokens | trade-off; keep (D11) |
| **Fx** (integrity, not metadata) | `DeleteBlock` is unauthenticated; [I] the relay can forge deletes | AD5 active | — | ⚠ **contradicts T-K-05 / T-T-02** | a MAC under a realm key, or a sealed tombstone type | M–L. D4 |

### Status per finding on master (v0.6, 2026-10-28)

The table above is the audit as ranked on 2026-10-03. This one says what is still true.

| # | status on master | evidence |
|---|---|---|
| F1 | ✅ **closed** (CR-2 B3–B6) | body = wire id, shape-checked and compared verbatim (`routing/SyncRouting.kt:606-617`; client `RestSyncRelay.kt:365-369`); `FetchEntityBlocksByWireIdSystemTest`; T-R-08 amended (`threat-model.md:2751`); openapi known issue 2 ✅ |
| F2 | ✅ **closed** (CR-2 B3–B6) | `TombstoneWireType` (`sync-service-proxy/.../TombstoneWireType.kt:32-39`) mapped at drain in `PushTombstoneImplementation.kt:38`; `TombstoneSentinelCollisionTest`; A6 amended (`threat-model.md:987`). Landed before the reset, so `delete_block` holds no concrete types |
| F3 | ✅ **closed** | name, MIME and exact size sealed under `K_share` as the only descriptor format (D13); the clear columns dropped; a one-time `VACUUM` purged them from freed pages, then the relay schema was re-cut to a fresh baseline for the 2026-10 reset. The sealed MIME is still the sharer's claim, so the web viewer previews an allowlist only and mints every other type as `application/octet-stream` (it had framed `text/html` same-origin — a stored XSS). Residuals: exact size from the chunks (F10, accepted); pre-F3 database backups expire on the 30-day lifecycle |
| F4 | ✅ **closed** | Ktor logs the matched route template (call log, `StatusPages`, the engine's own failure line); Caddy filters the URI with per-route rules plus a generic ≥ 20-char backstop; `CaddyLogFilterTest` walks the real route tree against all three Caddyfiles. ✅ Verified with `caddy:2.11` on 2026-10-28: `caddy validate` passes on all three files, and 10 capability paths (incl. `//share/…`, `/%73hare/…`) left **0** ids in the access log. ⚠ Deploy: `caddy reload` on the box (plain `up -d caddy` is a no-op); recreate the relay container to drop old `json-file` lines (`server-logging.md`) |
| F5 / N1 | ✅ **closed** (CR-2 B3–B6) | `hex(HMAC-SHA256(K_tag, holderWireName ‖ propertyId))`, a separate HKDF child and per holder (B3 wire spec §11.1); T-R-09 amended (`threat-model.md:2918-2925`). `regenerable` stays plaintext, by decision |
| F6 | accepted (D9) | threat model **T-SR-06**: the owner reuses its personal A4 key and `deviceId`; members use per-realm keys. Revisit at shared-realm GA |
| F7 | ✅ **closed** | `H(secret)` (domain-separated) on every join route, device names sealed and padded, AAD binds the joiner's keys, 7-min response TTL; the client fits the name to the relay cap, hub hash labelled `tenshu/pairing/hub/rendezvous/v1`, the joiner is registered on response **delivery** (an expired response no longer stalls tombstone GC), admission minted once per pin, A33 admitters retry. The offer flow registers on delivery too (relay migration 2 queues the registration on the offer row), a queued admission still orders `Device` tombstones in both flows, and the hub-offer lookup is `POST /sync/pairing/hub-offer/fetch` with the key in the body. Open: an epoch rotation between two approves fails the second (`tech-debt.md`) |
| F8 | ✅ **closed** | ⚠ moved up: the pairing QR carries `o=<ownerId>` (`PairingUri.kt`), so ownerId is public. The unused A9 server-side blob is removed (no client ever uploaded one; recovery derives A1/A13 from the 256-bit phrase), the challenge is realm-blind and bounded, the claim's signature is verified before any realm lookup, and `realm-epoch` is behind the device JWT. Threat model A9 / T-K-02 / T-RC-08. The challenge is also capped per client IP (IPv6 per /64, `RECOVERY_CHALLENGE_RATE_LIMIT_PER_MINUTE`, default 6) → `429` + `Retry-After`, checked before the 10 000-slot store. ⚠ Found on the way (T-RC-09): the client seated the phrase's master key *before* claiming, so its own scheduler's lazy genesis registered a **new realm** for any valid but wrong phrase; the key is now seated only after the claim succeeds. A device already set up for a *different* realm is refused before any network call (`DeviceAlreadySetUpError`); recovering its own realm stays allowed, since that is the lock screen's forgot-passphrase path (T-RC-09 residual closed) |
| F9 | accepted | T-R-08 names `hlc = (physical, counter, authoring deviceId)`, also on edge/watermark stamps. The relay already learns the pushing device from its device-key JWT, and `deviceId` is the HLC tie-break, so moving it into the ciphertext would only shrink at-rest/backup exposure |
| F10 | accepted with the numbers (D8) | T-T-07: nothing is padded; content is cut on a 2 MiB stride, so chunk count + last-chunk length give the exact size within a few bytes of AAD framing. Padding stays Phase 7 (inside the AEAD, never a core-format bump) |
| F11 | ⚠ **narrowed** | `hlc_tip` / `device_cursor` are deleted on device revoke, in the revocation's transaction with a late-write guard, and on account closure. `device_cursor` itself is gone since 2026-10-29 (relay schema v5; nothing read it after CR-2 B6). Remaining: GET still touches `last_referenced_at` (Phase 4 (d), decide with CR-2 B5); `admitted_hlc_device` kept (4 (e)) |
| F12 | ✅ **closed** | closure erases every owner-scoped table, the revocation ledgers included; only the pseudonymous `erased_realm` row survives; the docs and the in-memory stores say the same. ⚖️ Reviewed by the maintainer as counsel on 2026-10-28 ([`privacy-review-checklist.md`](../policy/privacy-review-checklist.md); the privacy notice matches). Ops still open: blob-mirror pruning (`MIRROR_PRUNE`) is off; a database restore does not re-apply erasures (`tech-debt.md`) |
| F13 | accepted (D11) | T-CB-04: `sub = ownerId`, so tier and quota per gated write; blinding would remove the only anti-sharing check |
| Fx | ✅ **closed** | `DeleteBlock.authTag` = HMAC under `K_tomb`, verified first in `processDeleteBlock`; `SyncBlock` envelope bound as AAD ([`sync-envelope-authentication.md`](sync-envelope-authentication.md)). F9 deliberately not bundled (its D-a); the HLC drift bound is a separate PR (D-b). *Pre-fix record:* `DeleteBlock` had no signature or MAC. T-T-02, T-T-03 and T-K-05 amended 2026-10-26 (D13 item 1) to say so. ⚠ Found while amending: a `SyncBlock`'s envelope `hlc` is not bound to its AEAD (no associated data) and the merge orders by it, so [I] an active relay can roll an entity back by re-stamping or replaying a genuine ciphertext. Same fix family as Fx |
| N2 | holds | persisted equal-stamp cascade grouping in `holder_watermark`; a CR-2 design question |
| N3, N5–N7 | recorded by CR-2 B3–B6's own amendments | |

### Notes on the ones that need more than a row

⚠ The F1, F2 and F5 notes are the pre-fix analysis, kept as the reasoning; all three are closed.

- **F1.** The client serialises the whole `DataId` (`RestSyncRelay.kt:305`). For a `File` that is
  `type` plus `keyValue = path`. For a shared-realm entity the key starts with `"$realmId::"`
  (`OnDemandResolutionImpl.kt:65-68`).
  - The relay computes `entityId.getDataIdHash().toString()`, a decimal 64-bit FNV
    (`core/.../DataId.kt:422-443`), and selects `sync_block WHERE target_entity_id_hash = ?`
    (`SqliteSyncMailbox.kt:94-105`).
  - New blocks are stamped `wireIds.of(targetEntityId)`, which is 32 hex chars of HMAC
    (`SyncObserver.kt:2793`; `WireEntityId.kt:75-78`).
  - [I] A decimal `Long` never equals 32 hex chars, so the route returns `[]` for every post-EI-1
    block. Two consequences, if this holds:
    - the on-demand manifest path and `EntitlementRecovery` silently find nothing;
    - every call still discloses the path.
  - The relay test that pins the route seeds a block under the local hash
    (`SyncRelayTest.kt:139-152`), so it would not notice.
  - ⇒ Phase 3 starts with a test that fails today.
- **F2.** Fully analysed in EI-4 §5b; summarised in Phase 1. One addition: EI-4 says *"history
  itself changes only under a reset"*. Because `DeleteBlock` is unsigned (Fx), **the relay can
  rewrite retained envelopes** to the sentinel without breaking anything a peer verifies. That
  makes D2 possible: a one-off scrub instead of a reset.
- **F5.** The relay's only use is as a primary-key component (§3.7). So `HMAC(K_entity, propertyId)`
  under the realm's entity key keeps every relay query working, and the client can invert it over
  its finite property vocabulary.
  - ⚠ Changing it **after** B3 creates two edge rows for one property in a mixed fleet, which is
    the refcount confusion the CR-2 track exists to remove. Before B3 it is free.
  - ⚠ **Key rotation:** the same objection C′ raised against keyed handles
    (`ContentReferencing.kt:20-24`) applies. A rotated volume key renames every tag. EI-1's wire
    id has the same property and accepted it (EI Q2), so this adds no new class.
- **F6.** This is not a relay change. It is the cheapest-looking and the most expensive to fix,
  because the owner's personal A4 key is what lets route-1 runtimes co-own shared realms. Recorded
  so that the shared-realm GA review sees it; not scheduled here (D9).
- **Fx.** T-K-05: *"Defense: Tombstones are signed; the master-delegation chain is checked at merge
  time."* The code says otherwise:
  - [C] The schema has no signature or MAC field.
  - [C] `processDeleteBlock` checks only HLCs against local metadata (`SyncProcessor.kt:213-260`).
  - [I] I found no other verification: grep for `verify`/`signature` near `DeleteBlock` in
    sync-service-*.
  - SyncBlocks are protected because the relay cannot produce valid AEAD under the realm key; a
    tombstone has no AEAD.
  - [I] An active relay can therefore delete any entity whose wire id it has stored, and it has
    them all.
  - EI-4 §5b already observes that *"no authority proof reaches the ingesting peer at all"* for
    compromised **members**. This extends that to the relay itself.

---

## 6. The Device-tombstone question

**Asked:** *"Do even `Device` tombstones need the type in the clear, since unpairing and revocation
have separate relay endpoints anyway?"*

### 6.1 How revocation reaches the relay today

| path | auth | effect on relay | durable? |
|---|---|---|---|
| **`POST /sync/push-tombstone`** with `targetEntityType == Device.ID` + `revokedDeviceId` (`SyncRouting.kt:332-399`) | pusher's JWT: **any admitted device** | `revokeIfNotStale` → `revoke` (hard DELETE of the `device` row ⇒ the JWT fails with 401) **and** `recordRemoval` (the permanent `removed_device` ledger) (`DeviceRegistry.kt:72-87`); the row is also counted into the realm epoch | ✅ rides `PENDING_TOMBSTONES` and is retried until it lands |
| `POST /sync/admission/revoke` (`routing/AdmissionRouting.kt:37-92`) | A1 master signature | appends `admission_revocation`: **strips minting power only**, for the whole admission subtree; never calls `revokeIfNotStale` | ❌ *"deliberately best-effort"* (`RevokeDeviceImplementation.kt:249-265`) |
| `POST /sync/realm-member/revoke` | realm master | deletes **shared-realm member** devices + `member_revocation`; ⚠ deliberately **not** `recordRemoval` (`DeviceRegistry.kt:93-97`) | n/a for personal realms |
| self-removal (`ForgetThisDeviceImplementation.kt:23-41`) | — | calls `RevokeDevice` on itself: **the same tombstone**, byte-identical | ✅ |

⇒ **The tombstone is not redundant and not a fallback. It is the only enforcement**, for every
personal-realm removal:

- `revokeIfNotStale` has one caller (`SyncRouting.kt:383`; DB:388 *"Written ONLY from
  `revokeIfNotStale`"*).
- The realm epoch has one relay-side definition (`SqliteSyncMailbox.kt:313-321`) and one
  client-side definition (`SyncProxyInitialization.kt:478`). Both count Device tombstones.

**A revocation authored on another device.** The revoking device pushes its own tombstone, and the
relay acts on push. Peers that later pull it apply the type-blind `processDeleteBlock` and bump
their local Device count. [I] I found no flow in which device B pushes a tombstone authored by
device A. Pending tombstones are a per-device queue, so "authored elsewhere and synced" reaches the
relay only through its author.

### 6.2 What would break if Device tombstones went opaque

1. **Access cut.** A removed device keeps a working JWT. This regresses T-K-02's push rejection,
   which the threat model rates Low today.
2. **The removal ledger** stops being written, so `wasRemoved` at the join and offer flows stops
   fencing a removed device's return (EI-4 §5b item 0).
3. **`GET /sync/realm-epoch`** has nothing to count. The recovery race of ADR 0007 returns:
   recovered devices claim epoch 0 and peers reject them.
4. **Epoch agreement.** The relay counts rows; the client counts distinct ids
   (`TombstoneRegistry.kt:325-336`). Any replacement must pick one cardinality and keep the
   monotonic floor (EI-4 §5b item 1; the 2026-08-18 incident).
5. **Staleness.** The authored-HLC ordering guard lives on the tombstone. `admission/revoke`
   carries a wall-clock `revokedAt` instead (EI-4 §5b item 3; the 2026-08-17 incident).
6. **Pre-change clients.** Their revocations become silent no-ops the moment the relay stops
   recognising the kind. No session fence can exclude them, because `relayProtocolVersionOk` runs
   only at genesis (EI-4 §5b, the fifth condition).

### 6.3 Answer

The Device bit has to stay until a new, A1-authorised, durably queued **device-access revocation
route** exists, together with the four prerequisites and the fleet condition in EI-4 §5b
(`sync-entity-identity.md` lines 406-1007).

⚠ **The metadata gain of the end state is small.** It is one bit ("this delete was a device") plus
`revokedDeviceId`, which the relay already knows from `device` and `removed_device`. The security
gain is large: today the irreversible access cut is authorised by **any member's JWT**, the inverted
guard EI-4 §5b names around line 617. So Phase 2 should be scheduled for its security value. The
metadata closure falls out at the end. That is D3.

---

## 7. Phased plan

⭐ **v0.4 progress:**

| phase | state on master |
|---|---|
| 1 | ✅ shipped in CR-2 B3–B6, inside the reset (the "after CR-2 B3–B6" column below is moot) |
| 1b | ✅ not needed: no concrete-type history exists |
| 2 | open: schedule with a device-access revocation track (D3) |
| 3 | ✅ shipped in CR-2 B3–B6, **in place**, no additive route |
| 4 | (a) ✅; (b) ✅; (c) ✅; (d), (e) open |
| 5 | ✅ shipped in CR-2 B3–B6, with a stronger tag than proposed (B3 wire spec §11.4) |
| 6 | ✅ shares (single format, D13); pairing (F7) |
| 7 | Fx ✅; F8 ✅; F6, F9, F10 accepted |

The phase texts below are the design as written; read them as the record for 1, 3 and 5.

A relay-protocol bump is **not** a storage-format bump (AGENTS.md HR 9). `RELAY_PROTOCOL_VERSION`
moves only when a client at N and a relay at N+1 would misunderstand each other
(`core-common/.../RelayProtocol.kt:25-40`). Additive routes never move it.

⚠⚠ **Where the `[min, current]` gate runs depends on which tree you are in:**
- **On master** it runs only on `POST /sync/realm` (EI-4 §5b, around line 336), so it fences new
  registrations and never an established client.
- **On CR-2 B3–B6** it also runs **per request** on `/sync/push`, `/sync/push-tombstone` and
  `/sync/content/reap`, because each body carries `relayProtocolVersion` (§4b). A `MIN` raise then
  fences established clients on their next write. A `CURRENT` bump on a client that meets an
  undeployed relay fails **every push**, not only genesis, so "relay first" goes from advisable to
  mandatory.

### Phase 1: the tombstone's type becomes one bit (= EI-4)

**What changes.**
- `TombstoneEmitter` / `SyncObserver.enqueueTombstone` write `targetEntityType = Device.ID` for
  device tombstones and **one sentinel** for everything else (EI-4 proposes `"tenshu/entity"`).
- ⭐ Map at **drain** time, not at enqueue, so that a queue filled before the upgrade does not
  leak after it (EI-4 §5b, around line 394).

**What the relay stores.**
- The same column with the same value domain, narrowed to two values. No relay code changes:
  both reads are already `== Device.ID`.
- ⚠ The retained envelope also holds the sentinel from now on. History keeps concrete types
  unless D2 scrubs it.

**How the receiver recovers the type.** It already does: `resolveDataId(idHash)` returns a `DataId`
that carries its type (`SyncProcessor.kt:217-218`). A tombstone for an entity the receiver never
saw is recorded by hash and needs no type. `TombstoneRegistry.typeCounts` collapses non-device
types into the sentinel bucket, and nothing reads that bucket.

**Protocol window.** There is **no `RELAY_PROTOCOL_VERSION` bump and no `MIN` move**. The brief
asked for a bump and a `[min, current]` coexistence window; EI-4 rejected it on the rule's own
normative test, and I agree:
- An old relay reads the sentinel as *not a device*, which is correct.
- A new relay reads a legacy concrete type with `== Device.ID`, which is also correct.
- Bumping `CURRENT` alone would make a bumped client hitting an undeployed relay fail `POST
  /sync/realm` with a 426, a self-inflicted bootstrap outage (`RelayProtocol.kt` range semantics;
  EI-4 §5b around line 373).

**Old and new peers.** Mixed fleets interoperate in both directions, because the shape is
byte-identical. That is the property the EI-4 gate test pins.

**The same analysis per tree:**

| | master (protocol 3) | after CR-2 B3–B6 (protocol 4, per-request gate) |
|---|---|---|
| bump needed? | no: shape-compatible, and the gate cannot fence established clients anyway | **still no**: shape-compatible. The per-request gate makes a bump *possible*; it does not make one *needed* |
| pre-Phase-1 clients after Phase 1 ships | keep writing concrete types indefinitely; nothing can stop them | keep writing them, **but** a `MIN` raise to 5 would now fence them on their next tombstone push. ⚠ It would also fence their `/sync/push`, so it is a cutover, justified only if paired with something else that needs one |
| history in `delete_block` | concrete types stay unless Phase 1b scrubs them | ⭐ **if Phase 1 lands inside CR-2 B3–B6** (before its reset), the reset leaves **no** concrete-type history. The forward-only caveat disappears, and Phase 1b is unnecessary |
| if Phase 1 lands after CR-2 B3–B6 | — | the same as master's column, except that a future `MIN` raise can retire old writers |
| relay change | none | none: `SyncRouting.kt:505` [C@CR-2] is still `== Device.ID` |

⇒ The cheapest moment for Phase 1 is **inside CR-2 B3–B6, before its reset** (D12). Phase 1 is a
client-only mapping at drain time plus test 5, and CR-2 B3–B6 already owes the reset and the fleet move.

**`delete_block` migration.** None is required. An **optional** relay-side scrub is D2 (Phase 1b
below).

**Relay redeploy.** Not required for Phase 1.

**Owed:**
- client release only, with **no lockstep**;
- no HR 9 bump (`DeleteBlock` and `PersistedTombstone` keep their shape; `SchemaShapeBaseline` is
  unchanged);
- threat-model amendment (HR 2): A6 gains *"tombstone kind = device or not"*; T-R-08 notes that the
  type is now one bit; and ⚠ it says plainly that the guarantee is **forward-only**.

**Tests:**
1. the emitter writes the sentinel for every non-device type and `Device.ID` for revocations;
2. a tombstone queued before the upgrade and drained after carries the sentinel;
3. relay: a Device tombstone still revokes and counts, and a sentinel one does neither (extend
   `SyncRelayTest`);
4. a pre-EI-4 parser decodes a post-EI-4 tombstone (the shape gate);
5. ⚠ the sentinel equals **no** registered schema id, asserted over the full registry in
   `:system-test-utils`, so a future type cannot collide;
6. `processDeleteBlock` deletes the right row given the sentinel;
7. the realm epoch is unchanged across the upgrade on both sides.

**Risk:** low. The failure mode is a collision with a real type id, which test 5 closes.

**Phase 1b, an optional history scrub (D2).** A one-off operator tool in the B0 pattern: loopback
plus a token, plan → apply, digest-gated. It:
- sets `target_entity_type = sentinel WHERE target_entity_type <> Device.ID`;
- re-serialises each such envelope with the sentinel. That is possible only because `DeleteBlock` is
  unsigned (Fx). ⚠ If Fx is fixed first, the scrub can no longer rewrite envelopes, so **order
  matters**.

Owes a relay redeploy and an operator run. Backups age out after 30 days. Peers' local
`PersistedTombstone` rows keep their concrete types, but those never leave the device.

### Phase 2: the Device discriminator (= the EI-4 end state)

**Scope.** EI-4 §5b items 0–3 plus the fleet condition, unchanged:
0. a new A1-authorised device-access route (`revokeIfNotStale` + `recordRemoval`, carrying the
   authored HLC) behind a durable client queue, plus `ReadmitDevice` consulting a
   `DeviceRevocation` record instead of row presence;
1. one epoch definition and cardinality, with its floor;
2. durability of the new route;
3. the staleness guard moved onto it.

Then, once no pre-Phase-2 client can author a revocation, the discriminator is removed.

**Additions from this audit:**
- ⚠ **Close the inverted guard as part of item 0.** A plain member JWT should no longer be able to
  cut another device's access; the tombstone path becomes advisory to the relay.
- ⚠ **`/sync/realm-epoch` needs a non-tombstone source.** Its unauthenticated exposure (F8) should
  be decided in the same slice.
- ⭐ **On CR-2 B3–B6, the fifth condition becomes enforceable.** EI-4 §5b calls it *"a fleet statement,
  not a code change, and the one part of this end state that cannot be scheduled"*, because the gate
  ran only at genesis. With `relayProtocolVersion` on every `CascadedTombstonePush`, raising `MIN`
  past the last tombstone-revoking client refuses that client's revocations with a 426 rather than
  letting them become silent no-ops. The cost is a cutover for that client's pushes too. So once
  CR-2 B3–B6 merges, item 5 is schedulable, priced as a cutover.

**Owed:**
- a new relay route (additive, no protocol bump) and a relay redeploy **before** clients;
- an identity-service HR 9 bump plus `SchemaMigration` for `Device.admittedAtHlc`, and a new
  `DeviceRevocation` composite (additive);
- for the final removal: a `DeleteBlock` reshape plus retained-envelope migration **or** a reset,
  and at that point a `RELAY_PROTOCOL_VERSION` review (EI-4 §5b, around line 987).

**Risk:** **high.** Every mistake is a removed device keeping access, and the A33-only admitter path
needs the product decision EI-4 records (option b, "waiting for an owner device").

### Phase 3: `fetch-entity-blocks` by wire id (F1). Recommended FIRST, independent of 1 and 2

- **Additive route** `POST /sync/fetch-entity-blocks/by-wire-id`. The body is a single wire-id
  string, and the relay compares it to `target_entity_id_hash` verbatim.
- **Client** uses `WireIdResolver.of(dataId)` with the same realm as the runtime's relay.
- ⚠⚠ **No fallback that sends a `DataId`.** On 404 (an old relay), treat it as unsupported and
  return empty. [I] That is what the old route effectively returns anyway.
- **Retire the old route** once clients have moved. A removal is a route-semantics change, so it
  needs a `RELAY_PROTOCOL_VERSION` review then, though [I] it probably passes the "misunderstand"
  test because old clients already treat 404 as empty (`RestSyncRelay.kt:318-322`).

**Owed:** relay redeploy first, then the client; no lockstep; no HR 9 bump. Threat-model T-R-08
amendment: the route took a `DataId`, not a hash.

⭐ **Cheaper inside CR-2 B3–B6.** Its hard cutover means the old body need not be served beside the new.
So the route's body can change **in place** from a `DataId` to a wire-id string, with no additive
route and no later retirement. That is one relay line (compare verbatim) and one client line
(`wireIds.of(entityId)`). It closes the branch's own openapi issue #2 and the plaintext path
together.

**Tests:**
1. **a failing-today test**: store a block under a keyed wire id and fetch via the old route ⇒ `[]`,
   which pins the [I];
2. the new route returns it;
3. a spy relay asserts no `DataId` is ever serialised to the relay by `OnDemandResolutionImpl` or
   `EntitlementRecovery`;
4. a shared-realm entity resolves under its realm's key.

**Risk:** low. It also probably **fixes** a silent functional break, which deserves its own line in
the PR.

### Phase 4: relay hygiene, one redeploy (F4, F11, F12)

Each item is small and relay-only unless noted.
- (a) **Call logging by route template** (F4). XS, no client change. Recommended immediately.
  - ⭐ v0.4: the server-logging change removed queries and secrets from every log and bounded Caddy's retention, so the
    remainder is the **path**. Ktor: a `CallLogging { format { … } }` that prints the matched route
    template (e.g. `/share/{shareId}`) instead of `request.path()`. Caddy: a `uri` filter that
    replaces the capability segments. Both are needed: either alone still logs the id.
- (b) **Prune `hlc_tip` and `device_cursor` on `revoke`.** [I] Check `ReclamationBarrier` and
  divergence detection first: a revoked device's stale cursor can only *stall* the barrier, so
  pruning should help, but verify.
- (c) **Extend `reclaimOwner`** to the edge, ref, reference and free-queue tables (F12). ⚖️ This is
  deletion, so it sits under the retention-quota P3 counsel gate.
- (d) **Stop persisting GET recency.** ⚠ The N0 age window and inventory read `last_referenced_at`
  and *know* GET refreshes it (`ContentEnumerationAgeWindow.kt:29`; `ContentInventory.kt:76`), so
  this is a semantic change for the age window, not cleanup. Decide with CR-2 B5, which adds its own
  per-content timing.
- (e) `admitted_hlc_device`: [I] it may be the HLC tie-break in `HlcStamp` ordering. Keep unless
  proven unused.

**Owed:** relay redeploy; no protocol bump. A threat-model amendment only for (c).

### Phase 5: keyed content property tag (F5), inside CR-2 B3's wire spec

`propertyTag = HMAC(K_entity, propertyId)` on `declare`, `undeclare` and B3's push edges. The relay
is unchanged because it already treats the value as opaque. The client inverts the tag over its
vocabulary.

**Owed:** nothing beyond B3's own obligations **if specified there**; if done later, a dual-row
transition. Threat model: T-R-09 / EI-4 §5b's residual is closed.

⚠⚠ **v0.2: B3 is built, on CR-2 B3–B6** (`PushedEdge.propertyId`; `content_edge` recreated by migration
22). So "before B3" now means **before CR-2 B3–B6 merges**:
- **Inside CR-2 B3–B6.** The tag rides the existing reset: migration 22 already drops and recreates
  `content_edge`. The relay needs no change: it only shape-checks the value with `EDGE_ID_PATTERN`
  (`ContentRouting.kt:1134` [C@CR-2]; hex fits) and uses it as a key. [I] I found no relay code that
  reads `property_id` semantically on the branch. The client change is the `PushedEdge` builder and
  the `contents` describe, plus any client code that reads a property id back from a relay response.
  [I] I found none that does; that is to be confirmed by the CR-2 B3–B6 session.
- **After CR-2 B3–B6.** It is a second reset or a dual-row transition for every edge, which is exactly
  the refcount confusion CR-2 exists to remove.
- ⚠ Either way, CR-2 B3–B6's B3c threat-model amendment should say the property id is plaintext (N1), or
  the amendment under-reports what it ships.

**Risk:** low inside CR-2 B3–B6, medium after.

### Phase 6: sealed Mode-A descriptor (F3) and pairing names (F7)

- **Shares.** Add an optional `sealedMeta` holding name, MIME and exact size under `K_share`. The
  relay keeps `chunkCount`, expiry and max-opens.
  - ⚠⚠ **Readers first, and never by blanking the clear fields under the old link format.** The viewers read `fileName` and `mimeType` straight from the clear descriptor
    (`tenshu-sdk-js/.../ShareViewer.kt:111-115, 184-190`). A sharer that sends them empty would make
    an older or cached viewer deliver the file with empty metadata. Share recipients are anonymous,
    so no fleet fence can exclude old viewers.
  - ⇒ Order:
    1. ship viewers that prefer `sealedMeta` when present, on web and in the KMP apps;
    2. then switch sharers to a **versioned link**, a new route or a version marker in the link,
       whose new-format descriptor carries no clear fields. An old viewer then fails visibly on a
       link it cannot read, instead of degrading silently.
  - Old-format links keep their clear fields until they expire or are revoked. That keeps the
    residual bounded, but not zero: §3.8's forever-retention of shares with no expiry still applies
    to them.
  - [I] Whether an old relay rejects an unknown JSON field depends on its `Json` configuration; if
    it does, the versioned route answers it anyway.
- **Pairing.** The join flow sends `H(secret)` as the offer flow already does, on an additive
  route. `deviceName` moves inside a blob sealed to the approver.

**Owed:**
- **Shares:** viewers first (web deploy plus app releases), then the relay route, then sharers.
  There is no lockstep, but the order is mandatory, because recipients cannot be fenced.
- **Pairing:** relay redeploy, then clients.
- **Threat model:** an amendment to the Mode-A reaffirmation, and to A6 versus T-R-02 (names).

### Phase 7: the expensive ones, to decide rather than schedule

- **F9**, `deviceId` out of the stored envelope. This is a `SyncBlock` reshape, which bumps
  `RELAY_PROTOCOL_VERSION` (`RelayProtocol.kt:30`) and needs dual decoding.
- **F10**, padding. Put it inside the AEAD so the relay needs no change.
  - ⚠⚠ It must **not** move `TENSHU_STORAGE_FORMAT_VERSION` (correcting v0.2).
    That constant is `:core`'s alone, and moving it wipes every local store (AGENTS.md HR 9).
  - The sealed plaintext is `sync-service-common`'s `BlockPayloadEnvelope`,
    `[varint coreVersion][varint stampLen][stamp][payload]` (`BlockPayloadEnvelope.kt:14`). Padding
    is therefore a **sync-owned** envelope change.
  - Rollout is reader first:
    1. make `open` tolerate padding, either an explicit pad-length field or a trailing pad after a
       length-prefixed payload, decoded backward-compatibly;
    2. ship that;
    3. only then let writers pad, gated on the owning sync service's version through the realm
       epoch vector.
  - ⚠ A store that keeps padded ciphertext needs no migration. Bump the sync service's own
    `service(id, version)` with its `SchemaMigration` only if a stored row's shape changes.
  - The cost is storage overhead.
- **F6**, a per-realm owner device identity.
- **F8**, an ownerId-blind recovery lookup.
- **Fx**, tombstone authentication. Recommended for its own security value. Done as a **new**
  tombstone type on an additive lane, it is also where the Device bit could move inside, which
  would supersede Phase 2's final reshape.

| phase | relay redeploy | client lockstep | HR 9 bump | protocol bump | risk |
|---|---|---|---|---|---|
| 1 | no (1b: yes, plus an operator run) | no | no | no | low |
| 2 | yes, first | no, but a fleet *waiting condition* before the final step | identity-service | final step only | high |
| 3 | yes, first | no | no | no (review at retirement) | low |
| 4 | yes | no | no | no | low |
| 5 | with B3 | B3's | B3's | B3's | low before B3 |
| 6 | yes; shares: **viewers first** | no (shares: versioned link, not a fence) | no | no | low–medium |
| 7 | varies | F9/F10: dual decode | F10: sync-service only if a stored shape changes (**never** the core format) | F9: yes | medium–high |

---

## 8. Decisions for the maintainer

The code settles the facts in §§3–6. These are choices.

⭐ **v0.4:** D1, D2, D5, D6 and D12 are **resolved** by CR-2 B3–B6 (D2 is moot). D10 is half done by
the server-logging change. D3, D4, D7, D8, D9 and D11 are still open.

- **D1. Phase 1 = EI-4's value-narrowing, not an encrypted field and a protocol bump.**
  ✅ *Done (CR-2 B3–B6).* *Recommend: yes.* Cost: per-type counts of non-device deletes vanish, and nothing reads them. The
  encrypted-field shape would owe a reshape, dual decoding and a bump, for no further disclosure
  reduction: the relay must still learn the device bit.
- **D2. Scrub history (Phase 1b)?** ✅ *Moot: Phase 1 landed before the reset.* *Recommend: yes,
  after Phase 1 ships and before Fx is fixed.*
  Production realms are test data. Cost: one operator run plus a redeploy, and 30 days of backup
  ageing. Without it, the disclosure is *stopped*, not *gone*.
- **D3. Schedule Phase 2 for security, not metadata.** *Recommend:* fold it into a device-access
  revocation track, where item 0 closes the inverted guard. Do not start it to save one bit.
- **D4. Fx: tombstone authentication.** *Recommend:* amend T-K-05 and T-T-02 in the first code PR
  of this track so the threat model stops claiming a signature, and open authentication as its own
  track. Cost: a new tombstone type plus a lane, M–L.
- **D5. Phase 3 first.** ✅ *Done (CR-2 B3–B6), in place.* *Recommend: yes.* It is the worst leak found, a small change, and probably a
  functional fix too. Cost: one relay deploy and one client release.
- **D6. F5 in B3.** ✅ *Done (CR-2 B3–B6).* *Recommend: yes.* ⚠ B3 is built on CR-2 B3–B6, so this now means
  "inside CR-2 B3–B6, before it merges" (D12). Cost: nothing there, and a second reset or a dual-row transition after.
- **D7. F3: seal or explicitly accept?** *Recommend: seal* (Phase 6); at minimum, correct the
  threat model's rationale. Note for §11 content-head addressing: object keys should stay opaque to
  the S3 provider.
- **D8. Padding (F10).** *Recommend: defer.* Accept explicitly with the numbers (last chunk = size
  mod 2 MiB) rather than the generic T-T-07 wording.
- **D9. F6, shared-realm owner linkage.** *Recommend:* accept and document now, and revisit at
  shared-realm GA. Cost of fixing now: large, because it touches route-1 co-ownership.
- **D10. Call logging (F4).** *Recommend: do it now* (ops). Also confirm with the maintainer
  whether existing docker and Caddy logs on the production box should be rotated out early.
  ⚠ *Half done (server logging, 2026-10-23):* queries, secrets and headers are gone and Caddy rolls in ≤ 7 days.
  Still open: route-template logging for path capabilities (Phase 4 (a)).
- **D11. Entitlement `sub` (F13).** *Recommend: keep.* Blinding removes the anti-sharing check.
- **D12. Ask the CR-2 B3–B6 session to take the cheap ones before it merges?** *Recommend: yes, for the
  first three below.* Each costs one or two lines plus a test because CR-2 B3–B6 already owes the relay
  reset, the protocol-4 cutover and the fleet move. Each is materially dearer afterwards. Ranked by
  how much merging first makes it cost:
  1. **F5 / N1: keyed `propertyId` tag** on `PushedEdge` and the describe. After the merge it needs
     a second reset or a dual-row transition. Also fix the B3c amendment so it says the id is
     plaintext, if the tag is declined.
  2. **F2: Phase 1 (EI-4 narrowing)** in the client tombstone emitter, mapped at drain. Inside the
     reset, history carries no concrete types and Phase 1b becomes moot; after it, history needs a
     scrub.
  3. **F1: `fetch-entity-blocks` body becomes the wire id, in place.** The hard cutover saves the
     additive route and its later retirement, and it closes the branch's own openapi #2.
  4. *Optional, equally cheap at any time:*
     - **F4**: route-template logging. The reset is a natural moment, but it is not tied to it.
     - **F7**: the raw secret in `pairingSecretHash`, openapi #4. In-place under the cutover;
       otherwise an additive route.
  5. *Not for CR-2 B3–B6:*
     - **N2**: persisted cascade grouping. The watermark *is* the B3 mechanism; reducing it means
       per-holder distinct stamps, which is a design question for CR-2, not a drive-by.
     - Phase 2, Fx, F3, F6 and F9: too large or out of scope.

  Cost of asking: scope creep on a 217-file PR. Items 1–3 are each one emitter-side change plus a
  test and touch no relay schema. ⚖️ This is the maintainer's call, because CR-2 B3–B6 belongs to another
  session.

  ⭐ **Asked (2026-10-03).** The maintainer asked the CR-2 B3–B6 session to take items 1–3: keyed
  property tags, EI-4 type narrowing, and `fetch-entity-blocks` by wire id.

  ✅ **Outcome (2026-10-04, merged 2026-10-05):** all three landed in CR-2 B3–B6 under its cutover:
  no new route, no relay migration, no protocol move, no HR 9 bump. As built:
  [`content-plane-cr2-b3-wire-spec.md`](content-plane-cr2-b3-wire-spec.md) §11, including §11.4 on
  where the build departed from this doc. Item 4 (F4, F7) was not taken; F4 was later narrowed by
  the server-logging change (remainder: route-template logging), and F7 was then open (v0.5).

- **D13. The pre-release cut** (maintainer, 2026-10-24). ✅ *Decided.*

  **The lens.** Until the first public release there is no fleet to keep compatible with. A wire
  change is in place (no reader-first window, no legacy links or routes to keep serving), and
  nothing the relay stores yet sits under a real person's name. That is what made F1, F2 and F5
  one-liners inside CR-2 B3–B6. After release every wire change is a reader-first rollout, and every
  retained field accumulates. So the cut is ranked by **how much dearer the fix gets after
  release**, not by severity alone.

  **Fix before release:**
  1. ✅ *Done 2026-10-26.* **Fx wording.** Amend T-K-05 and T-T-02 to say tombstones are
     unauthenticated, and open tombstone authentication as its own track (D4). Cost: none. A
     public security contract must not claim a signature that does not exist.
  2. ✅ *Done.* **F3, sealed share metadata.** Before release the sealed descriptor can be the *only* format:
     with no old viewers or links in the wild, Phase 6's readers-first ordering is unnecessary.
     After release, both formats live on until the last unexpiring share dies. Fallback if it
     slips: a default share expiry.
  3. ✅ *Done.* **F7, pairing secret, device name and TTL, in place.** `H(secret)` in the join flow, the
     device name sealed to the approver, and a TTL on join requests and responses (openapi known
     issue 4). After release this needs an additive route plus a retirement. Pair it with the
     pairing UX pass (the post-SAS confirmation screen), which touches the same flow.
  4. ✅ *Done (Caddy verified live 2026-10-28).* **F4 remainder, route-template logging** in Ktor `CallLogging` and Caddy (Phase 4 (a)).
  5. ✅ *Done in code, ⚖️ counsel sign-off still owed.* **F12, closure reclaims everything.** ⚠ Checked 2026-10-24: retention P3 (live) *is*
     `reclaimOwner`, so closure deletes `content_blob` rows and bytes only. The ordered log
     (`sync_block`, `delete_block`), `device`, `hlc_tip`, `device_cursor`, `content_edge`,
     `holder_watermark`, `content_ref`, `content_freed` and `content_unreferenced` survive closure
     indefinitely. ⚖️ Under the counsel sign-off retention P3 already owes, and must match what the
     privacy notice promises.
  - ✅ *Done: unset or unparseable `DEV_MODE` now means `false`; only `./gradlew :sync-relay-server:run` opts in.* *Adjacent, not metadata:* `DEV_MODE` defaulted to `true` when unset (openapi known issue 1), so
    a misconfigured relay fails open with an auth bypass. Flip the default.

  **Decide before release, possibly build:**
  6. **Fx, tombstone authentication.** An active relay can forge a delete for any entity it has a
     wire id for, and propagation plus content reclaim turn that into real data loss. It needs a
     protocol change, M–L. If done before release, **bundle F9** (`hlc.deviceId` inside the
     ciphertext) into the same cutover: both reshape the envelope, so one reset instead of two.
     After release it is an additive lane plus a long coexistence.
     ✅ **Done** (2026-10-27, folded into the protocol-1 release baseline): [`sync-envelope-authentication.md`](sync-envelope-authentication.md)
     — tombstone MAC plus `SyncBlock` envelope binding. ⚠ **F9 was deliberately not bundled**
     (its D-a): the HLC compare uses `deviceId` as its tie-break and tombstones have no ciphertext to
     move it into, so F9 is its own design; it can take the next pre-release reset. ⚠ Also bundle **envelope
     binding**: pass `blockId`, `hlc` and `targetEntityIdHash` as AEAD associated data, which
     closes the `SyncBlock` rollback found on 2026-10-26 (§5 Fx row).

  **Ship with, documented in the threat model:**
  - F11 (GET recency, stale tips and cursors): small, but touches the age window; do it with CR-2.
  - F8 (ownerId oracles): ⚠ open question, whether `ownerId` or the master public key appears in
    any public artefact (QR, link). If it does, F8 moves up.
  - F10, F6, F13: accept explicitly (D8, D9, D11).
  - Phase 2 (device-access route, the inverted guard): its own track. In a personal realm the
    guard mostly enables griefing by the user's own stolen device.

---

## 9. Threat-model amendments owed (not made in this PR)

Carried by the first code PR of the relevant phase, per House Rule 2.

**Made by CR-2 B3–B6** (v0.4):
- ✅ **A6**: the tombstone kind is one bit (`threat-model.md:987`).
- ✅ **T-R-08**: `fetchEntityBlocks` took a `DataId`; it now takes the wire id (`:2744`, `:2751`).
- ✅ **T-R-09**: content-edge property ids are keyed tags (`:2918-2925`).

**Made 2026-10-26** (D13 item 1): ✅ **T-T-02, T-T-03, T-K-05**: there is no block signature,
tombstones are unauthenticated, and envelope fields are not bound to the AEAD. Residual for
integrity of deletes against AD5 is now **High**, rollback **Medium**. `sync/design.md` carries an
as-built note.

**Still owed:**
- ✅ **A6 vs T-R-02** on device names: resolved by F7.
- ✅ **T-R-08**'s tuple names the authoring `deviceId` in `hlc` and in edge/watermark stamps (F9/N4).
- ✅ **Mode-A reaffirmation**: sealed (F3).

⚠ No pointer was added to the threat model in this PR. Its convention is amendment-with-code, and a
pointer to unbuilt design would be the kind of claim §12 maintenance has to prune.

---

## 10. What the code did not settle, and what is inferred

- ✅ **F1's "matches nothing"** was confirmed by CR-2 B3–B6: `FetchEntityBlocksByWireIdSystemTest` failed
  before the fix (the on-demand manifest path and `EntitlementRecovery` found nothing).
- [I] **Fx exploitability.** No verification was found, but an end-to-end forged-tombstone test is
  the proof.
- ✅ **Call-log format.** Settled by the server-logging change: Ktor's default `CallLogging` prints the path only, never
  the query string. `?pairingSecret=` reached logs through `StatusPages` / malformed-body lines
  (fixed) and Caddy (now dropped); path segments still do (F4).
- [I] **Mode-B ciphertext shared across owners** (`RelayDatabase.kt:601-603` comment): it would let
  the relay join `content_blob.ref` across owners. Whether that copy path is live was not verified.
- [I] **Old relays and unknown JSON fields** (Phase 6). This depends on its `Json` configuration.
- ✅ **Pruning tips and cursors** (Phase 4b): traced — nothing waits on a revoked device's rows since CR-2 B6 removed the barrier; the tombstone-GC quorum reads the device registry.
- ✅ **ownerId secrecy** (answered 2026-10-28): the pairing QR carries `o=<ownerId>`, so it is public;
  F8 moved up and is closed.
- ✅ **Verified (v0.5): no join-flow `pairingSecret` feeds `deriveEpcRendezvousSessionId`.** Its only
  production caller is `EpcPairingInvite.kt:32`, fed by `DefaultEpcPairingHost.generateSecret`
  (`nextSecureRandomBytes`, `DefaultEpcPairingHost.kt:28`), not by the join secret.
- **Doc drift noted, not fixed:**
  - [`sync-relay-persistence.md`](sync-relay-persistence.md)'s status line says "no code yet" while
    its body says the track is complete.
  - [`sync-relay-api.md`](sync-relay-api.md) L124 lists envelope fields that do not exist.
  - [`sync-backend-relay-plan.md`](sync-backend-relay-plan.md) §6.1 plans a property column (§4).
