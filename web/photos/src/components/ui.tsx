// Photos' small UI pieces over `@tenshu/web-tokens/ui`. The shared `PromptDialog` /
// `ConfirmDialog` carry inline English ("Cancel", "Working…"), so this app wraps the shared
// `Dialog` + `Button` with localised versions, as Contacts does.
import { useState, type FormEvent, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Button, Dialog, Field } from '@tenshu/web-tokens/ui'
import { bridgeErrorMessage, failureMessage, outcomeMessage, type Failure, type Outcome } from '../lib/errors'

export { Button, Dialog }

// A failed op or read. `kind`-keyed copy for a typed failure; a separate sentence for a bridge
// rejection. ⚠ `data-kind` carries the key so a test (and a bug report) can tell which.
export function ErrorText({ failure, error }: { failure?: Failure | null; error?: unknown }) {
  const { t } = useTranslation()
  if (!failure && (error === undefined || error === null)) return null
  return (
    <p role="alert" className="text-sm text-danger" data-kind={failure?.kind ?? 'bridge'}>
      {failure ? failureMessage(t, failure) : bridgeErrorMessage(t, error)}
    </p>
  )
}

export function OutcomeText({ outcome }: { outcome: Outcome }) {
  const { t } = useTranslation()
  if (!outcome) return null
  return (
    <p role="alert" className="text-sm text-danger" data-kind={'failure' in outcome ? outcome.failure.kind : 'bridge'}>
      {outcomeMessage(t, outcome)}
    </p>
  )
}

// A read that failed, in place of the view it would have filled — never an empty grid.
export function ErrorPanel({
  failure,
  error,
  onRetry,
}: {
  failure?: Failure | null
  error?: unknown
  onRetry?: () => void
}) {
  const { t } = useTranslation()
  return (
    <div className="flex flex-col items-start gap-3 rounded-lg border border-danger/30 bg-danger/5 px-4 py-3">
      <ErrorText failure={failure} error={error} />
      {onRetry && (
        <Button size="sm" variant="outline" onClick={onRetry}>
          {t('common.retry')}
        </Button>
      )}
    </div>
  )
}

export function Loading() {
  const { t } = useTranslation()
  return <p className="text-sm text-text-2">{t('common.loading')}</p>
}

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

export function ConfirmDialog({
  title,
  message,
  confirmLabel,
  onConfirm,
  onClose,
}: {
  title: string
  message: ReactNode
  confirmLabel: string
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
        <div className="text-sm text-text-2">{message}</div>
        <OutcomeText outcome={outcome} />
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button variant="danger" disabled={busy} onClick={confirm}>
            {busy ? t('common.working') : confirmLabel}
          </Button>
        </div>
      </div>
    </Dialog>
  )
}
