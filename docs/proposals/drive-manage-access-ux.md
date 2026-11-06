# Drive Manage access — CI-4 UX + implementation handoff

**Status:** Handoff v0.4 (2026-06-12). **CI-4a (§§2–4) and CI-4b (§5) both shipped
2026-06-12 — CI-4 complete** (Android manual pass pending). **§10 (Manage access
screen restructure) supersedes the §2.1 sheet** — pinned same day, shipped as its own
slice. The UX pass that
[`contact-invitation-handshake.md`](contact-invitation-handshake.md) §10 phase CI-4 was
gated on. Protocol and ops are **done** (CI-1–CI-3); this doc is UI-only —
no `:contact-service` protocol changes except one small read op (§4.2). *(That clause
didn't fully survive implementation: §§2.3/3.2 require data CI-3 never surfaced
client-side — the three as-built protocol deltas are pinned in §9.)*

**Maintainer decisions pinned this session (2026-06-12):**

1. **Two PRs, one design:** CI-4a (link flow, this doc §§2–4) then CI-4b (QR + SAS, §5).
   Rationale: the link flow is VM/system-testable; the QR slice is camera-/device-manual
   only and carries the `control-shared → ui-core` component move — different
   verification profiles, independently landable. Collapsing into one PR is fine if
   convenient; the spec doesn't change.
2. **The completion prompt lives in Drive for now and migrates to the v2 Contacts app.**
   The migration is cheap by construction: the prompt is pure UI over synced state
   (`ContactInvite` rows at `REDEEMED`) + the `CompleteContactHandshake` op; no contact
   logic lands in Drive. Flagged as a planned move in §2.3.
3. **Invitee entry = paste-link + Android deep links** (`tenshu-invite:` scheme +
   `https://tenshu.app/i` App Link), desktop = paste only.

---

## 1. Scope and flows

CI-4 delivers the v1 *Manage access* story of
[`contact-service-identity-model.md`](contact-service-identity-model.md) §8: the sheet
stops crashing, an owner can invite a person and complete the handshake, an invitee can
redeem and end up holding the inviter's identity. The **membership grant is still
parked** (cross-user join runtime, handshake doc §2) — the UI says so honestly rather
than pretending (§2.2 empty-grant copy).

Three user-visible flows:

- **Invite (inviter):** Files → folder ⋮ → *Manage access* → *Invite someone* → link
  sheet → OS share sheet / copy.
- **Complete (inviter, any device):** invite turns `REDEEMED` (synced) → badge on the
  Shared destination + prompt → *Save as new contact / link to existing* or *Reject*.
- **Redeem (invitee):** tap link (Android) or paste it (Shared → *Redeem an invite*) →
  enter own display name → waiting state → *accepted* (inviter saved as contact, share
  intent shown) / *declined* / error states. Resumes across app restarts.

## 2. Inviter UX

### 2.1 Manage access sheet — ⚠ superseded by §10 (screen + unified list)

Entry: `FileActionsViewModel.onManageAccessClicked()` (the
`TODO("Not yet implemented")` at
`drive-shared/.../ui/common/details/FileActionsViewModel.kt:139`), reachable from
`FileActionsBottomSheet`. Folder-only, matching the existing folder-only *Share* gate;
`onShareClicked()` (line 135) is the Mode-B producer's entry and is **not** in this
slice.

Sheet content (a new `ManageAccessSheet`, hosted the same way `CreateShareLinkSheet`
rides `ShareHost`):

- **Members** — `getSharedRealmMemberships` filtered to the folder's realm, one row per
  member: role label (`owner`/`member`). Hidden when the folder doesn't back a shared
  realm yet (the common case in v1 — the sheet is then invite-only).
- **Invited** — `ContactInvite` rows with `intentKind == SHARE_FOLDER` and
  `intentLabel == <folder name>`, status-labelled: *Invited* (`PENDING`), *Awaiting your
  confirmation* (`REDEEMED`, tap → §2.3 prompt), *Connected* (`COMPLETED`), *Link
  compromised — re-invite* (`COMPROMISED`, warning styling). `PENDING` rows get a
  *Revoke* affordance → `RevokeContactInvite`. (Filtering by advisory `intentLabel` is a
  known v1 approximation — fine until the grant slice gives invites a real realm
  association.)
- **Invite someone** button → §2.2.

### 2.2 Invite-link sheet

The `CreateShareLinkSheet` pattern (generate → result → hand out), backed by
`createContactInvite(intentKind = SHARE_FOLDER, intentLabel = folderName)`:

