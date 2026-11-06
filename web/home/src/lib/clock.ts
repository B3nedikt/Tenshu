// The app's render clock. ⚠ It only re-renders: it reads nothing over the bridge, writes nothing and
// starts no work in the runtime (a tab runs no ticker there — web-app-suite.md §4.H).
import { useEffect, useState } from 'react'

// A label that depends on the time ("As of 14:05" → "2026-07-23 14:05", Today → Yesterday) can change
// without its row moving.
export const AS_OF_REFRESH_MS = 60_000

// `Date.now()`, re-read every `AS_OF_REFRESH_MS` and, when given, exactly at `deadlineMs`,
// so a tile flips to "Hub offline" at its boundary, not up to a minute late.
export function useNow(deadlineMs: number | null): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const interval = setInterval(() => setNow(Date.now()), AS_OF_REFRESH_MS)
    return () => clearInterval(interval)
  }, [])
  useEffect(() => {
    if (deadlineMs === null) return
    // ⚠ Also when the deadline has just passed: `now` may still be the last tick's, before it.
    const timeout = setTimeout(() => setNow(Date.now()), Math.max(0, deadlineMs - Date.now()))
    return () => clearTimeout(timeout)
  }, [deadlineMs])
  return now
}
