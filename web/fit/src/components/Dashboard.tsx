// The dashboard (native Home + the category range switcher, fit-app.md §2 screen 4): today against the
// goals, then a selectable range of daily aggregates as bars with the goal line. The folds are the
// SDK's (`:fit-projection`'s `dailyAggregates`); this file only lays them out, with `ChartMath`'s
// geometry (`lib/chart.ts`).
//
// ⚠⚠ A failed read is a failure on screen, never zeros: the ring reading 0 is a claim about the
// user's day. A day the SUCCESSFUL read does not name is a real zero (`windowDays`).
import { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useLive } from '@tenshu/app-kit/live'
import type { DailyAggregatesResult, FitDay, FitGoals } from '@tenshu/sdk/fit'
import { barHeights, dailyAverage, deltaPercent, goalLineFraction, ringSweep, sum, windowDays } from '../lib/chart'
import { METRICS, RANGES, windowFor, type Metric, type Range } from '../lib/dashboard'
import { MAX_AGGREGATE_WINDOW_DAYS } from '../lib/bounds'
import { useLocalToday } from '../lib/today'
import { dayText, distanceText, energyText, minutesText, numberText } from '../lib/format'
import { useGoals } from '../lib/live'
import { dailyAggregates, subscribeDailyAggregates } from '../lib/proxy'
import { queryKeys } from '../lib/queryKeys'
import { useUnits } from '../lib/units'
import { Button, Loading, ReadFailure, Section, Stat, Stopped } from './ui'

export function Dashboard() {
  const { t } = useTranslation()
  const [range, setRange] = useState<Range>('week')
  const [metric, setMetric] = useState<Metric>('activeMinutes')
  // ⚠ Live: it rolls over at local midnight in a tab left open (`lib/today.ts`).
  const today = useLocalToday()
  const window = windowFor(range, today, MAX_AGGREGATE_WINDOW_DAYS)
  const goalsLive = useGoals()

  const seed = useCallback(() => dailyAggregates(window.from, window.to), [window.from, window.to])
  const subscribe = useCallback(
    (onValue: (v: DailyAggregatesResult) => void, onError: (e: Error) => void) =>
      subscribeDailyAggregates(window.from, window.to, onValue, onError),
    [window.from, window.to],
  )
  const days = useLive(queryKeys.days(window.from, window.to), seed, subscribe)

  return (
    <div>
      <Stopped live={days} />
      <Stopped live={goalsLive} />

      {days.isPending ? (
        <Loading />
      ) : days.error || !days.data ? (
        <ReadFailure error={days.error} onRetry={days.retry} />
      ) : !days.data.ok ? (
        <ReadFailure failure={days.data.error} onRetry={days.retry} />
      ) : (
        <Loaded
          rows={windowDays(days.data.days, window.from, window.to)}
          range={range}
          windowDays={window.days}
          withPrevious={window.withPrevious}
          metric={metric}
          onMetric={setMetric}
          goals={goalsLive.isPending ? 'pending' : goalsLive.data?.ok ? goalsLive.data.goals : 'failed'}
          goalsRetry={goalsLive.retry}
          goalsFailure={goalsLive.data && !goalsLive.data.ok ? goalsLive.data.error : null}
          goalsError={goalsLive.error}
        />
      )}

      <Section title={t('dashboard.range')}>
        <div role="group" aria-label={t('dashboard.range')} className="flex gap-1">
          {RANGES.map((r) => (
            <Button key={r} size="sm" variant={r === range ? 'primary' : 'outline'} aria-pressed={r === range} onClick={() => setRange(r)}>
              {t(`dashboard.ranges.${r}`)}
            </Button>
          ))}
        </div>
      </Section>
    </div>
  )
}

type GoalsState = FitGoals | 'pending' | 'failed'

