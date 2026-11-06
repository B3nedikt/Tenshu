# NT-2 — who posts an iOS notification, and can Control post at all?

**Status:** v0.3 — **NT-2b SHIPPED (as built §13); NT-2c folded into it.** Remaining: the
on-device pass (the granted-permission banner + the real OS wake), which belongs with P7.
⚠⚠ **REOPENED, and the v0.1 decision was REVERSED (2026-08-13, same day).**
v0.1 concluded *decline* (D1); v0.2 concludes **give Control-on-iOS a background wake** (D1-rev,
option B). Nothing about the platform analysis changed — §2 stands exactly as written. What changed
is the **yardstick**: v0.1 measured a background wake against *on-arrival* delivery, found it
wanting, and dismissed it. Measured against **what Android actually does**, it is parity. §5.5 is
the correction and it is the part to read.

Written to settle NT-2 of
[`ios-control-notifications-and-shares.md`](ios-control-notifications-and-shares.md) §3, whose
in-app tier (NT-1) has shipped.

⚠⚠ **The framing in NT-2's parent is not wrong, but it is not the binding constraint.** §3 asks
*"which app owns posting which class of notification"* — a duplicate-suppression question, because
`UNUserNotificationCenter` is per app and every route-1 app runs the same proxy graph. That is real.
But surveying the as-built found a harder constraint underneath it:

> **Control-on-iOS cannot post an alert *the moment* an inbound request arrives** — not because the
> binding is taken, but because the two mechanisms iOS offers both fail for this event class. It
> cannot *pre-arm* (the event is not known in advance) and it is not *woken* (`control-ios` declares
> no background modes at all).

⚠ Both halves of that are still true. The error in v0.1 was treating the second as a *platform*
limit; it is **our own configuration**, and Drive and Fit already declare the mode Control lacks.

⭐ **Scope, stated once because it is the thing most likely to be misread:** this doc is about
**inbound actionable requests only** — membership grants, redeemed contact invites, pairing
approvals. It is **not** about calendar reminders or any future alarm, which are locally scheduled
on every platform and are not affected by anything here. §11.

Companions: [`control-central-notifications.md`](control-central-notifications.md) (P3a is the
Android twin), [`calendar-app.md`](calendar-app.md) §4.3 (the pre-arm principle),
[`ios-proxy-architecture.md`](ios-proxy-architecture.md) §9.4 (no persistent process),
[`sync-relay-api.md`](sync-relay-api.md) §"OS-native push" (why APNs is already rejected at the
relay).

---

## 1. As built — the whole notification path on iOS, in one place

| Piece | Where | State |
|---|---|---|
| `NotificationPresenter` | `:app-notify` commonMain | `canPost` / `post` / `cancel` |
| `SchedulingNotificationPresenter` | same | adds `replaceScheduled(List<ScheduledAlert>)` |
| `NoopNotificationPresenter` | same | `canPost() = false`, everything else `Unit` |
| iOS binding | `control-shared-proxy` iosMain `PlatformProxyModule:175` | **`NoopNotificationPresenter`** |
| The one override | `CalendarLocalRuntime`, via the last-wins `appOverrides` slot | `IosNotificationPresenter` |
| The one producer | `DeliverDueRemindersImplementation` (`:calendar-service-proxy`) | calendar reminders |
| Android's elevation | `AndroidPendingRequestNotifier` | HIGH-importance channel, deep-links to the center |
| The platform-agnostic seam | `PendingRequestAlertHandle` + `PendingRequestAlerts` (diffs the feed) | ✅ exists, iOS has no impl |

⭐ **Note what the second-to-last row means: the shared half of the elevation is already built and
tested.** `PendingRequestAlerts` diffs `PendingRequestsRepository.requests` and calls
`onRequestArrived` on a handle. Android and desktop implement the handle. Writing an iOS one is a
small class. **That is not what makes NT-2 hard.**

---

## 2. The constraint that actually binds: pre-arm vs wake

`IosNotificationPresenter`'s own KDoc states the platform principle, and it is the crux:

> *"Android wakes; Apple platforms **pre-arm**"* — there is no reliable "wake me at 09:00" on iOS,
> so the answer is a different mechanism: hand the OS the next N alerts and let
> `UNCalendarNotificationTrigger` deliver them whether or not this process is alive.

