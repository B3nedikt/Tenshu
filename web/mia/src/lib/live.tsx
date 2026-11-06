// The push half of Mia: the history (with `replying`) and the grants, each subscribed ONCE at the
// app root and written into the query cache, so the chat, History and Grants read one copy. The
// mechanism is the app-kit's (`useLive`); this file is which streams.
//
// ⚠⚠ A terminal stream error is surfaced as `streamError` (render it with `StreamNotice`), and a
// typed failure arrives as `data` with `ok: false` — neither is ever an empty list.
import type { ReactNode } from 'react'
import type { GrantsResult, HistoryResult } from '@tenshu/sdk/mia'
import { createLiveContext, useLive, type Live } from '@tenshu/app-kit/live'
import { grants, history, subscribeGrants, subscribeHistory } from './proxy'
import { queryKeys } from './queryKeys'

interface LiveState {
  readonly history: Live<HistoryResult>
  readonly grants: Live<GrantsResult>
}

const [LiveContext, useLiveState] = createLiveContext<LiveState>('LiveProvider')

export function LiveProvider({ children }: { children: ReactNode }) {
  const historyLive = useLive(queryKeys.history(), history, subscribeHistory)
  const grantsLive = useLive(queryKeys.grants(), grants, subscribeGrants)
  return <LiveContext.Provider value={{ history: historyLive, grants: grantsLive }}>{children}</LiveContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useHistory(): Live<HistoryResult> {
  return useLiveState().history
}

// eslint-disable-next-line react-refresh/only-export-components
export function useGrants(): Live<GrantsResult> {
  return useLiveState().grants
}
