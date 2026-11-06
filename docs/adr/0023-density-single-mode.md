# ADR 0023 — Single density mode for v0.1

- **Status:** Accepted
- **Date:** 2026-05-09
- **Owner:** Benedikt
- **Supersedes:** —
- **Superseded by:** —
- **Renumbered:** 2026-08-13, from **ADR 0004**. Two ADRs were filed as 0004 —
  this one and *Mark set v7: ears reinstated* — and `production-plan.md` ended up
  citing "ADR 0004" for both, one row apart. The mark-set ADR keeps 0004: it is
  the successor ADR 0003 names by number in its own supersession header, and it is
  cited from shipped Kotlin. ⚠ This ADR's **decision and date are unchanged**; only
  its number moved. An old reference to "ADR 0004" that is about *density* means
  this document.

## Context

Production design systems often expose user-selectable density (compact /
comfortable / spacious in Material; small / medium / large in iOS). The
appeal: dense surfaces (data tables, settings, file lists) want different
spacing than relaxed surfaces (marketing, reading views), and users want to
trade information density against breathing room based on their own
preference.

Implementing density properly is non-trivial. Every component must respond
to the active density (paddings, font sizes, line heights, hit-target
sizes); typography scales need ratios that hold across each step; layout
templates need to pick the right density per surface (a settings screen
shouldn't get a marketing-density treatment).

For Tenshu's v0.1 the open question is: is the cost worth it now?

## Decision

**Ship a single density mode.** No user-selectable density. Every component
renders against the canonical token scale (`tokens/spacing.ts`,
`tokens/type.ts`).

The CSS custom properties `--type-scale` and `--space-scale` are kept in
`foundations.html` and the codified `web/apex/src/index.css` as multipliers
on the spacing and type token values, defaulting to `1`. They exist as
**reserved infrastructure** for a future density toggle: when we add density
modes later, flipping these scale multipliers can rescale the entire system
without touching every component definition.

The KMP side (`:ui-core/.../theme/Tokens.kt`) does the same: `dp` /
`TextStyle` values flow through scale variables defaulting to `1f`.

## Consequences

- Every component ships with one set of paddings / type sizes / hit
  targets. Reduces design + implementation cost by an order of magnitude
  vs. multi-density.
- `--type-scale` / `--space-scale` are present-but-unused. Linters or
  bundle-size analyzers should not flag them.
- Component authors must pick density-appropriate values for their target
  surface manually. A data table that wants tight rows uses smaller
  spacing tokens directly (`s2` instead of `s4` for row padding); a
  reading view uses larger ones (`s5`+). The choice lives in the component
  consumer, not in a global density setting.

## When to revisit

Add a density toggle when **at least two** of these are true:

1. User research shows a clear preference for compact mode in data-heavy
   surfaces (file lists, logs, settings) that goes beyond what
   per-component spacing choices can address.
2. We ship enterprise / pro tooling where density affects productivity
   metrics meaningfully.
3. A platform peer (M3 Expressive, Apple Vision OS, ...) introduces a
   density story we can leverage rather than build ourselves.

Until then the multipliers stay at `1` and density is a non-feature.

## Alternatives considered

- **Ship comfortable + compact from day one.** Rejected — doubles design
  + implementation effort for v0.1, and we don't yet have evidence that
  users care.
- **Drop the `--type-scale` / `--space-scale` infrastructure entirely.**
  Rejected — keeping the multipliers reserved is a one-line cost; adding
  them later means touching every component spec. The reserved
  infrastructure preserves optionality.
- **Per-app density (Drive compact, marketing comfortable).** Rejected —
  same component shouldn't render two ways across apps; better solved
  by surface-appropriate spacing choices at the component-consumer
  level.