⭐⭐ **This is why Calendar reminders work on iOS while a pending-request alert cannot.** A reminder
is *known in advance* — `ReminderPlanner` composes the forward set, `DeliverDueReminders` hands the
whole thing over, and the OS fires it days later with the app long dead. The app is not involved at
delivery time at all.

⚠⚠ **An inbound request is the opposite kind of event.** A membership grant, a redeemed contact
invite or a pairing approval arrives *when the other party acts*. There is no time to hand the OS in
advance, and no content to hand it. Pre-arming is not merely unimplemented here — it is
**inapplicable**.

That leaves waking. And:

⚠⚠ **`control-ios/Info.plist` declares no background modes and no
`BGTaskSchedulerPermittedIdentifiers` at all.** Of the iOS shells, only `drive-ios` and `fit-ios`
do. So Control-on-iOS is not "best-effort woken" — it is *never* woken. §9.4's "no persistent
background process" understates it for this app: there is not even a best-effort periodic task.

**Both mechanisms are therefore closed to this event class in this app.** No amount of DI work moves
that.

---

## 3. What Android does, and why it does not port

`AndroidPendingRequestNotifier` has **two** drivers, and both rest on Android affordances iOS lacks:

1. **A process-lifetime observer** of `PendingRequestsRepository.requests` — "near-real-time alerts
   *while Control's process is alive*". On Android, Control is the proxy: a bound service kept alive
   by the foreground-service task-liveness channel, by Drive being bound to it, by any running task.
   Its process is alive a great deal of the time.
2. **`notifyCurrent`, a one-shot from the `DrainSync` post-drain hook** — "the only thing that wakes
   a *closed* process on a schedule (~hourly, OS-bounded)".

⚠ On iOS neither exists for Control. There is no bound-service equivalent (every route-1 app hosts
its *own* proxy graph in-process, so nothing keeps Control's process alive on another app's
account), and there is no drain hook because there is no scheduled wake.

⭐ Worth stealing regardless of the outcome: Android's copy is **generic and PII-free** — request
*kind* only, never the grantor fingerprint or the peer's quarantined claimed name. Whatever iOS ever
posts inherits that rule; the surface is a lock screen.

---

## 4. So the real question is narrower than "who owns posting"

With pre-arm inapplicable and Control unwakeable, an OS-level alert for an inbound request on iOS
requires **some other process to be running when the request arrives**. That is the whole option
space, and it is small:

| # | Who posts | Requires | Verdict |
|---|---|---|---|
| **A** | Nobody. In-app tier only. | nothing | ~~Recommended~~ — superseded, §5.5 |
| **B** | Control, while its own UI is open | an iOS `PendingRequestAlertHandle` | Near-worthless *alone*; §5.2 |
| **B′** | **Control, from its own `BGAppRefresh` wake** | ⭐ a background mode on `control-ios` + the same handle | **Recommended (D1-rev), §5.5** |
| **C** | Drive, from *its* `BGAppRefresh` wake | cross-app ownership inversion | Rejected; §5.3 |
| **D** | The device, woken by a silent push | APNs infrastructure + a threat-model amendment | Out of scope; §5.4 |

---

## 5. The options

### 5.1 A — decline: the in-app tier is the whole surface on iOS

Control-on-iOS shows the center, the bell, the badge and the pinned cards whenever the app is open,
and posts nothing to the OS. NT-1 as shipped.

⭐ **This is more defensible than it sounds, because of what the requests are.** All three inbound
kinds — a membership grant, a redeemed contact invite, a pairing approval — are things the user is
*already expecting*, usually within minutes, because they or their counterparty just initiated the
exchange in person or over a trusted channel. None is a surprise arriving at 3am. The cost of "you
see it next time you open Control" is far lower here than for, say, a message.

⚠ It is genuinely worse in one case: a grant that arrives days later, from a counterparty acting on
their own schedule. The user sees it whenever they next open Control. That is the real cost of A and
it should be stated rather than argued away.

### 5.2 B — Control posts while its own UI is open

An iOS `PendingRequestAlertHandle` over `IosNotificationPresenter`, driven by the same
process-lifetime observer Android uses.

