# UI Core: Design System & Component Conventions

This module is the single source of truth for shared UI across the Tenshu app family — every
`*-shared-ui` module plus `control-shared` consumes it. It owns theme tokens, the component
library, adaptive layout primitives, and the KMP navigation host; `core-*` / `file-service-*`
never consume it.

## 1. Module Scope

* **What lives here**: theme tokens, cross-app composables (chrome, input, feedback, layout),
  adaptive navigation primitives, preview harness, KMP navigation host.
* **What does NOT live here**:
    * Feature-specific UI (sync-status rows, pairing wizards).
    * ViewModels, repositories, service **clients**. ⚠ `ui-core` does depend on a few service
      modules — `:preference-service`, `:storage-service-common`, `:app-reachability`,
      `:app-error-handling` — but for their **declarations only** (`MeasurementSystem`,
      `RealmStorageState`, `UnreachableReason`, `ErrorCopyKey`); never for a client. The
      build file records why each one is there.
    * App-specific navigation graphs.

⚠ `QrScanner` does **not** live here: it is in `:ui-qr-scanner` (same package,
`app.tenshu.ui.components`). Every app depends on `:ui-core`, and only Control and Drive scan, so
a camera dependency added here ships in all ten Android apps. ⚠ Android decodes with ZXing, not
ML Kit: ML Kit's barcode model logs usage to Google and merged INTERNET into Drive's manifest
(`ui-qr-scanner/build.gradle.kts`). `QrCode` (drawing a code, no camera) stays.
* **Targets**: `android`, `jvm("desktop")`, `iosArm64`, `iosSimulatorArm64`. ⚠ The iOS targets
  are **not** an iOS app consuming shared UI — no Compose Multiplatform UI ships on iOS at all
  (`ui-map-agent.md`). They exist so `:ui-map`'s MapLibre renderer can read the design-system
  tokens, which is why `KmpNavHost.ios.kt` is a deliberately minimal `actual` with no consumer.

## 2. Directory Layout

```
ui-core/src/commonMain/kotlin/app/tenshu/ui/
  theme/        TenshuTheme, Colors, Typography, Shapes, Spacing, Motion
  chrome/       AppTopAppBar, AppBottomSheet, AppDialog, AppListItem, AppEmptyState, AppFab
  input/        AppSearchBar, AppTextField, AppKeyboardShortcuts
  feedback/     AppLoadingIndicator, AppAutoContinueButton (control-pairing-ux.md §9)
  accessibility/ AccessibilityPreferences — screen-reader + reduced-motion signals (expect/actual)
  layout/       WindowWidthSizeClass, AdaptiveNavScaffold, ContentWidth
  navigation/   KmpNavHost (expect/actual), KmpNavEntry + DSL
  preview/      PreviewBox (test harness for every @Preview in this module)
ui-core/src/desktopMain/kotlin/app/tenshu/ui/layout/
  WindowStatePersistence.kt
```

Subpackage semantics:

* **`chrome/`** — surfaces (bars, sheets, dialogs, lists, empty states).
* **`input/`** — user input (text, search, keyboard shortcuts).
* **`feedback/`** — status indicators (loading, snackbars).
* **`layout/`** — structural primitives (scaffolds, width caps, size classes).
* **`navigation/`** — navigation infrastructure, not a visual primitive.
* **`preview/`** — tooling only; never exported to consumers at runtime.

## 3. Design Principles

* **Single Source of Truth**: If a color, spacing value, shape, or wrapper is used in more than one
  place, it MUST live in `ui-core`. Hex literals and raw `.dp` magic numbers in feature modules are
  prohibited — use tokens from `theme/`.
* **No Speculative Generality**: A component is extracted here only when it already has (or is
  about to have) two real consumers. DO NOT build an `AppButton` "just in case."
* **Deletion Is Part of Done**: When a token or component moves to `ui-core`, the duplicate copy in
  `drive-shared-ui` / `control-shared` MUST be removed in the same PR.
* **Platform Accommodations, Not Platform Forks**: iOS/Desktop get `expect`/`actual` affordances
  for things users would otherwise experience as broken (safe areas, keyboard shortcuts, window
  persistence). They do NOT get a separate design language.

## 4. Component Convention

