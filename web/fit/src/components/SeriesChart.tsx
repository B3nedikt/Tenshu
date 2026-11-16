// A time series against a workout's elapsed time (fit-web-parity.md §2.1–2.2): heart rate, and in F2
// elevation and pace. Native `HrChart`'s line over a tinted area and its dashed average, plus what a
// pointer affords: a time axis, a value axis, and a readout at the cursor.
//
// ⚠ The cursor can be CONTROLLED ([cursor] / [onCursor], a time in seconds), so several charts and
// the route map follow one pointer (F2). Uncontrolled, it keeps its own.
import { useId, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react'
import { clockText } from '../lib/format'
import { nearestIndex, rangeAxis } from '../lib/trend'
import { useMeasuredWidth } from '../lib/useMeasuredWidth'

export interface SeriesPoint {
  /** Seconds from the workout's start. */
  readonly t: number
  readonly v: number
}

// ⚠ Written out in full: Tailwind generates only the class names it finds in the source, so a
// `stroke-${tone}` built at runtime would never exist (the line drew nothing).
const TONES = {
  primary: { stroke: 'stroke-primary', fill: 'fill-primary' },
  danger: { stroke: 'stroke-danger', fill: 'fill-danger' },
  success: { stroke: 'stroke-success', fill: 'fill-success' },
} as const

const HEIGHT = 160
const TOP = 30
const BOTTOM = 22
const LEFT = 56
const RIGHT = 8

export function SeriesChart({
  series,
  ariaLabel,
  valueText,
  tickText = valueText,
  tone = 'primary',
  average = null,
  averageLabel,
  invert = false,
  cursor,
  onCursor,
  testId,
  duration: durationProp,
  gapSec = Infinity,
}: {
  series: readonly SeriesPoint[]
  ariaLabel: string
  /** A value as the readout says it ("142 bpm"). */
  valueText: (v: number) => string
  /** A value on the y axis (defaults to [valueText]). */
  tickText?: (v: number) => string
  tone?: 'primary' | 'danger' | 'success'
  average?: number | null
  averageLabel?: string
  /** Larger is drawn LOWER (pace: a faster, smaller min/km sits high). */
  invert?: boolean
  cursor?: number | null
  onCursor?: (t: number | null) => void
  testId?: string
  /** The time axis' end, seconds. ⚠ Pass the WORKOUT's duration to every chart that shares a cursor:
   *  each series' own last sample differs (HR outlasting GPS), and one moment would sit at different x. */
  duration?: number
  /** Samples further apart than this are a pause: the line breaks there instead of bridging it. */
  gapSec?: number
}) {
  const [ref, width] = useMeasuredWidth<HTMLDivElement>()
  const [own, setOwn] = useState<number | null>(null)
  const readoutId = useId()
  const svg = useRef<SVGSVGElement>(null)
  const at = cursor !== undefined ? cursor : own
  const setAt = (t: number | null) => {
    setOwn(t)
    onCursor?.(t)
  }

  const times = series.map((p) => p.t)
  const values = series.map((p) => p.v)
  const duration = Math.max(durationProp ?? 0, times[times.length - 1] ?? 1, 1)
  // ⚠ The average is ON the axis: pace's is distance / time, stops included, and can lie outside the
  // moving samples — it fell below the plot, label and all.
  const span = average !== null ? [...values, average] : values
  const axis = rangeAxis(Math.min(...span), Math.max(...span))
  const plotW = Math.max(1, width - LEFT - RIGHT)
  const plotH = HEIGHT - TOP - BOTTOM
  const x = (t: number) => LEFT + (t / duration) * plotW
  const yOf = (v: number) => {
    const f = (v - axis.lo) / Math.max(axis.hi - axis.lo, 1e-6)
    return TOP + (invert ? f : 1 - f) * plotH
  }
  // Runs between pauses: each its own line (and area), so a stop is a gap, not a straight bridge.
  const runs: SeriesPoint[][] = []
  for (const p of series) {
    const run = runs[runs.length - 1]
    if (run && p.t - run[run.length - 1]!.t <= gapSec) run.push(p)
    else runs.push([p])
  }
  const lineOf = (run: readonly SeriesPoint[]) => run.map((p) => `${x(p.t).toFixed(1)},${yOf(p.v).toFixed(1)}`).join(' ')
  const baseline = invert ? TOP : TOP + plotH
  const areaOf = (run: readonly SeriesPoint[]) =>
    `M${x(run[0]!.t)},${baseline} L${lineOf(run).replaceAll(' ', ' L')} L${x(run[run.length - 1]!.t)},${baseline} Z`
  const index = at !== null ? nearestIndex(times, at) : null
  const point = index !== null ? series[index]! : null
  const timeTicks = [0, duration / 2, duration]
  const { stroke, fill } = TONES[tone]

  function onPointerMove(event: PointerEvent<SVGSVGElement>) {
    const rect = svg.current?.getBoundingClientRect()
    if (!rect) return
    const px = event.clientX - rect.left - LEFT
    setAt(px < 0 || px > plotW ? null : (px / plotW) * duration)
  }

  function onKeyDown(event: KeyboardEvent<SVGSVGElement>) {
    if (series.length === 0) return
    const stepT = duration / 50
    let next: number | null
    if (event.key === 'ArrowRight') next = Math.min(duration, (at ?? -stepT) + stepT)
    else if (event.key === 'ArrowLeft') next = Math.max(0, (at ?? duration + stepT) - stepT)
    else if (event.key === 'Home') next = 0
    else if (event.key === 'End') next = duration
    else if (event.key === 'Escape') next = null
    else return
    event.preventDefault()
    setAt(next)
  }

  const readout = point ? `${valueText(point.v)} · ${clockText(point.t)}` : ''

  return (
    <figure ref={ref} className="relative w-full select-none" data-testid={testId}>
      {point && (
        <div
          aria-hidden
          data-testid="series-readout"
          className="pointer-events-none absolute top-0 z-10 -translate-x-1/2 whitespace-nowrap rounded-full border border-line bg-surface-2 px-3 py-0.5 text-xs font-medium text-text shadow-sm"
          style={{ left: Math.min(Math.max(x(point.t), 80), Math.max(80, width - 80)) }}
        >
          {readout}
        </div>
      )}
      <svg
        ref={svg}
        width={width}
        height={HEIGHT}
        role="img"
        aria-label={ariaLabel}
        aria-describedby={readoutId}
        tabIndex={0}
        onPointerMove={onPointerMove}
        onPointerLeave={() => setAt(null)}
        onKeyDown={onKeyDown}
        onBlur={() => setAt(null)}
        className="block focus:outline-none focus-visible:rounded-md focus-visible:ring-2 focus-visible:ring-primary"
      >
        {axis.ticks.map((v) => (
          <g key={v}>
            <line x1={LEFT} x2={width - RIGHT} y1={yOf(v)} y2={yOf(v)} strokeWidth={1} className="stroke-line" />
            <text x={LEFT - 8} y={yOf(v) + 4} textAnchor="end" fontSize={11} className="fill-text-2">
              {tickText(v)}
            </text>
          </g>
        ))}
        {timeTicks.map((t, i) => (
          <text key={i} x={x(t)} y={HEIGHT - 6} textAnchor={i === 0 ? 'start' : i === 2 ? 'end' : 'middle'} fontSize={11} className="fill-text-2">
            {clockText(t)}
          </text>
        ))}
        {/* No area under an inverted line (pace): it would shade the space ABOVE the curve. */}
        {!invert && runs.map((run, i) => <path key={`a${i}`} d={areaOf(run)} className={fill} fillOpacity={0.12} />)}
        {runs.map((run, i) => (
          <polyline
            key={`l${i}`}
            data-testid="series-line"
            points={lineOf(run)}
            fill="none"
            strokeWidth={2}
            strokeLinejoin="round"
            strokeLinecap="round"
            className={stroke}
          />
        ))}
        {average !== null && (
          <g data-testid="series-average">
            <line x1={LEFT} x2={width - RIGHT} y1={yOf(average)} y2={yOf(average)} strokeWidth={1.5} strokeDasharray="5 4" className="stroke-text-2" />
            {averageLabel && (
              <text x={width - RIGHT} y={yOf(average) - 5} textAnchor="end" fontSize={11} className="fill-text-2">
                {averageLabel}
              </text>
            )}
          </g>
        )}
        {point && (
          <g data-testid="series-cursor">
            <line x1={x(point.t)} x2={x(point.t)} y1={TOP - 6} y2={TOP + plotH} strokeWidth={1} strokeDasharray="3 3" className="stroke-text-2" />
            <circle cx={x(point.t)} cy={yOf(point.v)} r={4} strokeWidth={2} className={`${fill} stroke-surface-2`} />
          </g>
        )}
      </svg>
      <p id={readoutId} aria-live="polite" className="sr-only">
        {readout}
      </p>
    </figure>
  )
}
