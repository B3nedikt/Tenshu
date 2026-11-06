// The web style is `:basemap-core`'s (generated + parity-tested in Kotlin); here: what map-kit fills
// in, and the guard that the result reaches nothing but the app's own origin.
import { describe, expect, it } from 'vitest'
import bundle from './basemap-styles.generated.json'
import { assertNoForeignUrls, BASEMAP_MAX_ZOOM, buildBasemapStyle, labelLanguageFor } from './basemapStyle'

const ORIGIN = 'https://maps.tenshu.app'
const base = {
  theme: 'light' as const,
  tileUrl: 'tenshu-pmtiles://it-toscana',
  glyphsUrl: `${ORIGIN}/glyphs/{fontstack}/{range}.pbf`,
  ownOrigin: ORIGIN,
}

function strings(value: unknown): string[] {
  if (typeof value === 'string') return [value]
  if (Array.isArray(value)) return value.flatMap(strings)
  if (value !== null && typeof value === 'object') return Object.values(value).flatMap(strings)
  return []
}

describe('buildBasemapStyle', () => {
  it.each(['light', 'dark'] as const)('%s: names no http(s) URL but the app origin, and no generator token', (theme) => {
    const style = buildBasemapStyle({ ...base, theme, language: 'de-DE' })
    const urls = strings(style).filter((s) => /^[a-z][a-z0-9+.-]*:\/\//i.test(s))
    expect(urls.sort()).toEqual([`${ORIGIN}/glyphs/{fontstack}/{range}.pbf`, 'tenshu-pmtiles://it-toscana'])
    expect(strings(style).some((s) => s.includes('@tenshu/'))).toBe(false)
  })

  it('is the generated layer list, untouched — no layer is authored here', () => {
    const style = buildBasemapStyle({ ...base, language: null })
    expect(style.layers).toEqual((bundle.styles.light.endonym as { layers: unknown[] }).layers)
  })

  it('labels in a glyph-covered language, and falls back to the endonym otherwise', () => {
    const de = JSON.stringify(buildBasemapStyle({ ...base, language: 'de-AT' }))
    expect(de).toContain('"name:de"')
    const ja = JSON.stringify(buildBasemapStyle({ ...base, language: 'ja' }))
    expect(ja).not.toContain('name:')
    expect(labelLanguageFor('pt_BR')).toBe('pt')
    expect(labelLanguageFor('vi')).toBeNull() // Latin, but its glyph ranges are not shipped
  })

  it('without a region it draws the background only — no source, no layer that needs one', () => {
    const style = buildBasemapStyle({ ...base, tileUrl: null })
    expect(style.sources).toEqual({})
    expect(style.layers.map((l) => l.type)).toEqual(['background'])
  })

  it('takes the camera clamp from :basemap-core, not a second spelling', () => {
    expect(BASEMAP_MAX_ZOOM).toBe(bundle.maxZoom)
    expect(BASEMAP_MAX_ZOOM).toBeGreaterThan(10)
  })
})

describe('assertNoForeignUrls', () => {
  it.each([
    'https://tiles.service.tenshu.app/regions/x.pmtiles',
    'https://fonts.example/{fontstack}/{range}.pbf',
    'http://maps.tenshu.app.evil.example/glyphs',
    'pmtiles://https://cdn.example/planet.pmtiles',
    'mapbox://styles/x',
    '@tenshu/glyphs-url@',
  ])('refuses %s', (url) => {
    expect(() => assertNoForeignUrls({ sources: { a: { url } } }, ORIGIN)).toThrow(/reaches outside/)
  })

  it.each([
    '//fonts.example/{fontstack}/{range}.pbf',
    'http:/fonts.example/{fontstack}/{range}.pbf',
  ])('refuses a scheme-less or single-slash URL the browser would still fetch: %s', (glyphs) => {
    expect(() => assertNoForeignUrls({ glyphs, sources: {} }, ORIGIN)).toThrow(/reaches outside/)
    expect(() => assertNoForeignUrls({ sources: { a: { tiles: [glyphs] } } }, ORIGIN)).toThrow(/reaches outside/)
  })

  it('allows an own-origin relative glyph path', () => {
    expect(() => assertNoForeignUrls({ glyphs: '/glyphs/{fontstack}/{range}.pbf', sources: {} }, ORIGIN)).not.toThrow()
  })

  it('allows the own origin and the region protocol', () => {
    expect(() =>
      assertNoForeignUrls({ glyphs: `${ORIGIN}/glyphs/x`, sources: { a: { url: 'tenshu-pmtiles://r' } } }, ORIGIN),
    ).not.toThrow()
  })
})
