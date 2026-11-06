# iOS Drive parity — productize route-1 and close the feature gap

**Status: v1.8 — plan settled 2026-07-17; P1–P5 + P6a + P3b + S7 + P6b first slice all code-complete + build-green (as-built §6–§18). **P4e COMPLETE (all three pieces)** — gap #1 (iOS thumbnail *generation*, `IosGetPreviewImplementation`) BUILT 2026-07-18 + gap #2 (Swift list-row thumbnail loader, `DrivePreviewSurface.loadRowThumbnail` + `FileRow` `.task`) BUILT 2026-07-19 + the video-poster *generator* (`AVAssetImageGenerator` in `IosGetPreviewImplementation`) BUILT 2026-07-19; all build-green (as-built §21). Parity UI since: listing **Sort menu + list/grid toggle** (2026-07-20); share-action discoverability (as-built §22, 2026-07-19); **visible per-row overflow menu** taking item actions off long-press alone + My-shares Copy ID/Revoke, chevron dropped for the content-browser idiom (as-built §23 + §23.1, 2026-07-20, **device-verified on an iPhone 17e**); **file search** — the last real feature gap — closed (as-built §24.5 — **supersedes §22's "the ⓘ chain is a harmless secondary" call**). Remaining: P7 — the device-gated on-device ship pass (signing + TestFlight + App Attest + the functional matrix consolidating every framework-level deferral); **runbook in §19**. Plus the P6b tail (live PHPhotoLibraryChangeObserver trigger, per-album selection).**

Companion to [`ios-proxy-architecture.md`](ios-proxy-architecture.md) (the platform decision doc),
[`ios-drive-live-client-handover.md`](ios-drive-live-client-handover.md) (EPC live-client seam) and
[`ios-control-handover.md`](ios-control-handover.md) (Control-on-iOS C0–C6 + the route-1 as-built).
This doc adds the piece none of them contain: the plan from "route-1 proven as sim harness probes"
to "Drive-on-iOS is a shipping client at feature parity with the Android/desktop KMP apps".

---

## 1. Where we actually are

The premise "iOS Drive is far behind and mock-only" undersells the state. What exists on master:

- **A full SwiftUI Drive client** (`drive-ios/`, ~23 files): browse, file detail, create/rename/
  move, share link, manage access, shared-with-me, invite/redeem, settings/sync-prefs — all bound
  to the real `:drive-shared-logic` ViewModels through the `Drive*Surface` seams in
  `:tenshu-sdk-ios`. Only the *repositories* are fakes (`FakeFilesRepository` & co.), installed by
  default in `DriveReadSurface`; `installRealClient(channel)` swaps all three repos to a real
  `TenshuClient` in one call.
- **Control-on-iOS works on the simulator** (`control-ios/`, C4-RT complete): real in-process
  proxy runtime (`:control-shared-proxy` iosMain `PlatformProxyModule`), genesis → lock/unlock
  lifecycle, device pairing wizard, relay registration, EPC host.
- **The route-1 (in-process collapse) data plane is proven end-to-end** — as *probes*, not
  production wiring (handover §C4d–§C4e): shared App-Group replica
  (`sharedReplicaDirectory`), cross-process single-writer `FileAdvisoryLock`, cross-process
  shared HLC counter, durable realm-key hand-off over the shared Keychain
  (`RealmKeyMaterialBridge`: A4 + `derivedKeys` + ownerId/deviceId), Drive doing real in-process
  crypto → wire-valid `SyncBlock` → `POST /sync/push` → Control ingesting it through the live
  `SyncProcessor` into its DB, plus writer-crash recovery.
- **Empirical route confirmation**: a co-resident EPC channel establishes fine but stalls
  the moment Control is backgrounded — iOS suspends it even while Drive is foreground. Route 2
  (EPC) remains correct for the *remote*-proxy case and third-party apps; the same-device
  first-party data plane is route 1. The key-custody decision (2026-06-28, handover §"Route-1
  write key custody") made Drive a **key-holding realm device for route-1 same-device only**,
  riding Control's device identity (A4 + derived keys via shared Keychain; Drive never holds the
  master key A1).

The gap is therefore two-fold:

1. **Assembly**: nothing composes the proven route-1 primitives into a production Drive runtime;
   the shipping app still gates on a presence token and runs on fakes.
2. **Feature surfaces genuinely missing on iOS**: preview (image/PDF/video), real uploads
   (`IosFilesRepositoryPlatformDelegate` throws), offline pinning (Noop prefs), the deferred
   sharing-handshake sub-flows, synced-folders auto-backup, background execution.

## 2. Scope notes (what parity does *not* include)

- ~~**Search is excluded** — it is a stub on *every* platform (`FilesSearchBarViewModel` has empty
  handlers; there is no search op). Not an iOS gap; a future cross-platform feature.~~
  ⚠ **CORRECTED 2026-07-20 — this was true when written and is now wrong on both counts.** Drive
  search shipped on **Android + desktop**: `FilesSearchBarViewModel` drives a debounced
  (250 ms) `distinctUntilChanged` → `flatMapLatest` pipeline into
  `FilesRepository.searchFiles(query)`, and `TenshuFilesRepository.searchFiles` runs a real
  proxy-side chain — `root.files.traverse().filter<File> { name.contains(needle, ignoreCase = true) }`
  — over a **registered `Contains` operation** (`core/.../Contains.kt`,
  `ImplementationFactory.kt:76`), covered by `FileNameSearchIntegrationTest`. The bar is wired into
  both `HomeScreen` and `FilesScreen`, with a desktop `Ctrl+F`.
  ⇒ **Search is a genuine iOS parity gap — the only platform without it** — not a cross-platform
  stub. Closed by the §24 slice (as-built §24.5, 2026-07-20). Web has none either, but web Drive is out of this doc's scope.
- **Synced-folders auto-backup is a reframe, not a port** (`ios-proxy-architecture.md` §9.4):
  PhotoKit change-history + background `URLSession` + "open the app" UX. Sequenced last (P6).
- Web-only surfaces (apex onboarding etc.) are out of scope.

## 3. Phase map

Each phase is independently shippable; P1/P2 carry the architectural risk, P3–P5 are per-feature
slices over an already-proven seam. Threat-model amendments land with the code per House Rule #2
(the route-1 at-rest deltas — A4/A5 sharing across the two first-party apps — already landed with
C4e; per-phase deltas are flagged below).

### P1 — Production route-1 runtime ("Drive goes live in-process") — the keystone

Turn the C4e probe chain into a real `DriveReadSurface.installLocalRuntime()`: an in-process
proxy runtime inside Drive's process, mirroring the *production* wiring of Control's iosMain
`PlatformProxyModule` (never test fixtures), differing only where Drive's role differs:

- **Executor**: `LocalTaskExecutor` (in-process, the JsRuntime shape) — no EPC channel, no relay
  rendezvous, no second process.
- **Store**: `FileObjectStore` over `sharedReplicaDirectory("objects")` — the *same* replica
  Control writes (relocated there by C4e-1). ⚠ *History: since 2026-11-05 the shared replica is the
  SQLite `SharedReplicaStore` in `replica/`, and `FileObjectStore` is deleted
  ([`ios-shared-store-sqlite.md`](ios-shared-store-sqlite.md) B.22.9).*
- **Identity/keys**: read `RealmKeyMaterialBridge` (A4 signing key, `sharedIdentityKey` +
  `sharedVolumeKey`, ownerId/deviceId). Drive's `SyncManager` must consume **handed-off derived
  keys** instead of deriving from a master key it doesn't have — a "member-style" realm-context
  assembly. This is the sharpest P1 design point; the probes bypassed `SyncManager` entirely.
- **Single-writer discipline**: the natural seam is `DatabaseLock` — iOS currently binds
  `NoOpDatabaseLock` (sanctioned interim per its KDoc). Replace with a cross-process
  `FileAdvisoryLock`-backed `DatabaseLock` (`replica.writer.lock`) **in both apps' runtimes** so
  every replica write in either process is lock-guarded. ⚠ Today *neither* production runtime
  takes the writer lock — the probes proved the primitive, not the discipline.
- **Clock**: productize the C4e-3b shared logical counter into the HLC path (shared physical+
  logical state in the App-Group container, RMW under the lock) so the two writers never fork.
- **Sync**: Drive's own foreground sync cycle — `RestSyncRelay` push/pull with the A4-signed JWT
  + signed HLC tip, `SyncProcessor` ingest. Both apps pulling the same realm is fine: ingest is
  idempotent (`resolveConflictAndSave`), and blocks land in the shared replica either way.

Sub-slices (worktree-per-slice, PR each):

