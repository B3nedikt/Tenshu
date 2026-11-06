# Contact service & identity model — people, keyrings, and the Manage-access picker

**Status:** v0.1 — decisions in §2–§7 agreed with the maintainer (2026-06-11 session).
⚠ This said "not yet implemented" until 2026-08-11, contradicting its own §8: the v1 entities
(`Person`, `TenshuIdentity`, `ContactInvite`) shipped with **CI-1…CI-4** (2026-06-11/12) — see
`contact-invitation-handshake.md` §10. What remains unbuilt is v2 (`ContactSource` / vCard /
the Contacts app) and v3 (Rei `ATTESTED`); `contacts-app.md` is the live successor. Resolves the identity-directory open question of
[`drive-shared-realms.md`](drive-shared-realms.md) §2 (**no directory**) and concretises
[`tenshu-rei.md`](tenshu-rei.md) Q53's "Contacts association" lean. Feeds the *Contacts*
milestone app ([`milestones.md`](../milestones.md) §Contacts) — this doc designs the **data
service** the app will sit on, written ahead of the app because Drive's *Manage access*
surface depends on it.

---

## 1. The problem

`FileActionsViewModel.onManageAccessClicked()` (and `onShareClicked()`) are `TODO()` — the
Drive *Manage access* sheet crashes. The intended UI is a people picker that shares a
file/folder via the shared-realm mechanism (Mode B,
[`drive-shared-realm-file-model.md`](drive-shared-realm-file-model.md)).

The blocking dependency is **not** a contacts *app*. Per Q53's crux: adding a member to a
shared realm means wrapping the realm master key `Kr` to the invitee's realm-identity X25519
public key — so the owner must hold that public key *before* the add. A contact list of
names/emails alone unblocks nothing; an email is just a label unless something maps it to a
key. The minimal "people service" is therefore a store of **identity references**
(public key + display name) plus a way to **acquire** them.

Prior art split:

- **Centralized drives** (Google Drive, Dropbox, OneDrive): email is the universal
  identifier and the server *is* the directory — type an email, the server resolves it to an
  account and enforces ACLs; suggestions are mined from prior shares. None of this transfers:
  an E2EE relay that maps email → pubkey becomes a key-distribution trust root (key
  substitution reads everything).
- **E2EE comparables**: Proton Drive wraps a share passphrase to each member's address key —
  structurally identical to our `SharedRealmMembership.wrappedMasterKey` — and accepts a
  server-side email→key directory mitigated by key transparency. Signal uses phone numbers
  plus SGX-enclave private contact discovery (naive hashing is invertible at phone-number
  keyspace) and safety numbers for out-of-band verification — the same primitive as our SAS.

## 2. Decision — no directory, handshake-only identity acquisition

