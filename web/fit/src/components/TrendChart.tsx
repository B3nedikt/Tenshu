// The web's bar chart (fit-web-parity.md §2.1): native `TrendBars`, laid out for a wide screen and a
// pointer. Today's (the last) bar in full colour and the rest light; a dashed goal line and a quieter
// average line; gridlines with y labels; x labels sized to the count; and a readout — the bar under
// the pointer, or the one ←/→ walk to — in a pill above it.
//
// ⚠ Real pixel geometry from the measured width, not a stretched viewBox: strokes, text and the
// rounded bar caps stay crisp at any width. Own SVG, no chart library (the bundle budget).
import { useId, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react'
import { labelledSlots, niceAxis, slotAt } from '../lib/trend'
import { useMeasuredWidth } from '../lib/useMeasuredWidth'

export interface TrendPoint {
  /** Stable key (a date or a month). */
  readonly key: string
  readonly value: number
  /** The x-axis label (a weekday, a day number, a month initial). */
  readonly label: string
  /** The readout for this bar: "6,412 steps · Tue 11 Nov". */
  readonly readout: string
}

const HEIGHT = 200
const TOP = 36
const BOTTOM = 22
const LEFT = 56
const RIGHT = 6
const MAX_BAR = 24

export function TrendChart({
  points,
  ariaLabel,
  tickText,
  goal = 0,
  goalLabel,
  average = null,
  maxLabels = 12,
}: {
  points: readonly TrendPoint[]
  ariaLabel: string
  /** A y-axis value as text, in the metric's unit (`8k`, `30 min`, `5 km`). */
  tickText: (value: number) => string
  /** The day's goal; 0 = none (distance and energy have none). */
  goal?: number
  goalLabel?: string
  /** The period's daily average, drawn as a quiet line; null = none. */
  average?: number | null
  maxLabels?: number
}) {
  const [ref, width] = useMeasuredWidth<HTMLDivElement>()
  const [selected, setSelected] = useState<number | null>(null)
  const readoutId = useId()
  const svg = useRef<SVGSVGElement>(null)

  const n = points.length
  const plotW = Math.max(1, width - LEFT - RIGHT)
  const plotH = HEIGHT - TOP - BOTTOM
  const max = Math.max(goal, ...points.map((p) => p.value), 0)
  const axis = niceAxis(max * 1.05)
  const y = (v: number) => TOP + plotH - (Math.min(v, axis.top) / axis.top) * plotH
  const slot = plotW / Math.max(n, 1)
  const barW = Math.max(2, Math.min(MAX_BAR, slot * 0.7))
  const labels = labelledSlots(n, Math.min(maxLabels, Math.max(1, Math.floor(plotW / 28))))
  // The emphasised bar: the selected one, else today's (the last), as native.
  const emphasis = selected ?? n - 1
  const shown = selected !== null ? points[selected] : null

  function onPointerMove(event: PointerEvent<SVGSVGElement>) {
    const rect = svg.current?.getBoundingClientRect()
    if (!rect) return
    setSelected(slotAt(event.clientX - rect.left - LEFT, plotW, n))
  }

  function onKeyDown(event: KeyboardEvent<SVGSVGElement>) {
    if (n === 0) return
    let next: number | null
    if (event.key === 'ArrowRight') next = selected === null ? n - 1 : Math.min(n - 1, selected + 1)
    else if (event.key === 'ArrowLeft') next = selected === null ? n - 1 : Math.max(0, selected - 1)
    else if (event.key === 'Home') next = 0
    else if (event.key === 'End') next = n - 1
    else if (event.key === 'Escape') next = null
    else return
    event.preventDefault()
    setSelected(next)
  }

  const pillX = shown && selected !== null ? LEFT + selected * slot + slot / 2 : 0

  return (
    <figure ref={ref} className="relative w-full select-none">
      {shown && (
        <div
          aria-hidden
          data-testid="readout"
          className="pointer-events-none absolute top-0 z-10 -translate-x-1/2 whitespace-nowrap rounded-full border border-line bg-surface-2 px-3 py-1 text-xs font-medium text-text shadow-sm"
          style={{ left: Math.min(Math.max(pillX, 90), Math.max(90, width - 90)) }}
        >
          {shown.readout}
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
        onPointerLeave={() => setSelected(null)}
        onKeyDown={onKeyDown}
        onBlur={() => setSelected(null)}
        className="block focus:outline-none focus-visible:rounded-md focus-visible:ring-2 focus-visible:ring-primary"
      >
        {axis.ticks.map((v) => (
          <g key={v}>
            <line x1={LEFT} x2={width - RIGHT} y1={y(v)} y2={y(v)} strokeWidth={1} className="stroke-line" />
            <text x={LEFT - 8} y={y(v) + 4} textAnchor="end" fontSize={11} className="fill-text-2">
              {v === 0 ? '0' : tickText(v)}
            </text>
          </g>
        ))}
        {points.map((p, i) => {
          const h = Math.max(p.value > 0 ? 2 : 0, y(0) - y(p.value))
          const x = LEFT + i * slot + (slot - barW) / 2
          const r = Math.min(4, barW / 2, h)
          return (
            <path
              key={p.key}
              data-testid="bar"
              data-key={p.key}
              data-value={p.value}
              data-emphasis={i === emphasis || undefined}
              d={h <= 0 ? '' : `M${x},${y(0)}V${y(0) - h + r}Q${x},${y(0) - h} ${x + r},${y(0) - h}H${x + barW - r}Q${x + barW},${y(0) - h} ${x + barW},${y(0) - h + r}V${y(0)}Z`}
              className="fill-primary"
              fillOpacity={i === emphasis ? 1 : 0.3}
            />
          )
        })}
        {points.map((p, i) =>
          labels.has(i) ? (
            <text key={`l${p.key}`} x={LEFT + i * slot + slot / 2} y={HEIGHT - 6} textAnchor="middle" fontSize={11} className="fill-text-2">
              {p.label}
            </text>
          ) : null,
        )}
        {average !== null && n > 0 && (
          <line data-testid="average-line" x1={LEFT} x2={width - RIGHT} y1={y(average)} y2={y(average)} strokeWidth={1.5} className="stroke-primary" strokeOpacity={0.6} />
        )}
        {goal > 0 && (
          <g data-testid="goal-line">
            <line x1={LEFT} x2={width - RIGHT} y1={y(goal)} y2={y(goal)} strokeWidth={1.5} strokeDasharray="5 4" className="stroke-text-2" />
            {goalLabel && (
              <text x={width - RIGHT} y={y(goal) - 5} textAnchor="end" fontSize={11} className="fill-text-2">
                {goalLabel}
              </text>
            )}
          </g>
        )}
        {shown && selected !== null && (
          <line
            x1={pillX}
            x2={pillX}
            y1={TOP - 8}
            y2={y(shown.value)}
            strokeDasharray="3 3"
            strokeWidth={1}
            className="stroke-text-2"
          />
        )}
      </svg>
      {/* The readout for assistive tech: the pill is drawn for the eye, this is announced. */}
      <p id={readoutId} aria-live="polite" className="sr-only">
        {shown?.readout ?? ''}
      </p>
    </figure>
  )
}
