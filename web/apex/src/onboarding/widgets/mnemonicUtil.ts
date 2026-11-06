// Helpers for the recovery-phrase widgets. Lives outside the component
// files so React Fast Refresh's "components-only" rule applies cleanly.
export function pickConfirmPositions(count: number, total: number = 24): readonly number[] {
  const all = Array.from({ length: total }, (_, i) => i)
  const out: number[] = []
  while (out.length < count && all.length > 0) {
    const idx = Math.floor(Math.random() * all.length)
    const value = all.splice(idx, 1)[0]
    if (value !== undefined) out.push(value)
  }
  return out.sort((a, b) => a - b)
}
