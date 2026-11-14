import { useEffect, useLayoutEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { useTranslation } from 'react-i18next'
import type { Photo } from '@tenshu/sdk/photos'
import type { MenuEntry } from '@tenshu/web-tokens/ui'
import type { SelectionState } from '../lib/selection'
import { columnsFor, indexAtTop, layoutGrid, topOfIndex, visibleRows, type MonthSection } from '../lib/layout'
import { Tile } from './Tile'
import { Scrubber } from './Scrubber'

// jsdom has no layout: a container that measures 0 is given a desktop-sized box, so tests exercise
// the same virtualisation the browser does.
const FALLBACK_WIDTH = 960
const FALLBACK_HEIGHT = 800

const NO_MENU: readonly MenuEntry[] = []

/**
 * The capture-date grid, virtualised: only the rows within a screen of the viewport are mounted,
 * so a 10k-photo library mounts a few hundred tiles at most, and each tile holds bytes only while
 * it is on screen (`Tile`).
 *
 * [total] and [sections] come from the capture-time index (or a whole list, for an album); a cell
 * whose row is not loaded yet asks [need] for it, which is how the feed pages in.
 *
 * Pointer rules and keys (web-apps-layout.md §3.2, as Drive's list, L3): a click selects, ⌘/Ctrl
 * toggles, Shift extends in grid order, a double-click / Enter / Space opens, Escape clears, ⌘A
 * selects every LOADED photograph.
 * ⚠⚠ Keys belong to the tile that has FOCUS (`data-tile-index`), never to "the selection", and a
 * key already handled (`defaultPrevented`) or typed in a menu is skipped: the item menus are
 * portalled, but React still bubbles their keys up to this scroller.
 */
export function PhotoGrid({
  total,
  sections,
  photoAt,
  need,
  onOpen,
  selection,
  menuFor,
  onSelectAll,
  keysDisabled = false,
  times,
  scrollToIndex,
  onTopIndex,
  focusIndex,
  headers = true,
  tileTarget = 160,
  headerFormat = { month: 'long', year: 'numeric' },
}: {
  total: number
  sections: readonly MonthSection[]
  photoAt: (index: number) => Photo | undefined
  need?: (index: number) => void
  onOpen: (index: number) => void
  selection?: SelectionState
  // The item menu for a tile (right-click and ⋯).
  menuFor?: (photo: Photo, index: number) => readonly MenuEntry[]
  // ⌘A: select every loaded photograph.
  onSelectAll?: () => void
  // Something modal is up (the viewer, a picker): it owns the keys.
  keysDisabled?: boolean
  // The capture-time index, for the scrubber. Omitted = no scrubber.
  times?: readonly number[]
  // Bring this index on screen when it changes (the viewer's return).
  scrollToIndex?: number | null
  // The feed index at the top of the viewport as the user scrolls (a density switch keeps it).
  onTopIndex?: (index: number) => void
  // Bring this tile on screen and focus it (the viewer's close). A fresh object asks again.
  focusIndex?: { readonly index: number } | null
  // Month headers (the feed); false for a list in the user's order (an album).
  headers?: boolean
  // The tile size to aim at (px) and how a section header names its date — the Library's density.
  tileTarget?: number
  headerFormat?: Intl.DateTimeFormatOptions
}) {
  const { i18n } = useTranslation()
  const scroller = useRef<HTMLDivElement>(null)
  // `measured`: the box is the scroller's real one, not the fallback — a requested scroll waits for
  // it, or it is computed at 960 px columns and lands rows away once the real width reflows the grid.
  const [box, setBox] = useState({ width: FALLBACK_WIDTH, height: FALLBACK_HEIGHT, measured: false })
  const [scrollTop, setScrollTop] = useState(0)

  useLayoutEffect(() => {
    const el = scroller.current
    if (!el) return
    const measure = () =>
      setBox({ width: el.clientWidth || FALLBACK_WIDTH, height: el.clientHeight || FALLBACK_HEIGHT, measured: true })
    measure()
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(measure)
    observer.observe(el)
    return () => observer.disconnect()
  }, [])

  const layout = useMemo(
    () => layoutGrid(sections, box.width, columnsFor(box.width, tileTarget), headers),
    [sections, box.width, headers, tileTarget],
  )
  const rows = visibleRows(layout, scrollTop, box.height)

  // The furthest cell drawn: asking for it pages the feed forward (keyset: always in order).
  const lastDrawn = rows.reduce((max, row) => (row.kind === 'cells' ? Math.max(max, row.end - 1) : max), -1)
  useEffect(() => {
    if (need && lastDrawn >= 0) need(Math.min(lastDrawn, total - 1))
  }, [need, lastDrawn, total])

  useEffect(() => {
    if (scrollToIndex === null || scrollToIndex === undefined || !box.measured) return
    const el = scroller.current
    if (!el) return
    const top = topOfIndex(layout, scrollToIndex)
    if (top < el.scrollTop || top > el.scrollTop + box.height - layout.cell) {
      el.scrollTop = top
      setScrollTop(top)
    }
    // When the requested index changes, the box is first measured, or the density re-lays the grid
    // (tileTarget) — not on every layout, or a resize would yank the user back.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [scrollToIndex, box.measured, tileTarget])

  const topIndex = indexAtTop(layout, scrollTop)
  useEffect(() => {
    onTopIndex?.(topIndex)
  }, [onTopIndex, topIndex])

  // The viewer closed: its last photograph's tile is scrolled to, then focused once it is mounted
  // (the scroll mounts it a render later).
  const pendingFocus = useRef<number | null>(null)
  useEffect(() => {
    if (!focusIndex) return
    const el = scroller.current
    if (!el) return
    const top = topOfIndex(layout, focusIndex.index)
    if (top < el.scrollTop || top > el.scrollTop + box.height - layout.cell) {
      el.scrollTop = top
      setScrollTop(top)
    }
    pendingFocus.current = focusIndex.index
    // Only when a new request comes in, not on every layout.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focusIndex])
  useEffect(() => {
    const index = pendingFocus.current
    if (index === null) return
    const tile = scroller.current?.querySelector<HTMLElement>(`[data-tile-index="${index}"]`)
    if (!tile) return
    pendingFocus.current = null
    tile.focus({ preventScroll: true })
  })

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (keysDisabled || event.defaultPrevented || !selection) return
    const target = event.target as HTMLElement
    if (target.closest('[role="menu"]')) return
    const mod = event.metaKey || event.ctrlKey
    const raw = (target.closest('[data-tile-index]') as HTMLElement | null)?.dataset.tileIndex
    const index = raw === undefined ? null : Number.parseInt(raw, 10)
    const photo = index === null ? undefined : photoAt(index)
    if (event.key === 'Escape' && selection.size > 0) {
      event.preventDefault()
      selection.clear()
    } else if ((event.key === 'Enter' || event.key === ' ') && index !== null && photo) {
      // ⚠ preventDefault, or the focused button would also be clicked (and Space scroll the page).
      event.preventDefault()
      onOpen(index)
    } else if (mod && event.key.toLowerCase() === 'a' && onSelectAll) {
      event.preventDefault()
      onSelectAll()
    }
  }

  // Keyed by the options' content: a caller passing a fresh literal must not rebuild it per render.
  const formatKey = JSON.stringify(headerFormat)
  const monthFormat = useMemo(
    () => new Intl.DateTimeFormat(i18n.language, JSON.parse(formatKey) as Intl.DateTimeFormatOptions),
    [i18n.language, formatKey],
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
        onKeyDown={onKeyDown}
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
                      index={index}
                      size={layout.cell}
                      selecting={(selection?.size ?? 0) > 0}
                      selected={photo ? (selection?.has(photo) ?? false) : false}
                      menu={photo && menuFor ? menuFor(photo, index) : NO_MENU}
                      onClick={(event) => {
                        if (!photo) return
                        if (selection) selection.click(photo, index, event, photoAt)
                        else onOpen(index)
                      }}
                      onOpen={() => onOpen(index)}
                      onToggle={() => {
                        if (photo) selection?.toggle(photo, index)
                      }}
                      onContextSelect={() => {
                        if (photo) selection?.point(photo, index)
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
          at={topIndex}
          trackHeight={box.height}
          onSeek={seek}
        />
      )}
    </div>
  )
}
