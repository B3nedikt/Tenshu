# Suite sync status — every app says when sync is broken, and when data is still arriving

**Status:** v0.6 — **SS-1, SS-1b, SS-2 and SS-3 built (2026-11-12, §10–§13); SS-4 designed in §14
(2026-11-12), not built — ⚠ it is a thumbnail backfill first and a counter second.** Opened
2026-11-12 from a maintainer request after a real phone sat out of sync with nothing on screen
saying so (§1). Decisions SS-D1…SS-D8 (§7).

**Owner:** Benedikt

**Builds on:** [`drive-connectivity-service.md`](drive-connectivity-service.md) (the sync-health
signal and Drive's banner), [`ios-sync-activity-indicator.md`](ios-sync-activity-indicator.md) (the
`CatchingUp` / `Uploading` indicator, built on iOS only),
[ADR 0025](../adr/0025-forward-to-control-surface.md) (the Control-reachability banner and wall in
every app), [ADR 0019](../adr/0019-storage-capacity-gating.md) (`RealmStorageBanner`).

⚠⚠ **The design move is to finish what exists, not add a fourth system.** The suite already has a
shared engine for "Control is missing or out of date" and one for "storage is full", both in every
app. Sync health has a complete evaluator and copy, but only inside Drive. The pull indicator has a
shared evaluator and a published signal, but only an iOS renderer. Every phase below moves
something that already works into the place every app can reach it.

---

## 1. What happened

On 2026-11-12 the maintainer's OnePlus (a joiner) showed Photos as a grid of grey tiles: dates,
layout, no pictures. Nothing on screen said anything was wrong.

- Control's own `SyncHealthStatus` row read **`device_unrecognized`**: the relay rejected every
  request with `401`, and 130 local changes sat in the push queue.
- The phone's photo rows were already in the local store, so the grid laid itself out. The
  thumbnails are fetched from the relay on demand, so each one was refused.
- **Photos reads `device_unrecognized` already**, but only to purge its Collections snapshot
  (`ControlCurrentRealm.kt`, `photos-app.md` §36). No banner draws from it.
- **Drive would have said so**: `SyncBannerState.DeviceUnrecognized` ("pair this device again").
  No other app has that state, on any platform.

The cause on that phone was most likely a forget-then-pair in one Control process: the process
kept the forgotten key in memory and never saved the new one, so the next restart minted a stranger.
Its fix (`SyncManagerImpl.forgetThisDevice` re-mints the identity in the running process) is in
review. But the relay can stop recognising a joiner for other
reasons: a relay data reset, a revocation from another device, the per-IP auth-failure budget. And
a joiner cannot repair itself (no A1). **Whatever the cause, the user must be told, in the app they
are looking at.**

The maintainer's follow-up ask (2026-11-12): *"maybe also some kind of sync indicator in Photos when
stuff is still arriving? Like we see when we are not caught up yet, and after we are caught up …
when the actual data we want in a specific app is needed."*

## 2. What exists today

| What the user is told | iOS | Android / desktop | Engine | Copy |
|---|---|---|---|---|
| Control missing, out of date, fenced or not set up (banner or wall) | all apps | walls in all nine Compose apps; the **banner** in seven — Home and Mia mount only the walls (`home-shared-ui/.../App.kt`, `mia-shared-ui/.../App.kt`) | `:app-reachability` (`ControlReachability`, `UnreachableReason`) | per app, `*_control_unreachable` in six apps |
| Storage full / no plan | all apps | all apps | `RealmStorageState` (`:storage-service-common`) + `RealmStorageBanner` (`:ui-core`) | per app |
| Pull in progress ("Syncing…") | **all apps** (Control installs it itself; Mia hides it on Chat) | **none** | `evaluateSyncActivity` (`:sync-service-common`) + `SyncCatchUpStatus` / heartbeat rows | iOS `SyncActivitySurface` |
| Uploading N of M | Drive | Drive (as `SyncBannerState.Syncing`) | Drive's `SyncBanner.evaluate` | Drive |
| **Locked, device not recognised, needs attention (402), offline** | Control only | **Drive only** | Drive's `SyncBanner.evaluate`, over `SyncHealthStatus` | Drive |

What each signal costs to read from an app other than Drive, today:

- **`SyncHealthStatus`** (`syncHealth()`, `:sync-service-client`): already read across IPC by Photos.
  `connectivity` is one of **eight** values (`SyncHealthStatus.kt`): `online`, `offline`, `locked`,
  `device_unrecognized`, `needs_attention`, `deferred` (a Wi-Fi-only policy is holding uploads),
  `storage_full` (the relay's own `507`) and `relay_retrying` (uploads backing off; Drive counts it
  as online). Written by every runtime's `initSyncHealth` (`SyncProxyInitialization.kt`), so
  Android Control publishes it and every iOS route-1 app publishes its own.
