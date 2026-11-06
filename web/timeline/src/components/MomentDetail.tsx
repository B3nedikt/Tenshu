// One moment. A snapshot read (`timeline.momentDetail`), which looks past reconciliation, so a
// moment opened from a trip still resolves. ⚠ `moment: null` is an answer ("not found");
// `ok: false` is a failed read — the two render differently.
import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import type { TimelineDayMoment } from '@tenshu/sdk/timeline'
import { momentDetail } from '../lib/proxy'
import { queryKeys } from '../lib/queryKeys'
import {
  activityTypeText,
  dateTimeText,
  distanceText,
  durationText,
  kindText,
  momentPlaceLabel,
  momentTitle,
  movementModeText,
} from '../lib/format'
import { useUnits } from '../lib/units'
import type { Open } from '../lib/nav'
import { routeLegs } from '../lib/route'
import { RouteMap } from './RouteMap'
import { BackButton, Button, Loading, ReadFailure } from './ui'

export function MomentDetail({ momentId, onBack, onOpen }: { momentId: string; onBack: () => void; onOpen: Open }) {
  const { t } = useTranslation()
  const query = useQuery({ queryKey: queryKeys.moment(momentId), queryFn: () => momentDetail(momentId) })

  return (
    <div>
      <BackButton onBack={onBack} />
      {query.isPending ? (
        <Loading />
      ) : query.isError ? (
        <ReadFailure error={query.error} onRetry={() => void query.refetch()} />
      ) : !query.data.ok ? (
        <ReadFailure failure={query.data.error} onRetry={() => void query.refetch()} />
      ) : query.data.moment === null ? (
        <p className="text-sm text-text-2">{t('moment.notFound')}</p>
      ) : (
        <MomentBody moment={query.data.moment} onOpen={onOpen} />
      )}
    </div>
  )
}

function MomentBody({ moment, onOpen }: { moment: TimelineDayMoment; onOpen: Open }) {
  const { t, i18n } = useTranslation()
  const units = useUnits()
  const place = momentPlaceLabel(t, moment)
  const duration = durationText(t, moment.durationSec)
  const activity = activityTypeText(t, moment.activityType)
  const placeId = moment.placeId
  // Stable while the moment is (the query shares structure): the map re-pushes its overlay on a new one.
  const route = useMemo(() => routeLegs([moment]), [moment])

  const facts: [string, string][] = [[t('moment.when'), dateTimeText(i18n.language, moment.timestampMs)]]
  if (duration) facts.push([t('moment.duration'), duration])
  if (moment.kind === 'Movement') {
    facts.push([t('moment.mode'), movementModeText(t, moment.movementMode)])
    if (moment.distanceM !== null) facts.push([t('moment.distance'), distanceText(t, moment.distanceM, units)])
  }
  if (activity) facts.push([t('moment.activity'), activity])

  return (
    <article>
      <p className="text-xs font-semibold uppercase tracking-wide text-primary">{kindText(t, moment.kind)}</p>
      <h2 className="mt-1 text-xl font-semibold text-text">{momentTitle(t, moment)}</h2>
      {place?.secondary && <p className="mt-0.5 text-sm text-text-2">{place.secondary}</p>}

      {/* A movement's hero is its route (native M5): every trace behind the row, as ONE leg. */}
      {moment.kind === 'Movement' && (
        <div className="mt-4 empty:hidden">
          <RouteMap legs={route} />
        </div>
      )}

      <dl className="mt-5 grid grid-cols-[auto_1fr] gap-x-6 gap-y-2 text-sm">
        {facts.map(([term, value]) => (
          <div key={term} className="contents">
            <dt className="text-text-2">{term}</dt>
            <dd className="text-text">{value}</dd>
          </div>
        ))}
      </dl>

      {!moment.subjectResolved && moment.subjectKind !== 'none' && (
        <p className="mt-4 text-sm text-text-2">{t('moment.subjectPending')}</p>
      )}

      {placeId && (
        <Button className="mt-5" variant="outline" size="sm" onClick={() => onOpen({ kind: 'place', id: placeId })}>
          {t('moment.openPlace')}
        </Button>
      )}
    </article>
  )
}