function Loaded({
  rows,
  range,
  windowDays: count,
  withPrevious,
  metric,
  onMetric,
  goals,
  goalsRetry,
  goalsFailure,
  goalsError,
}: {
  rows: FitDay[]
  range: Range
  windowDays: number
  withPrevious: boolean
  metric: Metric
  onMetric: (m: Metric) => void
  goals: GoalsState
  goalsRetry: () => void
  goalsFailure: { kind: string; message?: string } | null
  goalsError: unknown
}) {
  const { t, i18n } = useTranslation()
  const units = useUnits()
  const current = rows.slice(-count)
  const previous = withPrevious ? rows.slice(0, rows.length - count) : []
  const today = current[current.length - 1]!

  // Wide windows: today and the range chart side by side (web-apps-layout.md §3.1, "a wide
  // dashboard"); `*:mt-0` drops the range Section's own top margin so both columns start level.
  return (
    <div className="min-[1200px]:grid min-[1200px]:grid-cols-[minmax(0,2fr)_minmax(0,3fr)] min-[1200px]:items-start min-[1200px]:gap-6 min-[1200px]:*:mt-0">
      <section aria-labelledby="today-heading" className="rounded-lg border border-line bg-surface-2 px-4 py-4">
        <h2 id="today-heading" className="text-sm font-semibold text-text">
          {t('dashboard.today')}
        </h2>
        {goals === 'pending' ? (
          <Loading />
        ) : goals === 'failed' ? (
          // ⚠ Never native's 30 / 8 000 defaults: progress against a goal we could not read is a guess.
          <ReadFailure failure={goalsFailure} error={goalsError} onRetry={goalsRetry} />
        ) : (
          <div className="mt-3 flex flex-wrap items-center gap-6">
            <Ring
              label={t('dashboard.activeMinutesGoal')}
              value={today.activeMinutes}
              goal={goals.activeMinutes}
              text={t('dashboard.ofGoal', { value: today.activeMinutes, goal: goals.activeMinutes })}
            />
            <Ring
              label={t('dashboard.stepsGoal')}
              value={today.steps}
              goal={goals.steps}
              text={t('dashboard.ofGoal', { value: numberText(i18n.language, today.steps), goal: numberText(i18n.language, goals.steps) })}
            />
          </div>
        )}
        <dl className="mt-4 grid grid-cols-2 gap-2 sm:grid-cols-4">
          <Stat label={t('metric.steps')} value={numberText(i18n.language, today.steps)} />
          <Stat label={t('metric.activeMinutes')} value={minutesText(t, today.activeMinutes)} />
          <Stat label={t('metric.distanceM')} value={distanceText(t, today.distanceM, units)} />
          <Stat label={t('metric.activeEnergyKj')} value={energyText(t, today.activeEnergyKj)} />
        </dl>
      </section>

      <Section title={t(`dashboard.rangeTitle.${range}`)}>
        <div role="group" aria-label={t('dashboard.metric')} className="mb-3 flex flex-wrap gap-1">
          {METRICS.map((m) => (
            <Button key={m} size="sm" variant={m === metric ? 'primary' : 'ghost'} aria-pressed={m === metric} onClick={() => onMetric(m)}>
              {t(`metric.${m}`)}
            </Button>
          ))}
        </div>
        <Summary rows={current} previous={previous} metric={metric} goals={typeof goals === 'object' ? goals : null} />
        <Bars rows={current} metric={metric} goals={typeof goals === 'object' ? goals : null} />
      </Section>
    </div>
  )
}

function goalFor(metric: Metric, goals: FitGoals | null): number {
  if (!goals) return 0
  return metric === 'steps' ? goals.steps : metric === 'activeMinutes' ? goals.activeMinutes : 0
}

function valueText(t: ReturnType<typeof useTranslation>['t'], locale: string, metric: Metric, value: number, units: ReturnType<typeof useUnits>) {
  switch (metric) {
    case 'steps':
      return numberText(locale, value)
    case 'activeMinutes':
      return minutesText(t, value)
    case 'distanceM':
      return distanceText(t, value, units)
    case 'activeEnergyKj':
      return energyText(t, value)
  }
}

