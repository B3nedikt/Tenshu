// A bound trip: its route map, its legs and its days (the same rows as Home). The map draws each
// leg's route coloured by mode, pins the two ends and fits the camera to the route (`RouteMap`).
// ⚠ The legs list never depends on the map: no route points (no map), a failed route read, or the
// map's "not available on the web yet" all leave it — and the days — as they are.
import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import type { TimelineTripDetail } from '@tenshu/sdk/timeline'
import { trip } from '../lib/proxy'
import { queryKeys } from '../lib/queryKeys'
import { distanceText, durationText, movementModeText, timeText } from '../lib/format'
import { useUnits } from '../lib/units'
import type { Open } from '../lib/nav'
import { routeLegs } from '../lib/route'
import { DayList } from './MomentRow'
import { RouteMap } from './RouteMap'
import { BackButton, Loading, ReadFailure, Section } from './ui'

export function TripDetail({ tripId, onBack, onOpen }: { tripId: string; onBack: () => void; onOpen: Open }) {
  const { t } = useTranslation()
  const query = useQuery({ queryKey: queryKeys.trip(tripId), queryFn: () => trip(tripId) })

  return (
    <div>
      <BackButton onBack={onBack} />
      {query.isPending ? (
        <Loading />
      ) : query.isError ? (
        <ReadFailure error={query.error} onRetry={() => void query.refetch()} />
      ) : !query.data.ok ? (
        <ReadFailure failure={query.data.error} onRetry={() => void query.refetch()} />
      ) : query.data.trip === null ? (
        <p className="text-sm text-text-2">{t('trip.notFound')}</p>
      ) : (
        <TripBody trip={query.data.trip} onOpen={onOpen} />
      )}
    </div>
  )
}

function TripBody({ trip, onOpen }: { trip: TimelineTripDetail; onOpen: Open }) {
  const { t, i18n } = useTranslation()
  const units = useUnits()
  const legs = trip.days.flatMap((d) => d.moments).filter((m) => m.kind === 'Movement')
  // Stable while the trip is (the query shares structure): the map re-pushes its overlay on a new one.
  const route = useMemo(() => routeLegs(trip.days.flatMap((d) => d.moments)), [trip])
  return (
    <article>
      <h2 className="text-xl font-semibold text-text">{trip.title || t('trip.untitled')}</h2>
      <p className="mt-0.5 text-sm text-text-2">{[trip.dateRange, trip.year].filter(Boolean).join(' · ')}</p>
      {trip.missingMomentCount > 0 && (
        <p className="mt-3 text-sm text-text-2">{t('trip.missing', { count: trip.missingMomentCount })}</p>
      )}

      <div className="mt-4 empty:hidden">
        <RouteMap legs={route} />
      </div>

      {legs.length > 0 && (
        <Section title={t('trip.legs')}>
          <ol aria-label={t('trip.legs')} className="space-y-1 text-sm">
            {legs.map((leg) => (
              <li key={leg.momentId}>
                <button
                  type="button"
                  onClick={() => onOpen({ kind: 'moment', id: leg.momentId })}
                  className="flex w-full gap-3 rounded-lg px-2 py-1.5 text-left hover:bg-surface-2"
                >
                  <span className="w-14 shrink-0 tabular-nums text-text-2">{timeText(i18n.language, leg.timestampMs)}</span>
                  <span className="flex-1 text-text">
                    {[
                      movementModeText(t, leg.movementMode),
                      leg.distanceM !== null ? distanceText(t, leg.distanceM, units) : null,
                      durationText(t, leg.durationSec),
                    ]
                      .filter(Boolean)
                      .join(' · ')}
                  </span>
                </button>
              </li>
            ))}
          </ol>
        </Section>
      )}

      <Section title={t('trip.days')}>
        {trip.days.length === 0 ? (
          <p className="text-sm text-text-2">{t('trip.noDays')}</p>
        ) : (
          <DayList days={trip.days} onOpen={onOpen} />
        )}
      </Section>
    </article>
  )
}
