# Adaptive navigation — the sidebar is 300 dp wide whatever you put in it

**Status:** v0.5 — sweep + design (2026-08-11), toggle correction (2026-08-12). **N1–N5 SHIPPED.**
The permanent drawer is gone;
every non-compact class renders one `WideNavigationRail` (96 dp collapsed, 220–360 dp content-sized
expanded), the expand/collapse choice persists per desktop app, and the body pane finally carries
the Material 24 dp window margin. As-built in §2. ⚠⚠ **§7 revises N3/N4:** the menu toggle moved
from Expanded to **Large and above**, because N3's stated reason for lowering it was based on a
false premise about the collapsed rail (it does show labels). Read §7 before touching the header.
Remaining: **N7** (§7.4, secondary destinations + a header FAB — the prerequisite for ever
lowering the toggle again) and open question **Q2** (the compact margin, still unverified).
**N6 SHIPPED** as its own doc — [`ios-adaptive-navigation.md`](ios-adaptive-navigation.md). §9: the opt-in floating compact bar (Photos).
Proposed replacing the permanent
navigation drawer in `AdaptiveNavScaffold` with the collapsed/expanded navigation rail that
Material 3 now specifies in its place, adding the two window size classes the current three-class
model is missing, and giving desktop windows the window-edge margin they have never had.

Companion to [ADR 0001](../adr/0001-design-system-direction.md) §3 (which specified the current
`compact → bar, medium → rail, expanded → drawer` mapping and is the direct origin of the defect),
[`design-improvement-plan.md`](design-improvement-plan.md) Phase 7 (M3 Expressive, deferred on a
blocker that has since expired) and [`ui-core/ui-agent.md`](../../ui-core/ui-agent.md) §8.

**Trigger (2026-08-11):** maintainer observation that the desktop sidebar "is always displayed with
the same length, even if all the texts in the row items are very short." That is correct, it is
measurable, and it turns out to be the visible symptom of a spec that moved underneath us: the
component we render at the default desktop window size was deprecated by Material 3, and the
breakpoint model grew from three classes to five.

---

## 0. The sweep

### 0.1 There is exactly one side-navigation implementation

`ui-core/src/commonMain/kotlin/app/tenshu/ui/layout/AdaptiveNavScaffold.kt` (348 lines) is the whole
of it. No per-app sidebars, no second drawer, no custom rail anywhere in the repo. It branches three
ways on `LocalWindowWidthSizeClass` (`AdaptiveNavScaffold.kt:149`):

| Branch | Lines | Chrome |
|---|---|---|
| `Compact` | 168–193 | `Scaffold(bottomBar = { NavigationBar })` |
| `Medium` | 195–220 | `Row { NavigationRail(fillMaxHeight) … Box(weight 1f) }` |
| `Expanded` | 222–253 | `PermanentNavigationDrawer` + `PermanentDrawerSheet` wrapping an inner `Scaffold` |

Eight apps consume it identically — Drive (`drive-shared-ui/.../App.kt:227`), Control
(`control-shared/.../App.kt:205`), Fit (`fit-shared-ui/.../App.kt:151`), Timeline
(`timeline-shared-ui/.../App.kt:144`), Home (`home-shared-ui/.../App.kt:76`), Mia
(`mia-shared-ui/.../App.kt:76`), Contacts (`contacts-shared-ui/.../App.kt:119`). Maps
(`maps-shared-ui/.../App.kt:52-59`) and Calendar (`calendar-shared-ui/.../App.kt:85-106`) have no
nav chrome at all and are unaffected by everything below.

**One file to fix; one file that breaks eight apps at once.** That asymmetry sets the testing bar in
§6.

### 0.2 The drawer is 300 dp regardless of its content — measured, not reasoned

`AdaptiveNavScaffold.kt:227` sets `Modifier.widthIn(min = 240.dp, max = 300.dp)`. The minimum is
unreachable: `NavigationDrawerItem` applies `fillMaxWidth()` internally, so the sheet always takes
the maximum of its incoming constraints.

A throwaway probe (rendered via `runDesktopComposeUiTest`, tagged the content pane, read
`boundsInRoot`) confirms the content pane's left edge:

| Window | Labels | `content.left` |
|---|---|---|
| 1024 px | `Home` / `Files` / `Fit` | **300.0 px** |
| 1024 px | `Home` / `Shared with me` / `Recently deleted` | **300.0 px** |
| 1600 px | `Home` / `Files` / `Fit` | **300.0 px** |
| 800 px (Medium → rail) | `Home` / `Files` / `Fit` | 80.0 px |

The width is content-independent by construction. Short labels cannot shrink it and long labels
cannot grow it. The rail branch measures at the M3 default 80 dp, as expected.

The probe was deliberately **not** committed — §6 proposes the permanent version.

### 0.3 What the specs say now

