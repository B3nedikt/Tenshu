# Design System Audit — Drive & Control

## Summary

**Components reviewed:** Drive + Control (shared/desktop/android/ios) — 2 theme files, 2 color
files, 1 typography file, ~27 common UI components
**Issues found:** 14 (3 critical, 6 major, 5 minor)
**Score:** 38 / 100

The design system is in an early, fragmented state. Drive and Control each maintain their own
`AppTheme` with different color palettes and different completeness levels. There is no shared UI
module, no spacing/shape/motion token layer, and the codebase is pinned to Material 3 1.4.0 /
JetBrains Compose 1.7.3 — i.e. pre-Expressive. Desktop compatibility works structurally (the
`-desktop` modules consume the same `commonMain` composables) but there is no adaptive layout layer,
no keyboard handling, and no density tuning.

The short answer to "should we centralize this?" is **yes — this is the single biggest leverage
point on the list.**

---

## 1. Current State

### Theme & color tokens

Two completely separate `AppTheme` composables exist:

- `drive-shared/src/commonMain/kotlin/app/tenshu/drive/Theme.kt` — full Material 3 color scheme, 39
  role tokens across light + dark, no typography, no shapes.
- `control-shared/src/commonMain/kotlin/app/tenshu/control/ui/theme/Theme.kt` — minimal 3-role
  scheme (primary/secondary/tertiary) using the default Android Studio wizard palette (
  Purple40/Purple80 etc.), plus a `Typography` with only `bodyLarge` defined.

Net result: the two apps don't look like they're part of the same product family unless you squint.
Control inherits most of its visual identity from unspecified Material defaults.

### Typography

- Drive: nothing defined → uses `MaterialTheme` defaults.
- Control: only `bodyLarge` is explicitly overridden; the other 14 M3 roles (`displayLarge` →
  `labelSmall`) fall back to defaults.

Neither app declares an emphasis axis, font weights, or a display/body distinction beyond default.

### Shape, elevation, motion

No `Shape.kt`, `Elevation.kt`, or `Motion.kt` exists in either module. Shapes appear as ad-hoc
`RoundedCornerShape(8.dp)` literals inside components. There is no motion token layer, which means
any animation work today has to reinvent durations and easings per component.

### Spacing / sizing

No `Spacing.kt`, `Dimens.kt`, or equivalent. Spot check on `HorizontalFileItem.kt` shows the typical
pattern: `8.dp`, `16.dp`, `24.dp`, `40.dp` as raw literals. Roughly 12+ component files use this
pattern. A desktop-specific constraint (`.widthIn(max = 800.dp)`) is hardcoded inline in
`FilesScreen.kt` rather than living in a layout-token file.

### Components

- `drive-shared/ui/common/` — ~24 components (file items, bottom sheets, dialogs, FABs, search
  bars).
- `control-shared/ui/common/` — 3 components (`QrCode`, `QrScanner`, `App`).

There is no cross-module sharing. If both apps ever need the same dialog, list row, or top app bar
pattern — and they will — it has to be copy-pasted or re-implemented.

### Versions

From `gradle/libs.versions.toml`:

```
compose = "2026.03.01"        # AndroidX Compose BOM
jetbrains-compose = "1.7.3"    # JetBrains (Desktop/iOS) Compose
material3 = "1.4.0"            # androidx.compose.material3
```

Material 3 Expressive was introduced around Material3 1.4+ / Compose Desktop 1.8+. The AndroidX
side (1.4.0) is technically Expressive-capable; the JetBrains Compose side (1.7.3) is below the
Expressive line. A version bump is required before any expressive APIs are usable on Desktop/iOS.

### Desktop compatibility

Structurally fine: both `drive-desktop` and `control-desktop` are Compose-for-Desktop JVM targets
that consume `commonMain` composables. Concretely:

