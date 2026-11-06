// Pair-new-device flow at /control/data/devices (per
// `web-control-plane.md` §4.3). Opens from the "Pair a new device" CTA
// on DevicesListPage. The web is BOTH the host and the requester; this
// dialog defaults to the host role per the spec's "Show my pairing
// code" default — the user displays the code, scans/types it on the
// other device, and approves the resulting request here.
//
// Wraps `Pairing.show()` from the SDK contract: the returned session
// emits `showing → request → paired` (or `cancelled`/`error`). UI
// surfaces approve/cancel against the latched deviceId. SAS is shown
// when the proxy populates it; falls through to deviceName-only
// confirmation otherwise (matches v1 native UX per the SDK comment).
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Pairing, type HostPairingSession, type HostPairingStatus } from '@tenshu/sdk/onboarding'
import { QrDisplay } from '../../onboarding/widgets/QrDisplay'
import { DevicesDialog } from './DevicesDialog'
import { AutoContinueButton } from './AutoContinueButton'
import { useAutoContinue } from './useAutoContinue'

type Phase =
  | { readonly kind: 'starting' }
  | { readonly kind: 'failed'; readonly message: string }
  | { readonly kind: 'active'; readonly status: HostPairingStatus; readonly session: HostPairingSession }

export function PairNewDeviceDialog({
  onClose,
  onPaired,
}: {
  readonly onClose: () => void
  readonly onPaired: () => void
}) {
  const { t } = useTranslation('control')
  const [phase, setPhase] = useState<Phase>({ kind: 'starting' })

  useEffect(() => {
    let cancelled = false
    let unsubscribe: (() => void) | null = null
    let activeSession: HostPairingSession | null = null
    void Pairing.show().then(result => {
      if (cancelled) {
        if (result.ok) void result.value.cancel()
        return
      }
      if (!result.ok) {
        const message =
          result.error.kind === 'network'
            ? t('devices.pair.networkError')
            : result.error.message === ''
              ? t('devices.pair.failedStartFallback')
              : result.error.message
        setPhase({ kind: 'failed', message })
        return
      }
      activeSession = result.value
      unsubscribe = result.value.observe(status => {
        if (cancelled) return
        setPhase({ kind: 'active', status, session: result.value })
        if (status.kind === 'paired') {
          onPaired()
        }
      })
    })
    return () => {
      cancelled = true
      unsubscribe?.()
      void activeSession?.cancel()
    }
  }, [onPaired, t])

  function handleClose() {
    if (phase.kind === 'active') void phase.session.cancel()
    onClose()
  }

  return (
    <DevicesDialog title={t('devices.pair.dialogTitle')} onClose={handleClose}>
      {phase.kind === 'starting' && <StartingStep />}
      {phase.kind === 'failed' && <FailedStep message={phase.message} onClose={handleClose} />}
      {phase.kind === 'active' && (
        <ActiveStep
          status={phase.status}
          session={phase.session}
          onClose={handleClose}
        />
      )}
    </DevicesDialog>
  )
}

function StartingStep() {
  const { t } = useTranslation('control')
  return (
    <div className="py-6 text-center">
      <div className="mx-auto h-8 w-8 animate-spin rounded-full border-2 border-line border-t-primary" />
      <p className="mt-4 text-sm text-text-2">{t('devices.pair.starting')}</p>
    </div>
  )
}

function FailedStep({
  message,
  onClose,
}: {
  readonly message: string
  readonly onClose: () => void
}) {
  const { t } = useTranslation('control')
  return (
    <div>
      <div className="rounded-md border border-danger/30 bg-danger/10 p-3 text-sm text-danger">
        {message}
      </div>
      <div className="mt-6">
        <button
          type="button"
          onClick={onClose}
          className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary shadow-sm transition-colors hover:bg-primary/90"
        >
          {t('devices.pair.close')}
        </button>
      </div>
    </div>
  )
}

function ActiveStep({
  status,
  session,
  onClose,
}: {
  readonly status: HostPairingStatus
  readonly session: HostPairingSession
  readonly onClose: () => void
}) {
  if (status.kind === 'showing') {
    return <ShowingStep pairingUri={status.pairingUri} onCancel={onClose} />
  }
  if (status.kind === 'request') {
    return (
      <RequestStep
        deviceId={status.deviceId}
        deviceName={status.deviceName}
        sas={status.sas}
        session={session}
        onCancel={onClose}
      />
    )
  }
  if (status.kind === 'paired') {
    return <PairedStep onClose={onClose} />
  }
  if (status.kind === 'cancelled') {
    return <CancelledStep onClose={onClose} />
  }
  return <ErrorStep message={status.message} onClose={onClose} />
}

