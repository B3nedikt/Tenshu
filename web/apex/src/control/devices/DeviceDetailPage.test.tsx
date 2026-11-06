// `DeviceDetailPage` mirrors the list page on the SDK side
// (`Devices.observe` + `Session.detect`) and adds the revoke flow
// (`Devices.revoke` returning `TResult<void, DeviceRevokeError>`).
//
// Tests cover the four interesting branches:
//   - unknown :deviceId → "Unknown device"
//   - "this device" disables Revoke (you can't sign yourself out)
//   - happy-path Revoke → confirm → success → /control/data/devices
//   - Revoke network failure surfaces the right copy in the dialog
//     and leaves the user on the page.
import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import type { Device, DevicesApi } from '@tenshu/sdk/identity'
import type { SessionState } from '@tenshu/sdk/onboarding'

const observeMock = vi.fn<DevicesApi['observe']>()
const detectMock = vi.fn<() => Promise<SessionState>>()
const revokeMock = vi.fn<DevicesApi['revoke']>()

vi.mock('@tenshu/sdk/identity', () => ({
  Devices: {
    observe: (cb: (devices: readonly Device[]) => void) => observeMock(cb),
    revoke: (deviceId: string) => revokeMock(deviceId),
  },
}))

vi.mock('@tenshu/sdk/onboarding', () => ({
  Session: { detect: () => detectMock() },
}))

import { DeviceDetailPage } from './DeviceDetailPage'

function deviceFixture(name: string, deviceId: string): Device {
  const publicKey = new Int8Array(32)
  for (let i = 0; i < 8; i++) publicKey[i] = 0x42
  return { deviceId, name, publicKey, isCurrent: false, isAuthorized: true }
}

function emit(devices: readonly Device[]) {
  observeMock.mockImplementation(cb => {
    cb(devices)
    return () => {}
  })
}

function LocationProbe() {
  const loc = useLocation()
  return <span data-testid="location">{loc.pathname}</span>
}

function renderAt(deviceId: string) {
  return render(
    <MemoryRouter initialEntries={[`/control/data/devices/${deviceId}`]}>
      <Routes>
        <Route path="/control/data/devices/:deviceId" element={<DeviceDetailPage />} />
        <Route path="/control/data/devices" element={<LocationProbe />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('DeviceDetailPage', () => {
  it('renders the unknown-device surface for a deviceId not in the realm', async () => {
    detectMock.mockResolvedValue({ kind: 'active', deviceName: 'Pixel 9' })
    emit([deviceFixture('Pixel 9', 'dev-1')])
    renderAt('dev-missing')
    expect(await screen.findByRole('heading', { name: 'Unknown device' })).toBeInTheDocument()
    expect(screen.getByText('dev-missing')).toBeInTheDocument()
  })

  it('disables Revoke for the device that matches the active session', async () => {
    detectMock.mockResolvedValue({ kind: 'active', deviceName: 'Pixel 9' })
    emit([deviceFixture('Pixel 9', 'dev-1')])
    renderAt('dev-1')
    expect(await screen.findByRole('heading', { name: 'Pixel 9' })).toBeInTheDocument()
    // Two buttons named "Revoke" exist (the action card title + the
    // disabled button); both are disabled in this branch.
    const revokeButtons = screen.getAllByRole('button', { name: 'Revoke' })
    expect(revokeButtons.length).toBeGreaterThan(0)
    for (const b of revokeButtons) expect(b).toBeDisabled()
  })

  it('Revoke → confirm → success bounces back to the device list', async () => {
    const user = userEvent.setup()
    detectMock.mockResolvedValue({ kind: 'active', deviceName: 'Pixel 9' })
    emit([
      deviceFixture('Pixel 9', 'dev-1'),
      deviceFixture('MacBook Pro', 'dev-2'),
    ])
    revokeMock.mockResolvedValue({ ok: true, value: undefined })
    renderAt('dev-2')
    await user.click(await screen.findByRole('button', { name: 'Revoke device' }))
    // Dialog opens; click the confirm button inside it.
    const dialogConfirm = screen.getAllByRole('button', { name: 'Revoke device' }).at(-1)!
    await user.click(dialogConfirm)
    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent('/control/data/devices'),
    )
    expect(revokeMock).toHaveBeenCalledWith('dev-2')
  })

  it('a network failure on revoke surfaces the friendly retry copy in the dialog', async () => {
    const user = userEvent.setup()
    detectMock.mockResolvedValue({ kind: 'active', deviceName: 'Pixel 9' })
    emit([
      deviceFixture('Pixel 9', 'dev-1'),
      deviceFixture('MacBook Pro', 'dev-2'),
    ])
    revokeMock.mockResolvedValue({ ok: false, error: { kind: 'network' } })
    renderAt('dev-2')
    await user.click(await screen.findByRole('button', { name: 'Revoke device' }))
    const dialogConfirm = screen.getAllByRole('button', { name: 'Revoke device' }).at(-1)!
    await user.click(dialogConfirm)
    expect(
      await screen.findByText(/Couldn't reach the relay\./i),
    ).toBeInTheDocument()
  })
})
