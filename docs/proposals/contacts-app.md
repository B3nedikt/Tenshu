# Contacts — the app over `:contact-service`: pre-design decision record

**Status:** v1.3 (2026-10-31). ⭐ **v2 of the service (§9.10): a card's big `PHOTO` lines live on
the content plane** — the S0b `rawVcard` blocker. v1.2 (2026-08-04): **Ph0–Ph9 shipped; Ph10 (the iOS shell) written but only half
verifiable on the build host — as-built in §9, and §9.9 says exactly which half.** The Contacts
app runs on
Android and desktop over the real `:contact-service`: the D3 badged list, the contact detail
with its keyring, the D6 own profile card, bare invitations end to end (mint → QR/link → the
other side accepts → completion prompt → contact), manual add / rename / delete / drop-a-key,
**post-handshake re-verification** — the Q2a compare-and-confirm gesture that closes the three
surfaces §1 says were homeless — and the **`ContactSource` + vCard half**: import, a
read-through union on the detail screen, and a field editor that merges rather than
regenerates — plus **platform import**: the OS contact picker, a `.vcf` file, or the whole
Android address book behind an opt-in permission asked for at one call site — and its chrome
is now Drive's, over the same `:ui-core` components, and contacts imported from a card carry
their photo, with a user-assisted duplicate review behind it. Thirteen new
service ops and one new composite in total; **the A24 threat-model amendment + T-CI-09b
landed with Ph5** (House Rule #2). Next: **a macOS pass over Ph10** (§9.9's owed list) — then
*pick from Drive*, which the §5 picker track unblocks and which Ph8's other half waits on. Locks D1–D8 from the maintainer
session (§3), resolves **Q1–Q7** in a follow-up pass the same day (§4), and phases the build
(§6).

⚠⚠ **Two phases did not go as written, both recorded in place.**

**Ph3:** `drive-manage-access-ux.md` decision 2 — the completion prompt "moves to the Contacts
app unchanged" — **expired at GR-4**, which chained the folder grant offer onto that prompt.
Two things shipped in response: the apps **partition the invite space** by `intentKind`
(**§9.1**), *and* the grant offer was **decoupled** from the prompt (**§9.1.1**), removing the
coupling that broke decision 2 in the first place. Moving the prompt to Contacts is therefore
unblocked but **deliberately undecided** — it would make Drive require Contacts to be
installed to finish a share it started.

**Ph4:** Q7b's *"name the shared realms and offer to revoke"* is **not buildable from Contacts
today** — every membership relation is `chainReadable = false` and `GrantedMembership` is
proxy-internal — so the delete confirmation warns without enumerating (**§9.2**).

⚠ **v0.2 changed three things v0.1 got wrong**, all recorded in place rather than silently
edited: D1's dependency on a content refcount (Q3 — Phase 4 stopped being a refcount on
2026-07-18, so the gate does not exist and a Drive delete cannot break an avatar); §4.2's
derived-checkmark rule (Q2b — a rotated key is a *new keyring row*, so a stale checkmark
survives on a key the contact no longer uses); and the recommendation to orphan identities on
delete (Q7a — reversed to delete-everything once Q2a made re-verification a local gesture).

Sibling to [`maps-app.md`](maps-app.md), [`timeline-app.md`](timeline-app.md) and
[`fit-app.md`](fit-app.md); companion to
[`contact-service-identity-model.md`](contact-service-identity-model.md) (the data-service
design, whose **v2** this is) and
[`contact-invitation-handshake.md`](contact-invitation-handshake.md) (the shipped v1 protocol).

**Owner's note:** decision- and scope-oriented. If a design handoff later lands under
`docs/design/contacts/` (local-only, matching the `timeline/` and `maps/` convention), *that*
becomes the source of truth for layout and vocabulary; this proposal is the durable record of
what was decided going in.

---

## 1. Goal & scope

Ship the milestone Contacts app ([`milestones.md`](../milestones.md) §Contacts): contact list,
per-contact detail, manual add / edit, vCard interop — at **parity with the stock
Android/Apple Contacts apps as far as it makes sense** for a handshake-only, no-directory
identity model.

**Premise correction, recorded deliberately.** Drive's sharing is *already functionally
complete* — CI-1–CI-4 (handshake, QR, SAS) and GR-1–GR-7 (membership grant, removal, key
rotation) all shipped. Contacts does **not** unblock sharing from working. What it unblocks is
sharing being **maintainable**: today a contact can only come into existence via a handshake,
and can then never be renamed, forgotten, or verified after the fact. Three surfaces in
[`drive-manage-access-ux.md`](drive-manage-access-ux.md) were explicitly deferred to "the v2
Contacts app" and are still homeless (§2.3 completion prompt + badge, §7 contact detail and
re-verification, §10.2 the verified badge). Contacts is where they land.

**In scope:** the app (list / detail / add / edit / own profile card), the missing
`:contact-service` CRUD + re-verification ops, `ContactSource` + a vCard codec, per-platform
contact import, and avatars.

**Out of scope:** v3 Rei `ATTESTED` verification
([`contact-service-identity-model.md`](contact-service-identity-model.md) §7); any relay-hosted
directory (§2 of that doc — rejected, permanently); calendar/birthday integration.

## 2. What already exists

The service is **not** a greenfield. All five `:contact-service` modules are built with the v1
handshake subset:

| Built | Detail |
|---|---|
| `Person` | `personId` + `displayName` (petname) only — the §4.1 thin shape, deliberately no vCard fields |
| `TenshuIdentity` | full keyring entry: `fingerprint`, `publicKey`, `personId`, `selfAssertedName`, the `UNVERIFIED/VERIFIED/ATTESTED` ladder, `verifiedAt`, `source`, `grantSeed` |
| `ContactInvite` | incl. `intentKind`, which already accepts `""` for a bare (non-share) invite |
| `ContactService` | three `maintained = true` relations: `persons`, `tenshuIdentities`, `contactInvites` |
| Ops (6) | `CreateContactInvite`, `RevokeContactInvite`, `RedeemContactInvite`, `AwaitInviteAck`, `CompleteContactHandshake`, `ListPendingRedemptions` |
| Drive's `ContactsRepository` | reactive `persons()` / `contactInvites()`, one-shot `connectedContacts()` with `grantable`, own display name via `OwnerPreferences.Name` |
| `MembershipRepository` | GR-4/GR-6 grant / accept / list members / remove |
| `:ui-core` | `QrCode` + `QrScanner` (moved there by CI-4b) |

**Absent entirely:** `ContactSource`, any vCard code (zero hits repo-wide), every mutating op
that is not part of the handshake (no create / rename / delete / merge / split `Person`, no
link / unlink / re-verify `TenshuIdentity`), avatars, the app modules, and platform import.

**Consequence for sequencing:** a read-only contact list ships today over the existing read
surface with **zero service work** — which is what makes the §6 phasing cheap at the front.

## 3. Decisions (locked 2026-08-02)

### D1 — Avatars: import from `PHOTO`, or pick from Drive; content-addressed, reference-guarded

The avatar comes from two sources: the `PHOTO` property of an imported vCard/system contact,
and an explicit **pick from Drive**. Both land as a **content-addressed blob** — content
addressing is kept.

Four sub-decisions:

1. **Contact avatars are not surfaced in Drive's browse tree.** A user does not expect a
   contact photo to appear among their files. The blob simply lies in the realm, addressed by
   content, referenced by the contact.
2. **Deleting the contact needs no explicit blob release.** ⚠ *Amended by Q3* — the blob
   lingers against the owner's quota exactly like any deleted file's, until account-lapse
   reclaim. Nothing to build.
3. **Deleting a Drive file that a contact uses shows an informational note**, not a warning.
   ⚠ *Amended by Q3* — the original Keep it / Move it / Delete anyway prompt assumed the delete
   could break the avatar. **It cannot:** content is addressed independently and is not reaped
   on file delete, so the photo survives either way, and a three-way choice would imply a
   consequence that does not exist. The note says the photo is also a contact's and that
   deleting the file will not remove it from them.
4. ⚠ **The refcount this originally depended on is not coming** — see Q3.
   [`file-content-addressing.md`](file-content-addressing.md) Phase 4 was revised 2026-07-18
   away from refcounting (HR#1-impossible: the relay cannot read a manifest — ⚠ reopened
   2026-09-08, `content-plane-refcount.md`). **D1 is gated on
   nothing here.** See §5 and Q3.

