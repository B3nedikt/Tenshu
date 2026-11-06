// Pure-context module so the provider component file stays "components
// only" and the hook stays "no JSX." Splitting these three pieces is
// what keeps `react-refresh/only-export-components` quiet.
import { createContext } from 'react'
import type { OnboardingSdk } from './mockOnboarding'

export const OnboardingSdkContext = createContext<OnboardingSdk | null>(null)
