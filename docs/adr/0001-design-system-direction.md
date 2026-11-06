# ADR 0001 — Design System Direction

- **Status:** Accepted; **§3 and §5 amended 2026-08-11** (see *Amendments* at the foot); **§1a (surface ramp) added 2026-11-01**
- **Date:** 2026-04-20
- **Owner:** Benedikt
- **Supersedes:** —
- **Related:** [`design/audit.md`](../design/audit.md) (filed as `design-system-audit.md` when
  this ADR was written), [`proposals/design-improvement-plan.md`](../proposals/design-improvement-plan.md)
  (then `design-system-improvement-plan.md`),
  [`proposals/ui-adaptive-navigation.md`](../proposals/ui-adaptive-navigation.md)

## Context

Drive and Control each maintain their own `AppTheme`, color palette, and component set, with no
shared UI module. Typography, spacing, shape, motion, and elevation tokens are absent or partial.
Desktop works structurally (both `-desktop` modules consume `commonMain`) but has no adaptive layout
layer, no keyboard handling, and a hardcoded window size.

Before scaffolding a shared UI module and migrating both apps, four decisions need to be locked in
so downstream phases have something stable to commit to.

## Decisions

### 1. Brand palette — custom "Amethyst Neutral"

Use a new custom palette, seeded from a muted violet. The previous Drive palette (M3 codegen purple)
is generic and the Control palette (Android Studio wizard purples) is a placeholder. A deliberate,
mature violet scheme gives the product family a consistent identity without straying from "neutral
but purple."

The palette below is a proposal — hex values should be validated against WCAG 2.1 AA contrast before
landing in Phase 2.

#### Light scheme

| Role                 | Hex       | Notes                                     |
|----------------------|-----------|-------------------------------------------|
| primary              | `#5A4A96` | Deep muted violet — brand anchor          |
| onPrimary            | `#FFFFFF` |                                           |
| primaryContainer     | `#E6DEFF` |                                           |
| onPrimaryContainer   | `#18034F` |                                           |
| secondary            | `#5F5C71` | Neutral gray-violet — supporting surfaces |
| onSecondary          | `#FFFFFF` |                                           |
| secondaryContainer   | `#E5DFF9` |                                           |
| onSecondaryContainer | `#1B1A2C` |                                           |
| tertiary             | `#7B5265` | Muted mauve — accents, highlights         |
| onTertiary           | `#FFFFFF` |                                           |
| tertiaryContainer    | `#FFD8E7` |                                           |
| onTertiaryContainer  | `#301120` |                                           |
| error                | `#BA1A1A` |                                           |
| errorContainer       | `#FFDAD6` |                                           |
| onError              | `#FFFFFF` |                                           |
| onErrorContainer     | `#410002` |                                           |
| background           | `#FEF7FF` | Tone 98 — amended 2026-11-01 (§1a)        |
| onBackground         | `#1C1B1F` |                                           |
| surface              | `#FEF7FF` | Tone 98 — amended 2026-11-01 (§1a)        |
| onSurface            | `#1C1B1F` |                                           |
| surfaceVariant       | `#E7E0EC` |                                           |
| onSurfaceVariant     | `#49454E` |                                           |
| outline              | `#7A757F` |                                           |
| outlineVariant       | `#CAC4CF` |                                           |
| inverseSurface       | `#313033` |                                           |
| inverseOnSurface     | `#F4EFF4` |                                           |
| inversePrimary       | `#CBC0FF` |                                           |
| surfaceTint          | `#5A4A96` | Mirrors primary                           |
| surfaceDim           | `#DED8E1` | §1a                                       |
| surfaceBright        | `#FEF7FF` | §1a                                       |
| surfaceContainerLowest | `#FFFFFF` | §1a                                     |
| surfaceContainerLow  | `#F7F2FA` | §1a — Fit's cards                         |
| surfaceContainer     | `#F3EDF7` | §1a — scrolled top bar                    |
| surfaceContainerHigh | `#ECE6F0` | §1a                                       |
| surfaceContainerHighest | `#E6E0E9` | §1a                                    |
| scrim                | `#000000` |                                           |

#### Dark scheme

