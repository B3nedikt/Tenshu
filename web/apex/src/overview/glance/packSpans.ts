// How wide each overview card is on large screens (docs/proposals/web-apex-home.md §3.3): wide = half
// a row, narrow = a third, on a 6-column grid. Cards keep the user's ORDER, so a row can end short
// (a third next to a half); its last card then grows to fill the row rather than leave a hole.

export const COLUMNS = 6
const UNITS = { wide: 3, narrow: 2 } as const

/** Column spans for [sizes], in order, every row filled exactly. */
export function packSpans(sizes: readonly ('wide' | 'narrow')[]): number[] {
  const spans: number[] = []
  let rowStart = 0
  let used = 0
  sizes.forEach((size, i) => {
    const want = UNITS[size]
    if (used + want > COLUMNS && i > rowStart) {
      spans[i - 1] = spans[i - 1]! + (COLUMNS - used)
      rowStart = i
      used = 0
    }
    spans.push(want)
    used += want
  })
  if (spans.length > rowStart) spans[spans.length - 1] = spans[spans.length - 1]! + (COLUMNS - used)
  return spans
}

/** Static class names, so Tailwind sees every span it must generate. */
export const SPAN_CLASS: Readonly<Record<number, string>> = {
  1: 'lg:col-span-1',
  2: 'lg:col-span-2',
  3: 'lg:col-span-3',
  4: 'lg:col-span-4',
  5: 'lg:col-span-5',
  6: 'lg:col-span-6',
}
