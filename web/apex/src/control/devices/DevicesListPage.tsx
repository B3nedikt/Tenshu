// /control/data/devices — devices list per `web-control-plane.md` §4.3
// + `control-ia.md` §5.2. Subscribes to `Devices.observe()` for the
// realm's authorised-device list, surfaces the browser-sync caveat from
// `web/architecture.md` §4.2, and ships the "Pair a new device" CTA at
// the top per the spec's "Show my pairing code" default.
//
// "This device" detection is name-match against the active session's
// deviceName. The Kotlin Device schema doesn't carry an `isCurrent`
// flag (the JS contract's stale `isCurrent` field is empty in practice),
// and `Session.detect()` is the only deviceName surface the apex has
// today. Name collisions are a corner case worth flagging in the UI
// (badge says "This device" only on a unique match) but otherwise fine.
import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { useTranslation } from 'react-i18next'
import { Devices, type Device } from '@tenshu/sdk/identity'
import { Session } from '@tenshu/sdk/onboarding'
import { detectBrowserFamily, browserFamilyLabel } from '../../lib/browserDetect'
import { PairNewDeviceDialog } from './PairNewDeviceDialog'
import { formatDeviceLabel, formatFingerprint } from './format'

export function DevicesListPage() {
  const { t } = useTranslation('control')
  const [devices, setDevices] = useState<readonly Device[] | null>(null)
  const [thisDeviceName, setThisDeviceName] = useState<string | null>(null)
  const [pairOpen, setPairOpen] = useState(false)

  useEffect(() => {
    let cancelled = false
    void Session.detect().then(s => {
      if (cancelled) return
      if (s.kind === 'active' || s.kind === 'locked') {
        setThisDeviceName(s.deviceName)
      }
    })
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    const unsubscribe = Devices.observe(next => setDevices(next))
    return () => unsubscribe()
  }, [])

  const browser = browserFamilyLabel(detectBrowserFamily())
  const showBrowserSyncWarning = browser === 'Chrome' || browser === 'Edge' || browser === 'Firefox'

  return (
    <div>
      <h1 className="text-2xl font-medium">{t('devices.list.title')}</h1>
      <p className="mt-1 text-sm text-text-2">{t('devices.list.subtitle')}</p>

      <div className="mt-6 flex items-center justify-between gap-4">
        <p className="text-sm text-text-2">
          {devices === null
            ? t('devices.list.loading')
            : t('devices.list.count', { count: devices.length })}
        </p>
        <button
          type="button"
          onClick={() => setPairOpen(true)}
          className="rounded-md bg-primary px-3 py-1.5 text-sm font-medium text-on-primary shadow-sm transition-colors hover:bg-primary/90"
        >
          {t('devices.list.pairNew')}
        </button>
      </div>

      {showBrowserSyncWarning && (
        <div className="mt-4 rounded-md border border-warning/30 bg-warning/10 p-4">
          <p className="text-sm font-medium text-warning">
            {t('devices.list.syncWarning.title', { browser })}
          </p>
          <p className="mt-1 text-sm text-warning">
            {t('devices.list.syncWarning.body', { browser })}
          </p>
        </div>
      )}

      <DevicesList devices={devices} thisDeviceName={thisDeviceName} />

      {pairOpen && (
        <PairNewDeviceDialog
          onClose={() => setPairOpen(false)}
          onPaired={() => {
            // Devices.observe poll will pick up the new device in the
            // next ~4 s. ⚠ No timed close here any more: the dialog's
            // own auto-continue closes it after 3 s, and "Stay" (or a
            // tap) keeps it open — a timer here would override both
            // (control-pairing-ux.md §9).
          }}
        />
      )}
    </div>
  )
}

function DevicesList({
  devices,
  thisDeviceName,
}: {
  readonly devices: readonly Device[] | null
  readonly thisDeviceName: string | null
}) {
  const { t } = useTranslation('control')
  if (devices === null) {
    return (
      <ul className="mt-4 divide-y divide-line rounded-md border border-line">
        {[0, 1].map(i => (
          <li key={i} className="px-4 py-4">
            <div className="h-4 w-1/3 animate-pulse rounded bg-surface-3" />
            <div className="mt-2 h-3 w-2/3 animate-pulse rounded bg-surface-3" />
          </li>
        ))}
      </ul>
    )
  }
  if (devices.length === 0) {
    return (
      <p className="mt-6 rounded-md border border-dashed border-line p-8 text-center text-sm text-text-2">
        {t('devices.list.empty')}
      </p>
    )
  }
  const matchingThis = devices.filter(d => d.name === thisDeviceName).length
  return (
    <ul className="mt-4 divide-y divide-line rounded-md border border-line">
      {devices.map(d => (
        <li key={d.deviceId}>
          <DeviceRow
            device={d}
            isThisDevice={
              thisDeviceName !== null && d.name === thisDeviceName && matchingThis === 1
            }
          />
        </li>
      ))}
    </ul>
  )
}

function DeviceRow({
  device,
  isThisDevice,
}: {
  readonly device: Device
  readonly isThisDevice: boolean
}) {
  const { t } = useTranslation('control')
  return (
    <Link
      to={`/control/data/devices/${encodeURIComponent(device.deviceId)}`}
      className="flex items-center gap-4 px-4 py-4 transition-colors hover:bg-surface-3 focus:outline-none focus-visible:bg-surface-3"
    >
      <span
        aria-hidden
        className="grid h-10 w-10 shrink-0 place-items-center rounded-md bg-primary text-sm font-semibold text-on-primary"
      >
        {(device.name[0] ?? '?').toUpperCase()}
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <p className="truncate text-sm font-medium text-text">
            {formatDeviceLabel(device.name, device.hardwareName)}
          </p>
          {isThisDevice && (
            <span className="shrink-0 rounded-full border border-line px-2 py-0.5 text-[10px] font-medium uppercase tracking-wide text-text-2">
              {t('devices.list.thisDeviceBadge')}
            </span>
          )}
        </div>
        <p className="mt-0.5 truncate font-mono text-[11px] text-text-2">
          {formatFingerprint(device.publicKey)}
        </p>
      </div>
      <span aria-hidden className="text-text-3">
        →
      </span>
    </Link>
  )
}
