# Control-on-iOS — the notification center and the shares inventory

**Status:** v0.3 — **NT-1 + SH-1 SHIPPED 2026-08-13; SH-0 shipped 2026-08-12;
Q3's mock-default seam CLOSED (§9); Q2 answered (§10); **NT-2 answered then REVERSED the same day —
[`ios-notification-elevation.md`](ios-notification-elevation.md) v0.2 now says give Control a
background wake.** As-built notes in §8. **NT-2b + NT-2c SHIPPED** (elevation doc §13) — Control now has a
background wake and posts request alerts. Remaining: the on-device pass. ⚠ NT-3 is un-mooted by the reversal —
once something is posted, actions on it are buildable again.
The other two Compose Control screens with no iOS sibling, written up together because they share
a frame and differ in exactly one place each.

**These are the inverse of [`ios-control-offline-maps.md`](ios-control-offline-maps.md).** There,
the screen looked like a port and wasn't — the architecture underneath it (D5's single Control-owned
store) doesn't hold on iOS. Here, both screens look structural and mostly aren't: **every view
model, mapper, repository and test they need already lives in `:control-shared-logic`**, the
Compose-free module that has iOS targets and is `export`ed from `:tenshu-sdk-ios`. This is the
`ios-fit-timeline-maps-parity.md` §1 finding again — *"almost none of the gap was view-model
logic"* — and it holds here more completely than it did there.

Each still has one real catch, and they are not the same catch. §3 and §4 are the doc.

Companions: [`control-central-notifications.md`](control-central-notifications.md) (P1–P4 as built;
P5 already recorded BLOCKED on Control-on-iOS), [`control-ia.md`](control-ia.md) §5.3 (where Shares
sits), [`ios-control-handover.md`](ios-control-handover.md) (the C0–C6 roadmap that tracks flows,
not screens), [`ios-proxy-architecture.md`](ios-proxy-architecture.md) §9.4 (no persistent
background process).

---

## 1. What is actually missing

⭐ **Less than the screen list suggests.** The *ambient* half of notifications already ships on iOS:
`control-ios/Sources/HomeView.swift` renders the paged event feed through
`ControlSurface.homeBound()` over the real `TenshuEventRepository`, flattening the shared two-level
sealed `Event` into a Swift-side `ControlEventRow`. Home's "Recent Events" is live.

What has no iOS sibling:

| Compose Control | iOS | What it is |
|---|---|---|
| `notifications/NotificationCenterScreen.kt` | — | the full list of inbound **actionable** requests |
| `notifications/PendingRequestCard.kt` | — | one request + its actions (approve / reject / dismiss) |
| `notifications/PinnedRequests.kt` | — | the sticky slot above Home's feed |
| `shares/SharesScreen.kt` | — | Data → **Shares**: the realm's outbound link-share inventory + revoke |

So the gap is the **elevated/actionable** surface, not notifications-in-general — a distinction
worth keeping, because the two halves have completely different iOS stories (§3).

**Everything behind them is already iOS-capable**, in `:control-shared-logic`:

- `NotificationsViewModel`, `NotificationMapper` (+ its test), `PendingRequest`,
  `PendingRequestAlerts` (+ test), `PendingRequestsRepository` (contact invites, membership grants,
  pairing approvals — all client-side ops), `Event`, `EventRepository`, `EventPagingSource`.
- `SharesViewModel`, `SharesUiState`, `ShareManager` / `TenshuShareManager`.
- Paging works: `:core-paging` is an `api` dependency there, and the module's own build comment
  records that only **iosX64** is excluded (`paging-common` ships no iosX64 klib) — `iosArm64` and
  `iosSimulatorArm64`, the two targets that matter, are fine.

The port shape is then the familiar one: a `ControlSurface.notificationsViewModel()` /
`sharesViewModel()` factory plus SwiftUI, exactly as `pairingWizardViewModel()` and `homeBound()`
already do. No Kotlin logic, no new module, no build change.

---

## 2. What ports for free, and one thing that ports *well*

