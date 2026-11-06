# Shared-realm membership grant — the cross-user join (GR)

**Status:** **GR-1…GR-7 COMPLETE** (2026-06-14; §6 phase map is canonical). ⚠ This header led
with "GR-1 + GR-2 landed" until 2026-08-11 and only corrected itself further down. ⚠ There is no
GR-5: the single "GR-5 hardening" phase was **split** (maintainer-confirmed 2026-06-14) into GR-6
(durable custody, remove-member UX, inbound poll) and GR-7 (Kr rotation) — so the gap in the
numbering is deliberate, not a lost phase. Review settled 2026-06-13; decisions D1–D7 confirmed
(⚠ D8 is resolved in §9.1, not §§7/10/11); review
questions Q-a…Q-e resolved and folded into the decisions below (resolution log in §7).
**GR-1 (crypto + schema) + GR-2 (relay) landed.** GR-1: capability/binding/revocation message
shapes + `MembershipChainVerifier` (`:sync-service-common`), `Member` + `MemberAuthorityKey`
(A26) schemas (`:identity-service`), grant-payload seal/unseal (`:contact-service-common`),
`deriveGrantAddress` (`:core-common`). GR-2: the `/sync/grant/*` sealed mailbox +
`/sync/realm-member/admit` (capability-chain admission) + `/sync/realm-member/revoke`
(master-signed revocation with device purge), durable grant/revocation/admission stores
(in-memory + SQLite), threat-model amendment (A26/A27, surface #12, §8.14 T-GR-01–06).
**GR-3 (ops + orchestration) landed + green** (`GrantSharedRealmSystemTest` round-trip; as-built
in §11 — owner mints A26 per §11.1, per-realm device-key JWT, revoke-only removal). **GR-4
(Drive UI) landed** (as-built §12 — grant prompt, Manage-access Members + grant action, D7,
accept-from-contact Option A; no poll op). **GR-6 (durable owner custody + remove-member UX +
inbound pending-grant poll/card) landed + green** (as-built §13). **GR-7 (Kr rotation on removal)
landed + green** (as-built §14): two PRs — a retained-keyring + trial-decrypt content subsystem
(`RetainedRealmKey` + `RealmContext.historicalDerivedKeys`), then `removeMember` rotates `Kr` →
`Kr′` and re-keys the remaining members via an empty-authority re-key drop on the existing
`/sync/grant/*` mailbox, **silently auto-applied** by the GR-6 poll (no card). The formerly-single
"GR-5 hardening" phase was **split** (maintainer-confirmed 2026-06-14): GR-6 shipped everything
*except* Kr rotation, GR-7 the rotation. **T-GR-02 is now Low** (was Medium colluding-relay-only —
the removed member is cryptographically cut off from post-rotation content). No relay/wire change in
GR-6 or GR-7.
This is the "cross-user join runtime" that
[`contact-invitation-handshake.md`](contact-invitation-handshake.md) §2 parked and every
Drive surface currently apologises for (*"sharing this folder with them is coming in an
upcoming update"*). Companion to [`sync-multi-realm-runtime.md`](sync-multi-realm-runtime.md)
(SR-M/SR-3, the substrate) and [`tenshu-rei.md`](tenshu-rei.md) Q25/Q26/Q30 (the decided
direction this doc implements).

---

## 1. Scope and story

Alice owns a shared realm backing a Drive folder. Bob is a **completed contact** (CI-4
handshake done — Alice holds Bob's `TenshuIdentity.publicKey`, his personal-realm A13, at
`UNVERIFIED` or `VERIFIED`). The grant:

1. Alice (Drive *Manage access* / the completion-prompt chain) grants Bob membership.
2. Bob is prompted, accepts, and his devices — all of them, present and future — sync the
   realm. The folder appears under *Shared with me*.
3. Alice's *Manage access* people list shows Bob as a real member (the §10 row goes
   `COMPLETED`-with-membership instead of contact-only).
4. Alice can remove Bob: relay access revoked immediately, Kr rotated as the
   cryptographic backstop (Q26).

**Non-goals (v1):** multi-owner realms, write-permission tiers (every member is
read-write; authorship is attributable via Q25 member attribution, not gated), MLS/TreeKEM
forward secrecy (Q31), push-assisted delivery, the relay long-poll, "invite from contacts"
bulk pickers.

## 2. What exists vs what's missing

The hard runtime work already landed (SR-M + SR-3b.4, PRs through 2026-06): multi-realm
client (`RealmRegistry`, per-realm emit/ingest/cursors, `sharedRealmRelayFactory`),
realm genesis (`SharedRealmBootstrapper`: Ed25519 realm master, random Kr/A12,
`ownerId = hash(masterPub)`, `POST /sync/realm`), same-user auto-join
(`SharedRealmJoinObserver` unwraps `SharedRealmMembership.wrappedMasterKey` with own A13),
delegation-gated relay device admission (`POST /sync/admit-device`,
`MasterDelegationVerifier`), A13 delivery to paired joiners (`PairingKeyBlob`), and the
sealed-box wrap primitives (`SyncCryptoManager.wrapKeyForIdentity`, threat-model A13 entry).
The CI track supplies the recipient identity (`TenshuIdentity.publicKey` + fingerprint +
verification level) and the relay-mailbox pattern (`InviteRouting`).

Five gaps, all cross-user:

| # | Gap | Why it doesn't fall out of the existing machinery |
|---|---|---|
| G1 | **Deliver the membership to Bob.** | `SharedRealmMembership` rows live in the *member's* personal realm. Alice can't write there; Bob can't read hers. Needs a relay mailbox hop. |
| G2 | **Authorize Bob's devices at the relay.** | `KnownDevicesStore` admission is per `(ownerId, deviceId)`; nothing admits a *foreign user's* devices, and Alice cannot enumerate them (and must not have to — Bob adds devices later without Alice online). |
| G3 | **Acceptance.** | Same-user auto-join is silent by design. A cross-user grant must not be: auto-joining foreign realms is an abuse/DoS surface and a UX lie. |
| G4 | **Member visibility.** | `getSharedRealmMemberships` returns *my* rows only. "Who's in this share?" needs the Q25 encrypted in-realm member list, which nothing writes yet. |
| G5 | **Removal.** | No revocation surface exists: no relay cap revocation, no Kr rotation/re-wrap path. |

## 3. Decisions

### D1. Relay auth = Q30 capability chain; the relay never holds a roster

Implements Q30 as decided. Alice's realm-master key signs a **membership capability**
binding a *member authority public key* (D2) to the realm; Bob's devices are admitted by
presenting the capability plus a member-authority-signed **device binding**. The relay
verifies the two-link chain against the `realm → masterPub` binding it already stores from
genesis, then registers the device in the existing `KnownDevicesStore` — after which the
JWT path is byte-identical to today.

Rejected: a relay membership table (the Q30-rejected roster — durable co-membership graph
server-side); Alice admitting Bob's devices directly via `/sync/admit-device` (requires
Alice to learn Bob's device list forever — a new cross-user sync surface, an availability
coupling, and a worse leak than the thing it replaces).

