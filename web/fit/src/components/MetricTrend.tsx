// One metric's days as a <TrendChart> (fit-web-parity.md §2.1): what the bars, axis labels and readouts
// SAY for each metric and range. Used by the dashboard now and by the Home cards and metric detail (F3).
//
// ⚠ Year is 12 MONTHLY bars, each the month's daily average — never 365 slivers, and never a sum (the
// current month is still under way and the first one is cut by the window).
import { useTranslation } from 'react-i18next'
import type { FitDay } from '@tenshu/sdk/fit'
import { dailyAverage } from '../lib/chart'
import type { Metric, Range } from '../lib/dashboard'
import { compactText, dateLabel, monthLabel } from '../lib/format'
import { metricValueText } from '../lib/metricText'
import { minutesText } from '../lib/format'
import { monthBuckets } from '../lib/trend'
import { useUnits, type MeasurementSystem } from '../lib/units'
import { TrendChart, type TrendPoint } from './TrendChart'
import type { TFunction } from 'i18next'

// ⚠ The axis is laid out in the units SHOWN (kcal, km or mi), not the stored ones (kJ, m): nice steps
// in kJ read as 39 / 78 kcal on the axis. Steps and minutes are shown as stored.
const KCAL_PER_KJ = 0.239006
const METRES_PER_MILE = 1609.344

function toShown(metric: Metric, value: number, units: MeasurementSystem): number {
  if (metric === 'activeEnergyKj') return value * KCAL_PER_KJ
  if (metric === 'distanceM') return value / (units === 'imperial' ? METRES_PER_MILE : 1000)
  return value
}

function tickText(t: TFunction, locale: string, metric: Metric, shown: number, units: MeasurementSystem): string {
  const n = new Intl.NumberFormat(locale, { maximumFractionDigits: 1 }).format(shown)
  switch (metric) {
    case 'steps':
      return compactText(locale, shown)
    case 'activeMinutes':
      return minutesText(t, Math.round(shown))
    case 'activeEnergyKj':
      return t('energy.kcal', { value: n })
    case 'distanceM':
      return units === 'imperial' ? t('trend.mi', { value: n }) : t('trend.km', { value: n })
  }
}

export function MetricTrend({
  days,
  metric,
  range,
  goal = 0,
}: {
  /** The range's days, oldest first, every date present (`windowDays`). */
  days: readonly FitDay[]
  metric: Metric
  range: Range
  /** The metric's daily goal (steps, active minutes); 0 = none. */
  goal?: number
}) {
  const { t, i18n } = useTranslation()
  const units = useUnits()
  const locale = i18n.language
  const say = (v: number) => metricValueText(t, locale, metric, v, units)

  const points: TrendPoint[] =
    range === 'year'
      ? monthBuckets(days, metric).map((b) => ({
          key: b.month,
          value: toShown(metric, b.average, units),
          label: monthLabel(locale, b.month, 'narrow'),
          readout: t('trend.monthReadout', { value: say(b.average), month: monthLabel(locale, b.month, 'long') }),
        }))
      : days.map((d) => ({
          key: d.date,
          value: toShown(metric, d[metric], units),
          label: dateLabel(locale, d.date, range === 'week' ? 'weekday' : 'day'),
          readout: `${say(d[metric])} · ${dateLabel(locale, d.date, 'readout')}`,
        }))

  return (
    <TrendChart
      points={points}
      ariaLabel={t(`trend.aria.${range}`, { metric: t(`metric.${metric}`) })}
      tickText={(v) => tickText(t, locale, metric, v, units)}
      goal={toShown(metric, goal, units)}
      goalLabel={goal > 0 ? t('trend.goal', { value: say(goal) }) : undefined}
      average={toShown(metric, dailyAverage(days.map((d) => d[metric])), units)}
      maxLabels={range === 'month' ? 8 : 13}
    />
  )
}
