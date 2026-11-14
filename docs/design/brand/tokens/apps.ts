// tokens/apps.ts — per-app colours for the web apex chrome (source of truth).
//
// One gradient pair per app: `a` is the lighter top-left stop, `b` the deeper bottom-right one.
// Both sit under a WHITE glyph and are identical in light and dark, like a phone's app icons.
// Decided in ADR 0027, which scopes them to the web apex chrome (`web/apex/src/apps/AppIcon.tsx`):
// the native launcher icons keep ADR 0026's five tokens.
//
// Codify into `web/shared/tokens.css` as `--app-<id>-a` / `--app-<id>-b`. Never edit the CSS copy.
//
// ⚠ Contrast floor: white on `a` must stay ≥ 3:1 (WCAG 1.4.11, a graphic). The ratio is noted on
// each line; check a new pair before adding it. `b` is always darker than `a`, so `a` is the bound.
// ⚠ An app missing here falls back to `default` — the brand amethyst — so registering an app never
// requires editing this file (web-app-suite.md F4).

export interface AppColor {
  a: string
  b: string
}

export const appColors: Record<string, AppColor> = {
  drive:    { a: '#4C8DF6', b: '#2459D8' }, // blue      3.26:1
  photos:   { a: '#F2557F', b: '#B8358F' }, // rose      3.29:1
  calendar: { a: '#EE5A52', b: '#C9302C' }, // red       3.38:1
  fit:      { a: '#23A35A', b: '#13733D' }, // green     3.25:1
  timeline: { a: '#7A63E0', b: '#4F3E9E' }, // indigo    4.48:1
  maps:     { a: '#0E97A6', b: '#0B6477' }, // teal      3.4:1
  contacts: { a: '#6B7A90', b: '#3F4A5C' }, // slate     4.4:1
  home:     { a: '#EE6A30', b: '#C2410C' }, // orange    3.12:1
  mia:      { a: '#A05FD6', b: '#6D3BA8' }, // violet    4.12:1
  default:  { a: '#8B6FE0', b: '#5A4A96' }, // amethyst  3.85:1
}
