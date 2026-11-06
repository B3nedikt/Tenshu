# Tenshu Web — Visual Design Production Plan

> Status: **draft v0.2** — pulls from `foundations.html`, `marks/Outline.tsx` + `marks/Solid.tsx`,
> `tokens/palettes.ts`, `marks/mark.svg`. Phase-1 open questions are now resolved (§9). Sequencing
> and remaining gaps called out inline; nothing here is locked except the items marked
> **✅ locked**.

---

## 0. Snapshot of what's locked today

| Layer                    | Decision                                                                         | Source                         |
|--------------------------|----------------------------------------------------------------------------------|--------------------------------|
| ✅ Mark set               | **Outline (primary)**, **Solid (secondary)** — same cat, triangle ears (ADR 0004). Hanko parked.  | `marks/*.tsx`, `marks/mark.svg` |
| ✅ Canonical palette      | **Amethyst** (ADR 0001) — `#5A4A96` primary, `#7B5265` accent                    | `tokens/palettes.ts`            |
| ✅ Secondary palette      | **Slate** — hue-free fallback for data-dense and co-branded surfaces             | `tokens/palettes.ts`            |
| ✅ Retired palettes       | Indigo, Jade — out of the system (ADR 0002)                                      | —                               |
| ✅ Type families (web)    | Geist Sans + Geist Mono                                                          | `tokens/type.ts`                |
| ✅ Type fallback (native) | iOS/macOS → San Francisco · Android → Roboto / Roboto Flex                       | `tokens/type.ts`                |
| ✅ Type scale             | mono 11 / caption 12 / body 14–16 / lead 20 / h3 24 / h2 36 / h1 56 / display 96 | `tokens/type.ts`                |
| ✅ Radii                  | xs 2 / s 4 / m 8 / l 14 / pill                                                   | `tokens/radii.ts`               |
| ✅ Spacing scale          | s1 4 → s9 96 (×4 base; multiplier kept for a future density mode)                | `tokens/spacing.ts`             |
| ✅ Density                | **Single mode** for v0.1 — no user-selectable density (ADR 0023)                 | §9, ADR 0023                    |
| ✅ Surface roles          | surface / surface-2 / surface-3, line / line-strong, success / danger / warning  | `tokens/palettes.ts`            |
| ✅ Dark-mode commitment   | **Marketing + product**, both palettes                                           | §9                              |
| 🟡 Imagery direction     | Direction set per tier (§6); asset commissioning still to do                     | §6                             |
| ❌ Component library      | **not started**                                                                  | —                              |
| ❌ Layout / grid system   | **not started**                                                                  | —                              |
| ❌ Motion                 | **not started**                                                                  | —                              |
| ❌ Iconography            | **not started**                                                                  | —                              |
| ❌ Surface inventory      | **not started**                                                                  | —                              |

---

## 1. Surface inventory

The full set of screens we will eventually deliver pixels for. Group order ≈ build order.

### 1.1 Marketing

- Landing
- Pricing
- About / story
- Changelog
- Legal (terms, privacy)
- 404 / 500

### 1.2 Auth

- Sign in
- Sign up
- Forgot password / reset
- Verify email
- SSO callback

### 1.3 App shell

- App nav (top bar + side rail)
- User menu
- Workspace switcher
- Command palette
- Global toast / notification stack

### 1.4 Core product surfaces

*To be filled in by product. Placeholder: home, document, settings.*

### 1.5 Settings

- Profile
- Account / security
- Workspace
- Billing
- Integrations
- Danger zone

### 1.6 Empty / error / loading states

- Empty (first-run, filtered, permission-denied) for every list surface
- Error (network, server, 404 inside app)
- Skeleton / loading variants

### 1.7 System artefacts

- Favicon set (16, 32, 180, 192, 512)
- OG / Twitter card template
- Email templates (welcome, magic link, receipt, alert)
- PDF / print stylesheet

---

## 2. Component inventory