* **Wrap Material, Don't Rewrite It**: Every component in `ui-core` is a thin wrapper over an M3
  primitive. We standardise defaults and slots; we do NOT paint new visuals.
* **Value Props For The Common Case, Slots For The Exotic One**: e.g., `AppDialog` exposes both a
  `confirmLabel: String` overload and a `confirmButton: @Composable () -> Unit` slot. Prefer the
  value-prop overload as the default public API.
* **Naming**: public composables are prefixed `App` (`AppTopAppBar`, `AppDialog`). Layout helpers
  use `Modifier.appFoo()` extensions (`Modifier.appContentWidth()`). Theme tokens go under
  `MaterialTheme.spacing`, `MaterialTheme.motion`, etc.
* **Pick The Right Subpackage**: see §2. If a new component does not fit an existing subpackage,
  propose a new one in the PR description rather than forcing a bad fit.
* **Two-Consumer Rule**: A single caller is a feature-module component, not a `ui-core` one. Prove
  concrete duplication or imminent second use before extracting.

## 5. Documentation Requirement

Every public composable in `ui-core` MUST have:

* A KDoc block with, in this order:
    1. One-sentence purpose summary.
    2. `### When to use` — the situations it is and is not appropriate for.
    3. `### Variants` — the meaningful configurations (minimum, with-action, etc.).
    4. `### Accessibility` — content descriptions, focus order, keyboard affordances.
    5. `@param` lines for every non-obvious parameter.
* At least one `@Preview` wrapped in `PreviewBox`. Recommended coverage:
    * Light-theme default state.
    * Dark-theme variant (`PreviewBox(useDarkTheme = true)`).
    * Component-specific variants (error state for inputs, size-class variants for layout
      primitives).

Non-visual items (modifiers, data classes, composition locals, navigation infrastructure) are
exempt from the `@Preview` requirement but MUST still have a KDoc block.

## 6. Preview Harness (`PreviewBox`)

`PreviewBox` (in `ui/preview/`) is the project-wide harness for `@Preview` composables. Every
`@Preview` across the app modules — `ui-core`, `drive-shared`, `control-shared`, and any future
UI module — MUST use it. Do NOT hand-roll a `TenshuTheme { Surface { ... } }` wrapper per preview.

`PreviewBox`:

* Wraps content in `TenshuTheme` so tokens resolve correctly.
* Accepts `useDarkTheme: Boolean` to flip the color scheme.
* Accepts `windowWidthSizeClass: WindowWidthSizeClass` to drive adaptive-layout testing without
  needing a real window.
* Paints `MaterialTheme.colorScheme.background` so light/dark variants are easy to tell apart in
  Android Studio's preview panel.

The inverse rule applies to UI *tests*: `runComposeUiTest` bodies wrap content in `TenshuTheme`
directly, NOT in `PreviewBox` (see `docs/testing.md` §1).

*Usage*:

```kotlin
@Preview
@Composable
private fun AppFooPreview() = PreviewBox {
    AppFoo(...)
}

@Preview
@Composable
private fun AppFooDarkPreview() = PreviewBox(useDarkTheme = true) {
    AppFoo(...)
}

@Preview
@Composable
private fun AppFooExpandedPreview() = PreviewBox(
    windowWidthSizeClass = WindowWidthSizeClass.Expanded,
) {
    AppFoo(...)
}
```

Import the annotation as **`androidx.compose.ui.tooling.preview.Preview`** at every call site,
because the JetBrains-namespaced annotation is the deprecated one.

⚠⚠ **The dependency does NOT follow the import.** Keep the JetBrains `compose.preview`
(`org.jetbrains.compose.ui:ui-tooling-preview`) — swapping in
`androidx.compose.ui:ui-tooling-preview` to "match" the import compiles on Android and breaks
every other target, because the androidx artifact publishes no iOS/js/wasm variants and so cannot
back a `commonMain` preview. The JetBrains artifact's android variant ships zero files and simply
depends on the androidx one, so there is no duplicate-class risk. Import and artifact are meant
to disagree; see `docs/tech-debt.md`, *"CMP Version Pin"*.

## 7. Theme Tokens

* **Colors**: `AppColorTokens` exposes both light and dark `ColorScheme`. Do NOT reference raw
  `Color(0xFF...)` values in feature modules.