- **`SyncCatchUpStatus`** (`catchUp(hostAppId)`): written by every runtime's `initSyncCatchUp`,
  keyed by host app id, **including Android and desktop Control** (common code). Nothing on those
  platforms reads it. ⚠ The **heartbeat** that lets a reader discount a dead writer's `true` is
  switched on only for iOS Control (`control-shared-proxy/src/iosMain/.../PlatformProxyModule.kt`);
  on Android a `true` left by a killed Control lingers until Control starts again.
- **Thumbnail retry in Photos:** a tile with no bytes asks again, backing off to **once a minute**
  (`PhotoTile.kt`, `PREVIEW_RETRY_MAX_MS`), and `PreviewCache` never remembers a miss. So tiles do
  recover — but up to a minute after sync does. ⚠ This corrects a claim made during the 2026-11-12
  diagnosis that Photos never retries; it does, slowly.

## 3. The principles

1. **A problem gets a full-width strip; activity gets the small indicator.** The split
   `ios-sync-activity-indicator.md` §0 already made, kept on every platform. A problem *hides* the
   indicator (`SyncActivity`'s own KDoc).
2. **One strip at a time, by one precedence, in every app.** Today an app can stack a reachability
   `ErrorBanner` above a `RealmStorageBanner`. The precedence is Drive's, which is already argued
   state by state (`SyncBanner.kt` `evaluate`): Control unreachable (no other signal can be trusted
   while it is), then this app out of step with Control (`NeedsAppUpdate`, skew), then locked, then
   device not recognised, then schema fence, then **no plan, then storage full**, then
   needs-attention, then offline.
3. **Evaluators stay stringless and live in common code; copy lives once in `:ui-core`.** `ui-core`
   already ships shared strings (`error_*`, en + de). Nine copies of "Can't reach Tenshu Control"
   are the existing debt this should not repeat.
4. **Say only what the device can know.** No counts it does not have, no "almost done" (the pull has
   no total; `ios-sync-activity-indicator.md` §3).
5. **No banner means nothing is wrong.** The property Drive's `Syncing` rework fought for
   (`SyncBanner.kt`, *"no banner once again means nothing is happening"*). An always-on strip is
   one users learn to ignore.

## 4. The shared problem banner (SS-1)

A **`SuiteSyncProblem`** evaluator in **`:app-reachability`** — the one module every app's shared
logic already depends on, and the owner of `UnreachableReason`. A pure function of the reachability
verdict, the `connectivity` **string** and the storage state's two booleans (has a plan, is full),
returning one of `None`, `Unreachable(reason)`, `Locked`, `DeviceUnrecognized`, `NeedsUpdate`,
`NoPlan`, `StorageFull`, `NeedsAttention`, `Offline` — Drive's account-wide states, in Drive's
order. A **`SuiteSyncBanner`** composable in `:ui-core` renders it with shared copy and the
existing `ErrorBanner` / `RealmStorageBanner` tones.

- ⚠ **Primitive inputs, on purpose.** `:app-reachability` depends on neither `:sync-service` nor
  `:storage-service-common`, and `:sync-service-common` reaches neither reachability nor storage
  (`sync-service-common/build.gradle.kts`). Taking strings and booleans keeps the evaluator where
  every app can call it without new edges *into* it. The reads still need edges: Fit, Timeline,
  Maps, Calendar, Contacts, Home and Mia's shared logic list no sync module, so SS-1 adds
  `:sync-service-client` to each to call `syncHealth()`.
- ⚠⚠ **Every one of the eight connectivity values is decided explicitly**, never by "not online".
  Drive's `Offline` is `!health.isOnline`, which would turn `deferred` into *Offline* in an app
  that has no uploads. Proposed: `storage_full` → `StorageFull` (it is the relay's account-wide
  `507`, which Drive already folds into `StorageFull`); `deferred` and `relay_retrying` → `None`
  outside Drive (they describe Drive's file transfers, which Drive keeps reporting);
  `needs_attention`, `locked`, `device_unrecognized`, `offline` → their states.

- **Drive keeps its own banner** and layers its file-transfer states (`Syncing`, `Deferred`,
  `BackupWaiting`, `StorageFull` from the relay side) on top. Its account-wide states delegate to
  the shared evaluator, so the two cannot drift: one test pins that Drive's order and the shared
  order agree.
- **Every other app** replaces its separate reachability and storage banners with the one
  `SuiteSyncBanner` slot. The walls (`AppControlUnavailableWall`) stay as they are — a dead end is
  not a banner.
- **Actions** follow ADR 0025: one route back to Control where acting there fixes it
  (`DeviceUnrecognized` → *"Pair this device again"* opens Control's pairing; `Locked` → open
  Control), none where it fixes itself (`Offline`). On a platform with no route, no button.
- **iOS (SS-1b):** route-1 apps run their own runtime and read their own `SyncHealthStatus`, so the
  same evaluator runs in each app's Kotlin surface, as `SyncActivitySurface` already does; SwiftUI
  renders the strip. Control-iOS already handles `DeviceUnrecognized` and is the reference.
- ⚠ **`offline` needs a dwell before it shows** outside Drive. It is derived from the last relay
  call (`drive-connectivity-service.md` §6 Q2), so a single failed tick on a flaky network would
  flash a strip in nine apps. Drive tolerates the flash because its user is watching transfers;
  Photos' user is not. Propose: show only after 30 s of continuous `offline` (SS-D3).

**No reshape.** Every input is an existing row read with an existing client extension; HR #9 is
untouched. ⚠ But an app newer than Control may meet a connectivity value Control does not write;
an unknown value — a ninth one added later — maps to `None`, never to a problem (SS-D5).

## 5. "Still arriving" — the indicator, and what an app can know about its own data

### 5.1 SS-2: the pull indicator on Android and desktop

Port the iOS capsule to Compose: `evaluateSyncActivity(catchingUp = catchUp(<Control's host app
id>))` — the key Control's runtime passes to `initSyncCatchUp`, which has no shared constant yet —
rendered in a slot on the Compose shell, `AdaptiveNavScaffold` (`:ui-core`), the counterpart of the
SwiftUI `AdaptiveAppShell` slot (§6a.2 there; Maps and Timeline mount it on their own stack). The
cautions:

- **Drive's bar already says `Syncing`** for uploads. Drive keeps upload progress in its banner (it
  has a count and Stop), and shows the capsule for the pull only — the iOS P2 split, mirrored.
- **The row crosses IPC between two installed apps.** `SyncCatchUpStatus` has existed since
  2026-10-06, so every Control in the field publishes it; an absent row reads `false`, which is
  the safe answer.
- ⚠ **A killed Control's `true` lingers** on Android, because its heartbeat is off (§2). Either
  switch the heartbeat on for Android Control and read with `anyCatchUpHeartbeat`, or accept a
  capsule that can stick until Control next starts. Proposed: the heartbeat (SS-D8).

### 5.2 Per-app "your data is still arriving" — what is and is not knowable

The maintainer's sharper question: after the device is caught up in general, can an app say *its*
data is not here yet?

- **Not from the relay, by design.** The relay cannot tell one app's blocks from another's:
  `propertyId` rides inside the ciphertext (T-R-08), and lane-batched blocks carry no entity hash
  (`sync-device-catch-up.md` C5). Asking the relay "how many Photos blocks remain" would need it to
  learn what HR #1 forbids it to learn. ⚠⚠ Do not design toward this.
- **Not during the pull either.** One pull brings every app's rows together in HLC order; nothing
  marks where Photos' rows end. So the honest global answer is the one SS-2 shows.
- **But an app can know what it has rows for and no bytes for.** That is local: Photos has the row
  for a picture and can see whether its thumbnail rung is present. *"128 photos still downloading"*
  is computable on the device, true, and is exactly what the OnePlus needed to say. It applies to
  any app whose data has an on-demand content half (Photos thumbnails, Drive files);
  apps whose data is all metadata are caught up when the pull is.

So the per-app signal is **content, not metadata** (SS-D6), and it is designed per app rather than
generalised: SS-4. ⚠⚠ **But "rows without their thumbnail" is not a count that goes down by
itself** — thumbnails are fetched only when a tile asks (§14.1). §14 is the corrected design.

## 6. Phases

| Phase | Scope | Needs | Done when |
|---|---|---|---|
| **SS-1** | `SuiteSyncProblem` (`:app-reachability`) + `SuiteSyncBanner` (`:ui-core`); mounted in the eight Compose apps beside Drive; Drive's account-wide states delegate to it | shared copy in `:ui-core` (en + de); a `:sync-service-client` edge for seven apps; one precedence test; a test per connectivity value; per-app mount | A device the relay no longer recognises (or a test double reporting `device_unrecognized`) shows *"This device isn't connected to your realm any more — pair it again"* in Photos, Fit, Timeline, Maps, Calendar, Contacts, Home and Mia |
| **SS-1b** | The same strip on iOS | the evaluator in each app's Kotlin surface; SwiftUI strip | Same, on a simulator and on the iPhone |
| **SS-2** | The pull capsule in Compose | the `AdaptiveNavScaffold` slot; Drive's split; Android Control's heartbeat (§5.1) | A fresh joiner shows "Syncing…" in Photos until its first pull completes, and never on a caught-up launch |
| **SS-3** | Photos wakes its waiting tiles when sync recovers | a `syncHealth` transition to `online`, and a catch-up ending, reset every waiting tile's backoff (one shared `MutableStateFlow` the tiles' `produceState` keys on, or a nudge the retry loop awaits) | After recovery, visible grey tiles fill within seconds, not up to a minute |
| **SS-4** | Photos' previews downloaded ahead of the scroll, and the count said — **§14 supersedes this row's first sketch** | §14: a preview backfill on the receiving device (SS-4b) and its count (SS-4c), after measurements (SS-4a) | Photos says *"Downloading previews · 1,240 of 5,000"* while the backfill runs, and nothing once it is done |

