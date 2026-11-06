// Presentation logic ported from native (`timeline-shared-logic` `TimelineFormat`, and
// `timeline-shared-ui` `TimelineText.kt` / `PlaceRoleUi.kt`): the logic decides the FORM, the
// catalogue renders it. Only what this app needs is ported — no photo counts, no "now", no route
// endpoints (the row folds that derive them live in the projection, not here).
import type { TFunction } from 'i18next'
import type { TimelineDayMoment, TimelinePlace } from '@tenshu/sdk/timeline'
import type { MeasurementSystem } from './units'

// ── distance (`TimelineFormat.distanceParts`) ─────────────────────────────────

export type DistanceForm = 'metres' | 'kilometres' | 'feet' | 'miles'

// ⚠ As native: `whole` + `tenths` carry km or mi, `small` carries m or ft. The decimal separator
// is the catalogue's (`2.4 km` / `2,4 km`), never the formatter's.
export interface DistanceParts {
  readonly whole: number
  readonly tenths: number
  readonly small: number
  readonly form: DistanceForm
}

const FEET_PER_MILE = 5280

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
  // Nearest tenth of a kilometre, carrying into the whole part (999.96 km → 1000.0).
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

// ── duration (`TimelineFormat.durationParts`) ────────────────────────────────

export function durationText(t: TFunction, durationSec: number): string | null {
  if (durationSec <= 0) return null
  const total = Math.floor(durationSec / 60)
  const hours = Math.floor(total / 60)
  const minutes = total % 60
  if (hours === 0) return t('duration.minutes', { minutes })
  if (minutes === 0) return t('duration.hours', { hours })
  return t('duration.hoursMinutes', { hours, minutes })
}

// ── time and day ─────────────────────────────────────────────────────────────

export function timeText(locale: string, timestampMs: number): string {
  return new Intl.DateTimeFormat(locale, { hour: 'numeric', minute: '2-digit' }).format(timestampMs)
}

export function dateTimeText(locale: string, timestampMs: number): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(timestampMs)
}

// `date` is the SDK's 'YYYY-MM-DD' in the zone the read was grouped in. ⚠ Formatted as a calendar
// date (UTC noon, rendered in UTC), never re-zoned: the SDK already decided which day a row is.
export function dayHeading(locale: string, date: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date)
  if (!match) return date
  const at = Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]), 12)
  return new Intl.DateTimeFormat(locale, {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
    timeZone: 'UTC',
  }).format(at)
}

// ── places ───────────────────────────────────────────────────────────────────

// E7 fixed-point → "48.13743, 11.57549". Five decimals is ~1 m: the coordinate is the secondary
// line of an unnamed place (§Web scope 2), the one thing that tells two of them apart.
export function coordinateText(latE7: number, lngE7: number): string {
  return `${(latE7 / 1e7).toFixed(5)}, ${(lngE7 / 1e7).toFixed(5)}`
}

// A CONFIRMED role's name, or null for none and for a role this build cannot name. ⚠ null rather
// than the raw token: a newer peer may sync `gym`, and printing a wire string is worse than
// falling back to the place's own name (`placeRoleName`).
export function roleName(t: TFunction, role: string | null | undefined): string | null {
  if (role === 'home') return t('role.home')
  if (role === 'work') return t('role.work')
  return null
}

export interface PlaceLabel {
  // The headline: the confirmed role (D9), else the name, else "Unnamed place".
  readonly title: string
  readonly unnamed: boolean
  // The coordinate when unnamed, else the city/country line (either may be '').
  readonly secondary: string
}

function placeLabelOf(
  t: TFunction,
  name: string,
  role: string | null,
  city: string,
  countryCode: string,
  latE7: number,
  lngE7: number,
): PlaceLabel {
  const trimmed = name.trim()
  const unnamed = trimmed === ''
  const title = roleName(t, role) ?? (unnamed ? t('place.unnamed') : trimmed)
  const where = [city.trim(), countryCode.trim()].filter(Boolean).join(', ')
  return { title, unnamed, secondary: unnamed ? coordinateText(latE7, lngE7) : where }
}