⭐ **Synced dismissal already works on any new device.** Central-notifications P4 made dismissal a dedicated synced
`DismissedRequest` record keyed per id, chosen over a list preference precisely so concurrent
multi-device dismissals union-merge instead of last-writer-wins clobbering. An iOS Control joins
that correctly the moment it renders the surface — no iOS-specific work, and no risk that the
"third device" case is where the design first gets tested.

The actions are ordinary ops through the in-process client (Control-on-iOS runs the real proxy since
C4-RT), and `PendingRequestAlerts` / `NotificationMapper` are pure and already unit-tested.

---

## 3. Notifications — the catch is **OS elevation**, and it is a decision, not a gap

The in-app screen is free (§1). The half that makes it *useful* is not, and the reason is precise
enough to be worth stating exactly:

> ⚠⚠ **`control-shared-proxy`'s iosMain deliberately binds `NoopNotificationPresenter`**
> (`PlatformProxyModule.kt:173`). Android binds `AndroidNotificationPresenter`; desktop binds its
> scheduling twin; **iOS binds nothing on purpose.**

The reason is in `IosNotificationPresenter`'s own KDoc: `UNUserNotificationCenter` is **per app**,
and under route 1 *every* first-party app runs the same proxy graph over the shared replica. If more
than one app bound a real presenter, the user would get **every notification once per app**. So the
base iOS module noops it and exactly one app overrides — today that is `CalendarLocalRuntime`, for
reminders. It mirrors `ios-drive-parity.md` P6a, where Drive rather than Control is the background
syncer on route 1.

**So the iOS twin of P3a (Android OS elevation) is not "write a presenter" — the presenter exists
and works.** It is: *decide which app owns posting each class of notification, and give Control a
way to post its own without becoming a second poster for everything else.* Binding
`IosNotificationPresenter` in Control's platform module would regress every route-1 user into
duplicate Calendar reminders — the single most likely wrong fix, so it is named here.

Two further constraints, neither new:

- ⚠ **No persistent background process** (`ios-proxy-architecture.md` §9.4). An inbound pairing
  approval cannot wake Control on iOS; the surface is live only while the app runs. That is also why
  `control-central-notifications.md` **P5** (the 6e app-reconnect hook) is already recorded as
  **BLOCKED on Control-on-iOS** — this doc does not unblock it.
- ⚠ **No action buttons on an Apple notification without a second path** — a registered
  `UNNotificationCategory` plus a `UNUserNotificationCenterDelegate` in the Swift shell that receives
  the response and calls back into Kotlin. `IosNotificationPresenter` currently drops
  `AppAlert.actions` for this reason and a tap just opens the app. An *approve from the
  notification* affordance is therefore a real slice, not a parameter — and for a pairing approval,
  arguably one we do not want: approving a device from a lock-screen button, without the SAS in
  front of you, is the wrong ceremony (`sync/pairing-flow.md`).

**Recommendation:** ship the in-app notification center (NT-1) — it is honest on its own, works
whenever the app is open, and needs none of the above. Treat elevation as its own decision (NT-2)
with the ownership question answered first.

---

## 4. Shares — the catch is that **half the screen has been stale since June**

`ShareManager` is two halves behind one interface, and only one of them is alive:

- **Link shares — live.** `shares()` projects the realm-wide `FileService.shares` collection
  (`Share` is `Syncable(Eager)`, so every realm device sees the same list) and `revoke()` calls
  `RevokeShareLink`. Ordinary client work; ports as-is.
- **Shared folders — stubbed.** `TenshuShareManager.sharedFolders()` returns `flowOf(emptyList())`,
  `members()` likewise, and `removeMember()` throws `NotYetAvailableException`. The KDoc says
  *"Empty until the shared-realm protocol (`tenshu-rei.md` §2.3) lands."*

⚠⚠ **That protocol landed. GR-1…GR-7 completed 2026-06-14**, and Drive has been reading membership
for months — `MembershipRepository`, `getSharedRealmMemberships`, `grantSharedRealmMembership`,
`ManageAccessViewModel`, and `SharedScreenViewModel`'s *Shared with me*. Control's inventory is
still returning empty lists against ops that exist.

