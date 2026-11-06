// The basemap style for the web: `:basemap-core`'s, never hand-written here. The JSON next to this
// file is GENERATED (`./gradlew :basemap-core:generateWebBasemapStyles`) and pinned to Kotlin by
// `WebStyleBundleParityTest`; this module only fills the three things only a browser knows — the
// tile URL, the glyph URL (the map app's own origin) and the label language — and then proves the
// result reaches nothing but the app's own origin and the region protocol.
import type { StyleSpecification } from 'maplibre-gl'
import bundle from './basemap-styles.generated.json'

export type MapTheme = 'light' | 'dark'

interface StyleBundle {
  readonly maxZoom: number
  readonly tileUrlToken: string
  readonly glyphsToken: string
  readonly labelLanguageToken: string
  readonly labelLanguages: readonly string[]
  readonly styles: Record<MapTheme, { readonly endonym: unknown; readonly localized: unknown }>
}

const BUNDLE = bundle as unknown as StyleBundle

/**
 * The tightest a camera may frame (`BASEMAP_MAX_ZOOM` in `:basemap-core`, carried in the generated
 * bundle so it is spelled once — `ios-apple-maps-option.md` §8.2 F5).
 */
export const BASEMAP_MAX_ZOOM: number = BUNDLE.maxZoom

/** The `name:<lang>` suffix for a BCP-47 tag, or null (endonym). `labelLanguageFor` in Kotlin. */
export function labelLanguageFor(tag: string | null | undefined): string | null {
  const primary = tag?.split(/[-_]/)[0]?.toLowerCase()
  return primary && BUNDLE.labelLanguages.includes(primary) ? primary : null
}

/** The protocol scheme a vector source may use (`protocol.ts`'s `PROTOCOL`; kept literal so this file stays maplibre/pmtiles-free). */
export const REGION_PROTOCOL = 'tenshu-pmtiles'

export interface BasemapStyleOptions {
  readonly theme: MapTheme
  /** A BCP-47 tag (`navigator.language`); unsupported scripts fall back to the endonym. */
  readonly language?: string | null
  /** The region's `tenshu-pmtiles://…` URL, or null to draw the background only (no region covers the camera). */
  readonly tileUrl: string | null
  /** `<own origin>/glyphs/{fontstack}/{range}.pbf`. */
  readonly glyphsUrl: string
  /** The app's own origin — the only http(s) origin the style may name. */
  readonly ownOrigin: string
}

function substitute(value: unknown, token: string, replacement: string): unknown {
  if (typeof value === 'string') return value.includes(token) ? value.split(token).join(replacement) : value
  if (Array.isArray(value)) return value.map((v) => substitute(v, token, replacement))
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, substitute(v, token, replacement)]))
  }
  return value
}

export function buildBasemapStyle(options: BasemapStyleOptions): StyleSpecification {
  const variants = BUNDLE.styles[options.theme]
  const lang = labelLanguageFor(options.language)
  const template = lang === null ? variants.endonym : substitute(variants.localized, BUNDLE.labelLanguageToken, lang)
  const style = structuredClone(template) as StyleSpecification & { sources: Record<string, { url?: string }> }

  style.glyphs = options.glyphsUrl
  if (options.tileUrl === null) {
    // No region: keep what needs no tiles (the background), drop the vector source and its layers.
    style.layers = style.layers.filter((layer) => !('source' in layer))
    style.sources = {}
  } else {
    for (const source of Object.values(style.sources)) {
      if (source.url === BUNDLE.tileUrlToken) source.url = options.tileUrl
    }
  }
  assertNoForeignUrls(style, options.ownOrigin)
  return style
}

/**
 * ⚠⚠ No third-party fetch (web-app-suite.md §4; House Rule #1's spirit, the style's
 * `everyUrlInTheStyleIsDeviceLocal` on native): every URL-shaped string in [style] must be the region
 * protocol or under [ownOrigin]. Throws, naming the offender — a map that would reach a tile, font or
 * sprite CDN must not render at all. A leftover generator token is refused for the same reason.
 */
export function assertNoForeignUrls(style: unknown, ownOrigin: string): void {
  const own = new URL(ownOrigin).origin
  const offenders: string[] = []
  const walk = (value: unknown) => {
    if (typeof value === 'string') {
      if (value.startsWith('@tenshu/')) offenders.push(value)
      const scheme = /^([a-z][a-z0-9+.-]*):\/\//i.exec(value)?.[1]?.toLowerCase()
      if (scheme === undefined || scheme === REGION_PROTOCOL) return
      if ((scheme === 'http' || scheme === 'https') && (value === own || value.startsWith(`${own}/`))) return
      offenders.push(value)
    } else if (Array.isArray(value)) value.forEach(walk)
    else if (value !== null && typeof value === 'object') Object.values(value).forEach(walk)
  }
  walk(style)
  // The fields MapLibre fetches from are resolved the way the browser will, so a scheme-less
  // `//host/…` or a single-slash `https:/host` (which the walk above cannot see) is caught too.
  const s = style as { glyphs?: unknown; sprite?: unknown; sources?: Record<string, Record<string, unknown>> }
  const fetched: unknown[] = [s.glyphs, s.sprite]
  for (const source of Object.values(s.sources ?? {})) fetched.push(source.url, source.tiles, source.data)
  const checkFetched = (value: unknown) => {
    if (Array.isArray(value)) return value.forEach(checkFetched)
    if (typeof value !== 'string' || value.startsWith('@tenshu/')) return
    let url: URL
    try {
      url = new URL(value.replace(/\{[a-z]+\}/gi, 'x'), own)
    } catch {
      offenders.push(value)
      return
    }
    if (url.protocol !== `${REGION_PROTOCOL}:` && url.origin !== own) offenders.push(value)
  }
  fetched.forEach(checkFetched)
  if (offenders.length > 0) throw new Error(`map style reaches outside ${own}: ${offenders.join(', ')}`)
}
