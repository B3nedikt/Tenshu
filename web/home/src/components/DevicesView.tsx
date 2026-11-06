// The Home tab (home-and-mia-apps.md §3, the iOS read-only `DevicesView`): the hub storage tile, then
// every bridged device grouped by room, with its state AS STORED.
//
// ⚠⚠ READ-ONLY, decided (web-app-suite.md §8 items 2 and 9). A row offers nothing to press: no power
// switch, no level slider, no add-device or pairing button, no rename, no reorder. The tab says plainly
// where control happens instead — as iOS does — and the runtime maps no command anyway.
import { useTranslation } from 'react-i18next'
import type { HomeDevice } from '@tenshu/sdk/home'
import { groupByRoom } from '../lib/devices'
import { stateLine } from '../lib/format'
import { useDevices, useHubStorage } from '../lib/live'
import { HubTile } from './HubTile'
import { EmptyState, Loading, ReadFailure, Section, Stopped } from './ui'

export function DevicesView() {
  const { t } = useTranslation()
  const devices = useDevices()
  const hub = useHubStorage()

  return (
    <div>
      <p className="mb-4 rounded-lg border border-line bg-surface-2 px-4 py-2 text-sm text-text-2" data-testid="view-only">
        {t('devices.viewOnly')}
      </p>
      <Stopped live={hub} />
      <Stopped live={devices} />

      {/* The tile: absent when no hub reports disk figures (`hub: null` — an answer), a failure when
          the read failed. */}
      {hub.isPending ? null : hub.error ? (
        <div className="mb-4">
          <ReadFailure error={hub.error} onRetry={hub.retry} />
        </div>
      ) : hub.data && !hub.data.ok ? (
        <div className="mb-4">
          <ReadFailure failure={hub.data.error} onRetry={hub.retry} />
        </div>
      ) : hub.data?.ok && hub.data.hub ? (
        <div className="mb-4">
          <HubTile hub={hub.data.hub} />
        </div>
      ) : null}

      {devices.isPending ? (
        <Loading />
      ) : devices.error ? (
        <ReadFailure error={devices.error} onRetry={devices.retry} />
      ) : devices.data && !devices.data.ok ? (
        <ReadFailure failure={devices.data.error} onRetry={devices.retry} />
      ) : devices.data?.ok && devices.data.devices.length === 0 ? (
        <EmptyState title={t('devices.emptyTitle')} body={t('devices.emptyBody')} />
      ) : devices.data?.ok ? (
        groupByRoom(devices.data.devices, devices.data.rooms).map((group) => (
          <Section key={group.room ?? ''} title={group.room ?? t('devices.unassigned')}>
            <ul aria-label={group.room ?? t('devices.unassigned')} className="divide-y divide-line rounded-lg border border-line">
              {group.devices.map((d) => (
                <DeviceRow key={d.deviceId} device={d} />
              ))}
            </ul>
          </Section>
        ))
      ) : null}
    </div>
  )
}

function DeviceRow({ device }: { device: HomeDevice }) {
  const { t } = useTranslation()
  const line = stateLine(t, device)
  const kind = t(`devices.kind.${device.kind}`, { defaultValue: t('devices.kind.other') })
  return (
    <li className="flex items-center justify-between gap-3 px-4 py-3" data-testid="device-row" data-id={device.deviceId}>
      <span className="min-w-0">
        <span className="block truncate text-sm font-medium text-text">{device.name.trim() !== '' ? device.name : kind}</span>
        {line.text !== '' && (
          <span className={`block truncate text-xs ${line.alert ? 'text-danger' : 'text-text-2'}`}>{line.text}</span>
        )}
      </span>
      <span className="shrink-0 text-xs text-text-2">{kind}</span>
    </li>
  )
}