So the finding is **not an iOS one**: Control's Shares screen shows an empty *Shared folders*
section on Android and desktop today, and porting it to iOS would faithfully reproduce a half-dead
screen on a third platform. ⚠ It also *looks* correct in a screenshot — an empty section is
indistinguishable from "you have no shared folders", which is exactly the failure mode
`offlineMapsSnapshot`'s `catalogUnavailable` flag exists to prevent one screen over.

**Recommendation: unstub first, port second** (SH-0 before SH-1), and unstub on all three platforms
at once, because it is one implementation in `:control-shared-logic` and every platform reads it.

---

## 5. Phases

| Phase | Work | Size |
|---|---|---|
| **SH-0** | ⭐ **Unstub `TenshuShareManager.sharedFolders()` / `members()` / `removeMember()`** against the shipped GR ops (`getSharedRealmMemberships` + the membership-revoke path Drive already drives). Cross-platform — fixes Android and desktop too | **S–M** |
| **NT-1** | `ControlSurface.notificationsViewModel()` + SwiftUI notification center: the request list, `PendingRequestCard` actions, the pinned slot above Home's existing feed, and the Home bell + badge | **S** |
| **SH-1** | `ControlSurface.sharesViewModel()` + SwiftUI Data → Shares: link-share inventory (with the Revoked badge Control keeps and Drive filters), revoke-confirm, and — post-SH-0 — the shared-folder section | **S** |
| **NT-2** | ✅ **ANSWERED — [`ios-notification-elevation.md`](ios-notification-elevation.md) v0.2 (D1-rev: give Control a background wake).** ⚠⚠ The v0.1 answer was *decline*, reversed the same day: iOS not waking Control is **our configuration**, not a platform limit, and Android's own closed-process path is `~hourly` best-effort — so a `BGAppRefresh` is parity, not a consolation prize (§5.5). ⚠ Do **not** bind `IosNotificationPresenter`; the path is `PendingRequestAlertHandle` (D3). NT-2a done; NT-2b is now the background mode + handle | **M** |
| **NT-3** | ⚠ **Un-mooted by NT-2 v0.2** — B′ posts something, so actions on it are buildable again. Still gated on NT-2b, and the original caution stands: ⚠⚠ approving a *pairing* from a lock-screen button without the SAS in front of you is the wrong ceremony | **M** |

NT-1 and SH-1 are the two that close the visible gap, and both are SwiftUI over logic that already
compiles for iOS. SH-0 is the one that should not wait for iOS at all.

---

## 6. Decisions proposed

- **D1 — Ship the in-app surfaces before the OS ones.** An actionable list that works while the app
  is open is honest and complete on its own terms; elevation is a separate capability with a
  separate blocker.
- **D2 — Notification *posting* ownership is a platform decision, recorded once.** Today it is
  implicit in which runtime overrides the noop binding (Calendar, for reminders). Whatever NT-2
  concludes belongs in `control-central-notifications.md` as a numbered decision, not in a DI
  comment — a second app binding a presenter is a silent regression that no unit test catches.
- **D3 — SH-0 is a bug fix, not a feature, and does not belong to the iOS track.** It is filed here
  only because porting the screen is what surfaced it.
- **D4 — No schema, wire or threat-model change.** Both surfaces are reads and existing ops over the
  in-process client; `DismissedRequest` already syncs. (⚠ SH-0 may need a membership-revoke op that
  Drive already calls — if it turns out Control needs a *new* op, that is its own note.)

---

## 7. Open questions

- **Q1.** Does the Home **bell + badge** belong on iOS Home, or does the iOS IA want the pinned slot
  only? Compose reaches the center from `NotificationsBell` in Home's top bar; SwiftUI has a
  toolbar, so the shape transfers — but Home on iOS is currently titled *"Recent Events"* and has no
  top-bar affordance at all.
- **Q2.** ✅ **ANSWERED — no duplication, and the question's own premise was wrong (§10).** It asked
  whether Control's shared-folder section duplicates Drive's *Manage access*, on the assumption that
  *"the member-removal action exists in both and should be checked for divergent confirm copy"*. It
  does not exist in both. `SharedFolderRow(folder)` takes only the folder and renders name, member
  count and ownership — no action, no roster, no callback — and neither `members()` nor
  `removeMember()` has a single call site in Control on either platform. Control is a read-only
  inventory and Drive owns member management, exactly as `control-ia.md` §5.3 says. There is no
  confirm copy to diverge. ⚠ But see §10: the *implementation* those two methods need is finished
  and unreachable.
