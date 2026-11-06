# Drive on the web — bringing the web app to feature parity

**Status:** ⚠ this said "Draft v0.1 (2026-06-24). No code." until 2026-08-11, contradicting its
own §6. **Phases A–C are COMPLETE** (A shell+bridge, B1–B3 SDK boundary, C1–C5 React UI),
plus two post-C slices landed 2026-06-26 (Open/preview, image row thumbnails).
Phase D remains deferred — and per §5 Q2 the web QR/SAS handshake is **resolved**
(`drive-web-mode-b-sharing.md`), so Mode-B is deferred as implementation work, not as an
unknown. Original framing follows: resolves the architectural fork and
defines the scope + SDK contract so Phase 8 of [`docs/web/roadmap.md`](../web/roadmap.md)
can be executed incrementally.

**Owners' note:** this doc is decision-oriented, not a UX redraw — the native Drive
screens ([`drive-shared-logic/`](../../drive-shared-logic)) already define the
interaction model. Its job is (1) lock the state/logic architecture, (2) triage which
native features even apply to web, (3) enumerate the SDK boundaries each in-scope feature
needs, and (4) phase the work.

---

## 1. Where we are

Drive-on-web **does not exist yet**. This is greenfield, not a feature-catch-up:

- **No `web/drive/` workspace.** [`web/`](../../web) holds only `apex/` (the control
  plane / `tenshu.app`) and `sdk/` (the `@tenshu/sdk` bridge). Phase 8 of the web roadmap
  (`drive.tenshu.app`) is fully gated and unstarted.
- **The SDK exposes a thin slice of file ops.**
  [`FileSystem.kt`](../../tenshu-sdk-js/src/jsMain/kotlin/app/tenshu/sdk/FileSystem.kt)
  exports `list` / `createFolder` / `uploadFile` / `getData` / `getPreview` — and nothing
  else. Move, rename, delete, file details + activity, sharing, manage-access, and
  shared-realm browse have **no JS boundary**.
- **The parity target is [`FilesRepository`](../../drive-shared-logic/src/commonMain/kotlin/app/tenshu/drive/data/FilesRepository.kt)**
  (28 operations) + [`MembershipRepository`](../../drive-shared-logic/src/commonMain/kotlin/app/tenshu/drive/data/MembershipRepository.kt)
  + [`ContactsRepository`](../../drive-shared-logic/src/commonMain/kotlin/app/tenshu/drive/data/ContactsRepository.kt),
  surfaced through the native screens under
  [`drive-shared-logic/.../ui/screens/`](../../drive-shared-logic/src/commonMain/kotlin/app/tenshu/drive/ui/screens)
  (`files`, `filedetails`, `home`, `movepicker`, `settings`, `shared`).

So "parity" decomposes into three layers, landed in order: **subdomain shell + IPC →
per-feature SDK boundaries → React UI**.

---

## 2. Decision: thin SDK + TypeScript rebuild (LOCKED)

The central fork was whether the web state/logic layer **reuses
`drive-shared-logic`** (compile the Compose-free VM/repo layer to JS, as iOS reuses it via
SwiftUI binding) **or reimplements VM logic in TypeScript** over a thin operation-only SDK.

**Decision: thin SDK + TypeScript rebuild.**

The SDK exposes *operations* (Promise-returning calls + reactive streams), not
repositories. React owns its own state (TanStack Query over the postMessage proxy, per
[architecture.md](../web/architecture.md) §"TanStack Query"). `drive-shared-logic` is a
**behavioural reference**, not shared code, and is **not** compiled into the JS bundle.

Rationale:
- **House Rule #5** — the web UI is TypeScript + React, not CMP-on-web; the protocol layer
  is shared via KMP→JS, the UI/state is not. Reusing the VMs pulls a large Kotlin surface
  (Compose-free, but still paging/flow/VM machinery) into the browser bundle and bends that
  line.
- **Bundle weight** — keeping the boundary at operations keeps the Kotlin→JS surface
  minimal, which matters for the gated bundle-splitting work
  ([`project_track_web_sdk_bundle_splitting`]).
