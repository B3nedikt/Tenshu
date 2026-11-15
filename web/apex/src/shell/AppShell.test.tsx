// The overview + hosted-app chrome (docs/proposals/web-apex-home.md §3.2–§3.4, H1).
import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'

const detect = vi.fn()
vi.mock('@tenshu/sdk/onboarding', () => ({
  Session: { detect: () => detect() },
}))

import { AppShell } from './AppShell'
import { SessionLayout } from './SessionLayout'
import { OverviewPage } from '../overview/OverviewPage'
import { fakeShownApps } from '../test/fakeShownApps'
import type { ShownAppsSource } from '../overview/shownAppsContext'

function LockProbe() {
  const { search } = useLocation()
  return <p>lock screen {search}</p>
}

function renderAt(path: string, shown: ShownAppsSource = fakeShownApps()) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/" element={<p>landing</p>} />
        <Route path="/lock" element={<LockProbe />} />
        <Route element={<SessionLayout shownApps={shown} />}>
          <Route element={<AppShell />}>
            <Route path="/overview" element={<OverviewPage />} />
            <Route path="/overview/customize" element={<OverviewPage customizing />} />
            <Route path="/drive/*" element={<p>drive frame</p>} />
            <Route path="/home/*" element={<p>home app frame</p>} />
          </Route>
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

describe('AppShell', () => {
  it('on the overview: brand left, gear into Control, the default apps in the dock', async () => {
    detect.mockResolvedValue({ kind: 'active', deviceName: 'Laptop' })
    renderAt('/overview')
    const dock = await screen.findByRole('region', { name: 'Your apps' })
    expect(screen.getByRole('link', { name: 'Tenshu' })).toHaveAttribute('href', '/overview')
    expect(screen.getByRole('link', { name: /^Control/ })).toHaveAttribute('href', '/control')
    // D1: never customised → Drive, Photos, then the Add tile.
    await waitFor(() =>
      expect(within(dock).getAllByRole('link').map(l => l.textContent)).toEqual(['Drive', 'Photos', 'Add app']),
    )
    expect(within(dock).getByRole('link', { name: 'Drive' })).toHaveAttribute('href', '/drive')
  })

  it('inside an app: a way back to the overview and the app named', async () => {
    detect.mockResolvedValue({ kind: 'active', deviceName: 'Laptop' })
    renderAt('/drive/files/some/folder')
    await screen.findByText('drive frame')
    expect(screen.getByRole('link', { name: 'Tenshu' })).toHaveAttribute('href', '/overview')
    expect(screen.getByRole('banner')).toHaveTextContent('Drive')
  })

  // ⚠ The Home APP lives at /home; the overview must not be mistaken for it, nor the reverse.
  it('treats /home as the Home app, not the overview', async () => {
    detect.mockResolvedValue({ kind: 'active', deviceName: 'Laptop' })
    renderAt('/home')
    await screen.findByText('home app frame')
    expect(screen.getByRole('link', { name: 'Tenshu' })).toHaveAttribute('href', '/overview')
  })

  // The accessible name must contain the visible label (WCAG 2.5.3) — "Tenshu", not a paraphrase.
  it('names the back link by its visible text', async () => {
    detect.mockResolvedValue({ kind: 'active', deviceName: 'Laptop' })
    renderAt('/drive/files')
    await screen.findByText('drive frame')
    expect(screen.getByRole('link', { name: 'Tenshu' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Back to all your apps' })).toBeNull()
  })

  it('sends a locked browser to /lock, carrying where it was going', async () => {
    detect.mockResolvedValue({ kind: 'locked', deviceName: 'Laptop' })
    renderAt('/drive/files/a')
    expect(await screen.findByText('lock screen ?next=%2Fdrive%2Ffiles%2Fa')).toBeInTheDocument()
  })

  // §3.5.1: one way out while the layout is being edited — no launcher, gear or device menu.
  it('in Customize mode the top bar is just Done', async () => {
    detect.mockResolvedValue({ kind: 'active', deviceName: 'Laptop' })
    renderAt('/overview/customize')
    const done = await screen.findByRole('link', { name: 'Done' })
    expect(done).toHaveAttribute('href', '/overview')
    expect(screen.queryByRole('button', { name: 'Apps' })).toBeNull()
    expect(screen.queryByRole('link', { name: /^Control/ })).toBeNull()
  })

  it('sends a first-time browser to the landing', async () => {
    detect.mockResolvedValue({ kind: 'firstTime' })
    renderAt('/overview')
    await waitFor(() => expect(screen.getByText('landing')).toBeInTheDocument())
  })
})
