// A metric's value as the charts and cards say it (steps, minutes, distance in the profile's units, kcal).
import type { TFunction } from 'i18next'
import type { Metric } from './dashboard'
import { distanceText, energyText, minutesText, numberText } from './format'
import type { MeasurementSystem } from './units'

export function metricValueText(t: TFunction, locale: string, metric: Metric, value: number, units: MeasurementSystem): string {
  switch (metric) {
    case 'steps':
      return t('trend.steps', { value: numberText(locale, Math.round(value)) })
    case 'activeMinutes':
      return minutesText(t, Math.round(value))
    case 'distanceM':
      return distanceText(t, value, units)
    case 'activeEnergyKj':
      return energyText(t, value)
  }
}
