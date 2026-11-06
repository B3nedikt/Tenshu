# Mode-B folder sharing on the web — handshake form factor

**Status:** Decided 2026-06-25. **MB-1 (Contact, link-only) shipped 2026-06-25** — see §11.
**MB-2 (Grant + members) shipped 2026-06-25, runtime + SDK + inbound UI** — see §12.
**MB-3 (Mode-B file read) shipped 2026-06-25** — see §13.
**MB-4 (Share a folder, outbound) shipped 2026-06-25** — see §14.
**MB-5 (Verify a contact — camera-QR + SAS) shipped 2026-06-26** — see §15. **Cluster
MB-1..MB-5 COMPLETE.**
Resolves [`drive-web-app.md`](drive-web-app.md) §5 Q2 ("sharing handshake form factor on
web"), the open question that parked Mode-B + the membership grant as **Deferred (D)**. This
doc fixes the *form factor*; the op-by-op SDK build and a threat-model pass land with the
implementing phases.

Background it composes (all native-shipped): the contact invitation handshake
([`contact-invitation-handshake.md`](contact-invitation-handshake.md)), the cross-user
membership grant ([`shared-realm-membership-grant.md`](shared-realm-membership-grant.md),
GR-1..GR-7), and the Mode-B file model
([`drive-shared-realm-file-model.md`](drive-shared-realm-file-model.md)).

---

## 1. The decision in one line

**Web v1 = deep-link invite → `UNVERIFIED` (TOFU) contact → explicit folder grant that shows
the contact's fingerprint.** No camera, no QR, no SAS. Camera-QR + the 6-digit SAS is a
**later, additive "verify your contact" upgrade** — not a prerequisite for sharing.

This is not a protocol change. Everything below is the *web rendering* of a handshake that
already exists cross-platform in KMP; the apex runs the same op code natives do.

## 2. Why this is much less open than it looked

Mode-A link sharing is web-native (copy a URL). Mode-B was assumed to need the QR/SAS
handshake natives use in person. But the contact protocol already defines two completion
paths (handshake doc §5.3), and the *remote* one is exactly web-shaped:

| Flow | Transport | Result | Web fit |
|------|-----------|--------|---------|
| **Remote link** | invite URL (fragment-carried) | `UNVERIFIED` / `source=INVITE_LINK` (TOFU) | **native** — copy / share / open a link |
| **QR in-person** | scan + 6-digit SAS both confirm | `VERIFIED` / `source=QR_SAS` | needs camera + SAS panels |

And crucially, **a realm grant does not require a `VERIFIED` contact.** Granting is "a
separate explicit act on a displayed key" (handshake doc): the grant step shows the peer's
fingerprint (`XXXXX-YYYYY-ZZZZZ`) for the user to eyeball. So an `UNVERIFIED` (link-only)
contact is grantable — which means **web Mode-B ships end-to-end with zero camera/QR/SAS
work.**

## 3. The invite link on the web (§5 Q2 resolved)

One artifact, three surfaces — all carrying the **same** link, whose secret `K_link`
(16 bytes) lives in the URL **fragment** and never reaches the relay (same posture as a
Mode-A share link):

```
https://tenshu.app/i#v1.<base64url(inviteId ‖ K_link ‖ a13Pub)>
```

- **Copy** — the default, always available; a read-only field + "Copy".
- **`navigator.share({ url })`** — the Web Share API: pops the OS share sheet so the inviter
  hands the link to Messages / WhatsApp / email / AirDrop. This *is* the protocol's
  "messaging link (OS share sheet)" form. Well-supported on mobile web, spotty on desktop
  (Safari/Edge yes, Chrome/Firefox limited) — a **progressive enhancement over copy**,
  feature-detected (`if (navigator.share)`), never the only path.
- **QR display** — render the link (or the `tenshu-invite:v1?i=&k=&p=` QR form) as an image
  for an in-person scan. Pure display; needs no camera. Optional in v1.

The joiner opens the link on their device → the apex parses the fragment, runs the
sealed-box redemption over `/sync/invite/*`, and the contact is born `UNVERIFIED`. The
fragment is local-only, so the relay never sees `K_link` — the link is a handshake
*initiator*, not a bearer capability (Q53).

## 4. Camera / QR scan (§5 Q2, the verify upgrade)

Desktop browsers **can** access the camera — `navigator.mediaDevices.getUserMedia({ video:
true })` works under HTTPS (which `drive.tenshu.app` already is) with a permission prompt.
So the scan path is viable cross-device, not mobile-only.

Policy: **default to the deep link; offer camera-scan where available** (`enumerateDevices`
finds a `videoinput`), decoding frames with a small JS QR lib (the CMP `QrScanner` in
`:ui-core` is Compose — **not reusable**; the web needs its own decoder). The scan path is
not just nicer UX — it is what upgrades a contact from `UNVERIFIED` (TOFU) to `VERIFIED`
(§5.3). Treat it as a **v2 additive**, behind the v1 deep-link default.

## 5. SAS — the suggestion (§5 Q2)

SAS exists **only** in the QR/in-person flow; the remote-link default has none by design.
So **web v1 renders no SAS at all.**

When the QR path lands, mirror native CI-4b verbatim — do **not** reinvent the encoding
(it's pinned cross-platform in KMP, test vector `744595`):

- After the key exchange, **both apex sides show the same 6-digit code** (HKDF over the two
  sorted A13 pubkeys, `info = "tenshu/contact/sas/v1"`, session-independent) **plus** the
  peer fingerprint `XXXXX-YYYYY-ZZZZZ`, with **"Do these match? [Confirm] / [They don't]"**.
- The contact is born `VERIFIED` only when **both** confirm. Six digits beat words/emoji for
  comparison over a phone call; the SDK derives them — React only renders.
- Cross-device case (web inviter ↔ phone joiner): the two humans compare verbally, or one
  person holding both screens compares directly. No protocol change — only the rendering
  crosses the bridge.

Later re-verification reuses the same derivation from the contact-detail screen (handshake
doc §5.3 "UI is v2").

## 6. SDK surface to build (greenfield)

The web SDK exposes **zero** contact / membership / Mode-B ops today (only Mode-A `Shares`).
Per House Rule #5 (thin SDK + TS rebuild), each is a Kotlin `@JsExport` op → `@tenshu/sdk`
typing → apex gateway handler → drive `proxy.ts` wrapper → React. The native surface:

- **Contact** (`ContactsRepository`): create invite, redeem invite, `connectedContacts()`,
  (v2) SAS reveal/confirm.
- **Membership** (`MembershipRepository`): `grant(realmId, personId, label)`,
  `accept(grantorFingerprint)`, remove, `getRealmMembers`, poll pending grants.
- **Mode-B file** (`drive-shared-realm-file-model.md`): `shareFolder`, `sharedFolders`,
  `browseSharedFolder`, `readSharedFile`, `sharedWithMe` (mount/`Placement` stays deferred —
  see §7).

Reactive ops (members, pending grants, shared-with-me) take the established
**snapshot + TanStack-Query refetch** shape (`Flow.first()` on the Kotlin side), unless a
real subscription proves necessary — settle per op when built ([`drive-web-app.md`](drive-web-app.md) §5 Q3).

## 7. What stays deferred / carries over

- **The "Add to my Drive" mount is deferred on native too** — a `Placement` has no visible
  home until personal-FS placement rendering lands. Web inbound is therefore
  **membership-driven, mount optional**: a "Shared with me" tab over `sharedWithMe`, no
  mount. The deferral carries over; it is not a new web question.
- **Cross-platform interop scope.** v1 can be web↔web; a web inviter ↔ Android/desktop/iOS
  contact works because the link/QR/SAS encodings are shared KMP, but the *form factors*
  differ. Decide the v1 interop matrix when the slice starts.

## 8. The one real implementation unknown

Everything above is rendering over existing op code. The genuine new risk is
**shared-realm key persistence in the web IndexedDB store**: inbound Mode-B needs the apex to
hold the shared realm's `Kr` and the GR-7 `RetainedRealmKey` keyring (trial-decrypt across
rotations). That path is exercised on native's local store but **untested on the web IDB
store across reloads** — verify it early in the implementing phase.

## 9. Threat model

A handshake (A24/A25) + grant (A26/A27) pass is required when the slice lands
([`drive-web-app.md`](drive-web-app.md) §8). Running it in a *browser* apex rather than a
native app touches the clipboard/camera surface and the SAS human channel; the
relay-unlinkability concern is already closed by `perRealmDeviceRelay` (per-realm device-key
JWT). No new model surface is expected from the **form-factor** decision here — the link
carries no relay-visible secret, and the apex runs the same sealed-box code — but confirm at
implementation time (House Rule #2).

## 10. Phasing (suggested, each shippable)

1. **MB-1 — Contact (link-only). ✅ SHIPPED 2026-06-25 (§11).** Invite create + link surfaces
   (copy / `navigator.share`; QR-display deferred), redeem-on-open, `connectedContacts()`.
   Contacts are `UNVERIFIED`.
2. **MB-2 — Grant + members. ✅ SHIPPED 2026-06-25 (runtime + SDK + inbound UI; §12).**
   `grant` / `accept` / `getRealmMembers` / pending-grant poll. Inbound (poll/accept/decline +
   members read) is built; outbound grant UI rides MB-4 (needs a realm to grant into).
3. **MB-3 — Mode-B file read. ✅ SHIPPED 2026-06-25 (§13).** `sharedWithMe` /
   `browseSharedFolder` / `readSharedFile` + the "Shared with me" tab browse/open. The §8
   keyring/content path is now wired (live cross-user read pending on-device).
4. **MB-4 — Share a folder (outbound). ✅ SHIPPED 2026-06-25 (§14).** `shareFolder` from the
   Drive browser + Manage access (grant/members/remove). Unblocks MB-2's deferred outbound
   grant UI (there's now a realm to grant into).
5. **MB-5 (v2, additive) — Verify a contact. ✅ SHIPPED 2026-06-26 (§15).** Camera-QR scan +
   the 6-digit SAS panels → `VERIFIED`. (Standalone contact-detail *re-*verification deferred —
   no native op exists; the verify rides the handshake.)

---

## 11. MB-1 as-built (2026-06-25)

The link-only contact handshake renders end-to-end on the web. Same `:contact-service` ops
the natives run; the apex holds the keys, the surfaces only render (House Rule #5 — the thin
SDK exposes *operations*, React owns state). No protocol change.

**Vertical slice (the established 5 layers):**
- **Kotlin `@JsExport`** — `Contacts` (`tenshu-sdk-js/.../Contacts.kt`): `createInvite` /
  `revokeInvite` / `redeemInvite` (typed `RedeemResult` preserving the T-CI-01
  `alreadyUsed`/`gone` cases) / `awaitAck` / `completeHandshake` / `listInvites` /
  `connectedContacts` / `pendingRedemptions`.
- **JsRuntime wiring** — a `ContactPlugin` (`FirstPartyPlugins.kt`) registered via
  `JsRuntimeBuilder`, `dependsOn = {"sync"}` so its `initialize` runs after the scheduler is
  published: it registers the inviter-side `InviteResponsePickup` on the sync cycle
  (`addSyncCycleHook`) exactly as `:control-shared` does natively, and seeds the contact
  relations (`initContactService`). `RuntimeContext.contactProxyComponents` builds a
  `RestInviteRelayClient` over the same device-key JWT seam as the sync relay + the A13
  identity-key seam through `syncManager` + the IndexedDB redemption store.
- **`@tenshu/sdk/contact`** — typed surface (`web/sdk/src/contact.ts` + `native.d.ts`).
- **Apex gateway** — `contact.*` handlers + validators (`proxyGateway.ts`).
- **Drive proxy + UI** — wrappers (`proxy.ts`) + a **People tab** (`PeopleView`): connected
  contacts (formatted fingerprint, `grantable` badge), an invite dialog (Copy +
  `navigator.share`), pending invites, and the **inviter completion prompt** on a fresh
  `REDEEMED` row (snapshot + 5 s refetch).
- **Redeem-on-open** — an apex `/i` route (`routes/invite/redeem.tsx`): captures the
  `#v1.…` fragment, scrubs it from the URL, session-gates (`Session.detect`: firstTime →
  create-account / locked → unlock / active → name + redeem + await-ack). The redeemer's
  identity binds the new contact, so this is signed-in (unlike the anonymous Mode-A `/s/…`).

**Deviations / deferrals (all doc-sanctioned):**
- **QR-display deferred** — §3 marks it optional in v1; shipping it needs a QR-encoder npm
  dep, deferred to avoid pulling a runtime dependency without a maintainer call. Copy +
  `navigator.share` ship now.
- **Own display name is browser-local** (`localStorage`) — Drive-web has no profile surface
  and the apex `preferenceService` isn't wired into the JS runtime (mirrors the native
  `ownDisplayName` preference). Prompted inline on first use.
- **Cross-platform interop** stays as §7 notes (v1 exercised web↔web); §8 keyring
  persistence is an MB-2/MB-3 concern (inbound), untouched here.

**Tests:** `Contacts.kt` compiles + `:tenshu-sdk-js:jsTest` green; `proxy.test.ts` (wire
names/params), `contacts.test.ts` (fingerprint + own-name), `PeopleView.test.tsx` (list +
completion). All drive (94) + apex (231) suites green; both `pnpm -F {drive,apex} build`
typecheck clean. On-device / live-relay round-trip pending (can't run in a worktree).

---

## 12. MB-2 as-built (2026-06-25) — runtime + SDK + inbound UI

Scoped (maintainer call) to **runtime wiring + the full Membership SDK surface + the inbound
UI**. The *outbound* grant op ships in the SDK but its UI rides MB-4 — a grant targets a
`realmId`, and a shared realm only exists once a folder is shared (MB-4). No protocol change.

**Grant runtime wiring (the load-bearing part).** `syncProxyImplementationFactory` only
returns the GR ops when `realmRegistry` + `grantedRelayFactory` are non-null, and a shared
realm only pushes/pulls its roster with a relay. The web `SyncPlugin` now threads three seams
(`FirstPartyPlugins.kt` / `JsRuntime.kt` / `JsRuntimePlugin.kt`):
- **`realmRegistryHolder`** — populated by `SyncPlugin.initialize` from
  `SyncProxyComponents.realmRegistry`, read at dispatch time (same lazy-holder shape as
  `syncSchedulerHolder`); unlocks `CreateSharedRealm` + the GR ops.
- **`grantedRelayFactory`** (`perRealmDeviceRelay`) — a *granted* realm's relay JWT is signed
  with the member's fresh per-realm device key (D2 unlinkability), not the personal A4; feeds
  the grant op impls **and** registers `MemberSelfBindObserver` (a member's 2nd device
  self-binds).
- **`sharedRealmRelayFactory`** — an A4-signed per-shared-realm relay (the registry's
  `relayFactory`), so a member's accept writes a `Member` row that actually syncs to the owner.

File-content factories (`sharedRealmContentBlobStoreFactory`, `fileDatabase`) stay null —
Mode-B file read is MB-3; `Member`/`PendingGrant` rows ride the block stream, not the content
plane. `getRealmMembers` needed no extra wiring (its impl reads the DB).

**SDK + plumbing.** `@JsExport Membership` (`Membership.kt`): `grant` / `accept` / `members` /
`remove` / `pollPendingGrants` / `declinePendingGrant` → `@tenshu/sdk/membership` →
`membership.*` gateway handlers → drive `proxy.ts` wrappers. Mirrors the native
`MembershipRepository` verbatim.

**Inbound UI** — a Drive **"Shared with me"** tab (`SharedWithMeView`): `pollPendingGrants`
("Check for shares" sweep), accept/decline cards (grantor petname resolved from
`connectedContacts` by fingerprint), and on accept a joined-realm confirmation that reads the
`getRealmMembers` roster. Mount stays deferred (§7); the shared-folder file browser is MB-3.

**Deferred:** outbound grant UI (MB-4 — needs a realm); the §8 keyring-persistence verification
(exercised by the MB-3 inbound *read*, where retained-key trial-decrypt actually runs).

**Tests:** `Membership.kt` compiles + `:tenshu-sdk-js:jsTest` green; `proxy.test.ts`
(membership wire contract), `SharedWithMeView.test.tsx` (poll / accept→members / decline). All
drive (103) + apex (231) suites green; both web apps typecheck. Live Alice→Bob grant
round-trip pending (needs a relay + a shared realm from MB-4; can't run in a worktree).

---

## 13. MB-3 as-built (2026-06-25) — Mode-B file read

The inbound read path renders end-to-end: list the realms shared with you → browse a realm's
folder tree → open (download) a file. Membership-driven, mount optional (§7). Same dedicated
read ops the natives run; no protocol change.

**Content-plane wiring (the §8 unknown, now wired).** MB-2 left the content seams null. MB-3
threads them through the web `SyncPlugin` so a shared realm's `SyncProcessor` builds a
`FileContentManifestApplier`:
- `initSyncService` now gets **`fileDatabase`** (already on the runtime) +
  **`sharedRealmContentBlobStoreFactory`** (a per-shared-realm content store, built like
  `sharedRealmRelayFactory`) + **`syncProcessorHolder`** (publishes the personal processor for
  OnDemand).
- **`OnDemandResolution` registered in the JS `tContext`** — `readSharedFile` fetches a file's
  bytes lazily on open through the realm runtime's resolver. Registered via an **eager
  forwarding wrapper** whose real `OnDemandResolutionImpl` delegate builds lazily: the impl
  reads `syncRelay`, which reads `tContext`, so eager construction inside the `tContext` build
  would recurse — the wrapper breaks the cycle (delegate materialises on first `resolve()`).
- **GR-7 retained-key trial-decrypt is automatic** — once the content store + `fileDatabase`
  are wired, the per-realm processor factory populates `RealmContext.historicalDerivedKeys`
  from synced `RetainedRealmKey` rows and the applier falls back across retired Kr on AEAD
  failure. No extra seam.

**SDK + plumbing.** `@JsExport SharedFiles` (`SharedFiles.kt`): `sharedWithMe` /
`browseSharedFolder` / `readSharedFile` → `@tenshu/sdk/sharedFiles` → `sharedFile.*` gateway
handlers → drive `proxy.ts` wrappers. `getRealmMembers`/`listSharedFolder`/`readSharedFile`/
`getSharedRealmMemberships` op impls were already mapped by the file/identity factories — only
the runtime content-plane + OnDemand wiring was missing. The realm's display name is its
wrapper-root `SharedFile`'s name (the joiner-naming gap; fallback "Shared folder" until it
syncs).

**UI.** The "Shared with me" tab now has a **Shared folders** section over `sharedWithMe`;
selecting a realm opens `SharedFolderBrowser` (breadcrumb folder navigation; files open via a
lazy byte fetch — images/PDF/text render inline, everything else downloads; see §16).
Pending-grant cards (MB-2) stay above it.

**Deferred:** `Placement` mount ("Add to my Drive" — no visible home until personal-FS
placement rendering lands, deferred on native too).

**The one thing still unproven:** a **live cross-user** read — a member fetching the owner's
realm content with the per-realm content store (`sharedRealmContentBlobStoreFactory` signs the
content JWT with the device's A4 scoped to the owner's realmId, mirroring native exactly).
Can't be exercised in a worktree (no relay, no shared realm). This is the doc's "one real
implementation unknown" (§8) — verify on-device with a real Alice→Bob folder share.

**Tests:** `SharedFiles.kt` compiles + `:tenshu-sdk-js:jsTest` green; `proxy.test.ts`
(sharedFile wire contract), `SharedFolderBrowser.test.tsx` (list / drill / open→download /
not-available), `SharedWithMeView.test.tsx` (realms list + browser route). All drive (113) +
apex (231) suites green; both web apps typecheck.

---

## 14. MB-4 as-built (2026-06-25) — Share a folder (outbound)

The outbound producer closes the loop: from the Files browser, **Share folder** bootstraps a
shared realm, populates it, and opens **Manage access** to grant contacts — which finally
gives MB-2's grant ops a realm to act on. Same op code natives run; no protocol change.

**File-content emit plane (the load-bearing add).** MB-3 wired the *read* side; sharing a
folder is the *write* side. `shareFolderIntoRealm` fans the folder's file bytes into the realm
content plane via a `FileContentSyncEmitter` it reads off a `SyncBlockEmitterHolder` — null ⇒
metadata-only (a member browses names but opens nothing), so this is the difference between a
real feature and a broken one. The web now threads it (chosen over metadata-only):
- A **`SyncBlockEmitterHolder`** on the runtime, passed to the file operation factory
  (`fileServiceProxyImplementationFactory(..., syncBlockEmitterHolder = …)`) and **populated by
  `SyncPlugin.initialize`** (`holder.emitter = components.syncBlockEmitter` +
  `holder.fileContentEmitter = components.fileContentEmitter`). The `SyncObserver` *is* both
  emitters; shared-realm emit routes through the realm registry's per-realm content store
  (`sharedRealmContentBlobStoreFactory`, wired in MB-3). The personal `contentBlobStore` stays
  null — the emit *reads* personal bytes from the local `fileDatabase` and *writes* to the
  shared realm's store, so the personal content plane isn't involved.

**SDK + plumbing.** `@JsExport SharedFiles` gains `shareFolder(folderPath)` (orchestrates
`createSharedRealm` → `shareFolderIntoRealm` → self-`mountSharedRealm`, mirroring the native
repo) + `sharedFolders()` (`listPlacements` filtered to owned realms via
`getSharedRealmMemberships` role=owner) → `@tenshu/sdk/sharedFiles` → `sharedFile.shareFolder`
/ `sharedFile.sharedFolders` gateway handlers → drive proxy wrappers. The grant/members/remove
ops were already exposed in MB-2.

**UI.**
- **Files browser** — folders get a **Share folder** row action (`shareFolder`); on success it
  opens Manage access for the new realm so people can be added immediately.
- **"Shared" tab** reframed to *everything you share out*: a **Shared folders** section
  (`sharedFolders`, each with **Manage access**) above the existing Mode-A **Shared links**.
- **`ManageAccessView`** (reused from the Files takeover + the Shared tab): the realm's member
  roster (`getRealmMembers`), an **Add people** picker (`connectedContacts` grantable, D7-deduped
  against the roster by fingerprint → `grant`), and per-member **Remove** (`removeMember`).

**Gating.** `shareFolderIntoRealm` carries the Tier-2 storage gate natively
(`ensureCapacity`); `createSharedRealm` hits the relay. The op enforces it inside; the web UI
doesn't pre-gate (the op surfaces a failure → the share returns null → a friendly error).

**Still unproven (worktree):** the **live cross-user round-trip** — a member actually reading
the owner's emitted bytes over the relay (two-plane content emit + the per-realm content JWT).
The metadata/realm/grant/roster all work locally; content flow is the §8 unknown, verifiable
only on-device with a real relay + a second device.

**Tests:** `:tenshu-sdk-js:jsTest` green; `proxy.test.ts` (shareFolder/sharedFolders wire
contract), `ManageAccessView.test.tsx` (members / grant-with-dedupe / remove),
`SharedFoldersView.test.tsx` (list + Manage takeover). All drive (120) + apex (231) suites
green; both web apps typecheck.

**Mode-B web cluster MB-1..MB-4 complete.** Remaining: **MB-5** (v2, additive — camera-QR scan
+ the 6-digit SAS verify upgrade → `VERIFIED` contacts).

---

## 15. MB-5 as-built (2026-06-26) — verify a contact (camera-QR + SAS)

The verify upgrade: an in-person QR scan + the §5.3 6-digit SAS compare mints a contact
`VERIFIED` instead of `UNVERIFIED` (TOFU). **No new ops** — the SAS is already proxy-derived and
returned by the MB-1 ops (`InviteAck.sas`, `ContactInviteItem.claimedSas`,
`PendingRedemptionItem.sas`), and `awaitAck`/`completeHandshake` already take `sasConfirmed`. MB-5
is the QR encode/decode + the SAS confirm UI + one SDK field.

**SDK.** One field: `ContactItem.verified` (`Contacts.connectedContacts` now reads
`TenshuIdentity.verification == VERIFIED`) → `@tenshu/sdk/contact`.

**Dependencies (maintainer-approved).** `qrcode` (encode, for QR-display) + `jsqr` (decode, for
camera scan) added to `web/drive` — the CMP `QrScanner` in `:ui-core` is Compose, not reusable
(§4), so the web has its own decoder. The camera is `getUserMedia({ facingMode: 'environment' })`
under the existing HTTPS.

**UI (all in Drive's People tab).**
- **QR-display** — `InviteDialog` gains a *Show QR* toggle (`QrImage`, the MB-1-deferred surface)
  rendering the same invite link.
- **Camera scan** — a *Scan invite* button → `ScanInviteFlow`: `QrScanner` decodes the QR → name
  → `redeemContactInvite` → the §5.3 SAS (from `pendingRedemptions`) shows in a `SasConfirmPanel`
  → *They match* runs `awaitInviteAck(sasConfirmed = true)` (→ VERIFIED), *They don't* falls back
  to the unverified close.
- **Inviter SAS** — `ContactCompletionDialog` shows the `claimedSas` + a "the codes match" tick
  that flips `completeHandshake(sasConfirmed = true)`; the button reads *Add verified contact*.
- **Verified badge** — the People list badges `verified` contacts (green ✓ / "Verified").

**Deferred:** standalone contact-detail *re-*verification (the doc's "(UI is v2)") — there's **no
native op** to re-derive a SAS for an already-connected contact or flip its verification outside
the handshake, so it's not buildable on the web without a new `:contact-service` op. The verify
rides the invite handshake only.

**Cross-browser / on-device.** The camera path (`getUserMedia` + `jsQR`) and the cross-browser
WebAuthn-style sweep (Safari/iOS, Firefox) can't run in a worktree — verify on-device. Unit tests
mock the camera (`QrScanner` stub) + the SAS derivation (proxy-supplied).

**Tests:** `Contacts.kt` compiles + `:tenshu-sdk-js:jsTest` green; `contacts.test.ts`
(`formatSas`), `SasConfirmPanel.test.tsx`, `ScanInviteFlow.test.tsx` (scan→name→redeem→SAS→verified
close + loud already-used), `PeopleView.test.tsx` (verified badge). All drive (126) + apex (231)
suites green; both web apps typecheck.

---

**Mode-B web sharing (MB-1..MB-5) is complete:** link-only contact handshake → cross-user grant +
inbound membership → Mode-B file read → outbound folder share + Manage access → camera-QR + SAS
verify. The standing on-device items are the live cross-user round-trips (handshake, grant accept,
content read/emit over a real relay) + the cross-browser camera/WebAuthn sweep — none verifiable in
a worktree.

---

## 16. Inline shared-file preview (2026-06-26) — MB-3 follow-up

Closes the "open = download" deferral on the shared-file read path (§13). The decrypted bytes
from `readSharedFile` already live in the page, so previewing is **pure web-side — no SDK,
protocol, or op change**.

**Behaviour.** Opening a shared file derives a preview kind from its filename extension
(`lib/preview.ts`, `previewKind`): **image** (raster — png/jpg/gif/webp/bmp/avif/ico) → `<img>`,
**pdf** → `<iframe>`, **text** (txt/md/csv/json/xml/yaml/log/…) → decoded `<pre>`. Each renders
in a lightbox (`FilePreview` in `SharedFolderBrowser`) with a **Download** button; **everything
else downloads directly**, as before. SVG is deliberately *not* previewable (untrusted shared
content tagged `image/svg+xml`). Text over `TEXT_PREVIEW_MAX_BYTES` (256 KiB) falls back to
download rather than render a huge `<pre>`. The per-file row label reads **Open** for previewable
kinds, **Download** otherwise.

**Object-URL lifecycle.** The open preview owns its `URL.createObjectURL` blob; a `useEffect`
keyed on the preview state revokes it on close / change / unmount, so the decrypted bytes don't
linger in the URL store. Direct downloads create + revoke the URL inline.

**Regular (non-shared) files now preview too** (landed 2026-06-26, out of Mode-B scope — see
[`drive-web-app.md`](drive-web-app.md) §6 *Post-C*). `FilePreview` + `buildPreview` + `previewKind`
were extracted here into `components/FilePreview.tsx`; the personal-file path bridges `fs.getData`
and reuses the same lightbox, so both surfaces share one preview implementation.

**Tests:** `preview.test.ts` (extension→kind mapping incl. SVG-excluded + extension-less);
`SharedFolderBrowser.test.tsx` extended (text → inline lightbox + Download link, no auto-download;
image → `<img>` with the blob src; non-previewable → download, no dialog). All drive (132) + apex
(231) suites green; both web apps typecheck. On-device live cross-user read still pending (§8/§13).