⚠⚠ **This posts a system notification for a request the user can already see.** Control's process on
iOS is alive essentially only when Control is foregrounded — so the alert fires while the user is
looking at the notification center that already lists it. It converts a real capability on Android
into theatre on iOS. **Not recommended**, and worth writing down because it is the option that looks
like "just implement the handle".

### 5.3 C — Drive posts, from its BGAppRefresh wake

Drive already declares `BGTaskSchedulerPermittedIdentifiers` and already drains sync in a ~30 s
budget (P6a). It could read `PendingRequestsRepository` off the shared replica and post.

⚠⚠ **This inverts ownership, and the inversion is not cosmetic.** The notification would come from
*Drive*, so tapping it opens Drive; the deep-link into Control's notification center becomes a
cross-app URL hop, which is a different consent and different failure modes. A user who has Drive
but has not opened Control gets notified about Control's business by a file manager.

⚠ It also spends Drive's background budget — a scarce, OS-rationed resource that P6a explicitly
sized for sync flushing — on another app's UI concern, and couples the two apps' release cadences.

⚠ And it does not even deliver timeliness: `BGAppRefresh` is best-effort and OS-scheduled, so the
alert arrives at an arbitrary later point, not on arrival. It buys a *worse* version of what A
already gives (you find out when something happens to run), at the cost of a cross-app dependency.

**Rejected.** If a future design wants one iOS process to own realm-wide background duties, that is
a much larger decision than NT-2 and should be taken as one.

### 5.4 D — silent push (APNs)

The honest "native wake on event" answer, and the only one that would make an inbound alert timely.

⚠⚠ **Already rejected one level down.** `sync-relay-api.md` records APNs as *"best UX; worst privacy
footprint"* — it requires the relay to register tokens with Apple and tells Apple *"user X has new
sync data at $time"*, an **A6 timing-metadata leak** plus a new AD5-class trust boundary. It is out
of scope for the relay protocol.

⚠ It is not forbidden forever — the same passage notes an adjunct that wakes the device, which then
uses the ordinary `/sync/changes`, carries no block data. But it is a **relay + threat-model +
infrastructure** decision, not an iOS-client one. NT-2 must not smuggle it in.

### 5.5 B′ — give Control its own background wake (the v0.1 error)

⭐⭐ **v0.1 dismissed this by comparing it to the wrong thing.** §5.3 rejected a background wake with
*"it does not even deliver timeliness: `BGAppRefresh` is best-effort and OS-scheduled, so the alert
arrives at an arbitrary later point, not on arrival."* Every word is true and the conclusion does
not follow, because **Android's closed-process path is also best-effort**:

> `AndroidPendingRequestNotifier.notifyCurrent` — *"a one-shot pass invoked from the `DrainSync`
> post-drain hook, the only thing that wakes a closed process on a schedule (**~hourly,
> OS-bounded**)"*.

So on Android a request that arrives while Control is closed is *not* announced on arrival either —
it waits for the next drain. v0.1 held iOS to a standard Android does not meet, and concluded iOS
should therefore do nothing. That is the wrong trade: it turned "iOS cannot be *as good as*
on-arrival" into "iOS gets *nothing*", when the achievable behaviour is roughly what the other
platform already ships.

**What B′ is.** Declare a `BGTaskSchedulerPermittedIdentifiers` entry for `control-ios` (Drive and
Fit already have one), and post from an iOS `PendingRequestAlertHandle` on that wake — plus the
live observer of §5.2, which stops being theatre once it is the *foreground half* of a pair rather
than the whole story.

⚠ **Honest about the difference that remains.** iOS `BGAppRefresh` is stingier and less predictable
than Android's `DrainSync` hook: the OS decides, it learns from usage, and a rarely-opened app may
get very few opportunities. So this is *approximate* Android parity for the closed case, not equal.
It is still the difference between "eventually" and "never".

⚠ It costs a background mode on an app that has none — meaning a real (if small) battery and
scheduling footprint, and one more thing for App Review to see. Recorded so the cost is not
discovered later as a surprise.

⚠⚠ **What B′ is not.** It is not on-arrival delivery, and it must not be sold as such. On-arrival
needs D (push), and macOS — where the resident daemon *does* announce on arrival — remains the
best-served platform. §11 records that divergence rather than hiding it.

---

---

## 6. Decisions proposed

