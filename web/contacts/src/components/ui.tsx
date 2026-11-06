// Contacts' small UI pieces over `@tenshu/web-tokens/ui`. The shared `PromptDialog` /
// `ConfirmDialog` carry inline English ("Cancel", "Working…"), so this app wraps the shared
// `Dialog` + `Button` with its own localised versions rather than editing the shared layer (the
// app-kit is run B2's to extract).
import { useState, type FormEvent, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Button, Dialog, Field } from '@tenshu/web-tokens/ui'
import type { Contact } from '@tenshu/sdk/contact'
import { bridgeErrorMessage, failureMessage, type Failure } from '../lib/errors'
import { useContactName, type Outcome } from '../lib/outcome'

export { Button, Dialog, Field }

// A failed op. `kind`-keyed copy for a typed failure; a separate sentence for a bridge rejection.
export function ErrorText({ failure, error }: { failure?: Failure | null; error?: unknown }) {
  const { t } = useTranslation()
  if (!failure && error === undefined) return null
  if (!failure && error === null) return null
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

export function PromptDialog({
  title,
  label,
  supporting,
  initialValue = '',
  submitLabel,
  allowEmpty = false,
  onSubmit,
  onClose,
}: {
  title: string
  label: string
  supporting?: string
  initialValue?: string
  submitLabel: string
  allowEmpty?: boolean
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
    if (!trimmed && !allowEmpty) return
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
        {supporting && <p className="text-sm text-text-2">{supporting}</p>}
        <Field autoFocus label={label} value={value} onChange={(e) => setValue(e.target.value)} />
        <OutcomeText outcome={outcome} />
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={busy || (!allowEmpty && !value.trim())}>
            {busy ? t('common.working') : submitLabel}
          </Button>
        </div>
      </form>
    </Dialog>
  )
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

// ⚠⚠ The ONLY checkmark in this app, and it reads `contact.trust` — the SDK's Q2b answer — never a
// keyring scan of its own (run-A review: Drive's derived `verified` disagreed with it).
export function TrustBadge({ trust, detail = false }: { trust: string; detail?: boolean }) {
  const { t } = useTranslation()
  const tone =
    trust === 'verified'
      ? 'bg-success/10 text-success'
      : trust === 'needsReverification'
        ? 'bg-warning/10 text-warning'
        : trust === 'unverified'
          ? 'bg-surface-3 text-text-2'
          : 'bg-surface-3 text-text-3'
  const key = TRUST_KEYS[trust] ?? 'unverified'
  return (
    <span className="inline-flex flex-col gap-1">
      <span
        className={`inline-flex w-fit items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ${tone}`}
        data-trust={trust}
      >
        {trust === 'verified' && <span aria-hidden>✓</span>}
        {t(`trust.${key}`)}
      </span>
      {detail && <span className="text-xs text-text-3">{t(`trust.${key}Detail`)}</span>}
    </span>
  )
}

// An unknown future trust value renders as the cautious answer, never as verified.
const TRUST_KEYS: Record<string, string> = {
  verified: 'verified',
  unverified: 'unverified',
  needsReverification: 'needsReverification',
  notConnected: 'notConnected',
}

export function Avatar({ contact, size = 40 }: { contact: Contact; size?: number }) {
  const name = useContactName()(contact)
  if (contact.avatar === 'present' && contact.avatarDataUrl) {
    return (
      <img
        src={contact.avatarDataUrl}
        alt=""
        width={size}
        height={size}
        className="shrink-0 rounded-full object-cover"
        style={{ width: size, height: size }}
      />
    )
  }
  return (
    <span
      aria-hidden
      className="grid shrink-0 place-items-center rounded-full bg-primary/15 text-sm font-semibold text-primary"
      style={{ width: size, height: size }}
    >
      {name.trim().charAt(0).toUpperCase() || '?'}
    </span>
  )
}
