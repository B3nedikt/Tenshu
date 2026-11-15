# Shared-realm read-only members — "Can view" by default, "Can edit" opt-in (RO)

**Status:** **Proposal — design only, nothing built** (2026-11-10). Direction set by the
maintainer: a shared folder is **view-only for members by default**, and editing is something
the sharer **turns on per member**. Enforced in **two independent places**: the relay refuses a
viewer's writes, and clients refuse any block not authored by a device entitled to write. Open
decisions are in §8. Decided 2026-11-10: D6 with Q1 (b), D7 (a), D8 (no real shares, so no
legacy handling), Q4 (one mechanism, §4.3), Q3 ("edited by", D9), D10 (no device identity
crosses a share). Open: Q6, a folder-wide activity feed.

Related: [`shared-realm-membership-grant.md`](shared-realm-membership-grant.md) (GR; its §1
lists write tiers as a v1 non-goal, which this proposal lifts),
[`sync-entity-identity.md`](sync-entity-identity.md) §5b (the A1-signed revocation records,
which shares the credential-chain machinery in §4.3), threat model T-GR-06 and T-GR-08.

---

## 0. Summary

Today every member of a shared realm can write, and nobody but the relay can tell who wrote
what. This proposal adds two roles, **viewer** (default) and **editor**, carried in the
master-signed membership capability, so the relay and every client verify the same signed fact.

- **The relay** refuses pushes, tombstones and content uploads from a viewer's devices. It is
  cheap, and it stops a modified client, but it trusts the relay.
- **Clients** accept a shared-realm block only if it carries an author signature from a device
  whose credential chain ends at the realm master and grants write. That holds even against a
  hostile relay, and it makes authorship attributable to peers for the first time.

The owner's devices are always writers: they are the realm's accepted root of trust (threat
model T-GR-08, maintainer decision 2026-11-10).

## 1. What exists today

- **One symmetric key, no author.** A shared realm's blocks are sealed under keys derived from
  Kr (`DefaultSyncCryptoManager.deriveSharedRealmKeys`). Every member holds Kr. A `SyncBlock`
  carries `blockId, ownerId, hlc, encryptedPayload, targetEntityIdHash` and no author field
  (`sync-service/.../SyncBlock.kt`). A `DeleteBlock` carries an HMAC under a Kr-derived key
  (`EnvelopeAuth.verifyTombstone`). Any member can therefore mint a block every peer accepts.
- ⚠ **The threat model overstated this.** A12 said member writes were "signed under each
  member's own A1" and so attributable. They are not: the only signatures on the write path are
  the relay JWTs (`PerRealmDeviceRelay`, `SharedRealmContentBlobStoreFactory`). The relay can
  attribute a push to a device; peers cannot. Corrected alongside this proposal.
- **The relay knows who is a member.** `member_admission` records every member device
  (`MembershipRegistry.isMemberDevice`, added for T-GR-08). It does not know any role.
- **The capability has no role.** `MembershipCapability.message` signs
  `tenshu-member-cap-v2 ‖ realmId ‖ memberAuthorityPub ‖ epoch ‖ issuedAt`
  (`sync-service-common/.../MembershipGrant.kt`).
- **`Member.role`** (`OWNER` / `MEMBER`) is a realm-resident display record that any member can
  write. It can never be the enforcement source.
- **A joiner writes into the realm on accept.** `SharedRealmGrantManager.accept` saves the
  joiner's own `Member` row as the D4 acceptance signal. A viewer cannot do that (D7).

## 2. Goals and non-goals

**Goals**

1. A member is a **viewer** unless the owner made them an **editor**. New grants default to
   viewer.
2. A viewer's writes are refused by the relay **and** ignored by every honest client, each
   check sufficient on its own for its threat (§5).
3. Every shared-realm write is attributable to a device and, through its chain, to a member,
   verifiable by peers rather than only by the relay.
4. The owner can change a member's role later without removing and re-inviting them.
5. A viewer's app never queues a write it will be refused for. The refusal shows up as a clear
   "view-only" state, never a retry loop.

**Non-goals**

- Finer tiers (comment-only, upload-only, per-subfolder). Two roles first.
- Defending against a compromised **owner** device (accepted, T-GR-08).
- Undoing damage an **editor** already did. Editors stay trusted with content (T-GR-06), but
  are now attributable.
