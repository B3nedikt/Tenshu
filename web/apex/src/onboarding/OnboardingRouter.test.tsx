// Verifies the three landing-state branches in `OnboardingRouter`:
// `firstTime` → /onboarding, `locked` → /lock, `active` → /control/home.
// Also covers the pre-resolve BootSplash. Routing is asserted via a
// catch-all probe route inside `MemoryRouter` rather than spying on the
// router internals — the assertion is "the URL is what we expect."
import { describe, expect, it } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import type { SessionState } from '@tenshu/sdk/onboarding'
import { OnboardingRouter } from './OnboardingRouter'
import { OnboardingSdkContext } from '../lib/onboardingSdkContext'
import type { OnboardingSdk } from '../lib/mockOnboarding'

// Minimal stub of the OnboardingSdk surface used by the router. Only
// `Session.detect()` is consulted; the rest of the SDK is unused but the
// type still demands the shape — `as unknown as OnboardingSdk` keeps the
// stub local.
function stubSdk(state: SessionState | Promise<SessionState>): OnboardingSdk {
  return {
    Session: {
      detect: () => Promise.resolve(state),
    },
  } as unknown as OnboardingSdk
}

function LocationProbe() {
  const location = useLocation()
  return <span data-testid="location">{location.pathname}</span>
}

function renderRouter(sdk: OnboardingSdk) {
  return render(
    <OnboardingSdkContext.Provider value={sdk}>
      <MemoryRouter initialEntries={['/start']}>
        <Routes>
          <Route path="/start" element={<OnboardingRouter />} />
          <Route path="*" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>
    </OnboardingSdkContext.Provider>,
  )
}

describe('OnboardingRouter', () => {
  it('shows the BootSplash before Session.detect resolves', () => {
    // A never-resolving promise keeps the router in its loading state so
    // we can observe the splash without racing the post-resolve redirect.
    const pending = new Promise<SessionState>(() => {})
    renderRouter(stubSdk(pending))
    expect(screen.getByText('Loading Tenshu…')).toBeInTheDocument()
  })

  it('redirects firstTime sessions to /onboarding', async () => {
    renderRouter(stubSdk({ kind: 'firstTime' }))
    await waitFor(() => {
      expect(screen.getByTestId('location')).toHaveTextContent('/onboarding')
    })
  })

  it('redirects locked sessions to /lock', async () => {
    renderRouter(stubSdk({ kind: 'locked', deviceName: 'Pixel 9' }))
    await waitFor(() => {
      expect(screen.getByTestId('location')).toHaveTextContent('/lock')
    })
  })

  it('redirects active sessions to /control/home', async () => {
    renderRouter(stubSdk({ kind: 'active', deviceName: 'Pixel 9' }))
    await waitFor(() => {
      expect(screen.getByTestId('location')).toHaveTextContent('/control/home')
    })
  })
})
