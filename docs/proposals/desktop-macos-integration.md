# Desktop macOS Integration — Control Tray + Drive File Provider

**Status:** Sketch (2026-06-02). macOS-first implementation plan derived from
[`tenshu-rei.md`](tenshu-rei.md) §3.6 (Desktop deployment shape) and
[`docs/protocol/desktop-ipc.md`](../protocol/desktop-ipc.md). Windows + Linux are
**outlook only** (§7) — deliberately deferred until the macOS surface is validated end
to end. Phased so each phase is independently shippable and verifiable, and so the
phases can be handed to Claude Code as discrete units of work.

**Scope.** Two macOS-native surfaces, both thin IPC clients of the existing Control
daemon:

1. **Control tray** — a persistent `NSStatusItem` menu-bar agent with a small status
   popup (sync state, recent notifications, Open Control, App Launcher). §3.6 already
   specs the behaviour; this doc is the macOS implementation.
2. **Drive File Provider** — an `NSFileProviderReplicatedExtension` that puts Tenshu
   Drive in Finder's *Locations* sidebar (under `~/Library/CloudStorage/`), with
   on-demand (dataless) file materialisation backed by the file-service through Control.

Both reuse the daemon and the `desktop-ipc.md` transport rather than introducing a
second sync engine. The relay still sees ciphertext only; plaintext exists only
daemon-side and (transiently) in the File Provider process, both inside the local
trust boundary.

**Touches:** `:core-common` (macOS socket-path resolution + a native-facing bridge
surface), `:control-desktop` (daemon/window lifecycle split, packaging, tray agent
embedding), `:drive-desktop` (File Provider extension embedding, domain registration),
new native sources under each desktop module, `docs/protocol/desktop-ipc.md`
(amendment), and `docs/sync/threat-model.md` (new local components + transport path).

This is a sketch, not a final spec. §8 lists the open questions that should be nailed
down as each phase is picked up.

---

## 1. Why this is cheaper than it looks

The expensive half of "deep OS integration" — a resident, login-started,
keychain-backed process that can yield decrypted bytes and push live events — already
exists. `control-desktop`'s `Main.kt` starts a `UnixSocketServer` over `ProxyGateway`
(framed `BinarySerializer` packets at `~/.tenshu/ipc.sock`, bidirectional, with the
`LOCAL_PEERPID` + code-signing handshake from `desktop-ipc.md` §3). `drive-desktop` is
already a pure IPC client of that daemon (it runs no proxy of its own).

So both new surfaces are *additional clients* of the same daemon:

- The **tray** is a native client that subscribes to sync state + notifications and can
  ask the daemon to surface the Control window or launch an app.
- The **File Provider** is a headless native client that issues file-service operations
  (list, read, and later write) on the daemon's behalf when Finder asks.

What is genuinely *new* on macOS is: (a) getting the daemon reachable from a sandboxed
extension, (b) the native code itself, and (c) packaging/signing/notarization. Phases
0–4 below are ordered to retire the riskiest of those first.

---

## 2. Architecture

### 2.1 Reachability: the App Group socket

The current socket lives at `~/.tenshu/ipc.sock` (700 perms). A macOS File Provider
extension is **sandboxed** and cannot reach an arbitrary path outside its container, so
`~/.tenshu/ipc.sock` is unreachable from the extension. The fix is to host the socket
in an **App Group container** shared by the daemon and its native clients:

```
~/Library/Group Containers/<TEAMID>.app.tenshu.shared/ipc.sock
```

Both the Control daemon and the native agents are signed with
`com.apple.security.application-groups` for `<TEAMID>.app.tenshu.shared`, so all of them
can `connect()`/`bind()` there even under the sandbox.

**The group container is for sandbox reachability, not a security boundary.** A
same-user process can still reach the directory; the gate remains the `desktop-ipc.md`
handshake — `LOCAL_PEERPID` → resolve binary → verify code-signing identity against a
known Tenshu publication. The File Provider extension's signed identity (it ships inside
Drive.app) is what the daemon checks, and the extension registers as an
`AppRegistration` per [`control-app-registration.md`](control-app-registration.md) on
first verified handshake.

### 2.2 Don't make Swift speak `BinarySerializer`: the native bridge surface

The wire today is `BinarySerializer` framing. Porting that codec to Swift — especially
the full file-service op surface the File Provider needs — is a large, fragile job. The
two surfaces also need very little: the tray needs a sync-state enum, a handful of
notification rows, and two commands; the File Provider needs list / read-metadata /
fetch-contents / (later) create / modify / delete.

So the daemon exposes a **small, stable, native-facing bridge** over the same
group-container UDS — a JSON request/response + event protocol whose handlers translate
into real `ProxyGateway` calls JVM-side. Native clients speak JSON; the daemon owns the
mapping to the binary proxy protocol and the permission checks. This keeps the Swift
code minimal and decouples the native surfaces from the internal binary format.

> **Note on the language choice (still open per §3.6).** If the macOS agents were
> written in **Kotlin/Native** instead of Swift, they could reuse the KMP
> `BinarySerializer` + client code directly and skip the JSON bridge. That argument is
> strong for the **tray** (small, pure Kotlin) and weak for the **File Provider** (an
> `.appex` in Kotlin/Native is very off-path — extension packaging/lifecycle is
> Xcode/Swift territory). This plan assumes **Swift + JSON bridge** as the lower-risk
> default for getting a working macOS demo, and flags the tray as the place to revisit
> Kotlin/Native later. The JSON bridge is useful regardless: it's the seam Windows's
> native tray will reuse in §7.

### 2.3 Process topology

