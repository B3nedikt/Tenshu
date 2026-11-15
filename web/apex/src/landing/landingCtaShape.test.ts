// Covers every branch of the smart-CTA table in
// web-apex-landing.md §4. The visual layer (LandingCta.tsx) is a thin
// wrapper around this; if a future copy or destination change happens,
// these tests are the canonical regression check.
//
// `label` / `secondary.label` are i18n keys, not rendered text — the
// translation lookup happens in LandingCta.tsx via `t(...)`. The
// rendered-text assertion lives in `LandingCta.test.tsx`.
import { describe, expect, it } from 'vitest'
import { landingCtaShape } from './landingCtaShape'

describe('landingCtaShape', () => {
  it('shows a generic Get started CTA while the session probe is in flight', () => {
    const cta = landingCtaShape({ kind: 'loading' }, true)
    expect(cta.label).toBe('cta.getStarted')
    expect(cta.to).toBe('/start')
    expect(cta.secondary).toBeNull()
  })

  it('routes empty-IDB visitors to /start with the "I already have Tenshu" fallback', () => {
    const cta = landingCtaShape({ kind: 'resolved', session: { kind: 'firstTime' } }, true)
    expect(cta.label).toBe('cta.getStarted')
    expect(cta.to).toBe('/start')
    expect(cta.secondary).toEqual({ label: 'cta.iAlreadyHaveTenshu', to: '/start' })
  })

  it('routes returning-active visitors to /control without the secondary affordance', () => {
    const cta = landingCtaShape(
      { kind: 'resolved', session: { kind: 'active', deviceName: 'Pixel 9' } },
      true,
    )
    expect(cta.label).toBe('cta.openTenshu')
    expect(cta.to).toBe('/overview')
    expect(cta.secondary).toBeNull()
  })

  it('routes returning-locked visitors to /lock with the unlock label', () => {
    const cta = landingCtaShape(
      { kind: 'resolved', session: { kind: 'locked', deviceName: 'Pixel 9' } },
      true,
    )
    expect(cta.label).toBe('cta.welcomeBackUnlock')
    expect(cta.to).toBe('/lock')
    expect(cta.secondary).toBeNull()
  })

  // ---- pre-launch signup gate closed (`lib/signupGate.ts`) ----
  //
  // The gate's whole point is that it moves exactly two rows. A returning
  // visitor's realm already exists in this browser, so taking their way back in
  // would read as data loss, not as a pre-launch notice — these two tests are
  // what stop a future edit from widening the gate onto them.

  describe('with signup closed', () => {
    it('offers a Coming soon CTA to empty-IDB visitors, with no pairing fallback', () => {
      const cta = landingCtaShape({ kind: 'resolved', session: { kind: 'firstTime' } }, false)
      expect(cta.label).toBe('cta.comingSoon')
      // Still /start: the route renders the closed notice, so there is one gate.
      expect(cta.to).toBe('/start')
      expect(cta.secondary).toBeNull()
    })

    it('bets on Coming soon while the probe is in flight', () => {
      const cta = landingCtaShape({ kind: 'loading' }, false)
      expect(cta.label).toBe('cta.comingSoon')
      expect(cta.to).toBe('/start')
    })

    it('leaves a returning-active visitor their way into the control plane', () => {
      const cta = landingCtaShape(
        { kind: 'resolved', session: { kind: 'active', deviceName: 'Pixel 9' } },
        false,
      )
      expect(cta.label).toBe('cta.openTenshu')
      expect(cta.to).toBe('/overview')
    })

    it('leaves a returning-locked visitor their unlock route', () => {
      const cta = landingCtaShape(
        { kind: 'resolved', session: { kind: 'locked', deviceName: 'Pixel 9' } },
        false,
      )
      expect(cta.label).toBe('cta.welcomeBackUnlock')
      expect(cta.to).toBe('/lock')
    })
  })
})