- Window sizing is hardcoded: `rememberWindowState(width = 1024.dp, height = 768.dp)` in `Main.kt`.
- File dialog uses `java.awt.FileDialog` — fine, but not wrapped in any cross-platform abstraction.
- No adaptive / responsive layout layer. The one concession is `widthIn(max = 800.dp)` on
  `FilesScreen` — meaning on a 2560px monitor, the content is a narrow column with dead space on
  either side, which is probably not desired.
- No window-size classes (`WindowSizeClass`), no two-pane / list-detail patterns, no navigation
  suite switching between rail/bar/drawer.
- No keyboard shortcuts (`KeyEvent` handlers not found).
- No focus management for keyboard navigation.
- No mouse hover / cursor affordances — components were authored for touch.

---

## 2. Findings

### Naming consistency

| Issue                          | Where                                                                                            | Recommendation                                                                                                                               |
|--------------------------------|--------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| Mixed token naming conventions | Drive uses `md_theme_light_primary` (M3 codegen style), Control uses `Purple40` (wizard default) | Adopt a single convention — semantic role names (`primary`, `onPrimary`, …) exposed via a typed `AppColors` object, not free-floating `val`s |
| Theme composable name clash    | Both modules export `AppTheme` from different packages                                           | Unify into a single `TenshuTheme` (or similar) in a shared module                                                                            |
| `Theme.kt` location varies     | Drive: `app.tenshu.drive.Theme.kt`; Control: `app.tenshu.control.ui.theme.Theme.kt`              | Move both into `ui-core/src/commonMain/kotlin/app/tenshu/ui/theme/`                                                                          |

### Token coverage

| Category   | Defined                              | Hardcoded values found                                                  |
|------------|--------------------------------------|-------------------------------------------------------------------------|
| Colors     | Drive: 39 roles. Control: 3 roles.   | 6 hardcoded hex in Control's `Color.kt`; 0 outside theme files in Drive |
| Typography | Control: 1 of 15 M3 roles. Drive: 0. | Minimal — most components rely on defaults, which is fine *for now*     |
| Spacing    | 0                                    | Estimated 60–100+ inline `.dp` literals across ~12 files                |
| Shape      | 0                                    | Scattered `RoundedCornerShape(n.dp)` inline                             |
| Elevation  | 0                                    | Material defaults only                                                  |
| Motion     | 0                                    | No custom durations/easings                                             |

### Component completeness (spot check)

| Component                        | States                       | Variants          | Docs | Score  |
|----------------------------------|------------------------------|-------------------|------|--------|
| `HorizontalFileItem`             | default / selected / loading | —                 | none | 5 / 10 |
| `FileSearchBar`                  | default / active             | —                 | none | 5 / 10 |
| `AddFileBottomSheet`             | default                      | —                 | none | 4 / 10 |
| `CreateFolderDialog`             | default / error              | —                 | none | 5 / 10 |
| `QrCode` / `QrScanner` (control) | default                      | platform variants | none | 6 / 10 |

None of the components have documented props, states, accessibility notes, or usage guidance. None
have `@Preview`-driven snapshot coverage that I could see.

### Material 3 Expressive alignment

Not aligned. No use of:

- `MaterialShapes` / expressive shape tokens
- `MotionScheme` (standard vs. expressive)
- Emphasis typography (`bodyLargeEmphasized`, etc.)
- `FloatingToolbar` / `FloatingActionButtonMenu`
- `LoadingIndicator` (the new expressive one)
- `ButtonGroup`
- Wavy / stepper progress indicators

This is fine — you don't have to adopt Expressive. But the question was asked, so: if you want it,
you need Material3 1.4+ on AndroidX (already there) and to bump JetBrains Compose from 1.7.3 to ≥1.8
for Desktop/iOS.

---

## 3. Recommendation: centralize into a single UI module

### Proposed module layout

