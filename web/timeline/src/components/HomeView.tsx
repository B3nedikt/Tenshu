// Home: the trip suggestion, the bound trips, then the day list — all from ONE feed snapshot
// (`timeline.feed.observe`), so the three can never disagree about which moments exist.
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { StreamNotice } from '@tenshu/app-kit/ui'
import { useFeed } from '../lib/live'
import type { Open } from '../lib/nav'
import { DayList } from './MomentRow'
import { SuggestionCard } from './SuggestionCard'
import { Loading, ReadFailure, Section } from './ui'

export function HomeView({ onOpen }: { onOpen: Open }) {
  const { t } = useTranslation()
  const live = useFeed()
  // Suggestions this tab already accepted or dismissed, hidden even before the feed re-emits.
  const [resolved, setResolved] = useState<readonly string[]>([])

  const notice = <StreamNotice live={live} copy={{ stopped: t('stream.stopped'), reconnect: t('stream.reconnect') }} />

  if (live.isPending) return <Loading />
  const result = live.data
  if (!result) return <ReadFailure error={live.error} onRetry={live.retry} />
  if (!result.ok) {
    return (
      <>
        {notice}
        <ReadFailure failure={result.error} onRetry={live.retry} />
      </>
    )
  }

  const suggestion = result.suggestion && !resolved.includes(result.suggestion.suggestionId) ? result.suggestion : null
  const empty = result.days.length === 0 && result.trips.length === 0 && !suggestion

  return (
    <div className="space-y-4">
      {notice}
      {suggestion && (
        <SuggestionCard
          suggestion={suggestion}
          onAccepted={(tripId) => onOpen({ kind: 'trip', id: tripId })}
          onResolved={(id) => setResolved((ids) => [...ids, id])}
          onRefresh={live.retry}
        />
      )}
      {result.trips.length > 0 && (
        <Section title={t('home.trips')}>
          <ul className="space-y-1">
            {result.trips.map((trip) => (
              <li key={trip.tripId}>
                <button
                  type="button"
                  onClick={() => onOpen({ kind: 'trip', id: trip.tripId })}
                  className="w-full rounded-lg border border-line px-3 py-2 text-left hover:bg-surface-2"
                >
                  <span className="block text-sm font-medium text-text">{trip.title || t('trip.untitled')}</span>
                  <span className="block text-xs text-text-2">{[trip.dateRange, trip.year].filter(Boolean).join(' · ')}</span>
                </button>
              </li>
            ))}
          </ul>
        </Section>
      )}
      {empty ? (
        <div className="rounded-lg border border-dashed border-line px-4 py-6 text-center">
          <p className="text-sm font-medium text-text">{t('home.emptyTitle')}</p>
          <p className="mt-1 text-sm text-text-2">{t('home.emptyBody')}</p>
        </div>
      ) : (
        <DayList days={result.days} onOpen={onOpen} />
      )}
    </div>
  )
}
