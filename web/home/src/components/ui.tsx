// Home's small UI pieces over `@tenshu/web-tokens/ui`. ⚠ No Dialog: nothing in a read-only app needs
// one, and the shared Dialog's scroll lock would need `style-src-elem 'unsafe-inline'` (the
// `hosted_map_app` policy) — Home stays on the plain `hosted_app` CSP.
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@tenshu/web-tokens/ui'
import { bridgeErrorMessage, failureMessage, streamErrorKind, streamErrorMessage, type Failure } from '../lib/errors'

// A failed READ — ⚠⚠ never an empty view. Typed failures render by `kind`; a bridge rejection gets its
// own sentence. Always with a retry.
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

// A push stream that ended: what is on screen may be stale. ⚠ Says WHICH failure by its kind (the
// gateway's `"kind: message"`), not only that it stopped.
export function Stopped({ live }: { live: { readonly streamError: Error | null; readonly retry: () => void } }) {
  const { t } = useTranslation()
  if (!live.streamError) return null
  return (
    <div
      role="alert"
      data-kind={streamErrorKind(live.streamError) ?? undefined}
      className="mb-4 flex items-center justify-between gap-3 rounded-lg border border-warning/30 bg-warning/10 px-4 py-2 text-sm text-warning"
    >
      <span>
        {t('stream.stopped')} {streamErrorMessage(t, live.streamError)}
      </span>
      <Button size="sm" variant="outline" onClick={live.retry}>
        {t('stream.reconnect')}
      </Button>
    </div>
  )
}

// The empty state each tab shares (iOS's `HomeEmptyState`): a title and a sentence that says why.
export function EmptyState({ title, body }: { title: string; body: string }) {
  return (
    <div className="rounded-lg border border-line px-4 py-8 text-center">
      <p className="text-base font-medium text-text">{title}</p>
      <p className="mx-auto mt-1 max-w-md text-sm text-text-2">{body}</p>
    </div>
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
