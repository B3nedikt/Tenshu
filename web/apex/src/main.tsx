import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import './index.css'
import './i18n'
import App from './App.tsx'
import { RoutedConstructionGate } from './components/ConstructionGate'
import { OnboardingSdkProvider } from './lib/onboardingSdk'
import { RuntimeLeaseNoticeSlot } from './components/RuntimeLeaseNoticeSlot'

// ⚠⚠ Nothing imported statically from this file may reach a `@tenshu/sdk/*` value: it would put the
// Kotlin runtime into the first chunk every visitor downloads (web-app-suite.md F6).
//
// Serve the cross-origin proxy gateway for subdomain apps when this apex IS the hidden iframe an
// app (e.g. drive.tenshu.app) dials over postMessage (docs/web/architecture.md §3.2 + §5.2). The
// gateway imports the runtime, so it loads lazily; the app re-sends its hello until the gateway
// answers. When the apex is the host page around an embedded app, `RuntimeApp` mounts it instead.
if (window.self !== window.top) {
  void import('./lib/proxyGateway').then(m => m.ensureProxyGateway())
}

const queryClient = new QueryClient()

// Pre-launch curtain (polish, not security). Active only when VITE_CONSTRUCTION_CODE
// is set at build time — unset (local dev, post-launch) renders the app directly.
// ⚠ Production DOES set it; a page a stranger must reach goes in lib/alwaysPublicPaths.ts.
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {/* The router sits outside the curtain so the curtain can read the path: a few public routes
        (lib/alwaysPublicPaths.ts — the account-deletion page) are never covered. */}
    <BrowserRouter>
      <RoutedConstructionGate code={import.meta.env.VITE_CONSTRUCTION_CODE}>
        <QueryClientProvider client={queryClient}>
          {/* Above every route: a tab without the runtime fails every SDK call, whatever page. */}
          <RuntimeLeaseNoticeSlot />
          <OnboardingSdkProvider>
            <App />
          </OnboardingSdkProvider>
        </QueryClientProvider>
      </RoutedConstructionGate>
    </BrowserRouter>
  </StrictMode>,
)
