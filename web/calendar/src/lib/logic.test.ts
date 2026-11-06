// The ported `calendar-shared-logic` arithmetic: grids, layout, recurrence presets, placement in
// the reader's zone, and the derived calendar id.
import { describe, expect, it } from 'vitest'
import { addDays, monthGrid, shiftMonth, weekContaining } from './dates'
import { arrangeDay } from './dayLayout'
import { classifyRule, seriesAnchor, toRrule } from './recurrence'
import { placeAll, slices } from './placement'
import { derivedCalendarId } from './calendarId'
import { nextColour, PALETTE, sameColour } from './palette'
import { offeredReminders } from './reminders'
import { occurrence } from '../test/proxyMock'

describe('grids (MonthGrid / WeekGrid)', () => {
  it('a month is always six rows, led by the right weekday', () => {
    // October 2026 starts on a Thursday.
    const monday = monthGrid(2026, 10, 1)
    expect(monday).toHaveLength(42)
    expect(monday[0]!.date).toBe('2026-09-28')
    expect(monday[3]).toEqual({ date: '2026-10-01', inMonth: true })
    expect(monday[41]!.inMonth).toBe(false)
    const sunday = monthGrid(2026, 10, 7)
    expect(sunday[0]!.date).toBe('2026-09-27')
  })

  it('a month starting ON the week start has no leading days', () => {
    // June 2026 starts on a Monday.
    expect(monthGrid(2026, 6, 1)[0]!.date).toBe('2026-06-01')
  })

  it('weeks and month shifts cross year ends and DST without skipping a day', () => {
    expect(weekContaining('2026-10-25', 1)).toEqual([
      '2026-10-19', '2026-10-20', '2026-10-21', '2026-10-22', '2026-10-23', '2026-10-24', '2026-10-25',
    ])
    expect(addDays('2026-03-28', 2)).toBe('2026-03-30')
    expect(shiftMonth(2026, 12, 1)).toEqual({ year: 2027, month: 1 })
    expect(shiftMonth(2026, 1, -1)).toEqual({ year: 2025, month: 12 })
  })
})

describe('arrangeDay (DayLayout)', () => {
  const at = (start: number, end: number) => ({ start, end })

  it('back-to-back events do not overlap; overlapping ones share the width', () => {
    const items = [at(540, 600), at(600, 660), at(540, 570)]
    const laid = arrangeDay(items, (x) => x)
    expect(laid[1]).toMatchObject({ column: 0, columnCount: 1, columnSpan: 1 })
    expect(laid[0]).toMatchObject({ column: 0, columnCount: 2 })
    expect(laid[2]).toMatchObject({ column: 1, columnCount: 2 })
  })

  it('widens rightwards over a free column, and floors zero-length events', () => {
    const laid = arrangeDay([at(540, 720), at(540, 570), at(600, 630)], (x) => x)
    // The short 10:00 event takes column 1 alone after 09:30, so nothing widens past it…
    expect(laid[0]).toMatchObject({ column: 0, columnSpan: 1, columnCount: 2 })
    const zero = arrangeDay([at(780, 780)], (x) => x)[0]!
    expect(zero.bottomMinute - zero.topMinute).toBe(20)
  })
})

