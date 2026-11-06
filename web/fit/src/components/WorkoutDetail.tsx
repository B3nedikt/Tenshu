// One workout (native `WorkoutDetail`): its stats, the HR curve, the route map, and delete.
//
// ⚠ `workout: null` is an ANSWER (no such workout — deleted here or on another device), rendered as
// such; `ok: false` is a failed read, rendered by `kind`. Neither is an empty screen.
// ⚠ No route → no map at all (an indoor or summary-only workout has nothing to draw). No HR series →
// no chart.
import { lazy, Suspense, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import type { FitWorkoutDetail } from '@tenshu/sdk/fit'
import { clockText, dateTimeText, distanceText, energyText, numberText, paceText } from '../lib/format'
import { refetchHead } from '../lib/history'
import { attempt } from '../lib/outcome'
import { deleteWorkout, workout } from '../lib/proxy'
import { queryKeys } from '../lib/queryKeys'
import { useUnits } from '../lib/units'
import { workoutTitle } from '../lib/workout'
import { HrChart } from './HrChart'
import { BackButton, Button, ConfirmDialog, Loading, ReadFailure, Section, Stat } from './ui'

// ⚠⚠ Its own chunk: the route map pulls `@tenshu/map-kit`'s loader, which must not ride with the
// dashboard or History (`bundleSplit.test.ts`).
const RouteMap = lazy(() => import('./RouteMap').then((m) => ({ default: m.RouteMap })))

export function WorkoutDetail({ workoutId, onBack }: { workoutId: string; onBack: () => void }) {
  const { t } = useTranslation()
  const query = useQuery({ queryKey: queryKeys.workout(workoutId), queryFn: () => workout(workoutId) })

  return (
    <div>
      <BackButton onBack={onBack} />
      {query.isPending ? (
        <Loading />
      ) : query.error || !query.data ? (
        <ReadFailure error={query.error} onRetry={() => void query.refetch()} />
      ) : !query.data.ok ? (
        <ReadFailure failure={query.data.error} onRetry={() => void query.refetch()} />
      ) : query.data.workout === null ? (
        <p role="status" className="text-sm text-text-2">
          {t('detail.gone')}
        </p>
      ) : (
        <Loaded detail={query.data.workout} onDeleted={onBack} />
      )}
    </div>
  )
}

function Loaded({ detail, onDeleted }: { detail: FitWorkoutDetail; onDeleted: () => void }) {
  const { t, i18n } = useTranslation()
  const units = useUnits()
  const queryClient = useQueryClient()
  const [confirming, setConfirming] = useState(false)
  const w = detail.workout
  const pace = w.paceSecPerKm !== null ? paceText(t, w.paceSecPerKm, units) : null

  return (
    <article>
      <header>
        <h2 className="text-xl font-semibold text-text">{workoutTitle(t, w)}</h2>
        <p className="mt-0.5 text-sm text-text-2">
          {dateTimeText(i18n.language, w.startMs)} · {t(`activity.${w.activityType}`)} · {t(`source.${w.source}`)}
        </p>
      </header>

      {/* ⚠ A null stat is UNKNOWN, never zero: it is left out, not shown as 0. */}
      <dl className="mt-4 grid grid-cols-2 gap-2 sm:grid-cols-4" data-testid="stats">
        <Stat label={t('detail.duration')} value={clockText(w.durationSec)} />
        {w.distanceM !== null && <Stat label={t('metric.distanceM')} value={distanceText(t, w.distanceM, units)} />}
        {pace !== null && <Stat label={t('detail.pace')} value={pace} />}
        {w.activeEnergyKj !== null && <Stat label={t('metric.activeEnergyKj')} value={energyText(t, w.activeEnergyKj)} />}
        {w.avgHr !== null && <Stat label={t('detail.avgHr')} value={t('detail.bpm', { value: w.avgHr })} />}
        {w.maxHr !== null && <Stat label={t('detail.maxHr')} value={t('detail.bpm', { value: w.maxHr })} />}
        {w.steps !== null && <Stat label={t('metric.steps')} value={numberText(i18n.language, w.steps)} />}
      </dl>

      {detail.heartRate.length >= 2 && (
        <Section title={t('detail.heartRate')}>
          <HrChart series={detail.heartRate} avgBpm={w.avgHr} />
        </Section>
      )}

      {detail.route.length >= 2 && (
        <Section title={t('detail.route')}>
          <Suspense fallback={<Loading />}>
            <RouteMap route={detail.route} />
          </Suspense>
        </Section>
      )}

      <div className="mt-8">
        <Button variant="danger" size="sm" onClick={() => setConfirming(true)}>
          {t('detail.delete')}
        </Button>
      </div>

      {confirming && (
        <ConfirmDialog
          title={t('detail.deleteTitle')}
          body={t('detail.deleteBody')}
          confirmLabel={t('detail.deleteConfirm')}
          danger
          onClose={() => setConfirming(false)}
          onConfirm={() =>
            attempt(
              () => deleteWorkout(w.workoutId),
              () => {
                refetchHead(queryClient)
                onDeleted()
              },
            )
          }
        />
      )}
    </article>
  )
}
