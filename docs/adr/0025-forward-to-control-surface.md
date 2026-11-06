# ADR 0025 — Every first-party app offers one route back to Control, on every platform

- **Status:** Accepted. Vocabulary + detection shipped 2026-08-25 (`UnreachableReason.NotInstalled`,
  the tri-state presence probe, the shared banner posture); **all nine apps** carry it; Android device pass
  2026-08-26; store-link mechanism + per-copy channel detection 2026-08-26 (§Decision 6/7) — no link
  is configured anywhere yet, by design. The Compose full-screen wall (`AppControlUnavailableWall`)
  is built and used for `NotInstalled` in all nine Compose apps. §Decision 8 (2026-10-02):
  `NotSetUp` — Control installed, no realm — takes the same wall, and holds the apps' unattended
  writes (§8.6); while the realm question is still unanswered (`Checking`) the roots show a
  non-interactive checking state, never the shell. A locked realm is never walled (maintainer, 2026-10-03).
- **Date:** 2026-08-25
- **Owner:** Benedikt
- **Relates to:** [`core-schema-skew-surface.md`](../proposals/core-schema-skew-surface.md) (SK-1…SK-4,
  which decided the *other* three reasons),
  [`core-client-resubscription.md`](../proposals/core-client-resubscription.md) R4 (the transport signal),
  [`fit-app.md`](../proposals/fit-app.md) §9.14 (the banner this generalises),
  [`ios-drive-parity.md`](../proposals/ios-drive-parity.md) §11.6.
- **Supersedes:** [ADR 0022](0022-ios-forward-to-control-surface.md) — which remains the record of
  *why*, and of the iOS mechanics that are unchanged.

## Context

[ADR 0022](0022-ios-forward-to-control-surface.md) settled this for iOS on 2026-08-07: one shared
screen, two postures (a wall at a dead end, a banner above a browsable surface), one destination.
It was written because five iOS apps had each handled Control's absence differently and four of them
offered no way back to a working state — *"an app telling the user to do something with no
affordance to do it"*.

**The same defect was live on Android and desktop the whole time, and nothing in 0022 could see
it.** The Compose side had converged in a different direction and stopped one step short:

| | iOS (ADR 0022) | Android / desktop |
|---|---|---|
| Engine | per-app `isProvisioned()` | `ControlReachability` (`:app-reachability`), shared by nine apps |
| Vocabulary | — | `UnreachableReason`: `Transport` / `Skew` / `Fenced` (SK-1…SK-3) |
| Control absent | its own screen, with a route out | folded into `Transport` |

So on Android, uninstalling Control produced *"Can't reach Tenshu Control — make sure Control is
installed and running"* — and on Fit's shell, *"open Control and unlock it, then come back"*: an
instruction naming an app that is not on the device. Underneath, `BinderTaskTransport.bind()`
**discarded `bindService`'s boolean return** — the framework saying, at exactly the right moment,
"there is no such service here" — so the one cause the user could act on never reached the shell at
all. (It also leaked a `ServiceConnection` record per retry; a `false` return still registers one.)

Two things make this an ADR rather than six patches. First, the divergence is not between platforms
but between *reasons*, and only lining the reasons up shows that one of the four had no words.
Second, four app shells were each about to grow their own copy of the same `when` — the exact
copy-paste 0022 caught on iOS, arriving here.

## Decision

**1. One destination, four reasons — and the posture is chosen by the reason, never by the
platform.**

| Reason | Recovers by | Surface |
|---|---|---|
| `NotInstalled` | installing Control | words that say so; the store route when there is one (§6) |
| `Transport` | itself, *or* by opening Control | today's banner + **Open Control**, where a route exists |
| `Skew` | updating a named binary | SK-1's wording, unchanged; **Update** where a page exists (below) |
| `Fenced` | updating a named binary | SK-3's wording, unchanged; **Update** where a page exists (below) |

*Amended 2026-11-04:* `Skew` and `Fenced` gain an **Update** action wherever the named binary has a
page for the copy installed here: tenshu.app for a downloaded APK, a configured Play listing, a
configured App Store id. It opens the page of the binary the verdict names, not the banner's app,
and follows the same tri-state rule as §6: no config, no button (`core-schema-skew-surface.md` §8.5).

⚠⚠ **Do not port iOS's wall/banner split as a platform rule.** It is tempting — but on Android and
desktop the apps have *no local store at all* (`ControlReachability`'s own KDoc: every read goes
over IPC), so an unreachable Control empties every screen, which by 0022 §Decision 4's own test is
*more* of a dead end than iOS route-1 with its cached data. What stops a wall there is not the data,
it is the **duration**: most Android/desktop outages are transient and self-recovering, and a
full-screen wall that slams down on a three-second blip is worse than any banner. `NotInstalled` is
the one that is permanent until the user acts, which is why it is the one that earns the stronger
surface.

**1b. `NotInstalled` takes the whole window, not a strip at the top of one.**
*(Amendment, 2026-08-26, after a device pass.)*

§Decision 1 argued that `NotInstalled` "is the one that is permanent until the user acts, which is
why it is the one that earns the stronger surface" — and then shipped a **banner** for it in all six
apps. The reasoning was right and the code did not follow it.

What that cost showed up the moment someone looked at a phone. With Control uninstalled, Timeline
rendered its banner above *"Your timeline starts here — Import from Google Takeout"*: an
empty-library claim that is false, over a button that cannot work, on a device with no realm at all.
Photos rendered *"No photos yet. Photos backed up from your devices appear here."* — a library that
looks deleted. ⚠⚠ **The rest of the UI was not a distraction from the banner; it was contradicting
it.** On a platform where every read goes over IPC to Control, an absent Control leaves nothing
truthful to render, so there is nothing for a banner to sit above.

So `NotInstalled` now renders `AppControlUnavailableWall` (`:ui-core`) in place of the entire shell —
no nav, no header, no empty state, no storage banner. It is the Compose twin of ADR 0022's
`ConnectToControlView`, and the platform split that ADR left behind is closed: **both platforms wall
at a dead end and band above a browsable one.** What still differs is only which reasons are dead
ends, and that was never a platform question.

⚠ Every other reason keeps the banner, unchanged. A transport outage is transient and
self-recovering, and a full-screen wall that slams down on a three-second blip is worse than any
banner. The rule remains the reason, never the platform.

⚠ The wall applies `WindowInsets.safeDrawing` **itself** rather than trusting a caller — a surface
that replaces the window has no caller chrome to inherit inset handling from.

**1c. One problem, one bar — and the shell owns the inset.**
*(Same amendment; the two defects that made the banner unreadable.)*

