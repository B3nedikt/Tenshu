import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const syncHealth = vi.fn()
const schemaFence = vi.fn()
const syncNow = vi.fn()
// W12: the banner subscribes as well as snapshotting. The fake records the pushed callback so a
// test can drive a change without a bridge, and returns the unsubscribe the component expects.
let pushSyncHealth: ((row: unknown) => void) | null = null
const unsubscribeSyncHealth = vi.fn()
const subscribeSyncHealth = vi.fn((onValue: (row: unknown) => void) => {
  pushSyncHealth = onValue
  return unsubscribeSyncHealth
})
vi.mock('../lib/proxy', () => ({
  syncHealth: () => syncHealth(),
  schemaFence: () => schemaFence(),
  syncNow: () => syncNow(),
  subscribeSyncHealth: (onValue: (row: unknown) => void) => subscribeSyncHealth(onValue),
}))

const { SyncBanner } = await import('./SyncBanner')

// ⚠⚠ `lastSyncedAtMillis` defaults to NOW, not 0. It used to be 0 because nothing read it;
// since W8 the banner does, and 0 means "never synced" — a real state with its own message.
// A healthy device has a recent timestamp, so that is what the shared fixture must carry, or
// every connectivity test would silently be asserting against a never-synced device.
function row(connectivity: string, lastSyncedAtMillis: number = Date.now()) {
  return { connectivity, isSyncing: false, pendingBlockCount: 0, lastSyncedAtMillis }
}

beforeEach(() => {
  schemaFence.mockResolvedValue({ fenced: false, serviceIds: [] })
  syncNow.mockResolvedValue(true)
  // ⚠ Counts, not just implementations. These fns are module-level and shared across the file,
  // so a test asserting "fetched exactly once" otherwise inherits every preceding test's calls
  // and fails for a reason that has nothing to do with the component.
  syncHealth.mockClear()
  schemaFence.mockClear()
  pushSyncHealth = null
  unsubscribeSyncHealth.mockClear()
  subscribeSyncHealth.mockClear()
})

function renderBanner() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <SyncBanner />
    </QueryClientProvider>,
  )
}

