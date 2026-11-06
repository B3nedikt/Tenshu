// Timeline's small UI pieces over `@tenshu/web-tokens/ui`. The shared `PromptDialog` /
// `ConfirmDialog` carry inline English ("Cancel", "Working…"), so this app wraps the shared
// `Dialog` + `Button` with localised versions (Contacts' pattern) rather than editing the shared
// layer.
import { useState, type FormEvent, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Button, Dialog, Field } from '@tenshu/web-tokens/ui'
import { bridgeErrorMessage, failureMessage, SOURCE_SILENT, type Failure } from '../lib/errors'
import type { Outcome } from '../lib/outcome'

export { Button, Dialog }

// A failed write. `kind`-keyed copy for a typed failure; a separate sentence for a bridge rejection.
export function OutcomeText({ outcome }: { outcome: Outcome }) {
  const { t } = useTranslation()
  if (!outcome) return null
  const failure = 'failure' in outcome ? outcome.failure : null
  return (
    <p role="alert" className="text-sm text-danger" data-kind={failure?.kind}>
      {failure ? failureMessage(t, failure) : bridgeErrorMessage(t, 'error' in outcome ? outcome.error : null)}
    </p>
  )
}

// A failed READ — ⚠⚠ never an empty view. Typed failures render by `kind`; the bounded-wait
// `error_timeline_source_silent` reads as "still loading / couldn't load" (its data is late, not
// gone); a bridge rejection gets its own sentence. Always with a retry.
export function ReadFailure({
  failure,
  error,
  onRetry,
}: {
  failure?: Failure | null
  error?: unknown
  onRetry: () => void
}) {
  const { t } = useTranslation()
  const silent = failure?.kind === SOURCE_SILENT
  const title = silent ? t('read.silentTitle') : t('read.failedTitle')
  const body = failure ? failureMessage(t, failure) : bridgeErrorMessage(t, error)
  return (
    <div role="alert" data-kind={failure?.kind} className="rounded-lg border border-line bg-surface-2 px-4 py-4">
      <p className="text-sm font-medium text-text">{title}</p>
      <p className="mt-1 text-sm text-text-2">{body}</p>
      <Button className="mt-3" size="sm" variant="outline" onClick={onRetry}>
        {t('common.retry')}
      </Button>
    </div>
  )
}

export function Loading() {
  const { t } = useTranslation()
  return (
    <p role="status" className="text-sm text-text-2">
      {t('common.loading')}
    </p>
  )
}

export function BackButton({ onBack }: { onBack: () => void }) {
  const { t } = useTranslation()
  return (
    <Button size="sm" variant="ghost" className="mb-4" onClick={onBack}>
      ← {t('common.back')}
    </Button>
  )
}

export function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="mt-6">
      <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-text-2">{title}</h3>
      {children}
    </section>
  )
}

// A one-field dialog: `onSubmit` resolves `null` (closes) or an outcome to show (stays open).
export function PromptDialog({
  title,
  label,
  initialValue = '',
  submitLabel,
  onSubmit,
  onClose,
}: {
  title: string
  label: string
  initialValue?: string
  submitLabel: string
  onSubmit: (value: string) => Promise<Outcome>
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [value, setValue] = useState(initialValue)
  const [busy, setBusy] = useState(false)
  const [outcome, setOutcome] = useState<Outcome>(null)

  async function submit(event: FormEvent) {
    event.preventDefault()
    const trimmed = value.trim()
    if (!trimmed) return
    setBusy(true)
    setOutcome(null)
    const result = await onSubmit(trimmed)
    if (result) {
      setOutcome(result)
      setBusy(false)
    } else {
      onClose()
    }
  }

  return (
    <Dialog title={title} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <Field autoFocus label={label} value={value} onChange={(e) => setValue(e.target.value)} />
        <OutcomeText outcome={outcome} />
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={busy || !value.trim()}>
            {busy ? t('common.working') : submitLabel}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

// A yes/no confirmation: `onConfirm` resolves `null` (closes) or an outcome to show.
export function ConfirmDialog({
  title,
  body,
  confirmLabel,
  danger = false,
  onConfirm,
  onClose,
  children,
}: {
  title: string
  body: string
  confirmLabel: string
  danger?: boolean
  onConfirm: () => Promise<Outcome>
  onClose: () => void
  children?: ReactNode
}) {
  const { t } = useTranslation()
  const [busy, setBusy] = useState(false)
  const [outcome, setOutcome] = useState<Outcome>(null)

  async function confirm() {
    setBusy(true)
    setOutcome(null)
    const result = await onConfirm()
    if (result) {
      setOutcome(result)
      setBusy(false)
    } else {
      onClose()
    }
  }

  return (
    <Dialog title={title} onClose={onClose}>
      <div className="space-y-4">
        <p className="text-sm text-text-2">{body}</p>
        {children}
        <OutcomeText outcome={outcome} />
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button variant={danger ? 'danger' : 'primary'} disabled={busy} onClick={() => void confirm()}>
            {busy ? t('common.working') : confirmLabel}
          </Button>
        </div>
      </div>
    </Dialog>
  )
}
