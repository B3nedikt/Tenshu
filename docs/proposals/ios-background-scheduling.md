# iOS background scheduling and notifications

**Status:** v0.3 (2026-10-02). **S0 is built (§4.1.1)**; its four install blockers are answered in
code by S0b, the SQLite shared replica, which every iOS build has bound since 2026-11-05
([`ios-shared-store-sqlite.md`](ios-shared-store-sqlite.md) B.22). ⚠ Blocker (b)'s device check
(the bench pair against `0xdead10cc`) has not been run. S1–S4 are design
only. ⭐ **D1–D6 decided by the maintainer the same day (§7)**: App Group now, a per-item lease,
time-sensitive reminders, **APNs wakes yes**, a background wake runs headless, routines stay
display-only until S1–S3 exist. Written the day the iOS apps moved to the paid company team, which
is what makes most of it possible.

**Relates to:** [`core-background-processing.md`](core-background-processing.md) §5.5 (which left
iOS "blocked on iOS architecture"), [`ios-drive-parity.md`](ios-drive-parity.md) §15,
[`ios-notification-elevation.md`](ios-notification-elevation.md) §5.4 and §12,
[`ios-route1-replica-scope.md`](ios-route1-replica-scope.md), [`routines-engine.md`](routines-engine.md),
[`calendar-app.md`](calendar-app.md) §4.3, [`sync-relay-api.md`](sync-relay-api.md) (the APNs/FCM note),
[`sync-push-wake.md`](sync-push-wake.md) (the S4 design),
[`docs/how-to/ios-signing.md`](../how-to/ios-signing.md) §6.

---

## 1. Why now

`PlatformScheduler` is the seam every scheduled feature goes through. Android binds
`WorkManagerScheduler`; iOS binds `NoOpPlatformScheduler`. The interface calls itself advisory, "a
platform that ignores them loses only app-closed wakeups", and on Android and desktop that is a small
loss. On iOS it is the whole loss: an iOS app that is not on screen is not running, so **every
registry item on iOS runs only while some Tenshu app is in the foreground**, and every constraint
(idle, charging, unmetered) and every `Punctual` instant is dropped.

Three things were worked around rather than solved, each by hand, each in one app:

| | What exists | Where |
|---|---|---|
| Drive | `BGAppRefreshTask` (15 min earliest) for the sync drain, a background `URLSession` for uploads | `drive-ios/Sources/BackgroundSync.swift`, `BackgroundUpload.swift` |
| Control | `BGAppRefreshTask` (30 min) for pending requests | `control-ios/Sources/ControlBackgroundRefresh.swift` |
| Fit | `BGAppRefreshTask` for the health import | `fit-ios/Sources/FitBackgroundRefresh.swift` |
| Calendar | local notifications **pre-armed** while the app runs (64-pending cap) | `app-notify` `IosNotificationPresenter`, `AppleAlertSchedule` |
| Timeline | CLVisit / significant-location relaunch | `timeline-ios` |

No `BGProcessingTask`, no remote push and no relay wake path exist anywhere.

Until 2026-10-02 the apps were signed with a free personal team. That team cannot use App Groups or
push, and its profiles expire after seven days. The company team removes those limits, and the
repo does not reflect it yet (§2).

## 2. What the inventory found (2026-10-02)

Read from the code by a research pass and spot-checked; file references are in the PR that added
this document.

