// The event editor (CA-3), as a dialog. Create, edit, delete — and its real job, the this-vs-all
// question, asked ONLY when it is real: an existing recurring occurrence, never a create or a
// one-off. Both answers are destructive in opposite directions: a series edit moves every
// occurrence, an occurrence edit leaves the change unapplied everywhere else.
//
// ⚠⚠ An occurrence whose zone this browser cannot resolve (`zoneUnresolved`) is REFUSED, not
// edited: its times are a reader's-zone placement, and saving them would rewrite the event in the
// wrong zone (run A refuses the write as `error_calendar_unknown_zone` anyway). It can still be
// deleted, which writes no time.
//
// ⚠ Times are edited in the EVENT's own zone (civil `startLocal`/`endLocal`), and the zone is kept
// on save. A create is floating (`timeZoneId: ''`), as the native editor creates it.
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import type { CalendarSummary, EventDraft, Occurrence } from '@tenshu/sdk/calendar'
import { addDays, civilDateTime, formatCivil, formatTime, parseTime, splitLocal, type CivilDate } from '../lib/dates'
import { attempt, type Outcome } from '../lib/outcome'
import {
  defaultReminderMinutes,
  deleteEvent,
  deleteOccurrence,
  remindersFor,
  saveEvent,
  saveOccurrence,
  setReminders,
} from '../lib/proxy'
import { classifyRule, OFFERABLE, seriesAnchor, toRrule, type RecurrencePreset } from '../lib/recurrence'
import { offeredReminders, reminderLabel } from '../lib/reminders'
import { Button, Dialog, Field, OutcomeText, SelectField, TextAreaField } from './ui'

export type EditorTarget =
  | { readonly mode: 'create'; readonly date: CivilDate; readonly minute?: number }
  | { readonly mode: 'edit'; readonly occurrence: Occurrence }

const DEFAULT_DURATION = 60

interface Form {
  title: string
  calendarId: string
  startDate: CivilDate
  startTime: string
  endDate: CivilDate
  endTime: string
  allDay: boolean
  location: string
  notes: string
  recurrence: RecurrencePreset
}

function initialForm(target: EditorTarget, calendarId: string): Form {
  if (target.mode === 'create') {
    const start = target.minute ?? 9 * 60
    const end = Math.min(start + DEFAULT_DURATION, 23 * 60 + 59)
    return {
      title: '',
      calendarId,
      startDate: target.date,
      startTime: formatTime(start),
      endDate: target.date,
      endTime: formatTime(end),
      allDay: false,
      location: '',
      notes: '',
      recurrence: 'never',
    }
  }
  const o = target.occurrence
  const start = splitLocal(o.startLocal)
  const end = splitLocal(o.endLocal || o.startLocal)
  // An all-day end is EXCLUSIVE (the next day's 00:00); the form shows the last day it covers.
  const endDate = o.allDay ? (end.date > start.date ? addDays(end.date, -1) : start.date) : end.date
  return {
    title: o.title,
    calendarId: o.calendarId,
    startDate: start.date,
    startTime: formatTime(start.minute),
    endDate,
    endTime: formatTime(end.minute),
    allDay: o.allDay,
    location: o.location,
    notes: o.notes,
    // ⚠⚠ Against the SERIES' anchor, not this occurrence's date.
    recurrence: classifyRule(o.recurrenceRule, seriesAnchor(o)),
  }
}

type Step = 'form' | 'saveScope' | 'deleteScope' | 'confirmDelete'

