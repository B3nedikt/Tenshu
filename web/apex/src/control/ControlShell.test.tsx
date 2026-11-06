// Smoke + nav-active-state coverage for the control-plane shell.
//
// `ControlShell` calls `Session.detect()` on mount; we mock the SDK
// onboarding entry point so the test runs without a live runtime. The
// test asserts that (a) the shell waits for the probe, (b) the nav
// surface renders all five sections, and (c) the section matching the
// current route gets the active styling. NavLink's `isActive` is the
// authoritative signal for an exact-match path; the broader
// `matchesBase` window.location fallback is for nested routes and is
// covered by the e2e suite (Phase T.3) where window.location is real.
import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'

vi.mock('@tenshu/sdk/onboarding', () => ({
  Session: {
    detect: () =>
      Promise.resolve({ kind: 'active', deviceName: 'Test Device' }),
  },
}))

import { ControlShell } from './ControlShell'

function renderShell(initialEntry: string) {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <Routes>
        <Route element={<ControlShell />}>
          <Route path="/control/home" element={<div data-testid="page-home">home</div>} />
          <Route path="/control/apps" element={<div data-testid="page-apps">apps</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

describe('ControlShell', () => {
  it('shows a loading state until Session.detect resolves, then renders the shell', async () => {
    renderShell('/control/home')
    expect(screen.getByText('Loading…')).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.getByTestId('page-home')).toBeInTheDocument()
    })
    // Top-bar Tenshu wordmark + the device-name pill in the side rail.
    expect(screen.getAllByText('Tenshu').length).toBeGreaterThan(0)
    // Device name appears in both the top-right device menu and the
    // side-rail "this device" pill.
    expect(screen.getAllByText('Test Device').length).toBeGreaterThan(0)
  })

  it('renders all five top-level sections in the side rail and bottom nav', async () => {
    renderShell('/control/home')
    await waitFor(() => screen.getByTestId('page-home'))
    // Each section appears twice — once in the side rail, once in the
    // bottom nav — even though only one is visible at a time per CSS
    // breakpoint. Both DOM trees are present in jsdom.
    for (const label of ['Home', 'Apps', 'Data', 'Services', 'Settings']) {
      expect(screen.getAllByRole('link', { name: label })).toHaveLength(2)
    }
  })

  it('marks the section matching the current route as active', async () => {
    renderShell('/control/apps')
    await waitFor(() => screen.getByTestId('page-apps'))
    const appsLinks = screen.getAllByRole('link', { name: 'Apps' })
    const homeLinks = screen.getAllByRole('link', { name: 'Home' })
    // NavLink toggles a `bg-primary` background on the active rail entry
    // (semantic token; was `bg-gray-900` before the design-system migration);
    // inactive entries instead carry `text-text-2`.
    expect(appsLinks.some(el => el.className.includes('bg-primary'))).toBe(true)
    expect(homeLinks.every(el => !el.className.includes('bg-primary'))).toBe(true)
  })
})
