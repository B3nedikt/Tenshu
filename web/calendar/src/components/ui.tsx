// Calendar's small UI pieces over `@tenshu/web-tokens/ui`. The shared `PromptDialog` /
// `ConfirmDialog` carry inline English, so this app wraps the shared `Dialog` + `Button` with
// localised versions (Contacts does the same).
import { useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Button, Dialog, Field } from '@tenshu/web-tokens/ui'
import type { Live } from '@tenshu/app-kit/live'
import { bridgeErrorMessage, failureMessage, type Failure } from '../lib/errors'
import type { Outcome } from '../lib/outcome'

export { Button, Dialog, Field }

// A failed op. `kind`-keyed copy for a typed failure; a separate sentence for a bridge rejection.
export function ErrorText({ failure, error }: { failure?: Failure | null; error?: unknown }) {
  const { t } = useTranslation()
  if (!failure && (error === undefined || error === null)) return null
  return (
    <p role="alert" className="text-sm text-danger" data-kind={failure?.kind}>
      {failure ? failureMessage(t, failure) : bridgeErrorMessage(t, error)}
    </p>
  )
}

export function OutcomeText({ outcome }: { outcome: Outcome }) {
  if (!outcome) return null
  return 'failure' in outcome ? <ErrorText failure={outcome.failure} /> : <ErrorText error={outcome.error} />
}

/**
 * A live read's failure, with a retry: a rejected seed (bridge) or a typed `{ ok: false }`.
 * ⚠⚠ Never an empty view. Returns null while the read is fine (or still pending).
 */
export function ReadError({ live }: { live: Live<{ ok: true } | { ok: false; error: Failure }> }) {
  const { t } = useTranslation()
  const failure = live.data && !live.data.ok ? live.data.error : null
  if (!live.error && !failure) return null
  return (
    <div className="flex items-center justify-between gap-3 rounded-lg border border-danger/30 bg-danger/5 px-4 py-3">
      {failure ? <ErrorText failure={failure} /> : <ErrorText error={live.error} />}
      <Button size="sm" variant="outline" onClick={live.retry}>
        {t('common.retry')}
      </Button>
    </div>
  )
}

export function Loading() {
  const { t } = useTranslation()
  return <p className="text-sm text-text-3">{t('common.loading')}</p>
}

export function ConfirmDialog({
  title,
  children,
  confirmLabel,
  danger = true,
  onConfirm,
  onClose,
}: {
  title: string
  children: ReactNode
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
        <div className="space-y-2 text-sm text-text-2">{children}</div>
        <OutcomeText outcome={outcome} />
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button variant={danger ? 'danger' : 'primary'} disabled={busy} onClick={confirm}>
            {busy ? t('common.working') : confirmLabel}
          </Button>
        </div>
      </div>
    </Dialog>
  )
}

export function ColourDot({ colour, size = 10 }: { colour: string; size?: number }) {
  return (
    <span
      aria-hidden
      className="inline-block shrink-0 rounded-full"
      style={{ width: size, height: size, backgroundColor: colour }}
    />
  )
}

// Native-looking select and textarea with the token classes `Field` uses.
export function SelectField({
  label,
  value,
  onChange,
  children,
  disabled,
}: {
  label: ReactNode
  value: string
  onChange: (value: string) => void
  children: ReactNode
  disabled?: boolean
}) {
  return (
    <label className="block text-sm">
      <span className="text-text-2">{label}</span>
      <select
        className="mt-1 w-full rounded-lg border border-line bg-surface px-3 py-1.5 text-sm text-text outline-none focus:border-line-strong disabled:opacity-60"
        value={value}
        disabled={disabled}
        onChange={(e) => onChange(e.target.value)}
      >
        {children}
      </select>
    </label>
  )
}

export function TextAreaField({
  label,
  value,
  onChange,
}: {
  label: ReactNode
  value: string
  onChange: (value: string) => void
}) {
  return (
    <label className="block text-sm">
      <span className="text-text-2">{label}</span>
      <textarea
        rows={3}
        className="mt-1 w-full rounded-lg border border-line bg-surface px-3 py-1.5 text-sm text-text outline-none focus:border-line-strong"
        value={value}
        onChange={(e) => onChange(e.target.value)}
      />
    </label>
  )
}
