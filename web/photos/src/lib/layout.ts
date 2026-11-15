// The grid's geometry: month sections from capture times, the rows they lay out into at a column
// count, and which rows a scroll position shows. Pure, so the virtualisation is testable without a
// layout engine (jsdom has none).
//
// ⚠⚠ Sections come from the capture-time index (`photo.captureTimes`), not from the rows held:
// the index is the whole feed's shape in one number per photograph, so the grid can size its
// scroll height and place every month header before a page past the first has loaded. Index `i`
// of the times is index `i` of the feed — both read the same window in the same order.

export interface MonthSection {
  // Local year × 12 + month: a stable React key, and what the header names.
  readonly key: number
  // Any capture time in the month (its first), for formatting the header.
  readonly firstMs: number
  // Index of the section's first photograph in the feed.
  readonly startIndex: number
  readonly count: number
}

export function monthKeyOf(ms: number): number {
  const d = new Date(ms)
  return d.getFullYear() * 12 + d.getMonth()
}

/**
 * One section per run of a month in [times], which must already be newest first. Like native's
 * `feedYearMarks`, this does not sort: an unordered input yields a month twice, which is visible,
 * rather than one section with a fabricated span.
 */
export function monthSections(times: readonly number[], keyOf: (ms: number) => number = monthKeyOf): MonthSection[] {
  const sections: MonthSection[] = []
  let start = 0
  for (let i = 1; i <= times.length; i++) {
    // One past the end, so the last run closes in the same branch as every other.
    if (i === times.length || keyOf(times[i]!) !== keyOf(times[start]!)) {
      sections.push({ key: keyOf(times[start]!), firstMs: times[start]!, startIndex: start, count: i - start })
      start = i
    }
  }
  return times.length === 0 ? [] : sections
}

// ── densities (web-apps-layout.md §3.5: the Library's Days / Months / Years) ───────────────────

/** How the Library groups and sizes the same feed: one section per day, month or year. */
export type Density = 'days' | 'months' | 'years'
export const DENSITIES: readonly Density[] = ['days', 'months', 'years']

export function dayKeyOf(ms: number): number {
  const d = new Date(ms)
  return (d.getFullYear() * 12 + d.getMonth()) * 31 + d.getDate()
}

export function yearKeyOf(ms: number): number {
  return new Date(ms).getFullYear()
}

/** The sections for [density] — local-time boundaries, like every other grouping here. */
export function sectionsFor(times: readonly number[], density: Density): MonthSection[] {
  return monthSections(times, density === 'days' ? dayKeyOf : density === 'years' ? yearKeyOf : monthKeyOf)
}

/** The tile size a density aims at: big for days, today's 160 px for months, a contact sheet for years. */
export const TILE_TARGET: Readonly<Record<Density, number>> = { days: 220, months: 160, years: 72 }