Each component ships with: **default · hover · focus-visible · active · disabled · loading**, plus
light + dark.

### 2.1 Atoms

- Button (primary, secondary, ghost, danger; sm / md / lg; icon-only)
- Icon button
- Input (text, email, password, number, search)
- Textarea
- Select / combobox
- Checkbox, radio, switch
- Slider
- Tag / chip / badge
- Avatar (single, stacked, with status)
- Tooltip
- Kbd / shortcut
- Spinner / progress

### 2.2 Molecules

- Form field (label + input + helper + error)
- Field group / fieldset
- Menu / dropdown
- Tabs
- Breadcrumb
- Pagination
- Toast
- Banner / inline notice
- Empty-state block
- Stat / metric card

### 2.3 Organisms

- Top bar
- Side nav
- Page header (title, description, actions)
- Data table
- Card list
- Modal / dialog
- Drawer / sheet
- Command palette

### 2.4 Templates

- Marketing page
- Auth page
- App page (header + content + optional rail)
- Settings page (split sidebar / content)
- Empty / error page

---

## 3. Layout & grid

- **Breakpoints (proposed):** `sm 640 / md 768 / lg 1024 / xl 1280 / 2xl 1536`. 1280 is the primary
  design target (matches `Brand Foundations.html` shell).
- **Container max-widths:** marketing 1280, app 1440, content prose 720.
- **Column rule:** 12-column at lg+, 6-column at md, stack at sm. Gutter = `--s-5` (24).
- **Density:** single mode. The `--space-scale` / `--type-scale` multipliers stay in the tokens so a
  future density toggle can rescale without touching every component.

---

## 4. Motion

Principles to define before any component animates.

- **Duration scale:** xs 80ms · s 140ms · m 220ms · l 360ms · xl 600ms.
- **Easing tokens:** `standard`, `enter`, `exit`, `emphasised` (M3-style).
- **Rules of thumb:**
    - State changes (hover, focus) → xs–s, standard easing.
    - Surface in/out (toast, modal) → m, enter / exit.
    - Page transitions → l, emphasised, only on routes that aren't just navigation.
    - Respect `prefers-reduced-motion` everywhere.
- **No bouncing, no spring overshoot** — the mark vocabulary is quiet; motion should be too.

---

## 5. Iconography

- **Stroke weight:** 1.75 at 24px nominal, scaling with `currentColor`.
- **Corner radius:** 2px on terminals, sharp on joins (matches `--r-xs`).
- **Sizes:** 14, 16, 20, 24, 32. 24 is canonical.
- **Source:** Lucide as base set, with a small bespoke pack for Tenshu-specific concepts (mark,
  archive, realm, relay). Audit list to be drawn up before commissioning.

---

## 6. Imagery

Direction is **set**; commissioning the actual assets is the remaining work. One recipe per tier —
never mix within a surface.

### 6.1 Marketing — relatively quiet

- **Primary treatment.** Single-weight line illustration in `--primary` over `--surface-2`. Same
  stroke vocabulary as the icon set, scaled up.
- **Secondary treatment.** Desaturated editorial photography, used sparingly when it carries
  information line illustration can't (people, devices in context). Tuned to fit the Amethyst
  temperature.
- **Mix rule.** Pick one per surface. Line and photo never share a hero.

### 6.2 Product — minimal

- **Default.** No imagery. Most product surfaces are productive — layout, type and the cat carry the
  brand.
- **When used.** Empty states, error pages, onboarding step markers — minimal line illustrations
  only, same stroke vocabulary as icons.

### 6.3 In-app personalisation — future

- Surfaces like timeline / overview should adapt to the *user*, not lean on stock illustration. Two
  paths to evaluate when the work lands:
    - **a)** Material 3 Expressive (or equivalent) — palette and shape adapt to user content /
      preference.
    - **b)** Personal imagery — when the user has already brought photos into Drive, surface them on
      the home / timeline as a personal backdrop.