function ShowingStep({
  pairingUri,
  onCancel,
}: {
  readonly pairingUri: string
  readonly onCancel: () => void
}) {
  const { t } = useTranslation('control')
  return (
    <div>
      <p className="text-sm text-text-2">{t('devices.pair.showing.body')}</p>
      <div className="mt-4 flex flex-col items-center gap-4 rounded-md border border-line bg-surface-2 p-4">
        <QrDisplay value={pairingUri} size={200} />
        <div className="w-full">
          <p className="text-[11px] uppercase tracking-wide text-text-2">{t('devices.pair.showing.label')}</p>
          <p className="mt-1 break-all font-mono text-xs text-text">
            {pairingUri}
          </p>
        </div>
      </div>
      <p className="mt-3 text-xs text-text-2">{t('devices.pair.showing.validity')}</p>
      <div className="mt-6 flex flex-wrap items-center gap-3">
        <span className="inline-flex items-center gap-2 text-xs text-text-2">
          <span aria-hidden className="h-2 w-2 animate-pulse rounded-full bg-warning" />
          {t('devices.pair.showing.waiting')}
        </span>
        <button
          type="button"
          onClick={onCancel}
          className="ml-auto text-sm font-medium text-text-2 hover:text-text"
        >
          {t('devices.pair.showing.cancel')}
        </button>
      </div>
    </div>
  )
}

function RequestStep({
  deviceId,
  deviceName,
  sas,
  session,
  onCancel,
}: {
  readonly deviceId: string
  readonly deviceName: string
  readonly sas: string | null
  readonly session: HostPairingSession
  readonly onCancel: () => void
}) {
  const { t } = useTranslation('control')
  const [approving, setApproving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function approve() {
    setApproving(true)
    setError(null)
    const result = await session.approve(deviceId)
    if (!result.ok) {
      setApproving(false)
      const message =
        result.error.kind === 'network'
          ? t('devices.pair.networkError')
          : result.error.message === ''
            ? t('devices.pair.request.approvalFailed')
            : result.error.message
      setError(message)
    }
  }

  return (
    <div>
      <p className="text-sm text-text-2">
        <span className="font-medium text-text">{deviceName}</span>
        {t('devices.pair.request.wantsToJoinSuffix')}
      </p>
      {sas !== null && (
        <div className="mt-4 rounded-md border border-line bg-surface-2 p-4">
          <p className="text-[11px] uppercase tracking-wide text-text-2">
            {t('devices.pair.request.sasLabel')}
          </p>
          <p className="mt-1 font-mono text-2xl font-semibold tracking-wider text-text">
            {sas}
          </p>
          <p className="mt-2 text-xs text-text-2">{t('devices.pair.request.versionHint')}</p>
        </div>
      )}
      {sas === null && (
        <p className="mt-3 text-xs text-text-2">{t('devices.pair.request.noSasHint')}</p>
      )}
      {error !== null && (
        <div className="mt-4 rounded-md border border-danger/30 bg-danger/10 p-3 text-sm text-danger">
          {error}
        </div>
      )}
      <div className="mt-6 flex flex-wrap items-center gap-3">
        <button
          type="button"
          disabled={approving}
          onClick={() => void approve()}
          className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary shadow-sm transition-colors hover:bg-primary/90 disabled:cursor-not-allowed disabled:bg-surface-3"
        >
          {approving ? t('devices.pair.request.approving') : t('devices.pair.request.approve')}
        </button>
        <button
          type="button"
          onClick={onCancel}
          className="text-sm font-medium text-text-2 hover:text-text"
        >
          {t('devices.pair.request.cancel')}
        </button>
      </div>
    </div>
  )
}

/**
 * The host's terminal: the request was approved. Auto-continues (`control-pairing-ux.md` §9) —
 * armed at once, because the host has no join race to wait out and the web runs no funding check.
 * Any pointer-down inside the step means "I'm reading this" and stops the countdown.
 */
function PairedStep({ onClose }: { readonly onClose: () => void }) {
  const { t } = useTranslation('control')
  const auto = useAutoContinue({ armed: true, onContinue: onClose })
  return (
    <div onPointerDownCapture={auto.cancel}>
      <div
        role="status"
        aria-live="polite"
        className="rounded-md border border-success/30 bg-success/10 p-3 text-sm text-success"
      >
        {t('devices.pair.paired.body')}
      </div>
      <div className="mt-6">
        <AutoContinueButton auto={auto} label={t('devices.pair.paired.done')} />
      </div>
    </div>
  )
}

function CancelledStep({ onClose }: { readonly onClose: () => void }) {
  const { t } = useTranslation('control')
  return (
    <div>
      <p className="text-sm text-text-2">{t('devices.pair.cancelled.body')}</p>
      <div className="mt-6">
        <button
          type="button"
          onClick={onClose}
          className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary shadow-sm transition-colors hover:bg-primary/90"
        >
          {t('devices.pair.cancelled.close')}
        </button>
      </div>
    </div>
  )
}

function ErrorStep({
  message,
  onClose,
}: {
  readonly message: string
  readonly onClose: () => void
}) {
  const { t } = useTranslation('control')
  return (
    <div>
      <div className="rounded-md border border-danger/30 bg-danger/10 p-3 text-sm text-danger">
        {message === '' ? t('devices.pair.errorFallback') : message}
      </div>
      <div className="mt-6">
        <button
          type="button"
          onClick={onClose}
          className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary shadow-sm transition-colors hover:bg-primary/90"
        >
          {t('devices.pair.close')}
        </button>
      </div>
    </div>
  )
}
