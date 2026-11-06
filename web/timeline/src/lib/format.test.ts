import { beforeAll, describe, expect, it } from 'vitest'
import i18n from '../i18n'
import { coordinateText, distanceParts, distanceText, durationText, momentMeta, momentTitle, placeLabel } from './format'
import { activity, movement, placeRow, visit } from '../test/proxyMock'

beforeAll(async () => {
  await i18n.changeLanguage('en')
})
const t = i18n.t.bind(i18n)

describe('distance — the native TimelineFormat.distanceParts rules', () => {
  it('reads whole metres below a kilometre and tenths above, carrying the rounding', () => {
    expect(distanceParts(999, 'metric')).toEqual({ whole: 0, tenths: 0, small: 999, form: 'metres' })
    expect(distanceParts(2449, 'metric')).toEqual({ whole: 2, tenths: 4, small: 0, form: 'kilometres' })
    expect(distanceParts(999_960, 'metric')).toMatchObject({ whole: 1000, tenths: 0 })
    expect(distanceParts(-5, 'metric').small).toBe(0)
  })

  it('switches imperial at a tenth of a mile, not at a mile', () => {
    expect(distanceParts(150, 'imperial')).toEqual({ whole: 0, tenths: 0, small: 492, form: 'feet' })
    expect(distanceParts(161, 'imperial').form).toBe('miles')
    expect(distanceParts(2414, 'imperial')).toMatchObject({ whole: 1, tenths: 5, form: 'miles' })
  })

  it('renders through the catalogue, so the separator is the locale’s', async () => {
    expect(distanceText(t, 2449, 'metric')).toBe('2.4 km')
    expect(distanceText(t, 2414, 'imperial')).toBe('1.5 mi')
    await i18n.changeLanguage('de')
    expect(distanceText(i18n.t.bind(i18n), 2449, 'metric')).toBe('2,4 km')
    await i18n.changeLanguage('en')
  })
})

describe('duration', () => {
  it('picks the native form, and says nothing for no duration', () => {
    expect(durationText(t, 45 * 60)).toBe('45 min')
    expect(durationText(t, 2 * 3600)).toBe('2 h')
    expect(durationText(t, 82 * 60)).toBe('1 h 22 min')
    expect(durationText(t, 0)).toBeNull()
  })
})

describe('place labels', () => {
  it('names an unnamed place "Unnamed place" with its coordinate as the secondary line', () => {
    const label = placeLabel(t, placeRow({ placeId: 'p', name: '  ', city: 'Munich', latE7: 481_374_300, lngE7: 115_754_900 }))
    expect(label).toEqual({ title: 'Unnamed place', unnamed: true, secondary: '48.13743, 11.57549' })
    expect(coordinateText(-337_000_000, 1_512_000_000)).toBe('-33.70000, 151.20000')
  })

  it('lets a confirmed role take the headline, but never an inferred one', () => {
    expect(placeLabel(t, placeRow({ placeId: 'p', name: 'Ring 12', role: 'home' })).title).toBe('Home')
    expect(placeLabel(t, placeRow({ placeId: 'p', name: 'Ring 12', role: 'home', roleIsSuggestion: true })).title).toBe('Ring 12')
    // A role this build cannot name falls back to the place's own name, never the wire token.
    expect(placeLabel(t, placeRow({ placeId: 'p', name: 'Ring 12', role: 'gym' })).title).toBe('Ring 12')
  })
})

describe('moment titles — derived, not the stored English title', () => {
  it('names a visit by its place, a movement by its mode, an activity by its kind', () => {
    expect(momentTitle(t, visit('v', 0, { id: 'p', name: 'Café Luitpold' }))).toBe('Café Luitpold')
    expect(momentTitle(t, visit('v', 0, { id: 'p', name: '' }))).toBe('Unnamed place')
    expect(momentTitle(t, movement('m', 0, 'cycle', 3000))).toBe('Ride')
    expect(momentTitle(t, movement('m', 0, 'hovercraft', 3000))).toBe('Movement')
    expect(momentTitle(t, activity('a', 0, 'Running'))).toBe('Running')
    // An activity type this build has no copy for keeps the stored title.
    expect(momentTitle(t, activity('a', 0, 'Bouldering', 'Evening climb'))).toBe('Evening climb')
  })
})

// ⚠ Feed rows are folded by the shared projection: the `span*` fields describe the row, while
// `distanceM`/`durationSec` describe only its representative stored moment.
describe('moment meta: the folded row, not its representative moment', () => {

  it('a collapsed ride shows its whole distance and duration, and its endpoints', () => {
    const ride = {
      ...movement('m1', 0, 'TRAIN', 2_400, 600),
      spanDistanceM: 34_200,
      spanDurationMin: 83,
      originPlace: 'Flat',
      destinationPlace: 'Office',
    }
    const meta = momentMeta(t, ride, 'metric')
    expect(meta).toContain('Flat → Office')
    expect(meta).toContain(distanceText(t, 34_200, 'metric'))
    expect(meta).not.toContain(distanceText(t, 2_400, 'metric'))
    expect(meta).toContain(durationText(t, 83 * 60)!)
  })

  it('a merged stay shows its total span', () => {
    const stay = { ...visit('v1', 0, { id: 'p1', name: 'Café' }, 1_800), spanDurationMin: 125, mergedStayCount: 2 }
    expect(momentMeta(t, stay, 'metric')).toBe(durationText(t, 125 * 60))
  })

  it('an unfolded row falls back to its own moment', () => {
    const walk = movement('m2', 0, 'WALKING', 2_400, 1_200)
    expect(momentMeta(t, walk, 'metric')).toBe(`${distanceText(t, 2_400, 'metric')} · ${durationText(t, 1_200)}`)
  })
})
