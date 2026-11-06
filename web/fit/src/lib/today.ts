// "Today" as a live value: the browser zone's calendar date, recomputed at the next local midnight
// and whenever the tab becomes visible again. ⚠ `localToday()` read on render alone keeps
// yesterday's window in a tab left open past midnight, until something else re-renders it.
//
// The timer is armed for the next local midnight, not on an interval: one wake a day. A sleeping
// laptop or a throttled background tab can fire it late, or not at all, which `visibilitychange`
// covers: a tab the user comes back to re-reads the date at once.
import { useEffect, useState } from 'react'
import { localToday } from './dates'

// Milliseconds from `now` to the next local midnight (calendar arithmetic, so a DST day is 23 or 25 h).
export function msUntilNextLocalMidnight(now: Date = new Date()): number {
  const next = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1)
  return Math.max(1, next.getTime() - now.getTime())
}

export function useLocalToday(): string {
  const [today, setToday] = useState(() => localToday())
  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined
    const check = () => {
      setToday(localToday())
      if (timer !== undefined) clearTimeout(timer)
      timer = setTimeout(check, msUntilNextLocalMidnight())
    }
    timer = setTimeout(check, msUntilNextLocalMidnight())
    const onVisible = () => {
      if (document.visibilityState === 'visible') check()
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      if (timer !== undefined) clearTimeout(timer)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [])
  return today
}
