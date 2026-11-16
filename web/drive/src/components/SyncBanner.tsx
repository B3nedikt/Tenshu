import { useEffect } from 'react'
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
// ⚠ Still the cadence for the SCHEMA FENCE query — the fence has
// no reactive read yet. Sync health no longer polls: W12 landed the push channel and this is its
// first consumer, so a connectivity change now arrives when it happens rather than up to 15s
// later. That latency mattered: `device_unrecognized` is the state a browser sat in for hours.
const POLL_MS = 15_000

// ⚠ No "last synced" / "not synced yet" banner any more (2026-11-16). It read the same health row
// as the apex's sync dot, which already says when this browser last synced, so it said the same thing
// twice — and it flickered: the row's last-sync time restarted at 0 with every runtime start (fixed in
// `initSyncHealth`), and while it was 0 its second line changed on every tick. This banner is for
// states the user can act on; the freshness answer lives in the top bar.

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

  // W12. Pushed updates land in the query cache, so everything downstream keeps reading one source
  // of truth rather than two.
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

  // Connectivity says nothing is wrong: nothing to act on, so nothing to show (see the note above).
  if (!state) return null

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
