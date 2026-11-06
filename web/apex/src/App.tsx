import { Route, Routes } from 'react-router'
import { Suspense, lazy } from 'react'
import { LandingPage } from './landing/LandingPage'
import { LocalizedLanding } from './landing/LocalizedLanding'
import { VisionPage } from './landing/VisionPage'
import { AccountDeletionPage } from './landing/AccountDeletionPage'
import { SITE_LANGUAGES, landingPath } from './landing/languages'

// ⚠⚠ The PUBLIC half of the apex. Nothing imported here may reach a `@tenshu/sdk/*` value: that
// pulls the 3.9 MB Kotlin runtime into the first chunk every visitor downloads, landing page
// included (web-app-suite.md F6). Everything that needs the runtime lives in `RuntimeApp.tsx`,
// loaded on demand by the catch-all route below.
const RuntimeApp = lazy(() => import('./RuntimeApp'))

// Dev-only tools. ⚠ Behind `import.meta.env.DEV` AND a dynamic import: MockDevtools reaches the
// mock onboarding SDK, which imports the Kotlin `Bip39`. A static import kept the whole runtime in
// the production first chunk even though the component renders nothing there.
const DevTools = import.meta.env.DEV
  ? lazy(() =>
      Promise.all([import('./onboarding/MockDevtools'), import('./dev/DevResetButton')]).then(
        ([{ MockDevtools }, { DevResetButton }]) => ({
          default: () => (
            <>
              <MockDevtools />
              <DevResetButton />
            </>
          ),
        }),
      ),
    )
  : null

export default function App() {
  return (
    <>
      <Routes>
        <Route index element={<LandingPage />} />
        {/* The landing page in one language (`/de`), the footer switch's targets. Explicit
            paths, not `:lang`, so an unknown first segment still falls through to the catch-all. */}
        {SITE_LANGUAGES.map(({ lang }) => (
          <Route key={lang} path={landingPath(lang).slice(1)} element={<LocalizedLanding lang={lang} />} />
        ))}
        <Route path="vision" element={<VisionPage />} />
        {/* ⚠ A stable public URL (Google Play's account-deletion requirement): reachable without the
            app, the runtime or a sign-in, and never behind the curtain (lib/alwaysPublicPaths.ts). */}
        <Route path="account/delete" element={<AccountDeletionPage />} />
        {/* Every other path: the runtime half, which owns its own unknown-path redirect. */}
        <Route
          path="*"
          element={
            <Suspense fallback={null}>
              <RuntimeApp />
            </Suspense>
          }
        />
      </Routes>
      {DevTools && (
        <Suspense fallback={null}>
          <DevTools />
        </Suspense>
      )}
    </>
  )
}
