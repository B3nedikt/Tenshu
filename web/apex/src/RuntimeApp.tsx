// Everything in the apex that needs the Kotlin runtime, as ONE lazily loaded chunk
// (web-app-suite.md F6). `App.tsx` renders this behind its catch-all route, so the public pages
// (landing, localized landings, vision) never download the runtime.
//
// ⚠⚠ A route that reads or writes the realm belongs HERE, inside a RequiresRuntime. A public page
// belongs in `App.tsx` and must not import `@tenshu/sdk/*` values (`RuntimeLaziness.test.tsx`
// checks the start; `BundleSplit.test.ts` checks the import graph).
import { Fragment, useEffect } from 'react'
import { Navigate, Route, Routes } from 'react-router'
import { HostedAppEmbed } from './apps/HostedAppEmbed'
import { HOSTED_APPS } from './apps/registry'
import { RequiresRuntime } from './components/RequiresRuntime'
import DevPage from './dev/DevPage'
import { MarketingLayout } from './landing/MarketingLayout'
import { OnboardingRouter } from './onboarding/OnboardingRouter'
import { OnboardingFlow } from './onboarding/OnboardingFlow'
import { SignupClosedNotice } from './onboarding/SignupClosedNotice'
import { LockedFlow } from './onboarding/LockedFlow'
import { RecoveryFlow } from './onboarding/RecoveryFlow'
import { ControlShell } from './control/ControlShell'
import { AppShell } from './shell/AppShell'
import { SessionLayout } from './shell/SessionLayout'
import { realShownApps } from './overview/realShownApps'
import { GlanceGatewayContext } from './overview/glance/glanceGateway'
import { callInProcess } from './lib/inProcessGateway'
import { StatusContext } from './shell/statusContext'
import { realStatus } from './shell/realStatus'
import { OverviewPage } from './overview/OverviewPage'
import { CUSTOMIZE_PATH, OVERVIEW_PATH, YOUR_APPS_PATH } from './lib/overviewPath'
import { SharesPage } from './control/shares/SharesPage'
import { BackgroundIngestPage } from './control/ingest/BackgroundIngestPage'
import ShareViewerRoute from './routes/share/[shareId]'
import ContactRedeemRoute from './routes/invite/redeem'
import { HomePage } from './control/HomePage'
import { RecoveryPage } from './control/recovery/RecoveryPage'
import { SettingsPage } from './control/settings/SettingsPage'
import { AccountRecoverySetupPage } from './control/recovery/AccountRecoverySetupPage'
import { AppsListPage } from './control/apps/AppsListPage'
import { DataPage } from './control/data/DataPage'
import { FilesInventoryPage } from './control/data/files/FilesInventoryPage'
import { AppDetailPage } from './control/apps/AppDetailPage'
import { AppRegistryProvider } from './control/apps/AppRegistryProvider'
import { realAppRegistry } from './control/apps/realAppRegistry'
import { DevicesListPage } from './control/devices/DevicesListPage'
import { DeviceDetailPage } from './control/devices/DeviceDetailPage'
import { AccountPickerPage } from './control/services/AccountPickerPage'
import { DisconnectConfirmPage } from './control/services/DisconnectConfirmPage'
import { FreeAccountSignupPage } from './control/services/FreeAccountSignupPage'
import { CheckoutPage } from './control/services/CheckoutPage'
import { CheckoutResultPage } from './control/services/CheckoutResultPage'
import { RedeemPage } from './control/services/RedeemPage'
import { PublisherClientProvider } from './control/services/PublisherClientProvider'
import { realPublisherClient } from './control/services/realPublisherClient'
import { StorageStatusProvider } from './control/services/StorageStatusProvider'
import { readStorageStatus } from './control/services/realStorageStatus'
import { ServicesPage } from './control/services/ServicesPage'
import { signupOpen } from './lib/signupGate'
import { ensureProxyGateway } from './lib/proxyGateway'

