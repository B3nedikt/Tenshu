// tokens/palettes.ts — Tenshu brand palettes (source of truth).
//
// Amethyst is canonical (ADR 0001). Slate is the only secondary, used where
// the violet would compete with content (data-dense product views, co-brand).
// Indigo and Jade have been retired — see ADR 0002.
//
// Each palette declares the same role contract for both light and dark.
// Codify these into:
//   - web/apex/src/index.css            (CSS custom properties)
//   - ui-core/.../theme/Palettes.kt     (Compose ColorScheme)
//
// Never edit generated values directly; regenerate from this file.
//
// **Mirror in `../palettes.js`**. The sibling `../palettes.js` file is
// loaded by `../foundations.html` at runtime via <script> — every hex value
// here MUST match its counterpart there. When you change a value, change
// both files in the same edit.
//
// Role notes:
//   --line          subtle divider; lower contrast than text; for grouping.
//   --line-strong   max-contrast outline. Equal to --text by design — use it
//                   when an outline must read as confidently as body type
//                   (focused inputs, selected card outlines, mark fills).
//   --primary-soft  background tint for primary-tinted regions (badges,
//                   selected rows). Pair with --primary text on top.
//   --on-primary    text/icon color *over* --primary surfaces.

export type Role =
  | '--primary'
  | '--primary-soft'
  | '--on-primary'
  | '--accent'
  | '--accent-soft'
  | '--surface'
  | '--surface-2'
  | '--surface-3'
  | '--text'
  | '--text-2'
  | '--text-3'
  | '--line'
  | '--line-strong'
  | '--success'
  | '--danger'
  | '--warning';

export type Scheme = Record<Role, string>;

export interface Palette {
  name: string;
  role: 'Primary' | 'Secondary';
  note: string;
  light: Scheme;
  dark: Scheme;
}

export const palettes: Record<'amethyst' | 'slate', Palette> = {
  amethyst: {
    name: 'Amethyst',
    role: 'Primary',
    note: 'Canonical brand. Muted violet anchor (ADR 0001) — quiet authority, faintly spiritual. Used on every marketing and product surface unless there is a specific reason not to.',
    light: {
      '--primary':      '#5A4A96',
      '--primary-soft': '#E6DEFF',
      '--on-primary':   '#FFFFFF',
      '--accent':       '#7B5265',
      '--accent-soft':  '#FFD8E7',
      '--surface':      '#FFFBFF',
      '--surface-2':    '#F4F0F7',
      '--surface-3':    '#E7E0EC',
      '--text':         '#1C1B1F',
      '--text-2':       '#49454E',
      '--text-3':       '#7A757F',
      '--line':         '#D9D2DE',
      '--line-strong':  '#1C1B1F',
      '--success':      '#1F6B4D',
      '--danger':       '#BA1A1A',
      '--warning':      '#7A5310',
    },
    dark: {
      '--primary':      '#CBC0FF',
      '--primary-soft': '#42347D',
      '--on-primary':   '#2C1E71',
      '--accent':       '#EBB8CB',
      '--accent-soft':  '#613B4D',
      '--surface':      '#141318',
      '--surface-2':    '#1C1B22',
      '--surface-3':    '#2A2832',
      '--text':         '#E6E1E6',
      '--text-2':       '#CAC4CF',
      '--text-3':       '#948F99',
      '--line':         '#2F2D38',
      '--line-strong':  '#E6E1E6',
      '--success':      '#7FD0AE',
      '--danger':       '#FFB4AB',
      '--warning':      '#F5C77A',
    },
  },
  slate: {
    name: 'Slate',
    role: 'Secondary',
    note: 'Hue-free fallback. The most editorial, archival reading — all weight comes from typography. Reach for it on data-dense surfaces (tables, logs, settings) where the violet would compete, or in co-branded contexts.',
    light: {
      '--primary':      '#1F2937',
      '--primary-soft': '#E5E7EB',
      '--on-primary':   '#FFFFFF',
      '--accent':       '#475569',
      '--accent-soft':  '#E2E8F0',
      '--surface':      '#FAFAF9',
      '--surface-2':    '#F1F1EF',
      '--surface-3':    '#E5E5E2',
      '--text':         '#0A0A0A',
      '--text-2':       '#3F3F46',
      '--text-3':       '#71717A',
      '--line':         '#D4D4D4',
      '--line-strong':  '#0A0A0A',
      '--success':      '#166534',
      '--danger':       '#991B1B',
      '--warning':      '#854D0E',
    },
    dark: {
      '--primary':      '#F4F4F5',
      '--primary-soft': '#27272A',
      '--on-primary':   '#0A0A0A',
      '--accent':       '#A1A1AA',
      '--accent-soft':  '#27272A',
      '--surface':      '#0A0A0A',
      '--surface-2':    '#141414',
      '--surface-3':    '#1F1F1F',
      '--text':         '#FAFAFA',
      '--text-2':       '#D4D4D8',
      '--text-3':       '#A1A1AA',
      '--line':         '#262626',
      '--line-strong':  '#FAFAFA',
      '--success':      '#86EFAC',
      '--danger':       '#FCA5A5',
      '--warning':      '#FCD34D',
    },
  },
};

export const canonicalPalette = 'amethyst' as const;
export const secondaryPalette = 'slate' as const;