* **Spacing**: `MaterialTheme.spacing.{xs, s, m, l, xl}`. Introduce a new step only if the existing
  scale genuinely cannot express the need; do not add ad-hoc one-off values.
* **Typography**: use `MaterialTheme.typography.*` (`titleMedium`, `bodyMedium`, etc.). Custom
  styles live in `Typography.kt`.
* **Shapes**: `MaterialTheme.shapes.*`. Component-specific shape overrides belong on the component,
  not in the feature module.
* **Motion**: `AppMotion` exposes duration and easing tokens. It is currently stubbed — add values
  as the motion system matures rather than sprinkling raw `tween(...)` arguments in features.

## 8. Adaptive Layout

* **`WindowWidthSizeClass`** — `Compact`, `Medium`, `Expanded`, `Large`, `ExtraLarge` (the five
  Material 3 breakpoints: 600 / 840 / 1200 / 1600). Read via `LocalWindowWidthSizeClass` in
  composables; set it at the app root. All adaptive components in `ui-core` branch on this.
  ⚠⚠ **Compare with `>=`, never `==`.** The constants are declared narrowest-first and enums are
  `Comparable` by ordinal, so `sizeClass >= Expanded` means "expanded and above". An `==` check
  compiles fine and simply stops firing when a wider class is added above it — which is how
  `appContentWidth` nearly lost its cap on the widest windows. Do not reorder the constants.
* **`AdaptiveNavScaffold`** — canonical navigation chrome: bottom `NavigationBar` (Compact), then
  one `WideNavigationRail` for every wider class — collapsed at 96 dp (Medium / Expanded), expanded
  and content-sized within 220–360 dp (Large / ExtraLarge). **From Large up — and only there** — a
  leading menu button toggles the two; the size class picks the default. Use `showNav = false` for
  edge-to-edge screens (login, onboarding).
  ⚠⚠ **Don't add the toggle at Expanded** on the grounds that 1024 dp would otherwise be
  icon-only. It would not: a `WideNavigationRailItem` *always* draws its label, and the collapsed
  wide rail is 96 dp rather than the classic rail's 80 dp precisely because it stacks that label
  under the icon. Expanding there hides nothing and reveals nothing — it re-flows the same text
  sideways for 124 dp of body width. M3's expanded rail
  earns its width by revealing **secondary destinations** the collapsed rail hides and by hosting
  a header FAB or logo; give it those first, then the control means something. Every app here
  still passes one flat 2–5 item list, inside the three-to-seven range where M3 says the
  collapsed rail is correct.
  ⚠ **There is no navigation drawer** — Material 3 deprecated it in favour of the expanded rail, and
  a `PermanentNavigationDrawer` renders a fixed 300 dp at every window width and every label set.
  Don't introduce one.
  Widths are pinned by `AdaptiveNavMetricsTest`; ⚠ note the expanded rail is width-*ranged*, not
  width-*fitting*, so labels shorter than the 220 dp floor do **not** shrink it.
* **`NavRailPreference`** — the port that makes the user's expand/collapse choice survive a
  restart. Provide `LocalNavRailPreference` at the app entry point; desktop has
  `filePersistedNavRailPreference(key, storageDir)`. ⚠ `remember` the instance — the scaffold reads
  it keyed on identity, so a fresh object per recomposition re-reads the file every frame. ⚠ Only
  an **explicit toggle** is persisted; a size-class change is not the user talking. Default is
  `null` = session-only, which is right for Android/iOS.
* **`rememberPersistedWindowState(key, storageDir = DesktopAppPaths.root())`** (desktop) — **every**
  Tenshu desktop app's window, and the one rule for its size: a first launch opens **centred at 80 %
  of the usable screen width × 90 % of its height**, never below 1024 × 768 (unless the screen is
  smaller) and never above 1680 × 1050; after that, whatever the user set is remembered. ⚠ Don't pass
  `defaultWidth`/`defaultHeight` or call bare `rememberWindowState()` — both reintroduce the
  per-app sizes that made the apps look inconsistent (Contacts and Maps opened at Compose's
  800 × 600). ⚠ A centred first-launch position is not written back until the user moves or resizes
  the window, so an untouched window keeps following the rule on the next launch. ⚠ At 1382 dp a
  16" MacBook's first launch is in the **Large** size class, so the rail starts expanded. Home's
  phone-shaped 450 × 920 window is the one deliberate exception.
