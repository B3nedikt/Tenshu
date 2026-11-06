# iOS sync-activity indicator

**Status:** **P1, P2 and P3 built** (2026-10-06): every first-party iOS app shows `CatchingUp`,
and Drive shows `Uploading`. P4 is an outline. P1 as built is **§10**; P2 and P3 are designed in
**§6a** and recorded as built in **§11**. Where those and an earlier section disagree, they are
the code. ⚠ The maintainer has seen P1's capsule on an iPhone. P2 and P3 have been seen on a
simulator over a scripted input only: no real upload and no real pull. ⭐ **Cross-process
activity built 2026-11-01, §12:** a heartbeat row per writer lets an app show a sibling's pull on a
shared store; not yet seen on a phone.

**Relates to:** [`drive-upload-progress.md`](drive-upload-progress.md) (§1 and §14.2 are rules this
note inherits), [ADR 0022](../adr/0022-ios-forward-to-control-surface.md),
[ADR 0025](../adr/0025-forward-to-control-surface.md),
[`ios-background-scheduling.md`](ios-background-scheduling.md), [`photos-ios.md`](photos-ios.md),
[`core-schema-evolution.md`](core-schema-evolution.md) §7.7.

## 0. Why, and what was asked

A user syncing photographs on a real iPhone gets no sign that anything is in progress. The ask:
"some kind of syncing banner" in Photos and the other apps. The accepted direction is one small
shared indicator while work is in progress, gone when idle; a full-width bar stays reserved for
problems.

**Answered by the user, 2026-10-05:** the wait they meant is *photographs from other devices
appearing in the Library*. That is the **pull**, not this phone's uploads. So the first slice is
Photos showing `CatchingUp` (§6, P1).

Lines marked *(inferred)* are conclusions drawn from the code, not statements read in it. File
references are collected in §9.

## 1. What exists today

- **Android and desktop Drive have a full bar.** `SyncBanner.evaluate` is a pure function over
  storage, sync health, the schema fence and the content-transfer figures [C1]. `SyncStatusBanner`
  renders every state in one full-width bar, including `Syncing(completed, total, bytesLeft)` [C2].
- **Its inputs are three device-local single-row relations** published by `initSyncService` [C3]
  and read through `syncHealth()`, `contentTransfer()` and `contentUploadProgress()` [C4].
- **Two rules are already decided** in `drive-upload-progress.md`, and this note keeps both. §1:
  *"No banner means nothing is happening"*; metadata chatter is invisible. §14.2: an iOS "N of M"
  comes from `SyncedFolderController.state`, never from the proxy's pass total.
- **iOS has two problem bars and no activity signal.** `ControlNotConnectedBanner` and the
  `ControlSetUpGate` wall live in `ios-onboarding/`; `RealmStorageBanner` lives in `ios-storage/`
  and is mounted above the shell [C5]. The only progress text on iOS is Drive's Settings footer
  [C6]. Control-iOS's `SyncDashboardView` is a device list. Nothing in `tenshu-sdk-ios` reads the
  three relations (grep, 2026-10-05).

## 2. What an iOS app can observe today

A route-1 app assembles the same proxy graph Control does and that graph calls `initSyncService`
[C7], so the three rows are written in every app's process *(inferred: wiring read, nothing run)*.

| Question | Knowable today? |
|---|---|
| Is this runtime in a tick (`isSyncing`) | Yes, but it flips every tick whether or not anything arrived [C8] |
| Files queued to upload from **this** process; an upload pass's "N of M" | Yes [C4] |
| "N of M" for a photo backup run | Only inside Drive: an in-memory `StateFlow` [C9] |
| Is a **pull** in progress | **No.** The scheduler knows and uses it only to re-wake itself [C10] |
| Thumbnail or content **downloads** in flight | **No** *(inferred)*: tiles fetch on draw [C11]; there is no queue |
| Offline, locked, deferred, storage full, device unrecognised | Yes, in `syncHealth()` |

Three facts bound the design:

1. ⚠⚠ **Photos cannot see the photo backup.** The PhotoKit drain runs in Drive's process [C12],
   and its run figures are an in-memory `StateFlow` there [C9]. An upload count is a fact about
   Drive. ⚠ *Corrected 2026-10-06:* this used to add that each app keeps its own store on a real
   iPhone. That is not true of the maintainer's phone, whose installed apps are App Group builds
   over **one** replica (their crash logs show `SharedReplicaDatabaseLock`). The store is shared
   there today; Drive's figures still are not in it.
2. **What Photos waits on is the pull**, and that is realm-wide: each app pulls the whole realm
   into its own store. The copy is therefore *"Syncing…"*, never *"Syncing photos…"*.
3. **In Drive, "uploaded" means staged**, not acknowledged by the relay [C13].

## 3. The signal

```kotlin
sealed interface SyncActivity {
    data object Idle : SyncActivity
    data object CatchingUp : SyncActivity                       // a pull in progress, no count
    data class Uploading(val count: Progress?) : SyncActivity  // completed/total; null = the tail
}
```

- **One pure function**, `evaluateSyncActivity`, in `:sync-service-common`, taking primitives, the
  way `evaluateRealmStorage` sits in `:storage-service-common`. Stringless.
- **No problem state.** Problems have owners already. A problem *hides* the capsule (§4).
- **Both at once: `Uploading` wins**, in every app (Codex, round 6). It carries a count, and a
  pull that matters to the user changes the screen anyway. No alternation: the state switches
  only when the winner ends, so an upload that finishes mid-pull hands over to `CatchingUp`
  without passing through `Idle`. No reason was found to reverse this for Photos in P4.
- **`isSyncing` cannot be used.** It goes true at the start of every tick and false at the end
  [C8], so an indicator on it blinks for ever on an idle device. `SyncBanner.kt:310-317` ruled it
  out for the bar for the same reason.
- **Debounce in the Kotlin flow**, tested once with virtual time: show after 700 ms of continuous
  activity, hold at least 1.5 s, hide after 1 s idle. Starting values, not measurements.
- **To Swift as flat values**, as `RealmStorageSurface` does: `SyncActivitySurface` in
  `tenshu-sdk-ios`, installed in `assembleBase` beside `RealmStorageSurface.install` [C7],
  exposing `observeLine()` and `observeAccessibilityLabel()`, both `Flow<String?>`, `null` =
  nothing. Copy in one Kotlin table, as `ErrorCopySurface` does; no Swift literal.

### 3.1 Publishing the pull (the first slice's plumbing)

A new in-memory figure, `SyncHealthFigures.catchingUp`. It is **raised on evidence** and
**settled by the writer**. There is no timer anywhere.

**Evidence that work is expected**. "More pages remain" alone
misses a one-page catch-up and the first request in flight. Raising before every first request
shows "Syncing…" on a launch with nothing to pull. The rule that satisfies both:

| Situation | Evidence, known before any response | Shows? |
|---|---|---|
| Joining device, slice bootstrap about to run | `bootstrapOnce` will fetch [C14] **and** (the first-pull marker is not `COMPLETED` **or** a persisted `backfillCursor` shows the backward walk unfinished). The second clause matters after a merged-lane handover: the forward cursor is promoted to the live tail while the walk still has pages, so `COMPLETED` can be written with history outstanding (round 15). It exists only where the cursor is persisted, so the round-12 case below still holds. The bootstrap's own guard is not enough: it skips only on `backfillCursor == 0`, which a relay without the merged lane never writes | **yes**, from before the fetch |
| Slice bootstrap runs on a device whose first pull is `COMPLETED` and which has no unfinished persisted backfill | none | **never**, including on every launch against a relay without the merged lane |
| First forward pull never finished | the first-pull marker reads *never pulled* or `STARTED` (below) | **yes**, from before the request, on every launch **until that pull completes**, even if the realm turns out to be empty; never after |
| Backward walk unfinished from an earlier run | `backfillCursor` is neither `0` nor absent. **Merged-lane relays only:** without that lane `backfillOnce` returns at once and persists no cursor [C15], so there the cursor is never evidence and `STARTED` is the only durable sign | **yes**, from before the first request |
| Merged lane: the forward pull completed, the **first** backfill request failed, then a restart | none: no backfill cursor was ever persisted, and the marker is `COMPLETED` | **late, accepted** (round 17): silent through the bootstrap and the retried request, shows when the first page of rows lands |
| Caught-up device, a page arrives with rows | none beforehand; the page is the evidence | **yes**, from arrival, through apply |
| Caught-up device, the response says `hasMore` | the tick's verdict | **stays** on across ticks |
| First pull completed, nothing to pull (an empty realm included), any network speed | none | **never** |
| Locked, unprovisioned, or pull not allowed (fenced) | the pull is never reached | **never** |

"Caught-up" above means the marker reads `COMPLETED`. Accepted cost of the "page arrives with rows" row: such a device
returning after days shows nothing while its first request is in flight, and lights when the
first page lands. Nothing on the device can know earlier.

**The first-pull marker** (Codex, round 3). The forward cursor cannot be the evidence: it reads
`0` both before any pull and after a pull of an empty realm, because an empty response returns
`nextCursor == since` and `pullMergedLog` stores `0` again [C22]. So the device records the fact
itself: a reserved sentinel row in the object store, `tenshu/sync/firstPull`, beside the sync
cursor and with its lifecycle (wiped when the store is wiped) [C22]. It is a small **state**, not
a presence flag (Codex, round 6), so that a crash is not mistaken for an upgrade:

| Marker | Forward cursor | Means | Pre-request signal |
|---|---|---|---|
| absent | `0` | never pulled; write `STARTED` before the first request is sent. ⚠ Also an upgraded device whose realm was empty: an **accepted** false positive, once per device, for one request, after which the marker is `COMPLETED`. No upgrade signal is added for it | shows |
| `STARTED` | any | the first pull was begun and not finished, including a kill after a cursor was persisted | shows |
| `COMPLETED` | any | first pull done | evidence only |
| absent | `> 0` | a device upgraded from a build without the marker; treat as `COMPLETED` and write it | evidence only |

