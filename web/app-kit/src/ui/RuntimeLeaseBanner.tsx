// One runtime per origin (docs/web/architecture.md "Cross-tab"). When another tab — an apex tab or
// another app tab — runs Tenshu, every call this tab makes is refused, so the views would show
// errors with no explanation. This is the explanation, and the way out ("Use here").
//
// ⚠ Polled, not pushed: the case it must catch late is ANOTHER tab taking over from this one.
import { useEffect, useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import type { RuntimeLeaseState } from '@tenshu/sdk/core'
import { sessionQueryKeys } from '../query'

const POLL_MS = 15_000

// A parent known to be the apex is never given up on (the bridge keeps re-sending its
// hello), so a host that never answers would otherwise read as a dead app. After this long, say
// that we are still waiting.
export const STILL_CONNECTING_AFTER_MS = 12_000

// ⚠ Local, not `isRuntimeElsewhere` from `@tenshu/sdk/core`: that module loads the Kotlin bundle,
// and an app imports types only (it has no runtime of its own — that is the point).
function elsewhere(state: string | undefined): boolean {
  return state === 'elsewhere' || state === 'displaced'
}

export interface LeaseCopy {
  readonly connectingTitle: string
  readonly connectingBody: string
  readonly elsewhereTitle: string
  readonly elsewhereBody: string
  // While the hand-over runs. It can wait up to 2 min for the other tab's flush — say so.
  readonly moving: string
  readonly movingShort: string
  readonly useHere: string
}

export interface RuntimeLeaseBannerProps {
  // The app's `runtime.lease` / `runtime.useHere` calls (its `lib/proxy.ts`).
  readonly lease: () => Promise<RuntimeLeaseState>
  readonly useHere: () => Promise<void>
  readonly copy: LeaseCopy
}

export function RuntimeLeaseBanner({ lease, useHere: takeOver, copy }: RuntimeLeaseBannerProps) {
  const { data, isPending } = useQuery({
    queryKey: sessionQueryKeys.runtimeLease(),
    queryFn: () => lease(),
    refetchInterval: POLL_MS,
  })
  // ⚠ Reload whatever happened: this page's runtime never opened its stores, so only a fresh one
  // can take the lock — and a failed hand-over leaves nothing a retry here could fix.
  const useHere = useMutation({
    mutationFn: () => takeOver(),
    onSettled: () => window.location.reload(),
  })

  const [slow, setSlow] = useState(false)
  useEffect(() => {
    if (!isPending) return
    const timer = setTimeout(() => setSlow(true), STILL_CONNECTING_AFTER_MS)
    return () => clearTimeout(timer)
  }, [isPending])

  if (isPending && slow) {
    return (
      <div role="status" className="mb-3 rounded-lg border border-line bg-surface-2 px-4 py-3 text-sm text-text-2">
        <p className="font-medium text-text">{copy.connectingTitle}</p>
        <p className="mt-1">{copy.connectingBody}</p>
      </div>
    )
  }

  if (!elsewhere(data)) return null

  // The sync-fence banner's shape — the same "reload fixes it" class of notice.
  return (
    <div role="status" className="mb-3 rounded-lg border border-warning/30 bg-warning/10 px-4 py-3 text-sm text-warning">
      <p className="font-medium">{copy.elsewhereTitle}</p>
      <p className="mt-1 opacity-90">{useHere.isPending ? copy.moving : copy.elsewhereBody}</p>
      <button
        type="button"
        disabled={useHere.isPending}
        onClick={() => useHere.mutate()}
        className="mt-2 rounded-md border border-current px-3 py-1.5 text-xs font-medium hover:bg-current/10 disabled:opacity-50"
      >
        {useHere.isPending ? copy.movingShort : copy.useHere}
      </button>
    </div>
  )
}
