// The agenda: the days ahead that have something on them, in order. An empty stretch says so, with
// how far it looked — never a blank page.
import { useTranslation } from 'react-i18next'
import { formatCivil, formatTime, type CivilDate } from '../lib/dates'
import { occurrenceKey, type Placed } from '../lib/placement'
import { ZoneMark } from './EventChip'
import { ColourDot } from './ui'

export function AgendaView({
  days,
  byDay,
  today,
  colourOf,
  onOpen,
}: {
  days: readonly CivilDate[]
  byDay: ReadonlyMap<CivilDate, Placed[]>
  today: CivilDate
  colourOf: (calendarId: string) => string
  onOpen: (placed: Placed) => void
}) {
  const { t, i18n } = useTranslation()
  const withEvents = days.filter((d) => (byDay.get(d) ?? []).length > 0)
  if (withEvents.length === 0) {
    return (
      <p className="rounded-lg border border-line bg-surface-2 px-4 py-6 text-center text-sm text-text-2">
        {t('views.agendaEmpty', { count: days.length })}
      </p>
    )
  }
  return (
    <ol aria-label={t('views.agenda')} className="space-y-4">
      {withEvents.map((d) => (
        <li key={d}>
          <h3 className={`mb-1 text-sm font-semibold ${d === today ? 'text-primary' : 'text-text'}`}>
            {formatCivil(i18n.language, d, { dateStyle: 'full' })}
          </h3>
          <ul className="divide-y divide-line rounded-lg border border-line">
            {(byDay.get(d) ?? []).map((p) => {
              const o = p.occurrence
              return (
                <li key={occurrenceKey(o)}>
                  <button
                    type="button"
                    data-event={o.eventId}
                    onClick={() => onOpen(p)}
                    className="flex w-full items-center gap-3 px-3 py-2 text-left hover:bg-surface-3"
                  >
                    <ColourDot colour={colourOf(o.calendarId)} />
                    <span className="w-24 shrink-0 text-xs tabular-nums text-text-3">
                      {p.allDay ? t('views.allDay') : `${formatTime(p.start)}–${p.end >= 1440 ? '24:00' : formatTime(p.end)}`}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="flex items-center gap-1 truncate text-sm text-text">
                        {o.title || t('event.untitled')}
                        {o.zoneUnresolved && <ZoneMark />}
                      </span>
                      {o.location && <span className="block truncate text-xs text-text-3">{o.location}</span>}
                    </span>
                    {o.isRecurring && <span className="text-xs text-text-3" aria-label={t('views.repeats')}>↻</span>}
                  </button>
                </li>
              )
            })}
          </ul>
        </li>
      ))}
    </ol>
  )
}