- Changing personal realms. Every personal-realm device is the same user.

## 3. Decisions

### D1. Two roles; owner devices are always writers

`VIEWER` (default) and `EDITOR` are member roles. The owner's devices (admitted through
`POST /sync/realm` and `/admit-device`) write by construction and carry no role.

### D2. The role lives in the master-signed capability

The capability gains a `role` field under a new domain tag, `tenshu-member-cap-v3`:
`LP(tag) ‖ LP(realmId) ‖ LP(memberAuthorityPub) ‖ LP(epoch) ‖ LP(issuedAt) ‖ LP(role)`.
It is signed by A1 as today. Both enforcement points read the role from here and nowhere else.
`Member.role` gains `VIEWER`/`EDITOR` for display only. The domain tag keeps v2/v3 unforgeably
distinct (the T-GR-07 rule).

### D3. Relay enforcement

At `/sync/realm-member/admit` the relay stores the role with the admission
(`member_admission.role`). On every realm-mutating route, a device that `isMemberDevice` and
whose admission role is `VIEWER` gets **403 `READ_ONLY_MEMBER`**:

- `/sync/push`
- `/sync/push-tombstone`
- `PUT /sync/content/{ref}`
- `/sync/content/declare`
- `/sync/content/reap`
- `POST /sync/grant/{address}`, where the realm is the grantor. Viewers can't invite.

Allowed for viewers: `/sync/changes` (including recording the viewer's signed tip),
`/sync/log`, content `GET`, grant pickup, and self-removal (§D6). The check sits beside the
T-GR-08 guard and reuses `isMemberDevice`.

### D4. Client enforcement — authored blocks

Every block emitted into a **shared** realm wraps its payload, **inside the encryption**, in
a new composite `AuthoredPayload(authorDeviceId, authorSignature, inner)`. `authorSignature` is
Ed25519 by the device's realm signing key over
`LP("tenshu/sync/authored-block/v1") ‖ syncBlockCanonical ‖ SHA-256(innerPayloadBytes)`, so it
binds the envelope fields (owner, block id, HLC, target) as well as the content.

- **The `SyncBlock` envelope does not change.** Only clients need the author; the relay never
  reads it.
- It is a distinct payload type, recovered from its wire tag like `FileContentManifestPayload`.
  Personal realms never emit it, so their blocks and stores are untouched.
- **No tombstone change either.** Shared realms don't pull `/sync/tombstones` (T-GR-08); a
  shared-realm delete reaches peers as a sync block, which this covers. If shared realms ever
  pull tombstones, they need authoring first (the T-GR-08 trap).

The signing key is the per-realm device key for members, and A4 for the owner's devices (the
key their relay registration already uses).

A receiver accepts a shared-realm block only if:

1. the signature verifies under `authorDeviceId`'s key; **and**
2. that key has a valid **writer credential** (§4.3): an owner-device delegation, or an editor
   chain `A1 → capability(role = EDITOR) → device binding`, not revoked. The chain is checked
   with the same `MembershipChainVerifier` the relay runs today.

Otherwise the block is dropped and counted in `EnvelopeRejections` under a new kind, `AUTHOR`.
The check runs right after the bound open and **before** `observe(hlc)` and before anything is
written: the same order `decryptAndParse` already uses. A shared-realm block whose payload is
not an `AuthoredPayload` counts as unauthored.

### D5. A viewer's own client refuses writes before queuing

A shared-realm write on a viewer device fails locally with a typed `ReadOnlyMemberError` before
it reaches the outbound queue, and Drive hides the edit affordances for that folder. The drain
treats a relay `403 READ_ONLY_MEMBER` as **terminal**: it drops the item and surfaces it once.
It never retries: a refused write that re-enqueues is the 401-storm shape in `tech-debt.md`.

### D6. Changing a role

- **Upgrade** (viewer → editor): the owner mints a new capability at `epoch + 1` with
  `role = EDITOR` and delivers it through the existing sealed grant mailbox. The member's
  devices re-run `/sync/realm-member/admit`. The relay keeps the highest epoch per authority.
- **Downgrade** (editor → viewer): the owner mints a `VIEWER` capability at `epoch + 1`. The
  higher epoch supersedes the editor one at the relay and at clients. **No Kr rotation**, since
  the member may still read. Removal stays as it is (revocation plus Kr rotation, GR D6).
