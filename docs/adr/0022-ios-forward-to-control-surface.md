# ADR 0022 — Every first-party iOS app forwards to Control through one shared screen

- **Status:** Accepted; adopted by **every** first-party route-1 iOS app. ⚠ **Amended
  2026-10-02 (§Decision 6): an app Control never provisioned takes the full-screen gate at
  its root** — Drive, Photos, Calendar, Contacts, Maps, Timeline, Fit, and since their
  route-1 runtimes landed the same day, Home and Mia. The banner (§Decision 5) remains for an
  app whose route to Control dropped under a mounted shell.
- **Date:** 2026-08-07 (amended the same day — §Decision 5; amended 2026-10-02 — §Decision 6)
- **Owner:** Benedikt
- **Relates to:** [`docs/proposals/ios-first-party-route-1-migration.md`](../proposals/ios-first-party-route-1-migration.md),
  [`docs/proposals/ios-drive-parity.md`](../proposals/ios-drive-parity.md) §11.6,
  [`docs/proposals/ios-fit-timeline-maps-parity.md`](../proposals/ios-fit-timeline-maps-parity.md) §3.1.2 (D10, the shared-Swift precedent),
  [`docs/how-to/ios-signing.md`](../how-to/ios-signing.md) §2 (build-setting → Info.plist → Swift).
- **Supersedes / Superseded by:** Superseded by [ADR 0025](0025-forward-to-control-surface.md),
  which widens this to Android and desktop. ⚠ Only the *scope* changed: the iOS mechanics below —
  the shared screen, the two postures, `appName` + `purpose`, nil-until-configured store ids — are
  unchanged and still the reference. 0025's §Context carries what iOS could not see from here.

## Context

On iOS every first-party app is **route-1**: it runs its own in-process proxy over the
shared App-Group replica, and adopts Control's device identity and realm keys from the
shared Keychain. Control is therefore not a nice-to-have companion — without it a
route-1 app has no realm, no keys, and nothing to show. What the app does in that state
is a real product surface, not an error case.

**Drive solved this once, properly.** `ConnectToControlView` (ios-drive-parity §11.6)
frames it as *"set up the Tenshu platform on this device"* rather than *"pair with an
external app"* — the latter framing belongs to third-party EPC apps — and routes the
user two ways: open Control by deep link (`tenshu-control://setup`), or, when
`openURL`'s completion reports that nothing handles the scheme, hand off to the App
Store. On return `scenePhase → .active` re-checks the Keychain and forwards in.

**Nobody else got it.** As of 2026-08-07 the same state was handled five different ways:

| App | Behaviour when Control is absent |
|---|---|
| Drive | The screen above — deep link, App Store fallback |
| Fit | A bespoke **Record-tab-only** wall: *"Open Tenshu Control and unlock it, then come back"* — an instruction with **nothing to tap**, while every other tab looked live |
| Timeline / Maps / Contacts | An honest empty surface, no route forward |
| Mia / Home | No route-1 runtime at all |

Two things made this worth an ADR rather than a copy-paste. First, the divergence was
invisible: each app's behaviour looked deliberate in isolation, and only lining them up
showed that four apps had no path back to a working state. Second, the App Store link
was a **hardcoded placeholder** (`id000000000`) — a URL that opens a broken Store page,
a failure only a user would ever see.

## Decision

**1. One shared screen, owned by `ios-onboarding/`.** `ConnectToControlView` and its
`Control` link enum move to `ios-onboarding/Sources/`, consumed through an XcodeGen
fragment (`onboarding.yml` → `templates: [ControlOnboardingApp]`). Two lines per app.

This follows **D10** (`ios-basemap`) exactly, including its constraint: the sources are
compiled **into each app target**, not vended as a local Swift package. A SwiftPM
`.binaryTarget` is resolved before any build phase runs, so a package sitting alongside
the Gradle-built `TenshuSdk.xcframework` fails on the fresh `-derivedDataPath` this
repo's build instructions require. XcodeGen takes sources from any path; a package
cannot.

