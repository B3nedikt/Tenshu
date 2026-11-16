// Home (native `HomeScreen`, fit-web-parity.md §2.3), laid out for a wide screen: today's active
// minutes as the hero ring ("N min to go"), the steps ring beside it; a stat strip whose cells open
// their metric; four 7-day cards (energy, steps, active minutes, distance), each its week's total and
// the change against the week before, opening its metric; and the latest workout.
//
// ⚠⚠ A failed read is a failure on screen, never zeros: a ring reading 0 is a claim about the user's
// day. One read serves the whole page: the last 14 days (the week, and the week before it).
import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import type { FitDay, FitGoals } from '@tenshu/sdk/fit'
import { deltaPercent, ringSweep, sum, windowDays } from '../lib/chart'
import { goalFor, windowFor, type Metric } from '../lib/dashboard'
import { useDays } from '../lib/days'
import { dateTimeText, clockText, distanceText } from '../lib/format'
import { metricValueText } from '../lib/metricText'
import { MAX_AGGREGATE_WINDOW_DAYS } from '../lib/bounds'
import { useLocalToday } from '../lib/today'
import { useGoals } from '../lib/live'
import { subscribeWorkoutChanges, workoutsPage } from '../lib/proxy'
import { queryKeys } from '../lib/queryKeys'
import { useUnits } from '../lib/units'
import { workoutTitle } from '../lib/workout'
import { Loading, ReadFailure, Section, Stopped } from './ui'
import { MetricTrend } from './MetricTrend'

// The week cards' order, as native Home's: energy first, distance last.
const WEEK_CARDS: readonly Metric[] = ['activeEnergyKj', 'steps', 'activeMinutes', 'distanceM']
// The stat strip under the rings: what the rings do not already show.
const STRIP: readonly Metric[] = ['steps', 'distanceM', 'activeEnergyKj']

export function Dashboard({ onOpenMetric, onOpenWorkout }: { onOpenMetric?: (metric: Metric) => void; onOpenWorkout?: (workoutId: string) => void } = {}) {
  const today = useLocalToday()
  // ⚠ Live: it rolls over at local midnight in a tab left open (`lib/today.ts`).
  const window = windowFor('week', today, MAX_AGGREGATE_WINDOW_DAYS)
  const days = useDays(window.from, window.to)
  const goalsLive = useGoals()

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
          goals={goalsLive.isPending ? 'pending' : goalsLive.data?.ok ? goalsLive.data.goals : 'failed'}
          goalsRetry={goalsLive.retry}
          goalsFailure={goalsLive.data && !goalsLive.data.ok ? goalsLive.data.error : null}
          goalsError={goalsLive.error}
          onOpenMetric={onOpenMetric}
        />
      )}
      <LatestWorkout onOpen={onOpenWorkout} />
    </div>
  )
}

type GoalsState = FitGoals | 'pending' | 'failed'

