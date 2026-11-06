# ADR 0021 — Timeline uses an editorial italic serif for headings, scoped to the app

- **Status:** Accepted; implemented as an interim (v1 uses the platform-generic
  serif behind a single accessor — see §Decision 3). A curated bundled face is
  decided *in principle*, not yet added.
- **Date:** 2026-07-02
- **Owner:** Benedikt
- **Relates to:** `docs/design/timeline/timeline-agent.md` §3 (the open type decision) — ⚠ that
  path is **gitignored** (`.gitignore:31`, the Timeline design handoff is local-only), so it is
  named here rather than linked, [ADR 0016 — Localisation](0016-localisation.md),
  [`docs/proposals/timeline-app.md`](../proposals/timeline-app.md) §3 D3.
- **Supersedes / Superseded by:** —

## Context

The **Timeline** app (docs/design/timeline, direction "Chapters") sets every
display / title / heading in an **italic serif** — day dates, moment titles,
place & trip names, section titles, the interlude line. This is not decoration;
it is the concept's whole voice ("entries read like a book"). The design handoff
mocks it with `Georgia, "Times New Roman", serif`.

But the Tenshu brand type system ships **no serif**. `brand-agent.md` is explicit
— Android uses Roboto; native fallbacks are San Francisco / Roboto Flex — and the
shared type scale in `:ui-core` (`AppTypography`, ADR 0001 lineage) is the M3
scale on `FontFamily.Default`. So the Chapters serif is a genuine fork the handoff
flagged (§3) and asked to be resolved by an ADR **before a serif enters the shared
type system**, offering two options:

- **Option A** — adopt an editorial serif for Timeline headings (add a display/
  headline family, write this ADR).
- **Option B** — fall back to the M3 sans headline styles (loses the editorial
  voice, zero brand change).

The handoff also set two hard constraints: **do not hardcode Georgia** in Compose
(it is not a guaranteed platform face on Android/iOS and isn't in the token
layer), and do not silently drop the serif.

## Decision

### 1. Timeline keeps the italic serif for headings (Option A) — it is the app's identity

Dropping it (Option B) would make Timeline read "app, not book," discarding the
one thing that distinguishes the direction. We keep the serif for the
display/title/heading roles; body, labels, meta, and all structural text stay on
the M3 sans scale.

### 2. The serif is **Timeline-scoped**, not added to `:ui-core`'s shared type system

This is the load-bearing scoping decision. The serif lives **only** in
`:timeline-shared-ui` (`ui/theme/TimelineType`), *layered on top of*
`MaterialTheme.typography` — it does **not** modify `AppTypography` in `:ui-core`.
So Drive and Control are untouched; the brand's shared type scale still ships no
serif. This keeps the "new family = ADR" gate (handoff §3) satisfied at the
narrowest possible blast radius: a single app opts into an extra family for a
handful of roles, rather than the platform growing a serif everyone inherits.
Consistent with [ADR 0016](0016-localisation.md)/the design-system stance that
`:ui-core` is a shared foundation, not a place for app-specific voice.

### 3. One accessor; v1 uses the platform-generic serif as an interim

All serif usage resolves through **one** point — `TimelineType.displayFamily`
(`:timeline-shared-ui/.../ui/theme/TimelineType.kt`). No call site names a family.
This makes the remaining sub-decision (which serif) a **one-line swap**:

- **v1 (shipped):** `displayFamily = FontFamily.Serif` — the platform-generic
  serif (Android Noto Serif / desktop system serif / iOS Times-family), each with
  a real italic. It renders the intended voice immediately with **zero bundled
  assets** and no per-platform font packaging, honouring "don't hardcode Georgia."
- **Later (in principle):** replace with a curated, licensed editorial serif
  bundled as a Compose font resource (`FontFamily(Res.font.<editorial_italic>)`).
  Trigger: brand owner picks a face and clears licensing for Android/desktop/iOS/
  web. Until then the generic serif is a faithful-enough stand-in.
- **Reversible:** setting `displayFamily = FontFamily.Default` collapses to
  Option B if the serif is ever rejected — again, one line.

## Consequences

- **Positive:** Timeline reads as designed today; the brand type system is
  unchanged; the "which serif" question is deferred without blocking the app; the
  choice is trivially reversible or upgradeable.
- **Negative / residual:** the platform-generic serif is not identical across
  platforms (Noto Serif vs Times vs system serif) — acceptable for v1, resolved
  when a curated face is bundled. iOS is not yet built; when it is, the same
  accessor applies (Times-family generic until the bundle lands).
- **Follow-ups:** (a) brand owner to pick + license the editorial face; (b) if a
  second app ever wants the same serif, promote it into `:ui-core` at that point
  (the "app #2" trigger), not before — mirroring the component-promotion policy in
  `timeline-app.md` §4.1.
