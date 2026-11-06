# Tenshu web design system — shared tokens, scratch-Tailwind components, M3 semantics

**Status:** v0.2 (2026-06-26). **Phases 1–4 shipped** — Phase 1 (token layer), 2
(Drive adoption → dark mode + amethyst), 3 (first shared components on Radix), 4
(apex migration: all 64 surfaces off `gray`/`dark:gray-*` onto tokens, dark-pairs
collapsed). **Track paused after Phase 4 — its goal is met.** The remaining work was
split (2026-06-26) into Phase 5 (`@tenshu/ui` promotion) + Phase 6 (Storybook), both
**deferred** until their triggers (app #2 / ~a dozen components) — see §4. Locks the
strategy (base, tooling, package shape) so the implementation phases run incrementally.
Triggered by the embedded-Drive dark-mode gap and the "standardise for future web apps"
question; this is the concrete first slice of the `web/shared` / `@tenshu/app-kit` layer
deferred in [ADR 0020](../adr/0020-web-app-structure.md).

**Owner:** Benedikt

**Scope note:** this is decision-oriented. The visual language already exists
([`docs/design/brand/`](../design/brand/)); this doc's job is to (1) decide the *base*
and *tooling*, (2) get the documented tokens off the page and into a **shared** layer both
web apps consume, (3) phase the adoption, and (4) say what we explicitly defer.

---

## 1. Where we are

The audit (2026-06-26) found a documented foundation that is **barely wired up**:

- **Brand tokens are documented + source-of-truth.** [`docs/design/brand/tokens/`](../design/brand/tokens/)
  defines amethyst (canonical, ADR 0001) + slate palettes, light **and** dark, plus type,
  radii, spacing, motion. The role names are Material-3 semantics (`--primary`,
  `--on-primary`, `--surface`/`-2`/`-3`, `--text`/`-2`/`-3`, `--line`, `--accent`). The
  "codify" contract lives in [`brand-agent.md`](../design/brand/brand-agent.md).
- **apex emits the palette but does not use it.** [`web/apex/src/index.css`](../../web/apex/src/index.css)
  carries the `--surface`/`--text`/… custom properties with light + dark values — but
  `var(--token)` usage across `web/apex/src` is **0**. apex components are styled with raw
  Tailwind grays instead: **429** `dark:gray-*` occurrences.
- **Drive uses raw `slate`, no dark at all.** [`web/drive`](../../web/drive) hardcodes
  `bg-slate-50` / `text-slate-800` / … with **zero** `dark:` variants, so it renders light
  regardless of OS — the white-iframe-in-dark-apex bug that started this.
- **No shared layer, no components, no Tailwind mapping.** `web/` is `apex` + `drive` +
  `sdk`. There is no `web/shared`, no `@tenshu/ui`, and the codify contract's "Tailwind
  reads the same tokens" step (brand-agent.md §"How to codify → Web" step 4) was never
  done (the apps are Tailwind v4, which has no `tailwind.config.ts`).

**Consequence:** the two web apps are inconsistent with *each other* (gray vs slate) and
with the *brand* (neither uses amethyst), and a third app would start the same ad-hoc way.
The design system is **aspirational, not operational**.

## 2. Goals / non-goals

**Goals**
- One shared, themeable token layer both apps (and future apps) consume — light + dark.
- Drive gains dark mode + amethyst brand as a *consequence* of adopting tokens, not a
  parallel `dark:gray-*` sweep.
- A documented, repeatable styling contract so app #3 starts on-system.

**Non-goals (this proposal)**
- A full component library. Deferred — see [§4 Phases](#4-phases) (Phase 5 `@tenshu/ui`).
- Migrating all of apex's 429 grays at once. Incremental — [Phase 4](#phase-4-apex-migration-incremental).
- A runtime theme switcher. Dark mode stays OS-driven (see D6).
- The Drive nav consolidation (6→4 tabs). That's an **IA** change, not a design-system
  one; it ships independently (tracked alongside, noted in §6).

## 3. Decisions (locked)

### D1 — Base: Material 3 *token semantics*, not a Material *component library*, not Expressive

Native is already Material 3 (`compose.material3` in `ui-core` + `drive-shared-ui`), and
the brand tokens are M3 role-semantics. So Tenshu *is* "M3 with a custom amethyst theme."
We keep that:

- **Design language / token roles → M3.** Already have it; keeps web aligned with native.
  Don't reinvent the role taxonomy.
- **Component library → NOT Material Web Components / MUI.** For a Tailwind + React stack a
  Material component lib is heavy, imposes Google's look, fights Tailwind, and would be
  overridden wholesale to reach amethyst. Uncommon and counterproductive.
- **Not "M3 Expressive."** Expressive M3 is bold/playful/energetic; the brand is "quiet
  authority, faintly spiritual, muted violet" (ADR 0001). Take M3's *structure*, not its
  Expressive *flavor*.

### D2 — Components: scratch-Tailwind on headless primitives (shadcn-style)

Build components ourselves with Tailwind, on **unstyled/headless primitives** (Radix UI or
React Aria) for the accessibility-hard ones — menus, dialogs, listboxes, tooltips, popovers.
This is the modern idiom; **shadcn/ui** is the canonical reference (Tailwind + Radix + your
own tokens, copy-in components, no lock-in). We do **not** add shadcn as a dependency — we
follow its *pattern* against our own tokens.

Rationale: we already hand-rolled `RowMenu` (a portal dropdown) and several dialogs in
Drive; a headless primitive would have given us focus-trapping, keyboard nav, and
positioning for free. The first extracted components should be the a11y-load-bearing ones.

### D3 — Shared token layer: `web/shared`, CSS vars → Tailwind v4 `@theme`, light + dark

A new workspace package (working name `@tenshu/web-tokens`, dir `web/shared/`) owns one
generated stylesheet:
- emits the amethyst (and slate) roles as CSS custom properties, **light + dark**, from
  `docs/design/brand/tokens/` per the codify contract;
- maps them into Tailwind v4 via `@theme { --color-surface: var(--surface); … }` so
  `bg-surface`, `text-text`, `border-line`, `bg-primary`, `text-on-primary`, plus radii
  (`rounded-m`) and spacing become **first-class utilities**;
- both `web/apex` and `web/drive` `@import` it instead of defining tokens locally.

This is the codify contract's never-done "Tailwind reads the same tokens" step, adapted to
Tailwind v4 (`@theme` mapping replaces the v3 `tailwind.config.ts` + `tokens.ts` shim the
contract names). **brand-agent.md gets a small amendment** to point the Web codify target at
`web/shared` + describe the `@theme` mapping (decisions land in docs, same PR).

### D4 — Dark mode: OS-driven via `prefers-color-scheme`, no toggle (for now)

apex already follows the OS (no switcher); the tokens carry both schemes. Drive adopting
the tokens inherits dark automatically through the same media query — **no cross-origin
theme plumbing** needed between apex and the embedded Drive iframe, because both documents
see the same `prefers-color-scheme`. The token CSS keeps the `[data-theme]` overrides so a
future explicit switcher works without re-codifying. **If** a manual switcher ever lands,
the cross-origin iframe will then need a postMessage theme sync — out of scope here, flagged.

### D5 — apex migration is incremental, not a blocker

Drive adopts tokens first (it's the one that's broken). apex keeps its 429 grays working
and migrates opportunistically (D-prefixed surfaces are already dark-correct via gray). The
brand tokens are the source of truth, so Drive-on-tokens is the *correct* one; apex catches
up. In dark mode both render dark surfaces, so the embedded view won't clash during the
overlap.

### D6 — Storybook + `@tenshu/ui` deferred until patterns repeat

Storybook is the right workshop/catalog/a11y-test/visual-regression tool **once a shared
component library exists**. As of Phase 3 there are four (Button/Field/Menu/Dialog) — still
too thin to earn a Storybook, so it stays at Phase 6, not before (the old Phase 5 was split
2026-06-26 into Phase 5 = `@tenshu/ui` promotion, Phase 6 = Storybook). Stand it up with
`addon-a11y` (optionally a Storybook MCP server for agent workflows) when the set nears ~a
dozen. Note the lightweight
precedent that already exists: [`foundations.html`](../design/brand/foundations.html) is a
static token/mark/component reference served via `python3 -m http.server`.

## 4. Phases

Each phase is independently shippable. Phases 1–2 deliver the dark-mode fix on a real
foundation; 4–5 are follow-ons.

### Phase 1 — `web/shared` token foundation

- New workspace package `web/shared/` (`@tenshu/web-tokens`), added to
  [`pnpm-workspace.yaml`](../../web/pnpm-workspace.yaml).
- A generated `tokens.css`: amethyst + slate roles as CSS custom properties, light +
  `@media (prefers-color-scheme: dark)` + `[data-theme]` overrides (lift the existing apex
  block; it's already correct), **plus** a Tailwind v4 `@theme` mapping exposing semantic
  utilities (`surface`, `surface-2`, `surface-3`, `text`, `text-2`, `text-3`, `primary`,
  `primary-soft`, `on-primary`, `accent`, `line`, `line-strong`, `success`, `danger`,
  `warning`; radii; spacing).
- Both apps import it; remove apex's inline token block (it moves to the shared file).
- Amend [`brand-agent.md`](../design/brand/brand-agent.md) §"How to codify → Web" to target
  `web/shared` + the `@theme` mapping.
- **No visual change yet** — apex still uses grays, Drive still slate; this only makes the
  utilities available.

### Phase 2 — Drive adopts the tokens (dark mode + brand)

- Convert `web/drive`'s ~24 component files from raw `slate`/`white`/`red` utilities to
  semantic token utilities (`bg-slate-50` → `bg-surface`, `text-slate-800` → `text-text`,
  `border-slate-200` → `border-line`, primary actions → `bg-primary text-on-primary`, etc.).
- ~Same effort as a naive `dark:` sweep (~359 occurrences) but yields dark mode **and**
  amethyst, and the tokens carry the dark values so no per-utility `dark:` pairs.
- Verify embedded (in apex chrome) and standalone (`:5174`) both follow OS dark/light.
- Tests: existing Drive vitest stays green (class-name assertions are minimal; spot-check).

### Phase 3 — first shared components (only what Drive already duplicates)

- Extract the a11y-load-bearing primitives Drive hand-rolls today into `web/shared`
  (still pre-Storybook): a headless **Menu** (replacing the portal `RowMenu`), **Dialog**,
  and the **Button**/**Field** token wrappers. Built on Radix/React Aria per D2.
- Adopt them in Drive; apex can adopt incrementally.
- This is the seed of `@tenshu/ui`; keep it small and demand-driven (no speculative set).

### Phase 4 — apex migration (incremental)

- Replace apex's `gray`/`dark:gray-*` with semantic token utilities, surface by surface.
- Non-blocking, opportunistic; can trail for as long as needed. Done when `var(--token)` /
  semantic-utility usage replaces the 429 grays.

### Phase 5 — `@tenshu/ui` promotion (DEFERRED — trigger not yet met)

- Promote `web/shared/ui` from a subpath of `@tenshu/web-tokens` into a standalone,
  documented `@tenshu/ui` package.
- **Deliberately not done yet** (decided 2026-06-26 after Phase 4): at one consumer
  (Drive) and four components, the promotion is pure packaging churn with no functional
  gain — the subpath `@tenshu/web-tokens/ui` works fine. The split only pays off alongside
  a catalog (Phase 6) and a second consumer.
- **Trigger:** app #2 consumes the components, **or** the set crosses ~a dozen. Until then
  new components keep landing under `web/shared/ui` (demand-driven, per D2).

### Phase 6 — Storybook + `addon-a11y` (DEFERRED — trigger not yet met)

- Stand up Storybook + `addon-a11y` (+ optional Storybook MCP for agent workflows) as the
  catalog / visual-regression / a11y harness, with light/dark token backgrounds.
- **Split out from the old Phase 5 and deliberately deferred** (2026-06-26): at four
  components a Storybook is too thin to earn its keep (D6 — gated on "once a shared
  component library exists … the set crosses ~a dozen"), and Radix already carries the
  a11y weight the addon would check.
- **Trigger:** the component set approaches ~a dozen, or a11y/visual-regression coverage
  becomes a felt need.

**Track status:** Phases 1–4 are shipped; the design system's actual goal (one themeable
token layer both apps consume, Drive + apex dark-correct on amethyst, the first a11y
primitives) is **met**. Phases 5–6 are infrastructure with explicit triggers that are not
yet met, so the track is **paused after Phase 4** rather than pushed further speculatively.

## 5. How it fits the bigger picture

- **ADR 0020** named `web/shared` / `@tenshu/app-kit` as the reusable per-app layer,
  deferred "until app #2." The token layer is the smallest, highest-value slice of it and
  has a concrete need now (Drive's theme), so it graduates early. `app-kit` (bridge
  bootstrap, session guard) and `@tenshu/ui` (components) remain later slices.
- **Codify contract**: this operationalises brand-agent.md's Web step for real and amends
  it to the shared location.
- **Per-app contract (ADR 0020 §1)** gains one line: a Tenshu web app imports
  `@tenshu/web-tokens` and styles with semantic utilities — that's how app #3 starts
  on-brand and dark-correct for free.

## 6. Related but out of scope: Drive nav consolidation

Independent IA change (not design-system): native Drive has **Home / Files / Shared /
Settings** ([`Destination.kt`](../../drive-shared-ui/src/commonMain/kotlin/app/tenshu/drive/ui/navigation/Destination.kt)),
folding contacts into the sharing/manage-access flow and *Shared with me* into a Shared
sub-tab. Web diverged to 6 top-level tabs (added **People** + **Shared with me**).
Recommendation: collapse web to match native — **Shared** becomes a segmented container
holding the existing `SharedFoldersView` / `SharedWithMeView` / `PeopleView` as panels.
Ships independently of the design-system phases; noted here so it isn't lost.

## 7. Open questions

- **Package name**: `@tenshu/web-tokens` vs folding tokens into a broader `@tenshu/ui` from
  the start. Leaning tokens-only first (D3) — smaller blast radius.
- **Headless primitive**: ~~Radix UI vs React Aria~~ **DECIDED Phase 3 → Radix UI.** Against
  the first real component (`Menu`), Radix is the lighter, shadcn-canonical fit (D2) and
  composes cleanly with our token utilities via `asChild`. `@radix-ui/react-dropdown-menu` +
  `@radix-ui/react-dialog` are dependencies of `@tenshu/web-tokens`; React Aria's stronger
  i18n wasn't needed (menu/dialog copy is app-supplied, already i18n'd app-side). Revisit
  only if a component needs React Aria's collection/i18n machinery.
- **Slate palette**: brand defines a secondary slate; neither app needs it yet. Wire amethyst
  only in Phase 1; add slate when a surface demands it.
- **Type scale**: web is on Geist (brand-agent.md). Fold `tokens/type.ts` sizes into the
  `@theme` in Phase 1, or leave type ad-hoc until a real need? Leaning: include the scale.

## 8. Implementation Notes for Claude Code

Canonical handoff. Earlier sections are rationale.

| Step | Files |
|------|-------|
| P1 package | `web/shared/package.json` (`@tenshu/web-tokens`, private), `web/shared/tokens.css`, add `"shared"` to `web/pnpm-workspace.yaml` |
| P1 tokens | lift the `:root` + dark blocks from `web/apex/src/index.css`; add `@theme { --color-surface: var(--surface); … }` mapping; generated from `docs/design/brand/tokens/palettes.ts` per codify |
| P1 wire | `web/apex/src/index.css` + `web/drive/src/index.css` → `@import '@tenshu/web-tokens/tokens.css'`; drop apex's inline token block |
| P1 docs | amend `docs/design/brand/brand-agent.md` §"How to codify → Web" (target `web/shared`, `@theme` mapping for v4) |
| P2 adopt | sweep `web/drive/src/**`: `slate/white/red` utilities → semantic token utilities; verify dark in embedded + standalone |
| P3 components | `web/shared/ui/{Menu,Dialog,Button,Field}.tsx` on Radix/React Aria; adopt in `web/drive` (replace `RowMenu`, `Dialog.tsx`) |
| P4 apex | incremental `gray*` → token utilities across `web/apex/src/**` |
| P5 promotion (deferred) | `@tenshu/ui` package — move `web/shared/ui/*` out of `@tenshu/web-tokens`; trigger: app #2 / ~a dozen components |
| P6 catalog (deferred) | Storybook + `addon-a11y` over `web/shared/ui`; trigger: set nears ~a dozen |

Per-worktree reminder: rebuild the JS SDK bundle + copy `local.properties` (see the
web-dev memory); the token CSS is plain static, no Gradle step.

## 9. References

- [ADR 0020 — Web app structure](../adr/0020-web-app-structure.md) (`web/shared` / app-kit)
- [`docs/design/brand/brand-agent.md`](../design/brand/brand-agent.md) (codify contract) +
  [`tokens/`](../design/brand/tokens/) (source of truth) + [`foundations.html`](../design/brand/foundations.html)
- [`web/architecture.md`](../web/architecture.md) §12.5 (TS+React, not CMP-web), §12.8 (layout)
- [drive-web-app.md](drive-web-app.md) §2 (thin SDK), §7 (types-only import)
- ADR 0001 (amethyst canonical brand)