- ⚠⚠ **The storage banner is suppressed while Control is unreachable**, reversing the rule four
  shells carried in a comment ("*'Control is gone' and 'the realm cannot sync' are different facts
  and a user can be owed both*"). Both facts are real, but the second is **not actionable while the
  first is true** — its *Manage storage* button opens a Control that is not answering — and two
  stacked bars pushed the app's own header off the top of the screen.
- ⚠⚠ **A banner belongs in a `Scaffold`'s `topBar` slot wherever there is one.** `AdaptiveNavScaffold`
  passes `contentWindowInsets = 0`, so a banner placed at the top of its *content* draws under the
  status bar — observed with the clock and battery icons sitting on top of the text. Where a shell
  has no topBar slot the shell applies the inset itself, which requires it to know whether **any**
  banner will render: hence `rememberRealmStorageState()` is hoisted out of the storage host in
  every app (Contacts already had it, which is why Contacts was the one app that looked right).

⚠ This withdraws the per-app freedom §Decision 2 left in place for *placement*. The words stay with
the app; where the surface sits, and which surface it is, no longer do. Six shells had drifted three
ways — banner above the header, banner below it, and one app rendering no Control surface at all —
and each looked deliberate in isolation, which is the same way ADR 0022's original divergence hid.

**2. `UnreachableReason` is the single vocabulary, and adding to it must break every consumer.**
The `when` over it is exhaustive with no `else` in all four shells and in `waitingWillFix`. That is
load-bearing: this ADR's own slice was routed to every site that needed a decision by the compiler,
including one — Fit's *"Still trying…"* empty state — that would otherwise have gone on promising
that waiting would fix an app that is not installed.

⚠ The **words** stay with the app (each has to name what *it* loses; 0022 §Decision 2), but the
**posture** — which reasons get a glyph, which get a button — is decided once, in `:ui-core`'s
`controlBannerIcon` / `rememberControlBannerAction`.

**3. "Installed" and "launchable" are two questions, and the answer to the second is not evidence
about the first.** `rememberControlServicesLauncher()` returning `null` is tempting as the
not-installed signal and would be wrong: `ControlLauncher.desktop.kt` returns `null`
*unconditionally* by design, so a user with a running Control would be told to install Control. The
presence probe is separate, and deliberately **not** `getLaunchIntentForPackage`.

**4. Absence must be *known*. A probe that cannot tell says so, and the third answer is a real
one.** `TaskTransport.controlInstalled(): Boolean?` — `true` / `false` / `null` for *cannot tell*,
defaulting to `null` so every transport and test double compiles untouched and every in-process
runtime answers honestly. `null` leaves the outage worded exactly as it is today.

⚠⚠ Two-valued would force each platform's ignorance into either "installed" or "missing", and both
are wrong in a way the user sees. Reading silence as absence is how you invite someone to install an
app they already have — the mirror image of the defect being closed here.

Per platform:

- **Android** — the package manager, and it needs no permission beyond the `<queries>` entry for
  `app.tenshu.control.android` that every first-party app already declares. ⚠ An app that forgets
  the entry is told "not installed" about a Control that is right there, so the entry ships with the
  app.
- **Desktop** — ⚠ **no permission and no entitlement are required**: both JVM host apps are
  deliberately **not sandboxed** (`signDesktopApp` signs them with no `app-sandbox` key; the
  sandboxed peer is Drive's File Provider `.appex`, which is not the process drawing a banner). So
  the check is an ordinary local query. macOS answers from `/Applications/TenshuControl.app` and
  then LaunchServices via `mdfind` on `app.tenshu.control`; Windows and Linux answer `null`, because
  Control has no installed-artifact convention there yet and inventing one would be a guess wearing
  an API.
  ⚠⚠ **The macOS negative is self-testing.** Spotlight can be off or a volume unindexed, and
  `mdfind` then reports nothing for a Control sitting in `/Applications`. So a negative is only
  trusted after a canary bundle present on every Mac answers; a silent canary means the instrument
  is broken, which is `null`.
- **iOS** — unchanged: route-1 apps learn Control's absence from ADR 0022's `openURL` **attempt**,
  which is authoritative on a platform that will not answer the query. `canOpenURL` cannot
  distinguish "not installed" from "`LSApplicationQueriesSchemes` entry missing", so it is used for
  `true` and never for `false`.

⚠ The probe is **polled on the reachability probe's cadence, not cached**: the user leaves, installs
Control and comes back, and a cached answer would argue with a device that has already been fixed.
It is consulted only while the app is unreachable, so a healthy device pays nothing.

**5. Web is out of scope, and that is a decision rather than an omission.** apex *is* Control in the
browser (`web/apex/src/control/ControlShell`). There is nothing to install, and the equivalent state
— "this browser is not a paired device" — is apex's own and already handled. "Every platform" here
means three.

**6. The store route is configured or absent — never derived, never a placeholder — and a build
offers only the store it actually came from.**

This generalises 0022 §3: an unset id renders an honest *"not in the store yet"* rather than a
button onto a broken page. The mechanism mirrors `:control-shared-proxy`'s relay baking, so there is
one to learn: `-P<key>` → `$ENV` → `local.properties` → **empty**, generated into
`ControlStoreConfig` by `:ui-core`'s `generateControlStoreConfig`. Keys: `tenshu.control.playUrl`,
`tenshu.control.macAppStoreUrl`.

⚠ **iOS is deliberately not in that task.** It already resolves this through
`TENSHU_CONTROL_APP_STORE_ID` → Info.plist → `Control.appStoreURL`, and the Kotlin side reads *the
same Info.plist key*. A generated Kotlin constant beside it would let one build configure one and
not the other, and the disagreement would only ever appear in front of a user.

**⚠⚠ A configured link is necessary, not sufficient — and this is stronger than the build flag this
ADR first proposed.** The flag could not have worked: an artifact reaches users by more than one
route, so the channel is a property of the installed copy, and both platforms record it.

⚠ *Corrected 2026-10-31.* This paragraph first said "the *same* Android APK ships through Play and
F-Droid". It does not, and for Control never can: a Play build sells through Play Billing and a build
for outside Play through Stripe (`commerce-store-link-out.md` D3), and Play re-signs with its own key
per app while every other channel is signed with our side-channel key
(`docs/how-to/android-release.md` "Which key signs what"). The conclusion stands for a different
reason: a Play-signed APK copied off a phone, or fetched by Aurora, is a Play artifact installed some
other way, and only the package manager's installer record says so.

| | Offers a store when | Otherwise |
|---|---|---|
| Android | the package manager attributes this install to Play (`com.android.vending` / `com.google.android.feedback`) | another store client (F-Droid, Aurora, Obtainium, …) → `SameChannelAsThisApp`; a debuggable build → as before; **any other installer → the website's download page** (amendment below) |
| macOS | the bundle carries `Contents/_MASReceipt/receipt` | `SameChannelAsThisApp` — DMG, Homebrew, or a dev run |
| iOS | the id is configured | `NotPublishedYet` — one channel, so nothing to detect |
| Windows / Linux | never | `SameChannelAsThisApp` — an installer or a package manager, neither linkable |

⚠ The macOS receipt is checked for **presence, not validity**. A real receipt check is a
cryptographic verification, and this is not that, nor should it be: the question is *which sentence
do I show*, not *is this user entitled to anything*. The worst case for a spoofed receipt is a
button to a public store page. (The bundle is located from `jpackage.app-path`, which the packaged
launcher sets — verified against the built binary, not assumed.)

**Amendment (2026-10-31): a downloaded Android copy gets the website's download page.** An APK from
`tenshu.app` has no store installer, so it was told *"it isn't in the store yet"* (or, once a Play link
is configured, *"install it the same way you installed Drive"*), with no link either way. It now gets
`Store("https://tenshu.app/apps/control/download/android")`: the `Store` case's words (*"Control
isn't installed"* + **Get Control**) name no store, so no copy changed. The URL is fixed, not
configured: the empty default above exists because a store id is unknown until the listing exists,
and our own page is not. ⚠ A debuggable build keeps the earlier answer — a developer's `adb install`
has no installer either, and the website's Control is signed by a different key. ⚠ The page must be
live before the first downloadable APK ships. The decision is `androidControlInstallRoute`
(`:ui-core` commonMain, tested in `AndroidControlInstallRouteTest`).

**7. "No link" is two different sentences, and picking the wrong one makes a promise the build
cannot keep.** `ControlInstallRoute` therefore has three cases, not a nullable URL:

- `Store(url)` → *"…Control isn't installed"* + **Get Control**.
- `NotPublishedYet` → *"…and it isn't in the store yet"*. Temporary, and it promises a link is
  coming.
- `SameChannelAsThisApp` → *"Install it the same way you installed <App>"*. Permanent for this copy,
  and — the point — **still correct after Control ships to a store**: sending a Homebrew or F-Droid
  user to a store leaves them with two copies from two channels and no idea which one updates.

⚠⚠ Collapsing these two was tried and is wrong in a way that would only show up on a real device: an
app installed **from Play** whose Control is **not yet on Play** would be told *"install Control the
way you installed this app"* — advice that cannot be followed, because that way does not work yet.

**Consequence, stated plainly: no store build ships a "Get Control" button today**, because no store
link is configured anywhere — that is what an empty default means. (Since 2026-10-31 a *downloaded*
Android copy does get one, to the website: the amendment under §6.) The *mechanism* is in place, so
publishing Control to a store becomes a build-setting change rather than a code change. Until then
`NotInstalled` renders words alone, which is not a dead end in the 0022 sense: *"Tenshu Control isn't
installed on this device"* is a true statement about the user's device, not an instruction they
cannot follow.

**8. A fifth reason, `NotSetUp`: Control is installed and answering, and this device has no realm.**
*(Amendment, 2026-10-02 — Android and desktop only. The definition in 8.1 was confirmed by the
maintainer on 2026-10-03.)*

§Decision 1 listed four reasons, and every one of them is a way for Control to be *missing or
refusing*. The case it did not list is the one a first-time user meets first: Control is installed,
running, answering every read — and has never been set up, so there is no realm on the device. No
reason fires, because nothing is wrong with the channel: the proxy opens one object store whether or
not a realm exists, so every app's reachability probe succeeds and every read returns **empty**.
The result is exactly the defect §1b closed for `NotInstalled`, arriving through the front door:
Timeline's *"Your timeline starts here — Import from Google Takeout"*, Photos' *"No photos yet"*, an
empty Drive — empty-library claims that are false in the specific sense that there is no library,
over affordances that write into a store that belongs to no realm. ⚠ And Home renders its
**mock household** in this state (`if (synced.isEmpty()) mocked else synced`, the *"Still owed"*
item under Consequences), so the first thing a new user's Home shows them is a lamp that is on.

**8.1 What "not set up" means — (a); (b) decided against (maintainer, 2026-10-03).**

- **(a) — implemented:** *no realm has been created or joined on this device.* Exactly Control's own
  `LandingState.FirstTime`, read the way Control's router reads it: a **successful**
  `GetUnlockStatus` that is not locked, then a **successful** `GetSyncStatus` whose `ownerId` is
  absent.
- **(b) — decided against:** a realm that exists but is **locked** is **never walled**. Decided by
  the maintainer on 2026-10-03: locked is *temporary*, and unlocking is the existing locked
  surface's job — Drive's `SyncBannerState.Locked` banner, the transport wording elsewhere. A
  locked device *has* a library, and a wall would hide that it does. ⚠ So `realmPresence` answers
  `true` for a locked device, which also opens §8.6's write gate for it: the writes then take
  today's locked path (the sync observer's locked-window hold, replayed on unlock).
- ⚠ **Why the lock is read at all, when (b) is not taken.** `ownerId` alone almost answers (a): the
  persisted owner slot survives locking (`clearPlaintextPayloadSlots` clears the phrase and joiner
  payload, never `OWNER_ID`). *Almost*: a wrapper sealed before the owner slot was persisted reports
  no owner while locked, and an `ownerId`-only probe would wall that set-up user as "not set up".
  Control's router has the same exposure and closes it the same way — lock first — so the apps read
  the pair in the router's order (`realmPresence`, pinned by
  `aLockedDeviceIsNeverNotSetUpEvenWithNoOwnerSlot`).

