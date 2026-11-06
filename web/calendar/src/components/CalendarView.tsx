// The calendar itself: month, week, day and agenda over one live occurrence window, rendered in the
// reader's zone (calendar-app.md §Web scope W5).
//
// ⚠⚠ Failures render, never an empty grid: a failed calendars read, a failed window and a dead
// stream each say so. ⚠⚠ An empty BOOK (no calendar at all) is not an empty view either: it asks
// the user to create a calendar — on the web nothing seeds a "Personal" one (W3).
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { CalendarSummary } from '@tenshu/sdk/calendar'
import { StreamNotice } from '@tenshu/app-kit/ui'
import {
  addDays,
  formatCivil,
  monthGrid,
  parseCivil,
  shiftMonth,
  today as todayOf,
  weekContaining,
  weekStartFor,
  civil,
  type CivilDate,
} from '../lib/dates'
import { useCalendars, useDefaultCalendar, useHidden, useOccurrences } from '../lib/live'
import { cssColour, UNKNOWN_CALENDAR_COLOUR } from '../lib/palette'
import { placeAll, queryWindow, type Placed } from '../lib/placement'
import { AgendaView } from './AgendaView'
import { CalendarDialog } from './CalendarDialog'
import { EventEditor, type EditorTarget } from './EventEditor'
import { MonthView } from './MonthView'
import { TimeGridView } from './TimeGridView'
import { Button, ErrorText, Loading, ReadError } from './ui'

export type ViewKind = 'month' | 'week' | 'day' | 'agenda'
const VIEWS: readonly ViewKind[] = ['month', 'week', 'day', 'agenda']
export const AGENDA_DAYS = 30

export function CalendarView({ initialDate, initialView = 'month' }: { initialDate?: CivilDate; initialView?: ViewKind }) {
  const { t } = useTranslation()
  const calendars = useCalendars()
  const def = useDefaultCalendar()
  const [creatingCalendar, setCreatingCalendar] = useState(false)

  const list = calendars.data?.ok ? calendars.data.calendars : null
  const defaultId = def.data?.ok ? def.data.calendarId : ''

  if (!list) {
    return (
      <div className="space-y-3">
        <StreamNotice live={calendars} copy={{ stopped: t('stream.stopped'), reconnect: t('stream.reconnect') }} />
        <ReadError live={calendars} />
        {!calendars.error && !(calendars.data && !calendars.data.ok) && <Loading />}
      </div>
    )
  }

  if (list.length === 0) {
    return (
      <div className="rounded-lg border border-line bg-surface-2 px-6 py-10 text-center">
        <h2 className="text-base font-semibold text-text">{t('empty.title')}</h2>
        <p className="mx-auto mt-1 max-w-md text-sm text-text-2">{t('empty.body')}</p>
        <Button className="mt-4" onClick={() => setCreatingCalendar(true)}>
          {t('empty.create')}
        </Button>
        {creatingCalendar && <CalendarDialog existing={list} hasDefault={!!defaultId} onClose={() => setCreatingCalendar(false)} />}
      </div>
    )
  }

  return <Views calendars={list} defaultId={defaultId} initialDate={initialDate} initialView={initialView} />
}

