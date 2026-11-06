// The recurrence choices the editor offers, mapped to and from RFC 5545 — a port of
// `calendar-shared-logic`'s `RecurrencePreset`.
//
// ⚠⚠ `custom` is the load-bearing member: an imported rule can be anything, and the editor must
// SHOW an event whose rule it cannot offer without lying about it or rewriting it (calendar-app.md
// §3 D2, "refuse to edit rather than silently rewrite"). It is a state, never a choice.
import { isoWeekday, type CivilDate } from './dates'

export type RecurrencePreset = 'never' | 'daily' | 'weekly' | 'monthly' | 'yearly' | 'custom'

// In picker order. ⚠⚠ `custom` absent on purpose.
export const OFFERABLE: readonly RecurrencePreset[] = ['never', 'daily', 'weekly', 'monthly', 'yearly']

const DAY_CODES = ['MO', 'TU', 'WE', 'TH', 'FR', 'SA', 'SU'] as const

/**
 * Classifies a stored `RRULE` against the SERIES' anchor date. ⚠ Only the exact shapes this editor
 * emits map back to a named preset: `FREQ=WEEKLY;INTERVAL=2` is weekly-ish but would lose its
 * interval on an unrelated save, so it is `custom`. Stricter than native's parser on one point: a
 * part this port does not know at all is `custom` too, rather than ignored.
 */
export function classifyRule(rrule: string, anchor: CivilDate | null): RecurrencePreset {
  const text = rrule.trim().replace(/^RRULE:/i, '')
  if (!text) return 'never'
  const parts = new Map<string, string>()
  for (const part of text.split(';')) {
    if (!part) continue
    const eq = part.indexOf('=')
    if (eq <= 0) return 'custom'
    parts.set(part.slice(0, eq).toUpperCase(), part.slice(eq + 1).toUpperCase())
  }
  const freq = parts.get('FREQ')
  if (!freq) return 'custom'
  for (const [key, value] of parts) {
    if (key === 'FREQ') continue
    if (key === 'INTERVAL' && value === '1') continue
    // A week start changes nothing for an interval-1 rule with at most one weekday.
    if (key === 'WKST') continue
    if (key === 'BYDAY' && freq === 'WEEKLY') continue
    return 'custom'
  }
  switch (freq) {
    case 'DAILY':
      return 'daily'
    case 'WEEKLY': {
      const byDay = parts.get('BYDAY')
      if (byDay === undefined) return 'weekly'
      return anchor && byDay === DAY_CODES[isoWeekday(anchor) - 1] ? 'weekly' : 'custom'
    }
    case 'MONTHLY':
      return 'monthly'
    case 'YEARLY':
      return 'yearly'
    default:
      return 'custom'
  }
}

/**
 * The anchor to classify an occurrence's rule against: its `recurrenceId` (the ORIGINAL start), not
 * its effective start, or a moved weekly occurrence would read as `custom` (`RecurrencePreset.of`).
 */
export function seriesAnchor(occurrence: { recurrenceId: string; startLocal: string }): CivilDate {
  const fromId = occurrence.recurrenceId.slice(0, 10)
  return /^\d{4}-\d{2}-\d{2}$/.test(fromId) ? fromId : occurrence.startLocal.slice(0, 10)
}

/**
 * The `RRULE` a preset stores, given the event's start date; `null` for `custom`, which an editor
 * must keep as it is. ⭐ `weekly` writes an explicit `BYDAY` so a later move of the event does not
 * silently drag the weekday with it.
 */
export function toRrule(preset: RecurrencePreset, anchor: CivilDate): string | null {
  switch (preset) {
    case 'never':
      return ''
    case 'custom':
      return null
    case 'daily':
      return 'FREQ=DAILY'
    case 'weekly':
      return `FREQ=WEEKLY;BYDAY=${DAY_CODES[isoWeekday(anchor) - 1]}`
    case 'monthly':
      return 'FREQ=MONTHLY'
    case 'yearly':
      return 'FREQ=YEARLY'
  }
}