```
  ┌─────────────────────────────┐        ┌──────────────────────────────┐
  │ Control.app (JVM daemon)     │        │ fileproviderd (macOS)         │
  │  • ProxyGateway              │        │   spawns on demand:           │
  │  • UnixSocketServer (binary) │        │  ┌──────────────────────────┐ │
  │  • NativeBridgeServer (JSON) │◀──UDS──┼──┤ Drive FileProvider .appex │ │
  │  • background sync loops     │   (group container socket)          │ │
  └──────────────┬──────────────┘        │  │  (Swift, sandboxed)      │ │
                 │  UDS (group socket)    │  └──────────────────────────┘ │
                 ▼                        └──────────────────────────────┘
  ┌─────────────────────────────┐
  │ Control Tray agent (Swift)  │   NSStatusItem + NSPopover
  │  launched by LaunchAgent     │
  └─────────────────────────────┘
```

The JVM Control process is the daemon. The tray agent and the File Provider extension
are independent native processes; each connects to the daemon's group-container socket.
The daemon must be running for either to do useful work — it is autostarted at login via
a `LaunchAgent` (§3.6). When it isn't, both surfaces degrade gracefully (tray shows
"Control not running" + offer to start; File Provider reports items unavailable).

---

## 3. Phase 0 — macOS foundation (de-risk first)