- Tracked separately. Out of scope for v0.1.

### 6.4 Tier B — emails, OG, system artefacts

- Inherit the marketing recipe. Line illustration over `--surface-2` for hero blocks, no photography
  in transactional emails.

---

## 7. Production sequencing

Eight phases, each ending in a review gate.

1. **Foundations freeze ✅** — palette retirements landed (Amethyst + Slate), Hanko parked, density
   set to single mode, dark-mode committed for both tiers, type fallback chosen per platform.
2. **Core atoms** — buttons, inputs, form field, badge, avatar, tooltip. Ship in light + dark.
3. **App shell** — top bar, side nav, page header, command palette stub.
4. **Marketing landing + pricing** — first public-facing surfaces; force the imagery commission (
   line set + first desaturated photo).
5. **Auth flows** — full sign-in / sign-up / reset, including all error states.
6. **Settings template + 3 settings pages** — establishes the split-layout pattern.
7. **Core product surfaces** — once product scope is final.
8. **System artefacts** — favicons, OG, email, print.

Each phase ships:

- Designs in the upstream design project (`claude.ai/design`), then dropped into this folder.
- A short ADR under `docs/adr/` if a decision changes.
- Updated tokens under `tokens/` and (if needed) updated marks under `marks/`.

---

## 8. Handoff

- **Source of truth:** this folder (`docs/design/brand/`). Tokens live under `tokens/`; marks live
  under `marks/` (React + Compose + canonical SVG); the visual reference is `foundations.html`.
  Iterated upstream in `claude.ai/design` and dropped here per round.
- **Naming:** per-token-family `<family>.ts` files (`palettes.ts`, `type.ts`, ...); per-mark
  `<MarkName>.tsx` + `<MarkName>.kt`.
- **Tokens → code:** CSS custom properties on web, Compose values on KMP. No hard-coded hex
  outside `tokens/palettes.ts`. Codify rules per platform live in `brand-agent.md`.
- **Review:** raise comments against this folder via the standard PR flow.

---

## 9. Resolved questions (Phase 1)

1. **Palettes.** Keep **Amethyst (primary)** and **Slate (secondary)**. Retire Indigo and Jade. →
   `palettes.js`, ADR 0002.
2. **Density.** **Single mode** for v0.1. Keep the `--space-scale` / `--type-scale` multipliers so a
   future toggle is a one-line change.
3. **Imagery (§6).**
    - *Marketing:* line illustrations primary, desaturated editorial photography secondary, kept
      relatively quiet.
    - *Product:* minimal line illustrations only — most surfaces stay imagery-free because they're
      productive.
    - *In-app personalisation* (timeline, etc.): adapts to the user — M3 Expressive direction or
      personal photos pulled from Drive. Future work.
4. **Dark mode.** Committed for **marketing and product**, both palettes. Every component ships
   light + dark from day one.
5. **Type fallback.**
    - *Web:* Geist Sans + Geist Mono, with a system stack underneath.
    - *iOS / macOS:* San Francisco via the standard Apple stack — no webfont.
    - *Android:* Roboto / Roboto Flex via the system default.
6. **Mark usage.** **Outline is primary — used wherever possible.** Solid is secondary, reserved for
   app icons, favicons and reversed chrome where Outline loses presence at small sizes. Hanko is *
   *parked** (not in v0.1). Triangle ears reinstated in v7 (ADR 0004); Solid whiskers stay outlined in
   `currentColor`, extending outward from the cheek so they read like the Outline mark.

---

## 10. Out of scope (for v0.1)

- Native (iOS / Android) shells — separate plan, but type fallback is fixed (§9.5) so we're not
  painted into a corner.
- Hanko mark variant — parked; revisit when there's a surface (signed content, certificates) that
  genuinely needs it.
- In-app personalisation imagery — see §6.3.
- Marketing video / motion graphics — TBD.
- Localisation visual treatment beyond LTR + Latin glyph coverage.
