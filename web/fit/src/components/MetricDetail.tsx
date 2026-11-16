// One metric over a range (native `CategoryDetailScreen`'s Series mode, fit-web-parity.md §2.3): the
// Week / Month / Year switcher, the big daily average, the full-width chart, the period's figures and
// the day-by-day entries. Opened from Home's cards and strip; its place is the URL
// (`/metric#m=steps&r=month`), so a reload or a shared link keeps it.
import { useTranslation } from 'react-i18next'
import type { FitDay, FitGoals } from '@tenshu/sdk/fit'
import { dailyAverage, deltaPercent, sum, windowDays } from '../lib/chart'
import { goalFor, RANGES, windowFor, type Metric, type Range } from '../lib/dashboard'
import { useDays } from '../lib/days'
import { dateLabel } from '../lib/format'
import { metricValueText } from '../lib/metricText'
import { MAX_AGGREGATE_WINDOW_DAYS } from '../lib/bounds'
import { useLocalToday } from '../lib/today'
import { useGoals } from '../lib/live'
import { useUnits } from '../lib/units'
import { BackButton, Button, Loading, ReadFailure, Section, Stat, Stopped } from './ui'
import { MetricTrend } from './MetricTrend'

export function MetricDetail({
  metric,
  range,
  onRange,
  onBack,
}: {
  metric: Metric
  range: Range
  onRange: (range: Range) => void
  onBack: () => void
}) {
  const { t } = useTranslation()
  const today = useLocalToday()
  const window = windowFor(range, today, MAX_AGGREGATE_WINDOW_DAYS)
  const days = useDays(window.from, window.to)
  const goalsLive = useGoals()
  const goals = goalsLive.data?.ok ? goalsLive.data.goals : null

  return (
    <div>
      <BackButton onBack={onBack} />
      <header className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-xl font-semibold text-text">{t(`metric.${metric}`)}</h2>
        <div role="group" aria-label={t('dashboard.range')} className="flex gap-1">
          {RANGES.map((r) => (
            <Button key={r} size="sm" variant={r === range ? 'primary' : 'outline'} aria-pressed={r === range} onClick={() => onRange(r)}>
              {t(`dashboard.ranges.${r}`)}
            </Button>
          ))}
        </div>
      </header>
      <Stopped live={days} />
      {days.isPending ? (
        <Loading />
      ) : days.error || !days.data ? (
        <ReadFailure error={days.error} onRetry={days.retry} />
      ) : !days.data.ok ? (
        <ReadFailure failure={days.data.error} onRetry={days.retry} />
      ) : (
        <Loaded
          rows={windowDays(days.data.days, window.from, window.to)}
          count={window.days}
          withPrevious={window.withPrevious}
          metric={metric}
          range={range}
          goals={goals}
        />
      )}
    </div>
  )
}

function Loaded({
  rows,
  count,
  withPrevious,
  metric,
  range,
  goals,
}: {
  rows: FitDay[]
  count: number
  withPrevious: boolean
  metric: Metric
  range: Range
  goals: FitGoals | null
}) {
  const { t, i18n } = useTranslation()
  const units = useUnits()
  const current = rows.slice(-count)
  const previous = withPrevious ? rows.slice(0, rows.length - count) : []
  const values = current.map((d) => d[metric])
  const total = sum(values)
  const delta = previous.length ? deltaPercent(total, sum(previous.map((d) => d[metric]))) : null
  const goal = goalFor(metric, goals)
  const met = goal > 0 ? values.filter((v) => v >= goal).length : null
  const say = (v: number) => metricValueText(t, i18n.language, metric, v, units)

  return (
    <>
      <p className="mt-4 text-xs font-semibold uppercase tracking-wide text-text-2">{t('dashboard.average')}</p>
      <p className="text-3xl font-semibold tabular-nums text-text" data-testid="daily-average">
        {say(dailyAverage(values))}
      </p>
      <div className="mt-4 rounded-xl border border-line bg-surface-2 px-4 pb-2 pt-3">
        <MetricTrend days={current} metric={metric} range={range} goal={goal} />
      </div>
      <dl className="mt-4 grid grid-cols-2 gap-2 sm:grid-cols-3">
        <Stat label={t('dashboard.total')} value={say(total)} />
        {delta !== null && <Stat label={t('dashboard.change')} value={t('dashboard.delta', { value: `${delta > 0 ? '+' : ''}${delta}` })} />}
        {met !== null && <Stat label={t('dashboard.goalDays')} value={t('dashboard.goalDaysValue', { met, days: current.length })} />}
      </dl>
      {range !== 'year' && (
        <Section title={t('metricPage.entries')}>
          {/* Newest first, as native's entries list. */}
          <ul className="divide-y divide-line rounded-xl border border-line bg-surface-2" data-testid="entries">
            {[...current].reverse().map((d) => (
              <li key={d.date} className="flex justify-between px-4 py-2 text-sm">
                <span className="text-text-2">{dateLabel(i18n.language, d.date, 'readout')}</span>
                <span className={`tabular-nums ${goal > 0 && d[metric] >= goal ? 'font-semibold text-primary' : 'text-text'}`}>{say(d[metric])}</span>
              </li>
            ))}
          </ul>
        </Section>
      )}
    </>
  )
}
