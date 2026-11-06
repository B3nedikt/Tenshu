// Presentation logic. The only native piece ported is `maps-shared-logic` `MapsFormat.relativeVisit`
// (the "last visit" bucket); the logic decides the FORM, the catalogue renders it (ADR 0016). The
// rest is the web's own: labels for a place row and the local search (`maps-app.md` §Web scope 1).
import type { TFunction } from 'i18next'
import type { MapsPlace } from '@tenshu/sdk/maps'

// ── labels ──────────────────────────────────────────────────────────────────

// Degrees → "48.13743, 11.57549". Five decimals is ~1 m. ⚠ Always `.` and `, `, whatever the UI
// language: this is also what *Copy coordinates* puts on the clipboard, and a map app pasting it
// must read it back.
export function coordinateText(latitude: number, longitude: number): string {
  return `${latitude.toFixed(5)}, ${longitude.toFixed(5)}`
}

// A CONFIRMED role's name (the SDK projects no suggestion, §Web scope 5d), or null.
export function roleName(t: TFunction, role: MapsPlace['role']): string | null {
  if (role === 'home') return t('role.home')
  if (role === 'work') return t('role.work')
  return null
}

// ⚠ One `Intl.DisplayNames` and one `Intl.Collator` per locale, for the page's life: both are
// expensive to build, and the search would otherwise build them per place, per keystroke and in the
// sort comparator. `null` caches a locale the engine refused.
const displayNamesCache = new Map<string, Intl.DisplayNames | null>()
const collatorCache = new Map<string, Intl.Collator>()

function regionNames(locale: string): Intl.DisplayNames | null {
  let names = displayNamesCache.get(locale)
  if (names === undefined) {
    try {
      names = new Intl.DisplayNames([locale], { type: 'region' })
    } catch {
      names = null
    }
    displayNamesCache.set(locale, names)
  }
  return names
}

function collatorFor(locale: string): Intl.Collator {
  let collator = collatorCache.get(locale)
  if (!collator) {
    collator = new Intl.Collator(locale, { sensitivity: 'base', numeric: true })
    collatorCache.set(locale, collator)
  }
  return collator
}

// The country's name in the UI language ("DE" → "Germany" / "Deutschland"), else the code itself.
export function countryName(locale: string, countryCode: string): string {
  const code = countryCode.trim().toUpperCase()
  if (!/^[A-Z]{2}$/.test(code)) return countryCode.trim()
  try {
    return regionNames(locale)?.of(code) ?? code
  } catch {
    return code
  }
}

export interface PlaceLabel {
  // The name, else "Unnamed place". ⚠ A role is a chip next to it, never the headline: native Maps
  // draws a confirmed role as the place's category (§Web scope 5d).
  readonly title: string
  readonly unnamed: boolean
  // "Munich, Germany"; the coordinate when the place has neither (an unnamed place always has the
  // coordinate, the one thing that tells two of them apart — the web has no geocoder).
  readonly secondary: string
}

export function whereText(locale: string, place: MapsPlace): string {
  return [place.city.trim(), countryName(locale, place.countryCode)].filter(Boolean).join(', ')
}

export function placeLabel(t: TFunction, locale: string, place: MapsPlace): PlaceLabel {
  const trimmed = place.name.trim()
  const unnamed = trimmed === ''
  const where = whereText(locale, place)
  return {
    title: unnamed ? t('place.unnamed') : trimmed,
    unnamed,
    secondary: unnamed || where === '' ? coordinateText(place.latitude, place.longitude) : where,
  }
}

// ── local search (§Web scope 1: TS only, no egress) ─────────────────────────

// Letters that are letters of their own, not a base letter plus a mark, so NFD leaves them whole:
// "kobenhavn" must find "København", "lodz" "Łódź", "reykjavik" "Reykjavík" … and "thorshofn"
// "Þórshöfn". Applied after lower-casing, so the capitals are covered too.
const TRANSLITERATE: Readonly<Record<string, string>> = {
  ß: 'ss',
  ø: 'o',
  æ: 'ae',
  œ: 'oe',
  ł: 'l',
  đ: 'd',
  ð: 'd',
  þ: 'th',
  ı: 'i',
}
const TRANSLITERABLE = new RegExp(`[${Object.keys(TRANSLITERATE).join('')}]`, 'g')

// Accent- and case-folded: "Café" and "cafe" are the same query. NFD splits "é" into "e" + a
// combining mark, which is dropped; `toLocaleLowerCase` with no locale argument folds case the same
// way for every UI language (no Turkish dotless-i surprise when the UI is not Turkish).
export function fold(text: string): string {
  return text
    .normalize('NFD')
    .replace(/\p{M}+/gu, '')
    .toLowerCase()
    .replace(TRANSLITERABLE, (c) => TRANSLITERATE[c]!)
    .replace(/\s+/g, ' ')
    .trim()
}