**The relay never maps human identifiers to keys.** Identity references are acquired
exclusively through a mutual **invitation handshake** (link or QR — the cross-user sibling of
device pairing, per Q53's invitation-link lean; bearer-key links remain rejected).

Rationale:

- A directory's only unique capability is contacting a *stranger* — someone you have no
  channel to. Consumer sharing always has an outside channel (you know the person; the invite
  link rides WhatsApp/email/QR-in-person). The genuine stranger-discovery case is
  Creator/Creations public identities, which is better served by Rei attestation (§7) than by
  a relay handle map.
- Spam is prevented by construction: both sides must act for a handshake to complete.
- Avoided costs: key-transparency infrastructure, a who-looks-up-whom metadata leak (A6
  class), and a directory that is itself an attack target — the "multi-week threat-model
  conversation" `drive-shared-realms.md` §7 warned about simply never happens.

The handshake's output is a `TenshuIdentity` record (§4): the peer's public key + their
self-asserted display name. **Contacts accrete as a side effect of sharing** — after one
handshake, future shares reuse the stored identity ("invite from contacts") without
re-exchanging keys.

## 3. Structural precedent — the Android aggregate model

Android's `ContactsContract` solves the matching problem we will face in v2 with a
three-tier shape: **raw contacts (one per source) aggregate into a contact**, with suggested
joins, manual join/split, and no data loss on either. We adopt it directly:

| Tenshu entity | ContactsContract analog | Role |
|---|---|---|
| `Person` | `Contacts` (aggregate) | stable id + the user-owned display label |
| `ContactSource` | `RawContacts` + `Data` | one imported record per source, lossless |
| `TenshuIdentity` | (no analog — the crypto half) | one keyring entry: pubkey + verification state |

`TenshuIdentity` and `ContactSource` each carry a `personId` link; per-person collections are
derived by relation maintainers (the `PlacementRelationMaintainer` pattern), so link/unlink
is a field write, not array surgery, and merge/split stays cheap.

## 4. Data model

All three composites route to the **personal** realm (your contact graph is your private
data; relay sees ciphertext, House Rule #1). Standard five-module `:contact-service` split.

### 4.1 `Person` — thin aggregate, petname-only

```kotlin
interface Person : Composite {
    val personId: TString get() = getField(Field.PERSON_ID)       // random, the key
    val displayName: TString get() = getField(Field.DISPLAY_NAME) // the petname
    // PersonDataIdProvider = stringDataId on PERSON_ID; Syncable(Eager)
}
```

**Decision (maintainer, 2026-06-11): `Person` carries no vCard fields.** Earlier drafts gave
the aggregate `emails`/`phones`/name-parts; that duplicates what `ContactSource` holds
losslessly, and schema fields are non-nullable — a vCard-shaped `Person` forces fake-empty
fields for the most common record. Consequences of the thin shape:

1. **v1 creates exactly this.** A handshake-only person is `Person{id, name}` + one
   `TenshuIdentity` — the minimal record *is* the complete record.
2. **`displayName` is the petname slot** — user-controlled (defaulted from the handshake's
   self-asserted name or a source's `FN`, but owned by the user). The one field on the
   aggregate is the spoof-resistant label; self-asserted names stay quarantined on the
   identity. Trust layering for free.
3. **No aggregation sync conflicts** — concurrent imports/merges contend on a one-field record.

The detail screen and the picker render the **read-through union** of linked `ContactSource`
data (parsed from `rawVcard`) + linked `TenshuIdentity` rows.

### 4.2 `TenshuIdentity` — one keyring entry

```kotlin
interface TenshuIdentity : Composite {
    val fingerprint: TString get() = getField(Field.FINGERPRINT)             // hash(publicKey), the key
    val publicKey: TByteArray get() = getField(Field.PUBLIC_KEY)             // realm-identity X25519 pub
    val personId: TString get() = getField(Field.PERSON_ID)                  // aggregate link; "" = unlinked
    val selfAssertedName: TString get() = getField(Field.SELF_ASSERTED_NAME) // what THEY claimed at handshake
    val verification: TString get() = getField(Field.VERIFICATION)           // UNVERIFIED | VERIFIED | ATTESTED
    val verifiedAt: TTimestamp get() = getField(Field.VERIFIED_AT)
    val source: TString get() = getField(Field.SOURCE)                       // INVITE_LINK | QR_SAS | REI
    // TenshuIdentityDataIdProvider = stringDataId on FINGERPRINT; Syncable(Eager)
}
```

**A keyring per person, not a key.** One person may legitimately hold multiple identity keys:
key rotation over time; future per-context **pairwise identities** (a fresh key per handshake
makes share-partners unable to correlate a person across contexts — not committed, but the
model keeps the door open); Rei attestation keys (§7). Note what does *not* force a keyring:
multiple shared realms with the same person all wrap to the same identity key today.

**Verification lives on the key, never on the person.** The UI checkmark on a `Person` is
*derived*: ≥1 linked identity at `VERIFIED` or above. Linking a key to a contact card must
never transfer trust (§6). The ladder:

- `UNVERIFIED` — TOFU: acquired via handshake, not independently confirmed.
- `VERIFIED` — SAS/QR confirmed out of band (same gesture family as pairing).
- `ATTESTED` — carried by a Tenshu Rei attestation chain (§7).

Realm associations are **not** stored on the identity — "which realms does Alice access" is
derivable from memberships wrapped to her keys; no duplicated state to keep consistent.

### 4.3 `ContactSource` — lossless raw record

```kotlin
interface ContactSource : Composite {
    val sourceKey: TString get() = getField(Field.SOURCE_KEY) // "android:$lookupKey" | "vcard:$uid" | "manual:$uuid"
    val personId: TString get() = getField(Field.PERSON_ID)   // aggregate link
    val rawVcard: TString get() = getField(Field.RAW_VCARD)   // lossless vCard 3.0 payload
    // keyed on SOURCE_KEY; Syncable(Eager)
}
```

`rawVcard` is the source of truth for human-facing detail. Round-trips imports losslessly
(including `X-` extension labels) and makes export trivial.

## 5. vCard interop — target 3.0, custom KMP codec

Both platform contact stores are "vCard-shaped but not vCard-stored": iOS `CNContact` is
explicitly vCard-aligned (`CNContactVCardSerialization` round-trips vCard **3.0**; labeled
multi-values mirror `TYPE` params 1:1); Android `ContactsContract` stores MIME-typed Data
rows mapping ~1:1 to vCard properties (`StructuredName`→`N`/`FN`, `Phone`→`TEL`,
`Email`→`EMAIL`, `StructuredPostal`→`ADR`, `Organization`→`ORG`) with an AOSP vCard
composer for import/export.

- **Target vCard 3.0**, not 4.0 — Android exports 2.1/3.0 and iOS speaks 3.0; 4.0 support is
  poor on both. Custom labels ride as `X-` params and survive because we keep the raw payload.
- **Decision (maintainer, 2026-06-11): write a minimal custom KMP vCard codec** rather than
  depend on a library (ez-vcard is JVM-only anyway; platform-specific import code is needed
  regardless, and the project prefers minimal external dependencies). Scope: parse/serialize
  the properties the UI touches (`FN`, `N`, `TEL`, `EMAIL`, `ADR`, `ORG`, `PHOTO`, `NOTE` +
  pass-through of unrecognised lines), versions 2.1/3.0 on read, 3.0 on write.
- **Import paths**: Android — `ContactsContract` query behind an explicit user opt-in
  (milestones §Contacts), or the permissionless system picker
  (`ActivityResultContracts.PickContact`) for one-off picks; iOS — `CNContact` when the iOS
  client lands; desktop — vCard file import (no system store to query).
- Match-relevant fields (emails/phones) are parsed from sources **at read time** — fine at
  contact-list scale. Escape hatch if it ever hurts: denormalise `emails`/`phones` as lists
  on `ContactSource` (lists are naturally "empty = absent"), never on `Person`.

## 6. Matching & merge rules (v2)

Centralized apps auto-match on a **verified identifier** (Signal/WhatsApp: SMS-verified phone
numbers; Google: account email). We have none — everything a handshake delivers is
self-asserted. Hence:

1. **Never auto-merge on self-asserted data; only suggest.** The attack: a handshake claims a
   known person's name+email; auto-merge would dress the attacker's key in that person's
   contact card. With suggest-and-confirm the attacker gets a fresh, checkmark-less entry at
   worst.
2. **Ask at handshake completion, not later.** The user just initiated the exchange with a
   person they chose — context is maximally fresh. Completion screen: *"Save as new contact,
   or link to existing?"* with candidates ranked by email/phone/name equality against
   imported sources. This one prompt prevents nearly all disconnected duplicates.
3. **Merge = re-point links; split = the reverse.** Merging two `Person`s re-points
   `personId` on their identities/sources and drops one aggregate; nothing is lost (the
   Android join/split precedent). Because trust lives on `TenshuIdentity.verification`,
   merge/split never moves trust — a mis-link is a cosmetic error, not a security one.

## 7. Rei integration (v3)

`tenshu-rei.md` Q52's verified-user badge is structurally a Rei attestation whose subject is
an **identity key** — it lands here as the `ATTESTED` rung of the §4.2 ladder plus a
`source = REI` keyring entry. Creator/Creations consume `ATTESTED` identities for public
("external") verification of people; the stranger-discovery case excluded by §2 is served
this way. Nothing in v1/v2 needs reworking.

## 8. Phasing

- **v1 — unblock *Manage access* (no Contacts app).** The sheet becomes: member list for the
  file's realm (via `getSharedRealmMemberships`, which exists) + an **Invite** button
  generating an invitation link/QR handed to the OS share sheet. The handshake (cross-user
  sibling of [`sync/pairing-flow.md`](../sync/pairing-flow.md)) yields a `TenshuIdentity` + thin `Person`; the picker
  thereafter lists previously connected people, with "add via QR/link" as the escape hatch.
  The protocol slice + threat-model amendment (§9) are now spec'd in
  [`contact-invitation-handshake.md`](contact-invitation-handshake.md) — **phases CI-1–CI-3
  shipped 2026-06-11** (crypto + schemas + relay mailbox + the five ops + threat-model
  §8.13/A24/A25; see that doc's §10 status pins). CI-4 (the Drive *Manage access* UI):
  spec'd in [`drive-manage-access-ux.md`](drive-manage-access-ux.md); **CI-4a (link flow
  + completion prompt + redemption) and CI-4b (QR + SAS) both shipped 2026-06-12 —
  v1 *Manage access* is feature-complete** (membership grant still parked).
  **v1 scope
  amendment (maintainer, 2026-06-11):** the five-module `:contact-service` skeleton is
  created in v1 with just the handshake subset (`Person`, `TenshuIdentity`,
  `ContactInvite` + ops); v2 fills in `ContactSource`/vCard/the app. **Hard prerequisite:** the
  cross-user join runtime — relay-side device admission for the *other user's* devices + key
  delivery to phrase-less joiners ([`sync-multi-realm-runtime.md`](sync-multi-realm-runtime.md)
  §3.1) — a separable track this doc does not size.
- **v2 — `:contact-service` + Contacts app.** The five-module service with the §4 model, the
  milestone Contacts app UI (list / detail / manual add / edit), Android provider import +
  vCard import/export (§5), match-and-link UX (§6).
- **v3 — Rei `ATTESTED`** (§7), alongside the Creator/Creations cluster.

Dropped: relay-hosted handle directory (§2). Revisit only if a concrete
stranger-contact need survives the Rei-attestation alternative.

## 9. Threat-model notes (for the amendment that ships with v1 code)

- New asset class: the **contact graph** (Person/Identity/Source rows) — personal-realm
  Syncables, encrypted like any entity; the relay learns nothing (House Rule #1 holds).
- The **handshake** is the new surface: it must bind the exchanged pubkeys to the out-of-band
  channel (link secret / QR / SAS) such that a MitM on the relay cannot substitute keys —
  same analysis shape as [`sync/pairing-flow.md`](../sync/pairing-flow.md). **Now spec'd:** the ready-to-apply
  amendment (assets A24/A25, threats T-CI-01–09, attack surface #11) is
  [`contact-invitation-handshake.md`](contact-invitation-handshake.md) §9 and co-lands
  with the CI-3 implementation PR. Invitation links are one-time/expiring (72 h default)
  capabilities, revocable before redemption (Q53).
- **Spoofing** is handled structurally: petname on `Person` (user-owned), self-asserted name
  quarantined on `TenshuIdentity`, no auto-merge (§6), checkmark derived from key
  verification only.
- Pairwise identities (§4.2), if adopted later, change the linkability analysis — flagged as
  an explicit future amendment, not silently absorbed.

## 10. Open questions

- ~~The handshake protocol spec itself (message flow, link format, expiry, relay mediation)~~
  — **resolved:** [`contact-invitation-handshake.md`](contact-invitation-handshake.md)
  (2026-06-11) is the v1 implementation doc.
- Auto-`Placement`/auto-mount on accepting a share remains the 3d UX decision of
  [`drive-shared-realm-file-model.md`](drive-shared-realm-file-model.md) §6.
- Avatar story for `Person` (likely a small content-addressed blob via `:file-service`;
  read-through from source `PHOTO` until then).
- ~~Whether v1's invite issues a *realm* invitation directly or a *contact* handshake followed
  by a membership grant~~ — **resolved (2026-06-11): the latter**, with an advisory
  *share intent* riding the sealed ack so Drive's flow stays one gesture; rationale in
  [`contact-invitation-handshake.md`](contact-invitation-handshake.md) §2.

## References

- [`drive-shared-realm-file-model.md`](drive-shared-realm-file-model.md) — the share mechanism this picks people for.
- [`drive-shared-realms.md`](drive-shared-realms.md) §2 — the directory question this resolves.
- [`tenshu-rei.md`](tenshu-rei.md) Q23/Q52/Q53 — invite mechanisms, verified badge, Contacts association.
- [`sync-multi-realm-runtime.md`](sync-multi-realm-runtime.md) §3.1 — cross-user join prerequisites.
- [`../milestones.md`](../milestones.md) §Contacts — the milestone app this service underpins.
- [`../sync/pairing-flow.md`](../sync/pairing-flow.md) — the SAS/QR gesture family the handshake reuses.
