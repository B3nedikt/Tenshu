# Cross-app file access — a Drive picker for Tenshu apps, and Drive in the OS file dialogs

**Status:** v0.3 (2026-11-01). **P3 built** (Android `DocumentsProvider` in Control, read-only — as-built §9); P0–P2 and P4–P6 have no code. D-A through D-H resolved (§3). Opened
off [`contacts-app.md`](contacts-app.md) §5, where D1's *pick an avatar from Drive* hit a gap
with no home. Scopes both halves of that gap, inventories what is already built (§2 —
considerably more than expected), records the decisions (§3) and phases the build (§5).

⚠ **v0.2 corrected two things v0.1 asserted**, recorded in place rather than silently edited:
a `DocumentsProvider` is **not** "ambient and continuous" disclosure (D-D — `queryChildDocuments`
is called by the system picker process, not the requesting app, so there is no bulk filename
leak); and **Synced Folders does not solve desktop** (D-F — it is the ingest direction only).
The v0.1 phase "P2 — Save to Drive" is also gone: D-H routes saving through the OS provider, so
it is a property of P3 rather than a phase.

---

## 1. The gap

Two halves, pointing in opposite directions. They share a name and almost nothing else.

**Inward — no Tenshu app can pick a file from Drive.** There is no "choose a file from your
Drive" affordance available to another Tenshu app. The live instance is not hypothetical:
Fit's `HealthFilePicker` opens the *platform* picker so the user can hand it a Google Takeout
export — so a user whose Takeout zip is sitting **in their Tenshu Drive** must export it back
out to local storage before Fit can read it. Contacts' avatar picker (D1) would be the second
instance, Photos and Calendar the third and fourth.

**Outward — Drive does not appear in the OS file dialogs.** When any third-party app on the
device opens a file picker, Google Drive, OneDrive and Dropbox appear as sources; Tenshu Drive
does not. Nor can a user "share to Drive" as a destination the way the other cloud drives
offer. This is the end-user parity gap the maintainer named: it is what makes a drive feel
like *part of the device* rather than a silo.

Every existing file-picker mention in the docs
([`drive-folder-upload.md`](drive-folder-upload.md) §139/§962,
[`ios-proxy-architecture.md`](ios-proxy-architecture.md) §928,
[`drive-synced-folders-new-folder-prompt.md`](drive-synced-folders-new-folder-prompt.md) §254)
is Drive **consuming** a platform picker to import files inward. Nothing anywhere makes Drive a
*provider*. Both halves above are unbuilt.

## 2. What already exists

The substrate is much further along than the gap suggests. Cataloguing it is most of the work,
because it changes what the phases are.

### 2.1 The data path is already there — this is not an IPC problem

Any Tenshu app can depend on `:file-service-client` and read files through the proxy today;
several non-Drive modules already do (`hub`, `control-shared`, `tenshu-sdk-js`,
`tenshu-sdk-ios`). **No new protocol, op, or IPC surface is required for a first-party app to
read a file it knows the path of.** What is missing is (a) a *UI* for choosing which file, and
(b) any *restriction* on which files an app may touch (§2.3).

### 2.2 The permission model exists as a descriptor — and names this exact feature

[`tenshu-rei.md`](tenshu-rei.md) §2.2 defines a capability class per operation:

- `Ambient` — read-broad, fine to grant once.
- **`Picker` — "write or destructive, default-grant per-invocation through a system-mediated
  picker in Control's UI".**
- `Scoped` — ambient but constrained by an entity filter ("only files in /Photos").

That middle one is precisely the SAF / `UIDocumentPicker` pattern: **the pick *is* the grant**.
The design already exists; nothing implements it.

Concretely built (`control-app-registration.md` Track A A0–A3, 2026-05-31):
`CapabilityClass {Ambient, Picker, Scoped}` and
`GestureRequirement {None, RequiresUserGesture, RequiresExplicitConfirmation}` as real types in
`:core` (`org.tenshu.core.app.AppManifest`), with the safe defaults (`Picker` +
`RequiresUserGesture`) baked in as field defaults; `AppRegistration` / `AppGrant` schemas; the
`AppService` surface with `RegisterApp` / `RemoveApp` / `SetAppGrant`; and Control's Apps list +
detail + permission widget UI.

