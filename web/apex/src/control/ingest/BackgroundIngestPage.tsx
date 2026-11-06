// /control/data/background-ingest — Control's background-ingest registry
// (`drive-synced-folders-phase2.md` §8). The web counterpart of the native
// `BackgroundIngestScreen`: the inventory of apps backing up device data + their
// live status, with a per-source central kill switch. Binds to the authenticated
// `IngestSources` SDK surface (`@tenshu/sdk/ingest`).
import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { IngestSources, type IngestSourceItem } from '@tenshu/sdk/ingest'

const STATUS_KEYS = new Set([
  'IDLE',
  'RUNNING',
  'UP_TO_DATE',
  'PAUSED_POLICY',
  'OUT_OF_SPACE',
  'ERROR',
])

export function BackgroundIngestPage() {
  const { t } = useTranslation('control')
  const [sources, setSources] = useState<readonly IngestSourceItem[] | null>(null)

  const load = useCallback(() => {
    void IngestSources.list()
      .then(setSources)
      .catch(() => setSources([]))
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const onToggle = useCallback(
    async (source: IngestSourceItem, enabled: boolean) => {
      // Optimistic flip; the central kill switch converges on re-load.
      setSources(prev => prev?.map(it => (it.key === source.key ? { ...it, enabled } : it)) ?? prev)
      await IngestSources.setEnabled(source.sourceId, source.deviceId, enabled)
      load()
    },
    [load],
  )

  return (
    <div>
      <h1 className="text-2xl font-medium">{t('backgroundIngest.title')}</h1>
      <p className="mt-1 text-sm text-text-2">{t('backgroundIngest.subtitle')}</p>

      <p className="mt-6 text-sm text-text-2">
        {sources === null
          ? t('backgroundIngest.loading')
          : t('backgroundIngest.count', { count: sources.length })}
      </p>

      <SourceList sources={sources} onToggle={onToggle} statusLabel={s => statusLabel(t, s)} />
    </div>
  )
}

function statusLabel(t: ReturnType<typeof useTranslation>['0'], status: string): string {
  return t(`backgroundIngest.status.${STATUS_KEYS.has(status) ? status : 'IDLE'}`)
}

function SourceList({
  sources,
  onToggle,
  statusLabel,
}: {
  readonly sources: readonly IngestSourceItem[] | null
  readonly onToggle: (source: IngestSourceItem, enabled: boolean) => void
  readonly statusLabel: (status: string) => string
}) {
  const { t } = useTranslation('control')
  if (sources === null) {
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
  if (sources.length === 0) {
    return (
      <p className="mt-6 rounded-md border border-dashed border-line p-8 text-center text-sm text-text-2">
        {t('backgroundIngest.empty')}
      </p>
    )
  }
  return (
    <ul className="mt-4 divide-y divide-line rounded-md border border-line">
      {sources.map(source => (
        <li key={source.key} className="flex items-center gap-4 px-4 py-4">
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-medium text-text">
              {source.displayName}
            </p>
            <p className="mt-0.5 text-xs text-text-2">{statusLabel(source.status)}</p>
          </div>
          <input
            type="checkbox"
            role="switch"
            aria-label={t('backgroundIngest.toggleAria', { name: source.displayName })}
            checked={source.enabled}
            onChange={e => onToggle(source, e.target.checked)}
          />
        </li>
      ))}
    </ul>
  )
}
