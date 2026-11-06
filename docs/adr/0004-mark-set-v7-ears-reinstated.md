# ADR 0004 — Mark set v7: ears reinstated

- **Status:** Accepted
- **Date:** 2026-05-17
- **Owner:** Benedikt
- **Supersedes:** ADR 0003 (ears decision only — see "Scope" below)
- **Superseded by:** —

## Context

ADR 0003 (mark set v6) retired the triangle "pagoda-eave" ears across the set:
at 16/20 px they blended into the dome and read as noise, and the cat was
judged recognisable from dome + dot eyes + outward whiskers alone.

A new brand iteration was produced upstream in the design project and dropped
into `docs/design/brand/marks/` (the source-of-truth folder per
`brand-agent.md`). In that iteration the maintainer brought the ears back: the
triangle ears restore the castle / watchtower reading (天守 = the keep) that is
the name's whole point, and the silhouette is stronger and more obviously a
*cat* with them. The small-size concern from ADR 0003 is accepted as a
known trade-off; favicons/app icons use **Solid**, where the filled ears hold
up better than the v5 outline ears did.

## Decision

**Mark set v7:**

- **Ears are reinstated** across the active set (Outline + Solid) as two
  triangle "pagoda-eave" paths flanking the dome top:
  - `EAR_L = "M 28.8 24.2 L 18 14.1 L 15.1 31.7 C 15.1 33.9 18.4 25.9 28.8 24.2 Z"`
  - `EAR_R = "M 35.1 23.9 C 38.9 20.3 42.6 17.1 45.8 13.8 L 48.7 31.4 C 48.7 33.6 45.4 25.5 35.1 23.9 Z"`

  Drawn with the head, after it, in the same treatment per mark: in **Outline**
  filled with surface and stroked in `currentColor` (like the dome); in
  **Solid** filled with `currentColor` (like the dome).
- Everything else from v6 is **unchanged**: dome head path, dot eyes with
  catchlights, six outward whiskers, kaomoji ω mouth, and the proportions.

### Scope — what ADR 0003 still governs

ADR 0004 reverses **only** the "ears retired" decision of ADR 0003. The rest of
ADR 0003 carries forward into v7 and remains in force:

- **Hanko — still parked.** Not revived. Revisit only when a real surface
  (signed content, certificates, hardware-attestation badges) needs it.
- **Solid whisker rule — unchanged.** Whiskers are drawn in `currentColor`
  (not surface), inner span invisible on the filled head, outer span emerging
  into the surface, so Solid reads as a filled-in Outline rather than a
  separate icon.
- The active set is still exactly two variants: Outline (primary), Solid
  (secondary).

## Consequences

- `marks/Outline.tsx`, `marks/Solid.tsx`, `marks/Outline.kt`, `marks/Solid.kt`,
  and the `marks.jsx` foundations sidecar now declare `EAR_L` / `EAR_R` and
  render them with the head. `marks/mark.svg` was regenerated from
  `Outline.tsx` (it ships the two ear paths in the head group).
- The mark is **not yet codified** into `web/apex/` or `:ui-core` (no
  `components/marks/` exists, favicons/OG are placeholder glyphs). When the
  codify step runs, the ears come along for free — no extra consumer work.
- Brand-doc text that asserted "no ears / ears removed" — the `mark.svg`
  header comment, `marks.jsx`, `brand-agent.md`, and `production-plan.md` — is
  updated to point at this ADR. `foundations.html` already documented ear
  construction (it never took the v6 "no ears" change) and is upstream-owned
  / regenerated, so it is left as-is; its specific ear dimensions are
  approximate and will be reconciled on the next design-project drop.
- ADR 0003's status header is updated to record this partial supersession.

## Alternatives considered

- **Keep v6 (no ears).** Rejected by the maintainer — the castle/天守 reading
  is core to the brand and the eared silhouette is more recognisably a cat.
- **Ears on Solid only (small-size surfaces), Outline stays domed.** Rejected
  — splitting the silhouette between variants reintroduces exactly the
  "Solid is a different icon" problem ADR 0003's whisker rule fixed. The set
  stays one cat.
- **Thinner / shorter ears than v5.** The v7 ears are already a distinct
  shape (longer, sketch-style triangles tied into the dome) rather than the
  v5 eaves; revisit dimensions only if a real small-size regression shows up.