function Summary({ rows, previous, metric, goals }: { rows: FitDay[]; previous: FitDay[]; metric: Metric; goals: FitGoals | null }) {
  const { t, i18n } = useTranslation()
  const units = useUnits()
  const values = rows.map((d) => d[metric])
  const total = sum(values)
  // ⚠ The current period ends with today, which is still under way, against a previous period that is
  // complete: exactly native Home's trend (`HomeViewModel`: `takeLast(7)` vs `dropLast(7).takeLast(7)`,
  // today included), kept so web and phone show the same figure.
  const delta = previous.length ? deltaPercent(total, sum(previous.map((d) => d[metric]))) : null
  const goal = goalFor(metric, goals)
  const met = goal > 0 ? values.filter((v) => v >= goal).length : null
  return (
    <dl className="mb-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
      <Stat label={t('dashboard.total')} value={valueText(t, i18n.language, metric, total, units)} />
      <Stat label={t('dashboard.average')} value={valueText(t, i18n.language, metric, dailyAverage(values), units)} />
      {delta !== null && <Stat label={t('dashboard.change')} value={t('dashboard.delta', { value: `${delta > 0 ? '+' : ''}${delta}` })} />}
      {met !== null && <Stat label={t('dashboard.goalDays')} value={t('dashboard.goalDaysValue', { met, days: rows.length })} />}
    </dl>
  )
}

const RING_R = 28
const RING_C = 2 * Math.PI * RING_R

function Ring({ label, value, goal, text }: { label: string; value: number; goal: number; text: string }) {
  const sweep = ringSweep(value, goal)
  return (
    <figure className="flex items-center gap-3">
      <svg
        width="72"
        height="72"
        viewBox="0 0 72 72"
        role="meter"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={goal}
        aria-valuenow={Math.min(value, goal)}
        aria-valuetext={text}
      >
        <circle cx="36" cy="36" r={RING_R} fill="none" strokeWidth="8" className="stroke-line" />
        <circle
          cx="36"
          cy="36"
          r={RING_R}
          fill="none"
          strokeWidth="8"
          strokeLinecap="round"
          className="stroke-primary"
          strokeDasharray={`${sweep * RING_C} ${RING_C}`}
          transform="rotate(-90 36 36)"
        />
      </svg>
      <figcaption>
        <p className="text-xs text-text-2">{label}</p>
        <p className="text-sm font-semibold text-text">{text}</p>
      </figcaption>
    </figure>
  )
}

function Bars({ rows, metric, goals }: { rows: FitDay[]; metric: Metric; goals: FitGoals | null }) {
  const { t, i18n } = useTranslation()
  const units = useUnits()
  const values = rows.map((d) => d[metric])
  const goal = goalFor(metric, goals)
  const heights = barHeights(values, goal)
  const goalY = goal > 0 ? 1 - goalLineFraction(values, goal) : null
  const width = 100 / Math.max(rows.length, 1)
  return (
    <figure>
      <svg viewBox="0 0 100 40" preserveAspectRatio="none" className="h-40 w-full" role="img" aria-label={t(`metric.${metric}`)}>
        {heights.map((h, i) => (
          <rect
            key={rows[i]!.date}
            data-testid="bar"
            data-date={rows[i]!.date}
            data-value={values[i]}
            x={i * width + width * 0.1}
            y={40 - h * 40}
            width={width * 0.8}
            height={h * 40}
            className="fill-primary"
          >
            <title>{`${dayText(i18n.language, rows[i]!.date)}: ${valueText(t, i18n.language, metric, values[i]!, units)}`}</title>
          </rect>
        ))}
        {goalY !== null && (
          <line data-testid="goal-line" x1="0" x2="100" y1={goalY * 40} y2={goalY * 40} strokeDasharray="2 1" strokeWidth="0.4" className="stroke-text-2" />
        )}
      </svg>
      <figcaption className="mt-1 flex justify-between text-xs text-text-2">
        <span>{dayText(i18n.language, rows[0]!.date, 'short')}</span>
        <span>{dayText(i18n.language, rows[rows.length - 1]!.date, 'short')}</span>
      </figcaption>
    </figure>
  )
}