SS-1 is the one the 2026-11-12 incident needed; it comes first. SS-3 is small and independent and
can ship alongside it. SS-4 needs its own short design pass on where the count is computed (the
content index is shared, `photos-feed-scrubber.md` §6) before code.

## 7. Decisions (proposed)

- **SS-D1** — One problem strip per app, Drive's precedence everywhere (§3.2).
- **SS-D2** — The problem evaluator in `:app-reachability` over primitive inputs (§4); the activity
  evaluator stays in `:sync-service-common`; copy once in `:ui-core`. Per-app copies of shared
  states are migrated, not added to.
- **SS-D3** — `offline` shows outside Drive only after 30 s continuous. Drive keeps its immediate
  behaviour.
- **SS-D4** — `DeviceUnrecognized` offers *pair again* via Control, never *Restore access*: the
  joiner case is the one users see (A1 holders heal themselves), and Restore access needs A1.
- **SS-D5** — An unknown connectivity value is `None`. A newer app must not invent a problem from a
  value it cannot read.
- **SS-D6** — "Still arriving" per app is about **content bytes**, computed locally. The relay is
  never asked (HR #1, T-R-08).
- **SS-D7** — The walls of ADR 0025 are unchanged; this doc only touches strips and the indicator.
- **SS-D8** — Android Control writes the catch-up heartbeat, and Compose apps read the pull through
  it, so a killed Control never leaves "Syncing…" on screen.

## 8. Open questions

1. **Should `NoPlan` hide `Offline`?** Drive's order puts `LocalOnly` (no plan) above `StorageFull` and `Offline`, so
   an unfunded device never says it is offline. In Drive that is right: files are not leaving the
   device either way. In Photos, an unfunded joiner that is also offline would be told only about
   the plan. Proposed: keep Drive's order everywhere (SS-D1), and settle it in SS-1 if a case
   argues otherwise.
2. **Mia's Chat** hides the capsule (the composer owns the bottom edge). Does it also hide the
   problem strip? Proposed: no — a problem outranks layout.
3. **Desktop has no route to Control in v1** for some states. SS-1 shows those without a button
   (ADR 0025's rule); confirm that is acceptable for `DeviceUnrecognized`, which otherwise has no
   remedy the user can find.

## 9. References

- `drive-shared-logic/.../domain/SyncBanner.kt` — the evaluator SS-1 generalises; its KDoc argues
  each state's rank.
- `sync-service-common/.../SyncActivity.kt` — `evaluateSyncActivity`, the model for SS-1's
  evaluator.
- `sync-service-proxy/.../SyncProxyInitialization.kt` — where every runtime publishes
  `SyncHealthStatus` and `SyncCatchUpStatus`.
- `photos-shared-logic/.../data/ControlCurrentRealm.kt` — Photos' existing `syncHealth` read.
- `photos-shared-ui/.../components/PhotoTile.kt`, `PreviewCache.kt` — the retry SS-3 tightens.
- `ui-core/.../storage/RealmStorageBanner.kt`, `ui-core/.../feedback/ErrorBanner.kt` — the tones.

## 10. SS-1 as built (2026-11-12)

- **Evaluator:** `SuiteSyncProblem` + `evaluateSuiteSyncProblem` + `withOfflineDelay`
  (`app-reachability/.../SuiteSyncProblem.kt`). `:app-reachability` gained `:sync-service`
  (`implementation`, for `SyncHealthStatus`'s constants, so no value is spelled twice) and
  `:storage-service-common` (`api`, since `RealmStorageState` is a parameter). The inputs are the
  Control verdict, the connectivity string and `RealmStorageState`, not booleans as §4 proposed:
  with the real types available, the precedence reads as Drive's does.
- **Read:** `SyncService.syncConnectivity()` (`:sync-service-client`), beside `syncHealth()`.
  ⚠⚠ Distinct, and `withOfflineDelay` is distinct again: the health row is rewritten whenever any
  of its figures move, so the same `offline` arrives every tick, and each repeat used to restart
  the 30 s wait — a device offline while the scheduler kept retrying never said so (review fix,
  pinned in `SuiteSyncProblemTest`).
- **Strip:** `SuiteSyncBanner` + `rememberSuiteSyncProblem` (`ui-core/.../feedback/SuiteSyncBanner.kt`),
  copy `suite_sync_*` in `:ui-core` (en + de). ⚠ **Control and storage keep each app's own
  banners** (`controlBanner` / `storageBanner` slots): their copy names the app and its install and
  update routes, and moving it is a separate step. What every app gained is the health half —
  locked, device not recognised, needs attention, offline, and the relay's `507` before the storage
  row catches up — plus one-at-a-time.
- **Mounted** in Photos, Fit, Timeline, Maps, Calendar (main screen; ⚠ its Calendars pane still
  shows only its Control banner, so there a fence outranks a lock — never a regression, it showed
  nothing else before), Contacts, Home and Mia. Home and Mia pass no Control verdict: they show
  Control trouble only as walls.
- **Drive** is unchanged in behaviour. Its connectivity mapping moved out of the repository into
  `syncHealthModelOf` (`FilesRepository.kt`) so `SuiteSyncOrderMatchesDriveTest` can compare
  Drive's banner with the shared evaluator over the same wire values (8 connectivities × 3 storage
  states × fenced or not). Mutation-checked: ranking needs-attention above storage turns it red.
  ⚠ Swapping `NoPlan` and `StorageFull` does *not*: a realm cannot be both, so their order is moot.
- **Tests:** `SuiteSyncProblemTest` (every connectivity value, precedence, the offline delay),
  `SuiteSyncBannerTest` (the 2026-11-12 state said in plain words; one strip; nothing for `None`),
  `SuiteSyncOrderMatchesDriveTest`.
- ⚠ **Not verified on a device yet.** The state needs a joiner the relay refuses; a test double
  reporting `device_unrecognized` is the cheap way, the OnePlus's next restart (until it is re-paired)
  the expensive one.

## 11. SS-3 as built (2026-11-12)

- **Signal:** `previewWakes(connectivity, catchingUp)` (`photos-shared-logic/.../data/PreviewWakes.kt`):
  one wake each time connectivity becomes reachable (`online` or `relay_retrying`) from anything
  else, and each time a catch-up ends. Never for a first value — a launch that starts online is not
  a recovery. `controlPreviewWakes(client)` reads Control's own rows: `syncConnectivity()` and
  `catchUp(RuntimeIdentity.CONTROL_APP_ID)`, the id Control's runtime publishes under on Android and
  desktop.
- **Shell:** Photos' `App` folds it into a **count** (`runningFold` → `stateIn(Eagerly)`) and
  provides that through `LocalPreviewWakes`, so a screen of tiles is one subscription to Control.
  ⚠⚠ A count, not a one-shot signal (review fix): a tile reads it *before* its ask and races
  "count moved" against its backoff, so sync recovering while a slow `GetPreview` is in flight is
  not lost. With a no-replay `SharedFlow` that wake vanished and the tile waited out its full
  backoff — the symptom SS-3 exists to fix.
- **Tile:** `PhotoTile`'s retry races its backoff wait against a wake; a wake asks at once and
  restarts the backoff from its first step. ⚠⚠ A race of two flows, not `withTimeoutOrNull`: the
  latter's timeout is not scheduled on the frame clock's dispatcher, so under a test clock it never
  fired and the existing retry test went red. ⚠ The default `LocalPreviewWakes` never fires (never
  `emptyFlow()`, whose `first()` ends at once), so a tile with no provider backs off as before.
- **Tests:** `PreviewWakesTest` (recovery, first value, retrying, catch-up end) and two in
  `PhotoTileRequestSizeTest`: *a waiting tile asks again at once when woken* (red with the wake
  dropped from the race) and *a wake during the tile's own ask is not lost* (red with the count
  read after the ask).
- The catch-up half reads Control's heartbeat (`controlCatchingUp`, since SS-2), so a `true` left by
  a killed Control is discounted and its wake is not lost.
- ⚠ Not seen on a device yet.

## 12. SS-2 as built (2026-11-12)

- **Heartbeat (SS-D8):** Control's `initSyncService` passes `catchUpHeartbeat = true` on Android and
  desktop too (`control-shared-proxy/src/{androidMain,desktopMain}/.../PlatformProxyModule.kt`), so
  every Compose app reads the pull with `anyCatchUpHeartbeat()` and a killed Control's stale `true`
  is discounted as time passes.
- **Indicator:** `SyncActivityCapsule` + `rememberSyncActivity` (`ui-core/.../feedback/SyncActivityCapsule.kt`),
  over the iOS rule and timing (`evaluateSyncActivity`, `debounceSyncActivity` — `:ui-core` gained
  `:sync-service-common`). Copy `sync_activity_*` (en + de). A static `Sync` glyph, inverse colours,
  bottom-centre, fades. ⚠ Drawn with a background, not a `Surface`, so a tap on it reaches the row
  underneath (pinned; red with a `clickable`).
- **Mounted:** over the screens, never beside them — inside each app's content `Box` (Fit,
  Timeline, Contacts, Home, Mia, Maps), through `AdaptiveNavScaffold`'s new `overlay` slot (Photos),
  in a `Box` around the calendar (Calendar), in `FilesScreenContent` (Drive). Hidden behind any
  problem strip (SS-1's `syncProblem`; Drive's own banner), on screens that are not tabs, on Mia's
  Chat (as on iOS), and in Drive during multi-select.
