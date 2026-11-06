// Hook split out from `onboardingSdk.tsx` to keep that file
// component-only (so React Fast Refresh stays happy).
import { useContext } from 'react'
import { OnboardingSdkContext } from './onboardingSdkContext'
import type { OnboardingSdk } from './mockOnboarding'

export function useOnboardingSdk(): OnboardingSdk {
  const sdk = useContext(OnboardingSdkContext)
  if (sdk === null) {
    throw new Error('useOnboardingSdk: called outside OnboardingSdkProvider')
  }
  return sdk
}