- **Self-removal** stays allowed for viewers. It is the T-GR-08 "may revoke only itself" rule,
  and moves to the explicit revocation route when §5b ships.
- **Which blocks the old role still covers** (decided 2026-11-10, Q1 (b)). Signatures prove
  *who* wrote, not *when*: an HLC is author-chosen, so "authored before the downgrade" cannot be
  checked from the block alone. So the downgrade capability carries **`downgradedAtHlc`**, the
  owner's realm HLC when it was minted. Clients reject that member's editor-signed blocks above
  it. A downgraded editor can therefore backdate only up to what the owner had seen.
  ⚠ Accepted residual: an edit the editor made offline just before the downgrade, which the
  owner had not yet received, can be dropped.

### D7. The acceptance signal moves out of the realm (decided 2026-11-10, option (a))

A viewer cannot write its own `Member` row. The relay sees ciphertext, so it can't make an
exception for one entity type. So acceptance travels **back through the grant mailbox**, as a
sealed acceptance to the owner (the pairing-response shape), at a second address derived from
the same handshake secret (a distinct HKDF `info`). The **owner writes the member's `Member`
row**. That is better anyway: the member list becomes owner-attested rather than self-asserted.
Editors use the same path for uniformity.

Options considered:

- **(b) The relay admits one write at join.** Rejected: the relay can't see what is written, so
  a modified client gets one arbitrary write.
- **(c) No signal.** Rejected: the list could only show "invited".
- **(d) Ask the relay which grants have admitted devices.** Rejected: the relay could claim an
  acceptance that never happened.
- **(e) The relay keeps the A26-signed `MemberDeviceBinding` from `/sync/realm-member/admit`;
  the owner fetches and verifies it.** The serious alternative: unforgeable, and no new joiner
  protocol. Not chosen because it needs the relay to store bindings and can carry only signed
  fields, no display name.
- **(f) Over the contact service's mailbox.** No gain over (a); couples joining to contacts.

### D8. Existing grants — none to carry (decided 2026-11-10)

The maintainer confirmed there are **no real shares** to preserve. So there is no legacy path:

- v3 **replaces** v2 at every mint and verify site, with no dual-accept window. That is the
  T-GR-07 / F4 precedent, which also shipped with 0 `member_admission` rows.
- No v2-means-editor rule, and no data migration of grants, admissions or `Member` rows.
- Every grant is minted v3, `VIEWER` unless the owner picks "Can edit".

⚠ If real shares exist by the time RO-1 ships, this decision must be revisited. Check production
`member_admission` / `member_revocation` row counts first, as F4 did.

### D9. Drive shows who edited (decided 2026-11-10, Q3)

In a shared folder, Drive shows the **member** who made each change, in two places:

- **File rows:** the last editor ("Edited by Bob"), next to the existing modified time.
- **The file's Activity list** (`FileActivity`, the details screen, from `FileModification`):
  every entry gains its author. No separate per-file list, since it would show the same events
  twice.

Two rules:

- ⚠⚠ **The author comes from the verified `AuthoredPayload` (D4), never from
  `FileModification`'s device field.** That field is written by the writer, so in a shared
  folder it is a claim, not a fact. An entry whose stated device differs from the verified
  author is shown as unverified, never under someone else's name. Until RO-3 enforces, entries
  without a verified author show no author at all.
- **Resolve to the member, not the device.** `FileActivity.deviceLabel` resolves against the
  viewer's own `Device` rows, so another member's device falls back to a raw id. In a shared
  folder the label is the member's owner-written `Member` name (D7), reached through the
  device's `WriterCredential`. The device name is added only for the viewer's own devices.
  No raw-id fallback for anyone else's device (today's `deviceLabel` fallback).

Owner devices resolve to the owner's **share name** (D10). Personal realms are unchanged.

### D10. No device identity crosses a share (decided 2026-11-10)

A member learns the owner, and every other member, **only by their public share name**, never
by a device name or a device id that links to their personal realm.

- **Owner devices get per-realm device keys** in a shared realm, as members already do (GR D2).
  Today `SharedRealmBootstrapper.admitKnownDevices` registers them with their **personal** A4,
  so the device ids members see in `peerTips`, and would see in `WriterCredential`s and
  `authorDeviceId`, are the owner's personal device ids. With per-realm keys, every id inside a
  share is realm-local and unlinkable. The master signs a delegation over the per-realm key, as
  `/admit-device` does today. No shares exist, so there is nothing to migrate (D8).
  ⭐ This also closes the A12 residual "a device's single A4 authenticates to two `ownerId`
  streams ⇒ the relay can build a co-membership graph" for owners, which was the deferred
  mitigation there.