- **Drive** shows the pull only (`FilesRepository.catchingUp()`, default never); uploads stay in its
  banner, as iOS's P2 split.
- ⚠ While it fades out it draws the last active state (review fix): drawing `Idle` shrank the pill
  to a bare glyph with an empty live-region label at the end of every catch-up.
- ⚠ Not mounted in Control's own Compose UI (iOS Control has it). Photos does not yet hide it
  behind its selection bar, as iOS does. In the rail layout, and in Maps, nothing insets it from the
  system navigation bar — the same gap the apps' FABs (`appFabInset`) have today.
- **Tests:** `SyncActivityCapsuleTest` (shows, hides, tap-through, the shared timing).
- ⚠ Not seen on a device yet.

## 13. SS-1b as built (2026-11-12)

- **No new view.** Every first-party iOS app already mounts `RealmStorageBanner`
  (`ios-storage/Sources/RealmStorageBanner.swift`), and the "Syncing…" capsule already hides on its
  text. So `RealmStorageSurface` (`tenshu-sdk-ios-core`) now decides that text with SS-1's
  `evaluateSuiteSyncProblem` over storage **and this runtime's own connectivity** (route 1: each iOS
  app runs its own scheduler), through `withOfflineDelay`.
- ⚠⚠ **Read in-process, never from the store's health row** (review fix). `SyncHealthStatus` is ONE
  row (`SINGLETON_KEY`) on the shared App Group replica, written by whichever runtime wrote last, so
  read from the store Photos said "Offline" or "locked" because Control or a sibling was — and the
  false strip hid Photos' real "Syncing…". `FirstPartyLocalRuntime.assembleBase` installs the
  runtime's own `SyncHealthHolder` stream, mapped by `SyncHealthFigures.connectivity()` — the rule
  `initSyncHealth` writes into the row, lifted out so the two cannot disagree
  (`SyncHealthConnectivityTest`). The row is only a fallback when no stream is installed. A per-writer
  row would also fix it, but is a reshape (HR #9) the strip does not need. Every iOS app gains locked,
  device not recognised, needs attention, offline and the relay's `507`, and the capsule hides
  behind them, with no change to any app's Swift root. The name stays: renaming ten mounts buys
  nothing.
- **Kotlin decides, Swift renders**, as before: `stripText` / `stripIsError` / `stripActionLabel`
  are pure (`RealmStorageSurface.kt`), and Swift gained one flow, `observeActionLabel()` — *Manage
  storage* for storage, *Open Control* for locked / not recognised / needs attention, none for
  offline. A mounted banner now holds three collectors (text, tone, label).
- **No Control verdict is passed**: each Swift root draws Control's banner itself, as ADR 0022/0025
  settled. ⚠ So on iOS the strip and the Control banner can both show — unchanged from the storage
  banner's behaviour before.
- **Copy** is English, the same sentences as `:ui-core`'s `suite_sync_*` (en) — iOS has no string
  catalogue for this surface yet, as the storage copy before it. ⚠ German owed with the iOS i18n
  rollout.
- `tenshu-sdk-ios-core` gained `:app-reachability` (`implementation`; not exported to Swift).
- **Tests:** `RealmStorageStripTest` (iosTest): every problem has words, the 2026-11-12 state is an
  error with *Open Control*, tones and buttons. Swift compiled with `scripts/ios-smoke.sh --build-only photos`.
- ⚠ Known, minor: on a device without Control (Drive-iOS works without it) the locked and
  not-recognised copy still names Control, with no button. The text, tone and label are three
  flows, so they can be a frame out of step.
- ⚠ Not seen on a device yet.

## 14. SS-4 — design (2026-11-12): previews downloaded ahead of the scroll, and the count said

Maintainer, 2026-11-12: *"Basically we know the numbers of images, right? Like downloading in this
case means a thumbnail arrived/got minted, right?"* Both halves need a correction before anything
can be counted, and the corrections are the design.

### 14.1 What the code does today

- **We know the number of photos this device has rows for** — Photos' shared index holds every one
  (`photos-app.md` §20). ⚠ Not the realm's number: a pull has no total
  (`ios-sync-activity-indicator.md` §3), so during a catch-up the count is still growing. Once the
  device is caught up (SS-2's `catchingUp` false), its rows *are* the library.
- **A thumbnail is minted once, on the device that holds the original** — at upload every still
  renders its whole ladder, `ThumbnailClasses.STILLS`: `LIST` (100 px, ~4 KB), `LARGE` (256 px,
  ~15 KB, cap 48 KB), the hero `XLARGE` (1024 px, ~139 KB, cap 512 KB) and `XXLARGE`
  (`UploadFileImplementation.kt`, `drive-synced-thumbnails.md` §19). §15.3's T-3 pass only backfills
  uploads older than that.
- ⚠⚠ **A device downloads a thumbnail only when something asks for it.** Since C7 every rung is
  `Syncable(OnDemand)` on the content plane (§11): a joiner fetches a blob when a tile's
  `GetPreview` reaches it, and keeps it (§11.2, "a re-visit is warm"). Scroll prefetch was not
  built (`photos-app.md` §30.2–§30.3: a read costs 18–21 ms, there is no latency to hide).
- ⚠⚠ **One thing already walks the library and asks: the on-device index.** Control's
  `MediaIndexPass` (Android and desktop) visits every image, and for one whose original is not on the
  device pulls its **`XLARGE`** rung through `onDemandResolution` and keeps it (`IndexPreview.kt`,
  `MediaLibrarySeams.kt`, `MediaIndexer.kt`), on an unmetered network (`MediaIndexSchedule.kt`), with
  its progress in `IndexProgressHolder` (the Models screen's bar). ⚠ It runs **only** once the user
  has consented and a model is installed (`inference-service.md` IN-D3), so on most devices nothing
  walks — and on an opted-in one the hero rung of the whole library is already arriving.
- So **"downloaded" today means "a tile once asked"** (or, opted in, "the index reached it"), and the count the first sketch proposed —
  rows without their rung — is the count of photos the user has not scrolled past. On a phone with a
  5,000-photo library that number stays in the thousands for ever; it never reaches zero, so it can
  never say "done". ⚠ That is the trap this section exists to avoid.
- **What a phone tile actually draws** is the hero rung, not `LIST`: a 3× phone's ~400 px tile asks
  for `XLARGE` (`drive-synced-thumbnails.md` §16.1, `PhotoTileRequestSizeTest`), and a smaller stored
  rung is served as a stand-in until it arrives (`PhotoPreview.standIn`). A `LARGE` stand-in is soft
  but recognisable; a grey square is neither.

### 14.2 The design

Two pieces, in this order — the second is meaningless without the first.

**(1) A preview backfill on the receiving device (SS-4b).** Control walks the library's still
photos and fetches each one's **`LARGE`** rung that is not on this device, through the existing
on-demand path (the same fetch a tile triggers, so no second implementation and no new wire).
⚠⚠ **It is `MediaIndexPass`'s shape, not a new one:** the same library seam
(`MediaLibrarySeams`), the same `onDemandResolution` pull, the same unmetered schedule family —
without a model, without consent (it reads nothing a tile would not), and for the cheaper rung.
⚠⚠ **Done means `LARGE` present — never "`LARGE` or `XLARGE`".** The local cache keeper evicts
every rung above `LARGE` when the cache passes its limit (1 GiB by default; `ThumbnailClasses`
`backfilled = true`, `ThumbnailCacheSidecars.isEvictable`), and `LIST`/`LARGE` never
(`backfilled = false`). So an `XLARGE` the index pass pulled can vanish, and a count that accepted it
would go backwards or never finish on a library larger than the cache. `LARGE` cannot be evicted,
so a still counted once stays counted. On an opted-in device that costs ~15 KB a still beside the
index's `XLARGE` — the price of a count that converges.

- **Why `LARGE`**: the smallest rung a phone tile can draw as a recognisable stand-in, at ~15 KB —
  5,000 photos ≈ 75 MB. `LIST` (~4 KB) is too small to stand in for a 400 px tile; `XLARGE`
  (~139 KB, ≈ 700 MB for 5,000) is what a scroll still fetches on demand, as today.
- **Who**: Control's proxy, not Photos — it holds the content plane, it runs without Photos open,
  and it is the one process on Android and desktop that can do this once for every app. On iOS
  (route 1) Photos' own runtime does it, in its foreground and its overnight `BGProcessingTask`
  (`photos-ios.md`, PR-6) — ⚠ which today is declared with **no network** and sits behind a flag
  (`photos-ios.md` §PR-6), so a download pass needs it re-declared with network connectivity.
- **When**: a `ScheduleItem` beside `registerContentBackfillSchedule`, **unmetered** (it downloads),
  not charging-gated (it is cheap next to the T-3 mint), and never while a catch-up is running — the
  rows come first, and a backfill racing the pull would be counting a moving total.
- **Budget per run** in files and bytes, resumable, a marker per device; ⚠ never
  correctness-bearing (§15.3's rule): a device that never runs it has today's on-demand grid.
- ⚠ **It stops at the free-disk floor** (`LocalCacheEviction`'s `minFreeSpaceBytes`) — the device's
  own space, not the cache limit, which `LARGE` is outside. A stopped pass is not a running pass, so
  the count is not shown (below) and nothing says "Downloading" over a pass that cannot move; it
  resumes on a later run when there is room.
- **A miss is counted once, not retried for ever.** A still whose `LARGE` cannot be fetched — never
  minted (an upload that could not decode it), its byte holder gone, a declined render — is recorded
  as **unavailable** for this pass. Otherwise the counter stops at "4,998 of 5,000" for ever.
- ⚠ Data policy: unmetered by default is the proposal; whether a phone on cellular may fetch ~15 KB
  per photo is the maintainer's call (§14.5 Q1).

**(2) The count, said (SS-4c).** While the backfill runs, Photos shows its progress in the SS-2
capsule: *"Downloading previews · 1,240 of 5,000"* — `SyncActivity` gains a third active kind
beside `CatchingUp` and `Uploading` (a Kotlin type in `:sync-service-common`, not a schema type).

- **Total** = still photos this device has rows for, taken when the pass starts (a fixed
  denominator — `SyncBanner.Syncing`'s lesson: a total that grows under its own count reads as a
  bug). **Done** = a `LARGE` rung present (never `XLARGE`: it can be evicted, above) + unavailable. Both are **local facts on the device that runs the
  backfill** — a directory of rungs and a row count — never a relay question (SS-D6).
- **Published** as a local-only derived row the way `SyncHealthStatus` is, so Photos reads it across
  IPC on Android and desktop. ⚠⚠ A new composite and relation on a service is a reshape: **HR #9
  bump + empty `SchemaMigration`** in the same PR, and on iOS the per-writer key (§13's lesson —
  never one shared row).
- **Precedence**: `Uploading` > `CatchingUp` > previews. The first two are `evaluateSyncActivity`'s
  shipped rule (uploading wins in every app — it carries a count); previews, the convenience, rank
  last.
- Shown **only while the pass is fetching** — not between runs, not when it stopped at the disk
  floor or a budget, not when it is done (§3 principle 5) — so it can never sit on screen over a pass
  that is not moving. A caught-up phone with a complete library opens Photos to nothing.

### 14.3 What this is not

- **Not a realm-wide progress bar.** The relay cannot tell one app's blocks from another's (§5.2);
  this counts one app's *content* on one device.
- **Not the hero rung for the whole library.** `XLARGE` stays on demand; the backfill buys
  recognisable tiles, not sharp ones.
- **Not prefetch on scroll** — §26.3's argument (a read costs 18–21 ms, nothing to hide) is about
  the scroll and stands; this is about a library the user has not reached yet, which §26.3 named as
  the unmeasured case.

### 14.4 Phases

| Phase | Scope | Done when |
|---|---|---|
| **SS-4a** | **Measure first**: `LARGE` size distribution over a real library; fetch time per blob on Wi-Fi from a phone; the relay's egress for 5,000 GETs; how many stills have no `LARGE` at all (the "unavailable" share); and on an opted-in device, how much of the library the index pass has already pulled (`IndexProgressHolder`) | the five numbers are in this section, and §14.2's budget and policy are set from them |
| **SS-4b** | The backfill: proxy-side pass, unmetered `ScheduleItem`, budget, marker, unavailable ledger; iOS in Photos' own runtime | a joiner with a 5,000-photo library shows recognisable tiles on its first scroll, offline |
| **SS-4c** | The count: derived row (HR #9 bump + empty migration), `SyncActivity` previews kind, capsule copy (en + de), iOS per-writer row | Photos says *"Downloading previews · N of M"* while the pass runs, and nothing after |

### 14.5 Open questions for the maintainer

1. **Cellular**: may a phone fetch ~15 KB per photo on a metered network (≈ 75 MB per 5,000), or
   unmetered only? Proposed: unmetered only, with a later setting if asked for.
2. **Videos**: their poster is a still rung too — in the same pass, or after stills? Proposed: in
   the same pass (it is the same fetch), counted in the same total.
3. **Drive**: Drive's grid view draws the same rungs. Run the pass for every file with a still
   rung, or Photos' stills only? Proposed: Photos' stills first; Drive later if asked.
4. **One pass or two** on an opted-in device: let the index pass's `XLARGE` pulls stand in for the
   backfill (it is already walking), or run the cheap `LARGE` pass first so tiles become recognisable
   before indexing reaches them? Proposed: `LARGE` first — minutes, not the index's hours.

⚠ **Not a question: eviction.** It is built (`OfflineKeeperImplementation`, an `.evicted` sentinel,
1 GiB default) but covers only rungs **above** `LARGE` — `LIST` and `LARGE` are never evicted
(`ThumbnailCacheSidecars.kt`, `ThumbnailClasses.kt`), so a `LARGE` backfill cannot fight it. An
`XLARGE` pulled by the index pass can be evicted, which is why "done" counts `LARGE` only.