export function EventEditor({
  target,
  calendars,
  defaultCalendarId,
  onClose,
}: {
  target: EditorTarget
  calendars: readonly CalendarSummary[]
  defaultCalendarId: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const original = target.mode === 'edit' ? target.occurrence : null

  if (original?.zoneUnresolved) {
    return <UnresolvedZone occurrence={original} onClose={onClose} />
  }

  const fallbackCalendar = calendars.some((c) => c.calendarId === defaultCalendarId)
    ? defaultCalendarId
    : (calendars[0]?.calendarId ?? '')
  return (
    <Dialog title={original ? t('editor.editTitle') : t('editor.createTitle')} onClose={onClose}>
      <EditorForm target={target} original={original} calendars={calendars} fallbackCalendar={fallbackCalendar} onClose={onClose} />
    </Dialog>
  )
}

function EditorForm({
  target,
  original,
  calendars,
  fallbackCalendar,
  onClose,
}: {
  target: EditorTarget
  original: Occurrence | null
  calendars: readonly CalendarSummary[]
  fallbackCalendar: string
  onClose: () => void
}) {
  const { t, i18n } = useTranslation()
  const [form, setForm] = useState<Form>(() => initialForm(target, fallbackCalendar))
  // `null` = not known: still loading, or the read failed. ⚠⚠ Never defaulted to `[]` on a failed
  // read — a save would then delete every reminder the event had.
  const [reminders, setRemindersState] = useState<number[] | null>(null)
  const [remindersFailed, setRemindersFailed] = useState(false)
  const [remindersTouched, setRemindersTouched] = useState(false)
  // Read when a default ARRIVES, not when it was asked for: a tick made while the read was in
  // flight is the user's answer, and must not be replaced by it.
  const touched = useRef(false)
  const [step, setStep] = useState<Step>('form')
  const [busy, setBusy] = useState(false)
  const [outcome, setOutcome] = useState<Outcome>(null)
  const [savedButRemindersFailed, setSavedButRemindersFailed] = useState(false)

  const set = <K extends keyof Form>(key: K, value: Form[K]) => setForm((f) => ({ ...f, [key]: value }))

  // An existing event: its stored offsets. A create: the default for its kind, until the user picks.
  const eventId = original?.eventId ?? ''
  const allDay = form.allDay
  // ⚠ Two effects, not one: an existing event's stored offsets are read ONCE (an all-day toggle must
  // not re-read them over the user's edits), while a create's default follows the all-day kind.
  useEffect(() => {
    if (!eventId) return
    let live = true
    remindersFor(eventId).then(
      (r) => {
        if (!live || touched.current) return
        if (r.ok) setRemindersState([...r.minutes])
        else setRemindersFailed(true)
      },
      () => {
        if (live && !touched.current) setRemindersFailed(true)
      },
    )
    return () => {
      live = false
    }
  }, [eventId])
  useEffect(() => {
    if (eventId || touched.current) return
    let live = true
    defaultReminderMinutes(allDay).then(
      (r) => {
        if (!live || touched.current) return
        if (r.ok) setRemindersState(r.minutes === null ? [] : [r.minutes])
        else setRemindersFailed(true)
      },
      () => {
        if (live && !touched.current) setRemindersFailed(true)
      },
    )
    return () => {
      live = false
    }
  }, [eventId, allDay])

  const startMinute = parseTime(form.startTime)
  const endMinute = parseTime(form.endTime)
  const endBeforeStart = form.allDay
    ? form.endDate < form.startDate
    : startMinute === null ||
      endMinute === null ||
      civilDateTime(form.endDate, endMinute) <= civilDateTime(form.startDate, startMinute)
  const canSave = !busy && form.title.trim() !== '' && form.calendarId !== '' && !endBeforeStart
  // ⚠⚠ An occurrence override carries only times, title, location and notes (`saveOccurrence`):
  // a changed rule, calendar or all-day flag would be silently dropped, so those are series-only.
  const seriesOnlyChange =
    original !== null &&
    (form.recurrence !== classifyRule(original.recurrenceRule, seriesAnchor(original)) ||
      form.calendarId !== original.calendarId ||
      form.allDay !== original.allDay)

  function draft(): EventDraft {
    const startLocal = form.allDay ? civilDateTime(form.startDate, 0) : civilDateTime(form.startDate, startMinute ?? 0)
    // ⚠ An all-day event is a date range with an EXCLUSIVE end: 00:00 the day after its last day.
    const endLocal = form.allDay ? civilDateTime(addDays(form.endDate, 1), 0) : civilDateTime(form.endDate, endMinute ?? 0)
    const rule = toRrule(form.recurrence, form.startDate) ?? original?.recurrenceRule ?? ''
    return {
      eventId,
      calendarId: form.calendarId,
      title: form.title.trim(),
      startLocal,
      endLocal,
      timeZoneId: original?.zoneId ?? '',
      allDay: form.allDay,
      location: form.location.trim(),
      notes: form.notes.trim(),
      recurrenceRule: rule,
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!canSave) return
    if (original?.isRecurring) setStep('saveScope')
    else void commit('all')
  }

  async function commit(scope: 'one' | 'all') {
    setBusy(true)
    setOutcome(null)
    const d = draft()
    let result: Outcome
    if (scope === 'one' && original) {
      // ⚠ An occurrence edit writes no reminders: they belong to the series, and this screen
      // promised to change one occurrence.
      result = await attempt(() => saveOccurrence({ ...d, recurrenceRule: original.recurrenceRule }, original.recurrenceId))
    } else {
      let savedId = ''
      result = await attempt(async () => {
        const r = await saveEvent(d)
        if (r.ok) savedId = r.eventId
        return r
      })
      // ⚠⚠ After the save, with the id it returned: a create has no id before it. Skipped when the
      // stored offsets were never read — writing a guess would replace them.
      if (!result && reminders !== null) {
        const reminderResult = await attempt(() => setReminders(savedId, reminders))
        if (reminderResult) {
          setOutcome(reminderResult)
          setSavedButRemindersFailed(true)
          setBusy(false)
          setStep('form')
          return
        }
      }
    }
    if (result) {
      setOutcome(result)
      setBusy(false)
      setStep('form')
    } else {
      onClose()
    }
  }

  async function remove(scope: 'one' | 'all') {
    if (!original) return
    setBusy(true)
    setOutcome(null)
    const result = await attempt(() =>
      scope === 'one' ? deleteOccurrence(original.eventId, original.recurrenceId) : deleteEvent(original.eventId),
    )
    if (result) {
      setOutcome(result)
      setBusy(false)
      setStep('form')
    } else {
      onClose()
    }
  }

  if (savedButRemindersFailed) {
    return (
      <div className="space-y-4">
        <p className="text-sm text-text">{t('editor.savedRemindersFailed')}</p>
        <OutcomeText outcome={outcome} />
        <div className="flex justify-end">
          <Button onClick={onClose}>{t('common.close')}</Button>
        </div>
      </div>
    )
  }

  if (step === 'saveScope' && original) {
    return (
      <div className="space-y-4">
        <p className="text-sm text-text">{t('editor.saveScopeQuestion')}</p>
        <div className="flex flex-col gap-2">
          <Button variant="outline" disabled={busy || seriesOnlyChange} onClick={() => void commit('one')}>
            {t('editor.thisEvent')}
          </Button>
          {seriesOnlyChange && <p className="text-xs text-text-3">{t('editor.thisEventSeriesOnly')}</p>}
          <Button variant="outline" disabled={busy} onClick={() => void commit('all')}>
            {t('editor.allEvents')}
          </Button>
          <p className="text-xs text-text-3">{t('editor.allEventsNote', { date: formatCivil(i18n.language, form.startDate) })}</p>
        </div>
        <OutcomeText outcome={outcome} />
        <div className="flex justify-end">
          <Button variant="ghost" onClick={() => setStep('form')}>
            {t('common.cancel')}
          </Button>
        </div>
      </div>
    )
  }

  if ((step === 'deleteScope' || step === 'confirmDelete') && original) {
    return (
      <DeleteChoice
        recurring={step === 'deleteScope'}
        busy={busy}
        outcome={outcome}
        onDelete={(scope) => void remove(scope)}
        onCancel={() => setStep('form')}
      />
    )
  }

  const offered = offeredReminders(form.allDay, reminders ?? [])
  const zoneNote = original && original.zoneId ? t('editor.zoneNote', { zone: original.zoneId }) : null

  return (
    <form onSubmit={submit} className="space-y-4">
      <Field autoFocus label={t('editor.title')} value={form.title} onChange={(e) => set('title', e.target.value)} />
      <SelectField label={t('editor.calendar')} value={form.calendarId} onChange={(v) => set('calendarId', v)}>
        {calendars.map((c) => (
          <option key={c.calendarId} value={c.calendarId}>
            {c.name || t('calendars.unnamed')}
          </option>
        ))}
      </SelectField>
      <label className="flex items-center gap-2 text-sm text-text">
        <input
          type="checkbox"
          checked={form.allDay}
          onChange={(e) => {
            set('allDay', e.target.checked)
            if (!eventId && !remindersTouched) {
              setRemindersState(null)
              setRemindersFailed(false)
            }
          }}
        />
        {t('editor.allDay')}
      </label>
      <div className="grid grid-cols-2 gap-3">
        <Field type="date" label={t('editor.startDate')} value={form.startDate} onChange={(e) => {
          const v = e.target.value
          if (!v) return
          setForm((f) => ({ ...f, startDate: v, endDate: f.endDate < v ? v : f.endDate }))
        }} />
        {!form.allDay && (
          <Field type="time" label={t('editor.startTime')} value={form.startTime} onChange={(e) => set('startTime', e.target.value)} />
        )}
        <Field type="date" label={t('editor.endDate')} value={form.endDate} onChange={(e) => e.target.value && set('endDate', e.target.value)} />
        {!form.allDay && (
          <Field type="time" label={t('editor.endTime')} value={form.endTime} onChange={(e) => set('endTime', e.target.value)} />
        )}
      </div>
      {zoneNote && <p className="text-xs text-text-3">{zoneNote}</p>}
      {endBeforeStart && <p className="text-sm text-danger">{t('editor.endBeforeStart')}</p>}

      {form.recurrence === 'custom' ? (
        <div className="text-sm">
          <span className="text-text-2">{t('editor.repeat')}</span>
          <p className="mt-1 text-text">{t('recurrence.custom')}</p>
          <p className="text-xs text-text-3">{t('editor.customRule')}</p>
        </div>
      ) : (
        <SelectField label={t('editor.repeat')} value={form.recurrence} onChange={(v) => set('recurrence', v as RecurrencePreset)}>
          {OFFERABLE.map((p) => (
            <option key={p} value={p}>
              {t(`recurrence.${p}`)}
            </option>
          ))}
        </SelectField>
      )}

      <Field label={t('editor.location')} value={form.location} onChange={(e) => set('location', e.target.value)} />
      <TextAreaField label={t('editor.notes')} value={form.notes} onChange={(v) => set('notes', v)} />

      <fieldset className="space-y-1">
        <legend className="text-sm text-text-2">{t('editor.reminders')}</legend>
        <p className="text-xs text-text-3">{t('editor.remindersRing')}</p>
        {remindersFailed && reminders === null ? (
          <p role="alert" className="text-sm text-warning">
            {eventId ? t('editor.remindersUnreadable') : t('editor.remindersDefaultUnreadable')}
          </p>
        ) : reminders === null && eventId ? (
          <p className="text-xs text-text-3">{t('common.loading')}</p>
        ) : (
          <div className="flex flex-wrap gap-x-4 gap-y-1">
            {offered.map((m) => (
              <label key={m} className="flex items-center gap-1.5 text-sm text-text">
                <input
                  type="checkbox"
                  checked={(reminders ?? []).includes(m)}
                  onChange={(e) => {
                    touched.current = true
                    setRemindersTouched(true)
                    const now = new Set(reminders ?? [])
                    if (e.target.checked) now.add(m)
                    else now.delete(m)
                    setRemindersState([...now].sort((a, b) => a - b))
                  }}
                />
                {reminderLabel(t, m, form.allDay)}
              </label>
            ))}
          </div>
        )}
      </fieldset>

      <OutcomeText outcome={outcome} />
      <div className="flex items-center justify-between gap-2">
        <div>
          {original && (
            <Button
              variant="danger-ghost"
              disabled={busy}
              onClick={() => setStep(original.isRecurring ? 'deleteScope' : 'confirmDelete')}
            >
              {t('editor.delete')}
            </Button>
          )}
        </div>
        <div className="flex gap-2">
          <Button variant="ghost" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={!canSave}>
            {busy ? t('common.working') : t('common.save')}
          </Button>
        </div>
      </div>
    </form>
  )
}

function DeleteChoice({
  recurring,
  busy,
  outcome,
  onDelete,
  onCancel,
}: {
  recurring: boolean
  busy: boolean
  outcome: Outcome
  onDelete: (scope: 'one' | 'all') => void
  onCancel: () => void
}) {
  const { t } = useTranslation()
  return (
    <div className="space-y-4">
      <p className="text-sm text-text">{recurring ? t('editor.deleteScopeQuestion') : t('editor.deleteQuestion')}</p>
      <div className="flex flex-col gap-2">
        {recurring ? (
          <>
            <Button variant="danger-ghost" disabled={busy} onClick={() => onDelete('one')}>
              {t('editor.deleteThis')}
            </Button>
            <Button variant="danger" disabled={busy} onClick={() => onDelete('all')}>
              {t('editor.deleteAll')}
            </Button>
          </>
        ) : (
          <Button variant="danger" disabled={busy} onClick={() => onDelete('all')}>
            {t('editor.delete')}
          </Button>
        )}
      </div>
      <OutcomeText outcome={outcome} />
      <div className="flex justify-end">
        <Button variant="ghost" onClick={onCancel}>
          {t('common.cancel')}
        </Button>
      </div>
    </div>
  )
}

// ⚠⚠ The refusal: what the event says, why it cannot be edited here, and where it can be.
function UnresolvedZone({ occurrence, onClose }: { occurrence: Occurrence; onClose: () => void }) {
  const { t, i18n } = useTranslation()
  const [step, setStep] = useState<'view' | 'delete'>('view')
  const [busy, setBusy] = useState(false)
  const [outcome, setOutcome] = useState<Outcome>(null)
  const fmt = new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' })

  async function remove(scope: 'one' | 'all') {
    setBusy(true)
    setOutcome(null)
    const result = await attempt(() =>
      scope === 'one' ? deleteOccurrence(occurrence.eventId, occurrence.recurrenceId) : deleteEvent(occurrence.eventId),
    )
    if (result) {
      setOutcome(result)
      setBusy(false)
    } else {
      onClose()
    }
  }

  return (
    <Dialog title={occurrence.title || t('event.untitled')} onClose={onClose}>
      {step === 'delete' ? (
        <DeleteChoice
          recurring={occurrence.isRecurring}
          busy={busy}
          outcome={outcome}
          onDelete={(scope) => void remove(scope)}
          onCancel={() => setStep('view')}
        />
      ) : (
        <div className="space-y-4">
          <div role="alert" className="rounded-lg border border-warning/30 bg-warning/10 px-4 py-3 text-sm text-warning">
            <p className="font-medium">{t('zone.refusalTitle')}</p>
            <p className="mt-1">{t('zone.refusalBody', { zone: occurrence.zoneId })}</p>
          </div>
          <p className="text-sm text-text-2">
            {occurrence.allDay
              ? t('zone.shownAllDay')
              : t('zone.shownAt', { start: fmt.format(new Date(occurrence.startEpochMs)), end: fmt.format(new Date(occurrence.endEpochMs)) })}
          </p>
          <div className="flex justify-between gap-2">
            <Button variant="danger-ghost" onClick={() => setStep('delete')}>
              {t('editor.delete')}
            </Button>
            <Button onClick={onClose}>{t('common.close')}</Button>
          </div>
        </div>
      )}
    </Dialog>
  )
}