function Loaded({
  rows,
  goals,
  goalsRetry,
  goalsFailure,
  goalsError,
  onOpenMetric,
}: {
  rows: FitDay[]
  goals: GoalsState
  goalsRetry: () => void
  goalsFailure: { kind: string; message?: string } | null
  goalsError: unknown
  onOpenMetric?: (metric: Metric) => void
}) {
  const { t, i18n } = useTranslation()
  const units = useUnits()
  const week = rows.slice(-7)
  const previous = rows.slice(0, rows.length - 7)
  const today = week[week.length - 1]!
  const goalsOk = typeof goals === 'object' ? goals : null

  return (
    <div className="space-y-6">
      <section aria-labelledby="today-heading" className="rounded-xl border border-line bg-surface-2 px-5 py-5">
        <h2 id="today-heading" className="text-sm font-semibold text-text">
          {t('dashboard.today')}
        </h2>
        {goals === 'pending' ? (
          <Loading />
        ) : goals === 'failed' ? (
          // ⚠ Never native's 30 / 8 000 defaults: progress against a goal we could not read is a guess.
          <ReadFailure failure={goalsFailure} error={goalsError} onRetry={goalsRetry} />
        ) : (
          <div className="mt-4 flex flex-wrap items-center gap-x-10 gap-y-4">
            <Ring
              size={148}
              label={t('dashboard.activeMinutesGoal')}
              value={today.activeMinutes}
              goal={goals.activeMinutes}
              centre={String(today.activeMinutes)}
              centreUnit={t('home.minutes')}
              text={t('dashboard.ofGoal', { value: today.activeMinutes, goal: goals.activeMinutes })}
              caption={
                today.activeMinutes >= goals.activeMinutes
                  ? t('home.goalReached')
                  : t('home.toGo', { value: goals.activeMinutes - today.activeMinutes })
              }
              note={t('home.who')}
            />
            <Ring
              size={104}
              label={t('dashboard.stepsGoal')}
              value={today.steps}
              goal={goals.steps}
              centre={new Intl.NumberFormat(i18n.language, { notation: 'compact', maximumFractionDigits: 1 }).format(today.steps)}
              centreUnit={t('metric.steps')}
              text={t('dashboard.ofGoal', {
                value: new Intl.NumberFormat(i18n.language).format(today.steps),
                goal: new Intl.NumberFormat(i18n.language).format(goals.steps),
              })}
            />
          </div>
        )}
        {/* The strip: each cell opens its metric (native `StatStrip`). */}
        <dl className="mt-5 grid grid-cols-3 gap-2" data-testid="strip">
          {STRIP.map((m) => (
            <button
              key={m}
              type="button"
              onClick={() => onOpenMetric?.(m)}
              className="rounded-lg border border-line bg-surface px-3 py-2 text-left hover:bg-well focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
            >
              <dt className="text-xs text-text-2">{t(`metric.${m}`)}</dt>
              <dd className="mt-0.5 text-base font-semibold text-text">{metricValueText(t, i18n.language, m, today[m], units)}</dd>
            </button>
          ))}
        </dl>
      </section>

      <Section title={t('home.thisWeek')}>
        <div className="grid gap-4 min-[900px]:grid-cols-2">
          {WEEK_CARDS.map((m) => (
            <WeekCard key={m} metric={m} week={week} previous={previous} goal={goalFor(m, goalsOk)} onOpen={() => onOpenMetric?.(m)} />
          ))}
        </div>
      </Section>
    </div>
  )
}

function WeekCard({ metric, week, previous, goal, onOpen }: { metric: Metric; week: FitDay[]; previous: FitDay[]; goal: number; onOpen: () => void }) {
  const { t, i18n } = useTranslation()
  const units = useUnits()
  const total = sum(week.map((d) => d[metric]))
  // ⚠ Native Home's trend: this week (today still under way) against the complete week before.
  const delta = previous.length ? deltaPercent(total, sum(previous.map((d) => d[metric]))) : null
  return (
    <article aria-labelledby={`week-${metric}`} className="rounded-xl border border-line bg-surface-2 px-4 pb-2 pt-3" data-testid={`week-${metric}`}>
      <header className="flex items-start justify-between gap-2">
        <div>
          <h3 id={`week-${metric}`} className="text-sm font-semibold text-text">
            <button type="button" onClick={onOpen} className="hover:underline focus:outline-none focus-visible:underline">
              {t(`metric.${metric}`)}
            </button>
          </h3>
          <p className="text-xs text-text-2">{t('home.weekTotal', { value: metricValueText(t, i18n.language, metric, total, units) })}</p>
        </div>
        {delta !== null && (
          <span
            data-testid="delta"
            className={`rounded-full px-2 py-0.5 text-xs font-medium ${delta >= 0 ? 'bg-primary-soft text-text' : 'bg-well text-text-2'}`}
          >
            {t('home.delta', { value: `${delta > 0 ? '+' : ''}${delta}` })}
          </span>
        )}
      </header>
      <MetricTrend days={week} metric={metric} range="week" goal={goal} />
    </article>
  )
}

