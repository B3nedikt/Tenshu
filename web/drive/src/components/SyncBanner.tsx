import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { schemaFence, subscribeSyncHealth, syncHealth, syncNow } from '../lib/proxy'
import { queryKeys } from '../lib/queryKeys'

// W3. Until this existed the web rendered a normal-looking file list no matter what the relay
// thought of this device: on 2026-08-28 a browser sat in `device_unrecognized` for hours, syncing
// nothing, and said nothing. The row driving this has been in the store since Phase A.
//
// ⚠ Only states the user can act on get a banner. `online` and `deferred` are deliberately
// silent — `deferred` is the Wi-Fi-only policy working as configured, and a banner for normal
// operation trains people to ignore banners.
//
// ⚠ Still the cadence for the SCHEMA FENCE query and the freshness clock below — the fence has
// no reactive read yet. Sync health no longer polls: W12 landed the push channel and this is its
// first consumer, so a connectivity change now arrives when it happens rather than up to 15s
// later. That latency mattered: `device_unrecognized` is the state a browser sat in for hours.
const POLL_MS = 15_000

// W8 (§1.2b). How long without a successful sync before we say so out loud. The cold tick is
// 30s, so a visible, healthy tab should never reach this; a tab that has just come back from
// the background routinely will, which is exactly the case worth explaining. ⚠ Not a warning
// tone — most of the time this is the browser behaving normally, and treating it as a fault
// would train people to ignore the banner that sits directly above it.
const STALE_AFTER_MS = 120_000

// Coarse on purpose — this answers "is it stuck?", not "exactly when?".
function relativeAge(ms: number): string {
  const seconds = Math.max(0, Math.round(ms / 1000))
  if (seconds < 90) return `${seconds} seconds ago`
  const minutes = Math.round(seconds / 60)
  if (minutes < 90) return `${minutes} minute${minutes === 1 ? '' : 's'} ago`
  const hours = Math.round(minutes / 60)
  return `${hours} hour${hours === 1 ? '' : 's'} ago`
}

// ⚠⚠ Its own clock, deliberately. React Query does structural sharing, so a poll that returns
// an unchanged `lastSyncedAtMillis` keeps the same object reference and does NOT re-render —
// the age would freeze at whatever it said when sync stopped, which is the one situation this
// exists to report. The whole point is that it keeps counting up while nothing happens.
function useNow(intervalMs: number): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), intervalMs)
    return () => clearInterval(id)
  }, [intervalMs])
  return now
}

type Tone = 'warn' | 'error'

function describe(connectivity: string): { tone: Tone; title: string; body: string } | null {
  switch (connectivity) {
    case 'device_unrecognized':
      return {
        tone: 'error',
        title: "This browser is no longer part of your realm",
        // The one action that works — and it is not discoverable from the error otherwise
        // (drive-web-parity.md W10b).
        body: 'It was removed, or the relay lost its registration. Disconnect this browser in Tenshu, then pair it again from another device.',
      }
    case 'locked':
      return {
        tone: 'warn',
        title: 'Tenshu is locked on this device',
        body: 'Unlock it to start syncing again. Your files stay where they are.',
      }
    case 'needs_attention':
      return {
        tone: 'warn',
        title: "Your changes aren't being saved to the cloud",
        body: 'This realm has no active storage plan, so uploads are refused. Everything still works on this device.',
      }
    case 'offline':
      return {
        tone: 'warn',
        title: "Can't reach Tenshu",
        body: "Changes are saved here and will sync when the connection returns.",
      }
    default:
      return null
  }
}

