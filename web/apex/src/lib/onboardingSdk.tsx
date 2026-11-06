// Provider component for the onboarding SDK. The context, hook, and
// mock implementation live in sibling files so the lint rule that keeps
// React Fast Refresh happy (only-components in JSX files) is preserved.
//
// Default impl is the real `@tenshu/sdk/onboarding` singletons (bridged
// to Kotlin/JS via `realOnboardingSdk.ts`). The `?mock=1` URL flag (or
// passing an explicit `sdk` prop) substitutes the apex-side mock for
// dev iteration without a relay — `MockDevtools` and the sessionStorage
// scenario flags still work in mock mode.
import type { ReactNode } from 'react'
import type { OnboardingSdk } from './mockOnboarding'
import { OnboardingSdkContext } from './onboardingSdkContext'
import { RealOnboardingSdk, lazyOnboardingSdk } from './realOnboardingSdk'

// ⚠ The mock is loaded lazily too: it decodes mnemonics with the Kotlin `Bip39`, so a static
// import would put the runtime back into the first chunk (web-app-suite.md F6).
const LazyMockOnboardingSdk = lazyOnboardingSdk(() =>
  import('./mockOnboarding').then(m => m.MockOnboardingSdk),
)

function defaultSdk(): OnboardingSdk {
  if (typeof window !== 'undefined') {
    const flag = new URLSearchParams(window.location.search).get('mock')
    if (flag === '1' || flag === 'true') return LazyMockOnboardingSdk
  }
  return RealOnboardingSdk
}

export function OnboardingSdkProvider({
  children,
  sdk,
}: {
  readonly children: ReactNode
  readonly sdk?: OnboardingSdk
}) {
  return (
    <OnboardingSdkContext.Provider value={sdk ?? defaultSdk()}>
      {children}
    </OnboardingSdkContext.Provider>
  )
}
