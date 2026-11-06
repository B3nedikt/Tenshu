// tokens/type.ts — Type scale, families, per-platform fallback.
//
// Web stays on Geist. Native apps use the platform UI font directly so we
// never ship a webfont over the network on iOS / Android. Same scale on every
// platform — only the family changes.
//
// Codify into:
//   - web/apex/src/index.css         (CSS custom properties)
//   - ui-core/.../theme/Typography.kt (Compose TextStyle)

export const families = {
  web: {
    sans: `'Geist', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif`,
    mono: `'Geist Mono', ui-monospace, SFMono-Regular, Menlo, monospace`,
  },
  ios: {
    sans: `-apple-system, BlinkMacSystemFont, 'SF Pro Text', 'Helvetica Neue', sans-serif`,
    mono: `ui-monospace, 'SF Mono', Menlo, monospace`,
  },
  android: {
    sans: `'Roboto Flex', 'Roboto', system-ui, sans-serif`,
    mono: `'Roboto Mono', ui-monospace, monospace`,
  },
} as const;

// Sizes in px. Multiplied by --type-scale (1 today; reserved for a future
// density toggle).
export const scale = {
  mono:    11,  // metadata, IDs, eyebrow labels — tracked +12% UPPER
  caption: 12,
  bodyS:   14,
  body:    16,
  lead:    20,
  h3:      24,
  h2:      36,
  h1:      56,
  display: 96,
} as const;

export const tracking = {
  display: -0.04,
  h1:      -0.03,
  h2:      -0.025,
  h3:      -0.015,
  lead:    -0.01,
  body:    -0.005,
  bodyS:   -0.005,
  caption:  0.005,
  mono:     0.12, // wide tracking, set in UPPERCASE
} as const;

export const lineHeight = {
  display: 0.92,
  h1:      1.0,
  h2:      1.05,
  h3:      1.15,
  lead:    1.4,
  body:    1.55,
  bodyS:   1.5,
  caption: 1.45,
  mono:    1.4,
} as const;

export const weight = {
  display: 800,
  h1:      800,
  h2:      700,
  h3:      600,
  lead:    400,
  body:    400,
  bodyS:   400,
  caption: 500,
  mono:    500,
} as const;