- Generating state → result state showing the messaging link (truncated middle) +
  primary **Share** + secondary **Copy**. ⚠ **As-built 2026-07-22** — the
  original "desktop actual = copy to clipboard, button relabels *Copy*" is
  superseded: desktop shares via `mailto:` and the button relabels *Send by
  email*; the launcher carries a `ShareTargetKind` rather than a boolean; copying
  goes through `SensitiveClipboard`, not `LocalClipboardManager`. The seam and
  its two hard constraints (no web share-intent redirectors; percent-encode the
  `mailto:` fields) are documented once in
  [`file-sharing.md`](../file-sharing.md) §8.1 *Handing the link out* — the
  Mode-A sheet and this one share it.
- Disclosure copy (the §3-of-the-handshake-doc honesty): *"Anyone with this link can
  request to connect with you. It works once and expires in 3 days."*
- Grant-parked copy when invoked from Manage access: *"Once you're connected, sharing
  this folder with them is coming in an upcoming update."* — the §1 honesty line.
- ⚠ The raw link exists only in this session (handshake doc §3): dismissing the sheet
  discards it; *Invited* rows offer *Revoke*, never *Show link again*.

### 2.3 Completion prompt (Drive-hosted, Contacts-app-bound)

Trigger: any `ContactInvite` at `REDEEMED` (synced — any device prompts). Surfaces:
a badge on the Shared destination + a banner row at its top; tapping (or tapping an
*Awaiting your confirmation* row in §2.1) opens the prompt dialog:

- Header: *"<selfAssertedName> accepted your invite"* — the self-asserted name comes
  from the stashed verified response; render it clearly as *their claim* (quoted),
  with the key fingerprint (`identityFingerprint` `XXXXX-YYYYY-ZZZZZ` form) beneath.
- Petname field, pre-filled with the self-asserted name (user-owned from here on —
  contact doc §4.1).