**Material 3 deprecated the navigation drawer.** *"[The navigation drawer] is no longer
recommended… use an expanded navigation rail, which has mostly the same functionality of the
navigation drawer and adapts better across breakpoints"*
([m3.material.io/components/navigation-drawer](https://m3.material.io/components/navigation-drawer)).
The collapsed↔expanded rail is the official replacement: two variants that *"transform into each
other when the menu button is selected"*, with the expanded variant able to reveal secondary
destinations the collapsed one hides.

**The breakpoint model is five classes, not three.** Verified in
[`WindowSizeClass.kt`](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/window/window-core/src/commonMain/kotlin/androidx/window/core/layout/WindowSizeClass.kt):
`MEDIUM=600`, `EXPANDED=840`, `LARGE=1200`, `EXTRA_LARGE=1600`.

| Class | Width | M3 specifies | We render |
|---|---|---|---|
| Compact | < 600 | Navigation bar | Navigation bar ✅ |
| Medium | 600–839 | Collapsed rail | Rail, 80 dp ✅ |
| Expanded | 840–1199 | **Collapsed rail** | **300 dp drawer** ❌ |
| Large | 1200–1599 | **Expanded rail** | 300 dp drawer ❌ |
| Extra-large | ≥ 1600 | **Expanded rail** | 300 dp drawer ❌ |

**This is why the default window looks wrong.** `WindowStatePersistence.kt:48-53` defaults desktop
windows to **1024 × 768**, which lands in Expanded — where M3 now wants a 96 dp icon-only rail and
we paint a 300 dp drawer. Drive, Control, Fit, Timeline and Mia all open at that size
(`rememberPersistedWindowState`); Contacts and Maps use Compose's 800 × 600 default and land in
Medium, which is why their rail has never looked wrong.

> ⚠ **Superseded 2026-09-16:** every desktop app now uses `rememberPersistedWindowState`, and a first
> launch opens centred at a screen-relative size (80 % × 90 % of the usable area, clamped to
> 1024 × 768 … 1680 × 1050) — 1382 × 884 on a 16" MacBook, which is **Large**, so the rail starts
> expanded there. The rule lives in `ui-core/ui-agent.md` §8.

**Widths**, from the M3 design tokens in AOSP (the spec site is JS-rendered and unfetchable; the
tokens are the authoritative implementation):

| Measure | Value | Token |
|---|---|---|
| Classic `NavigationRail` | 80 dp | `NavigationRailCollapsedTokens.NarrowContainerWidth` |
| `WideNavigationRail` collapsed | **96 dp** | `NavigationRailCollapsedTokens.ContainerWidth` |
| Expanded rail, min | **220 dp** | `NavigationRailExpandedTokens.ContainerWidthMinimum` |
| Expanded rail, max | **360 dp** | `NavigationRailExpandedTokens.ContainerWidthMaximum` |
| Rail top space | 44 dp | `NavigationRailCollapsedTokens.TopSpace` |
| Icon column (leading edge of any rail icon) | **36 dp** | `ItemHorizontalPadding` 20 + `NavigationRailHorizontalItemTokens.FullWidthLeadingSpace` 16 |
| Header start inset | **24 dp** | not a token — see §0.3.1 |
| Drawer width | 360 dp | `NavigationDrawerTokens.ContainerWidth` |

⭐ **The load-bearing property:** the expanded rail is width-*ranged*, not fixed — it sizes to its
widest item, clamped to 220–360 dp. That is precisely the behaviour the current drawer lacks, and
adopting it fixes the reported symptom as a side effect rather than by tuning a number.

**Margins.** M3 specifies 24 dp margins and a 24 dp pane spacer at medium and above
([breakpoints/medium](https://m3.material.io/foundations/layout/breakpoints/medium)). The Compose
implementation pins the pane spacer at 0 dp for compact/medium and 24 dp for expanded and above
([`PaneScaffoldDirective.kt`](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/material3/adaptive/adaptive-layout/src/commonMain/kotlin/androidx/compose/material3/adaptive/layout/PaneScaffoldDirective.kt)).
Rail-to-body spacing is **zero by design** — M3 models rails as the window perimeter, *"placed
outside any panes, always along the leading edge"*
([scaffold/rails](https://m3.material.io/foundations/layout/scaffold/rails)); the rail's own
container carries the padding.

### 0.3.1 The header is unpadded by the component, and 24 dp is the caller's job

⚠⚠ **`WideNavigationRail` places its `header` placeable at `x = 0` and pads it not at all**
(`WideNavigationRail.kt`: `headerPlaceable.placeRelative(0, 0)`), while every *item* is a full-width
cell that positions its icon at the 36 dp column above. An unpadded `IconButton` header therefore
renders its 24 dp icon at **12 dp** — 24 dp left of the icons it heads. That is the whole of the
"the menu button looks way too far left" report; nothing about it is a theme or a size-class bug.

**Why 24 dp and not something else.** An `IconButton` is 48 dp with its icon centred, so the icon
sits 12 dp inside it; landing on the 36 dp column needs `36 − 12 = 24`. ⭐ The number works in
*both* rail states, which is what makes one static padding sufficient: collapsed, the icon offset is
`ItemHorizontalPadding` (20) + `(ActiveIndicatorWidth 56 − IconSize 24) / 2` (16); expanded, it is
the same 20 + `FullWidthLeadingSpace` (16). It is also exactly what every androidx
`WideNavigationRail` sample passes — `Modifier.padding(start = 24.dp)` on the header `IconButton`,
in all four of them (`NavigationRailSamples.kt`) — so this is the reference spelling, not a
local tuning value.

⚠ Do not reach for `Alignment.CenterHorizontally` or a `fillMaxWidth` header instead. The rail
measures the header with loose constraints and sizes the *expanded* rail to
`maxOf(widestItem, headerWidth)`, so a header that fills the width feeds its own width back into
the rail's width.

### 0.4 What we have today at the window edge: nothing

`AdaptiveNavScaffold.kt:146` defaults `contentWindowInsets` to `WindowInsets(0, 0, 0, 0)`, and **no
desktop `Main.kt` in the repo touches `WindowInsets` at all**. The complete `WindowInsets` inventory
is six files, all shared UI, all about Android system bars. So on desktop the drawer sits flush at
x = 0 and body content is inset only by whatever each screen improvises from
`MaterialTheme.spacing.*`. There is no window-edge margin anywhere, at any size class.

Three apps pass `contentWindowInsets = WindowInsets.systemBars` (Fit `App.kt:157`, Home
`App.kt:81`, Mia `App.kt:79`). Those are no-ops on desktop and must not be confused with a margin.

⚠ `ui-agent.md` §8 documents a real trap here: overriding `contentWindowInsets` *and* having top
chrome doubles the inset, and Contacts carries a comment (`App.kt:110-118`) explaining why it
declines the override. Any margin work must go in as a *layout* margin, not as an inset override,
or it will re-open that bug.

---

## 1. Decisions

### D1 — Replace the permanent drawer with a collapsible wide rail

Adopt M3's stated replacement. The drawer branch goes away entirely; no size class renders a drawer.

**Why not just widen/narrow the drawer:** tuning `widthIn` would fix the reported symptom while
leaving us on a component its own design system has retired, and would still show a text sidebar at
840 dp where the spec wants icons. The expanded rail's content-sized width means the symptom cannot
recur.

### D2 — Grow `WindowWidthSizeClass` from three values to five

Add `Large` (≥ 1200) and `ExtraLarge` (≥ 1600) to
`ui-core/src/commonMain/kotlin/app/tenshu/ui/layout/WindowWidthSizeClass.kt:38-42`.

**Keep our own enum.** The comment at `WindowWidthSizeClass.kt:24-27` — *"intentionally a small enum
rather than a wrapper around `androidx.compose.material3.windowsizeclass.WindowSizeClass`, because
that artifact is Android-only"* — is still correct for `material3-window-size-class`. We need two
constants, not a dependency.

⚠ This is a source-incompatible change to a public `enum class`: every exhaustive `when` over it
must gain two branches. Grep before landing — `AdaptiveNavScaffold.kt:167` and
`ContentWidth.kt` are the known ones, but `LocalWindowWidthSizeClass` is public API of `ui-core`.

⚠⚠ **The dangerous site is not the `when`, it is the `==`.** A non-exhaustive equality check
compiles fine and just stops firing. `ContentWidth.kt:82` reads `sizeClass ==
WindowWidthSizeClass.Expanded`, so adding wider classes above it drops the 840 dp reading cap on
exactly the widest windows that need it. Every such comparison must become `>=`
(enums are `Comparable` by ordinal, so the declaration order becomes load-bearing).

**As built in N1 —** the repo turned out to have one accidental net here, and it is worth recording
because it is easy to over-learn from: reverting the comparison to `==` fails exactly one test,
`ContentWidthOrderTest.capBeforeFillMaxSize_caps`, and only because that test happens to render at
1440 dp — a width this change moves from `Expanded` to `Large`. Had it picked 1000 dp the
regression would have been silent. So: the existing suite is not coverage of this class of bug, and
the ⚠⚠ above stands.

### D3 — Target mapping

| Class | Chrome | Width |
|---|---|---|
| Compact | Navigation bar (bottom) | — |
| Medium | Wide rail, collapsed | 96 dp |
| Expanded | Wide rail, collapsed | 96 dp |
| Large | Wide rail, **expanded** | 220–360 dp, content-sized |
| Extra-large | Wide rail, **expanded** | 220–360 dp, content-sized |

Plus a **user-operable menu-button toggle** at Large and above, per M3's *"should always open from a
menu icon"*. The size class picks the *default* state; the toggle overrides it for the session.

**Open — Q1:** should the toggle state persist across launches? `WindowStatePersistence` already
exists and would make this cheap. Deferred to N4; not a blocker for N1–N3.

### D4 — `NavigationSuiteScaffold` is available and multiplatform, but does not solve this by itself

`org.jetbrains.compose.material3:material3-adaptive-navigation-suite` publishes `-desktop`,
`-iosarm64`, `-iossimulatorarm64`, `-js` and `-wasm-js` targets; CMP 1.11.1 (our version) pins
1.11.0-alpha07, and the multiplatform tracking issue
([compose-multiplatform issue 4952](https://github.com/JetBrains/compose-multiplatform/issues/4952)) is closed.
`WideNavigationRail` and `ShortNavigationBar` went **stable in material3 1.4.0** — which is the
`material3` version already in `gradle/libs.versions.toml:24`.

⚠⚠ **But `NavigationSuiteScaffoldDefaults.navigationSuiteType()` never auto-selects the expanded
rail and never selects a drawer.** Compact width → `ShortNavigationBarCompact`; tabletop posture or
compact height → `ShortNavigationBarMedium`; **everything else → `WideNavigationRailCollapsed`**.
Adopting the scaffold and trusting its default would trade one wrong fixed width (300 dp drawer at
every size) for another (96 dp collapsed rail at every size, including a 2560 px window). The
`navigationSuiteType` must be supplied by us from D3's table either way.

**Recommendation:** adopt it in N3 as the rendering spine — it gets us the stable `WideNavigationRail`
without hand-rolling the collapsed/expanded transition — but treat D2's size-class table as the
authority for *which* type, and keep `AdaptiveNavScaffold` as the public `ui-core` surface so the
eight call sites don't change. This also preserves the option to drop the dependency later.

### D5 — Window-edge margin as a layout token, not an inset override

Introduce a size-class-derived window margin in `ui-core` (0 dp compact per Q2, 24 dp medium and
above) and apply it to the body pane inside `AdaptiveNavScaffold`, **not** via `contentWindowInsets`
and **not** per screen. The rail keeps its flush leading edge per M3's perimeter model.

⚠ **Q2 — unverified:** the commonly-cited **16 dp compact margin could not be confirmed from a
primary source.** m3.material.io is fully JS-rendered; search snippets confirmed 24 dp for medium but
never surfaced a compact figure, and the Android codelab explicitly declines to give per-class
values. Do not write 16 dp into the token layer citing M3. Either ship compact at the existing
per-screen spacing (recommended — it changes nothing on phones) or find a primary source first.

### D6 — iOS is a separate track, and the premise about Compose there is wrong

The question that prompted this doc included *"Compose also supports more native Apple style when
apps run on iOS, with Liquid Glass."* It does not.

- **CMP renders Material 3 on iOS via Skia into a `UIView`.** It ships zero Cupertino-styled
  widgets, and nothing on the KMP roadmap suggests a native widget set is coming. What it adapts is
  *behaviour*, not appearance: scroll physics, back-swipe, safe areas, native text-selection
  pop-ups, and — new in 1.11.1 — opt-in native text input (`usingNativeTextInput(true)`).
- **`compose-cupertino` is effectively dead.** Last Maven release `0.1.0-alpha04`, 2024-04-07,
  pinned to CMP 1.6.1 / Kotlin 1.9.23. The maintained-ish fork
  (`io.github.schott12521:cupertino*`) says "not officially supported" in its own README.
- **There is no Liquid Glass support in CMP, and JetBrains says so.** Their own tutorial
  ([Liquid Glass on iOS](https://kotlinlang.org/docs/multiplatform/ios-liquid-glass.html))
  concludes you need a native SwiftUI shell, because Liquid Glass is rendered by the *system*
  through native `TabView` / `NavigationStack` / toolbar APIs.

⭐ **This costs us nothing, because our iOS apps are already 100 % SwiftUI.** There is zero
`ComposeUIViewController` in the repo; Kotlin reaches iOS only as logic through
`tenshu-sdk-ios/.../\*Surface.kt`. We are already on the only architecture that *can* get Liquid
Glass. ADR 0001 §4 ("Material with iOS accommodations", not Cupertino) is unchanged by this doc and
is strengthened by it.

**The real iOS gap is a different one:** every iOS app navigates with SwiftUI `TabView` +
`NavigationStack` (`drive-ios/.../PairedAppView.swift:18-49`, `calendar-ios/.../CalendarIosApp.swift:103-110`,
`timeline-ios/.../TimelineIosApp.swift:164`, `contacts-ios/.../ContactsIosApp.swift:164`,
`mia-ios/.../MiaIosApp.swift:28-33`). **`NavigationSplitView` appears nowhere in the Swift sources** —
the only mention in the repo is aspirational prose at `milestones.md:258`. On iPad that means a tab
bar where a sidebar belongs. Tracked as N5, gated behind the Compose work, and it belongs to
[`ios-drive-parity.md`](ios-drive-parity.md)'s family of tracks rather than this one.

⚠ **Correction worth recording, because two docs are already one release behind:** WWDC26 shipped
iOS/iPadOS/macOS **27**, which *reversed* the iOS 26 sidebar look. Sidebars now *"extend to the
window's edges"* with content flowing behind them, and sidebar icons *"regain their color"* using
the app's accent color (Platforms State of the Union + "Modernize your AppKit app", WWDC26). macOS
26 had specified the opposite — inset, floating. **Building a floating/inset sidebar today copies
last year's system look.** The HIG page has not caught up and still says sidebars "can float above
content"; trust the sessions. `ios-drive-parity.md:773,1222-1225` should be re-read against this
before anyone acts on its Liquid Glass notes.

⚠ **Do not quote Apple sidebar widths.** The HIG states none — the Sidebars, Layout and Split views
pages contain zero pt values. The only figure is an API default
(`maximumPrimaryColumnWidth` "corresponds to a minimum width of 320 points"), and
`navigationSplitViewColumnWidth`'s 150/200/400 are sample code. On macOS, row height and glyph size
follow the *user's* General-settings sidebar icon size and must not be hard-coded.

---

## 2. Phase map

| Phase | Scope | Gate |
|---|---|---|
| **N1** ✅ | `WindowWidthSizeClass` grows to five values; `ProvideWindowWidthSizeClass` computes them; fix every exhaustive `when`. No visual change — Large/XL fall through to the existing Expanded branch. | `:ui-core:desktopTest` + `assemble` |
| **N2** ✅ | Commit the width probe as a real regression test (§6). Pins today's behaviour *before* changing it, so N3's diff is legible. | `:ui-core:desktopTest` |
| **N3** ✅ | Replace the drawer branch with the collapsed/expanded `WideNavigationRail` per D3/D4. `AdaptiveNavScaffold`'s signature is unchanged, so no call site moves. | `desktopTest jvmTest test` + `scripts/android-smoke.sh` |
| **N4** ✅ | The menu-button toggle + Q1 persistence. | as N3 |
| **N5** ✅ | Window-edge margin token (D5), pending Q2. | as N3 |
| **N5b** ✅ | The header's 24 dp start inset, so the menu icon shares the destinations' icon column (§0.3.1). | `:ui-core:desktopTest` |
| **N6** ✅ | *iOS only, separate track — **shipped in its own doc**, [`ios-adaptive-navigation.md`](ios-adaptive-navigation.md).* Independent of N1–N5 and sharing no code with them. ⚠ Two premises in this row were wrong and are corrected there: the container is `.tabViewStyle(.sidebarAdaptable)`, not `NavigationSplitView` (the seven iOS shells are tab-shaped, not list-detail), and there is **no Mac SwiftUI target** to adapt. ⚠ The "27 edge-to-edge sidebar" is not buildable on the installed toolchain (Xcode 26.6 / SDK 26.5). | `ios-smoke.sh --build-only` + `AdaptiveShellUITests` on an iPad |

N1 and N2 are behaviour-preserving and can land together. N3 is the one that changes eight apps.

### As built — N1 + N2 (2026-08-11)

- `WindowWidthSizeClass.kt` — five constants, `fromWidth` at 600/840/1200/1600, and a KDoc note
  making the `>=`-not-`==` rule and the declaration ordering explicit.
- `ContentWidth.kt:85` — `==` → `>=`, so the 840 dp reading cap survives at Large/ExtraLarge.
- `AdaptiveNavScaffold.kt:222` — `Expanded, Large, ExtraLarge` share the drawer branch, with a
  comment naming N3 as the split point. **Zero visual change**, by construction.
- `WindowWidthSizeClassTest` — the eleven breakpoint cases, the constant ordering (which `>=`
  silently depends on), and the expanded-or-wider idiom.
- `AdaptiveNavMetricsTest` — the §0.2 probe as a fixture: 0 dp compact, 80 dp medium rail, 300 dp
  drawer at 1024/1280/1700, drawer width independent of label length, and body flush to the
  trailing edge at every size class (pinning the §0.4 missing margin for N5).
- `ui-core/ui-agent.md` §8 — updated for the five classes and the `>=` rule.

⚠ Two `AdaptiveNavMetricsTest` assertions were written to be **rewritten**, not preserved:
`expandedAndAbove_drawerIsAlways300dp` disappears in N3, and `contentIsIndependentOfLabelLength`
flips once the width-ranged expanded rail lands. Their KDoc says so, so nobody
"fixes" N3 by making them pass.

### As built — N3 (2026-08-11)

**One component, not three.** Every non-compact class renders the same `WideNavigationRail`; the
size class only chooses its initial `WideNavigationRailValue`. A window dragged across a breakpoint
therefore animates rather than swapping components — which the old three-branch `when` could not do.

| Class | Chrome | Measured body offset |
|---|---|---|
| Compact | `NavigationBar` (bottom) | 0 dp |
| Medium / Expanded | rail, collapsed | **96 dp** (was 80 dp medium / **300 dp** expanded) |
| Large / ExtraLarge | rail, expanded | **220 dp** floor, **331 dp** with a 31-char label |

- `AdaptiveNavScaffold.kt` — the `Medium` and `Expanded` branches collapsed into one arm;
  `PermanentNavigationDrawer` / `PermanentDrawerSheet` / `NavigationDrawerItem` / `NavigationRail`
  are gone from the file. `railValueFor(sizeClass)` is the default-state rule; a
  `LaunchedEffect(sizeClass)` re-applies it only when the window crosses a breakpoint, so a manual
  toggle survives every other recomposition.
- **Signature unchanged** — all eight call sites compile untouched, as D4 required.
- `AdaptiveNavMetricsTest` — 7 tests including the toggle interaction (click → animate → 220 dp,
  and the content description flipping to "Collapse navigation").
- `AdaptiveNavRenderTest` — writes six PNGs to `build/reports/adaptive-nav/`. Not a golden gate.

**Refinement to D3, made during implementation.** D3 put the toggle at "Large and above". As built
it is at **Expanded and above**, because D3's literal reading leaves the *default desktop window*
(1024 dp) icon-only with no way to reach labels at all — a regression against the drawer it
replaces. Medium still has no toggle: it is too narrow to spend 220 dp+ on labels.

> ⚠⚠ **Reverted 2026-08-12 — the premise above is false.** `WideNavigationRail` at 1024 dp is not
> icon-only. A `WideNavigationRailItem` *"always displays labels (if they exist) when selected and
> unselected"* (AOSP KDoc); collapsed sets `NavigationItemIconPosition.Top`, which stacks the label
> **under** the icon, and that is exactly why the collapsed wide rail is 96 dp where the classic
> `NavigationRail` is 80 dp. The refinement mistook the wide rail for the classic one. See §7.

⚠ **`WideNavigationRail` and `WideNavigationRailItem` are stable, not experimental**, in
`org.jetbrains.compose.material3:material3:1.9.0` — no `@OptIn` needed. Note that is what the
`compose.material3` DSL actually resolves to; it is **not** the CMP plugin version (1.11.1).

### As built — N4 + N5 (2026-08-11)

**N4 — the toggle survives a restart.** `NavRailPreference` is a two-method port
(`read()`/`write()`) behind `LocalNavRailPreference`, with `filePersistedNavRailPreference(key,
storageDir)` on desktop writing `<storageDir>/<key>.navrail.properties` — same directory, format
and swallow-everything error policy as `rememberPersistedWindowState`. Wired into the five desktop
apps that have a persisted window (Drive, Control, Fit, Timeline, Mia) — and since 2026-09-16 into
Photos, Contacts and Maps too.

Three rules, each with a test:
- **Only an explicit toggle is persisted.** A size-class change moves the rail too, but that is the
  window talking, not the user; recording it would turn a resize into a stored preference.
- **A stored choice is ignored below Expanded.** Medium has no toggle, so honouring "expanded"
  there would hand 220 dp of an 800 dp window to navigation with no way to undo it.
- ⚠⚠ **The `LaunchedEffect(sizeClass)` fires on first composition too.** Without an explicit
  first-pass skip it snaps the rail to the size-class default immediately, and the restored
  preference is overwritten before it is ever visible — the feature silently does nothing.
  `storedPreference_survivesRestartAtExpanded` is what catches that.

⚠ The port is a `CompositionLocal` rather than a parameter because the rail's state lives inside
`AdaptiveNavScaffold` (reached via each app's `App()`), while the storage directory is only known
at the desktop `Main.kt`. A parameter would have to thread through eight `App()` signatures that
seven apps would merely forward. ⚠ Callers must `remember` the instance — the scaffold reads it
keyed on identity, so a fresh object per recomposition re-reads the file every frame.

**N5 — the window margin.** `windowMarginFor(sizeClass)` / `Modifier.appWindowMargin()`: 0 dp
compact, 24 dp from medium up, applied by the scaffold to the body pane. Measured result: body
starts at rail + 24 dp and ends 24 dp short of the trailing edge. Before this, content ran flush to
the window edge at every size class — the "margins at the end of the UI" complaint that opened this
work.

⚠⚠ **Horizontal only, deliberately.** Top and bottom are left alone because each screen's top
chrome (`AppTopAppBar` / `AppSearchBar`) already consumes the status-bar inset, and `ui-agent.md`
§8 records that stacking a second vertical inset on top of them is a real, previously-shipped bug.
A vertical margin here would have reintroduced it across eight apps at once.

⚠ **Q2 stays open and compact stays at 0.** The 24 dp medium-and-up figure is confirmed; the
widely-repeated 16 dp compact margin is not, and m3.material.io cannot be fetched to settle it.
Compact keeping its existing per-screen spacing also means phones are unchanged by N5 — the
conservative reading, chosen because the alternative writes an unverified number into a token
layer eight apps read.

### As built — N5b, the header inset (2026-08-12)

A follow-up to N4, reported after N1–N5 shipped: the menu button read as "uncentered" in all eight
apps at once. §0.3.1 has the mechanism and the derivation; the change is one
`Modifier.padding(start = 24.dp)` on the header `IconButton` in `AdaptiveNavScaffold`.

⚠ **`AdaptiveNavMetricsTest` did not catch it, and its existing assertions never could** — every one
of them measures the *body pane's* edges, and the header lives inside the rail, where it cannot move
the body by a pixel. `menuButtonSharesTheIconColumnWithTheDestinations` is the first assertion in
this file about anything *inside* the chrome: it reads the icons' leading edges out of the unmerged
semantics tree, in both rail states, and fails at `12.0` vs `36.0` when the padding is removed.
⚠ Unmerged is load-bearing — `selectable` merges an item's icon description into the item, whose
bounds are the whole 96 dp cell and say nothing about where the icon is.

**Two things measurement caught that reasoning would have got wrong:**

1. ⚠⚠ The expanded rail is width-*ranged*, not width-*fitting*. `"Home"/"Files"/"Fit"` and
   `"Home"/"Shared with me"/"Recently deleted"` both measure **220 dp** — they fit inside the
   minimum. The planned `assertNotEquals` between those two sets would have failed for a reason
   unrelated to the behaviour under test. Only a 31-character label moves the number (331 dp).
2. Rendering *invented* long labels showed two-line wrapping at 96 dp that looked like a defect.
   Re-rendered with the real product labels — the longest any app ships is Control's
   `"Notifications"` — it fits on one line. The apparent defect was an artefact of the test data.
   ⭐ Render the labels the product actually uses, not the ones that stress the component.

---

## 3. Testing

⚠ Per house rule #8 this is an `androidMain`-touching change by transitivity — `ui-core` compiles
into every Android app — so N3 onward runs `scripts/android-smoke.sh`.

But the smoke gate cannot see this defect: it asserts no crash/ANR, and a 300 dp drawer is not a
crash. The evidence that matters is rendered:

- **N2 pins the widths.** A `desktopTest` in `ui-core` that renders `AdaptiveNavScaffold` at
  representative widths (599 / 800 / 1024 / 1280 / 1700) with both short and long labels, and
  asserts the content pane's left edge. This is the probe from §0.2 promoted to a fixture; it is the
  only test in the repo that would have caught the reported symptom.
- **Render-and-look.** `timeline-shared-ui/src/desktopTest/.../TimelineShellRenderTest.kt:144-172`
  is the existing precedent — it writes PNGs to `build/reports/` for a human to read, with
  deliberately weak assertions. Its own KDoc records why: the phone layout was correct at every size
  class the tests exercised, because there were none. Extend that pattern to at least one more app
  in N3; **eight apps change at once and only one has a shell render test today.**
- ⚠ The desktop default window (1024 × 768) is the *worst* place to eyeball N3, because it is the
  boundary case that flips from drawer to collapsed rail. Check 1024 and 1280 explicitly.

---

## 4. Doc amendments owed

Per AGENTS.md §"Communicating across sessions", two docs currently state things that are false and
would mislead the next person into re-deriving this sweep.

- **[ADR 0001](../adr/0001-design-system-direction.md) §3** hard-codes *"compact → bar, medium →
  rail, expanded → drawer"*. That line is the origin of the defect and needs a dated amendment
  pointing here — not silent supersession by code, or the ADR keeps teaching the wrong mapping.
- **[`design-improvement-plan.md`](design-improvement-plan.md) Phase 7** defers M3 Expressive
  *"Blocked on appetite for the Compose 1.7.3 → 1.8+ bump"*. **That bump landed 2026-07-08** (CMP
  1.11.1, see [`cmp-version-upgrade.md`](cmp-version-upgrade.md) and `tech-debt.md` *CMP Version
  Pin*). The blocker expired a month before this sweep and the doc still reads as blocked — which is
  a plausible reason nobody revisited the drawer. Phase 7's step 1 is already done; steps 2–5 should
  be re-scoped against `material3` 1.4.0 rather than "if a newer minor is available".
- **[`ui-core/ui-agent.md`](../../ui-core/ui-agent.md) §8** describes the three-class model and the
  drawer branch; update with N1/N3.
- ADR 0001 §5 ("M3 Expressive — defer") should record that its stated preconditions are now met.

ADR 0001 §4 (iOS styling) needs **no** change — see D6.

---

## 5. Open questions

- ~~**Q1** (D3) — persist the rail expand/collapse toggle across launches?~~ **Answered yes in N4**;
  explicit toggles only, desktop only.
- **Q2** (D5) — the compact window margin. 16 dp is widely repeated and **unverified from a primary
  source**; do not cite M3 for it. N5 shipped compact at 0 rather than guess.
- **Q5** (new, from N5) — only Timeline's real shell was render-checked against the new margin, and
  its content is width-capped and centred so the margin barely shows. Apps whose screens use a
  full-width `AppTopAppBar` now have that bar sitting 24 dp in from the window edge at expanded
  widths. That is arguably correct M3 (the bar belongs to the pane, and panes have margins) but it
  is a visible change nobody has looked at. ⚠ `TimelineShellRenderTest` is still the **only**
  real-shell render test in the repo; a Drive or Control equivalent would close this.
- **Q3** — Maps and Calendar have no nav chrome at all (§0.1). Is that deliberate, or are they two
  apps that quietly opted out of the shared shell? Out of scope here, but someone should answer it
  before a third app does the same.
- ~~**Q4** — M3 says the expanded rail *can* reveal secondary destinations the collapsed one hides.
  All eight apps currently pass a flat 2–5 item list.~~ **Answered 2026-08-12 in §7**, and it was
  not the per-app question it looked like: with no secondary destinations to reveal, the
  expand/collapse *control* had nothing to do either. The rail keeps the flat list; the toggle
  retreated to Large. The per-app question — which destinations are secondary, and what belongs in
  the header — is now **N7**, and it is the prerequisite for ever offering the toggle lower again.

---

## 6. Out of scope

- Cupertino styling for Compose (ADR 0001 §4 stands; see D6).
- Modal / dismissible drawers — no size class needs one under D3.
- `Scaffold`-level pane splitting (`ListDetailPaneScaffold` and the rest of `material3-adaptive`).
  That artifact is separate from the navigation-suite one, at a different version
  (1.3.0-beta02), and is a different conversation.
- Screenshot golden-image gating (`design-improvement-plan.md` Phase 6). §3 deliberately proposes
  assert-on-measurement plus render-and-look, not pixel diffs.


## 7. The toggle that revealed nothing (2026-08-12)

**Trigger:** maintainer observation that the menu button "expands the rail, but you can't really
see more or anything" — and the question of whether Material actually recommends it.

It does recommend the control. It does not recommend it *here*, and the reason is a fact about the
component that N3 got wrong.

### 7.1 What the spec actually attaches the toggle to

Three statements from the AOSP source (m3.material.io is JS-rendered and unfetchable from the build
env — see §0.3 — so `WideNavigationRail.kt` and `NavigationRailSamples.kt` are the primary sources):

- *"A `WideNavigationRailItem` always displays labels (if they exist) when selected and
  unselected."* Collapsed uses `NavigationItemIconPosition.Top`, expanded uses `.Start`.
- The rail takes *"a header containing a menu button, a `FloatingActionButton`, and/or a logo"*.
- *"When collapsed, the rail should display three to seven navigation items."*

⭐ **The load-bearing one is the first.** Expanding does not reveal text — it *re-flows* text the
user could already read, from under the icon to beside it. The width buys nothing on its own.

M3 gives the expanded rail two jobs that would justify it: revealing **secondary destinations** the
collapsed rail hides, and carrying a header FAB/logo. We adopted neither. Every app passes one flat
list to both states — Contacts 2, Drive 3, Mia 3, Home 4, Timeline 4, Control 5, Fit 5 — all inside
the three-to-seven band where the spec says *collapsed* is the right component, and the header holds
only the menu button.

So the control was real, the transition was real, and the payload was empty. That is precisely what
"expands, but you can't see more" describes.

### 7.2 What changed

| | Before | After |
|---|---|---|
| Toggle present | Expanded, Large, ExtraLarge | **Large, ExtraLarge** |
| Stored preference honoured | ≥ Expanded | **≥ Large** |
| Icon | `Menu`, always | `Menu` collapsed → **`AutoMirrored.MenuOpen`** expanded |
| Tooltip | none | **`PlainTooltip`**, anchored `End` |
| A11y | content description flipped | content description = action, **`stateDescription`** = state |
| Header alignment | flush to the rail edge | **`padding(start = 24.dp)`** |

⚠ **The stored preference had to move with the toggle** — they are one condition, not two. A `true`
written by an older build at 1024 dp is still on disk for real users, and honouring it at a width
that no longer has a menu button would spend 220 dp on navigation with nothing left to undo it.
`storedPreference_isIgnoredBelowLargeWhereThereIsNoToggle` covers 800 / 1024 / 1199 for that reason.

⚠ `AutoMirrored` is not cosmetic: `MenuOpen` points at the content and must flip in RTL. `Menu` is
symmetric and does not.

### 7.3 Three things only the rendered PNGs caught

The §3 "render-and-look" pattern paid for itself again; none of these are visible to a measurement,
because `AdaptiveNavMetricsTest` reads the **body pane** and never the header.

1. ⚠⚠ **The menu button hung off the rail's leading edge.** Item icons centre on a 48 dp axis; the
   header is laid out flush, so the button sat at ~24 dp with nothing under it. AOSP's sample
   carries `Modifier.padding(start = 24.dp)` and this is what it is for — it had been read as
   incidental sample styling and dropped.
2. **`TooltipAnchorPosition.Above` (the sample's value) is wrong for this rail.** Our header is the
   top-left-most control in the window, so "above" is off-screen: the first render clipped the
   tooltip against both edges. `End` opens it into the body pane and mirrors correctly in RTL.
   ⭐ Copying a sample's *position* without checking where your anchor actually sits is the same
   class of mistake as §2's "render the labels the product uses" — samples are not in your corner.
3. **A popup is a second semantics root.** `performClick` on the toggle also hovers it, so the
   tooltip opens and `onRoot()` fails with *"found 2 nodes"* — an error about test plumbing that
   reads like an error about rendering. `AdaptiveNavRenderTest` now captures
   `onAllNodes(isRoot()).onFirst()`.

### 7.4 N7 — the prerequisite for ever lowering the toggle again

Not scheduled. Before the toggle can move back below Large, an app needs something the expanded
rail can actually reveal: a split into primary and secondary destinations, and a header that
carries a FAB or logo. ⚠ Fit already has half of this in the wrong place — its `Record` destination
renders as an `emphasized` `AdaptiveNavItem` (a 56×32 filled pill), which is a FAB wearing a nav
item's clothes; M3 puts it in the rail **header**. That is the natural first consumer.

---

## 8. ⚠⚠ Hiding the chrome was destroying the app (2026-08-12)

Reported against Fit's History tab: scroll down to *4 days ago*, open a workout, come back — the
feed is at the top again and the week roll-up visibly counts up from 0. Drive does the same thing
and does not do this.

### 8.1 The scaffold, not the view model

The suspicion was `ViewModelStore` mechanics, and it was right about the *symptom*. The cause was
one line up:

```kotlin
if (!showNav) { Scaffold(…) { Box { content() } }; return }
when (sizeClass) {
    Compact -> Scaffold(bottomBar = { … }) { Box { content() } }
    else    -> Scaffold(…) { Row { WideNavigationRail(…); Box { content() } } }
}
```

Three call sites for one `content` lambda. **A Compose group's identity is where it is written**, so
a flip of `showNav` does not re-render the content — it discards that subtree and builds a new one
somewhere else. Everything beneath it goes: every `remember`, the nav host's
`rememberSaveableStateHolderNavEntryDecorator`, and its
`rememberViewModelStoreNavEntryDecorator` — which is what clears and rebuilds **every screen's
`ViewModel`**.

And `showNav` is not a rare flag. Six apps pass `showNav = current is Destination.TopLevel`
(Fit, Timeline, Home, Mia, Contacts, and Control's variant), so all six paid a full teardown on
every push of a detail screen and another on every pop. ⚠ **Drive is the only app that never passes
`showNav`** — which is the entire reason it was the one app that "worked", and the reason nothing
here was ever suspected. The comparison the reporter reached for was the right one; the difference
was one argument, not a different paging strategy.

The size-class branches had the same defect: a desktop window dragged across 600 dp swapped the
bottom bar for the rail and took the content with it. Nobody navigates while resizing, so it never
surfaced.

### 8.2 The fix, and the rule it leaves behind

One `Scaffold`, one `Row`, one `Box`, one `content()`. The bar and the rail became conditional
*siblings*; hiding a sibling does not touch the following group's identity, and the difference
between "no chrome" and "chrome" is now a modifier (`appWindowMargin`) rather than a second layout.
The rail's own state moved into a private `RowScope.NavRail`, so extracting it also kept the
scaffold body a single unconditional path.

⚠⚠ **The standing rule: `content` is invoked from exactly one source position.** Anything
conditional belongs in a modifier or in the chrome. `AdaptiveNavContentIdentityTest` pins it — it
counts *constructions* of the content subtree (not recompositions, which are expected and fine)
across a `showNav` flip and across a breakpoint crossing.

### 8.3 What it did and did not fix — measured

`HistoryNavRoundTripTest` (`fit-shared-ui`) drives the reported flow through the real
`AdaptiveNavScaffold` + `KmpNavHost` pairing, because that pairing *is* the bug — a screen rendered
on its own, which is how every other test in that package renders it, cannot see any of this.

- ✅ **The view model survives the round trip.** Reverting the scaffold makes it fail with
  `expected:<1> but was:<2>`. This is the "0 → 41" flash.
- ❌ **A deep-scrolled feed still re-anchors near the top.** Separate cause, and a *documented*
  one: the paged feed is collected without `cachedIn` (`PagingUtil`, and the same note on
  `FilesScreenViewModel.pagedFiles`), so leaving composition drops the loaded pages and re-entry
  starts at page 0. `LazyListState` restores its saved index into a list that is 10 rows long and
  clamps. **Drive shares this exactly** — it is simply less visible in a folder that fits on one
  page. The second test pins today's behaviour so a change to the paging contract cannot land
  silently.

The follow-up for that half is the one `FilesScreenViewModel` already names: `cachedIn` plus a
UI-side `LazyPagingItems.refresh()` driven by the change signal, **not** `cachedIn` on its own.
⚖️ It reverses a rule that was established by on-device instrumentation (a folder created while
viewing never appeared until re-entry), so it owes an on-device pass and is not a drive-by.

---

## 9. The floating compact bar — `CompactNavStyle.Floating` (2026-11-13)

A full-width 80 dp `NavigationBar` spends a strip of the phone on very little when an app has only
two destinations. Photos (Photos · Collections) opts into a floating pill instead; every other app
keeps the docked bar, and every non-compact class still gets the rail.

- **What it is.** M3 Expressive's *floating toolbar* shape — the pattern Google Photos' own bottom bar
  uses, measured on a device: 56 dp tall, 40 dp active indicator **centred** with 8 dp above and
  below, only the selected item labelled. ⚠ CMP 1.12.1 resolves `material3` **1.9.0**, which has
  `ShortNavigationBarItem` but not the `FloatingToolbar` composables (only their tokens), so the pill
  is a `Surface(CircleShape)` holding `ShortNavigationBarItem`s with `NavigationItemIconPosition.Start`.
  ⚠ The first cut top-aligned the items (a `Row`'s default), which left the indicator hanging off
  the top of a 64 dp pill — the "pill too big for its contents" look. Centre them.
- **Content runs underneath.** The pill is drawn over the content area rather than in the
  `Scaffold`'s `bottomBar` slot, and publishes `LocalNavBarClearance` — the measured distance from
  the pill's top to the content's bottom edge, navigation-bar inset included. ⚠⚠ Every scrolling
  container under a floating scaffold must add it to its bottom `contentPadding`, or its last row
  sits behind the pill. It is `0.dp` everywhere else, so reading it unconditionally is safe.
- **Insets.** Only in floating mode does the scaffold `consumeWindowInsets(innerPadding)`, so the pill's
  own `windowInsetsPadding(navigationBars)` never double-counts; the docked bar and rail keep exactly
  the insets they had. `content()` stays at its one call site (§8) — the clearance provider wraps it
  unconditionally.
- **Paired with an `enterAlways` header — Photos' feed only.** `AppTopAppBar` stays pinned elsewhere
  (its KDoc says why). The feed header slides away on scroll-down and returns on the first scroll-up,
  on compact only, never while selecting (the selection bar holds Add-to-album and Trash), and it is
  restored when the grid reaches the top without a finger (scrubber, new photographs at rest). The
  sync banners sit below it in the `topBar` slot and do not hide.

Tests: `FloatingNavBarTest` (clearance reported, docked bar reports none, ignored above compact) and
its PNGs in `build/reports/adaptive-nav/` (`10`/`11`/`12`).