- **The owner's `Member` row carries the owner's share name.** It is the name shown to members
  (D9) and chosen when sharing. Today `ensureOwnerMemberRow` writes `displayName = ""`.
- **Shared-realm writes carry realm-local ids only.** Checked 2026-11-10:
  - *File history does not leak.* `FileModification` rows are keyed by personal path and stay in
    the personal realm. Shared folders hold `SharedFile` rows, which have no device field, and
    `ShareFolderIntoRealm` is their only writer today.
  - ⚠⚠ *The block HLC does leak.* `SyncObserver.generateSyncBlock` routes a shared-realm entity
    to its realm but stamps it with the **personal** `syncManager.hlcManager`, whose node id is
    the personal device id. It is clear on the envelope, seen by the relay and every member, for
    owners and members alike (threat model T-GR-05, amended). Fix: tick the realm's
    `HLCManager(realm.deviceId)`, which today exists only on the ingest side. ⚠ The HLC device id
    also drives own-echo detection and LWW tie-breaks, so the change needs its own tests. For
    owners it is only realm-local once they have per-realm keys (above).
- **Display:** never show another principal's device name or id. Their share name only.

## 4. Wire, schema and key material

### 4.1 Versions (House Rule 9)

- **Capability v3** is a relay wire change: `RELAY_PROTOCOL_VERSION` 1 → 2. Per D8 the relay
  stops accepting v2 capabilities, so a client still on protocol 1 cannot admit a member. That
  is acceptable while there are no shares; clients move in lockstep as usual.
- **`AuthoredPayload`** is a new payload type, inside the ciphertext. No existing composite
  changes shape, so there is no `SyncBlock` / `DeleteBlock` reshape, no relay change, and no
  personal-realm migration. It still lands with what House Rule 9 asks of a new type: the
  `SchemaShapeBaseline` regeneration, plus whatever version step
  `SchemaShapeBaselineEnforcementTest` names. Older clients cannot parse it, which is fine with
  no shares (D8) and lockstep clients.
  ⚠ Putting the author on the envelope instead was the first draft's choice. It would have
  reshaped `SyncBlock` / `DeleteBlock` for **every** realm, a House Rule 9 migration for personal
  data, to expose a field nothing on the relay reads.
- **`member_admission.role`**: a new column, no backfill (D8: no rows to carry). It is still a
  relay schema change, so it needs the relay's own table-version step.

### 4.2 Personal realms

Personal realms neither emit nor check author fields. All their devices are one user, and
their tombstones already verify under the personal key. This keeps the cost to shared realms.

### 4.3 Writer credentials reach clients

The relay registry is not client-visible, so a verifier needs the chain in-realm. Each writing
device publishes, once, a self-certifying **`WriterCredential`** record into the realm:

- for an owner device: the master-signed device delegation;
- for an editor device: the capability plus the device binding.

It is verified by its chain, not by who pushed it, so it needs no prior credential.

**One mechanism with §5b (decided 2026-11-10, Q4).** "One" means one **verifier** and one
**revocation source**, not one data structure:

- **`WriterCredential`s stay self-certifying records**: a set, order-free, needing no freshness.
- **Every check of them**, writer or device, consults **one revocation source**: §5b's
  **append-only set of A1-signed removal and downgrade records**, synced in the realm. Each is
  self-certifying and order-free; supersession is by epoch.

*Amended 2026-11-10:* the maintainer shrank §5b's R6. The linear, versioned, compare-and-swap
roster chain with fork detection is dropped for this append-only record set. Accepted trade-off:
no freshness guarantee, so a relay can withhold a revocation or downgrade record and a client
keeps trusting the old credential until it arrives. The chain could not fully prevent
withholding either.

Why not one structure: credentials and revocations stay separate sets, so adding an editor or a
device never touches the revocation records, and personal realms never carry a shared-realm-only
concern. Why not two mechanisms: two security-critical verifiers drift. The only gain would be
shipping independently.

## 5. Threat model impact

