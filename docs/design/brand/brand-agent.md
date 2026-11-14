# Tenshu — Brand Foundations (handoff)

This folder is the **source of truth** for Tenshu's visual brand. It is produced
in the design project at `claude.ai/design` and dropped here whenever the brand
ships a new version.

When this folder changes, propagate the changes into the codebase. Don't edit
the generated values in `web/` or `ui-core/` directly — they will be regenerated
the next round and your edits will be lost.

## What's here

| File                                                       | Source of truth for                                                                                                                                                                                                                                                                                                                       |
|------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `foundations.html`                                         | Visual reference. Open in a browser to see every token, mark, and component the system defines. Loads the four sidecar files below at runtime. Some browsers block module loading from `file://` — serve via `python3 -m http.server` if so. **Not consumed by the build.**                                                              |
| `app.jsx`, `marks.jsx`, `palettes.js`, `tweaks-panel.jsx`  | Runtime sidecars for `foundations.html`. **Mirror** the values in `tokens/palettes.ts` and the components in `marks/Outline.tsx` + `marks/Solid.tsx`. *Not the source of truth* — `tokens/` and `marks/` are. The JSX is iterated upstream in `claude.ai/design` and dropped here per round; if you change a hex in `palettes.js` without changing `tokens/palettes.ts` (or vice versa), the next codify round silently drifts. See "Sidecar/source mirror discipline" below. |
| `production-plan.md`                                       | Phase plan, locked decisions, open questions. Read before changing tokens or marks.                                                                                                                                                                                                                                                       |
| `tokens/palettes.ts`                                       | Amethyst (primary) + Slate (secondary) palettes, light + dark, full role contract.                                                                                                                                                                                                                                                        |
| `tokens/type.ts`                                           | Type scale, families, per-platform font fallback (web / iOS / Android).                                                                                                                                                                                                                                                                   |
| `tokens/radii.ts`                                          | Radius scale (xs 2 / s 4 / m 8 / l 14 / pill).                                                                                                                                                                                                                                                                                            |
| `tokens/spacing.ts`                                        | Spacing scale (s1 4 → s9 96).                                                                                                                                                                                                                                                                                                             |
| `tokens/motion.ts`                                         | Duration + easing tokens.                                                                                                                                                                                                                                                                                                                 |
| `tokens/glass.ts`                                          | Web overview roles beside the palettes: the wallpaper, glass cards, `--well` and the rose `--attention` (ADR 0027). Web only.                                                                                                                                                                                                              |
| `tokens/apps.ts`                                           | Per-app icon colour pairs for the web apex chrome (ADR 0027). ⚠ Native launcher icons keep ADR 0026's tokens.                                                                                                                                                                                                                              |
| `marks/Outline.tsx`, `marks/Solid.tsx`                     | React (web) implementations of the two cat marks.                                                                                                                                                                                                                                                                                         |
| `marks/Outline.kt`, `marks/Solid.kt`                       | Compose Multiplatform implementations. Package `app.tenshu.ui.components.marks` per ADR 0001.                                                                                                                                                                                                                                             |
| `marks/mark.svg`                                           | Canonical SVG. Mirrors `Outline.tsx` exactly; regenerate from it, never hand-edit paths separately. Used for favicons, OG cards, anywhere outside React/Compose.                                                                                                                                                                          |

## How to codify (the propagate step)

The targets are deterministic. When this folder updates, regenerate the
following — never hand-write the values, always read them from `tokens/`:

### Web (`web/shared/`, `web/apex/`, `web/drive/`, future per-app subdomains)

The web token layer is **shared**, not per-app: it lives in the
`@tenshu/web-tokens` package (`web/shared/tokens.css`) and every app
`@import`s it. Codify into that one file; the apps stay value-free. See
[`docs/proposals/web-design-system.md`](../../proposals/web-design-system.md).

1. **Palettes** → `web/shared/tokens.css` (CSS custom properties). Emit the
   amethyst (canonical) roles for light + a `prefers-color-scheme: dark` block
   + `[data-theme="dark"]`/`[data-theme="light"]` overrides for a future
   switcher. Roles map 1:1 to the keys in `tokens/palettes.ts` (`--primary`,
   `--surface`, etc.). Slate (secondary) is added when a surface needs it.
2. **Type scale + families** → `web/shared/tokens.css`. The web stack stays on
   Geist; native fallbacks defined in `tokens/type.ts` are informational here.
3. **Radii / spacing / motion** → CSS custom properties in the same file.
   **Glass / apps** (`glass.ts`, `apps.ts`) → the same file: glass roles in all four scheme
   blocks plus `@theme inline` (`bg-glass`, `bg-well`, `bg-attention`, `shadow-glass`, …); app
   pairs once on `:root` as `--app-<id>-a/-b` (they do not change with the scheme).
4. **Tailwind v4** → the same `web/shared/tokens.css` carries an
   `@theme inline { --color-surface: var(--surface); … }` block (plus
   `--radius-*` / `--spacing-*` from `radii.ts` / `spacing.ts`) so the roles
   become first-class utilities (`bg-surface`, `text-text`, `border-line`,
   `bg-primary text-on-primary`, `rounded-m`, `p-s5`, …). This replaces the
   v3 `tailwind.config.ts` + `tokens.ts` shim — Tailwind v4 reads the tokens
   via `@theme`, no config file. `inline` makes color utilities flip with the
   media query / `[data-theme]` automatically (no per-class `dark:` pair).