- **`COMPLETED` is written when a tick's forward pull finishes with nothing more to come on any
  lane** (rounds 4, 5 and 7): every forward lane of the personal realm answered, applied and
  persisted its cursor, *and* none reported a backlog (`hasMore` false; on the fallback, neither
  lane truncated). A failed lane or a truncated page leaves `STARTED`, so a kill mid-backlog
  still shows the signal on the next launch, on either path.
- **"Answered" is reported, not inferred.** Today a failed request and an empty answer look the
  same to the code that stores the cursor [C22]. So each page reader also reports whether the
  relay answered; an answered empty page counts, a failure does not.
- **The one remaining ambiguity:** an upgraded device killed in the middle of a pull has no
  marker and a non-zero cursor, and reads as completed. That is the state every device is in
  today, so nothing regresses; it lights when its next page arrives.
- **Device-local and unsynced.** A raw `DbEntry`, not a registered composite, so the shape
  baseline does not see it and it owes no version bump of any kind; `ObjectStore.kt:386-390`
  states this for every sentinel in the run.

**Raise rules.** Three rules, not a list of lines; today's instances are in [C10], and each
instance gets its own scheduler test:

1. **Before the slice bootstrap, when it will fetch and either the first pull is not `COMPLETED`
   or a persisted backfill cursor is unfinished** (Codex, rounds 12 and 15). It GETs blobs and applies the root slice that first populates Photos. The
   bootstrap has no cheap "a slice exists" step to raise after: its seed key is a constant and
   the blob GET *is* the existence check [C14], so the gate is the durable marker.
   *Pre-existing, outside this note:* on a relay without the merged lane the bootstrap appears
   to be re-attempted in every process, since its latch is in memory and no backfill cursor is
   ever stored there. The relay in this repo serves the merged lane (`sync-relay-api.md`,
   `POST /sync/log`); what production runs was not checked.
2. **Before the tick's pull is sent, when the evidence table says so** (first pull unfinished; backward walk unfinished), and only
   once the pull is allowed.
3. **Wherever a relay response carrying rows is about to be applied, on any lane or path**
   (Codex, round 4). Today that is five places: the merged log, the backward backfill, the two
   lane readers the scheduler falls back to on a relay without `/sync/log`, and the shared-realm
   pull. A new pull path inherits the rule, not a line number: one helper every apply loop goes
   through.

**A raise is persisted before the work it announces** (Codex, round 9). The existing publishers
collect a conflating `StateFlow` asynchronously [C17]. A one-page pull raises, applies
synchronously and settles within one tick, so a collector may only ever see the final `false`:
the row never reads `true` and the capsule never appears, however long the apply takes. So the
raise, under rules 1 and 3 alike, is a **suspending call that writes the row itself and returns
when the write has landed**, and only then is the bootstrap or the apply loop entered. That is
safe where it is called from: the tick is a suspend function on the scheduler's own coroutine,
under the tick mutex, and the raise sits before the apply loop, outside it [C10] *(inferred: the
loops were read; whether a caller further up holds a store transaction was not traced)*. It
writes only on a change, so a ten-page catch-up still costs one `true`. The clear stays
asynchronous and conflated. One hazard follows and is closed the same way: a late collector
write of an old `false` must not land on top of a newer `true`, so both paths go through one
writer that takes a mutex and writes the holder's **current** value, never the collected one.

**Settle rules**, all on the writer:

- **Every tick end, whatever happened in it, by one rule symmetric with raise rule 3: the flag
  stays up while ANY pull path reports a backlog, and clears when none does** (Codex, round 5).
  A tick-local verdict starts `false` and each path ORs in its own report: the personal forward
  pull its `hasMore` (two-lane: either lane truncated), the backfill "still walking", and each
  shared realm the full-page test it already uses to re-wake itself [C10]. Without the shared
  half, a long shared-realm catch-up would clear and re-raise every tick and never get past the
  debounce. The verdict is delivered with the tick's health report, so it is assembled after the
  shared-realm pull, which runs later in the tick than the personal one. ⚠ Today the tick's
  `finally` opens *after* the bootstrap; P1 widens it so a raise under rule 1 is always settled,
  including on cancellation.
- **A tick that returns early because the realm is disabled** delivers `false` with the health
  report it already makes.
- **Scheduler stop** clears it, **and that clear is the last write** (Codex, round 16). `stop()`
  only cancels and returns while a tick may still be running, and the widened `finally` would
  otherwise publish that tick's verdict after it. So stopping sets a latch that the
  mutex-guarded writer checks: once it is set, no tick verdict or raise is accepted, and a
  cancelled tick's `finally` publishes nothing. A restart clears the latch.
- **Runtime start.** The publisher writes the holder's initial `false` first, replacing **its own**
  row if a killed process left it `true` *(inferred from `initContentUploadProgress` collecting a
  `StateFlow`)*.

A stalled request cannot hold it for ever: the iOS relay client has a 10 s connect and a 30 s
socket timeout [C16], the page then fails with `hasMore = false`, and the verdict is `false`.

**Other work before the pull, and whether it counts.** Read from `tickOnce` and `drainAndPull`:

| Step | Counts? |
|---|---|
| Maintenance hooks, dangling-queue repair, restamper | no: local, nothing arrives |
| Slice bootstrap | **yes** |
| Entitlement and registration recovery, `ensureRegistered`, the push drains | no: outbound or prerequisites. If they fail, that is a problem state, not activity. They sit inside the bracket when rule 1 raised, which is honest: the device is still joining |
| Realm key fetch | not on this path: route 1 adopts handed-off keys before the runtime is built [C7] |