- **D1-rev — Give Control-on-iOS a background wake and post from it (B′, §5.5).** ⚠⚠ **Reverses
  v0.1's D1** ("decline"). The reversal is not new information about iOS; it is measuring against
  Android's actual `~hourly` closed-process behaviour instead of against on-arrival delivery. The
  same event class is announced on Android and on macOS; iOS being silent is an inconsistency we
  chose, not one the platform imposed.
- ~~**D2 — Say so in the app, once.**~~ ⚠⚠ **WITHDRAWN, and the proposed string was wrong.** It read
  *"iOS cannot notify you about them in the background"* — **false**. iOS notifies in the background
  routinely; `calendar-ios` declares no background mode and still delivers reminders while dead
  (§11). The accurate statement was never about iOS's capability but about our configuration, and
  under D1-rev there is nothing to apologise for anyway. ⚠ If any user-facing line survives, it must
  not attribute our choice to the platform — that is both untrue and the kind of copy a user can
  catch us in.
- **D3 — Keep `NoopNotificationPresenter` bound in Control.** ⚠ **Still true under D1-rev, and for
  the original reason**: the duplicate-reminder hazard is untouched by any of this. Control's
  pending-request alerts do **not** go through `NotificationPresenter` — that seam belongs to
  Calendar's reminders. They go through `PendingRequestAlertHandle`, the platform-agnostic seam
  Android and desktop already implement, which is a separate path with no duplication risk. ⚠⚠ Do
  not "simplify" by routing them through the presenter: that reintroduces exactly the double-post
  the noop binding exists to prevent.
- **D4 — The pre-arm/wake distinction is the platform rule, not a Calendar detail.** Any future
  Tenshu notification on Apple platforms must say which of the two it uses. If it is neither, it
  cannot be delivered — and that is a design answer, not an implementation gap.
- **D5 — If a request class ever becomes genuinely time-critical, the answer is D (push), not C.**
  Recorded so the cheap-looking cross-app hack is not re-proposed as the pragmatic middle. ⚠ B′ does
  not close D — it closes the gap between "never" and "eventually", not between "eventually" and
  "now". §12 records the push question as the separate design it is.

---

## 7. What would reopen this

Each of these falsifies a premise above, and only these do:

- ✅ **This one fired, and it is why v0.2 exists** — though not the way it was written. The premise
  was "Control gains a background mode *for some other reason*"; what actually changed is that the
  reason was found to be sufficient **on its own** once measured against Android rather than against
  on-arrival delivery (§5.5). ⚠ Note the shape of the error: the reopening condition was stated as
  an external event, when it was really a comparison the doc had got wrong.
