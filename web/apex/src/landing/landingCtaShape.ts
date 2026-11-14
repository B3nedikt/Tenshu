// Pure-logic mapping from `LandingCtaState` to the CTA's label key
// and destination. Lives in its own file so it can be unit-tested
// without pulling in React Testing Library — tests in
// `landingCtaShape.test.ts` cover every branch of the table in
// web-apex-landing.md §4.
//
// `label` / `secondary.label` are i18n keys (under the `landing`
// namespace), not rendered text — the visual layer (LandingCta.tsx)
// runs them through `t(...)` at render time so the marketing copy can
// be translated without touching this table.
import type { LandingCtaState } from './landingCtaContext'
import { OVERVIEW_PATH } from '../lib/overviewPath'

export interface CtaShape {
  readonly label: string
  readonly to: string
  readonly secondary: { readonly label: string; readonly to: string } | null
}

// `signupOpen` is the pre-launch gate (`lib/signupGate.ts`). It changes only the
// two rows that lead a NEW visitor into realm creation; `active` and `locked`
// fall through untouched, because a realm already in this browser must keep its
// way back in. The destination stays `/start` in every case — the route itself
// renders the closed notice, so there is one gate, not two.
export function landingCtaShape(state: LandingCtaState, signupOpen: boolean): CtaShape {
  if (state.kind === 'loading') {
    // While the probe is in flight the visitor is unknown, so this label is a
    // bet. With signup closed the overwhelmingly likely answer is `firstTime`,
    // and betting on it keeps the common case from flashing a label it cannot
    // honour.
    return {
      label: signupOpen ? 'cta.getStarted' : 'cta.comingSoon',
      to: '/start',
      secondary: null,
    }
  }
  switch (state.session.kind) {
    case 'firstTime':
      if (!signupOpen) {
        // No secondary either: "I already have Tenshu" is the pairing door, and
        // the gate closes that one too (it is a branch of the same chooser).
        return { label: 'cta.comingSoon', to: '/start', secondary: null }
      }
      return {
        label: 'cta.getStarted',
        to: '/start',
        secondary: { label: 'cta.iAlreadyHaveTenshu', to: '/start' },
      }
    case 'active':
      return { label: 'cta.openTenshu', to: OVERVIEW_PATH, secondary: null }
    case 'locked':
      return { label: 'cta.welcomeBackUnlock', to: '/lock', secondary: null }
  }
}