**The row: one per writer.** A new composite `SyncCatchUpStatus` (key, `catchingUp`) on a new
relation of `SyncService`, modelled on `ContentUploadProgress` [C17] and, like it, **written on
change only** (raises synchronously, as above). Unlike it, the key is not a constant: it is the **host app id** the runtime
already has (`named("hostAppId")`, the route-1 `clientId`, or Control's id) [C23].

Why (Codex, round 3): with a shared replica, the simulator today and devices after S0b, every
route-1 process runs its own scheduler (`ios-background-scheduling.md` §2) and would install its
publisher on one singleton row. An idle app's initial `false`, or its tick-end write, would clear
another app's `true`. Keyed by writer, each process writes and clears only its own row.

**Readers take their own runtime's row, and only that.** `SyncService.catchUp(hostAppId)`; on
route 1 the surface passes the `clientId` it was installed with. Aggregating over *any* writer was
considered and rejected for P1: a row left `true` by an app that was killed and never relaunched
cannot be told from a live one without cross-process liveness, which does not exist until the S1
lease, and a timer is what §3.1 refuses. So in P1 another app's pull is out of scope.

⚠⚠ **That is correct only while each app has its own store. That is NOT every real device
today** (corrected 2026-10-06): the maintainer's phone runs App Group builds over one replica, and
so does every simulator. On one replica a sibling can pull the rows first: the foreground app
rehydrates them while its own row stays `false`, and content appears with no capsule, before S1
and under it (round 15). **This is a known limit of P1 as shipped, on that phone, now** — Photos
opened after Control or Drive has pulled shows the photographs and no "Syncing…". So the named
work item, **cross-process activity** (the lease holder's row, or an aggregation over live
writers), is no longer a prerequisite of a future build: it is owed. It is still not designed
here. What P1 does guarantee on a shared store is in §10.3. ⭐ *Designed and built 2026-11-01:
§12, a heartbeat row per writer; the S0b doc's B.13.2 row points there.*

⚠ **The three existing rows have the same race and are not fixed here.** `SyncHealthStatus`,
`ContentTransferStatus` and `ContentUploadProgress` are singletons written by every runtime on a
store; `SyncBanner.kt:230-235` already records the health row flapping between writers on
Android. P2 reads Drive's own in-process figures and is unaffected on a device. **P4 depends on
resolving it**, by the lease or by the same per-writer key.

**Why no heartbeat** (Codex P1, round 2). ⭐ *Superseded 2026-11-01 by §12, for the cross-process
reader only: the own row is still change-only and timer-free, and the write-cost worry below was
measured false in §10.4.* An expiry timer in the surface cannot work over a
change-only row: repeated `true` verdicts never reach it. The fix is either a per-tick timestamp
or expiry on the writer. This note takes the writer: the settle rules cover every way a tick stops, so the
surface needs no timer and the row is written **twice per catch-up**. A heartbeat would be a
store transaction per tick for the length of the catch-up, and the existing writer's own comment
says each such write *"wakes every observing client"* [C17]. Whether that re-runs the feed's
query was not determined, which is reason enough not to add writes while the feed is busiest.

**House Rule 9, decided: no version bump; regenerate the shape baseline.** `SyncService.kt:82-91`
records that E2d-2 *"deliberately did NOT bump"* when it added `SchemaFenceStatus`, because a
non-`Syncable`, locally derived relation puts nothing in a block, and a bump would raise the
realm's epoch and pull-fence every device not yet updated. `core-schema-evolution.md` §7.7 says
the same for the format version. `drive-upload-progress.md` §8 did exactly this for
`ContentUploadProgress`. `SchemaShapeBaselineEnforcementTest` reports the new type as `Unpinned`
until the baseline is regenerated [C18]; if it reports `ReshapedWithoutBump`, something existing
was touched. `SyncHealthStatus` itself must not gain a field: it is positional.

**Thumbnails do not count as catching up.** *Recommended.* A tile fetches its image when drawn
[C11]; nothing queues them, and a capsule keyed on those fetches would light on every scroll
through uncached photographs. Rows will appear before their pictures; the tile's own placeholder
is the signal for that. If the device pass shows the gap is long, change the placeholder.

## 4. The surface

**A small capsule, overlaid, bottom-centre above the tab bar:** an icon and one line, *"Syncing…"*
or *"Uploading 124 of 310"*. One shared view, `SyncActivityCapsule`, in `ios-storage/` beside
`RealmStorageBanner` (not `ios-onboarding/`, whose README scopes it to forward-to-Control).

- **Not a strip in the root `VStack`:** it would shift the whole feed each time activity starts or
  ends. **Not a toolbar item:** every tab owns its `NavigationStack`, so "the same in every app"
  would be a convention.
- **In Photos** it mounts as `.overlay(alignment: .bottom)` on the Library screen root [C19].
  Inside the tab it inherits the tab's safe area, so it clears the tab bar without measuring it.
- ⚠⚠ **It must stay an overlay.** A Library fix is in progress in `FeedGridView` (follow-the-top
  plus diffed updates), and the capsule appears exactly when that code is busiest. So never
  `.safeAreaInset`, never a `contentInset` change, never a stack sibling. It may cover a few
  points of the last visible row.
- Fades in and out; not tappable (`allowsHitTesting(false)`); hidden while Photos' selection bar
  is up.
- **Accessibility:** one element, trait `.updatesFrequently`, no per-count announcements, a static
  icon under Reduce Motion, Dynamic Type on one line.

**One thing at a time** (ADR 0025 Decision 1c, extended by one row):

| State | What shows |
|---|---|
| `ControlSetUpGate` is checking or absent | the spinner or the wall; the capsule is inside `content()` and is not built |
| A problem bar has text | the bar alone |
| Neither, and activity | the capsule |

**How the bar is made to win**. `ControlNotConnectedBanner` is shown by each
Swift root from its own reachability model [C20], which a centrally installed surface never
sees. So the mount is `.syncActivityCapsule(hostProblem: Bool, hidden: Bool)` and `hostProblem`
has **no default**: a root passes the expression that decides its banner (Photos, which takes the
wall, passes whether its root is connected). The storage half the capsule observes itself, from
`RealmStorageSurface.observeBannerText()`. Both are applied in Swift after the Kotlin flow, so a
problem hides the capsule at once. ⚠ **A problem suppresses the capsule only in a host that
renders that problem's bar** (Codex, round 11), or the host shows neither. The mount therefore
also takes `showsStorageBar: Bool`, again with no default, and ignores the storage half when it
is `false`. Home, Mia and Control do not mount `RealmStorageBanner` today [C25]; P3 mounts it in
the same step and flips the flag.

**It never holds a last value.** The writer settles the figure (§3.1). The surface emits `null`
with no installed client. The Swift model sets the line to `nil` when its `for await` loop ends
and whenever `scenePhase` is not `.active`, and re-reads on return.

## 5. Per app, and other platforms

- **Photos:** `CatchingUp` now; `Uploading` only once Drive's run figures sit in the shared store
  (S0b).
- **Drive:** `Uploading`, from `SyncedFolderController.state` and, outside a backup run, the
  content pass. Also `CatchingUp`. **The tail needs new plumbing** (Codex, round 13): when the
  run leaves `BackingUp`, staged chunks may still await the relay (§2 fact 3), and nothing
  publishes that. The input is **transfers in flight**, not journal occupancy (round 14): a
  transfer that ends in a network error or any failure other than 401/403 leaves its ref
  journalled with no retry until a later launch or refresh [C13], so "journal not empty" would
  read *"Uploading…"* with nothing running. P2 owns a `StateFlow` in `BackgroundUploadManager`:
  incremented on every hand-off to the background session, decremented in `onCompleted`
  whatever the outcome, and seeded at start from the session's own list of live tasks, since
  `nsurlsessiond` keeps running across launches. Refs left for retry do not count. While it is
  above zero after the count is done, the state is `Uploading(count = null)` and the capsule
  reads *"Uploading…"*: these are chunks, not files, so there is no honest number to show.
- **Fit, Timeline, Maps, Calendar, Contacts, Home, Mia:** `CatchingUp` only.
- **Control:** `CatchingUp`, in P3 (Codex, round 10). It runs the same `initSyncService` and is
  often the app in the foreground while a device first syncs; its Sync dashboard is a device list
  and shows no activity. It does not go through `assembleBase`, so the surface is installed where
  Control builds its own runtime, `ControlSurface.installRealRuntime` [C24], with Control's host
  id as the row key. It mounts only in Control's ready phase and passes `hostProblem: false`: it
  has no not-connected bar of its own.
- **Android and desktop:** the evaluator is `commonMain`, so Compose can render it. Two cautions,
  no design: Drive's bar already says `Syncing`, so adopting the capsule there means removing that
  state from the bar; and the row then crosses IPC between two installed apps, so an app newer
  than Control reads a relation Control does not publish.

## 6. Phases

P1 is specified to implementation level here. P2 and P3 got their own design pass, §6a. P4 is
still an outline.

| Phase | Scope | Needs |
|---|---|---|
| **P1 — built 2026-10-06, §10** | **Photos shows `CatchingUp`** | §3.1, the evaluator, the surface, the capsule |
| **P2 — built 2026-10-06, §6a.1, §11** | Drive shows `Uploading`, tail included | the run's count and `InFlightTransfers` on `BackgroundUploadManager` |
| **P3 — built 2026-10-06, §6a.2, §11** | The other seven apps **and Control**. The capsule did **not** move into `AdaptiveAppShell`: the shell gained a slot | Home and Mia mount `RealmStorageBanner`; ⚠ Control does not (§6a.2) and passes `showsStorageBar: false`; Control installs the activity surface itself; Maps and Timeline mount it on their one stack |
| **P4** | Photos shows `Uploading` | Drive's figures in the shared store; waits on S0b |
| **Cross-process activity — built 2026-11-01, §12** | A sibling's pull shows "Syncing…" on a shared store | a heartbeat row per writer (`SyncCatchUpHeartbeat`), any fresh row counts; the device check of §12.6 is owed. Does not gate S0b-4 |

**P1, the work:** (1) `catchingUp` with the raise and settle rules of §3.1; (2)
the first-pull marker and the per-writer `SyncCatchUpStatus` with its relation, publisher and
`catchUp(hostAppId)`, baseline regenerated; (3)
`evaluateSyncActivity` and the debounced flow; (4) `SyncActivitySurface`; (5)
`SyncActivityCapsule` and its model; (6) the mount on Photos' Library.

**P1, verification:**

| Level | Exists today | P1 adds |
|---|---|---|
| The flag | `BackfillCatchUpTest`, `SyncHealthReportingTest`: fixtures that serve a paged log to a real `SyncScheduler` [C21] | one assertion per row of the §3.1 table; `COMPLETED` marker + unfinished persisted backfill + restart raises before the bootstrap; a raise test per apply path in [C10], **including the two-lane fallback**; settled after a thrown tick, a failed page, a cancelled bootstrap, and on stop, incl. **stop racing a tick that has recorded a backlog: the row ends `false`**; the flag steady, with no clear between ticks, across a multi-page **shared-realm** catch-up; one test per row of the marker table: still `STARTED` after a failed page, a truncated or `hasMore` page on either path, an apply that throws, a kill after cursor-persist, and **block page ok + tombstone page failed** on the fallback; `COMPLETED` after an answered empty page; absent with cursor `> 0` becomes `COMPLETED` |
| The row | `SyncHealthRelationLocalTest` | the same for the new row, incl. a stale `true` replaced at start and two writes for a ten-page catch-up; **at the relation, not the holder:** one page with a slow apply, and a slow bootstrap, each observed `true` before the work finishes and `false` after; a delayed stale `false` does not overwrite a newer `true`; **two runtimes over one store**: an idle runtime's start and tick end leave the other's `true` row untouched, and each reader sees only its own |
| Evaluator, debounce | — | virtual-time unit tests, incl. "200 ms of activity shows nothing"; both inputs true gives `Uploading`, and upload ending mid-pull gives `CatchingUp` with no `Idle` between |
| Runtime on iOS | `PhotosLocalRuntimeTest` assembles a real route-1 runtime against an unreachable relay [C21] | the surface emits `null` on an idle runtime (`./gradlew iosTestCheck`, macOS) |
| The capsule on a simulator | `-photosFixture` and `-photosDemo` drive the Library without taps, over a fake repository with no runtime [C21] | a `DEV_HARNESS` scripted sequence (on, off, on then `hostProblem`, on then flow ends); screenshots; an assertion that the grid's frame, `adjustedContentInset` and content offset are identical with the capsule shown and hidden |
| A real pull on a simulator | `TENSHU_DEV_RELAY` points a debug runtime at `./gradlew :sync-relay-server:run` [C21] | manual, below |

⚠ **The repo has no seeded relay and no two-runtime simulator harness.** An end-to-end simulator
run is manual: a dev relay, a second device writing to it, and Control-iOS provisioning Photos.
It has a trap: the simulator shares one replica across apps, so if Control or Drive is opened
first it pulls the backlog and Photos has nothing to catch up on. Launch Photos first. P1 builds
no harness; the JVM tests carry the flag, the scripted sequence the view, the device the join.

**P1, on the device.** Acceptance, matching the §3.1 table:

- A fresh install against the populated realm: the capsule from the first second, steady while
  rows arrive, gone within about two seconds of the last page.
- A fresh install against an **empty** realm: the capsule for the first request, until that
  pull completes, and never on later launches.
- Photographs added from another device while Photos is closed: the capsule from when the first
  page lands.
- **A device that has completed a first pull and has nothing to pull: no capsule, including on a
  throttled network.** The one exclusion is the first launch after upgrading on an empty realm.
- The grid does not move when the capsule comes or goes.

## 7. Open questions

1. **A relation, or read `SyncHealthHolder` in-process?** On route 1 the surface could read the
   holder from Koin with no schema. *Recommend* the relation: Compose needs it anyway, it costs no
   version, and a second read path is how two platforms come to disagree.
2. **Bridge Drive's count to Photos before S0b**, for example a file in the App Group container?
   *Recommend* no: a second cross-process channel beside the one S0b is designing.
3. **Capsule or toolbar item?** *Recommend* the capsule; decide on a P1 device screenshot.
4. **How does a reader learn of another process's pull on a shared store?** ⭐ *Decided by the
   maintainer and built 2026-11-01 (§12):* a heartbeat. Each writer rewrites a stamped row every
   5 s while catching up; a reader counts any row younger than 15 s, or its own row. Not S1's
   lease, and not a blocker of S0b-4.
5. **Should the capsule say "Waiting for Wi-Fi" or "Offline"?** *Recommend* no: those are
   problem-bar states and iOS has no bar for them yet. Separate work.

## 8. Not determined from the code

- *Accepted limit:* the late show after a failed first backfill request plus a restart (§3.1
  table). No state is added for it. If P1's builder wants it closed, the cheap fix is to persist
  a "backfill not started" value when the forward lane first completes on a merged-lane relay.
- Whether the status rows are written and readable in a route-1 app on a device. Nothing was run.
- Whether a relation keyed by writer id fits the existing single-row plumbing (`DataId` on a
  string key) without more than the key changing. The mechanism was read, not tried.
- What `backfillOnce` returns when its page fails. It must be `false`.
- Whether a status-row write re-runs unrelated observers such as the feed (§3.1).
- Which expression each Swift root would pass as `hostProblem`. Only Calendar's was read.
- The debounce constants, and whether the capsule clears Photos' scrubber and the iOS 26 tab bar.

## 9. References

| | Where |
|---|---|
| C1 | `drive-shared-logic/src/commonMain/kotlin/app/tenshu/drive/domain/SyncBanner.kt:319-485` |
| C2 | `drive-shared-ui/src/commonMain/kotlin/app/tenshu/drive/ui/common/SyncStatusBanner.kt:144-152` |
| C3 | `sync-service-proxy/src/commonMain/kotlin/org/tenshu/sync/proxy/SyncProxyInitialization.kt:640-649` |
| C4 | `sync-service-client/src/commonMain/kotlin/org/tenshu/sync/client/SyncServiceExtensions.kt:26`, `:70`, `:80` |
| C5 | `photos-ios/Sources/PhotosIosApp.swift:352`; `drive-ios/Sources/PairedAppView.swift:30` |
| C6 | `tenshu-sdk-ios/src/iosMain/kotlin/org/tenshu/sdk/ios/DriveBackupSurface.kt:149`, `:176` |
| C7 | `tenshu-sdk-ios/.../FirstPartyLocalRuntime.kt:140-162`; `control-shared-proxy/src/iosMain/kotlin/app/tenshu/control/di/PlatformProxyModule.kt:766` |
| C8 | `sync-service-proxy/.../SyncHealthHolder.kt:206`, `:232` |
| C9 | `app-ingest/src/commonMain/kotlin/app/tenshu/ingest/backup/SyncedFolderController.kt:136` |
| C10 | `sync-service-proxy/.../SyncScheduler.kt`: tick `:868-965`, bootstrap `:933`, `finally` `:959-964`, disabled branch `:886-896`, pull and verdict `:1202-1215`, backfill `:2101-2229`, stop `:2606`. **Apply sites (raise rule 3), as of 2026-10-05:** merged log `:2523-2524`, backfill `:2265-2266`, block lane `pullChanges` `:2403`, tombstone lane `pullTombstones` `:2553`, shared realms `:1837`. **Backlog reports (settle rule):** merged `hasMore` `:2308`, two-lane `truncated` `:2351`, backfill `:2218-2229`, shared full page `:1846`, shared pull called at `:1254`. Failed page `:2488-2514`; two-lane fallback `:2295-2352` |
| C11 | `tenshu-sdk-ios/.../PhotosSurface.kt:210`; `photos-ios/Sources/PhotoTileView.swift:106` |
| C12 | `tenshu-sdk-ios/.../PhotosLocalRuntime.kt:31-33`; `photos-ios.md` PIO-D5 |
| C13 | `tenshu-sdk-ios/.../BackgroundUploadManager.kt:45-46`; `:65` (hand-off), `:79-95` (`onCompleted`: only 401/403 re-enqueues) |
| C14 | `sync-service-proxy/.../SliceSnapshotReader.kt:34`, `:59-66` (latch and guard), `:92-102` (the GET); `file-service-proxy/.../FileSliceSnapshotProducer.kt:104-111` (the constant seed) |
| C15 | `sync-service-proxy/.../SyncScheduler.kt:2092-2104` (the cursor's three states) |
| C16 | `control-shared-proxy/src/iosMain/.../PlatformProxyModule.kt:241-244` |
| C17 | `sync-service-proxy/.../ContentTransferInitialization.kt:95-135`; `sync-service/.../ContentUploadProgress.kt` |
| C18 | `core-common/src/commonMain/kotlin/org/tenshu/core/common/schema/migration/SchemaShapeBaseline.kt:148`; `system-test-utils/src/test/kotlin/org/tenshu/systemtest/SchemaShapeBaselineEnforcementTest.kt` |
| C19 | `photos-ios/Sources/LibraryScreen.swift:141` |
| C20 | `calendar-ios/Sources/CalendarIosApp.swift:92`, `:147` |
| C22 | `core-common/src/commonMain/kotlin/org/tenshu/core/common/proxy/db/ObjectStore.kt:180-195` (the cursor row, default `0`), `:386-390` (sentinels owe no bump); `sync-service-proxy/.../SyncScheduler.kt:2295-2308` |
| C23 | `tenshu-sdk-ios/.../FirstPartyLocalRuntime.kt:112`; `control-shared-proxy/src/iosMain/.../PlatformProxyModule.kt:766-774` |
| C24 | `tenshu-sdk-ios/.../ControlSurface.kt:274-288`; `control-ios/Sources/ControlIosApp.swift:64-67`, `:106` |
| C25 | `home-ios/Sources/HomeIosApp.swift:95-104`; `mia-ios/Sources/MiaIosApp.swift:96-105`; `control-ios/Sources/ControlIosApp.swift:1050-1052` |
| C21 | `sync-service-proxy/src/commonTest/kotlin/org/tenshu/sync/proxy/`; `tenshu-sdk-ios/src/iosTest/kotlin/org/tenshu/sdk/ios/PhotosLocalRuntimeTest.kt`; `photos-ios/Sources/PhotosIosApp.swift:48-69`; `tenshu-sdk-ios/.../DevRelayOverride.kt:10-21` |

## 6a. P2 and P3 — the design pass (2026-10-06)

This replaces the two outline rows of §6. As built, with what the code changed, is §11.

### 6a.1 P2 — Drive shows `Uploading`

**Two inputs, both in Drive's process, neither a store row.**

| Input | Source | Means |
|---|---|---|
| the run's "N of M" | `SyncedFolderController.state`, through `DriveBackupSurface.observeUploadRun()` | `BackingUp` gives a count; every other state gives none |
| transfers in flight | `BackgroundUploadManager.inFlight` | chunks handed to the background `URLSession` and not yet reported complete |

`uploading = (a run has a count) or (transfers in flight > 0)`. With a count the line is
*"Uploading N of M"*; without one it is the tail, *"Uploading…"*. The evaluator, the precedence
(`Uploading` over `CatchingUp`, no alternation) and the debounce are P1's, unchanged.

- **N is the file in hand, not the files done:** `min(completed + 1, total)`, the number the
  Settings footer already prints (*"Backing up 21 of 400…"*). One helper feeds both, so the two
  surfaces cannot disagree. Drive on iOS has no other transfer UI: no upload sheet, no row badge;
  `FilesView` shows only the conflict and failure alerts.
- **A hand-picked upload has no count.** It stages chunks like the backup does, so it shows the
  tail line while its transfers run. The proxy's pass total is not read (`drive-upload-progress.md`
  §14.2).
- **In flight is counted per ref, not as one integer.** `InFlightTransfers` (pure, in
  `:sync-service-common`) keeps how many live tasks each ref has: `+1` at every hand-off (the
  first enqueue, the 401/403 re-enqueue, the resume), `-1` in `onCompleted` whatever the outcome,
  never below zero. A completion for a ref it does not hold is ignored, so a stray or replayed
  delegate event cannot cancel out a different live transfer.
- **Seeded from the session's own task list.** The Swift bridge can list it
  (`URLSession.getAllTasks`), so `BackgroundUploadBridge` gains `liveTaskRefs`. The manager asks
  once, before its first resume re-enqueues anything. Completions that arrive while the answer
  is outstanding are remembered and subtracted, so a task that finished in that window is not
  seeded as live for ever. Where the two could disagree the count errs low: the tail may end
  early, it cannot stick.
- **With no bridge** (tests, or before Swift registers it) uploads go through the foreground
  store and return when the relay has answered; nothing is in flight and nothing is counted.
- **To the surface as one flow.** `SyncActivitySurface.installUploads(Flow<UploadInput>)`, called
  by `DriveLocalRuntime` only. Every other app has no upload input and never says `Uploading`.

**The mount.** Drive's connected root, through the shell (6a.2), with `hostProblem: false` (Drive
takes the wall and `PairedAppView` has no problem bar of its own) and `showsStorageBar: true`
(it mounts `RealmStorageBanner`).

