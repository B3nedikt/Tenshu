// @tenshu/web-tokens/tint — the overview's background colour, as a hue and a chroma that tint the
// wallpaper, the glass and the accent (docs/proposals/web-apex-home.md §4.14).
//
// A tint is TWO numbers, never a colour: the lightness comes from the mode (`tint.css` has one
// block per scheme), so any pick reads in light and dark alike and text contrast never depends on
// it. `<html data-tint style="--tint-h: 250; --tint-c: 0.12">`; absent = the brand's own tokens.
// The stored token is a preset name (`teal`) or `h<deg>c<thousandths>` (`h212c094`).
//
// ⚠ Pure: no DOM types, no imports — the boot script below is a literal string, like theme.ts.

export interface Tint {
  /** OKLCH hue, degrees. */
  readonly hue: number
  /** OKLCH chroma, 0 … MAX_CHROMA. */
  readonly chroma: number
}

export const MAX_CHROMA = 0.16

/**
 * The swatches, in order. Blue is the DEFAULT (nothing stored); `classic` is the brand's own
 * wallpaper — no tint at all, also what a signed-out page shows.
 */
export const TINT_PRESETS = {
  blue: { hue: 252, chroma: 0.12 },
  violet: { hue: 295, chroma: 0.12 },
  teal: { hue: 195, chroma: 0.1 },
  green: { hue: 150, chroma: 0.11 },
  rose: { hue: 8, chroma: 0.12 },
  amber: { hue: 70, chroma: 0.12 },
  graphite: { hue: 260, chroma: 0.015 },
  classic: null,
} as const satisfies Record<string, Tint | null>

export type TintPreset = keyof typeof TINT_PRESETS

export const TINT_PRESET_NAMES = Object.keys(TINT_PRESETS) as TintPreset[]

/** What nothing stored means. ⚠ Stored as nothing (`''`), so every realm that never picked gets it. */
export const DEFAULT_TINT_PRESET: TintPreset = 'blue'

/** localStorage mirror for the boot script — first paint must not wait for the realm. */
export const TINT_STORAGE_KEY = 'tenshu.tint'

const CUSTOM = /^h(\d{1,3})c(\d{1,3})$/

// ⚠ Own keys only: `in` also matches `constructor`, `toString`, … from the prototype.
const isPreset = (token: string): token is TintPreset => Object.prototype.hasOwnProperty.call(TINT_PRESETS, token)

/**
 * A stored token as a tint: absent, empty or unknown is the default (blue); `classic` is null (the
 * brand's own tokens, no tint).
 */
export function parseTint(token: unknown): Tint | null {
  const fallback = TINT_PRESETS[DEFAULT_TINT_PRESET]
  if (typeof token !== 'string') return fallback
  if (isPreset(token)) return TINT_PRESETS[token]
  const m = CUSTOM.exec(token)
  if (m === null) return fallback
  return { hue: Number(m[1]) % 360, chroma: Math.min(Number(m[2]) / 1000, MAX_CHROMA) }
}

export function customTintToken(tint: Tint): string {
  const hue = Math.round(((tint.hue % 360) + 360) % 360)
  const chroma = Math.round(Math.min(Math.max(tint.chroma, 0), MAX_CHROMA) * 1000)
  return `h${hue}c${String(chroma).padStart(3, '0')}`
}

/** The token's preset name, or null for a custom tint. */
export function presetOf(token: string | null | undefined): TintPreset | null {
  if (token === null || token === undefined || token === '') return DEFAULT_TINT_PRESET
  return isPreset(token) ? token : null
}

/** An sRGB `#rrggbb` as a tint: its OKLCH hue and chroma; its lightness is dropped (the mode's). */
export function tintFromHex(hex: string): Tint | null {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex)
  if (m === null) return null
  const n = parseInt(m[1]!, 16)
  const lin = (v: number) => {
    const c = v / 255
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
  }
  const r = lin((n >> 16) & 255)
  const g = lin((n >> 8) & 255)
  const b = lin(n & 255)
  const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
  const mm = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
  const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
  const A = 1.9779984951 * l - 2.428592205 * mm + 0.4505937099 * s
  const B = 0.0259040371 * l + 0.7827717662 * mm - 0.808675766 * s
  const hue = ((Math.atan2(B, A) * 180) / Math.PI + 360) % 360
  return { hue, chroma: Math.min(Math.hypot(A, B), MAX_CHROMA) }
}

/** A tint as an sRGB `#rrggbb` at a mid lightness — the colour input's value and a swatch. */
export function tintToHex(tint: Tint, lightness = 0.62): string {
  const h = (tint.hue * Math.PI) / 180
  const A = tint.chroma * Math.cos(h)
  const B = tint.chroma * Math.sin(h)
  const l = (lightness + 0.3963377774 * A + 0.2158037573 * B) ** 3
  const m = (lightness - 0.1055613458 * A - 0.0638541728 * B) ** 3
  const s = (lightness - 0.0894841775 * A - 1.291485548 * B) ** 3
  const enc = (v: number) => {
    const c = Math.min(Math.max(v, 0), 1)
    const e = c <= 0.0031308 ? 12.92 * c : 1.055 * c ** (1 / 2.4) - 0.055
    return Math.round(e * 255)
      .toString(16)
      .padStart(2, '0')
  }
  return `#${enc(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s)}${enc(
    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
  )}${enc(-0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s)}`
}

/**
 * Runs inline in <head> before first paint: applies the mirrored tint (the same attribute and
 * properties `applyTint` sets). try/catch: blocked storage just means the brand's own colours.
 * Kept a literal string so no bundler changes what ships; tint.test.ts evaluates it.
 */
export const TINT_BOOT_SCRIPT =
  `(function(){try{var t=localStorage.getItem(${JSON.stringify(TINT_STORAGE_KEY)});` +
  `var m=/^h(\\d{1,3})c(\\d{1,3})$/.exec(t||'');if(!m)return;var d=document.documentElement;` +
  `d.setAttribute('data-tint','');d.style.setProperty('--tint-h',String(Number(m[1])%360));` +
  `d.style.setProperty('--tint-c',String(Math.min(Number(m[2])/1000,${MAX_CHROMA})))}catch(e){}})();`

/** The mirror's value for [tint]: always the numeric form, so the boot script needs no preset table. */
export function tintMirrorValue(tint: Tint | null): string | null {
  return tint === null ? null : customTintToken(tint)
}
