// `DevicesListPage` reads three module-level surfaces:
//   - `Devices.observe(cb) → unsubscribe` from `@tenshu/sdk/identity`
//   - `Session.detect()`               from `@tenshu/sdk/onboarding`
//   - `detectBrowserFamily()`          from `../../lib/browserDetect`
//
// We mock all three so the page can be exercised without a live SDK
// runtime or a real userAgent. The `formatFingerprint` helper is left
// alone — it has its own pure-logic coverage in `format.test.ts` (when
// added) and the integration here is just "is the formatted string
// rendered against the correct device row?"
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import type { Device, DevicesApi } from '@tenshu/sdk/identity'
import type { SessionState } from '@tenshu/sdk/onboarding'
import type { BrowserFamily } from '../../lib/browserDetect'

const observeMock = vi.fn<DevicesApi['observe']>()
const detectMock = vi.fn<() => Promise<SessionState>>()
const detectBrowserFamilyMock = vi.fn<() => BrowserFamily>(() => 'safari')

vi.mock('@tenshu/sdk/identity', () => ({
  Devices: {
    observe: (cb: (devices: readonly Device[]) => void) => observeMock(cb),
    revoke: vi.fn(),
  },
}))

vi.mock('@tenshu/sdk/onboarding', () => ({
  Session: {
    detect: () => detectMock(),
  },
}))

vi.mock('../../lib/browserDetect', async () => {
  const actual = await vi.importActual<typeof import('../../lib/browserDetect')>(
    '../../lib/browserDetect',
  )
  return {
    ...actual,
    detectBrowserFamily: () => detectBrowserFamilyMock(),
  }
})

import { DevicesListPage } from './DevicesListPage'

function deviceFixture(name: string, deviceId: string, byte = 0x1a): Device {
  // 32-byte Ed25519 public key; only the first 8 bytes drive
  // `formatFingerprint`, so we fill them with a single value to make
  // the rendered fingerprint predictable.
  const publicKey = new Int8Array(32)
  for (let i = 0; i < 8; i++) publicKey[i] = byte
  return {
    deviceId,
    name,
    publicKey,
    isCurrent: false,
    isAuthorized: true,
  }
}

function renderPage() {
  return render(
    <MemoryRouter>
      <DevicesListPage />
    </MemoryRouter>,
  )
}

describe('DevicesListPage', () => {
  it('renders the loading skeleton until Devices.observe fires its first emission', () => {
    detectMock.mockResolvedValue({ kind: 'active', deviceName: 'Pixel 9' })
    observeMock.mockImplementation(() => () => {}) // no immediate emission
    renderPage()
    expect(screen.getByText('Loading…')).toBeInTheDocument()
  })

  it('shows the empty state when the realm reports zero devices', async () => {
    detectMock.mockResolvedValue({ kind: 'active', deviceName: 'Pixel 9' })
    observeMock.mockImplementation(cb => {
      cb([])
      return () => {}
    })
    renderPage()
    expect(
      await screen.findByText(/No devices yet\./i),
    ).toBeInTheDocument()
  })

  it('lists each device with a fingerprint + marks the matching one as "This device"', async () => {
    detectMock.mockResolvedValue({ kind: 'active', deviceName: 'Pixel 9' })
    observeMock.mockImplementation(cb => {
      cb([
        deviceFixture('Pixel 9', 'dev-1', 0xab),
        deviceFixture('MacBook Pro', 'dev-2', 0xcd),
      ])
      return () => {}
    })
    renderPage()
    expect(await screen.findByText('Pixel 9')).toBeInTheDocument()
    expect(screen.getByText('MacBook Pro')).toBeInTheDocument()
    // Count summary at the top.
    expect(screen.getByText('2 devices')).toBeInTheDocument()
    // `Pixel 9` matches the active session's deviceName uniquely → the
    // badge appears exactly once.
    expect(screen.getAllByText('This device')).toHaveLength(1)
    // Fingerprint formatted from the first 8 bytes of publicKey.
    expect(screen.getByText('abab:abab:abab:abab')).toBeInTheDocument()
    expect(screen.getByText('cdcd:cdcd:cdcd:cdcd')).toBeInTheDocument()
  })

  it('shows the browser-sync warning for Chrome but not for Safari', async () => {
    detectMock.mockResolvedValue({ kind: 'active', deviceName: 'Pixel 9' })
    observeMock.mockImplementation(cb => {
      cb([deviceFixture('Pixel 9', 'dev-1')])
      return () => {}
    })

    // Safari → no warning.
    detectBrowserFamilyMock.mockReturnValue('safari')
    const { unmount } = renderPage()
    await screen.findByText('Pixel 9')
    expect(screen.queryByText(/shares its identity with your/)).toBeNull()
    unmount()

    // Chrome → warning rendered with the Chrome-specific copy.
    detectBrowserFamilyMock.mockReturnValue('chrome')
    renderPage()
    expect(
      await screen.findByText(/shares its identity with your Chrome sync account/),
    ).toBeInTheDocument()
  })
})
