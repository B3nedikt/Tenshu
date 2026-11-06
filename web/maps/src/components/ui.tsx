// Maps' small UI pieces over `@tenshu/web-tokens/ui`. The shared `PromptDialog` /
// `ConfirmDialog` carry inline English ("Cancel", "Working…"), so this app wraps the shared
// `Dialog` + `Button` with localised versions (Contacts' pattern) rather than editing the shared
// layer.
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Button, Dialog, Field } from '@tenshu/web-tokens/ui'
import { bridgeErrorMessage, failureMessage, SOURCE_SILENT, type Failure } from '../lib/errors'
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
