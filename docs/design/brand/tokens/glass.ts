// tokens/glass.ts — the overview's wallpaper, glass cards and attention colour (source of truth).
//
// Additive roles beside palettes.ts, used by the apex overview (ADR 0027): a soft gradient
// wallpaper behind translucent ("glass") cards, and a rose attention colour for what needs action
// (the recovery band, the try-mode band, the bell's badge). They do NOT replace any palettes.ts
// role, and only the web codifies them today.
//
// Codify into `web/shared/tokens.css` (light block, dark media block, [data-theme] blocks) and its
// `@theme inline` mapping (`bg-glass`, `border-glass-line`, `bg-well`, `bg-attention`,
// `shadow-glass`, …). Never edit the CSS copy.
//
// ⚠ Contrast: the glass is translucent, so text on it is measured against glass composited over
// the wallpaper's brightest stop. `--text` and `--text-2` pass AA everywhere; ⚠ `--text-3` does
// NOT (4.2:1 in light), so text on glass uses `--text-2` at most.
// ⚠ `--attention` carries white text and a white glyph (5.16:1) in both schemes.

export type GlassRole =
  | '--wall'
  | '--glass'
  | '--glass-line'
  | '--glass-head'
  | '--glass-shadow'
  | '--well'
  | '--attention'
  | '--attention-2'
  | '--on-attention'
  | '--attention-line'

export const glass: { light: Record<GlassRole, string>; dark: Record<GlassRole, string> } = {
  light: {
    '--wall':
      'radial-gradient(120% 80% at 10% 0%, #E9E2FF 0%, transparent 60%), radial-gradient(90% 70% at 100% 10%, #FFE3EE 0%, transparent 55%), radial-gradient(100% 80% at 60% 100%, #DCEBFF 0%, transparent 60%), #F3EFFA',
    '--glass':          'rgb(255 255 255 / 0.66)',
    '--glass-line':     'rgb(255 255 255 / 0.75)',
    '--glass-head':     'rgb(255 255 255 / 0.35)',
    '--glass-shadow':   '0 1px 1px rgb(40 25 90 / 0.04), 0 12px 32px -8px rgb(60 40 120 / 0.18)',
    '--well':           'rgb(90 74 150 / 0.10)',
    '--attention':      '#B4476F',
    '--attention-2':    '#7B5265',
    '--on-attention':   '#FFFFFF',
    '--attention-line': 'rgb(123 82 101 / 0.25)',
  },
  dark: {
    '--wall':
      'radial-gradient(110% 80% at 0% 0%, #3B2A80 0%, transparent 55%), radial-gradient(80% 70% at 100% 20%, #5A2350 0%, transparent 55%), radial-gradient(120% 90% at 50% 110%, #123266 0%, transparent 60%), #120F24',
    '--glass':          'rgb(30 26 52 / 0.62)',
    '--glass-line':     'rgb(255 255 255 / 0.10)',
    '--glass-head':     'rgb(255 255 255 / 0.04)',
    '--glass-shadow':   '0 16px 40px -12px rgb(0 0 0 / 0.55)',
    '--well':           'rgb(255 255 255 / 0.08)',
    '--attention':      '#B4476F',
    '--attention-2':    '#7B5265',
    '--on-attention':   '#FFFFFF',
    '--attention-line': 'rgb(235 184 203 / 0.25)',
  },
}