- **Idiom** — React data-fetching (TanStack Query, Suspense, optimistic updates) is a
  poor fit for binding to externally-owned Kotlin VMs; rebuilding the thin state in TS is
  cleaner than adapting it.

Cost accepted: VM logic (selection, breadcrumb navigation, conflict resolution, paging
windows) is reimplemented in TS. This is bounded — most native VMs are thin orchestration
over the repository; the protocol/crypto/sync substance stays in Kotlin behind the SDK.

---

## 3. Scope triage for web v1

Not every native feature maps onto a browser. Each native `FilesRepository` /
`MembershipRepository` capability is classified **In / Deferred / N-A** for web v1.

| Native capability | Web v1 | Notes |
|---|---|---|
| Browse folder (`getFiles`/`getFilesPaged`) | **In** | `list` exists; add paging. Core screen. |
| Recent files (`recentFiles`) | **In** | Home surface; reuse `list`-style walk or add boundary. |
| Upload file (`uploadFile`) | **In** | Exists. Add progress + drag-drop UX. |
| Upload folder (`uploadFolder`) | **Deferred** | Browser folder upload (`webkitdirectory`) + conflict dialog is its own slice. |
| Download / read bytes (`getRemoteFile`/`getData`) | **In** | ✅ landed — `fs.getData` bridged + Details-drawer Open/Download with inline preview (§6 Open/preview). |
| Preview thumbnails (`getPreview`) | **In** | ✅ landed — `fs.getPreview` bridged + lazy `FileThumbnail` row icons for image files (§6 Thumbnails). |
| File details (`fileDetails`) | **In** | Reactive metadata stream → details pane. |
| File activity (`fileActivity`) | **In** | Modification history; reactive. |
| Create folder (`createFolder`) | **In** | Exists. |
| Rename (`renameFile`) | **In** | New boundary. |
| Move (`moveFile`) | **In** | New boundary + move-picker screen. |
| Delete (`deleteFile`) | **In** | New boundary. |
| Mode-A share links (`shareLink`/`revokeShareLink`/`shares`) | **In** | New boundaries. Web is a *natural* home for link-sharing. |
| Mode-B share folder (`shareFolder`/`sharedFolders`/`browseSharedFolder`/`readSharedFile`/`sharedWithMe`) | **Deferred** | Large surface; lands as a second sharing slice after Mode-A proves the boundary pattern. |
| Membership grant/accept/remove (`MembershipRepository`) | **Deferred** | Couples to Mode-B + the contact handshake. Form factor now resolved ([`drive-web-mode-b-sharing.md`](drive-web-mode-b-sharing.md)); remaining deferral is the greenfield SDK surface. |
| Storage status / gating (`storageStatus`) | **In** | Cheap; the upload/share CTAs gate on it on native too. |
| Offline toggle (`downloadFile`/`evictFile`/`hasLocalData`, sync rules) | **N-A** | The intent/presence axes assume a local persistence *keeper* + always-resident proxy. The browser proxy's IDB store is already "local"; there is no online-only-vs-pinned distinction to expose in v1. Revisit if a web offline-keeper story emerges. |
| Synced folders auto-backup (`uploadForBackup`) | **N-A** | Assumes an OS file watcher; no browser equivalent. |

**v1 in-scope screens:** Files browse (+ breadcrumbs, selection), File details + activity,
Upload, Create/Rename/Move/Delete, Move-picker, Mode-A *My shares*, Home (recent). Settings
is minimal (no offline/synced-folders sections on web).

---

## 4. SDK boundary inventory

Each in-scope op that isn't already exposed needs **one Kotlin actual**
(`tenshu-sdk-js/.../FileSystem.kt` or a sibling object) **+ one TS wrapper**
(`web/sdk/src/files.ts`-style), following the established
`bundle.X.getInstance()` re-export pattern and the `Recovery.revealPhrase()` precedent.
KDoc on each Kotlin op should name the native `FilesRepository` method it mirrors.

**Already shipped:** `list`, `createFolder`, `uploadFile`, `getData`, `getPreview` (Phase A);
**`rename`, `move`, `delete` (Phase B1, 2026-06-24)** — see the as-built note below.

**To add (Phase B):**