describe('SyncBanner', () => {
  // The state a browser sat in for hours on 2026-08-28 while showing a normal file list.
  it('names the device-unrecognised state and the action that fixes it', async () => {
    syncHealth.mockResolvedValue(row('device_unrecognized'))
    renderBanner()
    expect(await screen.findByText(/no longer part of your realm/i)).toBeInTheDocument()
    // ⚠ The remedy is not discoverable from the error anywhere else (W10b), so the banner
    // owns it. If this assertion goes. the user is back to a dead end.
    expect(screen.getByText(/disconnect this browser/i)).toBeInTheDocument()
  })

  it('says a locked realm is locked', async () => {
    syncHealth.mockResolvedValue(row('locked'))
    renderBanner()
    expect(await screen.findByText(/locked on this device/i)).toBeInTheDocument()
  })

  it('says uploads are refused when the realm has no storage', async () => {
    syncHealth.mockResolvedValue(row('needs_attention'))
    renderBanner()
    expect(await screen.findByText(/aren't being saved to the cloud/i)).toBeInTheDocument()
  })

  // ⚠ Silence is a feature for these two: `deferred` is the Wi-Fi-only policy working as
  // configured, and a banner during normal operation trains people to ignore banners.
  it.each(['online', 'deferred'])('stays silent for %s', async (state) => {
    syncHealth.mockResolvedValue(row(state))
    const { container } = renderBanner()
    await waitFor(() => expect(syncHealth).toHaveBeenCalled())
    expect(container.querySelector('[role="status"]')).toBeNull()
  })

  it('stays silent before the first row exists', async () => {
    syncHealth.mockResolvedValue(null)
    const { container } = renderBanner()
    await waitFor(() => expect(syncHealth).toHaveBeenCalled())
    expect(container.querySelector('[role="status"]')).toBeNull()
  })

  // ⭐ Web is the only platform that can finish this banner. `core-schema-skew-surface.md` says
  // "no platform has an update route, so no button" — true of Android, desktop and iOS, and
  // untrue here, because a reload IS the update route.
  it('offers a reload when the device is schema-fenced', async () => {
    syncHealth.mockResolvedValue(row('online'))
    schemaFence.mockResolvedValue({ fenced: true, serviceIds: ['tenshu/file'] })
    renderBanner()
    expect(await screen.findByText(/needs to reload to keep syncing/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /reload/i })).toBeInTheDocument()
  })

  // W8 (§1.2b). On web the app window IS the proxy: a backgrounded tab is frozen by the
  // browser, so it stops syncing entirely and `online` stays true the whole time. Without
  // this the UI is indistinguishable from one that is syncing fine, which is the single most
  // misread failure on web.
  it('says how long it has been when a healthy tab has not synced recently', async () => {
    syncHealth.mockResolvedValue(row('online', Date.now() - 12 * 60_000))
    renderBanner()
    expect(await screen.findByText(/last synced 12 minutes ago/i)).toBeInTheDocument()
    // The cause, in the user's terms — otherwise "12 minutes ago" reads as a fault.
    expect(screen.getByText(/only syncs while this tab is in front/i)).toBeInTheDocument()
  })

  it('distinguishes never-synced from stale', async () => {
    syncHealth.mockResolvedValue(row('online', 0))
    renderBanner()
    expect(await screen.findByText(/not synced yet/i)).toBeInTheDocument()
    // ⚠⚠ And does not explain it with the backgrounded-tab cause, which did not happen here.
    // A confident wrong reason is worse than none; caught on a device pass, not by a test.
    expect(screen.getByText(/has not synced with your other devices yet/i)).toBeInTheDocument()
    expect(screen.queryByText(/only syncs while this tab is in front/i)).toBeNull()
  })

  // ⚠ The counterpart to the silence test above: freshness must not become a fourth banner
  // that shows during normal operation.
  it('stays silent when a healthy tab synced moments ago', async () => {
    syncHealth.mockResolvedValue(row('online', Date.now() - 5_000))
    const { container } = renderBanner()
    await waitFor(() => expect(syncHealth).toHaveBeenCalled())
    expect(container.querySelector('[role="status"]')).toBeNull()
  })

  // W12 — the banner's first use of the push channel. Before this it polled every 15s, so a
  // connectivity change could sit unshown for that long; `device_unrecognized` is precisely the
  // state a browser sat in for hours on 2026-08-28 while rendering a normal file list.
  //
  // ⚠⚠ Asserts the snapshot is fetched ONCE and never again. That is the actual claim — a test
  // that only checked the banner updated would pass just as well against the old refetch, since
  // both end with the right text on screen.
  it('re-renders from a pushed update without refetching', async () => {
    syncHealth.mockResolvedValue(row('online'))
    renderBanner()
    await waitFor(() => expect(subscribeSyncHealth).toHaveBeenCalled())
    await waitFor(() => expect(syncHealth).toHaveBeenCalledTimes(1))

    act(() => pushSyncHealth!(row('device_unrecognized')))

    expect(await screen.findByText(/no longer part of your realm/i)).toBeInTheDocument()
    expect(syncHealth).toHaveBeenCalledTimes(1)
  })

  // ⚠ The apex holds a live Flow collector per subscriber, so a component that forgets to
  // unsubscribe leaks one for the life of the page.
  it('unsubscribes when it unmounts', async () => {
    syncHealth.mockResolvedValue(row('online'))
    const { unmount } = renderBanner()
    await waitFor(() => expect(subscribeSyncHealth).toHaveBeenCalled())

    unmount()

    expect(unsubscribeSyncHealth).toHaveBeenCalledTimes(1)
  })

  // ⚠ A fenced device is REACHABLE, so connectivity reads `online` — gate on the fence, not the
  // connection, or the banner says "all good" while nothing syncs.
  it('the fence outranks a healthy connection', async () => {
    syncHealth.mockResolvedValue(row('online'))
    schemaFence.mockResolvedValue({ fenced: true, serviceIds: [] })
    renderBanner()
    expect(await screen.findByText(/needs to reload/i)).toBeInTheDocument()
  })

  it('offers "Try now" only when offline', async () => {
    syncHealth.mockResolvedValue(row('offline'))
    renderBanner()
    const button = await screen.findByRole('button', { name: /try now/i })
    button.click()
    await waitFor(() => expect(syncNow).toHaveBeenCalledTimes(1))
  })

  // A kick cannot help these — a button that provably does nothing is worse than none.
  it.each(['locked', 'needs_attention', 'device_unrecognized'])(
    'offers no retry for %s',
    async (state) => {
      syncHealth.mockResolvedValue(row(state))
      renderBanner()
      await screen.findByRole('status')
      expect(screen.queryByRole('button', { name: /try now/i })).toBeNull()
    },
  )
})
