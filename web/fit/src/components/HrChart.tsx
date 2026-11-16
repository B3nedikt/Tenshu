// The heart-rate curve (native `HrChart`): the line over a tinted area, the dashed average, and — for
// a pointer — a time axis, a bpm axis and a readout at the cursor (`SeriesChart`, fit-web-parity.md
// §2.1). The cursor is shareable so F2's charts and the map can follow one pointer.
import { useTranslation } from 'react-i18next'
import type { FitHrPoint } from '@tenshu/sdk/fit'
import { SeriesChart } from './SeriesChart'

export function HrChart({
  series,
  avgBpm,
  cursor,
  onCursor,
  duration,
}: {
  series: readonly FitHrPoint[]
  avgBpm: number | null
  cursor?: number | null
  onCursor?: (t: number | null) => void
  /** The workout's duration: the shared time axis (`SeriesChart`). */
  duration?: number
}) {
  const { t } = useTranslation()
  const values = series.map((p) => p.bpm)
  const min = Math.min(...values)
  const max = Math.max(...values)
  return (
    <div>
      <SeriesChart
        testId="hr-chart"
        series={series.map((p) => ({ t: p.tSec, v: p.bpm }))}
        ariaLabel={t('detail.hrChart', { min, max })}
        valueText={(v) => t('detail.bpm', { value: Math.round(v) })}
        tickText={(v) => String(Math.round(v))}
        tone="danger"
        average={avgBpm}
        averageLabel={avgBpm !== null ? t('detail.hrAvg', { value: avgBpm }) : undefined}
        cursor={cursor}
        onCursor={onCursor}
        duration={duration}
      />
      <p className="mt-1 flex gap-4 text-xs text-text-2">
        <span>{t('detail.hrMin', { value: min })}</span>
        {avgBpm !== null && <span>{t('detail.hrAvg', { value: avgBpm })}</span>}
        <span>{t('detail.hrMax', { value: max })}</span>
      </p>
    </div>
  )
}
