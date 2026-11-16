// DeviceMenu — "Lock now" and "Disconnect this browser". The disconnect's own guards (the
// acknowledgement, the busy state, the outcomes) are pinned in `DisconnectBrowserPanel.test.tsx`;
// this suite pins what the menu adds: the name, a single removal path, and a lock that stays a lock.
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { reloadAfterPurge } from '../lib/reloadAfterPurge'
vi.mock('../lib/reloadAfterPurge', () => ({ reloadAfterPurge: vi.fn() }))
vi.mock('../lib/webauthn', () => ({ signalPasskeyRemoved: vi.fn(async () => false) }))
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import type { SessionApi } from '@tenshu/sdk/onboarding'

const clearMock = vi.fn<SessionApi['clear']>()
const revokeSelfMock = vi.fn()

vi.mock('@tenshu/sdk/onboarding', () => ({
  Session: { clear: (opts?: { purgeRealm?: boolean }) => clearMock(opts) },
}))
vi.mock('@tenshu/sdk/identity', () => ({
  Devices: { revokeSelf: () => revokeSelfMock() },
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

async function openDisconnect(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'Work laptop' }))
  await user.click(screen.getByRole('menuitem', { name: 'Disconnect this browser' }))
  return screen.getByRole('dialog')
}

describe('DeviceMenu', () => {
  beforeEach(() => {
    clearMock.mockReset()
    revokeSelfMock.mockReset()
    vi.mocked(reloadAfterPurge).mockClear()
  })

  it('disconnects through ONE removal path: the purge, never a separate revokeSelf first', async () => {
    const user = userEvent.setup()
    clearMock.mockResolvedValue({ removal: 'confirmed', passkeyCredentialId: null })
    renderMenu()
    const dialog = await openDisconnect(user)
    await user.click(within(dialog).getByRole('checkbox'))
    await user.click(within(dialog).getByRole('button', { name: 'Disconnect this browser' }))
    await waitFor(() => expect(reloadAfterPurge).toHaveBeenCalledWith('/'))
    expect(clearMock).toHaveBeenCalledTimes(1)
    expect(clearMock).toHaveBeenCalledWith({ purgeRealm: true })
    expect(revokeSelfMock).not.toHaveBeenCalled()
  })

  it('the dialog cannot be dismissed while the purge runs', async () => {
    const user = userEvent.setup()
    clearMock.mockReturnValue(new Promise(() => {}))
    renderMenu()
    const dialog = await openDisconnect(user)
    await user.click(within(dialog).getByRole('checkbox'))
    await user.click(within(dialog).getByRole('button', { name: 'Disconnect this browser' }))
    await user.keyboard('{Escape}')
    await user.click(dialog)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('cancelling wipes nothing', async () => {
    const user = userEvent.setup()
    renderMenu()
    const dialog = await openDisconnect(user)
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    expect(clearMock).not.toHaveBeenCalled()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('Lock now is a plain lock — no purge — routing to /lock', async () => {
    const user = userEvent.setup()
    clearMock.mockResolvedValue({ removal: null, passkeyCredentialId: null })
    renderMenu()
    await user.click(screen.getByRole('button', { name: 'Lock now' }))
    await waitFor(() => expect(clearMock).toHaveBeenCalledWith({}))
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/lock'))
  })
})
