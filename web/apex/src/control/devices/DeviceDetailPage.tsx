// /control/data/devices/:deviceId — per-device detail per
// `web-control-plane.md` §4.3 + `control-ia.md` §5.2. v1 surfaces:
//
//  - device name + fingerprint (first 8 bytes of the Ed25519 public key)
//  - "This device" badge when the active session's deviceName matches
//  - Revoke action (with confirmation step), wired through
//    `Devices.revoke(deviceId)` → `:sync-service` `RevokeDevice`
//  - Rename: deferred — needs an SDK op that doesn't exist on the
//    platform side either (the Android UI doesn't support rename
//    today). Surfaced as a disabled button with a follow-up note.
//  - Realm-epoch: deferred — `admittedAtEpoch` exists on the Composite
//    but isn't surfaced through the schema walker yet. Same deferral
//    note pattern.
import { useEffect, useState } from 'react'
import { Link, Navigate, useNavigate, useParams } from 'react-router'
import { useTranslation } from 'react-i18next'
import { Devices, type Device } from '@tenshu/sdk/identity'
import { Session } from '@tenshu/sdk/onboarding'
import { DevicesDialog } from './DevicesDialog'
import { formatDeviceLabel, formatFingerprint } from './format'

type RevokeState =
  | { readonly kind: 'idle' }
  | { readonly kind: 'confirming' }
  | { readonly kind: 'revoking' }
  | { readonly kind: 'failed'; readonly message: string }

export function DeviceDetailPage() {
  const { t } = useTranslation('control')
  const { deviceId: deviceIdRaw } = useParams<{ readonly deviceId: string }>()
  const navigate = useNavigate()
  const deviceId = deviceIdRaw === undefined ? null : decodeURIComponent(deviceIdRaw)
  const [devices, setDevices] = useState<readonly Device[] | null>(null)
  const [thisDeviceName, setThisDeviceName] = useState<string | null>(null)
  const [revoke, setRevoke] = useState<RevokeState>({ kind: 'idle' })

  useEffect(() => {
    let cancelled = false
    void Session.detect().then(s => {
      if (cancelled) return
      if (s.kind === 'active' || s.kind === 'locked') setThisDeviceName(s.deviceName)
    })
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    const unsubscribe = Devices.observe(next => setDevices(next))
    return () => unsubscribe()
  }, [])

  if (deviceId === null) return <Navigate to="/control/data/devices" replace />
  if (devices === null) return <Loading />
  const device = devices.find(d => d.deviceId === deviceId)
  if (device === undefined) return <NotFound id={deviceId} />

  const isThisDevice = thisDeviceName !== null && device.name === thisDeviceName

  async function performRevoke() {
    setRevoke({ kind: 'revoking' })
    const result = await Devices.revoke(deviceId!)
    if (!result.ok) {
      const message =
        result.error.kind === 'network'
          ? t('devices.detail.revoke.networkError')
          : result.error.message === ''
            ? t('devices.detail.revoke.fallbackError')
            : result.error.message
      setRevoke({ kind: 'failed', message })
      return
    }
    // Devices.observe will drop the entry on the next poll; bounce back
    // to the list so the user lands on a coherent state.
    navigate('/control/data/devices', { replace: true })
  }

  return (
    <div>
      <Link
        to="/control/data/devices"
        className="text-xs font-medium text-text-2 hover:text-text"
      >
        {t('devices.detail.backLink')}
      </Link>
      <header className="mt-4 flex items-start gap-4">
        <span
          aria-hidden
          className="grid h-12 w-12 shrink-0 place-items-center rounded-md bg-primary text-base font-semibold text-on-primary"
        >
          {(device.name[0] ?? '?').toUpperCase()}
        </span>
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <h1 className="text-2xl font-medium">
              {formatDeviceLabel(device.name, device.hardwareName)}
            </h1>
            {isThisDevice && (
              <span className="rounded-full border border-line px-2 py-0.5 text-[10px] font-medium uppercase tracking-wide text-text-2">
                {t('devices.detail.thisDeviceBadge')}
              </span>
            )}
          </div>
          <p className="mt-1 font-mono text-xs text-text-2">
            {formatFingerprint(device.publicKey)}
          </p>
        </div>
      </header>

      <dl className="mt-6 grid gap-4 sm:grid-cols-2">
        <MetaCell term={t('devices.detail.meta.deviceId')} value={device.deviceId} mono />
        <MetaCell
          term={t('devices.detail.meta.status')}
          value={
            device.isAuthorized
              ? t('devices.detail.meta.statusAuthorised')
              : t('devices.detail.meta.statusPendingRevocation')
          }
        />
      </dl>

      <section className="mt-8">
        <SectionHeader>{t('devices.detail.actions')}</SectionHeader>
        <div className="mt-3 grid gap-3 sm:grid-cols-2">
          <DisabledAction
            title={t('devices.detail.rename.title')}
            description={t('devices.detail.rename.description')}
            note={t('devices.detail.rename.note')}
          />
          {isThisDevice ? (
            <DisabledAction
              title={t('devices.detail.revokeSelf.title')}
              description={t('devices.detail.revokeSelf.description')}
              note={t('devices.detail.revokeSelf.note')}
            />
          ) : (
            <ActionCard
              title={t('devices.detail.revoke.title')}
              description={t('devices.detail.revoke.description')}
              cta={t('devices.detail.revoke.cta')}
              destructive
              onClick={() => setRevoke({ kind: 'confirming' })}
            />
          )}
        </div>
      </section>

      {(revoke.kind === 'confirming' || revoke.kind === 'revoking' || revoke.kind === 'failed') && (
        <RevokeConfirmDialog
          deviceName={device.name}
          state={revoke}
          onConfirm={() => void performRevoke()}
          onClose={() => setRevoke({ kind: 'idle' })}
        />
      )}
    </div>
  )
}

