// The reminder offsets the editor offers — `calendar-shared-logic`'s `ReminderPresets`. DATA only
// on the web (calendar-app.md §Web scope W1): the user's phone and desktop ring them.
//
// ⚠⚠ Two lists, because a timed and an all-day event ask different questions: a timed event alerts
// before it starts; an all-day one starts at 00:00, so its offsets are a time of day on a day.
// Offsets are signed: positive before the start, negative after.
import type { TFunction } from 'i18next'
import { formatTime } from './dates'

export const TIMED_PRESETS: readonly number[] = [0, 5, 10, 15, 30, 60, 120, 1440]
// −540 = 09:00 on the day; 540 = 15:00 the day before.
export const ALL_DAY_PRESETS: readonly number[] = [-540, -1080, 540, 900, 2340]

export function presetsFor(allDay: boolean): readonly number[] {
  return allDay ? ALL_DAY_PRESETS : TIMED_PRESETS
}

/** What the editor offers: the presets plus any stored offset outside them, so none is lost. */
export function offeredReminders(allDay: boolean, current: readonly number[]): number[] {
  const all = new Set([...presetsFor(allDay), ...current])
  return [...all].sort((a, b) => (allDay ? b - a : a - b))
}

export function reminderLabel(t: TFunction, minutesBefore: number, allDay: boolean): string {
  if (allDay) {
    const afterMidnight = -minutesBefore
    const dayOffset = Math.floor(afterMidnight / 1440)
    const time = formatTime(afterMidnight - dayOffset * 1440)
    if (dayOffset === 0) return t('reminders.onTheDay', { time })
    return t('reminders.daysBeforeAt', { count: -dayOffset, time })
  }
  if (minutesBefore === 0) return t('reminders.atStart')
  if (minutesBefore < 0) return t('reminders.minutesAfter', { count: -minutesBefore })
  if (minutesBefore % 1440 === 0) return t('reminders.daysBefore', { count: minutesBefore / 1440 })
  if (minutesBefore % 60 === 0) return t('reminders.hoursBefore', { count: minutesBefore / 60 })
  return t('reminders.minutesBefore', { count: minutesBefore })
}
