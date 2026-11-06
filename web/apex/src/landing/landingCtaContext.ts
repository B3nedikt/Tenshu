// Shared smart-CTA state for the landing page. The provider in
// `LandingCtaProvider.tsx` probes `Session.detect()` exactly once per
// landing mount (web-apex-landing.md §4) and exposes the result here so
// Block 1, Block 8, the marketing header, and the signed-in banner all
// render consistent labels/destinations without re-probing.
import { createContext } from 'react'
import type { SessionState } from '@tenshu/sdk/onboarding'

export type LandingCtaState =
  | { readonly kind: 'loading' }
  | { readonly kind: 'resolved'; readonly session: SessionState }

export const LandingCtaContext = createContext<LandingCtaState | null>(null)

/**
 * The CTA as a visitor nobody knows: a page that does not probe this browser's realm — a static
 * support page cannot, MarketingLayout's pages need not — says what the landing page says to a
 * first-time visitor ("Get started", or "Coming soon" while signup is closed) and links to
 * /start, which sorts out the rest.
 */
export const LOGGED_OUT_CTA: LandingCtaState = { kind: 'resolved', session: { kind: 'firstTime' } }