5. **Marks** → `web/apex/src/components/marks/` (copy `Outline.tsx`, `Solid.tsx`
   verbatim; the components are framework-agnostic apart from React).

### KMP (`:ui-core`)

1. **Palettes** → `ui-core/src/commonMain/.../theme/Palettes.kt`. Generate two
   `ColorScheme` (Amethyst, Slate), each with light + dark, from
   `tokens/palettes.ts`. Compose `Color(0x...)` literals; comment each role.
2. **Type scale** → `ui-core/.../theme/Typography.kt`. `TextStyle` per role,
   reading sizes / weights from `tokens/type.ts`. Family resolution per
   platform follows `tokens/type.ts` `fallback` map (San Francisco on Apple,
   Roboto Flex on Android; web doesn't apply).
3. **Radii / spacing / motion** → `ui-core/.../theme/Tokens.kt` as `dp` / `Dp`
   values + `Easing`/`AnimationSpec`.
4. **Marks** → `ui-core/.../components/marks/` (copy `Outline.kt`, `Solid.kt`
   verbatim; both use Compose primitives only).

### Favicons / OG / system artefacts

`marks/mark.svg` is the canonical raster source. Generate the favicon set
(16, 32, 180, 192, 512) and OG card from it; do not redraw.

### Sidecar/source mirror discipline

The four runtime files (`app.jsx`, `marks.jsx`, `palettes.js`, `tweaks-panel.jsx`)
are upstream snapshots that let `foundations.html` render standalone. They
duplicate values that *also* live in `tokens/` and `marks/` because the
preview can't import from `.ts` / `.tsx` without a build step, and the
preview is meant to be no-build. The pairs to keep in sync:

| Sidecar (preview)        | Source-of-truth (codify)            | What can drift |
|--------------------------|-------------------------------------|----------------|
| `palettes.js`            | `tokens/palettes.ts`                | Hex values per role per (palette, scheme). The JS adds a `swatch: [...]` array used by the tweaks-panel chip — codify ignores it. |
| `marks.jsx` (`Mark.Outline`) | `marks/Outline.tsx`             | Path data, eye positions, stroke ratios. Both files MUST match line-for-line on the geometry constants (`HEAD_PATH`, `WHISK_L`, etc.). |
| `marks.jsx` (`Mark.Solid`)   | `marks/Solid.tsx`               | Same geometry contract. Both expose `stroke` prop with `*0.55` whisker ratio and `*0.65` mouth ratio. |
| (none)                   | `marks/Outline.kt`, `marks/Solid.kt` | Compose ports of the React components. Same constants; check after changing geometry. |
| (none)                   | `marks/mark.svg`                    | Static SVG mirror of `Outline.tsx`. Regenerate from it. |
| `app.jsx` `AMETHYST_SHADES` | (preview-only)                  | Four sub-shade overrides used by the tweaks-panel "Amethyst shade" picker. Not part of the codify contract; lives here only. |

When you edit *any* row, edit *every* corresponding cell. The next round's
codify step reads `tokens/` and `marks/` only — silent drift between
sidecar and source means the preview shows one thing and the apps render
another.

### Iconography + imagery (prose-only — no token files yet)

These have concrete numbers in [`production-plan.md`](production-plan.md) but are
*not* tokenized in `tokens/`. Codify rules:

- **Iconography** (production-plan §5): stroke 1.75 at 24px nominal; sizes
  14, 16, 20, 24, 32 (24 canonical); base set is Lucide; bespoke icons go
  under `web/apex/src/components/icons/` and mirror Lucide's coordinate space.
- **Imagery** (production-plan §6): marketing primary = single-weight line
  illustration in `--primary` over `--surface-2` matching icon stroke
  vocabulary; marketing secondary = desaturated editorial photography. Product
  surfaces use minimal line illustration only. Do not introduce a separate
  imagery token system without an ADR.

If either acquires more values worth pinning at the token layer, promote to
`tokens/icons.ts` / `tokens/illustration.ts` and update this doc.

## Naming policy

- **Tenshu's primary mark is the Outline.** Use it wherever it reads cleanly.
- **Solid is reserved** for app icons, favicons, and reversed chrome where the
  outline loses presence at small sizes.
- **Hanko is parked** (not in v0.2). Don't re-introduce it without an ADR.
- **Ears were reinstated** in v7 (see
  `docs/adr/0004-mark-set-v7-ears-reinstated.md`, which supersedes the ears
  decision of ADR 0003). Don't change the silhouette without an ADR.

## Versioning

- Bump the version in `production-plan.md` when the brand ships a new round.
- Every retirement / direction change gets an ADR under `docs/adr/`.
- ADRs from this round: 0002 (Indigo + Jade retired), 0003 (mark set v6),
  0004 (single density mode).

## Don't

- Don't add new palettes ad-hoc. Amethyst (primary) + Slate (secondary). Anything
  else needs an ADR.
- Don't ship Geist on native. iOS / macOS use San Francisco; Android uses Roboto.
- Don't decorate product surfaces with imagery. Marketing has a curated recipe
  (line illustration primary, desaturated editorial photo secondary), product
  uses minimal line illustration only — see `production-plan.md` §6.
- Don't edit generated values (`web/apex/src/index.css`, Compose theme files)
  directly. Round-trip through this folder.
