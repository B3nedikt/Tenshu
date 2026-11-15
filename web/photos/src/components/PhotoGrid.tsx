import { useEffect, useLayoutEffect, useMemo, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react'
import { useTranslation } from 'react-i18next'
import type { Photo } from '@tenshu/sdk/photos'
import type { MenuEntry } from '@tenshu/web-tokens/ui'
import type { SelectionState } from '../lib/selection'
import { cellRects, columnsAt, columnsFor, indexAtTop, layoutGrid, marqueeHits, topOfIndex, visibleRows, type MonthSection } from '../lib/layout'
import { Tile } from './Tile'
import { DateBubble } from './DateBubble'

// jsdom has no layout: a container that measures 0 is given a desktop-sized box, so tests exercise
// the same virtualisation the browser does.
const FALLBACK_WIDTH = 960
const FALLBACK_HEIGHT = 800

const NO_MENU: readonly MenuEntry[] = []

// The marquee (rubber band): a press that moves less than this is a click on empty space (clear).
const DRAG_PX = 4
// While a drag's pointer is this close to the scroller's top or bottom edge, the grid scrolls…
const EDGE_PX = 40
// …by up to this many px a frame, faster the nearer the edge.
const EDGE_SPEED = 24
// Where a press may NOT start a marquee: a tile (its ⋯ and touch circle too), a header's link, any
// control, and anything in a menu or dialog (portalled, but React still bubbles their events here).
const NOT_EMPTY = '[data-tile], a, button, input, select, textarea, [role="menu"], [role="dialog"]'

// What a press needs from its event: a React pointer event and a native one (the content pane's
// listener, below) both have it.
type Press = Pick<
  globalThis.PointerEvent,
  'target' | 'button' | 'isPrimary' | 'pointerType' | 'pointerId' | 'clientX' | 'clientY' | 'metaKey' | 'ctrlKey' | 'shiftKey' | 'preventDefault'
>

interface Marquee {
  readonly pointerId: number
  // The press, in content coordinates (scroll included), and the pointer's last client position.
  readonly x0: number
  readonly y0: number
  clientX: number
  clientY: number
  // ⌘/Ctrl or Shift held at the press: the band adds to [base]; else it replaces the selection.
  readonly base: readonly Photo[]
  dragging: boolean
  // The pointer has been level with the scroller (between its top and bottom). ⚠ Until then — a
  // press above or below the grid, in the pane — its height is pinned to the grid's edge, or a
  // jitter would read as a drag out of the viewport and the edge scroll would run away.
  level: boolean
}

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
  onTogglePane,
  keysDisabled = false,
  times,
  scrollToIndex,
  onTopIndex,
  focusIndex,
  headers = true,
  tileTarget = 160,
  headerFormat = { month: 'long', year: 'numeric' },
  mosaic = false,
  fit = false,
  columns,
  onColumnsDrawn,
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
  // ⌘I: open or close the app's Info pane.
  onTogglePane?: () => void
  // Something modal is up (the viewer, a picker): it owns the keys.
  keysDisabled?: boolean
  // The capture-time index, for the date bubble beside the scrollbar. Omitted = no bubble.
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
  // Some full lines drawn as 2×2 features (native's Mosaic, §4.9).
  mosaic?: boolean
  // The whole photograph inside its square, not the square cropped from it (iCloud's aspect mode).
  fit?: boolean
  // Photos per row from the size slider (§4.10); omitted = sized by [tileTarget].
  columns?: number
  // The columns actually drawn (fewer than asked in a narrow window, whole blocks with mosaic).
  onColumnsDrawn?: (columns: number) => void
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

  // ⚠ The browser's own scrollbar is the position indicator (§4.9): `clientWidth` already excludes
  // a classic one, and an overlay one (macOS) floats over the last column's edge as in iCloud.
  const width = Math.max(1, box.width)
  const layout = useMemo(
    () =>
      layoutGrid(
        sections,
        width,
        columns !== undefined ? columnsAt(width, columns, mosaic) : columnsFor(width, tileTarget, mosaic),
        headers,
        mosaic,
      ),
    [sections, width, headers, tileTarget, mosaic, columns],
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
  // The photograph at the top before this render — read by the mosaic switch below, before the
  // passive effect moves it on to the re-laid grid's.
  const shownTop = useRef(topIndex)
  useEffect(() => {
    shownTop.current = topIndex
    onTopIndex?.(topIndex)
  }, [onTopIndex, topIndex])

  // ⚠ When the DRAWN arrangement changes — the mosaic switch, a slider step, a resize across a column
  // count — every line is re-laid: the same photograph stays at the top, as a density switch keeps its
  // place through the URL. ⚠ Keyed by what is drawn, not what was asked: a slider step that draws the
  // same grid (mosaic rounds to whole blocks; a narrow window caps the count) must not move the
  // scroll. ⚠ Not before the box is measured: the first real width is the scroll request's job.
  const arrangement = `${mosaic}:${layout.columns}`
  const arrangementWas = useRef<string | null>(null)
  useEffect(() => {
    onColumnsDrawn?.(layout.columns)
  }, [onColumnsDrawn, layout.columns])
  useLayoutEffect(() => {
    if (!box.measured) return
    const was = arrangementWas.current
    arrangementWas.current = arrangement
    if (was === null || was === arrangement) return
    const el = scroller.current
    if (!el) return
    const top = topOfIndex(layout, shownTop.current)
    el.scrollTop = top
    setScrollTop(top)
  }, [arrangement, layout, box.measured])

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
    } else if (event.key === ' ' && event.shiftKey && index !== null && photo) {
      // The keyboard's ⌘-click: Shift+Space adds the focused photo to the selection, or takes it out
      // (the check circles are touch-only, so this is how a keyboard picks several).
      event.preventDefault()
      selection.toggle(photo, index)
    } else if ((event.key === 'Enter' || event.key === ' ') && index !== null && photo) {
      // ⚠ preventDefault, or the focused button would also be clicked (and Space scroll the page).
      event.preventDefault()
      onOpen(index)
    } else if (mod && event.key.toLowerCase() === 'a' && onSelectAll) {
      event.preventDefault()
      onSelectAll()
    } else if (mod && event.key.toLowerCase() === 'i' && onTogglePane) {
      event.preventDefault()
      onTogglePane()
    }
  }

  // ── the marquee ───────────────────────────────────────────────────────────────────────────────
  // ⚠ Hits come from the layout's geometry (`marqueeHits`), never from measuring tiles: the grid is
  // virtualised, so a band dragged (or auto-scrolled) past the mounted rows still selects what it
  // covers — every index whose photograph is LOADED (`photoAt`); a row not paged in yet is skipped.
  const marquee = useRef<Marquee | null>(null)
  const [band, setBand] = useState<{ x: number; y: number; width: number; height: number } | null>(null)
  const frame = useRef<number | null>(null)

  // The pointer's client position → content coordinates (the rows' own: x from the rows' left edge,
  // y from the top of the scrolled content).
  function contentPoint(clientX: number, clientY: number): { x: number; y: number } {
    const el = scroller.current!
    const rect = el.getBoundingClientRect()
    return { x: clientX - rect.left + el.scrollLeft, y: clientY - rect.top + el.scrollTop }
  }

  function applyMarquee() {
    const m = marquee.current
    if (!m || !selection) return
    const raw = contentPoint(m.clientX, m.clientY)
    // Clamped to the content: a pointer dragged onto the Info pane or below the last row must not
    // grow the band past it (it would give the grid sideways overflow and a scrollbar).
    const el = scroller.current
    const p = {
      x: Math.min(Math.max(0, raw.x), Math.min(width, el?.clientWidth || width)),
      y: Math.min(Math.max(0, raw.y), layout.height),
    }
    if (!m.dragging) {
      if (Math.hypot(p.x - m.x0, p.y - m.y0) < DRAG_PX) return
      m.dragging = true
    }
    const rect = { left: Math.min(m.x0, p.x), top: Math.min(m.y0, p.y), right: Math.max(m.x0, p.x), bottom: Math.max(m.y0, p.y) }
    setBand({ x: rect.left, y: rect.top, width: rect.right - rect.left, height: rect.bottom - rect.top })
    const picked = new Map(m.base.map((photo) => [photo.path, photo]))
    let first: { index: number; path: string } | null = null
    for (const index of marqueeHits(layout, rect)) {
      const photo = photoAt(index)
      if (!photo) continue
      picked.set(photo.path, photo)
      first ??= { index, path: photo.path }
    }
    // The band's first photo becomes the anchor: a Shift-click afterwards extends from it (Finder).
    selection.replace([...picked.values()], first)
  }

  // While a drag is on, a pointer near the top or bottom edge scrolls the grid, and the band (and
  // the selection) follow the content under it.
  function edgeScroll() {
    frame.current = null
    const m = marquee.current
    const el = scroller.current
    if (!m || !el || !m.dragging || !m.level) return
    const rect = el.getBoundingClientRect()
    const height = el.clientHeight || box.height
    const fromTop = m.clientY - rect.top
    const fromBottom = rect.top + height - m.clientY
    let delta = 0
    if (fromTop < EDGE_PX) delta = -Math.ceil(EDGE_SPEED * (1 - Math.max(0, fromTop) / EDGE_PX))
    else if (fromBottom < EDGE_PX) delta = Math.ceil(EDGE_SPEED * (1 - Math.max(0, fromBottom) / EDGE_PX))
    if (delta !== 0) {
      const max = Math.max(0, layout.height - height)
      const next = Math.min(max, Math.max(0, el.scrollTop + delta))
      if (next !== el.scrollTop) {
        el.scrollTop = next
        setScrollTop(next)
        applyMarquee()
      }
    }
    scheduleEdgeScroll()
  }
  // The frame loop calls the LATEST render's `edgeScroll` (its layout, its `photoAt` — pages keep
  // arriving while it scrolls), never the closure of the render that started the drag.
  const tick = useRef<() => void>(() => undefined)
  useLayoutEffect(() => {
    tick.current = edgeScroll
  })
  function scheduleEdgeScroll() {
    if (frame.current === null && typeof requestAnimationFrame === 'function')
      frame.current = requestAnimationFrame(() => tick.current())
  }

  function endMarquee() {
    marquee.current = null
    setBand(null)
    if (frame.current !== null && typeof cancelAnimationFrame === 'function') cancelAnimationFrame(frame.current)
    frame.current = null
  }
  useEffect(() => endMarquee, [])
  // Pages arriving under a band that holds still are selected as they land, not only on the next move.
  useEffect(() => {
    if (marquee.current?.dragging) applyMarquee()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- re-run when rows load, nothing else
  }, [photoAt])

  // A band starts on the grid's empty space, or — as in iCloud Photos — anywhere empty in the content
  // pane around it (its padding, the gap under the toolbar): [inPane] presses, from the pane's own
  // listener. Such a press is pulled onto the grid's visible edge.
  function startBand(event: Press, inPane: boolean) {
    const el = scroller.current
    if (!el || !selection || keysDisabled) return
    if (event.button !== 0 || event.isPrimary === false) return
    // A finger on empty space scrolls the grid (touch selects with the circles): no band.
    if (event.pointerType === 'touch') return
    const target = event.target as Element
    // DOM containment, not React's tree: a portalled menu's press bubbles here but is not inside.
    if (!(target instanceof Element) || target.closest(NOT_EMPTY)) return
    if (inPane === el.contains(target)) return
    const rect = el.getBoundingClientRect()
    const visibleWidth = el.clientWidth || box.width
    // A press on the scrollbar (right of the content box) scrolls; it does not band.
    if (!inPane && event.clientX - rect.left >= visibleWidth) return
    // Nor on another scrollbar in the pane (the toolbar's, on a narrow window): it is the scroller's.
    if (inPane && onScrollbar(target, event.clientX, event.clientY)) return
    // No text selection by the drag — and the grid takes focus itself (the press's own focus move is
    // prevented), so Escape, ⌘A, ⌘I and Shift+Space work right after a band.
    event.preventDefault()
    el.focus({ preventScroll: true })
    const p = contentPoint(
      Math.min(Math.max(event.clientX, rect.left), rect.left + Math.min(width, visibleWidth)),
      Math.min(Math.max(event.clientY, rect.top), rect.top + (el.clientHeight || box.height)),
    )
    const add = event.metaKey || event.ctrlKey || event.shiftKey
    marquee.current = {
      pointerId: event.pointerId,
      x0: p.x,
      y0: p.y,
      clientX: event.clientX,
      clientY: clampY(event.clientY, rect, el.clientHeight || box.height),
      base: add ? selection.photos : [],
      dragging: false,
      level: event.clientY >= rect.top && event.clientY <= rect.top + (el.clientHeight || box.height),
    }
    try {
      el.setPointerCapture?.(event.pointerId)
    } catch {
      // jsdom, or a pointer already gone: the scroller still gets the moves it is over.
    }
  }

  // The content pane (`AppLayout`'s <main>) hands its presses outside the scroller to [startBand];
  // presses on the scroller come through React. The listener calls the LATEST render's [startBand].
  const paneStart = useRef<(event: globalThis.PointerEvent) => void>(() => undefined)
  useLayoutEffect(() => {
    paneStart.current = (event) => startBand(event, true)
  })
  useEffect(() => {
    const pane = scroller.current?.closest('main')
    if (!pane) return
    const listener = (event: globalThis.PointerEvent) => paneStart.current(event)
    pane.addEventListener('pointerdown', listener)
    return () => pane.removeEventListener('pointerdown', listener)
  }, [])

  function onPointerMove(event: PointerEvent<HTMLDivElement>) {
    const m = marquee.current
    if (!m || event.pointerId !== m.pointerId) return
    const el = scroller.current
    const rect = el?.getBoundingClientRect()
    const height = el?.clientHeight || box.height
    if (rect && !m.level && event.clientY >= rect.top && event.clientY <= rect.top + height) m.level = true
    m.clientX = event.clientX
    m.clientY = m.level || !rect ? event.clientY : clampY(event.clientY, rect, height)
    const wasDragging = m.dragging
    applyMarquee()
    if (!wasDragging && m.dragging) scheduleEdgeScroll()
  }

  function onPointerUp(event: PointerEvent<HTMLDivElement>) {
    const m = marquee.current
    if (!m || event.pointerId !== m.pointerId) return
    // A click on empty space — no drag — clears the selection, as in Finder; with ⌘/Shift it keeps it.
    if (!m.dragging && m.base.length === 0 && !(event.metaKey || event.ctrlKey || event.shiftKey)) selection?.clear()
    // Rows paged in while the band was still (auto-scroll ran ahead of the pages) join on release.
    if (m.dragging) applyMarquee()
    endMarquee()
  }

  // Keyed by the options' content: a caller passing a fresh literal must not rebuild it per render.
  const formatKey = JSON.stringify(headerFormat)
  const monthFormat = useMemo(
    () => new Intl.DateTimeFormat(i18n.language, JSON.parse(formatKey) as Intl.DateTimeFormatOptions),
    [i18n.language, formatKey],
  )

  return (
    <div className="relative min-h-0 flex-1">
      <div
        ref={scroller}
        data-testid="grid-scroller"
        onScroll={(e) => {
          setScrollTop(e.currentTarget.scrollTop)
          // A wheel scroll mid-drag moves the content under the band: it follows.
          if (marquee.current?.dragging) applyMarquee()
        }}
        onKeyDown={onKeyDown}
        onPointerDown={(e) => startBand(e, false)}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={endMarquee}
        onLostPointerCapture={(e) => {
          if (marquee.current?.pointerId === e.pointerId) endMarquee()
        }}
        tabIndex={-1}
        className="absolute inset-0 select-none overflow-x-hidden overflow-y-auto focus:outline-none"
      >
        <div style={{ height: layout.height, position: 'relative' }}>
          {band && (
            <div
              aria-hidden
              data-testid="marquee"
              className="pointer-events-none absolute z-10 border border-primary bg-primary/20"
              style={{ left: band.x, top: band.y, width: band.width, height: band.height }}
            />
          )}
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
                className="absolute left-0 right-0"
                style={{ top: row.top, height: row.height }}
              >
                {cellRects(layout, row).map(({ index, x, y, size }) => {
                  const photo = photoAt(index)
                  return (
                    <div key={photo?.path ?? `i${index}`} className="absolute" style={{ left: x, top: y }}>
                      <Tile
                        photo={photo}
                        index={index}
                        size={size}
                        fit={fit}
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
                    </div>
                  )
                })}
              </div>
            ),
          )}
        </div>
      </div>
      {times && times.length > 1 && (
        <DateBubble
          when={times[topIndex]}
          scrollTop={scrollTop}
          scrollHeight={layout.height}
          viewport={box.height}
          yearOnly={!('month' in headerFormat)}
        />
      )}
    </div>
  )
}

function clampY(clientY: number, rect: DOMRect, height: number): number {
  return Math.min(Math.max(clientY, rect.top), rect.top + height)
}

// The press is on [el]'s own scrollbar: past its content box on an axis it overflows.
function onScrollbar(el: Element, clientX: number, clientY: number): boolean {
  const r = el.getBoundingClientRect()
  const vertical = el.scrollHeight > el.clientHeight && clientX >= r.left + el.clientLeft + el.clientWidth
  const horizontal = el.scrollWidth > el.clientWidth && clientY >= r.top + el.clientTop + el.clientHeight
  return vertical || horizontal
}
