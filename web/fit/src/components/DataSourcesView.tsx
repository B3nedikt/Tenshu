// The data sources, READ-ONLY (fit-app.md §Web scope 5): one row per connected device × platform, with
// what its last import concluded. ⚠⚠ No switch, no "Import now", no wizard: Health Connect and HealthKit
// have no web surface, and flipping a phone's auto-import from a page that cannot show that phone would
// change it blind. The rows and their wording are the shared `DataSourceProjection`'s, through the SDK.
import { useTranslation } from 'react-i18next'
import type { FitDataSource } from '@tenshu/sdk/fit'
import { dateTimeText, numberText } from '../lib/format'
import { useDataSources } from '../lib/live'
import { Loading, ReadFailure, Stopped } from './ui'

export function DataSourcesView() {
  const { t, i18n } = useTranslation()
  const live = useDataSources()

  return (
    <div>
      <Stopped live={live} />
      {live.isPending ? (
        <Loading />
      ) : live.error || !live.data ? (
        <ReadFailure error={live.error} onRetry={live.retry} />
      ) : !live.data.ok ? (
        <ReadFailure failure={live.data.error} onRetry={live.retry} />
      ) : (
        <>
          {live.data.sources.length === 0 ? (
            <p className="text-sm text-text-2">{t('sources.none')}</p>
          ) : (
            <ul className="divide-y divide-line rounded-lg border border-line" aria-label={t('sources.title')}>
              {live.data.sources.map((s) => (
                <SourceRow key={`${s.kind}:${s.deviceId ?? ''}`} source={s} />
              ))}
            </ul>
          )}
          {live.data.batchImport && live.data.batchImport.lastRunAtMs !== null && (
            <p className="mt-2 text-xs text-text-2" data-testid="batch-import">
              {t('sources.batchImport', {
                when: dateTimeText(i18n.language, live.data.batchImport.lastRunAtMs),
                status: live.data.batchImport.lastStatus ? t(`sources.batchStatus.${live.data.batchImport.lastStatus}`) : '',
                runs: live.data.batchImport.runCount,
              })}
            </p>
          )}
        </>
      )}
      <p className="mt-3 text-xs text-text-2">{t('sources.managedOnDevices')}</p>
    </div>
  )
}

function SourceRow({ source }: { source: FitDataSource }) {
  const { t, i18n } = useTranslation()
  const device = source.isThisDevice ? t('sources.thisBrowser') : (source.deviceLabel ?? t('sources.unknownDevice'))
  const details = [
    t(`sources.state.${source.state}`),
    source.atMs !== null ? dateTimeText(i18n.language, source.atMs) : null,
    source.itemCount !== null ? t('sources.items', { count: source.itemCount, formatted: numberText(i18n.language, source.itemCount) }) : null,
    source.kind === 'TenshuHealth' ? null : source.autoImport ? t('sources.autoOn') : t('sources.autoOff'),
  ].filter((v): v is string => v !== null)
  return (
    <li className="px-4 py-2.5" data-testid="source-row" data-state={source.state}>
      <div className="flex items-center justify-between gap-2">
        <span className="text-sm font-medium text-text">
          {t(`sources.kind.${source.kind}`)} · {device}
        </span>
        {source.needsAttention && <span className="text-xs font-medium text-warning">{t('sources.needsAttention')}</span>}
      </div>
      <p className="mt-0.5 text-xs text-text-2">{details.join(' · ')}</p>
    </li>
  )
}