export function SyncBanner() {
  const queryClient = useQueryClient()
  // The snapshot still seeds it — a subscriber gets nothing until the row next changes, and the
  // banner must be right on first paint.
  const { data } = useQuery({
    queryKey: queryKeys.syncHealth(),
    queryFn: syncHealth,
  })
  const { data: fence } = useQuery({
    queryKey: queryKeys.schemaFence(),
    queryFn: schemaFence,
    refetchInterval: POLL_MS,
  })
  const kick = useMutation({ mutationFn: syncNow })
  // ⚠ Above every early return — hooks cannot live behind the fence branch below.
  const now = useNow(POLL_MS)

  // W12. Pushed updates land in the query cache, so everything downstream — including the
  // freshness branch — keeps reading one source of truth rather than two.
  useEffect(
    () =>
      subscribeSyncHealth((row) => {
        queryClient.setQueryData(queryKeys.syncHealth(), row)
      }),
    [queryClient],
  )

  // ⭐ The fence outranks connectivity: a fenced device is REACHABLE (schema-skew-surface: gate
  // on `reason`, not `state`), so it would otherwise render "online" while syncing nothing.
  //
  // ⚠⚠ And this is the one banner web can finish. `core-schema-skew-surface.md` records "no
  // platform has an update route, so no button" — true of Android, desktop and iOS, and simply
  // untrue here: a browser's update route is a reload. Native can only tell the user to go and
  // update something; we can do it for them.
  if (fence?.fenced) {
    return (
      <div role="status" className="mb-3 rounded-lg border border-danger/30 bg-danger/10 px-4 py-3 text-sm text-danger">
        <p className="font-medium">This browser needs to reload to keep syncing</p>
        <p className="mt-1 opacity-90">
          Your other devices are using a newer version of Tenshu. Reloading picks it up.
        </p>
        <button
          type="button"
          onClick={() => window.location.reload()}
          className="mt-2 rounded-md bg-danger px-3 py-1.5 text-xs font-medium text-on-primary hover:opacity-90"
        >
          Reload
        </button>
      </div>
    )
  }

  const state = data ? describe(data.connectivity) : null

  // Connectivity says nothing is wrong. That is not the same as "syncing" — on web the app
  // window IS the proxy, so a tab that was in the background has simply not been running, and
  // `online` is a truthful answer to a question the user is not asking. Say when it last worked.
  if (!state) {
    if (!data) return null
    const sinceMs = data.lastSyncedAtMillis > 0 ? now - data.lastSyncedAtMillis : -1
    if (sinceMs >= 0 && sinceMs < STALE_AFTER_MS) return null
    return (
      <div
        role="status"
        className="mb-3 rounded-lg border border-line bg-surface-2 px-4 py-3 text-sm text-text-2"
      >
        <p className="font-medium text-text">
          {sinceMs < 0 ? 'Not synced yet' : `Last synced ${relativeAge(sinceMs)}`}
        </p>
        <p className="mt-1">
          {data.isSyncing
            ? 'Catching up now.'
            : sinceMs < 0
              // ⚠ Found on a device pass: a browser that has NEVER synced was being told its
              // quiet is "normal after it has been in the background", which is a confident
              // explanation of something that did not happen. A fresh or local-only realm hits
              // this, and the wrong cause is worse than no cause.
              ? 'This browser has not synced with your other devices yet.'
              : 'Tenshu only syncs while this tab is in front, so this is normal after it has been in the background. It should catch up within a few seconds.'}
        </p>
      </div>
    )
  }

  const colour =
    state.tone === 'error'
      ? 'border-danger/30 bg-danger/10 text-danger'
      : 'border-warning/30 bg-warning/10 text-warning'

  return (
    <div role="status" className={`mb-3 rounded-lg border px-4 py-3 text-sm ${colour}`}>
      <p className="font-medium">{state.title}</p>
      <p className="mt-1 opacity-90">{state.body}</p>
      {/* ⚠ Offline only. A kick cannot help a locked realm, a refused entitlement or a device
          the relay does not know — offering it there would be a button that provably does
          nothing. ⚠ It is also NOT a fix for a backgrounded tab (§1.2b / W8): it helps someone
          who noticed, and does nothing for someone who did not. */}
      {data?.connectivity === 'offline' && (
        <button
          type="button"
          disabled={kick.isPending}
          onClick={() => kick.mutate()}
          className="mt-2 rounded-md border border-current px-3 py-1.5 text-xs font-medium hover:bg-current/10 disabled:opacity-50"
        >
          {kick.isPending ? 'Trying…' : 'Try now'}
        </button>
      )}
    </div>
  )
}
