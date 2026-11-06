// Component-level coverage for the smart CTA. The pure shape table is
// already covered in `landingCtaShape.test.ts`; this test exercises the
// React rendering layer — slot variants, the secondary affordance, and
// the loading-disabled state — without depending on the SDK probe.
//
// The provider is bypassed by injecting a synthetic `LandingCtaContext`
// value, mirroring the production wiring from `LandingCtaProvider`.
import { describe, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { LandingCta } from './LandingCta'
import { LandingCtaContext, type LandingCtaState } from './landingCtaContext'

function renderWithState(state: LandingCtaState, slot: 'hero' | 'signup' | 'header') {
  return render(
    <MemoryRouter>
      <LandingCtaContext.Provider value={state}>
        <LandingCta slot={slot} />
      </LandingCtaContext.Provider>
    </MemoryRouter>,
  )
}

describe('LandingCta', () => {
  it('hero slot renders the primary link only', () => {
    renderWithState(
      { kind: 'resolved', session: { kind: 'firstTime' } },
      'hero',
    )
    const primary = screen.getByRole('link', { name: 'Get started' })
    expect(primary).toHaveAttribute('href', '/start')
    // "Learn more" is Block1Hero's NextSectionLink, not LandingCta's;
    // the hero slot has no secondary.
    expect(screen.queryByRole('link', { name: 'Learn more' })).toBeNull()
  })

  it('signup slot renders the secondary "I already have Tenshu" link only for first-time visitors', () => {
    const { unmount } = renderWithState(
      { kind: 'resolved', session: { kind: 'firstTime' } },
      'signup',
    )
    expect(screen.getByRole('link', { name: 'I already have Tenshu' })).toHaveAttribute(
      'href',
      '/start',
    )
    unmount()

    renderWithState(
      { kind: 'resolved', session: { kind: 'active', deviceName: 'Pixel 9' } },
      'signup',
    )
    expect(screen.queryByRole('link', { name: 'I already have Tenshu' })).toBeNull()
    expect(screen.getByRole('link', { name: 'Open Tenshu' })).toHaveAttribute('href', '/control')
  })

  it('header slot renders a single compact link with no secondary affordance', () => {
    const { container } = renderWithState(
      { kind: 'resolved', session: { kind: 'locked', deviceName: 'Pixel 9' } },
      'header',
    )
    const link = within(container).getByRole('link', { name: 'Unlock to continue' })
    expect(link).toHaveAttribute('href', '/lock')
    // Only one link in the header slot; no secondary, no "Learn more".
    expect(within(container).getAllByRole('link')).toHaveLength(1)
  })

  it('renders a disabled placeholder while the session probe is loading', () => {
    renderWithState({ kind: 'loading' }, 'hero')
    // While loading we render a non-link span so the user can't navigate
    // before the probe resolves; aria-disabled signals the state.
    expect(screen.queryByRole('link', { name: 'Get started' })).toBeNull()
    const placeholder = screen.getByText('Get started')
    expect(placeholder).toHaveAttribute('aria-disabled', 'true')
  })
})