- **APNs lands for sync** (`sync-relay-api.md`'s adjunct, with its threat-model amendment). Then the
  wake exists and Control can post on arrival. This is the only path to *timely*.
- **One iOS process becomes the realm-wide background owner** across first-party apps. Then C stops
  being an inversion because there is a legitimate owner. ⚠ That decision is much larger than
  notifications and belongs with `ios-first-party-route-1-migration.md`.
- **A new inbound class arrives that the user is not already expecting.** §5.1's argument rests on
  all three current kinds being solicited. A genuinely unsolicited, time-critical inbound request
  would weaken it.

---

## 8. Phases

| Phase | Work | Size |
|---|---|---|
| **NT-2a** | ✅ Done — this doc + the pointers. ⚠ Its *conclusion* is superseded by v0.2; the record stands | **XS** |
| **NT-2b** | ✅ **SHIPPED** — as built in §13. The background mode + the iOS `PendingRequestAlertHandle` + both drivers |
| **NT-2c** | ✅ **Folded into NT-2b** — without it the feature is inert, so shipping them apart would have meant shipping something that could not work. Asked on first *arrival*, never at launch (§13) | — |
| **NT-3** | ⚠ **Now moot as scoped.** It was *"notification actions, gated on NT-2"* — with no notification posted there is nothing to attach an action to. Its content (a `UNNotificationCategory` + a `UNUserNotificationCenterDelegate` calling back into Kotlin) survives as a prerequisite of any future push design, and `IosNotificationPresenter` already records dropping `AppAlert.actions` for the same reason. Close it against this doc rather than leaving it open as buildable work. | — |

⚠⚠ **NT-2b still must not bind `IosNotificationPresenter` in Control** (D3). The pending-request
path is `PendingRequestAlertHandle`, not the presenter — a separate seam that Android and desktop
already implement, with no duplicate-reminder risk. A PR that reaches for the presenter has misread
this doc.

---

## 9. Open questions

- **Q1.** Does D2's Settings line belong in Control only, or does every route-1 app that shows a
  notification-shaped surface owe the same sentence? Leaning Control-only: it is the one with an
  actionable inbound surface. ⚖️ Worth one look across the shells before writing the string.
- **Q2.** The badge on Control's Home bell is *in-app* and therefore accurate. Should the **app icon
  badge** (`UNUserNotificationCenter.setBadgeCount`) also show the unread count? It survives the app
  being closed, so it looked like the one "you have something waiting" signal iOS can give without a
  wake. ⚠ **Two things check against it, and the second is probably fatal.** The badge is its own
  authorization option (`UNAuthorizationOptions.badge`) — a permission prompt, not a free ride; the
  repo currently requests only `[.alert, .sound]` everywhere it asks. And it can only be *set* while
  the app runs, so it would be stale in exactly the case that matters: a request arriving while
  Control is closed. It would mark what was pending at last launch, which is a weaker claim than it
  looks like it is making. ⚠ Leaning no; confirm before building.
- **Q3.** `PendingRequestAlertHandle` now has Android and desktop implementations and no iOS one. Is
  the seam's KDoc honest about that being a *decision* rather than a gap? If not, it will read as
  unfinished work to the next person.

---

## 11. Scope check — the calendar/alarm class is **not** affected

⚠⚠ Written because it is the first worry this doc raises in a reader, and the answer is the
opposite of the intuition: **for a notification whose time is known in advance, iOS is the
strongest platform of the three, not the weakest.** Nothing in NT-2 touches this class.

There are exactly **two** notification producers in the codebase — `CalendarAlerts` (reminders) and
the pending-request path. Only the second is what NT-2 is about. Reminders are **locally scheduled**
everywhere:

| | Mechanism | Fires with the app closed? |
|---|---|---|
| **iOS** | pre-armed `UNCalendarNotificationTrigger` via `IosNotificationPresenter` | ✅ exactly — the OS holds it, process dead or alive |
| **Android** | `DeliverDueReminders` carries the `Punctual` trait ⇒ `PunctualAlarmScheduler` → `AlarmManager` | ✅ exact **when `SCHEDULE_EXACT_ALARM` is granted**, else `setAndAllowWhileIdle` — inexact but Doze-exempt |
| **Desktop** | `PendingAlertSchedule` (a `SchedulingNotificationPresenter`) + the resident daemon | ✅ |

⭐ **Push would add nothing here, on any platform.** A reminder or an alarm has no inbound network
event to learn about — the time is known when the event is written, so the OS is handed the alert in
advance. APNs is a *wake* mechanism, and this class does not need waking. Any future clock-style
feature inherits the same property and the same three mechanisms.

⚠ The one real caveat is **Android, not iOS**: without `SCHEDULE_EXACT_ALARM` the fallback drifts by
minutes. `PunctualAlarmScheduler`'s KDoc explains why the permission is requested narrowly
(`USE_EXACT_ALARM` is a Play-policy risk for an app whose core function is not alarms), and that
trade is that doc's to revisit — not this one's.

---

## 12. The push question is real, and it is not this doc's

D (§5.4) keeps coming back because it is the only mechanism that delivers **on arrival**, and macOS
already does. Two framings worth separating before anyone designs it:

⚠ **The observer is Apple, not the relay.** The relay *sends* the push, so it already knows
everything a push would reveal — it stores the blocks. What changes is that **Apple** learns the
timing. Arguments about "not telling the relay too much" are aimed at the wrong party.

⚠⚠ **"Push on every SyncBlock" is not obviously the private option.** It sounds like camouflage —
one undifferentiated wake among many rather than a wake that means "someone shared with you". But it
hands Apple the device's *entire* sync-activity timeline, which is far more total metadata than rare
targeted pushes, and activity timing is itself identifying. Which is worse depends on the threat
model, and A6 is where that is written down.

⭐ The version of that instinct that does hold up is **coalescing**: at most one content-free wake
per window, collapsed by `apns-collapse-id`, so Apple learns *"activity in this window"* rather than
an event-by-event trace. Resolution becomes a tunable rather than a side effect.

⚠ Either way this is a **relay + threat-model** decision (HR #2 requires the amendment in the same
PR as the code) and it belongs with [`sync-relay-api.md`](sync-relay-api.md), which already rejected
APNs once, and the A6 entry in the threat model — **not** in a Control-on-iOS phase. B′ is
deliberately the option that needs none of it.

---

## 13. As built (NT-2b, 2026-08-13)

Four pieces, three of which are the shape Android and macOS already use:

| Piece | Where |
|---|---|
| `BGTaskSchedulerPermittedIdentifiers: [app.tenshu.control.refresh]` + `UIBackgroundModes: [fetch]` | `control-ios/project.yml` |
| `ControlBackgroundRefresh` — register at launch, arm on background, drain on wake | `control-ios/Sources/` |
| `IosPendingRequestNotifier : PendingRequestAlertHandle` | `:tenshu-sdk-ios` iosMain |
| `ControlSurface.startRequestAlerts()` / `runPendingRequestRefresh()` | `ControlSurface` |

⚠⚠ **The keys go in `project.yml`, not `Info.plist`.** `control-ios/Info.plist` is **generated by
xcodegen and gitignored** — an edit there survives until the next `xcodegen generate` and then
silently disappears, taking the feature with it (`BGTaskScheduler.register` would then crash on an
identifier the plist no longer permits). Caught before committing only because the edit produced no
git diff.

⭐ **NT-2c was folded in rather than deferred.** Control requested notification authorization
nowhere, so shipping the poster without the prompt would have shipped something that cannot work —
the exact "unit-green code that could not have run" shape this repo keeps paying for. The prompt
fires on the first *arrival*, never at launch: a denial is sticky (iOS does not ask twice), so a
prompt shown before the user has any context permanently disables the feature.

### 13.1 Two bugs the verification caught that a compile could not

⚠⚠ **`onChange(of: scenePhase)` does not fire for the initial value.** The live observer was
started only from the scenePhase arm, and a cold launch is *already* `.active` when the view
appears — so on the path that matters most (open the app, look at it) the observer never started.
It would have worked only after backgrounding and returning. Fixed by also starting it from
`.task`, which `startRequestAlerts`'s idempotence makes safe.

⚠⚠ **`UNUserNotificationCenter.currentNotificationCenter()` throws with no app bundle.** It was a
default constructor argument, so merely *constructing* `IosPendingRequestNotifier` killed the
Kotlin/Native test process with `NSInternalInconsistencyException: bundleProxyForCurrentProcess is
nil` before any assertion ran. It is a lazy provider now — construction is free and the OS is
touched only when something is posted. ⭐ The same shape as the Fit "clock read once, in a
constructor" defect: work done at construction time that the constructor's callers cannot see.

### 13.2 What is verified, and what is not

**Verified:** Kotlin + Swift compile; the app launches with the identifier registered (a *wrong*
identifier is a hard crash at `register`, so launching at all is the meaningful signal); the built
`Info.plist` carries both keys; the authorization prompt fires from the live observer, rendered on
the simulator via a new `-requestAlerts` DEBUG route; `IosPendingRequestNotifierTest` pins the
PII rule.

⚠ **Not verified, and it needs a person:** that a banner actually renders. That requires *granted*
authorization, and the simulator has no API to grant notification permission — `simctl privacy`
does not cover it, and automating the tap needs assistive access. Everything up to the prompt is
exercised; the tap and what follows it are the on-device pass.

⚠⚠ **The OS wake itself is never exercised on a simulator** — it does not fire `BGAppRefresh`
spontaneously. Drive's P6a comment says the same about its own wake, and it is right: this belongs
to the device pass, alongside P7.

### 13.3 The PII rule is now a test, not a comment

`IosPendingRequestNotifierTest` asserts the notification title contains **none** of a request's
carried detail — the invite's `claimedName`, `claimedFingerprint`, `claimedSas`, the grant's
`grantorFingerprint` — and that the two kinds are still distinguishable from each other.

⚠ Why it deserves a test rather than the comment Android and macOS carry: a lock screen renders
*before* authentication, and a redeemed invite's `claimedName` is **quarantined until the SAS is
confirmed**. Putting it in a banner would undo the ceremony. That rule reads like an aesthetic
preference ("keep the copy short") right up until someone helpfully personalises it.