**8.2 How the engine learns it — existing operations, not a new one.**
`ControlReachability` gains `controlHasRealm: (suspend () -> Boolean?)?`, wired on Android and
desktop to `syncService.getUnlockStatus()` → `isLocked` and `syncService.getSyncStatus()` →
`ownerId`, judged by `realmPresence`. `:sync-service` is already registered by seven of the nine
apps, and `ownerId` is the proxy's **resident** owner — the persisted membership marker
`TenshuLandingStateRepository` reads to route Control itself to onboarding, which cannot be
transiently blank behind a cold-start Argon2id autoload. So this adds **no operation, no wire type
and no parameter**: nothing is reshaped, and House Rule 9 is not engaged
(`SchemaShapeBaselineEnforcementTest` passes against the unchanged baseline).

- ⚠ **Onboarding cannot flicker the wall.** The resident owner only goes absent → present while a
  realm is created or joined: `initialize` and `initializeWithKeys` persist `OWNER_ID` before the
  keys are seated, and neither `discardSupersededRealm` nor the unlock-setup paths clear it — only
  *Forget this device* does. And once the engine reads `true` it stops re-reading until the next
  reconnect (pinned by `onceSetUpTheWallStaysDownUntilTheNextReconnect`).

- ⚠⚠ **Absence is known, never inferred — the §Decision 4 rule, unchanged.** `false` only from a
  successful read that carries no owner. A failed read, a throw, a blank-but-unparseable answer, an
  unregistered `:sync-service` — all `null`, which is *cannot tell*, which is **today's
  behaviour**. A probe that told a set-up user their device was not set up would be the mirror of
  telling them to install an app they have.
- ⚠ **Only on the reachable branch.** `NotSetUp` is reported only while `state == true`: it is a
  fact read *from* Control, so with Control unreachable the answer is stale and the transport
  reasons own the words. The answer is dropped on every outage and re-read on every recovery —
  *"a verdict that outlives its fix"* is the lesson `ControlReachability` already records once.