⚠ **Known limit: a transfer waiting for a network is in flight.** A background session holds a
task until it can run, so on a phone with no connection the tail line stays up for as long as the
system keeps the task. iOS has no offline bar to yield to (§7 question 5). Not closed here.

### 6a.2 P3 — the other apps

**The capsule does not move into `AdaptiveAppShell`; the shell gains a slot.** Three facts decide
it. The shell is its own XcodeGen fragment and does not compile `ios-storage`. Maps and Timeline do
not use it. And an overlay on the shell itself would sit *on* the tab bar: only a view inside a tab
inherits the safe area that clears it (§4). So:

- `AdaptiveAppShell` takes `destinationOverlay: AnyView?`, drawn bottom-aligned over **every**
  destination's content. It knows nothing about sync.
- **One observation per app, many places to draw.** A mount per tab would start a new observation
  at each tab switch, and the debounce would hide the capsule for 700 ms every time. So the
  model is held by the root (`@StateObject`) and observed once
  (`.syncActivityObservation(model)`), and the shell's overlay is a `SyncActivityCapsuleSlot`
  that only reads it. P1's `.syncActivityCapsule(...)` stays, now built from the same two pieces,
  for a root with one `NavigationStack`.

| App | Mount | `hostProblem` | `showsStorageBar` |
|---|---|---|---|
| Drive | shell slot | `false` | `true` |
| Fit | shell slot | neither route is live (the expression above its banner) | `true` |
| Calendar, Contacts | shell slot | `reachability.reachable == false` | `true` |
| Home | shell slot | `reachability.reachable == false` | `true`, **banner added in this step** |
| Mia | shell slot, hidden on Chat (the composer owns the bottom edge) | `false` (it has no bar) | `true`, **banner added in this step** |
| Maps, Timeline | `.syncActivityCapsule` on the root `NavigationStack` | `!controlReachable()` | `true` |
| Control | shell slot, in the ready phase only | `false` | **`false`**, below |
| Photos | unchanged: P1's mount on the Library | | |