function Loading() {
  const { t } = useTranslation('control')
  return (
    <div>
      <Link
        to="/control/data/devices"
        className="text-xs font-medium text-text-2 hover:text-text"
      >
        {t('devices.detail.backLink')}
      </Link>
      <div className="mt-6 h-6 w-1/3 animate-pulse rounded bg-surface-3" />
      <div className="mt-3 h-4 w-2/3 animate-pulse rounded bg-surface-3" />
    </div>
  )
}

function NotFound({ id }: { readonly id: string }) {
  const { t } = useTranslation('control')
  return (
    <div>
      <Link
        to="/control/data/devices"
        className="text-xs font-medium text-text-2 hover:text-text"
      >
        {t('devices.detail.backLink')}
      </Link>
      <h1 className="mt-4 text-2xl font-medium">{t('devices.detail.notFound.title')}</h1>
      <p className="mt-1 text-sm text-text-2">
        {t('devices.detail.notFound.bodyPrefix')}
        <span className="font-mono text-xs">{id}</span>
        {t('devices.detail.notFound.bodySuffix')}
      </p>
    </div>
  )
}

function SectionHeader({ children }: { readonly children: React.ReactNode }) {
  return (
    <h2 className="text-sm font-semibold uppercase tracking-wide text-text-2">{children}</h2>
  )
}

function MetaCell({
  term,
  value,
  mono,
}: {
  readonly term: string
  readonly value: string
  readonly mono?: boolean
}) {
  return (
    <div className="rounded-md border border-line px-3 py-2">
      <dt className="text-[11px] uppercase tracking-wide text-text-2">{term}</dt>
      <dd
        className={
          'mt-0.5 text-sm text-text' +
          (mono === true ? ' break-all font-mono text-xs' : '')
        }
      >
        {value}
      </dd>
    </div>
  )
}

function ActionCard({
  title,
  description,
  cta,
  destructive,
  onClick,
}: {
  readonly title: string
  readonly description: string
  readonly cta: string
  readonly destructive?: boolean
  readonly onClick: () => void
}) {
  const buttonClass =
    destructive === true
      ? 'rounded-md border border-danger/30 bg-surface px-3 py-1.5 text-sm font-medium text-danger shadow-sm transition-colors hover:bg-danger/10'
      : 'rounded-md border border-line bg-surface px-3 py-1.5 text-sm font-medium text-text shadow-sm transition-colors hover:bg-surface-2'
  return (
    <div className="flex flex-col rounded-md border border-line bg-surface p-4">
      <p className="text-sm font-medium text-text">{title}</p>
      <p className="mt-1 text-xs text-text-2">{description}</p>
      <div className="mt-4">
        <button type="button" onClick={onClick} className={buttonClass}>
          {cta}
        </button>
      </div>
    </div>
  )
}

function DisabledAction({
  title,
  description,
  note,
}: {
  readonly title: string
  readonly description: string
  readonly note: string
}) {
  return (
    <div className="flex flex-col rounded-md border border-line bg-surface p-4">
      <p className="text-sm font-medium text-text">{title}</p>
      <p className="mt-1 text-xs text-text-2">{description}</p>
      <div className="mt-4">
        <button
          type="button"
          disabled
          title={note}
          className="rounded-md border border-line bg-surface px-3 py-1.5 text-sm font-medium text-text-3"
        >
          {title}
        </button>
      </div>
      <p className="mt-2 text-[11px] text-text-2">{note}</p>
    </div>
  )
}

function RevokeConfirmDialog({
  deviceName,
  state,
  onConfirm,
  onClose,
}: {
  readonly deviceName: string
  readonly state: RevokeState
  readonly onConfirm: () => void
  readonly onClose: () => void
}) {
  const { t } = useTranslation('control')
  const inFlight = state.kind === 'revoking'
  return (
    <DevicesDialog title={t('devices.detail.revoke.dialogTitle')} onClose={inFlight ? () => {} : onClose}>
      <p className="text-sm text-text-2">
        {t('devices.detail.revoke.bodyPrefix')}
        <span className="font-medium text-text">{deviceName}</span>
        {t('devices.detail.revoke.bodySuffix')}
      </p>
      {state.kind === 'failed' && (
        <div className="mt-4 rounded-md border border-danger/30 bg-danger/10 p-3 text-sm text-danger">
          {state.message}
        </div>
      )}
      <div className="mt-6 flex flex-wrap items-center gap-3">
        <button
          type="button"
          disabled={inFlight}
          onClick={onConfirm}
          className="rounded-md border border-danger/30 bg-danger/10 px-4 py-2 text-sm font-medium text-danger shadow-sm transition-colors hover:bg-danger/10 disabled:cursor-not-allowed disabled:opacity-60"
        >
          {inFlight ? t('devices.detail.revoke.inFlight') : t('devices.detail.revoke.cta')}
        </button>
        <button
          type="button"
          disabled={inFlight}
          onClick={onClose}
          className="text-sm font-medium text-text-2 hover:text-text disabled:cursor-not-allowed disabled:opacity-60"
        >
          {t('devices.detail.revoke.cancel')}
        </button>
      </div>
    </DevicesDialog>
  )
}
