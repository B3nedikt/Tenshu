import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

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

// The section and the open folder live in the URL (useAppRoute), so each test starts at the root.
beforeEach(() => window.history.replaceState(null, '', '/'))

describe('App', () => {
  it('opens on Home and switches between all tabs', async () => {
    renderApp()

    // Home is the default view.
    await screen.findByText(/Nothing here yet/i)

    await userEvent.click(screen.getByRole('button', { name: 'My files' }))
    expect(await screen.findByText('This folder is empty.')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Shared links' }))
    expect(await screen.findByText(/haven.t shared anything/i)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Settings' }))
    expect(await screen.findByText('About')).toBeInTheDocument()
  })

  it('has no headline and keeps its sections in one navigation landmark', async () => {
    renderApp()
    await screen.findByText(/Nothing here yet/i)
    // The apex top bar names the app; the frame repeats nothing (web-apps-layout.md §3.1).
    expect(screen.queryByRole('heading', { name: 'Drive' })).toBeNull()
    expect(screen.getByRole('navigation', { name: 'Drive sections' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Home' })).toHaveAttribute('aria-current', 'page')
  })

  it('keeps the section in the path and the open folder in the fragment', async () => {
    renderApp()
    await screen.findByText(/Nothing here yet/i)
    await userEvent.click(screen.getByRole('button', { name: 'My files' }))
    expect(window.location.pathname).toBe('/files')
    await userEvent.click(screen.getByRole('button', { name: 'Settings' }))
    expect(window.location.pathname).toBe('/settings')
    await userEvent.click(screen.getByRole('button', { name: 'Home' }))
    expect(window.location.pathname).toBe('/')
  })

  it('opens the section and folder a URL names', async () => {
    window.history.replaceState(null, '', '/files#/Tax%20papers')
    renderApp()
    expect(screen.getByRole('button', { name: 'My files' })).toHaveAttribute('aria-current', 'page')
    // The breadcrumb shows the folder from the fragment; ⚠⚠ its name never reaches the path.
    expect(await screen.findByRole('navigation', { name: 'Breadcrumb' })).toHaveTextContent('Tax papers')
    expect(window.location.pathname).toBe('/files')
  })

  it('an unknown path opens Home', async () => {
    window.history.replaceState(null, '', '/Tax%20papers')
    renderApp()
    expect(await screen.findByText(/Nothing here yet/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Home' })).toHaveAttribute('aria-current', 'page')
  })
})