Rei §2.2 also defines the **primary handler**: one file manager per realm, holding ambient
access to `Picker`-class ops on its data type, user-swappable in Control. Drive ships as the
default primary handler for files. That concept is what keeps a picker from being an
every-single-time modal for the app that legitimately manages files.

### 2.3 ⚠ But nothing is enforced — say this out loud

Two independent gaps make the permission model display-only today:

1. **`CapabilityClass` never reaches the dispatch path.** Every reference outside
   `AppManifest.kt` is in Control's UI layer (`AppRegistry`, `MockAppRegistry`, screen tests).
   `ImplementationFactory` knows `SetAppGrant` only as an op it can *run* — it does not consult
   a grant before dispatching anything.
2. **No `AppRegistration` row is ever written.** Track A **A4** — the per-binding handshake
   trigger that actually calls `RegisterApp` — is unshipped, so, per that doc, *"no app (incl.
   Drive) appears in Control's Apps screen."*

**Consequence, and the single most important framing in this doc:** an app that links
`:file-service-client` can read every file in the realm right now. **Shipping a picker adds no
security whatsoever** — it adds *convenience*. Picker UX and grant enforcement are two
independent tracks, and conflating them would produce a security theatre surface that looks
like a permission boundary and is not one. Phasing (§5) keeps them separate and honest.

### 2.4 The outward half has more machinery than expected

Drive on Android already ships **two `ContentProvider`s**:

- `androidx.core.content.FileProvider` (`${applicationId}.fileprovider`) — the D3
  *open in another app* flow, handing a decrypted temp file to a chooser with a per-launch read
  grant.
- **`StreamingVideoProvider`** (`drive-shared-ui/.../common/StreamingVideoProvider.android.kt`,
  `exported=false`) — serves a **proxy file descriptor** backed by a `StreamingContentSession`,
  so `MediaPlayer` reads ranges that are decrypted on demand.

The second is the interesting one. `FilesRepository.openStreamingContent(path)` →
`openProxyFileDescriptor` → `onRead`/`onRelease` is **built and proven**
([`drive-streaming-preview.md`](drive-streaming-preview.md) S1–S4). A `DocumentsProvider`'s
`openDocument` returns exactly that: a `ParcelFileDescriptor`. So the hard part of the outward
half — *serving bytes of an encrypted, chunked, remote file to an arbitrary consumer without
materialising the whole thing* — is done. What a `DocumentsProvider` adds on top is the
directory/query surface (`queryRoots`, `queryChildDocuments`, `queryDocument`) over
`:file-service`, which is ordinary metadata work.

### 2.5 There is a picker seam precedent to copy

Fit's `HealthFilePicker` is a **DI-bound `fun interface`, deliberately not `expect`/`actual`**,
with the shell owning the `Activity`/window and per-platform holders
(`AndroidHealthFilePicker` keys the handover by owner so a dying Activity cannot silently break
a newer one). Its KDoc states the design rule: *"platforms that cannot pick a file simply do not
bind it, and the 'Add a source' row hides rather than offering something that does nothing."*
A Drive picker seam should be shaped the same way.

## 3. Decisions D-A – D-H (resolved 2026-08-02)

All eight were worked through with the maintainer in one pass. Kept lettered as opened.

