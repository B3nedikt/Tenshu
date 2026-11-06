# Drive offline / online — the two-axis model

**Status:** v1 shipped (single-file toggle + listing indicator). **v2 true
byte-presence signal shipped** (the `HasLocalData` op + per-row glyph + search-row
annotation; see §3.1). The background offline-keeper (§3.2) remains outstanding —
it is gated on background-processing Phase 6 (`PlatformScheduler`), not yet built.
**v3 — configurable per-device sync rules (§4): Phase 1 (model) shipped.** The
`SyncPolicy` enum + `PathSyncOverride` synced shape (stored as the DEVICE-scoped
`DrivePreferences.SyncPolicyDefault` + `SyncOverrides` preferences), the pure
`effectiveSyncPolicy` longest-prefix resolver, and the glyph reading the effective
rule (incl. the §4.5 unknown→neutral fix) are on master. No background behaviour:
the rules drive the glyph + the existing manual pin/evict actions only. **Phase 2
(settings + folder/inverse-override UI + Control read-only view) shipped.**
**Phase 3 is split into two halves:** the *user-triggered, foreground* half
**shipped** — flipping a folder's `…`-menu toggle now eagerly evicts/downloads the
folder's *current* contents (`FileActionsViewModel.reconcileFolderBytes`, respecting
per-file pins + nested overrides via `effectivePolicy`; see §3.2). The *ambient,
app-closed* keeper's **download half also shipped** — `OfflineKeeper` (the first
production `HeadlessSafe` op) periodically fetches `KEEP_OFFLINE` files missing
local bytes, dispatched by the `ScheduleDrainLoop` / Android `WorkManager`
(`PlatformScheduler`, bgproc §8 Phase 6/7, now a Koin-bound interface not an
`expect class`). **The keeper's evict half SHIPPED 2026-08-03** — the local-cache
ceiling + free-disk floor + coldest-first eviction of unpinned content
(`retention-quota-model.md` Face B / P2, as-built §4.11 there). The ceiling's settings row
landed the same day (*Sync preferences → Cache size limit*, `retention-quota-model.md` §4.12);
it still ships at `0` = unlimited, so a user opts in and only the free-disk floor is
active by default. **Still outstanding:** the iOS twin of that row, and
**Phase 4 (Control management)**.

## 1. Problem

Drive's "offline" feature conflated two distinct concepts into one vague idea,
and the single-file `…`-menu *Download* action was wired to an empty function:

- **Intent (a pin):** "I want this file kept on my device." Stored as
  `DrivePreferences.OfflineFiles` — a **device-scoped, synced** list of paths
  (`OfflinePreferences`). The multi-select sheet's *Make available offline*
  switch already wrote to it.
- **Presence (cache state):** "are the bytes on disk right now." Observable
  cheaply via `FileDatabase.fileSize(path)` (O(1), no payload read).

The intent axis had **no mechanism** behind it — pinning stored a string and
nothing downloaded or evicted bytes — and the listing's online/offline cloud
icon had been removed because resolving byte presence *eagerly in the
listing chain* tripped Android's Binder watchdog on large folders.

## 2. v1 — what shipped

A coherent, honest first cut with **no background machinery**:

- **Single-file `…` action is now a pin toggle** (`FileActionsViewModel`):
  - online-only → **"Make available offline"** (`CloudDownload`): adds the path
    to `OfflineFiles` **and** forces the bytes onto local disk
    (`FilesRepository.downloadFile`, which reuses the existing OnDemand-
    materialising `getRemoteFile` chain — no new fetch op needed).
  - kept offline → **"Remove download"** (`CloudOff`): removes the path **and**
    evicts the local bytes via the new **`EvictFileBytes`** op.
- **`EvictFileBytes`** (`file-service` + `-common` + `-client` + `-proxy`): drops
  the on-disk bytes (`FileDatabase.deleteFile`) while leaving the synced `File`
  entity and its metadata intact. **No tombstone, no notification, no parent-array
  edit** — eviction must never converge into a delete on other realm devices; a
  later open simply re-fetches OnDemand.