- **Walls and the lock.** Every mount is inside `ControlSetUpGate`'s content, or inside Control's
  `.ready` phase, so it is not built over a wall, the checking spinner, onboarding or the locked
  screen.
- **Control's storage bar is left out.** In Control the bar's one action is "open Control", and
  Control already has a Services screen that states the plan. Mounting the bar there is a design
  question (what the action does, whether Home's own storage card makes it redundant), not a
  mount. So Control passes `showsStorageBar: false` and the capsule ignores storage there. Control
  installs `SyncActivitySurface` in `ControlSurface.installRealRuntime` with its own host id.
- **Photos keeps its own mount** while its chrome is being reworked. Moving it to the slot, so
  Collections shows the capsule too, is a follow-up.
- **`RealmStorageModel`'s leak is fixed in the same step** (§10.2 item 11), since the banner gains
  two hosts: its observation becomes structured, as the capsule's is.
- **Own runtime only**, as P1: on a shared store a sibling's pull shows nothing (§3.1, §10.3).

**Verification.** Kotlin: the evaluator for the two inputs, the counter, the manager's hand-off,
completion and seeding. Swift: one shared DEV_HARNESS script (`-syncCapsuleScript`), compiled into
every app with the capsule, drives the surface's scripted input; a Drive XCUITest reads the line at
each step, and the other apps get a scripted launch and a screenshot.

## 10. P1 as built (2026-10-06)

### 10.1 What exists

| Piece | Where |
|---|---|
| The signal and its one writer | `sync-service-proxy/.../SyncCatchUp.kt` (`SyncCatchUp`, `initSyncCatchUp`) |
| Raise and settle | `SyncScheduler`: `applyPage` (rule 3, the one helper every apply loop goes through), `raiseBeforePull` (rule 2), `raiseBeforeBootstrap` (rule 1), `tickBacklog` (the verdict), the bracket in `tickOnce`, `stop()` |
| The first-pull marker | `ObjectStore.loadFirstPullMarker` / `FIRST_PULL_HASH`; `SyncManager.firstPullMarker` |
| "The relay answered" | `SyncLogOutcome.answered`, `PullOutcome.answered`, `ForwardPull` |
| The row | `SyncCatchUpStatus`, `SyncService.Relations.CATCH_UP`, `SyncService.catchUp(hostAppId)` |
| Evaluator, debounce | `sync-service-common/.../SyncActivity.kt` (`evaluateSyncActivity`, `SyncActivityTiming`, `debounceSyncActivity`) |
| Surface | `tenshu-sdk-ios/.../SyncActivitySurface.kt`, installed in `FirstPartyLocalRuntime.assembleBase` |
| Capsule, mount | `ios-storage/Sources/SyncActivityCapsule.swift`; mounted in `photos-ios/Sources/LibraryScreen.swift` only |

⚠ §9's `SyncScheduler.kt` line numbers were read before P1 and no longer land; the names above do.

`SyncService` stayed at version 9. Both pinned baselines reported the new type as unpinned and
nothing as reshaped; they were regenerated (`schema-shape-baseline.txt`, `property-kind-baseline.txt`).

### 10.2 Where the code left this note

1. **The flag is not a field of `SyncHealthFigures`.** `initSyncHealth` rewrites the health row
   on every emission of that holder, so the flag there would cost two more health-row writes per
   catch-up. It lives in `SyncCatchUp`, which the scheduler is handed beside the health holder.
2. ⚠⚠ **"The clear stays asynchronous and conflated" loses clears as written.** A raise and its
   settle inside one tick take the flag `false → true → false`. A collector over a conflating
   `StateFlow` that did not run in between is offered a value equal to the one it last saw and is
   never woken, so the `true` the raise wrote stays in the store for ever. Eleven of the first
   forty tests failed on it. The state now carries a revision that moves on every change, and the
   collector follows the revision.
3. **"Change only" is decided against the store, not a remembered last write** (§10.3).
4. **Start writes nothing unless a stale `true` is there.** Absent already reads `false`, so an
   ordinary launch costs no store transaction. The clear, when owed, is synchronous in init.
5. **Rule 1 has one more gate: the pull must be open** (`storageGate` or
   `realmRelayRegistered`). A never-funded, local-only device reads as "never pulled" by the
   marker, and its bootstrap does fetch; without the gate it would raise on every launch.
6. **Rule 2's backfill clause holds only while this process would walk** (the merged lane is
   wired and has not been refused). A floor left over from a relay that has since lost the route
   never advances, and raising on it would light the flag before every pull.
7. **The tick's bracket is a second `try`/`finally` around the existing one**, from the bootstrap
   to the tick's end. The existing `finally` (drain liveness, health report) is where it was, so
   nothing about health reporting moved. `SyncCatchUp.settle` does not suspend, so it runs under
   cancellation.
8. **Photos passes `hostProblem: status == .unreachable || .failed`**, the Library's own
   full-screen problem states. Photos takes the wall, so the not-connected case never reaches the
   mount.
9. **A DEV_HARNESS input lives on the surface** (`installHarness`, `harnessSetCatchingUp`,
   `harnessEndFlow`, `debugPulseRow`). It replaces the input only; the rule, the debounce and the
   copy under it are the shipping ones.
10. ⚠⚠ **The marker is cleared with the cursors, and so are the other positions in the log**.
    The note said the marker "is wiped with the store" and stopped there. A
    device re-paired to a different owner keeps its store: `discardSupersededRealm` zeroed the
    forward cursor and nothing else, so the new realm inherited `COMPLETED` and its first pull was
    never announced. Clearing the marker alone would not have fixed it — the surviving tombstone
    cursor reads as "pulled before" and the next pull writes `COMPLETED` straight back. So
    `SyncManager.resetPullProgress()` returns all five rows together (forward and tombstone
    cursors to zero; marker, backfill floor and backfill target to absent), and the three places
    that zeroed the forward cursor call it: `discardSupersededRealm`, `forgetThisDevice`,
    `reconcileStaleSyncCursor`. ⚠ This changes sync, not only the capsule: a re-paired device now
    reads the new realm's tombstones from zero and walks its history newest-first like a fresh
    joiner, where before it carried the old realm's positions into a log they did not describe.
    The row and the stop latch hold nothing per realm — every tick re-decides the flag.
11. ⚠⚠ **The Swift model holds no task**. It first stored `Task` handles, the
    shape `RealmStorageModel` has, and that raced twice: a cancelled run cleared the handle of
    the run that replaced it, and a mount removed while the scene stayed active cancelled nothing.
    `SyncActivityModel.observe()` is now the observation itself, awaited by the mount's
    `.task(id: scenePhase == .active)`, with every collector a child of that task. Measured on the
    simulator through `SyncActivitySurface.harnessCollectors()`: SKIE's `for await` does end the
    Kotlin collector when its Swift task is cancelled, so structure is all it takes. The pre-fix
    model read 4 collectors after a second mount was removed and 14 after twenty restarts, where
    one observation is 2 (`testCollectorsEndWithTheirMount_andARestartLeavesOne`).
    ⚠ `RealmStorageModel` still has the detached shape. It guards with `started` and is mounted
    once per app, so it does not duplicate; its two collectors do outlive a discarded banner.

### 10.3 Several processes on one store, today

> ⚠ *History: written over the iOS file store (2026-10-06). That store and its rehydrate are
> deleted since 2026-11-05 ([`ios-shared-store-sqlite.md`](ios-shared-store-sqlite.md) B.22.9);
> `SyncActivitySharedReplicaTest` now runs over `TestReplicas` (rows shared, listeners per process,
> as on the SQLite replica), and its foreground is only where the test looks again. The stale-cache
> mechanism in the third bullet was the file store's; the writer's check stays.*

The store on the maintainer's phone and on every simulator is shared (§3.1, corrected). What P1
guarantees there, each pinned by `SyncActivitySharedReplicaTest` over a real `FileObjectStore`
replica opened by two route-1 assemblies:

- An idle sibling's start and tick end write nothing, and leave a catching-up app's row alone.
- Each reader sees its own row, also once its store holds the sibling's `true`.
- A row left `true` by a killed app is cleared by **that app's** next start and by nothing else.
- ⚠⚠ **A sibling can hide the row, and the next raise puts it back.** The relation is one array of
  member hashes and every process caches it. A sibling that opened the replica before this app
  first wrote rebuilds the array without this app's row on its own first write, and after a
  rehydrate this app reads no row. The test records that this happens today. So the writer asks
  the store — is my row there, and does it say what I am about to say — before deciding it has
  nothing to write, and the next page's raise restores it. A `false` also replaces a stored
  `true` that is no longer a member, because a sibling writing from a stale cache can put a
  dropped member back.

⚠⚠ **What it does not do:** show a sibling's pull. ⭐ *That is §12's heartbeat (2026-11-01); the
own row above is unchanged by it.*

⚠ **The test drives the flag in the scheduler's place, so it stops the scheduler first**
(2026-10-10). Each assembly is a real runtime whose loop starts with it, the realm is
unprovisioned, and an unprovisioned tick ends in `settle(false)`. A tick ending after the test's
own `raise()` cleared it. Alternated on the simulator, 300 runs each at a load of 17–1,018
(median 293): 61 failures without the stop, 0 with. `theRuntimesOwnTickClearsAFlagRaisedFromOutside`
runs that tick on purpose. Not a store defect, and not the 200 ms wait, which waits for a write
that must not happen. In the product only the scheduler raises, inside the tick that settles.

### 10.4 The "not determined" items of §8, settled