| SDK op | Mirrors | Shape | Phase | Status |
|---|---|---|---|---|
| `FileSystem.rename(path, newName)` | `renameFile` | `Promise<boolean>` | B1 | ✅ landed |
| `FileSystem.move(sourcePath, destFolderPath)` | `moveFile` | `Promise<boolean>` | B1 | ✅ landed |
| `FileSystem.delete(path)` | `deleteFile` | `Promise<boolean>` | B1 | ✅ landed |
| `FileSystem.listPaged(path, cursor)` | `getFilesPaged` | `Promise<Page<File>>` | B1 | ⬜ (paging shape TBD) |
| `FileSystem.recentFiles(limit)` | `recentFiles` | `Promise<File[]>` | B1 | ✅ landed (with C5) |
| `FileSystem.details(path)` | `fileDetails` | `Promise<FileDetails \| null>` | B2a | ✅ landed |
| `FileSystem.activity(path)` | `fileActivity` | `Promise<FileActivity[]>` | B2b | ✅ landed |
| `Storage.status()` | `storageStatus` | `Promise<StorageStatus \| null>` | B2c | ⬜ re-homed → [`web-storage-status.md`](web-storage-status.md) (apex-side; build-all-3 decided) |
| `Shares.create(path, expiresAt?, maxOpens?)` | `shareLink` | `Promise<ShareLink \| null>` | B3 | ✅ landed |
| `Shares.revoke(shareId)` | `revokeShareLink` | `Promise<boolean>` | B3 | ✅ (pre-existing; now wired) |
| `Shares.list()` | `shares` | `Promise<ShareListItem[]>` | B3 | ✅ (pre-existing; now wired) |

**As-built — B1 mutations (rename/move/delete).** Three `@JsExport` ops added to
`tenshu-sdk-js/.../FileSystem.kt`, each a thin wrapper over the existing
`org.tenshu.core.file.system.{moveFile,deleteFile}` extensions (`:file-service/commonMain`)
with the same `.fold(onSuccess = { true }, onFailure = { false })` shape as `createFolder`.
The rename/move **target-path derivation lives in Kotlin**, copied verbatim from
`TenshuFilesRepository` (rename = `moveFile` under the same parent; move preserves the name
into the destination folder) so the web and native contracts can't drift. TS typing in
`native.d.ts`; reachable end-to-end via apex gateway handlers (`fs.rename`/`fs.move`/`fs.delete`)
+ drive `proxy.ts` wrappers (`rename`/`move`/`deleteEntry`). Op-level behaviour (a real
move/delete round-trip) is not unit-tested here — same posture as Phase A's `fs.list`; covered
by the native `moveFile`/`deleteFile` suites + the on-device pass. The drive proxy *wiring*
(right method + params) is unit-tested (`proxy.test.ts`).

