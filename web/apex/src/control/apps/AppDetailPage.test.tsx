// `AppDetailPage` reads `:appId` from the route and renders the live
// `AppRegistry` detail. Coverage: the unknown-id path, the happy path
// (title / version / permissions / services), the interactive tri-state grant
// control, revoke-all, the devices section and the all-devices removal (which
// stays on the page — the web still hosts the app).
import { describe, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { AppDetailPage } from './AppDetailPage'
import { AppRegistryProvider } from './AppRegistryProvider'
import { MockAppRegistry, registrationsOf } from './appRegistry'
import { findApp } from './manifest'

function renderAt(path: string, registry: MockAppRegistry = new MockAppRegistry()) {
  return render(
    <AppRegistryProvider registry={registry}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/control/apps/:appId" element={<AppDetailPage />} />
          <Route path="/control/apps" element={<div data-testid="apps-list">apps list</div>} />
        </Routes>
      </MemoryRouter>
    </AppRegistryProvider>,
  )
}

describe('AppDetailPage', () => {
  it('renders the unknown-app surface for an :appId not in the registry', () => {
    renderAt('/control/apps/does-not-exist')
    expect(screen.getByRole('heading', { name: 'Unknown app' })).toBeInTheDocument()
    expect(screen.getByText('does-not-exist')).toBeInTheDocument()
  })

  it("renders Drive's detail end-to-end", () => {
    const drive = findApp('drive')!
    renderAt('/control/apps/drive')
    expect(screen.getByRole('heading', { name: drive.label })).toBeInTheDocument()
    expect(screen.getByText(drive.description)).toBeInTheDocument()
    expect(screen.getByText(`v${drive.version}`)).toBeInTheDocument()
    expect(screen.getByText(drive.launchPath)).toBeInTheDocument()
    for (const p of drive.permissions) {
      const row = screen.getByText(p.label).closest('li')!
      expect(within(row).getByText(p.description)).toBeInTheDocument()
    }
    for (const s of drive.services) {
      const link = screen.getByRole('link', { name: s.label })
      expect(link).toHaveAttribute('href', `/control/services/${s.id}`)
    }
  })

  it('the tri-state control sets a grant on the registry', async () => {
    const user = userEvent.setup()
    const registry = new MockAppRegistry()
    renderAt('/control/apps/drive', registry)

    // "Read your files" starts granted; flip it to Denied.
    await user.click(screen.getByRole('button', { name: 'Set Read your files to Denied' }))

    expect(registry.getDetail('drive')!.permissions.find(p => p.id === 'files.read')!.state).toBe(
      'denied',
    )
    // The control reflects the new selection.
    expect(screen.getByRole('button', { name: 'Set Read your files to Denied' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
  })

  it('revoke-all resets every permission to ask-each-time', async () => {
    const user = userEvent.setup()
    const registry = new MockAppRegistry()
    renderAt('/control/apps/drive', registry)

    await user.click(screen.getByRole('button', { name: 'Revoke all permissions' }))

    expect(registry.getDetail('drive')!.permissions.every(p => p.state === 'askEachTime')).toBe(
      true,
    )
  })

  it('lists the devices the app is registered on, an unnamed one as unknown', () => {
    renderAt('/control/apps/drive', new MockAppRegistry(undefined, registrationsOf({ drive: ['Pixel 9', null] })))
    expect(screen.getByText('Pixel 9')).toBeInTheDocument()
    expect(screen.getByText('Unknown device')).toBeInTheDocument()
  })

  // ⚠⚠ The browser has no registration of its own: the only removal is from ALL devices, and the app
  // stays (the web still hosts it), so the page stays put.
  it('removes from all devices and stays on the page', async () => {
    const user = userEvent.setup()
    const registry = new MockAppRegistry(undefined, registrationsOf({ drive: ['Pixel 9'] }))
    renderAt('/control/apps/drive', registry)

    await user.click(screen.getByRole('button', { name: 'Remove from all devices' }))
    expect(screen.queryByRole('radio')).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Remove' }))

    expect(registry.removed).toEqual(['drive'])
    expect(await screen.findByText(/Not installed on any of your devices yet/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Remove from all devices' })).toBeNull()
    expect(screen.queryByTestId('apps-list')).toBeNull()
  })

  it('a failed removal is reported', async () => {
    const user = userEvent.setup()
    const registry = new MockAppRegistry(undefined, registrationsOf({ drive: ['Pixel 9'] }))
    registry.failRemoval = true
    renderAt('/control/apps/drive', registry)
    await user.click(screen.getByRole('button', { name: 'Remove from all devices' }))
    await user.click(screen.getByRole('button', { name: 'Remove' }))
    expect(await screen.findByRole('alert')).toHaveTextContent("Couldn't remove Drive")
    expect(screen.getByText('Pixel 9')).toBeInTheDocument()
  })

  it('offers no removal when no device has the app, or the read has no answer', () => {
    renderAt('/control/apps/drive', new MockAppRegistry(undefined, { kind: 'loading' }))
    expect(screen.queryByRole('button', { name: 'Remove from all devices' })).toBeNull()
  })

  it('the back link returns to the /control/apps list route', () => {
    renderAt('/control/apps/drive')
    expect(screen.getByRole('link', { name: '← Apps' })).toHaveAttribute('href', '/control/apps')
  })
})
