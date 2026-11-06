// The week / day time grid (CA-4): an all-day row, then 24 hours with each day's timed events laid
// out by `arrangeDay`. Clicking empty time creates an event there, on the half hour.
import { useEffect, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { civilAsUtcDate, formatTime, type CivilDate } from '../lib/dates'
import { arrangeDay, MINUTES_PER_DAY } from '../lib/dayLayout'
import { occurrenceKey, type Placed } from '../lib/placement'
import { EventChip, ZoneMark } from './EventChip'

const HOUR_PX = 44
const PX_PER_MINUTE = HOUR_PX / 60

export function TimeGridView({
  days,
  byDay,
  today,
  colourOf,
  onOpen,
  onCreate,
}: {
  days: readonly CivilDate[]
  byDay: ReadonlyMap<CivilDate, Placed[]>
  today: CivilDate
  colourOf: (calendarId: string) => string
  onOpen: (placed: Placed) => void
  onCreate: (date: CivilDate, minute?: number) => void
}) {
  const { t, i18n } = useTranslation()
  const scroller = useRef<HTMLDivElement>(null)
  const header = new Intl.DateTimeFormat(i18n.language, { weekday: 'short', day: 'numeric', timeZone: 'UTC' })
  const columns = `3.5rem repeat(${days.length}, minmax(0, 1fr))`

  // Open on the working day, not on midnight.
  useEffect(() => {
    if (scroller.current) scroller.current.scrollTop = 7 * HOUR_PX
  }, [])

  return (
    <div role="grid" aria-label={days.length === 1 ? t('views.day') : t('views.week')} className="overflow-hidden rounded-lg border border-line">
      <div role="row" className="grid border-b border-line bg-surface-2" style={{ gridTemplateColumns: columns }}>
        <div />
        {days.map((d) => (
          <div role="columnheader" key={d} className={`px-2 py-1 text-xs font-medium ${d === today ? 'text-primary' : 'text-text-3'}`}>
            {header.format(civilAsUtcDate(d))}
          </div>
        ))}
      </div>
      <div role="row" className="grid border-b border-line" style={{ gridTemplateColumns: columns }}>
        <div className="px-1 py-1 text-right text-[10px] text-text-3">{t('views.allDay')}</div>
        {days.map((d) => (
          <div role="gridcell" key={d} data-allday={d} className="min-h-7 space-y-0.5 border-l border-line p-0.5">
            {(byDay.get(d) ?? [])
              .filter((p) => p.allDay)
              .map((p) => (
                <EventChip key={occurrenceKey(p.occurrence)} placed={p} colour={colourOf(p.occurrence.calendarId)} onOpen={() => onOpen(p)} compact />
              ))}
          </div>
        ))}
      </div>
      <div ref={scroller} className="max-h-[36rem] overflow-y-auto">
        <div role="row" className="relative grid" style={{ gridTemplateColumns: columns, height: 24 * HOUR_PX }}>
          <div className="relative">
            {Array.from({ length: 24 }, (_, h) => (
              <div key={h} className="absolute right-1 -translate-y-1/2 text-[10px] tabular-nums text-text-3" style={{ top: h * HOUR_PX }}>
                {h === 0 ? '' : formatTime(h * 60)}
              </div>
            ))}
          </div>
          {days.map((d) => (
            <DayColumn
              key={d}
              date={d}
              items={(byDay.get(d) ?? []).filter((p) => !p.allDay)}
              colourOf={colourOf}
              onOpen={onOpen}
              onCreate={onCreate}
            />
          ))}
        </div>
      </div>
    </div>
  )
}

function DayColumn({
  date,
  items,
  colourOf,
  onOpen,
  onCreate,
}: {
  date: CivilDate
  items: Placed[]
  colourOf: (calendarId: string) => string
  onOpen: (placed: Placed) => void
  onCreate: (date: CivilDate, minute?: number) => void
}) {
  const { t } = useTranslation()
  const laidOut = arrangeDay(items, (p) => ({ start: p.start, end: p.end }))
  return (
    <div
      role="gridcell"
      data-day={date}
      className="relative border-l border-line"
      onClick={(e) => {
        const rect = e.currentTarget.getBoundingClientRect()
        const minute = Math.floor((e.clientY - rect.top) / PX_PER_MINUTE / 30) * 30
        onCreate(date, Math.min(Math.max(minute, 0), MINUTES_PER_DAY - 60))
      }}
    >
      {Array.from({ length: 24 }, (_, h) => (
        <div key={h} aria-hidden className="absolute inset-x-0 border-t border-line/60" style={{ top: h * HOUR_PX }} />
      ))}
      {laidOut.map((pos) => {
        const o = pos.value.occurrence
        const colour = colourOf(o.calendarId)
        return (
          <button
            key={occurrenceKey(o)}
            type="button"
            data-event={o.eventId}
            onClick={(e) => {
              e.stopPropagation()
              onOpen(pos.value)
            }}
            className="absolute overflow-hidden rounded border-l-4 px-1 py-0.5 text-left text-xs text-text"
            style={{
              top: pos.topMinute * PX_PER_MINUTE,
              height: Math.max((pos.bottomMinute - pos.topMinute) * PX_PER_MINUTE - 1, 12),
              left: `calc(${(pos.column / pos.columnCount) * 100}% + 1px)`,
              width: `calc(${(pos.columnSpan / pos.columnCount) * 100}% - 2px)`,
              borderLeftColor: colour,
              backgroundColor: `${colour}26`,
            }}
          >
            <span className="flex items-center gap-1">
              <span className="truncate font-medium">{o.title || t('event.untitled')}</span>
              {o.zoneUnresolved && <ZoneMark />}
            </span>
            {!pos.value.continuesBefore && (
              <span className="block tabular-nums text-text-3">{formatTime(pos.value.start)}</span>
            )}
          </button>
        )
      })}
    </div>
  )
}