⚠ **Standing constraint on D-A/D-B: [`tenshu-rei.md`](tenshu-rei.md) already owns app-to-app
invocation and already deferred it.** Q16 (*multi-app workflows* — "a Tenshu-mediated
invocation, Drive calling the viewer's `OpenFile` operation") was **decided 2026-05-29 as v2**,
landing with Q12's **delegated capability tokens**; neither is built.
[`contacts-app.md`](contacts-app.md) Q1 is the third face of the same primitive. Everything below
is a *seam* the Rei primitive can later sit behind — never a competing model.

### D-A — A shared UI module now, with the seam swappable for Control later

⚠⚠ **The decisive finding: reading files is `Ambient` in Rei's own model, not `Picker`.**
Control's permission rows carry `tenshu/file/getData` → "Read your files" →
`CapabilityClass.Ambient`, while `uploadFiles` and `deleteFile` are `Picker`. So a picker for
*choosing a file to read* **is not a permission boundary at all** — an app holding ambient read
can already read everything, and the picker is pure convenience. It becomes a boundary only for
an app that *lacks* ambient read and is scoped to one file — which is precisely Q12's delegated
capability token, deferred to v2.

**Resolved: a `:file-picker-ui`-style component each app renders in-process over
`:file-service-client`** (the `QrScanner`-in-`:ui-core` precedent). It works identically on
Android, desktop and iOS route-1, and the seam swaps for a Control-mediated implementation when
third-party pressure and Q12 arrive.

Two facts that ruled out Control-hosting *today*: ⚠ **Control has no file browser** (its screens
are apps / data / epc / home / locked / newuser / notifications / offlinemaps / recovery /
services / settings / shares / sync / unlock / welcome), so it would be a from-scratch build in
the wrong app; and ⚠ **iOS route-1 has no Control in the loop at all** — each app is its own
in-process proxy — so it would need a second mechanism there regardless.

⚠ **The justification does not extend to writes.** `uploadFiles` is `Picker`-class, and Rei Q7
accepted a residual risk explicitly bounded by *"all `Picker`-class ops remain Control-mediated"*.
That is why D-H keeps v1 read-only.

### D-B — A small `PickedFile` result

**Resolved:** `{path, contentId, name, size}` + a read entry point. Rationale is the addressing
model: `File`'s DataId is its **path**, and paths move (`File.fileId` is deleted, and
the rekey primitive migrates only reference types it knows about — a third app's stored pick is
not one). **`contentId` is move-immune by construction**, so it is what a durable consumer
stores; `path` carries provenance and display; bytes stream on demand for one-shot consumers.
It is also where Q12's capability token lands later without touching a single caller.

Covers both consumer shapes: Fit's import wants **bytes now** and stores nothing; Contacts'
avatar stores a **durable reference** and re-reads it forever.

### D-C — Control hosts the `DocumentsProvider`: it lives with the proxy

⚠ Note "Control has no file browser" is **not** a constraint here, unlike D-A — a
`DocumentsProvider` has no UI of its own; the system renders the picker from `queryRoots` /
`queryChildDocuments`.

**Resolved: Control.** One IPC hop rather than two (Control *is* the proxy, holds the keys, and
already hosts the streaming registry — only the fd wrapper sits in `drive-shared-ui`), and it is
**handler-neutral**: it survives Drive being uninstalled or swapped under Rei's primary-handler
model, because the files belong to the realm, not to Drive. Generalises to one rule — **the
provider lives wherever the proxy lives** — which puts it in Drive on iOS route-1. Reads as
cross-platform inconsistency; is actually one rule over two topologies.

### D-D — Rely on SAF's per-URI grants, plus one master switch in Control

⚠ **Corrects this doc's own D-D framing.** The draft called a `DocumentsProvider` "ambient and
continuous" disclosure. That overstates it: `queryChildDocuments` is called by the **system
picker process** (DocumentsUI), *not* by the requesting app, which receives only a per-URI grant
for what the user actually picked. **There is no bulk filename disclosure to third-party apps**,
and Android's picker is already the trusted mediator Rei §2.2 describes — Google's rather than
Control's, but the right shape.

What remains real: the Drive root becomes *visible* to any app opening a picker, and decrypted
bytes do cross to the chosen app.

**Resolved:** lean on the OS for per-file grants (it already does this better than we would) and
add **one switch in Control** — "Let other apps open your Drive files" — as the kill switch.
**Default on**, matching Google Drive and OneDrive: a provider nobody can discover is a parity
feature that does not deliver parity. A per-app allowlist was rejected as duplicating the OS's
enforcement at a coarser grain, and it would need the app identity Track A A4 has not shipped.

### D-E — iOS File Provider: deferred behind replica scoping

**Resolved: a dependency, not a preference.** A File Provider runs as a **separate process**, and
under route-1 there is no proxy to call into (EPC is not an option — it needs Control actively
hosting, the root of Fit's permanent "Can't reach Control" banner). So the extension needs **its
own in-process runtime over the shared replica**.

⚠⚠ The replica is the blocker: [`ios-route1-replica-scope.md`](ios-route1-replica-scope.md)
records Drive holding **107,055 rows** (~10.4k Fit workouts against **13 file rows**) because
each app registers every service module. That cannot fit an extension's memory budget. (The
second half of this objection has expired: iOS bound `InMemoryFileDatabase` so there was no durable
plaintext cache either — `IosFileDatabase` closed that on 2026-08-03. The working set, not the
cache, is what remains.) ⭐ The multi-process *locking* is already solved (`SharedReplicaDatabaseLock`,
flock on `replica.writer.lock`) — the mechanism is not the problem, the working set is.

