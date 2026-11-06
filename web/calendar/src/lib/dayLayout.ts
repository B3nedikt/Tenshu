// Overlapping-event layout for a day column — a port of `calendar-shared-logic`'s `DayLayout`
// (calendar-app.md §5.2 (2)). Pure math over `(start, end)` minute pairs: cluster transitively
// overlapping events, assign columns greedily, then widen each event rightwards over free columns.
//
// ⚠ Intervals are half-open: an event ending at 10:00 and one starting at 10:00 do not overlap.

export const MINUTES_PER_DAY = 24 * 60

// ⚠⚠ Load-bearing beyond looks: a zero-length event (RFC 5545 permits it, imports produce it) has
// an empty interval, would overlap nothing, stack invisibly and be impossible to click.
export const DEFAULT_MINIMUM_MINUTES = 20

export interface PositionedEvent<T> {
  readonly value: T
  readonly topMinute: number
  // A LAYOUT extent (floored at the minimum height), not the event's end.
  readonly bottomMinute: number
  readonly column: number
  readonly columnSpan: number
  readonly columnCount: number
}

interface Block<T> {
  readonly index: number
  readonly item: T
  readonly top: number
  readonly bottom: number
}

/** Lays `items` out for one day, returned in input order. `interval` is already clipped to the day. */
export function arrangeDay<T>(
  items: readonly T[],
  interval: (item: T) => { start: number; end: number },
  minimumMinutes: number = DEFAULT_MINIMUM_MINUTES,
): PositionedEvent<T>[] {
  if (items.length === 0) return []
  const blocks: Block<T>[] = items.map((item, index) => {
    const range = interval(item)
    const top = clamp(range.start, 0, MINUTES_PER_DAY)
    // ⚠ Clamped to the end of the day: a 23:55 event cannot get its full minimum height.
    const bottom = clamp(Math.max(range.end, top + minimumMinutes), top, MINUTES_PER_DAY)
    return { index, item, top, bottom }
  })

  // ⚠ Start, then longest first, then input order: the last key keeps two identical intervals from
  // swapping columns between renders.
  const ordered = [...blocks].sort((a, b) => a.top - b.top || b.bottom - a.bottom || a.index - b.index)

  const placed: PositionedEvent<T>[] = new Array<PositionedEvent<T>>(items.length)
  let cluster: Block<T>[] = []
  let clusterEnd = Number.NEGATIVE_INFINITY

  const flush = () => {
    if (cluster.length === 0) return
    for (const [index, positioned] of layOutCluster(cluster)) placed[index] = positioned
    cluster = []
  }

  for (const block of ordered) {
    // ⚠ `>=`: half-open, so an event starting exactly when the cluster ended begins a new one.
    if (block.top >= clusterEnd) {
      flush()
      clusterEnd = block.bottom
    } else {
      clusterEnd = Math.max(clusterEnd, block.bottom)
    }
    cluster.push(block)
  }
  flush()
  return placed
}

function layOutCluster<T>(cluster: Block<T>[]): [number, PositionedEvent<T>][] {
  const columnEnds: number[] = []
  const columnOf: number[] = []
  cluster.forEach((block, position) => {
    let column = columnEnds.findIndex((end) => end <= block.top)
    if (column < 0) {
      column = columnEnds.length
      columnEnds.push(0)
    }
    columnEnds[column] = block.bottom
    columnOf[position] = column
  })
  const columnCount = columnEnds.length
  return cluster.map((block, position) => {
    const column = columnOf[position]!
    return [
      block.index,
      {
        value: block.item,
        topMinute: block.top,
        bottomMinute: block.bottom,
        column,
        columnSpan: spanFrom(cluster, columnOf, position, column, columnCount),
        columnCount,
      },
    ]
  })
}

// ⚠ Only ever rightwards: growing both ways lets events swap sides as their neighbours change.
function spanFrom<T>(cluster: Block<T>[], columnOf: number[], position: number, column: number, columnCount: number): number {
  const block = cluster[position]!
  let span = 1
  while (column + span < columnCount) {
    const candidate = column + span
    const blocked = cluster.some(
      (other, i) => i !== position && columnOf[i] === candidate && other.top < block.bottom && block.top < other.bottom,
    )
    if (blocked) break
    span++
  }
  return span
}

function clamp(v: number, lo: number, hi: number): number {
  return Math.min(Math.max(v, lo), hi)
}
