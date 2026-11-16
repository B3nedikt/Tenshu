// One workout (native `WorkoutDetail`): its stats, the HR curve, the route map, and delete.
//
// ⚠ `workout: null` is an ANSWER (no such workout — deleted here or on another device), rendered as
// such; `ok: false` is a failed read, rendered by `kind`. Neither is an empty screen.
// ⚠ No route → no map at all (an indoor or summary-only workout has nothing to draw). No HR series →
// no chart.
import { lazy, Suspense, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import type { FitWorkoutDetail } from '@tenshu/sdk/fit'
import { clockText, dateTimeText, distanceText, elevationText, energyText, numberText, paceText } from '../lib/format'
import { ascentMetres, bestSplitIndex, elevationSeries, paceSeries, splits as splitsOf, type Split } from '../lib/streams'
import { refetchHead } from '../lib/history'
import { attempt } from '../lib/outcome'
import { deleteWorkout, workout } from '../lib/proxy'
import { queryKeys } from '../lib/queryKeys'
import { useUnits } from '../lib/units'
import { workoutTitle } from '../lib/workout'
import { HrChart } from './HrChart'
import { SeriesChart } from './SeriesChart'
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
  // What the route stream holds beyond the line (fit-web-parity.md §2.2), derived once per workout.
  const derived = useMemo(
    () => ({
      elevation: elevationSeries(detail.route),
      pace: paceSeries(detail.route),
      ascent: ascentMetres(detail.route),
      splits: splitsOf(detail.route, detail.heartRate, units === 'imperial'),
    }),
    [detail.route, detail.heartRate, units],
  )
  // ⚠ ONE cursor for every chart and the map: hovering heart rate marks pace, elevation and the place.
  const [cursor, setCursor] = useState<number | null>(null)
  const hasMap = detail.route.length >= 2

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
        {derived.ascent !== null && derived.ascent > 0 && <Stat label={t('detail.ascent')} value={elevationText(t, derived.ascent, units)} />}
      </dl>

      {/* Wide: the charts beside a map that stays in view, so the shared cursor is always visible on it. */}
      <div className={hasMap ? 'min-[1100px]:grid min-[1100px]:grid-cols-[minmax(0,3fr)_minmax(0,2fr)] min-[1100px]:items-start min-[1100px]:gap-6' : ''}>
        {hasMap && (
          <div className="min-[1100px]:sticky min-[1100px]:top-4 min-[1100px]:order-2">
            <Section title={t('detail.route')}>
              <Suspense fallback={<Loading />}>
                <RouteMap route={detail.route} cursor={cursor} />
              </Suspense>
            </Section>
          </div>
        )}
        <div className="min-w-0">
          {detail.heartRate.length >= 2 && (
            <Section title={t('detail.heartRate')}>
              <HrChart series={detail.heartRate} avgBpm={w.avgHr} cursor={cursor} onCursor={setCursor} duration={w.durationSec} />
            </Section>
          )}
          {derived.pace.length >= 2 && (
            <Section title={t('detail.paceChart')}>
              <SeriesChart
                testId="pace-chart"
                series={derived.pace}
                ariaLabel={t('detail.paceAria')}
                valueText={(v) => paceText(t, v, units) ?? ''}
                // The axis says m:ss only — "10:00 /km" does not fit the gutter, and the title names the unit.
                tickText={(v) => clockText(units === 'imperial' ? (v * 1609.344) / 1000 : v)}
                tone="primary"
                invert
                duration={w.durationSec}
                // A pause (no moving sample for longer than the 30 s window) breaks the line.
                gapSec={45}
                average={w.paceSecPerKm}
                averageLabel={pace !== null ? t('detail.avgPace', { value: pace }) : undefined}
                cursor={cursor}
                onCursor={setCursor}
              />
            </Section>
          )}
          {derived.elevation.length >= 2 && (
            <Section title={t('detail.elevation')}>
              <SeriesChart
                testId="elevation-chart"
                series={derived.elevation}
                ariaLabel={t('detail.elevationAria', {
                  min: elevationText(t, Math.min(...derived.elevation.map((p) => p.v)), units),
                  max: elevationText(t, Math.max(...derived.elevation.map((p) => p.v)), units),
                })}
                valueText={(v) => elevationText(t, v, units)}
                tone="success"
                duration={w.durationSec}
                cursor={cursor}
                onCursor={setCursor}
              />
            </Section>
          )}
          {derived.splits.length >= 1 && <Splits splits={derived.splits} imperial={units === 'imperial'} />}
        </div>
      </div>

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

// Native's lap table (`WorkoutDetailScreen.LapsSection`): #, distance, time, pace, average HR; the
// fastest FULL split highlighted, a trailing part-split marked "N·".
function Splits({ splits, imperial }: { splits: readonly Split[]; imperial: boolean }) {
  const { t } = useTranslation()
  const units = useUnits()
  const best = bestSplitIndex(splits)
  const unitM = imperial ? 1609.344 : 1000
  return (
    <Section title={t('detail.splits')}>
      <table className="w-full overflow-hidden rounded-lg border border-line text-sm tabular-nums" data-testid="splits">
        <thead className="bg-surface-2 text-xs text-text-2">
          <tr>
            <th className="px-3 py-2 text-left font-medium">#</th>
            <th className="px-3 py-2 text-right font-medium">{t(imperial ? 'detail.splitMi' : 'detail.splitKm')}</th>
            <th className="px-3 py-2 text-right font-medium">{t('detail.splitTime')}</th>
            <th className="px-3 py-2 text-right font-medium">{t('detail.splitPace')}</th>
            <th className="px-3 py-2 text-right font-medium">{t('detail.splitHr')}</th>
          </tr>
        </thead>
        <tbody>
          {splits.map((s) => {
            const isBest = s.index === best
            return (
              <tr key={s.index} className="border-t border-line" data-best={isBest || undefined}>
                <td className="px-3 py-1.5 text-text-2">{s.partial ? `${s.index}·` : s.index}</td>
                <td className="px-3 py-1.5 text-right">{(s.distanceM / unitM).toFixed(2)}</td>
                <td className="px-3 py-1.5 text-right">{clockText(s.durationSec)}</td>
                <td className={`px-3 py-1.5 text-right ${isBest ? 'font-semibold text-primary' : ''}`}>
                  {paceText(t, s.paceSecPerKm, units)}
                  {isBest && <span className="sr-only"> ({t('detail.splitBest')})</span>}
                </td>
                <td className="px-3 py-1.5 text-right text-text-2">{s.avgHr ?? '–'}</td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </Section>
  )
}

