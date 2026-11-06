import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { Photo } from '@tenshu/sdk/photos'
import { indexAtTop, layoutGrid, topOfIndex, visibleRows, type MonthSection } from '../lib/layout'
import { Tile } from './Tile'
import { Scrubber } from './Scrubber'

// jsdom has no layout: a container that measures 0 is given a desktop-sized box, so tests exercise
// the same virtualisation the browser does.
const FALLBACK_WIDTH = 960
const FALLBACK_HEIGHT = 800

export interface Selection {
  readonly active: boolean
  readonly has: (photo: Photo) => boolean
  readonly toggle: (photo: Photo) => void
}

/**
 * The capture-date grid, virtualised: only the rows within a screen of the viewport are mounted,
 * so a 10k-photo library mounts a few hundred tiles at most, and each tile holds bytes only while
 * it is on screen (`Tile`).
 *
 * [total] and [sections] come from the capture-time index (or a whole list, for an album); a cell
 * whose row is not loaded yet asks [need] for it, which is how the feed pages in.
 */
export function PhotoGrid({
  total,
  sections,
  photoAt,
  need,
  onOpen,
  selection,
  times,
  scrollToIndex,
  headers = true,
}: {
  total: number
  sections: readonly MonthSection[]
  photoAt: (index: number) => Photo | undefined
  need?: (index: number) => void
  onOpen: (index: number) => void
  selection?: Selection
  // The capture-time index, for the scrubber. Omitted = no scrubber.
  times?: readonly number[]
  // Bring this index on screen when it changes (the viewer's return).
  scrollToIndex?: number | null
  // Month headers (the feed); false for a list in the user's order (an album).
  headers?: boolean
}) {
  const { i18n } = useTranslation()
  const scroller = useRef<HTMLDivElement>(null)
  const [box, setBox] = useState({ width: FALLBACK_WIDTH, height: FALLBACK_HEIGHT })
  const [scrollTop, setScrollTop] = useState(0)

  useLayoutEffect(() => {
    const el = scroller.current
    if (!el) return
    const measure = () =>
      setBox({ width: el.clientWidth || FALLBACK_WIDTH, height: el.clientHeight || FALLBACK_HEIGHT })
    measure()
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(measure)
    observer.observe(el)
    return () => observer.disconnect()
  }, [])

  const layout = useMemo(() => layoutGrid(sections, box.width, undefined, headers), [sections, box.width, headers])
  const rows = visibleRows(layout, scrollTop, box.height)

  // The furthest cell drawn: asking for it pages the feed forward (keyset: always in order).
  const lastDrawn = rows.reduce((max, row) => (row.kind === 'cells' ? Math.max(max, row.end - 1) : max), -1)
  useEffect(() => {
    if (need && lastDrawn >= 0) need(Math.min(lastDrawn, total - 1))
  }, [need, lastDrawn, total])

  useEffect(() => {
    if (scrollToIndex === null || scrollToIndex === undefined) return
    const el = scroller.current
    if (!el) return
    const top = topOfIndex(layout, scrollToIndex)
    if (top < el.scrollTop || top > el.scrollTop + box.height - layout.cell) {
      el.scrollTop = top
      setScrollTop(top)
    }
    // Only when the requested index changes, not on every layout.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [scrollToIndex])

  const monthFormat = useMemo(
    () => new Intl.DateTimeFormat(i18n.language, { month: 'long', year: 'numeric' }),
    [i18n.language],
  )

  function seek(index: number) {
    const el = scroller.current
    if (!el) return
    const top = topOfIndex(layout, index)
    el.scrollTop = top
    setScrollTop(top)
  }

  return (
    <div className="relative min-h-0 flex-1">
      <div
        ref={scroller}
        data-testid="grid-scroller"
        onScroll={(e) => setScrollTop(e.currentTarget.scrollTop)}
        className="absolute inset-0 overflow-y-auto pr-6"
      >
        <div style={{ height: layout.height, position: 'relative' }}>
          {rows.map((row) =>
            row.kind === 'header' ? (
              <h2
                key={`h${row.section.key}:${row.section.startIndex}`}
                className="absolute left-0 right-0 flex items-end pb-2 text-sm font-semibold text-text"
                style={{ top: row.top, height: row.height }}
              >
                {monthFormat.format(row.section.firstMs)}
              </h2>
            ) : (
              <div
                key={`r${row.start}`}
                role="row"
                className="absolute left-0 flex gap-1"
                style={{ top: row.top, height: row.height }}
              >
                {Array.from({ length: row.end - row.start }, (_, k) => {
                  const index = row.start + k
                  const photo = photoAt(index)
                  return (
                    <Tile
                      key={photo?.path ?? `i${index}`}
                      photo={photo}
                      size={layout.cell}
                      selecting={selection?.active ?? false}
                      selected={photo ? (selection?.has(photo) ?? false) : false}
                      onActivate={() => {
                        if (!photo) return
                        if (selection?.active) selection.toggle(photo)
                        else onOpen(index)
                      }}
                    />
                  )
                })}
              </div>
            ),
          )}
        </div>
      </div>
      {times && times.length > 1 && (
        <Scrubber
          times={times}
          at={indexAtTop(layout, scrollTop)}
          trackHeight={box.height}
          onSeek={seek}
        />
      )}
    </div>
  )
}
