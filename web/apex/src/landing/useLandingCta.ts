// Hook split out from `LandingCtaProvider.tsx` so the provider file
// stays component-only (React Fast Refresh lint rule).
import { useContext } from 'react'
import { LandingCtaContext, type LandingCtaState } from './landingCtaContext'

export function useLandingCta(): LandingCtaState {
  const value = useContext(LandingCtaContext)
  if (value === null) {
    throw new Error('useLandingCta: called outside LandingCtaProvider')
  }
  return value
}
