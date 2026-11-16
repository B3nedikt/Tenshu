# Shared-realm membership records — a signed member list (MR)

**Status:** **MR-1, MR-2, RO D7 and MR-3 built** (2026-11-11, as-built §8–§11). The "invited"
state is built (§12). Open: entitlement-gated acceptance drops (§10). Option D of the R6c shared-realm
comparison in [`sync-entity-identity.md`](sync-entity-identity.md) §5b. ⭐ This is the concrete form of
the **revocation source** the read-only-members proposal names in its §4.3 ("one verifier and one
revocation source": §5b's append-only signed records, synced in the realm) — and it adopts RO's decided
D2, D6, D7, D8 and D10 rather than restating them.

"RO Dn" below refers to [`shared-realm-read-only-members.md`](shared-realm-read-only-members.md).

**Depends on:** [`shared-realm-membership-grant.md`](shared-realm-membership-grant.md) (GR — the
capability, A26, `Member` rows, removal + `Kr` rotation) and R6a/R6b in `sync-entity-identity.md` §5b
(the signed-record pattern this copies: self-certifying, append-only, re-emitted).

---

## 1. Problem

A shared realm's member list is the set of `Member` rows in the realm (GR D5). Every row is an
ordinary synced block under `Kr`, so **anyone holding `Kr` — every member — can write any row**:

1. **Resurrection.** Last-writer-wins lets a member push a removed member's row back.
2. **Removal does not propagate.** `SharedRealmGrantManager.removeMember` calls `deleteData` on the
   `Member` row, but a delete emits no tombstone unless an op emits one (`SyncObserver.onDataDeleted`
   is a no-op), and shared-realm pulls apply no `DeleteBlock`s anyway (`pullSharedRealms` →
   `processSyncBlock` only). ⇒ The row disappears **on the removing device only**: the owner's other
   devices and every other member keep listing the removed member. (Verified in code 2026-11-11.)
3. **Forgery.** A member can write a row for someone never granted, rename another member's row
   (impersonation in *Manage access*), or set its own `role` to `owner` (RO §1: `Member.role` "can
   never be the enforcement source").

⚠ What this does **not** protect, as in R6a: blocks are not signed per writer, so records cannot stop a
member's *writes* — RO D3 (relay) and RO D4 (authored blocks) do that. Records decide **who is in, with
which role, as clients see it** — and they are what RO D4's `WriterCredential` check consults for
revocation and supersession.

## 2. Facts the design rests on

- The realm master already signs two statements about a member, each domain-separated
  (`MembershipGrant.kt`): the **capability** `tenshu-member-cap-v2` (`realmId ‖ memberAuthorityPub ‖
  epoch ‖ issuedAt`; RO D2 makes it `-v3` with `role`) and the **revocation**
  `tenshu-member-revoke-v2` (`… ‖ revokedAt`). `MembershipChainVerifier.verifyCapability` / `isRevoked`
  already check both.
- A revocation is **permanent per authority key**: `isRevoked` is an exact match on
  `memberAuthorityPub`, no un-revoke. A re-invited person gets a **new** A26, so a new `memberId`.
  ⇒ No re-admission kind is needed (contrast R6b).
- `memberId = sha256(memberAuthorityPub)`; the owner's row uses `sha256(masterPub)`
  (`ensureOwnerMemberRow`'s caller).
- The shared realm's `ownerId = ownerIdForMasterPublicKey(masterPub)` (`SharedRealmBootstrapper`), so a
  master-signed record is self-certifying with no stored key — R6a's A1 rule holds verbatim.
- The owner **discards** the A26 private half after minting it (GR §11.1).

## 3. Design

### 3.1 `MembershipRecord` — the authority

New Identity type, realm-resident (the **shared** realm's store, under `Kr`), `Syncable(Eager)`, not
chain-readable, keyed `kind|memberId|epoch-or-at`, append-only by key. It carries one master-signed
statement **byte-for-byte**, plus its signature:

| Kind | Signed bytes | Written by an owner device at |
|---|---|---|
| `capability` | the membership capability (v2 today; **v3 with `role` from RO-1**, RO D2) | grant, and every role change (RO D6: a new capability at `epoch + 1`) |
| `revocation` | the membership revocation | `removeMember` |

⭐ **One signature, two planes** (the R6a rule): both kinds are the exact bytes the relay already
verifies at `/sync/realm-member/admit` and `/revoke`, so client and relay cannot disagree about who is in
or with which role. No new signed message family.

A record is valid iff `ownerIdForMasterPublicKey(signerPub) == realmId` and the signature verifies under
its kind's tag. ⚠ No member can mint one: members never hold the master.

⚠ **Reserved for delegation (§7.2):** the record carries an `authorityChain` field — a list of
delegation certificates, leaf → root — that is **always empty** in MR. The verifier accepts only the
empty chain (signer = master) until delegation is designed. It costs one field now and saves a
`MembershipRecord` reshape (an HR 9 bump) later.

### 3.2 The list, derived

```
members(realm) = { owner } ∪ { m | some valid capability names m, and no valid revocation names m }
role(owner)    = owner                            — memberId == sha256(masterPub); implicit, RO D1
role(m)        = role of m's valid capability with the highest epoch   (RO D6; v2 ⇒ no role yet)
```

Order-free: a revocation is forever, and role supersession is by epoch, both decidable from the set
alone. The RO D6 `downgradedAtHlc` rides inside the v3 capability, so it is in the record too.

⚠ **Check before RO-1 builds D6's epoch.** Every mint site passes `CAP_EPOCH = 0` today, deliberately
(`MembershipGrant.kt` KDoc; `admission-revocation-names-the-capability.md` §7b.1: a live epoch let a
lagging client mint *below* a revocation). Revocation stays an exact key match, so an epoch used only to
order **capabilities** does not re-open that — but RO D6 should say so explicitly.

### 3.3 `Member` rows become display-only, owner-written and master-signed

RO D7 moves acceptance to the grant mailbox and has the **owner write every `Member` row**. MR adds
the one thing that makes that enforceable: the row is **master-signed** (new tag
`tenshu-member-row-v2` over `realmId ‖ memberId ‖ displayName ‖ contactFingerprint ‖ joinedAt ‖ signedAt`), so a
member cannot rename another (problem 3). The row's `role` field is ignored — role is §3.2's. The
owner's own row carries its share name (RO D10).

⚠ Until RO D7 lands the member still writes its own row, which the master cannot sign. Interim: rows are
unsigned and only §3.4's membership filter applies (it stops resurrection and phantom members, not
renames).

### 3.4 Enforcement — at the store, so readers do not change (the R6a rule)

In the **shared** realm's `SyncProcessor` (`realmKeys != null`), verified against that realm's id:

- `MembershipRecord`: stored only if valid **and** no valid record holds its key (no LWW overwrite);
  never deleted by a tombstone, never kept out by one (R6b's three rules).
- `Member` row: ⚠ *corrected 2026-11-11 (MR-2):* dropped at ingest **only if its member is revoked**.
  "Stored only if in `members(realm)`" was wrong: a row and its capability record are separate blocks, and
  a row arriving first would be dropped for good. A revocation is permanent, so dropping on it is
  order-safe; a never-granted row is instead **hidden at read time** (`GetRealmMembers`) and appears the
  moment its capability arrives. (After RO D7, its signature must also verify.)
- A `revocation` arriving takes the revoked member's row out of the store and `MEMBERS`
  (`DeviceRecordObserver`'s sibling). ⇒ **Problem 2 is fixed by the record**, not by a tombstone.
- RO D4's `WriterCredential` check reads revocation and role from §3.2 — the "one verifier" of RO §4.3.

`GetRealmMembers` and *Manage access* keep reading `MEMBERS`. A `capability` with no `Member` row yet is
an **invitation not yet accepted**, visible on every owner device (today only the granting device knows,
via the personal-realm `GrantedMembership`).

### 3.5 Re-emit

An owner device re-emits the valid record set at start-up **and after every `Kr` rotation**: relay body
GC (as R6b), and a member granted after a rotation holds only the current `Kr`, so records sealed under an
older key would be unreadable to it.

### 3.6 Who can do what

| Actor | Can | Cannot |
|---|---|---|
| Owner device (holds master) | grant, change role, revoke, write rows, re-emit | — (root of trust, RO D1 / T-GR-08) |
| Member (holds `Kr`) | read the list | add, remove, rename, or change any role — its writes there are dropped |
| Removed member | nothing new (relay cut + `Kr′`) | reappear in anyone's list |
| Relay | withhold records (accepted no-freshness residual, decision C) | forge or alter one |

## 4. Schema and versioning (HR 9)

- New `MembershipRecord` composite (+ the `Member` signature fields with RO D7) ⇒ an Identity version
  bump with a `SchemaMigration` step. ⚠ Identity is already at v2 on this branch (R6a) and **unshipped**:
  if MR lands before the client deploy, fold it into that bump rather than adding v3.
- No legacy path (RO D8, no production shares): a member with no `capability` record is not listed. The
  migration step can be empty.
- `tenshu-member-row-v2` joins `MembershipMessageConfusionTest` / `CrossFamilySignatureTest`.
- **No relay change in MR.** ⚠ But RO §4.1 plans capability v3 as `RELAY_PROTOCOL_VERSION` **1 → 2** —
  and 2 is now taken by R2b (`RELAY_PROTOCOL_TOMBSTONE_IS_DATA_VERSION`). RO-1 needs **3**.

## 5. Phases — and where they sit against RO's

| Phase | Scope | Gate | Order |
|---|---|---|---|
| **MR-1** | `MembershipRecord` + `MembershipRecordVerifier` (`:sync-service-common`, over `MembershipChainVerifier`); owner writes `capability` / `revocation`; re-emit (§3.5) | verifier tests (wrong signer, cross-tag, permanent revocation, epoch supersession); system test: a removal reaches the owner's second device and another member | before RO-1, standalone |
| **MR-2** | Enforcement in the shared processor (§3.4); revoked-member row removal; "invited" state | resurrection, phantom-row and overwrite tests mirroring `DeviceRecordEnforcementTest` | before RO-1, standalone |
| **MR-3** | Master-signed `Member` rows (§3.3) | rename-another-member test | **with RO-1's D7** |
| **MR-4** | The "Invited" state in **SwiftUI** (iOS Drive's Manage access) and the **web** Drive app: the same `GetRealmInvitations` read Drive's Compose screen uses, named via contacts, with Remove. Not built with MR-1–MR-3, by maintainer decision (2026-11-12). | an iOS view-model test plus `scripts/ios-smoke.sh --build-only drive`; a web component test | next phase of this track |
| — | v3 capabilities flow into the same records unchanged; RO-2/RO-3's credential check consults §3.2 | RO's own gates | RO-1 … RO-3 |

## 6. Open questions for review

1. **MR-1/MR-2 now, ahead of RO-1?** Recommended yes: they fix problems 1 and 2 today and are what RO-3's
   verifier needs anyway; nothing in them changes when v3 arrives.
2. **"Invited" state in *Manage access* (§3.4)** in MR-2? Recommended yes; it costs one read.
3. **A removed member, for the remaining members:** disappear, or show as "Removed" like Manage devices
   (R6c)? Recommended: disappear for members; owners see "Removed" from the records.
4. **Fold into the unshipped Identity v2 (§4)?** Recommended yes, if MR-1 lands before the client deploy.
5. **Reserve `authorityChain` (§3.1, §7.2)?** Recommended yes: one always-empty field now against a
   reshape later.

## 7. Layering — app roles above the realm, and delegation

*Added 2026-11-11, from a maintainer discussion about a future messenger built on shared realms.*

### 7.1 Three layers, each narrowing the one below

| Layer | Answers | Enforced by |
|---|---|---|
| Realm access | who can read the realm at all | encryption (holding `Kr`) and relay device admission |
| Realm role (viewer / editor, RO) | who can write into the realm at all | the relay (RO D3) **and** honest clients (RO D4) |
| App roles (e.g. a messenger's admin, moderator, "can post in #general") | what a writer may do inside one app | **clients only**, by verifying signed app records |

The platform owns the first two; an app defines the third. Rules for anyone designing an app layer:

- ⚠⚠ **An app role needs authored blocks (RO D4).** The relay sees ciphertext (HR 1), so it can never
  enforce an app role; the role means "clients ignore this action unless its author held the role", and
  that is only checkable when every write carries a verified author. Before RO D4, any editor can pose
  as anyone, and an app role is decoration.
- ⚠ **It can never widen the layer below.** An app "moderator" who is a realm viewer still cannot post:
  the relay refuses the write. App participants who act are realm editors, and the app narrows from
  there.
- ⭐ **Reuse the record primitive, do not reinvent it.** R6 and MR built a generic shape: signed,
  self-certifying, append-only records with one verifier, fixed tie and order rules, a tombstone
  exemption and a re-emit. "Alice made Bob an admin of this group" is that shape. Offering it to apps
  (a typed record kind + a verifier hook) is cheaper and safer than each app hand-rolling the
  security-critical part, which is where these schemes usually break.
- Not a role concern but it comes first for a messenger: `Kr` rotates only on removal. A messenger will
  want a fresh key per membership change and protection for past messages (MLS, RFC 9420, is the
  reference). That is a realm-layer change, separate from this doc.

### 7.2 Delegation — later, on this design, with two things fixed now

Today only the master signs, so only the owner's devices can grant, change roles or remove. A group
where admins appoint admins needs **delegation**: the master signs "Alice may grant up to role X", and
Alice signs grants under it. This can be built later, on this design, because the precedent exists:
`AdmissionChainVerifier` already verifies N-link chains rooted at the master for delegated device
admission (`delegated-device-admission-authority.md`; leaf → root, depth ≤ `MAX_ADMIT_DEPTH`). A
delegated `MembershipRecord` would carry its chain in `authorityChain` and verify the same way.

What to fix **now**, because changing it later costs a reshape or a security decision under pressure:

1. **The field** (§3.1): `authorityChain`, empty. Without it, delegation reshapes the record type.
2. **The rule that a delegated statement can never exceed its delegation**, written into the verifier's
   contract from MR-1 even though no chain exists yet: a delegate can grant at most its own scope, can
   never revoke or downgrade the owner, and can never mint a delegation wider than its own.

What can wait for the delegation design itself, because no stored shape depends on it:

- **What happens to a delegate's grants when the delegate is removed** — do they die with it or stand?
  "Die with it" is simpler to reason about but cascades; "stand" needs the grant to be judged at the
  stamp it was made, which is the RO D6 `downgradedAtHlc` problem again (an author-chosen time).
  ⚠ Read `admission-revocation-names-the-capability.md` first: its lesson is that a security decision
  must not ride a counter that is not monotonic with the events it separates.
- Whether the relay verifies chains for membership as it does for admission (relay protocol bump), or
  delegated grants stay client-side until a delegate's grantee first admits a device.

## 8. As built — MR-1 (2026-11-11)

Decisions taken with it (the §6 recommendations): MR-1 ahead of RO-1; folded into the unshipped
Identity v2 (the step stays empty; baselines gained only the new type and relation); `authorityChain`
reserved.

- **`MembershipRecord`** (`identity-service`): key `"$realmId::$kind|$memberId|$epoch|$at"` — the
  `realmId::` prefix routes it into the shared realm, as `Member`'s does; fields `realmId`, `kind`
  (`capability` / `revocation`), `memberAuthorityPublicKey`, `epoch`, `at` (`issuedAt` / `revokedAt`),
  `signerPublicKey`, `signature`, `authorityChain` (always empty). Relation `MEMBERSHIP_RECORDS`, not
  chain-readable.
- **`MembershipRecordVerifier`** (`:sync-service-common`): `isValid` (master signer by
  `ownerIdForMasterPublicKey`, statement for this realm, signature under the statement's own tag via
  `MembershipChainVerifier`, empty chain), `members` (current capability per non-revoked member: highest
  epoch, then latest `issuedAt`), `revokedMembers`. The owner is implicit and never in `members`.
- **Writers** (`SharedRealmGrantManager`, via `MembershipRecords`): `grant` after the mailbox drop,
  `removeMember` after the relay accepted the revocation, and each re-signed capability in
  `rotateAndRegrant`. ⭐ The revocation record — not the local `deleteData` — is what tells other devices.
- **Re-emit**: after every rotation (so records travel under `Kr′`), and when an owner device registers
  the realm for the session (`SharedRealmJoinObserver` after its self-admission; the grant manager's
  rehydrate path). ⚠ There is no process-start hook for shared realms — each session registers one on
  demand — so "start-up" means that registration.
- **Tests**: `MembershipRecordVerifierTest` (11, real Ed25519: wrong signer, permanent revocation,
  cross-tag, other realm, chain refused, order-free supersession, same bytes as the relay's check);
  `SharedRealmMembershipRecordsSystemTest` — over the dev relay, Alice removes Bob and both **Alice's
  paired second device** and **Charlie** hold a verified revocation and no longer count Bob.
- ⚠ **Not yet** (MR-2): no ingest guard — a member can still push a garbage record row at a valid
  record's key (last-writer-wins), and `Member` rows are not filtered; `GetRealmMembers` is unchanged.

## 9. As built — MR-2 (2026-11-11)

- **Record ingest** (`SyncProcessor`, any processor — records route by key prefix): a `MembershipRecord`
  is stored only if `MembershipRecords.acceptsIncoming` — valid, and no valid record at its key (LWW off);
  a tombstone never deletes one and never keeps one out. Re-emits and echoes of a record a device already
  holds are therefore dropped, harmlessly (logged as such).
- **Revoked members**: an incoming `Member` row for a revoked member is dropped at ingest;
  `MembershipRecordObserver` takes a stored row out of the store and `MEMBERS` when a verified revocation
  arrives. Order-safe both ways, because a revocation is permanent.
- **Never-granted rows**: `GetRealmMembers` (identity proxy, which now depends on `:sync-service-common`
  like the contact/health/routine/file proxies) lists a row only if the realm's records vouch for it —
  granted and unrevoked, or the owner (proven by any valid record's signer, or before any grant by this
  device's own `OwnedRealmMaster` whose key binds to the realm id). Drive's *Manage access* and Control's
  shares read through it unchanged.
- **Tests**: `MembershipRecordEnforcementTest` (8, shared-realm processor; 4 fail with the guards
  removed), `GetRealmMembersVouchedTest` (5), and `SharedRealmMembershipRecordsSystemTest` extended: after
  the removal, no device lists Bob. ⚠ That test timed out once at 30 s inside an eight-test shared-realm
  run, then passed 4/4 alone; its wait is now 45 s with a diagnostic naming the lagging device.
- ⚠ **Not built**: the "invited" state (§3.4, open question 2) — a grant with no `Member` row is still
  invisible in *Manage access*; and `Member` rows are still unsigned, so a member can rename another
  (MR-3, with RO D7).

## 10. As built — RO D7, the owner writes members' rows (2026-11-11)

Built ahead of MR-3 (maintainer, 2026-11-11), so MR-3 can be master-signed only from the start: with a
two-signer interim rule, the member-signed half would have had to be removed again once D7 landed — left
in, an editor could overwrite the owner-written row by last-writer-wins.

- **Member side** (`SharedRealmGrantManager.accept`): no longer writes its own `Member` row. It signs an
  acceptance with its A26 (`MembershipAcceptance`, tag `tenshu-member-accept-v1`) over
  `realmId ‖ authorityPub ‖ displayName ‖ contactFingerprint ‖ acceptedAt`, seals it to the grantor's A13
  (`sealAcceptance`, info `tenshu/grant/acceptance-payload/v1`) and queues it in the durable
  **`OutboundAcceptance`** outbox (personal realm, synced), drained right away and on every grant poll.
- **The address** (`deriveAcceptanceAddress`): the grant address's handshake context under info
  `tenshu/grant/acceptance/v1`, and ⚠⚠ **bound to the realm id** — the mailbox keeps one blob per address,
  last write wins, so two acceptances to the same grantor would otherwise overwrite each other. Pinned
  vector in `GrantAddressTest`. **No relay change**: it rides `POST`/`GET /sync/grant/{address}`.
- **Owner side**: `grant` writes an **`AwaitedAcceptance`** (personal realm, synced: member, realm, grantee
  fingerprint). Every grant poll (`pollPendingGrants`, which Control runs every 30 s) picks up each awaited
  acceptance and honours it only if `isGenuineAcceptance` — right realm and member, the A26 the owner kept,
  from the contact it granted to, signed by that A26 — and the member is not revoked; then the owner
  writes the `Member` row and retires the wait. `removeMember` retires it too (revoke-before-accept).
- **Tests**: `AcceptanceVerificationTest` (6), `GrantAddressTest` (+4: pinned vector, per-realm,
  never a grant address), `MembershipMessageConfusionTest` (+1: acceptance vs binding). The shared-realm
  system tests now have the owner poll; all seven pass.
- **Rotation and acceptance** (PR review): `removeMember` processes waiting acceptances before rotating, so
  an accepted member without a row yet is re-keyed with the rest; and processing an acceptance for a realm
  that rotated since the grant drops that member the current `Kr` (closes GR §14.3's stale-`Kr` limit).
  `SharedRealmKrRotationSystemTest` pins both orders. One pickup per acceptance address routes the
  acceptance to the grant it names (a double grant before acceptance).
- ⚠ **The member appears only once an owner device polls.** Until then the grant is "invited" — still not
  shown in *Manage access* (the open "invited" state, §6 Q2).
- ⚠⚠ **Entitlement enforcement gates the acceptance — by decision (maintainer, 2026-11-12).**
  `POST /sync/grant/{address}` requires a live entitlement for the dropper's personal realm when
  `ENTITLEMENT_ENFORCE` is on (off by default). There is **no exemption**: "subscription" includes the
  free tier, so registering is low friction. What is built:
  - `RestSyncRelay.dropGrant` tags a `402` with `ENTITLEMENT_REJECTION_MARKER`.
  - The member's `OutboundAcceptance` is **kept** and marked `needsAccount`; it is retried on every poll
    and leaves only on a successful drop.
  - `GetPendingAcceptances` (identity) lists the outbox, so Drive's *Shared with me* says "Create a free
    account to join this folder", with *Open Control* where accounts are made. The notice clears by
    itself once the acceptance goes through.
  - Never silent, never dropped, never "Invited" for good:
    `GrantSharedRealmSystemTest.anAcceptanceRefusedForWantOfAnAccount_staysQueued_andGoesThroughOnceThereIsOne`
    and `SharedScreenNeedsAccountTest`.
  - ⚠ The notice shows *after* the tap. The relay is the only judge of an entitlement, and the accept's own
    local steps (registration, admission) are ungated and kept, so nothing is lost by accepting first.

## 11. As built — MR-3, master-signed `Member` rows (2026-11-11)

- **`Member` reshaped**: optional `signerPublicKey` + `signature` appended. ⚠ Folded into the unshipped
  Identity v2, so the v1 → v2 step is **no longer empty**: it maps a frozen `MEMBER_AT_V1` snapshot
  (`IdentityMigrationSnapshots.kt` — never edit it) onto the live shape, and the new optional fields arrive
  absent. `MemberV1MigrationTest` runs the registered step on a v1 row. The shape baseline re-pins
  `Member` at v2 — legitimate only because v2 has not shipped; once it has, a reshape needs v3.
- **`MemberRowAttestation`** (`:sync-service-common`, tag `tenshu-member-row-v2`) over
  `realmId ‖ memberId ‖ displayName ‖ contactFingerprint ‖ role ‖ joinedAt ‖ signedAt`, signed by the realm master;
  valid iff the signer binds to the realm id. Self-certifying, so checked on arrival in any order.
  ⚠⚠ **Ordered by `signedAt`, not by the block** (2026-11-14): any realm-key holder can re-push an older genuine
  row as a fresh block, so ingest drops a valid row older than the valid row stored at its key
  (`isOlderThanStored`; equal = the same signing, a re-emit, let through). `-v1` (no `signedAt`) never shipped —
  `Member` v2 is unreleased, which is why the field was added in place rather than in a v3.
- **Writers**: only the owner (RO D7) — `ensureOwnerMemberRow` (which also re-signs an unsigned pre-MR-3
  owner row) and the acceptance pickup, both via `signedMemberRow`.
- **Enforcement**: the shared processor drops an unsigned or forged `Member` row at ingest (so a member can
  neither rename another nor overwrite a signed row by LWW); `GetRealmMembers` lists only rows that are
  vouched for by the records **and** signed.
- **Tests**: `MembershipRecordEnforcementTest` (+4: signed lands, unsigned / other-key dropped, a rename
  never overwrites), `GetRealmMembersVouchedTest` (+1), `MembershipMessageConfusionTest` (+1: a row
  signature is never a capability or revocation), `MemberV1MigrationTest`. The shared-realm system tests
  pass unchanged.
- ⚠ The owner can still edit a row (it signs) — that is the design: rows are owner-attested.

## 12. As built — the "invited" state in *Manage access* (2026-11-11)

- **`GetRealmInvitations(realmId)`** (identity op, owner-side): the owner's `AwaitedAcceptance` rows for the
  realm — granted, not yet accepted. Empty on a member's device (the rows live in the owner's personal realm).
- **Drive *Manage access***: opening it polls first (so an acceptance that just arrived is picked up), then
  lists members and invitations. An invitation shows the contact's name and "Invited — waiting for them to
  accept", with *Remove* (revoke-before-accept, GR D6). An invitation whose member already has a row is
  shown once, as the member. Member rows now also fall back to the contact's name, since a member's own
  display name is empty by default (GR D5) — else "Bob — Invited" would turn into "Unnamed — Member".
- **Tests**: `ManageAccessViewModelTest` (+3), `GrantSharedRealmSystemTest` (Bob is an invitation before
  accepting, not after).
- ⚠ **Not built**: the SwiftUI *Manage access* (`tenshu-sdk-ios-drive` returns an empty list) and the web
  SDK.

## References

- `sync-entity-identity.md` §5b — R6a/R6b/R6c as-built (the record pattern).
- `shared-realm-membership-grant.md` — D1, D2, D4–D6, §11.1 (A26 custody), §14 (Kr rotation).
- RO ([`shared-realm-read-only-members.md`](shared-realm-read-only-members.md)) — D1–D4, D6–D8, D10, §4.1, §4.3.
- `admission-revocation-names-the-capability.md` §7b.1 — why `CAP_EPOCH` is pinned; and its ordering
  lesson for §7.2.
- `delegated-device-admission-authority.md` §4.4, `AdmissionChainVerifier` — the chain precedent (§7.2).
- `docs/sync/threat-model.md` — A26/A27, T-GR-02, T-GR-06, T-GR-08, T-K-02 (signed device records).
