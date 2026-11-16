// A live window of daily aggregates (the SDK's `dailyAggregates` + its push form), shared by Home and
// the metric page. ⚠ A failed read is a failure on screen, never zeros; a day a SUCCESSFUL read does
// not name is a real zero (`windowDays`).
import { useCallback } from 'react'
import { useLive } from '@tenshu/app-kit/live'
import type { DailyAggregatesResult } from '@tenshu/sdk/fit'
import { dailyAggregates, subscribeDailyAggregates } from './proxy'
import { queryKeys } from './queryKeys'

export function useDays(from: string, to: string) {
  const seed = useCallback(() => dailyAggregates(from, to), [from, to])
  const subscribe = useCallback(
    (onValue: (v: DailyAggregatesResult) => void, onError: (e: Error) => void) => subscribeDailyAggregates(from, to, onValue, onError),
    [from, to],
  )
  return useLive(queryKeys.days(from, to), seed, subscribe)
}