**As-built — B2a details (2026-06-24).** `FileSystem.details(path)` added to
`FileSystem.kt`: navigate to the parent folder (`navigateToFolder`, mirroring
`TenshuFilesRepository`'s private `navigateTo`), `select { fields(PATH, FILES);
relations(Size, CreatedAt, LastModifiedAt) }`, take the first flow emission, return the
walker (`toJs()`) object whose `path` matches — i.e. the same shape `list` emits **plus
`createdAt`**, or `null`. **Reactive decision settled: snapshot + refetch** (the Flow's
`.first()` taken on the Kotlin side; React refetches via TanStack Query) — per the
recommendation below. Raw values (bytes / epoch-millis); formatting is the web UI's job
(thin-SDK). `native.d.ts` `NativeFileDetails` + public `FileDetails` in `files.ts`; wired
via apex `fs.details` handler + drive `details()` wrapper (unit-tested in `proxy.test.ts`).

**As-built — B2b activity (2026-06-24).** `FileSystem.activity(path)` added to
`FileSystem.kt`: navigate to the file, read its `File.FileHistory` relation, `select` the
modification fields (+ subtype fields + the `FileModification.Device` relation), take the
first emission, map each row via a private `mapActivityJs` (a faithful port of
`FileMapper.mapActivity`: the type id drives `kind` + the `detail` string; device name
resolves via the `FileModification.Device` reference, null when unknown), sort newest-first.
Returns `{ kind, detail, timestampMillis, deviceId, deviceName }[]` (raw epoch-millis; empty
on any error). `native.d.ts` `NativeFileActivity` + public `FileActivity` in `files.ts`;
wired via apex `fs.activity` handler + drive `activity()` wrapper (unit-tested in
`proxy.test.ts`). Snapshot + refetch (the Flow's `.first()` on the Kotlin side).

**As-built — B3 Mode-A shares (2026-06-24).** Mostly *already existed*: the SDK `Shares`
object already shipped `list()` (the realm-wide *My shares* projection) and `revoke()`
(`revokeShareLink`) for the apex's Data → Shares view, plus the anonymous recipient-side
`ShareViewer`. B3 added the missing **`Shares.create(path, expiresAtMillis, maxOpens)`** —
a thin wrapper over the `org.tenshu.core.file.share.shareLink` extension returning
`{ shareId, urlFragment }` (the URL fragment is the read capability, A11; the caller
assembles `…/s/{shareId}#k={urlFragment}`) or null — and **wired all three** through the
apex gateway (`shares.create`/`shares.list`/`shares.revoke`) + drive `proxy.ts`
(`createShareLink`/`listShares`/`revokeShare`, unit-tested). `native.d.ts` gained
`create` + `NativeShareLink`; `share.ts` a public `ShareLink`. Mode-B folder sharing +
membership stay deferred (§3 / §5).

**Re-homed — B2c `storageStatus`.** This is **not a Drive-UI task** — it's apex/control-plane
work (producing `StorageStatus` in the apex runtime; the Drive side just *consumes*
`storageStatus()` over the bridge, identical to `list`). Full scope + the three-bucket plan +
handover live in [`web-storage-status.md`](web-storage-status.md). **Decision (2026-06-25):
build all three buckets** (storage plugin → relocate the entitlement-backed provider → port
the mock-publisher mint path to the apex). When the apex produces the status, the Drive side is
a ~1-hour add: a `Storage.status()` op + `storage.status` gateway handler + `storageStatus()`
proxy + gating in upload/share + Settings.

**Reactive surface note (settled).** `fileDetails`/`fileActivity`/`shares`/`storageStatus`
are `Flow`s on native. **Decision: snapshot + TanStack Query `refetch`** — the Kotlin op
takes `.first()` and returns a `Promise`, React owns refresh. Simpler, matches the
postMessage request/response transport; a streamed `observe` is a later upgrade only where
live update genuinely matters (the activity log can poll). B2a `details` follows this; B2b/B2c
will too.

**Deferred boundaries** (Mode-B share + membership) are inventoried when those slices start.

---

## 5. Open questions

1. **Download UX in the browser.** ✅ **v1 resolved (§6 Open/preview, 2026-06-26).** `getData` returns
   `Int8Array`; the Details drawer's Open action assembles a `Blob` + object-URL — previewable
   kinds (image/PDF/text) render in the shared `FilePreview` lightbox, everything else
   `<a download>`. Large-file streaming-to-disk (File System Access API) is still open, but
   not blocking v1.
