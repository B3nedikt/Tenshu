// The calendars list: show/hide (this browser only), colour, rename, delete, the default, and
// create. ⚠⚠ Visibility reads the hidden-set STREAM, never the list's `visible` field: a read right
// after a write may predate it (CA-2g).
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { CalendarSummary } from '@tenshu/sdk/calendar'
import { StreamNotice } from '@tenshu/app-kit/ui'
import { useCalendars, useDefaultCalendar, useHidden } from '../lib/live'
import { attempt, type Outcome } from '../lib/outcome'
import { cssColour } from '../lib/palette'
import { deleteCalendar, setCalendarVisible, setDefaultCalendar } from '../lib/proxy'
import { CalendarDialog } from './CalendarDialog'
import { Button, ColourDot, ConfirmDialog, Loading, OutcomeText, ReadError } from './ui'

export function CalendarsView() {
  const { t } = useTranslation()
  const calendars = useCalendars()
  const hidden = useHidden()
  const def = useDefaultCalendar()
  const [dialog, setDialog] = useState<{ kind: 'create' } | { kind: 'edit' | 'delete'; calendar: CalendarSummary } | null>(null)
  const [outcome, setOutcome] = useState<Outcome>(null)

  const list = calendars.data?.ok ? calendars.data.calendars : null
  const hiddenSet = hidden.data?.ok ? new Set(hidden.data.hidden) : null
  const defaultId = def.data?.ok ? def.data.calendarId : null
  const stopped = { stopped: t('stream.stopped'), reconnect: t('stream.reconnect') }

  async function run(op: () => Promise<{ ok: true } | { ok: false; error: { kind: string } }>) {
    setOutcome(await attempt(op))
  }

  return (
    <section className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold text-text">{t('calendars.title')}</h2>
        <Button onClick={() => setDialog({ kind: 'create' })} disabled={!list}>
          {t('calendars.new')}
        </Button>
      </div>
      <StreamNotice live={calendars} copy={stopped} />
      <StreamNotice live={hidden} copy={stopped} />
      <ReadError live={calendars} />
      <ReadError live={hidden} />
      <ReadError live={def} />
      <OutcomeText outcome={outcome} />
      {!list && !calendars.error && !(calendars.data && !calendars.data.ok) && <Loading />}
      {list && list.length === 0 && (
        <p className="rounded-lg border border-line bg-surface-2 px-4 py-6 text-center text-sm text-text-2">
          {t('empty.body')}
        </p>
      )}
      {list && list.length > 0 && (
        <ul className="divide-y divide-line rounded-lg border border-line">
          {list.map((c) => {
            const visible = hiddenSet ? !hiddenSet.has(c.calendarId) : null
            return (
              <li key={c.calendarId} className="flex flex-wrap items-center gap-3 px-4 py-3">
                <label className="flex min-w-0 flex-1 items-center gap-3">
                  <input
                    type="checkbox"
                    aria-label={t('calendars.show', { name: c.name })}
                    checked={visible ?? true}
                    disabled={visible === null}
                    onChange={(e) => void run(() => setCalendarVisible(c.calendarId, e.target.checked))}
                  />
                  <ColourDot colour={cssColour(c.colorArgb)} size={12} />
                  <span className="truncate text-sm text-text">{c.name || t('calendars.unnamed')}</span>
                  {defaultId === c.calendarId && (
                    <span className="rounded-full bg-primary/10 px-2 py-0.5 text-xs text-primary">{t('calendars.default')}</span>
                  )}
                </label>
                <div className="flex gap-1">
                  {defaultId !== null && defaultId !== c.calendarId && (
                    <Button size="sm" variant="ghost" onClick={() => void run(() => setDefaultCalendar(c.calendarId))}>
                      {t('calendars.makeDefault')}
                    </Button>
                  )}
                  <Button size="sm" variant="ghost" onClick={() => setDialog({ kind: 'edit', calendar: c })}>
                    {t('calendars.edit')}
                  </Button>
                  <Button size="sm" variant="danger-ghost" onClick={() => setDialog({ kind: 'delete', calendar: c })}>
                    {t('calendars.delete')}
                  </Button>
                </div>
              </li>
            )
          })}
        </ul>
      )}
      <p className="text-xs text-text-3">{t('calendars.visibilityNote')}</p>

      {dialog?.kind === 'create' && list && (
        <CalendarDialog existing={list} hasDefault={!!defaultId} onClose={() => setDialog(null)} />
      )}
      {dialog?.kind === 'edit' && list && (
        <CalendarDialog calendar={dialog.calendar} existing={list} hasDefault={!!defaultId} onClose={() => setDialog(null)} />
      )}
      {dialog?.kind === 'delete' && (
        <ConfirmDialog
          title={t('calendars.deleteTitle', { name: dialog.calendar.name })}
          confirmLabel={t('calendars.delete')}
          onConfirm={() => attempt(() => deleteCalendar(dialog.calendar.calendarId))}
          onClose={() => setDialog(null)}
        >
          <p>{t('calendars.deleteBody')}</p>
        </ConfirmDialog>
      )}
    </section>
  )
}
