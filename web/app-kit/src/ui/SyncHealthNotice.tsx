// Whether this device can sync, pushed over `sync.health.observe`. The small banner: only the
// states that mean "your edits stay here for now" get one, and nothing for `online` or the benign
// `deferred` — a banner for normal operation trains people to ignore banners. (Drive's
// `SyncBanner` is the large one: it also reads the content plane and the schema fence.)
import { useEffect, useState } from 'react'
import type { Connectivity, SyncHealthStatus } from '@tenshu/sdk/syncHealth'

export interface SyncHealthNoticeProps {
  // The app's `sync.health.observe` subscription (its `lib/proxy.ts`).
  readonly subscribe: (
    onValue: (status: SyncHealthStatus | null) => void,
    onError: (error: Error) => void,
  ) => () => void
  // The sentence for a connectivity state, or nothing for a state that gets no banner.
  readonly message: (connectivity: Connectivity) => string | null
}

export function SyncHealthNotice({ subscribe, message }: SyncHealthNoticeProps) {
  const [status, setStatus] = useState<SyncHealthStatus | null>(null)
  useEffect(() => subscribe(setStatus, () => setStatus(null)), [subscribe])
  const text = status ? message(status.connectivity) : null
  if (!text) return null
  return (
    <div role="status" className="mb-3 rounded-lg border border-line bg-surface-2 px-4 py-3 text-sm text-text-2">
      {text}
    </div>
  )
}
