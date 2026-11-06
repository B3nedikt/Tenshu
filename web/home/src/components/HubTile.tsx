// The hub storage tile (home-and-mia-apps.md §3, §8.1 "Storage tile live over real hub data"): the
// freshest hub's disk figures, a usage bar, and native's honesty line — "As of 14:05", or
// "Hub offline · as of 14:05" once the snapshot is older than the hub's own `staleAfterMs`.
//
// ⚠⚠ Staleness is judged on THIS app's clock (`lib/hub.ts`): the read's `stale` was true when it was
// read, and nothing re-derives it while the rows stand still.
import { useTranslation } from 'react-i18next'
import type { HomeHubStorage } from '@tenshu/sdk/home'
import { asOfText, byteSizeText } from '../lib/format'
import { useNow } from '../lib/clock'
import { isStale, staleAtMs } from '../lib/hub'

export function HubTile({ hub }: { hub: HomeHubStorage }) {
  const { t } = useTranslation()
  const now = useNow(staleAtMs(hub))
  const stale = isStale(hub, now)
  const used = hub.diskTotalBytes > 0 ? Math.min(1, Math.max(0, (hub.diskTotalBytes - hub.diskFreeBytes) / hub.diskTotalBytes)) : 0
  const asOf = asOfText(hub.freshAsOfMs, now)
  return (
    <div data-testid="hub-tile" data-stale={stale} className="rounded-lg border border-line bg-surface-2 px-4 py-3">
      <p className="text-sm font-medium text-text">{hub.name.trim() !== '' ? hub.name : t('hub.defaultName')}</p>
      <p className="mt-0.5 text-sm text-text-2">
        {t('hub.free', { free: byteSizeText(hub.diskFreeBytes), total: byteSizeText(hub.diskTotalBytes) })}
      </p>
      <div
        role="progressbar"
        aria-label={t('hub.usage')}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.round(used * 100)}
        className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-line"
      >
        <div className="h-full bg-primary" style={{ width: `${used * 100}%` }} />
      </div>
      <p className={`mt-1.5 text-xs ${stale ? 'text-danger' : 'text-text-2'}`}>
        {stale ? t('hub.offlineAsOf', { time: asOf }) : t('hub.asOf', { time: asOf })}
      </p>
    </div>
  )
}