- **P1a — read path**: assemble the runtime + real `TenshuFilesRepository` over the shared
  replica, reads only. Bar: iosSimulatorArm64 tests (assembly + a listing over a replica seeded
  by Control's genesis) + the A/B sim harness (Control genesis → Drive browses real rows).
- **P1b — local writes**: lock-backed `DatabaseLock` in both runtimes + Drive mutations
  (create/rename/move/delete) landing durably in the shared replica; Control sees them.
- **P1c — relay round-trip**: Drive's sync cycle pushes its blocks and ingests pulls; second
  device (desktop Control vs dev relay) converges. Includes the durable pending-push journal
  (re-drain on relaunch) that C4e-3c-3d left open.
- **P1d — seam flip**: `RootView` gates on `RealmKeyMaterialBridge` material (not the presence
  token), installs the local runtime at launch; fakes remain for tests/previews only.

Threat model: the at-rest sharing deltas are already amended (C4e-3c-1/#752/#753); P1d adds a
note that Drive is now a *production* route-1 writer (no new boundary, same custody).

### P2 — Session & lifecycle correctness

Split into **P2a (custody lifecycle — SHIPPED, as-built §10)** and **P2b (foreground
re-hydration — open)**.

- ~~Foreground/launch probe + teardown symmetry~~ **P2a done**: identity-destroying events
  (reset / sign-out / revocation → `clearDriveSession()`) clear the bridge; Drive's gate
  detects the vanish and `deprovision()`s (seal + purge its own custody copies). "Lock now"
  deliberately does NOT clear — durable custody while Control is locked is route 1's point.
- ~~Resident-cache re-hydration on foreground~~ **P2b done** (as-built §11):
  `FileObjectStore.rehydrateFromDisk()` + foreground hooks in both apps + pull-to-refresh.
  ⚠ *Retired 2026-11-05 with the file store (§11's note): the SQLite replica has no resident cache
  to re-hydrate.*
- Key-rotation propagation (GR-7 retained keyring) through the hand-off: republish on rotation.
- On-device durability tail: torn-write/power-loss atomicity check (needs hardware; `terminate`
  is a clean death).

### P3 — Upload parity

Split into **P3a (picker uploads — SHIPPED, as-built §12)** and **P3b (O3 background
`URLSession` — SHIPPED, as-built §16)**.

- ~~Real picker bytes~~ **P3a done**: the delegate reads picker `file://` URIs (bytes +
  source mtime); the Swift document path copies while security-scoped.
- ~~`File.CreatedAt`~~ **P3a done as source mtime** per the settled cross-platform decision
  (mtime, not EXIF — capture date is the future Photos `capturedAt` trait; photo-picker
  copies carry the import date until then).
- Bulk/background upload: the §9.4 **O3 content-key exception** — encrypt locally, push
  ciphertext chunks via background `URLSession`, register the manifest in a small foreground op.
  Content-addressing Ph1+ (CEK, manifest-as-blob) made this cleaner: the background session
  carries only sealed chunks. Threat-model note per O3 when it lands.

### P4 — Preview parity

iOS has no preview screens. Order by cost:

- **P4a images**: zoomable image preview over `File.Preview`/thumbnail ops (already eager-synced;
  the deferred-properties surface serves the one-shot case).
- **P4b PDF**: native **PDFKit** — the seam `drive-content-preview.md` §4.1 already reserves for
  iOS (the `expect PdfDocument` actuals are android+desktop only, deliberately).
- **P4c video**: AVPlayer. Start with the D2a decrypt-to-temp-file shape (A5 note already
  amended); the designed **S7** streaming slice (`AVAssetResourceLoaderDelegate` over the
  `OpenContentStream`/`ReadContentRange` pull ops, `drive-streaming-preview.md`) upgrades it —
  optional, after P4a/b.
- **P4d** "open externally" via `UIDocumentInteractionController` (mirrors D3).
- **P4e list-row thumbnails** (gap surfaced 2026-07-18 on-device: photos sync + full-open
  preview works, but Drive-iOS *list rows show only a type glyph*). Two stacked gaps, both
  iOS-only:
  1. **No thumbnail *generation*.** ✅ **BUILT 2026-07-18 (as-built §21).** There was no
     `iosMain` `GetPreview`/thumbnail resolver in `:file-service-proxy` (the dir didn't exist),
     so the iPhone never produced the eager-synced `File.Thumbnail`/`File.ThumbnailLarge` at
     upload. Added `IosGetPreviewImplementation` (`UIImage` downscale + `UIImagePNG/JPEG`
     encode for images, PDFKit page-0 for PDF) + `iosFileServiceProxyImplementationFactory`,
     wired into the iOS `platformImplementationFactory` before the common factory (mirrors
     desktop). **Video-poster generation** (`AVAssetImageGenerator`) was initially deferred
     here, then ✅ **BUILT 2026-07-19 (as-built §21 addendum 2)** — the `isVideo` branch now
     extracts a poster frame from the video bytes (temp file → `AVAssetImageGenerator`) and
     runs it through the same downscale/encode path, falling back to a stored poster only
     when extraction fails.
  2. **No list-row rendering.** ✅ **BUILT 2026-07-19 (as-built §21).** `FilesView.swift`
     rendered each row as `Image(systemName: file.previewIcon.sfSymbol)` — a static glyph.
     Added `DrivePreviewSurface.loadRowThumbnail(path, maxPx)` (delegates to the shared
     `FilesRepository.getPreview` — same op Android/desktop rows use — and returns a small
     LIST thumbnail as `NSData`, which crosses SKIE like S7's ≤256 KiB ranges), and a
     `FileRow` `.task(id:)` async loader that renders the `UIImage` (falling back to the
     glyph), gated to non-folder thumbnailable rows via `FileIcon.isThumbnailable`.
     Viewport-driven + auto-cancelling (SwiftUI cancels the row `.task` on scroll-off) — the
     iOS twin of the Android/desktop per-row `previewLoader`.
  ⚠ Was: photos backed up *from* the iPhone carried **no** synced thumbnail, and iOS showed
  glyphs on both its own and synced files. Both halves now closed (gap #1 generates + syncs
  the thumbnail; gap #2 renders it in the row). **P4e COMPLETE — all three pieces incl. the
  video-poster generator** (functional render → P7 on-device). Videos now generate their own
  poster on the iPhone (`AVAssetImageGenerator`), the same as photos/PDFs.

### P5 — Sharing cluster live + offline

- The deferred handshake sub-flows over the real repos: SAS-confirm/completion prompt
  (`ContactPromptViewModel`), invitee redemption (`RedeemInviteViewModel`), REDEEMED banner +
  resumed-redemption rows; on-device camera QR for invites.
- Offline pinning for real: replace `NoopOfflinePreferences`/`NoopSyncRulesPreferences` with the
  `:preference-service`-backed implementations over the local runtime (the toggles already exist
  in `SyncPreferencesView`, currently optimistic-only).
- Mode-B membership flows already have iOS UI (grant/accept/members over fakes) — they go live
  with the repos; on-device pass folds into P7.

### P6 — Background execution + Synced-Folders reframe

- `PlatformScheduler` iOS actual over `BGTaskScheduler` (`BGAppRefresh`/`BGProcessing`) driving
  the headless sync-drain ops (`HeadlessSafe`), per §9.4 "ports cleanly".
- Optional push-wake (silent push) as a sync nudge — needs APNs plumbing; assess after P1c.
- Synced-Folders **reframe**: PhotoKit change token + O3 background upload + explicit "open the
  app to finish" UX. The Android `BackupWorker` model does not port.

### P7 — Ship hardening

- Real Apple **App Attest** (arch doc phases 4b/6c/6d) — required for the *route-2/third-party*
  EPC surface; route-1 first-party doesn't depend on it, so it gates shipping, not P1–P6 dev.
- Real-device signing: team-prefixed `$(AppIdentifierPrefix)app.tenshu.shared` Keychain group +
  `group.app.tenshu.shared` App Group under one Team ID (the ad-hoc-sim bare-group trick does
  not carry to hardware; `docs/how-to/ios-signing.md`), paid App Groups capability.
- Full on-device functional pass (camera QR, pickers, background tasks, AVPlayer, FileProvider
  question deferred), TestFlight.

## 4. Dependency sketch

```
P1a → P1b → P1c → P1d → P2
P1a ─────────────→ P3, P4, P5 (features ride the live runtime; P4a/P4b only need reads)
P6 needs P1c (a sync cycle to schedule); P7 last.
```

## 5. Open questions

- **Q1** How exactly `SyncManager`/realm-context assembly consumes handed-off derived keys
  (member-style context without A1). Settle in P1a design; likely a narrow constructor seam on
  the existing `SyncManagerImpl` rather than a parallel implementation.
- **Q2** Should Control's iOS runtime *also* run its sync cycle when Drive is foreground, or
  should the awake app own the cycle exclusively (lease in the App-Group container)? Duplicate
  pulls are safe but wasteful; decide with P1c measurements.
- **Q3** Does the iOS Drive client need `:drive-shared-logic`'s paging over the local runtime
  (`TenshuPagingPresenter` already works over fakes) — expected yes, verify perf on-replica in P1a.

## 6. P1a as-built (2026-07-17)

- **`DriveLocalRuntime`** (`tenshu-sdk-ios` iosMain): assembles the production Control-on-iOS
  wiring (`proxyModule` + `platformProxyModule` — the same modules
  `ControlSurface.installRealRuntime()` boots) with a small last-wins override module:
  `platformPackageName` → `app.tenshu.drive.ios` (the `LocalClientConnection` key) and a
  Drive-named `SecureStorageDelegate` Keychain service. `assemble(replicaDirectory,
  secureStorage)` is the test seam; `install()` /
  `DriveReadSurface.installLocalRuntime()` swaps all three app repositories (files +
  contacts + membership, the `installRealClient` twin). The `ObjectStore` binding rides
  `platformProxyModule` unchanged → the **shared App-Group replica** in the entitled app,
  app-local fallback in tests.
- **Sealed-realm posture confirmed**: with no genesis/unlock in Drive's process the graph
  assembles, the sync scheduler idles gated, and metadata reads decode the plaintext replica
  rows (C4e-2). Q1 (handed-off-key `SyncManager` consumption) is untouched — it is a
  P1b concern, exactly as scoped.
- **Tests** (`DriveLocalRuntimeTest`, iosSimulatorArm64): full-graph assembly + empty-root
  list; a second runtime reading a first runtime's durable `createFolder` over one replica
  directory; the paged pipeline (the app's actual
  `DriveReadSurface.create`→`TenshuPagingPresenter` path) delivering rows. First time the
  full proxy graph runs in a bare K/N test process.
- **A/B sim verification** (ad-hoc-signed iPhone 16, erased): Control `-genesisAutoComplete`
  (18 replica blobs) → Drive `-localRuntime` (new DEV_HARNESS route) boots the runtime over
  the shared container, real `FilesView` renders; a dev-seeded `createFolder` through the
  real op path appears **live** in the mounted list (Pager-invalidation observer works
  in-app) and Control's store-less owner read lists Drive's folder + modification-history +
  notification rows cross-process.
- ⚠ **Find #1 — "empty == loading" conflation**: `FilesView` showed an eternal spinner on an
  empty folder (`files.isEmpty` alone gated the overlay; the canned fakes were never empty,
  and the co-resident EPC "Loading…" hang masked it as the wake-wall). Fixed by a new
  `TenshuPagingPresenter.refreshSettled: StateFlow<Boolean>` (true once the initial refresh
  lands; ⚠ stays false for static `PagingData.from` sources) + a real "No files yet" empty
  state. Other presenter consumers (Control-iOS Home, Timeline/Fit) inherit the same latent
  bug — sweep when those go live.
- ⚠ **Find #2**: this was the first time a **real Pager** (vs `PagingData.from` fakes) ran
  through `TenshuPagingPresenter` on iOS at all — it works on both Main and Default
  dispatchers (`DriveLocalRuntime.debugPagedProbe` pins this in the harness banner).
- Threat model: no new boundary — in-process read+write of the shared replica by co-resident
  first-party Drive was amended with C4e-2/C4e-3a; the production write posture
  note lands with P1d per §3.
- Harness probes kept for later slices: `debugListRoot` / `debugPagedProbe` /
  `debugSeedFolder` (DEV_HARNESS banner diagnostics).

## 7. P1b as-built (2026-07-17)

- **`SharedReplicaDatabaseLock`** (`core-common` iosMain): the composite cross-process
  [DatabaseLock] — `NSRecursiveLock` for the in-process reentrancy/thread serialisation the
  JVM's `ReentrantLockDatabaseLock` provides, plus a `FileAdvisoryLock` (`flock`) on
  `sharedReplicaWriterLockPath()` = the shared container root's **`replica.writer.lock`** —
  the *same file the C4e probes contend on* — acquired at outermost entry, released at
  outermost exit. `flock`'s useless same-fd/cross-thread semantics (the C4d spike finding)
  are covered by the recursive lock admitting one thread to the fd at a time. Crash-safe
  (C4e-3c-3d: the OS drops a dead holder's flock).
- **Bound in `platformProxyModule` iosMain replacing `NoOpDatabaseLock`** — ONE binding
  covers both apps, because Drive's runtime reuses Control's module (the P1a shape paying
  off). `DriveLocalRuntime.assemble(replicaDirectory=…)` overrides the lock into the temp
  replica dir so tests stay hermetic.
- **Tests**: `SharedReplicaDatabaseLockTest` (core-common iosSimulatorArm64) — nested-
  `withLock` reentrancy; flock held across nesting + released at outermost exit (independent-
  fd probe); a two-instance racy counter across `Dispatchers.Default` threads (two instances
  = two processes: exclusion can only come from the flock) with zero lost updates.
  `DriveLocalRuntimeTest` gained the full mutation set — create ×3 / rename / move / delete
  through the real op path, an independent runtime reads the exact final tree.
- **A/B sim** (erased ad-hoc iPhone 16): Control genesis runs under the new lock
  (regression-clean); Drive `-localRuntime` runs the `debugMutationCycle` probe — every step
  `ok`, the mounted Files list live-refreshes to `[P1b renamed (1 files), Route-1 demo]`;
  Control's owner read sees the final tree + the full modification history cross-process.
- ⚠ **Confirmed stranded-write caveat**: the owner-side dump shows exactly ONE
  `tenshu/sync/block` row — Control's genesis block. Drive's sealed saves emit **no**
  SyncBlocks (the known locked-save gap), so P1b writes are device-local by design until
  P1c gives Drive keys + emission — and P1c must **reconcile/re-emit rows saved while
  sealed** (the same reconcile-on-unlock problem the stuck-file bug identified), not just
  start emitting for new saves.
- ⚠ **Resident-cache staleness** across live processes recorded as a P2 duty (see §3 P2).
- Threat model: no change (local advisory lock; no data/keys/relay surface).

## 8. P1c as-built (2026-07-17)

- **Q1 RESOLVED — adoption rides the existing joiner path, no new SyncManager surface.**
  `SyncManagerImpl.initializeWithKeys` (derived keys + ownerId, **no master key**) is exactly
  the member-style assembly Q1 asked for — a route-1 Drive *is* a joiner-shaped device.
  `DriveLocalRuntime.install()` reads `RealmKeyMaterialBridge`, pre-seeds Drive's storage
  delegate with Control's `DEVICE_ID` + A4 blob **before** the `SyncManagerImpl` constructs
  (its deviceId/A4 are read from storage at construction), then adopts via
  `initializeWithKeys` + sets `realmRelayRegistered` / `joinedFundedRealm` (both already true
  realm-wide — Control registered; the realm is owner-funded). With `isEnabled()` true, the
  **standard machinery lights up**: save observers emit realm-sealed blocks, the scheduler
  drains them to the relay with the A4-signed JWT, pulls ingest via `SyncProcessor`. No
  parallel sync path, and adoption is durable (the joiner payload persists in Drive's sealed
  per-app bucket; construction-time rehydration covers later launches). Threat-model A4
  amendment co-landed (second at-rest copy inside the amended custody boundary).
- ⚠⚠ **Koin module-`val` singleton leak (find #3, fixed en route):** Koin stores singleton
  instances ON the factory objects inside a `Module`, so reusing the `proxyModule` /
  `platformProxyModule` **vals** across two `koinApplication`s silently hands the second app
  the first's singletons — my per-test assemblies shared one `SyncManager`, and the earlier
  multi-runtime tests partly tested one runtime talking to itself. Fixed with
  `buildProxyModule()` / `buildPlatformProxyModule()` builder functions (the vals remain for
  one-shot app boots); `DriveLocalRuntime` always assembles fresh. Production was never
  affected (one assembly per process).
- **Dev-relay override**: debug-binary-only `TENSHU_DEV_RELAY` env override
  (`SIMCTL_CHILD_…` at `simctl launch`) honored by `DriveLocalRuntime.install()` and
  `ControlSurface.installRealRuntime()` — the sim harnesses run against a dev
  `:sync-relay-server:run` without touching the production binding.
- **Verification**: unit — adopted runtime rides the handed-off deviceId/ownerId, `isEnabled`,
  and a `createFolder` lands a durable `tenshu/sync/block` row (offline: relay override at a
  closed port; also pinned: the emission path signs with A4 — a fake seed must be 32 bytes);
  sealed assembly stays read-only (no material → no blocks). A/B sim + dev relay: Control
  `-registerAndPublish` (201 realm registration) → Drive `-localRuntime` banner
  **"adoption: LIVE as owner=<Control's ownerId>"** → mutation cycle → **23 pushes, all
  `200 OK`**, blocks stored under Control's ownerId (relay mailbox log), Drive's own pull
  cycle green (33 × `/sync/changes 200`). Authority-side ingest of Drive-authored blocks was
  proven with the same wire shapes in C4e-3c-3b-2/3c.
- **Pending-push journal (C4e-3c-3d tail) — satisfied by the standard machinery, no bespoke code:**
  emitted blocks are durable replica rows and the scheduler's drain re-drives unpushed blocks
  on relaunch, exactly as on every other platform; the unit test shows blocks persisting
  through failed pushes.
- **Sealed-rows reconcile — deferred to P2, deliberately:** no reconcile-on-unlock primitive
  exists on master (the stuck-file fix branch never merged). With P1c, adoption happens at
  install-time *before any write*, and the P1d gate keeps Files off entirely when Control
  never provisioned — so the remaining stranded-write window is "keys vanish mid-session",
  which is precisely P2's teardown/lifecycle scope. Rows written by the pre-P1c dev harnesses
  are throwaway.
- **Remaining manual E2E**: a genuinely distinct second device (desktop Control paired into
  the realm) converging on Drive-authored blocks — maintainer-grade, folds into the P7
  on-device pass.

## 9. P1d as-built (2026-07-17) — P1 COMPLETE

- **The shipping gate routes on the route-1 key material.** `DriveLocalRuntime.isProvisioned()`
  (bridge-material presence probe) replaces the C3 presence token in `RootView`: provisioned →
  `installLocalRuntime()` (idempotent; adopts, sync live) → Files; absent → Connect-to-Control.
  The launch + foreground re-probe shape is unchanged, so returning from Control after setup
  forwards into live Files exactly as the §11.6 handoff intended. The DEV_HARNESS keeps two
  fallbacks after the material check: the Pair-tab EPC live client, and the dev-stub
  presence-token path (Files over fakes — unreachable in shipping, where only Control writes
  the shared-Keychain items).
- **Control's production handoff now publishes the material**: `provisionDriveSession()` also
  runs `publishRealmKeysToSharedKeychain()` best-effort (every onboarding call site — genesis
  "Open Control", recovery continue — has the unlocked realm it needs). Until now only the
  DEBUG harnesses published; the C3 presence token alone can no longer make Drive live.
- **Verified (shipping scheme, erased sim)**: unprovisioned launch → Connect-to-Control;
  Control `-publishRealmKeys` (genesis + publish, relay-free; the route is historical, deleted
  2026-11-05 — `-genesisAutoComplete` provisions the same way) → relaunch → **straight into
  live Files** with the real "No files yet" empty state (the P1a fix, now visible in the
  shipping surface). Full umbrella suite + all three app builds green.
- **Drive-by fixes (master's iOS umbrella was broken by the parallel Fit merges — iOS is not
  CI-gated):** `EpcFitClient` updated to `HealthImportBootstrap(importer=…)` (the `enabled`
  boolean was superseded); **`HealthKitImporter.autoRefreshReady()` override added** — the
  default `true` let the launch-time auto-refresh call `requestAuthorization` in any
  HealthKit-capable process, which **aborts the process** when the host lacks
  `NSHealthShareUsageDescription` (crashed the umbrella test binary; would crash any non-Fit
  host assembling the client). Now gated on the host app declaring the usage key — the iOS
  twin of Android's grant-less Health-Connect gate (fit-app.md §9.13). The Fit app declares
  the key, so its behavior is unchanged.
- P1 wrap-up: the P2 duties queued by P1a–P1c stand — teardown symmetry (clear the bridge
  **and** Drive's per-app joiner payload), foreground store re-hydration, sealed-rows
  reconcile for keys-vanish-mid-session, on-device durability tail.

## 10. P2a as-built (2026-07-17) — custody lifecycle

P2 split: **P2a = custody lifecycle** (this slice) · **P2b = foreground store re-hydration**
(needs its own reactive-invalidation design — see the updated P2 section).

- **Design call: "Lock now" does NOT clear the bridge.** Durable shared-Keychain custody is
  the point of route 1 (Drive keeps syncing while Control is locked); only
  **identity-destroying events** — realm reset / sign-out / revocation — clear the
  provisioning. The Control-side seam is `clearDriveSession()`, which now clears the
  presence token AND the bridge.
- **`DriveLocalRuntime.deprovision()`**: triggered by the shipping gate when the material has
  vanished while Drive holds adopted custody (`isInstalled() || hasResidualCustody()`), it
  seals the running graph (`SyncManager.lock()` — emission + drain idle) and purges Drive's
  at-rest copies (joiner payload, ownerId, A4 blob, deviceId, realm latches) — without it,
  the persisted joiner payload would silently resurrect the identity on the next launch (the
  unit test pins exactly this). Works pre-runtime too (standalone delegate on the same
  Keychain bucket). The shared replica is untouched: re-provisioning goes live again with no
  data loss. Residual: the sealed orphan graph until process death (the "Lock now" window);
  a relay-revoked device is additionally closed server-side.
- **Unlock refresh (refresh-only)**: `refreshDriveKeysIfProvisioned()` republishes the bridge
  after a successful Control unlock **iff material is already present** — refresh must never
  *create* provisioning (that stays the explicit onboarding handoff). Covers future
  key-material drift with no ceremony.
  ⚠⚠ **SUPERSEDED 2026-07-30 by `ensureDriveProvisioned()` — the refresh-only rule was
  unrecoverable on hardware.** See §16.
- **Verified**: unit — bridge-less relaunch rehydrates adoption from the joiner payload, and
  after `purgeCustody` a further relaunch stays sealed (iosSimulatorArm64); A/B sim — live
  Files → Control `-clearDriveSession` (new harness route) → Drive relaunch lands on
  Connect-to-Control (deprovisioned) → Control `-publishRealmKeys` (historical; deleted 2026-11-05) → Drive relaunch is
  **live again** (full teardown/re-provision round-trip, shipping scheme).
- **The keys-vanish "sealed-rows reconcile" is now moot in every reachable state**: adoption
  happens at install-time; a vanish routes to Connect (no write surface); writes made while
  the material vanishes mid-session still emit (in-memory keys live until deprovision runs).
- Threat model: the P1c A4 amendment updated in place — the teardown duty is implemented.
- Still P2b/manual: foreground store re-hydration (resident-cache staleness); on-device
  durability tail (torn-write, concurrent flock contention); GR-7-style rotation republish
  beyond the unlock hook if derived keys ever rotate.

## 11. P2b as-built (2026-07-17) — foreground store re-hydration; P2 COMPLETE

> ⚠ **Retired 2026-11-05** ([`ios-shared-store-sqlite.md`](ios-shared-store-sqlite.md) B.22.9, the
> 1.0.1 re-cut). `FileObjectStore`, `rehydrateFromDisk()`, both downcasts and every app's
> `rehydrateSharedReplica()` with its Swift foreground call are deleted. The shared replica is the
> SQLite `SharedReplicaStore`, which keeps no row cache: every read goes to the database inside one
> transaction, so the stale-resident-base hazard below cannot arise. Pull-to-refresh only
> re-`collect`s. What follows is the record of the file-store era.

- **Framing that shrank the problem**: when online, co-resident writes already converge
  *reactively* through the relay loop — each process pulls with its own cursor, ingest is
  idempotent, and this-process saves fire the normal observers. What re-hydration actually
  closes is (a) the **offline window** (disk-only visibility) and, more importantly, (b) the
  **stale-write hazard**: a foregrounded app saving an entity from a stale resident base
  clobbers rows the other app changed on disk. So the shape is a foreground-transition cache
  refresh, not a live-coherence protocol.
- **`FileObjectStore.rehydrateFromDisk()`**: rescans the replica dir, applies adds/changes/
  removals to the resident cache, fires listeners for changed rows, returns the changed count.
  ⚠ Change detection compares **encoded bytes** (`DbEntryCodec`) — `DbValue.ByteArrayValue`
  is a data class over a `ByteArray`, so structural `DbEntry` equality is reference-based
  there and would flag every byte-bearing row as changed each rescan (unit-pinned). Runs
  under the `DatabaseLock`, whose cross-process flock makes the rescan atomic against the
  co-resident writer.
- **Hooks**: `DriveLocalRuntime.rehydrateSharedReplica()` /
  `ControlSurface.rehydrateSharedReplica()` (lock-wrapped), called off-main at each app's
  foreground transition (Drive's `RootView` re-probe when already installed; Control's
  root `scenePhase` hook). `FilesView` pull-to-refresh is no longer a no-op: rehydrate +
  re-`collect` the paged source (collect replaces the generation → a fresh Pager load reads
  the refreshed cache).
- **Verified**: `FileObjectStoreRehydrateTest` (adds/changes/removals + listener fire +
  byte-row stability) and `DriveLocalRuntimeTest.rehydrateClosesTheCrossRuntimeStalenessWindow`
  — a live runtime is provably stale against the co-resident runtime's write and provably
  fresh after rehydrate, through the real read path. A/B sim smoke: background/foreground
  cycle over the live shipping app exercises the wiring, Files intact.
- **P2 COMPLETE.** Remaining lifecycle items are hardware-only (torn-write/power-loss
  atomicity, true concurrent flock contention) and fold into the P7 on-device pass.

## 12. P3a as-built (2026-07-17) — picker uploads

- **`IosFilesRepositoryPlatformDelegate` is real** — the structural twin of the desktop
  delegate: picker `file://` URI (NSURL percent-decoding, bare paths tolerated) → whole-file
  bytes off-thread → the common byte-based ops (`uploadFile`/`replaceFile` + the cancellable
  `…Background` pair), with **`sourceModifiedAt` = the source's filesystem mtime** riding
  along as the `File.CreatedAt` seed (the settled cross-platform decision — mtime, not EXIF;
  the capture date is the future Photos `capturedAt` trait). Folder enumeration stays
  unimplemented by design: no SAF tree on iOS — bulk ingest is the P6 Synced-Folders reframe.
- ⚠ **Latent Swift bug fixed**: the document-picker path released its security-scoped access
  in `defer` *before* the async upload read the bytes — invisible over the fakes (which never
  read), guaranteed failure for out-of-sandbox documents over the real delegate. Now the file
  is **copied while the scope is live** into a per-upload temp subdirectory (leaf name
  preserved = the upload's visible name; `copyItem` preserves the mtime = the CreatedAt seed).
- **Verified**: unit — a picker-shaped upload through the local runtime lands the listing row
  + local plaintext (`hasLocalData`), and the delegate resolves names/mtime from `file://`
  URIs (iosSimulatorArm64); A/B sim — the `debugUploadProbe` harness uploads through the
  production path and the mounted Files list live-refreshes with the real document row
  ("P3a-upload.txt · 20 b" + date subline). Framework + both Drive schemes build green.
- **P3b (open)**: O3 background-`URLSession` bulk upload — encrypt locally, push sealed
  chunks via a background session, register the manifest in a small foreground op; cleaner
  post-content-addressing (CEK chunks + manifest-as-blob already exist). Threat-model O3
  note lands with it. Also open: upload progress UI (the cancellable ops exist; no iOS
  progress surface yet).

## 13. P4 as-built (2026-07-17) — preview parity

- **`DrivePreviewSurface`** (umbrella iosMain): routing via the SHARED
  `fileCategoryForName`/`isPdf` (one truth with the list glyphs + other platforms' routers);
  bytes via the installed repository's `getRemoteFile`; a D2a
  `writePreviewTempFile`/`deletePreviewTempFile` pair. **Bridging decision: every renderer
  loads through the Kotlin-written temp file** — multi-MB payloads never cross the SKIE
  `ByteArray` bridge, and video/share need a URL anyway. v1 inherits the whole-`ByteArray`
  ceiling exactly like D2a elsewhere; S7 streaming is the upgrade path.
- **`FilePreviewView`** (SwiftUI router): image = pinch-zoom + double-tap reset; **PDF =
  native PDFKit** (`PDFView`, single-page-continuous — the §4.1-reserved seam); video =
  AVKit `VideoPlayer` over the temp file; other/unavailable = fallback. Every kind gets a
  system **share sheet** on the decrypted copy (the D3 "open externally" affordance in its
  iOS-native shape) + an ⓘ pushing the detail screen. **Navigation flip**: a tapped file
  now opens its PREVIEW (the canonical UX); `FileDetailView` moved behind ⓘ (new
  `FileDetailRoute`). Temp file deleted on disappear; player paused.
- **Verified**: `DrivePreviewSurfaceTest` — classifier routing; upload → `getRemoteFile`
  **byte-for-byte plaintext round-trip** (the full store/crypto path); temp-file lifecycle;
  missing-file → null. Harness `-previewImage`/`-previewPdf` (real fixture upload → the
  production preview path renders decrypted content): image fills the screen, PDFKit renders
  the fixture page. Suite + both Drive schemes green.
- ⚠ **Probe find worth keeping**: a file row can outlive its local plaintext (the iOS
  `FileDatabase` was in-memory — **closed 2026-08-03**, `ios-fit-timeline-maps-parity.md` §3.2.1;
  a row can still outlive its bytes, just no longer on every relaunch), and offline
  `getRemoteFile` then correctly fails →
  "Couldn't load" — the preview's offline/unfetched story is honest but minimal. The probe
  re-uploads per launch; the *product* answer (fetch-on-open spinner over the relay,
  offline-keeper pinning) is the P5-offline slice's concern.
- **Open**: video playback is code-complete but framework-level (AVPlayer) → on-device
  pending, same caveat as Android's `VideoView`; S7 streaming (range pulls via
  `AVAssetResourceLoaderDelegate`) stays the optional upgrade; a durable on-disk
  `FileDatabase` for iOS **shipped 2026-08-03** (`IosFileDatabase`, driven by Timeline's
  keep-offline need rather than by Drive — `ios-fit-timeline-maps-parity.md` §3.2.1), so the
  plaintext cache now survives a restart on iOS as it does elsewhere.

## 14. P5 as-built (2026-07-17) — sharing live + real offline prefs

- **Scope collapse discovered on entry**: the sharing repos (contacts + membership) have been
  live since P1a — `installLocalRuntime()` installs all three — and the CI-4-era SwiftUI
  sheets exist. P5's real content was (a) the Noop preference seams and (b) proving the
  sharing cluster actually works live. It didn't, at first — which surfaced the protocol
  find below.
- **⚠⚠ Find: A13 was missing from the route-1 hand-off.** Creating an invite failed with
  "Identity key (A13) unavailable" — invite sealing (and shared-realm key unwrap) needs the
  realm's X25519 encryption keypair, which the bridge never carried. Fixed end-to-end:
  `RealmKeyMaterial` gains nullable A13 pub/priv (two new Keychain items; absent on pre-P5
  material → sharing write-paths fail loudly as before), Control publishes it
  (`getRealmEncryptionKeyPair()`, available exactly when the rest of the material is), and
  adoption passes it through `initializeWithKeys`'s existing A13 slot — the joiner payload
  already carries A13, so persistence/deprovision needed nothing new. Threat-model A4/route-1
  paragraph amended (same custody boundary + lifecycle).
- **Real preference seams**: `installPreferences` slots on `DriveReadSurface` (Noop defaults;
  `installFakes` resets) — `TenshuOfflinePreferences`/`TenshuSyncRulesPreferences` over the
  in-process client installed by both live paths (local runtime + EPC). Settings' Sync
  Preferences, the detail screen's offline pin, and the selection VM all read the installed
  seams now — the toggles stopped being optimistic-only.
- **Verified**: unit — device-scoped offline pins round-trip through the real
  `WriteSetting`/read ops and survive a relaunch (same delegate → same deviceId); adoption
  test asserts A13 present after hand-off. A/B (clean sim + dev relay): the full harness
  chain green AND **`invite: LIVE link https://tenshu.app/i#v1.…`** — a real sealed invite
  registered in the relay mailbox by Drive-on-iOS.
- ⚠ Ops lesson re-learned: the invite first 401'd on a DIRTY sim (a re-run `-registerAndPublish`
  re-geneses → mixed-owner replica). Erase before relay-authed A/B chains.
- **Remaining sharing items** are multi-party by nature (redeem/completion round-trip needs a
  second user; camera QR needs hardware) → the P7 on-device pass, alongside Mode-B grant E2E.

## 15. P6a as-built (2026-07-17) — background sync drain

- **Route-1 insight that shaped the slice: on iOS, DRIVE is the background syncer.** Its
  adoption rehydrates from the persisted joiner payload with **no user present** (durable
  custody working as designed), so an OS wake can run a real drain — while Control's realm
  stays sealed until the user unlocks, making a Control BG task a guaranteed no-op. Drive
  registers; Control doesn't.
- **`DriveLocalRuntime.runBackgroundRefresh(maxWaitSeconds)`**: ensure the live runtime →
  P2b rehydrate (Control may have written while we slept — the stale-base guard before any
  bg work; ⚠ *gone since 2026-11-05 with the file store, §11's note*) → `syncNow()` (one-shot override; the in-process `ScheduleDrainLoop` is alive in
  a BG-launched process) → bounded window inside the ~30 s BGAppRefresh budget. v1 =
  bounded window, not drain-completion signalling; the suspend-until-drained seam is the
  cross-platform "SyncScheduler Phase 1 extraction" follow-up.
- **Swift `BackgroundSync`**: registration at app launch (both schemes), arm-on-background
  (gated on provisioning), handler re-arms first (grants are one-shot) + expiration
  cancellation. `UIBackgroundModes: [fetch]` + `BGTaskSchedulerPermittedIdentifiers:
  [app.tenshu.drive.refresh]` in project.yml.
- **Verified**: unit — the BG entry returns a status in any provisioning state, never
  crashes a background launch; sim — backgrounding fires the arm path and `submit` returns
  `BGTaskSchedulerErrorDomain Code=1` (**the documented simulator-unavailable behavior** —
  handled gracefully; foreground sync is unaffected). The live OS wake is hardware-only →
  the P7 on-device pass, the same verification split Android's WorkManager had.
- **Follow-up recorded — full `PlatformScheduler` registry integration**: iOS BG identifiers
  are STATIC (Info.plist), so a real iOS `PlatformScheduler` cannot mint per-`ScheduleItem`
  tasks like WorkManager; the correct shape is **one `BGProcessingTask` wake that runs all
  due registry items** (mapping `ConstraintSpec` → `requiresNetworkConnectivity`/
  `requiresExternalPower`). Deferred until a registry item beyond the sync drain matters on
  iOS (the offline keeper will be first).

### P6b — Synced-Folders reframe (first slice BUILT — as-built §18)

The Android auto-backup model (SAF grants + FS watch + `BackupWorker`) does not port. The
iOS reframe, per §9.4, when picked up:

1. **Source = PhotoKit, not the filesystem**: `PHPhotoLibrary` change history
   (`fetchPersistentChanges(since: PHPersistentChangeToken)`) gives an incremental "what's
   new since last run" cursor — the analogue of the SAF diff walk. Requires full library
   read authorization (a *new* consent surface; A34-style note).
2. **Transport = O3/P3b background `URLSession`**: encrypt locally, upload sealed CEK
   chunks via a background session (survives suspension), register the manifest in a small
   foreground/BG-task op. P6b therefore **depends on P3b**.
3. **Drive = the host app** (the background syncer, same reasoning as P6a); the BG wake
   that processes the change token rides the P6a task (or its BGProcessing sibling for
   power/network constraints).
4. **UX = "open the app to finish"**: iOS grants no guarantee of timely wakes — the
   Settings surface must show backup lag honestly and offer a foreground catch-up button
   (mirrors the §9.4 verdict).

## 16. P3b as-built (2026-07-17) — background-URLSession bulk upload (O3-B)

- **Reframe that shaped the slice.** The O3 write-up
  ([`ios-proxy-architecture.md`](ios-proxy-architecture.md) §9.4/§9.5) predates route-1: it framed
  background upload as needing a *content-key exception* because Drive was then a key-less EPC
  client. Route-1 (P1/P5) already made Drive a **key-holding realm device** — it holds the derived
  `sharedVolumeKey` (CEK-capable) + A13 and already encrypts chunks and PUTs blobs from its own
  process (P3a, foreground). So P3b is a **transport swap, not a trust concession**: move the
  content-plane blob `PUT`s onto a background `URLSession` (out-of-process `nsurlsessiond`) so a
  bulk upload survives app suspension/termination. Threat-model A5 amended accordingly (transient
  *sealed* ciphertext staged at rest — same posture as the ObjectStore replica already in the
  App-Group container; no new key/relay surface, HR#1 intact).
- **The whole restructure hides behind one seam.** `SyncObserver.emitFileContent` is **untouched**:
  Drive's runtime last-wins-overrides the personal-realm `ContentBlobStore` binding to
  `BackgroundUrlSessionContentBlobStore`, whose `putChunk(ref, cipher)` stops meaning "PUT now" and
  starts meaning "stage + enqueue"; `getChunk` (on-demand read) delegates unchanged to the
  foreground `RestContentBlobStore`. Control-on-iOS keeps the foreground store (on iOS route-1
  Drive is the background syncer).
- **Kotlin (`:tenshu-sdk-ios` iosMain).** `BackgroundUploadManager` stages each sealed chunk to
  `sharedReplicaDirectory("upload-staging")/<ref>`, records the ref in a durable
  `BackgroundUploadJournal` (a newline file in the App-Group container holding **only**
  content-addressed `blob-<sha256>` refs — **no** JWT/token/bytes at rest), and hands `(ref,
  stagedPath, PUT url, headers)` to the Swift bridge. Completion
  (`DriveLocalRuntime.onBackgroundUploadCompleted`) acks on 2xx (drop journal + delete staging),
  **re-mints + re-enqueues on 401/403** (a JWT baked into a background task can expire before
  `nsurlsessiond` runs the transfer — the one real gotcha), and leaves other failures for
  `resumeIncomplete()` (run on install + on the P6a background refresh; idempotent — content-
  addressed). With no bridge installed (unit tests, pre-registration) it **falls back to a
  foreground PUT**, preserving exact pre-P3b behavior.
- **Swift (`drive-ios`).** `BackgroundUpload` owns the `URLSession(.background,
  identifier: "app.tenshu.drive.content-upload")` + `uploadTask(with:fromFile:)` (background
  sessions upload only from a file — the staged chunk), reports completion back to Kotlin, and
  re-attaches after a relaunch. The relaunch entry point is a new `UIApplicationDelegateAdaptor`
  (`AppDelegate.handleEventsForBackgroundURLSession` → stash the completion handler + re-attach the
  same-identifier session); `DriveIosApp.init` registers the bridge at launch. No `project.yml`
  change — a background `URLSession` needs no `UIBackgroundMode` entry.
- **Ordering note (documented, not held).** The entity metadata `SyncBlock` may drain *ahead* of
  its content (blobs upload asynchronously); a peer's on-demand fetch then 404s and retries —
  content is content-addressed + immutable, so the retry is idempotent. That is the already-shipped
  content-plane contract, not a new hazard.
- **Verified.** `BackgroundUploadManagerTest` (iosSimulatorArm64, 6 cases, all green): journal
  durability across instances; stage → journal → enqueue with the right url + JWT header; 2xx ack
  cleans up staging + journal; 401 re-mints a fresh token and re-enqueues; no-bridge foreground
  fallback; resume re-enqueues chunks staged before a "termination". Both Drive schemes build green
  (Swift against the framework). ⚠ The live survives-suspension + relaunch behavior is
  framework-level and **hardware-only** → the P7 on-device pass, the same split as P6a's BGTask
  (`Code=1` on the sim).
- **Scope line (per the settled decision).** Single-file / personal-realm first: folder-tree bulk
  ingest stays P6b (Synced-Folders), shared-realm background upload folds into Mode-B, and an
  upload-**progress** UI is still open (the cancellable ops exist; no iOS progress surface yet). No
  stored-row/wire change → **HR#9 not triggered**.

## 17. S7 as-built (2026-07-17) — iOS streaming video preview

- **The substrate was already iOS-reachable.** The whole pull-range spine —
  `OpenContentStream`/`ReadContentRange`/`CloseContentStream` ops (`:sync-service`), the
  `StreamingContentSession` seek engine + `FileChunkDecryptor` (`:sync-service-proxy`), and the
  **commonMain** client `FilesRepository.openStreamingContent(path)` → `StreamingContentHandle`
  (`:drive-shared-logic`, S3) — is shipped (S1–S6) and compiled for iOS unchanged. iOS's real
  repository already has `openStreamingContent` for free. S7 added **only the presentation edge**.
- **Three iOS-edge pieces.** (1) Kotlin `IosContentStreamSession` (`:tenshu-sdk-ios` iosMain) — a
  Swift-facing wrapper over `StreamingContentHandle`: `totalSize`, suspend `readRange(offset,length):
  NSData?` (the `ByteArray`→`NSData` conversion happens in Kotlin so Swift gets `Data`), `close()`;
  plus `DrivePreviewSurface.openVideoStream(path)` returning it (null on Error/offline/fake → D2a
  fallback). (2) Swift `StreamingVideoLoader` — an `AVAssetResourceLoaderDelegate` over a
  custom-scheme (`tenshustream://`) `AVURLAsset`: fills `contentInformationRequest`
  (`isByteRangeAccessSupported`, `contentLength = totalSize`, UTI from the extension) and fulfils
  each `dataRequest` by looping `readRange` in ≤256 KiB from `currentOffset` → `respond(with:)` →
  `finishLoading()` (checks `isCancelled`; `finishLoading(with:)` on a null/gate-fail range). (3)
  `FilePreviewView` branches video → try `openVideoStream` (stream, retain the loader) else the
  existing D2a `writePreviewTempFile` player.
- **SKIE-bridge decision.** ≤256 KiB ranges DO cross the bridge (as `NSData`) — the P4 "no bytes
  across SKIE" rule was about multi-MB *whole* files (routed via a Kotlin temp file); a bounded
  per-range `NSData` is exactly the streaming win (no whole-file temp, no whole-`ByteArray`).
- **This is the iOS twin of Android S4** — the `AVAssetResourceLoaderDelegate`
  `shouldWaitForLoadingOfRequestedResource`→`readRange` / teardown→`close` replaces Android's
  `openProxyFileDescriptor` `onRead`/`onRelease`, minus the fd + `content://` provider indirection
  (AVFoundation's delegate is the seam). No content provider, no fd.
- **Trade-off:** streamed video has **no share button** (there's no on-disk copy, and a share would
  re-materialise the whole file, defeating streaming); the D2a fallback path keeps its share
  affordance. Note deliberate.
- **Threat model A5 amended** (S7 iOS consumer note): a **net reduction** in disk exposure — no
  whole-file plaintext temp on the streamed path; only a transient ≤256 KiB range in memory + the
  proxy session's bounded decrypted-chunk LRU (Drive's own in-process proxy under route-1). Custom-
  scheme asset played only by Drive's own `AVPlayer` (no third-party disclosure). HR#1 / relay shape
  unchanged; no stored-row/wire change → **HR#9 not triggered**.
- **Verified.** `IosContentStreamSessionTest` (iosSimulatorArm64, 4 green): a mid-file range's bytes
  round-trip byte-for-byte as `NSData`, EOF clamp + empty-past-EOF, `close` delegates, a null (gate
  fail) propagates. The seek engine / decrypt / range assembly are already covered cross-platform
  (`ContentStreamOpsTest`/`StreamingContentSessionTest`/`FileChunkDecryptorTest`). Both Drive schemes
  build green. ⚠ Actual playback (AVPlayer + resource loader) is framework-level → the P7 on-device
  pass, the same ceiling as P4 video + Android S4. Fleshes out `drive-streaming-preview.md` S7.

## 18. P6b as-built (2026-07-17) — Synced-Folders reframe (photo auto-backup, first slice)

- **The backup ENGINE was fully reusable — the win of the slice.** `SyncedFolderController`,
  `BackupSource`/`galleryBackupSource`, `MediaSourceProvider`, `TenshuBackupPreferences`,
  `BackupEnvironment`, and `FilesRepository.uploadForBackup` are all **commonMain** (the gate /
  cursor-advance / drain logic is already desktop-unit-tested). iOS did NOT reimplement the loop; it
  plugged a PhotoKit source into the shared, source-generic engine — exactly as Android/desktop plug
  in theirs.
- **New iOS code (all presentation-edge + framework glue):**
  - `IosMediaSourceProvider` (`:tenshu-sdk-ios` iosMain) — the PhotoKit `MediaSourceProvider`: one
    v1 bucket = the user library (`smartAlbumUserLibrary`); `listNewItems` fetches assets with a
    `creationDate > cursor` predicate oldest-first (cursor = creationDate epoch-millis); items carry
    a `phasset://<localIdentifier>` uri. `observeChanges` = `emptyFlow()` in v1 (the live change
    trigger is deferred — foreground catch-up + P6a wake drive drains, matching the honest "open the
    app to finish" iOS UX).
  - `IosPhotoLibrary` — resolves `phasset://` → `PHAsset` + reads the **original** resource bytes
    (`PHAssetResourceManager.requestDataForAssetResource`, iCloud-allowed).
  - `IosBackupEnvironment` — network via `NWPathMonitor` (satisfied + expensive → METERED), charging
    via `UIDevice.batteryState`.
  - `IosFilesRepositoryPlatformDelegate` now resolves a `phasset://` upload source to PhotoKit bytes
    + the asset's `creationDate` as the `File.CreatedAt` seed (a `file://` source unchanged). So a
    backed-up photo rides the SAME upload op as a picker upload — which since P3b stages + enqueues
    the sealed chunks on the background `URLSession`.
  - `DriveBackupSurface` — builds + `start()`s the controller over the iOS source in
    `DriveLocalRuntime.install()` (gates on the enabled pref, default OFF); exposes the Swift
    controls (`observeEnabled` / `setEnabled` / `observeStatusText` / `backupNow`). The P6a
    `runBackgroundRefresh` also nudges a `backupNow()` catch-up (the OS-wake-drives-backup path).
  - Swift `PhotoBackupSection` (Settings) — the "Back up my Photos" toggle (requests `PHPhotoLibrary`
    authorization on enable), a live status footer, and a foreground **"Back up now"** catch-up.
    `NSPhotoLibraryUsageDescription` added to `project.yml`.
- **Threat model:** T-B-04 (§8.12) — the new full-photo-library read consent surface; no new
  crypto/relay/protocol surface (photos ride the existing per-file op → P3b bg session → same
  ciphertext; T-B-01 holds). Opt-in, default OFF; the deferred change-observer means no background
  scan without an app wake.
- **Verified:** `IosPhotoLibraryTest` (iosSimulatorArm64, 2 green — the `phasset://` uri codec the
  source + delegate must agree on); the engine itself is covered cross-platform
  (`SyncedFolderControllerTest`, desktopTest). Both Drive schemes build green. ⚠ Everything
  behavioural — PhotoKit enumeration, `creationDate` cursoring, `PHAssetResourceManager` reads,
  consent, and the actual backup + BG wake against a real photo library — is framework-level →
  **on-device (P7)**, the largest such deferral on the track. No stored-row/wire change → HR#9 not
  triggered.
- **P6b tail (deferred):** the live `PHPhotoLibraryChangeObserver` auto-trigger, per-album bucket
  selection UI (v1 = whole library), and a temp-file-to-background-session handoff for very large
  videos (v1 reads the whole asset into memory like the D2a picker path).

## 19. P7 — on-device ship-pass runbook

P1–P6b are code-complete + build-green + unit/sim-verified, but every phase carried a
**framework-level deferral** that only a real device exercises. P7 is that device session — it is a
**verification + signing + distribution pass, not new feature code**. This section is the runbook:
what to sign, what to click through, and the exact acceptance signal per deferral. Consolidated from
the ⚠ notes in §§6–18 so the device session is a checklist, not an archaeology dig.

### 19.1 Signing + infra prerequisites (do first)

- [ ] **Real-device signing** under one Team ID: team-prefixed `$(AppIdentifierPrefix)app.tenshu.shared`
  Keychain group + `group.app.tenshu.shared` App Group (the ad-hoc-sim bare-group trick does NOT
  carry to hardware). See [`ios-signing.md`](../how-to/ios-signing.md); the sdk-gated auto-signing +
  `TenshuSharedKeychainGroup` Info.plist + headless `devicectl` flow already exist. ⚠ Free
  provisioning = **3-app device cap** + 7-day profiles — Drive + Control fit; a 4th app (Fit)
  displaces one. Paid App Groups capability removes the cap.
- [ ] **A dev relay reachable from the device** (not just the sim's host-loopback): the relay-authed
  chains (sharing, second-device) need it. `TENSHU_DEV_RELAY` env override exists (debug builds).

- [ ] ⚠⚠ **Start from a phone with no Tenshu app on it, and install all ten from ONE build at or
  after 1.0.1** (`ios-shared-store-sqlite.md` B.22.9). Since the 1.0.1 re-cut nothing removes an old
  file store: the launch reset (and its `[legacy-reset]` log line) is deleted, so a leftover
  `objects/` or `sync-hlc/` from a pre-1.0.1 build stays in the group container, and that
  `sync-hlc/` sits where the SQLite store keeps its live watermark. So on a test phone **delete
  every Tenshu app first** (the group container goes with the last one), then install every app from
  the same commit, **force-quit any that were running** (an old process survives an install until
  it is killed), and launch Control first, then the others. ⚠⚠ **Never install a build older than
  1.0.1 beside newer ones**: it would write the file store again and nothing reads or removes it.
  ⚠ Deleting the apps does **not** delete their Keychain identity: if the relay was reset too,
  Control comes back on the old device key and gets 401s. Wipe it as the reset runbook says
  ([`reset-and-commerce-test.md`](../how-to/reset-and-commerce-test.md), `--wipe-ios-identity`).
  Check that the group container holds `replica/` and no `objects/`
  (`xcrun devicectl device info files … --domain-type appGroupDataContainer
  --domain-identifier group.app.tenshu.shared`).

### 19.2 On-device functional matrix (the deferrals, per phase)

Each row is a framework path unverifiable off-device; the **Signal** column is the pass condition.

| Phase | What to exercise | Signal |
|---|---|---|
| **P3b** | Upload a large file, background the app mid-transfer, terminate it. | The `URLSession(.background)` transfer completes while suspended; app relaunches into `handleEventsForBackgroundURLSession`; the blob lands on the relay. Force an expired JWT → the **401 re-mint** re-enqueues. Kill mid-stage → `resumeIncomplete()` re-drives on next launch. |
| **P6a** | Leave Drive provisioned, background it, wait for an OS `BGAppRefresh` wake. | A real drain runs headless (sim gives `BGTaskSchedulerErrorDomain Code=1`). Confirm via console/relay push. |
| **S7** | Open a synced video; scrub/seek. | AVPlayer plays via the `AVAssetResourceLoaderDelegate`; a **seek** fetches a mid-file range (not a whole-file download); no whole-file temp appears. |
| **P4** | Open image / PDF / video previews; reopen after an app kill. | PDFKit + AVPlayer render; the reopen should now hit the **on-disk cache** rather than the relay — `IosFileDatabase` landed 2026-08-03, and this row is one of the two places to confirm the store→consumer chain end-to-end (`ios-fit-timeline-maps-parity.md` P4-2). |
| **P4e (list-row thumbnails)** | Upload an **image, a PDF, and a video** from the iPhone → look at the Files rows. Then open Files on a **second device** (Android/desktop) for the *same* realm without opening those files. | On the iPhone each row shows a **rendered thumbnail**, not the type glyph — including a **video poster frame** (`AVAssetImageGenerator`; not black/glyph). On the second, byte-less device the *same* rows show thumbnails too (the eager-synced `File.Thumbnail`/`ThumbnailLarge` resolved with no byte fetch) — this is the original "thumbnails don't sync from iOS" bug, now closed both ways. |
| **P4e (byte-less + scroll)** | Open a folder of many synced-but-not-downloaded files on iPhone; scroll fast. | Byte-less rows still show their **synced** thumbnail (stored-class fallback, no full-file fetch); scrolling stays smooth — the per-row `.task` loader is viewport-driven and **cancels on scroll-off**, so only visible rows fetch. Non-previewable rows (audio/archive/other) keep the glyph with no wasted `getPreview` call. |
| **P6b** | Grant photo access, toggle "Back up my Photos", add a photo, "Back up now"; then background for a wake. | PhotoKit enumerates; the `creationDate` cursor advances (no re-upload of old photos); `PHAssetResourceManager` reads the original bytes; the photo lands under `/Backup/<device>/All Photos` (→ P3b bg session). The consent prompt uses the `NSPhotoLibraryUsageDescription` copy. |
| **P1b/P2b** | Co-resident Control + Drive writing the shared replica; kill a writer mid-write. | SQLite's write lock serialises the writers (one transaction per access, `ios-shared-store-sqlite.md` §2.5); no torn row; the survivor reads the other's committed rows with no rehydrate (the file store's `flock` and rehydrate are gone, §11's note). |
| **P3a** | Real document-picker + photo-picker uploads. | Bytes + source mtime land a real row (the security-scope copy holds). |
| **P5** | Full sharing handshake on two devices: create invite → camera-QR scan → SAS → redeem → grant → accept. | The Manage-access people list converges; camera QR works on hardware. |
| **§23 (row overflow)** — ✅ **DONE 2026-07-20** (iPhone 17e) | Tap the row `···` in list and grid; tap the row body; tap the **dead space between filename and `···`**; repeat on a My-shares link row. | ✅ Menu opens without also navigating; row body **and the gap** both navigate (the §23.1 round-3 fix); grid overlay legible over a photo thumbnail. Remaining for the P7 sweep: confirm Copy ID pastes a **fragment-less** `/s/{shareId}`, and exercise nav depth (3+ folders, back) on the new `FilesRouter` path. |
| **§24 (search)** — placement ✅ 2026-07-23 (iPhone 17e, iOS 26) | Type a partial filename from a deep folder; check placement, responsiveness, and that a result tap opens the right file. | ✅ The search field appears where the OS puts it — and inside the `TabView` shell that is the **top nav-bar drawer on iOS 26 as well** (the bottom Liquid Glass field applies only without a tab bar, or via `Tab(role: .search)` — §24.2 as-observed note). Still pending for the P7 sweep: type-ahead keeps up on a real drive; a hit navigates to the correct file from any depth via `FilesRouter`; clearing the field restores the listing. |
| **cross-device** | Two real devices on the dev relay; mutate on one. | The relay round-trip converges the other device (not just two in-process runtimes). |

### 19.3 App Attest (separable — route-2/third-party only)

⚠ **Not on the route-1 first-party critical path.** Real Apple **App Attest** (arch doc phases
4b/6c/6d) hardens the **route-2 / third-party EPC** surface (a foreign app reaching Control over
EPC), which shipping Drive-on-iOS (route-1, in-process) does not use. It gates *shipping the EPC
surface*, not P1–P6b. The Phase-4a **software attestor** seam means swapping the real Apple verifier
in is no protocol change. Sequence it **after** 19.1/19.2 pass — it is the last gate before an
external TestFlight, not a blocker for internal on-device validation.

### 19.4 Distribution

- [ ] Archive + upload to **TestFlight** (internal testers) once 19.1–19.2 are green.
  The archive half exists: `scripts/ios-archive.sh`, runbook
  [`how-to/ios-testflight.md`](../how-to/ios-testflight.md). No build has been uploaded.
- [ ] Open decision to settle on-device: the **`FileProvider`** question (P4) — whether Drive
  exposes a Files.app provider extension, deferred since P4.

**Exit criteria:** every 19.2 row green on hardware + a TestFlight build installs and runs → the iOS
Drive parity track is shippable. App Attest (19.3) rides separately with the EPC/third-party surface.

## 20. Route-1 is the first-party pattern — Fit/Timeline/Maps belong here too (decided 2026-07-18)

§1 already draws the line ("Route 2 (EPC) remains correct for the *remote*-proxy case and third-party
apps; the same-device first-party data plane is route 1"). A support episode on **Fit-on-iOS** made it
worth stating as a **platform decision**, not a Drive-only observation, because Fit/Timeline/Maps are
still on route-2 EPC and inherit its costs.

**What went wrong on Fit (the concrete case).** Fit shows a permanent "Can't reach Tenshu Control"
banner regardless of Control's lock state. On-device diagnosis (iPhone 17e, `devicectl … --console`):
`[fit-autoconnect] presence token only — Control is not hosting`. Fit reaches Control **only** over a
live EPC channel (`FitLiveClientSession.isActive`), which requires Control to be *actively hosting* —
it publishes the `tenshu://epc/pair` invite **only while its "Connect an app" screen is on-screen**
(`ControlSurface.publishEpcInvite`, driven by `EpcAppPairingView` appear/disappear). Merely
installing/unlocking Control never satisfies it. This is the same class of failure §1/#746 already
found for Drive (EPC stalls once Control is backgrounded) — Fit just never got the route-1 migration
that fixed it for Drive. Full rationale + the rejected process-lifetime-host patch live in
[`fit-app.md`](fit-app.md) §11.8.

**The decision.** **EPC is the third-party connection protocol; every *first-party* app on iOS belongs
on route-1.** EPC's ceremony — QR/NFC + SAS, App Attest, Rei (`ios-proxy-architecture.md` §2) — exists
to admit apps we do *not* trust. Our own apps (same Team ID, co-signed, co-resident) should run their
own in-process proxy over the shared App-Group replica, needing no live Control channel — exactly what
P1–P6 productized for Drive. So:

- **Generalise `DriveLocalRuntime` → a first-party local runtime**, then light up **`FitLocalRuntime`
  / `TimelineLocalRuntime` / `MapsLocalRuntime`** on it. Control's proxy already hosts
  `:health-service` / `:place-service` / `:timeline-service` on iOS (all three apps moved onto it), so each app's in-process graph just installs *its* repos instead of Drive's — the P1a recipe
  (§6), differing only in the repo set. Build the shared runtime **once**; do not fork three bespoke
  ports.
- **EPC narrows to purely third-party** (a foreign app reaching someone's Control). That is where App
  Attest (§19.3) and Rei become load-bearing — and where the durable-per-app-reconnection-credential +
  relay-wake design (`ios-proxy-architecture.md` §3/§10) is the right investment, *not* a
  process-lifetime first-party invite host (rejected — [`fit-app.md`](fit-app.md) §11.8).

**Gating.** Route-1's clean form uses the shared App-Group replica → a paid Apple account (App Groups
aren't provisionable on a free personal team; [[project_ios_appgroup_fallback_free_signing]]). Moot for
shipping (App Store needs a paid account anyway). Free-tier dev interim for the un-migrated apps:
EPC-over-relay + honest banner copy ("open Control's *Connect an app* screen").

**⚠ OPEN QUESTION — key scope, decide before migrating Fit/Timeline/Maps.** Route-1 makes an app a
key-holding realm device, and today the hand-off (`RealmKeyMaterial` via `RealmKeyMaterialBridge`) is
**all-or-nothing**: it carries *realm-level* keys — `sharedVolumeKey` (decrypts **all** file content),
`sharedIdentityKey`, A13 — as one shared-Keychain blob, not app-scoped. So a route-1 Fit would hold
`sharedVolumeKey` and could **decrypt Drive's file bytes**. This collapses the app↔proxy least-privilege
boundary that EPC/Rei gave even to first-party apps, and does so asymmetrically vs Android (where a
separate proxy process mediates). The E2E guarantees are intact (relay sees ciphertext, keys only on
provisioned devices, A1 never leaves Control) — what's traded is inter-first-party-app least privilege.
Decision needed: does each first-party app get the whole realm blob (simple; full cross-app read), or
**scoped material** (per-service keys, or a metadata-only key for apps that never touch file content)?
Rationale + the platform-asymmetry framing: [`ios-proxy-architecture.md`](ios-proxy-architecture.md)
§0.5. Not blocking Drive (single first-party route-1 app today); blocking the *second*.

**Status.** Drive = route-1 (P1–P6b, this doc). Fit/Timeline/Maps = route-2 EPC, **migration not yet
scheduled** — this section is the standing rationale for when it is (gated on the key-scope decision
above). No code in this decision.

## Appendix — Implementation notes for Claude Code

P1a file-level starting points:

| Concern | Existing code to build on |
|---|---|
| Runtime assembly | `control-shared-proxy/src/iosMain/.../PlatformProxyModule.kt` (mirror, Drive flavour); `EpcDriveClient.assembleClient` (client-half wiring list) |
| Install seam | `tenshu-sdk-ios/.../DriveReadSurface.kt` (`installRealClient` twin → `installLocalRuntime`) |
| Shared replica | `core-common/src/iosSqliteMain/.../SharedReplicaStore.kt` (SQLite, since 2026-11-05; the P1a-era `FileObjectStore.kt` is deleted, `ios-shared-store-sqlite.md` B.22.9) |
| Keys/identity | `core-common/src/iosMain/.../RealmKeyMaterialBridge.kt`; `ControlSurface.publishRealmKeysToSharedKeychain()` (publisher side) |
| Writer lock | `FileAdvisoryLock` (core-common iosMain); `DatabaseLock` binding currently `NoOpDatabaseLock` in both iOS runtimes |
| Shared clock | `AppGroupProbe.tickSharedClock()` (probe shape → productize; the probe was deleted 2026-11-05, last version at `066f6f363`) |
| Relay client | `RelayBlockProbe`/`RelayAuthProbe` (probe shape, deleted 2026-11-05); production `RestSyncRelay` + `jwtTokenProvider` + `perRealmDeviceRelay` |
| Ingest | `SyncProcessorHolder` (iosMain PlatformProxyModule); `ControlSurface.pullApplyAndReadFolder` (probe shape, deleted 2026-11-05) |

Verification bar per slice: `:tenshu-sdk-ios:iosSimulatorArm64Test` + `xcodebuild` for both apps
(iOS is not CI-gated — run locally, `feedback_ios_xcodebuild_swift_slices`), plus the A/B sim
harness pattern (ad-hoc-signed, ERASED sim, dev `:sync-relay-server:run` where relay-touching).

## 21. As-built — P4e gap #1: iOS thumbnail generation (`IosGetPreviewImplementation`, 2026-07-18)

The first slice of **P4e** (§P4). Closes the *generation* half: before this, iOS wired only the
common `fileServiceProxyImplementationFactory`, which has no `GetPreview` arm, so
`UploadFileImplementation.renderThumbnail` (which dispatches `execute(GetPreview.ID, …)`) found no
impl → `File.Thumbnail`/`File.ThumbnailLarge` were never set. Consequences were two-directional:
photos backed up *from* the iPhone carried no synced thumbnail (nothing to sync outward), and a
thumbnail synced *to* iOS from Android/desktop couldn't be resolved for display (the resolver that
serves the stored class is the same `GetPreview`).

**New code (`:file-service-proxy` iosMain — the source set that didn't exist):**

- `IosGetPreviewImplementation` — the K/N twin of `JvmGetPreviewImplementation`, structurally
  line-for-line: same caller-shape handling (bare `File` at upload / `Entity<File>` on read), same
  LIST-class stored-thumbnail short-circuit for `≤100 px` Entity reads, same byte-less/stored-class
  selection (`storedThumbnail` via `ExecutionContext.resolveProperty` — value props aren't inline on
  the composite), same preview cache keying. The **only** platform-specific parts are the image
  pipeline: `UIImage.imageWithData` decode → `UIGraphicsImageRenderer` downscale (⚠ **`format.scale
  = 1.0`** — otherwise the renderer bakes the device scale ×2/×3 into the output and blows the
  thumbnail caps) → `UIImagePNGRepresentation` / `UIImageJPEGRepresentation(…, 0.8)` encode; and PDF
  page-0 via PDFKit `PDFDocument(data:).pageAtIndex(0).thumbnailOfSize(…, forBox:
  kPDFDisplayBoxCropBox)`.
- `iosFileServiceProxyImplementationFactory(id, fileDatabase, previewCache)` — maps `GetPreview.ID`
  → `IosGetPreviewImplementation(previewCache)`, else null (twin of the jvm/android factories).
- Wired in `control-shared-proxy` iosMain `PlatformProxyModule.platformImplementationFactory`,
  consulted **before** the common `fileServiceProxyImplementationFactory` (mirrors desktop's
  `jvmFileServiceProxyImplementationFactory` placement). One binding covers both apps; iOS already
  binds `InMemoryPreviewCache`.

**Scope / deferrals within the gap:**

- **Video-poster generation** was initially deferred (an `AVAssetImageGenerator`-over-temp-file
  path) — the `isVideo` branch shipped serving any *synced* poster (rendered by a device that can —
  Android) via `storedThumbnail`, like desktop/JVM. ✅ **Built shortly after (addendum 2 below)**;
  it mattered more on iOS because DRIVE is the photo/video backup source (§P6b), so the initial
  gap left iPhone-uploaded videos posterless everywhere.
- **No unit test.** Root `build.gradle.kts` deliberately disables iOS test compile/link/run for all
  `-proxy`/`-test-utils` modules (K/N test-link OOM avoidance; proxy-side iOS test-green deferred to
  a later phase). The shared decision logic is a line-for-line mirror of the JVM resolver covered by
  `JvmGetPreviewImplementationThumbnailFallbackTest`; the net-new UIKit/PDFKit rasterisation (esp.
  the `scale = 1` cap behaviour) is verified on-device in **P7**, consistent with the rest of the
  track's framework-level deferrals.
- **No threat-model change.** The stored thumbnail is the existing `File.Thumbnail`/`ThumbnailLarge`
  eager-block payload (A5 already amended for the low-res derivative at rest); iOS merely produces
  it where it previously produced nothing. No new stored-row/wire shape (HR#9 not triggered).

**Build-green:** `:file-service-proxy:compileKotlinIosSimulatorArm64`,
`:control-shared-proxy:compileKotlinIosSimulatorArm64`, `:tenshu-sdk-ios:compileKotlinIosSimulatorArm64`.

**Remaining for P4e:** gap #2 — the Swift **list-row thumbnail loader**. `drive-ios/Sources/
FilesView.swift` still renders `Image(systemName: file.previewIcon.sfSymbol)`; it needs a bounded
per-row async loader over `DrivePreviewSurface` (a new per-row thumbnail API), mirroring the
Android/desktop metadata-only-list + `previewLoader` pattern. Until it lands, iOS *generates* and
*syncs* thumbnails correctly but still shows glyphs in its own list.

### §21 addendum — P4e gap #2: Swift list-row thumbnail loader (2026-07-19)

The second slice completes P4e. With gap #1 the iPhone now *generates and syncs* thumbnails, but
`drive-ios/Sources/FilesView.swift`'s `FileRow` still rendered a static
`Image(systemName: file.previewIcon.sfSymbol)` glyph. This slice renders the thumbnail.

**New code:**

- `DrivePreviewSurface.loadRowThumbnail(path, maxPx): NSData?` (`:tenshu-sdk-ios`) — delegates to
  the shared `FilesRepository.getPreview(path, maxPx, maxPx)` (the *same* per-row op Android/desktop
  use) and returns the bytes as `NSData` (SKIE-bridged to Swift `Foundation.Data`). Unlike the
  full-open `loadBytes`/`writePreviewTempFile` path (multi-MB → temp file, deliberately kept off the
  SKIE byte bridge), a LIST thumbnail is a few KB, so it crosses as `NSData` exactly like S7's
  ≤256 KiB ranges. Null on any failure/no-thumbnail. Passing `maxPx = 100` hits the resolver's
  `≤100 px` Entity branch → serves the eager-synced `File.Thumbnail` (gap #1's iOS generator) with
  no full-byte read on a byte-less device.
- `FileIcon.isThumbnailable` (Swift, `FileIcon+Symbol.swift`) — `.image | .video | .pdf`; gates the
  fetch so non-previewable rows don't issue a wasted `getPreview` call.
- `FileRow` (`FilesView.swift`) — a `@State var thumbnail: UIImage?` filled by a `.task(id: file.path)`
  that calls `loadRowThumbnail` for non-folder thumbnailable rows and renders the `UIImage`
  (`.aspectRatio(.fill)` in a 28×28 rounded frame), falling back to the glyph. **Viewport-driven +
  auto-cancelling**: SwiftUI runs the row `.task` on appear and cancels it on scroll-off, so only
  visible rows fetch — the iOS twin of the Android/desktop per-row `previewLoader` (no explicit
  4-at-a-time semaphore needed: the proxy op executor already serialises `getPreview`, and the
  tasks are bounded to the visible window).

**Gotchas:** the SDK exports the Kotlin core `Data` type as `TenshuSdk.Data`, so `UIImage(data:)`
must disambiguate the bridged `NSData` return as `Foundation.Data` (bare `Data` is ambiguous).

**Scope:** at this point video rows still rendered a thumbnail only where a *synced* poster existed
(iOS didn't yet generate video posters — closed by addendum 2). No new op/wire/threat-model change
(reuses `getPreview`).

**Build-green:** `:tenshu-sdk-ios:compileKotlinIosSimulatorArm64` + `xcodebuild -scheme DriveIos
-destination generic/platform=iOS CODE_SIGNING_ALLOWED=NO` (BUILD SUCCEEDED, after
`assembleTenshuSdkDebugXCFramework`). Functional render (real thumbnails painting in rows) is the
P7 on-device pass, consistent with the rest of the track.

### §21 addendum 2 — video-poster generator (2026-07-19)

Closes the last P4e piece: the `IosGetPreviewImplementation` `isVideo` branch now **generates** a
poster frame instead of only serving a synced one, so a video uploaded from the iPhone carries its
own eager-synced poster (previously iPhone-uploaded videos were posterless on every device).

**Change (all in `IosGetPreviewImplementation`):** restructured the video path to mirror Android
rather than JVM — dropped the early `return storedThumbnail` for video, let the `≤100 px` Entity
short-circuit fall through for video (not just image/PDF), and added `extractVideoPoster(bytes,
fileName, maxEdge)` to the generation `when`. It writes the bytes to an app-private temp file (leaf
extension preserved from `fileName` so AVFoundation infers the container), builds an `AVURLAsset` +
`AVAssetImageGenerator` (`appliesPreferredTrackTransform = true`, `maximumSize` bounded to decode
straight to ≈`maxEdge`), grabs a frame ~1 s in (or the clip midpoint) via the synchronous
`copyCGImageAtTime`, wraps the `CGImage` in a `UIImage` (`CGImageRelease`s the +1-retained copy),
deletes the temp file, and runs the result through the same downscale/encode path as images/PDFs.
On any failure it falls back to a stored poster — identical best-effort contract to the other
branches. **Deferral resolved:** the two remaining thumbnail classes (LIST + LARGE) are both
generated for videos at upload now, exactly like photos.

⚠ `AVAssetImageGenerator.appliesPreferredTrackTransform` / `.maximumSize` are member properties, not
importable extensions (unlike `AVAsset.duration`). This slice shipped on the synchronous
`copyCGImageAtTime`, deprecated on iOS 16+ — swapped to the async replacement in addendum 3.

**Build-green:** `:file-service-proxy` + `:tenshu-sdk-ios` `compileKotlinIosSimulatorArm64`, and the
shipping `assembleTenshuSdkDebugXCFramework` links (AVFoundation/CoreMedia are default K/N system
frameworks). No Swift change — gap #2's `FileRow` already renders whatever `getPreview` returns for
`.video` rows. Functional poster extraction → P7 on-device.

**P4e is COMPLETE end-to-end** — generate (images/PDF/**video**) → sync → render in the row.

### §21 addendum 3 — poster generator off the deprecated sync API (2026-07-19)

The addendum-2 follow-up: `extractVideoPoster` grabbed the frame via the synchronous
`copyCGImageAtTime`, deprecated on iOS 16+. Swapped to `generateCGImageAsynchronouslyForTime` (the
iOS-16 replacement; deployment target is 16.0, so it's available on every supported OS). Because
`GetPreview` is a synchronous op (HR#3), the async generator is bridged back to the op-executor
thread with a **dispatch semaphore**: the completion handler `CGImageRetain`s the frame (the passed
`CGImage` is framework-owned, so it must outlive the callback) and signals; the thread waits on
`DISPATCH_TIME_FOREVER`, then wraps the retained image in a `UIImage` and `CGImageRelease`s it. The
semaphore wait is a happens-before barrier, so the handler's write to the captured `var` is visible.
No deadlock risk — AVFoundation runs the handler on its own queue, never the blocked thread — and the
threading posture is unchanged (the old sync call blocked the same thread; `getPreview` never runs on
main here — upload is off-main, list rows call through `Dispatchers.Default`). `dispatch_semaphore_*`
+ `DISPATCH_TIME_FOREVER` come from `platform.darwin`; `CGImageRetain`/`CGImageRef` from CoreGraphics.
Build-green (`:file-service-proxy` + `:tenshu-sdk-ios` klib + XCFramework link); no more deprecated-API
usage. **No iOS thumbnail work remains.**

## 22. Share-action discoverability — row context menu + root-folder access (2026-07-19)

**Problem (maintainer report).** The sharing *cluster* was fully built (P4/P5) and bound to the real
ViewModels, but its **entry points were not iOS-idiomatic and were near-undiscoverable**:

- Mode-A **Share Link** lived only inside `FileDetailView`, itself reached via `tap file → preview →
  ⓘ → detail` — a primary action three levels deep behind an *info* button. On iOS the ⓘ is a
  metadata affordance (Files, Photos), never the action surface.
- Mode-B **Manage Access** lived only on the `FilesView` toolbar `person.2` button, which renders
  only when `!isRoot` — so a folder sitting at the realm root could not be shared at all without
  first navigating *into* it.
- The `SharedView` tab is the inbound/outbound *listing*, not a place to *initiate* a share, which
  compounded the "there's no share button" impression.

Neither Apple Files (long-press context menu → Share; selection → bottom Share icon) nor Google
Drive (per-row `⋮` overflow → Share / Manage access / Copy link) hides sharing behind Get-Info.

**Fix (UI-only; the ViewModels already existed).** Added a per-row **`.contextMenu`** to `FilesView`
— the iOS-idiomatic counterpart of Android's `FileActionsBottomSheet` (`drive-shared-ui`):

- **Files:** *Share Link* (Mode-A) · Rename · Move · Delete.
- **Folders:** *Manage Access* (Mode-B) · Rename · Move · Delete.

Because *Manage Access* is now on every folder **row**, a root-level folder is shareable without
entering it — closing the `!isRoot` gap. The in-folder `person.2` toolbar button is kept (it shares
"the folder I'm currently in"); the root case correctly has none (the realm root itself is not a
shareable folder). The `FileDetailView` *Share Link* button is left in place as a harmless secondary.

Wiring reuses the existing self-contained sheets (`CreateShareLinkSheet` / `ManageAccessView` /
`RenameSheet` / `MovePickerView`) via a single `.sheet(item: RowSheet)`; Delete goes through a
confirm `.alert`. ⚠ **Superseded in part by §23 (2026-07-20):** the context menu is no longer the
*only* per-row surface — a visible overflow `Menu` renders the same `rowMenu`, and the claim below
that the `FileDetailView` button is "a harmless secondary" did not hold (folder Rename/Move/Delete
had no visible route at all). To fire a one-shot delete without spinning up the detail-streaming
`FileDetailsViewModel`, added **`DriveDetailSurface.actions(path:isFolder:)`** — an actions-only
builder (`FileActionsViewModel` over the installed repo + offline/sync-rules seams); `FilesModel`
retains the last VM so its `viewModelScope` delete coroutine outlives the menu tap.

**Deliberately left out of the menu:** the offline pin toggle (its label depends on live per-row
`isAvailableOffline` state that a static context menu can't cheaply carry — it stays in Details,
one tap away) and system share-sheet export (D3, already on the preview `ShareLink`).

Build-green: `:tenshu-sdk-ios` klib + XCFramework, `DriveIos` simulator build. On-device long-press +
live share round-trip fold into the P7 pass (§19), alongside the already-listed Mode-B grant E2E.

---

## 23. Visible row overflow — item actions off long-press alone (2026-07-20)

**Problem (maintainer report).** Raised as "the links under *My Shares* on iOS miss the *Copy ID*
and *Revoke* buttons Android has". Investigating it turned up a bigger, already-shipped version of
the same defect in the Files tab.

The literal report was half right: on `SharedView` **Revoke existed** (a trailing `.swipeActions`)
but **Copy ID did not**, so the row had no way to identify a share at all. The maintainer's follow-up
question — *is long-press actually a well-known pattern?* — is the load-bearing one, and the answer
splits:

- Context menus are **not new** (iOS 13, 2019; Peek and Pop from iOS 9 before that) and are
  thoroughly conventional. "Newer pattern" is not the problem.
- **Discoverability is.** They carry no visual affordance, which is why Apple's guidance treats a
  context menu as an *accelerator* rather than the sole route to a function. §22 shipped the Files
  item actions as a context menu **only**, on the reading that the ⓘ → `FileDetailView` chain was
  "a harmless secondary".

**That reading was wrong, and §22 is superseded on this point.** The ⓘ chain is three levels deep
(`row → preview → ⓘ → detail`) and is routed for **files only** — `FileDetailView` accepts
`isFolder` but `DriveIosApp` only routes it for `FileRoute`. A folder row's tap pushes a child
`FilesView`, whose toolbar recovers *Manage Access* alone. Net effect: **a user who never
long-pressed could not rename, move, or delete a folder anywhere in the app.**

Compounding it, the Sort-menu + list/grid toolbar shipped a **visible `ellipsis.circle`** holding view/sort
options only. A user scanning for an overflow menu found one — with none of the item actions in it —
which actively taught "there is no menu here", the exact impression §22 set out to fix.

**Fix (UI-only; no new ViewModel surface).**

- **`FilesView.overflowButton(_:overThumbnail:)`** — a visible `Menu` rendering the *same*
  `rowMenu(_:)` as the context menu, so the two cannot drift. Trailing-edge in the list; overlaid on
  the tile's top-right corner in the grid. Glyph is the **bare `ellipsis`** in rows (the circled
  variant reads as a chunky primary control beside a filename); the grid keeps
  `ellipsis.circle.fill`, where the filled disc earns its place as legibility backing over arbitrary
  thumbnail content. 44pt hit target via `.frame` + `.contentShape`, drawn glyph unchanged.
- **`.contextMenu` retained** on both layouts as the accelerator.
- **No disclosure chevron** — rows navigate through a new `FilesRouter` (§23.1).
- **Toolbar glyph disambiguated**: view options moved `ellipsis.circle` →
  `line.3.horizontal.decrease.circle`, since `ellipsis.circle` now means "actions for this item" on
  every row. `accessibilityIdentifier("view-options")` kept, so existing UI tests are unaffected.
- **`SharedView` My-shares rows** get the same shape: `shareMenu(_:)` (Copy ID · Revoke) rendered by
  a visible `shareOverflowButton` **and** a `.contextMenu`, with the destructive-only swipe kept.

**Two defects fixed in passing.**

1. The row subtitle rendered `row.revoked ? "Revoked" : "Active"` — **dead copy**:
   `TenshuFilesRepository.shares()` already filters revoked rows out, so the "Revoked" branch was
   unreachable. Now shows `row.filePath`, matching Android's `MyShareRowItem`.
2. Android hand-rolled the fragment-less copy URL inline in `SharedScreen.kt`. Since *dropping* the
   `#k=` fragment is the load-bearing A11 privacy behaviour, duplicating that construction in Swift
   would have put the rule in two files no test ties together. Added
   **`ShareLinkUrl.pathOnly(shareId)`** in `:drive-shared-logic` (automatically SKIE-visible); both
   platforms now call it, and `ShareLinkUrlPathOnlyTest` pins the no-fragment property plus the
   "path-only == capability URL minus fragment" invariant.

**Deliberately unchanged.** The offline pin toggle and share-sheet export stay out of the menu for
§22's original reasons. `FileDetailView` keeps its action buttons — now genuinely secondary, since
every action has a one-tap route from the row.

Green: `:drive-shared-logic:desktopTest` (incl. the new test) + `:drive-shared-ui:desktopTest`,
`:tenshu-sdk-ios` debug XCFramework, `DriveIos` simulator + **signed device** build.
**Visually verified on a real iPhone 17e** over four install-and-look rounds with the maintainer —
which is the whole story of §23.1 below.

---

### 23.1 The chevron, and three bugs a simulator build could not have shown

The first device install looked wrong immediately: *"having the `›` next to the `···` looks
awkward."* Chasing that produced the section's real content — and a caution about which Apple
guidance applies to this screen.

**What Apple documents.** UIKit rows compose *multiple* trailing accessories —
`cell.accessories = [.disclosureIndicator(), .customView(configuration:)]` — and a custom accessory
defaults to sitting **inboard** of the disclosure indicator. So UIKit's answer to "this row
navigates *and* carries a per-row action" is `[content] [···] [›]`, both present, framework-spaced.
(The old `UITableViewCell.detailDisclosureButton` — ⓘ + chevron, with its own
`accessoryButtonTappedForRowWithIndexPath:` — is the same idea.)

**What SwiftUI can actually do: none of that.** `List` has **no accessory API**. The chevron comes
only from `NavigationLink`, and nothing can be placed as its sibling — Apple's own forums confirm
there is no way to customise a `NavigationLink`'s accessory. Worse, the *ordering* is unreachable:
the chevron belongs to the link, so any control added outside it lands **outboard**, giving
`[content] [›] [···]` — inverted from UIKit's own default. That inversion was the awkwardness.

**Which guidance applies (the correction that settled it).** The "compose both accessories" model
comes from the **settings/hierarchy** list idiom (Settings, Mail accounts), where a row is a *route
to* something. A file browser is a **content browser** — the row *is* the object, and "tapping opens
it" is the screen's premise, so the indicator is redundant. Apple's own Files.app carries **no
chevron at all**, as do Photos albums and Music; so do OneDrive, Google Drive and Dropbox. Apple
documents what the chevron *means* ("possible to drill down deeper") but never writes this
distinction down — it is read off the apps, not the HIG. ⚠ **Lesson for the next slice:** Apple
documents the settings-list idiom in far more detail than the content-browser one, so for
Drive-shaped screens the apps are the better reference than the guidelines.

⇒ **Chevron dropped.** Since SwiftUI ties the chevron to `NavigationLink`, rows became `Button`s
over a new **`FilesRouter`** (an `ObservableObject` holding the Files tab's `NavigationPath`, owned
by `PairedAppView` and published into the environment, so child `FilesView`s inherit it at any
depth without threading a binding through every hop). `navigationDestination` stays value-based and
unchanged.

**The trap that follows, and cost three rounds to clear.** Leaving `NavigationLink` silently
transfers three duties SwiftUI had been doing for free — each surfaced only by touching the device:

| Round | Symptom | Cause |
|---|---|---|
| 1 | `›` beside `···` looks wrong | the chevron itself — `NavigationLink` renders it, unsuppressable |
| 2 | `···` floats mid-row, not right-aligned | `Button`'s label sizes to its content; needs `.frame(maxWidth: .infinity, alignment: .leading)` |
| 3 | only the text and the `···` respond; **the gap between them is dead** | `.frame` expands *layout* bounds, not the *hit* region. `.contentShape(Rectangle())` fixes it — but **must be inside the label**; outside the `Button` it shapes the button's outer boundary and the middle stays dead |

A `NavigationLink` row — and a UIKit `UICollectionViewListCell`, which is why Files.app has no such
gap — gets alignment and row-wide hit-testing by construction. Owning them is the price of dropping
the chevron. All three are commented at their call sites; anyone re-touching this row inherits the
same trap.

⚠ Note none of the three is reachable from a Kotlin test, a simulator build, or `xcodebuild` — all
three compiled and passed clean while looking wrong on hardware. This is the concrete argument for
the §19 device pass, and the reason the P7 matrix row for §23 is now marked verified rather than
pending.

---

## 24. Search on iOS — the last real feature gap (planned 2026-07-20, not built)

Opened by the §2 correction: search is **not** a cross-platform stub, it is an **iOS-only gap**.
Android and desktop have shipped it; iOS has no search surface at all (zero
`search`/`Searchable` hits across `drive-ios/Sources`, no `DriveSearchSurface`).

### 24.1 Why this is small

The whole logic layer is already portable and already compiles for iOS:

| Piece | Where | iOS-ready? |
|---|---|---|
| `FilesSearchBarViewModel` (debounce 250 ms → `distinctUntilChanged` → `flatMapLatest`) | `:drive-shared-logic` **commonMain** | ✅ |
| `FileSearchBarUiState(files, query, isActive)` | `:drive-shared-logic` commonMain | ✅ |
| `FilesRepository.searchFiles(query): Flow<List<FileModel>>` | commonMain, real impl in `TenshuFilesRepository` | ✅ (route-1 runtime already installs the real repo) |
| `Contains` op + proxy executor | `:core` / `:core-common`, registered | ✅ |

So this is the standard **"share the view models, write the UI native"** shape (§9.3) — a surface
seam plus a SwiftUI view. No new op, no schema change, no wire change ⇒ **HR#9 not triggered**.

### 24.2 UI shape — `.searchable`, default placement

Unlike §23's row overflow (where SwiftUI had *no* accessory API and the row had to be hand-rolled),
search has a first-class documented modifier that produces the Files.app behaviour directly:
`.searchable()` + the system's full-screen search presentation.

⚖️ **Decision: take `.automatic` placement — do not pin it.** The rendering is version-dependent and
that is *desirable*:

- **iOS 16–18**: resolves to `.navigationBarDrawer` — field beneath the navigation title, scrolls
  away with content (this is the Files.app placement the maintainer observed).
- **iOS 26+**: Apple moved it into a Liquid Glass container at the **bottom** of the screen on
  iPhone (reachability), top-trailing on iPad, with `searchToolbarBehavior(.minimize)` to collapse
  it to a toolbar button.

Deployment target is **iOS 16.0** (`drive-ios/project.yml:14`), so the app spans both. Letting the
default track the OS is the same call as §23.1's chevron: follow the platform's current idiom rather
than pinning a look. ⚠ The iOS 26 specifics above come from secondary sources — Apple's own pages
are JS-rendered and unfetchable from the sandbox. **Confirm against Xcode's docs before building**,
and check what Files.app does on the target device.

**As-observed (2026-07-23, iPhone 17e / iOS 26, Xcode 26.6 / SDK 26.5 build): the field renders in
the top nav-bar drawer — and that is the correct OS default, not an integration bug.** The
secondary-source claim above missed a qualifier: the bottom Liquid Glass field applies to a plain
`NavigationStack`; Drive's Files screen sits inside the two-tab `TabView` shell (`PairedAppView`),
and with the tab bar owning the bottom edge, `.automatic` resolves to the nav-bar drawer on iOS 26
too. The iOS 26 idiom for bottom search in a *tabbed* app is the dedicated `Tab(role: .search)`
(the tab bar morphs into the search field) — deliberately not what Drive wants, since search is
offered inside the Files tab at every folder depth rather than as its own destination. Maintainer
verdict: current placement looks right; keep `.automatic`, no pin needed.

**Deferred upgrade path (P7-polish candidate, not scheduled):** adopt `Tab(role: .search)` as a
third tab if Drive ever wants the full iOS 26 idiom (Music / App Store / Photos shape). It is a
restructure, not a placement flag: search becomes its own destination with its own
`NavigationStack` (result-tap routing + preview/detail `navigationDestination` wiring duplicated or
shared across tabs), and the `Tab` builder API is iOS 18+ while the deployment target is 16.0 — so
`PairedAppView` would need two conditional tab-shell variants (`Tab` syntax and legacy `.tabItem`
don't mix in one builder). Natural trigger: raising the deployment target to 18+, which halves the
branching.

### 24.3 Slice contents

1. **`DriveSearchSurface`** (`:tenshu-sdk-ios` iosMain) — `search(): FilesSearchBarViewModel` over
   the installed repo, mirroring `DriveShareSurface.sharedWithMe()`. Add a repo-injecting test
   overload, as the sibling surfaces do.
2. **Swift**: `.searchable(text:)` on the Files root + a results list reusing the existing row
   presentation. Bind `onQueryChanged`; observe `uiState` via the established
   `@MainActor ObservableObject` + `for await` pattern.
3. **Result tap** routes through the §23 `FilesRouter` — folder → `FolderRoute`, file → `FileRoute`
   — matching Android's folder/file split (`HomeScreen.kt:146-149`).
4. **Row actions**: results reuse §23's `rowMenu`, so a search hit gets the same overflow menu.
   ⚠ Verify the overflow behaves inside the search presentation (a different container).
5. **Tests**: Kotlin surface test (`DriveSearchSurfaceTest`, sibling of `DriveLocalRuntimeTest`) +
   `xcodebuild`. Per [[feedback_ios_xcodebuild_swift_slices]] the Kotlin test alone does not compile
   the SwiftUI.
6. **Device pass**: type-ahead responsiveness on a real drive, and confirm which placement the OS
   picks. Folds into P7 (§19.2).

### 24.4 Deliberately out of scope

The **projection leak in `RecursiveListWalk`** (`FilesRepository.searchFiles` KDoc): every node
materialises all stored value properties including `File.Thumbnail`, matched or not, making the walk
~5–20 µs/node against ~0.1 µs for the predicate. It is a `:core-common` fix that benefits **every**
platform's search (and any other `traverse()` caller), so it does not belong in an iOS UI slice —
its own PR, sequenced independently. iOS search ships fine without it at realistic drive sizes.

Likewise: no index. It only pays above ~10k–50k files, and the resident-set heap ceiling
(`bounded-store-cache.md`) is reached first.

---

## 24.5 As-built (2026-07-20) — iOS search shipped

Built as planned in §24.1–§24.4; no deviation from the plan's shape. Total surface: **one Kotlin
object, one SwiftUI model, one results list**. No new op, schema or wire change ⇒ HR#9 not
triggered.

**Kotlin (`:tenshu-sdk-ios` iosMain).** `DriveSearchSurface.search()` binds the installed repo,
with a repo-injecting overload for tests — same shape as `DriveShareSurface`/`DriveDetailSurface`.
That is the entire Kotlin delta: `FilesSearchBarViewModel`, `FileSearchBarUiState` and
`FilesRepository.searchFiles` are all `commonMain` and were already compiling for iOS.

**`FakeFilesRepository.searchFiles` implemented** (it had been inheriting the interface default,
`flowOf(emptyList())`). That fake backs SwiftUI previews and pre-runtime bring-up, where a search
bar that always returns nothing looks like a broken feature rather than an unconfigured fake. It
mirrors production semantics — case-insensitive match on the **name**, not the path — and is
reactive over the listing so mutations show up in an open search.

**SwiftUI (`drive-ios`).** `SearchModel` bridges the VM; `FilesView` gains `.searchable` plus a
`searchResults` list that swaps in while a query is active.

- **Placement is the default**, per the §24.2 decision — drawer under the title on iOS 16–18, the
  bottom Liquid Glass field on iOS 26+.
- **Offered at every folder level**, not just the root: search is drive-wide, so scoping the
  affordance to the root would imply a folder-scoped search that does not exist.
- **Results reuse the listing's row treatment wholesale** — same `FileRow`, same `rowButton`
  routing through §23's `FilesRouter`, same `overflowButton` and context menu — so a hit behaves
  exactly as that file does in its folder. Always a list, never the grid: results span folders, and
  a thumbnail wall without parent context reads as a pile of unrelated tiles.
- **Search owns the empty state while active.** The listing's own "No files yet" would otherwise
  claim an empty drive over a full one; a no-match query now reads "No files matching *x*".
- **`isSearching` is derived from the query**, not `@Environment(\.isSearching)` — the latter is
  only readable from a *child* of the searchable view, and an empty query has nothing to show
  regardless. Clearing the field calls `onActiveChanged(false)` so the VM resets.
- **No Swift-side debouncing.** The VM's 250 ms window is the single source of that behaviour.

**Verified:** `DriveSearchSurfaceTest` 4/4 on `iosSimulatorArm64` — case-insensitive cross-folder
match, blank query yields nothing (not the whole drive), the keystroke burst collapses to one scan,
and dismissal resets. Test results confirmed via the JUnit XML, not a green build line. Signed
device build + install on an iPhone 17e.

⚠ **Still to check on device:** which placement iOS 26 actually picks (the §24.2 caveat — the iOS 26
behaviour was sourced from secondary material, since Apple's docs are JS-rendered and unfetchable);
type-ahead responsiveness against a real drive now that the traverse walk no longer does per-node blob
materialisation; and that a result tap lands on the right file from a deep folder. Folds into the P7
matrix (§19.2).

## 25. As-built (2026-07-30) — the provisioning could be lost with no way back

Found chasing a **permanent** "Can't reach Tenshu Control" banner in Fit on a real iPhone. The
assumption going in was a stale install; it was not. Rebuilding and reinstalling all three apps
from current master changed nothing, and the device then said exactly why:

```
[fit-autoconnect] no epc-session item readable (group FAYT82GWD7.app.tenshu.shared)
```

That line only prints on the **route-2 fallback**, which `FitShippingRoot.connect()` reaches only
when `FitLocalRuntime.isProvisioned()` is false — so **both** shared-Keychain items were gone: the
`epc-session` presence token *and* the `RealmKeyMaterial` blob. Not a Fit-side defect: all three
signed bundles carry byte-identical `keychain-access-groups` (`FAYT82GWD7.app.tenshu.shared`), and
Drive's own `Documents/objects` had no writes since its previous install despite being launched —
Drive was in the same de-provisioned state, just silent about it.

⚠⚠ **The state was unrecoverable by construction.** `provisionDriveSession()` — the only thing
that *creates* the provisioning — is called from genesis (`OnboardingView`), recovery
(`RecoveryView`), and a `DEV_HARNESS`-only probe. The unlock hook deliberately called
`refreshDriveKeysIfProvisioned()`, documented refresh-only ("publishing here must never *create*
the provisioning"). But the items are cleared by every identity-destroying event
(`clearDriveSession()` / `wipeDeviceState()`), and an already-onboarded Control never revisits
onboarding — so once they were gone, nothing in a shipping build could put them back. There is no
manual escape either: **Control-on-iOS has no "Connect an app" host** (its Apps tab is a read-only
list), which is also what the Fit banner told the user to open.

**Fix — ensure, don't refresh.** `ControlSurface.ensureDriveProvisioned()` publishes the material
whenever this device reaches an unlocked realm, creating it when absent, and mints the presence
token **only into an empty slot** so a live pairing invite from a hosting Control is never
clobbered. Called from two places, because either can be the moment the realm opens: the
`LockedView.onUnlocked` hook (sealed device) and the `probe()` `"active"` branch (a cold start that
lands `.ready` outright never renders `LockedView`). Once per process — the keys stay in memory for
the session, so a second call would only rewrite the same items.

> ⚠ **2026-11-05 corrections.** (1) Control-on-iOS *does* have a "Connect an app" host
> (Settings → `EpcAppPairingView`). It pairs an app over EPC and has never republished the route-1
> material, so the fix's reasoning stands. (2) `provisionDriveSession()` and the presence token are
> deleted. `ensureDriveProvisioned()` publishes only the key material and is now the onboarding
> call too ([`ios-first-party-route-1-migration.md`](ios-first-party-route-1-migration.md) §8).

**Why creating here is not a custody widening:** the material is the same blob the onboarding
handoff writes; on iOS route-1 first-party apps are realm co-owners by decision
(`ios-first-party-route-1-migration.md` P0 → C); and the trigger — *this* device's realm being
unlocked — is strictly narrower than "any app on the device". Teardown is unchanged: sign-out /
wipe still clears, and a Control with no realm never reaches the call. No threat-model delta (A4's
custody note already covers the shared-Keychain hand-off).

Banner copy fixed in the same pass: it now names unlocking Control, which is the thing that
actually lights Fit up, instead of a screen iOS does not have.

⚠ **Unrelated finding, not fixed here:** that iPhone's Control store carries `.formatversion` = 15
while master is at 16 (bumped the same day by the P4d place-merge change), with the pre-bump blobs still in place — the
`FileObjectStore.open` reset gate did not fire. Worth its own look; it is the silent stale-format
class HR #9 exists for.

## 26. As-built (2026-08-05) — the file chain outlives the file it addresses

Reported as a small navigation bug: deleting from the detail screen "jumps back to the detail view
of the deleted item" and shows *"Couldn't load this file"*. The screen it lands on is actually the
**preview**, and the cause generalises past delete.

⚠ **A tapped file is TWO hops, and both carry the path.** `FileRoute` → `FilePreviewView`, then the
preview's ⓘ → `FileDetailRoute` → `FileDetailView`. So `dismiss()` on the detail screen — one hop —
lands on the deleted file's own preview, which then fails its byte fetch and reports it. The fix is
a `FilesRouter.popToFolder()` that unwinds both; the hop count lives on the router next to the
route declarations, since `NavigationPath` is opaque and it cannot be derived at the call site.

**The same defect had three faces, because a path is a file's identity.** *Delete* removes the
path; *move* changes it; and — the non-obvious one — so does **rename**, which is a `MoveFile`
under the hood (`file-content-addressing.md` §3.3.1 kept the converging path identity and deleted
`File.fileId`). Move already used `dismiss()` and had the identical dead-path landing. Rename had
no exit at all, so it left the detail screen *and* the preview beneath it addressing an entry that
no longer existed: every subsequent action there — delete, move, share link, offline pin — would
have missed silently.

⚖️ **Rename re-addresses rather than leaves.** Delete and move are departures; a rename is not, and
popping the user out of a file they only renamed would be a worse answer than the bug. So the shell
rebuilds the two hops on the new path (`retargetFileChain`) and the user stays put.

⚠⚠ **It must fire on the COMMIT, not the tap.** Re-addressing optimistically points the preview at
a path the move has not created yet — the same dead-path read by a different route, and a
permanent one, since the preview's byte fetch is one-shot. That needed a completion signal, which
rename did not have: `RenameFileDialogViewModel` was fire-and-forget while its sibling
`MovePickerViewModel` already exposed `moved`. Added a committed-new-path signal, and made
`FilesRepository.renameFile` return the resulting **new path** rather than `Unit`. Compose hosts
ignore both (their listings are reactive); no schema, wire, or format change.

⚠ **Correction (§27):** the first version of this section justified that return type as *"computing
it caller-side would duplicate the join rule the implementation owns"*. That is weak, and it was
challenged in review: a rename never dedupes, so the path is fully derivable from the arguments,
and both halves live in the same module. What the caller genuinely lacks is **whether the write
happened** — see §27, which puts the outcome on the ui state and makes the path the payload of its
success case.

⚠ The sheet cannot do the waiting itself — it dismisses on confirm and its `@StateObject` dies
while the rename is still in flight on the VM's `viewModelScope`. It hands the VM up to the detail
screen instead, the same ownership fix `FilesModel.lastActionsVm` makes for delete.

⚠ **The iOS fake was hiding this.** `FakeFilesRepository.renameFile` only *recorded* the call, so
the old path kept resolving in every test and preview — the fake modelled a rename as a relabel
when the real repository re-keys the row. It now re-keys the listing entry, which is what lets the
XCUITests (`test_delete_from_detail_returns_to_the_folder`,
`test_rename_from_detail_retargets_the_chain`) reproduce both bugs: each was confirmed to fail with
the fix reverted.

## 27. As-built (2026-08-05) — a refused rename was silent on every surface but the web

Fell out of reviewing §26: it observed only the **success** half of the rename outcome, which
prompted the question *"what is our error behaviour in the first place?"* — and the answer was
that there wasn't one.

`MoveFileImplementation` hard-rejects a target the parent already holds (no overwrite, no dedupe),
`TenshuFilesRepository` maps it to `RepositoryError.AlreadyExists` — and then
`RenameFileDialogViewModel` **discarded the result**. So on Android, desktop and iOS a collision
closed the dialog and changed nothing, with no message. ⚠ **Web was the only surface that got this
right** (`FileBrowser.onRename` → *"Couldn't rename — … may already exist."*), which is an argument
for the TS rebuild rather than against it: the KMP path had a state the shared view model could not
express, so no UI over it could report one.

Rename was also the odd one out among its own siblings — `CreateFolderDialogViewModel` has an
error channel *and* a `keepBoth()`; `MovePickerViewModel` has `errorMessage` in its ui state. Only
rename threw the answer away.

**Fix — one ui state, mirroring the move picker** (the sibling with the same act-then-close shape):
`newName` / `canRename` / `isRenaming` / `renamedPath` / `errorMessage`, the last as a **stable
token** (`ERROR_ALREADY_EXISTS`, `ERROR_FAILED`) that each screen resolves to localised copy per
ADR 0016 — not the freetext the create-folder VM still carries in English. Both dialogs now close
on the **commit** rather than the tap, and render the refusal against the name field.

⚠⚠ **`canRename` is not cosmetic.** The field is seeded with the file's current name, and `MoveFile`
rejects a target that exists — including the file's *own* path. Submitting an unedited name would
therefore have reported "already exists" about the file itself, which is nonsense the moment the
error becomes visible; the bug was invisible only because the refusal was. So an unchanged (or
blank) name is unsubmittable, exactly as the move picker disables *Move Here* over its own no-op
destination, and the guard lives in the view model rather than in each UI.

⚠ **The fake had to learn to refuse** — `FakeFilesRepository.renameFile` always succeeded, so the
refusal path was unreachable in tests and previews. It now rejects a target already in the listing,
which is what lets `test_rename_onto_an_existing_name_is_reported` (confirmed failing against the
old dismiss-on-tap sheet) exist at all. Same lesson as §26's re-key: **a fake that models only the
happy path hides the branch the UI was missing.**

### §27.1 — and a `/` is rejected, because the name is path structure

Landed in the same PR once the channel existed. `renameFile` did not sanitise, so typing
`sub/b.txt` **silently turned a rename into a move** — and into a folder that need not exist:
`MoveFileImplementation` validates only that the *target* path is free, never that the target's
**parent** has a row, so the file would be re-keyed somewhere reachable by path and listed in
nothing.

⚖️ **Who allows what, and why the split is not arbitrary:** Dropbox, OneDrive and Windows Explorer
reject the separator with a message; macOS Finder accepts it and stores `:`; Google Drive allows it
outright. The dividing line is whether a *name* is a path component. Google Drive can afford `/`
because a Drive name is a label on an ID; **Tenshu's path is the identity itself**
(`file-content-addressing.md` §3.3.1 kept the converging path identity and deleted `File.fileId`),
so the Google answer is not available to us and the Dropbox one is the fit.

⚠ It is **client-side validation, not a repository outcome** — a third token
(`ERROR_NAME_HAS_SEPARATOR`) on the same channel, reported *as the name is typed* rather than on
submit, because `canRename` already disables the button and a submit-time message would be
unreachable. Hence `onNewNameChanged` **recomputes** `errorMessage` rather than merely clearing it.

Deliberately not done: no other character is rejected (only `/` breaks identity — `.`/`..` are
merely odd names, since nothing normalises a path here), the same gap in the **create-folder**
dialog is untouched, and `MoveFileImplementation`'s missing parent check stands — no reachable
caller can hit it now that rename is fenced and the move picker only offers folders that exist.
"Move by typing a path" remains a possible *feature* (a destination field, autocomplete,
confirmation) — but a deliberate one, never a side effect of string concatenation.

## 28. As-built (2026-08-05) — the same, for the new-folder dialog

§27 for create-folder, which had the same shape of defect and two of its own. `CreateFolderDialog`
was in fact the *precedent* rename was measured against — it had an error channel where rename had
none — but it was the wrong precedent in three ways:

⚠ **The copy lived in the view model** (`"A folder with this name already exists."`, `"Not enough
storage."`), which ADR 0016 forbids. Compose never showed it — the dialog read the error as a mere
**presence flag** and rendered its own localised string — but **iOS printed it verbatim**. So the
one surface that displayed the VM's strings was the one that could not localise them.

⚠⚠ **Presence is not a diagnosis.** Because any non-null error meant "name taken", an
out-of-storage or unknown failure got the collision *title*, the collision *message*, and a **Keep
both** button — a remedy for a problem the user did not have, and one that would have failed 99
times in a row for the same underlying reason. `keepBoth()` also retried on *every* error, not just
collisions. Now `canKeepBoth` is `errorMessage == ERROR_ALREADY_EXISTS`, the loop re-tries only on
a collision and reports anything else as it is, and each token has its own copy.

⚠ **Confirm was never disabled**, on a field that starts empty — so opening the dialog and pressing
Confirm created a folder with no name. `canCreate` (non-blank, no `/`) closes that, mirroring
`canRename`.

Also folded in, all mirroring §27: the write moved from a `rememberCoroutineScope` launch to
`viewModelScope`, so **dismissing the dialog no longer cancels the folder being created**; the
suspend `createFolder(): Boolean` / `keepBoth(): Boolean` pair became plain calls with a terminal
`created` in the ui state (the Swift side no longer `await`s a Bool); and a `/` is rejected as
typed for the same reason as in rename — `CreateFolderImplementation` checks only that the path
itself is free, never that its **parent** has a row, so `a/b` would create a folder listed in
nothing.

⚖️ **iOS gained *Keep both*.** It had the button on Compose only, so a collision on iOS was a dead
end — and once the copy is localised properly, a message naming a remedy the screen does not offer
is worse than the untranslated string was. The parity fix is smaller than the divergence.

⚠ Same fake lesson a third time: `FakeFilesRepository.createFolder` always succeeded, so the
collision branch — and *Keep both* with it — was unreachable in tests and previews until the fake
learned to refuse.

Not done: `CreateFolderImplementation`'s missing parent check still stands (no reachable caller can
hit it now that both dialogs are fenced), and the **upload** path's own conflict handling
(`AddFileActionsViewModel`, `upload_conflict_*`) was not touched — it is a different interaction
(a conflict on a file the user picked, not a name they typed) and it already reports and offers a
remedy.

## 29. As-built (2026-08-05) — the upload path: healthier, and silent in two places

§27's treatment applied to single-file upload — the path §28 explicitly deferred as *"a different
interaction that already reports and offers a remedy"*. That was true of the **collision** and
wrong about everything else.

`AddFileActionsViewModel` is genuinely the best of the three: a ui state, a real distinction
between `AlreadyExists` and `InsufficientStorage`, a *Keep both* loop, a cancel. But:

⚠⚠ **`else -> Unit`.** Any failure that was neither a collision nor the quota gate produced *no
state change at all* — the user picked a file from the system picker and it simply never appeared,
with nothing said anywhere. The Compose screens had no error surface to render because the view
model had no error to give them.

⚠ **The *Keep both* loop gave up silently, twice over.** A non-collision error mid-loop set
`success = true` ("break loop") and closed the dialog; exhausting all 99 candidate names closed it
too. Either way *Keep both* looked like it had worked. It also re-tried on **every** error, so an
out-of-storage failure was retried 98 more times before that silent give-up.

⚠⚠ **iOS had no upload feedback at all** — `FilesView` called `uploadFile(…)` and **nothing
observed `addFileActions.uiState`**, so the collision dialog the Compose screens have shown all
along did not exist there. Uploading a file whose name was taken did nothing, visibly *or*
invisibly. Same shape as §28's missing *Keep both*, one step worse.

**Fix:** a token `uploadError` on the existing ui state (`ERROR_UPLOAD_FAILED`,
`ERROR_TOO_MANY_COPIES` — running out of names is its own message, since after 99 attempts the
*names* are the problem and "couldn't upload" would not say which lever the user has); the loop
re-tries only on a collision and reports everything else; and iOS observes the state, with a
conflict alert (Keep both / Cancel) and a failure alert.

⚖️ **One small refactor, deliberately:** the Home / Files / Folder screens each rendered the same
conflict + storage-gate block, so a third dialog would have been a third dialog in three places.
They now call one `UploadFeedback` — the `rowMenu` argument (§23), applied where the drift had
already started.

⚠ **A test that asserted only what did NOT happen.** `otherUploadError_raisesNoStorageDialog` was
written when the storage gate landed and passes just as happily against the swallow: it checks that
the *storage* dialog stays closed, which is also true when nothing whatsoever happens. It now
asserts the failure is reported. A negative-only assertion cannot tell a
correct narrow behaviour from a broken general one.

⚠ **The fake, a fourth time** (§26 re-key, §27 refusal, §28 collision): `FakeFilesRepository`'s
`uploadFile` always succeeded *and* never added the file to the listing, so on iOS a second upload
of the same name could not collide. Its `getFileName` also returned null, which made every upload
"Unknown" and hid the *Keep both* candidate naming (`keepBothCandidateName` splits on the
extension) behind an extension-less placeholder.

⚠ **Not verifiable by XCUITest:** the upload entry points are the system document and photo
pickers, which are out-of-process, so the iOS alerts are covered by the shared view-model tests and
need a device pass. Everything below the picker is exercised by the Compose + VM tests.

Untouched: the **folder**-upload conflict path (`FolderUploadController`), which has its own
progress bar, blanket-choice state and terminal summary — a different interaction with its own
reporting, not a silent one.

## 30. As-built (2026-08-05) — the proxy-side half: a write needs somewhere to hang

§27.1 and §28 fenced the two *typed-name* routes to an orphaned row, and both noted the same
deferral: the ops themselves still did not check. Closed here.

⚠⚠ **`CreateFolderImplementation`, `MoveFileImplementation` and `UploadFileImplementation` each
checked only that the TARGET path was free** — never that the path it was about to occupy had a
parent to hang from. `/Photos/a/b` with no `/Photos/a` wrote a row addressable by `getData`, in
**no folder's `FILES` list**: invisible in every listing, and unreachable by the UI that would
delete it. `ParentFilesRelationMaintainer` could not help — it appends to the parent's array, and
that array's owning entity was not there.

Fix = one `internal ExecutionContext.requireParentFolder(targetPath)` used by all three: the parent
row must exist **and be a `Folder`** (a file at the parent path is refused too — `/notes.txt/inner`
is not a place). It throws the existing `NotFoundError`, so **no new error type, no schema or wire
surface, no HR#9 trigger** — the taxonomy already had the right shape (`:core`'s
`NotFoundError`), which is worth checking before minting one.

⚠ Three things that made this safe rather than a behaviour break, all verified rather than assumed:

- **The root row exists.** `initFileService` seeds `"/"`, so every top-level write passes. Had it
  not, this check would have rejected *everything*.
- **`MoveFile`'s folder recursion is ordered correctly** — the new folder is `saveData`d before its
  children are moved into it, so each child's parent exists by the time it is checked
  (`moveFolder_recursive_updatesChildrenPaths` was already there and stayed green).
- **Shared-realm content never reaches these ops.** It is a different type (`SharedFile`, keyed
  `"$realmId::…"`) with its own tree maintainer, so the realm-prefixed paths — whose "parent" would
  be the bare `"$realmId::"` — are not affected.

Callers that build a tree already create it top-down (`TenshuFilesRepository.ensureFolderChain`,
the folder-upload loop), and the full gate passed unchanged, so nothing was relying on implicit
orphan creation.

⚖️ **Why this is worth having even though the UI can no longer reach it:** the ops are the API. The
JS SDK, a third-party app over EPC, and any future import path all write through them without the
dialogs' fences, and an invariant enforced only in the client is not an invariant. The client-side
checks stay — they give the user a message about the *name they typed*, which a proxy error cannot.

### §29.1 — the picker *is* drivable, and the pass found a naming bug

§29 recorded the upload alerts as "not verifiable by XCUITest: the entry points are the system
document and photo pickers, which are out-of-process". Half right — the pickers cannot be
*queried*, but they can be *driven*.

**What the tree actually shows.** With the photo picker up, the host app's element tree holds its
chrome (`"Photos"`, `"Collections"`, `"Cancel"`) but **not the photo grid** — `app.cells` returns
the four Files rows *underneath* it. The picker's own process is not queryable either
(`com.apple.PhotosUIPrivate.PhotosPicker` is "not running"; nothing PHPicker-shaped appears in
`launchctl list`). A screenshot confirmed the picker was genuinely presented the whole time, so
this is an accessibility-scope boundary, not a presentation failure.

⇒ **Select by coordinate tap.** `app.coordinate(withNormalizedOffset:)` needs no element, and the
chrome that *is* visible gives honest before/after gates: wait for `"Photos"` to exist (the remote
view is up) before tapping blind, and for it to stop existing (the picker dismissed) before
touching the app again. The alerts themselves are the app's own and query normally — only the
picker was opaque. `UploadConflictUITests` now covers pick → upload → pick again → conflict →
*Keep both* → the numbered copy, and the cancel path; both confirmed failing with
`FilesView`'s `observeUploads` task removed.

⚠⚠ **The suite needs a seeded photo** (`xcrun simctl addmedia booted …`). An empty library does
not fail loudly — the grid is invisible, so the blind tap hits nothing and the assertions time out.

⚠ Still not covered: the **failure** alert (`uploadError`). Over the fake repository the only
reachable failure is the collision; a non-collision failure has no route from the UI, so it stays
view-model-tested. And this is a **simulator** pass — a real device additionally exercises the
Photos permission prompt, the real file provider, and background upload.

⚠⚠ **Found while running it: every photo uploads as `photo.jpg`.** `FilesView.upload(photoItems:)`
names the temp file `(item.itemIdentifier ?? "photo") + ".jpg"`, and `itemIdentifier` is **nil
unless the picker is built with `photoLibrary: .shared()`** — which this one is not. So the name is
always the fallback, every subsequent photo collides with the first, and the library ends up as
`photo.jpg`, `photo (1).jpg`, `photo (2).jpg`. The conflict machinery works perfectly; it is being
handed a constant.

⚠ **The obvious fix is a trap.** Passing `photoLibrary: .shared()` makes `itemIdentifier` a PHAsset
local id — `"UUID/L0/001"`, **containing slashes**. `temporaryDirectory.appendingPathComponent(…)`
would then build nested directories that do not exist, `data.write(to:)` fails, and the `try?` +
`continue` swallows it: the upload would silently do nothing at all. And were the write to succeed,
the file name would carry a `/` into `UploadFile`, which §30 now refuses outright.

### §29.2 — resolved: the asset's own filename, via a file representation

Decided 2026-08-06: **the original filename**. `IMG_0111.jpeg`, not a capture-date scheme, because
it is the name the photo already has everywhere else the user meets it.

The route matters. `loadTransferable(type: Data.self)` yields bytes with **no name** — that is the
root of the whole bug. A `Transferable` backed by `FileRepresentation(contentType: .image)` yields
a **URL**, whose last path component is the asset's original filename; both `getFileName`
implementations (the real `IosFilesRepositoryPlatformDelegate` and the fake) already read exactly
that, so nothing downstream changed.

⇒ **Not** the `photoLibrary: .shared()` route to the same information, for the trap above.

⚠ The received file is deleted the moment the importing closure returns, so it is staged
immediately — through `stageForUpload`, now shared with the document path: a per-upload UUID
directory, which is what lets the leaf name survive *and* keeps two same-named uploads from
colliding on disk before the repository ever sees them.

⚠ **A quiet second fix: the extension stopped lying.** Every photo was named `.jpg` regardless of
the bytes, so a HEIC asset uploaded as `photo.jpg` — and the preview classifier keys off the
filename, which is the desktop-HEIC decode class (§D4/D5) arriving by a different door.

⚠ And the picker path no longer swallows its own failures: a pick that cannot be staged calls the
new `AddFileActionsViewModel.reportUploadFailed()`, which rides the same `uploadError` channel as
the repository failures §29 surfaced — the client-side twin of that `else -> Unit`.

Covered by `test_a_photo_uploads_under_its_original_name`, which **derives** the expected name
(it differs per photo library) rather than pinning one, and is confirmed failing against the old
`Data.self` + `itemIdentifier` path with the literal `photo.jpg` it produced.

### §29.2 — the fix verified over the REAL repository, not just the fake

`test_a_photo_uploads_under_its_original_name` runs over `FakeFilesRepository`, so a report that
photos were *still* arriving as `photo.jpg` raised a fair question: does the **real** route-1
repository name them differently? Answered by a throwaway `RealRuntimeUploadProbe` driving the
picker under `-localRuntime` — which mounts `LocalRuntimeView`, i.e. the real Files UI over
`DriveLocalRuntime`'s in-process proxy stack against the shared replica. **It does not.** The probe
reported the live conflict alert naming the file:

> A file named “IMG_0111.jpeg” already exists. Keep both or cancel.

with `/IMG_0111.jpeg` already in the replica from the preceding real-proxy upload. The probe was
deleted once it had answered.

⚠ **Real and fake cannot diverge here, structurally** — and this is the more useful half. The name
is decided in **Swift**, before either repository is reached: `PickedPhoto`'s `FileRepresentation`
yields a URL and `stageForUpload` preserves its leaf. Both `getFileName` implementations then take
the last path component (`TenshuFilesRepository` → `IosFilesRepositoryPlatformDelegate`, which
additionally percent-decodes via `NSURL`; the fake strips a query instead). Neither can turn
`…/IMG_0111.jpeg` into `photo.jpg`, because **`photo.jpg` was never a system-supplied name** — it
was our own `(item.itemIdentifier ?? "photo") + ".jpg"` literal. So a device still showing it is
running code from before the own-filename upload fix, and no repository-layer explanation is available.

⚠⚠ **Which is what it turned out to be: the phone was ~3 weeks stale.** The last `Debug-iphoneos`
build products date to Jul 18 and Xcode's own DerivedData for `DriveIos` to Jul 24 — both well
before that fix merged (Aug 6). ⚠ `CFBundleVersion` is a static `1` on this project, so **the
installed version string carries no signal at all** and cannot be used to tell builds apart;
build-product timestamps are the only local evidence.

⚠⚠ **A device reinstall from current master is not free**: `TENSHU_STORAGE_FORMAT_VERSION` moved
**2 → 20** across that window, so the install would hit a core-version store wipe on open — which
on iOS is the *unfixed* watchdog hazard (a wipe inside the 20 s launch budget gets the process
killed; the indexed-store launch fix covers the hydrate path, not the wipe path). Worth doing deliberately, not as a
side effect of "just reinstall to test the filename".

⚠ On the `-localRuntime` harness crash that blocked the first attempt (SIGABRT, unhandled Kotlin
exception on a background coroutine, so `try?` in `LocalRuntimeView.task` cannot catch it): it did
**not** reproduce after regenerating the project and rebuilding on current master — including with
`DRIVE_DEV_PROVISION_CONTROL=1`, which was the obvious suspect and is **not** the cause. The
earlier run was against a stale binary. Cause unpinned; recorded rather than diagnosed.

⚠ Process note: `DriveIos.xcodeproj` is XcodeGen-generated and gitignored, so a **new** UITest file
is invisible until `xcodegen generate` re-runs — the symptom is `Executed 0 tests`, not a
compile error.

### §29.3 — what the device install then found (two unrelated, previously-unseen faults)

Installing current master on the phone (2026-08-06) confirmed the stale-build diagnosis — the old
container held `Documents/files/photo.jpg` written that same morning — and surfaced two faults that
no simulator run reaches, because both need a **real, large, long-lived store**.

**(1) ⚠⚠ A fully-migrated store can still be unopenable inside the launch budget.** The phone's
store held **144,610 objects** (one file per object). Every launch was `SIGKILL`ed at a measured
**21 s** — the classic watchdog. ⚠ This was **not** the wipe and **not** a migration: the store was
already stamped (`.formatversion` = 20, all 21 service versions recorded, no migration journal),
and the object count did **not** move across three killed launches. So it is a startup pass that is
still unbounded at this size **on a build that already bounds the orphan
sweep's *matches*** (for exactly this class) and has the indexed-store + size-tier fixes. ⚠ `devicectl` offers **no targeted
delete** — `copy`/`install`/`uninstall` only — so the only in-reach remedy was uninstall +
reinstall. Recovery was immediate and the store refilled from the relay, which is the tell that the
cost scales with accumulated objects, not with anything the fresh install does. Note the store had
plausibly been inflated by the derived-projection broadcast loop.

**(2) ⚠⚠ Sync pull throws `ClassCastException` per block on a fresh store.**

```
kotlin.ClassCastException: DbValue.ByteArrayValue cannot be cast to DbValue.LongValue
  DbEntryMapper.deSerialize ← DatabaseImpl.getData ← SyncProcessor.resolveConflictAndSave
    ← applyDeltaBatch ← processSyncBlock ← SyncScheduler.pullSyncLog
```

⚠ It *looks* non-fatal — 90 occurrences in one 70 s launch, process alive, store filling
(0 → 2 692 objects) — but that reading is wrong, and the reason is worth keeping: **the forward
pull was wedged.** `SyncScheduler.pullSyncLog` calls `processSyncBlock` **outside** its try/catch
(the try wraps only the network fetch), so a throw skips the cursor return, every later block in
the page, and all its tombstones and peer tips. `pullMergedLog` only advances `lastSyncTimestamp`
on a normal return ⇒ the same page is refetched and rethrown forever. The growth that made it look
healthy came from the backfill/joiner-snapshot path, which is a different cursor.

⇒ **Diagnosed and FIXED (2026-08-06)**, cross-platform — the same cast also fires on Android via a
reactive array read (`deSerialize ← deserializeArray ← getArrayData`), i.e. two unrelated readers
over one bad row. Root cause is **C7 itself**: it moved `File.Thumbnail` /
`File.ThumbnailLarge` from `valueProperty` to `dynamicProperty`, and those two authoring forms
register **different schema kinds under the same property id** —

| authoring | registers | stored as | read arm |
|---|---|---|---|
| `valueProperty` | a `ValueSchema` | the value **inline** | `is ValueSchema` |
| `dynamicProperty` | a `DynamicPropertyRelationSchema` | a child-row **reference** | `is CompositeSchema` |

`RelationSchema : CompositeSchema`, so after the flip every pre-existing row's inline thumbnail
bytes matched the composite arm and were cast to a `LongValue` child-row id.

⚠⚠ **The reasoning that made it look free was "nothing reshaped positionally" — true, and not
sufficient.** A property relation is stored in a **named map**, not positionally, so
`SchemaShapeBaselineEnforcementTest` was honestly green ("0 changed") and the migration shipped
with the bump was empty. The kind of a *relation's* stored value is a stored-shape change that the
positional argument, and the only test enforcing it, both fail to cover.

⚠⚠ **`ClassCastException` is a property of `TByteArray`, not of the defect.** On a Long-shaped
property the stale slot *is* a valid `LongValue`, so nothing throws — the stored **value** is
dereferenced as a row id (a property set to `4096` sends the reader to row `4096`). A colliding
hash would return the wrong data silently. The read arm can reject a kind *mismatch*; it cannot
reject a kind match that means something else, so that half stays open — see
`ValueToDynamicPropertyMigrationTest`, which pins both halves.

Fix: `DbEntryMapper`'s relation walk drops a slot whose stored kind cannot be a child-row
reference (an inline value cannot become a child row at read time, and a dynamic property resolves
on demand anyway, so the leftover eager copy is superseded garbage). Read-side only — no schema,
wire, or store-shape change, so no version bump is owed. Verified on the phone: **0 exceptions**
(was 90/launch), `backfill handover — live tail jumps 500 → 791312`, store 2 692 → 16 891 objects.
