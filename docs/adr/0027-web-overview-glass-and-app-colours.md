# ADR 0027 — The web overview: a wallpaper, glass cards and per-app colours

- **Status:** Accepted
- **Date:** 2026-11-14
- **Owner:** Benedikt
- **Supersedes:** — (narrows [ADR 0026](0026-app-icon-system.md) §2 for the web chrome only)
- **Superseded by:** —

## Context

The web apex overview (`tenshu.app/overview`, [`web-apex-home.md`](../proposals/web-apex-home.md))
shipped with the layout and behaviour the maintainer accepted, but drawn flat: solid `surface-2`
cards, every app icon the same `primary-soft` square, and the needs-action band in the `warning`
role. The maintainer judged it "boring compared to Apple's" (the reference was iCloud.com's home)
and said the brown (`warning`, `#7A5310` in light) and the uniform tint "don't fit at all".

Three directions were mocked (glass on a gradient wallpaper; solid paper cards under a brand glow;
an aurora wallpaper with app-tinted card headers). The maintainer picked the first.

Two brand rules stood in the way, which is why this is an ADR and not just a restyle:

- `brand-agent.md`: *"Don't add new palettes ad-hoc… Anything else needs an ADR."*
- ADR 0026 §2: app icons use five tokens and *"No new hues."*

## Decision

1. **Per-app colours, web chrome only.** Each app gets a gradient pair under a white glyph
   (`docs/design/brand/tokens/apps.ts` → `--app-<id>-a/-b`), the same in both schemes. They are
   drawn by `web/apex/src/apps/AppIcon.tsx` (dock, Customize, launcher, card headers, the in-app
   top bar) and tint that app's glance-card accents (storage meter, agenda bars, goal ring). An app
   with no pair falls back to the brand amethyst, so registering an app never needs this file.
   - ⚠ **Native launcher icons are unchanged**: ADR 0026's five tokens still govern them, and the
     landing page's carousel still shows those real icons. The web chrome and the launcher now
     disagree on an app's colour; whether the native set follows is **open** and is its own
     decision.
   - ⚠ Floor: white on the lighter stop is ≥ 3:1 (WCAG 1.4.11). Each pair's ratio is noted in
     `apps.ts`.

2. **A wallpaper and glass, the overview only.** `tokens/glass.ts` adds roles beside
   `palettes.ts` (none of its roles change): `--wall` (a soft lavender/rose/sky mesh in light,
   amethyst/plum/night-blue in dark), `--glass`, `--glass-line`, `--glass-head`, `--glass-shadow`
   and `--well`. `AppShell` paints the wallpaper and a glass top bar when no app is open
   (overview, Customize, the Your-apps dialog); inside a hosted app the page stays `surface`, so
   an app's own UI is never drawn over a gradient.
   - ⚠ Glass is translucent, so contrast is measured against glass composited over the
     wallpaper's brightest stop. `--text-3` measures 4.2:1 there, under AA: **text on glass uses
     `--text-2` at the lightest.**

3. **A rose attention colour replaces the brown.** `--attention` (`#B4476F`, white on it 5.16:1)
   with `--attention-2` (= the brand `--accent`, for the chip's gradient) marks what needs action:
   the recovery band (a glass card with a rose shield chip; the words stay in the body colours),
   the try-mode strip and the bell's badge. `--warning` is unchanged and still means a warning
   (the sync pill's warn dot).

## Consequences

- Two new token files under `docs/design/brand/tokens/`, codified into `web/shared/tokens.css`
  per `brand-agent.md`; `:ui-core` does not consume them (nothing native draws this page).
- The recovery band also renders on Control's Notifications page, where it is glass over the
  plain surface. That is intended: the same band in both places.
- `backdrop-filter` is required for the full look; without it the glass falls back to its
  translucent fill, which still meets the contrast floor on its own.
