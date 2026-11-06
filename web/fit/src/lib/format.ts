// Presentation logic ported from native (`fit-shared-logic` `FitFormat`, and the unit handling
// `ui-core` does for `LocalMeasurementSystem`): the logic decides the FORM, the catalogue renders it.
// Storage is SI everywhere (metres, seconds, kJ, cm, kg); display follows the profile's units, at
// the edge, and nothing here is ever written back.
import type { TFunction } from 'i18next'
import type { MeasurementSystem } from './units'

const FEET_PER_MILE = 5280
const METRES_PER_MILE = 1609.344
const KG_PER_LB = 0.45359237
const CM_PER_INCH = 2.54

// ── distance (`FitFormat.distanceParts`, plus imperial) ──────────────────────

export type DistanceForm = 'metres' | 'kilometres' | 'feet' | 'miles'

// ⚠ As native: `whole` + `tenths` carry km or mi, `small` carries m or ft. The decimal separator is
// the catalogue's (`2.4 km` / `2,4 km`), never the formatter's.
export interface DistanceParts {
  readonly whole: number
  readonly tenths: number
  readonly small: number
  readonly form: DistanceForm
}

export function distanceParts(distanceM: number, units: MeasurementSystem): DistanceParts {
  const m = Math.max(0, Math.trunc(distanceM))
  if (units === 'imperial') {
    const feet = Math.trunc((m * 10_000) / 3_048)
    // ⚠ Switches at 0.1 mi, not at a mile: the same readability point as metric's 1 km.
    if (feet < FEET_PER_MILE / 10) return { whole: 0, tenths: 0, small: feet, form: 'feet' }
    const totalTenths = Math.trunc((feet * 10 + FEET_PER_MILE / 2) / FEET_PER_MILE)
    return { whole: Math.trunc(totalTenths / 10), tenths: totalTenths % 10, small: 0, form: 'miles' }
  }
  if (m < 1000) return { whole: 0, tenths: 0, small: m, form: 'metres' }
  const totalTenths = Math.trunc((m + 50) / 100)
  return { whole: Math.trunc(totalTenths / 10), tenths: totalTenths % 10, small: 0, form: 'kilometres' }
}

export function distanceText(t: TFunction, distanceM: number, units: MeasurementSystem): string {
  const p = distanceParts(distanceM, units)
  switch (p.form) {
    case 'metres':
      return t('distance.m', { value: p.small })
    case 'feet':
      return t('distance.ft', { value: p.small })
    case 'kilometres':
      return t('distance.km', { whole: p.whole, tenths: p.tenths })
    case 'miles':
      return t('distance.mi', { whole: p.whole, tenths: p.tenths })
  }
}

// ── duration (`FitFormat.durationParts` / `clockParts`) ──────────────────────

export function minutesText(t: TFunction, totalMinutes: number): string {
  const m = Math.max(0, Math.trunc(totalMinutes))
  const hours = Math.floor(m / 60)
  const minutes = m % 60
  if (hours === 0) return t('duration.minutes', { minutes })
  if (minutes === 0) return t('duration.hours', { hours })
  return t('duration.hoursMinutes', { hours, minutes })
}

// `MM:SS` under an hour, `H:MM:SS` from there — a workout's elapsed time.
export function clockText(totalSeconds: number): string {
  const s = Math.max(0, Math.trunc(totalSeconds))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  const mm = String(m).padStart(h > 0 ? 2 : 1, '0')
  const ss = String(sec).padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`
}

// ── pace (`FitFormat.paceParts`, per km or per mile) ─────────────────────────

// `null` for a non-positive pace (native renders "—"; the caller omits the stat).
export function paceText(t: TFunction, secPerKm: number, units: MeasurementSystem): string | null {
  if (!(secPerKm > 0)) return null
  const perUnit = Math.round(units === 'imperial' ? (secPerKm * METRES_PER_MILE) / 1000 : secPerKm)
  const minutes = Math.floor(perUnit / 60)
  const seconds = String(perUnit % 60).padStart(2, '0')
  return units === 'imperial' ? t('pace.perMi', { minutes, seconds }) : t('pace.perKm', { minutes, seconds })
}

// ── energy (`FitFormat.kjToKcal`: stored kJ, shown as kcal on both systems) ──

export function kjToKcal(kj: number): number {
  return Math.round(kj * 0.2390057)
}

export function energyText(t: TFunction, kj: number): string {
  return t('energy.kcal', { value: kjToKcal(kj) })
}

// ── body ─────────────────────────────────────────────────────────────────────

export function weightText(t: TFunction, kg: number, units: MeasurementSystem): string {
  if (units === 'imperial') return t('weight.lb', { value: Math.round((kg / KG_PER_LB) * 10) / 10 })
  return t('weight.kg', { value: Math.round(kg * 10) / 10 })
}

export function heightText(t: TFunction, cm: number, units: MeasurementSystem): string {
  if (units === 'imperial') {
    const { feet, inches } = cmToFeetInches(cm)
    return t('height.ftIn', { feet, inches })
  }
  return t('height.cm', { value: Math.round(cm) })
}

export function cmToFeetInches(cm: number): { feet: number; inches: number } {
  const totalInches = Math.round(cm / CM_PER_INCH)
  return { feet: Math.floor(totalInches / 12), inches: totalInches % 12 }
}

// What a form entered, in the SDK's units. ⚠ Height is an integer number of centimetres on the wire.
export function feetInchesToCm(feet: number, inches: number): number {
  return Math.round((feet * 12 + inches) * CM_PER_INCH)
}

export function poundsToKg(lb: number): number {
  return lb * KG_PER_LB
}

export function kgToPounds(kg: number): number {
  return kg / KG_PER_LB
}

// ── time and day ─────────────────────────────────────────────────────────────

export function dateTimeText(locale: string, timestampMs: number): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(timestampMs)
}

// `date` is the SDK's 'YYYY-MM-DD' in the zone the read was grouped in. ⚠ Formatted as a calendar
// date (UTC noon, rendered in UTC), never re-zoned: the SDK already decided which day a row is.
export function dayText(locale: string, date: string, style: 'short' | 'long' = 'long'): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date)
  if (!match) return date
  const at = Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]), 12)
  const options: Intl.DateTimeFormatOptions =
    style === 'short' ? { month: 'numeric', day: 'numeric', timeZone: 'UTC' } : { dateStyle: 'medium', timeZone: 'UTC' }
  return new Intl.DateTimeFormat(locale, options).format(at)
}

export function numberText(locale: string, value: number): string {
  return new Intl.NumberFormat(locale).format(value)
}