- ⚠⚠ **A device build has no App Group, so there is no shared replica on a real iPhone.** (True
  when written; S0 closed it, §4.1.1.) Every
  `*.device.entitlements` carries Keychain groups only. `sharedReplicaDirectory` falls back to the
  app's own Application Support directory, so each installed app keeps **its own store**, syncs it
  from the relay itself, and does so under **one shared device id** (`FirstPartyLocalRuntime` adopts
  Control's identity). The cross-process `SharedReplicaDatabaseLock` then locks a file no other
  process opens. The simulator, which does declare `group.app.tenshu.shared`, is the only place the
  shared replica has ever existed.
- ⚠⚠ **Every process runs every job.** Each route-1 app builds the full proxy graph, so each runs
  `SyncScheduler`, `ScheduleDrainLoop` and every registered item. Nothing elects one. What exists is
  per-feature: Calendar overrides the notification presenter so only it posts reminders, Control has
  its own pending-request alert handle, and "Drive is the background syncer" is a comment.
- **Constraints are stored and not enforced in process** (`ScheduleDrainLoop`), on every platform.
  On Android the OS enforces them; on iOS nobody does, so the idle-and-charging media index runs
  whenever an app is open.
- **Reclamation is dry-run on iOS** (`RECLAMATION_SWEEP_ENABLED = false`), and thumbnail backfill is
  not registered.
- **The relay cannot wake a device.** No push token, no APNs/FCM, no wake endpoint. The threat model
  has no text on push; `sync-relay-api.md` calls OS push "best UX, worst privacy footprint" (an A6
  timing leak to Apple and Google) and puts it out of the protocol as a possible adjunct.
- **Two docs disagree on a sealed Control:** `ControlBackgroundRefresh.swift` says its default is
  headless, `BackgroundSync.swift` says Control stays sealed until unlock. D5.

| Feature | Android | iOS today | iOS needs |
|---|---|---|---|
| Sync drain | WorkManager + expedited | foreground; Drive/Fit refresh, ~15 s | one owner (§4), a completion signal |
| Calendar reminders | AlarmManager (`Punctual`) | pre-armed, refreshed only while Calendar runs | background re-arm (§5.3) |
| Routines | AlarmManager + engine | not hosted (2026-10-02: iOS never executes) | D6 |
| Declaration backfill, slice snapshots | WorkManager | in process, in every app | `BGProcessingTask` + owner |
| Reclamation sweeps | enabled | dry-run | owner, then a device pass |
| Photo backup | `BackupWorker` 6 h + content trigger | "Back up now", Drive's wake, background `URLSession` | photo-library change observer, `BGProcessingTask` |
| Media index | idle + charging | registered, constraints ignored | `BGProcessingTask` (`requiresExternalPower`) |
| Thumbnail backfill | WorkManager | not registered | decide with the media index |
| Timeline capture | foreground service + watchdog | CLVisit / SLC relaunch | a write outbox |
| Fit health import | (not checked) | `BGAppRefreshTask` | HealthKit background delivery |
| Offline keeper, move replay, ICS refresh | WorkManager | in process only | `BGProcessingTask` |
| Map region downloads | foreground-service liveness | foreground only (inferred) | background `URLSession` |
| Pending requests (Control) | post-drain hook | Control's refresh task | push, if D4 says yes |

## 3. What iOS allows — the fixed points

A design that ignores these is a design for Android.

- **The OS decides when, not the app.** `BGAppRefreshTask` gives roughly 30 seconds, at a time iOS
  picks from how often the user opens *that* app. `BGProcessingTask` gives minutes, usually on power
  and idle, and can require network and external power. Neither can be made exact.
- **Each app is woken separately.** There is no "wake the suite". An app the user never opens gets
  little or no background time, whichever app we would prefer to do the work.
- **Nothing runs at an exact time without the user or a push.** A local notification fires at an
  exact time but runs no code until tapped. Only a notification action, a silent push or a
  time-sensitive entitlement moves that.
- **At most 64 pending local notifications per app.**
- **A silent push is throttled and is not delivered to a force-quit app.** It is a hint, not a
  guarantee.
- **Location, HealthKit and background `URLSession` relaunch the app on their own events.** Timeline,
  Fit and Drive's uploads already ride these.

So iOS can offer **eventually, when the OS agrees**, and **exactly, as a notification**. It cannot
offer Android's "at 07:00, run this code".

## 4. Foundations

### 4.1 One replica: the App Group on device builds (S0)

Add `group.app.tenshu.shared` to every `*.device.entitlements` and register the group with the
company team. `sharedReplicaDirectory` then resolves to the group container on a device as it does
on the simulator, and the flock starts excluding something (superseded in S0b: the replica's locks
become SQLite's, [`ios-shared-store-sqlite.md`](ios-shared-store-sqlite.md)).

- ⚠ **It moves the replica.** Each app's current store is in its own container and would be
  orphaned. The stores are replicas of relay state, so the proposal is no migration: the first app to
  launch after the change populates the group container from the relay, as a fresh install does.
  What must not be orphaned is anything **unsynced**: the pending content journal, Timeline's raw
  fix journal, a parked upload. S0's work is finding those and either draining them first or moving
  them.
- ⚠ **It is a prerequisite, not an optimisation.** §4.2's lease lives in the group container. Without
  it there is nowhere two processes can agree on anything except the Keychain.
- ⚠ [`ios-route1-replica-scope.md`](ios-route1-replica-scope.md) ("no decisions taken") asks what
  each app's replica should hold. With one store that question changes shape; settle it with D1.
- ⚠⚠ *(Since 2026-11-05 S0b is in every build, B.22; the paragraph is kept as the reasoning.)*
  **The App Group build is not safe to use on a device until sub-phase S0b is in it**
  ([`ios-shared-store-sqlite.md`](ios-shared-store-sqlite.md), which supersedes
  [`ios-scheduler-lease.md`](ios-scheduler-lease.md) §5; the store is SQLite). One shared store means one app can
  save from a stale in-memory base over rows another app just wrote, and iOS terminates an app
  suspended while it holds a lock on a file in the shared container. S0b puts the replica in one
  SQLite database whose transactions start from a current base, and puts a gate in front of its
  locks. S0's entitlement change can merge first; it must not be rolled out first. ⚠⚠ Apps on
  different schema versions over one store (M12) are **decided**: no build writes, migrates or wipes
  a store stamped newer than itself, checked at open and at every epoch change
  ([`ios-shared-store-sqlite.md`](ios-shared-store-sqlite.md) §7, D5). Until S0b is built, today's
  code still has an older build delete a store a newer one migrated.

#### 4.1.1 S0 as built (2026-10-02)

> ⭐ **Update 2026-11-05: the four blockers below are answered in code.** S0b's SQLite store is the
> only iOS shared replica, in every build ([`ios-shared-store-sqlite.md`](ios-shared-store-sqlite.md)
> B.22; the file store is deleted, B.22.9). (a) and (c): SQLite transactions, one per access, with
> the `meta.epoch` fence and the chunked wipe and migrator. (b): S0b-2's gate, quiesce and
> token-expiry interrupt (B.5–B.15). (d): D5, a store stamped newer is refused. ⚠ Still owed:
> (b)'s bench pair on a phone (§13's S0b-2 row), and every other device step in that doc. ⚠ No
> migration chain is registered on the SQLite store (B.13 (167)), so a store behind a build is
> wiped and re-synced, not migrated. The box below is the record of 2026-10-02 to 2026-10-06.
>
> ⚠⚠ **Not safe to install yet** (maintainer decision, 2026-10-02). S0 is merged; an App Group
> build must **not be installed on a device** until all four of these exist. ⚠⚠ **As of
> 2026-10-06 App Group builds are on the maintainer's iPhone, and blocker (b) has been observed
> there:** `DriveIos` (three times, 2026-10-06) and `PhotosIos` (once, 2026-10-05) were terminated
> with `0xdead10cc`. The maintainer accepts that until S0b ships
> ([`ios-shared-store-sqlite.md`](ios-shared-store-sqlite.md) §4.1). The four:
>
> - **(a) Store self-protection.** A generation the store advances on every write; a process that
>   is behind re-reads before it proceeds.
> - **(b) The lock gate and the quiesce step** against the suspended-lock termination
>   (`0xdead10cc`, below).
> - **(c) Every path that wipes, migrates or deletes replica files outside a locked live store
>   brought under the writer lock.** `FileObjectStore.open` runs unlocked and can wipe or migrate a
>   store a sibling has open; `wipeAllObjects` and `wipeFileObjectStore` likewise.
> - **(d) ⚠ Schema migration of a shared store.** With one store, an app updated to a newer schema
>   can migrate it under an older sibling that still has it open, or is launched later. This was the
>   maintainer's named blocker; decided 2026-10-03 (below).
>
> (a)–(c) were designed in `docs/proposals/ios-scheduler-lease.md` §5 (merged as a draft,
> with three findings open). **Since 2026-10-03 S0b's design is
> [`ios-shared-store-sqlite.md`](ios-shared-store-sqlite.md)**: the shared replica is SQLite, which
> covers (a) and (c) with the engine's transactions plus an epoch and a sequence, keeps (b), and
> answers (d) (its §7, decision D5: refuse a store stamped newer; accepted by the maintainer 2026-10-03).

**The entitlement.** All nine `*.device.entitlements` (Control, Drive, Fit, Timeline, Maps,
Contacts, Calendar, Photos, Home) declare `com.apple.security.application-groups` =
`group.app.tenshu.shared`, the same bare string the simulator files and the Kotlin
`TENSHU_APP_GROUP` use (an iOS App Group id is global and takes no team prefix). No `project.yml`
gates it by SDK beyond the existing `CODE_SIGN_ENTITLEMENTS[sdk=iphoneos*]` switch.

- ⭐ **No portal step for the group.** Building for a device with `-allowProvisioningUpdates` on
  team `752LWAZMKZ` made Xcode register the group and mint an explicit profile carrying it
  (`iOS Team Provisioning Profile: app.tenshu.control.ios`,
  `com.apple.security.application-groups = [group.app.tenshu.shared]`). Seven of the nine apps
  built and signed this way (Drive is the next point; Home is below). Before this most of them signed with the team's wildcard profile,
  which cannot carry an App Group, so each now needs its own explicit App ID.
- **Drive signs with the group under its own id — since 2026-10-11.** For nine days it could
  not: `app.tenshu.drive.ios` was refused to the company team (*"Failed Registering Bundle
  Identifier … not available"*) because the **personal** team had registered it on 2026-10-02
  (13:13 UTC, a profile for `FAYT82GWD7.app.tenshu.drive.ios` and one for `….uitests`) — which is
  what a device build does when `TENSHU_TEAM_ID` is unset and `local.properties` still names the
  old team. ⚠⚠ That is the lesson that outlives it: **an app id belongs to one team, and a single
  device build under the wrong one takes it** (`how-to/ios-signing.md`).
  In the meantime the maintainer's phone ran Drive as `app.tenshu.drive.ios2`, a local,
  uncommitted change. The id was released (a company-team profile for it exists from
  2026-10-10), and on 2026-10-11 a device build from master signed as
  `752LWAZMKZ.app.tenshu.drive.ios` with the App Group and the two Team-prefixed Keychain groups,
  came up on the realm already in the group container, and the `.ios2` app was uninstalled.
  Nothing in the repo ever carried `.ios2`. ⚠ The `.ios2` build had named the same Keychain group
  (`752LWAZMKZ.app.tenshu.drive.ios`, read off its signature), so both ids used one Keychain
  group. ⚠ Not checked beyond that: whether anything the `.ios2` app kept in its own container
  was still wanted when it was uninstalled.
- **Home** gained a route-1 runtime with a device entitlements file that had no App
  Group; it has the group now. ⚠ Its device build with the group has **not** been signed: Xcode
  had no developer account signed in when it was added (*"No Accounts"*), and Home had only ever
  used the wildcard profile, so there was no explicit profile to fall back on.
- ⚠ **Mia is untouched** (mock-only on master, no entitlements file). The day it gets a runtime it
  needs a `Mia.entitlements` and a `Mia.device.entitlements` with the same two keys as the other
  nine (`keychain-access-groups`: its own id and `app.tenshu.shared`, Team-prefixed on
  device; `com.apple.security.application-groups`: `group.app.tenshu.shared`), wired through
  `CODE_SIGN_ENTITLEMENTS` and `CODE_SIGN_ENTITLEMENTS[sdk=iphoneos*]`, plus the
  `TenshuSharedKeychainGroup` Info.plist key. Without the group a device build of it now throws at
  the first store open (next point), which is the intended way to find out.

**The fallback is a failure on a device.** `sharedReplicaDirectory` used to answer
`group container ?: the app's Documents`, on every build. It now throws
`SharedReplicaUnavailableException` when a **device** build (`iosArm64`, decided by the compilation
target) resolves no container, and keeps the app-local fallback only on the simulator slices, where
it is the intended configuration for the headless test host and the mock-only apps (reported once
per process). Failing was chosen over reporting because a device build without the group is a
signing defect, not a user state, and running anyway is the exact condition that went unnoticed: N
stores under one device id, each syncing its own copy, with the reclamation barrier counting sibling
stores as "self". It matches how the route-1 install path already treats a runtime that cannot come
up (`DriveLocalRuntime.install`: name the cause, then let it fail).

**The inventory.** "Today" is a device build before S0 (`Documents/` in each app's own container).
Everything resolved through `sharedReplicaDirectory` moves to the group container **empty**;
nothing else moves.

| State | Before S0 (per app) | After S0 | Effect |
|---|---|---|---|
| Realm rows, `DeviceSetting` rows, sync / tombstone / backfill cursors (reserved store rows), `.formatversion`, `.serviceversions` | `Documents/objects/` | group `objects/`, shared | **Re-pulled.** The cursors live in the store, so an empty store reads cursor 0 and resyncs; the sidecars are re-stamped on first open |
| Outbound queues: pending blocks (also exempt and per-shared-realm), tombstones, rekey tombstones, lane deltas | `Documents/objects/` | not carried | **Stranded** if non-empty: the writes never reach the relay |
| Pending content-emit journal (`PendingContentEmit`) | `Documents/objects/` | not carried | **Stranded** if non-empty |
| Source bytes of a not-yet-published upload, workout streams, `.unsent-content/` markers | `Documents/files/` | not carried | **Stranded**; if the metadata block was already pushed, peers hold a `File` whose bytes exist only here |
| Drive background-upload journal and staged chunks | `Documents/upload-staging/` | group `upload-staging/`, empty | **Stranded.** A transfer already inside `nsurlsessiond` still reads its old path and can finish, but its completion is acknowledged against the new, empty journal and a failure is never retried |
| A realm not yet registered with a relay (genesis before a storage subscription) | Control's `Documents/objects/` | not carried | **Stranded, all of it:** there is no relay copy to re-pull |
| Deferred moves (`PendingMove`), one-shot `ScheduleItem`s | `Documents/objects/` | not carried | **Stranded.** Periodic schedule items re-register at init; whether a queued one-shot is regenerated was not established |
| Offline keeper: pin set (`file.sync.*` device settings) / pinned bytes | `objects/` / `files/` | re-pulled / re-downloaded | Unaffected beyond bandwidth |
| Photo-backup preferences and cursor | `objects/` (device settings) | re-pulled | Unaffected |
| Thumbnails and sidecars, plaintext cache, embedding and face logs | `Documents/files/` | group `files/`, empty | Lost, all derivable (re-fetch, re-index) |
| Map region and model **bytes** | `Documents/files/` | group `files/`, empty | Lost, re-downloadable. ⚠ Their install **index** is in each app's Keychain and does not move, so it still lists them: remove and download again |
| HLC watermark (`IosSharedHlcWatermarkStore`) | `Documents/sync-hlc/` | group `sync-hlc/`, empty | Reset with the store it describes, which is its invariant. Not carried over on purpose: a watermark above what the new store has seen would forge the tombstone-GC ack. ⚠ The first tips may sit below the last one published |
| Writer lock | `Documents/replica.writer.lock` | group root | No state. It now excludes something |
| Timeline raw fix journal | none on iOS (Android only) | — | Nothing to strand |
| Timeline open-move journal; Fit recording journal and recorded-workout outbox | the app's Application Support | same | **Unaffected**, and they drain into the shared store |
| Fit HealthKit import state | no anchors; `HealthImportRun` rows in the store | re-pulled | Unaffected; the importer re-reads its window |
| Calendar armed reminders | the OS (`UNUserNotificationCenter`), no bookkeeping | same | Unaffected; re-derived at the next reconcile |
| `UserDefaults` (all `.standard`, no suite): Timeline capture / Wi-Fi / geocoding opt-ins and Wi-Fi fingerprints, basemap renderer, Control install sentinel and app-lock preference | the app's own defaults | same | **Unaffected** |
| Keychain: per-app custody (device id, A4 key, joiner payload, realm latches) and the shared key hand-off | Keychain | same | **Unaffected.** The latches meeting an empty store is the state a format-version wipe already produces |

**What a device actually has to lose.** The bundle ids changed with the team (to `app.tenshu.<app>.ios`), so the
replicas the free-team builds filled sat in *other apps'* containers (`app.tenshu.driveios`, …),
which a new build can neither migrate nor delete; they go when those apps are deleted. What a new
build can reach is what a company-team build wrote between that change and S0, and on the test phone that
is not nothing. Read from the phone on 2026-10-02 (listing only, nothing changed): no free-team app
is installed any more; Control, Drive, Fit and Photos hold app-local replicas of about 2,500,
31,000, 1,100 and 2,900 rows (Drive's still growing, so it was syncing); Timeline, Maps, Contacts
and Calendar hold none.

**The old stores.** ⚠ *History: `reclaimLegacyAppLocalReplica()` and its
`TENSHU_DISCARD_LEGACY_REPLICA` override are deleted since 2026-11-05 (B.22.9, the 1.0.1 re-cut:
every test phone wiped). Nothing removes a pre-App-Group app-local replica any more; deleting the app
does.* `reclaimLegacyAppLocalReplica()` runs when Control and each route-1 runtime
assemble, once the container resolves. It removes the app-local `objects/`, `files/`,
`upload-staging/`, `sync-hlc/` and `replica.writer.lock` **only when they are empty shells** (no
`.obj` row, no content file, no staged chunk, an empty journal), and only when it could look: a
listing or a stat that fails (a background launch before first unlock, a transient error) is
`UNREADABLE` and keeps everything, because unknown is not empty. Anything else is left alone and
reported once per launch on the console, because the table above says a non-empty store can hold
the only copy of something and nothing short of opening it with a realm-keyed graph can show it
does not. That would be the migration D1 declined.

- ⚠ **So a full abandoned replica is not cleaned by code on its own.** It takes a person: launch
  the app once with `TENSHU_DISCARD_LEGACY_REPLICA=1` in its environment
  (`devicectl device process launch --environment-variables …`) after making sure the old build
  drained, or having accepted the loss. Deleting the app does the same.
- ⚠ **Drain before installing.** The cutover that loses nothing is: open each pre-S0 app online
  until its uploads and sync are idle, then install the S0 builds, then discard.
- The threat model already describes the replica, the content cache and the upload staging as
  living in the App-Group container (A5, the C4e, P4 and P3b amendments), and names no purge duty
  for a replica (the A4 duty is the per-app Keychain custody). S0 makes a device match that text;
  it was not amended.

**Not verified: a lock held across suspension.** iOS terminates an app that is suspended while it
holds a lock on a file in a shared container (`0xdead10cc`). `SharedReplicaDatabaseLock` takes a
blocking `flock` around every store access, nothing asks for background time, and nothing stops the
loops when an app is backgrounded. With app-local stores that was harmless; with the store in the
App Group container it is not, if Apple's rule covers a plain `flock` (it is documented for SQLite
and Core Data locks; unconfirmed when this was written, ⚠ **observed on a device since**: see the
note at the top of this section). The fix is not in S0: S0b owns the lock gate and the
quiesce-on-backgrounding step ([`ios-shared-store-sqlite.md`](ios-shared-store-sqlite.md) §4, on
SQLite, whose locks Apple documents the rule for). To look for it on a device: background an S0 app mid-sync, then list
the crash logs (`xcrun devicectl device info files --device <udid> --domain-type systemCrashLogs`)
for a report named after the app. When S0 was built that listing held none for any Tenshu app,
which said nothing, since no S0 build had run there then. ⚠ **It holds them now** (2026-10-06):
`DriveIos` three times on 2026-10-06 (04:31, 04:48, 05:19) and `PhotosIos` once on 2026-10-05
(17:27), each terminated with `0xdead10cc`.

**Owed: the device pass** (§6's verify line). What was checked is the signed bundles: the App Group
is in the entitlements `codesign` reads back and in the embedded profile. When this was written
(2026-10-02) no S0 build had been installed on the phone: it turned out to be paired and syncing,
so an S0 install is the one-resync cutover itself (new empty store, watermark reset, the app-local
stores above stranded), and that was the maintainer's to time.

⚠⚠ **As of 2026-10-06 that is no longer the state of the phone.** What is **observed**, from its
crash logs: App Group builds of the real apps are installed and share one replica, and `DriveIos`
and `PhotosIos` were terminated with `0xdead10cc` on the dates above, the Photos report showing
two threads inside `SharedReplicaDatabaseLock.withLock`. The maintainer accepts those kills until
S0b ships. **None of that is the device pass**, and no item of it is ticked by it: nobody ran or
recorded one. Still owed, as a deliberate, recorded pass on hardware: an app launches and resolves
the container, two apps read one store, a write in one is seen by the other, the store's lock
(SQLite's write lock from S0b on) excludes a second process, and the console line for a kept
replica; and whether the cutover above (the drain, the stranded app-local stores, the watermark)
went as described on this phone is **not known**.

### 4.2 One runner per job: a lease, not an owner app (S1)

> **Designed in [`ios-scheduler-lease.md`](ios-scheduler-lease.md)**, which supersedes the sketch
> below where they differ. The shared store protects itself (sub-phase S0b, now
> [`ios-shared-store-sqlite.md`](ios-shared-store-sqlite.md), which supersedes the lease doc's §5),
> and the lease only decides who runs (its §4). The differences
> that matter:
> - ⚠⚠ The lease is a **record with a heartbeat and a deadline**, not a lock held for the run,
>   and it is read and written **in the store's own transaction**, together with the registry row
>   (a table of the same SQLite database, `ios-shared-store-sqlite.md` §9.2). iOS terminates an app
>   suspended while it holds a lock on a file in a shared container.
> - ⚠⚠ Staleness is **not the lease's business**. Every store access first catches the process up
>   to the store's `seq` and `epoch` (`ios-shared-store-sqlite.md` §3), whichever job or app is
>   running.
> - ⚠ On iOS the sync drain is **not a registry item**: it is `SyncScheduler`'s own loop, so the
>   lease also has to wrap that loop's tick (its §6.2).
> - "Registered only in their app" cannot be expressed by where a row lives once there is one
>   store; app-local items are a Kotlin-only policy table (its §3.3).

"Control owns background work" is the tidy answer and the wrong one: iOS wakes the apps the user
uses, and nobody opens Control. "Drive owns it" fails the same way for a user who lives in Photos.

Instead, **any app may be woken, and a per-item lease decides who runs**:

- a lease **row** per `dedupKey` in the `schedule_lease` table of the shared SQLite store, read and
  written in the same transaction as the registry row, holding the holder token, its renewal time
  and the item's last completion time; a holder renews it by heartbeat, and no lock is held for the
  run ([`ios-shared-store-sqlite.md`](ios-shared-store-sqlite.md) §9.2;
  [`ios-scheduler-lease.md`](ios-scheduler-lease.md) §4 for the state machine);
- a woken app asks the registry what is due, takes each due item's lease, runs it, records
  completion, releases;
- an item whose lease is held is skipped; a lease not renewed within its TTL is stale and may be
  taken;
- the **same lease gates the in-process loop**, which is what ends "every process runs every job"
  in the foreground too.

⚠ Realm-wide items (sync drain, backfill, sweeps, media index) take the lease. App-local items (Fit's
HealthKit import, Timeline's capture, Calendar's re-arm) are registered only in their app and need
none.

### 4.3 `IosPlatformScheduler` (S1–S2)

> **The S1 half is designed in [`ios-scheduler-lease.md`](ios-scheduler-lease.md) §7–§8**: the wake
> runner is Kotlin with a one-line call from each app's `App.init()` rather than a Swift shim per
> app, the existing identifiers are kept (`app.tenshu.<app>.refresh`, not `<bundle>.refresh`), and
> "best-effort" is reported through a Kotlin-only `SchedulePromise` that reshapes no stored or wire
> type. ⚠ D5 needs no Keychain change (its §9); a wake before first unlock must read as *locked*,
> not as *not provisioned*.

One Kotlin binding in `iosMain` replacing the NoOp, and one Swift shim per app replacing the three
hand-written drivers.

| `ScheduleItem` | iOS |
|---|---|
| periodic or one-shot, light, network only | `BGAppRefreshTask` |
| any of idle, charging, unmetered, storage, long-running | `BGProcessingTask` with `requiresExternalPower` / `requiresNetworkConnectivity` |
| `Punctual` | a pre-armed local notification (§5.3); the code runs when the app next runs |
| `maxDataMb`, battery-not-low | checked in process at run time (no OS equivalent) |

- Each app declares **two** identifiers, `<bundle>.refresh` and `<bundle>.processing`, in
  `BGTaskSchedulerPermittedIdentifiers`. The scheduler keeps one pending request of each kind and
  re-submits on every run; the registry, not the OS, knows which items are due.
- ⚠ `register` stays advisory in the interface's sense, and the binding must **say what it could not
  promise**: an item iOS cannot honour as asked (an exact instant, a 15-minute period) is recorded as
  best-effort, so a settings screen can say "runs when iOS allows" instead of implying a schedule.
- ⚠ In-process constraint enforcement is owed on every platform (`ScheduleDrainLoop`), and on iOS it
  is what stops the media index running at 4% battery because an app happened to be open.

## 5. What it buys, feature by feature

### 5.1 Sync
The drain becomes a leased item any woken app runs. Drive's background `URLSession` keeps the
uploads. A drain that ends early because the 30 seconds ran out must report "not finished" so the
next wake continues, rather than recording success.

### 5.2 Maintenance: backfill, sweeps, media index, thumbnail backfill, backup
All `BGProcessingTask`, all leased. Reclamation stays dry-run until a device pass with one replica.
The photo-library change observer is what makes backup timely; without it backup waits for a wake.

### 5.3 Punctual: calendar reminders, and routines if hosted
- Reminders stay pre-armed local notifications. What changes is **who re-arms**: today only a running
  Calendar does, so a reminder synced from another device never arms until Calendar is opened. With
  §4.3, any woken app that takes the `calendar-rearm` lease re-arms. ⚠ Notifications belong to the
  app that scheduled them, so the lease holder must **be** Calendar or hand the schedule to it; the
  honest version is that Calendar's own refresh task does it, and other apps' wakes do not help.
- Notification actions (snooze, done) already run code on tap. A **time-sensitive** entitlement lets a
  reminder break through Focus; it needs the capability and a reason Apple accepts.
- A routine with a time trigger can be shown as a notification at its time. It cannot *execute* at
  its time. D6.

### 5.4 Push (S4, decided in D4)
⭐ **Designed in [`sync-push-wake.md`](sync-push-wake.md)** (Draft v0.1, 2026-10-02): the threat
model, the relay routes and storage, the APNs sender, which app holds the token, the client
handler, the phases and the decisions still open. The bullets below are the summary that document
started from. Where they differ, it is the one to trust.

A content-free APNs wake is the only way to make a remote change arrive promptly on an idle phone:
a pending request for Control, a routine fired elsewhere, a shared-folder invitation.
⚠ The relay cannot wake for a shared-folder invitation without learning who the grantee is, which
T-GR-05 exists to prevent ([`sync-push-wake.md`](sync-push-wake.md) §3.6).

- **What the relay would hold:** a push token per device and an APNs signing key. The token is new
  device metadata on the relay; the key is a new relay secret.
- **What Apple learns:** that this device has something to fetch, and when. That is the A6 timing
  leak `sync-relay-api.md` names. Coalescing (`apns-collapse-id`, a floor between wakes) blunts it
  and does not remove it.
- **What it must never carry:** block data, a realm id in the clear, a count. House Rule 1 is
  unchanged: the relay sees ciphertext only, and a wake says "pull".
- ⚠ **A threat-model amendment in the same PR** as the relay code (House Rule 2). Drafted in
  [`sync-push-wake.md`](sync-push-wake.md) Appendix A.
- ⚠ **Not a `RELAY_PROTOCOL_VERSION` bump, and no coordinated deploy.** v0.2 of this document said
  it was one. The token routes are purely additive, and `RelayProtocol.kt` says additive routes
  need no bump: the relay ships first and an older relay answers 404, which a client reads as
  "unsupported" ([`sync-push-wake.md`](sync-push-wake.md) §5.3).
- ⚠ Which bundle id receives the wake is not free: only that app is woken. With §4.2 any app can run
  the drain, so the token can belong to whichever app the user uses; registering one per app is
  simpler and multiplies the metadata. [`sync-push-wake.md`](sync-push-wake.md) §7 recommends one
  token held by Control, because a per-app token set is an app inventory at the relay (A37) and
  the pending-request alert can only be posted by Control. It records the tension with D2's
  reasoning and the measurement that settles it.

## 6. Phases

| | What | Needs | Verify |
|---|---|---|---|
| **S0** | App Group on device builds; inventory and drain of unsynced per-app state; one replica | D1 | all ten apps on the iPhone read one store; kill one, another sees its write |
| **S1** | ⚠ Preceded by **S0b**, which gates installing the App Group build: the shared replica on SQLite, its epoch and catch-up, the lock gate, quiesce, the fence lifecycle and the schema-skew rule, designed in [`ios-shared-store-sqlite.md`](ios-shared-store-sqlite.md) (sub-phases Q0, S0b-1 to S0b-4). Then the per-item lease, `IosPlatformScheduler` over `BGAppRefreshTask`, and the three hand-written drivers retired. Design, sub-phases S0b and S1a–S1c, decisions M1–M11: [`ios-scheduler-lease.md`](ios-scheduler-lease.md) | S0 merged but not rolled out, D2 | S0b: a row written in one app is seen by another with no relaunch, and neither overwrites the other. S1: two apps foregrounded, one drain per interval in the log |
| **S2** | `BGProcessingTask` items; in-process constraint enforcement; reclamation device pass | S1 | media index runs only on power; each task forced once with the debugger's simulated launch |
| **S3** | Calendar background re-arm; time-sensitive reminders; routine notifications if D6 | S1, D3, D6 | a reminder created on Android fires on a locked iPhone that has not opened Calendar |
| **S4** | APNs wake: relay token store, signing key, coalesced content-free push, threat-model amendment. Design and sub-phases P0–P4: [`sync-push-wake.md`](sync-push-wake.md) | D4, and that document's PW-D1 to PW-D8 | a pending request reaches a backgrounded Control in under a minute |

S0 and S1 are the load-bearing ones. S2 and S3 are mostly mapping. S4 is decided (D4) and is the
only phase that changes the relay or the security contract, so it carries its own wire spec and
threat-model amendment in [`sync-push-wake.md`](sync-push-wake.md) and may be built in parallel
with S0–S1.

⚠ S4's relay half and Control's token and handler depend on neither S0 nor S1, and they deliver
the pending-request alert. "A change made elsewhere is in Drive on an idle iPhone" needs S0 (one
store) and S1 (the lease) as well: before S0 a wake fills Control's own store only
([`sync-push-wake.md`](sync-push-wake.md) §8.4).

## 7. Decisions (maintainer, 2026-10-02)

- **D1. The App Group goes on device builds now, with no store migration.** The stores are replicas
  and the realm is test data. The cost is one resync per device and S0's inventory of unsynced
  state.
- **D2. A lease per item (§4.2), not a named owner app.** An owner app starves when the user does
  not open it.
- **D3. Request the time-sensitive notification capability**, for calendar reminders.
- **D4. APNs wakes: yes.** Background refresh alone is opportunistic, and nothing else makes a
  remote change arrive on an idle phone. Coalesced and content-free, as §5.4 describes.
  - ⚠ **It makes sync timely, not guaranteed.** A silent push is throttled by iOS and is not
    delivered to an app the user force-quit. The design must stay correct when a wake never comes:
    the push is a hint to pull, and the refresh tasks remain the floor under it.
  - ⚠ Owed with S4, in the same PR: the threat-model amendment (push token as relay-held device
    metadata, the APNs signing key as a relay secret, the A6 timing leak to Apple), and the choice
    between one token per app and one per device.
- **D5. A background wake runs headless.** It does its work with no UI and without waiting for the
  user to unlock Control; an app must never come forward because a job ran.
  - ⚠ Headless means the realm key material is readable while the app is in the background and the
    phone is locked. That is already how Drive's and Fit's refresh tasks run. S1 must confirm
    Control's Keychain accessibility class allows it and that app lock gates the **UI** only, and
    correct whichever of `ControlBackgroundRefresh.swift` and `BackgroundSync.swift` says otherwise.
- **D6. Routines stay display-only on iPhone for now.** Once S1–S3 exist, time triggers move onto
  this machinery: executed best-effort behind the lease, with a notification at the instant.

## 8. Not in scope

Live Activities and widgets (`home-and-mia-apps.md` defers WidgetKit and App Intents); critical
alerts (a separate Apple entitlement request); Android and desktop scheduling, which work; the web
runtime.