- **Q3.** Should the notification center show anything at all on an **unprovisioned** iOS Control?
  Timeline learned this the hard way (`ios-fit-timeline-maps-parity.md` §2.2: a mock repository
  presented invented data as real, fixed with `NotConnectedTimelineRepository`).

  ⚠ **Checked, and Control's Home is currently safe — but by call ordering, not by construction.**
  `ControlSurface.installedEventRepository` defaults to `MockEventRepository()`, whose
  `EventPagingSource` generates an *endless* stream of plausible fabrications
  (`vacation_photo_7.jpg` added, `old_backup_12.zip` deleted, `Drive Client 3` registered, with
  realistic descending timestamps). What keeps it off a user's screen is that
  `ControlIosApp.init` calls `installRealRuntime()` on **every non-screenshot boot**, which swaps in
  a real `TenshuEventRepository`; the mock survives for the DEBUG deep-links and `installFakes()`.

  So there is no live bug — and also no structural guard. Timeline's fix was to make
  not-connected a **repository** rather than an ordering guarantee, and any new iOS entry point that
  forgets that one call inherits fabricated data silently. Worth converting to the Timeline shape
  before a second consumer (the notification center) starts reading the same seam.

  ✅ **RESOLVED — converted (§9).** And the survey that preceded it corrected this entry twice: the
  seam was never one repository but **seven**, and the argument for acting was stronger than "a
  second consumer appeared" — Control was the *last* iOS surface still defaulting to fabrication.

---

## 8. As built (NT-1 + SH-1, 2026-08-13)

Both screens shipped as designed: the gap really was unwritten SwiftUI, and §2's claim held — no
view model, mapper or repository had to be written, only `ControlSurface` bridging and Swift.

**The projection rules live in `commonMain`, not in Swift.** `pendingRequestFacts()` decides which
sealed leaf shows which subject and which variant carries a SAS; `unreadRequestCount()` decides what
the badge counts. Those are the parts that can be quietly wrong, so they are pinned by an ordinary
desktop test and `ControlPendingRequestRow` keeps only enum translation and one date format. ⚠ One
of those tests failed first and was right to: `unread` derived through `combine(...).stateIn(...)`
reads **stale immediately after a mutation**, which would have made every dismissal test racy — it
is recomputed synchronously now, from one derivation rather than a second stored copy.

**One snapshot, not four `for await` loops.** `notificationsBound()` combines the view model's four
flows into a single `ControlNotificationsSnapshot`; four independent loops in SwiftUI would each
render from a partial picture, so a card could show its spinner a frame before the request it
belongs to exists.

**The sealed leaves never cross the bridge.** `ControlSurface` takes a request *id* back and
re-resolves the leaf where the `when` is exhaustive. An id that no longer exists is a no-op rather
than a crash — a request can resolve between render and tap.

**Q1 is answered: the bell shipped.** Home gained a toolbar bell with an unread badge plus the
pinned slot above the ambient feed, i.e. the Compose shape transferred intact.

### 8.1 What the Mac pass found — two things no Kotlin gate could see

The PR was authored on a Linux host, where Apple targets are *skipped* (appending literal garbage
to a file in `iosMain` still yields `BUILD SUCCESSFUL`) and there is no Swift toolchain at all. It
was opened as a draft saying so. On a Mac, `xcodebuild -scheme ControlIos` failed with **five errors
from three causes** — while `compileKotlinIosSimulatorArm64` passed *unchanged*. Everything that
broke was Swift.

