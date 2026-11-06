import { afterEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import { manyPlaces, placeRow } from '../test/proxyMock'
import type { MapsPlace } from '@tenshu/sdk/maps'
import {
  coordinateText,
  countryName,
  filterIndex,
  fold,
  matches,
  placeLabel,
  relativeVisit,
  searchIndex,
  sortPlaces,
  searchPlaces,
} from './format'

const t = i18n.getFixedT('en')

describe('fold (accent and case folding)', () => {
  it('drops accents and case, so é is e and É is e', () => {
    expect(fold('Café')).toBe('cafe')
    expect(fold('CAFÉ')).toBe('cafe')
    expect(fold('Crème Brûlée')).toBe('creme brulee')
    expect(fold('München')).toBe('munchen')
    expect(fold('Straße')).toBe('strasse')
  })

  it('folds a decomposed (NFD) and a composed (NFC) é the same', () => {
    expect(fold('Café')).toBe(fold('Café'))
  })

  // NFD leaves these whole — they are letters, not a letter plus a mark.
  it.each([
    ['København', 'kobenhavn'],
    ['FÆRØERNE', 'faeroerne'],
    ['Œuvre', 'oeuvre'],
    ['Łódź', 'lodz'],
    ['Đakovo', 'dakovo'],
    ['Þórshöfn', 'thorshofn'],
    ['Garðabær', 'gardabaer'],
    ['Diyarbakır', 'diyarbakir'],
  ])('transliterates a letter NFD cannot split: %s → %s', (from, to) => {
    expect(fold(from)).toBe(to)
  })

  it('so an ASCII query finds them in search', () => {
    const cph = placeRow({ placeId: 'cph', name: 'Nyhavn', city: 'København', countryCode: 'DK' })
    expect(matches(t, 'en', cph, 'kobenhavn')).toBe(true)
    expect(matches(t, 'en', cph, 'København')).toBe(true)
  })
})

describe('local search', () => {
  const cafe = placeRow({ placeId: 'p1', name: 'Café Luitpold', city: 'München', countryCode: 'DE' })
  const gym = placeRow({ placeId: 'p2', name: 'gym', city: 'Berlin', countryCode: 'DE', role: 'work' })
  const unnamed = placeRow({ placeId: 'p3' })

  it('matches across accents and case, both ways', () => {
    expect(matches(t, 'en', cafe, 'cafe')).toBe(true)
    expect(matches(t, 'en', cafe, 'CAFÉ')).toBe(true)
    expect(matches(t, 'en', cafe, 'munchen')).toBe(true)
    expect(matches(t, 'en', placeRow({ placeId: 'x', name: 'cafe' }), 'Café')).toBe(true)
  })

  it('needs every word, in any order, across name, city, country and role', () => {
    expect(matches(t, 'en', cafe, 'munich cafe')).toBe(false) // "Munich" is not the stored city
    expect(matches(t, 'en', cafe, 'münchen luitpold')).toBe(true)
    expect(matches(t, 'en', cafe, 'germany')).toBe(true) // the country's name in the UI language
    expect(matches(t, 'de', cafe, 'deutschland')).toBe(true)
    expect(matches(t, 'en', gym, 'work')).toBe(true) // the confirmed role
    expect(matches(t, 'en', unnamed, 'cafe')).toBe(false)
  })

  it('an empty query keeps every place, named ones first, by collation', () => {
    const places = [unnamed, gym, cafe]
    expect(searchPlaces(t, 'en', places, '  ').map((p) => p.placeId)).toEqual(['p1', 'p2', 'p3'])
  })

  it('sorts accent- and case-insensitively, and numerically', () => {
    const rows = ['Éclair', 'apple', 'Zoo', 'Gate 10', 'Gate 2'].map((name, i) => placeRow({ placeId: `p${i}`, name }))
    expect(sortPlaces(t, 'en', rows).map((p) => p.name)).toEqual(['apple', 'Éclair', 'Gate 2', 'Gate 10', 'Zoo'])
  })
})

// ── thousands of places ───────────────────────────────────────────────────────

describe('search over 5 000 places', () => {
  const RealDisplayNames = Intl.DisplayNames
  const RealCollator = Intl.Collator
  afterEach(() => {
    Object.defineProperty(Intl, 'DisplayNames', { value: RealDisplayNames, writable: true, configurable: true })
    Object.defineProperty(Intl, 'Collator', { value: RealCollator, writable: true, configurable: true })
    vi.resetModules()
  })

  it('builds Intl.DisplayNames and Intl.Collator at most once per locale, however much is searched', async () => {
    const built = { names: [] as string[], collators: [] as string[] }
    class CountingNames extends RealDisplayNames {
      constructor(locales: string[], options: Intl.DisplayNamesOptions) {
        super(locales, options)
        built.names.push(String(locales))
      }
    }
    class CountingCollator extends RealCollator {
      constructor(locale?: string, options?: Intl.CollatorOptions) {
        super(locale, options)
        built.collators.push(String(locale))
      }
    }
    Object.defineProperty(Intl, 'DisplayNames', { value: CountingNames, writable: true, configurable: true })
    Object.defineProperty(Intl, 'Collator', { value: CountingCollator, writable: true, configurable: true })
    // A fresh module: its per-locale caches start empty.
    vi.resetModules()
    const fresh = await import('./format')

    const places = manyPlaces(5_000)
    for (const locale of ['en', 'de', 'en']) {
      const index = fresh.searchIndex(t, locale, places)
      for (const q of ['k', 'ko', 'kob', 'kobenhavn', 'germany', '']) fresh.filterIndex(index, q)
      fresh.countryName(locale, 'DE')
      fresh.sortPlaces(t, locale, places.slice(0, 50))
    }
    expect(built.names.sort()).toEqual(['de', 'en'])
    expect(built.collators.sort()).toEqual(['de', 'en'])
  })

  it('indexes once and filters each keystroke within budget', () => {
    const places = manyPlaces(5_000)
    let start = performance.now()
    const index = searchIndex(t, 'en', places)
    const indexMs = performance.now() - start
    const timings: number[] = []
    let shown: readonly MapsPlace[] = []
    for (const q of ['k', 'ko', 'kob', 'kobe', 'koben', 'kobenh', 'kobenha', 'kobenhav', 'kobenhavn', 'kobenhavn cafe']) {
      start = performance.now()
      shown = filterIndex(index, q)
      timings.push(performance.now() - start)
    }
    expect(shown.length).toBe(places.filter((p) => p.city === 'København' && p.name !== '').length)
    expect(shown.length).toBeGreaterThan(500)
    // Generous for a loaded CI box: per keystroke this is a substring scan of 5 000 strings.
    expect(indexMs).toBeLessThan(1_500)
    expect(Math.max(...timings)).toBeLessThan(50)
  })
})

describe('labels', () => {
  it('an unnamed place reads "Unnamed place" with its coordinate', () => {
    expect(placeLabel(t, 'en', placeRow({ placeId: 'p', city: 'Munich' }))).toEqual({
      title: 'Unnamed place',
      unnamed: true,
      secondary: '48.13743, 11.57549',
    })
  })

  it('a named place reads its city and country name', () => {
    expect(placeLabel(t, 'en', placeRow({ placeId: 'p', name: ' Home café ', city: 'Paris', countryCode: 'fr' })).secondary).toBe(
      'Paris, France',
    )
    expect(countryName('de', 'FR')).toBe('Frankreich')
    expect(countryName('en', 'zz9')).toBe('zz9')
  })

  it('coordinates are five decimals, dot-separated, whatever the language', () => {
    expect(coordinateText(-33.8688197, 151.2092955)).toBe('-33.86882, 151.20930')
  })
})

describe('relativeVisit (MapsFormat.relativeVisit)', () => {
  const now = new Date(2026, 9, 28, 12).getTime()
  const at = (y: number, m: number, d: number) => new Date(y, m - 1, d, 9).getTime()

  it.each([
    [at(2026, 10, 28), 'today', 0],
    [at(2026, 10, 29), 'today', 0], // a future delta clamps
    [at(2026, 10, 27), 'yesterday', 0],
    [at(2026, 10, 22), 'daysAgo', 6],
    [at(2026, 10, 15), 'weeksAgo', 1], // 13 days → 1 week, floored
    [at(2026, 10, 1), 'weeksAgo', 3],
    [at(2026, 9, 30), 'monthsAgo', 1], // 28 days, not a whole month yet → at least 1
    [at(2026, 4, 28), 'monthsAgo', 6],
    [at(2025, 10, 28), 'yearsAgo', 1],
    [at(2023, 1, 1), 'yearsAgo', 3],
  ])('%s → %s %s', (visit, form, value) => {
    expect(relativeVisit(now, visit)).toEqual({ form, value })
  })
})