// What a query is matched against: the name, the city, the country's name AND code, and the role.
function haystack(t: TFunction, locale: string, place: MapsPlace): string {
  return fold(
    [place.name, place.city, place.countryCode, countryName(locale, place.countryCode), roleName(t, place.role) ?? ''].join(
      '\n',
    ),
  )
}

function queryWords(query: string): string[] {
  return fold(query).split(' ').filter(Boolean)
}

// Every word of the query must appear (in any order): "munich cafe" finds "Café Luitpold, Munich".
export function matches(t: TFunction, locale: string, place: MapsPlace, query: string): boolean {
  const words = queryWords(query)
  if (words.length === 0) return true
  const text = haystack(t, locale, place)
  return words.every((w) => text.includes(w))
}

// The list, sorted once with each place's folded haystack beside it: what a keystroke filters.
// ⚠ Build it in a `useMemo` keyed on the places and the language — never per keystroke.
export interface SearchIndex {
  readonly places: readonly MapsPlace[]
  readonly haystacks: readonly string[]
}

// Named places by name in the UI language's collation (accent- and case-insensitive, numeric:
// "Gate 2" before "Gate 10"), then unnamed ones. ⚠ The SDK's order is store order, which means
// nothing to a reader; a list sorted by name is what a search narrows.
export function searchIndex(t: TFunction, locale: string, places: readonly MapsPlace[]): SearchIndex {
  const collator = collatorFor(locale)
  const rows = places.map((place) => ({
    place,
    name: place.name.trim(),
    where: whereText(locale, place),
    haystack: haystack(t, locale, place),
  }))
  rows.sort((a, b) => {
    if (!a.name !== !b.name) return a.name ? -1 : 1
    return (
      collator.compare(a.name, b.name) ||
      collator.compare(a.where, b.where) ||
      (a.place.placeId < b.place.placeId ? -1 : a.place.placeId > b.place.placeId ? 1 : 0)
    )
  })
  return { places: rows.map((r) => r.place), haystacks: rows.map((r) => r.haystack) }
}

export function filterIndex(index: SearchIndex, query: string): readonly MapsPlace[] {
  const words = queryWords(query)
  if (words.length === 0) return index.places
  return index.places.filter((_, i) => {
    const text = index.haystacks[i]!
    return words.every((w) => text.includes(w))
  })
}

export function sortPlaces(t: TFunction, locale: string, places: readonly MapsPlace[]): MapsPlace[] {
  return [...searchIndex(t, locale, places).places]
}

export function searchPlaces(t: TFunction, locale: string, places: readonly MapsPlace[], query: string): readonly MapsPlace[] {
  return filterIndex(searchIndex(t, locale, places), query)
}

// ── last visit (`MapsFormat.relativeVisit`) ─────────────────────────────────

export type RelativeForm = 'today' | 'yesterday' | 'daysAgo' | 'weeksAgo' | 'monthsAgo' | 'yearsAgo'

export interface RelativeVisit {
  readonly form: RelativeForm
  // The magnitude; 0 and unused for today / yesterday.
  readonly value: number
}

// Calendar days in the browser's zone, as native counts `LocalDate`s.
function dayNumber(ms: number): number {
  const d = new Date(ms)
  return Math.floor(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) / 86_400_000)
}

// As native: future/zero deltas clamp to today; magnitudes are floored (6 days → "6 days", 13 days
// → "1 week"); from 28 days on, whole calendar months, then whole years.
export function relativeVisit(nowMs: number, visitMs: number): RelativeVisit {
  const days = dayNumber(nowMs) - dayNumber(visitMs)
  if (days <= 0) return { form: 'today', value: 0 }
  if (days === 1) return { form: 'yesterday', value: 0 }
  if (days < 7) return { form: 'daysAgo', value: days }
  if (days < 28) return { form: 'weeksAgo', value: Math.floor(days / 7) }
  const now = new Date(nowMs)
  const visit = new Date(visitMs)
  let months = (now.getFullYear() - visit.getFullYear()) * 12 + (now.getMonth() - visit.getMonth())
  if (now.getDate() < visit.getDate()) months -= 1
  if (months < 12) return { form: 'monthsAgo', value: Math.max(1, months) }
  return { form: 'yearsAgo', value: Math.max(1, Math.floor(months / 12)) }
}

export function lastVisitText(t: TFunction, nowMs: number, visitMs: number): string {
  const r = relativeVisit(nowMs, visitMs)
  return t(`lastVisit.${r.form}`, { count: r.value })
}

export function dateTimeText(locale: string, timestampMs: number): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(timestampMs)
}
