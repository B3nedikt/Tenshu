// Create or edit a calendar: name, colour and the lock-screen control. Creating the first calendar
// also makes it the default (an empty book has none, and on the web nothing seeds one — W3).
import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import type { CalendarSummary } from '@tenshu/sdk/calendar'
import { derivedCalendarId } from '../lib/calendarId'
import { attempt, type Outcome } from '../lib/outcome'
import { cssColour, nextColour, PALETTE, sameColour } from '../lib/palette'
import { saveCalendar, setDefaultCalendar } from '../lib/proxy'
import { Button, Dialog, Field, OutcomeText } from './ui'

export function CalendarDialog({
  calendar,
  existing,
  hasDefault,
  onClose,
}: {
  // Absent = create.
  calendar?: CalendarSummary
  existing: readonly CalendarSummary[]
  // Whether a default calendar is set; a create makes itself the default when not.
  hasDefault: boolean
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [name, setName] = useState(calendar?.name ?? '')
  const [colour, setColour] = useState(calendar?.colorArgb ?? nextColour(existing.map((c) => c.colorArgb)))
  const [sensitive, setSensitive] = useState(calendar?.sensitive ?? false)
  const [busy, setBusy] = useState(false)
  const [outcome, setOutcome] = useState<Outcome>(null)

  const trimmed = name.trim()
  const collides =
    !calendar && trimmed !== '' && existing.some((c) => c.calendarId === derivedCalendarId(trimmed))

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!trimmed || collides) return
    setBusy(true)
    setOutcome(null)
    let createdId = ''
    let result = await attempt(async () => {
      const r = await saveCalendar(calendar?.calendarId ?? '', trimmed, colour, sensitive)
      if (r.ok) createdId = r.calendarId
      return r
    })
    if (!result && !calendar && !hasDefault) result = await attempt(() => setDefaultCalendar(createdId))
    if (result) {
      setOutcome(result)
      setBusy(false)
    } else {
      onClose()
    }
  }

  return (
    <Dialog title={calendar ? t('calendars.editTitle') : t('calendars.createTitle')} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <Field
          autoFocus
          label={t('calendars.name')}
          value={name}
          onChange={(e) => setName(e.target.value)}
          error={collides ? t('calendars.nameTaken') : null}
        />
        <fieldset>
          <legend className="text-sm text-text-2">{t('calendars.colour')}</legend>
          <div className="mt-2 flex flex-wrap gap-2">
            {PALETTE.map((c, i) => (
              <button
                key={c}
                type="button"
                aria-label={t('calendars.colourN', { n: i + 1 })}
                aria-pressed={sameColour(c, colour)}
                onClick={() => setColour(c)}
                className={`h-7 w-7 rounded-full border-2 ${sameColour(c, colour) ? 'border-text' : 'border-transparent'}`}
                style={{ backgroundColor: cssColour(c) }}
              />
            ))}
          </div>
        </fieldset>
        <label className="flex items-start gap-2 text-sm text-text">
          <input type="checkbox" className="mt-0.5" checked={sensitive} onChange={(e) => setSensitive(e.target.checked)} />
          <span>
            {t('calendars.sensitive')}
            <span className="block text-xs text-text-3">{t('calendars.sensitiveDetail')}</span>
          </span>
        </label>
        <OutcomeText outcome={outcome} />
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={busy || !trimmed || collides}>
            {busy ? t('common.working') : calendar ? t('common.save') : t('calendars.create')}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