describe('recurrence presets', () => {
  it('maps the editor’s own shapes back to their preset, against the SERIES anchor', () => {
    // 2026-10-27 is a Tuesday.
    expect(classifyRule('', null)).toBe('never')
    expect(classifyRule('FREQ=DAILY', null)).toBe('daily')
    expect(classifyRule('FREQ=WEEKLY;BYDAY=TU', '2026-10-27')).toBe('weekly')
    expect(classifyRule('FREQ=MONTHLY', null)).toBe('monthly')
    expect(classifyRule('RRULE:FREQ=YEARLY', null)).toBe('yearly')
    // A moved occurrence still classifies against its original start (`recurrenceId`).
    const moved = { recurrenceId: '2026-10-27T09:00', startLocal: '2026-10-29T09:00' }
    expect(classifyRule('FREQ=WEEKLY;BYDAY=TU', seriesAnchor(moved))).toBe('weekly')
  })

  it('anything else is custom — shown, never rewritten', () => {
    for (const rule of ['FREQ=WEEKLY;INTERVAL=2', 'FREQ=DAILY;COUNT=5', 'FREQ=WEEKLY;BYDAY=MO,WE', 'FREQ=MONTHLY;BYDAY=2TU', 'FREQ=YEARLY;UNTIL=20270101', 'FREQ=HOURLY', 'BYDAY=MO']) {
      expect(classifyRule(rule, '2026-10-26'), rule).toBe('custom')
    }
    expect(toRrule('custom', '2026-10-26')).toBeNull()
  })

  it('weekly writes an explicit BYDAY from the start date', () => {
    expect(toRrule('weekly', '2026-10-27')).toBe('FREQ=WEEKLY;BYDAY=TU')
    expect(toRrule('never', '2026-10-27')).toBe('')
  })
})

describe('placement in the reader’s zone', () => {
  it('splits a timed event across midnight into two slices, and an all-day range into days', () => {
    const night = occurrence({ title: 'Night', date: '2026-10-26', from: '22:00', to: '23:59' })
    const across = { ...night, endEpochMs: night.startEpochMs + 4 * 3_600_000 }
    const parts = slices(across)
    expect(parts.map((p) => [p.date, p.start, p.end])).toEqual([
      ['2026-10-26', 22 * 60, 1440],
      ['2026-10-27', 0, 120],
    ])
    const trip = occurrence({ title: 'Trip', date: '2026-10-26', allDay: true, startLocal: '2026-10-26T00:00', endLocal: '2026-10-29T00:00' })
    expect(slices(trip).map((p) => p.date)).toEqual(['2026-10-26', '2026-10-27', '2026-10-28'])
  })

  it('an event ending exactly at midnight does not spill into the next day', () => {
    const late = occurrence({ title: 'Late', date: '2026-10-26', from: '23:00', to: '23:00' })
    const atMidnight = { ...late, endEpochMs: late.startEpochMs + 3_600_000 }
    expect(slices(atMidnight).map((p) => p.date)).toEqual(['2026-10-26'])
  })

  it('places by the INSTANT, so a zoned event lands on the reader’s day', () => {
    // An event whose own civil date is the 27th, but whose instant is the 26th on this clock.
    const o = occurrence({ title: 'Call', date: '2026-10-26', from: '23:00', to: '23:30', startLocal: '2026-10-27T08:00', zoneId: 'Asia/Tokyo' })
    const byDay = placeAll([o], ['2026-10-26', '2026-10-27'])
    expect(byDay.get('2026-10-26')).toHaveLength(1)
    expect(byDay.get('2026-10-27')).toHaveLength(0)
  })
})

describe('the derived calendar id', () => {
  it('matches Kotlin’s "cal-" + name.trim().lowercase().hashCode() as unsigned hex', () => {
    // Vectors from the JVM (jshell): "work".hashCode() == 0x37c711; "a".hashCode() == 97.
    expect(derivedCalendarId('  Work ')).toBe('cal-37c711')
    expect(derivedCalendarId('Caf\u00e9 \u00dc')).toBe('cal-ae787abd')
    expect(derivedCalendarId('a')).toBe('cal-61')
    // A negative Java hash is printed unsigned (`toUInt()`).
    expect(derivedCalendarId('polygenelubricants')).toBe(`cal-${(-2147483648 >>> 0).toString(16)}`)
  })
})

describe('palette and reminder offers', () => {
  it('skips taken colours, comparing RGB only', () => {
    expect(nextColour([0x004285f4])).toBe(PALETTE[1])
    expect(sameColour(0x004285f4, PALETTE[0]!)).toBe(true)
  })

  it('offers every stored offset, even one outside the presets', () => {
    expect(offeredReminders(false, [45])).toContain(45)
    expect(offeredReminders(true, [])).toEqual([2340, 900, 540, -540, -1080])
  })
})