| Role                 | Hex       | Notes           |
|----------------------|-----------|-----------------|
| primary              | `#CBC0FF` | Elevated violet |
| onPrimary            | `#2C1E71` |                 |
| primaryContainer     | `#42347D` |                 |
| onPrimaryContainer   | `#E6DEFF` |                 |
| secondary            | `#C8C3DC` |                 |
| onSecondary          | `#302E42` |                 |
| secondaryContainer   | `#474459` |                 |
| onSecondaryContainer | `#E5DFF9` |                 |
| tertiary             | `#EBB8CB` |                 |
| onTertiary           | `#482535` |                 |
| tertiaryContainer    | `#613B4D` |                 |
| onTertiaryContainer  | `#FFD8E7` |                 |
| error                | `#FFB4AB` |                 |
| errorContainer       | `#93000A` |                 |
| onError              | `#690005` |                 |
| onErrorContainer     | `#FFDAD6` |                 |
| background           | `#141218` | Tone 6 — §1a    |
| onBackground         | `#E6E1E6` |                 |
| surface              | `#141218` | Tone 6 — §1a    |
| onSurface            | `#E6E1E6` |                 |
| surfaceVariant       | `#49454E` |                 |
| onSurfaceVariant     | `#CAC4CF` |                 |
| outline              | `#948F99` |                 |
| outlineVariant       | `#49454E` |                 |
| inverseSurface       | `#E6E1E6` |                 |
| inverseOnSurface     | `#313033` |                 |
| inversePrimary       | `#5A4A96` |                 |
| surfaceTint          | `#CBC0FF` |                 |
| surfaceDim           | `#141218` | §1a             |
| surfaceBright        | `#3B383E` | §1a             |
| surfaceContainerLowest | `#0F0D13` | §1a           |
| surfaceContainerLow  | `#1D1B20` | §1a             |
| surfaceContainer     | `#211F26` | §1a             |
| surfaceContainerHigh | `#2B2930` | §1a             |
| surfaceContainerHighest | `#36343B` | §1a          |
| scrim                | `#000000` |                 |


#### 1a. The surface ramp (amended 2026-11-01)

