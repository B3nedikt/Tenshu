# ADR 0002 — Retire Indigo and Jade palettes

- **Status:** Accepted
- **Date:** 2026-05-09
- **Owner:** Benedikt
- **Supersedes:** —
- **Superseded by:** —

## Context

Phase 1 of the visual design plan (`docs/design/brand/production-plan.md` §9) carried four palette
explorations: **Amethyst** (canonical, ADR 0001), **Indigo**, **Slate**, and **Jade**. Indigo was
chosen because it reads as engineering-trustworthy; Slate because it is hue-free and editorial; Jade
because it telegraphs privacy without the cliché.

Holding all four open beyond the foundations phase has costs:

- Every component must be reasoned about in four colour-systems.
- Marketing imagery (line illustration over `--surface-2`) reads differently against each.
- Token consumers (`web/apex/src/index.css`, `:ui-core/Theme.kt`) duplicate the role contract per
  palette; four palettes means four blocks to keep in sync.
- The whole point of having a canonical palette (ADR 0001) is undermined if three "alternates"
  remain shippable.

## Decision

**Retire Indigo and Jade.** The palette set going forward is:

- **Amethyst** — primary. The canonical brand. Used on every marketing and product surface unless
  there is a specific reason not to.
- **Slate** — secondary. Hue-free fallback. Reach for it on data-dense product surfaces (tables,
  logs, settings) where the violet would compete with content, or in co-branded contexts.

No other palettes are part of the system. Adding one requires a new ADR.

Slate is kept (rather than reduced to Amethyst-only) because it serves a distinct purpose Amethyst
cannot — content-heavy archival reading where any hue is a distraction — and because partner
co-brand surfaces will need a neutral baseline. Reducing further is a one-way door we can take later
if Slate turns out to be unused.

## Consequences

- `palettes.js` / `tokens/palettes.ts` ship Amethyst + Slate only.
- `Brand Foundations.html` removes the Indigo and Jade alternate cards; the alternates section
  becomes "Slate — secondary".
- The Tweaks panel exposes Amethyst (with shade variants) and Slate; there is no Indigo or Jade
  option.
- Any in-progress mock that used Indigo or Jade tokens must be re-themed before merge.
- Future palette additions require an ADR justifying why Amethyst + Slate are insufficient.

## Alternatives considered

- **Keep all four offstage.** Rejected — see Context. The ongoing cost outweighs the optionality.
- **Drop Slate too.** Rejected — Slate fills a real need (data-dense surfaces, co-brand) that
  Amethyst's violet doesn't address.
- **Replace Slate with a Vault-style near-black violet.** Considered. Rejected because Slate's
  hue-free quality is the feature; folding it back into the violet family loses the contrast Slate
  provides.
