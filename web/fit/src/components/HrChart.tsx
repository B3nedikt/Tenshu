// The heart-rate curve (native `HrChart` over `ChartMath.hrPolyline`): own SVG, no chart library.
import { useTranslation } from 'react-i18next'
import type { FitHrPoint } from '@tenshu/sdk/fit'
import { hrAverageY, hrPolyline } from '../lib/chart'

const W = 100
const H = 40

export function HrChart({ series, avgBpm }: { series: readonly FitHrPoint[]; avgBpm: number | null }) {
  const { t } = useTranslation()
  const points = hrPolyline(series)
  const min = Math.min(...series.map((p) => p.bpm))
  const max = Math.max(...series.map((p) => p.bpm))
  const avgY = avgBpm !== null ? hrAverageY(series, avgBpm) : null
  return (
    <figure>
      <svg
        viewBox={`0 0 ${W} ${H}`}
        preserveAspectRatio="none"
        className="h-36 w-full"
        role="img"
        aria-label={t('detail.hrChart', { min, max })}
        data-testid="hr-chart"
      >
        <polyline
          data-testid="hr-line"
          points={points.map((p) => `${(p.x * W).toFixed(2)},${(p.y * H).toFixed(2)}`).join(' ')}
          fill="none"
          strokeWidth="0.6"
          vectorEffect="non-scaling-stroke"
          className="stroke-danger"
        />
        {avgY !== null && (
          <line data-testid="hr-average" x1="0" x2={W} y1={avgY * H} y2={avgY * H} strokeDasharray="2 1" strokeWidth="0.3" className="stroke-text-2" />
        )}
      </svg>
      <figcaption className="mt-1 flex justify-between text-xs text-text-2">
        <span>{t('detail.hrMin', { value: min })}</span>
        {avgBpm !== null && <span>{t('detail.hrAvg', { value: avgBpm })}</span>}
        <span>{t('detail.hrMax', { value: max })}</span>
      </figcaption>
    </figure>
  )
}