function Views({
  calendars,
  defaultId,
  initialDate,
  initialView,
}: {
  calendars: readonly CalendarSummary[]
  defaultId: string
  initialDate?: CivilDate
  initialView: ViewKind
}) {
  const { t, i18n } = useTranslation()
  const now = todayOf()
  const [view, setView] = useState<ViewKind>(initialView)
  const [anchor, setAnchor] = useState<CivilDate>(initialDate ?? now)
  const [editor, setEditor] = useState<EditorTarget | null>(null)
  const weekStart = weekStartFor(i18n.language)
  const hidden = useHidden()

  const { days, cells } = useMemo(() => {
    const { year, month } = parseCivil(anchor)
    switch (view) {
      case 'month': {
        const grid = monthGrid(year, month, weekStart)
        return { days: grid.map((c) => c.date), cells: grid }
      }
      case 'week':
        return { days: weekContaining(anchor, weekStart), cells: [] }
      case 'day':
        return { days: [anchor], cells: [] }
      case 'agenda':
        return { days: Array.from({ length: AGENDA_DAYS }, (_, i) => addDays(anchor, i)), cells: [] }
    }
  }, [view, anchor, weekStart])

  const range = queryWindow(days[0]!, days[days.length - 1]!)
  const occurrences = useOccurrences(range.from, range.to)
  const result = occurrences.data

  const hiddenSet = hidden.data?.ok ? new Set(hidden.data.hidden) : null
  const byDay = useMemo(() => {
    if (!result?.ok) return new Map<CivilDate, Placed[]>()
    // ⚠ A failed hidden-set read shows everything, and says so (below) — never a silent filter.
    const visible = hiddenSet ? result.occurrences.filter((o) => !hiddenSet.has(o.calendarId)) : result.occurrences
    return placeAll(visible, days)
    // `hiddenSet` is rebuilt per render; its content is `hidden.data`.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [result, hidden.data, days])

  const colours = new Map(calendars.map((c) => [c.calendarId, cssColour(c.colorArgb)]))
  const colourOf = (id: string) => colours.get(id) ?? cssColour(UNKNOWN_CALENDAR_COLOUR)

  function step(delta: number) {
    setAnchor((a) => {
      switch (view) {
        case 'month': {
          const { year, month } = parseCivil(a)
          const next = shiftMonth(year, month, delta)
          return civil(next.year, next.month, 1)
        }
        case 'week':
          return addDays(a, 7 * delta)
        case 'day':
          return addDays(a, delta)
        case 'agenda':
          return addDays(a, AGENDA_DAYS * delta)
      }
    })
  }

  const open = (p: Placed) => setEditor({ mode: 'edit', occurrence: p.occurrence })
  const create = (date: CivilDate, minute?: number) => setEditor({ mode: 'create', date, minute })
  const openDay = (date: CivilDate) => {
    setAnchor(date)
    setView('day')
  }

  const label = rangeLabel(i18n.language, view, anchor, days)
  const hiddenFailed = hidden.error !== null || (hidden.data !== undefined && !hidden.data.ok)

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <div className="flex items-center gap-1">
          <Button size="sm" variant="outline" aria-label={t('nav.previous')} onClick={() => step(-1)}>
            ‹
          </Button>
          <Button size="sm" variant="outline" onClick={() => setAnchor(now)}>
            {t('nav.today')}
          </Button>
          <Button size="sm" variant="outline" aria-label={t('nav.next')} onClick={() => step(1)}>
            ›
          </Button>
        </div>
        <h2 className="min-w-0 flex-1 truncate text-lg font-semibold text-text" aria-live="polite">
          {label}
        </h2>
        <div role="tablist" aria-label={t('views.label')} className="flex rounded-lg border border-line p-0.5">
          {VIEWS.map((v) => (
            <button
              key={v}
              type="button"
              role="tab"
              aria-selected={view === v}
              onClick={() => setView(v)}
              className={`rounded-md px-2.5 py-1 text-sm ${view === v ? 'bg-primary text-on-primary' : 'text-text-2 hover:text-text'}`}
            >
              {t(`views.${v}`)}
            </button>
          ))}
        </div>
        <Button size="sm" onClick={() => create(view === 'month' && anchor.slice(0, 7) === now.slice(0, 7) ? now : anchor)}>
          {t('editor.new')}
        </Button>
      </div>

      <StreamNotice live={occurrences} copy={{ stopped: t('stream.stopped'), reconnect: t('stream.reconnect') }} />
      {result?.ok && result.readerZoneFallback && (
        <p role="status" className="rounded-lg border border-line bg-surface-2 px-4 py-2 text-sm text-text-2">
          {t('zone.readerFallback')}
        </p>
      )}
      {hiddenFailed && (
        <div className="space-y-1">
          <p role="status" className="text-sm text-warning">
            {t('views.hiddenUnreadable')}
          </p>
          <ErrorText failure={hidden.data && !hidden.data.ok ? hidden.data.error : null} error={hidden.error} />
        </div>
      )}

      {occurrences.error || (result && !result.ok) ? (
        <ReadError live={occurrences} />
      ) : !result ? (
        <Loading />
      ) : view === 'month' ? (
        <MonthView cells={cells} byDay={byDay} today={now} colourOf={colourOf} onOpen={open} onOpenDay={openDay} onCreate={create} />
      ) : view === 'agenda' ? (
        <AgendaView days={days} byDay={byDay} today={now} colourOf={colourOf} onOpen={open} />
      ) : (
        <TimeGridView days={days} byDay={byDay} today={now} colourOf={colourOf} onOpen={open} onCreate={create} />
      )}

      {editor && (
        <EventEditor
          key={editor.mode === 'edit' ? `${editor.occurrence.eventId}@${editor.occurrence.recurrenceId}` : 'create'}
          target={editor}
          calendars={calendars}
          defaultCalendarId={defaultId}
          onClose={() => setEditor(null)}
        />
      )}
    </div>
  )
}

function rangeLabel(language: string, view: ViewKind, anchor: CivilDate, days: readonly CivilDate[]): string {
  switch (view) {
    case 'month':
      return formatCivil(language, anchor, { month: 'long', year: 'numeric' })
    case 'day':
      return formatCivil(language, anchor, { dateStyle: 'full' })
    case 'week':
    case 'agenda': {
      const f = new Intl.DateTimeFormat(language, { dateStyle: 'medium', timeZone: 'UTC' })
      const first = days[0]!
      const last = days[days.length - 1]!
      return f.formatRange(new Date(`${first}T12:00:00Z`), new Date(`${last}T12:00:00Z`))
    }
  }
}