const RING_STROKE = 12

function Ring({
  size,
  label,
  value,
  goal,
  centre,
  centreUnit,
  text,
  caption,
  note,
}: {
  size: number
  label: string
  value: number
  goal: number
  centre: string
  centreUnit: string
  text: string
  caption?: string
  note?: string
}) {
  const r = size / 2 - RING_STROKE / 2 - 2
  const c = 2 * Math.PI * r
  const sweep = ringSweep(value, goal)
  return (
    <figure className="flex items-center gap-4">
      <div className="relative" style={{ width: size, height: size }}>
        <svg
          width={size}
          height={size}
          viewBox={`0 0 ${size} ${size}`}
          role="meter"
          aria-label={label}
          aria-valuemin={0}
          aria-valuemax={goal}
          aria-valuenow={Math.min(value, goal)}
          aria-valuetext={text}
        >
          <circle cx={size / 2} cy={size / 2} r={r} fill="none" strokeWidth={RING_STROKE} className="stroke-well" />
          <circle
            cx={size / 2}
            cy={size / 2}
            r={r}
            fill="none"
            strokeWidth={RING_STROKE}
            strokeLinecap="round"
            className="stroke-primary"
            strokeDasharray={`${sweep * c} ${c}`}
            transform={`rotate(-90 ${size / 2} ${size / 2})`}
          />
        </svg>
        <div aria-hidden className="absolute inset-0 flex flex-col items-center justify-center">
          <span className={`font-semibold tabular-nums text-text ${size > 120 ? 'text-3xl' : 'text-xl'}`}>{centre}</span>
          <span className="text-xs text-text-2">{centreUnit}</span>
        </div>
      </div>
      <figcaption>
        <p className="text-xs text-text-2">{label}</p>
        <p className="text-sm font-semibold text-text">{text}</p>
        {caption && <p className="mt-1 text-sm text-text">{caption}</p>}
        {note && <p className="mt-1 max-w-56 text-xs text-text-2">{note}</p>}
      </figcaption>
    </figure>
  )
}

// The latest workout (native Home's card): opens it. ⚠ Under History's query keys, so its change signal
// refreshes this too; and a change seen here re-reads it.
function LatestWorkout({ onOpen }: { onOpen?: (workoutId: string) => void }) {
  const { t, i18n } = useTranslation()
  const units = useUnits()
  const queryClient = useQueryClient()
  const query = useQuery({ queryKey: queryKeys.latestWorkout(), queryFn: () => workoutsPage(null, 1) })
  useEffect(() => {
    let first = true
    return subscribeWorkoutChanges(
      () => {
        if (first) {
          first = false
          return
        }
        void queryClient.invalidateQueries({ queryKey: queryKeys.latestWorkout() })
      },
      () => {},
    )
  }, [queryClient])

  if (query.isPending) return null
  if (query.error || !query.data) return <ReadFailure error={query.error} onRetry={() => void query.refetch()} />
  if (!query.data.ok) return <ReadFailure failure={query.data.error} onRetry={() => void query.refetch()} />
  const w = query.data.workouts[0]
  if (!w) return null
  return (
    <Section title={t('home.latest')}>
      <button
        type="button"
        onClick={() => onOpen?.(w.workoutId)}
        data-testid="latest-workout"
        className="flex w-full items-center justify-between gap-4 rounded-xl border border-line bg-surface-2 px-4 py-3 text-left hover:bg-well focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
      >
        <span>
          <span className="block text-sm font-semibold text-text">{workoutTitle(t, w)}</span>
          <span className="block text-xs text-text-2">{dateTimeText(i18n.language, w.startMs)}</span>
        </span>
        <span className="text-right text-sm tabular-nums text-text">
          {clockText(w.durationSec)}
          {w.distanceM !== null && <span className="block text-xs text-text-2">{distanceText(t, w.distanceM, units)}</span>}
        </span>
      </button>
    </Section>
  )
}