```
ui-core/                          (new, KMP module — commonMain + android + desktop + iOS source sets)
  ui/theme/
    TenshuTheme.kt                single @Composable entry point
    Colors.kt                     typed AppColors (light + dark palettes)
    Typography.kt                 full M3 scale + emphasis aliases
    Shapes.kt                     AppShapes
    Spacing.kt                    AppSpacing (4/8/12/16/24/32/48/64)
    Elevation.kt                  AppElevation
    Motion.kt                     durations + easings
  ui/tokens/
    LocalAppColors.kt             CompositionLocals for non-M3 tokens
  ui/components/
    AppTopAppBar.kt
    AppBottomSheet.kt
    AppDialog.kt
    AppListItem.kt                (replaces HorizontalFileItem styling)
    AppSearchBar.kt
    AppFab.kt
    AppButton.kt                  (optional — wraps M3 Button with app defaults)
  ui/layout/
    AppWindowSizeClass.kt         expect/actual
    AdaptiveScaffold.kt           rail / bar / drawer switching
  ui/preview/
    PreviewBox.kt                 light/dark + size-class preview helpers
```

`drive-shared` and `control-shared` both depend on `ui-core`. Feature-specific UI (file list rows
with sync status, QR scanner composables, pairing wizard) stays in the respective feature modules
but consumes `ui-core` primitives.

### Migration sequence

1. **Scaffold `ui-core`** with the three token layers (color, type, shape) — merge Drive's 39-role
   color scheme as the baseline; pick a real brand palette rather than the Android Studio wizard
   purples currently in Control.
2. **Introduce `AppSpacing` + `AppShapes`** and codemod the worst offender files (the ~12 with
   inline `.dp` literals). Don't try to boil the ocean — cover 80% of usages.
3. **Unify `AppTheme` → `TenshuTheme`.** Delete `drive-shared/Theme.kt` and
   `control-shared/ui/theme/Theme.kt`. Both `App.kt` composables call `TenshuTheme { … }`.
4. **Extract shared components** (top app bar, bottom sheet wrapper, dialog wrapper, list item).
   Don't extract speculatively — only what's already duplicated or about to be duplicated.
5. **Add `WindowSizeClass` + `AdaptiveScaffold`** — this is the piece that actually makes desktop
   feel native rather than "a phone app in a window."
6. **Bump JetBrains Compose → 1.8+** and optionally adopt a few Expressive primitives where they
   clearly earn their keep (`FloatingToolbar` on desktop, `LoadingIndicator` for sync states).

Steps 1–3 are roughly a weekend; step 4 is incremental; step 5 is the biggest desktop
quality-of-life win. Step 6 is optional.

### Cost of *not* doing this

- Every new component has to be built twice or picked arbitrarily in one app.
- The Control app's UI will continue to drift visually from Drive.
- Desktop polish (adaptive layouts, keyboard shortcuts) requires touching every screen individually
  rather than being solved once in the layout layer.
- Any future rebrand is a multi-file search-and-replace instead of changing one palette.

---

## 4. Priority actions

1. **Create `ui-core` and move tokens into it.** Highest leverage; unblocks everything else.
2. **Replace Control's placeholder palette with a real one** that's coherent with Drive. This is the
   most visible win and takes under an hour once `ui-core` exists.
3. **Introduce `AppSpacing` and codemod hardcoded `.dp` values.** Small, mechanical, but it's what
   separates a codebase with a design system from one without.
4. **Add `WindowSizeClass`-driven adaptive layout** for desktop — replaces the hardcoded 800dp max
   width with something intentional.
5. **Fill in the Material 3 typography scale** (or at minimum declare the 3–4 roles actually used)
   so type changes propagate globally.
6. **Decide on Material 3 Expressive.** Not urgent. Revisit once JetBrains Compose 1.8+ is viable
   for the Desktop/iOS targets.

---

## Open questions

- Is there a brand palette (from design) that should replace both the M3 codegen purples in Drive
  and the wizard purples in Control? If yes, steps 1–2 collapse into one PR.
- Is the desktop target a first-class product, or a convenience? This determines how much to invest
  in adaptive layout / keyboard / mouse affordances.
- Is iOS styling meant to match Android M3, or lean Cupertino? Current code is pure Material on
  iOS — fine, but worth making an explicit choice rather than a default.
