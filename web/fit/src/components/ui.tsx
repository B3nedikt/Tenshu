// Fit's small UI pieces over `@tenshu/web-tokens/ui`. The shared `PromptDialog` / `ConfirmDialog`
// carry inline English ("Cancel", "Working…"), so this app wraps the shared `Dialog` + `Button` with
// localised versions (Timeline's pattern) rather than editing the shared layer.
import { useState, type FormEvent, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { StreamNotice } from '@tenshu/app-kit/ui'
import { Button, Dialog, Field } from '@tenshu/web-tokens/ui'
import { bridgeErrorMessage, failureMessage, type Failure } from '../lib/errors'
import type { Outcome } from '../lib/outcome'

export { Button, Dialog, Field }

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

// A failed READ — ⚠⚠ never an empty view and never a default number. Typed failures render by
// `kind`; a bridge rejection gets its own sentence. Always with a retry.
export function ReadFailure({
  failure,
  error,
  onRetry,
  retryLabel,
  retryDisabled = false,
}: {
  failure?: Failure | null
  error?: unknown
  onRetry: () => void
  retryLabel?: string
  retryDisabled?: boolean
}) {
  const { t } = useTranslation()
  const body = failure ? failureMessage(t, failure) : bridgeErrorMessage(t, error)
  return (
    <div role="alert" data-kind={failure?.kind} className="rounded-lg border border-line bg-surface-2 px-4 py-4">
      <p className="text-sm font-medium text-text">{t('read.failedTitle')}</p>
      <p className="mt-1 text-sm text-text-2">{body}</p>
      <Button className="mt-3" size="sm" variant="outline" onClick={onRetry} disabled={retryDisabled}>
        {retryLabel ?? t('common.retry')}
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

export function Section({ title, children, action }: { title: string; children: ReactNode; action?: ReactNode }) {
  return (
    <section className="mt-6">
      <div className="mb-2 flex items-center justify-between gap-2">
        <h3 className="text-xs font-semibold uppercase tracking-wide text-text-2">{title}</h3>
        {action}
      </div>
      {children}
    </section>
  )
}

export function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-line bg-surface-2 px-3 py-2">
      <dt className="text-xs text-text-2">{label}</dt>
      <dd className="mt-0.5 text-base font-semibold text-text">{value}</dd>
    </div>
  )
}

// A push stream that ended: what is on screen may be stale.
export function Stopped({ live }: { live: { readonly streamError: Error | null; readonly retry: () => void } }) {
  const { t } = useTranslation()
  return <StreamNotice live={live} copy={{ stopped: t('stream.stopped'), reconnect: t('stream.reconnect') }} />
}

// A yes/no confirmation: `onConfirm` resolves `null` (closes) or an outcome to show.
export function ConfirmDialog({
  title,
  body,
  confirmLabel,
  danger = false,
  onConfirm,
  onClose,
}: {
  title: string
  body: string
  confirmLabel: string
  danger?: boolean
  onConfirm: () => Promise<Outcome>
  onClose: () => void
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

// A dialog around one form: `onSubmit` resolves `null` (closes) or an outcome to show (stays open).
// `invalid` is the form's own bound check, shown before anything is sent.
export function FormDialog({
  title,
  submitLabel,
  invalid,
  unchanged = false,
  onSubmit,
  onClose,
  children,
}: {
  title: string
  submitLabel: string
  invalid: string | null
  // Nothing was edited: Save is off, so an untouched value is never written back (and, through a
  // lossy unit round trip, changed).
  unchanged?: boolean
  onSubmit: () => Promise<Outcome>
  onClose: () => void
  children: ReactNode
}) {
  const { t } = useTranslation()
  const [busy, setBusy] = useState(false)
  const [outcome, setOutcome] = useState<Outcome>(null)

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (invalid || unchanged) return
    setBusy(true)
    setOutcome(null)
    const result = await onSubmit()
    if (result) {
      setOutcome(result)
      setBusy(false)
    } else {
      onClose()
    }
  }

  return (
    <Dialog title={title} onClose={onClose}>
      <form onSubmit={(e) => void submit(e)} className="space-y-4" noValidate>
        {children}
        {invalid && (
          <p className="text-sm text-danger" data-testid="bound">
            {invalid}
          </p>
        )}
        <OutcomeText outcome={outcome} />
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={busy || invalid !== null || unchanged}>
            {busy ? t('common.working') : submitLabel}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
