import { describe, expect, it } from 'vitest'
import i18n from '../i18n'
import { addDays, datesBetween, localToday } from './dates'
import { windowFor } from './dashboard'
import { clockText, cmToFeetInches, distanceParts, distanceText, energyText, feetInchesToCm, heightText, kjToKcal, paceText, poundsToKg, weightText } from './format'

const t = i18n.getFixedT('en')
const de = i18n.getFixedT('de')

describe('units', () => {
  it('distance: metres under 1 km, then tenths of a km; feet under 0.1 mi, then tenths of a mile', () => {
    expect(distanceParts(999, 'metric')).toEqual({ whole: 0, tenths: 0, small: 999, form: 'metres' })
    expect(distanceText(t, 1_049, 'metric')).toBe('1.0 km')
    expect(distanceText(t, 1_050, 'metric')).toBe('1.1 km')
    expect(distanceText(de, 5_250, 'metric')).toBe('5,3 km')
    expect(distanceText(t, 100, 'imperial')).toBe('328 ft')
    expect(distanceText(t, 1_609, 'imperial')).toBe('1.0 mi')
    expect(distanceText(t, 10_000, 'imperial')).toBe('6.2 mi')
  })

  it('pace per km, or per mile in imperial; none for a non-positive pace', () => {
    expect(paceText(t, 330, 'metric')).toBe('5:30 /km')
    expect(paceText(t, 330, 'imperial')).toBe('8:51 /mi')
    expect(paceText(t, 0, 'metric')).toBeNull()
  })

  it('energy is stored in kJ and shown in kcal on both systems (FitFormat.kjToKcal)', () => {
    expect(kjToKcal(1_000)).toBe(239)
    expect(energyText(t, 4_184)).toBe('1000 kcal')
  })

  it('weight and height', () => {
    expect(weightText(t, 72.46, 'metric')).toBe('72.5 kg')
    expect(weightText(t, 72.5, 'imperial')).toBe('159.8 lb')
    expect(heightText(t, 180, 'metric')).toBe('180 cm')
    expect(heightText(t, 180, 'imperial')).toBe('5′ 11″')
    expect(cmToFeetInches(178)).toEqual({ feet: 5, inches: 10 })
    expect(feetInchesToCm(5, 10)).toBe(178)
    expect(poundsToKg(160)).toBeCloseTo(72.5748, 3)
  })

  it('a workout clock is MM:SS under an hour, H:MM:SS from there', () => {
    expect(clockText(59)).toBe('0:59')
    expect(clockText(1_800)).toBe('30:00')
    expect(clockText(3_725)).toBe('1:02:05')
  })
})

describe('days', () => {
  it('today is the browser zone’s calendar date, and arithmetic never crosses a DST hour', () => {
    expect(localToday(new Date(2026, 2, 29, 0, 30))).toBe('2026-03-29')
    expect(addDays('2026-03-28', 1)).toBe('2026-03-29')
    expect(addDays('2026-01-01', -1)).toBe('2025-12-31')
    expect(datesBetween('2024-02-27', '2024-03-01')).toEqual(['2024-02-27', '2024-02-28', '2024-02-29', '2024-03-01'])
  })

  it('a range reads itself plus the period before, while that fits the SDK’s 400-day window', () => {
    expect(windowFor('week', '2026-10-28')).toEqual({ from: '2026-10-15', to: '2026-10-28', days: 7, withPrevious: true })
    expect(windowFor('month', '2026-10-28')).toEqual({ from: '2026-08-30', to: '2026-10-28', days: 30, withPrevious: true })
    expect(windowFor('year', '2026-10-28')).toEqual({ from: '2025-10-29', to: '2026-10-28', days: 365, withPrevious: false })
  })
})
