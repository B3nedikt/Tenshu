// DeviceMenu — self-revoke "sign out everywhere" (roadmap Phase B).
// Covers the load-bearing ordering (RevokeDevice tombstone pushed BEFORE
// the local wipe), the offline warn-and-proceed fork (B.2), the cancel
// path, and that "Lock now" stays a tombstone-free local clear.
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { reloadAfterPurge } from '../lib/reloadAfterPurge'
vi.mock('../lib/reloadAfterPurge', () => ({ reloadAfterPurge: vi.fn() }))
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import type { DevicesApi } from '@tenshu/sdk/identity'
import type { SessionApi } from '@tenshu/sdk/onboarding'

const revokeSelfMock = vi.fn<DevicesApi['revokeSelf']>()
const clearMock = vi.fn<SessionApi['clear']>()

vi.mock('@tenshu/sdk/identity', () => ({
  Devices: { revokeSelf: () => revokeSelfMock() },
}))

vi.mock('@tenshu/sdk/onboarding', () => ({
  Session: { clear: (opts?: { purgeRealm?: boolean }) => clearMock(opts) },
}))

import { DeviceMenu } from './DeviceMenu'

function LocationProbe() {
  const loc = useLocation()
  return <span data-testid="location">{loc.pathname}</span>
}

function renderMenu() {
  return render(
    <MemoryRouter initialEntries={['/control/home']}>
      <Routes>
        <Route path="/control/home" element={<DeviceMenu deviceName="Work laptop" />} />
        <Route path="*" element={<LocationProbe />} />
      </Routes>
    </MemoryRouter>,
  )
}

async function openSignOutConfirm(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'Work laptop' }))
  await user.click(screen.getByRole('menuitem', { name: 'Sign out everywhere' }))
  // The confirm dialog's primary button shares the "Sign out everywhere"
  // label; scope to the dialog to disambiguate from the (now-closed) menu.
  return screen.getByRole('dialog')
}

describe('DeviceMenu sign out everywhere', () => {
  beforeEach(() => {
    revokeSelfMock.mockReset()
    clearMock.mockReset()
    clearMock.mockResolvedValue(undefined)
  })

  it('pushes the RevokeDevice tombstone BEFORE wiping, then lands on /', async () => {
    const user = userEvent.setup()
    revokeSelfMock.mockResolvedValue({ ok: true, value: undefined })
    renderMenu()

    const dialog = await openSignOutConfirm(user)
    await user.click(within(dialog).getByRole('button', { name: 'Sign out everywhere' }))

    await waitFor(() => expect(clearMock).toHaveBeenCalledWith({ purgeRealm: true }))
    expect(revokeSelfMock).toHaveBeenCalledTimes(1)
    // Ordering is load-bearing: revoke must run before the wipe.
    const revokeOrder = revokeSelfMock.mock.invocationCallOrder[0]
    const clearOrder = clearMock.mock.invocationCallOrder[0]
    expect(revokeOrder).toBeDefined()
    expect(clearOrder).toBeDefined()
    expect(revokeOrder!).toBeLessThan(clearOrder!)
    // ⚠ A purge needs a real page load, not a router transition — see reloadAfterPurge.
    await waitFor(() => expect(reloadAfterPurge).toHaveBeenCalledWith('/'))
  })

  it('offline: warns and does NOT wipe until the user proceeds', async () => {
    const user = userEvent.setup()
    revokeSelfMock.mockResolvedValue({ ok: false, error: { kind: 'network' } })
    renderMenu()

    const dialog = await openSignOutConfirm(user)
    await user.click(within(dialog).getByRole('button', { name: 'Sign out everywhere' }))

    // Revoke attempted; wipe withheld pending the warn-and-proceed choice.
    await waitFor(() => expect(revokeSelfMock).toHaveBeenCalledTimes(1))
    expect(clearMock).not.toHaveBeenCalled()
    expect(
      await screen.findByText(/keep showing in your device list/i),
    ).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Sign out anyway' }))
    await waitFor(() => expect(clearMock).toHaveBeenCalledWith({ purgeRealm: true }))
    // ⚠ A purge needs a real page load, not a router transition — see reloadAfterPurge.
    await waitFor(() => expect(reloadAfterPurge).toHaveBeenCalledWith('/'))
  })

  it('cancelling the confirm dialog revokes nothing and wipes nothing', async () => {
    const user = userEvent.setup()
    renderMenu()
    const dialog = await openSignOutConfirm(user)
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    expect(revokeSelfMock).not.toHaveBeenCalled()
    expect(clearMock).not.toHaveBeenCalled()
  })

  it('Lock now is a local clear with no tombstone, routing to /lock', async () => {
    const user = userEvent.setup()
    renderMenu()
    await user.click(screen.getByRole('button', { name: 'Lock now' }))
    await waitFor(() => expect(clearMock).toHaveBeenCalledWith({}))
    expect(revokeSelfMock).not.toHaveBeenCalled()
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/lock'))
  })
})