Sequenced after device-scoped replicas land, which also gives that work a concrete second
consumer to design against.

### D-F — Desktop: deferred, with the virtual filesystem recorded as the end-state

⚠ **Synced Folders does not solve this** (a possibility this doc floated — now closed). It is the
*ingest* direction, and `drive-synced-folders-new-folder-prompt.md` states: *"Desktop: out of
scope. Control's resident watcher owns desktop ingest."* Nothing mirrors Drive content **out** to
disk, which is what a desktop file dialog would need.

**Resolved: defer**, recording the **on-demand virtual filesystem** as the intended shape —
macOS File Provider (the same framework as iOS, since macOS 11), Windows Cloud Filter API, FUSE
on Linux. Nothing is blocked: P0–P2 already cover Tenshu apps on desktop. A permanently mirrored
plaintext folder was rejected — a far larger A5 change than an on-demand fd, and it fights the
`OnDemand` content model by downloading the whole realm.

### D-G — Web: deferred, analogues recorded

**Resolved: defer.** ⚠ The *outward* half does not map — a browser has no OS file-dialog registry
to appear in. Web's equivalents are both PWA-manifest features: **Web Share Target** (so
Android/ChromeOS can "share to Drive") and **file handlers**. The File System Access API is the
*opposite* direction (a web app reading local disk) and serves save-as, not discoverability.

The inward half has **no web consumer**: `drive.tenshu.app` is the only Tenshu web app, so a web
picker would ship validated by nothing. Rides the already-outstanding PWA manifest work (web
roadmap Phase 7) when it lands.

### D-H — v1 is read-only; saving arrives via the OS provider