2. **Sharing handshake form factor on web.** ✅ **Resolved 2026-06-25 —
   [`drive-web-mode-b-sharing.md`](drive-web-mode-b-sharing.md).** v1 = deep-link invite
   (copy / `navigator.share` / QR-display, `K_link` in the fragment) → `UNVERIFIED` (TOFU)
   contact → explicit folder grant that shows the fingerprint; no camera/QR/SAS needed
   (a grant doesn't require a `VERIFIED` contact). Camera-QR + the 6-digit SAS is an
   additive "verify your contact" v2. Mode-B + membership stay Deferred (D) only as
   *implementation* (greenfield SDK surface), not a form-factor unknown.
3. **Reactive transport.** Per §4 — settle observe-vs-poll per reactive op in B2.

---

## 6. Phasing

Worktree-per-phase, each independently shippable. Phase A (shell) and the Phase B SDK
boundaries touch different modules and can proceed in parallel; the React UI (Phase C)
consumes B as it lands.

- **A — Subdomain shell + IPC** (roadmap 8.1–8.3). **✅ Landed (2026-06-24).** `web/drive/`
  workspace (Vite + TS + Tailwind, consuming `@tenshu/sdk`); the postMessage bridge as
  `@tenshu/sdk/bridge` (typed envelope + `mountApexProxy` client + `serveProxyGateway`,
  origin allowlist = §5.2 admission); apex mounts the gateway in `main.tsx`
  (`bridge.ping` + `fs.list` handlers); Drive's served `tenshu.*` `manifest.webmanifest`
  + an apex-side wire-level **validator** (`tenshuManifest.ts`). Hello-world page proves
  the origin split end-to-end (bridge ping → `fs.list("/")` round-trip). 8 bridge + 9
  manifest tests; drive + apex builds green. **Not yet:** the §6 install/grant flow (Rei
  signature + bundle `versionHash` verify) — that's 8.3's remainder.
- **B — SDK boundaries** (§4). B1 core file ops (paged list, recent, rename, move,
  delete) → B2 details/activity/storage (settle reactive transport) → B3 Mode-A shares.
  Small Kotlin+TS PRs.
- **C — React UI**, against the now-complete SDK. **C1 ✅ landed (2026-06-24)** — the
  Files browser (`FileBrowser` + `Breadcrumbs`): folder navigation, breadcrumb trail, a
  folders-first/alpha listing with size + date, and Upload into the current folder; the
  `fs.uploadFile` bridge boundary was wired as part of it. (Multi-select deferred to C3 with
  the move/delete actions that need it; paging deferred to when a folder view needs it.)
  **C2 ✅ landed (2026-06-24)** — tap a file to open a right-side `FileDetails` drawer with
  its **Details** (size / created / modified / path, over `details(path)`) and **Activity**
  (modification history, newest-first, over `activity(path)`; device name with a deviceId
  fallback). Pure UI over the B2a/B2b boundaries — no bridge/Kotlin change. **C3a ✅ landed
  (2026-06-24)** — entry management: a **New folder** toolbar action, plus a per-row
  actions menu (`RowMenu`) with **Rename** + **Delete**, over a reusable `Dialog` /
  `PromptDialog` / `ConfirmDialog` primitive; mutations invalidate the current folder's
  listing (and close the details drawer if its entry was mutated). Wired the `fs.createFolder`
  bridge boundary (the Kotlin op existed since Phase A). **C3b ✅ landed (2026-06-24)** —
  **Move** + **multi-select**: a `MovePicker` destination chooser (navigate folders, "Move
  here"; hides source folders + disables no-op moves via `parentDir`), a **Move** entry in
  the row menu, and per-row checkboxes driving a selection bar with bulk **Move** / **Delete**.
  Pure UI over the B1 `move`/`deleteEntry` boundaries — no bridge/Kotlin change. **C4 ✅
  landed (2026-06-24)** — Mode-A link sharing: a **Share** row action (files only) opens a
  `ShareDialog` that mints a link (`createShareLink`) and shows the copy-able URL
  (`shareUrl` = `…/s/{shareId}#k={fragment}`, the fragment never on the wire); a new
  **Shared** tab (`SharesView`, app-level Files/Shared nav) lists every outbound link with
  revoke (`listShares`/`revokeShare`). Pure UI over the B3 boundaries — no bridge/Kotlin
  change. **C5 ✅ landed (2026-06-24) — Phase C complete.** A **Home** tab (`HomeView`,
  now the default) listing recent files (tap → the C2 details drawer) + a minimal
  **Settings** tab (`SettingsView`: E2E blurb, the connected proxy origin, a hand-off link
  to the Tenshu apex for account/devices/recovery — most native settings are N-A on web or
  apex-side). App nav is now **Home / Files / Shared / Settings**. C5 also lands the last
  B1-remainder boundary — `FileSystem.recentFiles(limit)` (a genuinely new Kotlin op: a
  full-tree `traverse().sortByDescending(LastModifiedAt).take(limit)` mirroring native) +
  its `fs.recentFiles` bridge wiring.
- **Post-C — personal-file Open/preview ✅ landed (2026-06-26).** Closes §5 Q1 + the §4
  `getData` row. The Details drawer gains an **Open** (previewable) / **Download** action that
  lazily fetches bytes via a newly-bridged `fs.getData` (the Kotlin `FileSystem.getData` +
  `native.d.ts` already existed — only the apex handler + `proxy.ts` wrapper were missing).
  Previewable kinds (image / PDF / text) render inline in a shared `FilePreview` lightbox,
  everything else downloads. `FilePreview` + `buildPreview` + `previewKind` were extracted
  from the Mode-B `SharedFolderBrowser` (drive-web-mode-b-sharing.md §16) so personal and
  shared files share one preview surface — no duplication. Pure web-side bar the one-line
  `fs.getData` bridge boundary. **Still deferred:** large-file streaming-to-disk (File System
  Access API).
- **Post-C — image row thumbnails ✅ landed (2026-06-26).** Closes the §4 `getPreview` row.
  Bridges `fs.getPreview` (Kotlin `FileSystem.getPreview` + `native.d.ts` already existed) and
  adds a `FileThumbnail` row icon used by both `FileBrowser` and `HomeView`: for **image**
  files (`previewKind`) it lazily fetches a 40px WEBP thumbnail and shows it, falling back to
  the file glyph while loading / on failure / for non-images. **Lazy by visibility** — a
  `useInView` (`IntersectionObserver`, degrading to eager where unavailable) gates the fetch
  so a long folder doesn't fire a request per row up front; cached per path, `staleTime:
  Infinity`. The thumbnail `<img>` is decorative (`alt=""`). **Still deferred:** virtualization
  / `listPaged` (no large-folder user yet).
- **D (deferred)** — folder upload; Mode-B share-a-folder + shared-with-me; membership
  grant/accept/remove (each its own design increment, gated on the §5 handshake decision).

---

## 7. Implementation Notes for Claude Code

- **Per-worktree JS bundle.** The SDK bundle is gitignored + worktree-local — run
  `:tenshu-sdk-js:jsBrowserDistribution` before `pnpm -F drive dev` (mirror the apex flow,
  [`feedback_web_dev_per_worktree_bundle`]).
- **Typecheck gate** is `pnpm -F drive build` (tsc -b), not bare `tsc --noEmit`
  ([`feedback_apex_typecheck_gate`]).
- **Boundary authoring pattern.** Each new op: Kotlin `@JsExport` fun in a `FileSystem`/
  sibling object returning `GlobalScope.promise { ... }` (or an observe handle), KDoc naming
  the mirrored `FilesRepository` method; then the hand-written TS adapter in `web/sdk/src/`
  is the source of truth for the JS surface shape (see the `files.ts` header comment).
- **The schema walker flattens entries** via `Composite.toJs()` — keys are the last
  path-segment of the field id (`tenshu/file/path` → `path`); `'files' in entry` is the
  folder/file discriminator. Don't re-derive a shape; reuse `web/sdk/src/files.ts`'s `File`/
  `Folder` interfaces.
- **⚠ The `web/drive` app must import only *types* from `@tenshu/sdk` — never a value.**
  Any value export from `@tenshu/sdk/files` (incl. the tiny `isFolder`) transitively imports
  `_bundle.ts` → the full Kotlin/JS runtime, ballooning the thin subdomain bundle from
  ~230 KB to ~1.7 MB and defeating the apex/subdomain split (the runtime lives in the *apex*,
  reached over postMessage — the subdomain has none of it). C1 hit this and moved a local
  `isFolder` into `drive/src/lib/paths.ts`. The one allowed value import is
  `@tenshu/sdk/bridge` (`mountApexProxy`) — `bridge.ts` is framework-/runtime-free by design.
  Guard: if `pnpm -F drive build` warns about a >1 MB chunk, a value import crept in.
- **No threat-model change expected** for Phases A–C: the relay still sees ciphertext, the
  apex still holds master keys, the subdomain runs restricted (architecture §"Restricted
  consumers"). The Mode-B/membership deferred work *will* need a threat-model pass when its
  handshake form factor lands (House Rule #2).