### D2. Per-(member, realm) **member authority** keypair — new asset A26

At accept time Bob mints a fresh Ed25519 **member authority** keypair for this realm,
persists the private key in his own personal realm (synced — every Bob device can bind
itself), and the capability binds its *public* key. Properties: no owner action when Bob
adds a device (the new device syncs the authority key via Bob's personal realm and
self-binds); relay-side unlinkability (the authority pub is realm-local — the relay cannot
correlate it with Bob's personal `ownerId`, satisfying Q30's per-realm-credential lever);
revocation is one key to revoke (D6).

Per-realm **device signing keys**: each Bob device generates a fresh Ed25519 per shared
realm; the binding covers `(deviceId', perRealmDevicePub)` where `deviceId'` is a fresh
realm-local device id (reusing the personal `deviceId` would re-link what the per-realm
key just unlinked). JWTs for the shared `ownerId` are signed with the per-realm device
key. Owner-side devices keep their existing A4-based admission (the owner is already
linkable as the realm's creator; no regression).

**Q-a resolved (2026-06-13): fresh A26, not Bob's personal master key.** Reusing the
personal master would save a keypair but hands the relay the same public key on Bob's
personal realm and on every shared realm he joins — exactly the cross-realm co-membership
graph (and the link back to his personal identity) that Q30's per-realm credentials exist
to prevent, and it widens compromise blast-radius from one realm to all of Bob's. A26 is
realm-local by construction, so the relay cannot correlate it; revocation and compromise
stay scoped to the single realm. Billing is owner-pays (Alice, `tenshu-rei.md` Q24), so
Bob's payment/personal identity never enters the grant — no reason to drag the personal
key in. The "new device must sync A26 before self-binding" cost is already handled here and
in D4 and is cheap.

### D3. Key delivery = wrap Kr once to the contact A13; grant rides a sealed mailbox

Exactly the handshake doc §8 sketch: `wrapKeyForIdentity(Kr, TenshuIdentity.publicKey)`.
One wrap per member at master-key level (the A13 asset entry's stated invariant) — Bob's
devices all hold his personal A13 (phrase-derived or pairing-delivered), so
`SharedRealmJoinObserver` works unchanged once the membership row lands in Bob's personal
realm. The grant payload travels as a **sealed blob on a relay grant mailbox**
(`/sync/grant/*`, the `InviteRouting` pattern): the relay sees ciphertext + routing id
only (House Rule #1). Sealed payload: `grantId, realmId(=sharedOwnerId), wrappedKr,
capability, advisory label (folder name), grantor fingerprint`.

Addressing: the mailbox is keyed by a **grant address** derived during the contact
handshake (HKDF of the handshake's shared context, computable by both sides, meaningless
to the relay) — the relay learns neither participant's personal `ownerId` from routing.
Bob's client polls his derivable addresses on Shared-screen init (the
`listPendingRedemptions` resume pattern). Delete-on-pickup is compatible with Bob having
multiple devices: whichever device polls first does the Accept and writes the membership
row into Bob's personal realm, which then syncs to his other devices via the normal
same-user auto-join — no per-device mailbox read needed. The only residual the relay
observes is pickup timing, identical to the existing invite mailbox.

### D4. Acceptance is explicit; the acceptance signal is the in-realm Member record

Bob's pickup creates a local **pending grant** (quarantined: grantor rendered by contact
petname + fingerprint match against `TenshuIdentity` — a grant from an unknown fingerprint
is displayed loudly as such). Accept → (a) mint A26, (b) write his own
`SharedRealmMembership(role=member)` to his personal realm (auto-join fires), (c) bind +
admit this device at the relay, (d) write his **Member record** (D5) into the shared
realm. Alice sees membership become real when Bob's Member record syncs in — no separate
ack channel, no extra relay state. Decline/ignore → mailbox record expires (TTL, 30 days).

**Q-b resolved (2026-06-13): explicit accept, not silent auto-join.** Alice's side is
always explicit (she performs the grant); Q-b is purely Bob's side. Silent auto-join would
let anyone Bob has ever completed a contact handshake with cause his devices to start
downloading a foreign realm's bytes without consent — storage consumption, and worse,
unwanted/abusive/illegal content landing on his device unbidden. Being someone's contact is
not consent to receive their files; limiting grants to completed contacts narrows *who* can
attempt it but does not close the consent gap. Explicit Accept stands (D4 as written).

### D5. Q25 member list: encrypted `Member` records in the realm itself — gap G4

New realm-resident schema `Member`: `memberId (= hash(memberAuthorityPub))`, identity
reference (self-chosen display name; pseudonymous-by-default per Q25, optional contact
fingerprint for members who hold each other as contacts), `role`, `joinedAt`. Encrypted
under Kr like all realm state — the relay never sees it. The owner writes their own
Member record at bootstrap (lazy backfill for pre-GR realms on first grant). Drive's
*Manage access* Members section reads these — the §10 unified list gets real member rows.

### D6. Removal = capability revocation + Kr rotation, in that order (Q26)

`RemoveMember`: (a) owner posts a master-signed **revocation** of Bob's member-authority
pub to the relay (admission stops; registered per-realm device keys for that authority are
purged — immediacy); (b) rotate Kr: generate fresh Kr', re-wrap to each remaining member's
A13 (O(members) sealed blobs — each member's `SharedRealmMembership` is replaced via a
re-grant mailbox drop), bump the realm epoch; (c) tombstone Bob's Member record.
Per Q26: no bulk re-encryption of unchanged files — every new write wraps under Kr'
(ADR 0017 invariant), which covers all future content. **Revoke-before-accept is the same
op** — the sealed grant exposed Kr to Bob's A13 the moment it was posted, so the
conservative path treats it as a removal.

**Q-d resolved (2026-06-13): reuse the existing realm-state epoch — no new field.** The
device-admission delegation already carries an `epoch` (`POST /sync/admit-device` verifies
`(deviceA4Pub ‖ deviceId ‖ epoch)`) and `GET /sync/realm-epoch` already exists, so the
epoch concept is already in the admission path — reuse means zero new wire and zero new
concept. A separate per-realm *key* epoch would be conceptually cleaner (it would move only
on rotation, not on every state change) but is a new field everything must track for a
benefit v1 doesn't need. Reuse is safe because `SharedRealmJoinObserver` keys
re-ingestion on the membership row being *replaced* (the re-grant mailbox drop), not on the
epoch value — so the coarse existing counter is sufficient and rotation still fires.

### D7. Drive linkage: grants get a real realm association — closes the rename-orphan debt

`ContactInvite.intentLabel` filtering (the documented v1 approximation in Manage access)
is superseded where a grant exists: the grant prompt resolves the folder → `realmId` at
grant time and the *Manage access* invited-rows filter switches to
`realmId`-of-the-folder for invites that carry one. The label stays advisory-only inside
the sealed ack (no behaviour rides it), as the handshake doc requires.

## 4. Protocol shapes (normative once review lands)

```
capability        = realmId ‖ memberAuthorityPub ‖ epoch ‖ issuedAt        — Ed25519(realm master)
deviceBinding     = realmId ‖ deviceId' ‖ perRealmDevicePub ‖ boundAt      — Ed25519(member authority)
revocation        = realmId ‖ memberAuthorityPub ‖ epoch' ‖ revokedAt      — Ed25519(realm master)
grant payload     = sealed-box(A13_Bob){ grantId, realmId, wrappedKr,
                      capability, label, grantorFingerprint }              — relay-opaque
```

Relay endpoints (sibling of `InviteRouting`/`RealmRouting`):

- `POST /sync/grant/{address}` — drop a sealed grant (JWT: grantor's personal identity —
  authed like invite-ack posts; body opaque).
- `GET  /sync/grant/{address}` — pick up + delete. **Address-as-bearer** (Q-c resolved
  2026-06-13): the address is an HKDF of the handshake's shared secret, derivable only by
  Alice and Bob, so it is not a public identifier — a third party cannot fetch the grant
  unless it already observed the handshake secret. Hardened with single-pickup + TTL; the
  payload is sealed to A13 regardless, so worst case on a leaked address is denial (someone
  deletes it before Bob reads), never disclosure. No proof-of-A13 challenge in v1.
- `POST /sync/realm-member/admit` — `{realmId(ownerId), capability, deviceBinding}`;
  verifies the chain + revocation list, registers `(ownerId, deviceId') → perRealmDevicePub`
  in `KnownDevicesStore`. Unauthenticated, delegation-gated — the `/sync/admit-device` shape.
- `POST /sync/realm-member/revoke` — master-signed revocation; appends to the realm's
  revocation list, purges matching device registrations.

Relay state added: per-realm revocation list (tiny, append-only) + the grant mailbox
records (TTL'd). No roster, no membership semantics, ciphertext only.

## 5. Threat-model amendment (LANDED with GR-2 per House Rule #2)

> **Landed** — `docs/sync/threat-model.md` now carries assets A26/A27, attack surface #12,
> and threat group §8.14 (T-GR-01–06). The sketch below is the design rationale; the
> normative text lives in the threat model. The T-GR-02 rotation residual is revisited when
> GR-3 lands Kr rotation. (Note: as built the GR surface is its own **#12**, not a growth of
> #10 — the relay endpoints are new, the co-membership residual stays on #10.)


New assets: **A26 Member Authority Keypair** (per member per realm; private key is
personal-realm state like A24; compromise admits attacker devices to that one realm until
revoked — it does *not* yield Kr by itself) and **A27 Membership Capability & Grant
Payload** (master-signed cap + the sealed grant blob; the blob contains wrapped Kr —
sealed to A13, so relay/inbox compromise yields nothing without A13). Surface #10 grows
the grant mailbox + admit/revoke endpoints. New threats, T-GR-01…: stolen/unexpired grant
blob replay (single-pickup + TTL + fingerprint display), relay serving stale registrations
post-revocation (revocation purge + Kr rotation backstop — the Q26 two-layer argument),
malicious member floods or corrupts realm state (attributable via Q25 records; removal +
rotation; accepted v1 residual — same trust as any write-capable member), relay
correlation of member identities (defeated structurally by D2's realm-local keys; residual:
traffic timing), grantor-side coercion to grant (SAS/fingerprint display at grant prompt —
the grant is a "second, key-fingerprint-specific owner action" per the handshake doc §2).

## 6. Phase map

| Phase | Contents | Verifiable by |
|---|---|---|
| **GR-1** ✅ | Crypto + schema: A26 keygen/storage, capability/binding/revocation message shapes + verifiers (`:sync-service-common`), `Member` realm schema, grant-payload seal/unseal (`:contact-service-common` sealed-box reuse), grant-address derivation. | Unit tests (round-trips, wrong-key rejections, chain verification). **Landed:** `MembershipGrant`/`MembershipChainVerifier` (`:sync-service-common`), `Member` + `MemberAuthorityKey` (`:identity-service`), `GrantPayloads` (`:contact-service-common`), `deriveGrantAddress` (`:core-common`) — 23 tests. |
| **GR-2** ✅ | Relay: grant mailbox + `realm-member/admit` + `revoke` routing, revocation list, registration purge. Threat-model amendment lands here. | Routing tests (the `AdmitDeviceRoutingTest` pattern); chain/revocation matrix. **Landed:** `MembershipGrantRouting` (`/sync/grant/*` + `/sync/realm-member/admit`+`revoke`), `GrantStore`/`MembershipStore` + SQLite (migration v8), `MembershipChainVerifier` generalised to an `Ed25519SignatureVerifier` SAM (relay verifies with `Ed25519Backend::verify`), threat-model A26/A27 + surface #12 + §8.14. 13 routing tests + 8 (refactored) chain tests. |
| **GR-3** ✅ | Ops + orchestration (revoke-only removal per D9-a; **as-built §11**): `GrantSharedRealmMembership` + `AcceptSharedRealmGrant` + `RemoveSharedRealmMember`, `MemberSelfBindObserver` (D9-b), `perRealmDeviceRelay` (per-realm device-key JWT), owner-mints-A26 (§11.1). **Landed + green:** `GrantSharedRealmSystemTest` (handshake → grant → accept → Member syncs to owner → remove). Kr rotation → GR-5; `getRealmMembers` + second-device test assertion outstanding. | System test on the dev relay (the SR-3b.4c precedent). |
| **GR-4** ✅ | Drive UI (**as-built §12**): grant prompt chained from the completion prompt (handshake §2 — "Add {petname} to '{label}'?"); *Manage access* Members section over `getRealmMembers` + grant-a-contact action; inbound **accept-from-contact** (Option A — pickup+accept by contact, **no** quarantined card/poll op); D7 realmId filter switch. | VM tests (drive-shared desktopTest); manual Android pass. |
| **GR-6** ✅ | Durable owner custody + removal UX + inbound poll (**as-built §13**, the former "GR-5 hardening" minus rotation): `OwnedRealmMaster` (durable realm A1) + `GrantedMembership` (owner A26-pub roster) Syncable rows → grant/remove work cross-session and on any owner device; `RemoveSharedRealmMember` now takes `memberId` (resolves A26-pub from the roster); Manage-access *Remove*; `PollPendingGrants` + `DeclinePendingGrant` + a `PendingGrant` quarantine store + *Shared with me* pending-grant cards (D4). | VM tests (drive-shared desktopTest); `GrantSharedRealmSystemTest` extended (poll-then-accept; remove via cross-session rehydration). |
| **GR-7** ✅ | **Kr rotation on removal** (**as-built §14**, two PRs, no relay/wire change): part 1 = the **retained-keyring + trial-decrypt** content subsystem (`RetainedRealmKey` Syncable + `RealmContext.historicalDerivedKeys` + decrypt fallback at both planes; removes the dead `ReKeyingWorker` scaffold); part 2 = `removeMember` mints Kr′, retires the old key, re-keys the remaining members via an empty-authority `GrantPayload` on the existing grant mailbox, **auto-applied silently** by the GR-6 poll (no card). Closes T-GR-02 (Medium → **Low**). | `RetainedKeyringTrialDecryptTest` (unit) + `SharedRealmKrRotationSystemTest` (3-party: remove one member, remaining member silently rotates to Kr′ and still reads C1+C2, removed member never gets C2). Green. |

GR-1/GR-2 are independently landable; GR-3 is the integration risk concentration (mirror
the SR-3b.4c experience: budget for a live-relay system test catching what stubs hide).

## 7. Review resolution log (2026-06-13)

All five review questions are resolved and folded into the decisions; the rationale lives
where the decision lives, recorded here for cross-session traceability.

- **Q-a → D2.** Fresh per-(member, realm) A26, *not* Bob's personal master key — relay
  unlinkability + scoped blast-radius; owner-pays billing keeps the personal key out.
- **Q-b → D4.** Explicit Accept, *not* silent auto-join — contact ≠ consent to receive
  bytes; the consent gap is a security decision, not just UX.
- **Q-c → §4 (`GET /sync/grant/{address}`) + D3.** Address-as-bearer (HKDF of the handshake
  secret, not public) + single-pickup + TTL; sealed to A13 so leak = denial, not
  disclosure. No proof-of-A13 challenge in v1.
- **Q-d → D6.** Reuse the existing realm-state epoch — already in the admission path;
  re-ingestion keys on the replaced membership row, not the epoch value, so the coarse
  counter suffices.
- **Q-e → GR-4 / D7.** *Shared with me* (membership-driven, mount optional) suffices for
  v1; GR-4 does **not** gate on the deferred "Add to my Drive" mount slice.

## 8. Implementation Notes for Claude Code

**Review is settled (D1–D7 confirmed, Q-a…Q-e resolved per §7), so GR-1 is ready to
start.** Begin with GR-1 (crypto + schema); it and GR-2 are independently landable, GR-3 is
the integration-risk concentration (budget a live-relay system test — the SR-3b.4c lesson).

One PR per phase; tests green before the next; threat-model amendment in the same PR as
GR-2 (relay surface) with the residuals updated again in GR-3 (rotation). If anything here
contradicts §§1–7, those win. Verify package paths against neighbours before creating
files. Key files to mirror: `SharedRealmBootstrapper.kt` / `SharedRealmDeviceAdmitter.kt` /
`SharedRealmJoinObserver.kt` (sync-service-proxy), `InviteRouting.kt` + `RealmRouting.kt`
(sync-relay-server), `MasterDelegationVerifier`, `InvitePayloads.kt`
(contact-service-common), `ContactHandshakeSystemTest` (system-test harness), and the
CI-1→CI-4 phase discipline in `contact-invitation-handshake.md` §12. The per-phase file
tables are pinned during GR-1 kickoff (verify the neighbour paths above against the tree
first — they may have moved since 2026-06-13).

## 9. GR-3 implementation design notes (2026-06-13)

GR-3 (ops + orchestration) surfaced four design questions during kickoff. Two are
**resolved by precedent** and recorded here; two are **scope decisions** flagged for the
maintainer (D8/D9 below) and confirmed in §10.

### 9.1 Grant address needs a persisted seed (closes the GR-1 gap)

`deriveGrantAddress(grantor, grantee, sharedSecret)` (GR-1) takes the handshake shared
secret — but the as-built CI layer **retains no shared secret**: the inviter persists only
`SHA-256(K_link)` (asset A25 / `ContactInvite.secretHash`), and the invitee's
`PendingRedemption` clears `K_link` within ≤72 h. The only material both sides keep
long-term is the two A13 *public* keys (public — they ride in invite links), which would
make the address a public identifier and defeat Q-c.

**Resolution (D8, confirmed):** derive a **grant seed during the handshake**, when both
sides transiently hold `K_link`, and persist it: `grantSeed = HKDF(K_link, info =
"tenshu/grant/seed/v1", 32 B)`, stored as a new `TenshuIdentity.grantSeed` field (each side
writes it onto its copy of the other's identity row). The grant address is then
`deriveGrantAddress(grantorA13, granteeA13, grantSeed)` — secret-derived, both-derivable,
faithful to Q-c. **Cost:** touches the shipped CI complete/redeem/ack paths + a new
`TenshuIdentity` field; **only contacts handshaked after this lands carry a seed** —
pre-GR-3 contacts are not grantable until re-handshaked (a documented v1 limit; the UI gates
the grant action on `grantSeed != ""`).

### 9.2 Realm-scoped `Member` writes — resolved by the `SharedFile` precedent

Writing a `Member` row *into* a shared realm is **not** a missing seam: a composite whose
`StringDataId` carries the `"$realmId::"` prefix routes into that realm via
`RealmRegistry.realmForDataId` (which keys off `key.substringBefore("::")`). `SharedFile`
already does exactly this — a `realmId` field + a `compositeKey = "$realmId::$fileId"` field
+ a `StringDataIdProviderSchema` keyed on the composite key.

**Resolution:** reshape GR-1's `Member` to mirror `SharedFile` — add a `realmId` field + a
`compositeKey(realmId, memberId) = "$realmId::$memberId"` field, and key its DataIdProvider
on the composite key (GR-1 keyed it on the bare `memberId`, which would have routed it to
the *personal* realm). Reads go through a **dedicated op** `getRealmMembers(realmId)`
(mirroring `getSharedRealmMemberships` / `listSharedFolder`), **not** the chain DSL — the
file model's standing decision. The owner writes their own `Member` row at grant time (lazy
backfill for pre-GR realms); the new member writes theirs at accept time — that write
syncing into the realm is the D4 acceptance signal.

### 9.3 Kr rotation on removal — design + scope decision (D9-a)

Removal has two layers (Q26/D6): (a) the relay revocation + device purge (GR-2, immediate —
the removed member's devices can no longer pull the shared stream), and (b) Kr rotation as
the cryptographic backstop against a relay that *withholds* the revocation. Rotation =
generate Kr′, re-wrap it to each *remaining* member's A13 (the owner resolves each member's
A13 from its contact `TenshuIdentity` rows via `Member.contactFingerprint`), deliver via a
**re-grant mailbox drop** (the same `/sync/grant/*` channel, sealed to each A13), bump the
realm epoch, and tombstone the removed `Member` row.

**Open scope question (D9-a):** rotation is O(members) sealed re-grants + per-member
re-ingest, and the *practical* access cutoff is already the GR-2 relay revocation. The
threat model (T-GR-02) explicitly documents the "GR-2-only interim" residual as **Medium**.
So: implement full rotation in GR-3, or land removal = revocation + purge + tombstone now
and **defer Kr rotation to GR-5** (removal UX + hardening), closing T-GR-02 there? — see §10.

### 9.4 Cross-device member propagation — scope decision (D9-b)

When the member accepts on device 1, their `MemberAuthorityKey` (A26) + `SharedRealmMembership`
sync to device 2 via their personal realm. Device 2 must then **self-bind**: mint a fresh
per-realm device key, sign a `MemberDeviceBinding` with the synced A26 private key, and call
`/sync/realm-member/admit` — otherwise device 2's JWT for the shared `ownerId` is rejected and
it cannot sync the shared realm at all. This needs a new observer (a `SharedRealmJoinObserver`
sibling that fires on a synced `MemberAuthorityKey`).

**Open scope question (D9-b):** include the auto-self-bind observer in GR-3 (matches the phase
map + the "second-device join" system-test assertion), or ship single-device-accept for v1 and
add the observer as a fast-follow? — see §10.

## 10. GR-3 scope resolutions (2026-06-13)

- **D9-a → defer Kr rotation to GR-5.** GR-3's `RemoveSharedRealmMember` posts the
  master-signed revocation (the relay stops admitting + purges the member's devices, the
  practical access cutoff) and tombstones the member's `Member` row. **Kr rotation/re-wrap +
  epoch bump moves to GR-5**, where it closes the T-GR-02 residual the threat model already
  documents as a "Medium" GR-2/GR-3 interim. Rationale: rotation is the most
  integration-risky logic and the relay revocation is the immediate effect; isolating
  rotation keeps the GR-3 PR reviewable. (Deviates from the phase map, which listed rotation
  in GR-3 — the map is updated accordingly.)
- **D9-b → include the cross-device self-bind observer in GR-3.** A `SharedRealmJoinObserver`
  sibling fires on a synced `MemberAuthorityKey`: the second device mints its own per-realm
  device key, signs a `MemberDeviceBinding` with the synced A26, and calls
  `/sync/realm-member/admit`. This is required for a member's second device to sync the
  shared realm at all, and the GR-3 system test asserts the second-device join.

**Resulting GR-3 surface:** grant-seed plumbing (§9.1), reshaped `Member` (§9.2) +
`getRealmMembers`, relay client methods (landed), `GrantSharedRealmMembership` +
`AcceptSharedRealmGrant` + `RemoveSharedRealmMember` (revoke-only) ops, the member self-bind
observer (§9.4, D9-b), and a live-relay Alice→Bob round-trip system test (grant → accept →
sync; remove → revoke; second-device join). Kr rotation is GR-5.

## 11. GR-3 as-built (2026-06-13)

GR-3 ops + orchestration + the live-relay system test **landed and pass green**
(`GrantSharedRealmSystemTest`: handshake → grant → accept → the new member's `Member` row
syncs back to the owner → remove). Decisions taken during implementation, beyond §§9–10:

### 11.1 A26 custody — **owner mints**, ships the private key in the sealed grant (Option A)

GR-1's `GrantPayload` carries a **pre-signed** capability binding the member-authority
*public* key, so the owner must know — and therefore **mint** — the A26 keypair at grant
time. The owner signs the capability with the realm master, and ships the A26 **private**
key inside the payload (a new `GrantPayload.memberAuthorityPrivateKey` field; the whole
payload is already sealed to the grantee's A13, so the private key never travels in clear).
The grantee persists it as `MemberAuthorityKey` so every device self-binds; the owner
discards the private half and keeps only the public key (returned by
`GrantSharedRealmMembership`) to revoke.

**This deviates from D2's "Bob mints A26 at accept" prose** — it is the only shape
consistent with the landed wire (a one-directional mailbox + a pre-signed capability;
"Bob mints" would need a reverse channel GR-2 never built and the owner online twice). It
is **no security regression**: the owner holds the realm master and could admit any device
to the realm regardless, so transiently minting a realm-scoped A26 grants it nothing new;
all the properties D2 actually buys — A26 freshness, realm-locality, relay unlinkability,
one-key revocation — hold. The owner's A26-private custody is milliseconds in memory at
grant time. **D2's prose is hereby superseded by this section.** (Threat-model A26 note
updated in the same PR.)

### 11.2 Per-realm device keys are wired through the JWT (closes the unlinkability gap)

The accepting/self-binding device admits itself with a **fresh per-realm Ed25519 device key
+ realm-local device id**, and the realm's relay JWT is signed with that key via the new
`perRealmDeviceRelay` (registered explicitly on the granted `RealmContext`, bypassing the
personal-A4 `sharedRealmRelayFactory`). Before GR-3 the shared-realm JWT always used the
personal A4 — for a cross-user member that would let the relay correlate their shared-realm
presence with their personal `ownerId`, the exact linkage A26/Q30 exist to prevent (threat
model surface #12). The system test proves it end-to-end: the member's `Member` row only
reaches the owner because the relay accepts the per-realm-key JWT.

### 11.3 The capability rides on `MemberAuthorityKey` (cross-device self-bind)

`MemberAuthorityKey` gained a `capability` blob field (`epoch ‖ issuedAt ‖ signature`,
master-signed, secret-free) so a member's **second** device — which syncs only the
authority key + membership — can reconstruct the capability it needs to admit itself. The
`MemberSelfBindObserver` (D9-b) fires on `SYNC_INGEST` of those rows (the accepting device
already bound itself with a `USER`-origin write) and self-binds with its own fresh
per-realm key.

### 11.4 Latent `:core` bug fixed en route

`createEntity` read **every** schema field with `getField`, which throws on optional fields
— so once GR-3's first optional entity field (`TenshuIdentity.grantSeed`, landed by step 2)
existed, every `TenshuIdentity` entity materialisation (sync, relation maintainer) would
have crashed. `createEntity` now reads optional fields via `getOptionalField`.

### 11.5 Known limits / follow-ups

- **Owner re-grant/remove is same-session** — the realm master private key lives only in
  the in-memory `RealmContext` (a pre-existing SR-3b limit; the grant/remove ops fail loudly
  if it's unavailable). Durable owner-side persistence of the master key **and** of minted
  A26 public keys (so the UI removes by member, not by raw key) is **GR-5**.
- **Accept is pickup-and-accept by grantor fingerprint** — the quarantined-pending list +
  poll-all-contacts UI is **GR-4**; decline = simply not accepting (mailbox TTL-expires).
- The shared-realm **content plane** still signs with the personal A4 — harmless in GR-3
  (no cross-user file content flows), but cross-user file read must adopt per-realm keys
  when it lands.
- `getRealmMembers` + the second-device self-bind **system-test assertion** are the
  remaining §10-surface items.

## 12. GR-4 as-built (Drive UI, 2026-06-13)

GR-4 is **Drive UI only** (`:drive-shared` + DI) over the GR-3 ops — no protocol change.
All four scope items landed; verified by `:drive-shared:desktopTest` (the full suite green),
Android compile + on-device pass outstanding (the standing Drive convention).

### 12.1 Data layer — two thin additions

- **`MembershipRepository`** (`drive-shared/.../data/MembershipRepository.kt`) — `grant` /
  `accept` / `members` over `SyncService.grantSharedRealmMembership` /
  `acceptSharedRealmGrant` + `IdentityService.getRealmMembers`. It sits at the seam between
  `ContactsRepository` (who) and `FilesRepository` (which folder backs the realm), so it's
  its own repository rather than bloating either. **Remove is deliberately omitted** —
  removal-by-member needs the durable A26-pub persistence that is GR-5. `grant` discards the
  returned A26 pub (GR-5 stashes it).
- **`ContactsRepository.connectedContacts()`** (+ `ContactModel`) — a one-shot join of the
  `persons` × `tenshuIdentities` relations exposing `personId`, `displayName`, the A13
  `fingerprint`, and **`grantable`** (a linked identity carries a `grantSeed`). Both the
  grant and the accept-address derivation need that seed, so `grantable` gates both pickers.

### 12.2 Grant prompt chained from completion (handshake §2)

`ContactPromptViewModel`, on an accepted `SHARE_FOLDER` invite, builds the §2 offer **in the
same dialog** ("Add {petname} to '{label}'?"): it resolves the realm by matching the invite's
`intentLabel` against the owner's `sharedFolders()`, the `personId`+`grantable` by matching the
invite's raw `claimedFingerprint` against `connectedContacts()`, and grants on confirm. Any
gap (not a folder invite, label backs no owned realm, contact not yet grantable, lookup fails)
yields **no offer** and the handshake just completes. Owner grant is **same-session** — the
realm master key is in-memory; a failure surfaces inline and the user can skip.

### 12.3 Manage access — Members + grant action + D7

When the folder backs a shared realm the owner holds, `ManageAccessViewModel` captures the
`sharedRealmId`, loads `getRealmMembers` (owner row excluded — it's the pinned "You — Owner")
and the grantable contacts, and exposes a *Grant access to a contact* picker. **D7:** a
`COMPLETED` invite whose `claimedFingerprint` matches a member's `contactFingerprint` is
dropped from the invite rows — the realm-keyed member row supersedes the label-matched invite
(access is membership-driven, not completed-invite-driven). Other invite statuses (the
handshake lifecycle) always show.

### 12.4 Inbound — accept-from-contact (Option A; **no poll op**)

The §2.3/D4 *quarantined pending-grant card* needs a proxy poll op + a local `PendingGrantStore`
(the relay `GET /sync/grant/{address}` is single-pickup — a non-destructive "peek + quarantine"
is a protocol change). **Decision (maintainer, 2026-06-13):** the poll op's only real value is
*in-app discovery without out-of-band knowledge of who granted you*, which is marginal because a
grant follows an in-person handshake where the two parties are together. So GR-4 keeps
accept-by-fingerprint: *Shared with me* offers *Accept a shared folder* → pick a connected
contact → `acceptSharedRealmGrant(fingerprint)` (the tap **is** the explicit D4 consent). A
no-blob failure (the common case) shows a friendly "nothing shared from {name} yet" rather than
a raw error — at the cost of masking genuine transport errors, accepted for v1. The discovered
card + poll op remain a clean **GR-5** add if remote granting proves common.

### 12.5 Known limits / follow-ups (✅ resolved by GR-6 except where noted)

- **Remove-member UX** — ✅ GR-6 (§13.2): durable A26-pub roster (`GrantedMembership`) + the
  `RemoveSharedRealmMember(realmId, memberId)` shape + the Manage-access *Remove* affordance.
- **Owner grant is same-session** — ✅ GR-6 (§13.1): the realm master (A1) is persisted in
  `OwnedRealmMaster` and rehydrated on demand, so grant/remove work cross-session and on any
  owner device.
- **Accept masks real errors** as "nothing shared yet" (§12.4) — partly addressed by the GR-6
  poll/quarantine path (§13.3); the accept-from-contact picker still folds no-blob into a
  friendly notice.
- **The grant offer / pickers gate on `grantSeed`** — unchanged (the documented D8 limit; only
  contacts handshaked after GR-3 are grantable).

## 13. GR-6 as-built (durable custody + removal UX + inbound poll, 2026-06-14)

GR-6 realises the non-rotation half of the former "GR-5 hardening" phase and is green
(`drive-shared:desktopTest`; `GrantSharedRealmSystemTest` extended). **No relay or wire-protocol
change** — everything is new personal-realm Syncable schemas + proxy ops + Drive UI. Kr rotation
is split out to **GR-7** (§13.4).

### 13.1 Durable owner custody — closes "grant/remove is same-session"

Two new `Syncable(Eager)` schemas in the owner's **own personal realm** (`:identity-service`,
mirroring `MemberAuthorityKey`): **`OwnedRealmMaster`** (`sharedRealmId` → realm master Ed25519
keypair, A1) and **`GrantedMembership`** (`memberId` → realm + the minted A26 public key).
`SharedRealmBootstrapper` writes `OwnedRealmMaster` at genesis; `grant` writes a
`GrantedMembership` row. `SharedRealmGrantManager.ownerMasterKeyPair(realmId)` returns the
in-memory `RealmContext.masterKeyPair` when present, else **rehydrates** it from
`OwnedRealmMaster` and (re-)registers the realm so the owner's `Member`-row writes route into it.
Because both rows sync to the owner's paired devices, *any* owner device can grant/remove — a
strict improvement over the SR-3b same-session-only limit. Threat-model: A1/A26/A27 notes + the
GR-6 sub-amendment (House Rule #1 holds — all of it is personal-realm ciphertext to the relay).

### 13.2 Remove-member by `memberId`

`RemoveSharedRealmMember` now takes the in-realm **`memberId`** (what the roster/UI holds), not
the raw A26-pub hex: the op resolves `memberId → A26pub` from the `GrantedMembership` roster, then
posts the (existing, GR-2) master-signed revocation, tombstones the `Member` row, and drops the
roster row. Drive: `MembershipRepository.remove`, `ManageAccessViewModel.onRemoveMember`, and a
*Remove* affordance on each member row. Revoke-only (the GR-3 relay purge is the immediate
cutoff); Kr rotation is GR-7.

### 13.3 Inbound pending-grant poll + quarantine card (D4)

The grant mailbox `GET` is single-pickup, so discovery must persist the blob: `PollPendingGrants`
sweeps every grantable contact's derivable address, and for each waiting grant stores the
**still-sealed** blob as a `PendingGrant` Syncable row (quarantined — D4 explicit accept).
`AcceptSharedRealmGrant` now **prefers a stored `PendingGrant`** (the poll already consumed the
relay copy) and falls back to a live pickup for the Option-A "accept from contact" path;
`DeclinePendingGrant` tombstones the row. Drive: a *Shared with me* card per pending grant
(grantor petname + Accept/Decline), polled on screen init. No relay change — the poll is the
existing anonymous mailbox `GET`, iterated client-side over known contacts (so the quarantine is
inherently from a known fingerprint).

### 13.4 Kr rotation deferred to GR-7 — the content-keyring gap (✅ resolved by GR-7, §14)

Removal stops at the relay revocation + tombstone. **Kr rotation is *not* shipped here** because
the content layer has no per-block key-versioning: `RealmContext` holds a single `derivedKeys`,
`SyncProcessor`/`FileContentManifestApplier` decrypt with the *current* realm key only, and
`SyncBlock` carries no key-epoch — so generating Kr′ and re-registering would make every block
written under the old Kr undecryptable. ADR-0017's "no bulk re-encryption of unchanged files"
invariant implicitly assumes a **retained keyring** (try current key, then historical keys on
AEAD failure) that does not exist yet. GR-7 builds that keyring (RealmContext holds prior Kr's,
synced to remaining members; decrypt falls back across them) and then re-wraps Kr′ to remaining
members via a re-grant drop, closing T-GR-02. Maintainer-confirmed split, 2026-06-14.

## 14. GR-7 as-built (Kr rotation on removal, 2026-06-14)

GR-7 closes §13.4 in two PRs. **No relay or wire-protocol change** — the keyring is new
personal-realm Syncable state, and the re-key rides the existing `/sync/grant/*` mailbox.
Green: `RetainedKeyringTrialDecryptTest` (unit) + `SharedRealmKrRotationSystemTest` (3-party,
live in-process relay, real crypto).

### 14.1 Part 1 — retained keyring + trial-decrypt (the primitive)

- **`RetainedRealmKey`** — `Syncable(Eager)` (`:identity-service`), personal realm, keyed
  `realmId::keyId`, holding a *retired* `Kr` **wrapped to the member's A13** (House Rule #1: only
  the wrapped form leaves the device). `RETAINED_REALM_KEYS` relation + `RetainedRealmKeyRelationMaintainer`.
- **`RealmContext.historicalDerivedKeys`** (default empty — the personal realm never rotates)
  carries the retired keys' `DerivedKeys`.
- **Trial-decrypt** at both planes: `SyncProcessor.decryptAndParse` (identity key) and
  `FileContentManifestApplier` (volume key) try the current key first, then each retired key. A
  candidate wins only if decrypt **and** parse succeed (so a non-throwing wrong-key result is
  rejected). Empty keyring ⇒ byte-identical to the pre-GR-7 single-key path.
- `buildRealmKeyring(realmId, A13)` reads the `RetainedRealmKey` rows + unwraps + derives;
  called at every shared-realm `RealmContext` construction site (accept, owner re-register, join +
  self-bind observers), and threaded through the per-realm processor factory.
- Removed the dead simulated `ReKeyingWorker` / `rotateVolumeKey` / `historicalVolumeKeys` scaffold
  (personal-realm, re-encryption-based — the opposite approach; never wired).

### 14.2 Part 2 — rotation on removal + silent re-key delivery

- **`removeMember`** appends, after the (existing) revocation + tombstones: mint `Kr′`
  (`generateSharedRealmMasterKey`), `adoptNewRealmKey` on the owner side (retire the current key
  into the owner's keyring, re-wrap `Kr′` to the owner's own membership, re-register), then re-key
  every **remaining** member. Remaining members are enumerated from the in-realm `Member` rows
  (the removed member's row is already tombstoned out of the MEMBERS relation), resolved to an A13
  via `Member.contactFingerprint` → contact directory and to an A26-pub via the `GrantedMembership`
  roster.
- **Re-key = a `GrantPayload` with an empty `memberAuthorityPrivateKey`** (the member already holds
  their A26 + is already admitted, so a re-key ships no authority key and triggers no re-admission —
  only the content key changes). Re-signed capability for the existing A26-pub; same `sealGrant` +
  `deriveGrantAddress` + `dropGrant`. Re-grant drops are **best-effort** — an unreachable member
  keeps the old key until the next removal/manual re-grant; it is never *wrongly* left able to read
  new content (only remaining members ever receive `Kr′`).
- **Silent apply (maintainer decision, 2026-06-14):** `pollPendingGrants` unseals each pickup; if
  we already hold a `SharedRealmMembership` for `payload.realmId` it is a **re-key** for a realm we
  already belong to — adopt `Kr′` inline (`applyRekey` → `adoptNewRealmKey`, `masterKeyPair = null`),
  **no quarantine card**. A brand-new realm still quarantines (D4). The gate (already a member) is
  what makes auto-apply safe: a re-key cannot inject a new realm, and the blob is still sealed to
  our A13 + addressed via the handshake secret. `accept` carries the same branch defensively.
- **`adoptNewRealmKey`** (shared by owner + member) retires the current `Kr` into a `RetainedRealmKey`
  row, swaps the `SharedRealmMembership.wrappedMasterKey` to `Kr′`, and re-registers the realm with
  the new `DerivedKeys` + the (now larger) keyring. Idempotent (re-applying the same key is a no-op).
  Note: re-registration resets the realm's pull cursor — the member re-pulls the stream once and
  trial-decrypts pre-rotation blocks via the keyring (correct, mildly wasteful; acceptable v1).

### 14.3 Known limits / follow-ups

⚠ **Verification status.** GR-7 is green on `desktopTest` + the system tests named in §14;
Android compiles. The **on-device manual pass is still outstanding** — §12 records it for the
GR-4 UI, and no later phase has claimed one either, so treat it as owed for the track rather
than for any single phase.

- **Stale-pending resurrection blocking a re-key (fixed 2026-06-24).** `PendingGrant` is a *synced*
  row, so the pre-accept quarantine card a member consumes on `accept` (local `deleteData` →
  tombstone) can be **resurrected** by a sync pull that re-applies the create after the delete — a
  create→delete ordering race (the same family as the `UploadFileImplementation` tombstone-retention
  note). A resurrected card sits under the grantor's fingerprint and made `pollPendingGrants` *skip
  the mailbox pickup* (`one pending per contact`, D4), so a waiting **re-key never got collected** —
  the rotation's silent-apply guarantee broke and a phantom *Shared with me* card surfaced. This was
  an intermittent failure of `SharedRealmKrRotationSystemTest` under load (the test's 100 ms
  scheduler amplifies the window; reproduced ~30% under CPU saturation, isolated by an instrumented
  probe). **Fix:** `pollPendingGrants` now treats an existing pending whose realm we have *already
  joined* as stale (`isStalePending` unseals the stored card → `isExistingMember`) — it drops the
  card and falls through to the pickup, so the re-key is collected and silently applied. A genuine
  unaccepted pending (realm not joined) still blocks as before; no relay/wire/threat-model change.
  The deeper synced-row create→delete resurrection remains a separate, broader concern.
- **Cross-device re-key propagation:** a member's *second* device that already has the realm
  registered won't re-register on a synced re-key within a session (the join/self-bind observers
  short-circuit on `isRegistered`). The keyring + new membership do sync, so the next registration
  (restart / first-time this session) picks up `Kr′` + the retired key — the same class as the
  pre-existing "shared realms aren't re-registered mid-session" note. Not blocking the cutoff
  (only *remaining* members ever get `Kr′`).
- **A member granted but not yet accepted at rotation time** holds a pending *original* grant
  carrying the stale `Kr`; they get `Kr′` only on the next re-key. Out of scope for v1.
- File-content trial-decrypt relies on production AEAD throwing on a wrong key (it does); the
  stub-crypto edge isn't representative, so it's covered by the system test (real crypto), not the
  unit test.

## References

- [`sync-multi-realm-runtime.md`](sync-multi-realm-runtime.md) — SR-M substrate; §3.1 cross-device prerequisites this doc extends cross-user.
- [`contact-invitation-handshake.md`](contact-invitation-handshake.md) §2, §8 — the grant split + the three-step sketch this doc expands.
- [`contact-service-identity-model.md`](contact-service-identity-model.md) — identity refs, verification levels, quarantine rules the grant prompt inherits.
- [`tenshu-rei.md`](tenshu-rei.md) Q25 (member list), Q26 (removal = rotation), Q30 (capability tokens + per-realm credentials), Q53 (no bearer-key membership links).
- [`drive-shared-realm-file-model.md`](drive-shared-realm-file-model.md) — the file model the member finally gets to read.
- [`../sync/threat-model.md`](../sync/threat-model.md) — A12/A13 entries, surface #10/#11, §8.13.