The tables above originally set `background`/`surface` only, at the tones of the **pre-2023** M3
baseline (light tone 99 `#FFFBFF`, dark tone 10 `#1C1B1F`). M3 later added the `surfaceContainer*`
roles, and Compose fills any role a scheme leaves out with its **own** defaults — keyed to a tone-98
light page and a **tone-6** dark page. So every card (`surfaceContainerLow`, tone 10) was drawn on a
tone-10 dark page: contrast **1.003**, i.e. the cards were invisible and dark mode read as bright
marks on flat black. Found from Fit Home ("too many hard contrasts" vs Google Fit's paper feel).

⚠⚠ **The surface family is one tonal ramp — page and containers move together.** The page moved
to the tones the containers assume (light 98, dark 6), and all seven container/dim/bright roles are
now spelled out in `Colors.kt`, in the same neutral hue as the rest of the palette (`#1C1B1F` and
`#E6E1E6` are its tones 10 and 90). Dark cards now stand off the page at **1.089**; light cards at
**1.049** (down from 1.077 — the page is faintly tinted rather than near-white, which is the softer
look this was after). `ui-core`'s `SurfaceRampTest` pins the ordering and a ≥ 1.04 card/page step.

Body text gains contrast in both schemes (darker dark page, same `onSurface`), so the validation
step below is unaffected. ⚠ Brand material (`docs/design/brand/*`, launcher icons, web) keeps
`#FFFBFF` — it is not the in-app theme and was deliberately left alone.

**Validation step before Phase 2 lands:** run `primary` on `onPrimary`, `onPrimaryContainer` on
`primaryContainer`, and body text on `background`/`surface` through a contrast checker. Target ≥
4.5:1 for body text, ≥ 3:1 for large text and non-text UI. Adjust hexes if any pair fails.

### 2. Shared UI module name — `ui-core`

Chosen over `design-system`, `ui-shared`, and `tenshu-ui`. Matches the existing `core`, `core-aidl`,
`core-client`, `core-common`, `core-proxy` module naming convention. Short, neutral, and visibly
distinct from the per-app `*-shared` modules.

Gradle path: `:ui-core`. Package root: `app.tenshu.ui`.

### 3. Desktop ambition — first-class product

Desktop is a primary surface. Phase 4 of the improvement plan executes in full:

- `WindowSizeClass` + adaptive scaffold (compact → bar, medium → rail, expanded → drawer)
  — ⚠ **amended 2026-08-11, see A1**: five size classes, and no drawer at any of them
- Persisted window state across sessions
- Keyboard shortcuts (`Ctrl+F` search, `Esc` dismiss, `Delete` on selection, platform-standard for
  the rest)
- Explicit focus order on primary screens
- Hover affordances on interactive surfaces
- Native file dialogs wrapped in a cross-platform abstraction

This decision raises the bar — every new feature needs a "does this work on desktop?" review,
including for touch-vs-pointer affordances and keyboard-only flows.

### 4. iOS styling — Material with iOS accommodations

Not pure Material, not full Cupertino-leaning. Keep the Material 3 visual identity (Amethyst Neutral
palette, M3 components, M3 typography scale) but respect iOS platform conventions that materially
affect interaction:

- Safe area insets (top/bottom) handled via a `SafeAreaPadding` primitive in `ui-core`.
- Swipe-back gesture on navigation where iOS users expect it — either via Compose Multiplatform's
  gesture support or a platform-specific `actual`.
- System font (SF Pro) for body text on iOS; Material type scale for sizing/weight. This is an
  `expect/actual` on `AppTypography`.
- Cupertino-style alerts for destructive confirmations (delete, sign out) — either via
  `UIAlertController` bridge or a Material `AlertDialog` styled to feel at home.
- Native haptics on iOS via `expect/actual` `AppHaptics` helper.
- Status bar / nav bar tint responds to iOS light/dark correctly.

Anything not on this list stays pure Material 3. The line is: platform accommodations only for
interactions iOS users would otherwise experience as broken, not for aesthetic parity with native
iOS apps.

### 5. Material 3 Expressive — defer

Not adopted in Phase 0. Revisit at Phase 7 (per the improvement plan) once:

- JetBrains Compose is bumped from 1.7.3 to ≥ 1.8 (required for Desktop/iOS Expressive APIs).
- Phases 1–4 have landed, so there's a stable token layer to map Expressive's motion scheme and
  shape tokens onto.

⚠ **Both preconditions are now met — see A2.** This deferral is expired, not in force.

## Consequences

### Positive

- Single source of truth for all visual design across Drive, Control, Android, Desktop, iOS.
- Brand identity becomes coherent — Control stops looking like a wizard-generated Android app.
- Desktop gets adaptive layouts solved once in `ui-core` rather than per screen.
- iOS feels platform-appropriate without maintaining a parallel Cupertino design path.

### Negative / trade-offs

- First-class desktop raises the scope of every future UI feature. Touch-only patterns (e.g., bottom
  sheets triggered by long-press) need a pointer-friendly alternative.
- "Material with iOS accommodations" means some components (typography, haptics, alerts, gestures)
  now carry `expect/actual` complexity. Adds a small amount of per-component overhead compared to
  pure Material.
- The custom palette needs validation and possibly designer review before Phase 2 lands — if the
  values fail contrast or feel off, expect a hex-tuning round.
- Deferring M3 Expressive means the product won't look like the latest Material demos for a while.
  That's fine; Expressive isn't on the critical path.

## Out of scope

- Full Cupertino-leaning iOS design.
- Figma token sync / design tool integration.
- Dynamic color / Material You wallpaper theming.
- Rebrand beyond the palette chosen here.

## Next step

Proceed to Phase 1 of [`proposals/design-improvement-plan.md`](../proposals/design-improvement-plan.md)
— scaffold the empty `ui-core` module,
wire into `settings.gradle.kts`, make `drive-shared` and `control-shared` depend on it. Estimated ~1
day.

---

## Amendments

The decisions above are left as they were written; this section records where the world moved
underneath them. An ADR is a record of what was decided and why, so the original text stays intact
and the amendment sits beside it.

### A1 — §3's navigation mapping, 2026-08-11

**What changed.** §3 specified `compact → bar, medium → rail, expanded → drawer` over three window
size classes. There are now **five** size classes and **no drawer at any of them**:

| Class | Width | Chrome |
|---|---|---|
| Compact | < 600dp | `NavigationBar`, bottom |
| Medium | 600–839dp | `WideNavigationRail`, collapsed (96dp) |
| Expanded | 840–1199dp | `WideNavigationRail`, collapsed (96dp) |
| Large | 1200–1599dp | `WideNavigationRail`, expanded (220–360dp, content-sized) |
| ExtraLarge | ≥ 1600dp | as Large |

**Why.** Two independent reasons, and it is worth separating them because only one is a mistake.

1. **Material 3 deprecated the navigation drawer** in favour of the expanded navigation rail, which
   "adapts better across breakpoints", and grew the breakpoint model from three classes to five.
   §3 was faithful to the spec as it stood in April 2026. This half is drift, not error.
2. **The drawer never worked as intended anyway.** It rendered a fixed 300dp at every window width
   and with every label set, because `PermanentDrawerSheet`'s `widthIn` cannot beat
   `NavigationDrawerItem`'s internal `fillMaxWidth()`. Measured: the body pane's left edge sat at
   300.0dp at a 1024px window and at a 1600px window, with `"Fit"` and with `"Recently deleted"`
   alike. ⚠ That was true from the day §3 shipped and nothing caught it for four months — no test
   in the repo measured navigation chrome, and the Android smoke gate only asserts that nothing
   crashes. A 300dp sidebar is not a crash.

**What else moved with it.** The body pane now carries the Material 24dp window margin from Medium
up (there was none, at any size class), and the rail's expand/collapse choice persists per desktop
app. Full sweep, decisions and as-built:
[`proposals/ui-adaptive-navigation.md`](../proposals/ui-adaptive-navigation.md).

**Unchanged by this.** §3's actual decision — *desktop is a first-class product, every feature owes
a "does this work on desktop?" review* — stands, and is what made the defect worth fixing rather
than living with. §4 (iOS = Material with iOS accommodations, not Cupertino) also stands, and is
**strengthened**: Compose Multiplatform ships no Cupertino widgets and no Liquid Glass, and
JetBrains' own guidance is that a native SwiftUI shell is the only route to the latter — which is
already how the iOS apps here are built.

### A2 — §5's deferral of Material 3 Expressive, 2026-08-11

§5 deferred M3 Expressive until "JetBrains Compose is bumped from 1.7.3 to ≥ 1.8". **That
precondition was met on 2026-07-08** (CMP 1.7.3 → 1.11.1). The deferral is therefore expired
rather than still in force; `design-improvement-plan.md` Phase 7 is the tracking item and reads as
blocked on the same lapsed condition.

⚠ Recorded because a stale blocker reads exactly like a live one, and this pair is a worked example
of the cost: A1's defect sat in the one component Phase 7 would have revisited.