// A place-list / place-detail label. ⚠ An INFERRED role (`roleIsSuggestion`) is a question, never
// the headline — only a confirmed one renames the place.
export function placeLabel(t: TFunction, place: TimelinePlace): PlaceLabel {
  const confirmed = place.roleIsSuggestion ? null : place.role
  return placeLabelOf(t, place.name, confirmed, place.city, place.countryCode, place.latE7, place.lngE7)
}

// ── moments (`momentTitleText` / `momentMetaText`) ───────────────────────────

const MOVEMENT_MODES = ['walk', 'run', 'cycle', 'drive', 'transit', 'flight'] as const
const ACTIVITY_TYPES = ['running', 'walking', 'cycling', 'strength', 'other'] as const

// An unrecognised mode (a newer peer's) reads as the generic "Movement", never blank.
export function movementModeText(t: TFunction, mode: string | null): string {
  return (MOVEMENT_MODES as readonly string[]).includes(mode ?? '') ? t(`movement.${mode}`) : t('movement.unknown')
}

// `Workout.activityType` (`Running` / `Walking` / …) localised, or null for one this build has no
// copy for — the caller then keeps the stored title rather than printing a token.
export function activityTypeText(t: TFunction, activityType: string | null): string | null {
  const key = (activityType ?? '').toLowerCase()
  return (ACTIVITY_TYPES as readonly string[]).includes(key) ? t(`activity.${key}`) : null
}

// The row's place label, when it is a visit to a resolved place.
export function momentPlaceLabel(t: TFunction, m: TimelineDayMoment): PlaceLabel | null {
  if (m.placeName === null) return null
  return placeLabelOf(t, m.placeName, m.placeRole, m.placeCity ?? '', m.placeCountryCode ?? '', m.placeLatE7 ?? 0, m.placeLngE7 ?? 0)
}

// The headline, derived rather than read off the stored (ingest-composed, English) `title`.
export function momentTitle(t: TFunction, m: TimelineDayMoment): string {
  switch (m.kind) {
    case 'Visit': {
      const label = momentPlaceLabel(t, m)
      if (label) return label.title
      // The place row is not on this device (yet): say so rather than pretend it is unnamed.
      return m.subjectResolved ? m.title || t('place.unnamed') : t('moment.placePending')
    }
    case 'Movement':
      return movementModeText(t, m.movementMode)
    case 'Activity':
      return activityTypeText(t, m.activityType) ?? (m.title || t('kind.Activity'))
    default:
      return m.title || kindText(t, m.kind)
  }
}

export function kindText(t: TFunction, kind: string): string {
  return ['Activity', 'Visit', 'Event', 'Quiet', 'Movement'].includes(kind) ? t(`kind.${kind}`) : t('kind.other')
}

// "2.4 km · 31 min" for a movement, the duration alone for everything else.
// ⚠ A feed row is FOLDED (the shared `:timeline-projection`): its `span*` fields describe the whole
// row — a merged stay, or a ride with its station stops collapsed — while `distanceM`/`durationSec`
// still describe the one representative stored moment. Prefer the span; fall back to the moment.
export function momentMeta(t: TFunction, m: TimelineDayMoment, units: MeasurementSystem): string {
  const parts: string[] = []
  if (m.kind === 'Movement') {
    if (m.originPlace && m.destinationPlace) parts.push(`${m.originPlace} → ${m.destinationPlace}`)
    const distance = m.spanDistanceM ?? m.distanceM
    if (distance !== null) parts.push(distanceText(t, distance, units))
  }
  const duration = durationText(t, m.spanDurationMin !== null ? m.spanDurationMin * 60 : m.durationSec)
  if (duration) parts.push(duration)
  return parts.join(' · ')
}