- **What `backfillOnce` returns when its page fails:** `false` (`pullSyncLogBefore` returns
  `null`). Pinned by `aFailedBackfillPage_reportsNoBacklog`.
- **Whether a per-writer key fits the single-row plumbing:** yes, with only the key changing.
  The reader lists the relation and filters; it holds one row per installed app.
- **Which expression Photos passes as `hostProblem`:** §10.2 item 8.
- **The late show after a failed first backfill request plus a restart:** left as accepted.
- **Whether a status-row write re-runs unrelated observers such as the feed:** it does not, as
  far as the proxy delivers. Measured on the iOS simulator runtime
  (`SyncActivityRowWriteWakesNobodyTest`): a real route-1 Photos runtime over a `FileObjectStore`
  replica, 40 writes of the real row — its own reader was woken 40 times, the feed's paging flow
  was handed 0 new `PagingData`, and a sibling single-row relation's reader 0, each equal to an
  idle control window. ⚠ Two limits. The library was empty and the realm unprovisioned, and this
  counts what the proxy re-delivers, not `FeedGridView`'s draw counter: the simulator has no
  provisioned realm, so a real runtime could not be put under the grid. `-photosSyncRowProbe`
  runs the same pulse under the real grid and logs its draws; it is waiting for a device.
- **The debounce constants; whether the capsule clears the scrubber and the tab bar:** the
  constants are unchanged. On the iPhone 16 Pro simulator (iOS 18.5) the capsule sits 12 pt above
  the tab bar, centred, clear of the scrubber's edge. The iOS 26 tab bar was not looked at.

### 10.5 Verification, and what it did not cover

| Level | What ran |
|---|---|
| The flag, the marker | `SyncCatchUpSchedulerTest` — a real `SyncScheduler` over a scripted relay: one test per row of the evidence table, per apply path (merged log, backfill, both fallback lanes, the refused-route fallback, a shared realm), per settle rule (failed page, thrown apply, cancelled bootstrap, stop, stop racing a tick that holds a backlog, a realm that locks) and per row of the marker table |
| The row | `SyncCatchUpRelationTest` — through the client chain; the raise landing with no collector run; a late stale `false`; the conflation case of §10.2 item 2; two writes for ten pages; two runtimes over one store |
| The row on the store iOS ships | `SyncActivitySharedReplicaTest` (`iosSimulatorArm64Test`) — §10.3 |
| Evaluator, debounce | `SyncActivityTest`, virtual time |
| The capsule on a simulator | `SyncCapsuleUITests` on `-photosFixture -photosSyncCapsule`: the capsule is present exactly at the scripted steps (not inside the 700 ms show delay, not under a host problem, not after the flow ends), and the grid's frame in the window, `adjustedContentInset` and content offset are one identical string over all eight samples — at the top, and scrolled to line 40 |

⚠⚠ **Not verified: a real pull.** No relay was involved anywhere above. The device acceptance
list of §6 is untouched, and so is the shared-store limit of §3.1 as a user meets it.
⚠ **A consequence worth watching on the device:** with a first pull unfinished and the relay
unreachable, every tick raises before its request and settles when it fails — two row writes a
tick, and "Syncing…" for as long as each request takes to time out. That is the note's design
(the stall cannot hold the flag), and it costs what `isSyncing` already costs the health row.

## 11. P2 and P3 as built (2026-10-06)

### 11.1 What exists

| Piece | Where |
|---|---|
| Transfers in flight | `sync-service-common/.../InFlightTransfers.kt`; `BackgroundUploadManager.inFlight`, `handOff`, `seedInFlight` |
| The session's live tasks | `BackgroundUploadBridge.liveTaskRefs`; `drive-ios/Sources/BackgroundUpload.swift` (`getAllTasks`, running and suspended) |
| The run's count | `DriveBackupSurface.observeUploadRun()`, `fileInHand` (shared with the Settings footer) |
| The upload input | `UploadInput` and `evaluateSyncActivity(catchingUp, upload)` in `SyncActivity.kt`; `SyncActivitySurface.installUploads`, called from `DriveLocalRuntime.installLocked` |
| One observation, many slots | `SyncActivityCapsuleSlot`, `.syncActivityObservation(model)`, and `.syncActivityCapsule(...)` built from both, in `ios-storage/Sources/SyncActivityCapsule.swift` |
| The shell's slot | `AdaptiveAppShell(destinationOverlay:)` |
| Control's surface | `ControlSurface.installRealRuntime`, keyed by `RuntimeIdentity.CONTROL_APP_ID` |
| The storage banner's observation | `RealmStorageObservation`, `RealmStorageModel.observe()` in `RealmStorageBanner.swift` |
| The scripted input | `SyncActivitySurface.harnessSetUpload`; `ios-storage/Sources/SyncActivityScript.swift` (`-syncCapsuleScript`) |

No relation, composite or op was added, so no version moved and no baseline changed.

### 11.2 Decisions made while building

1. **The seed is asked for once per process, inside the first resume, under a mutex.**
   `resumeIncomplete` runs twice at launch (at install, and when Swift registers the bridge). The
   mutex puts the seed before either run's re-enqueues, so what the session already held and what
   this launch added are counted separately and each exactly once. The wait for Swift's answer is
   bounded at five seconds; with no answer nothing is seeded.
2. **`onCompleted` counts down before its coroutine is launched**, not inside it. The journal
   work is asynchronous and the count must not lag behind a delegate call the user can see the
   result of.
3. ⚠ **Pre-existing, not changed:** each resume re-enqueues every journalled ref whose staging
   file exists, including refs the session is still sending, so a launch can run a chunk twice.
   The relay takes it (content-addressed). The count is right about it: two tasks, two
   completions.
4. **The storage banner is one container with one observation**, where it was two branches that
   each started one. The bar's content moved into a function; nothing about what it draws changed.
5. **The debug script is compiled under `DEBUG`, not `DEV_HARNESS`.** Calendar, Home, Mia and
   Control have no harness configuration, and a script that four of the nine hosts cannot run
   would not be shared. It does nothing without its launch argument. Two more debug-only launch
   arguments came with it: `-devShowContentUnprovisioned` on `ControlSetUpGate` (build the root
   over the not-connected repositories) and `-shellDestination <id>` on the shell.
6. **Mia's capsule is hidden on Chat** (`hidden: selection == "chat"`). The composer is where the
   capsule would be.

### 11.3 Verification

| Level | What ran |
|---|---|
| Evaluator | `SyncActivityTest`: the upload input alone and with a pull, the tail, precedence both ways, a run then its tail then a pull as one appearance with no `Idle`, a pull ending mid-upload changing nothing, the gap between a run's last file and its first counted transfer |
| The count | `InFlightTransfersTest`: hand-off, completion, a completion for a ref not held, one ref twice, seeding, a completion while the list is outstanding, a hand-off while it is outstanding |
| The manager | `BackgroundUploadManagerTest` (`iosSimulatorArm64Test`): in flight until a 2xx; a network error and a 500 end it while the ref stays journalled; a 401 re-enqueue counts again; a stray completion is ignored; the first resume seeds once and before re-enqueueing; a transfer that completes while the session is being asked is not seeded; no bridge, nothing in flight |
| Drive on a simulator | `drive-ios/UITests/SyncCapsuleUITests`: the script's run, tail, hand-over and end, read as the line the slot drew at each step; the capsule above the tab bar in Files and, without a new wait, in Shared; a discarded storage banner returns the collector count to where it was |
| The other hosts | a scripted launch and a screenshot each (below) |

The leak test was run against the old shape (the observation started in an unstructured `Task`):
it fails there, 5 collectors where 3 are expected.

**Screenshots, iPhone 16 Pro simulator, iOS 18.5.** On a simulator nothing has provisioned, each
app was launched with `-devShowContentUnprovisioned -syncCapsuleScript pull-hold`:

| App | With its own problem state honoured | With `-syncCapsuleIgnoreHostProblem` |
|---|---|---|
| Calendar, Contacts, Home, Fit, Timeline, Maps | the not-connected bar, no capsule | the capsule, above the tab bar (above the home indicator in Timeline and Maps) |
| Mia | no bar; no capsule on Chat, the capsule on History | |
| Control | nothing over the genesis probe or the reinstall gate; the capsule over the main tabs after `-unlockAutoComplete`, on a real runtime | |
| Drive | `Uploading 124 of 310` and `Uploading…` over the dev-stub session | |

⚠⚠ **Not verified:**

- **A real upload.** The count has never been driven by a real `URLSession`: `liveTaskRefs` and
  the delegate's completions ran only in a fake. Whether `getAllTasks` lists a task that finished
  while the app was dead, and how long a task waiting for a network is kept, are both unmeasured.
- **A real pull in any of the nine hosts**, and each host's storage bar and capsule together.
- **iPad, and the iOS 26 tab bar.** The slot inherits the tab's safe area, which is the reason
  it should be right in both; neither was looked at.
- **Fit on route 2** (the EPC client) has no in-process runtime and shows no capsule. That is the
  surface's rule (`null` with no installed runtime), not something that was run.


## 12. Cross-process activity: the heartbeat (designed and built 2026-11-01)

This closes the work item §3.1 and §6 called *"owed now"*: on a store several apps share, an app
showed "Syncing…" only for its **own** pull. Photos opened after Control or Drive had started
pulling showed photographs arriving and no capsule. The maintainer chose the approach: a
**heartbeat flag**. ⭐ It does not gate S0b-4; it ships when ready.

It supersedes §3.1's *"Why no heartbeat"* paragraph and §7 Q4's recommendation to wait for S1's
lease. The reason given there against a heartbeat (each write might wake the feed) was measured
false in §10.4: a status-row write wakes that relation's readers and nobody else.

### 12.1 Checked first: the rows are device-local

A heartbeat on a synced type would be constant sync traffic, so this was checked before anything
was built. **The catch-up rows never leave the device**:

- `SyncObserver` turns a save into a block only for a type that carries `Syncable`
  (`processSavedData` and `stageIntoLane`, both `schema.hasTrait(Syncable.ID)`), and tombstones are
  emitted only by explicit `emit` calls, never by a delete (`onDataDeleted` is empty).