- **Link to existing contact** (collapsed by default): list of current `Person` rows;
  selecting one re-points instead of creating (contact doc §6.2 candidates — plain
  list in v1, no ranking yet since imported sources don't exist before v2).
- Actions: **Save contact** → `completeContactHandshake(inviteId, ACCEPT,
  newPersonName = petname | personId = selected, selfAssertedName = <own display
  name>, sasConfirmed = false)`; **Decline** → `…(REJECT)`.
- Own display name: prompt inline on first use and remember locally (Drive has no
  profile surface; the pairing `ownerMetadata.displayName` seam is Control's — don't
  couple to it in this slice).

~~**Planned migration:** this prompt (and the badge) moves to the v2 Contacts app
unchanged — it is a pure consumer of synced rows + one op (maintainer decision 2).~~

⚠⚠ **Superseded 2026-08-03 — decision 2's premise expired at GR-4, and this prompt is
staying in Drive.** It was a pure consumer when written; GR-4 then chained the grant
offer (*"Add {petname} to '{label}'?"*, §2) onto it, so `ContactPromptViewModel` now
reads `FilesRepository` + `MembershipRepository`, and `tenshu-sdk-ios`'s
`DriveShareSurface` exposes it besides Drive's own screens. It cannot move to an app
with no file domain, and moving it *without* the grant offer would regress a shipped
flow into two disconnected steps.

**What happened instead: the invite space is partitioned by `intentKind`.** Drive owns
`SHARE_FOLDER` invites end to end — this prompt, this badge, the grant chain — and the
Contacts app owns bare ones with a prompt of its own. Each app completes the handshakes
it starts, so no invitation is prompted in two places.

⚠ **Consequence for this section, already applied:** the §2.3 badge and the §10.2
`REDEEMED` row are **narrowed to `SHARE_FOLDER`**. `hasRedeemedInvites()` and
`SharedScreenViewModel` previously matched every kind; unnarrowed, a bare invite from
Contacts would raise a banner here and then chain a grant offer for a share that never
existed. Rationale and the options weighed:
[`contacts-app.md`](contacts-app.md) §9.1.

## 3. Invitee UX

### 3.1 Entry points

- **Android deep links** (`drive/src/main/AndroidManifest.xml`): a `VIEW` intent filter
  on the main activity for scheme `tenshu-invite:` and an `autoVerify` App Link for
  `https://tenshu.app/i`. App-Link verification reads
  `web/apex/public/.well-known/assetlinks.json` (added 2026-11-04 after Play reported the
  domain unverified; `AssetLinksTest` holds it to the pinned signing keys). Until it verifies
  the apex `/i` page shows open-in-app guidance (handshake doc §3), and the custom scheme
  works regardless.
- **Paste entry:** Shared destination overflow → *Redeem an invite* → paste field →
  `InviteLinkPayload.parse` (typed errors: malformed vs *"requires a newer version of
  Tenshu"* per `UnsupportedInviteVersionException`).

Both route to the redeem screen with the parsed link.

### 3.2 Redeem screen + states

1. **Confirm + name:** show the inviter key fingerprint, input *"Your name as they'll
   see it"* (≤ 64 chars), explanation that the name is shared with the inviter. CTA →
   `redeemContactInvite(link, selfAssertedName)`.
2. **Waiting:** `awaitInviteAck(inviteId)` (the op slow-polls; budget elapse returns
   `PENDING`). Copy: *"Waiting for them to confirm — you can close this; we'll keep
   checking."*
3. **Terminal states:** `ACCEPTED` → success card with the inviter's (quarantined)
   name + fingerprint; if the ack carried `intentKind == SHARE_FOLDER`, add
   *"<name> wants to share '<intentLabel>' with you — folder sharing arrives in an
   upcoming update."* `REJECTED` → *"They declined this request."* `GONE` → expired /
   already-used copy (the T-CI-01 tell: *"If you didn't use this link, ask the sender
   to send a new one."*). `INVALID` → generic tamper-safe failure.
4. **Resume:** on Shared-destination init, `listPendingRedemptions()` (§4.2) → for each,
   re-invoke `awaitInviteAck` and re-show the waiting row.

`409 already responded` on redeem (relay single slot) maps to the `GONE`-style copy —
it is the legitimate-user-loses-race signal from T-CI-01 and must be loud, not generic.

## 4. Shared plumbing (CI-4a)

### 4.1 `ContactsRepository` (drive-shared)

Mirror `FilesRepository`'s shape: reactive streams over
`ContactService.Relations.{CONTACT_INVITES, PERSONS, TENSHU_IDENTITIES}` (the relations
are maintained proxy-side by `ContactRelationMaintainer`) + thin suspend wrappers over
the five client extensions, returning `RepositoryResult`. The `REDEEMED` badge is
`contactInvites().map { it.any { row -> row.status == REDEEMED } }`.

### 4.2 New op: `ListPendingRedemptions`

Gap found grounding this doc: nothing client-side can enumerate redemptions to resume
`AwaitInviteAck` after restart (`PendingRedemptionStore.all()` exists proxy-side only).
Add a read-only op (`:contact-service` schema + RO + proxy impl over `all()`), returning
`(inviteId, redeemedAtMs, peerFingerprint)` triples — **never** `K_link` or the peer key
itself; the client needs none of it. `IoOperation`; no `HeadlessSafe` needed (UI-driven).

## 5. CI-4b — QR + SAS (second PR)

1. **Component move:** `QrCode.kt` + `QrScanner.kt` from `control-shared/.../ui/common/`
   to `:ui-core` (mechanical; control-shared imports update — the pairing wizard is the
   regression check). Camera permission lands in Drive's manifest with the scanner.
2. **Inviter:** the §2.2 link sheet gains a *QR* toggle rendering
   `InviteLinkPayload.parse(link).toQrUri()`. While the sheet is open with a `REDEEMED`
   arrival for *this* invite (in-person case: redemption is near-instant), it advances
   directly into the §2.3 prompt with a **SAS panel**: `deriveContactSas(ownA13Pub,
   payload.identityPublicKey)` + *"Compare this code with the other device"* + an
   explicit *Codes match* checkbox gating **Save contact** with
   `sasConfirmed = true` (→ `VERIFIED`/`QR_SAS`).
3. **Invitee:** *Redeem an invite* gains *Scan QR* (the moved scanner); after redeem,
   the §3.2 waiting state shows the same SAS (derivable immediately — both keys known
   from link + own identity); on `ACCEPTED`, *Codes match* confirm →
   `awaitInviteAck(inviteId, sasConfirmed = true)`.
4. SAS display format: 6 digits, space-grouped `482 913`, plus the peer fingerprint —
   matching the pairing wizard's presentation conventions.

## 6. Strings

All new user-facing strings via `stringResource(…)` in
`drive-shared/src/commonMain/composeResources/values/strings.xml` (ADR 0016; bare `'`,
never `\'` — CMP 1.7.3). Key prefixes, following `create_share_link_*`:
`manage_access_*`, `invite_link_*`, `contact_prompt_*`, `redeem_invite_*`,
`contact_sas_*` (CI-4b). While at it: `CompleteContactHandshakeImplementation` /
`AwaitInviteAckImplementation` hard-code `DEFAULT_DISPLAY_NAME = "New contact"` — change
both to default to the empty string and let the UI substitute
`stringResource(contact_prompt_unnamed)` (proxy emits codes, not English — the CI-3
review nit, fixed here).

## 7. Out of scope (explicitly)

The membership grant + grant prompt (cross-user join runtime; the §2.2/§3.2 copy lines
are the only acknowledgement); an "invite from contacts" picker (meaningless before the
grant); contact detail/re-verification UI (v2 Contacts app); Control-side surfaces;
push-assisted ack delivery.

## 8. Implementation Notes for Claude Code

One PR per phase (CI-4a → CI-4b); tests green before the second. If anything here
contradicts §§1–7, those win. Verify package paths against neighbours.

### CI-4a — new files

| Path (`drive-shared/.../drive/` unless noted) | Contents |
|---|---|
| `data/ContactsRepository.kt` | §4.1. Mirror `FilesRepository` (reactive relation streams + `RepositoryResult` wrappers). |
| `ui/access/ManageAccessSheet.kt` + `ManageAccessViewModel.kt` + `ManageAccessUiState.kt` | §2.1. Mirror the `ui/share/CreateShareLink*` trio. |
| `ui/access/InviteLinkSheet.kt` + `InviteLinkViewModel.kt` | §2.2. Generate/result states; Share + Copy. |
| `ui/contacts/ContactPromptDialog.kt` + `ContactPromptViewModel.kt` | §2.3. Petname field, link-to-existing list, Accept/Reject. |
| `ui/contacts/RedeemInviteScreen.kt` + `RedeemInviteViewModel.kt` | §3. Parse → name → waiting → terminal states; resume wiring. |
| `ui/common/ShareText.kt` (`expect`) + `androidMain`/`desktopMain` actuals | §2.2 OS share-out. Android `Intent.ACTION_SEND` + chooser; desktop = clipboard. |
| `:contact-service` `ListPendingRedemptions.kt` + client RO + `ListPendingRedemptionsImplementation.kt` | §4.2. Registered like the CI-3 ops (`ContactServiceClientModule`, impl factory). |

### CI-4a — modified

| Path | Change |
|---|---|
| `ui/common/details/FileActionsViewModel.kt` | `onManageAccessClicked()` (line 139): open `ManageAccessSheet` (folder-only). Leave `onShareClicked()` alone. |
| `ui/common/details/FileActionsBottomSheet.kt` + `ShareHost.kt` | Host the new sheets the way the Mode-A sheet is hosted. |
| Shared destination (`ui/screens/shared/SharedScreen*.kt`) | `REDEEMED` badge + banner (§2.3), *Redeem an invite* overflow entry (§3.1), resume-on-init (§3.2.4). |
| `drive/src/main/AndroidManifest.xml` | `VIEW` filters: `tenshu-invite:` scheme + `https://tenshu.app/i` (`autoVerify`). Route into the redeem screen. |
| Drive DI (where `FilesRepository`/VMs are provided) | `ContactsRepository` + new VMs. |
| `composeResources/values/strings.xml` | §6 keys. |
| `contact-service-proxy` `CompleteContactHandshakeImplementation.kt` / `AwaitInviteAckImplementation.kt` | `DEFAULT_DISPLAY_NAME` → `""` (§6). |
| `docs/proposals/contact-invitation-handshake.md` §10 + `docs/proposals/contact-service-identity-model.md` §8 + `AGENTS.md` row | Status pins on landing. |

### CI-4b — files

Move `control-shared/.../ui/common/QrCode.kt` + `QrScanner.kt` → `:ui-core` (update
control-shared imports; pairing wizard compiles + `:control-shared:desktopTest` green).
New `ui/contacts/SasConfirmPanel.kt` shared by §5.2/§5.3. Modify `InviteLinkSheet`
(QR toggle), `RedeemInviteScreen` (Scan QR + SAS), `ContactPromptDialog` (SAS panel,
`sasConfirmed` plumb-through). Drive Android manifest: camera permission.

### Tests

- `ManageAccessViewModelTest`, `InviteLinkViewModelTest`, `ContactPromptViewModelTest`,
  `RedeemInviteViewModelTest` (`drive-shared` desktopTest) against
  `:contact-service-test-utils` (`ContactTestProxy`, `FakeInviteRelay`) — cover: invite
  rows filtered by intent label; revoke; prompt ACCEPT-new vs ACCEPT-link-to-existing vs
  REJECT; redeem error mapping incl. the loud already-used copy; resume re-invokes
  `awaitInviteAck` per pending redemption; raw link absent from any persisted UI state.
- `InviteLinkPayload` round-trip through the share-out path (no truncation/encoding loss).
- Extend `ContactHandshakeSystemTest` only if the repository layer changes op usage;
  otherwise it already covers the protocol.
- CI-4b: QR move = compile + existing pairing tests; SAS panel VM test asserts
  `sasConfirmed` only flows on explicit confirm. Scanner is device-manual (note in PR).
- Manual Android pass (deep link from a real messaging app; share sheet; camera scan in
  CI-4b) — list the steps in the PR description per repo convention.

## 9. As-built deltas (CI-4a, 2026-06-12)

Grounding §§2–3 against the CI-3 protocol surfaced three places where the spec'd UI had
no data source; per the §8 precedence rule (§§1–7 win) the protocol grew minimally
rather than the UX shrinking. All three live in `:contact-service*` and are pinned in
the handshake doc §7.1/§10:

1. **`ContactInvite.claimedName` + `claimedFingerprint`** (synced, written by the
   pickup on the `REDEEMED` flip): the §2.3 prompt header's name + fingerprint. This is
   what makes maintainer decision 2 ("the prompt is pure UI over synced state") true —
   the stashed verified response is proxy-local in-memory and was unreadable from UI.
   Quarantine rules apply (rendered quoted; petname pre-fill only).
2. **`AwaitInviteAck` returns a transient `InviteAckResult` composite** (status +
   peerName + peerFingerprint + intentKind/intentLabel; the `ShareLinkResult` pattern)
   instead of the bare status `TString` — §3.2.3's success card + share-intent line
   have no other source (the ack's contents exist only inside the proxy unseal).
3. **`RedeemContactInvite.ErrorCode`** (`contact.invite.alreadyResponded` / `…gone`):
   the proxy maps the relay's `409`/`410`/unknown to stable codes (ADR 0016 —
   codes, not prose) so the §3.2 T-CI-01 *loud* already-used copy survives the IPC
   boundary instead of collapsing into a generic failure.

**CI-4b addendum (same day):** the §5.2/§5.3 SAS comparison needs both raw public
keys, which only the proxy holds — so the SAS itself is **proxy-derived,
display-ready** (it's public-derivation by design): `ContactInvite.claimedSas`
(synced with the claim pair at `REDEEMED`), `InviteAckResult.sas`, and
`PendingRedemptionInfo.sas`. The literal §5.3 sequencing ("on ACCEPTED, confirm →
`awaitInviteAck(sasConfirmed = true)`") is unimplementable as written — the ACCEPTED
close-out deletes the redemption record, so a later re-invoke returns `GONE` and the
records were already minted `UNVERIFIED`. As built, the *Codes match* checkbox lives on
the **waiting** state and restarts the live wait with `sasConfirmed = true`, so the
close itself mints `VERIFIED`/`QR_SAS`; an ack that lands before the user checks stays
`UNVERIFIED` (upgrade = the v2 re-verification surface). The completion prompt always
shows the SAS panel once the claim lands — ignoring it preserves the §2.3
`UNVERIFIED` default; only the explicit check flows into `sasConfirmed`.

Other implementation notes: the invitee's "name as they'll see it" is remembered in the
new REALM-scoped `DrivePreferences.ContactDisplayName` (deliberately not Control's
pairing `ownerMetadata` seam, §2.3); the share-out primitive landed as
`rememberShareTextLauncher()` (`ui/common/ShareText.kt`, desktop actual = clipboard +
*Copy* relabel); the nav badge rides a new `AdaptiveNavItem.showBadge` flag in
`:ui-core`; Manage access is folder-only in the actions sheet (the row hides for
files). No threat-model amendment needed: the claim pair is A24-class realm data
(ciphertext to the relay), no relay shape or sync-protocol change.

## 10. Manage access screen restructure (post-CI-4, 2026-06-12)

**Maintainer decision (pinned 2026-06-12, after CI-4 landed):** Manage access is a
**full-screen destination, not a bottom sheet**. The CI-4a hosting stacked three modal
layers (file-actions sheet → Manage-access sheet → invite sheet, plus the prompt
dialog) with fragile `onActionCompleted()` dismiss choreography; and Manage access is a
*stateful management surface* — a list that grows and re-labels live — not a transient
action. Bottom sheets stay for transient actions (the invite-link sheet keeps its
sheet); state lives on screens.

### 10.1 Structure

- `Destination.ManageAccess(folderPath)`; the file-actions *Manage access* row
  **navigates** (like *Details & Activity*) instead of flipping a host-rendered flag.
  `FileActionsUiState.showManageAccessForPath` + the VM flip/dismiss methods are
  deleted; hosts thread a `showManageAccess: (String) -> Unit` lambda exactly like
  `showFileDetails`.
- `ManageAccessScreen` (replaces `ManageAccessSheet`, same package): `Scaffold` +
  `AppTopAppBar` + **one unified people list** (no Members/Invited section headers) +
  a **+ FAB** → the §2.2/§5.2 invite-link sheet (unchanged, still a sheet). The
  completion prompt stays a dialog over the screen. `ManageAccessViewModel` survives
  unchanged — this is a hosting move.
- The list pins **"You — Owner"** as the first row unconditionally (the old Members
  section was gated on `backsSharedRealm` and could only ever show this row anyway;
  `backsSharedRealm` stays in the UiState for the grant slice).
- **Empty state** (no invites): ghosted placeholder + *"No one else has access yet"*
  + invite-with-+ hint — replaces the old invite-only sheet ambiguity.

### 10.2 Row states — the emphasis ramp

Status is encoded by row treatment, not section headers. Emphasis ramp:
**compromised > needs-your-confirmation > connected (steady state) > pending
(ghosted)**. Icon + short text together (never icon-only — WhatsApp-tick glyph
literacy can't be assumed); steady-state rows carry **no** status markers (indicators
mark exceptions).

| Status | Treatment |
|---|---|
| `PENDING` | Ghosted (reduced alpha), dashed-circle placeholder avatar (there *is* no person yet — it's an invite link), clock icon + *Invited* secondary line, *Revoke*. Stays interactive — ghost ≠ disabled. |
| `REDEEMED` | **Not** ghosted — accented (tonal container bg), quoted claimed name, *Accepted — tap to confirm* in primary colour, trailing chevron → completion prompt. The only row demanding user action; it must pop, never recede. |
| `COMPLETED` | Clean row: initials avatar + quoted claimed name, no status line, no icon. |
| `COMPROMISED` | Error tint on avatar (warning icon) + name + the §2.1 re-invite copy in error colour. Never muted. |

Precedent: Notion/Figma/GitHub use muted-row + "Pending" text badges in a unified
member list; Signal/Element (the cryptographic-identity analogs) ghost or separate
invited members. The unified ghosted list is the deliberate pick over Element-style
*Invited* section headers — one scan path. (Future, v2 Contacts app: a small verified
badge on `QR_SAS` contacts is the one steady-state marker worth adding — Signal's
pattern.)

### 10.3 Implementation notes for Claude Code

New: `ui/access/ManageAccessScreen.kt` (delete `ManageAccessSheet.kt`),
`Destination.ManageAccess` + `App.kt` entry. Modified: `FileActionsBottomSheet`
(row → `onManageAccessRequested(path)` + `onActionCompleted()`; drop the nested
`AppBottomSheet` host), `FileActionsViewModel`/`UiState` (delete the flag + two
methods), `HomeScreen`/`FilesScreen`/`FolderScreen`/`App.kt` FilePreview entry
(thread `showManageAccess`), `strings.xml` (add `manage_access_empty_*`,
`manage_access_status_accepted_confirm`; drop the now-dead section-header keys).
`ManageAccessViewModelTest` unchanged; `FileActionsBottomSheetTest` gains a
manage-access navigation case.

## References

- [`contact-invitation-handshake.md`](contact-invitation-handshake.md) — protocol, ops, §10 CI-4, §12 conventions.
- [`contact-service-identity-model.md`](contact-service-identity-model.md) §§4, 6, 8 — petname/quarantine rules the copy implements.
- [`drive-shared-realm-file-model.md`](drive-shared-realm-file-model.md) — `getSharedRealmMemberships` + the Mode-B surfaces this sheet sits beside.
- [ADR 0016](../adr/0016-localisation.md) — string authoring.
- `drive-shared/.../ui/share/CreateShareLinkSheet.kt` — the sheet pattern to mirror.