**2. The caller supplies `appName` + `purpose`.** The copy must name what *this* app
loses ("keep your files end-to-end encrypted and synced" vs "record and sync your
workouts"). A generic sentence would be true of nothing in particular, and the screen's
job is to make the cost of not continuing concrete.

**3. The App Store id is a build setting, and its absence is a rendered state.**
`TENSHU_CONTROL_APP_STORE_ID` → Info.plist `TenshuControlAppStoreId` → `Control.appStoreURL`,
mirroring the `TenshuSharedKeychainGroup` path so there is one mechanism to learn.

⚠ It resolves to **nil when unset**, and the screen then says *"Tenshu Control isn't on
the App Store yet"* instead of offering a button. This is the point of the change: the
previous placeholder meant the only way to discover the link was broken was to tap it in
production. Nil-by-default inverts that — shipping a wrong link now requires configuring
a wrong id. The guard also rejects an unsubstituted `$(…)` literal and the old
all-zeroes placeholder, since both would otherwise pass a naive non-empty check.

**4. Gate only where the app genuinely cannot function.** The screen replaces a
*dead end*, never a *browsable surface*. Fit gates **Record** (a mock recorder would tick
fake numbers and fail to save, fit-app.md §11.7) while History/Home/Browse/Profile stay
readable over cached data behind the existing non-blocking banner. Drive gates at the
root because without a realm it has no files at all.

⚠ This is why Timeline / Maps / Contacts are **not** converted here: their honest empty
surfaces still let you read cached content, and a full-screen wall would take that away.
They adopt the screen if and when they grow a state that is genuinely dead.

⚠ **Amended by §Decision 5** — they still take no wall, but "no wall" turned out not to
mean "nothing owed": all three (and Fit's own shell bar) were naming Control with no way
to reach it. They take the banner.

**5. A browsable surface owes the same route forward — as a banner, not a wall.**
*(Amendment, 2026-08-07, applying this ADR to the remaining apps.)*

Decision 4 settled *where the wall goes*. It left the other four surfaces where they
were — and on re-reading them together, they had the **same defect this ADR was written
about**. Timeline, Maps and Fit's shell all rendered a character-for-character identical
bar reading *"Can't reach Tenshu Control — open Control and unlock it, then come back."*;
Contacts read *"Your contacts will appear once it is running."* Every one of them is an
instruction the user cannot follow from where they are standing. Fit's Record tab was
fixed and Fit's own shell bar, one level up, was not.

So the ADR has **two surfaces, one destination**:

| Posture | Component | Where |
|---|---|---|
| Blocking | `ConnectToControlView` | A **dead end** — Drive's root, Fit's Record tab |
| Non-blocking | `ControlNotConnectedBanner` | Above a **browsable** surface — Fit's other tabs, Timeline, Maps, Contacts |

The banner keeps the copy's job (say why the screens are empty) and adds a *Set up*
button that presents the very same `ConnectToControlView` as a sheet. Nothing is walled
off; offline browsing in all four apps is untouched. Explaining an empty screen and
offering a way out are two duties, and only the first had been done.

⚠ **The banner does not dismiss its own sheet.** Every host already tears the tree down
when provisioning arrives (Timeline/Maps remount on `liveToken`, Contacts on its
reachability flow, Fit on `.liveSessionChanged`), which takes the sheet with it. A
banner-local "am I still needed" check would be a second source of truth for a question
the host already answers — and whichever of the two got it wrong would be invisible.

⚠ **Maps is the closest call,** and worth recording as such: with no runtime its places
list *and* its region catalogue are both empty, which is nearer to Drive's "no realm, no
files" than to Fit's cached tabs. It keeps the banner anyway, for a reason that is about
the screens rather than the data: each one already explains itself in place — the region
picker names Control outright — and a wall would replace several specific explanations
with one generic screen. If Maps ever grows a surface the user can be *dumped into* with
nothing to read, that is the trigger to revisit, and this paragraph is the note saying the
question was asked rather than missed.

**6. Unprovisioned is a dead end in every route-1 app — the gate goes at the root.**
*(Amendment, 2026-10-02, after a device pass on a freshly installed set of all ten apps.)*

Decisions 4 and 5 chose the posture by asking whether the surface behind it is browsable,
and answered "yes, over cached content" for Timeline, Maps and Contacts; Calendar later
argued the same for itself ("a calendar's dates exist without any data"). ⚠⚠ **That answer
was true of the EPC tier those apps were on when it was written, and is false on route 1.**
A route-1 app reads the shared replica only through the in-process runtime that
provisioning installs. With no provisioning there is no runtime, every repository is the
not-connected one, and every screen is empty by construction. There is no cached content
to browse.

What that looked like on the phone: Drive and Photos showed *Set Up Tenshu Control*, and
next to them Calendar showed an empty month under a one-line bar. It is the contradiction
[ADR 0025](0025-forward-to-control-surface.md) §Decision 1b found on Android and fixed
with the wall: the rest of the UI was not a distraction from the banner, it was
contradicting it. That finding was never carried back here.

So the rule is by **provisioning**, not by app:

| State | Surface |
|---|---|
| Control never provisioned this app (not installed, or installed and not set up) | `ConnectToControlView`, in place of the whole app — `ControlSetUpGate` at the shipping root |
| Provisioned, and the runtime reports Control unreachable | `ControlNotConnectedBanner` above the app, unchanged |

- `ControlSetUpGate` (`ios-onboarding`) owns the three-way switch — checking, absent,
  provisioned — so the four roots that adopted it (Calendar, Contacts, Maps, Timeline) do
  not each restate it. Drive and Photos already had the same switch inline.
- ⚠ The app's shell is **not built** until provisioning is known. The swap onto the
  route-1 repositories happens at view-model construction, so a shell built first holds
  view models over the not-connected repository; Maps and Timeline carried a remount
  token for exactly that.
- ⚠⚠ **Fit has two routes, so its gate asks about both.** Its shipping root falls back to
  the route-2 EPC handoff when unprovisioned, and "connected" there is the route-1 runtime
  **or** a live EPC client. The first cut of this amendment left Fit out on the strength of
  that fallback, and the phone showed why that was wrong: with neither route live it
  rendered the banner above *"Your activity couldn't be loaded. Still trying…"*. The EPC
  attempt keeps running behind the screen and lifts it through `.liveSessionChanged`.
- **Home is covered as of 2026-10-02.** It has a route-1 runtime (`HomeLocalRuntime`) and
  roots in `ControlSetUpGate` like Calendar. Until then `HomeSurface` bound mock
  repositories and showed a fabricated household (*"Sofa lamp · On · 70%"*) whether or not
  Control was set up — the defect ADR 0025 recorded for Android's Home. ⚠ Provisioned Home
  is read-only for now, and an empty realm is an empty state
  ([`home-and-mia-apps.md`](../proposals/home-and-mia-apps.md) §8.1).
- **Mia is covered as of 2026-10-02.** It has a route-1 runtime (`MiaLocalRuntime`;
  [`mia-query-layer.md`](../proposals/mia-query-layer.md) §19) and roots in
  `ControlSetUpGate` like Calendar. Until then `MiaSurface` bound a mock, so setting Control
  up changed nothing in the app and the wall would have been a lie. ⚠ The gate sits **ahead
  of Mia's onboarding**, not behind it — walking someone through setting up an assistant
  that cannot answer is the same defect one screen earlier, which is why ADR 0025 puts
  Android's wall ahead of onboarding too. ⚠ Mia takes the gate and **not** the banner
  (§Decision 5): it has no screen that goes on showing cached rows, and its runtime has no
  channel to Control for "Can't reach Tenshu Control" to be about — a turn that could not be
  asked says so in the conversation instead. ⚠ Provisioned Mia is read-only: it answers and
  offers no action.
- ⚠ The `DEV_HARNESS` shells and the render probes construct the app's root view directly
  and never pass through the gate.
- ⚠ Verified on a device for the unprovisioned state only. The transition (set Control up,
  return to the app) rides the same `scenePhase` re-check Drive uses and was not exercised
  here.

## Consequences

- A new consumer costs two lines of YAML plus one call site.
- The failure mode that motivated this — an app telling the user to do something with no
  affordance to do it — is now structurally hard: the shared screen always renders an
  action or an explicit explanation of why there isn't one, and after §Decision 5 there is
  no remaining route-1 surface that names Control without offering a way to reach it.
- The four local `ControlNotConnectedBanner` copies are gone. Three of them were already
  byte-identical, which is the divergence this ADR predicted — caught here before they
  drifted rather than after.
- ⚠ One consequence of sharing is worth stating plainly: the copy is now the *same
  sentence in five apps*, so it can no longer be tuned per app without either a parameter
  or a fork. `appName` + `purpose` are the sanctioned parameters (§Decision 2); anything
  beyond them should be argued for, not added.
- ⚠ **Still owed before launch:** set `TENSHU_CONTROL_APP_STORE_ID` when Control ships
  to the App Store. Until then every app correctly shows the TestFlight/Xcode wording.
- ⚠ The deep-link scheme `tenshu-control://setup` must stay stable; it is now referenced
  from one place, which makes changing it cheap but makes an unnoticed change wider.
- Mia and Home gained nothing from the first cut of §Decision 6 — neither had a route-1
  runtime, so neither had a provisioning state to gate on, and a gate that always says
  "connected" is worse than none. Both have one now and both take the gate (see the two
  bullets in §Decision 6).
