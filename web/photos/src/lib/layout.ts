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

export type GridRow =
  | { readonly kind: 'header'; readonly section: MonthSection; readonly top: number; readonly height: number }
  | { readonly kind: 'cells'; readonly start: number; readonly end: number; readonly top: number; readonly height: number }

export interface GridLayout {
  readonly rows: readonly GridRow[]
  readonly height: number
  readonly columns: number
  readonly cell: number
}

export const HEADER_HEIGHT = 44
export const GAP = 4

/** Columns for a [width] px container, aiming at ~[target] px tiles, never fewer than 3. */
export function columnsFor(width: number, target = 160): number {
  return Math.max(3, Math.round(width / target))
}

/**
 * Rows for [sections] at [width]. [headers] false lays a list out with no month headers — an
 * album is in the user's order, not by date, so a header per run would repeat months.
 */
export function layoutGrid(
  sections: readonly MonthSection[],
  width: number,
  columns = columnsFor(width),
  headers = true,
): GridLayout {
  const cell = Math.max(1, Math.floor((width - GAP * (columns - 1)) / columns))
  const rows: GridRow[] = []
  let top = 0
  for (const section of sections) {
    if (headers) {
      rows.push({ kind: 'header', section, top, height: HEADER_HEIGHT })
      top += HEADER_HEIGHT
    }
    const end = section.startIndex + section.count
    for (let start = section.startIndex; start < end; start += columns) {
      rows.push({ kind: 'cells', start, end: Math.min(end, start + columns), top, height: cell + GAP })
      top += cell + GAP
    }
  }
  return { rows, height: top, columns, cell }
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

/** The top of the row holding feed index [index] (for a scrubber seek or a viewer's return). */
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

/** The first feed index drawn at or below [scrollTop]: what the scrubber thumb is at. */
export function indexAtTop(layout: GridLayout, scrollTop: number): number {
  const [row] = visibleRows(layout, scrollTop, 0, 0)
  if (!row) return 0
  return row.kind === 'cells' ? row.start : row.section.startIndex
}

// ── the scrubber (ported from native `FeedYearIndex.kt`) ────────────────────────────────────────

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

/** Each mark's place on the track, 0 (top) to 1 (bottom), in photograph space. */
export function trackFractions(marks: readonly YearMark[]): { mark: YearMark; fraction: number }[] {
  const photographs = marks.reduce((n, m) => n + m.count, 0)
  if (photographs <= 1) return marks.map((mark) => ({ mark, fraction: 0 }))
  return marks.map((mark) => ({ mark, fraction: mark.startIndex / (photographs - 1) }))
}

/** Drop pills that would overlap the one above: greedy from the top, keeping the newest. */
export function thinToFit<T extends { fraction: number }>(fractions: readonly T[], minGap: number): T[] {
  if (fractions.length === 0) return []
  const kept = [fractions[0]!]
  for (const candidate of fractions.slice(1)) {
    if (candidate.fraction - kept[kept.length - 1]!.fraction >= minGap) kept.push(candidate)
  }
  return kept
}

/** The feed index a track fraction seeks to. */
export function indexAtFraction(fraction: number, total: number): number {
  if (total <= 0) return 0
  const f = Math.min(1, Math.max(0, fraction))
  return Math.round(f * (total - 1))
}

/** One section over a whole list of [count] (laid out with `headers` false). */
export function wholeList(count: number): MonthSection[] {
  return count === 0 ? [] : [{ key: 0, firstMs: 0, startIndex: 0, count }]
}