- **Cadence.** Read once each time the proxy becomes reachable; while the answer is `false` (or
  `null`), re-read on the probe interval so the wall lifts by itself after the user sets Control up
  and switches back; once `true`, not re-read until the next reconnect. ⚠ So a device that *leaves*
  its realm while an app stays open is not noticed until the next reconnect — accepted, see 8.5.
- ⚠ **Home and Mia register `:sync-service` to get this.** Neither did (§Consequences: *"Neither
  registers `syncService`"*), and an unregistered service fails at dispatch — so for them the probe
  would answer `null` forever and the wall would silently never fire. This is a **client schema-set**
  change, not a type reshape (no version moves; Calendar made the same addition), and its
  one cost is stated plainly: both apps now announce `:sync-service`, so a sync-service version skew
  between Home/Mia and Control now refuses their session as `Skew` — which is correct for an app that
  now calls it.
- iOS is untouched. Its route-1 runtimes pass nothing, so the case never fires there; the iOS
  equivalent is ADR 0022's own and is being decided separately.

**8.3 Why it earns the wall, not a banner.** By §Decision 1's own test — the **duration** —
`NotSetUp` is the second reason that is *permanent until the user acts*: nothing on the device will
create a realm by waiting. And by §1b's test — *is there anything truthful left to render?* — there
is not: with no realm every screen is an empty state making a claim about a library that does not
exist. So it renders `AppControlUnavailableWall` in place of the whole shell, exactly where
`NotInstalled` does, in all nine Compose apps. ⚠ `waitingWillFix(NotSetUp)` is `false`, for the
same reason.

⚠⚠ **The wall also covers the unknown.** While Control is reachable (or not yet known) and the
realm verdict is still PENDING, the reason is `UnreachableReason.Checking`, and every root renders
`AppControlCheckingGate` in place of its shell — blank for 300 ms, then *"Checking Tenshu Control…"*,
and past 20 s a retry wall rather than a spinner forever *(Codex P1 on `5f82133bc`; the iOS twin is
`ControlProvisioning.checking`)*. The shell's own writes are user acts the wall is relied on to
cover, so the shell must not be interactive while nobody knows whether there is a realm. A
remembered ABSENT keeps the wall through every recovery until a read sees a realm (the reason is
derived from the verdict, not from the last raw read, which an outage resets). While Control is
unreachable the transport reasons speak as before: every app's synced writes go over IPC to
Control, so nothing can land anywhere then — the only local write paths are the durable outboxes
(Fit's recorder, Timeline's capture), which are themselves gated on drain, and device-local
preferences that are not synced data. ⚠ Fit renders `Checking` and `NotSetUp` **above** its
onboarding gate: a device with no realm is exactly a device that has not onboarded, and onboarding
writes the owner's profile.

**8.4 The words and the button.**
- **Title:** *"Set up Tenshu Control"* — the title `NotInstalled` already uses, and here it is
  literally the instruction.
- **Body:** *"Tenshu Control is installed but isn't set up on this device yet. <App> keeps your
  <data> in Control — open it to start fresh or join your other devices, then come back."* ⚠ Never
  "install" (it is installed) and never "unlock" (that is (b), and there is nothing to unlock).
- **Button: Open Control** — `bannerActionKind(NotSetUp) = OPEN_CONTROL`, the `Transport` offer,
  because Control *is* installed and opening it is the whole recovery. ⚠ On desktop
  `rememberControlServicesLauncher()` is `null` by design (§Decision 3), so the wall renders words
  alone there — which is why the body says *"open it"* rather than relying on the button.
- **Icon:** a settings glyph — not `GetApp` (that says *install*) and not `CloudOff` (that says
  *wait*).

**8.5 Out of scope, deliberately.**
- **(b), the locked realm** — decided against; see 8.1.
- **iOS** (`ios-onboarding/`, `*-ios/`, ADR 0022) — a separate piece of work.
- **Web** — §Decision 5 holds: apex *is* Control in the browser.
- **Leaving a realm with an app open** — noticed on the next reconnect, not live. A push signal
  would need a new reactive read, i.e. exactly the operation 8.2 avoids.
- **Retiring Home's mock household** — still owed; the wall now covers its worst state (a brand-new
  device), not the realm-exists-but-empty one.
- **Drive's `SyncStatusBanner`.** The wall sits at Drive's `App.kt` root ahead of it, so no
  `SyncBannerState` is added. ⚠ Read from `SyncBanner.evaluate`, not observed on a device: a
  no-realm proxy is neither locked nor fenced and has no owner to hold storage, so it most likely
  fell through to `LocalOnly` (*"set up storage"*) — pointing a user with no realm at a purchase.

**8.6 The wall covers the shell; the realm verdict also holds the writers.** *(Added 2026-10-03,
from review of the implementation.)* Replacing the shell does not stop what an app does without
it. A no-realm Control **accepts** writes — the proxy opens its store either way — and the sync
observer, with no keys, only holds each save in memory (`pendingSaves`) to replay into whatever
realm is next enabled; a process restart instead strands it local, never emitted. So an app that
writes unattended on a device with no realm writes into a store that belongs to no realm, which
the shell-only wall never touched. Fit did exactly that: `HealthImportBootstrap` is
`createdAtStart`, and its launch pass drained the recorder outbox and imported from Health Connect.

- **`ControlReachability.realmVerdict`** — `PENDING` / `PRESENT` / `ABSENT` / `UNKNOWN`. ⚠⚠ Four
  states because "not asked yet" is not "cannot tell": the launch pass and the recovery drain both
  fire on the edge where Control becomes reachable, *before* the realm read answers, so `PENDING`
  is set on the way into reachable — ahead of `onRecovered`. `UNKNOWN` (no probe wired, a failed
  read, Control unreachable) is today's behaviour; only a *known* absence holds.
- ⚠⚠ **Held work is released only by a positive observation of a realm.** `ABSENT` is left by
  exactly one transition — a successful read answering `PRESENT` — never by an outage,
  `markUnreachable()`, a transport drop, a reconnect, or a read that cannot tell. *(Codex P1 on
  `35e5ff8ed`: the first cut reset `ABSENT` to `UNKNOWN` on any outage, which the release hook read
  as writable, so one failed operation on the wall drained the outboxes into the ownerless store.)*
  The verdict itself is the memory that tells "never observed" (a fresh process: `PENDING`, then
  `UNKNOWN` if the read cannot tell — today's behaviour) from "was `ABSENT`, now unknown" (keeps
  holding). ⚠⚠ The same holds for `PENDING`: an outage never moves a *holding* verdict, so the
  launch `PENDING` survives an organic `markUnreachable()` until a realm read actually answers
  *(Codex P1 on `f8d09e846`: it used to drop to `UNKNOWN` and open the gate before the first
  read had said anything)*. Only a writable verdict (`PRESENT`, `UNKNOWN`) goes to `UNKNOWN`
  across an outage. ⚠⚠ **And a realm read that fails is not an answer either** — a session not
  yet admitted, an IPC failure, a timeout: it never moves `PENDING` (or `ABSENT`). `PENDING`
  exits only on an answer, retried on a short backoff (250 ms, 500 ms, 1 s, 2 s, then the normal
  poll) so a launch just after a Control restart settles in about a second. *(Found by the Android
  smoke gate: right after a Control restart Fit's realm read landed ~120 ms before its schema
  announce was admitted, and the failure used to read as `UNKNOWN` and open the gate until the next
  poll.)* So the only standing `UNKNOWN` is an engine with no realm probe wired (iOS route-1,
  previews) or a writable device across an outage — a wired probe that never answers holds writes,
  which is the safe direction. ⚠ Fit (six sites) and Timeline (two) still call `markUnreachable()` on operation
  failures — the shape `ControlReachability.markUnreachable`'s KDoc already marks for retirement
  (Photos dropped it). Harmless to held work now; their removal is separate work.