| Threat | Today | After RO-1 (relay) | After RO-3 (clients) |
|---|---|---|---|
| Viewer edits or deletes via modified client | possible (T-GR-06) | refused by the relay | ignored by clients too |
| Hostile relay injects a block into a shared realm | accepted if Kr-sealed | unchanged | dropped: no valid author |
| Relay + viewer collude | possible | possible | dropped |
| Editor corrupts content | possible, relay-attributable only | unchanged | still possible, **peer-attributable** |
| Owner device compromised | full compromise | unchanged (accepted) | unchanged (accepted) |
| Viewer evicts devices (T-GR-08) | stopgap guard | viewer can't push tombstones at all | — |

Amendments, landing with the code (House Rule 2):

- T-GR-06 narrows to editors.
- A12's attribution claim is corrected (done with this proposal).
- A new entry for the authored-block check, and its residual: an editor's pre-downgrade blocks,
  pending §8 Q1.

## 6. Phases

| Phase | What ships | Enforcement |
|---|---|---|
| **RO-0** | Sign-off on D6–D8 and Q1–Q4 | — |
| **RO-1** | D10 owner per-realm device keys + realm-local ids; capability v3 with role; `member_admission.role`; D3 relay refusals; D5 local refusal + terminal 403; D7 mailbox acceptance; Drive "Can view" default / "Can edit" toggle; relay protocol 1 → 2 | relay |
| **RO-2** | Authored blocks emitted by every writer; `WriterCredential` publication; client verification in **count-only** mode (`EnvelopeRejections.AUTHOR`, nothing dropped) | relay (clients measure) |
| **RO-3** | Clients **drop** unauthored or unauthorised shared-realm blocks; relay protocol min raised so every supported client emits | relay + clients |
| **RO-4** | Role change UI (D6); D9 "edited by" on file rows and in Activity (needs RO-2's verified authors) | relay + clients |
| *RO-5 (optional)* | Folder-wide activity feed (Q6) | — |

RO-1 alone delivers the user-visible feature with relay trust. RO-2 then RO-3 add the guarantee
that survives a hostile relay. The count-only phase exists so a missed emit path shows up as a
counter, not as silently dropped edits.

## 7. Audit — every path that writes into a shared realm from a member device

Each must be refused for viewers (D5) and signed for editors (D4) before RO-3 enforces:

- [ ] `SharedRealmGrantManager.accept` → own `Member` row (moves to D7)
- [ ] Drive file create / rename / move / delete inside a shared folder
- [ ] content `PUT` / `declare` / `reap` from a member (`SharedRealmContentBlobStoreFactory`)
- [ ] derived data minted on a member device for shared files: thumbnails
      (`drive-synced-thumbnails.md`), previews, inference labels. A viewer's device must keep
      these local, never push them into the realm.
- [ ] any observer that `saveData`s into a shared realm's store on `SYNC_INGEST`
- [ ] tips (`/sync/changes`): allowed; carry realm-local ids once D10 lands
- [ ] D10: every field in a shared realm that names a device (`FileModification`, `Member`,
      tips, credentials) holds a realm-local id, never a personal one

## 8. Open questions

- **Q1 — a downgraded editor's earlier blocks.** *Decided: (b)* (D6). Options were:
  - (a) keep every block signed under the editor credential (simple; a downgraded editor can
    backdate);
  - (b) the downgrade carries the owner's observed realm HLC, and clients reject editor blocks
    above it (bounded by what the owner had seen);
  - (c) blocks accepted before the downgrade arrived stay, and later arrivals are dropped
    (order-dependent across peers).

- **Q2 — legacy grants:** *decided* (D8). There are none.
- **Q3 — "edited by":** *decided* (D9).
- **Q6 — a folder-wide activity feed** ("Bob changed 3 files this week") in the shared folder's
  screen. A per-file list can't answer that. Same verified-author source as D9; optional RO-5.
- **Q4 — one mechanism with §5b:** *decided* (§4.3). One verifier and one revocation source, the
  append-only A1-signed records; credentials stay separate self-certifying records.
- **Q5 — where the author fields live:** *resolved in the draft* (D4): inside the encrypted
  payload as `AuthoredPayload`, not on the envelope.

## 9. Not changed

- Kr custody, rotation on removal, and the grant mailbox.
- Personal realms.
- The relay's realm-agnostic data plane: the role check is a gate on who may write, not a
  separate endpoint family.