**Resolved: read-only picking in v1.** It is the half D-A's `Ambient`-read justification actually
covers, and the half with two waiting consumers (Contacts avatars, Fit's Takeout import).

⭐ **Saving then needs no bespoke Tenshu picker at all**: once D-C's `DocumentsProvider` lands, an
app writes into Drive through the standard **`ACTION_CREATE_DOCUMENT`** flow, system-mediated by
construction — so the `Picker`-class op stays Control-mediated and Rei Q7's bound holds without
special effort. Putting a save picker in the shared UI module would have quietly weakened exactly
that bound.

## 4. Dependencies and interactions

- **[`tenshu-rei.md`](tenshu-rei.md) §2.2** — owns the capability model this implements. The
  picker is the `Picker` class's reference implementation; do not invent a parallel model.
- **`control-app-registration.md` Track A A4** — until app registration actually fires, there is
  no principal to grant *to*. Enforcement is blocked on it; the inward picker is not.
- **[`drive-streaming-preview.md`](drive-streaming-preview.md) S1–S4** — the fd/range substrate
  the outward half rides (§2.4). Android only today; iOS has no equivalent yet.
- **[`contacts-app.md`](contacts-app.md)** — first consumer (D1 avatars, gated behind the
  `PHOTO`-import fallback so Contacts is not blocked on this doc).
- **[`ios-route1-replica-scope.md`](ios-route1-replica-scope.md)** — ⚠ **a hard gate on P5**, per
  D-E: an iOS File Provider needs its own runtime, and the current 107k-row replica cannot fit an
  extension. This doc is that work's second concrete consumer.
- ~~**`file-content-addressing.md` Ph4 (refcount + GC)**~~ — ⚠ **struck, but no longer for the
  reason given.** Phase 4 was revised 2026-07-18 away from refcounting as HR#1-impossible; a
  client-**declared** refcount shipped instead
  ([`content-plane-refcount.md`](content-plane-refcount.md), CR-0…CR-5). ⚠⚠ **The gate exists in
  mechanism and Contacts does not reach it**: declaration is driven by the schema's
  `Syncable(SyncStrategy.OnDemand)` trait, which today only `File` and `Workout.Streams` carry — so
  an avatar sharing a `contentId` with a Drive file still has no shared-reference gate, and the
  Keep/Move/Delete prompt stays informational. ⚠ **Wire an avatar as `OnDemand` and it gains one
  for free**, which is the day to revisit D1. See [`contacts-app.md`](contacts-app.md) Q3.

## 5. Phases

Ordered so the cheap, honest wins land first and nothing claims a boundary it does not have.
⚠ Note the v1 draft's "P2 — Save to Drive" is **gone**: D-H routes saving through the OS provider
instead, so it is a property of P4 rather than a phase of its own.

- **P0 — The seam.** A DI-bound `FilePicker` interface in the §2.5 shape returning D-B's
  `PickedFile`. No UI. Unblocks callers being written against it.
- **P1 — Inward picker, in-process (D-A), read-only (D-H).** The shared UI component over
  `:file-service-client`; Contacts' avatar and Fit's Takeout import as the two first consumers.
  ⚠ Documented explicitly as convenience, not a permission boundary (§2.3).
- **P2 — Enforcement track (independent).** Track A A4, then `CapabilityClass` consulted at
  dispatch, then the primary-handler grant. **This is the phase that makes the picker mean
  something**, and it is not the picker's own work. Runs on its own clock.
- **P3 — Android `DocumentsProvider`, hosted by Control (D-C).** Query surface over
  `:file-service` + `openDocument` returning the existing streaming fd, plus D-D's master switch.
  ⚠ Owes the A5 amendment. ⭐ Also delivers **save to Drive** for free via `ACTION_CREATE_DOCUMENT`
  (D-H).
- **P4 — Android "share to Drive"** as an upload destination (the `ACTION_SEND` target half).
- **P5 — iOS File Provider extension.** ⚠ Gated on D-E: device-scoped replicas must land first.
- **P6 — Desktop virtual filesystem** (D-F) and **web share-target / file handlers** (D-G), each
  behind its own gate.

P0–P1 deliver the parity gap for *Tenshu apps*; P3–P6 deliver it for *the OS*; P2 is the security
story and blocks none of them.

## 6. Threat-model notes

- **P3 / P5 owe an A5 amendment** (House Rule #2): a provider serving plaintext to a chosen app
  is a broader disclosure than the per-launch D3 chooser grant. ⚠ **D-D narrows what the
  amendment must claim** — the requesting app never sees a directory listing (the system picker
  process does), so the entry covers *root visibility* + *per-URI plaintext to the picked app*,
  not bulk metadata disclosure. D-D's master switch is the user-visible control it needs.
- **P2 touches A17 / T-C-14** (`control-app-registration.md`'s existing amendment) by turning a
  recorded grant into an enforced one.
- **P0–P1 owe nothing** — they add no capability an app does not already have (§2.3).
- **No relay impact anywhere in this doc.** All of it is device-local; the relay stores
  ciphertext (House Rule #1) and is not involved in picking, granting, or serving.

## 7. Implementation Notes for Claude Code

**First slice is P0 + P1**, and they are small. P0 is a DI-bound `fun interface` in the
`HealthFilePicker` shape (§2.5) — *not* `expect`/`actual`, so a platform that cannot pick simply
does not bind it and the affordance hides — returning D-B's `PickedFile`. P1 renders a file
browser in-process over `:file-service-client`, the `QrScanner`-in-`:ui-core` precedent.

Two consumers are waiting and should be wired as the proof: **Contacts' avatar** (stores
`contentId`, the move-immune half) and **Fit's Takeout import** (wants bytes, stores nothing) —
Fit's `HealthFilePicker` currently sends the user to SAF even when the file is in their Drive.

⚠ **Do not let P1 imply a permission boundary** (§2.3). Copy and KDoc should say it is a
convenience. The boundary arrives with P2 (enforcement) and Rei Q12's capability token, not here.

⚠ **P3 is the first phase that owes a threat-model amendment** (A5, narrowed per D-D) and the
first that touches Control's manifest. It is also where **save-to-Drive arrives for free** via
`ACTION_CREATE_DOCUMENT` — resist building a Tenshu save picker.

⚠ **P5 (iOS) is blocked, not merely unscheduled** — see D-E. Do not start it before
[`ios-route1-replica-scope.md`](ios-route1-replica-scope.md) lands a device-scoped replica.

## 8. References

- [`tenshu-rei.md`](tenshu-rei.md) §2.2 — capability classes, the `Picker` class, primary handler.
- `control-app-registration.md` — `AppRegistration` / `AppGrant` / `SetAppGrant`, Track A status.
- [`drive-streaming-preview.md`](drive-streaming-preview.md) — the fd/range substrate (S1–S4).
- [`contacts-app.md`](contacts-app.md) §5 — where this gap was surfaced.
- [`drive-folder-upload.md`](drive-folder-upload.md),
  [`ios-proxy-architecture.md`](ios-proxy-architecture.md) — the existing *inbound* picker uses.
- [`ios-route1-replica-scope.md`](ios-route1-replica-scope.md) — compounds D-E.

## 9. As-built: P3 — Android `DocumentsProvider` (2026-11-01)

Tenshu Drive is a root in the system file dialogs on Android 8+ ("Open from…", `ACTION_OPEN_DOCUMENT`,
`ACTION_GET_CONTENT`), read-only, hosted by Control per D-C. Threat model: the A5 amendment
*"Drive in the Android system file dialogs"*.

- **Two layers.** `DriveDocumentsSource` (`control-shared` commonMain) holds everything
  realm-shaped — listings, one item's metadata, ranged content, stored thumbnails — over Control's
  in-process `TenshuClient`, so it is tested on desktop against the real proxy chain
  (`DriveDocumentsSourceTest`). `DriveDocumentsProvider` (androidMain) only turns that into
  cursors and file descriptors. Its read helpers mirror the macOS bridge's (`ControlFileBridge`);
  the two were not merged, so a fix to one likely owes the other.
- **Bytes** go out through `StorageManager.openProxyFileDescriptor` over `OpenContentStream` /
  `ReadContentRange`: a reader fetches and decrypts only the chunks it touches, and nothing is
  written to disk. ⚠ That needs API 26, so **below 26 no root is offered** rather than a
  whole-file fallback. ⚠ A file with no content stream yet (bytes only local, not pushed) is read
  whole into memory — the macOS bridge's fallback too — but ⚠⚠ **only up to 64 MiB**
  (`MAX_WHOLE_FILE_BYTES`) and refused above it: Control is every app's proxy on a phone-sized
  heap, and a just-recorded 2 GB video picked before it synced would otherwise OOM it.
- ⚠ **Each ranged read blocks the reading app** until its chunk arrives, bounded at 60 s
  (`READ_TIMEOUT`), after which the app gets `EIO`. Listings and opens are bounded at 30 s,
  thumbnails at 8 s, and both honour the picker's `CancellationSignal`. Every failure reaches the
  caller as `FileNotFoundException`, the one exception apps catch around `openInputStream`. This is
  the trade against Google Drive / OneDrive, which download the whole file before `openDocument`
  returns: we start sooner and fetch less, but a stalled relay shows up as a read error in the
  other app.
- ⚠ **Files only, never a folder.** The root does not set `FLAG_SUPPORTS_IS_CHILD`, so the picker
  will not hand an app a folder (`ACTION_OPEN_DOCUMENT_TREE`). A tree grant lets the app list
  everything beneath it, which is exactly the disclosure D-D says does not happen. Adding folder
  picks is a decision for the threat model first, not a flag flip.
- **Thumbnails** are the stored `LIST` / `LARGE` classes only — no new rung. Naming a hero class
  would declare it wanted on this device and start a realm-wide backfill.
- ⚠ **Document ids are realm paths** (`/` is the root). Simple, but a rename or move breaks a URI
  an app was granted earlier, and ⚠⚠ a different file later saved at the same path is what that
  URI then opens (threat model A5). Stable ids would need an identifier map like the macOS bridge's
  `FileProviderIds`; deferred until a consumer persists grants and complains.
- **The master switch** (D-D) is `DriveDocumentsAccess`: a device-local SharedPreferences boolean,
  default on, shown under Control → Settings (`PlatformSettingsSection`, renamed from
  `DesktopOnlySettingsSection`). It is checked on **every** call, so turning it off also kills
  earlier grants, and it notifies the roots URI so the picker drops the root at once.
- **Not built:** search and recents (`FLAG_SUPPORTS_SEARCH` / `FLAG_SUPPORTS_RECENTS`), change
  notifications on folder cursors (the picker re-queries on navigation), shared-realm files
  (`SharedFile`) — only the personal file tree is listed — and D-H's save-to-Drive via
  `ACTION_CREATE_DOCUMENT`, which needs `createDocument` + a writable fd.