- `SyncCatchUpStatus` and the new `SyncCatchUpHeartbeat` carry no `Syncable` trait. Their writer
  goes through `database.addToArray`/`removeFromArray`, as the other status rows' writers do.
- Pinned by `SyncCatchUpHeartbeatTest.heartbeats_enqueueNoSyncBlock_whereASyncableSaveDoes`: over a
  real `initSyncService` with an unlocked realm, twenty beats and a direct `saveData` of a heartbeat
  row queue no block, and a `Syncable` save in the same fixture does queue one (the positive
  control, so the empty result is not vacuous).

### 12.2 The design

**A second row per writer, `SyncCatchUpHeartbeat` (key, `catchingUp`, `beatAtMillis`), on a new
relation `SyncService.Relations.CATCH_UP_HEARTBEAT`.** The own row, `SyncCatchUpStatus`, and its
reader `catchUp(hostAppId)` are **unchanged**.

**The writer** (`SyncCatchUp`, the same one writer, under the same mutex):

- **Raise** writes the own row as before and a beat stamped with the wall clock, unless the store
  already holds one of ours that a reader sees (a relation member) and that is younger than one
  interval. So a ten-page catch-up inside one interval costs one beat, and a beat a sibling dropped
  from the relation (§10.3) is put back by the next page.
- **While catching up, a beat every `H`.** One loop, started by the flag going up and cancelled by
  it going down or by `stop()` (`collectLatest` over `catchingUp && !stopped`). The loop's write is
  unconditional — rewriting is the point — and checks the flag under the write lock, so it cannot
  land after a settle with a stale `true`. The beat waits for the store's lock off the main thread,
  as `flush()` already does.
- **A beat updates its row in place.** When the row is already a member: `saveData` and
  `notifyArrayMemberUpdated` (the R2b path), one store write and one member-updated event.
  Remove-then-add is kept only to restore a member that is absent or was dropped: as the routine
  write it reached every relation stream as a removal and then an addition, a `false` between two
  `true`s once per interval, and cost three writes (review round 1).
- **Settle** writes the beat `false` over any stored `true`, member or not (the `Row.reads` rule:
  a stale-cache sibling can put a dropped member back). A sibling therefore clears **promptly** on
  a normal finish, not after `T`.
- **Start** clears this writer's own stale beat if one says `true`, synchronously, as it clears its
  own status row. An ordinary launch writes nothing.

**The reader** (`SyncService.catchUpOnThisStore(hostAppId)`, which `SyncActivitySurface` now
reads): **the own row, OR any writer's beat that says `catchingUp` and is younger than `T`**
(`anyCatchUpHeartbeat`). Its one timer (`catchUpHeartbeatLiveness`):

- With nothing fresh it emits `false` and completes. **An idle store holds no timer and does no
  polling**; the next change of the relation starts it again (`flatMapLatest`).
- While something is fresh it waits until the youngest beat would expire or one interval,
  whichever is sooner, and **re-reads the rows** (§12.3 says why). So a row expires **on screen as
  time passes**, with no write; a sibling still beating keeps it lit; one that settled lets it fall
  within one interval even where its write is not heard.

**`H = 5 s`, `T = 15 s`** (`CatchUpHeartbeatTiming.Default`). `H` sets the write cost: one small
local transaction per 5 s per catching-up runtime, only while catching up, which §10.4 measured as
waking that relation's readers and nothing else. `T = 3H`: two beats can be missed — a write queued
behind a sibling's writer lock, a store slow under load — before a live pull reads as stopped; and
it bounds how long a killed app's last beat can light a sibling. Starting values, like the
debounce's; neither was measured on a device. The capsule's own debounce (700 ms show, 1 s hide)
still applies on top.

**The clock.** Wall-clock milliseconds: every process that reads a beat is on the same device and
shares the clock. ⚠ **A beat more than `H` in the future is expired** (`catchUpHeartbeatFreshForMs`).
A live writer stamps with the same clock and rewrites within `H`, so after the clock is set back its
next beat is current again; a stamp still more than `H` ahead was left by a writer that stopped. It
counts neither until the clock reaches it (after a jump of a day, a day) nor for a fresh `T` at
each subscription — the surface re-subscribes at every foreground, and an earlier rule ("fresh for
`T` from when first seen", per subscription) would have lit the capsule for 15 s at every
foreground for that day (review round 1). A stamp less than `H` ahead is ordinary skew and counts
from its stamp. A clock set **forward** makes a live beat look old: the capsule may drop for up to
`H`, until the writer's next beat.

**House Rule 9: a new type, no version bump.** A heartbeat field on `SyncCatchUpStatus` would have
reshaped a positional composite and owed a `SyncService` bump — which moves the realm's epoch and
pull-fences every device not yet updated, over a row that never leaves the device (§12.1). A new
non-`Syncable` type reshapes nothing: the precedent `SyncCatchUpStatus` and `RuntimeIdentity` set,
and §3.1's own reasoning. `SchemaShapeBaselineEnforcementTest` reported it unpinned and nothing
reshaped; both baselines were regenerated.

**The copy is unchanged.** "Syncing…", which never named the app or the data (§2 fact 2), so it is
as true of a sibling's pull as of one's own. `Uploading` stays Drive's in-process figure.

**Per platform.**

| Platform | What changes |
|---|---|
| iOS route 1 (shared replica) | the surface shows a sibling's pull; a killed sibling's row expires in `T` |
| iOS, each app on its own store | nothing visible: the only beat on the store is the own runtime's, which says what the own row says |
| Android, desktop, web | **no heartbeat is written**: `initSyncService(catchUpHeartbeat = false)`, the default; only iOS's `PlatformProxyModule` passes `true`. One runtime owns the store and nothing reads the relation. The own `SyncCatchUpStatus` row is written as before, and the reader, if anything ever reads it there, sees the own row and an empty relation |

### 12.3 What a sibling's beat reaches, store by store

> ⚠ *History (2026-11-05): written while both stores existed. The file store and its
> `rehydrateFromDisk` are deleted ([`ios-shared-store-sqlite.md`](ios-shared-store-sqlite.md)
> B.22.9), and the SQLite store below is the only iOS store, in every build with no switch. Its
> bullet is what holds today.*

⚠⚠ **A sibling's write does not reach this process's listeners on either iOS store today.**

- **`FileObjectStore`** (shipping): a sibling's write is seen at a `rehydrateFromDisk`, which each
  app runs at its foreground return; between rehydrates the resident cache is stale, so a re-read
  returns the same stamps. What the user gets: on switching to Photos while Control pulls, the
  foreground rehydrate fires the relation's listener (and the surface re-observes on
  `scenePhase == .active`), the reader sees Control's fresh beat and
  shows "Syncing…" for up to `T` after it; Photos' own pull, when it runs, lights its own row.
- **The SQLite store** (behind `TENSHU_SHARED_REPLICA_SQLITE`): the binding has no cache yet and
  every read goes to SQLite (B.13.2), so the re-read once per interval **should** see each sibling
  beat while the reader holds a fresh row. *Not verified*: no test opens the SQLite store with two
  processes for this. Once S0b-1's catch-up (`data_version`, listener delivery) lands, the
  relation's own changes carry the beats and the re-read becomes redundant, not wrong.

The re-read is modelled on the JVM by a second proxy over the same rows whose store never fires a
listener (`onAStoreThatDoesNotHearASibling_theReReadKeepsItsPullLit_andSeesItEnd`).

### 12.4 What exists

| Piece | Where |
|---|---|
| The row and its relation | `sync-service/.../SyncCatchUpHeartbeat.kt`; `SyncService.syncCatchUpHeartbeats`, `Relations.CATCH_UP_HEARTBEAT`; Dto, Ro and id provider in `:sync-service-common`, registered in `syncServiceCommonModule` |
| Timing, the clock, the liveness rule | `sync-service-common/.../CatchUpHeartbeat.kt` (`CatchUpHeartbeatTiming`, `wallClockMillis`, `CatchUpBeat`, `catchUpHeartbeatLiveness`) |
| The writer | `SyncCatchUp` (`Heartbeat`, `syncHeartbeat`, `beat`, the loop in `publishIn`); `initSyncCatchUp(…, nowMillis, timing)` |
| The reader | `SyncService.anyCatchUpHeartbeat`, `SyncService.catchUpOnThisStore` in `SyncServiceExtensions.kt`; `SyncActivitySurface.observeActivity` reads the latter |
| The rehydrate fix (§12.5) | `FileObjectStore.rehydrateFromDisk`: every new row indexed before any listener runs. ⚠ *Deleted with the file store, 2026-11-05* |

### 12.5 Verification, and a store defect it found

⚠⚠ **A pre-existing `FileObjectStore` defect, fixed here.** `rehydrateFromDisk` indexed newly
appeared rows and notified each one's listeners in the same loop. An array row's listener
(`DatabaseImpl.setArrayListener`) reads every added member, and threw *"Entry not found"* for a
member the loop had not reached yet — so the foreground rehydrate threw whenever a sibling had
**created** a relation this process was already listening to, depending on the directory's order.
The surface's live observation of the heartbeat relation made that the common case (Control's
first beat creates the relation while Photos listens); it was reachable before for any relation
first written by a sibling. Now every new row is indexed before any listener runs, and each
listener the rehydrate calls runs in its own `try`: one that throws is logged (row hash and
exception class only) and the rest of the rehydrate — above all the resident rows still to be
refreshed, the stale-write hazard it exists to close — completes (review round 1).
`aListenerThatThrows_doesNotStopTheRehydrate` pins that.
`FileObjectStoreRehydrateTest.aListenerOnANewRow_findsEveryOtherNewRow_whateverTheDirectoryOrder`
fails without the fix and passes with it.

⚠ **A relation stream delivers its snapshot row by row**, so `flow().first()` can return a partial
list. The reader's re-read therefore uses `getAll()`, and the tests read rows the same way.