> **Status (2026-06-02): mostly shipped.** Tasks 1, 2, 4 landed + verified;
> task 3 scaffolded with verification deferred (no Apple signing identity in the
> build env). `SocketPathResolver` (group-container path + `~/.tenshu` dev
> fallback) wired into `UnixSocketServer`/`UnixSocketTaskTransport`;
> `NativeBridgeServer` (minimal JSON envelope, `ping` + `getSyncState`) running
> alongside the binary server in the daemon; Swift `HelloClient` builds and
> **round-trips against the real Kotlin server over a live UDS** (`pong` +
> `ACTIVE` sync state observed). Bridge-shape decision: **minimal hand-rolled
> envelope** for now, with a `tech-debt.md` entry ("Standard Tenshu JSON
> Representation") tracking a schema-derived canonical JSON for when a third JSON
> surface appears. **Deferred:** the signed-`Control.app`-in-sandbox round-trip
> (task 3 verification) — the `:control-desktop:signControlAppForAppGroup` Gradle
> task + `macos/entitlements/control.entitlements` are in place but need a Team ID
> + codesigning identity to run.

**Goal:** prove the unknown — that a sandboxed native process can reach the daemon and
round-trip a request — before building any UI or extension logic.

**Tasks:**

1. **Group-container socket path.** In `:core-common` desktopMain, add a
   `SocketPathResolver` so the macOS socket path resolves to the App Group container
   (`~/Library/Group Containers/<TEAMID>.app.tenshu.shared/ipc.sock`) with a fallback to
   `~/.tenshu/ipc.sock` for unsandboxed dev runs. Wire it into `UnixSocketServer` and
   `UnixSocketClientConnection`.
2. **Native bridge server.** Add a `NativeBridgeServer` (JSON-over-UDS) alongside
   `UnixSocketServer` in the daemon, with a v0 surface of exactly two methods: `ping`
   and `getSyncState`. Reuse the framing helper; JSON payload instead of
   `BinarySerializer`. Keep the binary `UnixSocketServer` untouched.
3. **Packaging spike.** Produce a **signed** `Control.app` via the Compose
   `nativeDistributions` pipeline + a custom re-sign/entitlements step (Compose's
   built-in signing doesn't cover App Group entitlements — expect a Gradle task that
   injects entitlements and runs `codesign`). Confirm the daemon can `bind()` in the
   group container when sandboxed. Signing setup (dev profile + App Group, and the
   no-account tray quickstart) is documented in
   [`../how-to/macos-signing.md`](../how-to/macos-signing.md).
4. **Native hello client.** A ~50-line Swift CLI that connects to the group socket,
   sends `ping`, prints the reply. This is the de-risk artifact.

**Deliverable / verification:** signed `Control.app` running sandboxed; the Swift CLI
prints a successful `ping` + a real `getSyncState`. If this works, the rest is
construction. **Risk pin:** App Group entitlement injection into a jpackage/Compose
bundle is the single most likely place to get stuck — timebox it and, if it resists,
fall back to a thin Xcode wrapper `.app` that embeds the JVM runtime.

---

## 4. Phase 1 — Control tray (NSStatusItem agent)

**Goal:** a persistent menu-bar presence that survives the main window closing, per
§3.6.

> **Sliced for delivery (each independently shippable):**
> - **1A — Daemon/window lifecycle split** (task 1): JVM runs headless, the Compose
>   window opens/closes on demand via a `WindowController`; close → hide; bridge gains
>   `openControlWindow` + `quitApp`. Pure Kotlin, desktop-verifiable. ✅ *(merged)*
> - **1B — Bridge surface v1 data methods** (task 4): `listApps` (from `AppRegistry`),
>   `recentNotifications(limit)` (from `NotificationMapper`), `launchApp`
>   (`not_supported` at 1B; **shipped in Phase 4 packaging** — `open -b` the app's
>   bundle, see §6a). **Deviation:** `subscribeSyncState`
>   *push* is deferred — the tray polls the existing `getSyncState` for v1 (a
>   menu-bar popover is short-lived; push adds a per-connection subscription
>   subsystem to the bridge core without a current consumer). Revisit if the
>   menu-bar *icon* needs continuous live state. ✅ *(merged)*
> - **1C — Swift tray agent** (task 3): `NSStatusItem` + `NSPopover` (SwiftUI),
>   `BridgeClient.swift` over the JSON bridge; `.accessory` (no Dock icon).
>   Builds with `swiftc`; bridge client proven over a live UDS. GUI = manual.
>   *(in progress)*
> - **1D — LaunchAgent + close-to-tray mechanism** (task 2 + the preference half of
>   task 1): `LaunchAgentManager` (plist generation + install/uninstall via
>   `launchctl`) + `DesktopControlSettings` (`closeToTray`, persisted to
>   `~/.tenshu/control.settings.properties`) wired into `Main.kt`'s close handler.
>   Unit-tested. *(in progress)*
> - **1E — desktop Settings UI** (remaining UI for tasks 1+2): a desktop-only
>   Settings section with toggles for *Close to menu bar* and *Start at login*,
>   wired to the 1D managers. Deferred from 1D because the Settings screen is the
>   shared (Android+desktop) nav surface — a desktop-only sub-screen + nav entry is
>   GUI-only work that can't be auto-verified. *(not started)*

**Tasks:**

1. **Daemon/window lifecycle split.** Refactor `control-desktop/Main.kt` so the JVM
   process can run headless (server + background loops alive) with the Compose `Window`
   opened/closed on demand rather than `onCloseRequest = ::exitApplication`. Close →
   hide window, daemon keeps running; explicit Quit → `shutdownAndJoin()`. Make
   close-to-tray vs quit configurable (preference-service).
2. **LaunchAgent.** Ship a `LaunchAgent` plist that autostarts the daemon at login;
   install/remove it from Control settings.
3. **Tray agent (Swift).** `NSStatusItem` with a template image (auto dark-mode) and an
   `NSPopover` rendering: current sync state, ≤4 most recent notifications, an *Open
   Control* button, an *App Launcher* listing installed Tenshu apps.
4. **Bridge surface v1.** Extend `NativeBridgeServer`: `subscribeSyncState` (push),
   `recentNotifications(limit)` (from `:notification-service`), `openControlWindow`,
   `listApps` + `launchApp` (from `AppRegistration`). Notification routing: wire only
   `FileCategory.Image` → open in Drive first (§3.6), others later.

**Deliverable / verification:** click the menu-bar icon → popup shows live sync state
and recent notifications; *Open Control* raises the window; closing the window leaves
the icon and daemon alive. Screenshot + a `:core-common` bridge round-trip test.

---

## 5. Phase 2 — Drive File Provider (read-only)

**Goal:** Tenshu Drive appears in Finder's *Locations*; folders browse; files download
on open. No writes yet — prove the read path and the Finder surface first.

> **Sliced for delivery:**
> - **2-bridge — daemon read methods (the backing surface for tasks 3–5):** the
>   `NativeBridgeServer` `listFolder` / `itemMetadata` / `readContents` methods,
>   backed by the in-process file-service through the chain DSL. Pure-Kotlin,
>   desktop-verifiable: pure JSON-shape tests + a real-UDS round-trip over a seeded
>   in-process `TenshuClient` (list a folder, read a file's bytes through base64,
>   `not_found` on a missing path). `registerFileMethods(koin)` lives in
>   `control-shared` (the bridge runs in the Control daemon, alongside
>   `registerDataMethods`) and is wired into `control-desktop/Main.kt`.
>   `PROTOCOL_VERSION` stays `1` — adding methods is additive (Phase 1 precedent).
>   ⚠ `readContents` base64-encodes the whole file into the JSON reply; large-file
>   streaming / backpressure is deferred to Phase 4 (§6a). ✅ *(this slice)*
> - **2-appex — Swift File Provider extension (tasks 1, 2, native half of 3–5):**
>   the `NSFileProviderReplicatedExtension` + enumerator + item mapping + content
>   fetch reusing the `BridgeClient.swift` pattern, a `DomainRegistrar` CLI for the
>   `NSFileProviderDomain`, the two entitlements (sandboxed `.appex` + App-Group
>   host), and the `:drive-desktop:signDriveAppForFileProvider` embed/sign task
>   (mirrors `signControlAppForAppGroup`, self-skips without identity). Ships as
>   scaffold under `drive-desktop/macos/FileProviderExtension/` (like Phase 0
>   `HelloClient` / Phase 1C `TrayAgent`): all Swift sources `swiftc -typecheck`
>   clean against the macOS SDK, but the `.appex` is an Xcode target and can only be
>   exercised from an installed, signed `Drive.app` — so build/embed/sign + the
>   Finder round-trip are **on-device manual** (read path itself is covered by the
>   2-bridge tests). ✅ *(scaffold; on-device pending)*

> **As built (2026-10-04) — the extension is built, signed, embedded and registered.**
> `:drive-desktop:packageDesktopApp` now produces a bundle with the `.appex` in it
> (`embedFileProviderExtension`: XcodeGen + unsigned `xcodebuild`, signed by the shared
> `signDesktopApp`), and Drive registers the domain at launch. The old
> `signDriveAppForFileProvider` task is gone. Three things differ from the sketch above:
> - **Reachability is pinned at signing time, not by environment.** `signDesktopApp` writes
>   `-Dtenshu.ipc.teamId` into every desktop launcher cfg and signs with the App Group
>   entitlement, so a packaged app always uses the group container; the native clients read the
>   Team ID off their own code signature. ⚠ `~/.tenshu` is never adopted: the one-time move
>   (`LegacyDesktopRoot`) was removed before the production reset. ⚠ All desktop apps must be repackaged together.
> - **The registrar only works when Drive spawns it** — the File Provider daemon attributes the
>   call to the responsible process; from a shell it refuses with `providerNotFound`.
> - **The first domain came up user-disabled** and had to be enabled once in System Settings.
>
> The traps are spelled out in `drive-desktop/macos/FileProviderExtension/README.md`.
> Verified on a device: the sandboxed extension reaches the daemon, folders list, and a file
> downloads on open. **Not verified:** any write from Finder (§6).

**Tasks:**

1. **Extension target.** A Swift `NSFileProviderReplicatedExtension` (`.appex`) embedded
   in `Drive.app/Contents/PlugIns`, extension point `com.apple.fileprovider-nonui`,
   signed with the App Group entitlement.
2. **Domain registration.** Register an `NSFileProviderDomain` (from Drive's main app or
   the extension) → creates the `~/Library/CloudStorage/` mount + Finder sidebar entry.
3. **Enumeration.** Implement the container + working-set `NSFileProviderEnumerator`
   backed by bridge `listFolder(id)` → file-service listing (the dedicated list ops /
   chain DSL behind the proxy). Map Tenshu file ids ↔ `NSFileProviderItemIdentifier`,
   and content version / HLC ↔ item version + enumeration anchor for deltas.
4. **Metadata.** Map `File` → `NSFileProviderItem` (name, size, timestamps,
   content-type, folder vs file).
5. **Content fetch.** Implement `fetchContents(for:version:)` → bridge
   `readContents(id)` → file-service read over the content-blob channel
   ([ADR 0017](../adr/0017-file-content-two-plane-channel.md)) → decrypted bytes;
   placeholders materialise on open.

**Deliverable / verification:** Tenshu Drive in Finder sidebar; navigating folders
matches the Drive UI; double-clicking a dataless file downloads + opens it; eviction
returns it to dataless. Manual on-device check + an enumeration unit test against a
fixture realm.

---

## 5a. Item identity and change detection (as built, 2026-10-04)

§5 task 3 assumed there was a Tenshu file id to map to `NSFileProviderItemIdentifier`. There is
not: in the personal realm a file's identity **is its path**
([`drive-shared-realm-file-model.md`](drive-shared-realm-file-model.md)), a move re-creates the
row under the new path and tombstones the old one, and the stable `File.fileId` that once existed
was removed ([`file-content-addressing.md`](file-content-addressing.md)). The scaffold used the
path as the identifier, so every rename looked like delete + create to Finder and orphaned
whatever it had downloaded.

**D1 — identifiers are minted by the daemon and are device-local.** `FileProviderIds`
(`control-shared`) keeps an `id ⇄ path` map, journalled to `fileprovider-ids.tsv` in the data
root, and every bridge method takes and returns ids; the realm path no longer crosses the bridge.
A rename or move through the bridge re-points the id, including everything under a moved folder.
Rejected: re-adding a synced `File.fileId` (a schema change the realm model decided against, for
one platform's shell integration) and using `File.ReferenceRoot` (content-plane infrastructure,
files only, scheduled to be re-minted).

- **A move through the bridge is journalled as an intent first** (source, destination, the
  file's content id) and settled once the source is gone and the destination holds that file.
  That covers Control dying between the move and the re-point, and a move the file-service
  *deferred* and replays later with no call back into the map.
- ⚠⚠ **The settle is a heuristic, and two cases defeat it** — both lose only the identity of
  that one move (delete + create), never mis-associate it. (1) The file is overwritten between
  a deferral and its replay: its content id changes and the destination no longer matches.
  (2) A refresh lands mid-replay, after the source row is removed and before the destination is
  saved: the intent is closed as "not this move". Folders are not tracked at all (nothing
  identifies one). The real fix is to ask the file-service — `PendingMove` rows carry
  `attempted` / `applied` — but that state is proxy-private (`PendingMoveStore`), so it needs a
  file-service operation exposing it; not built.
- ⚠ **A move made on another device is still delete + create here.** Nothing at this layer ties
  the new path to the old one. `FileModification`'s `Moved` / `Renamed` rows carry both and could
  drive a re-point; not built.
- ⚠ The journal holds every path in plaintext. So does `objects.db` beside it, so this is not a
  new exposure — but it is one more file that must move if the store is ever encrypted at rest.

**D2 — changes are computed, not observed.** The framework asks "what changed since anchor X".
There is no files-scoped change log to answer from (`FileModification` has no deleted row; the
sync cursors do not move on local writes), so `FileProviderChangeFeed` diffs a full realm listing
against the previous one and records the delta under a new anchor, keeping the last 64 deltas so
several enumerators can read the one feed at their own pace. An anchor it cannot answer — older
than the history, or from before a Control restart — is reported expired and the system re-lists.

- ⚠⚠ **Every look walks the whole realm.** Fine at hundreds of files; not measured beyond.
- ⚠⚠ **Nothing pushes.** The extension signals the working set when its process starts and every
  30 s while it lives, and the system kills the process within a minute of the last use. So a
  change appears when the user opens the folder, and within ~30 s while a Finder window is open
  — never while nothing is looking, which also means nothing is pre-downloaded. The real fix is
  an always-on signaller attributed to Drive; Control cannot do it (the daemon attributes
  `signalEnumerator` to the responsible process, as it does domain registration).

**D3 — the domain identifier carries the identifier scheme** (`app.tenshu.drive.personal.2`).
The system keeps its own database of a domain's items keyed by identifier; given a new scheme
under the same domain it kept every old item beside its new twin ("Backup", "Backup 2").
`tenshu-domain add` retires domains of earlier schemes. Bump `identifierScheme` whenever an
identifier changes meaning.

**D4 — thumbnails come from the stored thumbnail classes, not from the file** (as built,
2026-10-04). The extension implements `NSFileProviderThumbnailing`; `thumbnail(id, width, height)`
reads `File.Thumbnail` (100 px) / `File.ThumbnailLarge` (256 px) — the smallest that covers the
request, else whichever exists — so a dataless file has a picture and asking for one downloads
nothing. A class that is not on this Mac yet is pulled from the content plane once
(`Syncable(OnDemand)`; the same read-pull-read as `TenshuFilesRepository.getPreview`), and only
for names something mints a still for: images, videos (poster frame) and PDFs (first page).
Like `readContents` it only resolves the identifier, runs outside the lock, and re-checks the
mapping after the read.

- ⚠⚠ **Never `File.Preview`.** On a Mac that holds the bytes `GetPreview` renders from the
  original; on one that does not, a consumer of it is one refactor from fetching content to draw
  an icon. The stored classes are the only read that cannot touch the file.
- ⚠ **256 px is the ceiling, on purpose.** `XLARGE` (1024 px) would be sharper in gallery and
  Quick Look sizes, but naming a hero class *declares* it wanted for this device
  (`ThumbnailPreferences.WantedClasses`) and starts a realm-wide backfill. Whether a Finder
  mount should make that declaration is a decision, not a default.
- ⚠ **The format is whatever was minted.** Uploads mint JPEG at every class (since 2026-08-31);
  older `LIST` classes are PNG (desktop, iOS) or WebP (Android). Nothing establishes that the
  system accepts WebP as thumbnail data, so the extension passes JPEG and PNG through and
  re-encodes anything else as PNG with ImageIO.
- ⚠ **A file with no stored class has no thumbnail**, even downloaded: files uploaded before
  the classes existed, and videos uploaded from a desktop (the JVM has no frame decoder). The
  system caches an answer until the content version moves; whether it caches "none" the same
  way — so that a class minted later never shows — is **not established**.
- Thumbnails are fetched one at a time per request, and a cancel is honoured between items,
  not mid-call.

**Verified on a device:** create, rename (file and folder), read-after-rename and delete from
the mount; the working-set re-list on opening a Finder window. **Not verified:** a change made
outside Finder appearing in an open window (covered daemon-side by
`FileBridgeRoundTripTest.changesSinceReportsOutsideEditsAsDeltas`); **anything about
thumbnails in Finder** — D4 is covered daemon-side only
(`FileBridgeRoundTripTest.thumbnailServesTheStoredClassAndSaysWhenThereIsNone`), with the files'
bytes local, so neither the byte-less pull nor what Finder does with the data has been seen.

---

## 5b. File contents cross by hand-off, not through the bridge (as built, 2026-10-05)

Contents used to ride in the bridge's JSON reply as base64: read whole, encoded a third larger,
framed, sent, decoded — several copies of the file in memory on each side, and an upload above
~190 MB refused outright by the 256 MiB frame limit. A phone's video is that file.

**D5 — only a name crosses the bridge.** The side that has the bytes writes them to a file in
`fileprovider-staging/` in the App Group container (the one place the sandboxed extension and
the unsandboxed daemon can both reach) and sends its name; the other side collects it.
`fetchContents(id, staged)` for downloads, `createFileFrom` / `modifyContentsFrom` for uploads,
`cancelTransfer(staged)` to stop a download. The base64 methods remain for callers that still
use them.

- **Downloads are bounded-memory**: the daemon copies through the sync service's seekable
  content stream ([`drive-streaming-preview.md`](drive-streaming-preview.md) §3.5) 256 KiB at a
  time. A file with no stream yet (not synced; bytes only local) is read whole — the one case
  where the bytes are already here.
- ⚠⚠ **Uploads are not.** The file-service's upload takes one `ByteArray` on every platform, so
  the hand-off removes the base64 copy, the JSON copy and the frame limit — not the engine's
  own. A staged file is read into memory once, and one a `ByteArray` cannot hold (2 GiB) is
  refused as `too_large`. Lifting that is a file-service change, not a bridge one.
- **Progress and cancel**: the extension reads progress off the staging file's size as the
  daemon writes it; Finder's cancel is sent on its own connection and takes effect at the next
  chunk.
- ⚠⚠ **Plaintext on disk** between the write and the hand-off (threat model T-C-16). The
  directory is `0700`; a name is a token checked against a pattern, never a path; a consumed or
  failed transfer's file is removed at once, and anything a crash left is swept after 24 h.

Also fixed while testing this: files now carry `.allowsEvicting`, without which a downloaded
file could never be turned back into an online-only one; and the item's metadata version
includes a presentation revision, so a change in how the extension presents an unchanged item
actually reaches the system.

**Verified on a device:** download of online-only files with matching hashes; a 30 MB upload,
eviction, and re-download with a matching hash; the staging directory empty afterwards.
**Not verified:** anything at video size (hundreds of MB and up), progress as Finder displays
it, and cancel.

---

## 6. Phase 3 — Drive File Provider (write path + permissions)

**Goal:** Finder edits flow back through Control with the §2.2 permission model honoured.

> **Sliced for delivery (mirrors Phase 2):**
> - **3-bridge — daemon write methods (task 1):** `createFolder` / `createFile` /
>   `modifyContents` / `moveItem` / `deleteItem` on the daemon's `NativeBridgeServer`,
>   backed by file-service `createFolder` / `uploadFile` / `replaceFile` / `moveFile` /
>   `deleteFile`. Create/modify/move return the post-write `{ item }` (re-read
>   server-side) in one round-trip; a name collision surfaces the `already_exists`
>   code. `registerFileWriteMethods(koin)` lives beside `registerFileMethods` in
>   `control-shared`'s `ControlFileBridge.kt`, wired into `control-desktop/Main.kt`.
>   Verified end-to-end over a real UDS (`FileWriteBridgeRoundTripTest`: create folder
>   → create file → read back → modify → move/rename → delete → collision). ✅
> - **3-appex — Swift write path (task 1 native half):** `createItem` / `modifyItem`
>   / `deleteItem` in `TenshuFileProviderExtension.swift` calling the write bridge
>   methods, writable item capabilities, and the `already_exists` → `filenameCollision`
>   mapping. `swiftc -typecheck` clean; on-device manual (same `.appex` constraint as
>   2-appex). ✅ *(scaffold; on-device pending)*
> - **Outstanding (tasks 2–4):** the **permission posture** (task 2 — primary-handler
>   grant, `tenshu-rei.md` §2.2) is **deferred** — writes go straight to the file-service
>   today (ungated, like the Phase-2 reads), gated on the rei/AppGrant track which has
>   no module yet. **Change signalling** (task 3 — `signalEnumerator(.workingSet)` +
>   conflict versions) waits on the bridge's push channel (deferred since Phase 1B).
>   **Daemon-down/offline** (task 4): writes currently surface a retryable
>   `serverUnreachable`; a proper offline queue is later.

**Tasks:**

1. **Write ops.** Implement `createItem` / `modifyItem(contents)` / `deleteItem` /
   rename / move → bridge methods → file-service `uploadFile` / `replaceFile` /
   `deleteFile` / `moveFile` / `createFolder`.
2. **Permission posture.** The extension's writes are `Picker` / `requires_user_gesture`
   ops by default (`tenshu-rei.md` §2.2). Decide and wire the grant model: Drive as the
   **primary handler** for files (ambient on `Picker` ops for its data type, still
   gesture-bound), so routine Finder edits don't prompt per-op. Enforcement stays
   proxy-side.
3. **Change signalling + conflicts.** Call
   `NSFileProviderManager.signalEnumerator(.workingSet)` when the daemon reports remote
   changes; surface upload progress; map sync conflicts to File Provider conflict
   versions.
4. **Daemon-down + offline.** Define behaviour when the daemon is unreachable (reject
   with retryable error) and when offline (queue, reconcile on reconnect).

**Deliverable / verification:** create/edit/delete/rename in Finder round-trips to other
devices; conflicts surface sanely; permission checks visibly apply (a denied grant
blocks the write at the proxy). On-device round-trip + proxy permission test.

---

## 6a. Phase 4 — Hardening & polish

Notarization + Gatekeeper pass; Finder sync-status badges; large-file streaming +
backpressure; daemon-not-running UX on both surfaces; signature-cache invalidation on
app update (`desktop-ipc.md` §3 / `tenshu-rei.md` §5.F.49); dev-mode unsigned-build
bypass (the seven-tap reveal, §3.6).

> **Packaging — tray embedding + cold-launch lifecycle — ✅ landed (2026-06-04).** The
> Compose `nativeDistributions` path produces `TenshuControl.app` (`createDistributable`)
> with `CFBundleIdentifier = app.tenshu.control` (pinned via `nativeDistributions.macOS.bundleID`
> so LaunchServices resolves it). Two new self-skipping `macos`-group Gradle tasks
> (`control-desktop/build.gradle.kts`) build and embed the Swift tray:
> `buildTrayAgent` (`swiftc -O` of `macos/TrayAgent`) → `embedTrayAgent` (copies the binary
> to `TenshuControl.app/Contents/Helpers/tray-agent`). Both `onlyIf` the build host is macOS.
>
> **⚠ Build the umbrella, not `createDistributable` alone.** A plain `createDistributable`
> yields an app that launches but shows **no tray** (the cat) — `ensureTrayRunning()` in
> `Main.kt` no-ops when `Contents/Helpers/tray-agent` is absent, silently. Use
> **`./gradlew :control-desktop:packageControlApp`**, the umbrella task that always runs
> `embedTrayAgent` and prints the `cp -R … /Applications/` install line. The Touch ID
> keychain helper is **opt-in** there (`-Ptenshu.withKeychainHelper`) because it is
> signing-gated and hard-fails on an unsigned/free-team build (§masterkeystore) — so it
> must never be a default dependency of the umbrella. Run `embedKeychainHelper` separately
> once signing is set up (macos-signing.md §3b).
>
> **Tray and login-start orchestration — MAS slice landed (2026-11-03).** Unsandboxed
> development and Developer ID bundles retain the existing LaunchAgent behavior: the embedded
> tray remains an independent `app.tenshu.control.tray` agent and the daemon remains
> `app.tenshu.control`. A MAS bundle additionally carries
> `Contents/Library/LoginItems/TenshuControlLoginItem.app`, a sandboxed helper with its own
> App Group entitlement and Mac App Store profile. `DesktopStartAtLoginManager` selects
> the helper when `APP_SANDBOX_CONTAINER_ID` is present and otherwise falls back to
> `LaunchAgentManager`; the helper registers/unregisters
> `SMAppService.loginItem(identifier: "app.tenshu.control.loginitem")`. The login item
> opens the shared-bundle Control app at login and terminates. The remaining on-device
> verification is the System Settings approval flow, login startup, and tray cold-launch
> round trip with the installed MAS build.

> **`launchApp` — shipped (2026-06-04).** The tray's App Launcher (and any bridge
> client) can now launch a Tenshu app by `appId`: `launchApp` resolves the appId to a
> macOS bundle id and `open -b`s it via LaunchServices (`ControlDataBridge`, pure JVM
> ProcessBuilder — the daemon is unsandboxed, no native interop). **Convention:** internal
> first-party app IDs remain platform-neutral (`app.tenshu.drive`), while the Apple bundle
> ID is `app.tenshu.drive.ios` so iOS and macOS can share one App Store Connect record;
> `macOsBundleIdFor` owns that mapping. `nativeDistributions.macOS.bundleID =
> "app.tenshu.drive.ios"` is pinned on `:drive-desktop` so the produced `TenshuDrive.app`
> carries the shared Apple identity.
> `CFBundleIdentifier`. Returns `{launched:true}`, or `not_found` when no such bundle is
> installed. **On-device only:** the launch itself (needs an installed/registered
> `Drive.app`); the resolver + error paths are unit-tested with an injected launcher.

> **Threat-model amendment (house rule #2) — ✅ landed ahead of the rest of Phase 4.**
> `docs/sync/threat-model.md` gains **A20** (the native-bridge surface + group-container
> sockets; the tray agent + File Provider extension as local plaintext components) and
> **T-C-15 / T-C-16** (same-user process driving the bridge's plaintext file read/write;
> the File Provider's transient temp-file materialisation), with I1/I8 confirmations and a
> §10 note that the `.appex` is verified on-device only. `docs/protocol/desktop-ipc.md` §6.2
> now lists the shipped read/write methods and states the bridge inherits §3's handshake.

> **Peer-credential gate seam — ✅ wired (non-enforcing) — ⚠⚠ SUPERSEDED 2026-07-28.**
> **The deferral below is history, not the current posture.** The
> native verifier landed and the policy is now `strict = true`
> ([`desktop-ipc-client-identity.md`](desktop-ipc-client-identity.md) P3): peers are resolved
> from the **audit token** (not `LOCAL_PEERPID` — see that doc's D2 for why the pid route is
> wrong twice over) and rejected unless their running code satisfies a Team-ID-pinned
> requirement. Read the rest of this block for *why it waited*, not for what ships.
> Both desktop IPC servers
> (`NativeBridgeServer` + the binary `UnixSocketServer`) now run a `PeerCredentialPolicy`
> chokepoint on `accept()` (`core-common` `transport/PeerCredentialGate.kt`): a single,
> unit-tested admit-or-close point. The default `UnverifiedPeerCredentialVerifier` can't
> resolve the peer yet (the JVM exposes no `LOCAL_PEERPID` for an AF_UNIX peer on macOS;
> Java-17 toolchain), so the policy runs **non-strict** and admits all — T-C-15's residual
> is unchanged in practice, but the enforcement point now exists and is fail-closed-ready.
> **Remaining (the actual fix) — the native verifier, deliberately DEFERRED (2026-06-03)
> until signing infra exists.** The drop-in is: reflection-extract the socket fd
> (`sun.nio.ch.SocketChannelImpl` → `FileDescriptor.fd`, needing `--add-opens
> java.base/sun.nio.ch` + `java.base/java.io`) → `getsockopt(LOCAL_PEERPID)` via a native
> mechanism → `ProcessHandle.of(pid).info().command()` for the binary path → `codesign -R
> "…Team ID…"` for the signing check → flip `PeerCredentialPolicy(strict = true)` and wire
> developer-mode (A16) so unsigned dev builds still connect. Why it waits rather than ships
> now:
> 1. **The positive path is on-device-only testable.** `verified = true` requires a real
>    Tenshu-*signed* peer connecting; there is no signed `Drive.app` / `Control.app` yet (no
>    Apple Team ID + identity in the build env — signing is scaffolded, §3 / Phase 4). CI can
>    only exercise the *resolution* (self-connection: peer PID == our own PID), never the
>    enforcement decision that matters.
> 2. **Native mechanism is an open project decision** — JNA (a new runtime dependency) vs JNI
>    (a `.dylib` build to compile + codesign into the bundle) vs FFM-incubator (`--add-modules
>    jdk.incubator.foreign`, a fragile preview on the Java-17 toolchain). The repo has *no*
>    existing native bindings today.
> 3. **JDK-internal reflection is fragile**, and Compose `nativeDistributions` doesn't cleanly
>    expose packaged-app `jvmArgs` for the required `--add-opens`.
>
> The gate seam (above) already de-risked the *architecture* — the chokepoint exists, is
> tested, and is fail-closed-ready — so the verifier is a contained drop-in (a real
> `PeerCredentialVerifier` + `strict = true`) the day there's a signed build to validate it
> against. Until then, T-C-15's residual stands as documented.

Remaining Phase-4 work: the deferred native peer-credential verifier above (gated on signing
infra), plus notarization, streaming, badges, and the daemon-down UX listed at the top. Developer
ID + notarization steps (deferred until the surfaces are validated) are stubbed in
[`../how-to/macos-signing.md`](../how-to/macos-signing.md) §4.

---

## 7. Outlook — Windows & Linux (deferred)

Not in scope; sketched so the macOS work doesn't paint them into a corner. The
**native bridge surface (§2.2) is the shared seam** both reuse.

**Windows.** Tray via `Shell_NotifyIcon` (clean, stable — bind directly, no DorkBox).
File integration via the **Cloud Filter API (CfAPI)**: register a sync root
(`StorageProviderSyncRootManager`) for the File Explorer navigation-pane entry +
placeholder/hydrate-on-demand, backed by the same bridge. Transport is the named-pipe
variant already in `desktop-ipc.md` §4; no App Group equivalent needed (named-pipe ACLs
+ the peer-PID check suffice).

**Linux.** Tray via **DorkBox SystemTray** (the one place the library earns its keep —
StatusNotifierItem/D-Bus fragmentation, per `tenshu-rei.md` §3.6). File integration has
no clean OS-standard equivalent: a **FUSE** filesystem is the portable option, with
GVfs/KIO backends as a later DE-specific nicety. Both still talk the bridge to the
daemon over the `~/.tenshu/ipc.sock` UDS.

Sequencing: validate macOS Phases 0–3, then port the bridge consumers platform by
platform. Each platform is its own native surface; only the daemon-side bridge is shared.

---

## 8. Open questions

1. **Agent language (from §3.6).** ✅ **DECIDED (Phase 1 start, 2026-06-02): Swift for
   both.** The tray is a separate Swift process (LaunchAgent) consuming the JSON bridge,
   matching the Phase 2 File Provider (which must be Swift) and the proposal's NSPopover
   spec. Kotlin/Native (reuse KMP client, skip the bridge for the tray) was considered and
   rejected as off-path; an in-JVM Compose/AWT tray was rejected for AWT SystemTray's
   menu-only UI + weak macOS template-icon/dark-mode. Consequence: "Open Control" goes
   over the bridge (`openControlWindow`), and the daemon runs headless with the Compose
   window opened on demand (Phase 1 task 1 / §6 Q6). **Production launch orchestration:**
   unsandboxed development and Developer ID builds retain the independent LaunchAgent
   (`app.tenshu.control.tray`); MAS Control now uses the embedded `SMAppService` login item
   for daemon startup, with tray cold-launch verification still pending on an installed MAS build.
2. **Bridge protocol shape.** ✅ **DECIDED (Phase 0): minimal hand-rolled envelope** with a
   `v` (`PROTOCOL_VERSION`) field, private/internal. A future *standard* schema-derived
   Tenshu JSON representation is tracked as a `docs/tech-debt.md` entry ("Standard Tenshu
   JSON Representation", n=3 trigger) rather than adopted now.
3. **Domain ↔ realm mapping.** One File Provider domain for the personal realm in v1;
   how shared realms (`drive-shared-realm-file-model.md`) surface as domains/mounts later
   is deferred — likely one domain per mounted shared realm.
4. **Packaging path.** Compose `nativeDistributions` + custom re-sign, vs an Xcode
   wrapper that embeds the JVM runtime. Phase 0 decides based on how hard App Group
   entitlement injection turns out to be.
5. **Primary-handler grant for the extension.** Confirm Drive-as-files-primary-handler is
   the right default for Phase 3, and how the user sees/revokes it in Control.
6. **Tray ↔ daemon ↔ window in one JVM.** Confirm the headless-daemon + on-demand-window
   refactor doesn't fight Compose Desktop's `application {}` lifecycle assumptions.

---

## 9. Implementation Notes for Claude Code

Canonical handoff. File-level starting points per phase; paths relative to repo root.

**Phase 0 — foundation**

| Area | File / location | Action |
|------|-----------------|--------|
| Socket path | `core-common/src/desktopMain/kotlin/org/tenshu/core/common/transport/` | New `SocketPathResolver`; group-container path on macOS, `~/.tenshu/` fallback |
| Server | `…/transport/UnixSocketServer.kt` | Bind via resolver |
| Client | `…/transport/UnixSocketClientConnection.kt` | Connect via resolver |
| Bridge | `…/proxy/` (new `NativeBridgeServer.kt`) | JSON-over-UDS; v0 = `ping`, `getSyncState`; delegates to `ProxyGateway` |
| Packaging | `control-desktop/build.gradle.kts` | Gradle task: inject App Group entitlements + `codesign` after `nativeDistributions` |
| De-risk | `control-desktop/macos/HelloClient/` (new, Swift) | CLI: connect → `ping` → print |
| Tests | `core-common/src/desktopTest/.../transport/` | Extend existing `DesktopIpcIntegrationTest` for the resolver + a bridge round-trip |

**Phase 1 — tray**

| Area | File / location | Action |
|------|-----------------|--------|
| Lifecycle | `control-desktop/src/jvmMain/kotlin/app/tenshu/control/Main.kt` | Split daemon vs window; close→hide; explicit quit→`shutdownAndJoin()` |
| Autostart | `control-desktop/macos/LaunchAgent/app.tenshu.control.plist` (new) | LaunchAgent; install/remove from settings |
| Tray | `control-desktop/macos/TrayAgent/` (new, Swift) | `NSStatusItem` + `NSPopover`; bridge client |
| Bridge v1 | `…/proxy/NativeBridgeServer.kt` | `subscribeSyncState`, `recentNotifications`, `openControlWindow`, `listApps`, `launchApp` |
| Data sources | `notification-service-client`, `core` `AppService` (`AppRegistration`) | Back the bridge methods |

**Phase 2 — File Provider (read)**

| Area | File / location | Action |
|------|-----------------|--------|
| Extension | `drive-desktop/macos/FileProviderExtension/` (new, Swift) | `NSFileProviderReplicatedExtension`; `com.apple.fileprovider-nonui`; App Group entitlement |
| Embedding | `drive-desktop/build.gradle.kts` | Embed `.appex` in `Drive.app/Contents/PlugIns`; sign |
| Domain reg | extension or `drive-desktop/.../Main.kt` | `NSFileProviderManager.add(domain:)` |
| Bridge read | `…/proxy/NativeBridgeServer.kt` | `listFolder(id)`, `itemMetadata(id)`, `readContents(id)` → file-service list/read ops |
| Read ops | `file-service-client` (`listSharedFolder`/list, `getData`, `getPreview`) | Reuse via proxy |

**Phase 3 — File Provider (write)**

| Area | File / location | Action |
|------|-----------------|--------|
| Write bridge | `…/proxy/NativeBridgeServer.kt` | `createItem`, `modifyContents`, `deleteItem`, `move`, `createFolder` |
| Write ops | `file-service-client` (`uploadFile`, `replaceFile`, `deleteFile`, `moveFile`, `createFolder`) | Reuse via proxy |
| Permissions | `core` `AppService` / `AppGrant`; `tenshu-rei.md` §2.2 | Primary-handler grant for files; proxy-side enforcement |
| Signalling | extension | `signalEnumerator(.workingSet)` on daemon change events |

**Phase 4 — hardening**

| Area | File / location | Action |
|------|-----------------|--------|
| Tray packaging ✅ | `control-desktop/build.gradle.kts` (`buildTrayAgent`/`embedTrayAgent`); `LaunchAgentManager` | swiftc-build + embed tray at `Contents/Helpers/tray-agent`; second LaunchAgent (`.tray`, KeepAlive); `macOS.bundleID` pinned |
| Notarize | `*-desktop/build.gradle.kts` | notarization + staple in release pipeline |
| Threat model | `docs/sync/threat-model.md` | New local components + group-UDS transport; handshake-is-the-gate note |
| Protocol doc | `docs/protocol/desktop-ipc.md` | Amend: macOS group-container socket path; native bridge surface |

**Build/run reminders.** Desktop modules are Compose-Desktop JVM (`compose.desktop {
nativeDistributions { Dmg, Pkg } }`). Native targets (Swift) are built by `swiftc`/Xcode
invoked from Gradle tasks and embedded into the `.app` post-package. CMP is pinned to
1.7.3 — don't bump (`docs/tech-debt.md`). Run desktop: `./gradlew :control-desktop:run` /
`:drive-desktop:run`. The File Provider extension can only be exercised from an
installed, signed `Drive.app` (not from `:run`).
