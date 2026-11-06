import { useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { indexAtFraction, thinToFit, trackFractions, yearMarks } from '../lib/layout'

const PILL_PX = 22

// The scrubber over the capture-time index (native PH-17): year pills spaced by how many photographs
// each year holds, a thumb at the current position, and a drag (or the keyboard) that seeks. A seek
// deep into the library pages the feed in between — keyset paging only walks forward.
export function Scrubber({
  times,
  at,
  trackHeight,
  onSeek,
}: {
  times: readonly number[]
  at: number
  trackHeight: number
  onSeek: (index: number) => void
}) {
  const { t, i18n } = useTranslation()
  const track = useRef<HTMLDivElement>(null)
  const [dragging, setDragging] = useState<number | null>(null)
  const total = times.length
  const current = dragging ?? at

  const pills = useMemo(() => {
    const fractions = trackFractions(yearMarks(times))
    return thinToFit(fractions, trackHeight > 0 ? PILL_PX / trackHeight : 0)
  }, [times, trackHeight])

  const bubble = useMemo(
    () => new Intl.DateTimeFormat(i18n.language, { month: 'long', year: 'numeric' }),
    [i18n.language],
  )

  function indexAt(event: ReactPointerEvent) {
    const rect = track.current?.getBoundingClientRect()
    if (!rect || rect.height <= 0) return current
    return indexAtFraction((event.clientY - rect.top) / rect.height, total)
  }

  function seek(index: number) {
    const clamped = Math.max(0, Math.min(total - 1, index))
    onSeek(clamped)
    return clamped
  }

  const fraction = total > 1 ? current / (total - 1) : 0
  const when = times[current]

  return (
    <div
      ref={track}
      role="slider"
      tabIndex={0}
      aria-label={t('feed.scrubber')}
      aria-orientation="vertical"
      aria-valuemin={0}
      aria-valuemax={total - 1}
      aria-valuenow={current}
      aria-valuetext={when !== undefined ? bubble.format(when) : undefined}
      className="absolute bottom-0 right-0 top-0 w-5 cursor-ns-resize touch-none select-none"
      onPointerDown={(e) => {
        e.currentTarget.setPointerCapture?.(e.pointerId)
        setDragging(seek(indexAt(e)))
      }}
      onPointerMove={(e) => {
        if (dragging !== null) setDragging(seek(indexAt(e)))
      }}
      onPointerUp={() => setDragging(null)}
      onPointerCancel={() => setDragging(null)}
      onKeyDown={(e) => {
        const step = e.key === 'PageDown' ? 100 : e.key === 'PageUp' ? -100 : e.key === 'ArrowDown' ? 10 : e.key === 'ArrowUp' ? -10 : 0
        if (e.key === 'Home') seek(0)
        else if (e.key === 'End') seek(total - 1)
        else if (step !== 0) seek(current + step)
        else return
        e.preventDefault()
      }}
    >
      <div className="absolute bottom-0 right-2 top-0 w-px bg-line" />
      {dragging !== null &&
        pills.map(({ mark, fraction: f }) => (
          <span
            key={mark.year}
            className="absolute right-6 -translate-y-1/2 rounded-full bg-surface-2 px-2 text-xs text-text-2 shadow"
            style={{ top: `${f * 100}%` }}
          >
            {mark.year}
          </span>
        ))}
      <span
        aria-hidden
        className="absolute right-0.5 h-8 w-3 -translate-y-1/2 rounded-full bg-primary"
        style={{ top: `${fraction * 100}%` }}
      />
      {dragging !== null && when !== undefined && (
        <span
          className="absolute right-8 -translate-y-1/2 whitespace-nowrap rounded-lg bg-surface-2 px-3 py-1 text-sm font-medium text-text shadow-lg"
          style={{ top: `${fraction * 100}%` }}
        >
          {bubble.format(when)}
        </span>
      )}
    </div>
  )
}
