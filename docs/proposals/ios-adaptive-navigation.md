# iOS adaptive navigation — every app already ships on iPad, phone-shaped

**Status:** v1.0 — **SHIPPED 2026-08-12** (as built: §2.1 spike, §2.2 implementation). This is **N6** of
[`ui-adaptive-navigation.md`](ui-adaptive-navigation.md), broken out because it shares no code with
N1–N5 and gates differently. N1–N5 fixed the *Compose* shell (`ui-core/AdaptiveNavScaffold`);
nothing in them reaches iOS, whose chrome is SwiftUI in `*-ios/Sources`.

The one-line version: **`TARGETED_DEVICE_FAMILY = "1,2"` in every generated project** — all nine iOS
apps build and install on iPad today, and every one of them renders a bottom tab bar there. This is
not a missing feature, it is a shipped surface that is wrong.

---

## 0. Ground facts (verified in this repo / on this machine, 2026-08-11)

Each of these was checked, not assumed. They are the facts the plan below turns on.

| # | Fact | How it was checked |
|---|---|---|
| G1 | `NavigationSplitView` appears in **zero** Swift files. | `grep -rn NavigationSplitView --include=*.swift` |
| G2 | `horizontalSizeClass` / `userInterfaceIdiom` appear in **zero** Swift files. The iOS UI has no size-class awareness at all. | same grep |
| G3 | Every app is **iPad-capable today**: `TARGETED_DEVICE_FAMILY = 1,2`, unset in every `project.yml` so it takes Xcode's universal default. | `xcodebuild -showBuildSettings` on the generated `DriveIos.xcodeproj` |
| G4 | Deployment floor is **iOS 16.0** in all nine `project.yml`s (Calendar's carries a comment pinning it there for `UICalendarView`). | `grep -A2 deploymentTarget */project.yml` |
| G5 | Toolchain here is **Xcode 26.6 / iOS SDK 26.5**; newest simulator runtime **26.5**. There is **no iOS 27 SDK and no 27 runtime on this machine.** | `xcodebuild -version`, `xcrun simctl list runtimes` |
| G6 | `SUPPORTED_PLATFORMS = iphoneos iphonesimulator` — there is **no Mac target**. `drive-desktop/macos/FileProviderExtension` is an appex, not an app shell; macOS Control/Drive is Compose Desktop. | `xcodebuild -showBuildSettings` |
| G7 | ⚠ **NO LONGER TRUE — historical.** At the time of this sweep `calendar` was absent from `scripts/ios-smoke.sh`'s `spec_for` and from the `--build-only` list, so calendar-ios was in no gate at all. **The calendar compile-gate fix closed this on master while this track was building** (§2.2.1); D6 is withdrawn and this branch does not touch the script. | read `scripts/ios-smoke.sh:50-83` |
| G8 | iPad simulators are available here (iPad Pro 11"/13" M4, Air, mini, 10th gen). N6 is *visually verifiable on this machine*. | `xcrun simctl list devices` |
| G9 | **The iOS build of `master` (e63876a3) is green.** The XCFramework assembles and Drive compiles. This is the baseline N6-1's diff is read against — and it is not a formality: `master` was silently unable to build *any* iOS app for a stretch in 2026-08, because the XCFramework never assembled and no gate ran. | `ios-smoke.sh --build-only drive` → `PASS drive (compiles)`, exit 0 |

⚠ **G5 retires half of N6's own one-line brief.** `ui-adaptive-navigation.md:286` says N6 targets "the
27 edge-to-edge sidebar" (its §D6 records the WWDC26 reversal of the iOS 26 inset/floating look).
That look cannot be built, run, or looked at with the toolchain installed here. **This plan targets
≤ iOS 26 and treats 27 as a later re-check**, not as a design input. Nothing below hard-codes a
sidebar inset or width in either direction, so a 27 re-check is a look, not a rewrite.

⚠ **G6 retires the other half.** N6 reads "for iPad/Mac". There is no Mac SwiftUI target to adapt.
**N6 is iPad-only in practice.**

### 0.1 The shell inventory — seven tab-shaped apps, two stack-only

| App | Shipping shell | Top-level destinations |
|---|---|---|
| Control | `MainTabsView` (`ControlIosApp.swift:924`) | 4 — Home, Data, Apps, Settings |
| Drive | `PairedAppView` (`PairedAppView.swift:18`) | 2 — Files, Shared |
| Fit | `MainShell` (`FitIosApp.swift:222`) | 5 — Home, History, Record, Browse, Profile |
| Calendar | `CalendarIosApp.swift:103` | 3 — Month, Agenda, Calendars |
| Home | `HomeIosApp.swift:12` | 4 — Devices, Automations, Activity, Settings |
| Mia | `MiaIosApp.swift:28` | 3 — Chat, History, Settings |
| Contacts | `ContactsTabs` (`ContactsIosApp.swift:164`) | 2 — Contacts, You |
| **Timeline** | `TimelineRootView` | **none** — one `NavigationStack` |
| **Maps** | `MapsRootView` | **none** — one `NavigationStack` |

The `DEV_HARNESS` shells (App/Pair tabs in Drive, Fit and Timeline; Drive's Smokes tab was deleted 2026-11-05) are **out of
scope**: they are debug scaffolding and their whole job is to be obviously not the product.

⭐ **A finding worth recording on its own:** the two platforms disagree about which apps have nav
chrome. `ui-adaptive-navigation.md` §0.1 lists **Maps and Calendar** as the Compose apps with no nav
chrome; on iOS it is **Maps and Timeline**. Calendar has three tabs on iOS and none on desktop;
Timeline has a rail on desktop and no tabs on iOS. Neither divergence is written down anywhere, and
neither is obviously deliberate — this is Q3 of the Compose doc ("are these two apps that quietly
opted out of the shared shell?") with a second, differently-shaped instance. Answering it is out of
scope here; **naming it is not**.

---

## 1. Decisions

### D1 — The container is `TabView` + `.tabViewStyle(.sidebarAdaptable)`, not `NavigationSplitView`

N6's title names `NavigationSplitView`. That is the wrong tool for the surface it was aimed at, and
the recommendation here is to change the name rather than the apps.

- All seven tab-shaped apps have **top-level, peer, unordered app sections** — Home/Data/Apps/Settings,
  Files/Shared, Chat/History/Settings. That is what a tab bar (and its regular-width sidebar
  presentation) is *for*.
- `NavigationSplitView` models a **content hierarchy** — a selection in the leading column choosing
  what the trailing column shows. Drive's Files genuinely is that. `Chat / History / Settings` is not,
  and forcing it into two columns means inventing a detail placeholder for every app.
- `.sidebarAdaptable` (iOS 18+) is Apple's stated answer for exactly this: the same `TabView` renders
  as a sidebar at regular width, with the user-operable sidebar↔tab-bar toggle, and picks up the
  system's current sidebar look for free on each OS — which is the only way this repo gets the iOS 27
  behaviour (G5) without shipping its own copy of it.

⭐ **The load-bearing reason, and it is not aesthetics.** `.sidebarAdaptable` changes *presentation*
without changing the *view tree*. A `if regular { NavigationSplitView } else { TabView }` swap tears
down and rebuilds the whole subtree the moment the size class changes — which on iPad happens on
**every rotation and every Split View / Slide Over resize**. Each tab's `NavigationStack` path is
inside that subtree, so the user is thrown back to the root of wherever they were, mid-gesture. This
is the iOS twin of what N3 recorded on the Compose side ("a window dragged across a breakpoint
animates rather than swapping components — which the old three-branch `when` could not do"): **one
component that adapts beats two components selected by a `when`.**

~~⚠ **D1 is contingent on N6-0.**~~ **Resolved 2026-08-12 in D1's favour — see §2.1 F1.**
`.sidebarAdaptable` composes with the legacy `.tabItem` API on both 18.5 and 26.5; no `Tab(…)`
migration is required, so adoption is one modifier per app.

⚠⚠ **But N6-0 also found that the style does not give you a sidebar — it gives the user the option
of one (§2.1 F2).** D1 still holds as the *container* decision. What it does not settle is whether
"sidebar by default at regular width" is a requirement; if it is, D1 needs a second half, and that
belongs in N6-1 rather than after six apps have adopted.

### D2 — One shared shell in `ios-shell/`, on the `ios-onboarding` pattern

Seven apps, one behaviour. Copy the convention the repo already has for exactly this
(`ios-onboarding/onboarding.yml`, ADR 0022): a top-level `ios-shell/` directory holding `Sources/`
plus a `shell.yml` XcodeGen `targetTemplates` fragment each app `include`s and lists in `templates:`.

⚠ **Compiled into each app target, not vended as a local Swift package.** `onboarding.yml` documents
why and the constraint is unchanged: a SwiftPM `.binaryTarget` resolves before any build phase runs,
so a package sitting alongside the Gradle-built `TenshuSdk.xcframework` fails on the fresh
`-derivedDataPath` this repo's build instructions require. XcodeGen takes sources from any path; the
cost is one `include` line per app.

The surface is deliberately small — a destination list and a shell that renders it:

```swift
// ios-shell/Sources/AppShell.swift  (sketch, not final)
struct AppDestination: Identifiable {
    let id: String
    let title: String
    let systemImage: String
    var badge: Int? = nil
    let content: AnyView
}

struct AdaptiveAppShell: View {          // renders TabView; sidebar-adaptable where available
    let destinations: [AppDestination]
    @Binding var selection: String
}
```

⚠ `AnyView` is the right erasure here despite the usual advice against it: the destination list is
heterogeneous, `TabContentBuilder` is iOS 18+, and each destination is mounted once for the app's
lifetime rather than in a list body. Identity is stable because the array is constructed in a fixed
order with fixed `id`s — **that stability is the requirement**, not the erasure.

### D3 — Hoist selection and paths *above* the container, in the same PR that adopts it

This is the per-app work, and it is most of the work.

Drive already does it right: `FilesRouter` is an `@StateObject` on `PairedAppView`, **outside** the
`TabView`, precisely so any depth of `FilesView` can push. Everyone else keeps state inside — Fit's
`@State private var selectedTab` (which the Browse → Profile "connect a source" jump writes),
Contacts' `pendingCount` `.task`, Timeline's per-route paths.

Even under D1, where no swap should occur, hoisting is the thing that makes the shell *safe to change
its mind about*: if N6-0 forces an `if #available` split (iOS 18 sidebar tree vs iOS 16 tab tree),
that split is compile-time and harmless — but only because no navigation state lives inside either
branch.

### D4 — Timeline and Maps are not in scope, and that is not an oversight

Neither has a top-level destination list (§0.1). A sidebar with one row is worse than no sidebar. Both
already root a single `NavigationStack`, which is correct on iPad as-is. They come into scope only if
§0.1's ⭐ divergence is resolved toward "these apps should have chrome".

### D5 — Do not hard-code sidebar widths or insets

Carried unchanged from `ui-adaptive-navigation.md` §D6's second ⚠: the HIG states no sidebar pt
values; the only published figures are an API default and sample code. Let the system size it. This
is also what makes the G5 iOS-27 re-check cheap.

### D6 — ~~Fix the calendar gate hole in this track~~ (WITHDRAWN — the calendar compile-gate fix did it first)

⚠ **Withdrawn 2026-08-12 — see §2.2.1.** The reasoning below was sound and the hole was real, but
the calendar compile-gate fix (`7c729329`, §2.2.1) closed it on master while this track was building, and this branch was rebased with
its duplicate changes dropped. G7 is therefore **historical**: it describes the state at the start of
this work, not the state of the repo.

> G7: calendar-ios is in no gate. `ios-smoke.sh` is the gate this track runs, N6 touches Calendar's
> shell, and adding one line to `spec_for` plus one to the `--build-only` list closes it. Doing it
> anywhere else means doing it later.

---

## 2. Phase map

| Phase | Scope | Gate |
|---|---|---|
| **N6-0** ✅ | **Spike, no production code.** On an iPad Pro 11" sim at 26.5 *and* 18.5: screenshot Drive as it is today (the defect, pinned), then try `.tabViewStyle(.sidebarAdaptable)` against the existing `.tabItem` shells. Answer: does it compose with `.tabItem`, or is a `Tab(…)` migration required? What does the sidebar look like at each runtime? **Run 2026-08-12 — findings in §2.1; all spike code reverted.** | screenshots + a written answer |
| **N6-1** ✅ | `ios-shell/` + `shell.yml`; **Drive only**. `PairedAppView` onto `AdaptiveAppShell` (2 destinations, and its router is already hoisted — the cheapest correct first consumer). | `ios-smoke.sh --build-only drive` + iPhone & iPad screenshots |
| **N6-2** ✅ | The remaining six — Control, Fit, Calendar, Home, Mia, Contacts. Per app: hoist selection state (D3), then adopt. Fit is the interesting one (5 destinations + a cross-tab jump); Contacts carries a badge. | `ios-smoke.sh --build-only` (all) + one iPad screenshot per app |
| **N6-3** ✅ | ~~`scripts/ios-smoke.sh`: add `calendar` (D6)~~ — **already done by the calendar compile-gate fix**, see §2.2.1; this branch touches the script not at all. Doc amendments — this doc's as-built, `ui-adaptive-navigation.md`'s N6 row, and §0.1's ⭐ divergence recorded where someone will find it. | `ios-smoke.sh --build-only` (all nine) |
| **N6-4** | *Optional, separate, not scheduled.* Drive's Files as a real `NavigationSplitView` — folder tree in the leading column, listing in the middle, preview in the detail. The one surface where the container N6 was named after is genuinely right. Owes its own decisions (what is a "folder tree" over a paged listing?) and should not ride this track. | its own |

N6-0 is a half-day and gates the shape of everything after it. N6-1 and N6-2 can land together if
N6-0 comes back clean.

### 2.1 As run — N6-0 (2026-08-12)

Method: `DriveIos-Dev` / `DebugHarness` built for the simulator, installed on **iPad Pro 11" (M5)
at iOS 26.5** and **iPad Pro 11" (M4) at iOS 18.5**, launched with
`SIMCTL_CHILD_DRIVE_DEV_PROVISION_CONTROL=1` so the stub session lands straight in `PairedAppView`
over the fakes. `.tabViewStyle(.sidebarAdaptable)` was added to `PairedAppView` behind
`if #available(iOS 18.0, *)`, and a throwaway XCUITest drove the toggle. **All spike code was
reverted; nothing from it is in the tree.**

⚠ Two mechanical notes that cost time and will cost it again:
- Under `DebugHarness` the bundle id is **`app.tenshu.drive.ios.smoke`**, not `app.tenshu.drive.ios`
  (`project.yml:95`). Launching the latter fails with an opaque `FBSOpenApplicationServiceErrorDomain
  code=4` that names nothing.
- `simctl` cannot inject taps and `osascript` is denied assistive access on this machine, so
  **XCUITest is the only way to drive a control** in a spike. `drive-ios/UITests` already exists.

**F1 — `.sidebarAdaptable` composes with the legacy `.tabItem` API. No `Tab(…)` migration.**
Build green, and the sidebar renders with the right labels and SF Symbols on **both** runtimes. This
was D1's one contingency and it is resolved in D1's favour: adoption is a **one-line modifier per
app**, not a shell rewrite.

**F2 — ⚠⚠ but it does *not* default to a sidebar, and that is the finding that changes the plan.**
On both 26.5 and 18.5 the iPad still renders the tab bar; what `.sidebarAdaptable` adds is a system
**"Toggle sidebar"** button. The sidebar is **user-elected**, not size-class-driven. So the style
delivers *"iPad can have a sidebar"*, not *"iPad has a sidebar"* — which is not the same promise N6
was written to make. **N6-1 must decide explicitly** whether user election is the intended product
(defensible: it is what Apple ships, and it is the same "the size class picks the default, the user
overrides" posture N3/N4 landed on for Compose) or whether a regular-width default must be forced.
⚠ Do not discover this in N6-2 after six apps have adopted.

**F3 — the presentation is width-dependent, and only landscape is a true split.**

| Width | Presentation |
|---|---|
| 834 pt (11" portrait) | Floating **overlay** panel; content behind it is dimmed |
| 1210 pt (11" landscape) | **Persistent column** beside undimmed content |

**F4 — ⚠⚠ the toggle has no stable accessibility handle at all.** Three observed spellings of the
same control:

| Runtime | Placement | identifier | label |
|---|---|---|---|
| 26.5 | tab bar (collapsed) | `ToggleSideBar` | `Toggle sidebar` |
| 26.5 | sidebar (expanded) | `ToggleSidebar` ⚠ *different capital* | `Hide Sidebar` |
| 18.5 | tab bar (collapsed) | *(empty)* | `Toggle sidebar` |

⚠ **This corrects F4's own first draft**, which said "the label is stable on both, so match the
label" — true across *OS versions*, false across *states*: the label becomes `Hide Sidebar` once
expanded, and the identifier even changes case. A test that taps to expand and then looks for the
same handle to collapse will not find it. Match the label for the state you are in, re-query after
toggling, and never assert on the identifier. ⭐ Two spellings looked like a version quirk; the third
showed it is not a handle worth depending on.

⚠ The control also **moves**: top-right corner of the sidebar panel when expanded (y 100, above the
destination rows at y 159), inside the tab pill immediately left of the first destination when
collapsed. Position is not a selector either.

**F5 — the 26.5 sidebar is the floating/inset rounded panel** — i.e. exactly the iOS 26 look that
`ui-adaptive-navigation.md` §D6 records WWDC26 as having reversed to edge-to-edge. We get whatever
the system draws, for free, which is the strongest argument yet for D1 over a hand-rolled
`NavigationSplitView` and for D5's "hard-code nothing".

**F6 — ⚠ §0's premise was partly wrong: there is no bottom tab bar on iPad.** At iOS 26 the
`.tabItem` `TabView` already renders as a **floating pill at the top**. The defect is therefore not
"a phone's bottom bar in the wrong place" — it is the *absence of a sidebar* plus unused width. The
one-line summary at the head of this doc has been left as-is because "phone-shaped" is still true,
but nobody should go looking for a bottom bar to remove.

**F8 — ⚠⚠ F2 was only true of the bare style: `.defaultAdaptableTabBarPlacement(.sidebar)` exists,
compiles, and makes the sidebar the launch default** — verified on 26.5, no tap, toggle still
present. So "sidebar by default vs user-elected" is a **choice we make**, not a constraint the
framework imposes. Read F2 as "the *bare* style is user-elected"; the second half of D1 is
answerable after all.

**F9 — and Apple has a recommendation, so this is not purely per-app.** The HIG:
*"In an iPadOS app, consider using a sidebar instead of a tab bar, because a sidebar can display a
large number of items and can make navigating an iPad app more efficient"*
([HIG — Tab bars](https://developer.apple.com/design/human-interface-guidelines/tab-bars)). It also
carries a concrete follow-on we owe: **if the app uses a sidebar on iPad, switch filled SF Symbols
to their outlined variants in the sidebar.** ⚠ Drive is already outlined (`folder`, `person.2`) —
**Fit is not** (`house.fill`, `clock.fill`, `person.fill`, …), and neither is Control's mixed set.
That is per-app work N6-2 must carry, not a shell concern.

**F12 — ⭐⭐ the reference implementation settles it: Apple does NOT default to the sidebar in
portrait.** Apple's own **Files** app, launched on the *same* simulator at the *same* 834 pt portrait
width, renders the **tab bar pill at the top** (`Recents | Shared | Browse`) with the sidebar
collapsed and the content unobstructed — its toggle sitting left of the pill, exactly like ours.
That is the bare-`.sidebarAdaptable` behaviour of F2, not F8's forced default.

⭐ **Two things were being conflated, and separating them is the whole answer:**
- The **overlay-over-dimmed-content** presentation (F3) is *standard iPadOS* at this width when the
  user **opens** the sidebar. It is not a bug and not our layout's fault.
- **Launching with it already open** is what looks wrong — and that is F8's forced default, not the
  platform's behaviour.

⚠⚠ **So F10 option (2) is dead**, and it was the option a plain reading of the HIG's "consider using
a sidebar instead of a tab bar" would have led to. The HIG sentence is about *offering* a sidebar,
not about the launch state. **Checking the reference app cost one screenshot and overturned the
conclusion the guideline text alone produced.**

**F13 — ⭐⭐ and in LANDSCAPE, Files defaults to a persistent sidebar. Apple's behaviour is
WIDTH-CONDITIONAL.** Measured by hand (maintainer rotated the sim; `simctl io screenshot` stores the
raw framebuffer, so the file is portrait-shaped while the *content* is rotated 90° — read the picture,
not the pixel dimensions). At 1210 pt, Files shows a full sidebar — `Recents`, `Shared`, then a
**`Locations`** section (iCloud Drive, On My iPad, Recently Deleted) and a **`Tags`** section — with
the content pane beside it, undimmed. No user election happened: the sidebar was never toggled in
Files, so this is the system's width-driven default.

| Width | Apple Files defaults to |
|---|---|
| 834 pt (portrait) | tab bar, sidebar collapsed (F12) |
| 1210 pt (landscape) | **persistent sidebar** |

⚠⚠ **This restores F10 option (3) and retires the F12 revision to option (1).** Recording the whole
sequence because the failure mode is the interesting part — the recommendation moved **three times**:
(3) from the HIG text → (1) from a portrait-only screenshot → (3) from portrait **and** landscape.
⭐ **A single-orientation measurement is not a measurement of an adaptive layout.** It read as decisive
evidence both times and was wrong both times. The general form: when the thing under test is a
*function of* some variable, sampling it at one value of that variable produces confident nonsense.

⚠ Files is UIKit (`UISplitViewController`), not SwiftUI `.sidebarAdaptable`, so this establishes the
**platform convention we should match**, not that our modifier reproduces it. Whether
`defaultAdaptableTabBarPlacement` re-evaluates on rotation remains F10 option (3)'s untested ⚠ and is
**N6-1's first task**.

⭐ Also worth noting for Q4: Files' landscape sidebar carries **more than its tab items** — two extra
sections the tab bar never shows. All seven of our apps pass a flat 2–5 item list, so our sidebar will
look sparse beside the reference. That is the iOS twin of `ui-adaptive-navigation.md` Q4 ("the expanded
rail *can* reveal secondary destinations the collapsed one hides") and deserves a per-app answer after
N6-2, not before.

**F10 — ⚠ the real cost of defaulting to sidebar is PORTRAIT, and it is visible.** Because of F3,
"default to sidebar" at 834 pt means the user **launches into a dimmed listing behind a modal
sidebar** they must dismiss to read anything. Landscape is the good case (persistent column,
undimmed). So the honest options for N6-1 are three, not two:
  1. `.sidebarAdaptable` alone — tab bar by default, sidebar user-elected. No portrait regression;
     doesn't deliver the HIG's recommendation either.
  2. `+ .defaultAdaptableTabBarPlacement(.sidebar)` — sidebar always default. Best in landscape,
     **worst in portrait**.
  3. Compute the placement — sidebar default only above a width threshold (landscape-ish), tab bar
     below. Closest to both the HIG and N3/N4's "size class picks the default, the user overrides".
     ⚠ Untested: whether `defaultAdaptableTabBarPlacement` re-evaluates cleanly on rotation is
     exactly the kind of thing F2/F8 shows cannot be reasoned about. **Test it in N6-1.**
  ⭐ **Settled by F12 + F13: (3).** Apple's own Files is width-conditional — tab bar at 834 pt,
  persistent sidebar at 1210 pt — so (3) *is* the platform convention, not an embellishment on it.
  (2) is ruled out by F12 (nobody launches into a modal sidebar in portrait); (1) is ruled out by F13
  (landscape should not be left showing a tab bar the platform would have replaced).
  ⚠ **N6-1's first task is to establish that `defaultAdaptableTabBarPlacement` re-evaluates on
  rotation.** If it does not, the fallback is (1) — never (2). ⚠ See F13 on how this recommendation
  moved three times; do not re-open it from a single orientation.

**F11 — ⚠⚠ the accessibility shape changes with placement, and it breaks an existing test's
assumption.** Under tab-bar placement the destinations are `Button`; under **sidebar** placement they
are `Cell` (`identifier: 'folder', label: 'Files'`). §3 below previously reasoned that
`SharingClusterUITests.openSharedTab()` — `app.buttons["Shared"]` — would keep working because
"a sidebar row is still a button". **It is not.** The test survives only because it runs on iPhone,
where placement never changes. Any iPad UI test, and any future move of these tests onto an iPad
destination, must query placement-agnostically. ⭐ This is the doc's own "*expected* is not
*checked*" line coming true within one spike.

**F7 — a defect the sidebar will not fix, found by looking.** On an 11" iPad the listing renders four
rows full-bleed across 834 pt with the remaining ~70% of the window empty. That is a
content-layout/density question (column width, grid at regular width), entirely independent of the
navigation container. Out of scope for N6 — **but it is the more visible half of "this looks like a
phone app"**, and shipping N6 alone will not close the complaint that opened this work.

---

## 2.2 As built — N6-1 + N6-2 + N6-3 (2026-08-12)

**`ios-shell/`** — `AdaptiveAppShell` + `AppDestination` + `ShellPlacement`, plus `shell.yml`, on the
`ios-onboarding` pattern (compiled into each target; §D2). Seven consumers: Drive, Control, Fit,
Calendar, Home, Mia, Contacts. Timeline and Maps deliberately excluded (D4).

**The placement rule, and why it is a measured constant.** `ShellPlacement.forWidth` switches at
**1100 pt**. That number is bracketed, not guessed — Apple's Files defaults to a tab bar at 834 pt
(11" portrait) *and* at **1024 pt (13" portrait, measured in N6-3)*, and to a sidebar at 1210 pt
(11" landscape), so the real breakpoint lies in (1024, 1210] and 1100 is its midpoint. ⚠ It is **ours**;
the HIG publishes no value (D5).

⚠ Width is measured by a zero-sized `GeometryReader` in a `background` + a preference, **not** by
wrapping the `TabView` in a `GeometryReader` (which places its child at ideal size in the top-leading
corner and breaks the tab bar's safe-area behaviour) and **not** from `UIScreen` (wrong under Split
View — precisely the case where the answer differs).

**Verified by `AdaptiveShellUITests` on an iPad Pro 11" (M5), 4/4 green.** Two of them are the
reason the design holds:
- ⭐ `test_rotatingToLandscape_promotesToTheSidebar` — **`defaultAdaptableTabBarPlacement`
  re-evaluates when the measured width changes**, not only at first composition. This was N6-1's
  stated gate (§2.1 F10 option 3's ⚠); it passed, so no fallback was needed.
- ⭐ `test_navigationDepthSurvivesRotation` — a folder push survives a rotation. That is D1's whole
  argument made executable: one component that re-presents, not two swapped by a `when`.

⚠ The tests skip themselves on iPhone (`UIDevice.current.userInterfaceIdiom`), because there every
width is compact and all four would pass without exercising anything. **Run them on an iPad
destination** — the command is in the class KDoc.

**Symbols.** `AppDestination` carries an outline/filled pair; the shell picks outline for the sidebar
and filled for the tab bar, per the HIG rule found in §2.1 F9. Fit was filled-only (`house.fill`,
`clock.fill`, `person.fill`, …) and Control mixed; both now pass pairs.

**Fit's ordinals became ids.** Its `selectedTab: Int` with `.tag(0…4)` and jumps written
`selectedTab = 2` / `= 4` is now `selection: String` with `"record"` / `"profile"`. ⚠ The old form was
positional — `4` meant Profile only while Profile stayed fifth — so inserting a destination would
have silently redirected a jump.

### 2.2.1 D6 was already done by **the calendar compile-gate fix**, and this track re-did it

**D6 is withdrawn — it carries no code.** While this work was in progress,
the change *"fix(ios): add calendar-ios to the compile gate — it had never compiled"*
(`7c729329`) landed on master and did all of it: added `calendar` to `ios-smoke.sh`'s `spec_for` and
its `--build-only` default list, and fixed the same two files. This branch reached the same place
independently a few hours later and was rebased onto that fix with its duplicate changes dropped.

⚠ The two defects are recorded here anyway, because the *diagnosis* is reusable even though the fix
belongs to that fix — and because both are SKIE-spelling traps of the kind
`feedback_ios_xcodebuild_swift_slices` collects:

| File | Defect |
|---|---|
| `EventEditorScreen.swift` (×2) | `RecurrencePresetKt.label(x)` — the plain Obj-C file-class spelling. `:calendar-shared-logic` *is* exported; SKIE bridges a Kotlin extension on a bridged enum as a **Swift extension method**, so it is `x.label()`. |
| `RemindersPermissionSection.swift` (×2) | `granted?.boolValue == true` — optional chaining on a **non-optional** `KotlinBoolean`. The Kotlin callback's parameter stopped being nullable and no gate recompiled the Swift. |

⭐ Independently arriving at byte-identical fixes is mild evidence they were the right ones. It is
much stronger evidence of something else.

⚠⚠ **The lesson is not about Calendar, it is about not checking.** This track went from a written
plan straight to building without re-reading what had landed in the meantime, and burned a full
rebase on work someone else had already merged. `feedback_check_open_prs_before_diagnosing` exists
for exactly this and was not followed. ⭐ A plan written against a base is stale the moment the base
moves — **re-check open and recently-merged PRs at the start of implementation, not only at the start
of design.** The cost here was small because the overlap was mechanical; had that fix chosen a different
fix, the merge would have been a genuine mess.

⚠ What survives from D6: nothing to do. `ios-smoke.sh` is untouched by this branch.

---

## 3. Testing — the gate cannot see this defect either

Same shape as `ui-adaptive-navigation.md` §3, for the same reason. `ios-smoke.sh --build-only`
compiles against `generic/platform=iOS Simulator`; a phone-shaped iPad is not a compile error and not
a crash. What it *does* catch is the thing that actually breaks here — a Swift API used outside its
availability window, which is precisely what a `.sidebarAdaptable` adoption risks.

So the evidence is two-layer:

- **`ios-smoke.sh --build-only`** (all **nine** apps — `calendar` landed with N6-3) — the availability
  and API-shape gate. ⚠ Reassemble the XCFramework first; the script does.
- **`AdaptiveShellUITests` on an iPad destination** — the placement assertions (§2.2). ⚠⚠ **Not wired
  into any script**, because `ios-smoke.sh` has no test mode and no iPad in its device table; it is a
  command in the class KDoc that a human has to run. That is a known hole, not an oversight — see Q-E.
  ⚠⚠ **`-only-testing` with a class the project does not contain exits 0 and prints `** TEST
  SUCCEEDED **`.** It happened during N6-1: the test file was created after the last `xcodegen
  generate`, so it was in no target, and the run "passed" having executed **0 tests**. Only
  `xcresulttool get test-results summary` (`totalTestCount`) showed it. ⭐ After adding a test file,
  regenerate *then* confirm the case count — a green xcodebuild is not evidence a test ran.
- **An iPad simulator launch + screenshot per app** — the only thing that can see the defect. G8 says
  this machine can do it. Boot `iPad Pro 11-inch (M4)`, install, launch, `xcrun simctl io … screenshot`,
  in both orientations. ⚠ Check **Split View at ⅓ width** too: that is compact horizontal size class
  on an iPad, so it must fall back to the tab bar — and it is the case a rotation-only check misses.
- ⚠⚠ **`drive-ios/UITests` has 5 pre-existing failures on iPhone, and they are NOT N6's** — measured
  by running the full suite with and without the N6 `PairedAppView`: **identical** results, 8 passed /
  5 failed / 4 skipped both ways. Three are `UploadConflictUITests` ("the picker did not dismiss after
  selecting a photo" — the system PHPicker); two are the `SharingClusterUITests` rename pair.
  ⚠ The rename pair is **flaky, not broken**: it passes 4/4 in isolation and fails inside the
  17-test suite, so it is suite-order or simulator-state dependent. Someone should own that
  separately; it is why this section cannot say "the suite is green".

  ⭐⭐ **Method note, because getting this wrong is easy and I did.** The first isolation run — one
  sample with the change, one without — showed pass-without / fail-with, which reads as a clean
  bisect and is how a flaky test frames an innocent change. **One sample per arm cannot distinguish a
  regression from flakiness.** What settled it was running the *whole suite* both ways and comparing
  the failure sets.
- **`drive-ios/UITests`** — `SharingClusterUITests.openSharedTab()` taps `app.buttons["Shared"]`.
  ⚠⚠ **Corrected by §2.1 F11:** under sidebar placement that element is a `Cell`, not a `Button`, so
  the query does *not* survive the change — it survives only because these tests run on iPhone, where
  placement never changes. Do not move them to an iPad destination without making the query
  placement-agnostic. (This bullet previously reasoned the opposite from "a sidebar row is still a
  button"; the spike falsified it in an afternoon. ⭐ *Expected* is not *checked*.)

No Kotlin test is relevant: N6 changes no Kotlin. See §4.

---

## 4. Blast radius — what this track does and does not touch

**Touches:** `*-ios/Sources/*.swift` (seven apps), a new `ios-shell/`, seven `project.yml`s (one
`include` + one `templates` entry each), this doc + `ui-adaptive-navigation.md`. ⚠ **Not**
`scripts/ios-smoke.sh` — D6 was withdrawn once the calendar compile-gate fix landed the same change (§2.2.1).

**Does not touch:** any Kotlin. Not `ui-core`, not `*-shared-ui`, not `*-shared-logic`, not
`tenshu-sdk-ios`, not `commonMain` / `androidMain` / `desktopMain` / `iosMain`. No schema, no wire
shape, no service version, no `TENSHU_STORAGE_FORMAT_VERSION`, no relay.

Two consequences worth stating because they are easy to get wrong in both directions:

- ⚠ **House rule #8 does not fire.** It gates PRs that change `androidMain`, `:control`, or
  `:drive-android`. N6 changes none of them, so `scripts/android-smoke.sh` is **not** owed. (The Kotlin
  gate `./gradlew desktopTest jvmTest test` is likewise not owed — but it is also nearly free to run,
  and the standing lesson from `feedback_gates_that_did_not_check` is that "I didn't touch Kotlin" is a
  claim. Run it once at the end of the track, not per phase.)
- ⚠ **N6 shares no code with N1–N5 and should not be made to.** `ui-core`'s five-value
  `WindowWidthSizeClass` is read by Compose to *render chrome*. iOS chrome is SwiftUI reading
  `@Environment(\.horizontalSizeClass)`. A shared Kotlin size-class enum would have to be exported
  through the SDK and read by Swift to decide something Kotlin does not draw — cost with no consumer.
  The two tracks agree on *policy* (wide → sidebar, narrow → bar) and share nothing else; keeping them
  in one doc is what made N6 look gated on N1–N5 when it never was.

---

## 5. Open questions

- ~~**Q-A** — should the deployment floor rise from 16.0 to 18 for `.sidebarAdaptable`?~~
  **Answered no (2026-08-12): stay at 16.0 and availability-gate.** Recorded because the question is
  natural, will be asked again, and has three separate reasons not to.

  1. ⭐ **It is not a trade.** Availability-gating loses *nobody*: iOS 16/17 keeps today's tab bar,
     18+ gets the sidebar. Raising the floor was never required to get the feature — it only deletes
     a branch. The framing "lose users for a UI change" describes a choice nobody has to make.
  2. **The branch is cheap by construction.** D2 puts the shell in one shared `ios-shell/` file, so
     the `if #available(iOS 18, *)` exists **once**, not once per app. Paying one branch in one file
     for the pre-18 tail is not a close call. ⚠ This is only true *because* of D2 — the answer would
     be genuinely arguable if seven apps each carried their own copy.
  3. **The tail is ~7% and there are zero users today.** Apple's App Store figures, June 2026: iOS 26
     on 79% of iPhones, iOS 18 on 14% → ~93% on 18+. And Tenshu is not on the Store —
     `TENSHU_CONTROL_APP_STORE_ID` is `""` and P7 (signing / TestFlight / App Attest) is still open in
     `ios-drive-parity.md`. If the floor ever should move, that is a ship-time call against real
     numbers.

  ⚠⚠ **The floor is not an App Store requirement, and the two are one letter apart in conversation.**
  Apple mandates the SDK an app is **built with** — from **2026-04-28**, uploads to App Store Connect
  must be built with Xcode 26+ against the **iOS 26** SDK
  ([developer.apple.com/news/upcoming-requirements](https://www.developer.apple.com/news/upcoming-requirements/)).
  We already satisfy that (G5: Xcode 26.6 / SDK 26.5). Apple imposes **no minimum deployment
  target**: `IPHONEOS_DEPLOYMENT_TARGET = 16.0` builds against the iOS 26 SDK and ships unchanged.
  Building with a newer SDK does not drop older-OS support by itself. ⚠ G4's Calendar comment pins 16
  for `UICalendarView`, so nothing else is pushing the floor either.
- **Q-B — iOS 27.** Not buildable here (G5). The plan is version-agnostic by construction (D5), so this
  is a re-look when an Xcode 27 lands, not a blocker. Someone should confirm the WWDC26 sidebar
  reversal against the real SDK before `ui-adaptive-navigation.md` §D6's ⚠ is treated as settled.
- **Q-C — Mac.** N6's brief says "iPad/Mac"; there is no Mac target (G6). Confirm iPad-only is the
  intent, or the Mac half needs a home (Compose Desktop today, and ADR 0001 §3 owns that).
- **Q-D — §0.1's ⭐.** Why does Calendar have tabs on iOS and none on desktop, and Timeline the
  reverse? Out of scope, but it is now written down twice.
- **Q-E (new, from N6-2) — `AdaptiveShellUITests` is in no automated gate.** `ios-smoke.sh` compiles;
  it does not run tests, and its device table has no iPad. So the only check that can see this
  feature is a command a human must remember. Options: a `--ui-test` mode on `ios-smoke.sh` against a
  named iPad simulator, or leaving it as a documented pre-merge step for PRs touching `ios-shell/`.
  ⚠ Whichever is chosen, note that CI has been dark since 2026-06-30, so "add it to CI" is not
  currently an answer.
- **Q-F (new, from N6-2) — the sidebar is sparse.** Files' landscape sidebar carries `Locations` and
  `Tags` sections beyond its tab items (§2.1 F13); all seven of our apps pass a flat 2–5 item list, so
  the sidebar is a taller copy of the tab bar and nothing more. The iOS twin of
  `ui-adaptive-navigation.md` Q4. Worth a per-app answer now that the shell can carry one — Drive's
  `Locations`-equivalent (Recents / Shared / a folder favourites list) is the obvious first.