- ⚠⚠ **A reconnect re-checks.** A remembered `PRESENT` becomes `RECHECKING` the moment Control
  goes away, not a writable `UNKNOWN`: the transport can reconnect
  before the probe, let alone the realm read, has said anything, and a Control reset or a device
  forgotten during the outage has no realm. `RECHECKING` holds the unattended writers but keeps the
  shell — a user who just had a library is not thrown onto a checking screen for a reconnect — and
  only a realm read moves it: `PRESENT` releases, `ABSENT` walls, a failed read keeps re-checking on
  the short backoff. ⚖ **Accepted residual:** a *user* act in that ~1 s re-check window can still
  reach a Control that lost its realm during the outage; holding the user too would mean a checking
  screen on every reconnect, which costs more than it saves.
- ⚠⚠ **The probe is required too.** Every engine takes a `RealmProbe` — `Wired { read }` or
  `None(reason)` — with no default and no bare `null`: an engine without a probe reads as writable,
  so an Android or desktop app that forgot to wire one would open its whole gate.
  The per-app factories, `FitProjectionRepository`, `TimelineProjectionRepository` and the
  repositories that take an engine (`TenshuCalendarRepository`, `TenshuContactsRepository`,
  `TenshuPhotosRepository`, `PlaceProjectionRepository`) all require it; iOS route-1, the web
  runtime and tests pass `None` with the reason at the call site, and `RealmProbeWiringTest` fails
  if any Android/desktop wiring module passes `None` or wires no `Wired` probe.
- ⚠⚠ **The first frame is the gate, not the shell.** `ControlReachability.reason` is a `StateFlow`
  whose value is computed synchronously from its sources, and the repository seams
  (`controlUnreachableReason()`) return a `StateFlow` too, so every root reads it with
  `collectAsState()` — never `collectAsState(initial = null)`, which rendered the interactive shell
  for one frame while the verdict was already PENDING. With a wired probe and
  nothing known yet the first composition is the checking gate. `RealmProbeWiringTest` scans the
  nine roots for an `initial` on the reason.
- **"Try again" asks again.** The checking gate's retry calls `ControlReachability.recheck()`
  (through `recheckControl()` on the repositories), which wakes the probe and cuts the realm-read
  loop's wait short, and only then re-arms its own timer. A realm read hung in flight is cancelled
  and a fresh one starts at once; the cancelled read is no answer, so a held verdict stays held. `AppControlCheckingGate.onRetry` is required.
- **Held work is never dropped, only not attempted.** Fit's recorder saves and Timeline's captured
  visits/moves stay in their durable outboxes; Fit's Health Connect pass simply has not run yet
  (Health Connect still holds the data).
- **Resumption** — `awaitRealmWritable()` for a pass that can simply wait (Fit's bootstrap waits
  before its debounce, so held foregrounds collapse into one pass);
  `onEachRealmWritableEdge(since) { … }` for work that must re-run after each release (Fit's and
  Timeline's outbox drains, unconditionally; Fit's bootstrap, only when an import was held since
  its last pass — `realmHeldStops`). Releases are a **counter bumped by the producer**, so a busy
  or late collector cannot lose one to `StateFlow` conflation.
