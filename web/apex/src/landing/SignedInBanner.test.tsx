// Coverage for the signed-in landing banner's Lock affordance (added
// with the web session-exit work). The banner reads session state from
// `LandingCtaContext` and, on Lock, calls `Session.clear({})` directly
// from the SDK before routing to /lock. `Session` is mocked at the
// module boundary; navigation is asserted with a location probe,
// mirroring `LockedFlow.test.tsx`.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'

const clearMock = vi.fn<(opts: unknown) => Promise<void>>(async () => undefined)

vi.mock('@tenshu/sdk/onboarding', () => ({
  Session: { clear: (opts: unknown) => clearMock(opts) },
}))

// The click starts the runtime (`ensureRuntime`); stubbed so no Kotlin bundle is needed.
const ensureRuntimeMock = vi.fn()
vi.mock('../lib/runtime', () => ({ ensureRuntime: () => ensureRuntimeMock() }))

import { SignedInBanner } from './SignedInBanner'
import { LandingCtaContext, type LandingCtaState } from './landingCtaContext'

function LocationProbe() {
  const loc = useLocation()
  return <span data-testid="location">{loc.pathname}</span>
}

function renderBanner(state: LandingCtaState) {
  return render(
    <LandingCtaContext.Provider value={state}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<SignedInBanner />} />
          <Route path="/lock" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>
    </LandingCtaContext.Provider>,
  )
}

const activeState: LandingCtaState = {
  kind: 'resolved',
  session: { kind: 'active', deviceName: 'Test Browser' },
}

describe('SignedInBanner', () => {
  beforeEach(() => {
    clearMock.mockClear()
    window.sessionStorage.clear()
  })

  it('renders the signed-in row with a Lock action when the session is active', () => {
    renderBanner(activeState)
    expect(screen.getByText(/You're signed in as/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /lock this device/i })).toBeInTheDocument()
  })

  it('locks and routes to /lock when Lock is clicked', async () => {
    const user = userEvent.setup()
    renderBanner(activeState)
    await user.click(screen.getByRole('button', { name: /lock this device/i }))
    expect(clearMock).toHaveBeenCalledWith({})
    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent('/lock'),
    )
  })

  it('starts the runtime on the click, and stays put when another tab holds it', async () => {
    clearMock.mockRejectedValueOnce(new Error('tenshu/runtime-elsewhere'))
    renderBanner(activeState)

    await userEvent.click(screen.getByRole('button', { name: /lock/i }))

    await waitFor(() => expect(clearMock).toHaveBeenCalled())
    expect(ensureRuntimeMock).toHaveBeenCalled()
    // No navigation: RuntimeLeaseNotice carries the answer ("Use here"), and /lock cannot be served.
    expect(screen.queryByTestId('location')).toBeNull()
  })

  it('renders nothing when the session is locked (not active)', () => {
    const { container } = renderBanner({
      kind: 'resolved',
      session: { kind: 'locked', deviceName: 'Test Browser' },
    })
    expect(container).toBeEmptyDOMElement()
  })
})