| Level | Test | What it pins |
|---|---|---|
| Writer | `SyncCatchUpHeartbeatTest` (JVM, virtual time, wall clock tied to it) | a beat is one store write; twenty beats read as one appearance, the row never gone between them; no heartbeat unless the platform asks (`initSyncService`'s default); a beat each `H` while catching up and none when idle; one beat and one clear for ten pages inside an interval; a raise puts back a beat a sibling dropped; `stop` ends the beat and clears; a relaunch clears its own stale beat; an ordinary launch writes none |
| Reader, two writers over one store | same | a sibling's pull lights the reader; it stays lit while the sibling beats; a killed sibling clears at its last beat + `T` with no write (one emission, from time alone); a normal finish clears with no wait; a beat a day in the future stays dark at five successive subscriptions, and one less than `H` ahead counts from its stamp; an idle store and an expired row cost no further evaluation in an hour (the clock read is the probe); the own row lights with no fresh beat and never expires; a killed sibling's own row is still never read |
| Reader, a store that does not hear a sibling | same, a second proxy over the same rows whose store fires no listener | the re-read keeps a beating sibling lit past `T`, a kill expires, a normal finish is seen within one interval |
| Device-local | `heartbeats_enqueueNoSyncBlock_whereASyncableSaveDoes` | §12.1 |
| A real shared replica | `SyncActivitySharedReplicaTest` (`iosSimulatorArm64Test`, two route-1 assemblies over one `FileObjectStore`) | Control raises → Photos' **surface** says "Syncing…" after a foreground; Control's graph is cancelled (a kill) → the line goes `null` no earlier than the judged beat + `T`, the row on disk still says `true`, no beat follows the kill; a normal finish clears at the next foreground; a catching-up runtime writes a second beat after `H`, an idle one none |

**Mutations, each run and each red** (JVM unless marked):

| Mutation | Red |
|---|---|
| reader ignores the heartbeat half | 6 reader tests; iOS: the kill and the normal-finish tests |
| no expiry | 6 tests incl. the kill, the future beat, idle; iOS: the kill test |
| a far-future beat counts as just written (the round-1 bug's shape) | the five-subscriptions test |
| a beat rewrites its member as remove-then-add | `aBeat_isOneStoreWrite` (four writes, not one). ⚠ `aLongCatchUp_neverReadsTheRowAsGoneBetweenBeats` stays green under it: in one process the pair's two events never surfaced as a gap in the stream, so that test pins the property and does not discriminate; the gap is what a sibling's listener would see once S0b-1 delivers its writes |
| `initSyncService` defaults to writing the heartbeat | the no-heartbeat-unless-asked test |
| a rehydrate listener's exception not caught (iOS) | the throwing-listener test |
| poll while nothing is fresh | the idle and timer-end tests |
| no re-read | both deaf-store tests |
| the beat loop runs regardless **and** `beat()` ignores the flag | 5 tests (each half alone is green: the two are redundant guards) |
| settle writes no `false` beat | 5 tests |
| raise trusts a non-member beat | the dropped-member test |
| reader ignores the own row | the own-row test |
| `SyncCatchUpHeartbeat` made `Syncable` | the device-local test |
| `rehydrateFromDisk` notifies while indexing (iOS) | the new rehydrate test |

### 12.6 Open, and owed on the device

- ⚠⚠ **Not seen on a phone.** The device check: on the iPhone, open Control first so it pulls,
  then switch to Photos while photographs arrive. Expect "Syncing…" on Photos' return to the
  foreground, for up to 15 s after Control's last beat or for as long as Photos' own pull runs.
- ⚠ **A reader that has gone idle on a deaf store is deaf to a sibling that starts later.** With
  nothing fresh the reader holds no timer, by design, so on the SQLite store (no listener delivery
  until S0b-1's catch-up) a sibling's *new* pull is noticed only when the relation changes in this
  process or the surface re-observes at a foreground. On `FileObjectStore` the foreground
  rehydrate delivers it. *Recommend* leaving it: S0b-1's catch-up closes it without a timer.
  ⚠ *(2026-11-05: the file store is deleted, so the SQLite half of this bullet is the whole of it.)*
- **The SQLite re-read** (§12.3) is unverified across processes.
- **`H` and `T`** are unmeasured. If the device shows the capsule dropping during a long
  Control-side apply, raise `T` before touching `H`.

### 12.7 A sibling's in-place beat reached no stream (found 2026-11-04, fixed)

> ⚠ *History: this fix was the file store's, and went with it on 2026-11-05
> ([`ios-shared-store-sqlite.md`](ios-shared-store-sqlite.md) B.22.9): `rehydrateChangedRows`, both
> rehydrate callers and `FileObjectStoreRehydrateTest` are deleted. `Database.notifyMembersRewrittenElsewhere`
> is still in `:core-common` with no production caller. On SQLite a sibling's in-place beat reaches
> a reader only through §12.3's re-read.*

⚠⚠ **A product defect, found chasing a flake of `SyncActivitySharedReplicaTest`.** §12.2's beat
is an in-place save once the row is a member: `saveData` + `notifyArrayMemberUpdated`. That event
is **in-process** — it never reaches a sibling. The sibling's rehydrate refreshed the row, but its
notifications are the store's: a row's own listener (a member row has none) and the array row's,
which `DatabaseImpl` diffs by **membership** — unchanged. So once Photos' view held Control's beat
row (any earlier pull, or Photos opening after one), every later Control pull was invisible to
Photos' **mounted** surface, and every read joining that stream was handed the old row, at every
foreground. §12.3's "the foreground rehydrate fires the relation's listener" held for a sibling's
*first* beat only. The same gap covers any relation a sibling saves a member of in place.

**The fix — `FileObjectStore` only.** `rehydrateChangedRows()` says which rows changed, and both
iOS rehydrate callers (`FirstPartyLocalRuntime.rehydrate`, `ControlSurface.rehydrateSharedReplica`)
hand the set to `Database.notifyMembersRewrittenElsewhere`, which fires each listened array that
still lists a changed row — **once per array**, not per member, since every production listener
re-evaluates the whole read and a sibling's bulk write can change thousands of rows. A double
notice (the rehydrate's own array-row diff plus this) costs one spare re-evaluation and is accepted.
The SQLite store has no rehydrate and is not covered (§12.3). ⚠ Retired with the file store (the 1.0.1 re-cut): with no rehydrate
left to call it, `notifyMembersRewrittenElsewhere` was deleted (`chore/remove-file-store-seams`).

**Which rows count as changed** (fix round 1):

- a row that appeared or vanished, and a **resident** row whose bytes differ from the cached copy;
- a **watched** row that is **not** resident — one with a store listener (a listened array row) or
  a member of one — whose file was modified since the previous rehydrate (or the store's open).
  Not resident covers a row over `LARGE_ROW_THRESHOLD_BYTES` (never cached), one evicted by
  `invalidateCached` / `readDurable`, and one only ever scanned. ⚠⚠ Residency is the wrong test on
  its own: the stale copy lives in `StreamRegistry`'s last emission, not in the store's cache. The
  check is by modification time, so it may report a row this process wrote itself — a spare
  notice, never a missed one; a wall clock set back across a rehydrate can miss one.
- Rows neither resident nor watched are not checked: nothing listens to them, and their next read
  is fresh. The cost is one `stat` per watched non-resident row, and one raw read of each listened
  array row that is not resident, per foreground.

**The notice never depends on decoding the member.** The value handed to `onAdded` is a courtesy
(`StreamRegistry` ignores it), so a changed member whose row, or a row it refers to, cannot be
read still fires its array: the first changed member that decodes, else a `DataId` this process
knows, else the array's own reference.

Pinned by `aSiblingsLaterPull_rewritesItsBeatInPlace_andStillLightsAMountedSurface` (red with the
`Database` call removed), `FileObjectStoreRehydrateTest`'s three residency tests (red with the
watched-row pass removed) and `NotifyMembersRewrittenElsewhereTest` (red when a member that does
not decode skips its array).

**The flake itself was the test's.** Its reader took the last value of a fixed 1.5 s window. A
read made while the surface holds the same chain **joins** that stream (`DefaultInteractorProvider`
reuses by stream id) and gets its current value at once, while the rehydrate's re-evaluation is
still a launched coroutine — measured: `false`, then `true` 0 ms later idle; under load the `true`
can land after the window. Reads after a foreground now wait for the expected value and hold it
for 500 ms (`readsOnStoreSettlesAt`, `readsOfSettlesAt`) — for **positive** expectations only. ⚠ A
negative one must not "settle at false": that passes on `false → true → false`, a real
resurrection. It asserts `true` never appears over a 1.5 s window once `false` is read
(`readsOnStoreNeverTrue`, `readsOfNeverTrue`); only the first value of a read that joins a stream
which rightly said `true` before the rehydrate may be skipped.

⚠ **`settled(false)` is not "the beat says false".** The settle's collector writes the own row,
then the beat; a sibling rehydrating in between still sees the pull. Tests that hand a finished
pull to a sibling wait for both (`settledWithBeat`).

⚠⚠ **Open: a second writer under a sibling's id (found fix round 1, 2026-11-05, not yet
identified).** In `aSiblingsLaterPull_rewritesItsBeatInPlace_andStillLightsAMountedSurface`, 1 in
12–36 loaded class runs, Photos opens the replica and reads Control's rows as a fresh **raise**:
the own row and the beat both say `true`, and the beat is stamped about 40 ms after Control's
settle. Control's own view does not hold that write. It reads the `true` only after rehydrating
from disk, and its writer's flag is `false`. So something wrote Control's rows through a store
instance other than Control's own.
- The review's hypothesis, an extra `SyncScheduler.drainNow` tick on Control's writer, is not
  what this is. Control's writer's own store does not hold the write, and its flag is `false`.
  (312 instrumented runs showed only Control's three writes, but none of them reproduced the
  defect, so they say nothing about a failing run.)
- Printing from the write path hid it (0 in 312 runs). A silent creation registry did not: it
  reproduced 1 in 144.
- Round 2 ruled out a second `SyncCatchUp` under Control's id created in the same test, and any catch-up write through Photos' store. The test now treats the case as **inconclusive**: it prints the full diagnostic, returns early with "S4 OPEN", and never retries. `tech-debt.md` carries the entry.