/** How a density's header names its section. */
export const HEADER_FORMAT: Readonly<Record<Density, Intl.DateTimeFormatOptions>> = {
  days: { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' },
  months: { month: 'long', year: 'numeric' },
  years: { year: 'numeric' },
}

export type GridRow =
  | { readonly kind: 'header'; readonly section: MonthSection; readonly top: number; readonly height: number }
  | {
      readonly kind: 'cells'
      readonly start: number
      readonly end: number
      readonly top: number
      readonly height: number
      // A mosaic line (native `FeedLine.Feature`): two rows tall, one photograph per three columns
      // drawn over 2×2 cells. [heroFirst] puts the first block's hero on the left; blocks alternate.
      readonly feature?: { readonly heroFirst: boolean }
    }

export interface GridLayout {
  readonly rows: readonly GridRow[]
  readonly height: number
  readonly columns: number
  readonly cell: number
}

export const HEADER_HEIGHT = 44
// The columns one mosaic block spans: a 2×2 hero beside two stacked photographs.
export const MOSAIC_BLOCK = 3
// Between tiles: tight, as iCloud's square grid and native's feed (the gap reads as a seam, not space).
export const GAP = 2

/**
 * Columns for a [width] px container, aiming at ~[target] px tiles, never fewer than 3. With
 * [mosaic], a multiple of three: a mosaic block is three columns wide, and a line must be whole blocks.
 * ⚠ Rounded UP to the next block (from 90 % of the plain count): rounded to the nearest, a desktop
 * Days grid falls back to three columns and a hero is two thirds of the screen — a phone's
 * proportions on a monitor. The small tiles end a little under [target], the heroes under twice it.
 */
export function columnsFor(width: number, target = 160, mosaic = false): number {
  if (mosaic) return Math.max(MOSAIC_BLOCK, Math.ceil(((width / target) * 0.9) / MOSAIC_BLOCK) * MOSAIC_BLOCK)
  return Math.max(3, Math.round(width / target))
}

// ── the size slider (web-apps-layout.md §4.10) ─────────────────────────────────────────────────
// Photos per row, as iCloud's slider: six steps from a dense 8 to a large 3. ⚠ The slider names a
// COUNT, not a pixel size: "5 per row" is what the user chose, so it holds as the window changes.
export const MIN_COLUMNS = 3
export const MAX_COLUMNS = 8
export const DEFAULT_COLUMNS = 5
// Below this a tile is a smudge: a narrow window draws fewer columns than asked.
export const MIN_TILE_PX = 96

/**
 * The columns actually drawn for [requested] at [width]: fewer when the window is too narrow for
 * that many [MIN_TILE_PX] tiles, and with [mosaic] the nearest whole number of three-column blocks
 * (that fits) — a mosaic line is whole blocks.
 */
export function columnsAt(width: number, requested: number, mosaic = false): number {
  const fits = Math.max(MIN_COLUMNS, Math.floor(width / MIN_TILE_PX))
  const wanted = Math.min(MAX_COLUMNS, Math.max(MIN_COLUMNS, Math.round(requested)))
  let columns = Math.min(wanted, fits)
  if (mosaic) {
    columns = Math.max(MOSAIC_BLOCK, Math.round(columns / MOSAIC_BLOCK) * MOSAIC_BLOCK)
    while (columns > fits && columns > MOSAIC_BLOCK) columns -= MOSAIC_BLOCK
  }
  return columns
}

// ── the mosaic rhythm (ported from native `FeedLayout.of`) ───────────────────────────────────────
// Of every FEATURE_CYCLE full lines, the first and the SECOND_FEATUREth are features — two plain
// rows, then three, so the rhythm does not read as a stencil. ⚠⚠ The slot is the line's first
// photograph's position in the whole library divided by the column count, never a count of lines
// drawn: a slot then depends on the line's own photographs alone, so nothing re-phases as pages
// land, and the web picks the same lines as a phone at the same column count.
const FEATURE_CYCLE = 7
const SECOND_FEATURE = 3

function isFeatureSlot(slot: number): boolean {
  return slot % FEATURE_CYCLE === 0 || slot % FEATURE_CYCLE === SECOND_FEATURE
}

/**
 * Rows for [sections] at [width]. [headers] false lays a list out with no month headers — an
 * album is in the user's order, not by date, so a header per run would repeat months. [mosaic]
 * draws some full lines as features (only at a multiple of three columns — see [columnsFor]).
 */
export function layoutGrid(
  sections: readonly MonthSection[],
  width: number,
  columns = columnsFor(width),
  headers = true,
  mosaic = false,
): GridLayout {
  const cell = Math.max(1, Math.floor((width - GAP * (columns - 1)) / columns))
  const pitch = cell + GAP
  const features = mosaic && columns % MOSAIC_BLOCK === 0
  const rows: GridRow[] = []
  let top = 0
  for (const section of sections) {
    if (headers) {
      rows.push({ kind: 'header', section, top, height: HEADER_HEIGHT })
      top += HEADER_HEIGHT
    }
    const end = section.startIndex + section.count
    for (let start = section.startIndex; start < end; start += columns) {
      const last = Math.min(end, start + columns)
      const slot = Math.floor(start / columns)
      if (features && last - start === columns && isFeatureSlot(slot)) {
        rows.push({ kind: 'cells', start, end: last, top, height: 2 * pitch, feature: { heroFirst: slot % FEATURE_CYCLE === 0 } })
        top += 2 * pitch
      } else {
        rows.push({ kind: 'cells', start, end: last, top, height: pitch })
        top += pitch
      }
    }
  }
  return { rows, height: top, columns, cell }
}

/** One photograph's square in a row: x from the rows' left edge, y from the row's top. */
export interface CellRect {
  readonly index: number
  readonly x: number
  readonly y: number
  readonly size: number
}

/**
 * Where each photograph of [row] is drawn. ⚠⚠ The one geometry the grid draws AND the marquee hits
 * (`marqueeHits`), so a band can never select a tile drawn somewhere else.
 *
 * A feature's reading order is the paged order (native's rule): with the hero on the left it is
 * the block's first photograph and the two on the right follow, top then bottom; with the hero on
 * the right, the top-left is first, the hero second and the bottom-left third.
 */
export function cellRects(layout: GridLayout, row: Extract<GridRow, { kind: 'cells' }>): CellRect[] {
  const { cell } = layout
  const pitch = cell + GAP
  const out: CellRect[] = []
  if (!row.feature) {
    for (let k = 0; k < row.end - row.start; k++) out.push({ index: row.start + k, x: k * pitch, y: 0, size: cell })
    return out
  }
  const hero = 2 * cell + GAP
  for (let block = 0; block * MOSAIC_BLOCK < row.end - row.start; block++) {
    const first = row.start + block * MOSAIC_BLOCK
    const x0 = block * MOSAIC_BLOCK * pitch
    const heroLeft = row.feature.heroFirst === (block % 2 === 0)
    if (heroLeft) {
      out.push({ index: first, x: x0, y: 0, size: hero })
      out.push({ index: first + 1, x: x0 + 2 * pitch, y: 0, size: cell })
      out.push({ index: first + 2, x: x0 + 2 * pitch, y: pitch, size: cell })
    } else {
      out.push({ index: first, x: x0, y: 0, size: cell })
      out.push({ index: first + 1, x: x0 + pitch, y: 0, size: hero })
      out.push({ index: first + 2, x: x0, y: pitch, size: cell })
    }
  }
  return out
}

/** The rows that intersect `[scrollTop - overscan, scrollTop + viewport + overscan)`. */
export function visibleRows(layout: GridLayout, scrollTop: number, viewport: number, overscan = 400): GridRow[] {
  const { rows } = layout
  const from = scrollTop - overscan
  const to = scrollTop + viewport + overscan
  // First row whose bottom is past `from` (binary search: rows are in top order).
  let lo = 0
  let hi = rows.length
  while (lo < hi) {
    const mid = (lo + hi) >> 1
    const row = rows[mid]!
    if (row.top + row.height <= from) lo = mid + 1
    else hi = mid
  }
  const out: GridRow[] = []
  for (let i = lo; i < rows.length && rows[i]!.top < to; i++) out.push(rows[i]!)
  return out
}

/**
 * The feed indices whose cells intersect [rect] — content coordinates: x from the rows' left edge,
 * y from the top of the laid-out content, each cell where `cellRects` draws it (the gaps are space,
 * not tile). Pure geometry, so a marquee selects across rows the virtualised grid has not mounted.
 * ⚠ In reading (index) order, so the band's first hit is its anchor even across a mosaic block.
 */
export function marqueeHits(
  layout: GridLayout,
  rect: { readonly left: number; readonly top: number; readonly right: number; readonly bottom: number },
): number[] {
  const hits: number[] = []
  for (const row of visibleRows(layout, rect.top, rect.bottom - rect.top, 0)) {
    if (row.kind !== 'cells' || row.top >= rect.bottom || row.top + row.height <= rect.top) continue
    for (const c of cellRects(layout, row)) {
      const y = row.top + c.y
      if (c.x < rect.right && c.x + c.size > rect.left && y < rect.bottom && y + c.size > rect.top) hits.push(c.index)
    }
  }
  return hits.sort((a, b) => a - b)
}

/** The top of the row holding feed index [index] (a viewer's return, a Years card). */
export function topOfIndex(layout: GridLayout, index: number): number {
  const { rows } = layout
  let lo = 0
  let hi = rows.length - 1
  let found = 0
  while (lo <= hi) {
    const mid = (lo + hi) >> 1
    const row = rows[mid]!
    const start = row.kind === 'cells' ? row.start : row.section.startIndex
    if (start <= index) {
      found = mid
      lo = mid + 1
    } else hi = mid - 1
  }
  const row = rows[found]
  if (!row) return 0
  // A section's header sits above its first row: seeking to the first photograph shows the header.
  const prev = rows[found - 1]
  if (row.kind === 'cells' && row.start === index && prev?.kind === 'header') return prev.top
  return row.top
}

/** The first feed index drawn at or below [scrollTop]: what the date bubble names. */
export function indexAtTop(layout: GridLayout, scrollTop: number): number {
  // The LAST row starting at or above scrollTop. ⚠ Not `visibleRows(…, 0, 0)`: with no viewport its
  // window is empty exactly at a row's top — which is where every programmatic scroll lands
  // (`topOfIndex`) — and the answer fell back to 0, the newest photograph.
  const { rows } = layout
  let lo = 0
  let hi = rows.length - 1
  let found = 0
  while (lo <= hi) {
    const mid = (lo + hi) >> 1
    if (rows[mid]!.top <= scrollTop) {
      found = mid
      lo = mid + 1
    } else hi = mid - 1
  }
  const row = rows[found]
  if (!row) return 0
  return row.kind === 'cells' ? row.start : row.section.startIndex
}

// ── year marks (ported from native `FeedYearIndex.kt`; the Years view) ─────────────────────────

export interface YearMark {
  readonly year: number
  readonly startIndex: number
  readonly count: number
}

/**
 * One mark per year of [times] (newest first). ⚠⚠ Placed by how many photographs each year holds,
 * not by date: a track drawn from dates would put an empty decade mid-screen (native PH-17a).
 */
export function yearMarks(times: readonly number[], yearOf: (ms: number) => number = (ms) => new Date(ms).getFullYear()): YearMark[] {
  const marks: YearMark[] = []
  let start = 0
  for (let i = 1; i <= times.length; i++) {
    if (i === times.length || yearOf(times[i]!) !== yearOf(times[start]!)) {
      marks.push({ year: yearOf(times[start]!), startIndex: start, count: i - start })
      start = i
    }
  }
  return times.length === 0 ? [] : marks
}

/** One section over a whole list of [count] (laid out with `headers` false). */
export function wholeList(count: number): MonthSection[] {
  return count === 0 ? [] : [{ key: 0, firstMs: 0, startIndex: 0, count }]
}
