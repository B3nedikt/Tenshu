import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

// Each tab view reads the bridge on mount; stub the list calls + the APEX
// constant the Settings view reads.
vi.mock('./lib/proxy', () => ({
  recentFiles: async () => [],
  listFiles: async () => [],
  listShares: async () => [],
  // The shell mounts SyncBanner; a healthy realm renders nothing. ⚠ `lastSyncedAtMillis` must be
  // RECENT, not 0 — since W8 the banner reads it, and 0 means "never synced", which renders.
  syncHealth: async () => ({
    connectivity: 'online',
    isSyncing: false,
    pendingBlockCount: 0,
    lastSyncedAtMillis: Date.now(),
  }),
  schemaFence: async () => ({ fenced: false, serviceIds: [] }),
  syncNow: async () => true,
  // W12: the banner subscribes as well as snapshotting. No pushes needed here — the shell test
  // only cares that mounting works.
  subscribeSyncHealth: () => () => undefined,
  // One-runtime-per-origin: this tab holds it, so RuntimeBanner renders nothing.
  runtimeLease: async () => 'held',
  runtimeUseHere: async () => undefined,
  APEX: 'http://localhost:5173',
}))

const App = (await import('./App')).default

function renderApp() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>,
  )
}

describe('App', () => {
  it('opens on Home and switches between all tabs', async () => {
    renderApp()

    // Home is the default view.
    await screen.findByText(/Nothing here yet/i)

    await userEvent.click(screen.getByRole('button', { name: 'Files' }))
    expect(await screen.findByText('This folder is empty.')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Shared' }))
    expect(await screen.findByText(/haven.t shared anything/i)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Settings' }))
    expect(await screen.findByText('About')).toBeInTheDocument()
  })
})
