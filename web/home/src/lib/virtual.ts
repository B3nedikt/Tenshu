// The fixed-row-height window the activity feed renders (`ActivityView.tsx`), Fit's (`web/fit`).
export const ROW_HEIGHT = 56
// Rendered above and below the viewport, so a fast scroll does not show blank rows.
export const OVERSCAN = 8

export function visibleRange(scrollTop: number, viewport: number, count: number): { first: number; last: number } {
  const first = Math.max(0, Math.floor(scrollTop / ROW_HEIGHT) - OVERSCAN)
  const last = Math.min(count - 1, Math.ceil((scrollTop + viewport) / ROW_HEIGHT) + OVERSCAN)
  return { first, last }
}