* **`Modifier.appContentWidth()`** — caps body width at 840dp so text does not stretch across wide
  desktop windows.
* **`Modifier.appWindowMargin()`** — the Material window margin on a body pane: 0 dp Compact,
  24 dp Medium and up. `AdaptiveNavScaffold` applies it already, so screens must not.
  ⚠ **Horizontal only, deliberately** — vertical insets belong to each screen's top chrome, and
  adding a second one is the double-inset bug §8 warns about below. ⚠ Compact is 0 because the
  16 dp figure everyone quotes could not be confirmed from a primary source, not because M3 says
  zero.
* **Screen chrome owns the top inset.** `AdaptiveNavScaffold` forwards `WindowInsets(0, 0, 0, 0)` to
  its content and only consumes the **bottom** (nav bar / rail). So **every top-level screen is
  responsible for its own top status-bar / display-cutout inset.** The rule is about the screen's
  **top element**, not a specific container: its first visible chrome must be an **`AppTopAppBar`**
  (Material 3 `TopAppBar` consumes the status-bar inset by default) or an **`AppSearchBar`** (Material 3
  `SearchBar`, same effect). Either valid idiom works — both are in use:
    * `Scaffold(topBar = { AppTopAppBar(...) }) { padding -> … }` — body gets the inset via `padding`
      (e.g. `SharedScreen`, `SharedBrowseScreen`, `SyncPreferencesScreen`);
    * a plain `Column { AppTopAppBar(...) /* or FilesSearchBar */; … }` — the bar, **as the first
      child**, consumes the status bar and the rest of the column flows below it (e.g. `HomeScreen`,
      `FilesScreen`, `FolderScreen`, `ImagePreviewScreen`, `FileDetailsScreen`).

  Do **not** put a bare `TabRow` / `Column` / list at a screen root with no inset-consuming top
  element — it renders under the status bar / notch. (Prefer the bar/search-bar over a raw inset
  modifier like `statusBarsPadding()`, so the top chrome stays the single thing that owns the inset.)
  This is implicit — there's no compile-time guard — so it lives here as a rule. (Precedent: the Drive
  *Shared* tab's bare-`TabRow` root rendered under the status bar until it was given a `Scaffold` +
  `AppTopAppBar`.)

  ⚠ **The opposite mistake — overriding `contentWindowInsets` *and* having top chrome — doubles the
  inset.** Passing `contentWindowInsets = WindowInsets.systemBars` is legitimate **only** for a shell
  whose screens have no inset-consuming top element (Fit, Home and Mia are bare headline `Column`s, and
  their overrides are correct). It is wrong the moment a screen grows an `AppTopAppBar` or an
  `AppSearchBar`: the scaffold turns the inset into `innerPadding`, `Modifier.padding(innerPadding)`
  does **not** mark it consumed, so the screen's own bar reads the full status-bar inset again and pads
  a second time. (Precedent: Contacts carried the override under a comment asserting `AppTopAppBar`
  does not consume the inset — it does — giving ~100 dp of dead space above the list's search bar.
  Deleting the override was the fix.) Rule of thumb: **exactly one thing owns the top inset.**
  If shell-level chrome (a Control-unreachable `ErrorBanner`) is conditionally the top element, give the
  *column* `Modifier.windowInsetsPadding(WindowInsets.statusBars)` while that chrome is up — it applies
  and consumes in one step, so the screen below stops padding.

### 8.0 Dialogs

`AppDialog` is a **thin wrapper over Material 3's `AlertDialog`**, not an alternative to it.
Use it when its shape fits — a *string* title, an optional icon, a body, one or two labelled
buttons. Use `AlertDialog` directly when you need a composable title, custom container colours
or a non-standard button row. They are the same component; the choice is fit, not correctness,
and a screen mixing them is not violating anything.

* **The slot overload** (`confirmButton` / `dismissButton`) is for buttons that need state —
  a confirm disabled until a field is valid, or a destructive label in `colorScheme.error`.
  **The label overload** (`confirmLabel` / `dismissLabel`) is for the plain case; a `null`
  `dismissLabel` gives the one-button acknowledge dialog.
* ⚠ `icon` is an `ImageVector` plus a separate `iconContentDescription`, rather than a
  composable slot. A decorative dialog icon usually *should* be `null` — the title carries the
  meaning — but that ought to be a decision someone makes rather than a slot they fill in.
