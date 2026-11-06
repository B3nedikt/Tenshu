// Coverage for the provider's re-probe behaviour: besides the once-per-
// mount `Session.detect()`, it re-probes when the tab becomes visible
// again so a session change made elsewhere (lock / unlock / disconnect)
// is reflected without a hard reload. jsdom reports `visibilityState`
// as 'visible', so dispatching `visibilitychange` exercises the path.
import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { LandingCtaProvider } from './LandingCtaProvider'
import { useLandingCta } from './useLandingCta'
import { OnboardingSdkContext } from '../lib/onboardingSdkContext'
import type { OnboardingSdk } from '../lib/mockOnboarding'

function Probe() {
  const state = useLandingCta()
  const label = state.kind === 'resolved' ? state.session.kind : state.kind
  return <span data-testid="cta-state">{label}</span>
}

function renderProvider(sdk: OnboardingSdk) {
  return render(
    <OnboardingSdkContext.Provider value={sdk}>
      <LandingCtaProvider>
        <Probe />
      </LandingCtaProvider>
    </OnboardingSdkContext.Provider>,
  )
}

describe('LandingCtaProvider', () => {
  it('re-probes Session.detect when the tab becomes visible again', async () => {
    const detect = vi
      .fn()
      .mockResolvedValueOnce({ kind: 'active', deviceName: 'Test Browser' })
      .mockResolvedValueOnce({ kind: 'locked', deviceName: 'Test Browser' })
    const sdk = { Session: { detect } } as unknown as OnboardingSdk

    renderProvider(sdk)
    await waitFor(() =>
      expect(screen.getByTestId('cta-state')).toHaveTextContent('active'),
    )

    document.dispatchEvent(new Event('visibilitychange'))
    await waitFor(() =>
      expect(screen.getByTestId('cta-state')).toHaveTextContent('locked'),
    )
    expect(detect).toHaveBeenCalledTimes(2)
  })

  it('does not flash back to a loading state while re-probing', async () => {
    const detect = vi
      .fn()
      .mockResolvedValueOnce({ kind: 'active', deviceName: 'Test Browser' })
      .mockImplementationOnce(
        () =>
          new Promise(resolve =>
            // Resolve on a later tick so any intermediate render is observable.
            queueMicrotask(() =>
              resolve({ kind: 'locked', deviceName: 'Test Browser' }),
            ),
          ),
      )
    const sdk = { Session: { detect } } as unknown as OnboardingSdk

    renderProvider(sdk)
    await waitFor(() =>
      expect(screen.getByTestId('cta-state')).toHaveTextContent('active'),
    )

    document.dispatchEvent(new Event('visibilitychange'))
    // The resolved 'active' state stays put until the re-probe resolves —
    // it never reverts to 'loading'.
    expect(screen.getByTestId('cta-state')).toHaveTextContent('active')
    await waitFor(() =>
      expect(screen.getByTestId('cta-state')).toHaveTextContent('locked'),
    )
  })
})