- **Listing indicator** (`HorizontalFileItem`): an "online only" `CloudQueue`
  glyph on rows **not** kept offline.

### Key decision: the icon + label render off *intent*, not *presence*

Both the toggle label and the listing glyph are driven by the device's **pinned
set** (`OfflinePreferences.observe()`), which is already streamed to the client
cheaply (one list for the whole device). They are **not** driven by a per-row
byte-presence query. Rationale:

- A per-row presence query is a per-row IPC round-trip — exactly the cost shape
  that forced the icon's original removal. Intent is free (already in memory).
- Because pinning downloads immediately and unpinning evicts immediately, and
  `OfflineFiles` is **device-scoped**, "pinned ⇔ downloaded" holds for every file
  this device manages. So the glyph faithfully means **"not kept offline."**
- The one case it doesn't capture: a file cached merely by being *opened* (lazy-
  on-open) but never pinned still shows the cloud. Acceptable for v1.

## 3. v2 — the true presence signal (shipped)

### 3.1. What shipped

- **`HasLocalData` op** (`file-service` + `-common` + `-client` + `-proxy`): a
  `FileSystem` operation returning a `TBoolean`, backed by `FileDatabase.fileSize`
  (O(1) on the real-filesystem impls — a `length()` stat, no payload read). It
  answers *presence* — are the bytes on **this device's** disk right now —
  independent of the synced entity. `true` for a file that was pinned-and-
  downloaded **or** merely opened-and-cached (lazy `Syncable(OnDemand)`); `false`
  for an online-only or pinned-but-not-yet-fetched file. It is the *presence*
  twin of `EvictFileBytes`, and is deliberately an **op, not a synced
  `File.Relation`**: presence is device-local and non-syncable, so it doesn't
  belong on the wire schema (a `dynamicProperty` would have to fake a `Syncable`
  trait it doesn't have). `FilesRepository.hasLocalData(path)` wraps it; failures
  degrade to `false`.

- **Per-row, bounded rendering** — exactly the discipline previews use, never the
  listing chain (resolving presence eagerly is what tripped the Binder watchdog
  and forced the original icon removal). Each VM exposes
  `loadLocalData(path)` behind its own `Semaphore` (8 permits — looser than the
  preview cap because the probe is far cheaper); `HorizontalFileItem` launches it
  via `produceState`, so a row scrolled off-screen cancels the probe and releases
  its permit. A **counting** semaphore (not a `Mutex`) is the right tool: the goal
  is "at most N concurrent", not "exactly one" — a mutex would serialise the whole
  visible window.

- **Glyph logic** — `offlineRowGlyph(hasLocalData, isAvailableOffline)` (a pure,
  unit-tested decision; `OfflineRowGlyph.kt`) combines presence with the v1 pin
  intent:
  - present → **no glyph** (it's here — fixes v1's false "online only" on
    opened-but-unpinned files);
  - absent + pinned → **"queued"** glyph (`CloudSync`, *kept offline, downloading*
    — fixes v1's false "available" on a pin whose bytes haven't arrived);
  - absent + unpinned → **"online only"** (`CloudQueue`, the v1 glyph);
  - presence unknown (probe in flight / no loader) → fall back to the v1 intent
    glyph, avoiding flicker.
  Folders carry no presence glyph at all (they have no bytes) and are never
  probed.

- **Placement** — the glyph sits in the row's **trailing slot** (right edge),
  immediately left of the `…` overflow action, rather than inline after the
  filename. This is the convention OneDrive, iCloud Files and Dropbox all
  follow: status lives in a quiet right-aligned column, the affordance (`…`)
  to its right. Combined with the presence rule above — *present files show
  nothing* — most rows you've opened carry no glyph at all, so the listing
  stays calm instead of stamping a cloud on every unpinned row (the v1
  verbosity). The trailing slot is `null` only when there is nothing to show
  (e.g. a picker folder row with no `…` button).

- **Search-result rows** are now annotation-capable: `FilesSearchBar` /
  `FilesSearchBarContent` take `offlinePaths` + a `localDataLoader`, wired from the
  **host screen's** VM (the search VM has no file access of its own). ⚠ ~~The search
  feature itself is still a stub (`FilesSearchBarViewModel` performs no search and
  never populates `uiState.files`), so no rows render in production yet — but they
  will be annotated the moment search is built.~~ **CORRECTED 2026-07-20:** search
  is built — the VM populates `uiState.files` from a real proxy-side
  `Contains` scan, so these rows **do** render in production on Android + desktop,
  annotated as designed. (iOS has no search surface at all yet — `ios-drive-parity.md` §24.)

### 3.2. Enforcement — the two halves

Enforcing a sync rule means moving bytes to match it. That work splits cleanly by
*who triggers it*:

- **Foreground, user-triggered — SHIPPED.** When the user flips a folder's
  `…`-menu offline toggle, `FileActionsViewModel.onFolderOfflineToggled` writes the
  override **and** calls `reconcileFolderBytes(target)`: it walks the folder
  (`FilesRepository.listFilesRecursively`) and, for each file whose **effective**
  policy — the just-written folder override folded with per-file pins and nested
  overrides via `ruleSet.withPinnedPaths(offlinePaths).effectivePolicy(path)` —
  equals the new target, calls the existing per-file `evictFile` (→ ONLINE_ONLY) or
  `downloadFile` (→ KEEP_OFFLINE). A pinned file or a file under a nested
  `KEEP_OFFLINE` subfolder is left downloaded when the parent folder goes
  online-only, and vice-versa. Best-effort, sequential, and bounded by the screen's
  `viewModelScope` — leaving mid-run cancels the remainder (the persisted override
  stands; a re-run, or the keeper below, finishes a large download). No scheduler
  needed: it's a direct repository loop.

- **Background, ambient — the keeper: download-only half SHIPPED 2026-07-07.**
  `OfflineKeeper` (the **first production `HeadlessSafe` op**, `:file-service`) is a
  param-less self-driving reconcile: it reads the device's `SyncRuleSet` proxy-side,
  walks the file tree, and for every file whose effective policy is `KEEP_OFFLINE`
  but whose bytes are absent locally, drives the OnDemand fetch (`OnDemandResolution
  .resolve`) that lands them on disk — so `KEEP_OFFLINE` content that arrived via
  sync while the app was closed is present next open. Registered at proxy init as a
  **periodic, unmetered-only** `ScheduleItem`, so the in-process `ScheduleDrainLoop`
  dispatches it while Control runs and (on Android) `WorkManager` wakes a closed
  Control — the `PlatformScheduler` seam landed in `core-background-processing.md`
  §8 Phase 6/7. Selection logic is unit-tested (`OfflineKeeperSelectionTest`); the
  proxy-side rule read reuses `FileSyncSettingsRelationMaintainer`'s pattern. ⚠
  **Download-only** — it never evicts. And it reconciles **folder-granular rules
  only** (it doesn't fold the legacy `drive.offline.files` per-file pins, which live
  in the app module out of proxy reach; a pin still downloads immediately when set).
- **Background, ambient — SHIPPED 2026-08-03 (the evict half).** The same keeper
  pass now also evicts unpinned content that is over the device's local-cache
  ceiling or under its free-disk floor, coldest-first
  (`retention-quota-model.md` Face B). The two halves cannot fight: the download
  half only fetches `KEEP_OFFLINE` content and the evict half never touches it, so
  the ordering is safe by construction. The storage-pressure signal this was waiting
  on is `FileDatabase.availableSpaceBytes()`; the budget policy is D6's
  ceiling-plus-floor. ⚠ Because it "silently removes local data", the ceiling ships
  **off** (`0` = unlimited) until its settings screen exists — a default-on limit
  with nowhere to see or change it is exactly the deferral's original objection.

## 4. v3 — configurable per-device sync rules (proposed)

### 4.1. Why v1/v2 aren't enough

v1/v2 hard-code one policy: **default = online-only, plus a sparse set of
`KEEP_OFFLINE` pins** (`OfflineFiles`). That's already a "default + sparse
overrides" model — but the default is fixed and the only override direction is
"keep." Two gaps fall out:

- **The default is wrong for most platforms.** On a phone or desktop a user
  expects their files to *just be there*; on the web they expect the opposite
  (nothing downloaded — that's the point of a browser client). A single global
  "online-only unless pinned" default serves none of them well.
- **There's no way to express the inverse exception.** Once the default flips to
  "keep everything", you need to say "…except this one huge folder" — an
  `ONLINE_ONLY` override, which v1/v2 can't represent.

v3 generalises the existing shape rather than replacing it: **a per-device
*configurable* default, plus *bidirectional*, folder-granular, sparse overrides.**

### 4.2. The shape — default + sparse overrides (NOT a per-file × device map)

A tempting shape is a `Map<DeviceId, isOnlineOnly>` stored **on each File**.
Reject it: that is **O(files × devices)** synced state that re-encodes the default
on every file (10k files × 4 devices = 40k booleans, growing per device), when
~99% of files just follow their device's default. The rule for selective-sync
systems is **store the exception, not the rule.** Three layers, each where it
belongs:

1. **Per-device default policy** — *one value per device*, not per file:
   `OFFLINE_ALL | ONLINE_ONLY`. This is a **`DeviceSetting` in
   `:preference-service`** (it is exactly the per-device synced-setting shape that
   module exists for). Seeded from the platform at device registration (§4.4). M
   entries total, tiny; synced so *other* devices know each device's policy (the
   Control angle, §4.6).

2. **Sparse path overrides** — *only the exceptions*, **folder-granular and
   bidirectional**: `(deviceId, pathPrefix, policy = KEEP_OFFLINE | ONLINE_ONLY)`.
   Folder-prefix rules cover *future* files created under that folder, which is
   the whole point ("keep `/Photos` offline", "`/Archive` online-only on the
   phone"). This is the generalisation of today's `OfflineFiles` list (which is
   the `KEEP_OFFLINE`-only, exact-path-only special case — see migration, §4.9).

3. **Presence stays separate and device-local** — `HasLocalData` (§3.1) is
   unchanged: *are the bytes here now*, unsynced, transient. SyncRules express
   **intent**; `HasLocalData` reports **reality**. Keeping them distinct is what
   makes the glyph correct (§4.5).

**A per-file toggle is not lost** — it is simply the finest-grained override
(`pathPrefix` = the exact file path). Folder rules and per-file pins are the same
mechanism at different granularities; only the cost of "whole big folder" drops
from N overrides to 1.

**Effective rule resolution** for `(file, device)`: take the **longest matching
`pathPrefix` override for that device**; if none, the **device default**. Pure,
synchronous, no IO — a function of (device default setting, that device's
override set), both already in memory on the client. Resolvable on any device for
any other device, which §4.6 needs.

### 4.3. Enforcement — a policy gate over OnDemand materialisation

Do **not** make the static `Syncable` trait data-driven — that conflates
*wire/type semantics* (which the sync engine resolves statically and which must
stay deterministic) with *runtime per-device policy*. `File.Data` stays
`Syncable(OnDemand)` at the type level. Instead add a **policy gate** asking, per
file per device: *should I proactively materialise these bytes?*

- Effective rule `KEEP_OFFLINE` → the **keeper** (§3.2) proactively runs the
  existing OnDemand-materialising fetch (`getRemoteFile`/`downloadFile`) and keeps
  the bytes; `ONLINE_ONLY` → no proactive fetch, bytes arrive lazily on open (the
  v1/v2 behaviour).
- This reuses the **`SyncGate` / `ConstraintSyncGate` seam** the preference
  service already introduced (WifiOnly suspends the cycle). "Keep-offline policy"
  is another gate over the same proactive-fetch decision; it composes with
  WifiOnly (a metered phone can keep policy = OFFLINE_ALL but defer the actual
  download until unmetered).
- **Eviction** under storage pressure reuses `EvictFileBytes` (§2). The keeper
  picks eviction candidates from files whose effective rule is *not*
  `KEEP_OFFLINE`, LRU-first (§4.4).

All enforcement is therefore **keeper work**, exactly like §3.2 — and as of
2026-08-03 both halves of that keeper exist and run. ⚠ **The "gated on
background-processing Phase 6" framing this section used to carry was stale**: the
`PlatformScheduler` seam landed, `registerOfflineKeeperSchedule` runs at proxy init
on Android, desktop **and** iOS, and `SyncProxyInitialization` constructs the
`ScheduleDrainLoop` that dispatches it. Nothing about the keeper is waiting on
bgproc any more; what the evict half was actually missing was a budget policy and a
storage-pressure signal, both of which are now built.

### 4.4. Platform defaults — and the storage-pressure caveat

- **Web → `ONLINE_ONLY`.** Unambiguous; a browser client must not download the
  drive.
- **Phone / desktop → `OFFLINE_ALL`, but with automatic eviction**, *not*
  unconditional download-everything. "Keep every file" is fine on a small drive
  and brutal on a large one — which is exactly why Dropbox moved from full-sync to
  Smart Sync and iCloud has "Optimize Storage." The honest default is **keep
  offline, but let the keeper evict LRU under a storage budget** (iCloud's model).
  An evicted file is still *intended* offline (rule unchanged) — it just shows the
  "queued" glyph until re-fetched (the `absent + KEEP_OFFLINE` state already
  designed in §3.1).
- Defaults are a per-device **starting value**, user-overridable in settings; the
  point is sensible out-of-the-box behaviour, not a locked policy.

### 4.5. Presence vs intent, and the glyph default fix

The glyph should read the **effective rule** (intent) refined by **presence**:

- present → **no glyph** (it's here);
- absent + effective `KEEP_OFFLINE` → **"queued"** (`CloudSync`);
- absent + effective `ONLINE_ONLY` → **"online only"** (`CloudQueue`);
- **presence unknown (probe in flight) → no glyph** (neutral).

The last line **changes the v2 fallback** (`offlineRowGlyph(hasLocalData = null,
…) → OnlineOnly`). That fallback was chosen to avoid flicker, but it makes the
*unknown* state look like *online-only* — so every row, including a file you just
created locally, flashes a cloud until its async probe resolves. With a
configurable default that can be `OFFLINE_ALL`, "unknown ⇒ online-only" is even
more wrong. **Unknown presence must render neutral**; the cloud appears only once
the probe *confirms* the bytes are absent. (Update `OfflineRowGlyphTest`
accordingly.)

### 4.6. Cross-device management via Control — the payoff for syncing

Plain selective sync (Dropbox/OneDrive) keeps these rules **device-local** —
nobody else can see them. Syncing the per-device default + sparse overrides costs
little (§4.2) and unlocks the thing that justifies it: **Control (the control
plane) can show and edit "what each device keeps."** "From my desktop, make
`/Videos` online-only on my phone"; "this device is 90% full — here's what it's
keeping." That is a genuine differentiator and the reason the rules live on the
wire at all rather than purely in local prefs.

### 4.7. Dependencies — thumbnails and relay persistence

v3 only makes sense alongside two adjacent tracks:

- **Synced thumbnails** (`docs/proposals/drive-synced-thumbnails.md`, drafted)
  are the **enabler**: an `ONLINE_ONLY` file must still be *browsable and
  previewable* without its bytes. Online-only ⇒ show the synced thumbnail + a
  cloud glyph; tapping fetches bytes. Without eager thumbnails, online-only files
  are blank rows — the current bug.
- **Relay content persistence** (`docs/proposals/sync-relay-persistence.md`) is
  the **floor**: online-only only works if the relay reliably *has* the bytes to
  fetch later. Today `ContentBlobStore` is in-memory, so a relay restart drops the
  chunk blobs and an on-demand open fails (`File not found`). v3's whole premise —
  "you can safely not keep the bytes locally" — is unsafe until that store is
  durable.

### 4.8. Threat-model note

Sync rules are new synced metadata derived from the user's file/folder structure.
They reveal **coarse per-device retention intent** (which folders a device keeps)
to anyone who can read them. Per House Rule #1 the relay must not learn this:
fold the per-device default + overrides into the **existing encrypted realm
metadata** (same envelope as file metadata), so the relay sees only opaque
ciphertext, not a "device X keeps folder Y" map. A full amendment to
`docs/sync/threat-model.md` ships with the implementing PR (House Rule #2), since
this adds a synced data shape.

### 4.9. Migration from v1/v2

- Today's `DrivePreferences.OfflineFiles` (device-scoped synced list of pinned
  paths) **is** the `KEEP_OFFLINE`, exact-path override set under a fixed
  `ONLINE_ONLY` default. Migration is additive: introduce the per-device default
  setting (defaulting existing devices to `ONLINE_ONLY` to preserve current
  behaviour), and treat existing pins as `KEEP_OFFLINE` overrides. No data loss,
  no behaviour change until a user flips a default.
- The `…`-menu pin toggle and multi-select sheet keep working — they write
  exact-path `KEEP_OFFLINE` overrides. New UI adds: per-device default (settings),
  folder-level override (folder `…` menu), and the inverse `ONLINE_ONLY` action.

### 4.10. Phased plan + open questions

**Phases** (each independently shippable; enforcement trails the keeper):

1. **Model** — per-device default `DeviceSetting` + `PathSyncOverride` synced
   shape + the pure effective-rule resolver + glyph reads effective rule (§4.5
   fix). No background behaviour; rules drive glyph + manual actions only.
   **✅ Shipped.** Implemented in `:drive-shared`: `SyncPolicy` /
   `PathSyncOverride` / `SyncRuleSet` + `effectiveSyncPolicy` (`offline/SyncRules.kt`),
   the `SyncPolicyAdapter` / `PathSyncOverrideSetAdapter` byte codecs, the
   `DrivePreferences.SyncPolicyDefault` (default `ONLINE_ONLY`, the §4.9 migration
   baseline) + `SyncOverrides` DEVICE-scoped preferences, the `SyncRulesPreferences`
   reader, and `SelectionViewModel.syncRules` (default + overrides + legacy pins
   folded as winning exact-path `KEEP_OFFLINE` overrides) threaded through the
   listings/search into `offlineRowGlyph`. The two device-rule preferences have no
   writer yet (they resolve to their defaults), so Phase 1 behaviour is identical
   to v2 except the §4.5 neutral-while-unknown glyph; Phase 2's UI is purely
   additive. **Deviation:** `OFFLINE_ALL` (§4.2.1) and `KEEP_OFFLINE` (§4.2.2)
   collapse to a single binary `SyncPolicy.KEEP_OFFLINE` at the model level — the
   "…with LRU eviction" nuance of an `OFFLINE_ALL` default is a keeper property
   (Phase 3), not a distinct resolved-policy value, so it needs no enum value to
   express the Phase-1 model + glyph.
2. **UI** — settings default toggle; folder-level + inverse overrides; Control
   read-only view of per-device policy. **Slice 2a (settings default toggle)
   shipped:** Drive's Sync Preferences screen gains a *Keep files on this device*
   switch — the first writer of `SyncPolicyDefault`. `SyncRulesPreferences` grew
   `observeDefaultPolicy()` / `setDefaultPolicy()` (DEVICE-scoped read/write via
   `readForDevice`/`setForDevice`); `SyncPreferencesViewModel` reads it reactively
   + writes optimistically. No download/evict (keeper is Phase 3) — flipping it
   only changes the listing glyph for un-overridden files today. **Slice 2b
   (folder-level + inverse overrides) shipped:** the file `…` menu is now
   folder-aware (`isFolder` threaded through the factory + screen UiStates); a
   **folder** shows a *Keep folder offline* / *Make folder online-only* toggle
   (replacing the file-pin Download row, which folders never had bytes for) that
   writes a sparse `SyncOverrides` entry. The single toggle covers **both
   directions** — under an `ONLINE_ONLY` default it adds a `KEEP_OFFLINE` folder
   rule; under a `KEEP_OFFLINE` default it adds the inverse `ONLINE_ONLY` rule.
   Edits go through the pure `List<PathSyncOverride>.withFolderPolicy(prefix,
   policy, deviceDefault)` (upsert, or **clear** when the choice equals the device
   default — keeps the set sparse, §4.2); `SyncRulesPreferences.setOverrides`
   writes the whole list. **Slice 2c (Control read-only view) shipped:** Control's
   device list shows each device's default policy ("Keeps all files on this
   device" / "Files stay online-only"), read **typed and codec-free** via
   `FileSynchronisationSettings.fileSyncDefaultPolicyOrNull(deviceId)` — no
   `:drive-shared` dependency. **Phase 2 complete.** **Outstanding (later):** a
   per-*file* inverse `ONLINE_ONLY` action (only matters under a keep-offline
   default — deferred, files are covered by the pin today).
3. **Enforcement** — keeper consults the resolver to proactively fetch
   `KEEP_OFFLINE` and LRU-evict the rest under budget (gated on bgproc Phase 6).
4. **Control management** — edit another device's rules from Control (§4.6).

**Architecture: the settings model lives in `:file-service`.** The sync-rule
model — `SyncPolicy` / `PathSyncOverride` / `SyncRuleSet` / `effectiveSyncPolicy`
/ the adapters / the `FileSyncPreferences` declarations (`file.sync.defaultPolicy`,
`file.sync.overrides`) — lives in **`:file-service`** (`org.tenshu.core.file.sync`),
not the Drive app module, because it governs file-byte retention. Both apps
already depend on file-service, so both consume the typed
`FileSynchronisationSettings` surface (`:file-service-client`) over the preference
service: Drive (`SyncRulesPreferences` resolves *this* device's id and delegates),
Control (any device's id, read-only). This is what makes §4.6 codec-free — no app
holds the model, no cross-app string contract. (The legacy exact-path pin list
`DrivePreferences.OfflineFiles` stays in Drive — it's driven by the Drive pin UI;
the resolver folds it in via `withPinnedPaths`.)

**Follow-up — a first-class `serviceDataRelation` (shipped).**
`FileSynchronisationSettings` started as a typed *facade* (extension functions over
`readForDevice`/`setForDevice`); it is now joined by a chain-DSL-native relation,
`preferenceService.fileSyncSettings: TList<FileSyncSetting>`. The relation is
registered from `:file-service-common` via `serviceDataRelation(fromId =
PreferenceService.ID, …)` — **no preference-service change**, the registry resolves
it cross-config (exactly as `FileService.shares`/`modifications` hang off
`FileService.ID`). The client accessor builds the same chain a native relation
property would, through the public `RequestObject` seam (`:file-service-client`).

The element is a **typed projection**, `FileSyncSetting { deviceId, defaultPolicy,
overrides }` — one row per device — so callers read `defaultPolicy` field-by-field
with no generic-`Data` decode (`fileSyncSettingFor` / `fileSyncRuleSetFor`), and
`FileSyncSetting.ruleSet()` decodes back to a `SyncRuleSet`. It is a **derived,
non-`Syncable`** entity: the two `file.sync.*` `DeviceSetting` rows are the synced
source of truth, and a proxy-side `FileSyncSettingsRelationMaintainer`
(`:file-service-proxy`, registered in `initFileService`) re-derives the projection
on every (local or sync-arriving) setting save — a **2→1 fold** (it re-reads both
source rows and rewrites the single projection), distinct from the 1:1 mirror the
other maintainers do.

Two design points worth knowing: the element type needs a typed RO
(`FileSyncSettingRo`) registered via `roFactory` for the `search<FileSyncSetting> {
deviceId equalTo … }` predicate receiver; and readers extract `.value` through
`TResult.Success<*>` rather than `Entity<T>`, since a relation array element comes
back as a bare composite (the `search<T>: Maybe<Entity<T>>` static type would
otherwise insert a failing `Entity` checkcast). The write surface stays on the
facade (`setFileSyncDefaultPolicy`/`setFileSyncOverrides` → the two source rows);
the relation is read-only. `fileSyncDefaultPolicyOrNull` deliberately keeps querying
the source `DeviceSetting` rows — its `null ⇔ no defaultPolicy row` contract is more
precise than the projection (whose `defaultPolicy` is always defaulted), and
Control's read depends on that distinction. End-to-end proof:
`FileSyncSettingsRelationSyncSystemTest` (host writes → joiner reads the re-derived
projection through the SDK chain); the fold itself is unit-covered by
`FileSyncSettingsRelationMaintainerTest`.

**Follow-up — `SyncPolicy` → `TConstant` + self-describing overrides (shipped).**
The Phase-1 byte codecs are gone. `SyncPolicy` is now a first-class `TConstant`
(mirrors `FileCategory`) instead of a plain enum, so a policy *is* a self-describing
`Data`: the synced `file.sync.defaultPolicy` value is the constant itself in the
polymorphic `genericData` slot (`SyncPolicyAdapter` collapsed to an identity codec),
and `FileSyncSetting.defaultPolicy` is a `composite(type = SyncPolicy.Schema)` field
read straight through the schema. The override set is promoted from the
length-prefixed `TByteArray` blob to a registered **`PathSyncOverrideEntry`**
composite (`pathPrefix` + a `SyncPolicy` constant field, keyed by `pathPrefix`),
wrapped in a single **`PathSyncOverrideSet`** composite. The wrapper is the storable
shape for **both** the `file.sync.overrides` preference value (the `genericData` slot
can't hold a bare array — it needs a single composite reference) **and** the
`FileSyncSetting.overrides` projection field. `PathSyncOverride` stays a context-free
value `data class` for the pure resolvers + UI; conversion to/from the composites
happens only at the storage seam (`PathSyncOverrideSetAdapter` + `FileSyncSetting`).

Two seam decisions worth knowing: (a) the projection's overrides ride a **composite
scalar field**, not a native `list` — a list field on a *keyed* row deSerialises to a
lazy `ArrayDatabaseReference` that a plain `search().get()` doesn't resolve, so it
would arrive empty over the SDK chain; a composite scalar field materialises eagerly
(its nested list resolves with a null dataId), surviving the chain (the lazy-vs-
materialised seam AGENTS.md documents). (b) `PathSyncOverrideSet` is **content-
addressed** (a derived `key` field) so a composite carrying a list has an addressable
DataId and identical override sets across devices fold to one immutable entry. This
required a `:core-common` prerequisite teaching `DbEntryMapper` to store a
`TConstant` in a composite/`genericData` field (it round-trips as a `StringValue` of
the constant's schema id). Not a threat-model change: the relay still sees ciphertext;
the wire stays a polymorphic `genericData` `Data` (a different concrete type inside).

**Open questions:**

- **Override granularity key** — `pathPrefix` (covers future files, simple) vs
  per-entity `dataId` (precise, but doesn't cover new files in a kept folder).
  Leaning `pathPrefix`; revisit if folder renames/moves make prefix rules
  brittle (a moved folder's overrides would need rewriting — or store the rule
  against the folder's `dataId` and resolve paths through it).
- **Realm-level "keep everywhere" intent** — should there be a device-agnostic
  override ("this folder matters, keep it on every device") on top of per-device
  rules? Could be sugar that fans out to per-device, or a separate realm rule.
  Deferred.
- **Storage budget** — fixed, percentage-of-free, or user-set? Owned by the
  keeper (§4.4); deferred to Phase 3.
- **Large-thumbnail tier eviction** — the large thumbnail class
  (drive-synced-thumbnails) is itself evictable like `File.Data`; does it follow
  the file's effective rule or its own? Likely its own (cheaper, keep more).
