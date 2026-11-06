// The month grid (`MonthGrid`: always six rows of seven). A cell lists what is on the day, up to a
// few lines, then "+N more" — which opens the day, as does the date itself.
import { useTranslation } from 'react-i18next'
import { civilAsUtcDate, formatCivil, parseCivil, type CivilDate, type MonthCell } from '../lib/dates'
import type { Placed } from '../lib/placement'
import { occurrenceKey } from '../lib/placement'
import { EventChip } from './EventChip'

const LINES = 3

export function MonthView({
  cells,
  byDay,
  today,
  colourOf,
  onOpen,
  onOpenDay,
  onCreate,
}: {
  cells: readonly MonthCell[]
  byDay: ReadonlyMap<CivilDate, Placed[]>
  today: CivilDate
  colourOf: (calendarId: string) => string
  onOpen: (placed: Placed) => void
  onOpenDay: (date: CivilDate) => void
  onCreate: (date: CivilDate) => void
}) {
  const { t, i18n } = useTranslation()
  const weekday = new Intl.DateTimeFormat(i18n.language, { weekday: 'short', timeZone: 'UTC' })
  return (
    <div role="grid" aria-label={t('views.month')} className="overflow-hidden rounded-lg border border-line">
      <div role="row" className="grid grid-cols-7 border-b border-line bg-surface-2">
        {cells.slice(0, 7).map((c) => (
          <div role="columnheader" key={c.date} className="px-2 py-1 text-xs font-medium text-text-3">
            {weekday.format(civilAsUtcDate(c.date))}
          </div>
        ))}
      </div>
      {Array.from({ length: cells.length / 7 }, (_, row) => (
        <div role="row" key={row} className="grid grid-cols-7">
          {cells.slice(row * 7, row * 7 + 7).map((cell) => {
            const items = byDay.get(cell.date) ?? []
            const shown = items.slice(0, LINES)
            const more = items.length - shown.length
            return (
              <div
                role="gridcell"
                key={cell.date}
                data-date={cell.date}
                onDoubleClick={() => onCreate(cell.date)}
                className={`min-h-24 border-b border-r border-line p-1 last:border-r-0 ${cell.inMonth ? '' : 'bg-surface-2/60'}`}
              >
                <button
                  type="button"
                  onClick={() => onOpenDay(cell.date)}
                  aria-label={formatCivil(i18n.language, cell.date, { dateStyle: 'full' })}
                  className={`mb-0.5 grid h-6 w-6 place-items-center rounded-full text-xs ${
                    cell.date === today
                      ? 'bg-primary font-semibold text-on-primary'
                      : cell.inMonth
                        ? 'text-text hover:bg-surface-3'
                        : 'text-text-3 hover:bg-surface-3'
                  }`}
                >
                  {parseCivil(cell.date).day}
                </button>
                <div className="space-y-0.5">
                  {shown.map((p) => (
                    <EventChip
                      key={occurrenceKey(p.occurrence)}
                      placed={p}
                      colour={colourOf(p.occurrence.calendarId)}
                      onOpen={() => onOpen(p)}
                      compact
                    />
                  ))}
                  {more > 0 && (
                    <button type="button" onClick={() => onOpenDay(cell.date)} className="px-1.5 text-xs text-text-3 hover:text-text">
                      {t('views.more', { count: more })}
                    </button>
                  )}
                </div>
              </div>
            )
          })}
        </div>
      ))}
    </div>
  )
}
