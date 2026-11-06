# Design System Improvement Plan

Companion to [`docs/design/audit.md`](../design/audit.md). Originally a full sequenced plan from zero to a shared
design system; as of April 2026 the main body is shipped, and this document now only tracks what's
left.

## Status — April 2026

Phases 0 through 6 are shipped. `ui-core` is a KMP module (`android` + `jvm("desktop")`) consumed
by `drive-shared` and `control-shared`; both apps render through `TenshuTheme`; the adaptive-layout
stack (size classes, adaptive nav scaffold, content-width cap, window-state persistence, keyboard
shortcuts) is in use; every public composable in `ui-core` has KDoc plus at least one `@Preview`
via the shared `PreviewBox` harness.

Module-level conventions live in [`ui-core/ui-agent.md`](../../ui-core/ui-agent.md). Design-system
direction lives in [
`docs/adr/0001-design-system-direction.md`](../adr/0001-design-system-direction.md).
For the historical plan with full per-phase steps and acceptance criteria, see
[`docs/design/audit.md`](../design/audit.md) and the git history of this file.

## Principles (still in force)

- **Small, shippable PRs.** Each remaining item below should land as 1–2 PRs, not a mega-change.
- **No speculative generality.** Only extract a shared component once there are two real consumers,
  or one is obviously coming.
- **Deletion is part of done.** An item isn't complete until any old duplicate is removed.

---

## Remaining work (all optional)

### Phase 4f — Hover affordances

Polish. Add `Modifier.hoverable()` + subtle color state to desktop-interactive surfaces — list
rows, icon buttons, nav rail/drawer items. Cheapest-impact item on the list; noticeably improves
desktop feel.

**Where to start:** `AppListItem`, `NavigationRailItem` / `NavigationDrawerItem` wrappers in
`AdaptiveNavScaffold`. Pick up opportunistically when next touching those files.

**Acceptance:** every clickable surface on the two main Drive screens and the Control dashboard
reacts to hover on desktop.

---

### Phase 6 — Screenshot tests

Deterministic visual regression coverage. Paparazzi is Android-only and would only cover the
`android` target of `ui-core`; [Roborazzi](https://github.com/takahirom/roborazzi) supports Compose
Multiplatform and is the better fit here.

**Gate:** CI capacity and storage for golden images.

**Acceptance:** every `@Preview` in `ui-core` has a corresponding screenshot test; CI fails the PR
on an unreviewed visual diff.

---

### Phase 7 — Material 3 Expressive (revisit)

Not started. Blocked on appetite for the Compose `1.7.3 → 1.8+` bump and the iOS/Desktop compile
churn that comes with it.

Steps (when we take it on):

1. Bump JetBrains Compose `1.7.3 → 1.8+`. Expect iOS/Desktop compile churn; test thoroughly on
   both.
2. Bump AndroidX `material3` if a newer minor is available.
3. Introduce `MotionScheme` in `TenshuTheme` — likely `MotionScheme.expressive()` or a custom
   scheme using the `AppMotion` tokens stubbed in Phase 2e.
4. Adopt expressive primitives where they earn their keep:
    - `FloatingToolbar` on desktop detail screens.
    - `LoadingIndicator` (wavy/expressive) for sync progress in Control's `SyncDashboardScreen`.
    - `ButtonGroup` for `FileActionsBottomSheet`.
    - Emphasis typography (`bodyLargeEmphasized`, etc.) where hierarchy currently relies on bold.
5. Don't rewrite what works. Adopt piecemeal.

**Acceptance:** chosen expressive APIs are in use on at least one real screen each; no regressions
on non-expressive screens.

---

## Retired

- **5d `AppSnackbarHost`.** Neither Drive nor Control uses a snackbar host today. The plan's
  two-consumer rule says we don't extract what doesn't exist yet. Revive this item when a real
  caller appears.

---

## Out of scope for this plan

- Rebranding (palette change beyond Phase 0's decision).
- Cupertino iOS styling.
- Design tool sync (Figma tokens).
- Animation system beyond Phase 2e stubs and Phase 7 adoption.