1. ⚠⚠ **`ContentUnavailableView` is iOS 17+; every Tenshu shell targets 16.0** (4 of the 5 errors,
   both new files). This is the **most-repeated iOS mistake in the repo** — 5 occurrences now
   (contacts' iOS shell, then fit/timeline/maps, then NT-1 + SH-1, which used it twice). Every fix is the
   same hand-rolled `VStack{Image+Text}` under a different name, because the shells link no common
   Swift module: `FitEmptyState`, timeline's `DetailSupport`, contacts' and maps' local copies, and
   now `ControlEmptyState`. Before writing a sixth, grep for the sibling to copy.
2. ⭐ **`NotificationsViewModel` had no `refresh`.** `PendingRequestsRepository.refresh()` existed
   with **no caller anywhere in the repo**: Compose Control shows this screen in a scaffold that
   never offered the gesture, so the 30 s grant sweep was the only thing that ever moved a poll-only
   source. The iOS pull-to-refresh is that interface member's first consumer; the view model now
   forwards it. A stub can sit in an interface indefinitely when only one platform has the UI idiom
   that calls it.

The nullable `memberCount` crossing as `KotlinInt?` — the risk the draft flagged highest — is
**clear**: the `-shares` render exercises both branches (`3 members` and the null fallback
*"Shared with you"*), so `Int(truncating:)` holds for present and absent counts. The mock's
deliberate unnamed / unknown-count sample earned its keep.

⚠ Verified on the **rebased** tree, not the authored one: N6 replaced `TabView` with
`AdaptiveAppShell` underneath these screens between authoring and merge, and a green gate certifies
a tree rather than a branch.

### 8.2 Not covered by any gate

- **Home's bell + badge is not behind a screenshot flag** — it needs a real session, so unlike the
  center and the inventory it has never been rendered in verification. It is the one NT-1 surface
  resting on compile-correctness alone.
- **The two new empty states compile but never render**, because both screens run over Mocks that
  always carry samples. This is the mirror image of Q3's concern: the deep-links exist so a
  screenshot is not empty, which means the empty branch is exactly what they cannot show.

---

## 9. The mock-default seam, closed (Q3)

`ControlSurface`'s `installed*` seams now default to **not-connected** implementations rather than
Mocks and Fakes, and the fakes became an explicit opt-in. This is the conversion Q3 asked for, and
what it took was smaller than the finding it closed.

### 9.1 Q3 understated it twice

Q3 was written about *the event feed*, and §8.2 framed the notification center as "a second
consumer". Surveying the surface before converting corrected both:

- **Seven seams, not one or two** — events, pending requests, shares, the app registry, realm data,
  sync, and the biometric delegate.
- **Control was the last iOS surface on the old shape.** `FitSurface` already defaulted to
  `NotConnectedFitRepository`; Timeline, Maps, Contacts and Calendar had all been converted. So the
  argument was never "a new consumer arrived" but "every sibling already did this."

⚠ The reads were the mild half. **`FakeControlSyncRepository` answers every *write* with
`ResultSuccessDto`** — `createRealm`, `revokeDevice`, `approvePairingRequest`, `recoveryClaim` — and
hands the Show tab a well-formed canonical `tenshu://join` link. `unlockLocal` returns `true` for
*any* passphrase. None of it is a vulnerability where it is reached (no runtime installed means no
keys and no store behind the shell, so nothing real is unlocked or created), but each one is a
confident false answer about an operation that never happened.

⭐ The one seam that was already safe: `FakeControlBiometricUnlockDelegate` fails **closed** —
unavailable, not enrolled, `unwrapWrapKey → NotAvailable`. It denies rather than grants. It was
converted anyway, because its `enrollWrapKey` returns `Success`, claiming an enrolment that never
happened — and because a default list of six `NotConnected*` and one `Fake*` invites the same
question at every reading.

### 9.2 The shape

⚠⚠ **A failure, not an empty success** — the distinction `NotConnectedFitRepository` documents at
length, and the whole reason this is not a one-line default swap. Empty successes would make the
screens draw confident conclusions about the user's realm: *"No recent events"*, *"You are all
caught up"*, *"Nothing shared yet"*, an empty Apps list. Those are fabricated claims about the
user's data, just quieter than fabricated rows, because emptiness reads as a legitimate state.

So the events feed returns a `PagingSource` that **errors** rather than one returning zero rows
(the paging analogue of Fit's `ListLoadState.Failed`), every sync call returns
`ResultFailureDto`, the request actions return `RequestActionResult.Failure`, and the grant/share
writes throw.

⚠ `getDeviceId()` is the one method with nowhere to put a failure — a bare `String`. It returns
`"not-connected"` rather than the fake's plausible `"ios-control-device"`, which reads like a real
id in a log.

### 9.3 The fakes are now opt-in, and that is the load-bearing half

The screenshot deep-links relied on the mock **default**: `ControlIosApp.init` skipped
`installRealRuntime()` for those routes and never called anything else. Flipping the defaults alone
would therefore have emptied every capture. `ControlIosApp` now calls `installFakes()` explicitly on
the screenshot branch — verified by re-rendering `-shares`, which is pixel-identical to its NT-1 + SH-1
capture.

⚠ **Do not restore a fake as a default.** The safe state must be the one you get by doing nothing;
that is the entire content of this change, and the defaults are otherwise one careless edit from
where they started.

### 9.4 What guards it

`NotConnectedControlRepositoriesTest` (8 tests, `iosSimulatorArm64Test`) pins one invariant: **a
not-connected repository never answers successfully.** ⭐ Verified non-vacuous the only way that
counts — pointing the sync test at `FakeControlSyncRepository` turns it red, so it is asserting
success-vs-failure rather than shape.

⚠ The tests construct the classes directly and do **not** assert on `ControlSurface`'s defaults:
the surface is a process-wide `object`, and a test reading its mutable installed state would pass or
fail on what ran before it. The defaults themselves rest on the declaration-site comment and on the
Swift side's explicit `installFakes()` — honest to say so rather than to imply coverage that a
singleton cannot give.

---

## 10. `members()` / `removeMember()` — built, working, and reachable from nothing (from Q2)

Answering Q2 turned up something the question did not ask about. `TenshuShareManager` implements
all three shared-folder methods; **SH-1 consumed one of them.**

| Method | Implemented | Called from Control |
|---|---|---|
| `sharedFolders()` | ✅ SH-0 | ✅ the section SH-1 shipped |
| `members(realmId)` | ✅ SH-0 | ❌ nothing |
| `removeMember(realmId, memberId)` | ✅ SH-0 | ❌ nothing |

⚠ **This is not an unimplemented stub — it is the opposite, and that is what makes it worth
recording.** `removeMember` calls the real `removeSharedRealmMember` op, raises a dedicated
`MemberRemovalFailedException` rather than swallowing a failure (with a comment explaining that a
swallowed one *"reads to the user as a removal"*), and there is a `rosterRevision` counter whose
entire purpose is to make the one-shot roster reads re-run **after a removal that never happens**.
`members()` maps over that same counter. It is careful, deliberate code with no consumer.

⚠ The only test naming these is `MockShareManagerTest` — it exercises the **mock**, not
`TenshuShareManager`. So the real implementation has neither a caller nor direct coverage.

⚠⚠ **The interface KDoc still calls all three "Stubbed until that protocol lands."** That was true
until SH-0 and had been wrong since. Fixed alongside this note — a stale "not built
yet" is worse than silence, because it tells the next reader not to look.

### 10.1 The decision this needs

Two coherent options, and it should be one of them rather than drift:

1. **Control grows member management** — a roster under each shared-folder row, owner-only, with a
   removal confirm. Then `members()`/`removeMember()` are prerequisites that arrived early, and the
   ⚖️ IA question Q2 *thought* it was asking becomes real: two surfaces removing members, whose
   confirm copy must agree. ⚠ `control-ia.md` §5.3 currently says creation stays in Drive but is
   silent on *removal*, so this needs an IA decision first, not a UI PR.
2. **Control stays a read-only inventory** — then the two methods do not belong on Control's
   `ShareManager` at all, and carrying a working revocation path behind an interface nobody calls is
   a liability, not forward-compatibility.

⭐ **Second instance of this exact shape in one day.** `PendingRequestsRepository.refresh()` also sat
on a `:control-shared-logic` interface with zero callers until the iOS pull-to-refresh became its
first consumer (§8.1). Both were added "so the screen-level wiring doesn't churn later". That
rationale is reasonable and it has now twice produced code that no gate covers and no reader can
tell is dead — worth a habit of checking call sites when adding to these interfaces.