- ⚠⚠ **The rule every gated pipeline implements** *(Codex P1 + P2 on `fde421540` and on
  `792da428e`)*:
  1. **The gate is observed for the whole run, not sampled at entry.** Every write re-checks it,
     and **every check asks the engine's single `holdsWrites` predicate (true for `PENDING` and
     `ABSENT`), never a hand-picked state** — after a reconnect the verdict is `PENDING` until the
     realm read answers, and an `ABSENT`-only check let a batch through in exactly that window.
     Fit's outbox drain per entry, Fit's Health Connect import per batch (`HealthImportWriter`'s
     `beforeEachWrite`, plus its supersede and run-record writes), Timeline's `DurableImportWriter`
     per entry. A gate that holds stops the run there; what was not written stays in its durable
     source (the outbox, or Health Connect) for the resumption.
  2. **Any pass that does not complete because the gate holds — at entry, mid-run, anywhere — is a
     held pass and is re-run on the next release, and a release is emitted for every transition from
     a holding verdict to a writable one, `PENDING → PRESENT` included.** Fit's import counts every
     held pass in `realmHeldStops` (an entry rejection exactly like a mid-run stop), which is also
     how a pass that never started is told from one that finished: a held pass moves the counter, a
     finished one does not, so a pass that merely *waited* and then ran is not run twice. ⚠ The
     re-run is requested from **both** sides — on every release and on every held stop, each
     request waiting for the gate — because a release can land between a refusal and its stop being
     counted, and a release-only trigger then sees nothing to re-run *(Codex P2 on `d0f9f1a53`)*. The outbox
     drains need no counter — they re-drain on every release. ⚠⚠ **Automatic passes only.** A
     *manual* import the gate refuses is never folded into a held automatic pass — its re-run would
     substitute the all-category recent import, or nothing at all with auto-import off — so it
     surfaces `ImportStatus.WaitingForControl(selection, window)` instead: visible, nothing
     written, not counted, and "Try again" replays exactly that request *(Codex P2 on
     `f8d09e846`)*. While such a request is waiting, an automatic pass — the launch bootstrap
     resuming on the same release — skips its import and leaves the status alone, so it cannot
     overwrite the request; the recorder-outbox drain still runs *(Codex P2 on `dcf7f9b1e`)*.
  3. **The gate lives at the write boundary, not at call sites** *(Codex P1s on `6ae61f405`:
     two rounds running found one more write that a per-call-site check had missed)*. Every
     synced write or delete a gated pipeline can reach goes through a writer that calls
     `ControlReachability.refuseIfRealmWritesHeld()` (or `gateOf(writesHeld)`) **before each op**
     and throws `RealmWritesHeldException`. The pipeline catches that once — an import pass is
     held and re-runs; an outbox entry is kept, because it is removed only after every write in
     it landed. ⚠⚠ **No gate parameter has a default** *(Codex P1 on `d2a13fe22`: a defaulted
     no-op `beforeEachDelete` let `AutoDetectBootstrap`'s launch-time supersession skip the gate)*.
     Every caller passes one, so the compiler lists them all; a path the wall already covers (a user
     act) or one with no probe (iOS route-1, the web runtime) passes `OPEN_REALM_GATE` / `{ false }`
     explicitly, with the reason beside it. `FitRepository.refuseIfRealmWritesHeld()` is likewise
     abstract, so every implementation says what it guards. Behind the boundary today:
     - **Fit** (`gatedHealthWriter()`, `gatedRunRecorder`, supersession's `beforeEachDelete`):
       `recordImportedHealth` (each batch and chunk), `recordWorkout` (each dense workout) — for the
       outbox drain and the automatic import alike; `registerHealthSource` and `recordImportRun`
       (the run recorder, on every exit); `deleteWorkout` (each superseded detection); the
       demographic seed's three preference writes — `BirthDate`, `Sex`, and the one-shot
       `DemographicsSeeded` marker, written **last** so a refusal never burns it
       (`OwnerProfile.seedDemographics`'s `beforeEachWrite`; Codex P1 on `bc7871fd9`).
     - **Timeline** (`DurableImportWriter`'s gate, handed to `TimelineImportWriter` and
       `PlaceLabelEnricher.enrich`): `recordPlace`, `recordWorkout`, `recordMovementTrace`,
       `recordMoment`, `recordTrip` (each op of each captured visit or move), and the enrichment's
       `recordPlace` (each label); the geocoding backfill's `recordPlace` at launch (the same
       enricher boundary); the rail-refinement sweep's `recordMovementTrace`, `recordSnappedRoute`
       and `recordMoment`, at launch and on capture (`RailRefinementSweep`'s `beforeEachWrite`).
       Wi-Fi recognition *learns* as it resolves (`recordPlaceNetworkFingerprint`, fire-and-forget),
       so while the gate holds the capture engine does not consult it at all — the dwell keeps its
       grid-snapped place — and does not schedule a rail sweep for a leg it only journalled.
     - Not behind it, deliberately: user acts the wall already covers — a file import the user
       picked, the auto-import switch, a manual workout — and the ungated callers of the same
       writers (`AutoDetectBootstrap`'s supersession, inert without its realm-stored toggle), which
       are not gated pipelines (§8.6 sweep). ⚠ The import's bulk-write *task* lease is not a synced data
       write and is not gated.
  4. **The release subscription is installed before any initial work.** Its marker is taken
     synchronously before the first drain or pass (Fit's repository `init`, Fit's bootstrap `init`,
     Timeline's app-start block), so a release that lands during the initial work still causes one
     more run afterwards. Releases during a run coalesce into one follow-up, and runs never overlap
     — Fit's drain holds its mutex and its bootstrap a pass lock, and Timeline's drains share the
     outbox's own lock across every writer instance.
- **The sweep (2026-10-04)** — every write a first-party app on Android or desktop makes **without
  the user doing anything**: `LaunchedEffect`s above the wall, view models built above it,
  repository and view-model `init` blocks, DI-eager singletons, `Application.onCreate`, desktop
  `main`, workers, receivers, widgets and services. *(Re-swept after Codex P1 on `802d5afdc` found
  Calendar's launch seed — the first finding outside Fit and Timeline.)* Every gated row waits for
  the same `ControlReachability` gate and resumes on its release.

  | App | Unattended write | Gated how |
  |---|---|---|
  | Fit | Bootstrap pass at launch / foreground: outbox drain; Health Connect import (batches, source registration, run record, supersession deletes, demographic seed) | gated writers (`gatedHealthWriter`, `gatedRunRecorder`, `beforeEachDelete`, `seedDemographics`), held pass re-run on release and on held stop |
  | Fit | Recorder save finishing in the background | journalled; gated drain on release |
  | Fit | `AutoDetectBootstrap` promotion + supersession (launch) | waits for the gate; promotions journal through the gated outbox, each supersession delete gated; a held pass re-runs on release |
  | Fit | `FitImportServiceLauncher` | no write (starts the FGS for an import) |
  | Timeline | Capture visits and moves (FGS) | `DurableImportWriter` gate; entry journalled until release |
  | Timeline | App-start outbox drain | gate + release collector |
  | Timeline | Wi-Fi learning while capturing | skipped while the gate holds |
  | Timeline | Rail-refinement sweep (launch, capture) | `RailRefinementSweep.beforeEachWrite`; re-runs next launch / trace |
  | Timeline | Geocoding backfill (launch, Android and desktop) | `PlaceLabelEnricher.backfill` gate; re-runs next launch / opt-in edge |
  | Timeline | Launch thumbnail-class declaration | gated declaration, retried after the next release |
  | Calendar | Default calendar + `DefaultCalendarId` seed (`App`'s `LaunchedEffect`, above the wall) | waits for the gate, each write gated (preference first), retried after a refusal |
  | Calendar | Widget refresh; reminder deep links; opened `.ics` | none: reads, or a user act behind the wall |
  | Drive | Synced Folders source registration + status mirror (every launch, Android; registration only on desktop) | `RealmGatedIngestRegistry`: each write waits for the gate, retried after a refusal |
  | Drive | Synced Folders uploads, `BackupWorker` | storage gate: `NO_STORAGE` with no realm (no entitlement row); ⚠ not re-examined past *Forget this device* |
  | Drive | Folder upload; new-folder notification action | none: user acts |
  | Photos | Launch thumbnail-class declaration | gated declaration, retried after the next release |
  | Contacts | — | nothing unattended writes |
  | Home | — | nothing unattended writes (hub status is read; the mock household is behind the wall) |
  | Maps | — | nothing unattended writes (region downloads are user-started) |
  | Mia | Launch schema announce | not an app write: Control's own registration, and a precondition for the realm read itself |
  | All apps | Schema announce / session preflight | same as Mia's |

  iOS is unchanged: its route-1 runtimes wire no realm probe, so the verdict is `UNKNOWN` there.

## Consequences

- The reason a user can act on now has words on Android and desktop, and `Transport` gains an
  **Open Control** button wherever a route exists — the case where the copy has said *"open Control
  and unlock it"* since §9.14 with nothing to tap.
- ⚠ **Drive-on-Compose is the outlier and stays one for now.** Drive on Android/desktop does not use
  `ControlReachability` at all — it has its own `FilesRepository` sync-status banner — so the app
  that got this right on iOS is the one Compose app this slice does not reach. Converging it is its
  own piece of work, not a line in this one.
- ⚠ Calendar and Photos build the engine without `transportConnected`, `schemaSkew` or
  `schemaFence`, so their banners are probe-only and neither renders a reason yet. They inherit the
  new case for free the day they do.
- ⚠ `:ui-core` now depends on `:app-reachability` (`api`, for the type in the signatures). The
  engine stays out of Compose; only its vocabulary crosses.
- ⚠ `bindService`'s return value is now checked, and a refusal unbinds. Watch for
  `ServiceConnectionLeaked` disappearing from long-running logs on devices without Control.
- **Device-verified on Android** (emulator, 2026-08-26), which is worth recording because two of
  the three states behave differently than a reading of the code suggests:
  - Control **uninstalled** → Maps renders *"Tenshu Control isn't installed on this device…"*. The
    case that had no words now has them.
  - Control **installed but disabled** → the transport wording is kept (correct: it *is* installed)
    **and no button appears**, because a disabled package has no launch intent. The two halves
    compose without either knowing about the other, which is what §Decision 3 predicted.
  - Control **installed and enabled** → no banner at all: `BIND_AUTO_CREATE` revives it. ⚠ This is
    why the *button* could not be observed on the emulator — the Android transport case that
    persists long enough to tap is a **locked** Control, which needs a paired device. The judgement
    behind it is unit-pinned instead (`reasonOffersOpeningControl`).
  - ⚠⚠ A trap for the next person: the first attempt tested **Fit and Contacts**, which are exactly
    the two apps the smoke gate leaves un-built as lockstep canaries — so their APKs predate the
    change and showed the old wording. `docs/testing.md` §6 has the rule; this is what it looks like
    when you forget it.
- **The store route was device-verified the same way**, including the half that is easy to assert
  and hard to believe:
  - No link configured → *"…and it isn't in the store yet"*.
  - Link configured (`-Ptenshu.control.playUrl=…`, confirmed in the generated `ControlStoreConfig`)
    but the copy installed over `adb` (`pm list packages -i` → `installer=null`) → *"Install it the
    same way you installed Maps"*, **and no button**. ⭐ That is §Decision 6's "necessary, not
    sufficient" demonstrated rather than claimed: the one arrangement where a naive
    link-is-configured check would have shipped a wrong store button.
- **Calendar and Photos adopted the banner 2026-08-26**, and they were the worst case rather than
  merely the next one: both *already built* the engine (Photos even fed it from its read folds via
  `markReachable`/`markUnreachable`) and neither read the result, so an unreachable Control rendered
  an empty month and an empty library with **no explanation at all** — the silent-blank failure
  `:app-reachability` was extracted to prevent. Calendar's own `PlatformClientModule` comment said
  *"the reachability banner is what says so"* about a banner that did not exist.
  - ⚠ Both were also verbatim pre-SK-1: each called `wireSchemaAnnounce` and **discarded the
    returned announcer**, so the refusal verdict was computed and read by nobody. Wired here.
  - ⚠ Their copy is **literal strings, not `stringResource`** — neither app ships
    `composeResources`, and half-localising one banner would hide the app's convention rather than
    advance it. They join the i18n rollout (ADR 0016) as whole apps or not at all.
  - ⭐⭐ **Found while device-verifying, fixed 2026-08-26:** Photos' feed rendered *"Something went
    wrong loading your photos."* with a **Try again** button underneath the new banner, despite
    having a correct `FeedError.Unreachable` arm. The cause was not the view model, which maps
    correctly, but the repository: `RemoteBinaryTaskExecutor` folds an unreachable proxy into a
    typed `TResult.Failure` rather than throwing, so on Android a dead transport reaches the read's
    `fold(onFailure)` — **not** the `catch` beneath it — and that arm reported `Failed` while the
    line directly above it had already told the reachability engine the proxy was unreachable. The
    `Unreachable` result was effectively dead code on the one platform where it mattered.
    - ⚠⚠ Classified from `TenshuClient.proxyConnected`, **never** from the error's text: the
      executor's connection failure is a `GenericError` carrying the string *"Proxy not
      connected"*, and pinning behaviour to a diagnostic message is a rename away from a
      production-only regression. `false` is authoritative, `true` proves nothing — so a live
      channel stays a genuine read failure and the shell's reason supplies the words.
    - The **Try again** button now appears only where retrying can succeed, via `waitingWillFix`
      hoisted from Fit into `:app-reachability` — moved when a second app needed the same answer
      rather than after the two had drifted. And the `Unreachable` copy no longer asserts *"Control
      just isn't running"*, which is false when it is not installed: the banner above names the
      cause once, and the body stopped guessing at it.
    - ⭐ **The rest of Photos' reads got the same treatment 2026-09-03, and the "Still owed" line
      below had the diagnosis wrong.** It supposed the album, trash and duplicates screens "read
      through paths that throw rather than fold". They fold — that *is* the bug, and it is the
      feed's, five more times: `albums`, `albumPhotos`, `favourites`, `trashed`, `duplicates` and
      `photographicSettings` each folded a failure straight to their own `Failed`, so their
      `Unreachable` arm was reachable only from a `catch` that a dead transport does not
      enter (a proxy dying mid-call fails the caller rather than wedging it). An uninstalled Control rendered *"something went wrong"* with a **Try again**
      button on every one of them.
      - The classifier is now one one-liner **per result family** beside `pageReadFailure`
        (`albumsReadFailure`, `trashReadFailure`, `duplicatesReadFailure`, `exifReadFailure`),
        pinned by `ReadFailureClassificationTest` — pure functions, because a repository fake
        carrying a live `TenshuClient` would dwarf the decision it checks.
      - ⚠⚠ **And the repository stopped calling `markUnreachable` at all**, which the executor's outcome
        classification had already made vestigial in every app: a dead transport reaches the engine through
        `transportConnected` (R4/R5) and a throw is not evidence the proxy is gone — Fit's
        `retryingOnThrow` states the rule. The catches now name the throwable in a `Failed`, so
        §18.4's five unregistered services would have read as the client-side bugs they were
        instead of each spending a phase looking like an outage. **`markReachable` stays**: a read
        the proxy answered *is* sound evidence, and that asymmetry is the whole rule.
      - ⚠ One `markReachable` went too, and it was not vestigial. `duplicates()` claimed reachable
        off a non-null `RealmContentIndex.current()` — which is `observe().first()`, so a warm
        subscription answers from cache and proves nothing about the channel now. An organic
        success sets `state = true`, which **cancels** the probe loop (`collectLatest`), so an
        unsound one silences the only check that finds a live-but-refusing proxy.
- **Home and Mia adopted the wall 2026-08-26, and neither was the "no runtime, nothing to gate on"
  case ADR 0022 recorded for iOS.** On Android both load a client module and talk to Control:
  - **Mia** binds `TenshuConversationRepository`, whose own module says *"the mock is not a
    fallback"* — it already answered every question with an honest "I can't reach Control". What was
    missing was the shell's half: it rendered *"Hi, I'm Mia — ask me to find photos from your
    Drive"* over a live input box, inviting a question it knew it could not answer. ⚠ Its wall sits
    **ahead of onboarding**: walking someone through setting up an assistant that cannot answer is
    the same defect one screen earlier.
  - ⚠⚠ **Home was the worst of the nine, and it did not look it.** Its screens rendered *populated*
    without Control rather than empty, because `HomeDevicesRepository` was a chain whose base
    delegate was `MockHomeDevicesRepository()` — so an absent proxy produced *"Living room ·
    Connected"*, *"588 GB free of 1 TB"*, *"Sofa lamp · On · 70%"*: fabricated device states
    indistinguishable from real ones. An app that shows a lamp as on when it can reach nothing is
    worse than one that shows nothing, and it is the *"fakes are opt-in, never a default"* rule the
    Control-on-iOS work already wrote down. ⚠ Its module KDoc claims `:home-android` "stays
    mock-only"; `HomeApplication` has loaded `homeClientModule` for some time, so the comment is
    stale in the direction that hides the problem.
  - ⚠ Neither registers `syncService`, so both wire `schemaSkew` **only** — `Fenced` cannot surface
    in either until they register it, which is a schema-set change rather than a DI line. Wiring the
    fence anyway would have produced a signal that silently never fires, because an unregistered
    service fails at *dispatch* and every caller catches.
- ⚠⚠ **Still owed, and now scoped: retiring Home's mock household.** The wall stops it being shown
  when Control is absent; it is still shown when Control is *present* and the realm is empty
  (`if (synced.isEmpty()) mocked else synced`). Removing it is two unequal halves:
  - *Automations* — clean. `SyncedAutomationsRepository` takes `mock:` purely as a read fallback;
    writes already go through `RoutineControlSource`.
  - *Devices* — **blocked on a product decision, not a refactor.** `addDevice` / `removeDevice` /
    `moveDevice` all delegate to the mock, and there is no proxy-backed write path: real devices
    arrive read-only via `DeviceSource`, and pairing goes through `DevicePairingSource`. Deleting
    the mock therefore deletes the Devices tab's add affordance, which today writes to an in-memory
    object that survives nothing. ⭐ The principle to apply when it is done: **demo content belongs
    behind the proxy** — seeded rows a real read returns — never a client-side repository that
    substitutes itself when the real one is empty or unreachable. A fallback in the client cannot be
    told apart from the truth by the screen above it, which is exactly how this survived nine apps.
- **Drive-on-Compose adopted it 2026-08-26, and it was not the outlier the earlier note assumed.**
  Its `SyncStatusBanner` predates the shared vocabulary and grew its own: `Disconnected` *is*
  `Transport` under another name and `NeedsUpdate` *is* `Fenced`. ⚠⚠ **Two cases were absent, and
  both were defects rather than untidiness:**
  - **`Skew`** — Drive called `wireSchemaAnnounce` and **discarded the announcer**, exactly as
    Calendar, Photos and Home did. A refused session leaves `proxyConnected` true, no storage
    figures and no health row, so `SyncBanner.evaluate` fell through both `null` guards to **no
    banner at all** — an empty file list with nothing to explain it, in the app whose whole job is
    showing you your files. Now `SyncBannerState.NeedsAppUpdate`, placed directly below
    `Disconnected` (a dead channel makes the refusal verdict itself stale) and above `Locked` (an
    unlock cannot help a session the proxy will not admit).
  - **`NotInstalled`** — rendered as `Disconnected`, whose own KDoc says *"informational, no
    action"*. Now the wall, at Drive's `App.kt` root.
  - ⚠ The engine wires `schemaSkew` **only**: Drive derives `Transport` from `proxyConnected` and
    the fence from its own stream, and two paths to one banner state would be two sources of truth
    for one fact.
  - ⚠⚠ **Its other six states stay exactly where they are.** `Locked`, `DeviceUnrecognized`,
    `NeedsAttention`, `Deferred`, `Offline` and `Syncing` are facts about the *relay and the queue*
    that no other app has. Drive was never an app that failed to follow the pattern; it is one
    carrying a Control surface and a sync surface fused into a single banner, built before the
    pattern existed.
  - ⚠ **Placement is still unconverged, and it is a real gap.** Drive renders its banner on
    `FilesScreen` alone, while its own KDoc calls it *"persistent, account-wide"* — so on Home and
    Shared, two of three tabs, a full realm or a locked one shows nothing. Hoisting it to the shell
    is the obvious fix and carries one screen-local rule with it: the banner hides during
    multi-select because "the selection bar owns the top strip then", which only makes sense where
    the selection bar is.
- **The storage banner's copy converged 2026-08-26 — one sentence for all nine apps.** ⚠ Only the
  *copy*: Drive keeps its own component and its single quiet tone (it never escalates `Full` to the
  error container the shared banner uses), which was the deliberate half of its difference.
  - It had drifted to **five lead-ins and eight nouns** — "Not syncing" / "Not backed up" / "Local
    only", over "contacts", "workouts", "places and offline maps", "timeline", "events", "photos",
    "files" and "data". ⚠ Home and Mia already said **data**, so the convergence adopts the most
    recent precedent rather than inventing one — and fixes an agreement bug those two carried
    ("your data … *they* can't sync").
  - ⚠⚠ **"Nothing is lost." is gone**, and this is the substantive edit rather than a tidy-up: a
    user does not need telling that their device will not delete their files. What is *not*
    obvious is that storage is what buys **syncing**. So the sentence now ends "Set up storage to
    sync across your devices" — which was **Drive's** wording all along, and is the second place
    this convergence adopted Drive's answer rather than the shared one.
  - ⚠ The button follows the sentence: `Full` keeps "Manage storage", no-plan becomes "Set up
    storage". A button reading "Manage storage" under "Set up storage to sync…" is two
    instructions, and there is nothing to *manage* on a realm with no plan. Drive already did this;
    the other eight now do too.
- ⚠ **`iosKotlinCheck` did not cover `:ui-core`**, found while adding an `iosMain` file to it: the
  task reported success in four seconds having never compiled it, because it depends on the
  `:tenshu-sdk-ios` umbrella and nothing in that graph links `:ui-core`. Added explicitly. The
  umbrella is a heuristic, not a proof — and a module with an `iosMain` that no iOS binary links is
  precisely the one that needs the gate most, since nothing else will ever compile it.
- ⚠⚠ **Still owed:** an actual store link (the mechanism is built; publishing Control is now a
  build-setting change); a Compose twin of `ConnectToControlView` for the wall posture, if and when a
  Compose surface is found that is a genuine dead end rather than a browsable one; Drive-on-Compose;
  the locked-Control button on a paired device; and the **desktop** and **store-route** wording,
  which is unit-tested but has never been seen on a Mac with Control absent or on a store build of
  anything.
  - ✅ The *"retry that cannot work"* audit on Photos' album, trash and duplicates screens is done
    (2026-09-03, above). ⚠ **Six apps still hold the same unclassified folds and the same vestigial
    `markUnreachable` calls** — Photos is the reference, not the fix.
  - ⚠ Photos' pass is **unit-verified only**. Nobody has uninstalled Control and opened Collections,
    the trash or duplicates since; the device pass that found the feed's copy is exactly what would
    find the next one.