⚠ **Schema note:** putting `avatarRef` on `Person` is a *positional reshape* of a shipped
composite (House Rule #9 — bump `service(id, version = …)` + ship a `SchemaMigration`).
`ContactService.Schema` is at **`version = 1`** (the 2026-11-05 re-cut folded §9.10's v2 back into the baseline; `core-schema-evolution.md` Appendix A.6). A sibling row keyed by
`personId` (the `TenshuIdentity` pattern) avoids reshaping `Person` altogether — see Q4.

### D2 — Parity scope: take ~80%, defer the rest

Parity with stock Contacts where it makes sense; the remainder is a later version, not a
blocker.

**In:** list + detail, manual add / edit / delete, favourites, **local search across your own
contacts**, groups/labels, share-as-vCard, import/export, per-contact actions offered by other
installed apps (D3).

**Out (deliberately):** ringtones, SIM import, "linked accounts" in the Google sense,
per-contact call/message blocking — all assume a telephony or account substrate Tenshu does
not have.

**Anti-parity by design — two things stock apps do that Tenshu must not:**

- **No *directory* search.** Clarifying the §4 wording of the prior session: local search over
  your own contact list is in scope and expected. What is permanently rejected is
  *relay-hosted stranger lookup* — a server mapping email/handle → public key
  ([`contact-service-identity-model.md`](contact-service-identity-model.md) §2), which would
  make the relay a key-distribution trust root.
- **No auto-merge.** See D7.

### D3 — One list, badged rows; per-contact app actions in the detail

**One list, not two.** Crypto-connected contacts (those with a `TenshuIdentity`) and
import-only contacts live in a single list, distinguished by a **badge** on the row. Splitting
into two lists would confuse users who think in terms of "people", not key material.

The **detail** screen hosts the actions other installed apps offer for this contact — today
only Drive's *share a folder*. This implies a small **per-contact action extension point**
rather than a hard-coded Drive button; see Q1.

### D4 — Re-verification: already structurally solved, needs an op and a screen

The concern was that a one-time trust decision must not be lost. It is not — and no new crypto
is required:

- **Trust is already durable.** `TenshuIdentity.verification` is a personal-realm
  `Syncable(Eager)` field: once set it persists and syncs to every device of the user. Nothing
  is lost today; there is simply no *path* to set it after the handshake window.
- **The SAS is already session-independent.** `deriveContactSas`
  (`core-common/.../util/ContactSas.kt`) derives from **both identity public keys only** —
  not from the handshake's shared secret, unlike `derivePairingSas`. Its KDoc states the
  intent outright: *"session-independent, so the same code can be compared at handshake time
  and at any later re-verification (the Signal safety-number shape)."*

So the options reduce to plumbing, and the choice is narrow:

| Option | Shape | Verdict |
|---|---|---|
| **A — `VerifyTenshuIdentity` op** | New op takes `fingerprint` + `sasConfirmed`; proxy re-derives the SAS from the stored `publicKey` and its own, elevates `UNVERIFIED → VERIFIED`, stamps `verifiedAt`/`source = QR_SAS` | **Recommended.** Minimal, matches the existing verification-lives-on-the-key rule, reuses the shipped SAS derivation and the `:ui-core` QR components |
| B — re-run the handshake | Issue a fresh invite and redeem it again | Rejected: needs both parties online and a new relay round trip to re-state something already known |
| C — client-side write | Let the app write `verification` directly | Rejected: elevation must stay proxy-side, never auto, per threat-model A24 |

**Locked: Option A.** Design still owns the *gesture* (in-person QR scan vs. read-the-6-digits
over a voice call) and whether downgrade/revoke is offered — Q2.

### D5 — "Manually typed contact" = a source without an identity

Confirming the model raised in the session: **yes.** A handshake is what yields a
`TenshuIdentity` (the key). Everything else — typed by hand, imported from
`ContactsContract`, read off a SIM, parsed from a `.vcf` — is *just a contact*: a `Person`
aggregate plus a `ContactSource` row holding the record losslessly
([`contact-service-identity-model.md`](contact-service-identity-model.md) §4.3), with **no
key and no verification state**. Manual entry is therefore not a third path; it is
`ContactSource{sourceKey = "manual:$uuid"}`, one origin among several.

This is exactly what D3's badge distinguishes, and it makes the ladder legible: a contact
*acquires* an identity later by handshaking, without changing its aggregate.

### D6 — The own profile card belongs here

Drive punted this (*"Drive has no profile surface"* — `ContactsRepository.ownDisplayName`).
Contacts is its home: **a detail-shaped view of yourself** — your display name, your identity
fingerprint, and your invite QR / link. It reads and writes the platform-level
`OwnerPreferences.Name` that Drive and Fit's Profile already share, so it introduces no second
answer to "what is my name". Bare (non-share) invites need no protocol work: `intentKind`
already accepts `""`.

### D7 — Merge is user-assisted and late

**Never auto-merge** — unchanged from
[`contact-service-identity-model.md`](contact-service-identity-model.md) §6, and load-bearing:
everything a handshake delivers is self-asserted, so auto-merge would let an attacker's key
inherit a known person's contact card. Merge is a **user-assisted** flow (suggest candidates,
user confirms), scheduled **late** and marked low priority. The existing
"ask at handshake completion" prompt already prevents the majority of duplicates, and merge
stays cosmetic by construction — trust lives on the key, so a mis-link is never a security
error.

### D8 — Build order: UI over mock → service ops → real

The shape proven by Timeline, Fit and Maps: **UI first over a mock repository**, then the
service ops behind the seam, then swap the mock out. Applied here with one Tenshu-specific
advantage — the *read* surface is real today (§2), so the mock is only needed for what the
service cannot yet do (CRUD, sources, avatars).

## 4. Q1–Q7 — resolved (2026-08-02, same session)

All seven opened questions were worked through and answered. Kept numbered as asked, so the
reasoning stays findable.

### Q1 — Per-contact app actions: a static list + Drive's existing deep link

⚠ **[`tenshu-rei.md`](tenshu-rei.md) already owns this problem and already deferred it.** Q16
(*multi-app workflows*, "a Tenshu-mediated invocation — Drive calling the viewer's `OpenFile`
operation") was **decided 2026-05-29 as v2**, landing with Q12's **delegated capability
tokens**. Neither is built. Contacts Q1 and
[`cross-app-file-access.md`](cross-app-file-access.md) D-A/D-B are three faces of that one
primitive, and the standing risk is Contacts inventing a parallel model Rei must later absorb.

**Resolved: a static list behind a small internal seam, invoked over the deep link Drive
already registers** (`tenshu-invite:` + the `https://tenshu.app/i` App Link,
`drive-android/src/main/AndroidManifest.xml`, from CI-4a). Justification: **n = 1** — exactly
one action, from one app; an extension point serving one caller is speculative generality. The
row hides where no handler exists (the `HealthFilePicker` rule, §2.5 of the file-access doc).
**The seam is the swap point** when Rei Q16 lands.

### Q2 — Re-verification: compare digits locally; a mixed keyring demotes the badge

⚠ **Re-verification needs no exchange at all.** `deriveContactSas` is over **both public keys**,
session-independent — so each device derives the same six digits *independently*, with no round
trip, no relay, and no co-presence requirement. Verification is a **unilateral local act** on
each side (the Signal safety-number shape).

**Q2a resolved: compare-and-confirm.** Contact detail shows the 6-digit SAS + the pairing
fingerprint format (`XXXXX-YYYYY-ZZZZZ`), per
[`contact-invitation-handshake.md`](contact-invitation-handshake.md) §5.3, which already
anticipated this screen ("later upgrade from the contact detail screen reuses the same
derivation (UI is v2)"). Users compare over any channel they already trust and each taps
*Verify*. Chosen over QR because the population that needs it — remote-link handshakes — is
exactly the population not in the room.

**Q2b resolved: a mixed keyring demotes the person badge, and says why.** ⚠⚠ **This fixes a
latent bug in §4.2's derived-checkmark rule.** `TenshuIdentity` is keyed by fingerprint, so a
rotated or re-handshaked key is a **new row in the same keyring**, not a mutation. Under the
shipped rule (*"≥1 linked identity at `VERIFIED` or above"*) a person with an old verified key
and a current unverified one still renders a checkmark — the badge describing a key they no
longer use, which is precisely the case verification exists to catch. The person now shows as
needing re-verification with copy naming the key change; manual un-verify is also offered.

### Q3 — Delete-path guard: an informational note, resolved Drive→`:contact-service`

⚠ **Corrects this doc's own D1.4.** [`file-content-addressing.md`](file-content-addressing.md)
Phase 4 **is no longer a refcount** — revised 2026-07-18, which states the earlier framing "had
the model backwards": a literal manifest refcount is **HR#1-impossible** (the relay cannot read
a manifest), so deleted-file blobs "simply **linger, counted against the owner's quota**" until
account-lapse reclaim. **There is no refcount coming, and D1 is not gated on one.**
⚠⚠ **Reopened 2026-09-08** — [`content-plane-refcount.md`](content-plane-refcount.md) shows the
"HR#1-impossible" claim rules out only a relay-*derived* refcount, not a client-*declared* one — and
that the actual closure was ADR 0017 D6, which ST-3b has since overridden in practice. ⚠ The avatar is
**not** affected (corrected below), and under that design **no service declares anything**:
declaration lives at the sync layer's emit and tombstone seams (that doc's §5.2), so `:contact-service`
neither gains a hook nor needs one.

Consequences, both good: deleting a contact needs no blob release (the blob lingers exactly
like any deleted file's), and **deleting a Drive file does not break a contact avatar** — the
content is addressed independently and is not reaped on file delete.
⚠⚠ **Corrected 2026-09-08 — an avatar is not content-addressed at all.** `PersonAvatar` holds
`imageData: TByteArray` **inline**, capped at `MAX_IMAGE_BYTES = 256 KiB`; it has no `contentId` and
therefore cannot share one with a Drive file. The safety claimed here is real but comes from the
schema, not from the reclamation posture — so it survives a refcount unchanged.
[`content-plane-refcount.md`](content-plane-refcount.md) Q1 measured this: the whole
reference-holding population is `File.ContentId` and `Album.coverContentId`. `DeleteFile`'s local
cleanup may drop local chunks; the relay copy persists and re-fetch covers it.

**Q3a resolved: an informational note**, not a warning — the delete confirmation says the photo
is also a contact's, and that deleting the file will not remove it from them. A Keep/Move/
Delete-anyway prompt would imply a consequence that does not exist. **Q3b resolved: Drive
queries `:contact-service`** for a matching avatar `contentId`. Honest at n = 1; a generic
`:file-service` reference registry is new platform surface built for one caller, and can
replace this when a second consumer appears.

### Q4 — Avatar: a sibling `PersonAvatar` row, read through a **deferred** `dynamicProperty`

**Resolved: both halves.** The row is the source of truth; a `deferred = true`
`dynamicProperty` on `Person` reads it for ergonomics.

The sibling row follows [`place-roles.md`](place-roles.md) §2.3, whose reason is *not* the
field constraint: a separately-keyed row is **structurally immune to the replace-write**. ⚠ Note
the timing — `Person` is safe *today* (`CompleteContactHandshakeImplementation` only ever
`create`s a fresh row), and **Ph4's `RenamePerson` is what makes it unsafe**; a property would
have us build the hazard and the thing that trips it in one proposal (the `MoveFile` deletion-by-omission loss
class). It is additive, so no HR#9 bump. Symmetry worth keeping: `displayName` is the
user-owned **petname** over self-asserted names; `PersonAvatar` is the user-owned **photo** over
whatever `PHOTO` the sources carry, which stay lossless in `rawVcard`.

The deferred property is what `place-roles.md` §2.3 "considered and did not recommend" — but
that objection was **purely cost** ("one chain execution per place per read"), and it explicitly
said the durability objection "does not apply". Two things flip the trade: it was rejecting an
**eager** property, and deferral is exactly that mitigation (streaming terminals emit rows
immediately, the resolution queue patches per row); and an avatar is the `File.Preview` shape —
a blob resolving to an image — not a scalar that folds into a `Map`. `File.Preview` is already
`deferred = true` by default, so this reuses a pattern rather than inventing one.

⚠ Carry into implementation: (1) the surface rule from
[`core-deferred-properties.md`](core-deferred-properties.md) is **lists = loader, single-entity
reactive = deferred** — so contact *detail* reads the property while the *list* likely wants a
per-row loader, both over the same row; (2) a `dynamicProperty` dispatches through the **client**
module's `roFactory` registration, not merely `operation(Schema)` in common — miss it and every
select projecting the avatar dies as an opaque `error/internal` (the `Workout.Streams` / TS-4
bug); (3) one-shot `getAll()` reads deferred properties as `null`, which matters because Drive's
`connectedContacts()` picker is a `getAll()` caller.

### Q5 — Groups: a `ContactGroup` composite + membership rows

**Resolved:** `ContactGroup {groupId, name}` plus membership rows back-linked by `personId` —
the `ContactsContract.Groups` shape. Consistent with the service's existing idiom, stated in
`Person`'s own KDoc: per-person collections derive from back-links so *"link/unlink is a field
write, never array surgery"*. Groups are user-owned and survive merges; source `CATEGORIES`
round-trip losslessly in `rawVcard` and seed suggestions. A membership *list* on `ContactGroup`
was rejected — every add rewrites the whole row and two devices conflict on one row.

### Q6 — Editable set = the parsed set

**Resolved:** name, phones, emails, addresses, organisation, photo, note — with type labels
(mobile/home/work), which are most of what contact editing *is*. Editable and parsed are the
same list; no codec scope change from
[`contact-service-identity-model.md`](contact-service-identity-model.md) §5.

⚠ **Constraint that holds regardless: the edit path must *merge into* the existing vCard, never
regenerate it.** A save that rebuilds the record from the UI's fields silently drops every `X-`
extension and unmapped property the import preserved — deletion-by-omission, the `MoveFile` loss class.
Lossless-on-import is only lossless if the write path respects it.

### Q7 — Delete means delete; and the delete flow must speak to access

**Q7a resolved: delete everything** — the `Person`, its `ContactSource` rows **and** its
`TenshuIdentity` rows. (An earlier draft recommended orphaning to `personId = ""`; that was
wrong and is recorded here so it is not re-proposed.) Orphaning's only benefit was preserving a
verification — and **Q2a made re-verification a local six-digit comparison**, so the benefit is
now trivial while the cost is a peer's public key retained on every device after the user asked
to delete them, in rows **no surface lists** (unauditable, unclearable, A24 data). It may not
even deliver that benefit: the DataId *is* the fingerprint, so a re-handshake addresses the same
row either way, and whether verification survives is decided by what `CompleteContactHandshake`
writes. `personId = ""` also models a *transient* not-yet-linked identity, not a tombstone.

**Q7b resolved: warn and offer to revoke.** ⚠⚠ **Deleting a contact revokes nothing.**
`MembershipRepository.grant(realmId, personId, …)` is keyed by `personId`, but existing
memberships are keyed by the member's A26 identity — so deleting a contact removes only the
ability to *name* them, while their access to shared folders continues. A user deleting a
contact to cut them off gets the opposite, silently. The delete confirmation therefore names any
shared realms the contact belongs to and offers removing access as part of the delete. This is
wiring, not new mechanism: `RemoveSharedRealmMember` already exists with `Kr` rotation
(GR-6/GR-7).

## 5. Dependencies

- **`:contact-service` CRUD + re-verify ops** (§2, D4) — this proposal's own work.
- ~~**`file-content-addressing.md` Ph4 (refcount + GC)**~~ — ⚠ **not a dependency; struck by
  Q3.** Phase 4 was revised 2026-07-18 away from refcounting, so nothing here waits on it.
  Deleted-file blobs linger against quota, which is precisely why a Drive delete cannot break a
  contact avatar.
- **Cross-app file picking — [`cross-app-file-access.md`](cross-app-file-access.md)** (scoped
  2026-08-02, off this doc). D1's *pick
  from Drive* needs something Tenshu does not have: a **"select from Drive" picker other
  Tenshu apps can invoke**, and its platform mirror, **Drive appearing in the OS file
  dialogs** (Android `DocumentsProvider` / Storage Access Framework, iOS File Provider
  extension) the way Google Drive and OneDrive do. Every existing hit in the docs
  ([`drive-folder-upload.md`](drive-folder-upload.md),
  [`ios-proxy-architecture.md`](ios-proxy-architecture.md)) is Drive *consuming* a platform
  picker to import files — never Drive *being* a provider. This is a Drive/platform track, not
  a Contacts one; it is a real end-user parity gap in its own right.
  **Fallback so Contacts is not blocked:** ship avatars from `PHOTO` import first, and add
  pick-from-Drive when the picker lands.
- **iOS shell** — route-1 (`FirstPartyLocalRuntime`); Maps proved a new first-party app needs
  no new service wiring ([`ios-fit-timeline-maps-parity.md`](ios-fit-timeline-maps-parity.md)
  §3.3.1). ⚠ Standing rule D12 of that doc applies: a new umbrella export must not steal a
  Swift name from a shipped shell — `Person` and `ContactSource` are exactly the kind of
  generic names that collide, so they need `@ObjCName` from the first commit.
- **Per-platform contact stores** — Android `ContactsContract` (explicit opt-in), iOS
  `CNContact`, desktop `.vcf` file import only.

## 6. Phases

Per D8. The **app track** (Ph0–Ph3) is self-contained and needs no service work; the
**service track** (Ph4–Ph9) lights up capability behind the repository seam.

### App track

- **Ph0 — Scaffolding. SHIPPED** (as-built §9). Five modules by analogy to Maps/Timeline:
  `:contacts-shared-logic` (Compose-free, android / `jvm("desktop")` / iOS),
  `:contacts-shared-data`, `:contacts-shared-ui`, `:contacts-desktop`, `:contacts-android`.
  Empty `contactsModules()` DI + Compose-resource wiring. Can start before the design handoff.
- **Ph1 — List + detail over a mock. SHIPPED** (as-built §9). The D3 single badged list,
  contact detail, the D6 own profile card. Mock repository.
- **Ph2 — Real reads. SHIPPED** (as-built §9). Swap the mock for the existing
  `ContactService` read surface (persons + identities + derived verification badge). **No
  service work** — this is live data on day one.
- **Ph3 — Migrate Drive's homeless surfaces. SHIPPED, but not as written** (as-built §9.1).
  ⚠⚠ **The premise was stale.** [`drive-manage-access-ux.md`](drive-manage-access-ux.md)
  decision 2 — *"pure UI over synced rows + one op"*, so the prompt moves *unchanged* — was
  true when written and stopped being true at **GR-4**, which chained the grant offer ("Add
  {petname} to '{label}'?") onto the completion prompt. It now reads `FilesRepository` and
  `MembershipRepository`, and is additionally exposed through the iOS `DriveShareSurface`. It
  cannot move, and moving it minus the grant offer regresses a shipped flow. **As shipped: the
  invite space is partitioned** — Drive owns `SHARE_FOLDER` invites end to end, Contacts owns
  bare ones and hosts a prompt of its own. Bare invites from the own-profile card (D6) shipped
  as specified.

### Service track

- **Ph4 — CRUD ops. SHIPPED, Q7b partially** (as-built §9.2). Create / rename / delete
  `Person` (Q7a: delete takes sources *and* identities); unlink `TenshuIdentity`; manual add
  is real. ⚠ `RenamePerson` is the replace-write Q4's sibling row exists to survive — do not
  regress it into a property. **Two deviations:** *link* `TenshuIdentity` was deferred to Ph9
  (re-pointing a key at a different contact *is* merge, and nothing today produces an
  unlinked key to attach); and **Q7b ships as a warning without enumeration** — naming the
  shared realms a contact belongs to needs a client read that does not exist
  (`GrantedMembership` is proxy-internal and every membership relation is
  `chainReadable = false`), so the delete confirmation says access is unaffected and points
  at Drive rather than listing folders it cannot see.
- **Ph5 — Re-verification. SHIPPED** (as-built §9.3). The D4 Option A `VerifyTenshuIdentity`
  op + the Q2a compare-and-confirm screen + the Q2b demote-on-key-change rule + the §10.2
  verified badge — the last two already shipped in Ph1, so this phase is the op, its
  companion `GetContactSas` read, and the screen. ⚠ Threat-model amendment **co-landed**
  (House Rule #2): A24 amendment + new **T-CI-09b**.
- **Ph6 — `ContactSource` + vCard codec. SHIPPED** (as-built §9.4). The §4.3 composite
  (additive — a *new* type, so no reshape of `Person`) + the §5 codec + import/export of
  `.vcf`. Read-through union on the detail screen. ⚠ File **picking** stays with Ph7 (the
  platform dialogs); the seam takes and returns `.vcf` *text*, so Ph7 only has to supply
  bytes.
- **Ph7 — Platform import. SHIPPED** (as-built §9.5). A `ContactImporter` `expect`/`actual`
  seam over the Ph6 text seam — Android's OS contact picker (permissionless), `.vcf` file
  import, and the `READ_CONTACTS`-gated whole address book, all read through
  `CONTENT_VCARD_URI` so the import is lossless; desktop is `.vcf` only, by decision.
  ⚠ **iOS `CNContact` deferred to Ph10 on structure**: there is no `contacts-ios` shell for
  it to compile against, and `:contacts-shared-ui` has no iOS source set. Adds no op, no
  composite and no baseline movement — Ph6 drawing its seam at `.vcf` *text* is what made
  that true.
- **Ph7b — Design alignment + `:ui-core` audit. SHIPPED** (as-built §9.6). A pass over what
  the design system already ships, after seven phases of each screen picking its own chrome.
  Adopted: `AppSearchBar` as the top strip (replacing a hand-rolled field under a title bar),
  a Drive-shaped FAB holding both acquisition paths, and `AppShortcuts.focusSearch`.
  Deliberately **not** adopted at the time, with reasons recorded: `AppDialog` (raw
  `AlertDialog` is the repo's majority convention, 18 files / 7 modules vs 6 / 2) and a new
  `:ui-core` `AppFab` (5 raw call sites across 3 apps — a wrapper with 1 of 6 consumers is a
  second idiom, so it is a follow-up across all six). ⚠ **Both were settled afterwards as
  `:ui-core` work** — `AppFab`/`AppExtendedFab`/`appFabInset()` across all six sites, and
  `AppDialog` de-forked onto `AlertDialog`; §9.6's two bullets carry the as-built corrections
  to the framing above.
- **Ph8 — Avatars. SHIPPED** (as-built §9.7). The `PersonAvatar` sibling row + the deferred
  `Person.Avatar` property (Q4), the `PHOTO` reader that fills it at import, a 256 KiB
  eager-sync ceiling, and the photo on the list rows + detail header. Purely additive — five
  new types, no shape change. ⚠ **Q3a/Q3b deferred with *pick from Drive***, not by choice:
  with a card's embedded `PHOTO` as the only source, no avatar can be a Drive file, so the
  delete-path note has no trigger and Drive has no `contentId` to match on.
- **Ph9 — User-assisted merge (D7). SHIPPED** (as-built §9.8). `MergePersons` +
  `LinkTenshuIdentity` (the op Ph4 deferred for want of a caller), a pure `findMergeCandidates`
  heuristic **in the app** so the service holds none, and a *Possible duplicates* review behind
  a list banner. Additive — two new ops, no shape change. ⚠ Merge needs no trust logic: keyrings
  union and Q2b answers, which is why a mis-merge is cosmetic. ⚠ No un-merge, said before the
  user commits.
- **Ph10 — iOS shell. WRITTEN, PARTLY UNVERIFIED** (as-built §9.9). The `maps-ios` three-unit
  shape: umbrella export + `ContactsSurface`/`ContactsLocalRuntime` + a SwiftUI shell, born on
  route 1. ⚠⚠ Opened with a **D12 name clash that pointed at the shipped `drive-ios`** —
  `ContactsRepository` and `RepositoryResult` collide with Drive's and sort *earlier*, so the
  export would have renamed **Drive's**; both now carry `@ObjCName`. ⚠⚠ `:tenshu-sdk-ios`'s
  K/N compile is **SKIPPED on Linux** (SKIE is Apple-host-only), so the surface, the runtime,
  their tests and the whole Swift shell are written but never compiled — see §9.9's table.

**v3 — Rei `ATTESTED`** stays out of scope
([`contact-service-identity-model.md`](contact-service-identity-model.md) §7).

## 7. Threat-model and schema-evolution notes

- **House Rule #2.** Ph5 (re-verification) changes how an identity reaches `VERIFIED` and
  must amend [`../sync/threat-model.md`](../sync/threat-model.md) in the same PR (asset A24 —
  verification is never auto-elevated). Ph6–Ph8 widen the contact graph's contents (A24) but
  not its trust rules; Ph8 adds contact-photo plaintext to the content plane (A5 class).
- **House Rule #9.** `ContactService.Schema` is at **`version = 1`** — the release reset
  restarted it at 1, §9.10 (big photos on the content plane) moved it to 2, and the 2026-11-05 re-cut
  (no build had reached a user) put it back at 1 with the §9.10 shape. New composites
  (`ContactSource`, a `PersonAvatar` sibling, `ContactGroup`) are *additive* and reshape
  nothing. Any field added to the shipped `Person`, `TenshuIdentity` or `ContactInvite` **is** a
  positional reshape → bump the service version and register a `SchemaMigration` in the same
  PR. `SchemaShapeBaselineEnforcementTest` fails loudly if this is forgotten, so the failure
  mode is named, not silent.
- **No relay impact.** Nothing here touches `RELAY_PROTOCOL_VERSION`; the contact graph is
  personal-realm ciphertext (House Rule #1). No relay redeploy is owed by any phase.

## 8. Implementation Notes for Claude Code

Handoff for the session that starts building. **§§1–7 win if anything here contradicts them.**
Verify package paths against neighbours before writing files.

### Start here

**Ph0 (scaffolding) is the next action and needs no further input.** Five modules by analogy to
`:maps-*` — copy that structure, it is the most recent and the only one that also had to think
about iOS:

```
:contacts-shared-logic   Compose-free; android / jvm("desktop") / iOS targets. VMs + repos.
:contacts-shared-data    models + the repository seam
:contacts-shared-ui      App() + contactsModules() DI + Compose resources; android / desktop
:contacts-desktop        jvm main() + the desktop ViewModelStore/SavedState shims
:contacts-android        ComponentActivity + Application shell
```

Then **Ph1 over a mock**, then **Ph2 swaps in the real read surface** — which already works
(§2), so Ph2 is genuinely small. Do not start the service track (Ph4+) first; D8 exists because
the UI-over-mock order is what worked for Timeline, Fit and Maps.

### Read before deciding anything

1. [`contact-service-identity-model.md`](contact-service-identity-model.md) §§3–6 — the data
   model and the no-directory/no-auto-merge rules. This doc is its v2, not its replacement.
2. §4 of *this* doc — Q1–Q7, with the reasoning. Three of them reversed an earlier position;
   the reasoning is what stops them being re-reversed.
3. [`contact-invitation-handshake.md`](contact-invitation-handshake.md) §5.3 — the SAS + the
   fingerprint display format Ph5 renders.

### Traps, each already paid for once

- ⚠⚠ **A `dynamicProperty` dispatches through the CLIENT module's `roFactory` registration** —
  `operation(Schema)` in the common module is *not* enough. Miss it and every select projecting
  the avatar dies as an opaque `error/internal`. Cost `Workout.Streams` and TS-4 a debugging
  session each. Applies to Q4's deferred avatar property.
- ⚠ **One-shot `getAll()` reads deferred properties as `null`** — only Maybe/stream terminals
  materialise them. Drive's `connectedContacts()` picker is a `getAll()` caller, so it will see
  no avatar unless it moves to `search → select → get()`.
- ⚠ **`RenamePerson` (Ph4) is a replace-write.** It is the reason Q4 chose a sibling row. Do not
  "simplify" the avatar into a property or field on `Person` later — that silently reintroduces
  the `MoveFile` deletion-by-omission loss class, and `place-roles.md` §2.2 documents the same trap.
- ⚠ **vCard edit must merge into the existing record, never regenerate it** (Q6), or every `X-`
  extension the import preserved is dropped by omission.
- ⚠ **House Rule #9:** new composites (`ContactSource`, `PersonAvatar`, `ContactGroup`) are
  additive and free. Any field added to the shipped `Person` / `TenshuIdentity` / `ContactInvite`
  is a positional reshape owing a `service(id, version = …)` bump + a `SchemaMigration` in the
  same PR. `ContactService.Schema` is at **`version = 1`** (2026-11-05 re-cut).
  `SchemaShapeBaselineEnforcementTest` fails loudly if this is missed.
- ⚠ **iOS (Ph10): `@ObjCName` from the first commit.** `Person`, `ContactSource` and
  `ContactGroup` are exactly the generic names that collide in the K/N umbrella, and a collision
  silently renames a *shipped* app's Swift types — `ios-fit-timeline-maps-parity.md` D12, where
  exporting Maps renamed Timeline's `Place` to `Place_`.
- ⚠ Ph5 owes a **threat-model amendment in the same PR** (House Rule #2, asset A24).

### Testing

`./gradlew desktopTest jvmTest test` — ⚠ `test` alone runs **zero** KMP test tasks. Android-
touching work also owes `scripts/android-smoke.sh` (House Rule #8). Ph3 touches `:drive-shared-*`
(migrating the completion prompt out), so it owes the smoke gate.

### Not decided, and deliberately so

- Whether a design handoff lands under `docs/design/contacts/` (local-only, the `maps/` +
  `timeline/` convention) for layout and vocabulary. Maintainer's call; §§1–7 are complete
  enough to build Ph0–Ph2 without it.
- Rei Q12/Q16 (app-to-app invocation + capability tokens) stay **v2 and unbuilt**. Q1's static
  list is the deliberate stand-in; do not grow it into a general registry.

## 9. As built — Ph0–Ph2 (2026-08-03)

The app track shipped in three commits on `claude/contacts-app-scaffolding-icm891`. Deltas
from §6/§8 recorded here so the next session does not have to diff.

**Ph0 — scaffolding.** The five modules as specified, package root `app.tenshu.contacts.*`.
Notes worth carrying:

- `:contacts-android`'s manifest strips `INTERNET` (all egress is Control's, House Rule #1 at
  the OS boundary) and declares the standard `<queries>` + `ClientCallbackService` pair. It
  deliberately does **not** declare `READ_CONTACTS`: platform import is Ph7 and opt-in, so the
  permission belongs with the importer, not the shell. *(Ph7 landed it — §9.5. It is
  requested at exactly one call site, and only by the whole-address-book row.)*
- No macOS `.icns` (the icon track has not reached Contacts) — `:home-desktop`'s posture. The
  Android adaptive icon is a stroked person-on-a-card on the shared 84/4.125 group.

**Ph1 — UI over the mock.** Two top-level surfaces (`Contacts`, `You`) plus a pushed detail.
The load-bearing piece is that **Q2b is modelled, not derived ad hoc**: `TrustState` +
`trustOf(identities)` live in `:contacts-shared-logic`, so the demote-on-mixed-keyring rule
has exactly one home and `ContactTrustTest` pins it. The mock fixture deliberately contains a
contact in that state — a screen that has never rendered `NeedsReverification` has not really
been built.

Three presentation decisions taken here, each defensible but not in §§1–7:

1. `TrustState.NotConnected` renders **no badge**. Once import lands (Ph6/Ph7) it is the
   majority state, and badging it reads as a warning about people the user simply typed in.
2. Row supporting text appears **only** for `NeedsReverification`. A calm list is what makes
   the one actionable state visible; repeating "not verified" under every row buries it.
3. Search is an **in-place filter**, not Drive's expanding search *mode* — filtering an
   address book is the ordinary way to use it.

The seam is read-only apart from `setOwnDisplayName`. Create/rename/delete need Ph4 ops that
do not exist, and giving the mock powers no real implementation has is exactly what the
UI-over-mock order exists to prevent.

**Ph2 — the real read surface.** `TenshuContactsRepository` in `:contacts-shared-data` over
the Binder/UDS client to Control, carrying `:contact-service` + `:preference-service` (D6's
name is `OwnerPreferences.Name`, never an app-private copy). Three things that needed getting
right, all test-pinned:

- ⚠ **`personId == ""` is not a person.** An unlinked key (redeemed-but-incomplete handshake)
  must attach to nobody; grouping it like any other back-link collects every unlinked key
  under one phantom contact.
- ⚠ **The ladder mapping fails closed.** An unrecognised `verification` string reads as
  `UNVERIFIED`. A client that does not understand a rung must under-claim trust — being wrong
  that way costs a re-comparison, the other way puts a checkmark on an unchecked key.
- ⚠ **Q2b's premise is verified against the store, not assumed.**
  `ContactsPipelineIntegrationTest` seeds two keys for one person through a `TestProxy`
  hosting `:contact-service` and asserts the second lands as a **new row**. The join itself is
  a pure function (`joinContacts`) so the cases above are testable without a proxy.

**Added beyond §6: the reachability banner.** Not in the phase text, and load-bearing. The app
has no store of its own, so an unreachable Control folds every read to empty and the list says
"No contacts yet" to someone with 200 contacts. The shell carries `:app-reachability`
(`fit-app.md` §9.14 recipe) — ⚠ **the probe owns detection**, because a reactive `.flow()`
read against a dead transport *hangs* rather than failing, so waiting for a read to report the
problem waits forever. `ContactsRepository.controlReachable()` defaults to `true` so
in-process implementations (mock, previews, render tests) need no Control.

**Own profile, honestly incomplete.** `OwnProfile.fingerprint` is `""` from the real
repository: there is **no client-readable own-identity op** anywhere in the tree (checked —
`:identity-service` exposes none), and the invite QR / link is Ph3. The card says so rather
than inventing a value.

**Verification.** `:contacts-shared-logic:desktopTest` (19), `:contacts-shared-data:desktopTest`
(11, incl. 4 pipeline), `:contacts-shared-ui:desktopTest` (6 Compose render),
`:contacts-android:assembleDebug`. ⚠ **`scripts/android-smoke.sh` is owed and was not run** —
no emulator was available in the build environment. The app is new and touches no existing
`androidMain`, `:control` or `:drive-android` code, but House Rule #8 still applies before
merge. Neither has anyone clicked through `:contacts-desktop:run`.

**Nothing else moved.** No schema, no op, no wire or storage-format change, no relay redeploy,
no threat-model delta — the whole track reads rows that already ship. Ph5 is where House Rule
#2 first bites.

### 9.1 Ph3 — why the prompt did not move, and what shipped instead

⚠⚠ **`drive-manage-access-ux.md` decision 2 is stale, and §2.3's "moves to the v2 Contacts app
unchanged" cannot be executed.** Recorded here rather than quietly worked around, because the
next person to read that line will otherwise plan against it.

**What changed under it.** Decision 2 was true at CI-3: the prompt really was a pure consumer
of synced `ContactInvite` rows plus `CompleteContactHandshake`. **GR-4 chained the grant offer
onto it** — an accepted `SHARE_FOLDER` invite immediately offers *"Add {petname} to
'{label}'?"* — so `ContactPromptViewModel` now takes a `FilesRepository` (to resolve the
intent label to an owned shared realm) and a `MembershipRepository` (to grant). It is also
exposed through `tenshu-sdk-ios`'s `DriveShareSurface`, so it has an iOS consumer besides
Drive's own screens. None of that can follow the prompt into an app that has no file domain —
and giving Contacts one would be inventing the app-to-app invocation model Q1 explicitly
deferred to Rei Q16.

**Options.** ⚠ Read this table with §9.1.1 — three of the first four are dominated, so it
records why the obvious moves fail, not a genuine four-way weighing. The real choice was
between the last two.

| | Outcome |
|---|---|
| Move it, drop the grant offer | Regresses a shipped flow: sharing a folder with a new contact becomes two disconnected manual steps. Rejected. |
| Move it, give Contacts the file domain | Contacts depends on `:file-service` + the shared-realm ops to offer a folder grant — the parallel model Q1 rejected. |
| Host it in both, unfiltered | The same invitation prompts in two apps, and Drive's prompt would chain a grant offer for a share that never existed. |
| **Partition the invite space** | **Shipped.** Each app completes the handshakes it starts. |
| **Decouple the grant offer** (§9.1.1) | **Also shipped.** Removes the coupling itself, so the prompt *could* move — the move is deliberately left undecided. |

**The partition.** `ContactInvite.intentKind` already carries the distinction, and Drive only
ever mints `SHARE_FOLDER` (`InviteLinkViewModel`). So:

- **Drive** owns `SHARE_FOLDER` invites end to end — its prompt, its badge, its grant chain,
  untouched apart from a filter. `hasRedeemedInvites()` and `SharedScreenViewModel` now narrow
  to `SHARE_FOLDER`; before this they matched every kind, which is what would have caused the
  double prompt.
- **Contacts** owns bare invites (`intentKind == ""`, which `ContactInvite` already accepted).
  It mints them from the own profile card, badges the nav item while one is `REDEEMED`,
  banners it above the list, and completes it with a prompt of its own.

This also reads well as product: Drive's prompt continues into *"…and add them to the folder"*,
Contacts' just saves a contact. Nothing forces them to be one screen.

⚠ **The residual duplication is the two prompts themselves** — ~150 lines of similar dialog
across two apps. Deliberate: merging them needs the app-invocation primitive (Rei Q12/Q16, v2
and unbuilt), and a shared-UI module between two apps' contact surfaces would be the wrong
seam to reach for first. If a third consumer appears, that is the signal to revisit.

#### 9.1.1 The fifth option — decouple the grant offer. **SHIPPED; the move stays open.**

⚠ **The four options above were not the whole space, and the table overstated how forced the
partition was.** Three of the four are plainly dominated, so the real choice was always
binary. The option missing from it — **stop chaining the grant offer onto the completion
prompt** — was prototyped, held up, and is now part of this phase.

**The idea.** The prompt is impure only because GR-4 made accepting a `SHARE_FOLDER` invite
flow straight into *"Add {petname} to '{label}'?"*. Move that offer out — surface it on
Drive's Shared screen as a card derived from **`COMPLETED`** invites — and the prompt goes
back to being a pure consumer of synced rows plus one op. Decision 2 becomes true again and
the prompt really *can* move to Contacts, with no partition needed.

⚖️ **Decided: take the decoupling, leave the move undecided.** Both are in. The partition
stays in place and Drive keeps its own prompt, so nothing double-prompts and Drive remains
self-sufficient for the flow it starts. What changed is that the option is now *live*: moving
the prompt is a UI deletion away, whenever the Drive-needs-Contacts-installed question below
gets an answer.

**It works, and the derivation needs nothing new.** `ContactInvite.resultFingerprint` is
already populated on completion (`CompleteContactHandshakeImplementation`), so
*"this contact finished a folder invite and was never granted the realm"* is derivable from
rows that already sync — no new op, no schema change, no wire change. As built:
`SharedScreenViewModel.recomputeGrantOffers()`, and `ContactPromptViewModel` now takes a
single `ContactsRepository` (it took three).

**Two things the prototype makes better, not just equal:**

- The offer **survives**. Chained, it existed for one modal moment: dismiss the prompt, get
  interrupted, or complete the handshake on another device, and the share was silently never
  granted. Derived, it persists until acted on.
- Granting **converges structurally**. The card is filtered by "is this contact already a
  member of that realm", so a grant made on any device removes it everywhere with nothing
  remembered.

⚠⚠ **And one thing it makes worse, which is the honest cost.** *Not now* has nowhere to
live. The chained offer needed no dismissal state because it was modal — declining ended it.
A derived card must be told to go away, and "go away" is not derivable from any synced row.
The prototype keeps dismissals **in memory**, so the offer returns on the next launch and on
every other device; `dismissingHidesTheOffer_butOnlyForThisProcess` pins exactly that rather
than pretending otherwise. Making it durable is a real decision (a device-scoped preference?
a synced `GrantOfferDismissed` row, which is a new composite and an HR#9 question?) that the
prototype deliberately does not take.

**What is deliberately *not* done: moving the prompt to Contacts and deleting Drive's.** It is
unblocked, but it carries a consequence that deserves its own decision — **Drive would then
require the Contacts app to be installed to finish a share it started.** Under the partition
Drive is self-sufficient. So this phase stops at the enabling refactor.

**The open question, for whoever takes it up.** Keep both prompts (Drive standalone, ~150
lines duplicated), or move and delete Drive's (one prompt, decision 2 fully honoured, Drive
depends on Contacts)? Two things that were true when this was written and are worth
re-checking then: the apps already install together (the smoke gate does Control + Drive +
Maps), and the duplication is dialog markup rather than logic. If the move happens, the work
is: drop the `intentKind` filters in `TenshuContactsRepository.bareInvites`,
`ContactsRepository.hasRedeemedInvites` and `SharedScreenViewModel`; delete
`ContactPromptDialog`/`ContactPromptViewModel`/`InviteBadgeViewModel` and their entry points
in `SharedScreen` + `ManageAccessScreen`; and retire `DriveShareSurface.contactPrompt`.

**Also shipped in Ph3.** The invite half of the seam — `outstandingInvites`,
`pendingConfirmations`, `createBareInvite`, `revokeInvite`, `completeHandshake`. All map to
CI-1–CI-4 ops that already exist, so §2's rule still holds: no method on the repository can do
something no real implementation can.

Details worth carrying:

- ⚠ **The link is shown once and cannot be re-derived** — it carries `K_link`, never persisted,
  never relay-visible. The sheet says so *before* it closes, `onCreateClicked` is guarded so a
  second tap cannot strand the first link, and `OutstandingInvite` deliberately has no link
  field. Revoke-and-reissue is the only remedy, exactly as Drive's *Invited* rows work.
- ⚠ **`sasConfirmed` comes only from the explicit check**, which starts unticked (threat-model
  A24). Tests pin both directions: accepting without it leaves the key `UNVERIFIED`, ticking it
  yields `VERIFIED`, and declining elevates nothing even with the box ticked.
- ⚠ **Linking to an existing contact never transfers trust.** The new key joins that keyring
  `UNVERIFIED`, which then surfaces as the Q2b mixed-keyring demotion — pinned by a test, since
  this is precisely the case where a careless "simplification" would inherit a checkmark.
- The nav badge is read at the shell rather than in the list's view model: that VM only exists
  while the Contacts tab is composed, and the badge's whole job is to be visible from the other
  tab. Drive spends an `InviteBadgeViewModel` on this; here `App` already holds the repository
  for the reachability banner.
- **Not done:** redeeming a link inside Contacts. The Android deep links (`tenshu-invite:`, the
  `tenshu.app/i` App Link) are registered to Drive, and re-registering them is a migration with
  its own failure modes, not a Ph3 line item. The inviter-side flow is complete without it.

**Verification.** `:contacts-shared-logic:desktopTest` (32), `:contacts-shared-data:desktopTest`
(12), `:contacts-shared-ui:desktopTest` (8), `:drive-shared-ui` + `:drive-shared-logic`
`desktopTest`, `:contacts-android:assembleDebug`, `:drive-android:compileDebugKotlin`. ⚠ **The
smoke gate is owed and now genuinely applies** — Ph3 touches `:drive-shared-logic`, so House
Rule #8 is in scope, and no emulator was available. Still no schema, op, wire or relay change.

### 9.2 Ph4 — contact CRUD, and the half of Q7b that could not be built

**Four new ops on `:contact-service`** — `CreatePerson`, `RenamePerson`, `DeletePerson`,
`UnlinkTenshuIdentity` — all local writes (no relay, no crypto). The rows are personal-realm
`Syncable(Eager)`, so they reach the user's other devices on the ordinary sync path with
nothing service-specific to add.

**House Rule #9: no version bump is owed, and that was checked rather than assumed.**
`SchemaShapeBaselineEnforcementTest` reported the four ops as *"new types not pinned yet"* —
not as changed shapes — which is the mechanical proof that no shipped composite was reshaped.
`ContactService.Schema` still declared no `version =` at the time (it later moved to `version = 2`, with the completion-fingerprint fix). The baseline was regenerated. ⓘ That
regeneration also picked up a **pre-existing** drift in two `:core` rows
(`tenshu/core/data/schema/composite` and `tenshu/core/entity`, version column 19 → 20), which
reproduces on an unmodified tree — someone bumped the core version without regenerating. It
rides along here because the file is generated wholesale.

**Deviation 1 — `LinkTenshuIdentity` deferred to Ph9.** *(Landed in Ph9 — §9.8. The deferral
was right: it acquired two callers there, the merge itself and the mis-link remedy.)* It has
no consumer:
`CompleteContactHandshake` always links the key it creates, so an unlinked identity
(`personId == ""`) is a transient state nothing currently leaves behind. The real use case —
pointing an existing key at a *different* contact — is merge, which D7 puts late and marks
low priority. Adding the op now would be a surface with no caller.

**Deviation 2 — Q7b ships as a warning, not an enumeration.** ⚠ The safety half is built:
every delete confirmation states that deleting a contact does **not** withdraw anything
already shared, because memberships are keyed by the member's A26 identity rather than by
`personId`. That closes the silent-failure Q7b identified — a user deleting someone to cut
them off no longer gets the opposite without being told.

What is *not* built is naming the realms and offering revoke inline, because Contacts cannot
see them:

- `GrantedMembership` (the owner's roster of who they granted, GR-6) is **proxy-internal** —
  it sits on no service relation at all.
- `SharedRealmMembership`, `Member` and `PendingGrant` are all `chainReadable = false`.
- `getRealmMembers(realmId)` exists and does carry each member's A13 `contactFingerprint`
  (the join key back to a contact) — but it needs a `realmId` to start from, and the only way
  to enumerate a user's owned shared realms today is Drive's `sharedFolders()`, i.e. the file
  domain.

So the missing piece is a new cross-service read — *"which shared realms does this key
belong to"* — on `:sync-service` or `:identity-service`. That is a different service's
surface with its own threat-model surface area, not a contact-CRUD line item, and inventing
it inside this phase would repeat the §9.1 mistake in the opposite direction. The copy points
at Drive instead, which is where the user can actually act today.

⚠ Note the realm↔folder mapping is genuinely Drive's knowledge: one shared realm can back
more than one folder, so even with the enumeration op, *naming* what a contact can reach
still needs the file domain. Whoever picks this up should decide whether the offer belongs in
Contacts at all, or whether Drive's *Manage access* should grow a "remove everywhere" action.

**Other details worth carrying:**

- ⚠ `DeletePersonImplementation` removes the **keys first, then the person**. The `Person` is
  the only handle back to its keyring, so a crash the other way round strands keys nothing can
  find; this order leaves at worst a keyless contact the user can delete again.
- ⚠ It also refuses to treat `personId == ""` as a person. That value means *not yet linked*,
  so a `deletePerson("")` from an upstream bug must not sweep every unlinked candidate in the
  realm. Test-pinned.
- ⚠ `UnlinkTenshuIdentity` **deletes the row** rather than blanking `personId`, for the same
  reason Q7a rejected orphaning: `""` already means "candidate on the way in", and reusing it
  for "deliberately detached" would leave unreferenced keys no surface lists.
- The pipeline test earned its keep immediately: a bare `TestProxy` (fine for Ph2's
  relation reads) answered every new op with `No implementation found for operation: …`, so
  the harness now goes through `createContactProxy`, which installs the implementation
  factory.
- `PersonCrudTest` drives all four ops **over a real client**, which is the only thing that
  proves the client-module `roFactory` registrations exist — without them each call dies as an
  opaque `error/internal`.

**Verification.** `:contact-service-proxy:jvmTest` (7 new), `:contacts-shared-logic:desktopTest`
(42), `:contacts-shared-data:desktopTest` (15), `:contacts-shared-ui:desktopTest` (8),
`SchemaShapeBaselineEnforcementTest`, `:control-shared-proxy:desktopTest` + `:control`
(Control hosts the service), `:drive-shared-ui:desktopTest`, `:contacts-android:assembleDebug`.
⚠ Smoke gate still owed. No wire, storage-format or relay change; no threat-model delta —
`DeletePerson` *reduces* retained A24 data, which is the direction Q7a wanted.

### 9.3 Ph5 — re-verification

**Two ops, because the screen needs to *show* the code before anyone can confirm it.** D4
specified one (`VerifyTenshuIdentity`); a second was unavoidable. `deriveContactSas` is over
**both** identity public keys, and there is still no client-readable own-identity surface
(§9's `OwnProfile.fingerprint` note), so the app cannot derive the digits itself.
`GetContactSas(fingerprint)` derives them proxy-side. The alternative — caching the code on
the `TenshuIdentity` row — would be a positional reshape of a shipped composite (House Rule
#9) to store a pure function of two values the realm already holds.

**House Rule #9: no bump owed, checked not assumed.** `SchemaShapeBaselineEnforcementTest`
reported both ops as *new types not pinned*, not as changed shapes. Baseline regenerated (two
lines; no unrelated drift this time).

**Deviation from D4, deliberate.** D4's parameter was `sasConfirmed`; it is `verified` and
carries **both** directions, because Q2b also asks for manual un-verify — a contact whose
keyring went mixed needs a way back as well as forward. `false` therefore means *demote*, not
*leave alone*.

**Rules the ops enforce, each test-pinned at the service level:**

- ⚠⚠ **`ATTESTED` is refused in both directions.** It is Rei-carried (v3) and outranks a
  device-local human verdict, so this op must neither claim it nor overwrite it. The mock
  mirrors the rule — if the two disagreed, the screen would be built against a rule the
  service does not have.
- ⚠⚠ **Recording a verdict is a replace-write** over a shipped composite, so every field is
  carried across explicitly. This bit immediately: `grantSeed` is an **optional** field, so
  `identity.grantSeed` (the interface's plain `getField`) *throws*, and the naive version
  failed loudly rather than silently — but the silent version of the same mistake would have
  made the contact un-grantable (`grantSeed` lost) or unreachable (`publicKey` lost). The test
  that caught it asserts the whole row survives.
- An unknown key yields `""` rather than an error from `GetContactSas`: an unknown
  fingerprint and a locked realm are both states the screen renders, not failures it reports.

**The UI guarantee is where the trust rule actually lives.** `VerifyTenshuIdentity` is inert —
it writes what it is told — so *"never auto-elevated"* (A24) has to be enforced at the
surface, and is: confirm is **disabled unless the six digits are on screen**, the code is
fetched per key rather than cached, and a late-arriving code for a sheet the user already
closed is dropped. `confirmingWithNoCodeOnScreenDoesNothing` is the test that matters here.

⚠ **A wart worth knowing.** Elevation stamps `source = QR_SAS`, per D4 — but Q2a chose
read-the-digits over QR precisely *because* the people who need re-verification are the ones
not in the room. The ladder has one "confirmed out of band" value and this is it; the field
records the acquisition path, and no better value exists without reshaping the composite. On
demotion `source` is left alone (un-verifying does not change how the key was acquired).

**Threat-model, co-landed (House Rule #2).** A24 gains a re-verification amendment and §8.13
gains **T-CI-09b** (*a verification recorded without a comparison*). The load-bearing findings:
the A24 invariant is **unchanged** — verification is still never auto-elevated, because
`verified = true` is only ever a record of a human answering *"did the digits match?"*;
**nothing new is exposed**, since the SAS is derived from two public keys and is not a secret;
and there is **no relay, wire or protocol delta**, because re-verification needs no exchange
at all. The reachable-state change is that a realm-device compromise can forge a `VERIFIED`
row at any time rather than only during a live handshake — unchanged in kind, and bounded by
the argument A24 already makes. Residual **Medium**, same consent-failure class as T-CI-01;
the per-app capability model that would close the malicious-app half is Rei Q12, v2 and
unbuilt.

**Already shipped, so not re-done here:** the Q2b demote-on-key-change rule and the §10.2
verified badge both landed in Ph1 (`trustOf`, `TrustBadge`). Ph5 is what makes them
*actionable* — before it, a contact could show *key changed* with no way to resolve it.

**Verification.** `:contact-service-proxy:jvmTest` (7 new), `:contacts-shared-logic:desktopTest`
(49), `:contacts-shared-data:desktopTest`, `:contacts-shared-ui:desktopTest`,
`SchemaShapeBaselineEnforcementTest`, `:control-shared-proxy:desktopTest` + `:control`,
`:contacts-android:assembleDebug`. ⚠ Smoke gate still owed.

### 9.4 Ph6 — source records and the vCard codec

**One new composite, two new ops, and a codec.** `ContactSource` (`sourceKey`, `personId`,
`rawVcard`) keyed on the source key; `SaveContactSource` (upsert) and `ImportContact`
(person + first record in one step); `ContactService.Relations.SOURCES` for the read.

**House Rule #9: purely additive, checked.** The baseline reported four *new* types —
including `ContactSource` itself — and, notably, **adding the `SOURCES` relation to the
shipped `ContactService.Schema` did not register as a reshape**, which is §7's claim about
new composites confirmed mechanically rather than assumed. No version bump, no migration.
Regenerated: four lines, no drift.

**The codec is the engineering.** `:contact-service-common` gains `VCard.kt` (parse /
serialize / split) and `ContactCard.kt` (the editable projection + the merge). Scope per §5:
reads 2.1 and 3.0, writes 3.0, understands `FN`/`TEL`/`EMAIL`/`ADR`/`ORG`/`NOTE` and treats
everything else as opaque lines to carry.

⚠⚠ **The load-bearing design choice: `VCardProperty.value` holds the value still
escaped/encoded, exactly as it arrived.** Round-tripping is therefore byte-exact for every
property nobody edited — `X-` extensions, `CATEGORIES`, `UID`, `PHOTO`, vendor lines nothing
models. Decoding happens only to display, re-encoding only on change. This is what makes
`mergeIntoVCard` safe, and it is why the merge could be written at all.

**Q6's constraint, made executable.** `mergeIntoVCard(original, edited)` walks the original
property list: non-editable properties pass through untouched and in place; an editable one
with a counterpart is rewritten **in situ** (keeping its position, its Apple `item1.` group
and its non-`TYPE` params); removals drop; additions append beside their own kind. A save that
regenerated the card instead would silently destroy everything the editor does not model, and
nothing downstream could detect it. Thirteen codec tests exist mostly to state that one
property from different angles.

⚠ **Known lossy corner, documented rather than hidden:** editing an `ADR` collapses its seven
structured components into one free-text line, because the editor shows an address as one
field. An *untouched* `ADR` keeps its structure exactly. Splitting the editor into components
is the fix when someone wants it; this is the only edit-time loss in the merge.

**Carry-forward the phase forced:** ⚠ **`DeletePerson` now sweeps sources too.** Q7a always
said *"sources **and** identities"*; until this phase there were simply no sources to sweep.
Leaving them would strand the whole contact card — name, numbers, addresses — in rows nothing
renders. Test-pinned by reading the relation back, not by counting calls.

**Read-through union (`unionDetails`).** One person may have been imported *and* typed into,
so the detail screen merges across records: multi-values concatenate in source order,
de-duplicated by value (keeping the first occurrence's label); single-valued fields take the
first non-empty. Display-time only — it is never written back, so unioning cannot collapse two
sources into one. Search widens to numbers, emails and organisation, which is how people find
contacts they never named carefully.

**Deliberate scope calls:**

- **`.vcf` *text* in, text out.** `importVcards(text)` / `exportVcard(personId)` — the platform
  file dialogs are Ph7's, so the seam is already the shape they will need.
- ⚠ **Each imported card is a new contact.** Recognising that an import duplicates someone is
  merge (D7), user-assisted and late by design; folding them silently is what D7 forbids.
- **Editing targets the first record** of a person with several. Editing a specific record
  among several is not a surface that exists, nor one the UI could express yet. A contact with
  no record gets a `manual:` one keyed by `personId` (D5) — idempotent if two devices create it
  at once.
- **The editor says what it does not show.** *"Anything else your imported card holds is
  kept"* — a user who sees five fields and knows their card had more would otherwise
  reasonably assume editing threw the rest away.
- **`ContactDetails` mirrors the codec's `ContactCard`** rather than reusing it:
  `:contacts-shared-logic` has no service dependencies (Ph0), which is what lets the mock and
  the view models exist without a proxy. `DetailMapping.kt` in `:contacts-shared-data` is the
  only place the two meet.

**Verification.** `:contact-service-common:jvmTest` (13 codec), `:contact-service-proxy:jvmTest`
(6 new source/cascade), `:contacts-shared-logic:desktopTest` (62),
`:contacts-shared-data:desktopTest` (20, incl. read-through union + a merge round trip through
the store), `:contacts-shared-ui:desktopTest`, `SchemaShapeBaselineEnforcementTest`,
`:contacts-android:assembleDebug`. ⚠ Smoke gate still owed. No wire, storage-format or relay
change; no threat-model delta (§7: Ph6 widens A24's *contents*, not its trust rules).

### 9.5 Ph7 — platform import, and the honest shape of an unverifiable phase

**Nothing new in the service layer.** Ph6 deliberately left the seam taking `.vcf` *text*
(`ContactsRepository.importVcards`), so this phase adds no op, no composite, no schema change
and no baseline movement — it supplies bytes. That was the point of drawing the line there,
and it held.

**The seam.** `ContactImporter` in `:contacts-shared-ui` `commonMain`, obtained through a
`@Composable expect fun rememberContactImporter()` — the `RecorderPermissionGate` shape from
`:fit-shared-ui`, and `@Composable` for the same reason: the Android actual holds
`ActivityResult` launchers, which may only be registered during composition. Three sources
(`OneContact` / `File` / `AddressBook`), each declared through `supports(...)`, so the sheet
renders what is true on this platform without either shell knowing about the other.

⚠⚠ **This is the first phase where most of what was written cannot be verified here.** A
`ContactsContract` query needs a device; an AWT file dialog needs a desktop session. What
that bought was a hard rule about where logic may live: **the actuals contain no logic beyond
producing a string.** Every branch — what a count means, what an empty file means, what a
cancel means, what a denial offers next — is in `ContactImportViewModel` and
`ImportSheetContent`, both of which run under test. The five sheet tests fake the seam, which
is exactly the thing that cannot be faked on a device.

**Android reads through `CONTENT_VCARD_URI`, not the `Data` table.** Both contact sources ask
the platform to serialise the contact itself. That is what makes the file small *and*
lossless: structured names, multiple labelled numbers, postal addresses and vendor `X-` lines
all survive into the `rawVcard` Ph6 stores verbatim. A hand-rolled MIME-row mapper would have
had to enumerate every type it knows and would have silently dropped the rest — the
loss-by-omission the Ph6 merge rule was written against, reintroduced at the front door.

**`READ_CONTACTS` is asked for once, at one call site.** Ph0 deliberately left it out of the
manifest; it arrives here, and only the *whole address book* row requests it. Picking one
person and importing a `.vcf` are both permissionless, which is why they are listed first —
the sheet is ordered by what each source costs the user, cheapest first, and the address-book
row states its cost in its supporting line rather than letting the OS dialog be the first
news of it.

⚠ **The permissionless picker has a known soft spot, handled rather than hidden.** The OS
contact picker grants access to the URI it returns, and `CONTENT_VCARD_URI` is a different
path — so on some builds that read is refused even though the user just chose the contact.
Caught, never crashed on: it surfaces as `PermissionDenied`, whose copy points at the
address-book row, which asks properly. The alternative — requesting `READ_CONTACTS` up front
so this can never fail — would make the permissionless path permissioned, which is the whole
reason it is offered first. **On-device verification of which OEMs extend the grant is
owed.**

**Address-book reads go one card at a time**, not through `CONTENT_MULTI_VCARD_URI`, which
takes the lookup keys joined into the URI itself and so has a length ceiling nobody
documents. A few thousand small reads is a one-off cost on an explicit user action; a
truncated import would be a silent one. Lookup keys are de-duplicated first — importing the
same person twice is precisely what D7 forbids doing accidentally.

**Desktop is `.vcf` only, by decision.** macOS, Windows and the Linux desktops each keep an
address book behind their own API, and every one of them exports `.vcf`. Three native
integrations to reach data the user can hand over in one step is not a trade worth making, so
the two contact sources report unsupported and the sheet simply does not offer them — absent,
not greyed out, because a disabled row reads as something the user failed to switch on.

⚠ **iOS is deferred to Ph10, on structure rather than preference.** There is no `contacts-ios`
shell, so a `CNContact` importer would have no consumer to compile against, and
`:contacts-shared-ui` has no iOS source set at all (android + desktop, per Ph0). The seam is
`expect`/`actual` with two actuals for exactly that reason. When the shell lands, iOS import
is one actual against an interface that already exists.

**Small correctness carry-forward:** the mock's `importVcards` now **fails** on text holding
no cards instead of succeeding with zero, matching the real implementation. Ph7's UI branches
on the difference between *"that file was not an address book"* and *"none of its cards could
be stored"*, and a mock that collapsed them would have made one of those branches untestable.

**D7, stated before the import rather than discovered after it.** The sheet says plainly that
everyone imported is added as a new contact even if you already have them. Pinned by
`the same file twice gives you everyone twice`, which exists to make that behaviour a
decision rather than an accident.

**Verification.** `:contacts-shared-logic:desktopTest` (69, incl. 8 new import ones),
`:contacts-shared-ui:desktopTest` (13, incl. 5 new sheet ones over a stand-in importer),
`:contacts-desktop:compileKotlinJvm`, `:contacts-android:assembleDebug`. No service, wire,
storage-format or relay change; no baseline movement; no threat-model delta (§7: Ph7 widens
A24's *contents*, not its trust rules — imported cards are local personal-realm rows like any
other). ⚠ **Owed on device:** the OS contact picker and its grant behaviour, the
`READ_CONTACTS` flow, a real address-book read at size, the desktop file dialog, and the
smoke gate.

### 9.6 Ph7b — design alignment with Drive, and a `:ui-core` reuse audit

**Why an in-between phase.** Ph1–Ph7 built the app screen by screen and each screen picked
its own chrome. That is how a second idiom gets into a design system without anyone deciding
to add one. This phase is a pass over what `:ui-core` already ships, adopting what fits and —
just as importantly — recording what does not, so the next app does not have to re-derive it.

**Three adoptions.**

1. **`AppSearchBar` replaces the hand-rolled field.** Ph1 rendered an `AppTopAppBar` titled
   *Contacts* with an `AppTextField` beneath it, carrying its own magnifier and clear icons —
   a re-implementation of the component `:ui-core` ships and Drive's `FilesSearchBar` already
   wraps. The bar is now the top strip, with no title bar above it: the nav item already says
   *Contacts*, and spending a whole bar repeating it is what cost the search field its place.
2. **The `+` is a FAB.** `AddContactFab` is Drive's `AddFileActionsFloatingActionButton`
   shape — a FAB bottom-end over the content, opening an `AppBottomSheet` of the ways to
   acquire something. Contacts has exactly two that are not a handshake (*add by hand*,
   *import*), and Ph7 had just made that two icon buttons in a top app bar: the app's primary
   action in the corner furthest from the thumb, with the rarely-used import at equal
   billing. ⚠ Inviting is deliberately **not** in the sheet — it lives on the *You* card (D6),
   because an invitation is something you send *about yourself*.
3. **`keyboardShortcuts` + `AppShortcuts.focusSearch`.** Ctrl+F / Cmd+F focuses the bar,
   the same binding Drive's Files screen carries. ⚠ Worth noting what the audit turned up:
   before this, `:ui-core`'s shortcut catalogue had **exactly one consumer in the repo**
   (Drive's `FilesScreen`) despite being written to be shared.

⚠ **One deliberate behaviour change rides along.** Results now live in the search bar's
expanded surface and the list behind stays whole, where Ph1 filtered the list in place. That
is the shape `AppSearchBar` is built for, and it is Drive's: searching becomes a distinct
gesture with somewhere to go back to, rather than a query that silently shrinks the address
book. Consequences, all pinned by test: `visibleContacts` became `searchResults`; a blank
query answers with **nothing** rather than everything (an expanded bar listing the same rows
as the screen behind it is noise); and collapsing drops the query, so a stale one cannot sit
invisible behind a collapsed bar.

**Two deliberate non-adoptions.** Both are places where "reuse `:ui-core`" would have meant
taking a side in a question the repo has not settled, and doing it in the app least entitled
to.

- ❌ **`AppDialog`.** Contacts' six dialogs use Material's `AlertDialog`. So does most of the
  repo: raw `AlertDialog` appears in **18 files across 7 modules** (Home, Fit, Timeline,
  Control, Drive and Contacts), while `AppDialog` has **6 files across 2** (Drive and
  Control). Contacts is not the outlier here; migrating it would just move one app across a
  line nobody has drawn. ⚠ `AppDialog`'s KDoc claims a real reason to prefer it — desktop AWT
  focus behaviour — but its implementation is a plain `Dialog` in `commonMain` with no
  platform variant, so the doc and the code disagree. **Settling that is a `:ui-core`
  question, not a Contacts one.**

  *(**Settled** — see `ui-core/ui-agent.md` §8.0. The doc-vs-code contradiction resolved
  against the doc, and harder than expected: the claim was not merely unimplemented but
  **unimplementable by that code**, since it opened the very `ui.window.Dialog` it said it
  avoided — the same primitive `AlertDialog` opens internally. `AppDialog` now wraps
  `AlertDialog`, so the two idioms stopped being different things and the "which is right"
  question dissolved: it is a fit choice. No mass migration — with the fork gone there is no
  behaviour delta to chase across 26 call sites. Contacts' own `NameDialog` moved onto it as
  the worked example of the don't-wrap-the-wrapper rule.)*
- ❌ **A new `AppFab` in `:ui-core`.** Tempting, because the placement idiom
  (`.align(BottomEnd).padding(spacing.l)`) is repeated verbatim at **five sites across three
  apps** (Drive ×2, Home ×2, Control ×1) with no shared wrapper. But adding one here and
  using it in Contacts alone would make it 1 consumer out of 6 — a second idiom, which is the
  exact problem this phase exists to remove. Contacts uses the existing idiom; **`AppFab` at
  n = 6 is the follow-up**, and it should land as one PR across all six call sites with each
  app's smoke gate.

  *(**Done** — see `ui-core/ui-agent.md` §8.1. ⚠ Two corrections to the framing above, found
  once the sites were read side by side: it is **two** components, not one — four sites are
  icon-only, two are Material **extended** FABs with a visible label and the opposite
  accessibility rule — and only four of the six place the FAB by hand at all, the other two
  using a `Scaffold` slot that owns placement. The "repeated verbatim" claim was also too kind:
  the four hand-placed sites wrote the same 16 dp two ways, and the divergence ran **inside**
  Drive, where `FilesScreen` used `spacing.l` while `HomeScreen` and `FolderScreen` used a raw
  literal.)*

**A Ph1 defect this phase surfaced rather than caused.** The shell passed
`contentWindowInsets = WindowInsets.systemBars` to `AdaptiveNavScaffold` under a comment
asserting that `AppTopAppBar` does not consume the status-bar inset itself. ⚠ It does — and so
does M3 `SearchBar` behind `AppSearchBar`, which offsets its collapsed bar by the *unconsumed*
top inset in its own measure policy. So the inset landed twice: the scaffold turned it into
`innerPadding`, `Modifier.padding` does **not** mark it consumed, and the screen's own bar
padded by it again. All four Contacts screens open with such a bar, so all four were affected
from Ph1; swapping the list's `AppTopAppBar` for an `AppSearchBar` only made it the worst of
them, by adding that component's 16 dp collapsed docked padding on top — ~100 dp of dead space,
which is how it was finally noticed. Fixed by deleting the override; the shell-level
Control-unreachable `ErrorBanner` becomes the top element while it is up, so the column takes
`windowInsetsPadding(statusBars)` for exactly that case. ⚠ Contacts-specific: Fit, Home and Mia
pass the same override, but their screens have **no** inset-consuming chrome, so theirs is
correct. Rule + failure mode now in `ui-core/ui-agent.md` §8. ⚠⚠ **No test can guard this** —
window insets are zero on desktop.

**Also available and still unused, recorded rather than adopted:** `PreviewBox` (only
`:ui-core` and `:control-shared` use it — Drive, Fit, Timeline, Home and Contacts all ship
without `@Preview`s, so adopting it in Contacts alone would not be alignment either).

**Verification.** `:contacts-shared-logic:desktopTest` (73 — the search test rewritten around
the new split, plus collapse-drops-the-query and the sheet-closes-into-the-dialog),
`:contacts-shared-ui:desktopTest` (16 — 11 screen + 5 sheet, including the FAB sheet holding
both acquisition paths and an unmatched query never claiming the list is empty),
`:contacts-android:assembleDebug`, `:contacts-desktop:compileKotlinJvm`. UI-only: no service,
schema, wire or relay change. ⚠ Owed: the maintainer's own click-through — a chrome change is
the kind of thing a render test can confirm the structure of and not the feel.

### 9.7 Ph8 — contact photos

**One new composite, three new ops, one deferred property.** `PersonAvatar` (`personId`,
`imageData`) keyed on the person; `SetPersonAvatar` / `ClearPersonAvatar` (service ops) and
`GetPersonAvatar` (a **chain** op with a `Person` receiver, the `GetPreview` shape) resolving
`Person.Avatar`; `ContactService.Relations.AVATARS` for the read.

**House Rule #9: purely additive, checked.** The baseline reported **five new types and no
changed shape** — `Person` did not move despite gaining a dynamic property, which is §7's
claim about code-side schema metadata confirmed mechanically. No version bump, no migration.

**Q4 implemented as resolved, both halves.** The sibling row is the source of truth and the
deferred `dynamicProperty` reads it. ⚠ The row's reason is structural and worth restating,
because it is the kind of thing a later "simplification" undoes: **`RenamePerson` (Ph4) is a
replace-write.** An avatar on `Person` would be destroyed by a rename, silently — the
`MoveFile` deletion-by-omission loss class. `renaming a contact does not lose their photo` exists to fail if
anyone moves it.

⚠⚠ **The deferred property needed *three* registrations, and the test caught two missing
ones.** Q4's carry-in warned about the client `roFactory`; in practice the property also needs
`operation(GetPersonAvatar.Schema)` **and** `dynamicProperty(fromId, property)` in the
**common** module. The first attempt resolved to `null`, the second died as
`InternalErrorDto([Not a data object])` — the exact opaque failure the `Workout.Streams` /
TS-4 note describes. Third find: ⚠ **the receiver arrives as `caller`, not in `params`**, and
in two shapes (`Entity<Person>` on a projection read, a bare `Person` when called directly) —
reading the declared `PERSON` param yields nothing. `the deferred avatar property resolves
through the chain` is the only test in the tree that exercises any of this, because the
repository deliberately does not use the property (below).

**Where the photo actually comes from: a card's `PHOTO`.** `readContactPhoto` in
`:contact-service-common` is the one reader of the property Ph6 carries opaquely, and it only
ever reads — `ImportContact` promotes the image to a row without rewriting the card, so
`rawVcard` stays lossless and the avatar is a second copy rather than a move. Handles the
three encodings exporters emit (3.0 `ENCODING=b`, 2.1 `ENCODING=BASE64`, and the `data:` URI
Android writes even in cards it labels 3.0).

⚠ **A `PHOTO;VALUE=uri:` is never fetched.** A URL is not an image, and resolving one would be
egress on behalf of imported data — House Rule #1 puts all network behind Control, and this
would route around that for a contact photo. Test-pinned in both spellings, including a card
that claims `ENCODING=b` *and* hands over a URL.

**Deliberate scope calls:**

- ⚠ **Stored inline, not on the content plane.** A vCard `PHOTO` is a small embedded image and
  the `File.Thumbnail` precedent is exactly this shape. D1's other source — *pick from Drive*,
  which is content-addressed — waits on the §5 picker track and will owe this composite a
  reshape: a version bump plus a `SchemaMigration`, which House Rule #9 now makes loud and
  supported rather than a wipe. Adding a speculative `contentId` today would guess at a shape
  nobody has built.
- ⚠ **A 256 KiB ceiling, refused rather than downscaled.** The row syncs **eagerly to every
  device**, so a 4 MB portrait embedded by some desktop address book would cost all of them
  for a 40 dp glyph. Downscaling instead means a platform image decoder inside a service
  module. Over-sized cards still import — only the photo is left behind, and it survives in
  `rawVcard`.
- **No `setAvatar` on the repository seam.** Import is the service-side source and
  pick-from-Drive does not exist yet, so a setter would be a seam method nothing exercises —
  which is the rule Ph2 set. *Remove photo* is offered (and only when there is one), with copy
  saying the card keeps its picture.
- ⚠ **The repository reads the rows, not the property.** It already streams the sibling
  relations for identities and sources, so avatars are a fourth `combine` argument rather than
  a second read mechanism — and it sidesteps the deferred-property rule that a one-shot read
  resolves to `null`, which Q4's own carry-in (3) flags for `getAll()` callers. The property
  earns its place for *chain* consumers projecting a photo beside a person; nothing in-tree is
  one yet, so the test above is its only exercise.
- ⚠ **`Contact` needs hand-written `equals`/`hashCode` now.** `ByteArray` equality is
  reference identity, so the generated data-class equality would call two identical contacts
  different on every re-read and recompose the whole list. (The trap
  `FileObjectStore.rehydrateFromDisk` hit — `ios-drive-parity.md` P2b.)
- ⚠ **Undecodable bytes fall back to the monogram**, never to a broken-image glyph: Skiko
  ships no HEIF codec, and a contact whose picture this platform cannot read is still a
  contact.

⚠ **Q3a/Q3b are deferred with Ph8's other half, and the reason is structural.** The §6 phase
text bundled them here, but the delete-path note fires when *a Drive file a contact uses* is
deleted — and in Ph8 no avatar can be a Drive file, because the only source is a card's
embedded `PHOTO`. There is no trigger to attach the note to and no `contentId` for Drive to
match on. Both land with pick-from-Drive.

⚠ **A second `decodeToImageBitmap` now exists** (`:contacts-shared-ui`, beside
`:drive-shared-ui`'s). Not shared, because Drive's is genuinely a different function — it
carries the macOS ImageIO/HEIC bridge (`:macos-helpers`) and a two-pass downsample against
Android's canvas ceiling, both for full-resolution *viewing*. Graduating one into `:ui-core`
would drag `:macos-helpers` in with it, and per §9.6 that is a `:ui-core` decision, not one for
Contacts to settle in passing.

**Verification.** `:contact-service-common:jvmTest` (10 new photo-codec),
`:contact-service-proxy:jvmTest` (36 total, 12 new incl. the chain-resolution test),
`:contacts-shared-logic:desktopTest` (75), `:contacts-shared-ui:desktopTest` (18),
`:contacts-shared-data:desktopTest` (20), `:drive-shared-logic:desktopTest` +
`:control-shared:desktopTest` (the other `:contact-service` consumers),
`SchemaShapeBaselineEnforcementTest` regenerated (5 new lines, no shape change),
`:contacts-android:assembleDebug`, `:contacts-desktop:compileKotlinJvm`. No wire,
storage-format or relay change; no threat-model delta (§7 anticipated one for
*content-plane* photos — inline eager rows are ordinary personal-realm A24 contents). ⚠ Owed:
the smoke gate, and a real card with a real embedded photo on a device.

### 9.8 Ph9 — user-assisted merge

**Two new ops, no new composite.** `MergePersons(source, target)` folds one contact into
another; `LinkTenshuIdentity(fingerprint, personId)` re-points a single key — the op Ph4
deferred *"for want of a caller"*, which Ph9 gives two. Baseline: two new types, **no shape
change**, so House Rule #9 owes nothing again.

**D7 is enforced by where the code lives, not by discipline.** `:contact-service` contains
**no heuristic at all** — `MergePersons` takes two ids and does as it is told. Candidate
suggestion is `findMergeCandidates`, a pure function in `:contacts-shared-logic` over the
already-loaded contact list. Nothing proxy-side or wire-side can act on a resemblance, which
is what makes *never auto-merge* structural rather than a rule someone has to remember. The
reason it matters: everything a handshake delivers is self-asserted, so a system that merged
on a name match would let an attacker's key inherit a known person's contact card.

**The heuristic, and what it refuses to do:**

- Shared **phone** (strongest) → shared **email** → same **name** (weakest), each recorded so
  the row can say which fired. A name-only match is flagged `isNameOnly` and the copy hedges
  — two people genuinely can share a name.
- ⚠ Phone comparison is digits-only on the **last nine**: `+44 7700 900123`, `07700 900123`
  and `00447700900123` are one number written three ways, and a string compare finds none of
  them. Numbers shorter than nine digits compare whole.
- ⚠⚠ **Two *connected* contacts are never suggested on name alone.** Both completed a
  handshake, so the user separately confirmed each exists; a name collision between them is
  far likelier to be coincidence than duplication, and the cost of merging is higher — an
  unfamiliar key silently joins a known contact's keyring. Shared phone or email still
  surfaces them, because that is evidence rather than coincidence.

**What merge preserves, and why each mattered enough to pin:**

- **Each key keeps its own verification, timestamp and source.** Verification is a fact about
  the *key*, so re-pointing it changes none of them; re-stamping any would fabricate history.
- **Keyrings union rather than compete.** The merged contact's badge is then whatever Q2b
  makes of the combined ring — a verified key beside an unverified one reads *key changed*,
  which is exactly right for someone who just acquired an unfamiliar key. **Merge needs no
  trust logic of its own**, and that is why a mis-merge is cosmetic rather than a security
  error.
- **Both contacts keep every source record, byte for byte.** A person legitimately has
  several and Ph6's union already renders them; combining the payloads would be the
  regenerate-don't-merge mistake Q6 forbids. `X-VENDOR-THING` is the canary in the test.
- **The target's photo wins**; only a contact without one inherits.
- ⚠ **`grantSeed` survives** — the Ph5 optional-field trap, re-armed by another replace-write.
  It caught the *test's own assertion* this time before it caught anything else; the
  implementation was already reading it optionally.

**Guards, because two of the inputs are dangerous:** merging into `""` is refused (that is
the *not-yet-linked* marker, and a merge involving it would sweep every unlinked key in the
realm onto one contact), as is self-merge (it would delete the person it had just moved
everything onto) and a target that does not exist.

⚠ **There is no un-merge, and the UI says so before the user commits.** The rows that moved
carry no memory of where they came from. Recovery means re-creating a contact and re-pointing
its keys by hand — possible now that `LinkTenshuIdentity` exists, but not a button. That is
the honest cost of the sibling-row model being simple.

**Surface calls:**

- **A banner on the list, not a tab.** *Possible duplicates* is empty for almost everyone
  almost always; a permanent tab for a screen that usually says "nothing here" taxes every
  other visit. It sits **below** the invite confirmations — somebody waiting to be saved is
  more urgent than two rows that look alike.
- **The only question asked is which side survives**, because it is the only choice that
  changes the outcome (the target's petname is what remains). The default prefers the
  connected contact, then the one with more detail — a default, not a decision.
- **Candidates are derived, never stored.** A merge on another device simply makes the pair
  stop being suggested: no dismissal state to sync, no stale list. ⚠ The corollary is that
  *"Not now"* does not exist here either — dismissing the dialog leaves the pair suggested,
  which is honest but will want revisiting if anyone finds a pair they want permanently
  ignored.
- ⚠ A review whose pair stops existing (merged elsewhere, one side deleted) closes itself.
  Holding a dialog open over a stale pair is how a merge ends up hitting a contact that is
  already gone.

**Verification.** `:contact-service-proxy:jvmTest` (50 total, 14 new — most of them about
what must *survive*), `:contacts-shared-logic:desktopTest` (95, 20 new),
`:contacts-shared-ui:desktopTest` (21, 3 new), `:contacts-shared-data:desktopTest` (20),
`:drive-shared-logic:desktopTest` + `:control-shared:desktopTest`,
`SchemaShapeBaselineEnforcementTest` regenerated (2 new lines, no shape change),
`:contacts-android:assembleDebug`, `:contacts-desktop:compileKotlinJvm`. No wire,
storage-format or relay change; no threat-model delta — merge moves rows within the user's own
personal realm and changes no trust rule (A24's *never auto-elevated* is untouched, since no
verification is ever written by a merge). ⚠ Owed: the smoke gate, and a real duplicate pair on
a device.

**With Ph9, the §6 phase map is complete except Ph10 (the iOS shell).**

### 9.9 Ph10 — the iOS shell, and an honest account of what could not be verified

**Three units, the `maps-ios` P5 shape** (`ios-fit-timeline-maps-parity.md` §3.3): the umbrella
export, a `ContactsSurface` seam + `ContactsLocalRuntime`, and the SwiftUI shell. Contacts is
**born on route 1** — it never had an EPC tier, so there is nothing to migrate off and no
`EpcContactsClient` beside the runtime. `ios-first-party-route-1-migration.md`'s phase map was
already complete; this is a new app arriving on the finished substrate.

⚠⚠ **Read the verification section at the end before treating this as shipped.** Roughly half
of it could not be compiled on this builder.

**The find that came before any code: a D12 name clash, and it pointed at Drive.**
`:contacts-shared-logic` declares `ContactsRepository` and `RepositoryResult`; so does
`:drive-shared-logic`, which is already on the umbrella and whose `drive-ios` shell ships. K/N
resolves an Obj-C name clash by appending `_` to whichever **fully-qualified name sorts later**,
and `app.tenshu.contacts` sorts *before* `app.tenshu.drive` — so the export would have silently
renamed **Drive's** types and broken a shipped app. That is D12's exact incident (the Maps
export renamed Timeline's `Place`), and D12's rule is that the newcomer carries the annotation:
`@ObjCName("ContactsAppRepository")` / `@ObjCName("ContactsRepositoryResult")`. ⚠ Only the
sealed parent needs annotating — nested `Success`/`Failure` derive from it. A sweep of every
other exported module found no further collisions.

**`ContactsSurface`** is Maps' shape with one seam instead of two (every Contacts screen reads
the same repository): `installRepository` / `installNotConnected` / `installMock`, a
`controlReachable()` passthrough, `repository()` for the shell's few direct reads, and one
factory per view model. ⚠ View models are built **per call**, not cached: SwiftUI owns their
lifetime through `@StateObject`, and a cached instance would outlive its screen and keep
collecting.

⚠⚠ **The default is the honest not-connected repository, and it matters more here than in any
sibling app.** A mock behind the shipping gate would put invented people in front of someone who
has connected nothing — complete with trust badges implying key material that does not exist.
The whole point of the contact graph is that its contents were confirmed by a human. Every read
is empty, every write fails with a stated reason, and `controlReachable` emits **`false`, not
`null`** (`null` means *not yet known*, and the shell shows no banner while it waits).

**`ContactsLocalRuntime`** is a thin `FirstPartyLocalRuntime` consumer needing **no new service
wiring** — the route-1 graph already hosts `:contact-service` and `:preference-service` (D6's own
name is the *platform* name Drive and Fit's profiles share, not an app-private copy). That claim
is what `ContactsLocalRuntimeTest` exists to prove, and ⚠ emptiness alone would not: an unwired
service throws into the repository's catch and returns the same empty list, so the assertion
carrying the weight is the **reachability verdict**.

⚠ **The foreground rehydrate is load-bearing here specifically.** Control writes the contact
graph too — it hosts the invitation handshake — so a contact accepted while this app was
backgrounded is a co-resident write that nothing else picks up offline.

**Shell scope, and what it deliberately leaves out.** The read surfaces plus the actions that
are one control each: the badged list (native `.searchable`, the `drive-ios` §24.5 call), the
detail with its keyring and the Q2a compare-and-confirm alert, the D6 profile card, and the Ph9
duplicates review. ⚠ **Not** here: manual add, import, the invitation sheet and the completion
prompt — each needs a native equivalent of a Compose sheet, and the read surfaces are what prove
the runtime. Also absent: an app icon (the icon track has not reached Contacts) and the
`MapBearingApp` template, which would pull MapLibre and ~40 MB of tiles into an app with nothing
to plot.

⚠ **Entitlements from the first commit** (Keychain + App Group, sim and device variants).
`timeline-ios` shipped for a while with neither, found in route-1 migration P3-2: a shell with no
Keychain can never see provisioning, so the gate reads not-connected forever.

#### ⚠⚠ Verification — what is real, and what is written but unchecked

This is the least-verified phase of the track, and the split is sharp:

| | Status |
|---|---|
| `:contacts-shared-logic`, `:contacts-shared-data` — `iosArm64` + `iosSimulatorArm64` | ✅ **really compiled** (klibs on disk) |
| The `@ObjCName` annotations compile + all JVM/Android tests still green | ✅ |
| `ContactsSurface.kt`, `ContactsLocalRuntime.kt`, `ContactsLocalRuntimeTest.kt` | ❌ **never compiled** |
| The generated Obj-C header — i.e. the D12 clash actually resolving | ❌ never observed |
| `contacts-ios` Swift sources, `xcodegen generate`, `xcodebuild` | ❌ never built |
| `ContactsLocalRuntimeTest` (10 tests) | ❌ never run |

⚠⚠ **`:tenshu-sdk-ios`'s Kotlin/Native compile is `SKIPPED` on a Linux host** — the umbrella
applies **SKIE**, an Apple-host-only compiler plugin, so the task reports `BUILD SUCCESSFUL`
while compiling nothing. That is a genuinely misleading signal and it is worth stating plainly:
a green Gradle run on this builder does **not** mean the umbrella's iOS sources compile.
Framework *linking* is skipped for the same reason, which is why the D12 resolution above is
reasoned and annotated rather than seen.

What was done instead, since the compiler could not help: every API the two new umbrella files
call was checked by hand against its declaration — `FirstPartyLocalRuntime.Config`,
`assembleBase`'s five parameters, `Base`'s two fields, `isProvisioned` / `rehydrate` /
`standaloneDelegate` / `hasResidualCustody` / `purgeCustody`, `devRelayOverride`,
`contactsControlReachability` and `TenshuContactsRepository`'s constructor. That catches
signature drift; it does not catch type errors, SKIE export problems, or anything Swift.

**Owed, and this is the whole list:** a macOS build of `:tenshu-sdk-ios` (compile + link + the
XCFramework), `xcodegen generate` + `xcodebuild` for `contacts-ios`, the 10 runtime tests on a
simulator, a look at the generated header confirming Drive's names are untouched, and the
Control-genesis A/B pass every sibling shell owes.

### 9.10 v2 — big photos on the content plane (the S0b `rawVcard` blocker)

**Why this is here, and why now.** `ContactSource.rawVcard` kept a card's `PHOTO` losslessly, so an
imported desktop contact with a multi-MB portrait was a row — and a single-entity sync block —
bigger than the iOS SQLite store's `ROW_MAX` (512 KiB), refused with `RowTooLarge` on every attempt
([`ios-shared-store-sqlite.md`](ios-shared-store-sqlite.md) §4.2.1, F6s). It had to close before the
App Group build ships. **This section is the design's home** (the feature owns it); the S0b doc
points here. The maintainer's decision, 2026-10-31: **move only big photos out** — the card stays
inline as text, a `PHOTO` above a threshold goes to the content plane (the plane `File.Data`,
the thumbnails and `Workout.Streams` use), referenced from the card, and the card is reassembled
byte-exact wherever the full card is needed. Contacts stay readable offline; only the big image may
need fetching.

**The decisions.**

| | Decision | Why |
|---|---|---|
| Threshold | A `PHOTO` content line over **64 KiB** (UTF-8 bytes of the raw line, folds included — ≈ 48 KiB of image) moves. Then a **card budget of 256 KiB**: a card still over it moves its remaining `PHOTO` lines, largest first. `ContactPhotoContent` in `:contact-service-common`. | Every phone-exported avatar (96–720 px JPEG) stays inline; a desktop portrait moves. An inline photo is at most an eighth of `ROW_MAX`, and the budget keeps many medium photos from adding up to a refused row, leaving half the row for its other fields, the encoding and the block envelope. ⚠ The budget bounds only photos — see open item 1. |
| Reference | Each moved line is replaced **at its own position** by `X-TENSHU-PHOTO-REF;SHA-256=<hex of the moved span>:<index>`; the moved spans travel as **one framed blob** (`TCPHOTOS1`, a count, then length-prefixed spans) — `ContactSource.Photos`, a `dynamicProperty(Syncable(OnDemand), PrimaryContent())`, addressed by `ContactSource.ContentId` (a `valueProperty` bound to `ContentReferencing.CONTENT_ID`). The `Workout.Streams` registration set exactly: op + dynamic property + value property + requirement in common, the `GetContactSourcePhotosRo` in client, `ContactPhotosPropertyWriter` in the one `DelegatingExternalPropertyWriter` of every runtime. | An `X-` line is one every vCard reader must carry or ignore, so the stored card stays a well-formed card: `parseVCard` / `readContactCard` read it and the editor never sees it, and `mergeIntoVCard` passes it through like any property it does not model — which is what lets an **offline edit** save without touching the photo. The span (not the decoded image) is what moves, so reassembly is a splice: byte-exact by construction. The digest makes a placeholder *ours*: a blob of other photos is refused, never spliced in. |
| Export / share / re-encode without the bytes | **Fail visibly.** `fullVcard` (`:contact-service-client`) reads `ContactSource.Photos` through a Maybe `resolve()` — the terminal that materialises an on-demand property, so a blob not yet on the device is fetched first — and answers `PhotosUnavailable` when it still cannot; both export paths (`TenshuContactsRepository`, the web SDK) turn that into `error_contacts_photo_unavailable`. ⚠⚠ Never the stored card in the photo's place. | Not-connected is a failure, never an empty success. The iOS surface has no export (`ContactsSurface.exportVcard` is `unavailable()`), so it owes nothing yet. |
| `PersonAvatar` promotion | Unchanged, and read from the card **as imported** — before its photos move. A 120 KiB portrait both moves and is promoted. | Promotion only ever read the incoming card; the 256 KiB avatar ceiling and its reason are untouched. |
| Refcount and reclamation | **Generic, by declaration.** `ContactSource` carries `ContentReferencing`, so every push of the row names its edge (`contentEdgesOf`); a fresh **random** content id per relocation leaves superseded photos declared with no live edge (CR-5) rather than overwritten under a live id. A save that drops the photos states a **blank** id, so the edge reads `none`. `DeletePerson` now **tombstones** every row it deletes — the source's tombstone is what retires the edge. Local blobs go only at the sweep, once nothing names them (§9.10.1). | ⚠⚠ Found while building this: `DeletePerson` emitted **no tombstones at all** (`SyncObserver.onDataDeleted` is a deliberate no-op; the tombstone is the op's duty, `DeleteWorkout`'s note), so a deleted contact stayed on every other device. With content on the plane that would also have meant photos never reclaimed. ⚠ `UnlinkTenshuIdentity`, `ClearPersonAvatar` and `MergePersons`' person/avatar deletes still emit none — open item 3. |
| Every write states the content id | `writeContactSource` (`:contact-service-proxy`) is the **one writer** of the row — save, import and the relocation pass — and `saveContactSourceRow` saves an entity with the slot **set**, blank or not. `MergePersons`' relink carries it. | ⚠⚠ Measured, not assumed: a bare-composite save **keeps** the stored row's content id (the store merges the property map), so a save that removed a card's photos would have gone on declaring them for ever; the opposite failure (a payload without the slot reads `none`, audit W8) is why the relink must carry it. `the row declares its photos as a content edge - and a photo-less save declares none` went red on exactly the first. |
| Migration of existing big-photo rows | ⚠ **Superseded 2026-11-05:** the re-cut (`core-schema-evolution.md` Appendix A.6) left no pre-§9.10 row anywhere, so the v1→v2 step and the relocation pass are **deleted**; the schedule now runs only the superseded-blob sweep, as `SweepContactPhotos` (no network, battery not low). *As built before that:* **Lazy, plus a bounded pass.** The v1→v2 `SchemaMigration` is **empty** — a step is pure (`MigrationContext`: no store write, no IO, no emit), and a move is a `FileDatabase` write plus a content emit. A row moves at its next save, or in `RelocateContactPhotos`: a `HeadlessSafe` op on a periodic schedule (24 h; **any network, battery not low** — not idle + charging like its neighbours, because until a row moves it is too big for the iOS store), at most 32 rows a run, through the same writer, each re-checked against the row it replaces (§9.10.1); each run ends with the superseded-blob sweep. Registered on Android, desktop and iOS; not on web (no schedules there). | Until it moves, an old row reads, edits and exports exactly as at v1 (no placeholder, nothing to fetch). ⚠ A row over `ROW_MAX` that an iOS store already refused stays refused until a device holding it moves it — Android/desktop Control does, and the moved row then syncs to the iPhone as a small one. |
| Older peers | **Lockstep, by the bump.** `ContactService` v1 → **v2**: the row gains a named slot a v1 reader cannot decode and a placeholder a v1 reader would export as-is, so v1 devices are pull-fenced by the realm epoch (`core-schema-evolution.md` §7.3) and v1 clients refused at announce. ⚠ A fenced v1 device may still **push** its pending writes (push-allowed), so a big inline card can arrive after the bump; it is stored as it is and moved by the next pass. | HR #9. The baseline moved 31 contact types v1 → v2 and added `getContactSourcePhotos`; no composite changed its positional layout. |

**Threat model (House Rule #2).** No trust-boundary change: the photo was relay ciphertext inside the
row's block and is relay ciphertext in CEK-sealed chunks now. What the relay learns that it did not
is that a content object exists for a contact row and its size class — the same size it already saw
as an outsized block. A24 carries a dated note.

**The tests, each shown red by a mutation** (§9.10 is what they pin):

| Test | Mutation that turns it red |
|---|---|
| `ContactPhotoContentTest` (11, `:contact-service-common`) — threshold, budget, byte-exact CRLF/LF/multi-photo reassembly, `PhotosUnavailable` / `PhotosMismatch`, offline-readable stored card, merge passes the placeholder | nothing moves → 6 red; everything moves → the small-photo and threshold tests red; reassembly unfolds the span → the byte-exact tests red |
| `ContactPhotoConcurrencyTest` (7, `:contact-service-proxy`) — review round 1, §9.10.1 | see §9.10.1 |
| `ContactPhotoContentPlaneTest` (11, `:contact-service-proxy`) — big photo on the plane + row size, small photo stays, export byte-exact, export without bytes fails, avatar promotion, offline edit, forged reference refused, the relocation pass, delete tombstones + blob, content edge and its `none`, merge carries the id | one per test: export answers `Ok(stored card)` without the photos; promotion reads the stored card; the pass's filter `> 1`; the source deleted without its tombstone; a bare-composite save when no photos (the edge test — ⚠ an entity with the slot *absent* does **not** go red, so it is the bare composite that is the hazard); the relink saving a null id |
| `ContactPhotosOnDemandSystemTest` (`:system-test-utils`) — host imports a 1 MiB portrait; the joiner gets a small row and no bytes, then exports the card byte-exact through the on-demand fetch | `ContactPhotosPropertyWriter` dropped from the harness's writer → red |
| `ContactPhotoRowMaxSharedReplicaTest` (`:tenshu-sdk-ios-core`, simulator) — over the real SQLite store, the 2 MiB-portrait contact imports; the same card in one row is `RowTooLarge` | nothing moves → the import fails with *a row of 2909942 bytes exceeds ROW_MAX (524288)* |

**Open items** (decided in review round 1, 2026-10-31):

1. **A card whose text alone is over the budget** (`NOTE`, `LOGO`, `SOUND`, `KEY`) — not fixed here;
   `docs/tech-debt.md`, with the recommendation to widen the movable set.
2. ~~A receiver keeps a superseded blob~~ — **closed** by the sweep below.
3. **Three more deletes are device-local** (`UnlinkTenshuIdentity`, `ClearPersonAvatar`, `MergePersons`'
   source person and avatar) — not fixed here; `docs/tech-debt.md`, its own PR.
4. **The relocation pass runs on any network** — kept.

### 9.10.1 Review round 1: what the first cut got wrong

| Finding | Fix | Pinned by (mutation → red) |
|---|---|---|
| ⚠⚠ The row and the blob were saved before `awaitEmitIdle()`, the upload intent journalled only inside `emitFileContent()` — a cancel in between left a row naming photos nothing would ever upload, which the pass then skips (it has refs) | The save and `journalFileContent()` are **one `NonCancellable` span**, the wait bounded (30 s, the upload producer's), the upload cancellable after it — `UploadFileImplementation`'s shape. (Round 1 also wrapped the save in the old content's ownership — removed in round 3, §9.10.3, as the cause of a deadlock.) Repair (ST-3f) still whitelists file bytes only: its `readFilePath` cannot answer a contact's path, and the journal now owns the retry the finding is about; the revive upload (CR-2 B4) already reaches `ContactSource.Photos` through the writer | `a cancel in the entity-before-bytes wait still leaves the upload journalled` (span made cancellable) |
| ⚠⚠ **The pass wrote a stale snapshot** — rows read at its start, written after minutes of uploads: a concurrent edit lost, a merge's `personId` reverted, a deleted row resurrected as an orphan | `writeContactSource` reads, decides and saves under `sourceWriteLock` **and one `database.transaction`** — the store lock `deleteData` holds across its fanout and ingest takes — and the pass hands it an `expect`: the row still there, still its card and person. Otherwise `SKIPPED`. `MergePersons`' relink reads and saves in one transaction too | the three `… while the pass uploads …` tests, each over a suspending upload fake (recheck removed → all three) |
| The edit path where the photos are local (`unchanged`) was untested | — | `an edit where the photos are local keeps the content id and uploads nothing new` (branch removed) |
| ⚠ Deleting the superseded blob at save threw away the only plaintext while a peer could re-declare it: B, offline, edits the C1 card; B's save wins last-writer, so the row names C1 again — on a device that already deleted C1 | **Nothing is deleted at a save or a delete.** `ContactPhotoSupersededRecorder` (one observer, so local saves, a peer's arriving row and a peer's tombstone are all covered) records every id a row stops naming in a device-local `/.contacts/photos/.superseded`; `sweepSupersededContactPhotos` — at the end of each `RelocateContactPhotos` run, under `sourceWriteLock` and one `database.transaction` — deletes a candidate only when no stored row names it and no journalled upload reads it. ⚠ `DeletePerson`'s direct delete is gone too: a peer's write winning over the delete may still name the blob. The remaining window: a peer re-declares an id *after* the sweep deleted it. ⚠ Corrected in round 4 (§9.10.4): that is safe only if the content **reached** the relay — a photo that was only journalled used to be retired by the drain the moment the row named other content, and then the sweep took the last copy. Since round 4 its intent carries the blob's hash, so the drain publishes it by its bytes before anything can sweep it | `a superseded blob a peer names again survives the sweep` (references ignored → red; deleting at the save → red) |
| A card near the budget, edited offline, is refused: `mergeIntoVCard` re-folds every line at 75 octets with CRLF (~4%) | The offline-edit check allowed a 12.5% margin over the budget — ⚠ superseded in round 2 (§9.10.2) by judging "new photo" against the stored card | `an offline edit of a card just under the budget is not refused for the re-fold` (margin 0) |

⚠ `sourceWriteLock` on its own is not what the race tests pin — the store transaction already
serialises the read and the save, and removing the mutex leaves them green. It is kept because the
sweep and the writer's journal step must not interleave, and it costs nothing a photo save notices.

### 9.10.2 Review round 2

| Finding | Fix | Pinned by (mutation → red) |
|---|---|---|
| ⚠⚠ The sweep asked the journal about the candidates it read first, then deleted every candidate it read in its transaction — one recorded in between (a `DeletePerson` or a peer's tombstone, neither of which takes `sourceWriteLock`) was deleted without the question, possibly the only copy of an upload still journalled | A candidate not in the snapshot is kept for the next run, which asks | `a candidate recorded between the sweep's snapshot and its delete waits for the next run` (guard removed); `a candidate whose upload is still journalled survives the sweep` pins the journal guard itself (owed guard removed). Both drive the peer's delete with `WriteOrigin.SYNC_INGEST`, as is the peer's row in the last-writer test |
| The web has no `RelocateContactPhotos` schedule, so after round 1 nothing ever freed a photo blob there | The sweep runs from the web sync scheduler's **maintenance hook** (`sweepContactPhotos`, `FirstPartyPlugins`), which also runs while the realm is sealed; one read of the candidate file when idle | — (wiring; the sweep's rules are the common tests') |
| An offline edit of a card with a relocated photo *and* an inline photo just under 64 KiB was refused: 48,000 bytes of image is a 64,027-byte line, 66,622 once `mergeIntoVCard` re-folds it | "New photo" is judged against the **stored** card: a `PHOTO` already inline there (same parameters and value, unfolded) is never new (`relocatablePhotosNotIn`). This also covers the budget case, so round 1's `REFOLD_MARGIN_BYTES` is gone | `an offline edit keeps an inline photo the re-fold pushes over the threshold`, and the round-1 budget test (stored card ignored → both red) |
| Multi-MB file IO under the store lock: `decide()` read the old blob twice and wrote the new one inside `database.transaction`, stalling ingest and IPC reads | Plan outside the lock, commit inside it (`planAndCommit`): the blob is read once, reassembled, split and the new one written **before** the transaction; the transaction re-reads the row and saves only if it is still the row planned from, else plans again (at most 3 times, then a typed refusal) | `a blob written for a plan that never commits is swept` (commit re-check removed → red); the three pass-race tests still pin `expect` |
| A blob was orphaned when the save threw after its write | A fresh blob is recorded as a candidate **before** it is written: a plan that never commits leaves a candidate nothing names, and a committed one is dropped from the list at the next sweep | same test (pre-record removed → red) |
| An ingest's `prepareSave` can read the row while another save is landing, so the recorder may miss a superseded id | **Accepted.** Ingest takes neither `sourceWriteLock` nor a lock around observer preparation, and adding one there for a disk-only leak is the wrong trade: the worst case is one superseded blob that stays on that device for good (no row names it, but no candidate does either) | — |

### 9.10.3 Review round 3: the lock order, and the content id a save commits

⭐ **The lock order, everywhere:** `ContactProxyComponents.sourceWriteLock` → (the store's
`database.transaction`, never across a suspension) → **at most one content key**, taken by the
journal or the upload for the id this save committed. Nothing here takes a content key and then
waits for `sourceWriteLock`; the sync drain takes content keys only, never the Mutex.

| Finding | Fix | Pinned by (mutation → red) |
|---|---|---|
| ⚠⚠ A deadlock: round 1 took `withContentEmitOwnership` (the OLD content's key) before `sourceWriteLock`. Save A held the Mutex, committed C2 and parked in `awaitEmitIdle`; save B of the same source took C2's key, then waited for the Mutex; A's journal then needed C2's key — a cycle inside `NonCancellable`, every later write and sweep stuck behind it | **No content ownership around a photo save at all.** It exists for a file overwritten in place under a drain; a photo blob's path *is* its content id, so the bytes under a key never change. Only the bound journal and emit take a key, inside / after the Mutex | `two overlapping photo saves of one source both complete`, over a fake that models `SyncObserver`'s per-key ownership (claim the key the row names now; re-enter an owned key; wait for any other), bounded by `withTimeout` and run in `backgroundScope` so a cycle fails the test (ownership put back outside the Mutex → times out) |
| ⚠⚠ The journal and the upload re-read the content id off the stored row (`resolveContentTarget`). Ingest takes neither the Mutex nor a key, so a peer's row naming C2 landing in the wait journalled and published A's C1 bytes **under C2** — every export of C2 then failing its digest check, and C1 never recorded | `FileContentSyncEmitter` gains a **bound** `journalFileContent` / `emitFileContent` overload taking the committed content id; `SyncObserver` builds the target from it instead of from the row (the file producer keeps the unbound calls). The writer carries the id from the commit | `a peer row ingested during the wait does not take over this save's upload` — journal and upload carry C1, and the byte-exact export works once the row names C1 again (unbound calls put back → red) |

⚠ **Accepted (round 4):** the sweep can delete C1 while a revive (`SyncObserver.reuploadForRevive`)
is reading it — the sweep takes `sourceWriteLock`, a revive does not. The revive then fails on a
missing file and answers `NotNow`; the sweep only takes ids no stored row names, so the revive was
re-uploading content nothing on this device references any more.

### 9.10.4 Review round 4: every path that holds a target keeps it bound

| Finding | Fix | Pinned by (mutation → red) |
|---|---|---|
| ⚠⚠ **P1-b came back through the locked-realm replay.** While locked, a bound emit parked `(entity, property, path)` and dropped the content id; the replay called the unbound `emitFileContent`, which re-read the id off the row. Two photo saves committed C1 then C2 while locked: the first replay published C1's bytes under C2, marked C2 published, C2's own replay was skipped, the drain retired C1 and the sweep deleted it — C2 on the relay held C1's bytes | `ContentTarget` carries `bound`; the locked-window park keeps a bound target **whole** and replays it through `withEmitKey(target.emitKey) { emitFileContentOwned(target) }`. The drain's own target (built from the journal entry, which is authoritative) is bound too. The journal write, the resume state and the drain read the entry, never the row; the ensure and repair paths are the file producer's and stay unbound | `BoundContentEmitTest.twoBoundEmitsHeldWhileLockedEachReplayTheirOwnBytes`, against the production `SyncObserver` (replay through the unbound call → red) |
| Nothing tested the real `SyncObserver` half of round 3 — the contacts tests ran over a fake | `BoundContentEmitTest` (`:sync-service-proxy`, beside `DeferredIntentSurvivesAMoveTest`) | `aBoundJournalAndEmitCarryTheCommittedContentWhileTheRowNamesAnother` — the entry's contentId and the manifest's fileId are C1 while the row names C2 (target re-resolved from the row → red) |
| ⚠⚠ **The drain retired a never-uploaded photo once the row named other content** — its "hashless content's rows never move" assumption holds for thumbnails and workout streams, not for a contact row that moves and comes back (C1 journalled on cellular under Wi-Fi-only; a peer's C2 row arrives; the drain retires C1, the sweep deletes it; B's offline C1 edit wins — C1 lost everywhere) | The photo writer computes `wholeFileHashOf(blob)` at plan time and passes it through the bound overloads, so the entry takes `PUBLISH_VERIFIED`. Chosen over exempting the property by name: the drain's rule stays "the bytes decide", and the hash is the evidence it already knows how to use | `aJournalledOnlyPhotoIsPublishedByItsBytesAfterTheRowMovesOn` (hash dropped in the bound target → red); `a photo save journals the whole-file hash of the blob it wrote` (writer passes null → red) |

⚠ **G4, by design:** a superseded photo that never had an edge on the relay — never published, or
published by no push that named it — is never freed there; reclamation frees content after its last
edge, and content with none has no last edge. The device-local blob still goes at the sweep.

### 9.10.5 Review round 5

| Finding | Fix | Pinned by (mutation → red) |
|---|---|---|
| ⚠⚠ **Drive data path:** round 4 marked the drain's target `bound`, so a drain on a realm that locked mid-pass parked a copy of its entry; a move retargeting the entry while locked was undone by the replay's journal write, and the next drain retired the moved file's upload. And the park stripped `verifyWhileStreaming` | A **content-keyed** drain entry never parks (`drainLegacy` still parks an unbound copy, exactly as on master): `drainOne` keeps the entry untouched on a locked realm, and its target says `parkIfLocked = false` for a lock landing later in the pass — the journal entry is already the durable intent. Only the bound **producer** overloads park, whole, nothing stripped | `DeferredIntentSurvivesAMoveTest.aRealmLockedMidDrainLeavesTheEntryForAMoveToRetargetAndTheUnlockToPublish` (drain target parks → red). ⚠ The verify-strip mutation alone is not observable any more: no target that can carry `verifyWhileStreaming` (only the drain's) reaches the park |
| An import saved its `Person` before `writeContactSource` could wait for `sourceWriteLock`; a cancel or shutdown there left a name-only contact, and the retry made a second | `writeContactSource(alsoInCommit = …)`: the import's person is saved inside the record's commit transaction, so both land or neither does | `an import cancelled at the source lock leaves no name-only person` (person saved first → red) |
| `BoundContentEmitTest` compared the manifest's hash with the caller's own | Also asserts `totalSize` against the source file | — |
| (round 6) An import's `PersonAvatar` was saved after the inline upload: a cancel there left a contact without its picture (and a failed op whose retry duplicated it); a `DeletePerson` during the upload was followed by an orphan avatar | The avatar is saved in `alsoInCommit` with the person and the record. The import still uploads inline — a cancel after the commit leaves a whole contact and a journalled upload, though the op itself reports the cancel | `an import cancelled during its upload leaves a whole contact with its avatar`, `a contact deleted during its import's upload leaves no avatar behind` (avatar saved after the call → both red) |
| (round 6) Nothing pinned `drainOne`'s locked-realm check — the round-5 test locks after it | — | `aRealmThatLocksDuringOneEntryLeavesTheNextEntryUntouched` (check deleted → red) |


**Device-only, owed:** a real desktop address book `.vcf` with a multi-MB portrait imported on Android,
then the contact on the iPhone on the App Group build; an export from a second device that has
never seen the photo, online and in airplane mode (the second must say the photo is not here); the
relocation pass running under WorkManager with Control closed; and `android-smoke.sh`'s lockstep
over a reinstalled Contacts canary (this bump moves the contact service, so a stale Contacts is
flagged for that reason too).

## 10. References

- [`contact-service-identity-model.md`](contact-service-identity-model.md) — the data-service
  design; this doc is its **v2**.
- [`contact-invitation-handshake.md`](contact-invitation-handshake.md) — the shipped v1
  protocol (CI-1–CI-4).
- [`drive-manage-access-ux.md`](drive-manage-access-ux.md) — §2.3 / §7 / §10.2, the three
  surfaces promised to this app.
- [`shared-realm-membership-grant.md`](shared-realm-membership-grant.md) — GR-1–GR-7, the
  grant flow the detail screen's Drive action drives.
- [`file-content-addressing.md`](file-content-addressing.md) §4.2 — the 2026-07-18 revision that
  removed refcounting from Phase 4, and with it D1's only gate (Q3).
- [`place-roles.md`](place-roles.md) §2.3 — the sibling-row-vs-property precedent behind Q4.
- [`core-deferred-properties.md`](core-deferred-properties.md) — the deferred `dynamicProperty`
  and the lists-vs-detail surface rule Q4 inherits.
- [`maps-app.md`](maps-app.md), [`timeline-app.md`](timeline-app.md),
  [`fit-app.md`](fit-app.md) — the UI-over-mock phasing precedent (D8).
- [`../milestones.md`](../milestones.md) §Contacts — the milestone entry.
