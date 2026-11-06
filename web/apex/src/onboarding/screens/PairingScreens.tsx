// §4a — pairing flow screens for the existing-realm-device path. Per
// the locked-in protocol direction (web is requester), the apex shows
// a scan-or-paste affordance from the start; the host device hosts the
// QR. UX §4a.1's "browser shows QR" layout is rewritten here.
//
// Submit → Pairing.submit({pairingUri, deviceName}) → PairingSession.
// We then `observe` until terminal status; the Awaiting screen renders
// the SAS that the user must verify against their host device's screen.
import { useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { Trans, useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import type {
  OfferPairingStatus,
  PairingSession,
  PairingStatus,
} from '@tenshu/sdk/onboarding'
import { useOnboardingSdk } from '../../lib/useOnboardingSdk'
import { validatePairingUri } from '../../lib/pairingUri'
import {
  Hint,
  LinkButton,
  OnboardingActions,
  OnboardingCard,
  OnboardingProse,
  OnboardingTitle,
  PrimaryButton,
  SecondaryButton,
} from '../OnboardingShell'
import { QrDisplay } from '../widgets/QrDisplay'
import { QrScanner } from '../widgets/QrScanner'
import { SasDisplay } from '../widgets/SasDisplay'

// Non-`awaiting` offer statuses that end the offerer's showing state and
// bubble up to the orchestrator (`OnboardingFlow`).
export type OfferTerminalStatus = Exclude<OfferPairingStatus, { kind: 'showing' }>

export type PairingTab = 'scan' | 'show'

export function PairingSubmitScreen({
  deviceName,
  onSubmitted,
  onCancel,
}: {
  readonly deviceName: string
  readonly onSubmitted: (session: PairingSession) => void
  readonly onCancel: () => void
}) {
  const { t } = useTranslation('onboarding')
  const sdk = useOnboardingSdk()
  const [scanning, setScanning] = useState(false)
  const [pairingUri, setPairingUri] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const validation = useMemo(() => validatePairingUri(pairingUri), [pairingUri])
  const validationMessage =
    pairingUri.trim() === '' || validation.ok
      ? null
      : validation.reason === 'wrongScheme'
        ? t('pairing.submit.validation.wrongScheme')
        : validation.reason === 'unknownRole'
          ? t('pairing.submit.validation.unknownRole')
          : validation.reason === 'offerRole'
            ? t('pairing.submit.validation.offerRole')
            : t('pairing.submit.validation.missingParams')

  async function onSubmit() {
    setError(null)
    setSubmitting(true)
    const result = await sdk.Pairing.submit({ pairingUri: pairingUri.trim(), deviceName })
    setSubmitting(false)
    if (!result.ok) {
      setError(submitErrorMessage(t, result.error))
      return
    }
    onSubmitted(result.value)
  }

  return (
    <OnboardingCard>
      <OnboardingTitle>{t('pairing.submit.title')}</OnboardingTitle>
      <OnboardingProse>
        <p>{t('pairing.submit.body')}</p>
        <ol className="ml-5 list-decimal space-y-1 text-sm">
          <li>{t('pairing.submit.step1')}</li>
          <li>
            <Trans
              ns="onboarding"
              i18nKey="pairing.submit.step2"
              components={{ em: <em /> }}
            />
          </li>
          <li>{t('pairing.submit.step3')}</li>
        </ol>
      </OnboardingProse>

      <div className="mt-6 space-y-4">
        <div className="flex items-center gap-3">
          <SecondaryButton onClick={() => setScanning(s => !s)}>
            {scanning ? t('pairing.submit.stopCamera') : t('pairing.submit.useCamera')}
          </SecondaryButton>
          <Hint>{t('pairing.submit.cameraHint')}</Hint>
        </div>
        <QrScanner
          active={scanning}
          onResult={text => {
            setScanning(false)
            setPairingUri(text)
          }}
        />
        <label className="block">
          <span className="block text-xs font-medium text-text-2">
            {t('pairing.submit.pasteLabel')}
          </span>
          <textarea
            rows={3}
            value={pairingUri}
            onChange={e => setPairingUri(e.target.value)}
            placeholder={t('pairing.submit.pastePlaceholder')}
            className="mt-1 w-full rounded-md border border-line bg-surface px-3 py-2 font-mono text-sm shadow-sm"
          />
        </label>
        {validationMessage !== null && (
          <p className="text-sm text-warning">{validationMessage}</p>
        )}
        {error !== null && (
          <p
            role="alert"
            className="rounded-md border border-danger/30 bg-danger/10 px-3 py-2 text-sm text-danger"
          >
            {error}
          </p>
        )}
      </div>

      <OnboardingActions>
        <PrimaryButton
          onClick={() => void onSubmit()}
          disabled={!validation.ok || submitting}
        >
          {submitting ? t('pairing.submit.submitting') : t('pairing.submit.continue')}
        </PrimaryButton>
        <LinkButton onClick={onCancel}>{t('pairing.submit.cancel')}</LinkButton>
      </OnboardingActions>
    </OnboardingCard>
  )
}

function submitErrorMessage(t: TFunction, error: { kind: string; message?: string }): string {
  switch (error.kind) {
    case 'invalidUri':
      return t('pairing.submit.error.invalidUri')
    case 'network':
      return t('pairing.submit.error.network')
    default:
      return error.message ?? t('pairing.submit.error.fallback')
  }
}

export function PairingAwaitScreen({
  session,
  onTerminal,
}: {
  readonly session: PairingSession
  readonly onTerminal: (status: Exclude<PairingStatus, { kind: 'awaiting' }>) => void
}) {
  const { t } = useTranslation('onboarding')
  const [status, setStatus] = useState<PairingStatus>({ kind: 'awaiting', sas: '— —' })
  useEffect(() => {
    return session.observe(next => {
      setStatus(next)
      if (next.kind !== 'awaiting') onTerminal(next)
    })
  }, [session, onTerminal])

  if (status.kind !== 'awaiting') {
    // Render a holding state while the orchestrator transitions.
    return (
      <OnboardingCard>
        <OnboardingTitle>{t('pairing.await.connecting')}</OnboardingTitle>
      </OnboardingCard>
    )
  }

  return (
    <OnboardingCard>
      <OnboardingTitle>{t('pairing.await.title')}</OnboardingTitle>
      <OnboardingProse>
        <p>{t('pairing.await.body1')}</p>
        <p className="font-medium text-text">
          {t('pairing.await.body2')}
        </p>
      </OnboardingProse>
      <div className="mt-6 rounded-md border border-line bg-surface-2 px-6 py-6 text-center">
        <SasDisplay sas={status.sas} />
      </div>
      <Hint>{t('pairing.await.mismatchHint')}</Hint>
      <Hint>{t('pairing.await.versionHint')}</Hint>
      <OnboardingActions>
        <SecondaryButton
          onClick={() => {
            void session.cancel()
            onTerminal({ kind: 'denied' })
          }}
        >
          {t('pairing.await.cancel')}
        </SecondaryButton>
      </OnboardingActions>
    </OnboardingCard>
  )
}

export function PairingTerminalScreen({
  status,
  onContinue,
  onRetry,
  onCancel,
}: {
  readonly status: Exclude<PairingStatus, { kind: 'awaiting' }>
  readonly onContinue: () => void
  readonly onRetry: () => void
  readonly onCancel: () => void
}) {
  const { t } = useTranslation('onboarding')
  if (status.kind === 'paired') {
    return (
      <OnboardingCard>
        <OnboardingTitle>{t('pairing.paired.title')}</OnboardingTitle>
        <OnboardingProse>
          <p>{t('pairing.paired.body')}</p>
        </OnboardingProse>
        <OnboardingActions>
          <PrimaryButton onClick={onContinue}>{t('pairing.paired.continue')}</PrimaryButton>
        </OnboardingActions>
      </OnboardingCard>
    )
  }
  if (status.kind === 'denied') {
    return (
      <OnboardingCard>
        <OnboardingTitle>{t('pairing.denied.title')}</OnboardingTitle>
        <OnboardingProse>
          <p>{t('pairing.denied.body')}</p>
        </OnboardingProse>
        <OnboardingActions>
          <PrimaryButton onClick={onRetry}>{t('pairing.denied.retry')}</PrimaryButton>
          <LinkButton onClick={onCancel}>{t('pairing.denied.cancel')}</LinkButton>
        </OnboardingActions>
      </OnboardingCard>
    )
  }
  if (status.kind === 'expired') {
    return (
      <OnboardingCard>
        <OnboardingTitle>{t('pairing.expired.title')}</OnboardingTitle>
        <OnboardingProse>
          <p>{t('pairing.expired.body')}</p>
        </OnboardingProse>
        <OnboardingActions>
          <PrimaryButton onClick={onRetry}>{t('pairing.expired.retry')}</PrimaryButton>
          <LinkButton onClick={onCancel}>{t('pairing.expired.cancel')}</LinkButton>
        </OnboardingActions>
      </OnboardingCard>
    )
  }
  return (
    <OnboardingCard>
      <OnboardingTitle>{t('pairing.failed.title')}</OnboardingTitle>
      <OnboardingProse>
        {/* SDK-supplied error message — already English; future
            error-code rollout converts this to a key lookup. */}
        <p>{status.message}</p>
      </OnboardingProse>
      <OnboardingActions>
        <PrimaryButton onClick={onRetry}>{t('pairing.failed.retry')}</PrimaryButton>
        <LinkButton onClick={onCancel}>{t('pairing.failed.cancel')}</LinkButton>
      </OnboardingActions>
    </OnboardingCard>
  )
}

// Tab host for the two directions of the pairing handshake
// (`sync-pairing-inverted-direction.md` §5.3): "Scan a code" is the join
// flow (this browser scans an existing device's code — the requester role);
// "Show a code" is the offer flow (this browser displays a code an existing
// device scans — the offerer role, the natural web ergonomics). Both panels
// share the collected `deviceName`.
export function PairingScreen({
  deviceName,
  initialTab = 'scan',
  onJoinSession,
  onOfferTerminal,
  onCancel,
}: {
  readonly deviceName: string
  readonly initialTab?: PairingTab
  readonly onJoinSession: (session: PairingSession) => void
  readonly onOfferTerminal: (status: OfferTerminalStatus) => void
  readonly onCancel: () => void
}) {
  const { t } = useTranslation('onboarding')
  const [tab, setTab] = useState<PairingTab>(initialTab)
  return (
    <div>
      <div
        role="tablist"
        aria-label={t('pairing.tabs.label')}
        className="mb-4 flex gap-1 rounded-lg border border-line bg-surface-2 p-1"
      >
        <PairingTabButton
          selected={tab === 'scan'}
          onClick={() => setTab('scan')}
        >
          {t('pairing.tabs.scan')}
        </PairingTabButton>
        <PairingTabButton
          selected={tab === 'show'}
          onClick={() => setTab('show')}
        >
          {t('pairing.tabs.show')}
        </PairingTabButton>
      </div>
      {tab === 'scan' ? (
        <PairingSubmitScreen
          deviceName={deviceName}
          onSubmitted={onJoinSession}
          onCancel={onCancel}
        />
      ) : (
        <PairingOfferScreen
          deviceName={deviceName}
          onTerminal={onOfferTerminal}
          onCancel={onCancel}
        />
      )}
    </div>
  )
}

function PairingTabButton({
  selected,
  onClick,
  children,
}: {
  readonly selected: boolean
  readonly onClick: () => void
  readonly children: ReactNode
}) {
  return (
    <button
      type="button"
      role="tab"
      aria-selected={selected}
      onClick={onClick}
      className={
        'flex-1 rounded-md px-3 py-2 text-sm font-medium transition-colors ' +
        (selected
          ? 'bg-surface text-text shadow-sm'
          : 'text-text-2 hover:text-text')
      }
    >
      {children}
    </button>
  )
}

// Offer-flow offerer screen (`sync-pairing-inverted-direction.md` §5.1): this
// browser is the new device. On mount it calls `Pairing.offer(deviceName)`,
// renders the returned `tenshu://offer?…` URI as a QR, and observes the
// session. `showing` keeps the QR up; every other status is terminal and
// bubbles up via `onTerminal`.
export function PairingOfferScreen({
  deviceName,
  onTerminal,
  onCancel,
}: {
  readonly deviceName: string
  readonly onTerminal: (status: OfferTerminalStatus) => void
  readonly onCancel: () => void
}) {
  const { t } = useTranslation('onboarding')
  const sdk = useOnboardingSdk()
  const [state, setState] = useState<
    | { readonly kind: 'starting' }
    | { readonly kind: 'showing'; readonly pairingUri: string }
    | { readonly kind: 'failed'; readonly message: string }
  >({ kind: 'starting' })

  useEffect(() => {
    let cancelled = false
    let unsubscribe: (() => void) | null = null
    let activeSession: { cancel: () => Promise<void> } | null = null
    void sdk.Pairing.offer(deviceName).then(result => {
      if (cancelled) {
        if (result.ok) void result.value.cancel()
        return
      }
      if (!result.ok) {
        setState({ kind: 'failed', message: submitErrorMessage(t, result.error) })
        return
      }
      activeSession = result.value
      unsubscribe = result.value.observe(status => {
        if (cancelled) return
        if (status.kind === 'showing') {
          setState({ kind: 'showing', pairingUri: status.pairingUri })
        } else {
          onTerminal(status)
        }
      })
    })
    return () => {
      cancelled = true
      unsubscribe?.()
      void activeSession?.cancel()
    }
  }, [deviceName, sdk, onTerminal, t])

  if (state.kind === 'failed') {
    return (
      <OnboardingCard>
        <OnboardingTitle>{t('pairing.offer.title')}</OnboardingTitle>
        <OnboardingProse>
          <p
            role="alert"
            className="rounded-md border border-danger/30 bg-danger/10 px-3 py-2 text-danger"
          >
            {state.message}
          </p>
        </OnboardingProse>
        <OnboardingActions>
          <LinkButton onClick={onCancel}>{t('pairing.offer.cancel')}</LinkButton>
        </OnboardingActions>
      </OnboardingCard>
    )
  }

  return (
    <OnboardingCard>
      <OnboardingTitle>{t('pairing.offer.title')}</OnboardingTitle>
      <OnboardingProse>
        <p>{t('pairing.offer.body')}</p>
        <ol className="ml-5 list-decimal space-y-1 text-sm">
          <li>{t('pairing.offer.step1')}</li>
          <li>
            <Trans
              ns="onboarding"
              i18nKey="pairing.offer.step2"
              components={{ em: <em /> }}
            />
          </li>
          <li>{t('pairing.offer.step3')}</li>
        </ol>
      </OnboardingProse>
      <div className="mt-6 flex flex-col items-center gap-4 rounded-md border border-line bg-surface-2 px-6 py-8">
        {state.kind === 'showing' ? (
          <QrDisplay value={state.pairingUri} />
        ) : (
          <div
            className="h-[220px] w-[220px] animate-pulse rounded-lg bg-surface-3"
            aria-hidden
          />
        )}
        <span className="inline-flex items-center gap-2 text-xs text-text-2">
          <span aria-hidden className="h-2 w-2 animate-pulse rounded-full bg-warning" />
          {t('pairing.offer.waiting')}
        </span>
      </div>
      <Hint>{t('pairing.offer.validity')}</Hint>
      <OnboardingActions>
        <LinkButton onClick={onCancel}>{t('pairing.offer.cancel')}</LinkButton>
      </OnboardingActions>
    </OnboardingCard>
  )
}

// Terminal screen for the offer flow. `paired` means this browser is now a
// realm device (symmetric to the join flow's paired state) and shows the SAS
// for a post-hoc comparison against the responder's approval prompt; the other
// statuses reuse the join-flow denied/expired/failed copy since the user-facing
// situation is identical.
export function PairingOfferTerminalScreen({
  status,
  onContinue,
  onRetry,
  onCancel,
}: {
  readonly status: OfferTerminalStatus
  readonly onContinue: () => void
  readonly onRetry: () => void
  readonly onCancel: () => void
}) {
  const { t } = useTranslation('onboarding')
  if (status.kind === 'paired') {
    return (
      <OnboardingCard>
        <OnboardingTitle>{t('pairing.paired.title')}</OnboardingTitle>
        <OnboardingProse>
          <p>{t('pairing.paired.body')}</p>
          <p className="text-sm">{t('pairing.offer.pairedSasHint')}</p>
        </OnboardingProse>
        <div className="mt-4 rounded-md border border-line bg-surface-2 px-6 py-5 text-center">
          <SasDisplay sas={status.sas} />
        </div>
        <OnboardingActions>
          <PrimaryButton onClick={onContinue}>{t('pairing.paired.continue')}</PrimaryButton>
        </OnboardingActions>
      </OnboardingCard>
    )
  }
  if (status.kind === 'denied') {
    return (
      <OnboardingCard>
        <OnboardingTitle>{t('pairing.denied.title')}</OnboardingTitle>
        <OnboardingProse>
          <p>{t('pairing.denied.body')}</p>
        </OnboardingProse>
        <OnboardingActions>
          <PrimaryButton onClick={onRetry}>{t('pairing.denied.retry')}</PrimaryButton>
          <LinkButton onClick={onCancel}>{t('pairing.denied.cancel')}</LinkButton>
        </OnboardingActions>
      </OnboardingCard>
    )
  }
  if (status.kind === 'expired') {
    return (
      <OnboardingCard>
        <OnboardingTitle>{t('pairing.expired.title')}</OnboardingTitle>
        <OnboardingProse>
          <p>{t('pairing.expired.body')}</p>
        </OnboardingProse>
        <OnboardingActions>
          <PrimaryButton onClick={onRetry}>{t('pairing.expired.retry')}</PrimaryButton>
          <LinkButton onClick={onCancel}>{t('pairing.expired.cancel')}</LinkButton>
        </OnboardingActions>
      </OnboardingCard>
    )
  }
  return (
    <OnboardingCard>
      <OnboardingTitle>{t('pairing.failed.title')}</OnboardingTitle>
      <OnboardingProse>
        <p>{status.message}</p>
      </OnboardingProse>
      <OnboardingActions>
        <PrimaryButton onClick={onRetry}>{t('pairing.failed.retry')}</PrimaryButton>
        <LinkButton onClick={onCancel}>{t('pairing.failed.cancel')}</LinkButton>
      </OnboardingActions>
    </OnboardingCard>
  )
}