* ⚠⚠ **`AppDialog` is not a fork, and must not become one.** A hand-rolled
  `Dialog { Card { … } }` justified as *"a custom modal overlay to avoid focus issues with
  top-level AWT windows"* cannot be that: it opens `androidx.compose.ui.window.Dialog`, the same
  primitive `AlertDialog` opens internally. Such a fork buys nothing and costs the M3 spec (480 dp
  max width instead of 560, no min width, flat instead of tonal elevation, an unstyled body
  slot). If a component here ever claims a platform workaround, check that the code contains
  one.
* **Don't wrap the wrapper.** A local `FooDialog` that re-does title-body-buttons over
  `AlertDialog` is the same smell one level down; build it on `AppDialog` (Contacts'
  `NameDialog` is the worked example).

### 8.1 The floating action button

`AppFab` (icon only) and `AppExtendedFab` (icon + label) are the whole surface, plus
`Modifier.appFabInset()` for the hand-placed case. Every FAB in the repo goes through them —
they were extracted at n = 6 from six call sites across Drive, Home, Control and Contacts.

* **One per screen.** The FAB is the action the screen exists to enable. Two candidate actions
  means the FAB opens a sheet holding both (Drive's `AddFileActionsFloatingActionButton`,
  Contacts' `AddContactFab`); two FABs is not a layout this design system has.
* ⚠ **`AppFab`'s `contentDescription` is required, not nullable.** A FAB is an icon and nothing
  else, so an unlabelled one is the screen's most important control, invisible to a screen
  reader. All six pre-existing call sites happened to pass one; the type is what stops the
  seventh from forgetting.
* ⚠ **`AppExtendedFab` passes `contentDescription = null` by construction** — its `label` is the
  accessible name, and describing the icon too makes a screen reader announce the action twice.
  That is the *opposite* rule to `AppFab`, which is why they are two functions rather than one
  with a nullable label.
* **Placement, two valid shapes:** a `Scaffold`'s `floatingActionButton` slot (which owns
  placement and inset — pass no modifier), or `Modifier.align(Alignment.BottomEnd).appFabInset()`
  over a `Box`. ⚠ Do not use `appFabInset()` inside a `Scaffold` slot; the insets double.
* **Why the inset is a helper rather than `.padding(MaterialTheme.spacing.l)`:** at extraction
  time the six sites wrote the same 16 dp three ways — `spacing.l`, a raw `16.dp`, and nothing
  at all — including **within one module**, where Drive's `FilesScreen` used the token while its
  own `HomeScreen` and `FolderScreen` used the literal. Same number today; two things to keep in
  step forever otherwise.

## 9. Navigation (`KmpNavHost`)

* `KmpNavHost` is `expect`/`actual` so each target plugs into its platform's transition APIs
  without dragging the others' dependencies along.
* Destinations are declared via the `kmpEntryProvider<T> { entry<T.Foo> { FooScreen() } }` DSL —
  see `KmpNavEntry.kt`.
* The host renders the top of the caller-owned back stack and wires platform back semantics
  (Android system back, Esc on desktop). Caller retains ownership of the back stack.
* This file is navigation infrastructure, not a visual primitive — it has no appearance of its own.

## 10. Contributing Checklist

Before merging a change to `ui-core`:

1. **Confirm the two-consumer rule** for any new component.
2. **Pick the right subpackage** (§2).
3. **Add / update KDoc** per §5.
4. **Add `@Preview`s** in `PreviewBox` covering at least light + dark. Add size-class or state
   variants when meaningful.
5. **Delete the duplicate** in `drive-shared-ui` / `control-shared` in the same PR.
6. **Call out breaking API changes** in the PR description; both consumers migrate in the same PR
   that introduces the break.

For design-system direction and migration roadmap, see
[`docs/adr/0001-design-system-direction.md`](../docs/adr/0001-design-system-direction.md) and
[`docs/proposals/design-improvement-plan.md`](../docs/proposals/design-improvement-plan.md). For
broader UI conventions
(Screen vs. Content split, testing, platform delegate pattern), see
[`docs/agent.md` §6](../docs/agent.md) — it is the source of truth for cross-cutting UI patterns.