export default function RuntimeApp() {
  // Host case: an app embedded in this page (HostedAppEmbed) dials its parent, the apex. It re-sends
  // its hello every second until answered, so mounting from an effect is in time.
  useEffect(() => {
    ensureProxyGateway()
  }, [])
  return (
    <PublisherClientProvider client={realPublisherClient}>
      <StorageStatusProvider read={readStorageStatus}>
      {/* Hosted apps + the realm's AppRegistration rows; reads nothing until a signed-in page listens. */}
      <AppRegistryProvider registry={realAppRegistry}>
      {/* The overview cards' gateway (web-apex-home.md §3.6) — unscoped; `scopedCall` narrows it per app. */}
      <GlanceGatewayContext.Provider value={callInProcess}>
      {/* The bell and sync pill's source (web-apex-home.md §3.4); nothing subscribes until they mount. */}
      <StatusContext.Provider value={realStatus}>
      <Routes>
        {/* ⚠⚠ Runtime routes are wrapped in RequiresRuntime; public ones are NOT. Starting the
            runtime takes the one-per-origin `tenshu-runtime` lock, so a landing / marketing /
            share-viewer page that started it would lock every other tab out just by being open
            (docs/web/architecture.md "Cross-tab"). A new route that reads or writes the realm
            goes inside a RequiresRuntime; a public one stays outside. */}
        <Route path="start" element={<RequiresRuntime><OnboardingRouter /></RequiresRuntime>} />
        {/* Pre-launch signup gate (`lib/signupGate.ts`). Gating the ROUTE, not the
            CTA, is what makes this hold: "Get started" renders in three slots and
            `/start` is deep-linkable, so hiding buttons would leave the door open.
            Only this route is closed — `/lock`, `/recovery` and `/control/*` stay
            live so a realm already in this browser is never stranded. Closed, it is a
            public page and wears the site's chrome (MarketingLayout); open, it is a
            flow holding key material and keeps OnboardingShell. */}
        <Route
          path="onboarding"
          element={
            signupOpen() ? (
              <RequiresRuntime><OnboardingFlow /></RequiresRuntime>
            ) : (
              <MarketingLayout>
                <main className="mx-auto flex w-full max-w-2xl flex-col px-6 py-12">
                  <SignupClosedNotice />
                </main>
              </MarketingLayout>
            )
          }
        />
        <Route path="lock" element={<RequiresRuntime><LockedFlow /></RequiresRuntime>} />
        <Route path="recovery" element={<RequiresRuntime><RecoveryFlow /></RequiresRuntime>} />
        {/* Mode-A share viewer — anonymous, no session/ControlShell.
            `/s/:shareId#k=<K_share>` (drive-file-sharing.md §3.2). */}
        <Route path="s/:shareId" element={<MarketingLayout><ShareViewerRoute /></MarketingLayout>} />
        {/* Contact-invite redeem-on-open — session-gated, MB-1.
            `/i#v1.<base64url(inviteId ‖ K_link ‖ a13Pub)>` (drive-web-mode-b-sharing.md §3). */}
        <Route path="i" element={<RequiresRuntime><MarketingLayout><ContactRedeemRoute /></MarketingLayout></RequiresRuntime>} />
        {/* Signed-in routes: one session guard (SessionLayout), two shells (web-apex-home.md §3.2) —
            the overview and the hosted apps under AppShell, Control under ControlShell. */}
        <Route element={<RequiresRuntime><SessionLayout shownApps={realShownApps} /></RequiresRuntime>}>
          <Route element={<AppShell />}>
            {/* ⚠⚠ `/overview`, not `/home`: `/home` is the Home APP's route (lib/overviewPath.ts). */}
            <Route path={OVERVIEW_PATH.slice(1)} element={<OverviewPage />} />
            <Route path={YOUR_APPS_PATH.slice(1)} element={<OverviewPage editingApps />} />
            <Route path={CUSTOMIZE_PATH.slice(1)} element={<OverviewPage customizing />} />
            {/* Every hosted app (Drive, Contacts, …) is its own app on its own origin — apex frames it
                inside this chrome (HostedAppEmbed), at the route its registry entry names
                (`apps/registry/apps/<id>.ts`), plus that entry's back-compat redirects. The embedded
                app owns its own in-app navigation, so each route ends in `/*`. */}
            {HOSTED_APPS.map(app => (
              <Fragment key={app.id}>
                {app.redirects?.map(path => (
                  <Route key={path} path={path} element={<Navigate to={app.manifest.launchPath} replace />} />
                ))}
                <Route path={app.routePath} element={<HostedAppEmbed app={app} />} />
              </Fragment>
            ))}
          </Route>
          <Route element={<ControlShell />}>
            <Route path="control" element={<Navigate to="/control/home" replace />} />
            <Route path="control/home" element={<HomePage />} />
            <Route path="control/apps" element={<AppsListPage />} />
            <Route path="control/apps/:appId" element={<AppDetailPage />} />
            <Route path="control/data" element={<DataPage />} />
            <Route path="control/data/files" element={<FilesInventoryPage />} />
            <Route path="control/data/devices" element={<DevicesListPage />} />
            <Route path="control/data/devices/:deviceId" element={<DeviceDetailPage />} />
            <Route path="control/data/shares" element={<SharesPage />} />
            <Route path="control/data/background-ingest" element={<BackgroundIngestPage />} />
            <Route path="control/services" element={<ServicesPage />} />
            <Route path="control/services/picker" element={<AccountPickerPage />} />
            <Route path="control/services/signup" element={<FreeAccountSignupPage />} />
            <Route path="control/services/checkout/:productId" element={<CheckoutPage />} />
            {/* Where Stripe returns a browser customer. The service derives this
                URL from WEB_APP_ORIGIN; it is not request-supplied. */}
            <Route path="control/services/checkout-result" element={<CheckoutResultPage />} />
            <Route path="control/services/redeem" element={<RedeemPage />} />
            <Route path="control/services/disconnect" element={<DisconnectConfirmPage />} />
            <Route path="control/settings" element={<SettingsPage />} />
            <Route path="control/settings/recovery" element={<RecoveryPage />} />
            <Route path="control/settings/recovery/setup" element={<AccountRecoverySetupPage />} />
            <Route path="dev" element={<DevPage />} />
          </Route>
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
      </StatusContext.Provider>
      </GlanceGatewayContext.Provider>
      </AppRegistryProvider>
      </StorageStatusProvider>
    </PublisherClientProvider>
  )
}
