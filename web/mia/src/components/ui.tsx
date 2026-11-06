// Mia's small UI pieces over `@tenshu/web-tokens/ui`. ⚠ No `Dialog`: nothing here confirms, so the
// app keeps the `hosted_app` CSP (no inline `<style>` from a scroll lock).
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import type { Live } from '@tenshu/app-kit/live'
import { StreamNotice } from '@tenshu/app-kit/ui'
import { Button } from '@tenshu/web-tokens/ui'
import { bridgeErrorMessage, failureMessage, type Failure } from '../lib/errors'

export { Button }

// A failed call that is not a read: `kind`-keyed copy for a typed failure, its own sentence for a
// bridge rejection.
export function ErrorText({ failure, error }: { failure?: Failure | null; error?: unknown }) {
  const { t } = useTranslation()
  return (
    <p role="alert" className="text-sm text-danger" data-kind={failure?.kind}>
      {failure ? failureMessage(t, failure) : bridgeErrorMessage(t, error)}
    </p>
  )
}

// A failed READ — ⚠⚠ never an empty view. Always with a retry.
export function ReadFailure({ failure, error, onRetry }: { failure?: Failure | null; error?: unknown; onRetry: () => void }) {
  const { t } = useTranslation()
  const body = failure ? failureMessage(t, failure) : bridgeErrorMessage(t, error)
  return (
    <div role="alert" data-kind={failure?.kind} className="rounded-lg border border-line bg-surface-2 px-4 py-4">
      <p className="text-sm font-medium text-text">{t('read.failedTitle')}</p>
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

// A push stream that ended: what is on screen may be stale.
export function StreamEnded({ live }: { live: Live<unknown> }) {
  const { t } = useTranslation()
  return <StreamNotice live={live} copy={{ stopped: t('stream.stopped'), reconnect: t('stream.reconnect') }} />
}

export function Card({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <div className={`rounded-lg border border-line bg-surface-2 px-4 py-4 ${className}`}>{children}</div>
}
