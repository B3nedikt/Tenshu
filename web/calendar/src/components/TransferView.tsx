// `.ics` import and export (calendar-app.md §Web scope W4, decided): a file input plus
// drag-and-drop in, a Blob download out. Import is idempotent by `UID` (re-importing replaces).
// ⚠ The partial-failure counts (`skipped`, `failed`, `warnings`) are shown, never swallowed.
import { useState, type DragEvent } from 'react'
import { useTranslation } from 'react-i18next'
import type { IcsImportResult } from '@tenshu/sdk/calendar'
import { useCalendars, useDefaultCalendar } from '../lib/live'
import { downloadText, safeFileName } from '../lib/download'
import { exportIcs, importIcs } from '../lib/proxy'
import { Button, ErrorText, Loading, ReadError, SelectField } from './ui'

type Report = { kind: 'imported'; result: Extract<IcsImportResult, { ok: true }>; file: string } | null

export function TransferView() {
  const { t } = useTranslation()
  const calendars = useCalendars()
  const def = useDefaultCalendar()
  const list = calendars.data?.ok ? calendars.data.calendars : null
  const defaultId = def.data?.ok ? def.data.calendarId : ''
  const [target, setTarget] = useState('')
  const [exportFrom, setExportFrom] = useState('')
  const [busy, setBusy] = useState(false)
  const [dragging, setDragging] = useState(false)
  const [report, setReport] = useState<Report>(null)
  const [failure, setFailure] = useState<{ kind: string } | null>(null)
  const [error, setError] = useState<unknown>(null)

  const into = target || (list?.some((c) => c.calendarId === defaultId) ? defaultId : (list?.[0]?.calendarId ?? ''))

  function reset() {
    setReport(null)
    setFailure(null)
    setError(null)
  }

  async function importFile(file: File) {
    if (!into) return
    reset()
    setBusy(true)
    try {
      const result = await importIcs(await file.text(), into, file.name)
      if (result.ok) setReport({ kind: 'imported', result, file: file.name })
      else setFailure(result.error)
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }

  async function exportNow() {
    reset()
    setBusy(true)
    try {
      const result = await exportIcs(exportFrom)
      if (!result.ok) {
        setFailure(result.error)
        return
      }
      const name = exportFrom ? (list?.find((c) => c.calendarId === exportFrom)?.name ?? '') : ''
      downloadText(result.ics, `${safeFileName(name, t('transfer.allFileName'))}.ics`, 'text/calendar')
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }

  function onDrop(event: DragEvent) {
    event.preventDefault()
    setDragging(false)
    const file = event.dataTransfer.files[0]
    if (file) void importFile(file)
  }

  if (!list) {
    return (
      <section className="space-y-4">
        <ReadError live={calendars} />
        {!calendars.error && !(calendars.data && !calendars.data.ok) && <Loading />}
      </section>
    )
  }
  if (list.length === 0) {
    return <p className="rounded-lg border border-line bg-surface-2 px-4 py-6 text-center text-sm text-text-2">{t('transfer.noCalendars')}</p>
  }

  return (
    <section className="space-y-8">
      <div className="space-y-3">
        <h2 className="text-lg font-semibold text-text">{t('transfer.importTitle')}</h2>
        <SelectField label={t('transfer.into')} value={into} onChange={setTarget}>
          {list.map((c) => (
            <option key={c.calendarId} value={c.calendarId}>
              {c.name || t('calendars.unnamed')}
            </option>
          ))}
        </SelectField>
        <div
          data-testid="drop-zone"
          onDragOver={(e) => {
            e.preventDefault()
            setDragging(true)
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={onDrop}
          className={`rounded-lg border-2 border-dashed px-4 py-8 text-center text-sm ${
            dragging ? 'border-primary bg-primary/5 text-primary' : 'border-line text-text-2'
          }`}
        >
          <p>{t('transfer.drop')}</p>
          <label className="mt-3 inline-block cursor-pointer rounded-lg border border-line px-3 py-1.5 text-sm font-medium text-text hover:bg-surface-3">
            {t('transfer.choose')}
            <input
              type="file"
              accept=".ics,text/calendar"
              className="sr-only"
              aria-label={t('transfer.chooseLabel')}
              disabled={busy}
              onChange={(e) => {
                const file = e.target.files?.[0]
                e.target.value = ''
                if (file) void importFile(file)
              }}
            />
          </label>
        </div>
        <p className="text-xs text-text-3">{t('transfer.importNote')}</p>
      </div>

      <div className="space-y-3">
        <h2 className="text-lg font-semibold text-text">{t('transfer.exportTitle')}</h2>
        <SelectField label={t('transfer.from')} value={exportFrom} onChange={setExportFrom}>
          <option value="">{t('transfer.allCalendars')}</option>
          {list.map((c) => (
            <option key={c.calendarId} value={c.calendarId}>
              {c.name || t('calendars.unnamed')}
            </option>
          ))}
        </SelectField>
        <Button onClick={exportNow} disabled={busy}>
          {t('transfer.export')}
        </Button>
      </div>

      <div aria-live="polite" className="space-y-2">
        {busy && <Loading />}
        <ErrorText failure={failure} error={failure ? undefined : error} />
        {report && <ImportReport report={report} />}
      </div>
    </section>
  )
}

function ImportReport({ report }: { report: NonNullable<Report> }) {
  const { t } = useTranslation()
  const r = report.result
  return (
    <div role="status" className="space-y-1 rounded-lg border border-line bg-surface-2 px-4 py-3 text-sm text-text">
      <p>{t('transfer.imported', { count: r.imported, file: report.file })}</p>
      {r.overrides > 0 && <p className="text-text-2">{t('transfer.overrides', { count: r.overrides })}</p>}
      {r.skipped > 0 && <p className="text-warning">{t('transfer.skipped', { count: r.skipped })}</p>}
      {r.failed > 0 && <p className="text-danger">{t('transfer.failed', { count: r.failed })}</p>}
      {r.warnings.length > 0 && <p className="text-warning">{t('transfer.warnings', { count: r.warnings.length })}</p>}
    </div>
  )
}
